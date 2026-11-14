import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { before, beforeEach, after, describe, it } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds, RulesTestEnvironment, RulesTestContext } from '@firebase/rules-unit-testing';
import { collection, doc, getDoc, getDocs, query, where, orderBy, limit, setDoc, Timestamp } from 'firebase/firestore';
import { assignmentFixture, assignmentRulesFixture } from './assignment-pr1a-fixture.js';
import { assignmentBinding, DAY_MS, executionTime, materializeAssignment, validateClientAssignmentDocuments } from '../src/assignment-feasibility-contract.js';
let env: RulesTestEnvironment;
let f: ReturnType<typeof assignmentRulesFixture>;
let base: string, source: string, index: string;
async function seedAssignment(db: ReturnType<RulesTestContext['firestore']>, fixture: ReturnType<typeof assignmentRulesFixture>) {
  for (const [p, value] of materializeAssignment(fixture.descriptor, fixture.version, fixture.uid)) {
    const data = { ...value };
    for (const k of ['scheduledInstant', 'accessExpiresAt']) if (`${k}Millis` in data) { data[k] = Timestamp.fromMillis(data[`${k}Millis`]); delete data[`${k}Millis`]; }
    await setDoc(doc(db, p), data);
  }
}
before(async () => {
  const production = await readFile('../firestore.rules', 'utf8');
  const start = production.indexOf('    match /workspaces/{wid}/assignedPrograms/{aid} {');
  const end = production.indexOf('    match /spikeEcho/', start);
  assert.ok(start > 0 && end > start);
  const isolated = production.slice(0, start) + '    match /workspaces/{wid}/assignedPrograms/{aid} { allow read, write: if false; }\n\n' + production.slice(end);
  env = await initializeTestEnvironment({ projectId: 'demo-tillfailure-m7-pr1a-rules', firestore: { rules: isolated, host: '127.0.0.1', port: Number(process.env.FIRESTORE_EMULATOR_HOST!.split(':')[1]) } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  f = assignmentRulesFixture(); // One Date.now() capture per live fixture, with 48h read validity.
  base = `users/${f.client}/workspaces/${f.wid}/assignedPrograms/${f.descriptor.assignment.assignmentId}`;
  source = `workspaces/${f.wid}/programTemplates/${f.version.templateId}/versions/${f.version.versionId}`;
  index = `workspaces/${f.wid}/assignedPrograms/${f.descriptor.assignment.assignmentId}`;
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore();
    for (const [uid, role] of [['A', 'trainer'], ['B', 'client'], ['C', 'client']]) {
      await setDoc(doc(db, `users/${uid}`), { schemaVersion: 1, accountStatus: 'active' });
      await setDoc(doc(db, `workspaces/w/memberships/${uid}`), { schemaVersion: 1, workspaceId: 'w', userId: uid, status: 'active', role });
    }
    await setDoc(doc(db, 'workspaces/w'), { schemaVersion: 1, status: 'active' });
    await seedAssignment(db, f);
    await setDoc(doc(db, source), f.version);
  });
});
describe('M7 PR1a isolated proposed trainer denial and actual client queries', () => {
  it('keeps live descriptors valid after the frozen vector expiry without changing any clock', () => {
    const reference = Date.UTC(2030, 0, 1), frozen = assignmentFixture();
    assert.ok(reference > frozen.descriptor.assignment.accessExpiresAtMillis);
    for (const state of ['active', 'expired'] as const) {
      const relative = assignmentRulesFixture(reference, state), a = relative.descriptor.assignment;
      assert.equal(a.scheduledInstantMillis - a.preparedAtMillis, DAY_MS);
      assert.equal(a.accessExpiresAtMillis - a.scheduledInstantMillis, DAY_MS);
      assert.equal(a.accessExpiresAtMillis - reference, (state === 'active' ? 2 : -1) * DAY_MS);
      assert.equal(executionTime(a, reference), state === 'active' ? 'current' : 'assignment-schedule-conflict');
      assert.deepEqual(relative.binding, assignmentBinding(relative.uid, relative.descriptor));
      assert.equal(relative.binding.id, assignmentBinding(frozen.uid, frozen.descriptor).id);
      assert.notEqual(relative.binding.hash, assignmentBinding(frozen.uid, frozen.descriptor).hash);
      assert.equal(a.sourceContentHash, frozen.descriptor.assignment.sourceContentHash);
      assert.equal(a.snapshotHash, frozen.descriptor.assignment.snapshotHash);
      const prefix = `users/${relative.client}/workspaces/${relative.wid}/assignedPrograms/${a.assignmentId}`;
      const copy = new Map([...materializeAssignment(relative.descriptor, relative.version, relative.uid)]
        .filter(([path]) => path.startsWith(prefix)));
      assert.ok(validateClientAssignmentDocuments(relative.client, relative.wid, a.assignmentId, copy));
    }
  });
  it('denies trainer index get/list for every role, and clients never read source content', async () => {
    for (const uid of ['A', 'B', 'C']) {
      const db = env.authenticatedContext(uid, { email_verified: true }).firestore();
      await assertFails(getDoc(doc(db, index)));
      await assertFails(getDocs(query(collection(db, 'workspaces/w/assignedPrograms'), where('schemaVersion', '==', 1), where('workspaceId', '==', 'w'), where('trainerId', '==', uid), limit(6))));
      await assertFails(getDoc(doc(db, source))); await assertFails(getDocs(collection(db, source + '/workouts')));
      await assertFails(setDoc(doc(db, base), { clientId: uid }));
    }
  });
  it('reads actual bounded own header/workout/item/plan/inventory queries and validates the complete copy without source reads', async () => {
    const db = env.authenticatedContext('B', { email_verified: true }).firestore();
    await assertSucceeds(getDocs(query(collection(db, 'users/B/workspaces/w/assignedPrograms'), orderBy('__name__'), limit(6))));
    const exactQuery = (p: string) => query(collection(db, p), where('clientId', '==', 'B'), where('workspaceId', '==', 'w'),
      where('assignmentId', '==', f.descriptor.assignment.assignmentId), where('snapshotId', '==', 'content'), limit(13));
    await assertSucceeds(getDocs(exactQuery(base + '/snapshots/content/workouts')));
    await assertSucceeds(getDocs(exactQuery(base + `/snapshots/content/workouts/${f.version.workouts[0]!.id}/exercises`)));
    await assertSucceeds(getDocs(query(collection(db, base + '/plannedWorkouts'), where('clientId', '==', 'B'), where('workspaceId', '==', 'w'),
      where('assignmentId', '==', f.descriptor.assignment.assignmentId), where('snapshotId', '==', 'content'), where('status', '==', 'planned'), orderBy('scheduledInstant'), limit(2))));
    const actual = new Map<string, any>();
    for (const path of materializeAssignment(f.descriptor, f.version, f.uid).keys()) {
      if (!path.startsWith(base)) continue; // Trainer index is not client content.
      const value = (await assertSucceeds(getDoc(doc(db, path)))).data()!;
      for (const k of ['scheduledInstant', 'accessExpiresAt']) if (value[k]) { value[`${k}Millis`] = value[k].toMillis(); delete value[k]; }
      actual.set(path, value);
    }
    assert.ok(validateClientAssignmentDocuments('B', 'w', f.descriptor.assignment.assignmentId, actual));
    for (const [path, value] of actual) {
      const changed = new Map(actual); changed.set(path, { ...value, privateNote: 'private' });
      assert.equal(validateClientAssignmentDocuments('B', 'w', f.descriptor.assignment.assignmentId, changed), false);
      const missing = new Map(actual); missing.delete(path); assert.equal(validateClientAssignmentDocuments('B', 'w', f.descriptor.assignment.assignmentId, missing), false);
    }
    const malformed = new Map(actual), manifest = malformed.get(base + '/manifests/download');
    malformed.set(base + '/manifests/download', { ...manifest, requiredPaths: [source] });
    assert.equal(validateClientAssignmentDocuments('B', 'w', f.descriptor.assignment.assignmentId, malformed), false);
  });
  it('denies cross-client/workspace, anonymous/unverified and malformed/expired/terminal parent content while safe terminal header remains readable', async () => {
    for (const uid of ['A', 'C']) {
      const db = env.authenticatedContext(uid, { email_verified: true }).firestore();
      await assertFails(getDoc(doc(db, base))); await assertFails(getDoc(doc(db, base + '/snapshots/content')));
      await assertFails(getDocs(query(collection(db, 'users/B/workspaces/w/assignedPrograms'), limit(6))));
    }
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), base + '/snapshots/content')));
    await assertFails(getDoc(doc(env.authenticatedContext('B', { email_verified: false }).firestore(), base + '/snapshots/content')));
    const db = env.authenticatedContext('B', { email_verified: true }).firestore();
    await assertFails(getDoc(doc(db, base.replace('/w/', '/foreign/') + '/snapshots/content')));
    const original = (await getDoc(doc(db, base))).data()!;
    for (const patch of [{ accessStatus: 'revoked' }, { lifecycleState: 'terminal' },
      { accessExpiresAt: 'future' }, { clientId: 'C' }, { workspaceId: 'foreign' }, { snapshotId: 'other' }, { schemaVersion: 2 }]) {
      await env.withSecurityRulesDisabled(async ctx => { await setDoc(doc(ctx.firestore(), base), { ...original, ...patch }); });
      await assertFails(getDoc(doc(db, base + '/snapshots/content')));
      await assertFails(getDoc(doc(db, base + '/manifests/download')));
      await assertFails(getDocs(query(collection(db, base + '/plannedWorkouts'), limit(2))));
    }
    await env.withSecurityRulesDisabled(async ctx => { await setDoc(doc(ctx.firestore(), base), { ...original, accessStatus: 'revoked', lifecycleState: 'terminal' }); });
    await assertSucceeds(getDoc(doc(db, base)));
    const expired = assignmentRulesFixture(f.descriptor.assignment.preparedAtMillis, 'expired');
    assert.ok(expired.descriptor.assignment.accessExpiresAtMillis < f.descriptor.assignment.preparedAtMillis);
    await env.withSecurityRulesDisabled(async ctx => { await seedAssignment(ctx.firestore(), expired); });
    await assertFails(getDoc(doc(db, base + '/snapshots/content')));
    await assertFails(getDoc(doc(db, base + '/manifests/download')));
    await assertFails(getDocs(query(collection(db, base + '/plannedWorkouts'), limit(2))));
    await assertSucceeds(getDoc(doc(db, base))); // Safe expired metadata still has its existing gate.
  });
});
