import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { trainerNoteOperation, validNoteBody } from "../src/trainer-notes.js";
import { provisionTrainer } from "../src/provisioning.js";
import { invitationOperation } from "../src/invitations.js";
import { clientRevocationOperation } from "../src/client-revocation.js";
import { resolveOwnCommand, resolutionBinding } from "../src/command-resolution.js";
import { RESOLUTION_CONFIG_PATH, RESOLUTION_GENERATION } from "../src/command-identity.js";
import { transitionAccountLifecycle, transitionMembership, transitionWorkspace } from "../src/catalog.js";
import { commandId, stableHash } from "../src/hashing.js";
const db=emulatorFirestore(),auth=getAuth(),uid="note_author",subject="note_subject",wid="note_workspace";
const note=db.doc(`workspaces/${wid}/privateTrainerNotes/${subject}`);
const get=()=>trainerNoteOperation(db,uid,{action:"note-get",workspaceId:wid,clientUid:subject});
async function save(key="save",body="Review demonstration next meeting.") {
 const r=await get();return {action:"save-trainer-note",workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-${key}`,noteSave:{clientUid:subject,body,bindingNonce:"ab".repeat(32),expectedRevision:r.note?.revision??0,...r.authority}};
}
const run=(d:unknown,caller:string|undefined=uid)=>trainerNoteOperation(db,caller,d);
const resolve=(d:unknown)=>resolveOwnCommand(db,uid,{protocolVersion:1,descriptor:d});
async function revoke() {const p=await clientRevocationOperation(db,uid,{action:"prepare-client-revocation",workspaceId:wid,clientUid:subject});return clientRevocationOperation(db,uid,{action:"revoke-client",workspaceId:wid,idempotencyKey:"note-revoke",revocation:p.revocationPreparation});}
describe("private trainer note fixed slot",()=>{
 beforeEach(async()=>{
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`,{method:"DELETE"});
  for(const id of [uid,subject,"note_other"]){await auth.deleteUser(id).catch(()=>{});await auth.createUser({uid:id,email:`${id}@example.invalid`,emailVerified:true});}
  process.env.TF_RESOLUTION_PROVENANCE_ID="notes-intact";
  await db.doc(RESOLUTION_CONFIG_PATH).create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:"notes-intact",historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:30});
  await provisionTrainer(db,{uid,workspaceId:wid,workspaceName:"Synthetic notes",idempotencyKey:"bootstrap",expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
   {uid,email:`${uid}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
  const inv=await invitationOperation(db,uid,{action:"create",workspaceId:wid,email:`${subject}@example.invalid`,idempotencyKey:"invite"});
  await invitationOperation(db,subject,{action:"accept",invitationId:inv.invitationId,token:inv.deliveryToken,expectedVersion:1,idempotencyKey:"accept"});
 });
 it("uses one exact slot, immutable bindings, minimal receipt and repeatable correction",async()=>{
  assert.equal((await get()).note,null);const d=await save();const r=await run(d);assert.equal(r.status,"note-saved");
  const original=(await note.get()).data()!;assert.equal(original.body,d.noteSave.body);assert.equal(original.authorTrainerId,uid);
  const receipt=(await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).data()!;
  assert.deepEqual(Object.keys(receipt).sort(),["schemaVersion","commandKind","callerUid","workspaceId","clientUid","requestHash","result","committedAt"].sort());
  assert.equal(JSON.stringify(receipt).includes(d.noteSave.body),false);assert.equal(JSON.stringify(receipt).includes(d.noteSave.bindingNonce),false);
  assert.equal((await run(d)).replayed,true);assert.deepEqual((await note.get()).data(),original);
  await run(await save("correction","Review session plan."));const next=(await note.get()).data()!;assert.equal(next.revision,2);assert.deepEqual(next.createdAt,original.createdAt);
  for(const f of ["authorTrainerId","subjectClientId","authorMembershipRevision","subjectMembershipRevision","authorLifecycleRevision","subjectLifecycleRevision"])assert.equal(next[f],original[f]);
 });
 it("concurrent creates and corrections have one durable winner and one conflict",async()=>{
  for(const suffix of ["create","correct"]) {
   const a=await save(suffix+"a"),b={...a,idempotencyKey:a.idempotencyKey+"b",noteSave:{...a.noteSave,body:"Another synthetic administration note."}};
   const results=await Promise.all([run(a),run(b)]);assert.deepEqual(results.map(r=>r.status).sort(),["note-conflict","note-saved"]);
   const loser=results[0]!.status==="note-conflict"?a:b;assert.equal((await resolve(loser)).outcome,"not-applied-conflict");
  }
  assert.equal((await note.get()).get("revision"),2);
 });
 it("has strict caller, workspace, subject, owner and field isolation",async()=>{
  const d=await save();
  for(const caller of [undefined,subject,"note_other"])await assert.rejects(trainerNoteOperation(db,caller,d));
  for(const invalid of [{...d,callerUid:uid},{...d,workspaceId:"other"},{...d,noteSave:{...d.noteSave,clientUid:uid}},{...d,noteSave:{...d.noteSave,extra:true}},{...d,noteSave:{...d.noteSave,expectedRevision:"0"}},{...d,action:"delete"}])await assert.rejects(run(invalid));
  await db.doc(`workspaces/${wid}/clientProfiles/${subject}`).update({trainerId:"note_other"});await assert.rejects(run(d));assert.equal((await note.get()).exists,false);
 });
 it("denies disabled/deleted/unverified Auth and malformed current sources",async()=>{
  const d=await save();
  for(const id of [uid,subject]) {await auth.updateUser(id,{disabled:true});await assert.rejects(run(d));await auth.updateUser(id,{disabled:false});}
  await auth.updateUser(uid,{emailVerified:false});await assert.rejects(run(d));await auth.updateUser(uid,{emailVerified:true});
  for(const [path,field,value] of [[`users/${subject}`,"accountStatus","disabled"],[`workspaces/${wid}`,"status","suspended"],[`workspaces/${wid}/memberships/${uid}`,"workspaceId","other"],[`users/${subject}/membershipRefs/${wid}`,"status","revoked"],[`workspaces/${wid}/clientProfiles/${subject}`,"onboardingStatus","unsupported"]] as const){const ref=db.doc(path),old=(await ref.get()).get(field);await ref.update({[field]:value});await assert.rejects(run(d));await ref.update({[field]:old});}
  assert.equal((await note.get()).exists,false);await auth.deleteUser(subject);await assert.rejects(run(d));
 });
 it("preserves occupied inaccessible slot across withdrawal and forbids rebinding or replacement",async()=>{
  await run(await save());const original=(await note.get()).data();await revoke();assert.deepEqual((await note.get()).data(),original);await assert.rejects(get());
  await db.doc(`workspaces/${wid}/memberships/${subject}`).update({status:"active",revision:3,catalogContributionActive:true});await db.doc(`users/${subject}/membershipRefs/${wid}`).create({schemaVersion:1,status:"active"});
  await assert.rejects(get());await assert.rejects(run({...(await save().catch(()=>({}))),action:"save-trainer-note"}));assert.deepEqual((await note.get()).data(),original);
 });
 it("old occupied slot denies a replacement author even when newly assigned",async()=>{
  await run(await save());const original=(await note.get()).data();
  await db.doc("users/note_other").set({schemaVersion:1,accountStatus:"active",lifecycleRevision:1,emailNormalized:"note_other@example.invalid"});
  await db.doc(`workspaces/${wid}/memberships/note_other`).set({schemaVersion:1,workspaceId:wid,userId:"note_other",role:"trainer",status:"active",revision:1,catalogContributionActive:true});
  await db.doc(`workspaces/${wid}/trainerProfiles/note_other`).set({schemaVersion:1,workspaceId:wid,userId:"note_other"});await db.doc(`users/note_other/membershipRefs/${wid}`).set({schemaVersion:1,status:"active"});
  await db.doc(`workspaces/${wid}/clientProfiles/${subject}`).update({trainerId:"note_other"});
  await assert.rejects(trainerNoteOperation(db,"note_other",{action:"note-get",workspaceId:wid,clientUid:subject}));assert.deepEqual((await note.get()).data(),original);
 });
 it("suspension/restoration and disable/enable revisions never restore old content",async()=>{
  await run(await save());
  for(const [path,field] of [[`workspaces/${wid}/memberships/${uid}`,"revision"],[`workspaces/${wid}/memberships/${subject}`,"revision"],[`users/${uid}`,"lifecycleRevision"],[`users/${subject}`,"lifecycleRevision"]] as const) {
   const ref=db.doc(path),v=(await ref.get()).get(field);await ref.update({[field]:v+1});await assert.rejects(get());await ref.update({[field]:v});
  }
 });
 it("actual Save-first transaction serializes with concurrent revocation without a note-artifact guard",async()=>{
  const d=await save();let release!:()=>void;const held=new Promise<void>(r=>release=r);let entered!:()=>void;const ready=new Promise<void>(r=>entered=r);
  const saving=trainerNoteOperation(db,uid,d,Date.now,async()=>{entered();await held});await ready;
  const revoking=revoke();release();await saving;await revoking;assert.equal((await note.get()).get("revision"),1);await assert.rejects(get());assert.equal((await resolve(d)).outcome,"applied");
 });
 it("revocation-first prevents delayed Save with no note and resolves by fencing",async()=>{
  const d=await save();await revoke();await assert.rejects(run(d));assert.equal((await note.get()).exists,false);assert.equal((await resolve(d)).outcome,"not-applied-fenced");await assert.rejects(run(d));
 });
 it("same-key resolver and writer contention have exactly one terminal record",async()=>{
  const d=await save();const results=await Promise.allSettled([run(d),resolve(d)]);assert.equal(results[1]!.status,"fulfilled");
  const outcome=(results[1] as PromiseFulfilledResult<any>).value.outcome;assert.equal((await note.get()).exists,outcome==="applied");
  const original=(await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).data();await resolve(d);assert.deepEqual((await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).data(),original);
 });
 it("fence-first consumes key across changed text/action and never retains text or nonce",async()=>{
  const d=await save();assert.equal((await resolve(d)).outcome,"not-applied-fenced");await assert.rejects(run(d),/idempotency-key-reused/);
  await assert.rejects(run({...d,noteSave:{...d.noteSave,body:"Changed"}}));
  const fence=(await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).data();assert.equal(JSON.stringify(fence).includes(d.noteSave.body),false);assert.equal(JSON.stringify(fence).includes(d.noteSave.bindingNonce),false);
 });
 it("rejects malformed slots and revisions without consuming a business key",async()=>{
  await run(await save());const d=await save("bad");await assert.rejects(run({...d,noteSave:{...d.noteSave,expectedRevision:8}}));
  const original=(await note.get()).data()!;for(const changed of [{...original,extra:"x"},{...original,revision:"1"},{...original,body:""},{...original,authorTrainerId:"other"}]){await note.set(changed);await assert.rejects(run(d));await note.set(original);}
  assert.equal((await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).exists,false);
 });
 it("validates Unicode/control/size boundaries and frozen canonical binding",async()=>{
  for(const body of [""," ","\uFEFF","\u2028","a\u0000","a\r", "a\ud800","a\udc00","a\u0085","a".repeat(2001)])assert.equal(validNoteBody(body),false);
  for(const body of ["a\n\tb","😀".repeat(2000),"a".repeat(2000)])assert.equal(validNoteBody(body),true);
  const d=await save("unicode","😀".repeat(2000));assert.ok(JSON.stringify({protocolVersion:1,descriptor:d}).length<=8192);await run(d);
  const vector={action:"save-trainer-note",workspaceId:"w",idempotencyKey:"key",noteSave:{clientUid:"C",body:"Synthetic 😀\nReview",bindingNonce:"ab".repeat(32),expectedRevision:0,authorMembershipRevision:1,subjectMembershipRevision:2,authorLifecycleRevision:1,subjectLifecycleRevision:1}};
  assert.equal(resolutionBinding("A",{protocolVersion:1,descriptor:vector}).hash,"f06b52a740f6545436c6d364bdce78c21593cce6817fd7818656a31afbeccadf");
 });
 it("strict callable verifies bearer and never accepts raw caller overrides",async()=>{
  const custom=await auth.createCustomToken(uid);const signed=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fixture`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({token:custom,returnSecureToken:true})});const token=(await signed.json() as any).idToken;
  const invoke=(body:unknown,bearer=token)=>fetch(`http://127.0.0.1:5001/${SPIKE_PROJECT_ID}/europe-west1/trainerNotesEmulator`,{method:"POST",headers:{"Content-Type":"application/json",Authorization:`Bearer ${bearer}`},body:JSON.stringify({data:body})});
  assert.equal((await invoke({action:"note-get",workspaceId:wid,clientUid:subject})).status,200);
  assert.notEqual((await invoke(await save(),"forged")).status,200);assert.notEqual((await invoke({...await save(),callerUid:uid})).status,200);
 });
 it("a current account lifecycle transaction serializes with Save and never restores the note",async()=>{
  const d=await save();const results=await Promise.allSettled([run(d),transitionAccountLifecycle(db,{callerUid:"pr2_operator",idempotencyKey:"note-disable",uid:subject,enabled:false,expectedLifecycleRevision:1,maxMembershipsPerAccount:2})]);
  assert.equal(results[1]!.status,"fulfilled");assert.equal((await db.doc(`users/${subject}`).get()).get("accountStatus"),"disabled");await assert.rejects(get());
  const applied=results[0]!.status==="fulfilled";assert.equal((await note.get()).exists,applied);assert.equal((await resolve(d)).outcome,applied?"applied":"not-applied-fenced");
 });
 for(const change of ["author-withdrawal","workspace-suspension","reassignment-fixture"]) it(`${change} transaction races Save without historical restoration`,async()=>{
  const d=await save(),workspace=db.doc(`workspaces/${wid}`),member=db.doc(`workspaces/${wid}/memberships/${uid}`),subjectMember=db.doc(`workspaces/${wid}/memberships/${subject}`),profile=db.doc(`workspaces/${wid}/clientProfiles/${subject}`);
  const workspaceRevision=(await workspace.get()).get("membershipRevision"),authorRevision=(await member.get()).get("revision");
  const withdraw=()=>change==="author-withdrawal" ? transitionMembership(db,{callerUid:"pr2_operator",idempotencyKey:"note-author-withdraw",workspaceId:wid,uid,role:"trainer",nextStatus:"revoked",expectedRevision:authorRevision,expectedWorkspaceRevision:workspaceRevision,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5}) :
   change==="workspace-suspension" ? transitionWorkspace(db,{callerUid:"pr2_operator",idempotencyKey:"note-suspend",workspaceId:wid,nextStatus:"suspended",expectedMembershipRevision:workspaceRevision,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5,maxWrites:20}) :
   // No reassignment product/API exists. A future trusted writer must advance the relationship revision.
   db.runTransaction(async tx=>{const [m]=await tx.getAll(subjectMember,profile);tx.update(subjectMember,{revision:m!.get("revision")+1});tx.update(profile,{trainerId:"note_other"});});
  const results=await Promise.allSettled([run(d),withdraw()]);assert.equal(results[1]!.status,"fulfilled");await assert.rejects(get());
  const applied=results[0]!.status==="fulfilled";assert.equal((await note.get()).exists,applied);assert.equal((await resolve(d)).outcome,applied?"applied":"not-applied-fenced");
  if(change==="author-withdrawal") await transitionMembership(db,{callerUid:"pr2_operator",idempotencyKey:"note-author-return",workspaceId:wid,uid,role:"trainer",nextStatus:"active",expectedRevision:authorRevision+1,expectedWorkspaceRevision:workspaceRevision+1,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
  else if(change==="workspace-suspension") await transitionWorkspace(db,{callerUid:"pr2_operator",idempotencyKey:"note-restore",workspaceId:wid,nextStatus:"active",expectedMembershipRevision:workspaceRevision+1,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5,maxWrites:20});
  else await db.runTransaction(async tx=>{const m=await tx.get(subjectMember);tx.update(subjectMember,{revision:m.get("revision")+1});tx.update(profile,{trainerId:uid});});
  await assert.rejects(run(d));if(applied)await assert.rejects(get());else assert.equal((await get()).note,null);
 });
 it("rate budget blocks requests at the exact bound without effects",async()=>{
  const d=await save();await db.doc(`trainerNoteRequests/${stableHash(uid)}`).set({minute:Math.floor(Date.now()/60000),count:120});await assert.rejects(run(d),/rate-limited/);assert.equal((await note.get()).exists,false);
 });
 it("rate/elapsed limits fail without partial business effect",async()=>{
  const d=await save();let clock=0;await assert.rejects(trainerNoteOperation(db,uid,d,()=>clock++<2?0:5001));assert.equal((await note.get()).exists,false);
 });
});
