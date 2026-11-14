// Create-only task-owned independent fixtures. No reset/import or old-identity reuse.
import { readFile,writeFile,mkdir } from 'node:fs/promises';
import { emulatorFirestore } from '../lib/src/environment.js';
import { requireProvisioningEmulators } from '../lib/src/provisioning.js';
import { createAssignmentFixture,attestIndependentAssignmentFixture } from '../lib/test/assignment-pr1b-fixture.js';
import { programAssignmentOperation } from '../lib/src/program-assignments.js';
import { publicationDraft } from '../lib/src/program-publication-contract.js';
import { Timestamp } from 'firebase-admin/firestore';
requireProvisioningEmulators();
const root=process.env.TF_M7_PR1B_ROOT;if(!root)throw new Error('Require task-owned root');
const run=JSON.parse(await readFile(`${root}/run.json`,'utf8')),db=emulatorFirestore();
if(!/^m7p1br[a-f0-9]{8}$/.test(run.prefix))throw new Error('Bad fixture prefix');
if((await db.listCollections()).length || (await (await import('firebase-admin/auth')).getAuth().listUsers(1)).users.length)throw new Error('Require an independent empty task supervisor before create-only fixture setup');
const fixtures=[];
for(const platform of ['android','ios'])for(const mode of ['applied','fenced','conflict','failure','proof','settled','admission','ui']) {
 const uid=`${run.prefix}_${platform}_assign_${mode}`,client=uid+'_client',wid=uid+'_ws';
 const f=await createAssignmentFixture(db,uid,client,wid,mode==='admission'?12:1,mode==='admission',run.password);
 const content=publicationDraft(f.version);const at=Timestamp.fromMillis(f.version.publishedAtMillis);
 await db.doc(`workspaces/${wid}/programTemplates/${f.version.templateId}`).create({schemaVersion:2,policyVersion:1,workspaceId:wid,templateId:f.version.templateId,ownerUid:uid,
  title:content.title,description:content.description,workouts:content.workouts,revision:2,versionCount:1,currentVersionId:'v0000000001',createdAt:at,updatedAt:at});
 // Fresh Auth and live authenticated mediator preparation before any protected command.
 const response=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-key`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:`${uid}@example.invalid`,password:run.password,returnSecureToken:true})});
 const identity=await response.json();if(!response.ok || !identity.idToken)throw new Error('Auth preflight failed');
 const request={action:'assignment-prepare',workspaceId:wid,clientUid:client,templateId:f.version.templateId,versionId:f.version.versionId,scheduledInstantMillis:Date.now()+86400000-60000};
 const preflight=await fetch('http://127.0.0.1:48701/demo-tillfailure-m3/europe-west1/programAssignmentsEmulator',{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${identity.idToken}`},body:JSON.stringify({data:request})});
 const body=await preflight.json();if(!preflight.ok || !body.result?.assignment || body.result.assignment.clientUid!==client)throw new Error('Mediator preflight failed: '+JSON.stringify(body));
 fixtures.push({platform,mode,uid,client,wid,version:f.version,preflight:body.result.assignment});
}
await mkdir(`${root}/fixtures`,{recursive:true});await writeFile(`${root}/fixtures/inventory.json`,JSON.stringify(fixtures));
await attestIndependentAssignmentFixture(db,run.prefix,`${root}/before-protected`);
console.log(`Created ${fixtures.length} independent fixtures; Auth, mediator, full explicit export and immutable coverage verified before protected commands.`);
