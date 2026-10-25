import { Firestore, Timestamp } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { verifiedEmulatorIdentity } from "./invitations.js";
import { requireProvisioningEmulators } from "./provisioning.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { requireCommandGeneration } from "./command-identity.js";
import { commandId, stableHash } from "./hashing.js";

type Data = Record<string, any>;
const fail = (reason="note-unavailable"): never => { throw new Error(reason); };
export const noteRevision = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number)>=1 && (v as number)<=2147483646;
const exact = (v: unknown, fields: string[]): v is Data => !!v && typeof v==="object" && !Array.isArray(v) && Object.keys(v).sort().join() === [...fields].sort().join();
export const NOTE_AUTHORITY_FIELDS=["authorMembershipRevision","subjectMembershipRevision","authorLifecycleRevision","subjectLifecycleRevision"];
export function validNoteBody(v: unknown): v is string {
  if(typeof v!=="string" || !v.trim() || v.includes("\r") || Buffer.byteLength(v,"utf8")>8000 || [...v].length>2000) return false;
  for(const c of v) { const n=c.codePointAt(0)!; if(n>=0xd800 && n<=0xdfff || n<32 && n!==9 && n!==10 || n>=127 && n<=159) return false; }
  return true;
}
export function validNoteSave(v: unknown): v is Data {
  if(!exact(v,["clientUid","body","bindingNonce","expectedRevision",...NOTE_AUTHORITY_FIELDS]) || !validNoteBody(v.body) ||
    !/^[a-f0-9]{64}$/.test(v.bindingNonce) || typeof v.bindingNonce!=="string" ||
    !Number.isSafeInteger(v.expectedRevision) || v.expectedRevision<0 || v.expectedRevision>=2147483646 || !NOTE_AUTHORITY_FIELDS.every(k=>noteRevision(v[k]))) return false;
  try { participantUid(v.clientUid,"clientUid"); return true; } catch { return false; }
}
export function noteDecisionValid(v: unknown, wid: string, p: Data): boolean {
  return exact(v,["status","workspaceId","clientUid","noteRevision"]) && v.workspaceId===wid && v.clientUid===p.clientUid && noteRevision(v.noteRevision) &&
    (v.status==="note-saved" ? v.noteRevision===p.expectedRevision+1 : v.status==="note-conflict" && v.noteRevision>p.expectedRevision);
}
function membership(v: Data|undefined,wid:string,uid:string,role:string) {
  if(!v || v.schemaVersion!==1 || v.workspaceId!==wid || v.userId!==uid || v.role!==role || v.status!=="active" || v.catalogContributionActive!==true || !noteRevision(v.revision)) fail("unauthorized");
}
const stamp=(v: unknown): v is Timestamp => v instanceof Timestamp && v.toMillis()>=0 && v.toMillis()<=8_640_000_000_000_000;
function account(v: Data|undefined) { if(!v || v.schemaVersion!==1 || v.accountStatus!=="active" || !noteRevision(v.lifecycleRevision)) fail("unauthorized"); }
function validateNote(v: Data,wid:string,client:string,author:string,authority:Data) {
  if(!exact(v,["schemaVersion","policyVersion","workspaceId","subjectClientId","authorTrainerId",...NOTE_AUTHORITY_FIELDS,"body","revision","createdAt","updatedAt"]) ||
    v.schemaVersion!==1 || v.policyVersion!==1 || v.workspaceId!==wid || v.subjectClientId!==client || v.authorTrainerId!==author ||
    !NOTE_AUTHORITY_FIELDS.every(k=>v[k]===authority[k]) || !validNoteBody(v.body) || !noteRevision(v.revision) || !stamp(v.createdAt) || !stamp(v.updatedAt) || v.updatedAt.toMillis()<v.createdAt.toMillis()) fail("unauthorized");
}
/** Fixed-slot, synthetic demo mediator. It never logs inputs or returns raw failures. */
export async function trainerNoteOperation(db:Firestore,caller:string|undefined,raw:unknown,clock:()=>number=Date.now,beforeWrites?:()=>Promise<void>) {
  requireProvisioningEmulators(); if(!caller) fail("unauthorized"); const uid=participantUid(caller,"uid");
  const user=await verifiedEmulatorIdentity(uid);
  await db.runTransaction(async tx=>{
    const ref=db.doc(`trainerNoteRequests/${stableHash(uid)}`), s=await tx.get(ref), minute=Math.floor(clock()/60000), count=s.get("minute")===minute?s.get("count"):0;
    if(!Number.isSafeInteger(count) || count<0 || count>=120) fail("rate-limited");
    tx.set(ref,{minute,count:count+1});
  },{maxAttempts:3});
  const read=(raw as Data)?.action==="note-get";
  if(!exact(raw,read?["action","workspaceId","clientUid"]:["action","workspaceId","idempotencyKey","noteSave"]) ||
    !read && raw.action!=="save-trainer-note" || Buffer.byteLength(JSON.stringify({data:raw}),"utf8")>32768) fail();
  const d=raw as Data, wid=participantUid(strictPathIdentifier(d.workspaceId,"workspaceId"),"workspaceId");
  if(!read && (!validNoteSave(d.noteSave) || JSON.stringify({protocolVersion:1,descriptor:d}).length>8192)) fail();
  const client=participantUid(read?d.clientUid:d.noteSave.clientUid,"clientUid"); if(client===uid) fail("unauthorized");
  const target=await getAuth().getUser(client).catch(()=>fail("unauthorized")); if(target.disabled) fail("unauthorized");
  const key=read?undefined:participantUid(strictPathIdentifier(d.idempotencyKey,"key"),"key");
  const hash=key?stableHash({...d,callerUid:uid,policyVersion:1}):undefined, started=clock();
  return db.runTransaction(async tx=>{
    const deadline=()=>{if(clock()-started>=5000)fail();}; deadline();
    if(key) await requireCommandGeneration(tx,db,key);
    const refs=[`users/${uid}`,`workspaces/${wid}`,`workspaces/${wid}/memberships/${uid}`,`workspaces/${wid}/trainerProfiles/${uid}`,`users/${uid}/membershipRefs/${wid}`,
      `users/${client}`,`workspaces/${wid}/memberships/${client}`,`workspaces/${wid}/clientProfiles/${client}`,`users/${client}/membershipRefs/${wid}`,`workspaces/${wid}/privateTrainerNotes/${client}`].map(p=>db.doc(p));
    if(key) refs.push(db.doc(`lifecycleCommands/${commandId(uid,key)}`));
    const docs=await tx.getAll(...refs);const [a,w,m,t,dir,c,cm,cp,cd,n,r]=docs;
    account(a!.data());account(c!.data());
    if(a!.get("emailNormalized")!==user.email!.toLowerCase() || w!.get("schemaVersion")!==1 || w!.get("status")!=="active" || !noteRevision(w!.get("membershipRevision"))) fail("unauthorized");
    membership(m!.data(),wid,uid,"trainer");membership(cm!.data(),wid,client,"client");
    if(t!.get("schemaVersion")!==1 || t!.get("workspaceId")!==wid || t!.get("userId")!==uid || [dir,cd].some(s=>s!.get("schemaVersion")!==1 || s!.get("status")!=="active")) fail("unauthorized");
    const profile=cp!.data();
    if(!profile || Object.keys(profile).some(k=>!["schemaVersion","workspaceId","userId","trainerId","createdAt","onboardingStatus","onboardingRevision","onboardingPolicyVersion","completedAt"].includes(k)) ||
      profile.schemaVersion!==1 || profile.workspaceId!==wid || profile.userId!==client || profile.trainerId!==uid || !stamp(profile.createdAt) || !["pending","complete"].includes(profile.onboardingStatus) ||
      (profile.onboardingStatus==="complete" ? !noteRevision(profile.onboardingRevision) || profile.onboardingPolicyVersion!==1 || !stamp(profile.completedAt) :
      profile.onboardingRevision!==undefined && profile.onboardingRevision!==0 || profile.completedAt!==undefined || profile.onboardingPolicyVersion!==undefined)) fail("unauthorized");
    const authority:Data={authorMembershipRevision:m!.get("revision"),subjectMembershipRevision:cm!.get("revision"),authorLifecycleRevision:a!.get("lifecycleRevision"),subjectLifecycleRevision:c!.get("lifecycleRevision")};
    const note=n!.data();if(note)validateNote(note,wid,client,uid,authority);
    deadline();
    if(read) return {status:"note",workspaceId:wid,clientUid:client,note:note?{body:note.body,revision:note.revision,createdAtMillis:note.createdAt.toMillis(),updatedAtMillis:note.updatedAt.toMillis()}:null,authority};
    const p=d.noteSave;
    if(!NOTE_AUTHORITY_FIELDS.every(k=>p[k]===authority[k])) fail("unauthorized");
    if(r!.exists) {
      const receipt=r!.data()!;
      if(!exact(receipt,["schemaVersion","commandKind","callerUid","workspaceId","clientUid","requestHash","result","committedAt"]) || receipt.schemaVersion!==1 || receipt.commandKind!=="trainer-note-save" ||
        receipt.callerUid!==uid || receipt.workspaceId!==wid || receipt.clientUid!==client || receipt.requestHash!==hash || !stamp(receipt.committedAt) || !noteDecisionValid(receipt.result,wid,p)) fail("idempotency-key-reused");
      return {...receipt.result,replayed:true};
    }
    const revision=note?.revision??0;
    if(revision<p.expectedRevision || !note && p.expectedRevision!==0) fail();
    const conflict=revision!==p.expectedRevision;
    const result={status:conflict?"note-conflict":"note-saved",workspaceId:wid,clientUid:client,noteRevision:conflict?revision:revision+1};
    const now=Timestamp.now();const next={schemaVersion:1,policyVersion:1,workspaceId:wid,subjectClientId:client,authorTrainerId:uid,...authority,body:p.body,revision:result.noteRevision,createdAt:note?.createdAt??now,updatedAt:now};
    if(!conflict && Buffer.byteLength(JSON.stringify(next),"utf8")>16384) fail();
    if(beforeWrites) await beforeWrites(); deadline();
    if(!conflict) tx.set(n!.ref,next);
    tx.create(r!.ref,{schemaVersion:1,commandKind:"trainer-note-save",callerUid:uid,workspaceId:wid,clientUid:client,requestHash:hash,result,committedAt:now});
    return {...result,replayed:false};
  },{maxAttempts:3});
}
