import { FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { commandId, stableHash } from "./hashing.js";

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

export async function closeAssignment(db: Firestore, uid: string, workspaceId: string, assignmentId: string, status: "archived" | "cancelled" | "revoked"): Promise<void> {
  const headerRef = db.doc(`users/${uid}/workspaces/${workspaceId}/assignedPrograms/${assignmentId}`);
  const indexRef = db.doc(`workspaces/${workspaceId}/assignedPrograms/${assignmentId}`);
  await db.runTransaction(async (transaction) => {
    const header = await transaction.get(headerRef);
    if (!header.exists || header.get("clientId") !== uid || header.get("workspaceId") !== workspaceId) throw new Error("assignment-missing");
    transaction.update(headerRef, { accessStatus: status, lifecycleState: "terminal", revision: FieldValue.increment(1) });
    transaction.update(indexRef, { status, revision: FieldValue.increment(1) });
  });
}
