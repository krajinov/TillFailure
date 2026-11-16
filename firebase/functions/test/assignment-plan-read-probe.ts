/** Explicit isolated PR2a probe. No exported callable, product issuance, reset or import. */
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {readFile,writeFile,mkdir,readdir,stat} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {initializeApp as adminApp} from 'firebase-admin/app';
import {getAuth as adminAuth} from 'firebase-admin/auth';
import {getFirestore,Timestamp} from 'firebase-admin/firestore';
import {initializeApp,deleteApp} from 'firebase/app';
import {getAuth,connectAuthEmulator,signInWithEmailAndPassword} from 'firebase/auth';
import {initializeFirestore,connectFirestoreEmulator,doc,getDocFromServer,getDocsFromServer,collection,query,orderBy,limit,terminate,Timestamp as ClientTimestamp} from 'firebase/firestore';
import {planFixture} from './assignment-plan-fixture.js';
import {planHash,planBinding,materializePlan,validatePlanGraph,planDecision,planDescriptor,planExecution} from '../src/assignment-plan-feasibility.js';
import {assignmentAuthority,assignmentDocumentDto,assignmentStoredDocuments} from '../src/program-assignments.js';
import {assignmentCopyHash} from '../src/assignment-feasibility-contract.js';
import {publicationContentHash,publicationDraftHash,publicationDraft} from '../src/program-publication-contract.js';
import {stableHash} from '../src/hashing.js';
async function main() {
const out=process.env.TF_M7_PR2A_EVIDENCE!;
await mkdir(out,{recursive:true});
await writeFile(`${out}/attempt-started.json`,JSON.stringify({startedAt:new Date().toISOString()}),{flag:"wx"});
assert.ok(out && process.env.FIRESTORE_EMULATOR_HOST==='127.0.0.1:50780' && process.env.FIREBASE_AUTH_EMULATOR_HOST==='127.0.0.1:50799');
adminApp({projectId:'demo-tillfailure-m3'});const db=getFirestore(),auth=adminAuth();
const prefix='p2a-'+randomUUID(),uid=(prefix+'-trainer').padEnd(128,'a'),client=(prefix+'-client').padEnd(128,'b'),wid=(prefix+'-workspace').padEnd(128,'w'),password=randomBytes(24).toString('base64url');
const f=planFixture(3,12,'😀',true),now=Date.now(),at=Timestamp.fromMillis(now-1000),email=(id:string)=>`${id.slice(0,55)}@example.invalid`;
f.uid=uid;f.client=client;f.wid=wid;f.version.ownerUid=uid;f.version.workspaceId=wid;
const {schemaVersion,versionId,versionNumber,draftRevision,draftHash,contentHash,publishedAtMillis,...content}=f.version;
f.version.contentHash=publicationContentHash(content);f.version.draftHash=publicationDraftHash(publicationDraft(content));
f.copy={copyPolicyVersion:1,title:content.title,description:content.description,workouts:content.workouts};
const a=f.descriptor.assignmentPlan;
a.clientUid=client;a.sourceContentHash=f.version.contentHash;a.snapshotHash=assignmentCopyHash(f.copy);a.authority={trainerLifecycleRevision:1,trainerMembershipRevision:1,clientLifecycleRevision:1,clientMembershipRevision:1,workspaceMembershipRevision:1,clientOnboardingRevision:1};a.preparedAtMillis=now;a.occurrences=a.occurrences.map((o:any,i:number)=>({...o,scheduledInstantMillis:now+3600000*(i+1)}));a.accessExpiresAtMillis=a.occurrences.at(-1).scheduledInstantMillis+86400000;a.planHash=planHash(a.occurrences);f.descriptor.workspaceId=wid;f.descriptor.idempotencyKey=`or1-b91f0176ec74475e8cf7406e25b7e9d3-${prefix}`;
const d=planDescriptor(f.descriptor),base=`users/${client}/workspaces/${wid}/assignedPrograms/${a.assignmentId}`,source=`workspaces/${wid}/programTemplates/${f.version.templateId}/versions/${f.version.versionId}`;
await writeFile(`${out}/read-fixture-private.json`,JSON.stringify({...f,password}));
for(const id of [uid,client])await auth.createUser({uid:id,email:email(id),emailVerified:true,password});
const setup:Record<string,any>={[`workspaces/${wid}`]:{schemaVersion:1,status:'active',ownerUid:uid,membershipRevision:1,activeRosterCount:2,catalogContributionCount:2},[`workspaces/${wid}/trainerProfiles/${uid}`]:{schemaVersion:1,workspaceId:wid,userId:uid},[`workspaces/${wid}/clientProfiles/${client}`]:{schemaVersion:1,workspaceId:wid,userId:client,trainerId:uid,createdAt:at,onboardingStatus:'complete',onboardingRevision:1,onboardingPolicyVersion:1,completedAt:at},[source]:{...f.version,publishedAt:at}};delete setup[source].publishedAtMillis;
for(const [id,role] of [[uid,'trainer'],[client,'client']]) {
 setup[`users/${id}`]={schemaVersion:1,accountStatus:'active',emailNormalized:email(id!),lifecycleRevision:1};
 setup[`workspaces/${wid}/memberships/${id}`]={schemaVersion:1,workspaceId:wid,userId:id,role,status:'active',revision:1,catalogContributionActive:true};
 setup[`users/${id}/membershipRefs/${wid}`]={schemaVersion:1,status:'active'};
}
let batch=db.batch();for(const [p,v] of Object.entries(setup))batch.create(db.doc(p),v);await batch.commit();
async function exportCheckpoint(label:string) {
 const dir=`${out}/${label}-export`,response=await fetch('http://127.0.0.1:50700/_admin/export',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({path:dir,initiatedBy:'isolated-pr2a-feasibility',targets:['auth','firestore','storage']})});assert.ok(response.ok,await response.text());
 const meta=JSON.parse(await readFile(`${dir}/firebase-export-metadata.json`,'utf8'));for(const k of ['auth','firestore','storage'])assert.ok(meta[k]);
 const hashes:Record<string,string>={};async function scan(p:string){for(const name of await readdir(p)){const file=`${p}/${name}`;if((await stat(file)).isDirectory())await scan(file);else hashes[file.slice(dir.length+1)]=createHash('sha256').update(await readFile(file)).digest('hex');}}await scan(dir);
 for(const [p,h] of Object.entries(hashes))assert.equal(createHash('sha256').update(await readFile(`${dir}/${p}`)).digest('hex'),h);
 await writeFile(`${out}/${label}-export.sha256.json`,JSON.stringify(hashes,null,2));return stableHash(hashes);
}
await exportCheckpoint('before-probe');
await db.doc(`planFeasibilityConfig/${prefix}`).create({only:'isolated-test',newIssuance:false});await db.doc(`planFeasibilityCoverage/${prefix}`).create({only:'independent-test',source:stableHash(await readFile(__filename,'utf8'))});
const proto=createRequire(__filename)('@google-cloud/firestore').protos.google.firestore.v1.CommitRequest;
const native=db as any,request=native.request,stream=native.requestStream;let observations=0,commitBytes=0,writeCount=0;
native.requestStream=function(method:string,bidirectional:boolean,req:any,...rest:any[]){if(method==='batchGetDocuments')observations+=req.documents.length;return stream.call(this,method,bidirectional,req,...rest);};
native.request=function(method:string,req:any,...rest:any[]){if(method==='commit' && req.writes.some((w:any)=>w.update?.name.includes('/snapshots/content'))){commitBytes=proto.encode(proto.fromObject(req)).finish().length;writeCount=req.writes.length;}return request.call(this,method,req,...rest);};
const admissionMinute=Math.floor(Date.now()/60000); // Freeze only the rate-test clock; latency/deadlines use real monotonic time.
async function admission(id:string) {
 await db.runTransaction(async tx=>{const r=db.doc(`planFeasibilityRates/${stableHash(id)}`),s=await tx.get(r),minute=admissionMinute,n=s.get('minute')===minute?s.get('count'):0;if(n>=120)throw Error('rate-limited');tx.set(r,{minute,count:n+1});},{maxAttempts:3});
}
const writerStarted=performance.now();await admission(uid);
await db.runTransaction(async tx=>{
 const authority=await assignmentAuthority(tx,db,uid,client,wid,email(uid),email(client));assert.deepEqual(authority,a.authority);
 const occupancy=await tx.getAll(...[`planFeasibilityConfig/${prefix}`,`planFeasibilityCoverage/${prefix}`,source,`lifecycleCommands/${planBinding(uid,d).id}`,`assignmentCommands/${planBinding(uid,d).id}`,base,`workspaces/${wid}/assignedPrograms/${a.assignmentId}`].map(p=>db.doc(p)));
 assert.ok(occupancy[0]!.exists && occupancy[1]!.exists && occupancy[2]!.exists);assert.ok(occupancy.slice(3).every(s=>!s.exists));
 const version={...occupancy[2]!.data(),publishedAtMillis:occupancy[2]!.get('publishedAt').toMillis()};delete (version as any).publishedAt;
 const g=materializePlan(d,version,uid);assert.equal(planExecution(a,Date.now()),'current');assert.ok(performance.now()-writerStarted<5000);
 for(const [p,v] of assignmentStoredDocuments(g))tx.create(db.doc(p),v);
 tx.update(db.doc(`workspaces/${wid}`),{membershipRevision:2});
 // Test-only receipt, never a product protected command or an exposed writer.
 tx.create(db.doc(`planFeasibilityReceipts/${planBinding(uid,d).id}`),{schemaVersion:1,commandKind:'program-assignment-plan-create',callerUid:uid,workspaceId:wid,requestHash:planBinding(uid,d).hash,result:planDecision(d,'plan-created'),committedAt:Timestamp.now()});
},{maxAttempts:3});
const writer={documentReadsIncludingAdmission:observations,commitBytes,businessWrites:writeCount,admissionWrites:1,latencyMillis:performance.now()-writerStarted};assert.equal(observations,17);assert.equal(writeCount,24);assert.ok(commitBytes<=524288);
native.request=request;native.requestStream=stream;
await exportCheckpoint('before-native');
const app=initializeApp({projectId:'demo-tillfailure-m3',apiKey:'demo-key',appId:'demo-pr2a'},prefix),sdkAuth=getAuth(app);connectAuthEmulator(sdkAuth,'http://127.0.0.1:50799',{disableWarnings:true});
const sdk=initializeFirestore(app,{experimentalForceLongPolling:true});connectFirestoreEmulator(sdk,'127.0.0.1',50780);
await signInWithEmailAndPassword(sdkAuth,email(uid),password);const token=await sdkAuth.currentUser!.getIdToken(true);
const perCall:any[]=[];let deadlineRefusals=0;
const server=createServer(async(req,res)=>{
 try {
  const started=performance.now();let text='';for await(const b of req){text+=b;if(text.length>32768)throw Error('oversize');}
  const q=JSON.parse(text);if(req.url!=='/read' || Object.keys(q).some(k=>!['relativePath','delayMillis'].includes(k)))throw Error('invalid');
  const decoded=await auth.verifyIdToken((req.headers.authorization??'').replace(/^Bearer /,''),true);assert.equal(decoded.uid,uid);const user=await auth.getUser(uid);assert.ok(user.emailVerified && !user.disabled);await admission(uid);
  let reads=0;const currentStream=native.requestStream;
  native.requestStream=function(method:string,bidirectional:boolean,r:any,...rest:any[]){if(method==='batchGetDocuments')reads+=r.documents.length;return currentStream.call(this,method,bidirectional,r,...rest);};
  let value:any;
  try {value=await db.runTransaction(async tx=>{
   await assignmentAuthority(tx,db,uid,client,wid,email(uid),email(client));
   const [h,m]=await tx.getAll(db.doc(base),db.doc(`${base}/manifests/download`));const head=assignmentDocumentDto(h!.data()!),manifest=m!.data()!,paths=manifest.requiredPaths;
   assert.equal(head.trainerId,uid);assert.ok(paths.length===19);const children=await tx.getAll(...paths.map((p:string)=>db.doc(`${base}/${p}`)));
   const g=new Map<string,any>([[base,head],[`${base}/manifests/download`,manifest]]);for(const s of children)g.set(s.ref.path,assignmentDocumentDto(s.data()!));assert.ok(validatePlanGraph(client,wid,a.assignmentId,g));
   if(q.delayMillis)await new Promise(ok=>setTimeout(ok,q.delayMillis));if(performance.now()-started>=5000){deadlineRefusals++;throw Error('deadline');}
   assert.ok(head.accessExpiresAtMillis>Date.now());
   if(q.relativePath===null)return {workspaceId:wid,clientUid:client,header:head,inventory:manifest,serverNowMillis:Date.now()};
   const path=q.relativePath===''?base:`${base}/${q.relativePath}`;if(!g.has(path))throw Error('unavailable');return {workspaceId:wid,clientUid:client,assignmentId:a.assignmentId,relativePath:q.relativePath,document:g.get(path),serverNowMillis:Date.now()};
  },{maxAttempts:3});}finally{native.requestStream=currentStream;}
  const packet=JSON.stringify({result:value});assert.ok(Buffer.byteLength(packet)<=65536);perCall.push({relativePath:q.relativePath,reads,admissionReads:1,latencyMillis:performance.now()-started,packetBytes:Buffer.byteLength(packet)});res.writeHead(200,{'content-type':'application/json'});res.end(packet);
 }catch(e){res.writeHead(503,{'content-type':'application/json'});res.end(JSON.stringify({error:(e as Error).message}));}
});await new Promise<void>(ok=>server.listen(50731,'127.0.0.1',ok));
async function call(relativePath:string|null,delayMillis=0) {const r=await fetch('http://127.0.0.1:50731/read',{method:'POST',headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},body:JSON.stringify({relativePath,delayMillis})});const body:any=await r.json();if(!r.ok)throw Error(body.error);return body.result;}
const loads:any[]=[];const initialRate=await db.doc(`planFeasibilityRates/${stableHash(uid)}`).get();
for(let n=0;n<3;n++) {
 const started=performance.now(),before=perCall.length,first=await call(null),graph=new Map<string,any>([[base,first.header],[`${base}/manifests/download`,first.inventory]]);
 for(const p of first.inventory.requiredPaths){const packet=await call(p);graph.set(`${base}/${p}`,packet.document);}
 const final=await call('');assert.deepEqual(final.document,first.header);assert.ok(validatePlanGraph(client,wid,a.assignmentId,graph));const calls=perCall.slice(before);assert.equal(calls.length,21);assert.equal(calls.reduce((n,c)=>n+c.reads,0),630);
 loads.push({kind:n===0?'cold-after-writer':'warm',calls:calls.length,graphAuthorityReads:630,admissionReads:21,latencyMillis:performance.now()-started,maximumCallMillis:Math.max(...calls.map(c=>c.latencyMillis)),packetBytes:calls.reduce((n,c)=>n+c.packetBytes,0)});
}
await writeFile(`${out}/trainer-loads.json`,JSON.stringify({writer,loads,perCall},null,2));
await assert.rejects(call('',5100),/deadline/);assert.equal(deadlineRefusals,1);
// Exhaust the same bounded logical per-minute gate through actual HTTP requests.
let rateRefused=false,extraCalls=0;for(let n=0;n<120;n++){try{await call('');extraCalls++;}catch(e){assert.match(String(e),/rate-limited/);rateRefused=true;break;}}assert.ok(rateRefused);
await new Promise<void>((ok,no)=>server.close(e=>e?no(e):ok()));
const finalRate=(await db.doc(`planFeasibilityRates/${stableHash(uid)}`).get()).data();
await writeFile(`${out}/trainer-measurements.json`,JSON.stringify({writer,loads,perCall,deadlineRefusals,initialRate:initialRate.data(),finalRate,extraCalls},null,2));
await signInWithEmailAndPassword(sdkAuth,email(client),password);
let sdkReads=0;const clientLoads:any[]=[];
for(let n=0;n<3;n++) {
 const started=performance.now(),beforeReads=sdkReads;const one=async(path:string)=>{sdkReads++;const s=await getDocFromServer(doc(sdk,path));assert.ok(s.exists());const data={...s.data()};for(const k of ["scheduledInstant","accessExpiresAt"])if(k in data){assert.ok(data[k] instanceof ClientTimestamp);assert.equal(data[k].nanoseconds%1000000,0);data[k+"Millis"]=data[k].toMillis();delete data[k];}return data;};
 const h=await one(base),m=await one(`${base}/manifests/download`),g=new Map<string,any>([[base,h],[`${base}/manifests/download`,m]]);
 for(const p of m.requiredPaths)g.set(`${base}/${p}`,await one(`${base}/${p}`));assert.deepEqual(await one(base),h);assert.ok(validatePlanGraph(client,wid,a.assignmentId,g));
 // Actual authenticated client-only time transaction: four business documents plus admission.
 await sdkAuth.currentUser!.getIdToken(true);await admission(client);await db.runTransaction(async tx=>{const [u,w,member,parent]=await tx.getAll(...[`users/${client}`,`workspaces/${wid}`,`workspaces/${wid}/memberships/${client}`,base].map(p=>db.doc(p)));assert.equal(u!.get('accountStatus'),'active');assert.equal(w!.get('status'),'active');assert.equal(member!.get('role'),'client');assert.ok(parent!.get('accessExpiresAt').toMillis()>Date.now());});
 assert.equal(sdkReads-beforeReads,22);clientLoads.push({sdkDocumentReads:sdkReads-beforeReads,timeCalls:1,timeBusinessReads:4,timeAdmissionReads:1,latencyMillis:performance.now()-started});
}
assert.equal(sdkReads,66);
const discovered=await getDocsFromServer(query(collection(sdk,`users/${client}/workspaces/${wid}/assignedPrograms`),orderBy('__name__'),limit(6)));assert.equal(discovered.size,1);
await assert.rejects(getDocFromServer(doc(sdk,source)));await assert.rejects(getDocFromServer(doc(sdk,`workspaces/${wid}/assignedPrograms/${a.assignmentId}`)));
for(const collection of ['lifecycleCommands','assignmentCommands','commandResolutionConfig','commandResolutionCoverage'])assert.equal((await db.collection(collection).limit(1).get()).empty,true);
await exportCheckpoint('after-read-probe');
const result={writer,trainerLoads:loads,trainerPerCall:perCall,deadlineRefusals,rate:{limit:120,clock:'fixed-minute test clock, not measured per-minute production throughput',initial:initialRate.data(),final:finalRate,extraSuccessfulCalls:extraCalls,refused:rateRefused},clientLoads,sdkDiscoveryDocuments:discovered.size,noProductCommandsOrAttestations:true,productionPerformanceProof:false,latencyScope:'Node HTTP loopback trainer probe and actual JS Firebase SDK client SERVER reads; not product/native UI latency'};
await writeFile(`${out}/read-measurements.json`,JSON.stringify(result,null,2));console.log('PR2A_READ_RESULT',JSON.stringify({...result,trainerPerCall:undefined}));
await terminate(sdk);await deleteApp(app);await db.terminate();

}
main().catch(error=>{console.error(error);process.exit(1);});
