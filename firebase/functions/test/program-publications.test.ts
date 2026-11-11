import { mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { getAuth } from 'firebase-admin/auth';
import { emulatorFirestore, SPIKE_PROJECT_ID } from '../src/environment.js';
import { programDraftOperation, sourceIdentity } from '../src/program-drafts.js';
import { programPublicationOperation } from '../src/program-publications.js';
import { publicationDraftHash, publicationContentHash } from '../src/program-publication-contract.js';
import { provisionTrainer } from '../src/provisioning.js';
import { RESOLUTION_CONFIG_PATH, RESOLUTION_GENERATION } from '../src/command-identity.js';
import { resolveOwnCommand, resolutionBinding } from '../src/command-resolution.js';
const db=emulatorFirestore(),auth=getAuth(),uid='publication_author',wid='publication_workspace',id='00000000-0000-4000-8000-000000000001';
const root=db.doc(`workspaces/${wid}/programTemplates/${id}`),sourceRef=db.doc('systemExercises/synthetic');
const source={schemaVersion:1,name:'Synthetic',nameNormalized:'synthetic',muscleGroups:['legs'],equipment:['bodyweight'],instructions:'Original synthetic instructions.',status:'published'};
const read=(action='publication-prepare',extra:any={expectedRevision:1})=>programPublicationOperation(db,uid,{action,workspaceId:wid,templateId:id,...extra});
const run=(d:any)=>programPublicationOperation(db,uid,d);
const resolve=(d:any)=>resolveOwnCommand(db,uid,{protocolVersion:1,descriptor:d});
let save:any;
const command=async(key='publish',revision=1)=>({action:'publish-program-template',workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-${key}`,publish:(await read('publication-prepare',{expectedRevision:revision})).publish});
describe('immutable program publication',()=>{
 beforeEach(async()=>{
  // This suite is only run on the isolated disposable test emulator, never retained native datasets.
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`,{method:'DELETE'});
  await auth.deleteUser(uid).catch(()=>{});await auth.createUser({uid,email:`${uid}@example.invalid`,emailVerified:true});
  process.env.TF_RESOLUTION_PROVENANCE_ID='publications-isolated';
  await db.doc(RESOLUTION_CONFIG_PATH).create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:'publications-isolated',historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:30});
  await provisionTrainer(db,{uid,workspaceId:wid,workspaceName:'Synthetic publications',idempotencyKey:'bootstrap',expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
   {uid,email:`${uid}@example.invalid`,emailVerified:true,disabled:false},{uid:'pr2_operator',allowedUid:'pr2_operator',emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
  const authority=(await programDraftOperation(db,uid,{action:'draft-prepare',workspaceId:wid})).authority;
  await sourceRef.create(source);
  save={action:'save-program-draft',workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-save`,draftSave:{templateId:id,expectedRevision:0,authority,title:'Synthetic program',description:'',workouts:[{id,title:'Workout',items:[{id,...sourceIdentity('system','synthetic',wid,uid,source),prescription:{sets:3,reps:10,restSeconds:60,loadGrams:null}}]}]}};
  await programDraftOperation(db,uid,save);
 });
 it('binds actual canonical draft/content and planned cross-language command vectors',async()=>{
  const preview=await read();assert.equal(preview.publish.draftHash,publicationDraftHash((await root.get()).data()!));
  assert.equal(preview.contentHash,publicationContentHash(preview.preview));assert.equal(preview.preview.workouts[0].items[0].instructions,source.instructions);
  const b=resolutionBinding('A',{protocolVersion:1,descriptor:{action:'publish-program-template',workspaceId:'w',idempotencyKey:`or1-${RESOLUTION_GENERATION}-publication-vector`,publish:{templateId:id,expectedRevision:1,authority:{authorLifecycleRevision:1,authorMembershipRevision:1,workspaceMembershipRevision:1},draftHash:'a'.repeat(64),contentPolicyVersion:1}}});
  const local={...preview.preview,workspaceId:'w',ownerUid:'A'};
  assert.equal(publicationDraftHash({...local,workouts:local.workouts.map((w:any)=>({...w,items:w.items.map(({instructions,muscleGroups,equipment,...i}:any)=>i)}))}),'ec1e7cd0a5bd1f64576b0ecab0cf458792a55ea9170962faa283254a546ec51d');
  assert.equal(publicationContentHash(local),'dcfc19cf298f31055c1946bb143df1bae8b5e7cc9800dae7391719e795666811');
  assert.equal(b.id,'f3305d4ae3cd01f3ff117fb22b54b078b2bda261d9632501087de6552b883f6c');assert.equal(b.hash,'9f2f8f049c555ec488c4e01388432921094a3250b348175081a665338cb0584c');
 });
 it('publish v1, Save schema-2 draft, publish v2 preserves exact v1 and server metadata',async()=>{
  const a=await command();const first=await run(a);assert.equal(first.status,'program-published');
  const v1=root.collection('versions').doc(first.versionId);const bytes=JSON.stringify((await v1.get()).data());
  const edit={...save,idempotencyKey:save.idempotencyKey+'edit',draftSave:{...save.draftSave,expectedRevision:2,title:'Edited'}};
  assert.equal((await programDraftOperation(db,uid,edit)).status,'draft-saved');
  const r=(await root.get()).data()!;assert.equal(r.schemaVersion,2);assert.equal(r.versionCount,1);assert.equal(r.currentVersionId,first.versionId);
  assert.equal((await programDraftOperation(db,uid,save)).replayed,true);
  assert.equal((await programDraftOperation(db,uid,{...save,idempotencyKey:save.idempotencyKey+'stale'})).status,'draft-conflict');
  await assert.rejects(programDraftOperation(db,uid,{...edit,idempotencyKey:edit.idempotencyKey+'inject',draftSave:{...edit.draftSave,versionCount:99}}));
  const second=await run(await command('v2',3));assert.equal(second.versionNumber,2);assert.equal(second.templateRevision,4);
  assert.equal(JSON.stringify((await v1.get()).data()),bytes);assert.equal((await run(a)).replayed,true);
  assert.equal((await root.collection('versions').get()).size,2);
  const list=await read('version-list',{beforeId:null});assert.deepEqual(list.items.map((v:any)=>v.versionNumber),[2,1]);
  assert.equal((await read('version-get',{versionId:first.versionId})).version.contentHash,first.contentHash);
  assert.equal((await resolve(a)).outcome,'applied');
 });
 it('changed instructions, taxonomy, identity or revision after preview cannot silently publish',async()=>{
  for(const [index,change] of [{instructions:'Changed instructions'},{muscleGroups:['arms']},{equipment:[]},{nameNormalized:'different'},{status:'withdrawn'}].entries()) {
    await sourceRef.set(source);const d=await command(`source-${index}`);await sourceRef.update(change);
    const r=await run(d);assert.equal(r.status,'publication-source-conflict');assert.equal(r.versionId,null);assert.equal((await resolve(d)).outcome,'not-applied-conflict');
    await assert.rejects(read(),/publication-source-conflict/);
  }
  assert.equal((await root.collection('versions').get()).size,0);assert.equal((await root.get()).get('revision'),1);
 });
 it('draft content mismatch and incomplete content have exact terminal conflicts',async()=>{
  const d=await command();d.publish.draftHash='b'.repeat(64);assert.equal((await run(d)).status,'publication-draft-conflict');
  await root.update({title:''});const p=(await root.get()).data()!;
  const incomplete={...d,idempotencyKey:d.idempotencyKey+'incomplete',publish:{...d.publish,draftHash:publicationDraftHash(p)}};
  assert.equal((await run(incomplete)).status,'publication-incomplete');await assert.rejects(read(),/publication-incomplete/);
 });
 it('Save/Publish and Publish/Publish share one root revision and identical republication needs a new command',async()=>{
  const d=await command();const e={...save,idempotencyKey:save.idempotencyKey+'parallel',draftSave:{...save.draftSave,expectedRevision:1}};
  const values=await Promise.all([run(d),programDraftOperation(db,uid,e)]);assert.equal(values.filter(v=>['draft-saved','program-published'].includes(v.status)).length,1);
  assert.equal((await root.get()).get('revision'),2);
  const a=await command('concurrentA',2),b={...a,idempotencyKey:a.idempotencyKey+'B'};
  const pair=await Promise.all([run(a),run(b)]);assert.deepEqual(pair.map(v=>v.status).sort(),['program-published','publication-conflict']);
  const count=(await root.collection('versions').get()).size;
  await run(a);await run(b);assert.equal((await root.collection('versions').get()).size,count);
  assert.equal((await run(await command('explicit-again',3))).versionNumber,count+1);
 });
 it('source write first conflicts; publish first copies exactly the confirmed snapshot',async()=>{
  const d=await command();let entered!:()=>void,release!:()=>void;
  const ready=new Promise<void>(r=>entered=r),held=new Promise<void>(r=>release=r);
  const publishing=programPublicationOperation(db,uid,d,Date.now,async()=>{entered();await held});
  await ready;const changed=sourceRef.update({instructions:'After transaction'});release();
  const decision=await publishing;await changed;assert.equal(decision.status,'program-published');
  assert.equal((await read('version-get',{versionId:decision.versionId})).version.workouts[0].items[0].instructions,source.instructions);
  await sourceRef.set(source);const next=await command('source-first',2);
  let locked!:()=>void,finish!:()=>void;const acquired=new Promise<void>(r=>locked=r),hold=new Promise<void>(r=>finish=r);
  const updating=db.runTransaction(async tx=>{await tx.get(sourceRef);tx.update(sourceRef,{instructions:'Before publication'});locked();await hold;});
  await acquired;const waiting=run(next);finish();await updating;assert.equal((await waiting).status,'publication-source-conflict');
 });
 it('authority loss, restoration and own resolver/writer races retain exact outcomes',async()=>{
  const d=await command();assert.equal((await resolve(d)).outcome,'not-applied-fenced');await assert.rejects(run(d),/idempotency-key-reused/);
  const a=await command('writer-first');let entered!:()=>void,release!:()=>void;const ready=new Promise<void>(r=>entered=r),held=new Promise<void>(r=>release=r);
  const writing=programPublicationOperation(db,uid,a,Date.now,async()=>{entered();await held});await ready;const resolving=resolve(a);release();
  assert.equal((await writing).status,'program-published');assert.equal((await resolving).outcome,'applied');
  await db.doc(`workspaces/${wid}`).update({status:'suspended',membershipRevision:2});
  await assert.rejects(run(a),/unauthorized/);await assert.rejects(read('version-list',{beforeId:null}),/unauthorized/);assert.equal((await resolve(a)).outcome,'applied');
  await db.doc(`workspaces/${wid}`).update({status:'active',membershipRevision:3});await assert.rejects(run(a),/unauthorized/);
  assert.equal((await read('version-list',{beforeId:null})).items.length,1);
 });
 it('denies clients and foreign authors without leaking version content',async()=>{
  const d=await command();const result=await run(d);
  await db.doc(`workspaces/${wid}/memberships/${uid}`).update({role:'client'});
  await assert.rejects(read('version-get',{versionId:result.versionId}),/unauthorized/);
  await assert.rejects(run(d),/unauthorized/);
  await db.doc(`workspaces/${wid}/memberships/${uid}`).update({role:'trainer'});
  await root.update({ownerUid:'foreign-author'});
  await assert.rejects(read('version-get',{versionId:result.versionId}),/publication-unavailable|draft-unavailable/);
 });
 it('rejects malformed reads, content, overflow and incomplete source authority with finite work',async()=>{
  for(const extra of [{expectedRevision:0},{expectedRevision:'1'},{expectedRevision:1,extra:true},{expectedRevision:2147483646}])await assert.rejects(read('publication-prepare',extra));
  const d=await command();await db.doc(`users/${uid}/authorizations/systemCatalog`).update({status:'inactive'});
  assert.equal((await run(d)).status,'publication-source-conflict');
  await db.doc(`users/${uid}/authorizations/systemCatalog`).update({status:'active'});
  await root.update({revision:2147483646});await assert.rejects(read('publication-prepare',{expectedRevision:2147483646}));
  const stale=await run({...d,idempotencyKey:d.idempotencyKey+'overflow'});assert.equal(stale.status,'publication-conflict');
  await root.update({revision:1});await sourceRef.update({instructions:'x'.repeat(2001)});await assert.rejects(read(),/publication-source-conflict/);
  let n=0;await assert.rejects(programPublicationOperation(db,uid,{action:'version-list',workspaceId:wid,templateId:id,beforeId:null},()=>{n++;return n<3?0:6000}),/publication-unavailable/);
 });
 for(const first of ['Save','Publish'])it(`shared root revision serializes explicit ${first}-first ordering`,async()=>{
  const d=await command(),edit={...save,idempotencyKey:save.idempotencyKey+'ordered',draftSave:{...save.draftSave,expectedRevision:1,title:'Ordered edit'}};
  let entered!:()=>void,release!:()=>void;const ready=new Promise<void>(r=>entered=r),hold=new Promise<void>(r=>release=r);
  const hook=async()=>{entered();await hold;};
  const firstWrite=first==='Save'?programDraftOperation(db,uid,edit,Date.now,hook):programPublicationOperation(db,uid,d,Date.now,hook);
  await ready;const secondWrite=first==='Save'?run(d):programDraftOperation(db,uid,edit);release();
  const [a,b]=await Promise.all([firstWrite,secondWrite]);assert.equal(a.status,first==='Save'?'draft-saved':'program-published');assert.equal(b.status,first==='Save'?'publication-conflict':'draft-conflict');
  assert.equal((await root.get()).get('revision'),2);assert.equal((await root.collection('versions').get()).size,first==='Save'?0:1);
 });
 it('custom copied content binds identity, revision and fingerprint; timestamp-only changes are excluded',async()=>{
  const {Timestamp}=await import('firebase-admin/firestore');const now=Timestamp.now(),ref=db.doc(`workspaces/${wid}/exercises/${id}`);
  const custom={schemaVersion:1,policyVersion:1,workspaceId:wid,exerciseId:id,ownerUid:uid,name:'Synthetic custom',instructions:'Confirmed instructions',muscleGroups:['legs'],equipment:['bodyweight'],revision:1,createdAt:now,updatedAt:now};
  await ref.create(custom);
  await programDraftOperation(db,uid,{...save,idempotencyKey:save.idempotencyKey+'custom',draftSave:{...save.draftSave,expectedRevision:1,workouts:[{id,title:'Workout',items:[{id,...sourceIdentity('custom',id,wid,uid,custom),prescription:{sets:3,reps:10,restSeconds:60,loadGrams:null}}]}]}});
  for(const [index,change] of [{instructions:'Unconfirmed instructions'},{revision:2},{ownerUid:'foreign'}].entries()) {
    await ref.set(custom);const d=await command(`custom-${index}`,2);await ref.update(change);assert.equal((await run(d)).status,'publication-source-conflict');
  }
  await ref.set(custom);const d=await command('custom-time',2);await ref.update({updatedAt:Timestamp.fromMillis(now.toMillis()+1)});
  const result=await run(d);assert.equal(result.status,'program-published');const version=(await read('version-get',{versionId:result.versionId})).version;
  assert.equal(version.workouts[0].items[0].instructions,custom.instructions);assert.deepEqual(version.workouts[0].items[0].equipment,custom.equipment);
 });
 it('version paging is five plus lookahead and malformed lookahead rejects the whole page',async()=>{
  for(let i=0;i<7;i++)assert.equal((await run(await command(`page-${i}`,i+1))).versionNumber,i+1);
  const a=await read('version-list',{beforeId:null});assert.deepEqual(a.items.map((v:any)=>v.versionNumber),[7,6,5,4,3]);assert.equal(a.nextId,'v0000000003');
  const b=await read('version-list',{beforeId:a.nextId});assert.deepEqual(b.items.map((v:any)=>v.versionNumber),[2,1]);assert.equal(b.nextId,null);
  await root.collection('versions').doc('v0000000002').update({contentHash:'f'.repeat(64)});await assert.rejects(read('version-list',{beforeId:null}),/publication-unavailable/);
 });
 it('precommit failure and an occupied immutable path leave no partial pointer or receipt',async()=>{
  const d=await command();const before=JSON.stringify((await root.get()).data());
  await assert.rejects(programPublicationOperation(db,uid,d,Date.now,async()=>{throw new Error('injected-before-commit');}),/injected-before-commit/);
  assert.equal(JSON.stringify((await root.get()).data()),before);assert.equal((await root.collection('versions').get()).size,0);
  const {commandId}=await import('../src/hashing.js');assert.equal((await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).exists,false);
  await root.collection('versions').doc('v0000000001').create({unexpected:'occupied'});
  await assert.rejects(run(d));assert.equal(JSON.stringify((await root.get()).data()),before);
  assert.deepEqual((await root.collection('versions').doc('v0000000001').get()).data(),{unexpected:'occupied'});
  assert.equal((await db.doc(`lifecycleCommands/${commandId(uid,d.idempotencyKey)}`).get()).exists,false);
  assert.equal((await resolve(d)).outcome,'not-applied-fenced');
 });
 it('fresh bearer callable binds preview, execution, detail and replay without author selectors',async()=>{
  const custom=await auth.createCustomToken(uid);
  const signed=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fixture`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:custom,returnSecureToken:true})});
  const token=(await signed.json() as any).idToken;
  const invoke=(data:any,bearer=token)=>fetch(`http://127.0.0.1:${process.env.TF_FUNCTIONS_EMULATOR_PORT??5001}/${SPIKE_PROJECT_ID}/europe-west1/programPublicationsEmulator`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${bearer}`},body:JSON.stringify({data})});
  const prepared=await invoke({action:'publication-prepare',workspaceId:wid,templateId:id,expectedRevision:1});assert.equal(prepared.status,200);
  const preview=(await prepared.json() as any).result;const d={action:'publish-program-template',workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-callable`,publish:preview.publish};
  assert.notEqual((await invoke(d,'forged')).status,200);assert.notEqual((await invoke({...d,ownerUid:uid})).status,200);
  const published=await invoke(d);assert.equal(published.status,200);const decision=(await published.json() as any).result;assert.equal(decision.contentHash,preview.contentHash);
  assert.equal((await (await invoke(d)).json() as any).result.replayed,true);
  const detail=await invoke({action:'version-get',workspaceId:wid,templateId:id,versionId:decision.versionId});assert.equal(detail.status,200);
  assert.equal((await detail.json() as any).result.version.contentHash,preview.contentHash);
 });
 it('bounds actual full-content callable and Admin SDK commit serialization',async()=>{
  const unicode='😀';const workouts:any[]=[];
  for(let w=0;w<3;w++) {
    const items:any[]=[];
    for(let i=0;i<4;i++) {
      const index=w*4+i,sourceId='z'.repeat(126)+String(index).padStart(2,'0'),name=unicode.repeat(index===0?120:index===1?80:1);
      const definition={...source,name,nameNormalized:name,instructions:unicode.repeat(2000),muscleGroups:Array.from({length:8},(_,n)=>unicode.repeat(39)+String(n)),equipment:Array.from({length:8},(_,n)=>unicode.repeat(39)+String(n))};
      await db.doc(`systemExercises/${sourceId}`).create(definition);
      items.push({id:`00000000-0000-4000-8000-${String(index+10).padStart(12,'0')}`,...sourceIdentity('system',sourceId,wid,uid,definition),prescription:{sets:20,reps:1000,restSeconds:3600,loadGrams:1000000}});
    }
    workouts.push({id:`00000000-0000-4000-8000-${String(w+2).padStart(12,'0')}`,title:unicode.repeat(40),items});
  }
  await programDraftOperation(db,uid,{...save,idempotencyKey:save.idempotencyKey+'boundary',draftSave:{...save.draftSave,expectedRevision:1,title:unicode.repeat(80),description:unicode.repeat(240),workouts}});
  const preview=await read('publication-prepare',{expectedRevision:2});const previewBytes=Buffer.byteLength(JSON.stringify({result:preview}));assert(previewBytes<=192*1024);
  const native=db as any,original=native.request,proto=require('@google-cloud/firestore').protos.google.firestore.v1.CommitRequest;let measured=0;const documentBytes:Record<string,number>={};
  native.request=function(method:string,request:any,...rest:any[]) {
    if(method==='commit' && request.writes.some((v:any)=>v.update?.name.includes('/versions/'))) {measured=proto.encode(proto.fromObject(request)).finish().length;assert.equal(request.writes.length,3);assert(measured<=256*1024);assert(request.transaction.length<=9); const Document=require('@google-cloud/firestore').protos.google.firestore.v1.Document; for(const w of request.writes){const kind=w.update.name.includes('/versions/')?'version':w.update.name.includes('/programTemplates/')?'root':'receipt';documentBytes[kind]=Document.encode(Document.fromObject(w.update)).finish().length;}}
    return original.call(this,method,request,...rest);
  };
  try {
    const r=await run({action:'publish-program-template',workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-boundary`,publish:preview.publish});
    const version=(await read('version-get',{versionId:r.versionId})).version;
    await mkdir('/private/tmp/tillfailure-m6p4-response-fixtures',{recursive:true});
    await writeFile('/private/tmp/tillfailure-m6p4-response-fixtures/preview.json',JSON.stringify(preview));
    await writeFile('/private/tmp/tillfailure-m6p4-response-fixtures/version.json',JSON.stringify({workspaceId:wid,version}));
const versionBytes=Buffer.byteLength(JSON.stringify(version));assert(versionBytes<=160*1024);assert(measured>0);
    console.log(`PUBLICATION_ACTUAL previewCallable=${previewBytes} version=${versionBytes} adminCommit=${measured} adminDocuments=${JSON.stringify(documentBytes)} writes=3 sources=12`);
  } finally {native.request=original;}
 });
});
