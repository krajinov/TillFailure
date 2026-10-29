import { exerciseExact, exerciseText, exerciseId, exerciseRevision, EXERCISE_AUTHORITY } from './custom-exercise-contract.js';
import { participantUid, strictPathIdentifier } from './input-validation.js';
import { stableHash } from './hashing.js';
type Data = Record<string, any>;
export function validDraftSave(d: unknown): d is Data {
  if (!exerciseExact(d,['templateId','expectedRevision','authority','title','description','workouts']) || !exerciseId(d.templateId) ||
    !Number.isInteger(d.expectedRevision) || d.expectedRevision<0 || d.expectedRevision>2147483645 ||
    !exerciseExact(d.authority,EXERCISE_AUTHORITY) || !EXERCISE_AUTHORITY.every(k=>exerciseRevision(d.authority[k]))) return false;
  const text=(s:unknown,n:number,m=false)=>s==='' || exerciseText(s,n,m);
  if (!text(d.title,80) || !text(d.description,240,true) || !Array.isArray(d.workouts) || d.workouts.length>3) return false;
  const workouts=new Set<string>(), items=new Set<string>(), strings=[d.title,d.description];
  for(const w of d.workouts) {
    if(!exerciseExact(w,['id','title','items']) || !exerciseId(w.id) || workouts.has(w.id) || !text(w.title,40) || !Array.isArray(w.items) || w.items.length>12) return false;
    workouts.add(w.id);strings.push(w.title);
    for(const i of w.items) {
      if(!exerciseExact(i,['id','sourceKind','sourceId','sourceRevision','sourceHash','name','prescription']) || !exerciseId(i.id) || items.has(i.id) ||
        !exerciseText(i.name,120) || typeof i.sourceHash!=='string' || !/^[a-f0-9]{64}$/.test(i.sourceHash)) return false;
      if(i.sourceKind==='system' ? typeof i.sourceId!=='string' || !/^[A-Za-z0-9_-]{1,128}$/.test(i.sourceId) || i.sourceRevision!==null :
        i.sourceKind!=='custom' || !exerciseId(i.sourceId) || !exerciseRevision(i.sourceRevision)) return false;
      const p=i.prescription;
      if(!exerciseExact(p,['sets','reps','restSeconds','loadGrams']))return false;
      for(const [key,min,max] of [['sets',1,20],['reps',1,1000],['restSeconds',0,3600],['loadGrams',0,1000000]] as const)
        if(p[key]!==null && (!Number.isInteger(p[key]) || p[key]<min || p[key]>max))return false;
      items.add(i.id);strings.push(i.name);
    }
  }
  return items.size<=12 && strings.reduce((n,s)=>n+[...s].length,0)<=650 && strings.reduce((n,s)=>n+Buffer.byteLength(s,'utf8'),0)<=2600;
}
export function draftDescriptor(workspaceId:string,idempotencyKey:string,draftSave:unknown) {
  participantUid(strictPathIdentifier(workspaceId,'workspace'),'workspace');
  participantUid(strictPathIdentifier(idempotencyKey,'key'),'key');
  if(!/^or1-b91f0176ec74475e8cf7406e25b7e9d3-[A-Za-z0-9_-]{1,80}$/.test(idempotencyKey) || !validDraftSave(draftSave))throw new Error('draft-invalid');
  return {action:'save-program-draft',idempotencyKey,workspaceId,draftSave};
}
export function draftRequestHash(callerUid:string,descriptor:ReturnType<typeof draftDescriptor>) {
  return stableHash({...descriptor,callerUid,policyVersion:1});
}
