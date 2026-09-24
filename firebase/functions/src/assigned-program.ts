import { FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { commandId, stableHash } from "./hashing.js";

// Every ID that becomes a Firestore path segment is validated as exactly one document segment
// before any reference is constructed and before any transaction starts. Without this, a value such
// as `day/exercises/item` is accepted as a nested document path instead of a single workout ID and
// materializes a workout at an exercise path, producing a "ready" assignment whose structure no
// longer matches the manifest and the client schema; an assignment ID could likewise route writes
// beneath another assignment. UIDs keep the shared Firebase UID contract for the `users/{uid}` path
// scheme (non-blank, at most 128 UTF-16 code units, no path separator, not `.`/`..`, not the
// reserved `__.*__` form) instead of a stricter business whitelist, so email-shaped and punctuated
// identities stay supported; business IDs (workspace, assignment, workout, exercise, plan) use that
// same single-segment rule.
const MAX_PATH_SEGMENT_LENGTH = 128;
const RESERVED_DOCUMENT_ID = /^__.*__$/;

function singlePathSegment(value: unknown, failure: string): string {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_PATH_SEGMENT_LENGTH || value.trim().length === 0) {
    throw new Error(`${failure}-invalid`);
  }
  if (value.includes("/")) throw new Error(`${failure}-path-separator`);
  if (value === "." || value === ".." || RESERVED_DOCUMENT_ID.test(value)) throw new Error(`${failure}-invalid`);
  return value;
}

// Publication references are built from the user, workspace, assignment (and its predecessor),
// snapshot workout, snapshot exercise, and plan IDs, so all of them are validated up front.
function requirePublishPathIdentifiers(input: PublishAssignmentRequest): void {
  singlePathSegment(input.uid, "client-uid");
  singlePathSegment(input.workspaceId, "workspace-id");
  singlePathSegment(input.assignmentId, "assignment-id");
  if (input.replacesAssignmentId !== undefined) singlePathSegment(input.replacesAssignmentId, "replaces-assignment-id");
  for (const workout of input.workouts) {
    singlePathSegment(workout.id, "snapshot-workout-id");
    for (const exercise of workout.exercises) singlePathSegment(exercise.id, "snapshot-exercise-id");
  }
  for (const plan of input.plans) singlePathSegment(plan.id, "planned-workout-id");
}

export interface SnapshotExercise {
  readonly id: string;
  readonly position: number;
  readonly displayName: string;
  readonly prescription: string;
}

export interface SnapshotWorkout {
  readonly id: string;
  readonly position: number;
  readonly title: string;
  readonly exercises: readonly SnapshotExercise[];
}

export interface PlannedWorkout {
  readonly id: string;
  readonly workoutId: string;
  readonly scheduledInstantMillis: number;
}

export interface PublishAssignmentRequest {
  readonly callerUid: string;
  readonly idempotencyKey: string;
  readonly uid: string;
  readonly workspaceId: string;
  readonly trainerId: string;
  readonly assignmentId: string;
  readonly sourceTemplateId: string;
  readonly sourceVersionId: string;
  readonly sourceVersionNumber: number;
  readonly sourceContentHash: string;
  readonly snapshotHash: string;
  readonly accessExpiresAtMillis: number;
  readonly workouts: readonly SnapshotWorkout[];
  readonly plans: readonly PlannedWorkout[];
  readonly replacesAssignmentId?: string;
  readonly replacesExpectedRevision?: number;
  readonly maxWrites: number;
}

export interface AssignmentResult {
  readonly assignmentId: string;
  readonly revision: number;
  readonly documentCount: number;
  readonly replayed: boolean;
}

