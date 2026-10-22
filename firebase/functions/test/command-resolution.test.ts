import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { createHash } from "node:crypto";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { resolveOwnCommand, resolutionBinding } from "../src/command-resolution.js";
import { RESOLUTION_GENERATION, RESOLUTION_CONFIG_PATH } from "../src/command-identity.js";
import { basicProfileOperation } from "../src/basic-profile.js";
import { completeOnboarding } from "../src/onboarding.js";
import { invitationOperation } from "../src/invitations.js";
import { provisionTrainer } from "../src/provisioning.js";
import { commandId, stableHash } from "../src/hashing.js";
import { transitionMembership, transitionAccountLifecycle, transitionWorkspace } from "../src/catalog.js";

const db=emulatorFirestore(), auth=getAuth(), uid="resolution_client", trainer="resolution_trainer", wid="resolution_workspace";
const key=(n:string)=>`or1-${RESOLUTION_GENERATION}-${n}`;
const profile=(k=key("profile"))=>({action:"update-profile",workspaceId:wid,idempotencyKey:k,profileUpdate:{displayName:"Updated",locale:"bs-BA",timeZoneId:"Europe/Sarajevo",expectedRevision:2}});
const packet=(descriptor:any)=>({protocolVersion:1,descriptor});
const resolve=(d:any,caller=uid)=>resolveOwnCommand(db,caller,packet(d));
const digest=(s:string)=>createHash("sha256").update(s).digest("hex");
async function sources() { const refs=[`users/${uid}`,`workspaces/${wid}`,`workspaces/${wid}/memberships/${uid}`,`workspaces/${wid}/clientProfiles/${uid}`];return stableHash((await db.getAll(...refs.map(p=>db.doc(p)))).map(d=>({p:d.ref.path,d:d.data(),t:d.updateTime?.toMillis()}))); }
async function owner() {
 await provisionTrainer(db,{uid:trainer,workspaceId:wid,workspaceName:"Resolution",idempotencyKey:"bootstrap",expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
 {uid:trainer,email:`${trainer}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
}
async function join() {
 const r=await invitationOperation(db,trainer,{action:"create",workspaceId:wid,email:`${uid}@example.invalid`,idempotencyKey:"initial-invite"});
 await invitationOperation(db,uid,{action:"accept",invitationId:r.invitationId,expectedVersion:1,token:r.deliveryToken,idempotencyKey:"initial-accept"});
 return r;
}
async function ready() {await owner();await join();await completeOnboarding(db,uid,{action:"complete-onboarding",workspaceId:wid,idempotencyKey:"initial-onboard",onboarding:{displayName:"Original",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:1,expectedOnboardingRevision:0}});}
function projection(r:any) {assert.deepEqual(Object.keys(r).sort(),["protocolVersion","commandKind","commandId","requestHashVersion","requestHash","outcome","decidedAtMillis"].sort());assert(!JSON.stringify(r).includes("Updated"));}
describe("own-command atomic outcome resolution",()=>{
 beforeEach(async()=>{
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`,{method:"DELETE"});
  for(const id of [uid,trainer,"resolution_other"]){await auth.deleteUser(id).catch(()=>{});await auth.createUser({uid:id,email:`${id}@example.invalid`,emailVerified:true});
   await db.doc(`users/${id}`).set({schemaVersion:1,accountStatus:"active",lifecycleRevision:1,emailNormalized:`${id}@example.invalid`,displayName:"Original",locale:"en",timeZoneId:"UTC",profileRevision:1});}
  for(const id of [uid,"resolution_other"]) await db.doc(`users/${id}/authorizations/systemCatalog`).set({schemaVersion:1,status:"inactive",activeMembershipCount:0,revision:1});
  // Provisioning accepts an existing account only with a consistent inactive entitlement.
  await db.doc(`users/${trainer}`).delete();
  process.env.TF_RESOLUTION_PROVENANCE_ID="resolution-suite-intact";
  await db.doc(RESOLUTION_CONFIG_PATH).create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:"resolution-suite-intact",historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:20});
 });
 it("pins cross-platform canonical vectors",()=>{
  const a=resolutionBinding("A",packet({action:"create",workspaceId:"w",email:"c@example.invalid",idempotencyKey:"key"}));
  assert.equal(a.id,"3393e16babad34f10337e79fd4ffb966679b3b5a35b1a6f58a52e02349d36313");assert.equal(a.hash,"ae24898ad9863e5b3e535f670c55f6f7d67a0c7d468a799e2006e35dfc8c9796");
  assert.equal(resolutionBinding("A",packet({action:"update-profile",workspaceId:"w",idempotencyKey:"key",profileUpdate:{displayName:"Žan 😀",locale:"bs-BA",timeZoneId:"Europe/Sarajevo",expectedRevision:2}})).hash,"69d54b93774f1e7fa5800b191caa9c30117d850628b23f5d6a79a273c92c5906");
  for(const [action,hash] of Object.entries({resend:"c2d934bf9da4013916244dfc034bc31801d0b20f86dc2b74e1f183d98f00f9cf",revoke:"aee6323b55551f5e383680e9fdf27eb414ca21b952b218865105b0d7b03f3a8d"}))
   assert.equal(resolutionBinding("A",packet({action,workspaceId:"w",invitationId:"a".repeat(64),expectedVersion:2,idempotencyKey:"key"})).hash,hash);
  assert.equal(resolutionBinding("A",packet({action:"accept",invitationId:"a".repeat(64),expectedVersion:2,idempotencyKey:"key",acceptanceTokenDigest:"b".repeat(64)})).hash,"1d363ef29f11943185c11aabcc5c014e34bc08a4c27c981cb3dc06fce1acb7db");
  assert.equal(resolutionBinding("A",packet({action:"complete-onboarding",workspaceId:"w",idempotencyKey:"key",onboarding:{displayName:"Žan 😀",locale:"bs-BA",timeZoneId:"Europe/Sarajevo",acknowledged:true,expectedAccountRevision:2,expectedOnboardingRevision:0}})).hash,"936c9439422299d6d126278055676c7447fac6acdd31748680913e3f073aa76b");
  assert.doesNotThrow(()=>resolutionBinding("A",packet({action:"revoke",workspaceId:"w",invitationId:"a".repeat(64),expectedVersion:2_147_483_647,idempotencyKey:"key"})));
  assert.throws(()=>resolutionBinding("A",packet({action:"resend",workspaceId:"w",invitationId:"a".repeat(64),expectedVersion:Number.MAX_SAFE_INTEGER,idempotencyKey:"key"})));
 });
 it("commit-first lost reply resolves after membership loss without resource reads/writes or access restoration",async()=>{
  await ready();const d=profile();await basicProfileOperation(db,uid,d);
  await db.doc(`workspaces/${wid}/memberships/${uid}`).update({status:"revoked"});await db.doc(`users/${uid}/membershipRefs/${wid}`).delete();
  const before=await sources();const r=await resolve(d);projection(r);assert.equal(r.outcome,"applied");assert.equal(await sources(),before);assert.deepEqual(await resolve(d),r);
  await assert.rejects(basicProfileOperation(db,uid,d),/unauthorized/);
 });
 it("fresh callable worker validates bearer identity and returns only the terminal projection",async()=>{
  await ready();const d=profile();await basicProfileOperation(db,uid,d);
  const customToken=await auth.createCustomToken(uid);
  const signIn=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fixture`,
   {method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({token:customToken,returnSecureToken:true})});
  const token=(await signIn.json() as {idToken:string}).idToken;assert(token);
  const url=`http://${process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001"}/${SPIKE_PROJECT_ID}/europe-west1/commandResolutionEmulator`;
  const response=await fetch(url,{method:"POST",headers:{"content-type":"application/json",authorization:`Bearer ${token}`},body:JSON.stringify({data:packet(d)})});
  assert.equal(response.status,200);const body=await response.json() as {result:any};projection(body.result);assert.equal(body.result.outcome,"applied");
  const denied=await fetch(url,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({data:packet(d)})});assert.equal(denied.status,403);
 });
 it("resolver-first fences delayed original after its invocation starts; exactly zero business effects",async()=>{
  await ready();const d=profile();let entered!:()=>void,release!:()=>void;
  const barrier=new Promise<void>(r=>entered=r), held=new Promise<void>(r=>release=r);
  const delayed=new Proxy(db,{get(target,prop){if(prop==="runTransaction")return (body:any,options:any)=>target.runTransaction(async tx=>{entered();await held;return body(tx);},options);const v=(target as any)[prop];return typeof v==="function"?v.bind(target):v;}}) as Firestore;
  const original=basicProfileOperation(delayed,uid,d);await barrier;
  const before=await sources(), r=await resolve(d);assert.equal(r.outcome,"not-applied-fenced");release();await assert.rejects(original,/idempotency-key-reused/);
  assert.equal(await sources(),before);assert.deepEqual(await resolve(d),r);
  await assert.rejects(basicProfileOperation(db,uid,{...d,profileUpdate:{...d.profileUpdate,displayName:"Changed"}}),/idempotency-key-reused/);
 });
 it("concurrent commit/resolution has one immutable winner and no duplicate effects",async()=>{
  await ready();const d=profile();const results=await Promise.allSettled([basicProfileOperation(db,uid,d),resolve(d),resolve(d)]);
  const r=await resolve(d), receipt=(await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).data()!;
  assert.equal((await db.doc(`users/${uid}`).get()).get("profileRevision"),r.outcome==="applied"?3:2);
  assert.equal(receipt.schemaVersion,r.outcome==="applied"?1:2);assert(results.some(x=>x.status==="fulfilled"));assert.deepEqual(await resolve(d),r);
 });
 it("an original aborted after reading C absent must re-read the winning fence on transaction retry",async()=>{
  await ready();const d=profile();let entered!:()=>void,abort!:()=>void,retry!:()=>void;
  const read=new Promise<void>(r=>entered=r), releaseAbort=new Promise<void>(r=>abort=r), releaseRetry=new Promise<void>(r=>retry=r);
  let attempts=0;
  const delayed=new Proxy(db,{get(target,prop){
   if(prop==="runTransaction")return (body:any,options:any)=>target.runTransaction(async tx=>{
    attempts++;
    if(attempts>1){await releaseRetry;return body(tx);}
    const wrapped=new Proxy(tx,{get(transaction,field){
     if(field==="get")return async (ref:any)=>{const result=await transaction.get(ref);if(ref.path===`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`){
      assert(!(result as any).exists);entered();await releaseAbort;throw Object.assign(new Error("injected transaction abort after absent read"),{code:10});}return result;};
     const value=(transaction as any)[field];return typeof value==="function"?value.bind(transaction):value;
    }});return body(wrapped);
   },options);
   const value=(target as any)[prop];return typeof value==="function"?value.bind(target):value;
  }}) as Firestore;
  const original=basicProfileOperation(delayed,uid,d);await read;const before=await sources();
  const resolution=resolve(d);abort();const result=await resolution;assert.equal(result.outcome,"not-applied-fenced");
  retry();await assert.rejects(original,/idempotency-key-reused/);assert(attempts>=2);assert.equal(await sources(),before);
 });
 it("projects each invitation receipt without inviter/current membership authority or token recovery",async()=>{
  await owner();const create={action:"create",workspaceId:wid,email:`${uid}@example.invalid`,idempotencyKey:key("create")};
  const a=await invitationOperation(db,trainer,create);assert.equal((await resolve(create,trainer)).outcome,"applied");
  const resend={action:"resend",workspaceId:wid,invitationId:a.invitationId,expectedVersion:1,idempotencyKey:key("resend")};
  const b=await invitationOperation(db,trainer,resend);assert.equal((await resolve(resend,trainer)).outcome,"applied");
  const accept={action:"accept",invitationId:a.invitationId,expectedVersion:2,idempotencyKey:key("accept"),token:b.deliveryToken};await invitationOperation(db,uid,accept);
  const descriptor={action:accept.action,invitationId:accept.invitationId,expectedVersion:2,idempotencyKey:accept.idempotencyKey,acceptanceTokenDigest:digest(accept.token!)};
  await db.doc(`workspaces/${wid}/memberships/${uid}`).update({status:"revoked"});await db.doc(`workspaces/${wid}`).update({status:"suspended"});
  const r=await resolve(descriptor);projection(r);assert.equal(r.outcome,"applied");assert.equal((await resolve(create,trainer)).outcome,"applied");
 });
 it("fences all six kinds; delayed requests cannot bypass consumed keys, even across shared primitives",async()=>{
  await ready();const iid="a".repeat(64);
  const ds=[{action:"create",workspaceId:wid,email:`${uid}@example.invalid`,idempotencyKey:key("c")},
   ...["resend","revoke"].map(action=>({action,workspaceId:wid,invitationId:iid,expectedVersion:1,idempotencyKey:key(action)})),
   {action:"accept",invitationId:iid,expectedVersion:1,idempotencyKey:key("a"),acceptanceTokenDigest:digest("A".repeat(43))},
   {action:"complete-onboarding",workspaceId:wid,idempotencyKey:key("o"),onboarding:{displayName:"Original",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:1,expectedOnboardingRevision:0}},profile()];
  for(const d of ds){const r=await resolve(d);projection(r);assert.equal(r.outcome,"not-applied-fenced");assert.deepEqual(await resolve(d),r);}
  const d=ds[0]!, k=d.idempotencyKey;const before=await sources();
  await assert.rejects(transitionMembership(db,{callerUid:uid,idempotencyKey:k,workspaceId:wid,uid,role:"client",nextStatus:"revoked",expectedRevision:1,expectedWorkspaceRevision:2,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5}),/idempotency-key-reused/);
  await assert.rejects(transitionAccountLifecycle(db,{callerUid:uid,idempotencyKey:k,uid,enabled:false,expectedLifecycleRevision:1,maxMembershipsPerAccount:2}),/idempotency-key-reused/);
  await assert.rejects(transitionWorkspace(db,{callerUid:uid,idempotencyKey:k,workspaceId:wid,nextStatus:"suspended",expectedMembershipRevision:2,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5,maxWrites:200}),/idempotency-key-reused/);assert.equal(await sources(),before);
 });
 it("validates revoke/onboarding/conflict legacy receipts with distinct semantics",async()=>{
  await ready();const d=profile("legacy-update");await basicProfileOperation(db,uid,d);
  const conflict=profile("legacy-conflict");await basicProfileOperation(db,uid,conflict);assert.equal((await resolve(conflict)).outcome,"not-applied-conflict");
  const onboarding={action:"complete-onboarding",workspaceId:wid,idempotencyKey:"initial-onboard",onboarding:{displayName:"Original",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:1,expectedOnboardingRevision:0}};
  assert.equal((await resolve(onboarding)).outcome,"applied");
  const c={action:"create",workspaceId:wid,email:`${uid}@example.invalid`,idempotencyKey:"another-invite"};const i=await invitationOperation(db,trainer,c);
  const revoke={action:"revoke",workspaceId:wid,invitationId:i.invitationId,expectedVersion:1,idempotencyKey:"legacy-revoke"};await invitationOperation(db,trainer,revoke);projection(await resolve(revoke,trainer));
 });
 it("forecloses valid delayed business requests for every protected variant without partial writes",async()=>{
  await ready();
  const target="resolution_other";
  const create={action:"create",workspaceId:wid,email:`${target}@example.invalid`,idempotencyKey:key("valid-create")};
  const beforeCreate=await sources();await resolve(create,trainer);
  await assert.rejects(invitationOperation(db,trainer,create),/idempotency-key-reused/);assert.equal(await sources(),beforeCreate);
  assert(!(await db.doc(`invitations/${commandId(trainer,create.idempotencyKey)}`).get()).exists);
  const invitation=await invitationOperation(db,trainer,{...create,idempotencyKey:"valid-invitation"});
  const invitationRef=db.doc(`invitations/${invitation.invitationId}`);
  for(const action of ["resend","revoke"]) {
   const d={action,workspaceId:wid,invitationId:invitation.invitationId,expectedVersion:1,idempotencyKey:key(`valid-${action}`)};
   const before=(await invitationRef.get()).updateTime!;await resolve(d,trainer);
   await assert.rejects(invitationOperation(db,trainer,d),/idempotency-key-reused/);assert((await invitationRef.get()).updateTime!.isEqual(before));
  }
  const accept={action:"accept",invitationId:invitation.invitationId,expectedVersion:1,idempotencyKey:key("valid-accept"),token:invitation.deliveryToken};
  const {token,...binding}=accept;const beforeAccept=await sources();
  await resolve({...binding,acceptanceTokenDigest:digest(token!)},target);
  await assert.rejects(invitationOperation(db,target,accept),/idempotency-key-reused/);assert.equal(await sources(),beforeAccept);
  assert(!(await db.doc(`workspaces/${wid}/memberships/${target}`).get()).exists);
  await invitationOperation(db,target,{...accept,idempotencyKey:"different-legitimate-accept"});
  const onboarding={action:"complete-onboarding",workspaceId:wid,idempotencyKey:key("valid-onboard"),onboarding:{displayName:"Other",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:1,expectedOnboardingRevision:0}};
  const account=db.doc(`users/${target}`), directory=db.doc(`workspaces/${wid}/clientProfiles/${target}`);
  const before=await db.getAll(account,directory);await resolve(onboarding,target);
  await assert.rejects(completeOnboarding(db,target,onboarding),/idempotency-key-reused/);
  const after=await db.getAll(account,directory);after.forEach((s,i)=>assert(s.updateTime!.isEqual(before[i]!.updateTime!)));
  const d=profile(key("valid-profile")), profileBefore=await sources();await resolve(d);
  await assert.rejects(basicProfileOperation(db,uid,d),/idempotency-key-reused/);assert.equal(await sources(),profileBefore);
 });
 it("denies foreign payloads, tampering and generic absence without rewriting records",async()=>{
  await ready();const d=profile();await basicProfileOperation(db,uid,d);
  await assert.rejects(resolve({...d,profileUpdate:{...d.profileUpdate,displayName:"Foreign"}}),/outcome-unavailable/);
  await assert.rejects(resolve(profile("legacy-missing")),/outcome-unavailable/);
  const foreign=await resolve(d,"resolution_other");assert.equal(foreign.outcome,"not-applied-fenced");assert.notEqual(foreign.commandId,commandId(uid,d.idempotencyKey));
  const ref=db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`);await ref.update({extra:"private"});const before=(await ref.get()).updateTime;
  await assert.rejects(resolve(d),/outcome-unavailable/);assert((await ref.get()).updateTime!.isEqual(before!));
  await assert.rejects(resolve({action:"accept",invitationId:"a".repeat(64),expectedVersion:1,idempotencyKey:"legacy-accept"}),/outcome-unavailable/);
 });
 it("bounds budgets, preserves replay and disables unproved coverage/retired generations",async()=>{
  await ready();await db.doc(RESOLUTION_CONFIG_PATH).update({maxFencesPerCaller:1});const d=profile(key("first"));const r=await resolve(d);
  await assert.rejects(resolve(profile(key("second"))),/rate-limited/);assert.deepEqual(await resolve(d),r);
  await db.doc(RESOLUTION_CONFIG_PATH).update({historyVerified:false});await assert.rejects(resolve(profile(key("lost"))),/outcome-unavailable/);await assert.rejects(basicProfileOperation(db,uid,profile(key("lost"))),/outcome-unavailable/);
  const unknown=profile("or1-00000000000000000000000000000000-retired");await assert.rejects(basicProfileOperation(db,uid,unknown),/outcome-unavailable/);
  const provisioning={uid:trainer,workspaceId:wid,workspaceName:"Resolution",idempotencyKey:unknown.idempotencyKey,expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0 as const};
  await assert.rejects(provisionTrainer(db,provisioning,{uid:trainer,email:`${trainer}@example.invalid`,emailVerified:true,disabled:false},
   {uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5}),/outcome-unavailable/);
  assert.deepEqual(await resolve(d),r); // Intact terminal proof does not require absence coverage.
  await db.doc(`commandResolutionRequests/${stableHash(uid)}`).set({minute:Math.floor(Date.now()/60000),count:120});await assert.rejects(resolve(d),/rate-limited/);
 });
 it("denies disabled/deleted Auth and global account without repair or terminal writes",async()=>{
  await ready();const d=profile();await auth.updateUser(uid,{disabled:true});await assert.rejects(resolve(d),/identity-required/);await auth.updateUser(uid,{disabled:false});
  for(const patch of [{accountStatus:"disabled"},{schemaVersion:9},{lifecycleRevision:"1"}]){const ref=db.doc(`users/${uid}`), data=(await ref.get()).data()!;await ref.update(patch);await assert.rejects(resolve(d),/identity-required/);await ref.set(data);}
  await auth.deleteUser(uid);await assert.rejects(resolve(d),/identity-required/);assert(!(await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).exists);
 });
});
