import { sourceIdentity } from '../src/program-drafts.js';
import { publicationDraftHash, publicationContentHash } from '../src/program-publication-contract.js';
import { assignmentBinding, assignmentCopy, assignmentCopyHash, DAY_MS } from '../src/assignment-feasibility-contract.js';
export const assignmentUuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
export function assignmentFixture(character = 'S', count = 1, wide = false, widePath = false) {
  const uid = wide ? (widePath ? '界' : '\u0001').repeat(128) : 'A', wid = wide ? (widePath ? '節' : '\u0002').repeat(128) : 'w', client = wide ? (widePath ? '語' : '\u0003').repeat(128) : 'B';
  const id = assignmentUuid(1), m = wide ? 2147483645 : 1;
  const singleLine = character.replaceAll('\n', '');
  const title = singleLine.repeat(wide ? 80 : 1), description = character.repeat(wide ? 240 : 1);
  const names = wide ? 290 : count;
  let remaining = names;
  const items = Array.from({ length: count }, (_, index) => {
    const size = wide ? Math.min(120, remaining - (count - index - 1)) : 1; remaining -= size;
    const sourceId = 's'.repeat(wide ? 126 : 1) + String(index).padStart(2, '0');
    const source = { schemaVersion: 1, name: singleLine.repeat(size), nameNormalized: 'synthetic',
      instructions: character.repeat(wide ? 2000 : 1), status: 'published',
      muscleGroups: wide ? Array.from({ length: 8 }, (_, n) => character.repeat(39) + String(n)) : ['legs'],
      equipment: wide ? Array.from({ length: 8 }, (_, n) => character.repeat(39) + String(n)) : ['bodyweight'] };
    return { id: assignmentUuid(10 + index), ...sourceIdentity('system', sourceId, wid, uid, source),
      prescription: { sets: wide ? 20 : 3, reps: wide ? 1000 : 10, restSeconds: wide ? 3600 : 60, loadGrams: wide ? 1000000 : null },
      instructions: source.instructions, muscleGroups: source.muscleGroups, equipment: source.equipment };
  });
  const content = { contentPolicyVersion: 1, workspaceId: wid, templateId: id, ownerUid: uid, title, description,
    workouts: [{ id: assignmentUuid(2), title: singleLine.repeat(wide ? 40 : 1), items }] };
  const draft = { ...content, workouts: content.workouts.map(w => ({ ...w, items: w.items.map(({ instructions, muscleGroups, equipment, ...i }) => i) })) };
  const version = { schemaVersion: 1, ...content, versionId: `v${String(m).padStart(10, '0')}`, versionNumber: m, draftRevision: m,
    draftHash: publicationDraftHash(draft), contentHash: publicationContentHash(content), publishedAtMillis: 1 };
  const copy = assignmentCopy(version, uid, wid, id);
  const descriptor = { action: 'assign-program-version', workspaceId: wid,
    idempotencyKey: 'or1-b91f0176ec74475e8cf7406e25b7e9d3-' + (wide ? 'a'.repeat(80) : 'assignment-vector'),
    assignment: { assignmentId: assignmentUuid(3), plannedWorkoutId: assignmentUuid(4), clientUid: client, templateId: id,
      versionId: version.versionId, versionNumber: m, sourceContentHash: version.contentHash, copyPolicyVersion: 1, snapshotHash: assignmentCopyHash(copy),
      authority: { trainerLifecycleRevision: m, trainerMembershipRevision: m, clientLifecycleRevision: m, clientMembershipRevision: m, workspaceMembershipRevision: m, clientOnboardingRevision: m },
      preparedAtMillis: wide ? 253402127999999 : 1800000000000,
      scheduledInstantMillis: wide ? 253402214399999 : 1800000000001,
      accessExpiresAtMillis: wide ? 253402300799999 : 1800086400001 } };
  return { uid, wid, client, version, copy, descriptor };
}

// Live Rules authorization must not inherit the canonical vectors' frozen clock.
// Capture the reference once; keep the approved schedule/expiry arithmetic and
// revalidate/rebind the changed descriptor through the existing contract helper.
export function assignmentRulesFixture(now = Date.now(), state: 'active' | 'expired' = 'active') {
  const f = assignmentFixture();
  const preparedAtMillis = now - (state === 'expired' ? 3 * DAY_MS : 0);
  const scheduledInstantMillis = preparedAtMillis + DAY_MS;
  const descriptor = { ...f.descriptor, assignment: { ...f.descriptor.assignment,
    preparedAtMillis, scheduledInstantMillis, accessExpiresAtMillis: scheduledInstantMillis + DAY_MS } };
  return { ...f, descriptor, binding: assignmentBinding(f.uid, descriptor) };
}