export async function publishAssignment(db: Firestore, input: PublishAssignmentRequest): Promise<AssignmentResult> {
  requirePublishPathIdentifiers(input);
  const replaces = input.replacesAssignmentId !== undefined;
  if (replaces && (!Number.isInteger(input.replacesExpectedRevision) || (input.replacesExpectedRevision ?? 0) < 1)) {
    throw new Error("predecessor-revision-required");
  }
  if (!replaces && input.replacesExpectedRevision !== undefined) {
    throw new Error("predecessor-revision-without-target");
  }
  const requestHash = stableHash(input);
  const receiptRef = db.doc(`assignmentCommands/${commandId(input.callerUid, input.idempotencyKey)}`);
  const headerRef = db.doc(`users/${input.uid}/workspaces/${input.workspaceId}/assignedPrograms/${input.assignmentId}`);
  const snapshotRef = headerRef.collection("snapshots").doc("content");
  const trainerIndexRef = db.doc(`workspaces/${input.workspaceId}/assignedPrograms/${input.assignmentId}`);
  const manifestRef = headerRef.collection("manifests").doc("download");
  const predecessorHeaderRef = replaces ? db.doc(`users/${input.uid}/workspaces/${input.workspaceId}/assignedPrograms/${input.replacesAssignmentId}`) : null;
  const predecessorIndexRef = replaces ? db.doc(`workspaces/${input.workspaceId}/assignedPrograms/${input.replacesAssignmentId}`) : null;
  const workoutCount = input.workouts.length;
  const exerciseCount = input.workouts.reduce((sum, workout) => sum + workout.exercises.length, 0);
  const documentCount = 1 + 1 + workoutCount + exerciseCount + input.plans.length + 1 + 1 + 1 + (replaces ? 2 : 0);
  if (documentCount > input.maxWrites) throw new Error("write-budget-exceeded");
  const snapshotWorkoutIds = new Set<string>();
  for (const workout of input.workouts) {
    // Path segments were validated before any reference was built; duplicates are still rejected.
    if (snapshotWorkoutIds.has(workout.id)) throw new Error("snapshot-workout-id-duplicated");
    snapshotWorkoutIds.add(workout.id);
  }
  for (const plan of input.plans) {
    if (typeof plan.workoutId !== "string" || plan.workoutId.trim().length === 0) throw new Error("plan-workout-reference-invalid");
    if (!snapshotWorkoutIds.has(plan.workoutId)) throw new Error("plan-workout-reference-missing");
  }
  const requiredPathSet = new Set<string>(["snapshots/content"]);
  input.workouts.forEach((workout) => {
    requiredPathSet.add(`snapshots/content/workouts/${workout.id}`);
    workout.exercises.forEach((exercise) => requiredPathSet.add(`snapshots/content/workouts/${workout.id}/exercises/${exercise.id}`));
  });
  input.plans.forEach((plan) => requiredPathSet.add(`plannedWorkouts/${plan.id}`));
  const requiredPaths = [...requiredPathSet].sort();

  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(receiptRef, headerRef, ...(predecessorHeaderRef && predecessorIndexRef ? [predecessorHeaderRef, predecessorIndexRef] : []));
    const receipt = documents[0]!;
    const existing = documents[1]!;
    if (receipt.exists) {
      if (receipt.get("requestHash") !== requestHash) throw new Error("idempotency-key-reused");
      return { assignmentId: receipt.get("assignmentId") as string, revision: 1, documentCount: receipt.get("documentCount") as number, replayed: true };
    }
    if (existing.exists) throw new Error("assignment-id-reused");
    if (predecessorHeaderRef && predecessorIndexRef) {
      const predecessor = documents[2]!;
      const predecessorIndex = documents[3]!;
      const expectedPredecessorRevision = input.replacesExpectedRevision!;
      if (!predecessor.exists) throw new Error("predecessor-missing");
      if (
        predecessor.get("clientId") !== input.uid ||
        predecessor.get("workspaceId") !== input.workspaceId ||
        predecessor.get("assignmentId") !== input.replacesAssignmentId ||
        predecessor.get("trainerId") !== input.trainerId
      ) throw new Error("predecessor-identity-mismatch");
      if (predecessor.get("revision") !== expectedPredecessorRevision) throw new Error("predecessor-stale-revision");
      if (predecessor.get("lifecycleState") !== "ready" || predecessor.get("accessStatus") !== "active") throw new Error("predecessor-not-replaceable");
      if (!predecessorIndex.exists) throw new Error("predecessor-index-missing");
      if (
        predecessorIndex.get("workspaceId") !== input.workspaceId ||
        predecessorIndex.get("clientId") !== input.uid ||
        predecessorIndex.get("assignmentId") !== input.replacesAssignmentId ||
        predecessorIndex.get("trainerId") !== input.trainerId ||
        predecessorIndex.get("status") !== "active" ||
        predecessorIndex.get("revision") !== expectedPredecessorRevision
      ) throw new Error("predecessor-index-inconsistent");
    }
    const identity = { schemaVersion: 1, workspaceId: input.workspaceId, clientId: input.uid, assignmentId: input.assignmentId };
    transaction.create(headerRef, {
      ...identity,
      trainerId: input.trainerId,
      sourceTemplateId: input.sourceTemplateId,
      sourceVersionId: input.sourceVersionId,
      sourceVersionNumber: input.sourceVersionNumber,
      sourceContentHash: input.sourceContentHash,
      snapshotId: "content",
      snapshotHash: input.snapshotHash,
      revision: 1,
      lifecycleState: "ready",
      accessStatus: "active",
      accessExpiresAt: Timestamp.fromMillis(input.accessExpiresAtMillis)
    });
    transaction.create(snapshotRef, {
      ...identity,
      snapshotId: "content",
      contentHash: input.snapshotHash,
      sourceTemplateId: input.sourceTemplateId,
      sourceVersionId: input.sourceVersionId,
      sourceVersionNumber: input.sourceVersionNumber,
      sourceContentHash: input.sourceContentHash,
      title: "Milestone 3 client-safe snapshot"
    });
    for (const workout of input.workouts) {
      const workoutRef = snapshotRef.collection("workouts").doc(workout.id);
      transaction.create(workoutRef, { ...identity, snapshotId: "content", workoutId: workout.id, position: workout.position, title: workout.title });
      for (const exercise of workout.exercises) {
        transaction.create(workoutRef.collection("exercises").doc(exercise.id), {
          ...identity,
          snapshotId: "content",
          workoutId: workout.id,
          itemId: exercise.id,
          position: exercise.position,
          displayName: exercise.displayName,
          prescription: exercise.prescription
        });
      }
    }
    for (const plan of input.plans) {
      transaction.create(headerRef.collection("plannedWorkouts").doc(plan.id), {
        ...identity,
        snapshotId: "content",
        plannedWorkoutId: plan.id,
        workoutId: plan.workoutId,
        status: "planned",
        revision: 1,
        scheduledInstant: Timestamp.fromMillis(plan.scheduledInstantMillis)
      });
    }
    transaction.create(manifestRef, { ...identity, assignmentRevision: 1, snapshotHash: input.snapshotHash, requiredPaths });
    transaction.create(trainerIndexRef, { ...identity, trainerId: input.trainerId, status: "active", revision: 1 });
    if (predecessorHeaderRef && predecessorIndexRef) {
      const retiredRevision = input.replacesExpectedRevision! + 1;
      transaction.update(predecessorHeaderRef, {
        accessStatus: "replaced",
        lifecycleState: "terminal",
        replacedByAssignmentId: input.assignmentId,
        replacedAt: FieldValue.serverTimestamp(),
        revision: retiredRevision
      });
      transaction.update(predecessorIndexRef, {
        status: "replaced",
        replacedByAssignmentId: input.assignmentId,
        replacedAt: FieldValue.serverTimestamp(),
        revision: retiredRevision
      });
    }
    transaction.create(receiptRef, { schemaVersion: 1, requestHash, commandKind: "publish-assignment", assignmentId: input.assignmentId, documentCount, committedAt: FieldValue.serverTimestamp() });
    return { assignmentId: input.assignmentId, revision: 1, documentCount, replayed: false };
  });
}

