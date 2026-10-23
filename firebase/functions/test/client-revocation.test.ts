import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { clientRevocationOperation } from "../src/client-revocation.js";
import { resolveOwnCommand, resolutionBinding } from "../src/command-resolution.js";
import { RESOLUTION_CONFIG_PATH, RESOLUTION_GENERATION } from "../src/command-identity.js";
import { invitationOperation } from "../src/invitations.js";
import { provisionTrainer } from "../src/provisioning.js";
import { completeOnboarding } from "../src/onboarding.js";
import { basicProfileOperation } from "../src/basic-profile.js";
import { publishAssignment } from "../src/assigned-program.js";
import { transitionMembership } from "../src/catalog.js";
import { clientOperation } from "../src/clients.js";
import { ClientCursorKeys } from "../src/client-cursor.js";
import { commandId, stableHash } from "../src/hashing.js";

const db=emulatorFirestore(),auth=getAuth(),owner="r4_owner",client="r4_client",wid="r4_workspace";
const key=(v:string)=>`or1-${RESOLUTION_GENERATION}-${v}`;
const call=(d:unknown,uid:string|undefined=owner)=>clientRevocationOperation(db,uid,d);
const prepare=async()=> (await call({action:"prepare-client-revocation",workspaceId:wid,clientUid:client})).revocationPreparation!;
const command=async(k=key("remove"))=>({action:"revoke-client",workspaceId:wid,idempotencyKey:k,revocation:await prepare()});
const resolve=(d:any)=>resolveOwnCommand(db,owner,{protocolVersion:1,descriptor:d});
const assignment=()=>publishAssignment(db,{callerUid:owner,trainerId:owner,uid:client,workspaceId:wid,idempotencyKey:"r4-publish",assignmentId:"r4_assignment",sourceTemplateId:"t",sourceVersionId:"v",sourceVersionNumber:1,sourceContentHash:"h",snapshotHash:"s",accessExpiresAtMillis:Date.UTC(2030,0,1),workouts:[],plans:[],maxWrites:20});
let invitation:any;
async function state() {
 const roots=["users","workspaces","invitations","lifecycleCommands","assignmentCommands"];
 const values:any[]=[];
 async function collection(path:string) {for(const d of (await db.collection(path).get()).docs) {values.push([d.ref.path,d.data(),d.updateTime?.toMillis()]); for(const c of await d.ref.listCollections())await collection(c.path);}}
 for(const r of roots)await collection(r);return stableHash(values.sort((a,b)=>a[0].localeCompare(b[0])));
}
describe("narrow client workspace revocation",()=>{
 beforeEach(async()=>{
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`,{method:"DELETE"});
  for(const uid of [owner,client,"r4_other"]){await auth.deleteUser(uid).catch(()=>{});await auth.createUser({uid,email:`${uid}@example.invalid`,emailVerified:true});}
  process.env.TF_RESOLUTION_PROVENANCE_ID="r4-suite-intact";
  await db.doc(RESOLUTION_CONFIG_PATH).create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:"r4-suite-intact",historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:20});
  await provisionTrainer(db,{uid:owner,workspaceId:wid,workspaceName:"Revocation fixture",idempotencyKey:"bootstrap",expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
   {uid:owner,email:`${owner}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
  invitation=await invitationOperation(db,owner,{action:"create",workspaceId:wid,email:`${client}@example.invalid`,idempotencyKey:"invite"});
  await invitationOperation(db,client,{action:"accept",invitationId:invitation.invitationId,expectedVersion:1,token:invitation.deliveryToken,idempotencyKey:"accept"});
 });
 it("fresh callable bearer authorization exposes only preparation and rejects foreign authority selectors",async()=>{
  const custom=await auth.createCustomToken(owner);
  const signed=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fixture`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({token:custom,returnSecureToken:true})});
  const idToken=(await signed.json() as any).idToken;
  const request={action:"prepare-client-revocation",workspaceId:wid,clientUid:client};
  const invoke=(body:unknown,bearer=idToken)=>fetch(`http://127.0.0.1:5001/${SPIKE_PROJECT_ID}/europe-west1/clientRevocationEmulator`,{method:"POST",headers:{"Content-Type":"application/json",Authorization:`Bearer ${bearer}`},body:JSON.stringify({data:body})});
  const prepared=await invoke(request);assert.equal(prepared.status,200);const result=(await prepared.json() as any).result;
  assert.deepEqual(Object.keys(result),["revocationPreparation"]);assert.deepEqual(Object.keys(result.revocationPreparation).sort(),["clientUid","expectedTargetRevision","expectedWorkspaceRevision","expectedCallerMembershipRevision","expectedCallerLifecycleRevision"].sort());
  assert.notEqual((await invoke({...request,callerUid:owner})).status,200);assert.notEqual((await invoke(request,"forged")).status,200);
 });
 it("prepares minimal revisions without writes; commits exactly five paths once preserving provenance and unrelated data",async()=>{
  const before=await state(),d=await command();assert.equal(await state(),before);
  const memberRef=db.doc(`workspaces/${wid}/memberships/${client}`),profileRef=db.doc(`workspaces/${wid}/clientProfiles/${client}`);
  const original=(await memberRef.get()).data()!,profile=(await profileRef.get()).data(),account=(await db.doc(`users/${client}`).get()).data();
  const untouched=[`users/${owner}`,`workspaces/${wid}/memberships/${owner}`,`users/${owner}/authorizations/systemCatalog`];
  const times=await db.getAll(...untouched.map(p=>db.doc(p)));
  const writes:string[]=[];
  const measured={doc:db.doc.bind(db),collection:db.collection.bind(db),runTransaction:(fn:any,options:any)=>db.runTransaction(tx=>fn(new Proxy(tx,{get(t,k){
   if(["create","update","delete","set"].includes(String(k)))return(ref:any,...args:any[])=>{writes.push(ref.path);return (t[k as keyof typeof t] as any).call(t,ref,...args);};
   const value=Reflect.get(t,k);return typeof value==="function"?value.bind(t):value;
  }})),options)} as unknown as Firestore;
  const result=await clientRevocationOperation(measured,owner,d);assert.equal(result.revocationDecision?.status,"client-revoked");
  assert.deepEqual(writes.sort(),[memberRef.path,`users/${client}/membershipRefs/${wid}`,`users/${client}/authorizations/systemCatalog`,`workspaces/${wid}`,`lifecycleCommands/${commandId(owner,d.idempotencyKey)}`].sort());
  assert.deepEqual((await memberRef.get()).data(),{...original,status:"revoked",catalogContributionActive:false,revision:original.revision+1});
  assert.deepEqual((await profileRef.get()).data(),profile);assert.deepEqual((await db.doc(`users/${client}`).get()).data(),account);
  assert.equal((await db.doc(`users/${client}/membershipRefs/${wid}`).get()).exists,false);
  assert.equal((await db.doc(`users/${client}/authorizations/systemCatalog`).get()).get("activeMembershipCount"),0);
  const after=await state();assert.equal((await call(d)).replayed,true);assert.equal(await state(),after);
  for(const [i,path] of untouched.entries())assert.equal((await db.doc(path).get()).updateTime?.toMillis(),times[i]!.updateTime?.toMillis());
 });
 it("supports completed onboarding and disabled targets without account repair",async()=>{
  await completeOnboarding(db,client,{action:"complete-onboarding",workspaceId:wid,idempotencyKey:"onboard",onboarding:{displayName:"Client",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:0,expectedOnboardingRevision:0}});
  await auth.updateUser(client,{disabled:true});await db.doc(`users/${client}`).update({accountStatus:"disabled",lifecycleRevision:2});await db.doc(`users/${client}/authorizations/systemCatalog`).update({status:"inactive"});
  const before=(await db.doc(`users/${client}`).get()).data(),d=await command();await call(d);assert.deepEqual((await db.doc(`users/${client}`).get()).data(),before);
 });
 it("requires owner AND assigned trainer; denies forged, self, disabled and unverified callers without effects",async()=>{
  const d=await command();
  for(const request of [{...d,callerUid:owner},{...d,workspaceId:"a/b"},{...d,revocation:{...d.revocation,clientUid:owner}},{...d,revocation:{...d.revocation,expectedTargetRevision:0}}]) {const before=await state();await assert.rejects(call(request));assert.equal(await state(),before);}
  for(const uid of [undefined,client,"r4_other"]) {const before=await state();await assert.rejects(clientRevocationOperation(db,uid,d));assert.equal(await state(),before);}
  for(const change of [{disabled:true},{disabled:false,emailVerified:false}]) {await auth.updateUser(owner,change);const before=await state();await assert.rejects(call(d));assert.equal(await state(),before);}
  await auth.updateUser(owner,{disabled:false,emailVerified:true});
  await db.doc(`workspaces/${wid}`).update({ownerUid:"r4_other"});await assert.rejects(call(d));
  await db.doc(`workspaces/${wid}`).update({ownerUid:owner});await db.doc(`workspaces/${wid}/clientProfiles/${client}`).update({trainerId:"r4_other"});await assert.rejects(call(d));
 });
 it("records stale revisions as immutable conflicts; a late original cannot become valid later",async()=>{
  const d=await command();await db.doc(`workspaces/${wid}`).update({membershipRevision:d.revocation.expectedWorkspaceRevision+1});
  const result=await call(d);assert.equal(result.revocationDecision?.status,"revocation-conflict");assert.equal((await resolve(d)).outcome,"not-applied-conflict");
  await db.doc(`workspaces/${wid}`).update({membershipRevision:d.revocation.expectedWorkspaceRevision});const before=await state();assert.equal((await call(d)).replayed,true);assert.equal(await state(),before);
  await assert.rejects(call({...d,revocation:{...d.revocation,expectedTargetRevision:2}}),/idempotency-key-reused/);
 });
 it("concurrent distinct keys have one success, one conflict and exactly one decrement",async()=>{
  const d=await command();const results=await Promise.all([call(d),call({...d,idempotencyKey:key("second")})]);
  assert.deepEqual(results.map(r=>r.revocationDecision?.status).sort(),["client-revoked","revocation-conflict"]);
  assert.equal((await db.doc(`users/${client}/authorizations/systemCatalog`).get()).get("activeMembershipCount"),0);
 });
 it("stale caller, target and workspace expectations each produce their own durable conflict",async()=>{
  const d=await command();
  for(const field of ["expectedTargetRevision","expectedWorkspaceRevision","expectedCallerMembershipRevision","expectedCallerLifecycleRevision"]) {
   const stale={...d,idempotencyKey:key(field),revocation:{...d.revocation,[field]:(d.revocation as any)[field]+1}};
   assert.equal((await call(stale)).revocationDecision?.status,"revocation-conflict");assert.equal((await resolve(stale)).outcome,"not-applied-conflict");
  }
  assert.equal((await db.doc(`workspaces/${wid}/memberships/${client}`).get()).get("status"),"active");
 });
 it("malformed and lost authority sources reject without consuming the command",async()=>{
  const d=await command();
  for(const [path,field,value] of [[`users/${owner}`,"accountStatus","disabled"],[`users/${owner}`,"emailNormalized","foreign@example.invalid"],
   [`workspaces/${wid}`,"status","suspended"],[`workspaces/${wid}/trainerProfiles/${owner}`,"userId","foreign"],
   [`users/${owner}/membershipRefs/${wid}`,"status","revoked"],[`workspaces/${wid}/memberships/${owner}`,"role","client"],
   [`workspaces/${wid}/memberships/${client}`,"role","trainer"],[`workspaces/${wid}/clientProfiles/${client}`,"onboardingStatus","unsupported"]] as const) {
   const ref=db.doc(path),original=(await ref.get()).get(field);await ref.update({[field]:value});const before=await state();await assert.rejects(call(d));assert.equal(await state(),before);await ref.update({[field]:original});
  }
 });
 it("retained account headers and workspace indexes, including terminal artifacts, reject without writes",async()=>{
  const d=await command();
  for(const path of [`users/${client}/workspaces/${wid}/assignedPrograms/terminal`,`workspaces/${wid}/assignedPrograms/terminal`]) {
   await db.doc(path).set({clientId:client,lifecycleState:"terminal",status:"archived"});const before=await state();await assert.rejects(call(d),/assignment-artifacts/);assert.equal(await state(),before);await db.doc(path).delete();
  }
 });
 it("publication and revocation share a transactional revision fence in both orders and concurrently",async()=>{
  const d=await command();const results=await Promise.allSettled([call(d),assignment()]);
  assert.equal(results.filter(r=>r.status==="fulfilled").length,1);
  const revoked=(await db.doc(`workspaces/${wid}/memberships/${client}`).get()).get("status")==="revoked";
  assert.equal((await db.doc(`users/${client}/workspaces/${wid}/assignedPrograms/r4_assignment`).get()).exists,!revoked);
  if(revoked)await assert.rejects(assignment(),/assignment-authority-unavailable/);else await assert.rejects(call(d),/assignment-artifacts/);
 });
 it("publication-first leaves every retained artifact untouched and rejects prepared revocation",async()=>{
  const d=await command();await assignment();const before=await state();await assert.rejects(call(d),/assignment-artifacts/);assert.equal(await state(),before);
  assert.equal((await resolve(d)).outcome,"not-applied-fenced");
 });
 it("revocation-first makes later publication fail without artifacts or publication receipt",async()=>{
  await call(await command());const before=await state();await assert.rejects(assignment(),/assignment-authority-unavailable/);assert.equal(await state(),before);
 });
 it("freezes the seventh canonical descriptor and rejects changed binding",()=>{
  const descriptor={action:"revoke-client",workspaceId:"w",idempotencyKey:"key",revocation:{clientUid:"C",expectedTargetRevision:2,expectedWorkspaceRevision:3,expectedCallerMembershipRevision:1,expectedCallerLifecycleRevision:1}};
  assert.equal(resolutionBinding("A",{protocolVersion:1,descriptor}).hash,"0fbea0b0fed9442012962dc68aa22f580ede004db58ea65521b10ebe382ebde3");
  assert.throws(()=>resolutionBinding("A",{protocolVersion:1,descriptor:{...descriptor,revocation:{...descriptor.revocation,extra:true}}}));
 });
 it("commit-first and resolver-first revocation outcomes need no workspace authority and restore no access",async()=>{
  const d=await command(),fenced={...d,idempotencyKey:key("fenced")};assert.equal((await resolve(fenced)).outcome,"not-applied-fenced");
  const before=await state();await assert.rejects(call(fenced),/idempotency-key-reused/);assert.equal(await state(),before);
  await call(d);await db.doc(`workspaces/${wid}/memberships/${owner}`).update({status:"revoked",catalogContributionActive:false});await db.doc(`workspaces/${wid}`).update({ownerUid:"r4_other"});
  assert.equal((await resolve(d)).outcome,"applied");await assert.rejects(call(d));assert.equal((await db.doc(`workspaces/${wid}/memberships/${client}`).get()).get("status"),"revoked");
 });
 it("old acceptance and new same-pair invitations cannot restore access; roster/details and profile deny",async()=>{
  await call(await command());
  await assert.rejects(invitationOperation(db,client,{action:"accept",invitationId:invitation.invitationId,expectedVersion:1,token:invitation.deliveryToken,idempotencyKey:"accept"}));
  const newer=await invitationOperation(db,owner,{action:"create",workspaceId:wid,email:`${client}@example.invalid`,idempotencyKey:"invite-again"});
  await assert.rejects(invitationOperation(db,client,{action:"accept",invitationId:newer.invitationId,expectedVersion:1,token:newer.deliveryToken,idempotencyKey:"accept-again"}));
  await assert.rejects(clientOperation(db,owner,{action:"get",workspaceId:wid,clientId:client},new ClientCursorKeys()),/unauthorized/);
  assert.deepEqual((await clientOperation(db,owner,{action:"list",workspaceId:wid,pageSize:2,cursor:null},new ClientCursorKeys()) as any).items,[]);
  await assert.rejects(basicProfileOperation(db,client,{action:"profile-get",workspaceId:wid}));
 });
 it("preserves another workspace contribution and every unrelated membership/account",async()=>{
  const b=db.batch(),paths=[`workspaces/r4_other/memberships/${client}`,`workspaces/r4_other/clientProfiles/${client}`,`users/${client}/membershipRefs/r4_other`];
  b.set(db.doc(paths[0]!),{schemaVersion:1,userId:client,workspaceId:"r4_other",role:"client",status:"active",revision:3,catalogContributionActive:true});
  b.set(db.doc(paths[1]!),{retained:"independent"});b.set(db.doc(paths[2]!),{schemaVersion:1,status:"active"});b.update(db.doc(`users/${client}/authorizations/systemCatalog`),{activeMembershipCount:2});await b.commit();
  const before=await db.getAll(...paths.map(p=>db.doc(p)));await call(await command());
  for(const [i,path] of paths.entries())assert.deepEqual((await db.doc(path).get()).data(),before[i]!.data());
  assert.equal((await db.doc(`users/${client}/authorizations/systemCatalog`).get()).get("status"),"active");
  assert.equal((await db.doc(`users/${client}/authorizations/systemCatalog`).get()).get("activeMembershipCount"),1);
 });
 it("onboarding racing revocation preserves a committed completion or prevents later completion",async()=>{
  const d=await command(),onboard={action:"complete-onboarding",workspaceId:wid,idempotencyKey:key("onboarding-race"),onboarding:{displayName:"Completed",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:0,expectedOnboardingRevision:0}};
  const results=await Promise.allSettled([call(d),completeOnboarding(db,client,onboard)]);assert.equal(results[0]!.status,"fulfilled");
  const completed=results[1]!.status==="fulfilled";assert.equal((await db.doc(`workspaces/${wid}/clientProfiles/${client}`).get()).get("onboardingStatus"),completed?"complete":"pending");
  assert.equal((await resolveOwnCommand(db,client,{protocolVersion:1,descriptor:onboard})).outcome,completed?"applied":"not-applied-fenced");
 });
 it("profile racing revocation preserves a committed global update without rollback",async()=>{
  await completeOnboarding(db,client,{action:"complete-onboarding",workspaceId:wid,idempotencyKey:"complete-first",onboarding:{displayName:"Original",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:0,expectedOnboardingRevision:0}});
  const d=await command(),profile={action:"update-profile",workspaceId:wid,idempotencyKey:key("profile-race"),profileUpdate:{displayName:"Updated",locale:"en",timeZoneId:"UTC",expectedRevision:1}};
  const results=await Promise.allSettled([call(d),basicProfileOperation(db,client,profile)]);assert.equal(results[0]!.status,"fulfilled");
  const updated=results[1]!.status==="fulfilled";assert.equal((await db.doc(`users/${client}`).get()).get("displayName"),updated?"Updated":"Original");
  assert.equal((await resolveOwnCommand(db,client,{protocolVersion:1,descriptor:profile})).outcome,updated?"applied":"not-applied-fenced");
 });
 it("unrelated invitation acceptance racing revocation serializes workspace revision and counters",async()=>{
  const invite=await invitationOperation(db,owner,{action:"create",workspaceId:wid,email:"r4_other@example.invalid",idempotencyKey:"other-invite"}),d=await command();
  const results=await Promise.all([call(d),invitationOperation(db,"r4_other",{action:"accept",invitationId:invite.invitationId,token:invite.deliveryToken,expectedVersion:1,idempotencyKey:"other-accept"})]);
  const removed=results[0].revocationDecision?.status==="client-revoked";
  const roster=await db.collection(`workspaces/${wid}/memberships`).where("status","==","active").get();assert.equal(roster.size,removed?2:3);
  assert.equal((await db.doc(`workspaces/${wid}`).get()).get("activeRosterCount"),roster.size);assert.equal((await db.doc(`workspaces/${wid}`).get()).get("catalogContributionCount"),roster.size);
 });
 it("another-workspace membership transition shares target entitlement contention without lost deltas",async()=>{
  const other="r4_independent";await db.doc(`workspaces/${other}`).set({schemaVersion:1,status:"active",membershipRevision:1,activeRosterCount:1,catalogContributionCount:1});
  await db.doc(`workspaces/${other}/memberships/${client}`).set({schemaVersion:1,workspaceId:other,userId:client,role:"client",status:"active",revision:1,catalogContributionActive:true});
  await db.doc(`users/${client}/membershipRefs/${other}`).set({schemaVersion:1,status:"active"});await db.doc(`users/${client}/authorizations/systemCatalog`).update({activeMembershipCount:2});
  const d=await command();await Promise.all([call(d),transitionMembership(db,{callerUid:"pr2_operator",idempotencyKey:"independent-loss",workspaceId:other,uid:client,role:"client",nextStatus:"revoked",expectedRevision:1,expectedWorkspaceRevision:1,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5})]);
  const ent=await db.doc(`users/${client}/authorizations/systemCatalog`).get();assert.equal(ent.get("activeMembershipCount"),0);assert.equal(ent.get("status"),"inactive");
 });
 it("a stale request cannot consume its key when entitlement arithmetic or discovery is malformed",async()=>{
  const d=await command();d.revocation.expectedWorkspaceRevision++;
  for(const [path,field,value] of [[`users/${client}/authorizations/systemCatalog`,"activeMembershipCount",0],[`users/${client}/membershipRefs/${wid}`,"status","unsupported"]] as const) {
   const ref=db.doc(path),original=(await ref.get()).get(field);await ref.update({[field]:value});const before=await state();await assert.rejects(call(d));assert.equal(await state(),before);await ref.update({[field]:original});
  }
 });
 it("malformed sources and exhausted elapsed-work budget have no partial writes",async()=>{
  const d=await command();
  for(const [path,field,value] of [[`workspaces/${wid}`,"activeRosterCount",0],[`users/${client}/authorizations/systemCatalog`,"activeMembershipCount",0],[`workspaces/${wid}/memberships/${client}`,"userId","foreign"]] as const) {
   const original=(await db.doc(path).get()).get(field);await db.doc(path).update({[field]:value});const before=await state();await assert.rejects(call(d));assert.equal(await state(),before);await db.doc(path).update({[field]:original});
  }
  let time=0;const before=await state();await assert.rejects(clientRevocationOperation(db,owner,d,()=>time++===0?0:5001));assert.equal(await state(),before);
 });
});
