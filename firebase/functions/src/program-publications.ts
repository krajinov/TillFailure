import { Firestore, Timestamp, Transaction } from 'firebase-admin/firestore';
import { verifiedEmulatorIdentity } from './invitations.js';
import { requireProvisioningEmulators } from './provisioning.js';
import { participantUid, strictPathIdentifier } from './input-validation.js';
import { requireCommandGeneration } from './command-identity.js';
import { commandId, stableHash } from './hashing.js';
import { exerciseExact as exact, exerciseId, exerciseBytes as bytes, EXERCISE_AUTHORITY } from './custom-exercise-contract.js';
import { draftDefinition, sourceIdentity, programAdmission, programAuthority } from './program-drafts.js';
import { draftContent, publicationDraft, publicationDraftHash, publicationContentHash, publicationComplete,
  publicationDescriptor, publicationDecisionValid, publicationVersionId, validVersionId, validPublicationContent,
  PUBLICATION_DOCUMENT_BYTES, PUBLICATION_RESPONSE_BYTES } from './program-publication-contract.js';

type Data = Record<string, any>;
const fail = (reason = 'publication-unavailable'): never => { throw new Error(reason); };
const stamp = (v: unknown): v is Timestamp => v instanceof Timestamp && Number.isSafeInteger(v.toMillis()) && v.toMillis() >= 0 && v.toMillis() <= 8640000000000000;

/** Every item is checked, including copies that Save is permitted to retain. */
async function copiedContent(tx: Transaction, db: Firestore, uid: string, wid: string, draft: Data): Promise<Data | null> {
  const items: Data[] = draft.workouts.flatMap((w: Data) => w.items);
  if (items.some(i => i.sourceKind === 'system')) {
    const e = await tx.get(db.doc(`users/${uid}/authorizations/systemCatalog`));
    if (bytes(e.data() ?? {}) > 4096 || e.get('schemaVersion') !== 1 || e.get('status') !== 'active' ||
      !Number.isInteger(e.get('activeMembershipCount')) || e.get('activeMembershipCount') < 1 || e.get('activeMembershipCount') > 20) return null;
  }
  const path = (i: Data) => i.sourceKind === 'system' ? `systemExercises/${i.sourceId}` : `workspaces/${wid}/exercises/${i.sourceId}`;
  const paths = [...new Set(items.map(path))];
  const snapshots = paths.length ? await tx.getAll(...paths.map(p => db.doc(p))) : [];
  const sources = new Map(snapshots.map(s => [s.ref.path, s.data()]));
  for (const i of items) {
    const current = sourceIdentity(i.sourceKind, i.sourceId, wid, uid, sources.get(path(i)));
    const selected = { sourceKind: i.sourceKind, sourceId: i.sourceId, sourceRevision: i.sourceRevision, sourceHash: i.sourceHash, name: i.name };
    if (!current || stableHash(current) !== stableHash(selected)) return null;
  }
  return { ...draftContent(draft), workouts: draft.workouts.map((w: Data) => ({ ...w, items: w.items.map((i: Data) => {
    const s = sources.get(path(i))!;
    return { ...i, instructions: s.instructions, muscleGroups: s.muscleGroups, equipment: s.equipment };
  }) })) };
}

export function publicationVersion(v: Data | undefined, uid: string, wid: string, tid: string, vid: string, root: Data): Data {
  if (!exact(v, ['schemaVersion','contentPolicyVersion','workspaceId','templateId','ownerUid','title','description','workouts',
    'versionId','versionNumber','draftRevision','draftHash','contentHash','publishedAt']) || v.schemaVersion !== 1 ||
    v.workspaceId !== wid || v.ownerUid !== uid || v.templateId !== tid || v.versionId !== vid ||
    !Number.isInteger(v.versionNumber) || v.versionNumber < 1 || v.versionNumber > root.versionCount || vid !== publicationVersionId(v.versionNumber) ||
    !Number.isInteger(v.draftRevision) || v.draftRevision < v.versionNumber || v.draftRevision >= root.revision || !stamp(v.publishedAt) || v.publishedAt.toMillis() > root.updatedAtMillis) fail();
  const c = draftContent(v!);
  if (!validPublicationContent(c) || publicationContentHash(c) !== v!.contentHash || publicationDraftHash(publicationDraft(c)) !== v!.draftHash) fail();
  const dto: Data = { ...v!, publishedAtMillis: v!.publishedAt.toMillis() }; delete dto.publishedAt;
  if (bytes(dto) > PUBLICATION_DOCUMENT_BYTES) fail();
  return dto;
}

