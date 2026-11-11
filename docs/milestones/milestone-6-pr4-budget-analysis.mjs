// Documentation-only, offline sizing. Run from any directory with Node >=22:
// node docs/milestones/milestone-6-pr4-budget-analysis.mjs
// Requires the existing firebase/functions/node_modules from the unchanged lockfile.
// No app/backend build, service initialization, network, database or file writes.
// New publication shapes are MODELS, not implemented serializers or an endpoint.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(root, 'firebase/functions/package.json'));
const ts = require('typescript');
const loaded = new Map();
// Execute existing pure validators/hash functions from current TS, never stale lib/.
// Dependencies are loaded unchanged; no exported operation is invoked.
function sourceModule(file) {
  if (loaded.has(file)) return loaded.get(file).exports;
  const text = readFileSync(file, 'utf8'), module = { exports: {} };
  loaded.set(file, module);
  const js = ts.transpileModule(text, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const localRequire = name => name.startsWith('.')
    ? sourceModule(resolve(dirname(file), name.replace(/\.js$/, '.ts'))) : require(name);
  new Function('require', 'module', 'exports', js)(localRequire, module, module.exports);
  return module.exports;
}
const source = name => sourceModule(resolve(root, 'firebase/functions/src', name));
const { validDraftSave } = source('program-draft-contract.ts');
const { sourceIdentity } = source('program-drafts.ts');
const { stableHash, commandId } = source('hashing.ts');
const { Firestore, Timestamp, protos } = require('@google-cloud/firestore');
const db = new Firestore({ projectId: 'demo-tillfailure-m3' }); // no RPC is made
const serializer = db._serializer; // installed Admin implementation, no alternate encoder
const proto = protos.google.firestore.v1;
const bytes = x => Buffer.byteLength(JSON.stringify(x));
const utf8 = x => Buffer.byteLength(x);
const M = 2147483646, T = 8640000000000000;
const uuid = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const key = 'or1-b91f0176ec74475e8cf7406e25b7e9d3-' + 'a'.repeat(80);
const authority = { authorLifecycleRevision: M, authorMembershipRevision: M, workspaceMembershipRevision: M };
const hash = 'a'.repeat(64), templateId = uuid(1), versionId = 'v2147483645';
const maxima = {};
const record = (key, n) => { maxima[key] = Math.max(maxima[key] ?? 0, n); };
// Firestore's year-9999 maximum is narrower than the inherited millis validator.
// Recovery/DTO width models retain T as a conservative overestimate.
const stamp = Timestamp.fromMillis(253402300799999);
function projection(v) { return JSON.parse(JSON.stringify(v, (_, x) => x)); }
function pending(uid, wid, action, fields = {}) {
  return { uid, action, idempotencyKey: key, workspaceId: wid, invitationId: null,
    expectedVersion: null, email: null, onboarding: null, profileUpdate: null,
    acceptanceTokenDigest: null, resolutionStarted: false, revocation: null, ...fields };
}
function reply(fields) {
  return { invitationId: null, workspaceId: null, workspaceName: null, status: null,
    tokenVersion: null, expiresAtMillis: null, replayed: null, deliveryToken: null,
    summaries: null, accountRevision: null, onboardingRevision: null, terminalOutcome: null,
    revocationPreparation: null, revocationDecision: null, profile: null, profileCommandKey: null, ...fields };
}
const normal = (command, fields) => ({ command, source: 'normal-v1', resolution: null, acknowledgment: reply(fields) });
function resolution(command, kind, version, outcome) {
  return { command, source: 'resolution-v1', acknowledgment: null, resolution: {
    protocolVersion: 1, commandKind: kind, commandId: commandId(command.uid, command.idempotencyKey),
    requestHashVersion: version, requestHash: hash, outcome, decidedAtMillis: T } };
}
function marker(uid, kind, version) {
  return { command: null, source: 'compacted-v1', resolution: null, acknowledgment: null,
    marker: { schemaVersion: 1, uid, commandKind: kind, commandId: 'b'.repeat(64),
      requestHashVersion: version, requestHash: hash, source: 'resolution-v1',
      outcome: 'not-applied-conflict', decidedAtMillis: T } };
}
function envelope(uid, wid, p, proof, settled) {
  return { schemaVersion: 1, uid, offlineAccessGrant: null, downloadManifests: [],
    workoutRecoverySnapshot: null, mutationJournal: [], pendingUploads: [], switchMarker: null,
    preferredWorkspaceId: wid, pendingInvitationCommands: p ? [p] : [],
    pendingTerminalProof: proof, lastSettledCommand: settled };
}
// Document storage formula is calculated, not backend billing measurement.
const stringSize = s => utf8(s) + 1;
const nameSize = path => path.split('/').reduce((n, s) => n + stringSize(s), 16);
function valueSize(v) {
  if (v === null || typeof v === 'boolean') return 1;
  if (typeof v === 'number' || v instanceof Timestamp) return 8;
  if (typeof v === 'string') return stringSize(v);
  if (Array.isArray(v)) return v.reduce((n, x) => n + valueSize(x), 0);
  return 32 + Object.entries(v).reduce((n, [k, x]) => n + stringSize(k) + valueSize(x), 0);
}
function indexBudget(path, data) {
  const parent = path.split('/').slice(0, -2).join('/');
  const base = nameSize(path) + (parent ? nameSize(parent) : 0) + 32;
  let count = 0, total = 0, largest = 0;
  const entry = (field, v) => {
    const n = base + stringSize(field) + Math.min(1500, valueSize(v));
    count++; total += n; largest = Math.max(largest, n);
  };
  function fields(v, prefix = '') {
    for (const [k, x] of Object.entries(v)) {
      const field = prefix + k;
      // Current documented defaults include whole-map/whole-array asc/desc.
      entry(field, x); entry(field, x);
      if (Array.isArray(x)) x.forEach(e => entry(field, e));
      else if (x !== null && typeof x === 'object' && !(x instanceof Timestamp)) fields(x, field + '.');
    }
  }
  fields(data); return { count, total, largest };
}
function encodedDoc(path, data) {
  return { name: `projects/demo-tillfailure-m3/databases/(default)/documents/${path}`, fields: serializer.encodeFields(data) };
}
function measureLayout(label, entries) {
  const documents = entries.map(([path, data]) => encodedDoc(path, data));
  // Hypothetical commit assembled with actual Admin field/protobuf serializers.
  // A fixed 32-byte transaction token is a sizing fixture, not captured RPC traffic.
  const request = { database: 'projects/demo-tillfailure-m3/databases/(default)',
    transaction: Buffer.alloc(32), writes: documents.map(update => ({ update, currentDocument: { exists: false } })) };
  record(label + 'CommitProtobuf', proto.CommitRequest.encode(proto.CommitRequest.fromObject(request)).finish().length);
  record(label + 'Writes', entries.length);
  let indexes = 0, indexBytes = 0, storage = 0;
  entries.forEach(([path, data], i) => {
    record(label + 'LargestDocumentProtobuf', proto.Document.encode(proto.Document.fromObject(documents[i])).finish().length);
    const size = nameSize(path) + valueSize(data);
    record(label + 'LargestDocumentStorage', size); storage += size;
    const idx = indexBudget(path, data); indexes += idx.count; indexBytes += idx.total;
    record(label + 'LargestIndexEntry', idx.largest);
    record(label + 'LargestIndexCountPerDocument', idx.count);
  });
  record(label + 'Storage', storage); record(label + 'IndexEntries', indexes); record(label + 'IndexBytes', indexBytes);
}
let vectors = 0;
for (const idChar of ['\u0001', '界']) for (const c of ['😀', '界', '"', '\\', '/']) for (const kind of ['system', 'custom']) {
  const uid = idChar.repeat(128), wid = idChar.repeat(128);
  let remaining = 210;
  const sources = new Map();
  const draft = { templateId, expectedRevision: M - 1, authority, title: c.repeat(80), description: c.repeat(240),
    workouts: Array.from({ length: 3 }, (_, w) => ({ id: uuid(w + 2), title: c.repeat(40),
      items: Array.from({ length: 4 }, (_, i) => {
        const index = w * 4 + i, n = Math.min(remaining - (11 - index), 120); remaining -= n;
        const sourceId = kind === 'system' ? 'z'.repeat(126) + String(index).padStart(2, '0') : uuid(100 + index);
        const name = c.repeat(n), instructions = c.repeat(2000);
        const labels = Array.from({ length: 8 }, (_, j) => c.repeat(39) + String.fromCodePoint(0x1f600 + j));
        const definition = kind === 'system'
          ? { schemaVersion: 1, name, nameNormalized: c.repeat(120), instructions, muscleGroups: labels, equipment: labels, status: 'published' }
          : { schemaVersion: 1, policyVersion: 1, workspaceId: wid, exerciseId: sourceId, ownerUid: uid,
            name, instructions, muscleGroups: ['arms', 'shoulders', 'whole-body'], equipment: ['barbell', 'dumbbell', 'machine'],
            revision: M, createdAt: stamp, updatedAt: stamp };
        const identity = sourceIdentity(kind, sourceId, wid, uid, definition); assert(identity);
        sources.set(sourceId, definition);
        return { id: uuid(10 + index), ...identity, prescription: { sets: 20, reps: 1000, restSeconds: 3600, loadGrams: 1000000 } };
      }) })) };
  assert(validDraftSave(draft)); assert.equal(remaining, 0); vectors++;
  const contentBase = { contentPolicyVersion: 1, workspaceId: wid, templateId, ownerUid: uid,
    title: draft.title, description: draft.description, workouts: draft.workouts };
  const draftHash = stableHash({ hashVersion: 'program-draft-content-v1', ...contentBase });
  const content = { ...contentBase, workouts: draft.workouts.map(w => ({ ...w, items: w.items.map(i => {
    const s = sources.get(i.sourceId);
    return { ...i, instructions: s.instructions, muscleGroups: s.muscleGroups, equipment: s.equipment };
  }) })) };
  const contentHash = stableHash({ hashVersion: 'program-version-content-v1', ...content });
  const publish = { templateId, expectedRevision: M - 1, authority, draftHash, contentPolicyVersion: 1 };
  const descriptor = { action: 'publish-program-template', workspaceId: wid, idempotencyKey: key, publish };
  const resolver = { protocolVersion: 1, descriptor };
  record('descriptorJson', bytes(descriptor)); record('resolverUnits', JSON.stringify(resolver).length);
  record('resolverJson', bytes(resolver)); record('publishCallableJson', bytes({ data: descriptor })); record('resolveCallableJson', bytes({ data: resolver }));
  const version = { schemaVersion: 1, ...content, versionId, versionNumber: M - 1, draftRevision: M - 1,
    draftHash, contentHash, publishedAt: stamp };
  const nextRoot = { schemaVersion: 2, policyVersion: 1, workspaceId: wid, templateId, ownerUid: uid, revision: M,
    createdAt: stamp, updatedAt: stamp, title: draft.title, description: draft.description, workouts: draft.workouts,
    versionCount: M - 1, currentVersionId: versionId };
  const versionDto = { ...version, publishedAtMillis: T }; delete versionDto.publishedAt;
  record('versionDtoJson', bytes(versionDto)); record('rootJson', bytes(projection(nextRoot)));
  const preview = { workspaceId: wid, publish, nextVersionNumber: M - 1, preview: content, contentHash };
  record('prepareResultJson', bytes(preview));
  record('prepareCallableResponseJson', bytes({ result: preview }));
  record('versionGetCallableResponseJson', bytes({ result: { workspaceId: wid, authority, version: versionDto } }));
  const applied = { status: 'program-published', workspaceId: wid, templateId, templateRevision: M,
    versionId, versionNumber: M - 1, contentHash };
  const receipt = { schemaVersion: 1, commandKind: 'program-template-publish', callerUid: uid, workspaceId: wid, templateId,
    requestHash: stableHash({ ...descriptor, callerUid: uid, policyVersion: 1 }), result: applied, committedAt: stamp };
  record('receiptJson', bytes(projection(receipt)));
  const basePath = `workspaces/${wid}/programTemplates/${templateId}`, versionPath = `${basePath}/versions/${versionId}`;
  const common = [[basePath, nextRoot], [`lifecycleCommands/${commandId(uid, key)}`, receipt]];
  measureLayout('inline', [[versionPath, version], ...common]);
  // Bounded comparison: manifest of workout IDs, per-workout item IDs, per-item copies.
  const { workouts, ...header } = version;
  const children = [[versionPath, { ...header, workoutIds: workouts.map(w => w.id) }]];
  for (const w of workouts) {
    const path = `${versionPath}/workouts/${w.id}`;
    children.push([path, { schemaVersion: 1, id: w.id, title: w.title, itemIds: w.items.map(i => i.id) }]);
    for (const i of w.items) children.push([`${path}/exercises/${i.id}`, { schemaVersion: 1, ...i }]);
  }
  measureLayout('children', [...children, ...common]);
  const p = pending(uid, wid, descriptor.action, { publish });
  const proofs = [normal(p, { publishDecision: { ...applied, replayed: false } }),
    ...['publication-conflict', 'publication-draft-conflict', 'publication-source-conflict', 'publication-incomplete'].map(status =>
      normal(p, { publishDecision: { status, workspaceId: wid, templateId,
        templateRevision: status === 'publication-conflict' ? M : M - 1, versionId: null, versionNumber: null, contentHash: null, replayed: false } })),
    ...['applied', 'not-applied-conflict', 'not-applied-fenced'].map(outcome => resolution(p, 'program-template-publish', 'program-publication-v1', outcome))];
  record('pendingJson', bytes(p)); proofs.forEach(proof => record('fullProofJson', bytes(proof)));
  // Full legacy note is a supported old representation; newer markers are much smaller.
  // Also bound old custom/draft full proofs, seven legacy kinds, and all five compacted kinds.
  const oldKey = '\u0004'.repeat(128);
  const histories = { empty: null };
  const old = (action, fields = {}) => pending(uid, wid, action, { idempotencyKey: oldKey, ...fields });
  for (const action of ['create', 'resend', 'revoke', 'accept']) {
    const cmd = old(action, action === 'create' ? { email: 'x'.repeat(240) + '@example.test' }
      : { invitationId: hash, expectedVersion: M, ...(action === 'accept' ? { workspaceId: null, acceptanceTokenDigest: hash } : {}) });
    histories[action] = normal(cmd, { invitationId: hash, workspaceId: wid, status: action === 'accept' ? 'accepted' : action === 'revoke' ? 'revoked' : 'pending',
      tokenVersion: action === 'create' ? 1 : action === 'resend' ? M + 1 : M, expiresAtMillis: T, replayed: true });
  }
  const basics = { displayName: '界'.repeat(80), locale: 'bs-BA', timeZoneId: 'America/Argentina/ComodRivadavia' };
  histories.onboarding = normal(old('complete-onboarding', { onboarding: { ...basics, acknowledged: true, expectedAccountRevision: M - 1, expectedOnboardingRevision: M - 1 } }),
    { workspaceId: wid, status: 'complete', accountRevision: M, onboardingRevision: M, replayed: true });
  histories.profile = normal(old('update-profile', { profileUpdate: { ...basics, expectedRevision: M - 1 } }),
    { workspaceId: wid, status: 'profile-updated', accountRevision: M, profileCommandKey: oldKey, replayed: true });
  histories.revocation = normal(old('revoke-client', { revocation: { clientUid: uid, expectedTargetRevision: M - 1,
    expectedWorkspaceRevision: M - 1, expectedCallerMembershipRevision: M - 1, expectedCallerLifecycleRevision: M - 1 } }),
    { replayed: true, revocationDecision: { workspaceId: wid, clientUid: uid, status: 'client-revoked', commandKey: oldKey,
      targetMembershipRevision: M, workspaceRevision: M, callerMembershipRevision: M - 1, callerLifecycleRevision: M - 1 } });
  histories.fullNote = normal(old('save-trainer-note', { noteSave: { clientUid: uid, body: '😀'.repeat(2000), bindingNonce: hash, expectedRevision: M - 1,
    authorMembershipRevision: M, subjectMembershipRevision: M, authorLifecycleRevision: M, subjectLifecycleRevision: M } }),
    { noteDecision: { status: 'note-saved', workspaceId: wid, clientUid: uid, noteRevision: M, replayed: false } });
  for (const action of ['create-custom-exercise', 'correct-custom-exercise']) histories[action] = normal(old(action, {
    idempotencyKey: key.replace(/a{80}$/, 'b'.repeat(80)), exerciseWrite: { exerciseId: uuid(500), expectedRevision: action.startsWith('create') ? 0 : M - 1,
      authority, name: '😀'.repeat(120), instructions: '😀'.repeat(2000), muscleGroups: ['arms', 'shoulders', 'whole-body'], equipment: ['barbell', 'dumbbell', 'machine'] } }),
    { exerciseDecision: { status: action.startsWith('create') ? 'exercise-created' : 'exercise-corrected', workspaceId: wid, exerciseId: uuid(500), exerciseRevision: action.startsWith('create') ? 1 : M, replayed: false } });
  const oldDraft = { ...draft, workouts: draft.workouts.map(w => ({ ...w, items: w.items.map(i => ({ ...i, prescription: { ...i.prescription, sets: null } })) })) };
  assert(validDraftSave(oldDraft)); // nullable old prescriptions are wider than published sets
  histories.fullDraft = normal(old('save-program-draft', { idempotencyKey: key.replace(/a{80}$/, 'b'.repeat(80)), draftSave: oldDraft }),
    { draftDecision: { status: 'draft-saved', workspaceId: wid, templateId, templateRevision: M, replayed: false } });
  for (const [kind, version] of [['trainer-note-save', 'trainer-note-v1'], ['custom-exercise-create', 'custom-exercise-v1'],
    ['custom-exercise-correct', 'custom-exercise-v1'], ['program-draft-save', 'program-draft-v1'], ['program-template-publish', 'program-publication-v1']]) histories[kind] = marker(uid, kind, version);
  for (const [history, settled] of Object.entries(histories)) for (const started of [false, true]) {
    const cmd = { ...p, resolutionStarted: started };
    const states = [envelope(uid, wid, cmd, null, settled), ...proofs.map(proof => envelope(uid, wid, cmd, { ...proof, command: cmd }, settled)),
      envelope(uid, wid, null, null, marker(uid, 'program-template-publish', 'program-publication-v1'))];
    const departure = envelope(uid, wid, null, null, marker(uid, 'program-template-publish', 'program-publication-v1'));
    departure.switchMarker = { schemaVersion: 1, departingUid: uid, accountEpoch: 9223372036854775807n.toString(), state: 'SwitchingOut' };
    // Quoted 19-digit epoch deliberately overestimates the actual integer by two bytes.
    states.push(departure);
    for (const state of states) record('envelope-' + history, bytes(state));
  }
}
const maxEnvelope = Math.max(...Object.entries(maxima).filter(([k]) => k.startsWith('envelope-')).map(([, v]) => v));
const encrypted = n => ({ androidBound: 108 + 4 * Math.ceil((n + 16) / 3), swiftBound: 181 + 8 * Math.ceil(n / 3) });
const vector = { action: 'publish-program-template', workspaceId: 'w', idempotencyKey: key.slice(0, -80) + 'publication-vector',
  publish: { templateId, expectedRevision: 1, authority: { authorLifecycleRevision: 1, authorMembershipRevision: 1, workspaceMembershipRevision: 1 },
    draftHash: hash, contentPolicyVersion: 1 } };
assert(maxima.resolverUnits <= 8192 && maxima.resolverJson <= 24576);
assert(maxima.publishCallableJson <= 32768 && maxima.resolveCallableJson <= 32768 && maxima.pendingJson <= 16384);
assert(maxEnvelope <= 36500 && encrypted(36500).swiftBound <= 98304);
assert(maxima.versionDtoJson <= 163840 && maxima.rootJson <= 16384 && maxima.receiptJson <= 4096);
assert(maxima.prepareResultJson <= 196608 && maxima.inlineCommitProtobuf < 262144);
assert(maxima.prepareCallableResponseJson <= 196608 && maxima.versionGetCallableResponseJson <= 196608);
assert(maxima.inlineLargestIndexEntry < 4096 && maxima.inlineLargestIndexCountPerDocument <= 128);
console.log(JSON.stringify({ classification: 'TS validation/hash + actual Admin serializers on hypothetical documents; recovery JSON is a model, not Kotlin/native execution',
  node: process.version, firestore: require('@google-cloud/firestore/package.json').version, typescript: ts.version, vectors,
  maxima, maxEnvelope, encryptedAtModeledMaximum: encrypted(maxEnvelope), encryptedAtAdmission: encrypted(36500),
  syntheticVector: { callerUid: 'A', descriptor: vector, commandId: commandId('A', vector.idempotencyKey),
    requestHash: stableHash({ ...vector, callerUid: 'A', policyVersion: 1 }) },
  sourceFingerprint: createHash('sha256').update([...loaded.keys()].sort().map(p => `${createHash('sha256').update(readFileSync(p)).digest('hex')}  ${p.slice(root.length + 1)}\n`).join('')).digest('hex') }, null, 2));