export type AssignmentCloseStatus = "archived" | "cancelled" | "revoked";

export interface CloseAssignmentRequest {
  readonly callerUid: string;
  readonly idempotencyKey: string;
  readonly uid: string;
  readonly workspaceId: string;
  readonly assignmentId: string;
  readonly status: AssignmentCloseStatus;
  readonly expectedRevision: number;
}

export interface CloseAssignmentResult {
  readonly assignmentId: string;
  readonly revision: number;
  readonly status: AssignmentCloseStatus;
  readonly replayed: boolean;
}

// Guarded live-to-terminal transition: an already replaced/archived/cancelled/revoked or
// expired assignment is never rewritten, terminal reasons and replacement metadata are
// immutable, and only the original caller-bound receipt replays a successful close.
export async function closeAssignment(db: Firestore, input: CloseAssignmentRequest): Promise<CloseAssignmentResult> {
  // The header and discovery-index references are built from these three IDs, so each must be a
  // single document segment before the transaction reads or publishes anything.
  singlePathSegment(input.uid, "client-uid");
  singlePathSegment(input.workspaceId, "workspace-id");
  singlePathSegment(input.assignmentId, "assignment-id");
  if (!Number.isInteger(input.expectedRevision) || input.expectedRevision < 1) throw new Error("expected-revision-invalid");
  const requestHash = stableHash(input);
  const receiptRef = db.doc(`assignmentCommands/${commandId(input.callerUid, input.idempotencyKey)}`);
  const headerRef = db.doc(`users/${input.uid}/workspaces/${input.workspaceId}/assignedPrograms/${input.assignmentId}`);
  const indexRef = db.doc(`workspaces/${input.workspaceId}/assignedPrograms/${input.assignmentId}`);
  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(receiptRef, headerRef, indexRef);
    const receipt = documents[0]!;
    const header = documents[1]!;
    const index = documents[2]!;
    if (receipt.exists) {
      if (receipt.get("requestHash") !== requestHash) throw new Error("idempotency-key-reused");
      return {
        assignmentId: receipt.get("assignmentId") as string,
        revision: receipt.get("revision") as number,
        status: receipt.get("status") as AssignmentCloseStatus,
        replayed: true
      };
    }
    if (!header.exists) throw new Error("assignment-missing");
    if (
      header.get("clientId") !== input.uid ||
      header.get("workspaceId") !== input.workspaceId ||
      header.get("assignmentId") !== input.assignmentId
    ) throw new Error("assignment-identity-mismatch");
    if (header.get("revision") !== input.expectedRevision) throw new Error("assignment-stale-revision");
    if (header.get("lifecycleState") !== "ready" || header.get("accessStatus") !== "active") throw new Error("assignment-not-live");
    const expiresAt = header.get("accessExpiresAt");
    if (!(expiresAt instanceof Timestamp) || expiresAt.valueOf() <= Timestamp.now().valueOf()) throw new Error("assignment-not-live");
    if (!index.exists) throw new Error("assignment-index-missing");
    if (
      index.get("workspaceId") !== input.workspaceId ||
      index.get("clientId") !== input.uid ||
      index.get("assignmentId") !== input.assignmentId ||
      index.get("trainerId") !== header.get("trainerId") ||
      index.get("status") !== "active" ||
      index.get("revision") !== input.expectedRevision
    ) throw new Error("assignment-index-inconsistent");
    const revision = input.expectedRevision + 1;
    transaction.update(headerRef, { accessStatus: input.status, lifecycleState: "terminal", revision });
    transaction.update(indexRef, { status: input.status, revision });
    transaction.create(receiptRef, { schemaVersion: 1, requestHash, commandKind: "close-assignment", callerUid: input.callerUid, assignmentId: input.assignmentId, revision, status: input.status, committedAt: FieldValue.serverTimestamp() });
    return { assignmentId: input.assignmentId, revision, status: input.status, replayed: false };
  });
}
