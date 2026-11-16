/** Explicit loopback-only packet probe, independent create-only fixtures, no product wiring. */
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {mkdir,writeFile,readFile,readdir,stat} from 'node:fs/promises';
import {initializeApp} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getFirestore,Timestamp} from 'firebase-admin/firestore';
import {planFixture} from './assignment-plan-fixture.js';
import {assignmentFixture} from './assignment-pr1a-fixture.js';
import {publicationContentHash,publicationDraftHash,publicationDraft} from '../src/program-publication-contract.js';
import {assignmentCopyHash,materializeAssignment,millis} from '../src/assignment-feasibility-contract.js';
import {materializePlan,planHash} from '../src/assignment-plan-feasibility.js';
import {assignmentAuthority,assignmentStoredDocuments,assignmentDocumentDto} from '../src/program-assignments.js';
import {metadataView,readPacket,PacketAssembly,serverTimeBound} from '../src/assignment-plan-packets-feasibility.js';
import {exerciseExact as exact,exerciseId} from '../src/custom-exercise-contract.js';
import {participantUid,strictPathIdentifier} from '../src/input-validation.js';
import {stableHash} from '../src/hashing.js';
type Data=Record<string,any>;
async function main() {
 assert.equal(process.env.GCLOUD_PROJECT,'demo-tillfailure-m3');assert.equal(process.env.FIRESTORE_EMULATOR_HOST,'127.0.0.1:52780');assert.equal(process.env.FIREBASE_AUTH_EMULATOR_HOST,'127.0.0.1:52799');
 const root=process.env.TF_M7_EIGHT_EVIDENCE!;assert.ok(root);await mkdir(root,{recursive:true});await writeFile(`${root}/probe-started.json`,JSON.stringify({startedAt:new Date().toISOString()}),{flag:'wx'});
 initializeApp({projectId:'demo-tillfailure-m3'});const db=getFirestore(),auth=getAuth(),fixtures:Data[]=[];
 async function fixture(policy:1|2,mode='normal') {
  const f=policy===1?assignmentFixture('😀',12,true):planFixture(3,12,'😀',true),prefix='eight-'+randomUUID(),uid=(prefix+'-trainer').padEnd(128,'a'),client=(prefix+'-client').padEnd(128,'b'),wid=(prefix+'-workspace').padEnd(128,'w'),password=randomBytes(24).toString('base64url'),email=(id:string)=>`${id.slice(0,60)}@example.invalid`,at=Timestamp.fromMillis(Date.now()-1000);
  const d=f.descriptor as Data,a=policy===1?d.assignment:d.assignmentPlan,now=Date.now();f.version.ownerUid=uid;f.version.workspaceId=wid;
  const {schemaVersion,versionId,versionNumber,draftRevision,draftHash,contentHash,publishedAtMillis,...c}=f.version;
  f.version.contentHash=publicationContentHash(c);f.version.draftHash=publicationDraftHash(publicationDraft(c));
  a.clientUid=client;a.sourceContentHash=f.version.contentHash;a.snapshotHash=assignmentCopyHash({copyPolicyVersion:1,title:c.title,description:c.description,workouts:c.workouts});a.authority={trainerLifecycleRevision:1,trainerMembershipRevision:1,clientLifecycleRevision:1,clientMembershipRevision:1,workspaceMembershipRevision:1,clientOnboardingRevision:1};
  const last=mode==='expiry'?now+6000-86400000:now+3600000*(policy===1?1:3);a.preparedAtMillis=mode==='expiry'?last-86400000:now-1000;a.accessExpiresAtMillis=last+86400000;
  if(policy===1)a.scheduledInstantMillis=last;else {a.occurrences=a.occurrences.map((o:Data,i:number)=>({...o,scheduledInstantMillis:mode==='expiry'?last-(2-i)*1000:now+3600000*(i+1)}));a.planHash=planHash(a.occurrences);}
  d.workspaceId=wid;const aid=a.assignmentId,base=`users/${client}/workspaces/${wid}/assignedPrograms/${aid}`;
  const graph=policy===1?materializeAssignment(d,f.version,uid):materializePlan(d,f.version,uid);
  for(const id of [uid,client])await auth.createUser({uid:id,email:email(id),emailVerified:true,password});
  const setup:Record<string,Data>={[`workspaces/${wid}`]:{schemaVersion:1,status:'active',membershipRevision:1},[`workspaces/${wid}/trainerProfiles/${uid}`]:{schemaVersion:1,workspaceId:wid,userId:uid},[`workspaces/${wid}/clientProfiles/${client}`]:{schemaVersion:1,workspaceId:wid,userId:client,trainerId:uid,createdAt:at,onboardingStatus:'complete',onboardingRevision:1,onboardingPolicyVersion:1,completedAt:at}};
  for(const [id,role] of [[uid,'trainer'],[client,'client']]) {setup[`users/${id}`]={schemaVersion:1,accountStatus:'active',lifecycleRevision:1,emailNormalized:email(id!)};setup[`users/${id}/membershipRefs/${wid}`]={schemaVersion:1,status:'active'};setup[`workspaces/${wid}/memberships/${id}`]={schemaVersion:1,workspaceId:wid,userId:id,role,status:'active',revision:1,catalogContributionActive:true};}
  const batch=db.batch();for(const [p,v] of Object.entries(setup))batch.create(db.doc(p),v);for(const [p,v] of assignmentStoredDocuments(graph))batch.create(db.doc(p),v);await batch.commit();
  // Intentionally no trainer source/version documents: packet reads cannot read through them.
  const signed=await fetch('http://127.0.0.1:52799/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-key',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:email(uid),password,returnSecureToken:true})});const body=await signed.json() as Data;assert.ok(body.idToken);
  const result={uid,client,wid,aid,base,token:body.idToken,password,policy,expires:a.accessExpiresAtMillis};fixtures.push(result);await writeFile(`${root}/fixtures-private.json`,JSON.stringify(fixtures));return result;
 }
 async function checkpoint(label:string) {
  const dir=`${root}/${label}-export`;const r=await fetch('http://127.0.0.1:52700/_admin/export',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({path:dir,initiatedBy:'isolated-eight-packet-probe',targets:['auth','firestore','storage']})});assert.ok(r.ok,await r.text());
  const metadata=JSON.parse(await readFile(`${dir}/firebase-export-metadata.json`,'utf8'));for(const k of ['auth','firestore','storage'])assert.ok(metadata[k]);
  const manifest:Record<string,string>={};async function scan(p:string){for(const name of await readdir(p)){const file=`${p}/${name}`;if((await stat(file)).isDirectory())await scan(file);else manifest[file.slice(dir.length+1)]=createHash('sha256').update(await readFile(file)).digest('hex');}}await scan(dir);for(const [p,h] of Object.entries(manifest))assert.equal(createHash('sha256').update(await readFile(`${dir}/${p}`)).digest('hex'),h);await writeFile(`${root}/${label}-export.sha256.json`,JSON.stringify(manifest,null,2));
 }
 const full=await fixture(2),old=await fixture(1);await checkpoint('before-reads');
 const minute=Math.floor(Date.now()/60000),calls:Data[]=[],failures:Data[]=[],native=db as any;let busy=false;const injectedDelay=new Map<string,number>();const heldReads=new Map<string,{entered:()=>void;release:Promise<void>}>();
 async function admission(uid:string) {await db.runTransaction(async tx=>{const ref=db.doc(`packetFeasibilityRates/${stableHash(uid)}`),s=await tx.get(ref),count=s.get('minute')===minute?s.get('count'):0;if(count>=120)throw Error('rate-limited');tx.set(ref,{minute,count:count+1});},{maxAttempts:3});}
 const server=createServer(async(req,res)=>{
  const started=performance.now(),wallStarted=Date.now();let observed=0,paths:string[]=[],restore:(()=>void)|undefined;let q:Data={},ownsSlot=false;
  try {
   assert.ok(!busy);busy=true;ownsSlot=true;let text='';for await(const part of req){text+=part;assert.ok(Buffer.byteLength(text)<=32768);}
   q=JSON.parse(text);assert.equal(req.url,'/packets');assert.ok(exact(q,['workspaceId','clientUid','assignmentId','kind','index','metadataHash']));
   const wid=participantUid(strictPathIdentifier(q.workspaceId,'workspace'),'workspace'),client=participantUid(q.clientUid,'client');assert.ok(exerciseId(q.assignmentId) && ['metadata','items','final'].includes(q.kind) && Number.isInteger(q.index) && q.index>=0 && q.index<8);
   const decoded=await auth.verifyIdToken((req.headers.authorization??'').replace(/^Bearer /,''),true);const uid=participantUid(decoded.uid,'caller');const [user,target]=await Promise.all([auth.getUser(uid),auth.getUser(client)]);assert.ok(user.emailVerified && target.emailVerified && !user.disabled && !target.disabled && uid!==client);
   const original=native.requestStream;native.requestStream=function(method:string,bidirectional:boolean,r:Data,...args:any[]){if(method==='batchGetDocuments'){observed+=r.documents.length;paths.push(...r.documents);}return original.call(this,method,bidirectional,r,...args);};restore=()=>{native.requestStream=original;};
   await admission(uid);const b=`users/${client}/workspaces/${wid}/assignedPrograms/${q.assignmentId}`;
   const packet=await db.runTransaction(async tx=>{
    await assignmentAuthority(tx,db,uid,client,wid,user.email!.toLowerCase(),target.email!.toLowerCase());
    const [h,inv]=await tx.getAll(db.doc(b),db.doc(`${b}/manifests/download`));assert.ok(h!.exists && inv!.exists);const header=assignmentDocumentDto(h!.data()!);assert.equal(header.trainerId,uid);
    const manifest=inv!.data()!;assert.ok(Array.isArray(manifest.requiredPaths) && manifest.requiredPaths.length>=4 && manifest.requiredPaths.length<=19 && Buffer.byteLength(JSON.stringify(manifest))<=8192);
    // Validate bounded relative-path grammar before references; full exact inventory follows.
    assert.ok(manifest.requiredPaths.every((p:unknown)=>typeof p==='string' && p.length<=160 && /^(snapshots\/content(?:\/workouts\/[a-f0-9-]{36}(?:\/exercises\/[a-f0-9-]{36})?)?|plannedWorkouts\/[a-f0-9-]{36})$/.test(p)));
    const children=await tx.getAll(...manifest.requiredPaths.map((p:string)=>db.doc(`${b}/${p}`))),g=new Map<string,Data>([[b,header],[`${b}/manifests/download`,manifest]]);for(const s of children){assert.ok(s.exists);g.set(s.ref.path,assignmentDocumentDto(s.data()!));}
    const p=readPacket(client,wid,q.assignmentId,g,q.kind,q.index,q.metadataHash,Date.now());
    const held=heldReads.get(uid);heldReads.delete(uid);if(held){held.entered();await held.release;}
    const delay=injectedDelay.get(uid)??0;injectedDelay.delete(uid);if(delay)await new Promise(ok=>setTimeout(ok,delay));
    const now=serverTimeBound(wallStarted,Date.now(),header.accessExpiresAtMillis);assert.ok(performance.now()-started<5000);p.serverNowMillis=now;return p;
   },{maxAttempts:3});
   const body=JSON.stringify({result:packet});assert.ok(Buffer.byteLength(body)<=65536);assert.ok(!paths.some(p=>p.includes('/programTemplates/') || p.includes('/customExercises/')));
   calls.push({policy:packet.binding.schedulePolicyVersion,kind:q.kind,index:q.index,readsIncludingAdmission:observed,bytes:Buffer.byteLength(body),latencyMillis:performance.now()-started});res.writeHead(200,{'content-type':'application/json'});res.end(body);
  }catch(e){failures.push({kind:q.kind??null,index:q.index??null,readsIncludingAdmission:observed,reason:String((e as Error).message)});res.writeHead(503,{'content-type':'application/json'});res.end(JSON.stringify({error:'unavailable'}));}
  finally{restore?.();if(ownsSlot)busy=false;}
 });await new Promise<void>(ok=>server.listen(52731,'127.0.0.1',ok));
 async function call(f:Data,kind:string,index:number,hash:string|null,session?:PacketAssembly) {
  const started=performance.now(),r=await fetch('http://127.0.0.1:52731/packets',{method:'POST',headers:{authorization:`Bearer ${f.token}`,'content-type':'application/json'},body:JSON.stringify({workspaceId:f.wid,clientUid:f.client,assignmentId:f.aid,kind,index,metadataHash:hash})});const body=await r.text();const received=performance.now();if(!r.ok){session?.deny();throw Error('denied');}session?.accept(body,started,received);return {body,packet:JSON.parse(body).result,started,received};
 }
 const loads:Data[]=[];
 for(const f of [full,old])for(let n=0;n<3;n++) {
  const session=new PacketAssembly(f.client,f.wid,f.aid),start=performance.now(),before=calls.length;
  const m=await call(f,'metadata',0,null,session);metadataView(f.client,f.wid,f.aid,m.packet.metadata);
  for(let index=1;index<m.packet.totalPackets-1;index++)await call(f,'items',index,m.packet.binding.metadataHash,session);
  await call(f,'final',m.packet.totalPackets-1,m.packet.binding.metadataHash,session);const result=session.result(performance.now()),list=calls.slice(before);
  assert.equal(list.length,8);assert.equal(result.size,f.policy===2?21:17);assert.ok(list.every(p=>p.readsIncludingAdmission===(f.policy===2?31:27)));
  loads.push({policy:f.policy,run:n,calls:list.length,graphAuthorityReads:list.reduce((n,p)=>n+p.readsIncludingAdmission-1,0),admissionReads:list.length,admissionWrites:list.length,totalReads:list.reduce((n,p)=>n+p.readsIncludingAdmission,0),bytes:list.reduce((n,p)=>n+p.bytes,0),latencyMillis:performance.now()-start,maximumCallMillis:Math.max(...list.map(p=>p.latencyMillis))});
 }
 const regressionResults:Data[]=[];
 const signedClient=await fetch('http://127.0.0.1:52799/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-key',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:`${full.client.slice(0,60)}@example.invalid`,password:full.password,returnSecureToken:true})});
 const clientIdentity=await signedClient.json() as Data;assert.ok(clientIdentity.idToken);
 await assert.rejects(call({...full,token:clientIdentity.idToken},'metadata',0,null));regressionResults.push({name:'no-new-client-mediator-grant',denied:true});
 await assert.rejects(call({...full,token:old.token},'metadata',0,null));regressionResults.push({name:'foreign-trainer-denied',denied:true});

 async function denial(name:string,mutate:(f:Data)=>Promise<void>,expiry=false) {
  const f=await fixture(2,expiry?'expiry':'normal'),s=new PacketAssembly(f.client,f.wid,f.aid),m=await call(f,'metadata',0,null,s);await mutate(f);
  await assert.rejects(call(f,name==='changed-final'?'final':'items',name==='changed-final'?7:1,m.packet.binding.metadataHash,s));assert.throws(()=>s.result(performance.now()));regressionResults.push({name,denied:true,receiverCleared:true});await checkpoint(name);
 }
 await denial('membership-loss',async f=>{await db.doc(`workspaces/${f.wid}/memberships/${f.uid}`).update({status:'revoked',revision:2});});
 await denial('client-auth-disabled',async f=>{await auth.updateUser(f.client,{disabled:true});});
 await denial('changed-final',async f=>{const batch=db.batch();for(const path of [f.base,`${f.base}/snapshots/content`])batch.update(db.doc(path),{sourceVersionId:'v0000000002',sourceVersionNumber:2});await batch.commit();});
 await denial('expiry-between-packets',async f=>{await new Promise(ok=>setTimeout(ok,Math.max(1,f.expires-Date.now()+5)));},true);
 await denial('five-second-deadline',async f=>{injectedDelay.set(f.uid,5100);});
 const single=await fixture(2);let enter!:()=>void,release!:()=>void;
 const entered=new Promise<void>(ok=>{enter=ok;}),gate=new Promise<void>(ok=>{release=ok;});heldReads.set(single.uid,{entered:enter,release:gate});
 const pending=call(single,'metadata',0,null);await entered;
 try {await assert.rejects(call(single,'metadata',0,null));await assert.rejects(call(single,'metadata',0,null));}finally{release();}
 await pending;regressionResults.push({name:'probe-single-flight-ownership',firstCompleted:true,concurrentRequestsDenied:true});await checkpoint('single-flight');
 const beforeRate=(await db.doc(`packetFeasibilityRates/${stableHash(full.uid)}`).get()).data();let extra=0;
 for(let n=0;n<=120;n++){try{await call(full,'metadata',0,null);extra++;}catch{break;}}
 const afterRate=(await db.doc(`packetFeasibilityRates/${stableHash(full.uid)}`).get()).data();assert.equal(beforeRate!.count,24);assert.equal(afterRate!.count,120);assert.equal(extra,96);
 for(const name of ['lifecycleCommands','assignmentCommands','commandResolutionConfig','commandResolutionCoverage'])assert.equal((await db.collection(name).limit(1).get()).empty,true);
 await checkpoint('final');await new Promise<void>((ok,no)=>server.close(e=>e?no(e):ok()));
 const result={loads,calls,regressions:regressionResults,failures,rate:{limit:120,clock:'fixed minute for deterministic budget, real request/expiry/deadline clocks',before:beforeRate,after:afterRate,extraSuccessful:extra,refusedAfter120:true},sourceReads:0,completeGraphAndFreshAuthPerRequest:true,noProductCommandsOrAttestations:true,noCrossRequestAuthorityOrGraphCache:true,productionPerformanceProof:false};
 await writeFile(`${root}/read-measurements.json`,JSON.stringify(result,null,2));console.log('EIGHT_ACTUAL',JSON.stringify({...result,calls:undefined,failures:undefined}));await db.terminate();
}
main().catch(e=>{console.error(e);process.exit(1);});
