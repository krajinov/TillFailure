// PR 2a isolated contracts. No product import, endpoint, ledger dispatch or issuance.
import { commandId, stableHash } from './hashing.js';
import { participantUid, strictPathIdentifier } from './input-validation.js';
import { exerciseExact as exact, exerciseId, exerciseBytes as bytes } from './custom-exercise-contract.js';
import { validPublicationContent, publicationContentHash, publicationDraft, publicationDraftHash, publicationVersionId } from './program-publication-contract.js';
import { ASSIGNMENT_AUTHORITY, DAY_MS, millis, assignmentCopyHash, assignmentDescriptor, validateClientAssignmentDocuments } from './assignment-feasibility-contract.js';
type Data = Record<string, any>;
export const PLAN_KIND='program-assignment-plan-create', PLAN_HASH_VERSION='program-assignment-plan-v1';
export const PLAN_STATUSES=['plan-created','plan-schedule-conflict','plan-source-conflict','plan-copy-conflict','plan-id-conflict'];
const fields=['assignmentId','clientUid','templateId','versionId','versionNumber','sourceContentHash','copyPolicyVersion','snapshotHash','authority','preparedAtMillis','accessExpiresAtMillis','schedulePolicyVersion','expectedHorizonRevision','horizonRevision','planHash','occurrences'];
const hash=(x:unknown)=>typeof x==='string' && /^[a-f0-9]{64}$/.test(x);
export const planHash=(occurrences:Data[])=>stableHash({hashVersion:'program-assignment-horizon-v1',schedulePolicyVersion:2,horizonRevision:1,occurrences});
export function planDescriptor(raw:unknown):Data {
  if(!exact(raw,['action','workspaceId','idempotencyKey','assignmentPlan']) || raw.action!=='assign-program-plan' || typeof raw.idempotencyKey!=='string' || !/^or1-b91f0176ec74475e8cf7406e25b7e9d3-[A-Za-z0-9_-]{1,80}$/.test(raw.idempotencyKey))throw Error('plan-invalid');
  participantUid(strictPathIdentifier(raw.workspaceId,'workspace'),'workspace');
  const a=raw.assignmentPlan;
  if(!exact(a,fields) || !exerciseId(a.assignmentId) || !exerciseId(a.templateId) || !Number.isInteger(a.versionNumber) || a.versionNumber<1 || a.versionNumber>2147483645 || a.versionId!==publicationVersionId(a.versionNumber) || !hash(a.sourceContentHash) || !hash(a.snapshotHash) || !hash(a.planHash) || a.copyPolicyVersion!==1 || a.schedulePolicyVersion!==2 || a.expectedHorizonRevision!==0 || a.horizonRevision!==1 || !exact(a.authority,[...ASSIGNMENT_AUTHORITY]) || !ASSIGNMENT_AUTHORITY.every(k=>Number.isInteger(a.authority[k]) && a.authority[k]>=1 && a.authority[k]<=2147483646) || a.authority.workspaceMembershipRevision>=2147483646 || !millis(a.preparedAtMillis) || !millis(a.accessExpiresAtMillis) || !Array.isArray(a.occurrences) || a.occurrences.length<1 || a.occurrences.length>3)throw Error('plan-invalid');
  participantUid(a.clientUid,'client');
  const ids=new Set(),workouts=new Set();let last=a.preparedAtMillis;
  for(const o of a.occurrences) {
    if(!exact(o,['plannedWorkoutId','workoutId','scheduledInstantMillis']) || !exerciseId(o.plannedWorkoutId) || !exerciseId(o.workoutId) || ids.has(o.plannedWorkoutId) || workouts.has(o.workoutId) || !millis(o.scheduledInstantMillis) || o.scheduledInstantMillis<=last || o.scheduledInstantMillis-a.preparedAtMillis>DAY_MS)throw Error('plan-invalid');
    ids.add(o.plannedWorkoutId);workouts.add(o.workoutId);last=o.scheduledInstantMillis;
  }
  if(last>253402300799999-DAY_MS || a.accessExpiresAtMillis!==last+DAY_MS || a.planHash!==planHash(a.occurrences))throw Error('plan-invalid');
  const resolver={protocolVersion:1,descriptor:raw};
  if(JSON.stringify(resolver).length>8192 || bytes(resolver)>24576 || bytes({data:raw})>32768 || bytes({data:resolver})>32768)throw Error('plan-oversize');
  return raw;
}
export function discriminateDescriptor(raw:unknown):1|2 { try {assignmentDescriptor(raw);return 1;}catch{} planDescriptor(raw);return 2; }
export function planBinding(uid:string,raw:unknown) {participantUid(uid,'caller');const d=planDescriptor(raw);if(uid===d.assignmentPlan.clientUid)throw Error('plan-self');return {id:commandId(uid,d.idempotencyKey),hash:stableHash({...d,callerUid:uid,policyVersion:1})};}
export function planCopy(v:Data,uid:string,wid:string,tid:string):Data {
  const meta=['schemaVersion','versionId','versionNumber','draftRevision','draftHash','contentHash','publishedAtMillis'];
  if(!exact(v,[...meta,'contentPolicyVersion','workspaceId','templateId','ownerUid','title','description','workouts']) || v.schemaVersion!==1 || v.ownerUid!==uid || v.workspaceId!==wid || v.templateId!==tid || !Number.isInteger(v.versionNumber) || v.versionNumber<1 || v.versionNumber>2147483645 || v.versionId!==publicationVersionId(v.versionNumber) || !Number.isInteger(v.draftRevision) || v.draftRevision<v.versionNumber || v.draftRevision>2147483645 || !millis(v.publishedAtMillis) || bytes(v)>163840)throw Error('plan-source-unavailable');
  const c=Object.fromEntries(Object.entries(v).filter(([k])=>!meta.includes(k)));
  if(!validPublicationContent(c) || publicationContentHash(c)!==v.contentHash || publicationDraftHash(publicationDraft(c))!==v.draftHash)throw Error('plan-source-unavailable');
  return {copyPolicyVersion:1,title:c.title,description:c.description,workouts:c.workouts};
}
export function planExecution(a:Data,now:number) {
  // This helper is independently fail-closed, even without an upstream writer/parser.
  planDescriptor({action:'assign-program-plan',workspaceId:'timing-validation',idempotencyKey:'or1-b91f0176ec74475e8cf7406e25b7e9d3-timing-validation',assignmentPlan:a});
  if(!millis(now) || now<a.preparedAtMillis)throw Error('plan-clock-unavailable');
  return now<a.occurrences[0].scheduledInstantMillis?'current':'plan-schedule-conflict';
}
export function planDecision(d:Data,status:string):Data {
  planDescriptor(d);if(!PLAN_STATUSES.includes(status))throw Error('plan-decision-invalid');const a=d.assignmentPlan,ok=status==='plan-created';
  return {status,workspaceId:d.workspaceId,clientUid:a.clientUid,assignmentId:a.assignmentId,assignmentRevision:ok?1:0,horizonRevision:ok?1:0,planHash:ok?a.planHash:null,plannedWorkoutCount:ok?a.occurrences.length:0,snapshotHash:ok?a.snapshotHash:null};
}
export const validPlanDecision=(d:Data,v:Data)=>exact(v,['status','workspaceId','clientUid','assignmentId','assignmentRevision','horizonRevision','planHash','plannedWorkoutCount','snapshotHash']) && PLAN_STATUSES.includes(v.status) && stableHash(planDecision(d,v.status))===stableHash(v);
function graph(d:Data,copy:Data,uid:string):Map<string,Data> {
  const a=d.assignmentPlan,b=`users/${a.clientUid}/workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}`;
  const identity={schemaVersion:1,workspaceId:d.workspaceId,clientId:a.clientUid,assignmentId:a.assignmentId,schedulePolicyVersion:2};
  const ci={...identity,snapshotId:'content'}, provenance={sourceTemplateId:a.templateId,sourceVersionId:a.versionId,sourceVersionNumber:a.versionNumber,sourceContentHash:a.sourceContentHash};
  const out=new Map<string,Data>();
  out.set(b,{...ci,...provenance,trainerId:uid,snapshotHash:a.snapshotHash,revision:1,lifecycleState:'ready',accessStatus:'active',scheduledInstantMillis:a.occurrences[0].scheduledInstantMillis,accessExpiresAtMillis:a.accessExpiresAtMillis,timeZoneId:'UTC',preparedAtMillis:a.preparedAtMillis,horizonRevision:1,planHash:a.planHash,plannedWorkoutIds:a.occurrences.map((o:Data)=>o.plannedWorkoutId)});
  out.set(`${b}/snapshots/content`,{...ci,...provenance,contentHash:a.snapshotHash,copyPolicyVersion:1,title:copy.title,description:copy.description,workoutIds:copy.workouts.map((w:Data)=>w.id)});
  for(const w of copy.workouts) {
    out.set(`${b}/snapshots/content/workouts/${w.id}`,{...ci,workoutId:w.id,title:w.title,itemIds:w.items.map((i:Data)=>i.id)});
    for(const {id,...item} of w.items)out.set(`${b}/snapshots/content/workouts/${w.id}/exercises/${id}`,{...ci,workoutId:w.id,itemId:id,...item});
  }
  for(const o of a.occurrences)out.set(`${b}/plannedWorkouts/${o.plannedWorkoutId}`,{...ci,...o,timeZoneId:'UTC',status:'planned',revision:1,horizonRevision:1});
  const requiredPaths=[...out.keys()].filter(p=>p!==b).map(p=>p.slice(b.length+1)).sort();
  out.set(`${b}/manifests/download`,{...ci,assignmentRevision:1,horizonRevision:1,snapshotHash:a.snapshotHash,planHash:a.planHash,requiredPaths});
  out.set(`workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}`,{...identity,trainerId:uid,status:'active',revision:1,horizonRevision:1,planHash:a.planHash});
  for(const [p,v] of out)if(bytes(v)>(p.includes('/exercises/')?24576:p.endsWith('/snapshots/content') || p.endsWith('/manifests/download')?8192:4096))throw Error('plan-document-oversize');
  return out;
}
export function materializePlan(d:Data,v:Data,uid:string) {
  planBinding(uid,d);const a=d.assignmentPlan,copy=planCopy(v,uid,d.workspaceId,a.templateId);
  if(v.versionId!==a.versionId || v.versionNumber!==a.versionNumber || v.contentHash!==a.sourceContentHash || assignmentCopyHash(copy)!==a.snapshotHash || JSON.stringify(copy.workouts.map((w:Data)=>w.id))!==JSON.stringify(a.occurrences.map((o:Data)=>o.workoutId)))throw Error('plan-preview-conflict');
  return graph(d,copy,uid);
}
/** Reconstruct solely from client-owned graph. No source DTO/read or field redaction. */
export function validatePlanGraph(client:string,wid:string,aid:string,docs:Map<string,Data>):boolean {
  try {
    const b=`users/${client}/workspaces/${wid}/assignedPrograms/${aid}`,h=docs.get(b)!,s=docs.get(`${b}/snapshots/content`)!;
    participantUid(client,'client');participantUid(wid,'workspace');participantUid(h.trainerId,'trainer');
    if(!exerciseId(aid) || h.clientId!==client || h.workspaceId!==wid || h.assignmentId!==aid || h.schedulePolicyVersion!==2 || !Array.isArray(s.workoutIds) || !Array.isArray(h.plannedWorkoutIds))return false;
    const workouts=s.workoutIds.map((id:string)=>{const w=docs.get(`${b}/snapshots/content/workouts/${id}`)!;return {id,title:w.title,items:w.itemIds.map((itemId:string)=>{const item=docs.get(`${b}/snapshots/content/workouts/${id}/exercises/${itemId}`)!;const {schemaVersion,workspaceId,clientId,assignmentId,schedulePolicyVersion,snapshotId,workoutId,...i}=item;const {itemId:stored,...fields}=i;return {id:stored,...fields};})};});
    const copy={copyPolicyVersion:1,title:s.title,description:s.description,workouts};
    const content={contentPolicyVersion:1,workspaceId:wid,templateId:h.sourceTemplateId,ownerUid:h.trainerId,title:s.title,description:s.description,workouts};
    if(!validPublicationContent(content) || publicationContentHash(content)!==h.sourceContentHash || assignmentCopyHash(copy)!==h.snapshotHash)return false;
    const occurrences=h.plannedWorkoutIds.map((id:string)=>{const p=docs.get(`${b}/plannedWorkouts/${id}`)!;return {plannedWorkoutId:id,workoutId:p.workoutId,scheduledInstantMillis:p.scheduledInstantMillis};});
    const d={action:'assign-program-plan',workspaceId:wid,idempotencyKey:'or1-b91f0176ec74475e8cf7406e25b7e9d3-validation',assignmentPlan:{assignmentId:aid,clientUid:client,templateId:h.sourceTemplateId,versionId:h.sourceVersionId,versionNumber:h.sourceVersionNumber,sourceContentHash:h.sourceContentHash,copyPolicyVersion:1,snapshotHash:h.snapshotHash,authority:Object.fromEntries(ASSIGNMENT_AUTHORITY.map(k=>[k,1])),preparedAtMillis:h.preparedAtMillis,accessExpiresAtMillis:h.accessExpiresAtMillis,schedulePolicyVersion:2,expectedHorizonRevision:0,horizonRevision:1,planHash:h.planHash,occurrences}};
    planBinding(h.trainerId,d);if(JSON.stringify(workouts.map((w:Data)=>w.id))!==JSON.stringify(occurrences.map((o:Data)=>o.workoutId)))return false;
    const expected=new Map([...graph(d,copy,h.trainerId)].filter(([p])=>p.startsWith(b+'/') || p===b));
    return docs.size===expected.size && [...expected].every(([p,v])=>docs.has(p) && stableHash(v)===stableHash(docs.get(p)));
  }catch{return false;}
}

/** Absence means historical policy 1; only explicit integer 2 selects full-plan policy. */
export function discriminateGraph(client:string,wid:string,aid:string,docs:Map<string,Data>):1|2 {
 const h=docs.get(`users/${client}/workspaces/${wid}/assignedPrograms/${aid}`);
 if(h && !Object.hasOwn(h,'schedulePolicyVersion') && validateClientAssignmentDocuments(client,wid,aid,docs))return 1;
 if(h?.schedulePolicyVersion===2 && validatePlanGraph(client,wid,aid,docs))return 2;
 throw Error('plan-policy-unavailable');
}
