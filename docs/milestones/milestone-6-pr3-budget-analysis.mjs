// Documentation-only sizing model. No application imports, network, files or backend writes.
// Run: node docs/milestones/milestone-6-pr3-budget-analysis.mjs
// Mirrors compact JSON, explicit defaults/nulls in PersistenceJson, and EncodeDefault.NEVER
// for absent note/exercise/new draft fields. This is not an executed Kotlin/native serializer.
import assert from 'node:assert/strict';
const json = JSON.stringify;
const bytes = value => Buffer.byteLength(json(value), 'utf8');
const M = 2147483646, T = 8640000000000000;
const uid = '\u0001'.repeat(128), wid = '\u0002'.repeat(128);
const key = 'or1-b91f0176ec74475e8cf7406e25b7e9d3-' + 'a'.repeat(80);
const uuid = n => '00000000-0000-4000-8000-' + String(n).padStart(12, '0');
const hash = 'a'.repeat(64);
function pending(action, fields = {}) {
  return {uid, action, idempotencyKey:key, workspaceId:wid, invitationId:null,
    expectedVersion:null, email:null, onboarding:null, profileUpdate:null,
    acceptanceTokenDigest:null, resolutionStarted:false, revocation:null, ...fields};
}
function reply(fields) {
  return {invitationId:null, workspaceId:null, workspaceName:null, status:null,
    tokenVersion:null, expiresAtMillis:null, replayed:null, deliveryToken:null,
    summaries:null, accountRevision:null, onboardingRevision:null, terminalOutcome:null,
    revocationPreparation:null, revocationDecision:null, profile:null, profileCommandKey:null, ...fields};
}
const normal = (command, acknowledgment) => ({command, source:'normal-v1', resolution:null, acknowledgment});
const resolved = command => ({command, source:'resolution-v1', resolution:{protocolVersion:1,
  commandKind:'program-draft-save', commandId:hash, requestHashVersion:'program-draft-v1',
  requestHash:hash, outcome:'not-applied-conflict', decidedAtMillis:T}, acknowledgment:null});
const oldKey = '\u0004'.repeat(128);
const legacy = {};
for (const action of ['create','resend','revoke','accept']) {
  const p = pending(action, {idempotencyKey:oldKey,
    ...(action === 'create' ? {email:'x'.repeat(240)+'@example.test'} :
      {invitationId:hash, expectedVersion:M, ...(action === 'accept' ? {workspaceId:null,acceptanceTokenDigest:hash} : {})})});
  legacy[action] = normal(p, reply({invitationId:hash, workspaceId:wid,
    status:action === 'accept' ? 'accepted' : action === 'revoke' ? 'revoked' : 'pending',
    tokenVersion:action === 'create' ? 1 : action === 'resend' ? M+1 : M,
    expiresAtMillis:T, replayed:true}));
}
legacy.onboarding = normal(pending('complete-onboarding', {idempotencyKey:oldKey,
  onboarding:{displayName:'界'.repeat(80),locale:'bs-BA',timeZoneId:'America/Argentina/ComodRivadavia',
    acknowledged:true,expectedAccountRevision:M-1,expectedOnboardingRevision:M-1}}),
  reply({workspaceId:wid,status:'complete',accountRevision:M,onboardingRevision:M,replayed:true}));
legacy.profile = normal(pending('update-profile', {idempotencyKey:oldKey,
  profileUpdate:{displayName:'界'.repeat(80),locale:'bs-BA',timeZoneId:'America/Argentina/ComodRivadavia',expectedRevision:M-1}}),
  reply({workspaceId:wid,status:'profile-updated',accountRevision:M,profileCommandKey:oldKey,replayed:true}));
