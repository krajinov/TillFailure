// PR 1a pure contract only. Not imported by index.ts or any product writer/resolver.
import { commandId, stableHash } from './hashing.js';
import { participantUid, strictPathIdentifier } from './input-validation.js';
import { exerciseExact as exact, exerciseId, exerciseRevision, exerciseBytes as bytes } from './custom-exercise-contract.js';
import { validPublicationContent, publicationContentHash, publicationDraft, publicationDraftHash, publicationVersionId } from './program-publication-contract.js';

type Data = Record<string, any>;
export const ASSIGNMENT_KIND = 'program-assignment-create';
export const ASSIGNMENT_HASH_VERSION = 'program-assignment-v1';
export const DAY_MS = 86_400_000;
export const MAX_FIRESTORE_MILLIS = 253_402_300_799_999;
export const ASSIGNMENT_AUTHORITY = ['trainerLifecycleRevision', 'trainerMembershipRevision', 'clientLifecycleRevision',
  'clientMembershipRevision', 'workspaceMembershipRevision', 'clientOnboardingRevision'] as const;
export const ASSIGNMENT_BUDGET = Object.freeze({ requestBytes: 32768, responseBytes: 65536, resolverUnits: 8192,
  resolverBytes: 24576, pendingBytes: 16384, plaintextBytes: 36500, encryptedBytes: 98304,
  sourceBytes: 163840, itemBytes: 24576, headerBytes: 4096, snapshotBytes: 8192, inventoryBytes: 8192,
  businessReads: 16, businessWrites: 20, admissionReads: 1, admissionWrites: 1, maxAttempts: 3,
  deadlineMillis: 5000, requestsPerMinute: 120, commitBytes: 524288,
  indexEntriesPerDocument: 64, indexEntryBytes: 4096, listLimit: 6 });
const fields = ['assignmentId', 'plannedWorkoutId', 'clientUid', 'templateId', 'versionId', 'versionNumber',
  'sourceContentHash', 'copyPolicyVersion', 'snapshotHash', 'authority', 'preparedAtMillis', 'scheduledInstantMillis', 'accessExpiresAtMillis'];
