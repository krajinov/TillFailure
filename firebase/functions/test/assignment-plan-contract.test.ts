import assert from 'node:assert/strict';
import {describe,it} from 'node:test';
import {mkdir,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {Firestore,Timestamp} from 'firebase-admin/firestore';
import {planFixture} from './assignment-plan-fixture.js';
import {assignmentFixture} from './assignment-pr1a-fixture.js';
import {assignmentBinding,assignmentDescriptor,validateClientAssignmentDocuments,assignmentCopyHash,materializeAssignment} from '../src/assignment-feasibility-contract.js';
import {planDescriptor,planBinding,planHash,planCopy,materializePlan,validatePlanGraph,discriminateDescriptor,discriminateGraph,planExecution,planDecision,validPlanDecision,PLAN_STATUSES} from '../src/assignment-plan-feasibility.js';
const {protos}=createRequire(__filename)('@google-cloud/firestore');
const out=process.env.TF_M7_PR2A_EVIDENCE;
describe('PR2a isolated full-plan contract',()=>{
 it('keeps exact v1 vectors and explicitly discriminates new one-workout policy',()=>{
  const old=assignmentFixture(),n=planFixture(1,1);
  assert.equal(assignmentBinding(old.uid,old.descriptor).hash,'5252e6120cfb19f03f30c955c5e510bdec5e9f30f51db126ef8f11b93d0d5949');
  assert.equal(planBinding(n.uid,n.descriptor).id,assignmentBinding(old.uid,old.descriptor).id);
  assert.equal(planBinding('A',planFixture().descriptor).hash,'10ecf1aaf8995c961a9fc23628d051cfde08cf3beb124d55c005433ded765a8e');
  assert.equal(planFixture().descriptor.assignmentPlan.planHash,'22e034c871bf85ec157c149e72fc4d7a6afe1720443d5feec4de69bab9c6d60a');
  assert.equal(discriminateDescriptor(old.descriptor),1);assert.equal(discriminateDescriptor(n.descriptor),2);
  for(const [fixture,version] of [[old,1],[n,2]] as const) {
   const d:any=fixture.descriptor,aid=version===1?d.assignment.assignmentId:d.assignmentPlan.assignmentId,b=`users/${fixture.client}/workspaces/${fixture.wid}/assignedPrograms/${aid}`;
   const all=version===1?materializeAssignment(d,fixture.version,fixture.uid):materializePlan(d,fixture.version,fixture.uid),g=new Map([...all].filter(([p])=>p===b || p.startsWith(b+'/')));
   assert.equal(discriminateGraph(fixture.client,fixture.wid,aid,g),version);
   for(const policy of [null,1,3,'2',true]) {const bad=new Map(g);bad.set(b,{...bad.get(b),schedulePolicyVersion:policy});assert.throws(()=>discriminateGraph(fixture.client,fixture.wid,aid,bad));}
  }
  assert.throws(()=>assignmentDescriptor(n.descriptor));assert.throws(()=>planDescriptor(old.descriptor));
  for(const policy of [undefined,null,1,3,'2',true])assert.throws(()=>planDescriptor({...n.descriptor,assignmentPlan:{...n.descriptor.assignmentPlan,schedulePolicyVersion:policy}}));
  assert.notEqual(planBinding(n.uid,n.descriptor).hash,assignmentBinding(old.uid,old.descriptor).hash);
 });
 it('requires every source workout exactly once in source order; complete client-only graph',()=>{
  const compositions=(n:number,k:number):number[][]=>k===1?[[n]]:Array.from({length:n-k+1},(_,i)=>i+1).flatMap(x=>compositions(n-x,k-1).map(t=>[x,...t]));
  let cases=0;
  for(let w=1;w<=3;w++)for(let total=w;total<=12;total++)for(const distribution of compositions(total,w)) {
   cases++;
   const f=planFixture(w,total,"S",false,false,distribution),d=f.descriptor,g=materializePlan(d,f.version,f.uid),b=`users/${f.client}/workspaces/${f.wid}/assignedPrograms/${d.assignmentPlan.assignmentId}`;
   const own=new Map([...g].filter(([p])=>p===b || p.startsWith(b+'/')));
   assert.equal(g.size+2,6+w+total+w);assert.equal(own.size,3+w+total+w);assert.ok(validatePlanGraph(f.client,f.wid,d.assignmentPlan.assignmentId,own));
   assert.equal(validateClientAssignmentDocuments(f.client,f.wid,d.assignmentPlan.assignmentId,own),false);
   for(const [p,v] of own){const bad=new Map(own);bad.set(p,{...v,privateNote:'excluded'});assert.equal(validatePlanGraph(f.client,f.wid,d.assignmentPlan.assignmentId,bad),false);bad.delete(p);assert.equal(validatePlanGraph(f.client,f.wid,d.assignmentPlan.assignmentId,bad),false);}
   const a=d.assignmentPlan;
   for(const occurrences of [a.occurrences.slice(1),...(w>1?[[...a.occurrences].reverse()]:[]),[...a.occurrences,a.occurrences[0]],a.occurrences.map((o:any)=>({...o,workoutId:'00000000-0000-4000-8000-000000000999'}))]) {
    const changed={...d,assignmentPlan:{...a,occurrences,planHash:planHash(occurrences)}};
    assert.throws(()=>materializePlan(changed,f.version,f.uid));
   }
  }
  assert.equal(cases,298);
 });
 it('strictly binds authority/time/expiry/id/outcome and refuses altered source/hash',()=>{
  const f=planFixture(),d=f.descriptor,a=d.assignmentPlan;
  for(const patch of [{extra:1},{horizonRevision:2},{expectedHorizonRevision:1},{clientUid:'../x'},{copyPolicyVersion:2},{accessExpiresAtMillis:a.accessExpiresAtMillis+1},{preparedAtMillis:a.occurrences[0].scheduledInstantMillis},{planHash:'a'.repeat(64)}])assert.throws(()=>planDescriptor({...d,assignmentPlan:{...a,...patch}}));
  for(const n of [NaN,Infinity,-1,1.5,'1',true,null]) {const o=a.occurrences.map((x:any,i:number)=>i?x:{...x,scheduledInstantMillis:n});assert.throws(()=>planDescriptor({...d,assignmentPlan:{...a,occurrences:o,planHash:planHash(o)}}));}
  assert.throws(()=>planDescriptor({...d,idempotencyKey:[d.idempotencyKey]}));
  assert.equal(planExecution(a,a.preparedAtMillis),'current');assert.equal(planExecution(a,a.occurrences[0].scheduledInstantMillis),'plan-schedule-conflict');assert.throws(()=>planExecution(a,a.preparedAtMillis-1));
  for(const patch of [{schedulePolicyVersion:3},{occurrences:[]},{planHash:'a'.repeat(64)},{preparedAtMillis:undefined}])assert.throws(()=>planExecution({...a,...patch},a.preparedAtMillis));
  for(const invalid of [null,[],undefined])assert.throws(()=>planExecution(invalid as any,a.preparedAtMillis));
  for(const status of PLAN_STATUSES) {const v=planDecision(d,status);assert.ok(validPlanDecision(d,v));assert.equal(validPlanDecision(d,{...v,extra:true}),false);assert.equal(validPlanDecision(d,{...v,extra:undefined}),false);assert.equal(validPlanDecision(d,{...v,extra:null}),false);}

  for(const patch of [{ownerUid:'other'},{title:''},{contentHash:'a'.repeat(64)},{privateNote:'x'}])assert.throws(()=>planCopy({...f.version,...patch},f.uid,f.wid,f.version.templateId));
 });
 it('exports actual canonical serializers, documents, packets, commit protobuf and index inventory',async()=>{
  const db=new Firestore({projectId:'demo-tillfailure-m3'}),serializer=(db as any)._serializer,measurements:any[]=[];
  for(const [name,w,n,c,wide,widePath] of [ ['ascii',3,12,'S',false,false],['single',1,1,'S',false,false],['escaped',3,12,'é/"\\\n界😀',false,false],...['😀','界','"','/','\\'].flatMap(c=>[false,true].map(path=>[`boundary-${c.codePointAt(0)}-${path}`,3,12,c,true,path]))] as [string,number,number,string,boolean,boolean][]) {
   const f=planFixture(w,n,c,wide,widePath),d=f.descriptor,binding=planBinding(f.uid,d),g=materializePlan(d,f.version,f.uid),result=planDecision(d,'plan-created');
   const stored=(value:any)=>Object.fromEntries(Object.entries(value).map(([k,v])=>k==='scheduledInstantMillis' || k==='accessExpiresAtMillis'?[k.replace('Millis',''),Timestamp.fromMillis(v as number)]:[k,v]));
   const docs=new Map([...g].map(([p,v])=>[p,stored(v)]));docs.set(`planFeasibilityReceipts/${binding.id}`,{schemaVersion:1,commandKind:'program-assignment-plan-create',callerUid:f.uid,workspaceId:f.wid,requestHash:binding.hash,result,committedAt:Timestamp.fromMillis(1)});docs.set(`workspaces/${f.wid}`,{membershipRevision:2147483646});
   const writes=[...docs].map(([p,v])=>({update:{name:`projects/demo-tillfailure-m3/databases/(default)/documents/${p}`,fields:serializer.encodeFields(v)},currentDocument:{exists:p===`workspaces/${f.wid}`}}));
   const commit=protos.google.firestore.v1.CommitRequest.encode(protos.google.firestore.v1.CommitRequest.fromObject({database:'projects/demo-tillfailure-m3/databases/(default)',transaction:Buffer.alloc(9),writes})).finish().length;
   let entries=0,maxEntries=0;for(const v of docs.values()){let count=0;const walk=(o:any)=>{for(const v of Object.values(o)){count+=2;if(Array.isArray(v))count+=v.length;else if(v && typeof v==='object' && !(v instanceof Timestamp))walk(v);}};walk(v);maxEntries=Math.max(maxEntries,count);entries+=count;}
   const resolver={protocolVersion:1,descriptor:d};const row={name,source:Buffer.byteLength(JSON.stringify(f.version)),copy:Buffer.byteLength(JSON.stringify(f.copy)),request:Buffer.byteLength(JSON.stringify({data:d})),resolverUnits:JSON.stringify(resolver).length,resolverBytes:Buffer.byteLength(JSON.stringify(resolver)),maxDocument:Math.max(...[...g.values()].map(v=>Buffer.byteLength(JSON.stringify(v)))),maxItemPacket:Math.max(...f.copy.workouts.flatMap((w:any)=>w.items.map((i:any)=>Buffer.byteLength(JSON.stringify({result:{workspaceId:f.wid,clientUid:f.client,sourceContentHash:f.version.contentHash,snapshotHash:d.assignmentPlan.snapshotHash,workoutId:w.id,item:i}}))))),commit,writes:writes.length,indexEntries:entries,maxEntries};
   assert.ok(commit<=524288);assert.ok(writes.length<=24);assert.ok(row.request<=32768 && row.resolverUnits<=8192 && row.resolverBytes<=24576 && row.maxItemPacket<=65536);assert.ok(maxEntries<=64);measurements.push(row);
   if(out){await mkdir(`${out}/ts`,{recursive:true});await writeFile(`${out}/ts/${name}.json`,JSON.stringify({...f,binding,documents:[...g]}));}
  }
  console.log('M7_PLAN_SERIALIZERS',JSON.stringify(measurements));if(out)await writeFile(`${out}/serializer-measurements.json`,JSON.stringify(measurements,null,2));
  await db.terminate();
 });
});
