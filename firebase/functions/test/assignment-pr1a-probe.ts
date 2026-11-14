// Isolated transaction experiments. Never imported by a callable or production writer.
import { Firestore, Timestamp } from 'firebase-admin/firestore';
import { assignmentBinding, assignmentDecision, assignmentCopy, assignmentCopyHash, validAssignmentDecision, executionTime,
  materializeAssignment, ASSIGNMENT_AUTHORITY } from '../src/assignment-feasibility-contract.js';
import { commandId, stableHash } from '../src/hashing.js';
import { exerciseExact as exact } from '../src/custom-exercise-contract.js';
type Data = Record<string, any>;
export const probeGeneration = 'b91f0176ec74475e8cf7406e25b7e9d3';
export const probeProvenance = 'm7-pr1a-disposable-only';
const fail = (reason = 'outcome-unavailable'): never => { throw new Error(reason); };
export function rejectLegacyProductKey(key: string) {
  if (key.startsWith('or1-')) fail('legacy-product-key-reserved');
}
export async function seedProbeAuthority(db: Firestore, uid: string, client: string, wid: string, authority: Data) {
  const docs: Record<string, Data> = {
    [`users/${uid}`]: { schemaVersion: 1, accountStatus: 'active', lifecycleRevision: authority.trainerLifecycleRevision, emailNormalized: 'trainer@example.invalid' },
    [`users/${client}`]: { schemaVersion: 1, accountStatus: 'active', lifecycleRevision: authority.clientLifecycleRevision, emailNormalized: 'client@example.invalid' },
    [`workspaces/${wid}`]: { schemaVersion: 1, status: 'active', membershipRevision: authority.workspaceMembershipRevision },
    [`workspaces/${wid}/trainerProfiles/${uid}`]: { schemaVersion: 1, workspaceId: wid, userId: uid },
    [`workspaces/${wid}/clientProfiles/${client}`]: { schemaVersion: 1, workspaceId: wid, userId: client, trainerId: uid, onboardingStatus: 'complete', onboardingPolicyVersion: 1, onboardingRevision: authority.clientOnboardingRevision },
    [`users/${uid}/membershipRefs/${wid}`]: { schemaVersion: 1, status: 'active' },
    [`users/${client}/membershipRefs/${wid}`]: { schemaVersion: 1, status: 'active' },
    'commandResolutionConfig/current': { schemaVersion: 1, generation: probeGeneration, provenanceId: probeProvenance, historyVerified: true,
      executionEnabled: true, absenceEnabled: true, assignmentDualNamespaceVerified: true, maxFencesPerCaller: 100 }
  };
  for (const [id, role, revision] of [[uid, 'trainer', authority.trainerMembershipRevision], [client, 'client', authority.clientMembershipRevision]])
    docs[`workspaces/${wid}/memberships/${id}`] = { schemaVersion: 1, workspaceId: wid, userId: id, role, status: 'active', revision, catalogContributionActive: true };
  const batch = db.batch(); for (const [p, d] of Object.entries(docs)) batch.create(db.doc(p), d); await batch.commit();
}
export async function probeAuthority(tx: FirebaseFirestore.Transaction, db: Firestore, uid: string, client: string, wid: string): Promise<Data> {
  const paths = [`users/${uid}`, `workspaces/${wid}`, `workspaces/${wid}/memberships/${uid}`, `workspaces/${wid}/trainerProfiles/${uid}`, `users/${uid}/membershipRefs/${wid}`,
    `users/${client}`, `workspaces/${wid}/memberships/${client}`, `workspaces/${wid}/clientProfiles/${client}`, `users/${client}/membershipRefs/${wid}`];
  const [a, w, m, t, dir, c, cm, cp, cd] = (await tx.getAll(...paths.map(p => db.doc(p)))).map(x => x.data());
  if ([a, w, m, t, dir, c, cm, cp, cd].some(x => Buffer.byteLength(JSON.stringify(x ?? null)) > 16384)) fail('assignment-authority-oversize');
  if ([a, w, m, t, dir, c, cm, cp, cd].some(x => x?.schemaVersion !== 1) || uid === client ||
    a?.accountStatus !== 'active' || c?.accountStatus !== 'active' || w?.status !== 'active' ||
    [dir, cd].some(x => x?.status !== 'active') || t?.workspaceId !== wid || t?.userId !== uid || cp?.workspaceId !== wid || cp?.userId !== client || cp?.trainerId !== uid ||
    cp?.onboardingStatus !== 'complete' || cp?.onboardingPolicyVersion !== 1 ||
    [[m, uid, 'trainer'], [cm, client, 'client']].some(([x, id, role]: any[]) => x?.workspaceId !== wid || x?.userId !== id || x?.role !== role || x?.status !== 'active' || x?.catalogContributionActive !== true)) fail('assignment-authority-unavailable');
  const authority = { trainerLifecycleRevision: a!.lifecycleRevision, trainerMembershipRevision: m!.revision, clientLifecycleRevision: c!.lifecycleRevision,
    clientMembershipRevision: cm!.revision, workspaceMembershipRevision: w!.membershipRevision, clientOnboardingRevision: cp!.onboardingRevision };
  if (!Object.values(authority).every(x => Number.isSafeInteger(x) && x > 0 && x <= 2147483646)) fail('assignment-authority-unavailable');
  return authority;
}
export function firestoreDocuments(docs: Map<string, Data>): Map<string, Data> {
  return new Map([...docs].map(([path, value]) => {
    const data = { ...value };
    for (const k of ['scheduledInstant', 'accessExpiresAt']) if (`${k}Millis` in data) { data[k] = Timestamp.fromMillis(data[`${k}Millis`]); delete data[`${k}Millis`]; }
    return [path, data];
  }));
}
export async function probeAssignment(db: Firestore, uid: string, d: Data, mode: 'write' | 'resolve', now: number,
  provenance: string | undefined = probeProvenance, barrier?: () => Promise<void>) {
  const b = assignmentBinding(uid, d), a = d.assignment, current = db.doc(`lifecycleCommands/${b.id}`), old = db.doc(`assignmentCommands/${b.id}`);
  const started = Date.now();
  return db.runTransaction(async tx => {
    if (Date.now() - started >= 5000) fail('deadline');
    const [account, receipt, legacy, config, budget] = await tx.getAll(db.doc(`users/${uid}`), current, old,
      db.doc('commandResolutionConfig/current'), ...(mode === 'resolve' ? [db.doc(`assignmentProbeFenceBudget/${stableHash(uid)}`)] : [])) as [FirebaseFirestore.DocumentSnapshot, FirebaseFirestore.DocumentSnapshot, FirebaseFirestore.DocumentSnapshot, FirebaseFirestore.DocumentSnapshot, FirebaseFirestore.DocumentSnapshot?];
    if (Buffer.byteLength(JSON.stringify(account.data() ?? null)) > 16384 || [receipt, legacy, config, ...(budget ? [budget] : [])].some(x => Buffer.byteLength(JSON.stringify(x.data() ?? null)) > 4096)) fail();
    if (account.get('schemaVersion') !== 1 || account.get('accountStatus') !== 'active') fail('global-identity-unavailable');
    if (config.get('generation') !== probeGeneration || config.get('schemaVersion') !== 1 || !provenance || config.get('provenanceId') !== provenance ||
      config.get('historyVerified') !== true || config.get('executionEnabled') !== true || config.get('assignmentDualNamespaceVerified') !== true) fail();
    if (legacy.exists) fail(); // No legacy hash/receipt migration or projection.
    const authority = mode === 'write' ? await probeAuthority(tx, db, uid, a.clientUid, d.workspaceId) : undefined;
    if (receipt.exists) {
      const r = receipt.data()!;
      if (r.callerUid !== uid || r.requestHash !== b.hash) fail();
      if (exact(r, ['schemaVersion','commandKind','callerUid','originalCommandKind','protocolVersion','requestHashVersion','requestHash','outcome','decidedAt']) && r.protocolVersion === 1 && r.schemaVersion === 2 && r.commandKind === 'own-command-fence' && r.originalCommandKind === 'program-assignment-create' && r.requestHashVersion === 'program-assignment-v1' && r.outcome === 'not-applied-fenced' && r.decidedAt instanceof Timestamp)
        return { outcome: 'not-applied-fenced' };
      if (!exact(r, ['schemaVersion','commandKind','callerUid','workspaceId','requestHash','result','committedAt']) || r.workspaceId !== d.workspaceId || r.schemaVersion !== 1 || r.commandKind !== 'program-assignment-create' || !(r.committedAt instanceof Timestamp) || !validAssignmentDecision(r.result, d)) fail();
      return mode === 'resolve' ? { outcome: r.result.status === 'assignment-created' ? 'applied' : 'not-applied-conflict' } : { ...r.result, replayed: true };
    }
    if (mode === 'resolve') {
      if (config.get('absenceEnabled') !== true || budget!.exists && (!Number.isInteger(budget!.get('count')) || budget!.get('count') < 0 || budget!.get('count') >= 100)) fail();
      if (barrier) await barrier();
      tx.create(current, { schemaVersion: 2, commandKind: 'own-command-fence', callerUid: uid, originalCommandKind: 'program-assignment-create', protocolVersion: 1,
        requestHashVersion: 'program-assignment-v1', requestHash: b.hash, outcome: 'not-applied-fenced', decidedAt: Timestamp.fromMillis(now) });
      tx.set(budget!.ref, { count: budget!.exists ? budget!.get('count') + 1 : 1 });
      return { outcome: 'not-applied-fenced' };
    }
    if (authority && ASSIGNMENT_AUTHORITY.some(k => authority[k] !== a.authority[k])) fail('assignment-authority-unavailable');
    const versionPath = `workspaces/${d.workspaceId}/programTemplates/${a.templateId}/versions/${a.versionId}`;
    const headerPath = `users/${a.clientUid}/workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}`;
    const indexPath = `workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}`;
    const [version, header, index] = await tx.getAll(db.doc(versionPath), db.doc(headerPath), db.doc(indexPath)) as [FirebaseFirestore.DocumentSnapshot, FirebaseFirestore.DocumentSnapshot, FirebaseFirestore.DocumentSnapshot];
    if ([header, index].some(x => Buffer.byteLength(JSON.stringify(x.data() ?? null)) > 4096)) fail('assignment-integrity-unavailable');
    const v = version.data() ?? fail('assignment-source-unavailable');
    const copied = assignmentCopy(v, uid, d.workspaceId, a.templateId); // Missing/malformed/foreign is nonterminal.
    let status: string = executionTime(a, now);
    if (status === 'current') status = v.contentHash !== a.sourceContentHash || v.versionNumber !== a.versionNumber ? 'assignment-source-conflict'
      : assignmentCopyHash(copied) !== a.snapshotHash ? 'assignment-copy-conflict' : header.exists || index.exists ? 'assignment-id-conflict' : 'assignment-created';
    if (header.exists && (header.get('clientId') !== a.clientUid || header.get('workspaceId') !== d.workspaceId || header.get('trainerId') !== uid) ||
      index.exists && (index.get('clientId') !== a.clientUid || index.get('workspaceId') !== d.workspaceId || index.get('trainerId') !== uid)) fail('assignment-integrity-unavailable');
    if (barrier) await barrier();
    const result = assignmentDecision(d, status);
    if (status === 'assignment-created') {
      for (const [p, value] of firestoreDocuments(materializeAssignment(d, v, uid))) tx.create(db.doc(p), value);
      tx.update(db.doc(`workspaces/${d.workspaceId}`), { membershipRevision: a.authority.workspaceMembershipRevision + 1 });
    }
    tx.create(current, { schemaVersion: 1, commandKind: 'program-assignment-create', callerUid: uid, workspaceId: d.workspaceId, requestHash: b.hash, result, committedAt: Timestamp.fromMillis(now) });
    return { ...result, replayed: false };
  }, { maxAttempts: 3 });
}
export async function probeDiscovery(db: Firestore, uid: string, client: string, wid: string, now: number) {
  return db.runTransaction(async tx => {
    await probeAuthority(tx, db, uid, client, wid);
    const q = db.collection(`users/${client}/workspaces/${wid}/assignedPrograms`).where('trainerId', '==', uid)
      .where('accessStatus', '==', 'active').where('accessExpiresAt', '>', Timestamp.fromMillis(now))
      .orderBy('accessExpiresAt').orderBy('__name__').limit(6);
    const rows = await tx.get(q);
    for (const row of rows.docs) if (row.get('schemaVersion') !== 1 || row.get('workspaceId') !== wid || row.get('clientId') !== client || row.get('assignmentId') !== row.id || row.get('lifecycleState') !== 'ready') fail('assignment-discovery-invalid');
    return rows.docs.slice(0, 5).map(x => ({ assignmentId: x.id, revision: x.get('revision') }));
  }, { maxAttempts: 3 });
}
