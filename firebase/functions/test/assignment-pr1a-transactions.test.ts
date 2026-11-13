import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { mkdir, writeFile } from 'node:fs/promises';
import { Firestore } from 'firebase-admin/firestore';
import { assignmentFixture } from './assignment-pr1a-fixture.js';
import { seedProbeAuthority, probeAssignment, rejectLegacyProductKey, probeDiscovery } from './assignment-pr1a-probe.js';
import { assignmentBinding, materializeAssignment } from '../src/assignment-feasibility-contract.js';
import { publishAssignment, closeAssignment } from '../src/assigned-program.js';
import { commandId } from '../src/hashing.js';
import { publicationContentHash, publicationDraftHash, publicationDraft } from '../src/program-publication-contract.js';
const db = new Firestore({ projectId: 'demo-tillfailure-m7-pr1a' });
const f = () => assignmentFixture();
const seed = async (fixture = f()) => {
  await seedProbeAuthority(db, fixture.uid, fixture.client, fixture.wid, fixture.descriptor.assignment.authority);
  await db.doc(`workspaces/${fixture.wid}/programTemplates/${fixture.version.templateId}/versions/${fixture.version.versionId}`).create(fixture.version);
  return fixture;
};
const gate = () => { let ready!: () => void, release!: () => void; return { ready: new Promise<void>(r => ready = r), held: new Promise<void>(r => release = r), enter: () => ready(), release: () => release() }; };
describe('M7 PR1a isolated dual-namespace transactions', () => {
  beforeEach(async () => {
    assert.match(process.env.FIRESTORE_EMULATOR_HOST ?? '', /^127\.0\.0\.1:/);
    await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/demo-tillfailure-m7-pr1a/databases/(default)/documents`, { method: 'DELETE' });
  });
  it('writes a complete 20-write boundary commit, replays and resolves exactly without another copy', async () => {
    const fixture = await seed(assignmentFixture('😀', 12, true)), { uid, descriptor: d, version } = fixture;
    const native = db as any, original = native.request; let count = 0, measured = 0;
    const proto = require('@google-cloud/firestore').protos.google.firestore.v1.CommitRequest;
    native.request = function(method: string, request: any, ...args: any[]) {
      if (method === 'commit' && request.writes.some((x: any) => x.update?.name.includes('/snapshots/content'))) {
        count = request.writes.length; measured = proto.encode(proto.fromObject(request)).finish().length;
        assert.equal(count, 20); assert.ok(request.transaction.length <= 9); assert.ok(measured <= 524288);
      }
      return original.call(this, method, request, ...args);
    };
    try { assert.equal((await probeAssignment(db, uid, d, 'write', d.assignment.preparedAtMillis)).status, 'assignment-created'); }
    finally { native.request = original; }
    const artifacts = materializeAssignment(d, version, uid);
    for (const p of artifacts.keys()) assert.ok((await db.doc(p).get()).exists);
    assert.equal((await db.doc(`workspaces/${d.workspaceId}`).get()).get('membershipRevision'), d.assignment.authority.workspaceMembershipRevision + 1);
    assert.equal((await probeAssignment(db, uid, d, 'write', d.assignment.preparedAtMillis)).replayed, true);
    assert.equal((await probeAssignment(db, uid, d, 'resolve', d.assignment.preparedAtMillis)).outcome, 'applied');
    assert.equal((await db.collection('lifecycleCommands').get()).size, 1);
    console.log('M7_ACTUAL_TRANSACTION', JSON.stringify({ writes: count, commit: measured }));
    const out = process.env.TF_M7_PR1A_EVIDENCE;
    if (out) { await mkdir(out, { recursive: true }); await writeFile(`${out}/actual-transaction.json`, JSON.stringify({ writes: count, commit: measured })); }
  });
  it('resolver-first prevents delayed write and changed kind/hash/target never consumes another decision', async () => {
    const { uid, descriptor: d } = await seed();
    assert.equal((await probeAssignment(db, uid, d, 'resolve', d.assignment.preparedAtMillis)).outcome, 'not-applied-fenced');
    assert.equal((await probeAssignment(db, uid, d, 'write', d.assignment.preparedAtMillis)).outcome, 'not-applied-fenced');
    const changed = { ...d, assignment: { ...d.assignment, snapshotHash: 'a'.repeat(64) } };
    await assert.rejects(probeAssignment(db, uid, changed, 'write', d.assignment.preparedAtMillis), /outcome-unavailable/);
    assert.equal((await db.collectionGroup('snapshots').get()).size, 0);
    await db.doc(`lifecycleCommands/${assignmentBinding(uid, d).id}`).set({ schemaVersion: 1, commandKind: 'profile-update' });
    await assert.rejects(probeAssignment(db, uid, d, 'resolve', d.assignment.preparedAtMillis), /outcome-unavailable/);
  });
  it('writer-first and resolver-first held transactions serialize on one caller/key', async () => {
    const { uid, descriptor: d } = await seed(); const g = gate();
    const writing = probeAssignment(db, uid, d, 'write', d.assignment.preparedAtMillis, undefined, async () => { g.enter(); await g.held; });
    await g.ready; const resolving = probeAssignment(db, uid, d, 'resolve', d.assignment.preparedAtMillis); g.release();
    assert.equal((await writing).status, 'assignment-created'); assert.equal((await resolving).outcome, 'applied');
    const other = { ...d, idempotencyKey: d.idempotencyKey + '-other' }; const h = gate();
    const fencing = probeAssignment(db, uid, other, 'resolve', d.assignment.preparedAtMillis, undefined, async () => { h.enter(); await h.held; });
    await h.ready; const delayed = probeAssignment(db, uid, other, 'write', d.assignment.preparedAtMillis); h.release();
    assert.equal((await fencing).outcome, 'not-applied-fenced'); assert.equal((await delayed).outcome, 'not-applied-fenced');
  });
  it('occupied legacy, dual, corrupt and missing-provenance records all remain immutable and unavailable', async () => {
    const { uid, descriptor: d } = await seed(), b = assignmentBinding(uid, d);
    const old = db.doc(`assignmentCommands/${b.id}`), current = db.doc(`lifecycleCommands/${b.id}`);
    for (const legacy of [{ schemaVersion: 1, commandKind: 'publish-assignment', callerUid: uid, requestHash: b.hash }, { corrupt: true }]) {
      await old.set(legacy); const before = JSON.stringify((await old.get()).data());
      for (const mode of ['write', 'resolve'] as const) await assert.rejects(probeAssignment(db, uid, d, mode, d.assignment.preparedAtMillis), /outcome-unavailable/);
      assert.equal(JSON.stringify((await old.get()).data()), before);
      await current.set({ corrupt: true }); await assert.rejects(probeAssignment(db, uid, d, 'resolve', d.assignment.preparedAtMillis));
      assert.deepEqual((await current.get()).data(), { corrupt: true }); await current.delete();
    }
    await old.delete();
    for (const patch of [{ historyVerified: false }, { provenanceId: 'foreign' }, { assignmentDualNamespaceVerified: false }, { absenceEnabled: false }]) {
      const config = db.doc('commandResolutionConfig/current'), original = (await config.get()).data()!;
      await config.update(patch); await assert.rejects(probeAssignment(db, uid, d, 'resolve', d.assignment.preparedAtMillis));
      assert.equal((await current.get()).exists, false); await config.set(original);
    }
  });
  it('legacy functions currently bypass lifecycle fences; proposed prefix exclusion blocks BOTH writers in either ordering', async () => {
    const { uid, client, wid, descriptor: d } = await seed();
    await probeAssignment(db, uid, d, 'resolve', d.assignment.preparedAtMillis);
    const request = { callerUid: uid, idempotencyKey: d.idempotencyKey, uid: client, workspaceId: wid, trainerId: uid,
      assignmentId: 'legacy-gap', sourceTemplateId: 't', sourceVersionId: 'v1', sourceVersionNumber: 1, sourceContentHash: 's', snapshotHash: 's',
      accessExpiresAtMillis: Date.UTC(2030, 0, 1), workouts: [], plans: [], maxWrites: 20 };
    // Characterize the unpatched implementation honestly, on a disposable project only.
    assert.equal((await publishAssignment(db, request)).replayed, false);
    const closeKey = d.idempotencyKey + '-close';
    await db.doc(`lifecycleCommands/${commandId(uid, closeKey)}`).create({ schemaVersion: 2, commandKind: 'own-command-fence' });
    assert.equal((await closeAssignment(db, { callerUid: uid, idempotencyKey: closeKey, uid: client, workspaceId: wid, assignmentId: 'legacy-gap', status: 'cancelled', expectedRevision: 1 })).revision, 2);
    for (const kind of ['publish', 'close']) for (const beforeFence of [true, false]) {
      const key = d.idempotencyKey + `-${kind}-${beforeFence}`;
      const candidate = { ...d, idempotencyKey: key };
      const fence = () => probeAssignment(db, uid, candidate, 'resolve', d.assignment.preparedAtMillis);
      if (!beforeFence) assert.equal((await fence()).outcome, 'not-applied-fenced');
      const guarded = async () => {
        rejectLegacyProductKey(key); // Exact proposed guard at BOTH legacy entry points, before any references.
        return kind === 'publish' ? publishAssignment(db, { ...request, idempotencyKey: key, assignmentId: 'must-not-exist' }) :
          closeAssignment(db, { callerUid: uid, idempotencyKey: key, uid: client, workspaceId: wid, assignmentId: 'legacy-gap', status: 'cancelled', expectedRevision: 2 });
      };
      await assert.rejects(guarded(), /legacy-product-key-reserved/);
      if (beforeFence) assert.equal((await fence()).outcome, 'not-applied-fenced');
      assert.equal((await db.doc(`assignmentCommands/${commandId(uid, key)}`).get()).exists, false);
    }
    const firstKey = d.idempotencyKey + '-legacy-first';
    await publishAssignment(db, { ...request, idempotencyKey: firstKey, assignmentId: 'legacy-first' });
    await assert.rejects(probeAssignment(db, uid, { ...d, idempotencyKey: firstKey }, 'resolve', d.assignment.preparedAtMillis), /outcome-unavailable/);
    assert.equal((await db.doc(`lifecycleCommands/${commandId(uid, firstKey)}`).get()).exists, false);
    rejectLegacyProductKey('unchanged-historical-key');
  });
  it('current relationship discovery works and denies withdrawal, reassignment and malformed membership', async () => {
    const { uid, client, wid, descriptor: d } = await seed();
    await probeAssignment(db, uid, d, 'write', d.assignment.preparedAtMillis);
    assert.equal((await probeDiscovery(db, uid, client, wid, d.assignment.preparedAtMillis)).length, 1);
    assert.equal((await probeDiscovery(db, uid, client, wid, d.assignment.accessExpiresAtMillis)).length, 0);
    const profile = db.doc(`workspaces/${wid}/clientProfiles/${client}`);
    await profile.update({ trainerId: 'other' }); await assert.rejects(probeDiscovery(db, uid, client, wid, d.assignment.preparedAtMillis));
    await profile.update({ trainerId: uid });
    for (const patch of [{ status: 'revoked' }, { workspaceId: 'foreign' }, { userId: 'foreign' }, { role: 'trainer' }]) {
      const ref = db.doc(`workspaces/${wid}/memberships/${client}`), original = (await ref.get()).data()!;
      await ref.update(patch); await assert.rejects(probeDiscovery(db, uid, client, wid, d.assignment.preparedAtMillis)); await ref.set(original);
    }
  });
  it('only current eligible exact authority records minimal schedule/copy conflicts; denial never settles', async () => {
    const { uid, client, wid, descriptor: d } = await seed();
    assert.equal((await probeAssignment(db, uid, d, 'write', d.assignment.scheduledInstantMillis)).status, 'assignment-schedule-conflict');
    const changed = { ...d, idempotencyKey: d.idempotencyKey + '-copy', assignment: { ...d.assignment, snapshotHash: 'a'.repeat(64) } };
    assert.equal((await probeAssignment(db, uid, changed, 'write', d.assignment.preparedAtMillis)).status, 'assignment-copy-conflict');
    const denied = { ...d, idempotencyKey: d.idempotencyKey + '-denied' };
    await db.doc(`workspaces/${wid}/memberships/${client}`).update({ status: 'revoked' });
    await assert.rejects(probeAssignment(db, uid, denied, 'write', d.assignment.preparedAtMillis), /authority/);
    assert.equal((await db.doc(`lifecycleCommands/${assignmentBinding(uid, denied).id}`).get()).exists, false);
    assert.equal((await probeAssignment(db, uid, denied, 'resolve', d.assignment.preparedAtMillis)).outcome, 'not-applied-fenced');
  });
  it('pins v1 despite a later draft/latest pointer and independently published v2', async () => {
    const { uid, descriptor: d, version } = await seed();
    const content = { contentPolicyVersion: version.contentPolicyVersion, workspaceId: version.workspaceId, templateId: version.templateId,
      ownerUid: uid, title: 'Later version', description: version.description, workouts: version.workouts };
    const v2 = { ...version, ...content, versionId: 'v0000000002', versionNumber: 2, draftRevision: 2,
      contentHash: publicationContentHash(content), draftHash: publicationDraftHash(publicationDraft(content)) };
    const root = `workspaces/${d.workspaceId}/programTemplates/${version.templateId}`;
    await db.doc(root).create({ ownerUid: uid, currentVersionId: v2.versionId, revision: 9, title: 'Unpublished draft' });
    await db.doc(`${root}/versions/${v2.versionId}`).create(v2);
    assert.equal((await probeAssignment(db, uid, d, 'write', d.assignment.preparedAtMillis)).status, 'assignment-created');
    const base = `users/${d.assignment.clientUid}/workspaces/${d.workspaceId}/assignedPrograms/${d.assignment.assignmentId}`;
    assert.equal((await db.doc(`${base}/snapshots/content`).get()).get('title'), version.title);
    assert.equal((await db.doc(base).get()).get('sourceVersionId'), version.versionId);
    assert.deepEqual((await db.doc(`${root}/versions/${version.versionId}`).get()).data(), version);
  });
});
