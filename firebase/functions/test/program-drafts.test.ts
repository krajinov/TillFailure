import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { getAuth } from 'firebase-admin/auth';
import { emulatorFirestore, SPIKE_PROJECT_ID } from '../src/environment.js';
import { programDraftOperation, sourceIdentity } from '../src/program-drafts.js';
import { provisionTrainer } from '../src/provisioning.js';
import { RESOLUTION_CONFIG_PATH, RESOLUTION_GENERATION } from '../src/command-identity.js';
import { resolveOwnCommand } from '../src/command-resolution.js';
const db=emulatorFirestore(),auth=getAuth(),uid='exercise_author',wid='exercise_workspace',id='00000000-0000-4000-8000-000000000001';
const read=(action='draft-prepare',extra={})=>programDraftOperation(db,uid,{action,workspaceId:wid,...extra});
const run=(d:any)=>programDraftOperation(db,uid,d);
const resolve=(d:any)=>resolveOwnCommand(db,uid,{protocolVersion:1,descriptor:d});
async function command(revision=0,key='save') {return {action:'save-program-draft',workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-${key}`,draftSave:{templateId:id,expectedRevision:revision,authority:(await read()).authority,title:'Synthetic',description:'',workouts:[] as any[]}};}
describe('bounded program drafts',()=>{
beforeEach(async()=>{
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`,{method:"DELETE"});
  for(const u of [uid,"exercise_other"]){await auth.deleteUser(u).catch(()=>{});await auth.createUser({uid:u,email:`${u}@example.invalid`,emailVerified:true});}
  process.env.TF_RESOLUTION_PROVENANCE_ID="exercises-intact";
  await db.doc(RESOLUTION_CONFIG_PATH).create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:"exercises-intact",historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:30});
  await provisionTrainer(db,{uid,workspaceId:wid,workspaceName:"Synthetic exercises",idempotencyKey:"bootstrap",expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
   {uid,email:`${uid}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
 });
 it('atomic root replacement, exact replay, concurrent conflicts and resolution',async()=>{
  const a=await command(),b={...a,idempotencyKey:a.idempotencyKey+'b'};
  const results=await Promise.all([run(a),run(b)]);
  assert.deepEqual(results.map(r=>r.status).sort(),['draft-conflict','draft-saved']);
  for(const [i,d] of [a,b].entries()) {assert.equal((await run(d)).status,results[i]!.status);assert.equal((await resolve(d)).outcome,results[i]!.status==='draft-saved'?'applied':'not-applied-conflict');}
  assert.equal((await read('draft-get',{templateId:id})).draft.revision,1);
  const c=await command(1,'edit');c.draftSave.workouts=[{id,title:'',items:[]}];await run(c);
  const removed=await command(2,'remove');await run(removed);
  assert.deepEqual((await read('draft-get',{templateId:id})).draft.workouts,[]);
 });
 it('resolver-first fences prevent delayed Save and changed payload',async()=>{
  const d=await command();assert.equal((await resolve(d)).outcome,'not-applied-fenced');await assert.rejects(run(d),/idempotency-key-reused/);
 });
 it('new source selections are transactional and saved copies survive correction/removal',async()=>{
  const ref=db.doc('systemExercises/synthetic');
  const source={schemaVersion:1,name:'Synthetic exercise',nameNormalized:'synthetic exercise',muscleGroups:[],equipment:[],instructions:'Original synthetic text.',status:'published'};
  await ref.create(source);const selected=sourceIdentity('system','synthetic',wid,uid,source)!;assert(selected);
  const d=await command();d.draftSave.workouts=[{id,title:'',items:[{id,...selected,prescription:{sets:null,reps:null,restSeconds:0,loadGrams:null}}]}];
  await ref.update({name:'Changed'});assert.equal((await run(d)).status,'draft-source-conflict');assert.equal((await resolve(d)).outcome,'not-applied-conflict');
  await ref.set(source);const fresh=await command(0,'fresh');fresh.draftSave.workouts=d.draftSave.workouts;assert.equal((await run(fresh)).status,'draft-saved');
  await ref.delete();const retained=await command(1,'retained');retained.draftSave.workouts=d.draftSave.workouts;assert.equal((await run(retained)).status,'draft-saved');
  const duplicate=await command(2,'duplicate');duplicate.draftSave.workouts=[{id,title:'',items:[{...d.draftSave.workouts[0].items[0],id:'00000000-0000-4000-8000-000000000002'}]}];assert.equal((await run(duplicate)).status,'draft-source-conflict');
 });
 it('withdrawal denies execution but permits minimal own resolution',async()=>{
  const d=await command();await db.doc(`workspaces/${wid}`).update({status:'suspended',membershipRevision:2});
  await assert.rejects(run(d),/unauthorized/);assert.equal((await resolve(d)).outcome,'not-applied-fenced');
 });
 it('foreign custom source is indistinguishable from missing selection and grants no content',async()=>{
  const d=await command();const selection={id,sourceKind:'custom',sourceId:id,sourceRevision:1,sourceHash:'a'.repeat(64),name:'Synthetic',prescription:{sets:null,reps:null,restSeconds:null,loadGrams:null}};
  d.draftSave.workouts=[{id,title:'',items:[selection]}];
  const first=await run(d);assert.equal(first.status,'draft-source-conflict');
  const ref=db.doc(`workspaces/${wid}/exercises/${id}`);
  const {Timestamp}=await import('firebase-admin/firestore');
  await ref.create({schemaVersion:1,policyVersion:1,workspaceId:wid,exerciseId:id,ownerUid:'exercise_other',name:'Foreign',instructions:'Synthetic foreign content.',muscleGroups:[],equipment:[],revision:1,createdAt:Timestamp.now(),updatedAt:Timestamp.now()});
  const second=await run({...d,idempotencyKey:d.idempotencyKey+'foreign'});assert.deepEqual({...second,replayed:false},{...first,replayed:false});
  assert.equal(JSON.stringify(second).includes('Foreign'),false);
 });
 it('commit-first own resolution and later authority loss preserve one exact decision',async()=>{
  const d=await command();let entered!:()=>void,release!:()=>void;
  const ready=new Promise<void>(r=>entered=r),held=new Promise<void>(r=>release=r);
  const saving=programDraftOperation(db,uid,d,Date.now,async()=>{entered();await held});
  await ready;const resolving=resolve(d);release();assert.equal((await saving).status,'draft-saved');assert.equal((await resolving).outcome,'applied');
  await db.doc(`workspaces/${wid}`).update({status:'suspended',membershipRevision:2});
  await assert.rejects(run(d),/unauthorized/);assert.equal((await resolve(d)).outcome,'applied');
 });
 it('source and lifecycle transactions serialize on both sides of Save',async()=>{
  const sourceRef=db.doc('systemExercises/ordered');
  const source={schemaVersion:1,name:'Synthetic ordered',nameNormalized:'synthetic ordered',muscleGroups:[],equipment:[],instructions:'Synthetic.',status:'published'};
  await sourceRef.create(source);
  const make=async(revision:number,key:string)=>{const d=await command(revision,key);d.draftSave.workouts=[{id,title:'',items:[{id,...sourceIdentity('system','ordered',wid,uid,source)!,prescription:{sets:null,reps:null,restSeconds:null,loadGrams:null}}]}];return d;};
  const first=await make(0,'source-save-first');
  let entered!:()=>void,release!:()=>void;
  const ready=new Promise<void>(r=>entered=r),held=new Promise<void>(r=>release=r);
  const saving=programDraftOperation(db,uid,first,Date.now,async()=>{entered();await held});
  await ready;const correction=sourceRef.update({name:'Corrected synthetic'});release();
  assert.equal((await saving).status,'draft-saved');await correction;
  // A new item ID cannot use the retained-copy exception after the correction.
  const changed=await make(1,'source-first');changed.draftSave.workouts[0].items[0].id='00000000-0000-4000-8000-000000000002';
  let sourceLocked!:()=>void,releaseSource!:()=>void;
  const sourceReady=new Promise<void>(r=>sourceLocked=r),sourceHeld=new Promise<void>(r=>releaseSource=r);
  const correcting=db.runTransaction(async tx=>{await tx.get(sourceRef);tx.update(sourceRef,{name:'Corrected again'});sourceLocked();await sourceHeld;});
  await sourceReady;const waitingSave=run(changed);releaseSource();await correcting;
  assert.equal((await waitingSave).status,'draft-source-conflict');
  const current=await command(1,'lifecycle-save-first');
  const lifecycle=db.doc(`workspaces/${wid}`);
  let arrived!:()=>void,finish!:()=>void;
  const atWrite=new Promise<void>(r=>arrived=r),continueWrite=new Promise<void>(r=>finish=r);
  const writing=programDraftOperation(db,uid,current,Date.now,async()=>{arrived();await continueWrite});
  await atWrite;const suspension=lifecycle.update({status:'suspended',membershipRevision:2});finish();
  assert.equal((await writing).status,'draft-saved');await suspension;
  let lifecycleLocked!:()=>void,releaseLifecycle!:()=>void;
  const lifecycleReady=new Promise<void>(r=>lifecycleLocked=r),lifecycleHeld=new Promise<void>(r=>releaseLifecycle=r);
  const suspending=db.runTransaction(async tx=>{await tx.get(lifecycle);tx.update(lifecycle,{membershipRevision:3});lifecycleLocked();await lifecycleHeld;});
  await lifecycleReady;const denied=assert.rejects(run({...current,idempotencyKey:current.idempotencyKey+'after'}),/unauthorized/);releaseLifecycle();await suspending;await denied;
  assert.equal((await resolve(current)).outcome,'applied');
  await lifecycle.update({status:'active',membershipRevision:4});
  await assert.rejects(run({...current,idempotencyKey:current.idempotencyKey+'restored'}),/unauthorized/);
  const restored=await command(2,'restored-fresh');assert.equal((await run(restored)).status,'draft-saved');
 });
 it('measures the actual Admin SDK boundary commit protobuf without logging content',async()=>{
  const d=await command(0,'wire-boundary');d.draftSave.title='😀'.repeat(80);d.draftSave.description='😀'.repeat(240);
  for(let w=0;w<3;w++) {
   const items=[];
   for(let i=0;i<4;i++) {
    const index=w*4+i,sourceId='z'.repeat(126)+String(index).padStart(2,'0');
    const name='😀'.repeat(index===0?120:index===1?80:1);
    const source={schemaVersion:1,name,nameNormalized:name,muscleGroups:[],equipment:[],instructions:'Synthetic.',status:'published'};
    await db.doc(`systemExercises/${sourceId}`).create(source);
    items.push({id:'00000000-0000-4000-8000-'+String(index+10).padStart(12,'0'),...sourceIdentity('system',sourceId,wid,uid,source)!,prescription:{sets:null,reps:1000,restSeconds:3600,loadGrams:1000000}});
   }
   d.draftSave.workouts.push({id:'00000000-0000-4000-8000-'+String(w+2).padStart(12,'0'),title:'😀'.repeat(40),items});
  }
  // Test-only interception of the actual request after Admin serialization, before gRPC transport.
  const proto=require('@google-cloud/firestore').protos.google.firestore.v1.CommitRequest;
  const native=db as any,original=native.request;let measured=0;
  native.request=function(method:string,request:any,...rest:any[]) {
   if(method==='commit' && request.writes.some((v:any)=>v.update?.name.includes('/programTemplates/'))) {
    measured=proto.encode(proto.fromObject(request)).finish().length;
    assert.equal(request.writes.length,2);assert(measured<65536);
   }
   return original.call(this,method,request,...rest);
  };
  try {assert.equal((await run(d)).status,'draft-saved');assert(measured>0);console.log(`DRAFT_ADMIN_COMMIT bytes=${measured} writes=2 uniqueSources=12`);}
  finally {native.request=original;}
 });
});
