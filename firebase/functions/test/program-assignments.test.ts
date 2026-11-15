import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { beforeEach, describe, it } from 'node:test';
import { getAuth } from 'firebase-admin/auth';
import { clientRevocationOperation } from '../src/client-revocation.js';
import { transitionAccountLifecycle } from '../src/catalog.js';
import { Timestamp } from 'firebase-admin/firestore';
import { readFile } from 'node:fs/promises';
import { emulatorFirestore,SPIKE_PROJECT_ID } from '../src/environment.js';
import { programAssignmentOperation as operation, assignmentDocumentDto } from '../src/program-assignments.js';
import { resolveOwnCommand, resolutionBinding } from '../src/command-resolution.js';
import { assignmentBinding, materializeAssignment, validateClientAssignmentDocuments } from '../src/assignment-feasibility-contract.js';
import { publishAssignment, closeAssignment } from '../src/assigned-program.js';
import { createAssignmentFixture,attestIndependentAssignmentFixture } from './assignment-pr1b-fixture.js';
const db=emulatorFirestore(),auth=getAuth();
let f:Awaited<ReturnType<typeof createAssignmentFixture>>;
const run=(d:any=f.descriptor,clock?:()=>number,barrier?:()=>Promise<void>)=>operation(db,f.uid,d,clock,barrier);
const resolve=(d:any=f.descriptor)=>resolveOwnCommand(db,f.uid,{protocolVersion:1,descriptor:d});
const source=()=>db.doc(`workspaces/${f.wid}/programTemplates/${f.version.templateId}/versions/${f.version.versionId}`);
const base=()=>`users/${f.client}/workspaces/${f.wid}/assignedPrograms/${f.descriptor.assignment.assignmentId}`;
const fresh=(action:string,extra:any={})=>run({action,workspaceId:f.wid,clientUid:f.client,...extra});
async function assertFull(d:any=f.descriptor) {
  const expected=materializeAssignment(d,f.version,f.uid),actual=new Map<string,any>();
  for(const [p]of expected) {const s=await db.doc(p).get();assert.equal(s.exists,true,p);if(p.startsWith('users/'))actual.set(p,assignmentDocumentDto(s.data()!));}
  assert.ok(validateClientAssignmentDocuments(f.client,f.wid,d.assignment.assignmentId,actual));
  assert.equal((await db.doc(`lifecycleCommands/${assignmentBinding(f.uid,d).id}`).get()).get('result.status'),'assignment-created');
}
describe('M7 PR1b installed online assignment',()=>{
 beforeEach(async context=>{
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`,{method:'DELETE'});
  for(const uid of ['A','B','C'])await auth.deleteUser(uid).catch(()=>{});
  f=await createAssignmentFixture(db,'A','B','w',context.name.startsWith('maximum')?12:1,context.name.startsWith('maximum'));await attestIndependentAssignmentFixture(db,'m7-pr1b-reset');
 });
 it('prepares exact source and complete child preview; atomic write/replay/resolver preserve every byte',async()=>{
  const p=await fresh('assignment-prepare',{templateId:f.version.templateId,versionId:f.version.versionId,scheduledInstantMillis:Date.now()+3600000});
  const item=await fresh('assignment-preview-item',{assignment:p.assignment,itemId:p.preview.workouts[0].itemIds[0]});assert.deepEqual(item.item,f.version.workouts[0]!.items[0]);
  assert.equal(p.assignment.snapshotHash,f.descriptor.assignment.snapshotHash);
  const d={...f.descriptor,assignment:p.assignment};assert.deepEqual(resolutionBinding(f.uid,{protocolVersion:1,descriptor:d}).hash,assignmentBinding(f.uid,d).hash);
  assert.equal((await run(d)).status,'assignment-created');await assertFull(d);
  const revision=(await db.doc(`workspaces/${f.wid}`).get()).get('membershipRevision');
  assert.equal((await run(d)).replayed,true);assert.equal((await resolve(d)).outcome,'applied');assert.equal((await db.doc(`workspaces/${f.wid}`).get()).get('membershipRevision'),revision);
  const listed=await fresh('assignment-list',{after:null});assert.equal(listed.items.length,1);
  const detail=await fresh('assignment-get',{assignmentId:d.assignment.assignmentId});assert.equal(detail.inventory.requiredPaths.length,4);
  const copied=new Map<string,any>(),root=`users/${f.client}/workspaces/${f.wid}/assignedPrograms/${d.assignment.assignmentId}`;
  for(const path of ['', 'manifests/download',...detail.inventory.requiredPaths]) {
    const read=await fresh('assignment-document',{assignmentId:d.assignment.assignmentId,relativePath:path});
    copied.set(path?`${root}/${path}`:root,read.document);
  }
  assert.deepEqual(copied.get(root),detail.header);
  assert.ok(validateClientAssignmentDocuments(f.client,f.wid,d.assignment.assignmentId,copied));
 });
 it('resolver-first fence refuses delayed writer; original own proof remains content-free after authority loss',async()=>{
  assert.equal((await resolve()).outcome,'not-applied-fenced');await assert.rejects(run(),/outcome-unavailable/);
  assert.equal((await db.doc(base()).get()).exists,false);
  await db.doc('workspaces/w/memberships/A').update({status:'revoked'});
  const r=await resolve();assert.equal(r.outcome,'not-applied-fenced');assert.equal(Object.keys(r).length,7);
 });
 it('installed legacy writers reject reserved keys before replay or path effects and retain historical non-product replay',async()=>{
  const old={callerUid:'A',idempotencyKey:f.descriptor.idempotencyKey,uid:'B',workspaceId:'w',trainerId:'A',assignmentId:'old',sourceTemplateId:'t',sourceVersionId:'v',sourceVersionNumber:1,sourceContentHash:'s',snapshotHash:'s',accessExpiresAtMillis:Date.now()+100000,workouts:[],plans:[],maxWrites:20};
  await assert.rejects(publishAssignment(db,old),/legacy-product-key-reserved/);
  await assert.rejects(closeAssignment(db,{callerUid:'A',idempotencyKey:old.idempotencyKey,uid:'B',workspaceId:'w',assignmentId:'old',status:'cancelled',expectedRevision:1}),/legacy-product-key-reserved/);
  const historical={...old,idempotencyKey:'non-product-original'};await publishAssignment(db,historical);assert.equal((await publishAssignment(db,historical)).replayed,true);
 });
 it('legacy occupancy, dual namespace, corrupt receipts and fake boolean coverage fail closed without mutation',async()=>{
  const id=assignmentBinding(f.uid,f.descriptor).id,old=db.doc(`assignmentCommands/${id}`),r=db.doc(`lifecycleCommands/${id}`);
  for(const data of [{corrupt:true},{schemaVersion:1,commandKind:'publish-assignment'}]) {await old.set(data);await assert.rejects(run());await assert.rejects(resolve());assert.deepEqual((await old.get()).data(),data);}
  await r.create({corrupt:true});await assert.rejects(resolve());await assert.rejects(run());await old.delete();await assert.rejects(resolve());await assert.rejects(run());
  await r.delete();await db.doc('commandResolutionCoverage/m7-pr1b-reset').delete();await assert.rejects(resolve(),/outcome-unavailable/);await assert.rejects(run(),/outcome-unavailable/);
  assert.equal((await db.doc(base()).get()).exists,false);
 });
 it('original version is independent of latest/source/draft edits; strict source/schema rejects private data',async()=>{
  await db.doc(`workspaces/w/programTemplates/${f.version.templateId}`).create({currentVersionId:'v0000000002',title:'Later'});
  await source().parent.doc('v0000000002').create({privateNote:'Later'});await db.doc('systemExercises/irrelevant').create({instructions:'Later'});
  await run();await assertFull();
  await source().update({privateNote:'must never copy'});await assert.rejects(fresh('assignment-prepare',{templateId:f.version.templateId,versionId:f.version.versionId,scheduledInstantMillis:Date.now()+100000}));
 });
 it('exact typed source/copy/schedule conflicts are durable; changed key binding never replays',async()=>{
  for(const [field,value,status]of [['sourceContentHash','a'.repeat(64),'assignment-source-conflict'],['snapshotHash','b'.repeat(64),'assignment-copy-conflict']] as const) {
    const d={...f.descriptor,idempotencyKey:f.descriptor.idempotencyKey+field,assignment:{...f.descriptor.assignment,[field]:value}};
    assert.equal((await run(d)).status,status);assert.equal((await run(d)).replayed,true);assert.equal((await resolve(d)).outcome,'not-applied-conflict');
  }
  const d=f.descriptor;assert.equal((await run(d,()=>d.assignment.scheduledInstantMillis)).status,'assignment-schedule-conflict');assert.equal((await resolve()).outcome,'not-applied-conflict');
  await assert.rejects(run({...d,assignment:{...d.assignment,snapshotHash:'c'.repeat(64)}}));assert.equal((await db.doc(base()).get()).exists,false);
 });
 it('same-key concurrency has one effect; newly confirmed repetitions are independent; stale concurrent tuple refuses',async()=>{
  const r=await Promise.all([run(),run()]);assert.equal(r.filter(x=>!x.replayed).length,1);await assertFull();
  const p=await fresh('assignment-prepare',{templateId:f.version.templateId,versionId:f.version.versionId,scheduledInstantMillis:Date.now()+3600000});
  const d={...f.descriptor,idempotencyKey:f.descriptor.idempotencyKey+'repeat',assignment:p.assignment};await run(d);assert.equal((await db.collection('users/B/workspaces/w/assignedPrograms').get()).size,2);
  await assert.rejects(run({...f.descriptor,idempotencyKey:f.descriptor.idempotencyKey+'stale'}),/unauthorized/);
 });
 it('every artifact uses create preconditions, including orphan children; abort leaves no business effect or decision',async()=>{
  const child=base()+`/snapshots/content/workouts/${f.version.workouts[0]!.id}`;await db.doc(child).create({corrupt:true});await assert.rejects(run());
  assert.equal((await db.doc(base()).get()).exists,false);assert.equal((await db.doc(`lifecycleCommands/${assignmentBinding(f.uid,f.descriptor).id}`).get()).exists,false);assert.equal((await db.doc('workspaces/w').get()).get('membershipRevision'),1);
  await db.doc(child).delete();await assert.rejects(run(f.descriptor,undefined,async()=>{throw new Error('precommit-failure');}));assert.equal((await db.doc(base()).get()).exists,false);
 });
 it('current trainer/client/Auth/onboarding/tenant authority denies every read and replay after loss; own resolver uses only original identity',async()=>{
  await run();await auth.updateUser('B',{disabled:true});await assert.rejects(run());await assert.rejects(fresh('assignment-list',{after:null}));await auth.updateUser('B',{disabled:false});
  await db.doc('workspaces/w/clientProfiles/B').update({trainerId:'C'});await assert.rejects(run());await assert.rejects(fresh('assignment-get',{assignmentId:f.descriptor.assignment.assignmentId}));assert.equal((await resolve()).outcome,'applied');
  await auth.updateUser('A',{disabled:true});await assert.rejects(resolve(),/identity-required/);
 });
 it('actual expiry query and configured composite index agree; detail refuses exact expiry',async()=>{
  await run();const expires=f.descriptor.assignment.accessExpiresAtMillis;
  const r=await operation(db,'A',{action:'assignment-list',workspaceId:'w',clientUid:'B',after:null},()=>expires);assert.equal(r.items.length,0);
  await assert.rejects(operation(db,'A',{action:'assignment-get',workspaceId:'w',clientUid:'B',assignmentId:f.descriptor.assignment.assignmentId},()=>expires));
  const indexes=JSON.parse(await readFile('../firestore.indexes.json','utf8'));assert.ok(indexes.indexes.some((i:any)=>i.collectionGroup==='assignedPrograms' && i.fields.map((x:any)=>x.fieldPath).join()==='trainerId,accessStatus,accessExpiresAt,__name__'));
 });
 it('trainer detail and final header carry server time and refuse expiry during the request',async()=>{
  await run();const now=f.descriptor.assignment.scheduledInstantMillis+1000,expires=f.descriptor.assignment.accessExpiresAtMillis;
  for(const request of [{action:'assignment-get'},...['','snapshots/content'].map(relativePath=>({action:'assignment-document',relativePath}))]) {
    const data={...request,workspaceId:f.wid,clientUid:f.client,assignmentId:f.descriptor.assignment.assignmentId};
    const result=await operation(db,f.uid,data,()=>now);assert.equal(result.serverNowMillis,now);
    assert.ok(Number.isSafeInteger(result.serverNowMillis) && result.serverNowMillis<expires);
    let ticks=0;await assert.rejects(operation(db,f.uid,data,()=>expires-4+ticks++));
  }
  await db.doc('workspaces/w/clientProfiles/B').update({trainerId:'C'});
  await assert.rejects(fresh('assignment-document',{assignmentId:f.descriptor.assignment.assignmentId,relativePath:''}),/unauthorized/);
 });

 it('actual membership revocation and Assign serialize in both orderings and under concurrent requests',async()=>{
  const prepared=await clientRevocationOperation(db,'A',{action:'prepare-client-revocation',workspaceId:'w',clientUid:'B'});
  const revoke={action:'revoke-client',workspaceId:'w',idempotencyKey:f.descriptor.idempotencyKey+'revoke',revocation:prepared.revocationPreparation};
  const pair=await Promise.allSettled([run(),clientRevocationOperation(db,'A',revoke)]);
  assert.equal(pair.filter(x=>x.status==='fulfilled').length,1);
  const assigned=(await db.doc(base()).get()).exists;
  if(assigned){await assertFull();await assert.rejects(clientRevocationOperation(db,'A',revoke),/assignment-artifacts/);}
  else {assert.equal((await db.doc('workspaces/w/memberships/B').get()).get('status'),'revoked');await assert.rejects(run(),/unauthorized/);}
 });
 it('assignment-first revocation refuses all retained artifacts; revocation-first refuses assignment without consuming its key',async()=>{
  const prepared=await clientRevocationOperation(db,'A',{action:'prepare-client-revocation',workspaceId:'w',clientUid:'B'});
  const revoke={action:'revoke-client',workspaceId:'w',idempotencyKey:f.descriptor.idempotencyKey+'revoke',revocation:prepared.revocationPreparation};
  await clientRevocationOperation(db,'A',revoke);await assert.rejects(run(),/unauthorized/);
  assert.equal((await db.doc(`lifecycleCommands/${assignmentBinding(f.uid,f.descriptor).id}`).get()).exists,false);
 });
 it('global lifecycle loss after commit retains the copy while denying current reads and business replay',async()=>{
  await run();await transitionAccountLifecycle(db,{callerUid:'synthetic-operator',idempotencyKey:'disable-A',uid:'A',enabled:false,expectedLifecycleRevision:1,maxMembershipsPerAccount:20});
  await assert.rejects(run(),/unauthorized/);await assert.rejects(resolve(),/identity-required/);await assertFull();
 });
 it('writer-held versus resolver and second Assign transactions serialize on immutable receipt and workspace fence',async()=>{
  let entered!:()=>void,release!:()=>void;const ready=new Promise<void>(r=>entered=r),held=new Promise<void>(r=>release=r);
  const writing=run(f.descriptor,undefined,async()=>{entered();await held;});await ready;
  const resolving=resolve();release();const values=await Promise.all([writing,resolving]);assert.equal(values[0]!.status,'assignment-created');assert.equal(values[1]!.outcome,'applied');await assertFull();
 });

 it('assignment-first revocation refuses retained copy; occupied foreign/corrupt parent/index cannot consume another decision',async()=>{
  const prepared=await clientRevocationOperation(db,'A',{action:'prepare-client-revocation',workspaceId:'w',clientUid:'B'});
  await run();await assertFull();
  await assert.rejects(clientRevocationOperation(db,'A',{action:'revoke-client',workspaceId:'w',idempotencyKey:f.descriptor.idempotencyKey+'revoke',revocation:prepared.revocationPreparation}),/assignment-artifacts/);
  const p=await fresh('assignment-prepare',{templateId:f.version.templateId,versionId:f.version.versionId,scheduledInstantMillis:Date.now()+3600000});
  const duplicate={...f.descriptor,idempotencyKey:f.descriptor.idempotencyKey+'id',assignment:{...p.assignment,assignmentId:f.descriptor.assignment.assignmentId}};
  assert.equal((await run(duplicate)).status,'assignment-id-conflict');
  await db.doc(`workspaces/w/assignedPrograms/${f.descriptor.assignment.assignmentId}`).update({clientId:'C'});
  const damaged={...duplicate,idempotencyKey:duplicate.idempotencyKey+'damaged'};await assert.rejects(run(damaged),/assignment-integrity-unavailable/);
  assert.equal((await db.doc(`lifecycleCommands/${assignmentBinding('A',damaged).id}`).get()).exists,false);
 });
 it('own terminal outcome remains available while new issuance and absence fencing are disabled',async()=>{
  await run();await db.doc('commandResolutionConfig/current').update({executionEnabled:false,absenceEnabled:false});
  assert.equal((await resolve()).outcome,'applied');await assert.rejects(run(),/outcome-unavailable/);
  await assert.rejects(resolve({...f.descriptor,idempotencyKey:f.descriptor.idempotencyKey+'new'}),/outcome-unavailable/);
 });
 it('fresh authenticated callable refuses caller selectors and revoked tokens; eligible client time grants no trainer source access',async()=>{
  const password=randomBytes(24).toString('base64url');await auth.updateUser('A',{password});
  const signed=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-key`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:'m7-pr1b-reset-A@example.invalid',password,returnSecureToken:true})});
  const identity=await signed.json() as any;assert.ok(identity.idToken);
  const endpoint=`http://127.0.0.1:${process.env.TF_FUNCTIONS_EMULATOR_PORT??5001}/${SPIKE_PROJECT_ID}/europe-west1/programAssignmentsEmulator`;
  const data={action:'assignment-list',workspaceId:'w',clientUid:'B',after:null};
  const invoke=(body:any,token=identity.idToken)=>fetch(endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${token}`},body:JSON.stringify({data:body})});
  assert.equal((await invoke(data)).status,200);assert.notEqual((await invoke({...data,callerUid:'C'})).status,200);assert.notEqual((await invoke(data,'invalid')).status,200);
  await auth.updateUser('A',{disabled:true});assert.notEqual((await invoke(data)).status,200);
 });

 it('maximum actual writer intercepts the atomic 20-write commit and bounded reads without aggregate preview transport',async()=>{
  const native=db as any,original=native.request,originalStream=native.requestStream;let writes=0,commitBytes=0,reads=0;
  const proto=require('@google-cloud/firestore').protos.google.firestore.v1.CommitRequest;
  native.requestStream=function(method:string,bidirectional:boolean,request:any,...args:any[]){if(method==='batchGetDocuments')reads+=request.documents.length;return originalStream.call(this,method,bidirectional,request,...args);};
  native.request=function(method:string,request:any,...args:any[]) {
    if(method==='commit' && request.writes.some((x:any)=>x.update?.name.includes('/snapshots/content'))){writes=request.writes.length;commitBytes=proto.encode(proto.fromObject(request)).finish().length;}
    return original.call(this,method,request,...args);
  };
  try{await run();}finally{native.request=original;native.requestStream=originalStream;}
  assert.equal(f.version.workouts[0].items.length,12);assert.equal(writes,20);assert.ok(commitBytes<=524288);assert.equal(reads,17);
  await assertFull();console.log(`M7P1B_ACTUAL writes=${writes} commitBytes=${commitBytes} observedDocumentReadsIncludingAdmission=${reads} sourceBytes=${Buffer.byteLength(JSON.stringify(f.version),'utf8')}`);
  if(process.env.TF_M7_PR1A_EVIDENCE)await (await import('node:fs/promises')).writeFile(`${process.env.TF_M7_PR1A_EVIDENCE}/actual-pr1b-transaction.json`,JSON.stringify({writes,commitBytes,observedDocumentReadsIncludingAdmission:reads,sourceBytes:Buffer.byteLength(JSON.stringify(f.version),'utf8')}));
 });
 it('unknown retained assignment artifacts prevent coverage even when receipt namespaces are empty',async()=>{
  await db.doc('commandResolutionConfig/current').delete();await db.doc('commandResolutionCoverage/m7-pr1b-reset').delete();
  await db.doc('users/B/workspaces/w/assignedPrograms/unknown/snapshots/content/workouts/orphan/exercises/private').create({unknown:true});
  await assert.rejects(attestIndependentAssignmentFixture(db,'must-remain-locked'),/fixture-history-not-independent-empty/);
  assert.equal((await db.doc('commandResolutionCoverage/must-remain-locked').get()).exists,false);
 });
 it('finite request, malformed clock, strict cursor and bounded rate admission refuse without consuming command',async()=>{
  await assert.rejects(run({...f.descriptor,extra:true}));await assert.rejects(fresh('assignment-list',{after:{assignmentId:'bad',accessExpiresAtMillis:1}}));
  await assert.rejects(run(f.descriptor,()=>-1));
  await db.doc(`programAssignmentRequests/${(await import('../src/hashing.js')).stableHash('A')}`).set({minute:Math.floor(Date.now()/60000),count:120});await assert.rejects(run(),/rate-limited/);
  assert.equal((await db.doc(base()).get()).exists,false);
 });
});
