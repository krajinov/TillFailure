import { FieldPath, Firestore, Timestamp } from 'firebase-admin/firestore';
import { verifiedEmulatorIdentity } from './invitations.js';
import { requireProvisioningEmulators } from './provisioning.js';
import { participantUid, strictPathIdentifier } from './input-validation.js';
import { requireCommandGeneration } from './command-identity.js';
import { commandId, stableHash } from './hashing.js';
import { exerciseExact as exact, exerciseId, exerciseRevision, exerciseBytes as bytes, EXERCISE_AUTHORITY, exerciseDescription, exerciseText } from './custom-exercise-contract.js';
import { validDraftSave, draftDescriptor } from './program-draft-contract.js';
type Data=Record<string,any>;
const fail=(s='draft-unavailable'):never=>{throw new Error(s);};
const stamp=(v:unknown):v is Timestamp=>v instanceof Timestamp && Number.isSafeInteger(v.toMillis()) && v.toMillis()>=0 && v.toMillis()<=8640000000000000;
export function draftDecisionValid(v:unknown,wid:string,p:Data):v is Data {
  return exact(v,['status','workspaceId','templateId','templateRevision']) && v.workspaceId===wid && v.templateId===p.templateId && Number.isInteger(v.templateRevision) && v.templateRevision>=0 && v.templateRevision<=2147483646 &&
    (v.status==='draft-saved'?v.templateRevision===p.expectedRevision+1:v.status==='draft-conflict'?v.templateRevision>p.expectedRevision:v.status==='draft-source-conflict' && v.templateRevision===p.expectedRevision);
}
function definition(v:Data|undefined,wid:string,id:string,uid:string):Data {
  if(!exact(v,['schemaVersion','policyVersion','workspaceId','templateId','ownerUid','revision','createdAt','updatedAt','title','description','workouts']) || v.schemaVersion!==1 || v.policyVersion!==1 || v.workspaceId!==wid || v.templateId!==id || v.ownerUid!==uid || !exerciseRevision(v.revision) || !stamp(v.createdAt) || !stamp(v.updatedAt) || v.updatedAt.toMillis()<v.createdAt.toMillis())fail();
  // Validate stored content using the same structural contract; no authority is persisted in content.
  if(!validDraftSave({templateId:id,expectedRevision:0,authority:{authorLifecycleRevision:1,authorMembershipRevision:1,workspaceMembershipRevision:1},title:v!.title,description:v!.description,workouts:v!.workouts}))fail();
  const result:Data={...v!,createdAtMillis:v!.createdAt.toMillis(),updatedAtMillis:v!.updatedAt.toMillis()};
  delete result.createdAt;delete result.updatedAt;
  if(bytes(result)>16384)fail();return result;
}
export function sourceIdentity(kind:string,id:string,wid:string,uid:string,v:Data|undefined):Data|null {
  if(!v || bytes(v)>24576)return null;
  if(kind==='system') {
    if(!exact(v,['schemaVersion','name','nameNormalized','muscleGroups','equipment','instructions','status']) || v.schemaVersion!==1 || v.status!=='published' || !exerciseText(v.name,120) || !exerciseText(v.nameNormalized,120) || !exerciseText(v.instructions,2000,true))return null;
    if(!['muscleGroups','equipment'].every(k=>Array.isArray(v[k]) && v[k].length<=8 && new Set(v[k]).size===v[k].length && v[k].every((s:unknown)=>exerciseText(s,40))))return null;
    return {sourceKind:kind,sourceId:id,sourceRevision:null,sourceHash:stableHash({sourceKind:kind,sourceId:id,definition:v}),name:v.name};
  }
  if(kind!=='custom' || !exact(v,['schemaVersion','policyVersion','workspaceId','exerciseId','ownerUid','name','instructions','muscleGroups','equipment','revision','createdAt','updatedAt']) || v.schemaVersion!==1 || v.policyVersion!==1 || v.workspaceId!==wid || v.exerciseId!==id || v.ownerUid!==uid || !exerciseRevision(v.revision) || !exerciseDescription(v) || !stamp(v.createdAt) || !stamp(v.updatedAt) || v.updatedAt.toMillis()<v.createdAt.toMillis())return null;
  const projection={schemaVersion:v.schemaVersion,policyVersion:v.policyVersion,ownerUid:v.ownerUid,name:v.name,instructions:v.instructions,muscleGroups:v.muscleGroups,equipment:v.equipment,revision:v.revision};
  return {sourceKind:kind,sourceId:id,sourceRevision:v.revision,sourceHash:stableHash({sourceKind:kind,workspaceId:wid,sourceId:id,definition:projection}),name:v.name};
}
const selection=(i:Data)=>({sourceKind:i.sourceKind,sourceId:i.sourceId,sourceRevision:i.sourceRevision,sourceHash:i.sourceHash,name:i.name});
/** Emulator-only trusted primitive; draft issuance uses the separately proved native admission. */
export async function programDraftOperation(db:Firestore,caller:string|undefined,raw:unknown,clock:()=>number=Date.now,beforeWrites?:()=>Promise<void>) {
  requireProvisioningEmulators();if(!caller)fail('unauthorized');
  const uid=participantUid(caller,'uid'),user=await verifiedEmulatorIdentity(uid),d=raw as Data;
  const mutation=d?.action==='save-program-draft';
  const fields=mutation?['action','workspaceId','idempotencyKey','draftSave']:d?.action==='draft-prepare'?['action','workspaceId']:d?.action==='draft-list'?['action','workspaceId','afterId']:d?.action==='draft-get'?['action','workspaceId','templateId']:[];
  if(!fields.length || !exact(d,fields) || bytes({data:d})>32768)fail();
  const wid=participantUid(strictPathIdentifier(d.workspaceId,'workspace'),'workspace');
  if(mutation) {
    draftDescriptor(wid,d.idempotencyKey,d.draftSave);
    const wrapper=JSON.stringify({protocolVersion:1,descriptor:d});
    if(wrapper.length>8192 || Buffer.byteLength(wrapper,'utf8')>24576)fail();
  } else if(d.action==='draft-get' && !exerciseId(d.templateId) || d.action==='draft-list' && d.afterId!==null && !exerciseId(d.afterId))fail();
  await db.runTransaction(async tx=>{
    const ref=db.doc(`programDraftRequests/${stableHash(uid)}`),s=await tx.get(ref),minute=Math.floor(clock()/60000);
    if(s.exists && (!exact(s.data(),['minute','count']) || !Number.isSafeInteger(s.get('minute')) || s.get('minute')<0 || !Number.isSafeInteger(s.get('count')) || s.get('count')<0 || s.get('count')>120))fail('rate-limited');
    const count=s.get('minute')===minute?s.get('count'):0;if(count>=120)fail('rate-limited');tx.set(ref,{minute,count:count+1});
  },{maxAttempts:3});
  const started=clock();
  return db.runTransaction(async tx=>{
    const deadline=()=>{if(clock()-started>=5000)fail();};deadline();
    if(mutation)await requireCommandGeneration(tx,db,d.idempotencyKey);
    const docs=await tx.getAll(...[`users/${uid}`,`workspaces/${wid}`,`workspaces/${wid}/memberships/${uid}`,`workspaces/${wid}/trainerProfiles/${uid}`,`users/${uid}/membershipRefs/${wid}`].map(p=>db.doc(p)));
    if(docs.some(s=>s.exists && bytes(s.data())>16384))fail();
    const [a,w,m,t,dir]=docs;
    if(a!.get('schemaVersion')!==1 || a!.get('accountStatus')!=='active' || !exerciseRevision(a!.get('lifecycleRevision')) || a!.get('emailNormalized')!==user.email!.toLowerCase() ||
      w!.get('schemaVersion')!==1 || w!.get('status')!=='active' || !exerciseRevision(w!.get('membershipRevision')) ||
      m!.get('schemaVersion')!==1 || m!.get('workspaceId')!==wid || m!.get('userId')!==uid || m!.get('role')!=='trainer' || m!.get('status')!=='active' || m!.get('catalogContributionActive')!==true || !exerciseRevision(m!.get('revision')) ||
      t!.get('schemaVersion')!==1 || t!.get('workspaceId')!==wid || t!.get('userId')!==uid || dir!.get('schemaVersion')!==1 || dir!.get('status')!=='active')fail('unauthorized');
    const authority:Data={authorLifecycleRevision:a!.get('lifecycleRevision'),authorMembershipRevision:m!.get('revision'),workspaceMembershipRevision:w!.get('membershipRevision')};
    const collection=db.collection(`workspaces/${wid}/programTemplates`);
    let result:Data;
    if(d.action==='draft-prepare')result={workspaceId:wid,authority};
    else if(d.action==='draft-list') {
      let query=collection.where('ownerUid','==',uid).orderBy(FieldPath.documentId()).limit(21);if(d.afterId!==null)query=query.startAfter(d.afterId);
      const values=(await tx.get(query)).docs.map(s=>definition(s.data(),wid,s.id,uid));
      result={workspaceId:wid,authority,items:values.slice(0,20).map(v=>({templateId:v.templateId,title:v.title,revision:v.revision})),nextId:values.length>20?values[19]!.templateId:null};
    } else if(d.action==='draft-get')result={workspaceId:wid,authority,draft:definition((await tx.get(collection.doc(d.templateId))).data(),wid,d.templateId,uid)};
    else {
      const p=d.draftSave;
      if(!EXERCISE_AUTHORITY.every(k=>authority[k]===p.authority[k]))fail('unauthorized');
      const target=collection.doc(p.templateId),receipt=db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`);
      const [old,r]=await tx.getAll(target,receipt),hash=stableHash({...d,callerUid:uid,policyVersion:1});
      if(r!.exists) {
        const v=r!.data()!;
        if(bytes(v)>4096 || !exact(v,['schemaVersion','commandKind','callerUid','workspaceId','templateId','requestHash','result','committedAt']) || v.schemaVersion!==1 || v.commandKind!=='program-draft-save' || v.callerUid!==uid || v.workspaceId!==wid || v.templateId!==p.templateId || v.requestHash!==hash || !stamp(v.committedAt) || !draftDecisionValid(v.result,wid,p))fail('idempotency-key-reused');
        deadline();return {...v.result,replayed:true};
      }
      const prior=old!.exists?definition(old!.data(),wid,p.templateId,uid):null;
      if(p.expectedRevision>0 && (!prior || prior.revision<p.expectedRevision))fail();
      const revision=prior?.revision??0;
      let status=revision!==p.expectedRevision?'draft-conflict':'draft-saved';
      if(status==='draft-saved') {
        const previous=new Map<string,Data>((prior?.workouts??[]).flatMap((w:Data)=>w.items).map((i:Data)=>[i.id,i]));
        const changed:Data[]=p.workouts.flatMap((w:Data)=>w.items).filter((i:Data)=>!previous.has(i.id) || stableHash(selection(previous.get(i.id)!))!==stableHash(selection(i)));
        const paths=[...new Set<string>(changed.map(i=>i.sourceKind==='system'?`systemExercises/${i.sourceId}`:`workspaces/${wid}/exercises/${i.sourceId}`))];
        const sources=paths.length?await tx.getAll(...paths.map(path=>db.doc(path))):[];
        const values=new Map(sources.map(s=>[s.ref.path,s.data()]));
        let entitled=true;
        if(changed.some(i=>i.sourceKind==='system')) {
          const e=await tx.get(db.doc(`users/${uid}/authorizations/systemCatalog`)),n=e.get('activeMembershipCount');
          entitled=bytes(e.data()??{})<=4096 && e.get('schemaVersion')===1 && e.get('status')==='active' && Number.isInteger(n) && n>=1 && n<=20;
        }
        for(const i of changed) {
          const path=i.sourceKind==='system'?`systemExercises/${i.sourceId}`:`workspaces/${wid}/exercises/${i.sourceId}`;
          const source=i.sourceKind==='system' && !entitled?null:sourceIdentity(i.sourceKind,i.sourceId,wid,uid,values.get(path));
          if(!source || stableHash(source)!==stableHash(selection(i)))status='draft-source-conflict';
        }
      }
      const applied=status==='draft-saved',now=Timestamp.now();
      if(applied && (revision>=2147483646 || prior && now.toMillis()<prior.updatedAtMillis))fail();
      const decision={status,workspaceId:wid,templateId:p.templateId,templateRevision:applied?revision+1:revision};
      const next={schemaVersion:1,policyVersion:1,workspaceId:wid,templateId:p.templateId,ownerUid:uid,revision:decision.templateRevision,createdAt:old!.get('createdAt')??now,updatedAt:now,title:p.title,description:p.description,workouts:p.workouts};
      if(applied)definition(next,wid,p.templateId,uid);
      if(beforeWrites)await beforeWrites();deadline();
      if(applied) {if(prior)tx.set(target,next);else tx.create(target,next);}
      tx.create(receipt,{schemaVersion:1,commandKind:'program-draft-save',callerUid:uid,workspaceId:wid,templateId:p.templateId,requestHash:hash,result:decision,committedAt:now});
      result={...decision,replayed:false};
    }
    deadline();if(bytes(result)>(d.action==='draft-list'?32768:d.action==='draft-get'?24576:4096))fail();return result;
  },{maxAttempts:3});
}
