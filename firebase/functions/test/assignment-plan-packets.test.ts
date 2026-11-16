import assert from 'node:assert/strict';
import {describe,it} from 'node:test';
import {mkdir,writeFile} from 'node:fs/promises';
import {planFixture} from './assignment-plan-fixture.js';
import {assignmentFixture} from './assignment-pr1a-fixture.js';
import {materializePlan} from '../src/assignment-plan-feasibility.js';
import {materializeAssignment} from '../src/assignment-feasibility-contract.js';
import {metadataView,readPacket,PacketAssembly,packetBytes,serverTimeBound} from '../src/assignment-plan-packets-feasibility.js';
import {stableHash} from '../src/hashing.js';
import {publicationContentHash,publicationDraftHash,publicationDraft} from '../src/program-publication-contract.js';
import {assignmentCopyHash} from '../src/assignment-feasibility-contract.js';
import {sourceIdentity} from '../src/program-drafts.js';
type Data=Record<string,any>;
function prepared(old=false,count=12,char='S',wide=false,path=false,workouts=3) {
 const f=old?assignmentFixture(char,count,wide,path):planFixture(workouts,count,char,wide,path),d=f.descriptor as Data,a=old?d.assignment:d.assignmentPlan;
 const b=`users/${f.client}/workspaces/${f.wid}/assignedPrograms/${a.assignmentId}`,g=new Map([...(old?materializeAssignment(d,f.version,f.uid):materializePlan(d,f.version,f.uid))].filter(([p])=>p===b || p.startsWith(b+'/')));
 const now=a.preparedAtMillis,first=readPacket(f.client,f.wid,a.assignmentId,g,'metadata',0,null,now),packets=[first];
 for(let n=1;n<first.totalPackets-1;n++)packets.push(readPacket(f.client,f.wid,a.assignmentId,g,'items',n,first.binding.metadataHash,now+n));
 packets.push(readPacket(f.client,f.wid,a.assignmentId,g,'final',first.totalPackets-1,first.binding.metadataHash,now+first.totalPackets));
 return {...f,a,b,g,now,packets};
}
const wire=(p:Data)=>JSON.stringify({result:p});
function complete(f:ReturnType<typeof prepared>,packets=f.packets) {const session=new PacketAssembly(f.client,f.wid,f.a.assignmentId);packets.forEach((p,n)=>session.accept(wire(p),n*10,n*10+1));return session.result(100);}
function rejected(f:ReturnType<typeof prepared>,packets:Data[]) {const s=new PacketAssembly(f.client,f.wid,f.a.assignmentId);assert.throws(()=>{packets.forEach((p,n)=>s.accept(wire(p),n*10,n*10+1));s.result(100);});assert.throws(()=>s.result(100));}
describe('isolated eight-call full-document read packets',()=>{
 it('independently validates metadata, then reconstructs exact old/new graphs at stable packet order',()=>{
  for(const old of [false,true])for(let count=old?1:3;count<=12;count++) {
   const f=prepared(old,count),first=f.packets[0]!;const m=metadataView(f.client,f.wid,f.a.assignmentId,first.metadata);
   assert.equal(m.slots.length,count);assert.equal(first.totalPackets,2+Math.ceil(count/2));assert.ok(first.totalPackets<=8);
   const out=complete(f);assert.equal(out.size,f.g.size);for(const [p,v] of out)assert.deepEqual(v,f.g.get(p?`${f.b}/${p}`:f.b));
   const early=new PacketAssembly(f.client,f.wid,f.a.assignmentId);early.accept(wire(first),0,1);assert.throws(()=>early.result(1));
  }
 });

 it('preserves a valid empty description and multiline escaped instructions',()=>{
  for(const old of [false,true]) {
   const f=prepared(old),v=f.version as Data,d=f.descriptor as Data;
   v.description='';
   // Exercise allowed literal newline and JSON quote/backslash escaping without trimming.
   v.workouts[0].items[0].instructions='Line one\n"quoted" \\ line two';
   const item=v.workouts[0].items[0];Object.assign(item,sourceIdentity('system',item.sourceId,f.wid,f.uid,{schemaVersion:1,name:item.name,nameNormalized:'synthetic',instructions:item.instructions,status:'published',muscleGroups:item.muscleGroups,equipment:item.equipment}));
   const {schemaVersion,versionId,versionNumber,draftRevision,draftHash,contentHash,publishedAtMillis,...content}=v;
   v.contentHash=publicationContentHash(content);v.draftHash=publicationDraftHash(publicationDraft(content));
   const a=old?d.assignment:d.assignmentPlan;a.sourceContentHash=v.contentHash;a.snapshotHash=assignmentCopyHash({copyPolicyVersion:1,title:v.title,description:v.description,workouts:v.workouts});
   const g=new Map([...(old?materializeAssignment(d,v,f.uid):materializePlan(d,v,f.uid))].filter(([p])=>p===f.b || p.startsWith(f.b+'/')));
   const meta=readPacket(f.client,f.wid,a.assignmentId,g,'metadata',0,null,f.now),packets=[meta];metadataView(f.client,f.wid,a.assignmentId,meta.metadata);
   for(let n=1;n<meta.totalPackets-1;n++)packets.push(readPacket(f.client,f.wid,a.assignmentId,g,'items',n,meta.binding.metadataHash,f.now+n));
   packets.push(readPacket(f.client,f.wid,a.assignmentId,g,'final',meta.totalPackets-1,meta.binding.metadataHash,f.now+8));
   const result=complete({...f,g,packets},packets);assert.equal(result.get('snapshots/content')!.description,'');
  }
 });
 it('rejects malformed metadata without consulting items or a source, including recomputed metadata digests',()=>{
  const f=prepared();const edits=[
   (m:Data)=>delete m.snapshot,
   (m:Data)=>m.header.privateNote='excluded',
   (m:Data)=>m.header.schedulePolicyVersion='2',
   (m:Data)=>m.header.sourceVersionNumber=1.5,
   (m:Data)=>m.header.clientId='other',
   (m:Data)=>m.snapshot.workoutIds.reverse(),
   (m:Data)=>m.workouts.reverse(),
   (m:Data)=>m.workouts[0].itemIds.push(m.workouts[0].itemIds[0]),
   (m:Data)=>m.workouts[0].title='',
   (m:Data)=>m.plans.reverse(),
   (m:Data)=>m.plans[1].plannedWorkoutId=m.plans[0].plannedWorkoutId,
   (m:Data)=>m.inventory.requiredPaths.pop(),
   (m:Data)=>m.inventory.requiredPaths.reverse(),
   (m:Data)=>m.inventory.requiredPaths.push('privateNotes/foreign'),
   (m:Data)=>m.inventory.planHash='a'.repeat(64),
   (m:Data)=>m.header.accessExpiresAtMillis++,
   (m:Data)=>m.extra=true
  ];
  for(const edit of edits) {const p=structuredClone(f.packets[0]!);edit(p.metadata);p.binding.metadataHash=stableHash(p.metadata);assert.throws(()=>metadataView(f.client,f.wid,f.a.assignmentId,p.metadata));rejected(f,[p,...f.packets.slice(1)]);}
 });
 it('fails closed on missing/extra/duplicate/reordered/mixed/incomplete/corrupt packets and changed final headers',()=>{
  const f=prepared();const p=f.packets;
  for(let i=0;i<p.length;i++){rejected(f,p.filter((_,n)=>n!==i));rejected(f,[...p.slice(0,i),p[i]!,...p.slice(i)]);}
  rejected(f,[...p,p.at(-1)!]);rejected(f,[p[0]!,p[2]!,p[1]!,...p.slice(3)]);
  const edits=[
   (q:Data[])=>q[1]!.items.reverse(),
   (q:Data[])=>q[1]!.items.pop(),
   (q:Data[])=>q[1]!.items.push(q[1]!.items[0]),
   (q:Data[])=>delete q[1]!.items[0].document.instructions,
   (q:Data[])=>q[1]!.items[0].document.name+='changed',
   (q:Data[])=>q[1]!.items[0].document.privateNote=true,
   (q:Data[])=>q[1]!.items[0].document.assignmentId='00000000-0000-4000-8000-000000000999',
   (q:Data[])=>q[1]!.binding.planHash='a'.repeat(64),
   (q:Data[])=>q[1]!.binding.assignmentId='00000000-0000-4000-8000-000000000999',
   (q:Data[])=>q[1]!.packetIndex=2,
   (q:Data[])=>q[1]!.totalPackets=7,
   (q:Data[])=>q[1]!.serverNowMillis='1',
   (q:Data[])=>q[1]!.serverNowMillis=f.now+0.5,
   (q:Data[])=>q[1]!.serverNowMillis=null,
   (q:Data[])=>q[1]!.serverNowMillis=f.now-1,
   (q:Data[])=>delete q[1]!.serverNowMillis,
   (q:Data[])=>q[1]!.extra=true,
   (q:Data[])=>q.at(-1)!.header.accessExpiresAtMillis++,
   (q:Data[])=>q.at(-1)!.header.lifecycleState='cancelled'
  ];
  for(const edit of edits){const q=structuredClone(p);edit(q);rejected(f,q);}
  const s=new PacketAssembly(f.client,f.wid,f.a.assignmentId);assert.throws(()=>s.accept(wire(p[0]!)+ ' '.repeat(65536),0,1));
  // Same immutable source but different assignment: neither envelope nor item mixing is accepted.
  const other=prepared();const d=other.descriptor as Data;d.assignmentPlan.assignmentId='00000000-0000-4000-8000-000000000999';
  const g=new Map([...materializePlan(d,other.version,other.uid)].filter(([path])=>path.startsWith(`users/${other.client}/`)));
  const meta=readPacket(other.client,other.wid,d.assignmentPlan.assignmentId,g,'metadata',0,null,other.now);
  const mixed=readPacket(other.client,other.wid,d.assignmentPlan.assignmentId,g,'items',1,meta.binding.metadataHash,other.now+1);
  rejected(f,[p[0]!,mixed,...p.slice(2)]);
  const q=structuredClone(p);q[1]!.items=mixed.items;rejected(f,q);
 });
 it('uses conservative server-time/monotonic bounds, clears on denial, and revalidates the full graph for each packet',()=>{
  const f=prepared();const p=f.packets;
  assert.equal(serverTimeBound(f.now,f.now,f.a.accessExpiresAtMillis),f.now);
  for(const now of [f.now-1,f.a.accessExpiresAtMillis,f.now+0.5,NaN])assert.throws(()=>serverTimeBound(f.now,now,f.a.accessExpiresAtMillis));
  assert.throws(()=>readPacket(f.client,f.wid,f.a.assignmentId,f.g,'metadata',0,null,f.a.accessExpiresAtMillis));
  assert.throws(()=>readPacket(f.client,f.wid,f.a.assignmentId,f.g,'items',1,'a'.repeat(64),f.now));
  const partial=new Map(f.g);partial.delete(`${f.b}/${p[0]!.metadata.inventory.requiredPaths[0]}`);assert.throws(()=>readPacket(f.client,f.wid,f.a.assignmentId,partial,'final',7,p[0]!.binding.metadataHash,f.now));
  const broken=new Map(f.g);broken.set(f.b,{...broken.get(f.b),accessStatus:'revoked'});assert.throws(()=>readPacket(f.client,f.wid,f.a.assignmentId,broken,'items',1,p[0]!.binding.metadataHash,f.now));
  const s=new PacketAssembly(f.client,f.wid,f.a.assignmentId);s.accept(wire(p[0]!),0,1);s.deny();assert.throws(()=>s.accept(wire(p[1]!),2,3));assert.throws(()=>s.result(3));
  const q=structuredClone(p);q[0]!.serverNowMillis=f.a.accessExpiresAtMillis-1;const delayed=new PacketAssembly(f.client,f.wid,f.a.assignmentId);assert.throws(()=>delayed.accept(wire(q[0]!),0,0.01)); // ceil(0.01) consumes final ms
  const expiring=structuredClone(p);expiring[1]!.serverNowMillis=f.a.accessExpiresAtMillis;rejected(f,expiring);
  const full=new PacketAssembly(f.client,f.wid,f.a.assignmentId);const near=structuredClone(p);near.forEach((packet,n)=>{packet.serverNowMillis=f.a.accessExpiresAtMillis-100+n;full.accept(wire(packet),n*2,n*2+1);});assert.ok(full.result(20));assert.throws(()=>full.result(100));
 });
 it('measures complete serialized packets at text/escaping/ID/wrapper maxima without truncation',async()=>{
  const out=process.env.TF_M7_EIGHT_EVIDENCE,rows:Data[]=[];
  for(const old of [false,true])for(const w of old?[1]:[1,2,3])for(const path of [false,true])for(const char of ['😀','界','"','\\','/']) {
   const f=prepared(old,12,char,true,path,w),sizes=f.packets.map(packetBytes),view=metadataView(f.client,f.wid,f.a.assignmentId,f.packets[0]!.metadata);
   assert.equal(f.packets.length,8);assert.equal(complete(f).size,f.g.size);
   const docSum=[...view.documents.values()].reduce((n,d)=>n+Buffer.byteLength(JSON.stringify(d)),0);
   const overhead=Math.max(...f.packets.map(p=>packetBytes(p)-(p.kind==='metadata'?docSum:p.kind==='items'?p.items.reduce((n:number,i:Data)=>n+Buffer.byteLength(JSON.stringify(i.document)),0):Buffer.byteLength(JSON.stringify(p.header)))));
   assert.ok(overhead<=5120);assert.ok(sizes.every(n=>n<=65536));
   rows.push({policy:old?1:2,workouts:w,path: path?'wide-utf8':'escaped-controls',character:char.codePointAt(0),metadata:sizes[0],items:Math.max(...sizes.slice(1,-1)),final:sizes.at(-1),cumulative:sizes.reduce((n,x)=>n+x,0),overhead,metadataDocuments:view.documents.size});
  }
  const bounds={metadata:4096+8192+8192+6*4096+5120,items:2*24576+5120,final:4096+5120};for(const n of Object.values(bounds))assert.ok(n<=65536);
  console.log('EIGHT_PACKET_SIZES',JSON.stringify({fixtures:rows.length,maxima:Object.fromEntries(['metadata','items','final','cumulative','overhead'].map(k=>[k,Math.max(...rows.map(r=>r[k]))])),modeledBounds:bounds}));
  if(out){await mkdir(out,{recursive:true});await writeFile(`${out}/packet-sizes.json`,JSON.stringify({rows,modeledBounds:bounds},null,2));}
 });
});
