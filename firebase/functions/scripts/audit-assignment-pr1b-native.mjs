import {readFile,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {emulatorFirestore} from '../lib/src/environment.js';
import {assignmentBinding,materializeAssignment,validAssignmentDecision} from '../lib/src/assignment-feasibility-contract.js';
import {assignmentDocumentDto} from '../lib/src/program-assignments.js';
import {stableHash} from '../lib/src/hashing.js';
const [root,platform,mode,stageLog,phase,output,resumeLog]=process.argv.slice(2);
const fixtures=JSON.parse(await readFile(`${root}/fixtures/inventory.json`,'utf8')),f=fixtures.find(x=>x.platform===platform&&x.mode===mode);assert.ok(f);
const log=await readFile(stageLog,'utf8'),match=log.match(/M7P1B_BINDING_JSON (\{[^\r\n]+\})/);assert.ok(match,'original exact binding logged');
const d=JSON.parse(match[1]).descriptor,b=assignmentBinding(f.uid,d),db=emulatorFirestore();
assert.equal(d.assignment.clientUid,f.client);assert.equal(d.workspaceId,f.wid);assert.equal(d.assignment.sourceContentHash,f.version.contentHash);
const r=(await db.doc(`lifecycleCommands/${b.id}`).get()).data(),legacy=await db.doc(`assignmentCommands/${b.id}`).get();assert.equal(legacy.exists,false);
const outcome=mode==='fenced'?'not-applied-fenced':mode==='conflict'?'not-applied-conflict':'applied';
if(phase==='stage'&&mode==='fenced')assert.equal(r,undefined);
else if(mode==='fenced'){assert.equal(r?.commandKind,'own-command-fence');assert.equal(r?.requestHash,b.hash);assert.equal(r?.outcome,outcome);}
else{assert.equal(r?.commandKind,'program-assignment-create');assert.equal(r?.callerUid,f.uid);assert.equal(r?.requestHash,b.hash);assert.ok(validAssignmentDecision(r.result,d));assert.equal(r.result.status,mode==='conflict'?'assignment-copy-conflict':'assignment-created');}
const expected=materializeAssignment({...d,assignment:{...d.assignment,snapshotHash:f.preflight.snapshotHash}},f.version,f.uid);
for(const [p,v]of expected){const s=await db.doc(p).get();assert.equal(s.exists,outcome==='applied',p);if(s.exists)assert.equal(stableHash(assignmentDocumentDto(s.data())),stableHash(v),p);}
assert.equal((await db.doc(`workspaces/${f.wid}`).get()).get('membershipRevision'),outcome==='applied'?2:1);
const headers=await db.collection(`users/${f.client}/workspaces/${f.wid}/assignedPrograms`).get();assert.equal(headers.size,outcome==='applied'?1:0);
if(phase==='resume') {const resumed=await readFile(resumeLog,'utf8');assert.ok(resumed.includes(`M7P1B_MARKER ${b.id} ${b.hash}`),'original command id/hash unchanged through cold restart');}
await writeFile(output,JSON.stringify({platform,mode,phase,commandId:b.id,requestHash:b.hash,outcome,artifactCount:outcome==='applied'?expected.size:0,workspaceRevision:outcome==='applied'?2:1,verified:true},null,2));
console.log(`${platform} ${mode} ${phase}: original binding, complete server effect and decision verified`);
