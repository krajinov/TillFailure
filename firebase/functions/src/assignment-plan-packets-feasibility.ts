// Isolated eight-call feasibility. No product transport, ledger, cache or UI imports this module.
import {exerciseExact as exact,exerciseId,exerciseText,exerciseBytes as bytes} from './custom-exercise-contract.js';
import {participantUid,strictPathIdentifier} from './input-validation.js';
import {validPublicationContent,publicationVersionId} from './program-publication-contract.js';
import {DAY_MS,millis} from './assignment-feasibility-contract.js';
import {discriminateGraph,planHash} from './assignment-plan-feasibility.js';
import {stableHash} from './hashing.js';
type Data=Record<string,any>;
export type PacketKind='metadata'|'items'|'final';
export const PACKET_CAP=65536;
const hash=(x:unknown)=>typeof x==='string' && /^[a-f0-9]{64}$/.test(x);
const assert=(ok:unknown):void=>{if(!ok)throw Error('packet-unavailable');};
const same=(a:unknown,b:unknown)=>stableHash(a)===stableHash(b);
const headerFields=['schemaVersion','workspaceId','clientId','assignmentId','snapshotId','sourceTemplateId','sourceVersionId','sourceVersionNumber','sourceContentHash','trainerId','snapshotHash','revision','lifecycleState','accessStatus','scheduledInstantMillis','accessExpiresAtMillis','timeZoneId'];
const provenance=['sourceTemplateId','sourceVersionId','sourceVersionNumber','sourceContentHash'];
const identityFields=['schemaVersion','workspaceId','clientId','assignmentId','snapshotId'];
function boundedDocument(path:string,v:Data) {assert(v && bytes(v)<=(path.includes('/exercises/')?24576:path==='snapshots/content' || path==='manifests/download'?8192:4096));}
export interface MetadataView {binding:Data;documents:Map<string,Data>;slots:{workoutId:string;itemId:string;path:string}[];header:Data;itemPackets:number}
/** Independent metadata validation: no item bytes, source DTO, network, authority or prior packet required. */
export function metadataView(client:string,wid:string,aid:string,value:unknown):MetadataView {
 participantUid(client,'client');participantUid(strictPathIdentifier(wid,'workspace'),'workspace');assert(exerciseId(aid));
 assert(exact(value,['header','inventory','snapshot','workouts','plans']));const m=value as Data,h=m.header,s=m.snapshot,inv=m.inventory;
 const policy=Object.hasOwn(h??{},'schedulePolicyVersion')?h.schedulePolicyVersion:1;assert(policy===1 || policy===2);
 assert(exact(h,[...headerFields,...(policy===2?['schedulePolicyVersion','preparedAtMillis','horizonRevision','planHash','plannedWorkoutIds']:[])]));
 participantUid(h.trainerId,'trainer');assert(h.trainerId!==client && h.schemaVersion===1 && h.workspaceId===wid && h.clientId===client && h.assignmentId===aid && h.snapshotId==='content' && h.revision===1 && h.lifecycleState==='ready' && h.accessStatus==='active' && h.timeZoneId==='UTC' && exerciseId(h.sourceTemplateId) && Number.isInteger(h.sourceVersionNumber) && h.sourceVersionNumber>=1 && h.sourceVersionNumber<=2147483645 && h.sourceVersionId===publicationVersionId(h.sourceVersionNumber) && hash(h.sourceContentHash) && hash(h.snapshotHash) && millis(h.scheduledInstantMillis) && millis(h.accessExpiresAtMillis));
 const id={schemaVersion:1,workspaceId:wid,clientId:client,assignmentId:aid,snapshotId:'content',...(policy===2?{schedulePolicyVersion:2}:{})};
 const idFields=[...identityFields,...(policy===2?['schedulePolicyVersion']:[])];
 const tuple=(v:Data)=>Object.entries(id).every(([k,x])=>v?.[k]===x);
 assert(exact(s,[...idFields,...provenance,'contentHash','copyPolicyVersion','title','description','workoutIds']) && tuple(s) && provenance.every(k=>s[k]===h[k]) && s.copyPolicyVersion===1 && s.contentHash===h.snapshotHash && exerciseText(s.title,80) && (s.description==='' || exerciseText(s.description,240,true)));
 assert(Array.isArray(s.workoutIds) && s.workoutIds.length>=1 && s.workoutIds.length<=(policy===1?1:3) && s.workoutIds.every(exerciseId) && new Set(s.workoutIds).size===s.workoutIds.length);
 assert(Array.isArray(m.workouts) && m.workouts.length===s.workoutIds.length && Array.isArray(m.plans) && m.plans.length===s.workoutIds.length);
 const docs=new Map<string,Data>([['',h],['manifests/download',inv],['snapshots/content',s]]),slots:MetadataView['slots']=[],itemIds=new Set<string>(),planIds=new Set<string>(),occurrences:Data[]=[];
 for(let n=0;n<s.workoutIds.length;n++) {
  const wid=s.workoutIds[n],w=m.workouts[n],p=m.plans[n];
  assert(exact(w,[...idFields,'workoutId','title','itemIds']) && tuple(w) && w.workoutId===wid && exerciseText(w.title,40) && Array.isArray(w.itemIds) && w.itemIds.length>=1 && w.itemIds.length<=12);
  for(const itemId of w.itemIds) {assert(exerciseId(itemId) && !itemIds.has(itemId));itemIds.add(itemId);slots.push({workoutId:wid,itemId,path:`snapshots/content/workouts/${wid}/exercises/${itemId}`});}
  assert(itemIds.size<=12);
  assert(exact(p,[...idFields,'plannedWorkoutId','workoutId','scheduledInstantMillis','timeZoneId','status','revision',...(policy===2?['horizonRevision']:[])]) && tuple(p) && exerciseId(p.plannedWorkoutId) && !planIds.has(p.plannedWorkoutId) && p.workoutId===wid && p.timeZoneId==='UTC' && p.status==='planned' && p.revision===1 && millis(p.scheduledInstantMillis));
  planIds.add(p.plannedWorkoutId);occurrences.push({plannedWorkoutId:p.plannedWorkoutId,workoutId:wid,scheduledInstantMillis:p.scheduledInstantMillis});
  docs.set(`snapshots/content/workouts/${wid}`,w);docs.set(`plannedWorkouts/${p.plannedWorkoutId}`,p);
 }
 assert(occurrences[0]!.scheduledInstantMillis===h.scheduledInstantMillis && h.accessExpiresAtMillis===occurrences.at(-1)!.scheduledInstantMillis+DAY_MS);
 if(policy===2) {
  assert(millis(h.preparedAtMillis) && h.horizonRevision===1 && hash(h.planHash) && same(h.plannedWorkoutIds,occurrences.map(o=>o.plannedWorkoutId)) && h.planHash===planHash(occurrences));
  let last=h.preparedAtMillis;for(const o of occurrences){assert(o.scheduledInstantMillis>last && o.scheduledInstantMillis-h.preparedAtMillis<=DAY_MS);last=o.scheduledInstantMillis;}assert(m.plans.every((p:Data)=>p.horizonRevision===1));
 }
 assert(exact(inv,[...idFields,'assignmentRevision','snapshotHash','requiredPaths',...(policy===2?['horizonRevision','planHash']:[])]) && tuple(inv) && inv.assignmentRevision===1 && inv.snapshotHash===h.snapshotHash && (policy===1 || inv.horizonRevision===1 && inv.planHash===h.planHash));
 const required=[...docs.keys()].filter(p=>p!=='' && p!=='manifests/download').concat(slots.map(s=>s.path)).sort();assert(same(inv.requiredPaths,required));
 for(const [path,v] of docs)boundedDocument(path,v);
 const binding={workspaceId:wid,clientUid:client,assignmentId:aid,schedulePolicyVersion:policy,assignmentRevision:1,horizonRevision:1,sourceContentHash:h.sourceContentHash,snapshotHash:h.snapshotHash,planHash:policy===2?h.planHash:null,metadataHash:stableHash(m)};
 assert(bytes(binding)<=4096);
 return {binding,documents:docs,slots,header:h,itemPackets:Math.ceil(slots.length/2)};
}
function metadataFromGraph(client:string,wid:string,aid:string,g:Map<string,Data>):Data {
 const b=`users/${client}/workspaces/${wid}/assignedPrograms/${aid}`,h=g.get(b)!,s=g.get(`${b}/snapshots/content`)!;
 const workouts=s.workoutIds.map((id:string)=>g.get(`${b}/snapshots/content/workouts/${id}`)!);
 const plans=[...g].filter(([p])=>p.startsWith(`${b}/plannedWorkouts/`)).map(([,v])=>v);
 return {header:h,inventory:g.get(`${b}/manifests/download`),snapshot:s,workouts,plans:workouts.map((w:Data)=>plans.find(p=>p.workoutId===w.workoutId))};
}
/** Caller supplies a freshly authorized complete graph on EVERY invocation. No state is cached. */
export function readPacket(client:string,wid:string,aid:string,g:Map<string,Data>,kind:PacketKind,index:number,expectedMetadataHash:string|null,now:number):Data {
 discriminateGraph(client,wid,aid,g);
 const metadata=metadataFromGraph(client,wid,aid,g),v=metadataView(client,wid,aid,metadata),h=v.header,b=`users/${client}/workspaces/${wid}/assignedPrograms/${aid}`;
 assert(millis(now) && now<h.accessExpiresAtMillis && (h.schedulePolicyVersion!==2 || now>=h.preparedAtMillis));
 for(const [p,doc] of g)boundedDocument(p===b?'':p.slice(b.length+1),doc);
 const totalPackets=v.itemPackets+2;let payload:Data;
 if(kind==='metadata') {assert(index===0 && expectedMetadataHash===null);payload={metadata};}
 else {
  assert(hash(expectedMetadataHash) && expectedMetadataHash===v.binding.metadataHash);
  if(kind==='items') {
   assert(Number.isInteger(index) && index>=1 && index<=v.itemPackets);
   payload={items:v.slots.slice((index-1)*2,index*2).map(s=>({workoutId:s.workoutId,itemId:s.itemId,document:g.get(`${b}/${s.path}`)}))};
  }else {assert(kind==='final' && index===totalPackets-1);payload={header:h};}
 }
 const p={packetVersion:1,kind,binding:v.binding,packetIndex:index,totalPackets,serverNowMillis:now,...payload};
 assert(bytes({result:p})<=PACKET_CAP);return p;
}
function decodePacket(body:string):Data {
 assert(typeof body==='string' && Buffer.byteLength(body,'utf8')<=PACKET_CAP);const envelope=JSON.parse(body);assert(exact(envelope,['result']));const p=envelope.result;
 assert(p && ['metadata','items','final'].includes(p.kind) && exact(p,['packetVersion','kind','binding','packetIndex','totalPackets','serverNowMillis',p.kind==='metadata'?'metadata':p.kind==='items'?'items':'header']) && p.packetVersion===1 && Number.isInteger(p.packetIndex) && Number.isInteger(p.totalPackets) && p.totalPackets>=3 && p.totalPackets<=8 && millis(p.serverNowMillis));return p;
}
/** Memory-only receiving regression model. No authority grant or persistent snapshot/cache. */
export class PacketAssembly {
 private view:MetadataView|undefined;private docs=new Map<string,Data>();private next=0;private lastServer=-1;private lastReceived=-1;private deadline=Infinity;private closed=false;private complete=false;
 constructor(private client:string,private wid:string,private aid:string){}
 deny():void {this.closed=true;this.complete=false;this.docs.clear();this.view=undefined;this.deadline=-Infinity;}
 accept(body:string,startedMonotonic:number,receivedMonotonic:number):void {
  try {
   assert(!this.closed && !this.complete && Number.isFinite(startedMonotonic) && startedMonotonic>=0 && Number.isFinite(receivedMonotonic) && receivedMonotonic>=startedMonotonic && startedMonotonic>=this.lastReceived);
   const p=decodePacket(body);assert(p.packetIndex===this.next && p.serverNowMillis>=this.lastServer);
   if(this.next===0) {
    assert(p.kind==='metadata');const v=metadataView(this.client,this.wid,this.aid,p.metadata);assert(same(v.binding,p.binding) && p.totalPackets===v.itemPackets+2);
    this.view=v;this.docs=new Map(v.documents);
   }
   const v=this.view!;assert(same(v.binding,p.binding) && p.totalPackets===v.itemPackets+2);
   const remaining=v.header.accessExpiresAtMillis-p.serverNowMillis-Math.ceil(receivedMonotonic-startedMonotonic);
   assert(remaining>0);this.deadline=Math.min(this.deadline,receivedMonotonic+remaining);assert(receivedMonotonic<this.deadline);
   if(this.next>0 && this.next<p.totalPackets-1) {
    assert(p.kind==='items' && Array.isArray(p.items));const expected=v.slots.slice((this.next-1)*2,this.next*2);assert(p.items.length===expected.length);
    for(let i=0;i<expected.length;i++) {
     const slot=expected[i]!,entry=p.items[i],d=entry?.document;
     assert(exact(entry,['workoutId','itemId','document']) && entry.workoutId===slot.workoutId && entry.itemId===slot.itemId);
     const idFields=[...identityFields,...(v.binding.schedulePolicyVersion===2?['schedulePolicyVersion']:[])];
     assert(exact(d,[...idFields,'workoutId','itemId','sourceKind','sourceId','sourceRevision','sourceHash','name','instructions','muscleGroups','equipment','prescription']));
     assert(d.schemaVersion===1 && d.workspaceId===this.wid && d.clientId===this.client && d.assignmentId===this.aid && d.snapshotId==='content' && d.workoutId===slot.workoutId && d.itemId===slot.itemId && (v.binding.schedulePolicyVersion===1 || d.schedulePolicyVersion===2));
     const {schemaVersion,workspaceId,clientId,assignmentId,snapshotId,schedulePolicyVersion,workoutId,itemId,...fields}=d;
     assert(validPublicationContent({contentPolicyVersion:1,workspaceId:this.wid,templateId:v.header.sourceTemplateId,ownerUid:v.header.trainerId,title:'V',description:'',workouts:[{id:slot.workoutId,title:'W',items:[{id:slot.itemId,...fields}]}]}));
     boundedDocument(slot.path,d);assert(!this.docs.has(slot.path));this.docs.set(slot.path,d);
    }
   }else if(this.next===p.totalPackets-1) {
    assert(p.kind==='final' && same(p.header,v.header));
    const b=`users/${this.client}/workspaces/${this.wid}/assignedPrograms/${this.aid}`,full=new Map([...this.docs].map(([path,d])=>[path?`${b}/${path}`:b,d]));
    assert(discriminateGraph(this.client,this.wid,this.aid,full)===v.binding.schedulePolicyVersion);this.complete=true;
   }
   this.next++;this.lastServer=p.serverNowMillis;this.lastReceived=receivedMonotonic;
  }catch{this.deny();throw Error('packet-unavailable');}
 }
 result(nowMonotonic:number):Map<string,Data> {
  if(!this.complete || this.closed || !Number.isFinite(nowMonotonic) || nowMonotonic<this.lastReceived || nowMonotonic>=this.deadline){this.deny();throw Error('packet-unavailable');}
  // Return a detached transient copy; failed/expired sessions retain no renderable state.
  return structuredClone(this.docs);
 }
}
export const packetBytes=(packet:Data)=>bytes({result:packet});

/** Server-side clock regression/expiry guard, independent of device wall time. */
export function serverTimeBound(startedMillis:number,nowMillis:number,expiresMillis:number):number {
 assert(millis(startedMillis) && millis(nowMillis) && millis(expiresMillis) && nowMillis>=startedMillis && nowMillis<expiresMillis);return nowMillis;
}
