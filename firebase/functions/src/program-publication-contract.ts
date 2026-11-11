import { exerciseExact as exact, exerciseId, exerciseRevision, exerciseText, exerciseLabels,
  EXERCISE_AUTHORITY, EXERCISE_MUSCLES, EXERCISE_EQUIPMENT, exerciseBytes as bytes } from './custom-exercise-contract.js';
import { validDraftSave } from './program-draft-contract.js';
import { participantUid, strictPathIdentifier } from './input-validation.js';
import { stableHash } from './hashing.js';

type Data = Record<string, any>;
export const PUBLICATION_RESPONSE_BYTES = 192 * 1024;
export const PUBLICATION_DOCUMENT_BYTES = 160 * 1024;
export const publicationHash = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
export const publicationVersionId = (n: number) => `v${String(n).padStart(10, '0')}`;
export function validVersionId(v: unknown): v is string {
  return typeof v === 'string' && /^v[0-9]{10}$/.test(v) && Number(v.slice(1)) >= 1 && Number(v.slice(1)) <= 2147483645;
}
export function validPublish(p: unknown): p is Data {
  return exact(p, ['templateId', 'expectedRevision', 'authority', 'draftHash', 'contentPolicyVersion']) &&
    exerciseId(p.templateId) && exerciseRevision(p.expectedRevision) && p.expectedRevision < 2147483646 &&
    exact(p.authority, EXERCISE_AUTHORITY) && EXERCISE_AUTHORITY.every(k => exerciseRevision(p.authority[k])) &&
    publicationHash(p.draftHash) && p.contentPolicyVersion === 1;
}
export function publicationDescriptor(workspaceId: string, idempotencyKey: string, publish: unknown) {
  participantUid(strictPathIdentifier(workspaceId, 'workspace'), 'workspace');
  if (!/^or1-b91f0176ec74475e8cf7406e25b7e9d3-[A-Za-z0-9_-]{1,80}$/.test(idempotencyKey) || !validPublish(publish)) throw new Error('publication-invalid');
  const descriptor = { action: 'publish-program-template', workspaceId, idempotencyKey, publish };
  const resolver = { protocolVersion: 1, descriptor };
  if (JSON.stringify(resolver).length > 8192 || bytes(resolver) > 24576 || bytes({ data: descriptor }) > 32768 || bytes({ data: resolver }) > 32768) throw new Error('publication-invalid');
  return descriptor;
}
export function draftContent(d: Data): Data {
  return { contentPolicyVersion: 1, workspaceId: d.workspaceId, templateId: d.templateId, ownerUid: d.ownerUid,
    title: d.title, description: d.description, workouts: d.workouts };
}
export const publicationDraftHash = (d: Data) => stableHash({ hashVersion: 'program-draft-content-v1', ...draftContent(d) });
export const publicationContentHash = (c: Data) => stableHash({ hashVersion: 'program-version-content-v1', ...c });
export function publicationComplete(d: Data): boolean {
  return d.title !== '' && d.workouts.length >= 1 && d.workouts.every((w: Data) => w.title !== '' && w.items.length >= 1 &&
    w.items.every((i: Data) => i.prescription.sets !== null && i.prescription.reps !== null && i.prescription.restSeconds !== null));
}
export function publicationDraft(c: Data): Data {
  return { ...c, workouts: c.workouts.map((w: Data) => ({ ...w, items: w.items.map((i: Data) => {
    const { instructions, muscleGroups, equipment, ...item } = i;
    return item;
  }) })) };
}
export function validPublicationContent(c: unknown): c is Data {
  if (!exact(c, ['contentPolicyVersion', 'workspaceId', 'templateId', 'ownerUid', 'title', 'description', 'workouts']) || c.contentPolicyVersion !== 1) return false;
  try {
    participantUid(strictPathIdentifier(c.workspaceId, 'workspace'), 'workspace'); participantUid(c.ownerUid, 'owner');
    if (!Array.isArray(c.workouts) || c.workouts.length > 3) return false;
    for (const w of c.workouts) {
      if (!exact(w, ['id', 'title', 'items']) || !Array.isArray(w.items) || w.items.length > 12) return false;
      for (const i of w.items) {
        if (!exact(i, ['id', 'sourceKind', 'sourceId', 'sourceRevision', 'sourceHash', 'name', 'prescription', 'instructions', 'muscleGroups', 'equipment']) || !exerciseText(i.instructions, 2000, true)) return false;
        if (i.sourceKind === 'system') {
          if (!['muscleGroups', 'equipment'].every(k => Array.isArray(i[k]) && i[k].length <= 8 && new Set(i[k]).size === i[k].length && i[k].every((s: unknown) => exerciseText(s, 40)))) return false;
        } else if (!exerciseLabels(i.muscleGroups, EXERCISE_MUSCLES) || !exerciseLabels(i.equipment, EXERCISE_EQUIPMENT) || i.equipment.includes('bodyweight') && i.equipment.length !== 1) return false;
      }
    }
    const d = publicationDraft(c);
    return validDraftSave({ templateId: d.templateId, expectedRevision: 0, authority: { authorLifecycleRevision: 1, authorMembershipRevision: 1, workspaceMembershipRevision: 1 },
      title: d.title, description: d.description, workouts: d.workouts }) && publicationComplete(d) && bytes(c) <= PUBLICATION_DOCUMENT_BYTES;
  } catch { return false; }
}
export function publicationDecisionValid(v: unknown, wid: string, p: Data): v is Data {
  if (!exact(v, ['status', 'workspaceId', 'templateId', 'templateRevision', 'versionId', 'versionNumber', 'contentHash']) ||
    v.workspaceId !== wid || v.templateId !== p.templateId || !exerciseRevision(v.templateRevision)) return false;
  if (v.status === 'program-published') return v.templateRevision === p.expectedRevision + 1 && Number.isInteger(v.versionNumber) &&
    v.versionNumber >= 1 && v.versionNumber <= p.expectedRevision && v.versionId === publicationVersionId(v.versionNumber) && publicationHash(v.contentHash);
  return v.versionId === null && v.versionNumber === null && v.contentHash === null &&
    (v.status === 'publication-conflict' ? v.templateRevision > p.expectedRevision :
      ['publication-draft-conflict', 'publication-source-conflict', 'publication-incomplete'].includes(v.status) && v.templateRevision === p.expectedRevision);
}
