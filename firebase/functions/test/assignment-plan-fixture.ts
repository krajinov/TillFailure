import { assignmentFixture,assignmentUuid as uuid } from './assignment-pr1a-fixture.js';
import { publicationContentHash,publicationDraftHash,publicationDraft } from '../src/program-publication-contract.js';
import { assignmentCopyHash } from '../src/assignment-feasibility-contract.js';
import { planCopy,planHash } from '../src/assignment-plan-feasibility.js';
export function planFixture(workoutCount=3,count=12,character='S',wide=false,widePath=false,distribution?:number[]) {
  const old=assignmentFixture(character,count,wide,widePath),v:any=structuredClone(old.version),one=v.workouts[0];
  // Redistribute the existing twelve-total-item budget, including 650 draft scalars.
  v.workouts=Array.from({length:workoutCount},(_,i)=>({id:uuid(100+i),title:i===0?one.title:character.replaceAll('\n',''),items:distribution?one.items.slice(distribution.slice(0,i).reduce((a,b)=>a+b,0),distribution.slice(0,i+1).reduce((a,b)=>a+b,0)):one.items.filter((_:any,n:number)=>n%workoutCount===i)}));
  if(wide && workoutCount>1)v.workouts[0].items[0].name=v.workouts[0].items[0].name.slice(0,-(workoutCount-1)*character.length);
  const {schemaVersion,versionId,versionNumber,draftRevision,draftHash,contentHash,publishedAtMillis,...content}=v;
  v.contentHash=publicationContentHash(content);v.draftHash=publicationDraftHash(publicationDraft(content));
  const copy=planCopy(v,old.uid,old.wid,v.templateId),prepared=old.descriptor.assignment.preparedAtMillis;
  const occurrences=v.workouts.map((w:any,i:number)=>({plannedWorkoutId:uuid(200+i),workoutId:w.id,scheduledInstantMillis:prepared+(wide?86400000-workoutCount+1+i:1000*(i+1))}));
  const {plannedWorkoutId,scheduledInstantMillis,...rest}=old.descriptor.assignment;
  const descriptor={action:'assign-program-plan',workspaceId:old.wid,idempotencyKey:old.descriptor.idempotencyKey,assignmentPlan:{...rest,sourceContentHash:v.contentHash,snapshotHash:assignmentCopyHash(copy),accessExpiresAtMillis:occurrences.at(-1).scheduledInstantMillis+86400000,schedulePolicyVersion:2,expectedHorizonRevision:0,horizonRevision:1,planHash:planHash(occurrences),occurrences}};
  return {uid:old.uid,client:old.client,wid:old.wid,version:v,copy,descriptor};
}
