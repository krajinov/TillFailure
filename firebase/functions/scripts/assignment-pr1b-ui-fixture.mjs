// Create-only v2 for the already verified, unissued UI fixtures. No Publish command.
import {readFile,writeFile} from 'node:fs/promises';
import {Timestamp} from 'firebase-admin/firestore';
import {emulatorFirestore} from '../lib/src/environment.js';
import {requireProvisioningEmulators} from '../lib/src/provisioning.js';
import {publicationDraft,publicationDraftHash,publicationContentHash,draftContent} from '../lib/src/program-publication-contract.js';
import {assignmentCopy,assignmentCopyHash} from '../lib/src/assignment-feasibility-contract.js';
import {stableHash} from '../lib/src/hashing.js';
requireProvisioningEmulators();const root=process.env.TF_M7_PR1B_ROOT;if(!root)throw new Error('Require task-owned root');
const inventory=JSON.parse(await readFile(`${root}/fixtures/inventory.json`,'utf8')),db=emulatorFirestore(),evidence=[];
for(const f of inventory.filter(x=>x.mode==='ui')) {
 const ref=db.doc(`workspaces/${f.wid}/programTemplates/${f.version.templateId}`),v1=ref.collection('versions').doc('v0000000001');
 const before=stableHash((await v1.get()).data()),rootDoc=(await ref.get()).data();if(rootDoc.revision!==2 || rootDoc.versionCount!==1)throw new Error('Occupied UI setup');
 const now=Timestamp.now(),content={...draftContent(f.version),title:'Later synthetic version'};
 const version={schemaVersion:1,...content,versionId:'v0000000002',versionNumber:2,draftRevision:2,draftHash:publicationDraftHash(publicationDraft(content)),contentHash:publicationContentHash(content),publishedAt:now};
 const dto={...version,publishedAtMillis:now.toMillis()};delete dto.publishedAt;
 assignmentCopy(dto,f.uid,f.wid,f.version.templateId);
 await db.runTransaction(async tx=>{const old=await tx.get(ref);if(old.get('revision')!==2)throw new Error('UI fixture changed');tx.create(ref.collection('versions').doc('v0000000002'),version);tx.update(ref,{title:content.title,revision:3,versionCount:2,currentVersionId:'v0000000002',updatedAt:now});});
 if(stableHash((await v1.get()).data())!==before)throw new Error('Original version changed');
 evidence.push({platform:f.platform,workspaceId:f.wid,v1Fingerprint:before,v1ContentHash:f.version.contentHash,v2ContentHash:version.contentHash,v1CopyHash:f.preflight.snapshotHash,v2CopyHash:assignmentCopyHash(assignmentCopy(dto,f.uid,f.wid,f.version.templateId))});
}
await writeFile(`${root}/ui-source-evidence.json`,JSON.stringify(evidence,null,2));console.log('Created independently validated v2; original v1 bytes preserved. Publish remains disabled.');
