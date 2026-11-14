import assert from 'node:assert/strict';
import { it, describe } from 'node:test';
import { mkdir, writeFile } from 'node:fs/promises';
import { Firestore, Timestamp } from '@google-cloud/firestore';
import { createRequire } from 'node:module';
const { protos } = createRequire(__filename)('@google-cloud/firestore');
import { assignmentFixture } from './assignment-pr1a-fixture.js';
import { assignmentBinding, assignmentDescriptor, validAssignment, executionTime, assignmentDecision, validAssignmentDecision,
  materializeAssignment, validateAssignmentDocuments, validateClientAssignmentDocuments, ASSIGNMENT_STATUSES, MAX_FIRESTORE_MILLIS, DAY_MS } from '../src/assignment-feasibility-contract.js';
describe('M7 PR1a pure assignment contracts', () => {
  it('freezes canonical source/copy/request vectors and exports actual TS DTOs', async () => {
    const out = process.env.TF_M7_PR1A_EVIDENCE;
    for (const character of ['S', 'é/\\"\n\u2028界😀']) {
      const f = assignmentFixture(character); const b = assignmentBinding(f.uid, f.descriptor);
      assert.equal(assignmentDescriptor(f.descriptor), f.descriptor);
      assert.notEqual(f.descriptor.assignment.snapshotHash, f.version.contentHash);
      const docs = materializeAssignment(f.descriptor, f.version, f.uid);
      assert.ok(validateAssignmentDocuments(f.descriptor, f.version, f.uid, docs));
      for (const [path, value] of docs) {
        const changed = new Map(docs); changed.set(path, { ...value, privateNote: 'must never be copied' });
        assert.equal(validateAssignmentDocuments(f.descriptor, f.version, f.uid, changed), false);
      }
      if (out) { await mkdir(`${out}/ts`, { recursive: true }); await writeFile(`${out}/ts/${character === 'S' ? 'ascii' : 'escaped'}.json`, JSON.stringify({ ...f, binding: b, documents: [...docs] })); }
      console.log('M7_VECTOR', JSON.stringify({ character: character === 'S' ? 'ascii' : 'escaped', ...b, sourceHash: f.version.contentHash, copyHash: f.descriptor.assignment.snapshotHash }));
    }
  });
  it('defines integral UTC milliseconds, inclusive 24h ceiling, exclusive execution/expiry boundaries', () => {
    const { descriptor: d } = assignmentFixture(); const a = d.assignment;
    assert.ok(validAssignment(a)); assert.equal(executionTime(a, a.preparedAtMillis), 'current');
    assert.equal(executionTime(a, a.scheduledInstantMillis - 1), 'current');
    assert.equal(executionTime(a, a.scheduledInstantMillis), 'assignment-schedule-conflict');
    assert.equal(executionTime(a, a.accessExpiresAtMillis), 'assignment-schedule-conflict');
    for (const t of [0, 1, DAY_MS, DAY_MS + 1]) {
      const n = { ...a, scheduledInstantMillis: a.preparedAtMillis + t, accessExpiresAtMillis: a.preparedAtMillis + t + DAY_MS };
      assert.equal(validAssignment(n), t > 0 && t <= DAY_MS);
    }
    for (const bad of [NaN, Infinity, -1, 0.5, Number.MAX_SAFE_INTEGER, MAX_FIRESTORE_MILLIS + 1, '1', null, true])
      assert.equal(validAssignment({ ...a, preparedAtMillis: bad }), false);
    assert.throws(() => executionTime(a, a.preparedAtMillis - 1), /clock/);
    assert.equal(validAssignment({ ...a, accessExpiresAtMillis: a.accessExpiresAtMillis + 1 }), false);
    assert.ok(validAssignment(assignmentFixture('😀', 12, true).descriptor.assignment));
  });
  it('rejects unknown/foreign identities and incomplete, altered or multiple-workout source copies', () => {
    const f = assignmentFixture(), d = f.descriptor;
    for (const patch of [{ assignmentId: '../x' }, { clientUid: 'a/b' }, { copyPolicyVersion: 2 }, { versionNumber: 0 }, { snapshotHash: 'bad' }, { extra: true }])
      assert.equal(validAssignment({ ...d.assignment, ...patch }), false);
    assert.throws(() => assignmentBinding(f.client, d), /self/);
    assert.throws(() => assignmentDescriptor({ ...d, callerUid: 'A' }));
    for (const key of [[d.idempotencyKey], null, 1, { value: d.idempotencyKey }])
      assert.throws(() => assignmentDescriptor({ ...d, idempotencyKey: key }), /assignment-invalid/);
    const base = `users/${f.client}/workspaces/${f.wid}/assignedPrograms/${d.assignment.assignmentId}`;
    const docs = new Map([...materializeAssignment(d, f.version, f.uid)].filter(([path]) => path.startsWith(base)));
    for (const client of ['../other', '__reserved__', '\ud800', '', 'x'.repeat(129)]) {
      const changedBase = `users/${client}/workspaces/${f.wid}/assignedPrograms/${d.assignment.assignmentId}`;
      const changed = new Map([...docs].map(([path, value]) => [changedBase + path.slice(base.length), { ...value, clientId: client }]));
      assert.equal(validateClientAssignmentDocuments(client, f.wid, d.assignment.assignmentId, changed), false);
    }
    for (const patch of [{ ownerUid: 'foreign' }, { privateNote: 'secret' }, { contentHash: 'a'.repeat(64) }, { versionNumber: 2 }, { title: '' }, { workouts: [] }, { workouts: [f.version.workouts[0], f.version.workouts[0]] }])
      assert.throws(() => materializeAssignment(d, { ...f.version, ...patch }, f.uid));
    assert.throws(() => materializeAssignment({ ...d, assignment: { ...d.assignment, snapshotHash: 'a'.repeat(64) } }, f.version, f.uid), /preview/);
    const original = JSON.stringify(f.version); materializeAssignment(d, f.version, f.uid); assert.equal(JSON.stringify(f.version), original);
  });
  it('strictly binds every minimal normal outcome; no content or current-state grant', () => {
    const { descriptor: d } = assignmentFixture();
    for (const status of ASSIGNMENT_STATUSES) {
      const v = assignmentDecision(d, status); assert.ok(validAssignmentDecision(v, d));
      assert.equal(validAssignmentDecision({ ...v, extra: 1 }, d), false);
      assert.equal(validAssignmentDecision({ ...v, assignmentId: 'foreign' }, d), false);
      assert.equal(validAssignmentDecision({ ...v, assignmentRevision: 99 }, d), false);
    }
  });
  it('measures real Admin SDK documents/commit and automatic-index bounds on valid 12-item content', async () => {
    const db = new Firestore({ projectId: 'demo-tillfailure-m3' }); const serializer = (db as any)._serializer;
    const maximum: Record<string, number> = {}; const out = process.env.TF_M7_PR1A_EVIDENCE;
    for (const widePath of [false, true]) for (const character of ['😀', '界', '"', '\\', '/']) {
      const f = assignmentFixture(character, 12, true, widePath), b = assignmentBinding(f.uid, f.descriptor);
      const docs = materializeAssignment(f.descriptor, f.version, f.uid);
      const result = assignmentDecision(f.descriptor, 'assignment-created');
      docs.set(`lifecycleCommands/${b.id}`, { schemaVersion: 1, commandKind: 'program-assignment-create', callerUid: f.uid,
        workspaceId: f.wid, requestHash: b.hash, result, committedAt: Timestamp.fromMillis(1) });
      docs.set(`workspaces/${f.wid}`, { schemaVersion: 1, status: 'active', membershipRevision: 2147483646 });
      const writes = [...docs].map(([path, data]) => ({ update: { name: `projects/demo-tillfailure-m3/databases/(default)/documents/${path}`,
        fields: serializer.encodeFields(data) }, currentDocument: { exists: path === `workspaces/${f.wid}` } }));
      const packet = protos.google.firestore.v1.CommitRequest.fromObject({ database: 'projects/demo-tillfailure-m3/databases/(default)', transaction: Buffer.alloc(9), writes });
      const n = protos.google.firestore.v1.CommitRequest.encode(packet).finish().length;
      const sizes = { version: Buffer.byteLength(JSON.stringify(f.version)), commit: n, writes: writes.length,
        resolverUnits: JSON.stringify({ protocolVersion: 1, descriptor: f.descriptor }).length,
        request: Buffer.byteLength(JSON.stringify({ data: f.descriptor })), largestDocument: Math.max(...[...docs.values()].map(v => Buffer.byteLength(JSON.stringify(v)))) };
      for (const [k, v] of Object.entries(sizes)) maximum[k] = Math.max(maximum[k] ?? 0, v);
      assert.equal(writes.length, 20); assert.ok(n < 524288); assert.ok(sizes.request < 32768);
      // Automatic defaults: two entries per scalar/map leaf, plus one per array element.
      for (const data of docs.values()) {
        let entries = 0;
        const fields = (o: any) => { for (const v of Object.values(o)) { entries += 2; if (Array.isArray(v)) entries += v.length; else if (v && typeof v === 'object' && !(v instanceof Timestamp)) fields(v); } };
        fields(data); assert.ok(entries <= 64, `index entries ${entries}`);
      }
      if (out && !widePath) { await mkdir(`${out}/ts`, { recursive: true }); await writeFile(`${out}/ts/boundary-${character.codePointAt(0)}.json`, JSON.stringify({ ...f, binding: b, documents: [...materializeAssignment(f.descriptor, f.version, f.uid)] })); }
    }
    console.log('M7_ACTUAL_ADMIN', JSON.stringify(maximum));
    if (out) await writeFile(`${out}/admin-measurements.json`, JSON.stringify(maximum, null, 2));
  });
});