legacy.revocation = normal(pending('revoke-client',{idempotencyKey:oldKey,
  revocation:{clientUid:'\u0003'.repeat(128),expectedTargetRevision:M-1,expectedWorkspaceRevision:M-1,
    expectedCallerMembershipRevision:M-1,expectedCallerLifecycleRevision:M-1}}),
  reply({replayed:true,revocationDecision:{workspaceId:wid,clientUid:'\u0003'.repeat(128),
    status:'client-revoked',commandKey:oldKey,targetMembershipRevision:M,workspaceRevision:M,
    callerMembershipRevision:M-1,callerLifecycleRevision:M-1}}));
const history = {...legacy};
history['full-note'] = normal(pending('save-trainer-note',{idempotencyKey:oldKey,noteSave:{
  clientUid:'\u0003'.repeat(128),body:'😀'.repeat(2000),bindingNonce:hash,expectedRevision:M-1,
  authorMembershipRevision:M,subjectMembershipRevision:M,authorLifecycleRevision:M,subjectLifecycleRevision:M}}),
  reply({noteDecision:{status:'note-saved',workspaceId:wid,clientUid:'\u0003'.repeat(128),noteRevision:M,replayed:false}}));
for (const action of ['create-custom-exercise','correct-custom-exercise']) {
  history['full-'+action] = normal(pending(action,{idempotencyKey:key.slice(0,-80)+'b'.repeat(80),exerciseWrite:{exerciseId:uuid(500),
    expectedRevision:action==='create-custom-exercise'?0:M-1,
    authority:{authorLifecycleRevision:M,authorMembershipRevision:M,workspaceMembershipRevision:M},
    name:'😀'.repeat(120),instructions:'😀'.repeat(2000),muscleGroups:['arms','shoulders','whole-body'],
    equipment:['barbell','dumbbell','machine']}}),reply({exerciseDecision:{
      status:action==='create-custom-exercise'?'exercise-created':'exercise-corrected',
      workspaceId:wid,exerciseId:uuid(500),exerciseRevision:action==='create-custom-exercise'?1:M,replayed:false}}));
}
for (const [kind, version] of [['trainer-note-save','trainer-note-v1'],
  ['custom-exercise-create','custom-exercise-v1'],['custom-exercise-correct','custom-exercise-v1']]) {
  history[kind] = {command:null, source:'compacted-v1', resolution:null, acknowledgment:null,
    marker:{schemaVersion:1,uid,commandKind:kind,commandId:'b'.repeat(64),requestHashVersion:version,
      requestHash:hash,source:'resolution-v1',outcome:'not-applied-conflict',decidedAtMillis:T}};
}
function envelope(p, proof, settled) {
  return {schemaVersion:1,uid,offlineAccessGrant:null,downloadManifests:[],workoutRecoverySnapshot:null,
    mutationJournal:[],pendingUploads:[],switchMarker:null,preferredWorkspaceId:wid,
    pendingInvitationCommands:[p],pendingTerminalProof:proof,lastSettledCommand:settled};
}
function draft(character, sourceKind='system') {
  // Exactly 650 aggregate text scalars; all 12 sources have names. Distribution is size-neutral.
  let remaining = 210;
  return {templateId:uuid(1),expectedRevision:M-1,
    authority:{authorLifecycleRevision:M,authorMembershipRevision:M,workspaceMembershipRevision:M},
    title:character.repeat(80),description:character.repeat(240),
    workouts:Array.from({length:3},(_,w)=>({id:uuid(w+2),title:character.repeat(40),
      items:Array.from({length:4},(_,i)=>{
        const n = Math.min(remaining-(11-(w*4+i)),120); remaining-=n;
        return {id:uuid(10+w*4+i),sourceKind,sourceId:sourceKind==='system'?'z'.repeat(128):uuid(100+w*4+i),
          sourceRevision:sourceKind==='system'?null:M,sourceHash:hash,name:character.repeat(n),
          prescription:{sets:null,reps:1000,restSeconds:3600,loadGrams:1000000}};
      })}))};
}
const maxima = {};
function record(k,v) { maxima[k] = Math.max(maxima[k]??0,v); }
for (const c of ['😀','界','"','\\']) for (const kind of ['system','custom']) {
  const d = draft(c,kind);
  if (c==='\\') d.description='x'+'\n'.repeat(238)+'x'; // Interior LF, valid trimmed multiline text.
  for (const expectedRevision of [0,M-1]) {
    d.expectedRevision=expectedRevision;
    const descriptor={action:'save-program-draft',workspaceId:wid,idempotencyKey:key,draftSave:d};
    const resolver={protocolVersion:1,descriptor};
    const p=pending(descriptor.action,{draftSave:d});
    const proofs=[resolved(p), ...['draft-saved','draft-conflict','draft-source-conflict'].map(status=>
      normal(p,reply({draftDecision:{status,workspaceId:wid,templateId:d.templateId,
        templateRevision:status==='draft-saved'?expectedRevision+1:status==='draft-source-conflict'?expectedRevision:M,replayed:false}})))];
    record('resolverUnits',json(resolver).length); record('resolverBytes',bytes(resolver));
    record('saveCallableBytes',bytes({data:descriptor})); record('resolveCallableBytes',bytes({data:resolver}));
    record('pendingBytes',bytes(p));
    for (const proof of proofs) {
      record('fullProofBytes',bytes(proof));
      record('envelopeWithoutHistoryBytes',bytes(envelope(p,proof,null)));
      for (const [name,settled] of Object.entries(history)) record('envelope-'+name,bytes(envelope(p,proof,settled)));
    }
    const doc={schemaVersion:1,policyVersion:1,workspaceId:wid,templateId:d.templateId,ownerUid:uid,
      revision:M,createdAtMillis:T,updatedAtMillis:T,title:d.title,description:d.description,workouts:d.workouts};
    record('documentProjectionBytes',bytes(doc));
  }
}
const maxEnvelope=Math.max(...Object.entries(maxima).filter(([k])=>k.startsWith('envelope-')).map(([,v])=>v));
// Headers and base64 lengths: AES-GCM 12-byte nonce, 16-byte tag. Swift tag is separate.
const encrypted=n=>({android:bytes({encryptionVersion:1,keyIdentifier:'tillfailure.recovery.v1',
  nonce:'A'.repeat(16),ciphertext:'A'.repeat(4*Math.ceil((n+16)/3))}),
  swift:bytes({encryptionVersion:1,keyIdentifier:'tillfailure.recovery.v1',nonce:'A'.repeat(16),
    ciphertext:'A'.repeat(4*Math.ceil(n/3)),tag:'A'.repeat(24)}),
  // Swift currently sets sortedKeys only: conservatively allow escaping EVERY base64 symbol.
  // Padding cannot be '/', so this is an upper bound, not attainable exact ciphertext.
  swiftSlashUpperBound:bytes({encryptionVersion:1,keyIdentifier:'tillfailure.recovery.v1',nonce:'A'.repeat(16),
    ciphertext:'A'.repeat(4*Math.ceil(n/3)),tag:'A'.repeat(24)})+16+24+4*Math.ceil(n/3)});
console.log(JSON.stringify({model:'documentation-only; calculated compact JSON sizes',maxima,
  maxEnvelope,legacyProofBytes:Object.fromEntries(Object.entries(legacy).map(([k,v])=>[k,bytes(v)])),
  encryptedAtMax:encrypted(maxEnvelope),encryptedAtDraftAdmission:encrypted(36500),
  encryptedAt64KiB:encrypted(65536)},null,2));
assert(maxima.resolverUnits<=8192); assert(maxima.resolverBytes<=24576);
assert(maxima.saveCallableBytes<=32768 && maxima.resolveCallableBytes<=32768);
assert(maxima.pendingBytes<=16384); assert(maxEnvelope<=36500);
assert(Math.max(...Object.values(encrypted(36500)))<=98304);
// Existing envelope metadata has no universal finite total: real-record admission is mandatory.
const p=pending('save-program-draft',{draftSave:draft('😀')});
assert(bytes({...envelope(p,resolved(p),history.revocation),preferredWorkspaceId:'x'.repeat(65536)})>65536);