/** No live authority or content is consulted by historical own-resolution; this writer checks both. */
export async function programPublicationOperation(db: Firestore, caller: string | undefined, raw: unknown,
  clock: () => number = Date.now, beforeWrites?: () => Promise<void>) {
  requireProvisioningEmulators(); if (!caller) fail('unauthorized');
  const uid = participantUid(caller, 'uid'), user = await verifiedEmulatorIdentity(uid), d = raw as Data;
  const mutation = d?.action === 'publish-program-template';
  const fields = mutation ? ['action','workspaceId','idempotencyKey','publish'] : d?.action === 'publication-prepare' ? ['action','workspaceId','templateId','expectedRevision'] :
    d?.action === 'version-get' ? ['action','workspaceId','templateId','versionId'] : d?.action === 'version-list' ? ['action','workspaceId','templateId','beforeId'] : [];
  if (!fields.length || !exact(d, fields) || bytes({ data: d }) > 32768) fail();
  const wid = participantUid(strictPathIdentifier(d.workspaceId, 'workspace'), 'workspace');
  if (mutation) publicationDescriptor(wid, d.idempotencyKey, d.publish);
  else if (!exerciseId(d.templateId) || (d.action === 'publication-prepare' && (!Number.isInteger(d.expectedRevision) || d.expectedRevision < 1 || d.expectedRevision > 2147483645)) ||
    (d.action === 'version-get' && !validVersionId(d.versionId)) || (d.action === 'version-list' && d.beforeId !== null && !validVersionId(d.beforeId))) fail();
  await programAdmission(db, uid, clock);
  const started = clock();
  return db.runTransaction(async tx => {
    const deadline = () => { if (clock() - started >= 5000) fail(); }; deadline();
    if (mutation) await requireCommandGeneration(tx, db, d.idempotencyKey);
    const authority = await programAuthority(tx, db, uid, wid, user.email!.toLowerCase());
    const tid = mutation ? d.publish.templateId : d.templateId, target = db.doc(`workspaces/${wid}/programTemplates/${tid}`);
    let result: Data;
    if (mutation) {
      const p = d.publish;
      if (!EXERCISE_AUTHORITY.every(k => authority[k] === p.authority[k])) fail('unauthorized');
      const receipt = db.doc(`lifecycleCommands/${commandId(uid, d.idempotencyKey)}`);
      const [old, r] = await tx.getAll(target, receipt), hash = stableHash({ ...d, callerUid: uid, policyVersion: 1 });
      if (r!.exists) {
        const v = r!.data()!;
        if (bytes(v) > 4096 || !exact(v, ['schemaVersion','commandKind','callerUid','workspaceId','templateId','requestHash','result','committedAt']) ||
          v.schemaVersion !== 1 || v.commandKind !== 'program-template-publish' || v.callerUid !== uid || v.workspaceId !== wid || v.templateId !== tid ||
          v.requestHash !== hash || !stamp(v.committedAt) || !publicationDecisionValid(v.result, wid, p)) fail('idempotency-key-reused');
        deadline(); return { ...v.result, replayed: true };
      }
      const root = draftDefinition(old!.data(), wid, tid, uid);
      if (root.revision < p.expectedRevision) fail();
      let status = root.revision !== p.expectedRevision ? 'publication-conflict' : publicationDraftHash(root) !== p.draftHash ? 'publication-draft-conflict' :
        !publicationComplete(root) ? 'publication-incomplete' : 'program-published';
      let content: Data | null = null;
      if (status === 'program-published') { content = await copiedContent(tx, db, uid, wid, root); if (!content) status = 'publication-source-conflict'; }
      const applied = status === 'program-published', n = (root.versionCount ?? 0) + 1, now = Timestamp.now();
      if (applied && (root.revision >= 2147483646 || n > 2147483645 || now.toMillis() < root.updatedAtMillis || !validPublicationContent(content))) fail();
      const decision = { status, workspaceId: wid, templateId: tid, templateRevision: root.revision + (applied ? 1 : 0),
        versionId: applied ? publicationVersionId(n) : null, versionNumber: applied ? n : null, contentHash: applied ? publicationContentHash(content!) : null };
      if (applied) {
        const next = { ...old!.data(), schemaVersion: 2, revision: decision.templateRevision, versionCount: n, currentVersionId: decision.versionId, updatedAt: now };
        draftDefinition(next, wid, tid, uid);
        const version = { schemaVersion: 1, ...content!, versionId: decision.versionId, versionNumber: n,
          draftRevision: root.revision, draftHash: p.draftHash, contentHash: decision.contentHash, publishedAt: now };
        publicationVersion(version, uid, wid, tid, decision.versionId!, next);
        if (beforeWrites) await beforeWrites(); deadline();
        tx.create(target.collection('versions').doc(decision.versionId!), version); tx.set(target, next);
      } else { if (beforeWrites) await beforeWrites(); deadline(); }
      tx.create(receipt, { schemaVersion: 1, commandKind: 'program-template-publish', callerUid: uid, workspaceId: wid, templateId: tid, requestHash: hash, result: decision, committedAt: now });
      result = { ...decision, replayed: false };
    } else {
      const root = draftDefinition((await tx.get(target)).data(), wid, tid, uid);
      if (d.action === 'publication-prepare') {
        if (root.revision !== d.expectedRevision) fail('publication-conflict');
        if (!publicationComplete(root)) fail('publication-incomplete');
        const content = await copiedContent(tx, db, uid, wid, root);
        if (!content) fail('publication-source-conflict');
        if (!validPublicationContent(content)) fail();
        result = { workspaceId: wid, publish: { templateId: tid, expectedRevision: root.revision, authority, draftHash: publicationDraftHash(root), contentPolicyVersion: 1 },
          nextVersionNumber: (root.versionCount ?? 0) + 1, preview: content, contentHash: publicationContentHash(content!) };
      } else if (d.action === 'version-get') {
        if (root.schemaVersion !== 2) fail();
        result = { workspaceId: wid, version: publicationVersion((await tx.get(target.collection('versions').doc(d.versionId))).data(), uid, wid, tid, d.versionId, root) };
      } else {
        // The pinned emulator cannot scan document keys descending. Versions are consecutive,
        // so six deterministic point reads preserve newest-first paging and the approved bound.
        const highest = Math.min(root.versionCount ?? 0, d.beforeId === null ? 2147483645 : Number(d.beforeId.slice(1)) - 1);
        const ids = Array.from({ length: Math.min(6, highest) }, (_, i) => publicationVersionId(highest - i));
        const snapshots = ids.length ? await tx.getAll(...ids.map(id => target.collection('versions').doc(id))) : [];
        const values = snapshots.map(s => publicationVersion(s.data(), uid, wid, tid, s.id, root));
        if (root.schemaVersion !== 2 && values.length) fail();
        result = { workspaceId: wid, templateId: tid, items: values.slice(0,5).map(v => ({ versionId: v.versionId, versionNumber: v.versionNumber,
          title: v.title, draftRevision: v.draftRevision, publishedAtMillis: v.publishedAtMillis, contentHash: v.contentHash })), nextId: values.length > 5 ? values[4]!.versionId : null };
      }
    }
    deadline();
    const limit = ['publication-prepare','version-get'].includes(d.action) ? PUBLICATION_RESPONSE_BYTES : d.action === 'version-list' ? 16384 : 4096;
    if (bytes({ result }) > limit) fail(); return result;
  }, { maxAttempts: 3 });
}