export const assignmentHash = (x: unknown): x is string => typeof x === 'string' && /^[a-f0-9]{64}$/.test(x);
export const millis = (x: unknown): x is number => Number.isSafeInteger(x) && (x as number) >= 0 && (x as number) <= MAX_FIRESTORE_MILLIS;
export function validAssignment(x: unknown): x is Data {
  try {
    if (!exact(x, fields) || !['assignmentId', 'plannedWorkoutId', 'templateId'].every(k => exerciseId(x[k])) ||
      !Number.isInteger(x.versionNumber) || x.versionNumber < 1 || x.versionNumber > 2147483645 || x.versionId !== publicationVersionId(x.versionNumber) ||
      !assignmentHash(x.sourceContentHash) || !assignmentHash(x.snapshotHash) || x.copyPolicyVersion !== 1 ||
      !exact(x.authority, [...ASSIGNMENT_AUTHORITY]) || !ASSIGNMENT_AUTHORITY.every(k => exerciseRevision(x.authority[k])) ||
      x.authority.workspaceMembershipRevision >= 2147483646 ||
      ![x.preparedAtMillis, x.scheduledInstantMillis, x.accessExpiresAtMillis].every(millis)) return false;
    participantUid(x.clientUid, 'client');
    return x.scheduledInstantMillis > x.preparedAtMillis && x.scheduledInstantMillis - x.preparedAtMillis <= DAY_MS &&
      x.scheduledInstantMillis <= MAX_FIRESTORE_MILLIS - DAY_MS && x.accessExpiresAtMillis === x.scheduledInstantMillis + DAY_MS;
  } catch { return false; }
}
export function assignmentDescriptor(raw: unknown): Data {
  if (!exact(raw, ['action', 'workspaceId', 'idempotencyKey', 'assignment']) || raw.action !== 'assign-program-version' ||
    typeof raw.idempotencyKey !== 'string' || !/^or1-b91f0176ec74475e8cf7406e25b7e9d3-[A-Za-z0-9_-]{1,80}$/.test(raw.idempotencyKey) || !validAssignment(raw.assignment)) throw new Error('assignment-invalid');
  participantUid(strictPathIdentifier(raw.workspaceId, 'workspace'), 'workspace');
  const resolver = { protocolVersion: 1, descriptor: raw };
  if (JSON.stringify(resolver).length > 8192 || bytes(resolver) > 24576 || bytes({ data: raw }) > 32768 || bytes({ data: resolver }) > 32768) throw new Error('assignment-oversize');
  return raw;
}
export function assignmentBinding(uid: string, raw: unknown) {
  participantUid(uid, 'caller'); const d = assignmentDescriptor(raw);
  if (d.assignment.clientUid === uid) throw new Error('assignment-self');
  return { id: commandId(uid, d.idempotencyKey), hash: stableHash({ ...d, callerUid: uid, policyVersion: 1 }) };
}
export function executionTime(a: Data, now: number): 'current' | 'assignment-schedule-conflict' {
  if (!validAssignment(a) || !millis(now) || now < a.preparedAtMillis) throw new Error('assignment-clock-unavailable');
  return now < a.scheduledInstantMillis ? 'current' : 'assignment-schedule-conflict';
}
export function assignmentCopy(version: Data, uid: string, wid: string, templateId: string): Data {
  const metadata = ['schemaVersion', 'versionId', 'versionNumber', 'draftRevision', 'draftHash', 'contentHash', 'publishedAtMillis'];
  if (!exact(version, [...metadata, 'contentPolicyVersion', 'workspaceId', 'templateId', 'ownerUid', 'title', 'description', 'workouts']) ||
    version.schemaVersion !== 1 || version.ownerUid !== uid || version.workspaceId !== wid || version.templateId !== templateId ||
    !Number.isInteger(version.versionNumber) || version.versionNumber < 1 || version.versionNumber > 2147483645 ||
    version.versionId !== publicationVersionId(version.versionNumber) || !Number.isInteger(version.draftRevision) ||
    version.draftRevision < version.versionNumber || version.draftRevision > 2147483645 || !millis(version.publishedAtMillis) || bytes(version) > 163840) throw new Error('assignment-source-unavailable');
  const content = Object.fromEntries(Object.entries(version).filter(([k]) => !metadata.includes(k)));
  if (!validPublicationContent(content) || content.workouts.length !== 1 || publicationContentHash(content) !== version.contentHash ||
    publicationDraftHash(publicationDraft(content)) !== version.draftHash) throw new Error('assignment-source-unavailable');
  // Content allowlist only. Source owner/authority and timestamps are not client content.
  return { copyPolicyVersion: 1, title: content.title, description: content.description, workouts: content.workouts };
}
export const assignmentCopyHash = (copy: Data) => stableHash({ hashVersion: 'program-assignment-copy-v1', ...copy });
export const ASSIGNMENT_STATUSES = ['assignment-created', 'assignment-schedule-conflict', 'assignment-source-conflict', 'assignment-copy-conflict', 'assignment-id-conflict'];
export function assignmentDecision(d: Data, status: string): Data {
  if (!validAssignment(d.assignment) || !ASSIGNMENT_STATUSES.includes(status)) throw new Error('assignment-decision-invalid');
  const a = d.assignment, applied = status === 'assignment-created';
  return { status, workspaceId: d.workspaceId, clientUid: a.clientUid, assignmentId: a.assignmentId,
    assignmentRevision: applied ? 1 : 0, plannedWorkoutId: applied ? a.plannedWorkoutId : null, snapshotHash: applied ? a.snapshotHash : null };
}
export function validAssignmentDecision(v: unknown, d: Data): boolean {
  return exact(v, ['status', 'workspaceId', 'clientUid', 'assignmentId', 'assignmentRevision', 'plannedWorkoutId', 'snapshotHash']) &&
    ASSIGNMENT_STATUSES.includes(v.status) && stableHash(v) === stableHash(assignmentDecision(d, v.status));
}
export function materializeAssignment(d: Data, version: Data, uid: string): Map<string, Data> {
  assignmentBinding(uid, d);
  const a = d.assignment, copy = assignmentCopy(version, uid, d.workspaceId, a.templateId);
  if (version.versionId !== a.versionId || version.versionNumber !== a.versionNumber || version.contentHash !== a.sourceContentHash || assignmentCopyHash(copy) !== a.snapshotHash) throw new Error('assignment-preview-conflict');
  const identity = { schemaVersion: 1, workspaceId: d.workspaceId, clientId: a.clientUid, assignmentId: a.assignmentId };
  const contentIdentity = { ...identity, snapshotId: 'content' };
  const provenance = { sourceTemplateId: a.templateId, sourceVersionId: a.versionId, sourceVersionNumber: a.versionNumber, sourceContentHash: a.sourceContentHash };
  const base = `users/${a.clientUid}/workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}`;
  const w = copy.workouts[0], out = new Map<string, Data>();
  out.set(base, { ...contentIdentity, ...provenance, trainerId: uid, snapshotHash: a.snapshotHash, revision: 1,
    lifecycleState: 'ready', accessStatus: 'active', scheduledInstantMillis: a.scheduledInstantMillis, accessExpiresAtMillis: a.accessExpiresAtMillis, timeZoneId: 'UTC' });
  out.set(`${base}/snapshots/content`, { ...contentIdentity, ...provenance, contentHash: a.snapshotHash, copyPolicyVersion: 1, title: copy.title, description: copy.description, workoutIds: [w.id] });
  out.set(`${base}/snapshots/content/workouts/${w.id}`, { ...contentIdentity, workoutId: w.id, title: w.title, itemIds: w.items.map((i: Data) => i.id) });
  for (const i of w.items) out.set(`${base}/snapshots/content/workouts/${w.id}/exercises/${i.id}`, { ...contentIdentity, workoutId: w.id, itemId: i.id,
    sourceKind: i.sourceKind, sourceId: i.sourceId, sourceRevision: i.sourceRevision, sourceHash: i.sourceHash, name: i.name,
    instructions: i.instructions, muscleGroups: i.muscleGroups, equipment: i.equipment, prescription: i.prescription });
  out.set(`${base}/plannedWorkouts/${a.plannedWorkoutId}`, { ...contentIdentity, plannedWorkoutId: a.plannedWorkoutId, workoutId: w.id,
    scheduledInstantMillis: a.scheduledInstantMillis, timeZoneId: 'UTC', status: 'planned', revision: 1 });
  const requiredPaths = [...out.keys()].filter(p => p !== base).map(p => p.slice(base.length + 1)).sort();
  out.set(`${base}/manifests/download`, { ...contentIdentity, assignmentRevision: 1, snapshotHash: a.snapshotHash, requiredPaths });
  out.set(`workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}`, { ...identity, trainerId: uid, status: 'active', revision: 1 });
  for (const [path, value] of out) {
    const ceiling = path.includes('/exercises/') ? 24576 : path.endsWith('/snapshots/content') || path.endsWith('/manifests/download') ? 8192 : 4096;
    if (bytes(value) > ceiling) throw new Error('assignment-document-oversize');
  }
  return out;
}
/** Complete exact copy validation, not a field-redacting DTO mapper. */
export function validateAssignmentDocuments(d: Data, version: Data, uid: string, actual: Map<string, Data>): boolean {
  try {
    const expected = materializeAssignment(d, version, uid);
    return expected.size === actual.size && [...expected].every(([p, v]) => actual.has(p) && stableHash(v) === stableHash(actual.get(p)));
  } catch { return false; }
}
/** Client validation uses only account-owned documents and path context, never the source DTO. */
export function validateClientAssignmentDocuments(client: string, wid: string, aid: string, actual: Map<string, Data>): boolean {
  try {
    participantUid(client, 'client'); participantUid(strictPathIdentifier(wid, 'workspace'), 'workspace');
    if (!exerciseId(aid) || actual.size < 6 || actual.size > 17) return false;
    const base = `users/${client}/workspaces/${wid}/assignedPrograms/${aid}`, header = actual.get(base)!;
    if (!exact(header, ['schemaVersion', 'workspaceId', 'clientId', 'assignmentId', 'snapshotId', 'sourceTemplateId', 'sourceVersionId', 'sourceVersionNumber',
      'sourceContentHash', 'trainerId', 'snapshotHash', 'revision', 'lifecycleState', 'accessStatus', 'scheduledInstantMillis', 'accessExpiresAtMillis', 'timeZoneId']) ||
      header.schemaVersion !== 1 || header.workspaceId !== wid || header.clientId !== client || header.assignmentId !== aid || header.snapshotId !== 'content' ||
      header.revision !== 1 || header.lifecycleState !== 'ready' || header.accessStatus !== 'active' || header.timeZoneId !== 'UTC' ||
      !millis(header.scheduledInstantMillis) || !millis(header.accessExpiresAtMillis) || header.accessExpiresAtMillis - header.scheduledInstantMillis !== DAY_MS) return false;
    const snapshot = actual.get(`${base}/snapshots/content`)!;
    if (!exact(snapshot, ['schemaVersion', 'workspaceId', 'clientId', 'assignmentId', 'snapshotId', 'sourceTemplateId', 'sourceVersionId', 'sourceVersionNumber', 'sourceContentHash',
      'contentHash', 'copyPolicyVersion', 'title', 'description', 'workoutIds']) || !Array.isArray(snapshot.workoutIds) || snapshot.workoutIds.length !== 1) return false;
    const workoutId = snapshot.workoutIds[0]; if (!exerciseId(workoutId)) return false;
    const w = actual.get(`${base}/snapshots/content/workouts/${workoutId}`)!;
    if (!exact(w, ['schemaVersion', 'workspaceId', 'clientId', 'assignmentId', 'snapshotId', 'workoutId', 'title', 'itemIds']) ||
      !Array.isArray(w.itemIds) || w.itemIds.length < 1 || w.itemIds.length > 12 || new Set(w.itemIds).size !== w.itemIds.length) return false;
    const tuple = (v: Data) => v.schemaVersion === 1 && v.workspaceId === wid && v.clientId === client && v.assignmentId === aid && v.snapshotId === 'content';
    if (!tuple(snapshot) || !tuple(w) || w.workoutId !== workoutId || snapshot.copyPolicyVersion !== 1 ||
      ['sourceTemplateId', 'sourceVersionId', 'sourceVersionNumber', 'sourceContentHash'].some(k => snapshot[k] !== header[k])) return false;
    const items = w.itemIds.map((itemId: string) => {
      if (!exerciseId(itemId)) throw new Error('item-id');
      const i = actual.get(`${base}/snapshots/content/workouts/${workoutId}/exercises/${itemId}`)!;
      if (!exact(i, ['schemaVersion', 'workspaceId', 'clientId', 'assignmentId', 'snapshotId', 'workoutId', 'itemId', 'sourceKind', 'sourceId', 'sourceRevision', 'sourceHash',
        'name', 'instructions', 'muscleGroups', 'equipment', 'prescription']) || !tuple(i) || i.workoutId !== workoutId || i.itemId !== itemId) throw new Error('item');
      return { id: itemId, sourceKind: i.sourceKind, sourceId: i.sourceId, sourceRevision: i.sourceRevision, sourceHash: i.sourceHash,
        name: i.name, instructions: i.instructions, muscleGroups: i.muscleGroups, equipment: i.equipment, prescription: i.prescription };
    });
    const copy = { copyPolicyVersion: 1, title: snapshot.title, description: snapshot.description, workouts: [{ id: workoutId, title: w.title, items }] };
    const content = { contentPolicyVersion: 1, workspaceId: wid, templateId: header.sourceTemplateId, ownerUid: header.trainerId,
      title: copy.title, description: copy.description, workouts: copy.workouts };
    if (!validPublicationContent(content) || assignmentCopyHash(copy) !== header.snapshotHash || snapshot.contentHash !== header.snapshotHash ||
      publicationContentHash(content) !== header.sourceContentHash || header.sourceVersionId !== publicationVersionId(header.sourceVersionNumber) ||
      !Number.isInteger(header.sourceVersionNumber) || header.sourceVersionNumber < 1 || header.sourceVersionNumber > 2147483645) return false;
    const prefix = `${base}/plannedWorkouts/`, plans = [...actual].filter(([p]) => p.startsWith(prefix));
    if (plans.length !== 1) return false;
    const [planPath, plan] = plans[0]!;
    if (!exact(plan, ['schemaVersion', 'workspaceId', 'clientId', 'assignmentId', 'snapshotId', 'plannedWorkoutId', 'workoutId', 'scheduledInstantMillis', 'timeZoneId', 'status', 'revision']) ||
      !tuple(plan) || !exerciseId(plan.plannedWorkoutId) || planPath !== prefix + plan.plannedWorkoutId || plan.workoutId !== workoutId ||
      plan.scheduledInstantMillis !== header.scheduledInstantMillis || plan.timeZoneId !== 'UTC' || plan.status !== 'planned' || plan.revision !== 1) return false;
    const manifestPath = `${base}/manifests/download`, manifest = actual.get(manifestPath)!;
    if (!exact(manifest, ['schemaVersion', 'workspaceId', 'clientId', 'assignmentId', 'snapshotId', 'assignmentRevision', 'snapshotHash', 'requiredPaths']) ||
      !tuple(manifest) || manifest.assignmentRevision !== 1 || manifest.snapshotHash !== header.snapshotHash || !Array.isArray(manifest.requiredPaths)) return false;
    const required = ['snapshots/content', `snapshots/content/workouts/${workoutId}`, ...w.itemIds.map((id: string) => `snapshots/content/workouts/${workoutId}/exercises/${id}`), `plannedWorkouts/${plan.plannedWorkoutId}`].sort();
    return JSON.stringify(manifest.requiredPaths) === JSON.stringify(required) && actual.size === required.length + 2 &&
      [...actual.keys()].every(p => p === base || p === manifestPath || required.includes(p.slice(base.length + 1)) && p.startsWith(base + '/'));
  } catch { return false; }
}
