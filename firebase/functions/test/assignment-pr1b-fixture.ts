import { getAuth } from 'firebase-admin/auth';
import { Firestore, Timestamp } from 'firebase-admin/firestore';
import { assignmentFixture } from './assignment-pr1a-fixture.js';
import { requireProvisioningEmulators } from '../src/provisioning.js';
import { SPIKE_PROJECT_ID } from '../src/environment.js';
import { RESOLUTION_GENERATION } from '../src/command-identity.js';
import { ASSIGNMENT_BARRIERS } from '../src/assignment-coverage.js';
import { assignmentCopy, assignmentCopyHash } from '../src/assignment-feasibility-contract.js';
import { publicationContentHash, publicationDraftHash, publicationDraft, draftContent } from '../src/program-publication-contract.js';
import { stableHash } from '../src/hashing.js';
import { createHash } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/** Create-only independent fixture setup. No protected command, receipt or historical migration. */
export async function createAssignmentFixture(db: Firestore, uid='A', client='B', wid='w', count=1, wide=false, password?:string) {
  requireProvisioningEmulators();if((db as unknown as {projectId:string}).projectId!==SPIKE_PROJECT_ID)throw new Error('fixture-project-invalid');
  const f=assignmentFixture(wide?'😀':'Synthetic',count,wide), now=Date.now(), at=Timestamp.fromMillis(now-1000);
  const content=draftContent({...f.version,ownerUid:uid,workspaceId:wid});
  const version:Record<string,any>={schemaVersion:1,...content,draftHash:publicationDraftHash(publicationDraft(content)),contentHash:publicationContentHash(content),versionId:'v0000000001',versionNumber:1,draftRevision:1,publishedAtMillis:now-1000};
  const email=(id:string)=>`${uid==='A'?'m7-pr1b-reset-':''}${id}@example.invalid`;
  for(const id of [uid,client])await getAuth().createUser({uid:id,email:email(id),emailVerified:true,...(password?{password}:{})});
  const docs:Record<string,any>={
    [`workspaces/${wid}`]:{schemaVersion:1,name:'Synthetic assignment',ownerUid:uid,status:'active',membershipRevision:1,activeRosterCount:2,catalogContributionCount:2,createdAt:at,createdBy:'synthetic-setup'},
    [`workspaces/${wid}/trainerProfiles/${uid}`]:{schemaVersion:1,workspaceId:wid,userId:uid,bio:'',createdAt:at},
    [`workspaces/${wid}/clientProfiles/${client}`]:{schemaVersion:1,workspaceId:wid,userId:client,trainerId:uid,createdAt:at,onboardingStatus:'complete',onboardingPolicyVersion:1,onboardingRevision:1,completedAt:at}
  };
  for(const [id,role] of [[uid,'trainer'],[client,'client']] as const) {
    docs[`users/${id}`]={schemaVersion:1,accountStatus:'active',lifecycleRevision:1,emailNormalized:email(id).toLowerCase(),displayName:'Synthetic fixture',locale:'en',timeZoneId:'UTC',profileRevision:1,createdAt:at};
    docs[`users/${id}/membershipRefs/${wid}`]={schemaVersion:1,status:'active'};
    docs[`users/${id}/authorizations/systemCatalog`]={schemaVersion:1,status:'active',activeMembershipCount:1,revision:1,updatedAt:at,updatedBy:'synthetic-setup'};
    docs[`workspaces/${wid}/memberships/${id}`]={schemaVersion:1,workspaceId:wid,userId:id,role,status:'active',revision:1,catalogContributionActive:true,joinedAt:at};
  }
  const stored={...version,publishedAt:Timestamp.fromMillis(version.publishedAtMillis)};delete (stored as any).publishedAtMillis;
  docs[`workspaces/${wid}/programTemplates/${version.templateId}/versions/${version.versionId}`]=stored;
  const batch=db.batch();for(const [p,d] of Object.entries(docs))batch.create(db.doc(p),d);await batch.commit();
  const copy=assignmentCopy(version,uid,wid,version.templateId);
  const descriptor={...f.descriptor,workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-${uid}-assignment`,assignment:{...f.descriptor.assignment,clientUid:client,versionId:version.versionId,versionNumber:1,
    sourceContentHash:version.contentHash,snapshotHash:assignmentCopyHash(copy),authority:{trainerLifecycleRevision:1,trainerMembershipRevision:1,clientLifecycleRevision:1,clientMembershipRevision:1,workspaceMembershipRevision:1,clientOnboardingRevision:1},
    preparedAtMillis:now,scheduledInstantMillis:now+3600000,accessExpiresAtMillis:now+3600000+86400000}};
  return {uid,client,wid,version,copy,descriptor};
}

/** Full recursive explicit export, including empty-parent descendants and Auth. Verified
 * twice before creating immutable coverage. Only task-owned reset/native supervisors call it. */
export async function attestIndependentAssignmentFixture(db:Firestore,provenance:string,output?:string) {
  requireProvisioningEmulators();if((db as unknown as {projectId:string}).projectId!==SPIKE_PROJECT_ID)throw new Error('fixture-project-invalid');
  if(!/^[A-Za-z0-9_-]{1,80}$/.test(provenance))throw new Error('fixture-provenance-invalid');
  const collect=async()=>{
    const documents:Record<string,any>={};
    async function visit(c:FirebaseFirestore.CollectionReference) {
      for(const ref of await c.listDocuments()) {
        const s=await ref.get();if(s.exists)documents[ref.path]=s.data();
        for(const child of await ref.listCollections())await visit(child);
      }
    }
    for(const c of await db.listCollections())await visit(c);
    const auth:any[]=[];let token:string|undefined;
    do {const page=await getAuth().listUsers(1000,token);auth.push(...page.users.map(u=>u.toJSON()));token=page.pageToken;}while(token);
    return {documents,auth:auth.sort((a,b)=>a.uid.localeCompare(b.uid))};
  };
  const first=await collect();
  if(Object.keys(first.documents).some(p=>p.startsWith('lifecycleCommands/') || p.startsWith('assignmentCommands/') || p.startsWith('commandResolutionCoverage/') || p.startsWith('commandResolutionConfig/') || p.includes('/assignedPrograms/')))throw new Error('fixture-history-not-independent-empty');
  const exported=JSON.stringify(first),exportSha256=createHash('sha256').update(exported).digest('hex');
  if(output) {await mkdir(output,{recursive:true});await writeFile(`${output}/before-protected.json`,exported);if(createHash('sha256').update(await readFile(`${output}/before-protected.json`)).digest('hex')!==exportSha256)throw new Error('export-checkpoint-mismatch');}
  const second=await collect();if(stableHash(first)!==stableHash(second))throw new Error('fixture-changed-during-attestation');
  const paths=['src/assigned-program.ts','src/command-resolution.ts','src/assignment-coverage.ts','src/program-assignments.ts','../firestore.rules','../firestore.indexes.json'];
  const fingerprint=createHash('sha256');for(const p of paths)fingerprint.update(await readFile(resolve(__dirname,'../..',p)));
  const coverage={schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:provenance,barriers:ASSIGNMENT_BARRIERS,sourceFingerprint:fingerprint.digest('hex'),exportSha256,
    historyKind:'independent-empty-before-protected-command',lifecycleCount:0,legacyCount:0};
  await db.doc(`commandResolutionCoverage/${provenance}`).create(coverage);
  await db.doc('commandResolutionConfig/current').create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:provenance,assignmentCoverageSha256:stableHash(coverage),historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:100});
  process.env.TF_RESOLUTION_PROVENANCE_ID=provenance;process.env.TF_ASSIGNMENT_COVERAGE_SHA=stableHash(coverage);
  if(output)await writeFile(`${output}/coverage.json`,JSON.stringify({coverage,coverageSha256:stableHash(coverage)},null,2));
  return coverage;
}
