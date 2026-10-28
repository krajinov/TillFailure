import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { customExerciseOperation } from "../src/custom-exercises.js";
import { validExerciseDescriptor, exerciseText } from "../src/custom-exercise-contract.js";
import { provisionTrainer } from "../src/provisioning.js";
import { resolveOwnCommand, resolutionBinding } from "../src/command-resolution.js";
import { RESOLUTION_CONFIG_PATH, RESOLUTION_GENERATION } from "../src/command-identity.js";
import { transitionAccountLifecycle, transitionMembership, transitionWorkspace } from "../src/catalog.js";
import { commandId, stableHash } from "../src/hashing.js";
const db=emulatorFirestore(),auth=getAuth(),uid="exercise_author",wid="exercise_workspace",id="00000000-0000-4000-8000-000000000001";
const target=db.doc(`workspaces/${wid}/exercises/${id}`);
const read=(action="exercise-prepare",extra={})=>customExerciseOperation(db,uid,{action,workspaceId:wid,...extra});
const run=(d:any,caller=uid)=>customExerciseOperation(db,caller,d);
const resolve=(d:any)=>resolveOwnCommand(db,uid,{protocolVersion:1,descriptor:d});
async function write(revision=0,key="write") {return {action:revision===0?"create-custom-exercise":"correct-custom-exercise",workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-${key}`,
 exerciseWrite:{exerciseId:id,expectedRevision:revision,authority:(await read()).authority,name:"Synthetic demo",instructions:"Original demonstration text.",muscleGroups:["legs"],equipment:["barbell"]}};}
describe("author custom exercise contract",()=>{
 beforeEach(async()=>{
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`,{method:"DELETE"});
  for(const u of [uid,"exercise_other"]){await auth.deleteUser(u).catch(()=>{});await auth.createUser({uid:u,email:`${u}@example.invalid`,emailVerified:true});}
  process.env.TF_RESOLUTION_PROVENANCE_ID="exercises-intact";
  await db.doc(RESOLUTION_CONFIG_PATH).create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:"exercises-intact",historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:30});
  await provisionTrainer(db,{uid,workspaceId:wid,workspaceName:"Synthetic exercises",idempotencyKey:"bootstrap",expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
   {uid,email:`${uid}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
 });
 it("freezes hashes and complete maximum request/resolver budgets",async()=>{
  for(const revision of [0,2]) {
   const d={action:revision===0?"create-custom-exercise":"correct-custom-exercise",workspaceId:"w",idempotencyKey:"key",exerciseWrite:{exerciseId:id,expectedRevision:revision,authority:{authorLifecycleRevision:1,authorMembershipRevision:2,workspaceMembershipRevision:3},name:"Synthetic 😀",instructions:'Demo "quote" \\ step\nReview',muscleGroups:["legs"],equipment:["barbell"]}};
   assert.equal(resolutionBinding("A",{protocolVersion:1,descriptor:d}).hash,revision===0?"ae185ccb7e0cce0507213e64cde7f195e4403c637f3c7e25cdd522fc34f78838":"33630c7796954279b0783f391d484a407414e31f901ad94e58a585d0964934c6");
  }
  for(const text of ["😀".repeat(2000),'"'.repeat(2000),"\\".repeat(2000),"界".repeat(2000),"a"+"\n".repeat(1998)+"b"]) {
   const d=await write();d.workspaceId="\u0002".repeat(128);d.idempotencyKey=`or1-${RESOLUTION_GENERATION}-${"a".repeat(80)}`;d.exerciseWrite.name="😀".repeat(120);d.exerciseWrite.instructions=text;
   assert.equal(validExerciseDescriptor(d),true);
  }
  for(const text of ["","a\r","a\t","a\ud800","a\u0085","a".repeat(2001)])assert.equal(exerciseText(text,2000,true),false);
 });
 it("creates/corrects with immutable identity, minimal receipts and exact replay",async()=>{
  const d=await write();assert.equal((await run(d)).status,"exercise-created");const old=(await target.get()).data()!;
  assert.equal((await run(d)).replayed,true);assert.deepEqual((await target.get()).data(),old);
  const c=await write(1,"correct");c.exerciseWrite.instructions="Corrected synthetic text.";await run(c);const next=(await target.get()).data()!;
  assert.equal(next.revision,2);for(const k of ["ownerUid","workspaceId","exerciseId","createdAt"])assert.deepEqual(next[k],old[k]);
  assert.equal((await run(d)).exerciseRevision,1);assert.equal((await resolve(d)).outcome,"applied");
  const receipt=(await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).data()!;
  assert.deepEqual(Object.keys(receipt).sort(),["schemaVersion","commandKind","callerUid","workspaceId","exerciseId","requestHash","result","committedAt"].sort());assert.equal(JSON.stringify(receipt).includes(d.exerciseWrite.instructions),false);
 });
 it("collisions and concurrent corrections each produce one immutable conflict",async()=>{
  for(const revision of [0,1]) {
   const a=await write(revision,`a${revision}`),b={...a,idempotencyKey:a.idempotencyKey+"b"};
   const results=await Promise.all([run(a),run(b)]);assert.deepEqual(results.map(r=>r.status).sort(),["exercise-conflict",revision===0?"exercise-created":"exercise-corrected"].sort());
   const loser=results[0]!.status==="exercise-conflict"?a:b;assert.equal((await resolve(loser)).outcome,"not-applied-conflict");assert.equal((await run(loser)).replayed,true);
  }
  assert.equal((await target.get()).get("revision"),2);
 });
 it("lists only authors, validates lookahead and has transient ID pagination",async()=>{
  const d=await write();await run(d);const value=(await target.get()).data()!;
  for(let n=2;n<=23;n++)await target.parent.doc(`00000000-0000-4000-8000-${String(n).padStart(12,"0")}`).create({...value,exerciseId:`00000000-0000-4000-8000-${String(n).padStart(12,"0")}`});
  await target.parent.doc("00000000-0000-4000-9000-000000000001").create({...value,exerciseId:"00000000-0000-4000-9000-000000000001",ownerUid:"exercise_other"});
  const a=await read("exercise-list",{afterId:null});assert.equal(a.items.length,20);const b=await read("exercise-list",{afterId:a.nextId});assert.equal(b.items.length,3);assert.equal(b.nextId,null);
  await target.parent.doc("00000000-0000-4000-8000-000000000021").update({instructions:""});await assert.rejects(read("exercise-list",{afterId:null}));
 });
 it("denies owner/other author/client and foreign existence with identical errors",async()=>{
  const d=await write();await run(d);
  await db.doc("users/exercise_other").create({schemaVersion:1,accountStatus:"active",lifecycleRevision:1,emailNormalized:"exercise_other@example.invalid"});
  await db.doc(`workspaces/${wid}/memberships/exercise_other`).create({schemaVersion:1,workspaceId:wid,userId:"exercise_other",role:"trainer",status:"active",revision:1,catalogContributionActive:true});
  await db.doc(`workspaces/${wid}/trainerProfiles/exercise_other`).create({schemaVersion:1,workspaceId:wid,userId:"exercise_other"});await db.doc(`users/exercise_other/membershipRefs/${wid}`).create({schemaVersion:1,status:"active"});
  await db.doc(`workspaces/${wid}`).update({ownerUid:"exercise_other"});
  const list=await run({action:"exercise-list",workspaceId:wid,afterId:null},"exercise_other");assert.equal(list.items.length,0);
  for(const exerciseId of [id,"00000000-0000-4000-8000-000000000099"])await assert.rejects(run({action:"exercise-get",workspaceId:wid,exerciseId},"exercise_other"),/exercise-unavailable/);
  const foreign={...await write(),idempotencyKey:d.idempotencyKey+"foreign"};await assert.rejects(run(foreign,"exercise_other"),/exercise-unavailable/);
  await db.doc(`workspaces/${wid}/memberships/exercise_other`).update({role:"client"});await assert.rejects(run({action:"exercise-list",workspaceId:wid,afterId:null},"exercise_other"),/unauthorized/);
 });
 it("fails strict shapes, unknown fields, invalid bounds without any business effect",async()=>{
  const d=await write();for(const bad of [{...d,ownerUid:uid},{...d,workspaceId:"elsewhere"},{...d,exerciseWrite:{...d.exerciseWrite,expectedRevision:"0"}},{...d,exerciseWrite:{...d.exerciseWrite,equipment:["barbell","bodyweight"]}},{...d,exerciseWrite:{...d.exerciseWrite,instructions:"x".repeat(2001)}}])await assert.rejects(run(bad));
  assert.equal((await target.get()).exists,false);assert.equal((await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).exists,false);
 });
 it("current malformed authority and Auth loss deny all paths",async()=>{
  const d=await write();for(const [path,field,value] of [[`users/${uid}`,"accountStatus","disabled"],[`workspaces/${wid}`,"status","suspended"],[`workspaces/${wid}/memberships/${uid}`,"workspaceId","foreign"],[`workspaces/${wid}/trainerProfiles/${uid}`,"userId","foreign"],[`users/${uid}/membershipRefs/${wid}`,"status","revoked"]] as const) {
   const ref=db.doc(path),old=(await ref.get()).get(field);await ref.update({[field]:value});await assert.rejects(run(d));await ref.update({[field]:old});
  }
  await auth.updateUser(uid,{disabled:true});await assert.rejects(run(d));await assert.rejects(resolve(d));await auth.updateUser(uid,{disabled:false,emailVerified:false});await assert.rejects(run(d));
 });
 it("resolver-first fences the whole key across both kinds and changed payload",async()=>{
  const d=await write();assert.equal((await resolve(d)).outcome,"not-applied-fenced");await assert.rejects(run(d),/idempotency-key-reused/);
  for(const changed of [{...d,exerciseWrite:{...d.exerciseWrite,name:"Changed"}},{...d,action:"correct-custom-exercise",exerciseWrite:{...d.exerciseWrite,expectedRevision:1}}])await assert.rejects(run(changed));
  assert.equal((await target.get()).exists,false);
 });
 it("writer-first and held receipt-read contention yield one terminal decision",async()=>{
  const d=await write();let release!:()=>void,entered!:()=>void;const held=new Promise<void>(r=>release=r),ready=new Promise<void>(r=>entered=r);
  const writing=customExerciseOperation(db,uid,d,Date.now,async()=>{entered();await held});await ready;const resolving=resolve(d);release();await writing;assert.equal((await resolving).outcome,"applied");assert.equal((await target.get()).get("revision"),1);
 });
 for(const mode of ["membership","workspace","account"])it(`${mode} withdrawal serializes, restoration permits fresh reads but never rebinds old commands`,async()=>{
  await run(await write());const d=await write(1,"delayed"),w=(await db.doc(`workspaces/${wid}`).get()).get("membershipRevision");
  if(mode==="membership")await transitionMembership(db,{callerUid:"pr2_operator",idempotencyKey:"withdraw",workspaceId:wid,uid,role:"trainer",nextStatus:"revoked",expectedRevision:1,expectedWorkspaceRevision:w,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
  else if(mode==="workspace")await transitionWorkspace(db,{callerUid:"pr2_operator",idempotencyKey:"suspend",workspaceId:wid,nextStatus:"suspended",expectedMembershipRevision:w,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5,maxWrites:20});
  else await transitionAccountLifecycle(db,{callerUid:"pr2_operator",idempotencyKey:"disable",uid,enabled:false,expectedLifecycleRevision:1,maxMembershipsPerAccount:2});
  await assert.rejects(run(d));await assert.rejects(read("exercise-get",{exerciseId:id}));
  if(mode!=="account")assert.equal((await resolve(d)).outcome,"not-applied-fenced");else await assert.rejects(resolve(d));
  if(mode==="membership")await transitionMembership(db,{callerUid:"pr2_operator",idempotencyKey:"restore",workspaceId:wid,uid,role:"trainer",nextStatus:"active",expectedRevision:2,expectedWorkspaceRevision:w+1,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
  else if(mode==="workspace")await transitionWorkspace(db,{callerUid:"pr2_operator",idempotencyKey:"restore",workspaceId:wid,nextStatus:"active",expectedMembershipRevision:w+1,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5,maxWrites:20});
  else await transitionAccountLifecycle(db,{callerUid:"pr2_operator",idempotencyKey:"enable",uid,enabled:true,expectedLifecycleRevision:2,maxMembershipsPerAccount:2});
  assert.equal((await read("exercise-get",{exerciseId:id})).exercise.revision,1);await assert.rejects(run(d));await run(await write(1,"fresh"));assert.equal((await target.get()).get("revision"),2);
 });
 for(const revision of [0,1])for(const change of ["account","workspace","membership"])it(`${change} races revision-${revision} execution with no partial effects`,async()=>{
  if(revision===1)await run(await write());const d=await write(revision,"race"),w=(await db.doc(`workspaces/${wid}`).get()).get("membershipRevision");
  const lifecycle=()=>change==="account" ? transitionAccountLifecycle(db,{callerUid:"pr2_operator",idempotencyKey:"race-disable",uid,enabled:false,expectedLifecycleRevision:1,maxMembershipsPerAccount:2}) : change==="workspace" ?
   transitionWorkspace(db,{callerUid:"pr2_operator",idempotencyKey:"race-suspend",workspaceId:wid,nextStatus:"suspended",expectedMembershipRevision:w,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5,maxWrites:20}) :
   transitionMembership(db,{callerUid:"pr2_operator",idempotencyKey:"race-revoke",workspaceId:wid,uid,role:"trainer",nextStatus:"revoked",expectedRevision:1,expectedWorkspaceRevision:w,maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
  const results=await Promise.allSettled([run(d),lifecycle()]);assert.equal(results[1]!.status,"fulfilled");await assert.rejects(read());
  const applied=results[0]!.status==="fulfilled";assert.equal((await target.get()).get("revision"),applied?revision+1:revision===0?undefined:revision);
  const receipt=(await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).exists;assert.equal(receipt,applied);
  if(change!=="account")assert.equal((await resolve(d)).outcome,applied?"applied":"not-applied-fenced");else await assert.rejects(resolve(d));
 });
 it("exact callable bearer verification rejects raw author overrides",async()=>{
  const token=await auth.createCustomToken(uid);const response=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fixture`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({token,returnSecureToken:true})});const bearer=(await response.json() as any).idToken;
  const invoke=(data:unknown,credential=bearer)=>fetch(`http://127.0.0.1:5001/${SPIKE_PROJECT_ID}/europe-west1/customExercisesEmulator`,{method:"POST",headers:{"Content-Type":"application/json",Authorization:`Bearer ${credential}`},body:JSON.stringify({data})});
  assert.equal((await invoke({action:"exercise-prepare",workspaceId:wid})).status,200);
  assert.notEqual((await invoke(await write(),"forged")).status,200);assert.notEqual((await invoke({...await write(),ownerUid:uid})).status,200);
 });
 it("a conflict does not rewrite content or depend on advancing its timestamp",async()=>{
  const d=await write();await run(d);await target.update({updatedAt:Timestamp.fromMillis(Date.now()+60000)});const prior=(await target.get()).data();
  const collision={...d,idempotencyKey:d.idempotencyKey+"collision"};assert.equal((await run(collision)).status,"exercise-conflict");assert.deepEqual((await target.get()).data(),prior);
  await assert.rejects(run(await write(1,"clock-regression")));assert.deepEqual((await target.get()).data(),prior);
 });
 it("unsupported admission records fail closed without silent replacement",async()=>{
  const d=await write(),ref=db.doc(`customExerciseRequests/${stableHash(uid)}`);
  for(const value of [{minute:"damaged",count:1},{minute:0,count:121},{minute:0,count:1,extra:true}]) {
   await ref.set(value);await assert.rejects(run(d),/rate-limited/);assert.deepEqual((await ref.get()).data(),value);
  }
  assert.equal((await target.get()).exists,false);
 });
 it("exact replay respects the final work deadline without rewriting its immutable decision",async()=>{
  const d=await write();await run(d);const prior=(await target.get()).data(),ref=db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`),receipt=(await ref.get()).data();
  let ticks=0;await assert.rejects(customExerciseOperation(db,uid,d,()=>ticks++<3?0:5001),/exercise-unavailable/);
  assert.deepEqual((await target.get()).data(),prior);assert.deepEqual((await ref.get()).data(),receipt);assert.equal((await run(d)).replayed,true);
 });
 it("budget, deadline and absent history errors never create effects",async()=>{
  const d=await write();await db.doc(`customExerciseRequests/${stableHash(uid)}`).set({minute:Math.floor(Date.now()/60000),count:120});await assert.rejects(run(d),/rate-limited/);
  await db.doc(`customExerciseRequests/${stableHash(uid)}`).delete();let ticks=0;await assert.rejects(customExerciseOperation(db,uid,d,()=>ticks++<2?0:5001));
  await db.doc(RESOLUTION_CONFIG_PATH).update({historyVerified:false});await assert.rejects(run(d));await assert.rejects(resolve(d));assert.equal((await target.get()).exists,false);
 });
});
