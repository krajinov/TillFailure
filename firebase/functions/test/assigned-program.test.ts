import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { AssignmentResult, closeAssignment, CloseAssignmentRequest, publishAssignment, PublishAssignmentRequest } from "../src/assigned-program.js";
import { emulatorFirestore } from "../src/environment.js";

const db = emulatorFirestore();
const base: PublishAssignmentRequest = {
  callerUid: "trainer",
  idempotencyKey: "assign-one",
  uid: "client",
  workspaceId: "ws",
  trainerId: "trainer",
  assignmentId: "asg-one",
  sourceTemplateId: "template",
  sourceVersionId: "v1",
  sourceVersionNumber: 1,
  sourceContentHash: "sha256:source",
  snapshotHash: "sha256:snapshot",
  accessExpiresAtMillis: Date.UTC(2030, 0, 1),
  workouts: [{ id: "day-one", position: 0, title: "Day one", exercises: [{ id: "squat", position: 0, displayName: "Squat", prescription: "3 x 5" }] }],
  plans: [{ id: "plan-one", workoutId: "day-one", scheduledInstantMillis: Date.UTC(2027, 0, 1) }],
  maxWrites: 200
};

describe("assigned-program publication", () => {
  beforeEach(async () => {
    for (const name of ["users", "workspaces", "assignmentCommands"]) await db.recursiveDelete(db.collection(name));
  });

  it("publishes bounded content atomically and replays idempotently", async () => {
    const result = await publishAssignment(db, base);
    assert.equal(result.replayed, false);
    assert.equal((await publishAssignment(db, base)).replayed, true);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one/snapshots/content").get()).get("snapshotId"), "content");
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one/snapshots/content/workouts/day-one/exercises/squat").get()).get("prescription"), "3 x 5");
    const manifest = await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one/manifests/download").get();
    assert.deepEqual(manifest.get("requiredPaths"), [
      "plannedWorkouts/plan-one",
      "snapshots/content",
      "snapshots/content/workouts/day-one",
      "snapshots/content/workouts/day-one/exercises/squat"
    ]);
  });

  it("publishes a deterministic complete inventory with zero, one, or many plans", async () => {
    await publishAssignment(db, { ...base, idempotencyKey: "zero", assignmentId: "asg-zero", plans: [] });
    assert.deepEqual(
      (await db.doc("users/client/workspaces/ws/assignedPrograms/asg-zero/manifests/download").get()).get("requiredPaths"),
      ["snapshots/content", "snapshots/content/workouts/day-one", "snapshots/content/workouts/day-one/exercises/squat"]
    );

    await publishAssignment(db, {
      ...base,
      idempotencyKey: "many",
      assignmentId: "asg-many",
      plans: [
        { id: "plan-z", workoutId: "day-one", scheduledInstantMillis: Date.UTC(2027, 0, 3) },
        { id: "plan-a", workoutId: "day-one", scheduledInstantMillis: Date.UTC(2027, 0, 2) }
      ]
    });
    const requiredPaths = (await db.doc("users/client/workspaces/ws/assignedPrograms/asg-many/manifests/download").get()).get("requiredPaths") as string[];
    assert.deepEqual(requiredPaths, [...requiredPaths].sort());
    assert.equal(new Set(requiredPaths).size, requiredPaths.length);
    assert.ok(requiredPaths.includes("plannedWorkouts/plan-a"));
    assert.ok(requiredPaths.includes("plannedWorkouts/plan-z"));
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-many/plannedWorkouts/plan-a").get()).exists, true);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-many/plannedWorkouts/plan-z").get()).exists, true);
  });

  it("replacement uses new identity and retires both predecessor representations atomically", async () => {
    await publishAssignment(db, base);
    const replacement = await publishAssignment(db, { ...base, idempotencyKey: "assign-two", assignmentId: "asg-two", sourceVersionId: "v2", sourceVersionNumber: 2, replacesAssignmentId: "asg-one", replacesExpectedRevision: 1 });
    assert.equal(replacement.replayed, false);
    const predecessor = await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get();
    assert.equal(predecessor.get("lifecycleState"), "terminal");
    assert.equal(predecessor.get("accessStatus"), "replaced");
    assert.equal(predecessor.get("replacedByAssignmentId"), "asg-two");
    assert.equal(predecessor.get("revision"), 2);
    assert.ok(predecessor.get("replacedAt") !== undefined);
    const predecessorIndex = await db.doc("workspaces/ws/assignedPrograms/asg-one").get();
    assert.equal(predecessorIndex.get("status"), "replaced");
    assert.equal(predecessorIndex.get("replacedByAssignmentId"), "asg-two");
    assert.equal(predecessorIndex.get("revision"), 2);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-two").get()).get("lifecycleState"), "ready");
    const discoverable = await db.collection("workspaces/ws/assignedPrograms").where("status", "==", "active").get();
    assert.deepEqual(discoverable.docs.map((document) => document.id), ["asg-two"]);
    await closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-asg-two", uid: "client", workspaceId: "ws", assignmentId: "asg-two", status: "revoked", expectedRevision: 1 });
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-two").get()).get("accessStatus"), "revoked");
  });

  it("replays a replacement without re-retiring the predecessor", async () => {
    await publishAssignment(db, base);
    const request = { ...base, idempotencyKey: "assign-two", assignmentId: "asg-two", replacesAssignmentId: "asg-one", replacesExpectedRevision: 1 };
    const first = await publishAssignment(db, request);
    const predecessorBefore = (await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).data();
    const indexBefore = (await db.doc("workspaces/ws/assignedPrograms/asg-one").get()).data();
    const replay = await publishAssignment(db, request);
    assert.equal(replay.replayed, true);
    assert.equal(replay.assignmentId, first.assignmentId);
    assert.equal(replay.documentCount, first.documentCount);
    assert.deepEqual((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).data(), predecessorBefore);
    assert.deepEqual((await db.doc("workspaces/ws/assignedPrograms/asg-one").get()).data(), indexBefore);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).get("revision"), 2);
    assert.equal((await db.collection("workspaces/ws/assignedPrograms").get()).size, 2);
    assert.equal((await db.collection("assignmentCommands").get()).size, 2);
  });

  it("rejects an oversized publication without partial state", async () => {
    await assert.rejects(() => publishAssignment(db, { ...base, maxWrites: 2 }), /write-budget/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).exists, false);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one/manifests/download").get()).exists, false);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one/plannedWorkouts/plan-one").get()).exists, false);
  });

  it("counts both predecessor retirement writes in the publication budget", async () => {
    await publishAssignment(db, base);
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "budget-9", assignmentId: "asg-b9", replacesAssignmentId: "asg-one", replacesExpectedRevision: 1, maxWrites: 9 }), /write-budget-exceeded/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-b9").get()).exists, false);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).get("revision"), 1);
    const exact = await publishAssignment(db, { ...base, idempotencyKey: "budget-10", assignmentId: "asg-b10", replacesAssignmentId: "asg-one", replacesExpectedRevision: 1, maxWrites: 10 });
    assert.equal(exact.documentCount, 10);
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "budget-7", assignmentId: "asg-b7", maxWrites: 7 }), /write-budget-exceeded/);
    assert.equal((await publishAssignment(db, { ...base, idempotencyKey: "budget-8", assignmentId: "asg-b8", maxWrites: 8 })).documentCount, 8);
  });

  it("rejects stale, missing, and malformed replacement requests without partial state", async () => {
    await publishAssignment(db, base);
    const captureState = async () => ({
      predecessor: (await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).data(),
      predecessorIndex: (await db.doc("workspaces/ws/assignedPrograms/asg-one").get()).data(),
      receipts: (await db.collection("assignmentCommands").get()).size,
      indexes: (await db.collection("workspaces/ws/assignedPrograms").get()).size
    });
    const before = await captureState();
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "stale", assignmentId: "asg-stale", replacesAssignmentId: "asg-one", replacesExpectedRevision: 99 }), /predecessor-stale-revision/);
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "missing", assignmentId: "asg-missing", replacesAssignmentId: "asg-none", replacesExpectedRevision: 1 }), /predecessor-missing/);
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "no-revision", assignmentId: "asg-norev", replacesAssignmentId: "asg-one" }), /predecessor-revision-required/);
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "no-target", assignmentId: "asg-notarget", replacesExpectedRevision: 1 }), /predecessor-revision-without-target/);
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "zero-revision", assignmentId: "asg-zero-rev", replacesAssignmentId: "asg-one", replacesExpectedRevision: 0 }), /predecessor-revision-required/);
    for (const id of ["asg-stale", "asg-missing", "asg-norev", "asg-notarget", "asg-zero-rev"]) {
      assert.equal((await db.doc(`users/client/workspaces/ws/assignedPrograms/${id}`).get()).exists, false);
      assert.equal((await db.doc(`workspaces/ws/assignedPrograms/${id}`).get()).exists, false);
    }
    assert.deepEqual(await captureState(), before);
  });

  it("rejects terminal and already-replaced predecessors", async () => {
    await publishAssignment(db, base);
    await closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-asg-one", uid: "client", workspaceId: "ws", assignmentId: "asg-one", status: "cancelled", expectedRevision: 1 });
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "closed", assignmentId: "asg-closed", replacesAssignmentId: "asg-one", replacesExpectedRevision: 2 }), /predecessor-not-replaceable/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-closed").get()).exists, false);

    await publishAssignment(db, { ...base, idempotencyKey: "fresh", assignmentId: "asg-fresh" });
    await publishAssignment(db, { ...base, idempotencyKey: "replace-fresh", assignmentId: "asg-successor", replacesAssignmentId: "asg-fresh", replacesExpectedRevision: 1 });
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "second-successor", assignmentId: "asg-second", replacesAssignmentId: "asg-fresh", replacesExpectedRevision: 2 }), /predecessor-not-replaceable/);
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "stale-successor", assignmentId: "asg-third", replacesAssignmentId: "asg-fresh", replacesExpectedRevision: 1 }), /predecessor-stale-revision/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-second").get()).exists, false);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-third").get()).exists, false);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-fresh").get()).get("replacedByAssignmentId"), "asg-successor");
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-fresh").get()).get("revision"), 2);
    const active = await db.collection("workspaces/ws/assignedPrograms").where("status", "==", "active").get();
    assert.deepEqual(active.docs.map((document) => document.id), ["asg-successor"]);
    assert.equal((await db.doc("workspaces/ws/assignedPrograms/asg-one").get()).get("status"), "cancelled");
    assert.equal((await db.doc("workspaces/ws/assignedPrograms/asg-fresh").get()).get("status"), "replaced");
  });

  it("rejects mismatched predecessor and index identities without partial state", async () => {
    await publishAssignment(db, { ...base, idempotencyKey: "other-trainer", trainerId: "trainer-b", assignmentId: "asg-b" });
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "cross-trainer", assignmentId: "asg-c", replacesAssignmentId: "asg-b", replacesExpectedRevision: 1 }), /predecessor-identity-mismatch/);

    await publishAssignment(db, { ...base, idempotencyKey: "tamper-seed", assignmentId: "asg-t" });
    await db.doc("users/client/workspaces/ws/assignedPrograms/asg-t").update({ clientId: "other-client" });
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "tampered", assignmentId: "asg-u", replacesAssignmentId: "asg-t", replacesExpectedRevision: 1 }), /predecessor-identity-mismatch/);

    await publishAssignment(db, { ...base, idempotencyKey: "no-index-seed", assignmentId: "asg-ni" });
    await db.doc("workspaces/ws/assignedPrograms/asg-ni").delete();
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "no-index", assignmentId: "asg-v", replacesAssignmentId: "asg-ni", replacesExpectedRevision: 1 }), /predecessor-index-missing/);

    await publishAssignment(db, { ...base, idempotencyKey: "bad-index-seed", assignmentId: "asg-bi" });
    await db.doc("workspaces/ws/assignedPrograms/asg-bi").update({ workspaceId: "other-ws" });
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "bad-index", assignmentId: "asg-w", replacesAssignmentId: "asg-bi", replacesExpectedRevision: 1 }), /predecessor-index-inconsistent/);

    await publishAssignment(db, { ...base, idempotencyKey: "split-index-seed", assignmentId: "asg-si" });
    await db.doc("workspaces/ws/assignedPrograms/asg-si").update({ status: "revoked" });
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "split-index", assignmentId: "asg-x", replacesAssignmentId: "asg-si", replacesExpectedRevision: 1 }), /predecessor-index-inconsistent/);

    for (const id of ["asg-c", "asg-u", "asg-v", "asg-w", "asg-x"]) {
      assert.equal((await db.doc(`users/client/workspaces/ws/assignedPrograms/${id}`).get()).exists, false);
      assert.equal((await db.doc(`workspaces/ws/assignedPrograms/${id}`).get()).exists, false);
    }
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-b").get()).get("lifecycleState"), "ready");
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-b").get()).get("revision"), 1);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-ni").get()).get("lifecycleState"), "ready");
    assert.equal((await db.doc("workspaces/ws/assignedPrograms/asg-bi").get()).get("status"), "active");
  });

  it("allows exactly one of two competing replacements of the same predecessor", async () => {
    await publishAssignment(db, base);
    const competitor = (key: string, id: string) => publishAssignment(db, { ...base, idempotencyKey: key, assignmentId: id, replacesAssignmentId: "asg-one", replacesExpectedRevision: 1 });
    const results = await Promise.allSettled([competitor("race-a", "asg-race-a"), competitor("race-b", "asg-race-b")]);
    const fulfilled = results.filter((result): result is PromiseFulfilledResult<AssignmentResult> => result.status === "fulfilled");
    assert.equal(fulfilled.length, 1);
    assert.equal(results.filter((result) => result.status === "rejected").length, 1);
    const winner = fulfilled[0]!.value.assignmentId;
    const loser = winner === "asg-race-a" ? "asg-race-b" : "asg-race-a";
    const predecessor = await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get();
    assert.equal(predecessor.get("revision"), 2);
    assert.equal(predecessor.get("lifecycleState"), "terminal");
    assert.equal(predecessor.get("replacedByAssignmentId"), winner);
    assert.equal((await db.doc("workspaces/ws/assignedPrograms/asg-one").get()).get("status"), "replaced");
    assert.equal((await db.doc(`users/client/workspaces/ws/assignedPrograms/${loser}`).get()).exists, false);
    assert.equal((await db.doc(`workspaces/ws/assignedPrograms/${loser}`).get()).exists, false);
    const active = await db.collection("workspaces/ws/assignedPrograms").where("status", "==", "active").get();
    assert.deepEqual(active.docs.map((document) => document.id), [winner]);
    assert.equal((await db.collection("assignmentCommands").get()).size, 2);
  });

  it("rejects plans that do not reference materialized snapshot workouts", async () => {
    const plan = (id: string, workoutId: string) => ({ id, workoutId, scheduledInstantMillis: Date.UTC(2027, 0, 1) });
    // Valid single and multiple references still publish.
    await publishAssignment(db, { ...base, idempotencyKey: "plans-valid", assignmentId: "asg-plans-valid", plans: [plan("p1", "day-one"), plan("p2", "day-one")] });
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-plans-valid/plannedWorkouts/p2").get()).exists, true);

    // Absent workout reference.
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "plan-absent", assignmentId: "asg-plan-absent", plans: [plan("p1", "day-missing")] }), /plan-workout-reference-missing/);
    // Blank plan workout reference.
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "plan-blank", assignmentId: "asg-plan-blank", plans: [plan("p1", "   ")] }), /plan-workout-reference-invalid/);
    // Duplicate snapshot workout IDs make every reference ambiguous.
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "workouts-duplicated", assignmentId: "asg-dup", workouts: [base.workouts[0]!, { ...base.workouts[0]!, position: 1 }] }), /snapshot-workout-id-duplicated/);
    // Blank snapshot workout ID.
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "workout-blank", assignmentId: "asg-blank", workouts: [{ ...base.workouts[0]!, id: " " }] }), /snapshot-workout-id-invalid/);
    // Mixed set where one of several plans is invalid.
    await assert.rejects(() => publishAssignment(db, { ...base, idempotencyKey: "plan-mixed", assignmentId: "asg-mixed", plans: [plan("p1", "day-one"), plan("p2", "day-missing")] }), /plan-workout-reference-missing/);

    // No header, snapshot, workout, exercise, plan, manifest, index, or receipt remains.
    for (const id of ["asg-plan-absent", "asg-plan-blank", "asg-dup", "asg-blank", "asg-mixed"]) {
      assert.equal((await db.doc(`users/client/workspaces/ws/assignedPrograms/${id}`).get()).exists, false);
      assert.equal((await db.doc(`users/client/workspaces/ws/assignedPrograms/${id}/snapshots/content`).get()).exists, false);
      assert.equal((await db.doc(`users/client/workspaces/ws/assignedPrograms/${id}/manifests/download`).get()).exists, false);
      assert.equal((await db.doc(`users/client/workspaces/ws/assignedPrograms/${id}/plannedWorkouts/p1`).get()).exists, false);
      assert.equal((await db.doc(`workspaces/ws/assignedPrograms/${id}`).get()).exists, false);
    }
    assert.equal((await db.collection("assignmentCommands").get()).size, 1);
    // Valid idempotent publication remains unchanged.
    const replay = await publishAssignment(db, { ...base, idempotencyKey: "plans-valid", assignmentId: "asg-plans-valid", plans: [plan("p1", "day-one"), plan("p2", "day-one")] });
    assert.equal(replay.replayed, true);
    assert.equal((await db.collection("assignmentCommands").get()).size, 1);
  });

  it("closes only live assignments through guarded revision-checked transitions", async () => {
    // Valid active-to-terminal transitions for each supported reason.
    for (const [id, status] of [["asg-archived", "archived"], ["asg-cancelled", "cancelled"], ["asg-revoked", "revoked"]] as const) {
      await publishAssignment(db, { ...base, idempotencyKey: `publish-${id}`, assignmentId: id });
      const closed = await closeAssignment(db, { callerUid: "trainer", idempotencyKey: `close-${id}`, uid: "client", workspaceId: "ws", assignmentId: id, status, expectedRevision: 1 });
      assert.equal(closed.replayed, false);
      assert.equal(closed.revision, 2);
      assert.equal(closed.status, status);
      const header = await db.doc(`users/client/workspaces/ws/assignedPrograms/${id}`).get();
      assert.equal(header.get("accessStatus"), status);
      assert.equal(header.get("lifecycleState"), "terminal");
      assert.equal(header.get("revision"), 2);
      const index = await db.doc(`workspaces/ws/assignedPrograms/${id}`).get();
      assert.equal(index.get("status"), status);
      assert.equal(index.get("revision"), 2);
    }

    // Stale and malformed expected revisions reject without any write.
    await publishAssignment(db, { ...base, idempotencyKey: "publish-stale", assignmentId: "asg-stale-close" });
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-stale", uid: "client", workspaceId: "ws", assignmentId: "asg-stale-close", status: "archived", expectedRevision: 99 }), /assignment-stale-revision/);
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-zero", uid: "client", workspaceId: "ws", assignmentId: "asg-stale-close", status: "archived", expectedRevision: 0 }), /expected-revision-invalid/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-stale-close").get()).get("lifecycleState"), "ready");
    assert.equal((await db.doc("workspaces/ws/assignedPrograms/asg-stale-close").get()).get("status"), "active");

    // Expired assignments are not live and cannot be closed.
    await publishAssignment(db, { ...base, idempotencyKey: "publish-expired", assignmentId: "asg-expired", accessExpiresAtMillis: Date.UTC(2020, 0, 1) });
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-expired", uid: "client", workspaceId: "ws", assignmentId: "asg-expired", status: "archived", expectedRevision: 1 }), /assignment-not-live/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-expired").get()).get("lifecycleState"), "ready");
  });

  it("preserves terminal state, receipts, and replacement metadata against repeated closes", async () => {
    await publishAssignment(db, { ...base, idempotencyKey: "publish-guard", assignmentId: "asg-guard" });
    await closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-guard", uid: "client", workspaceId: "ws", assignmentId: "asg-guard", status: "archived", expectedRevision: 1 });
    const closedHeader = (await db.doc("users/client/workspaces/ws/assignedPrograms/asg-guard").get()).data();
    const closedIndex = (await db.doc("workspaces/ws/assignedPrograms/asg-guard").get()).data();
    const receiptsBefore = (await db.collection("assignmentCommands").get()).size;

    // A different operation ID cannot close the already-terminal assignment again.
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-guard-again", uid: "client", workspaceId: "ws", assignmentId: "asg-guard", status: "revoked", expectedRevision: 2 }), /assignment-not-live/);
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-guard-stale", uid: "client", workspaceId: "ws", assignmentId: "asg-guard", status: "revoked", expectedRevision: 1 }), /assignment-stale-revision/);
    // A different caller cannot replay the original caller's receipt.
    await assert.rejects(() => closeAssignment(db, { callerUid: "someone-else", idempotencyKey: "close-guard", uid: "client", workspaceId: "ws", assignmentId: "asg-guard", status: "archived", expectedRevision: 2 }), /assignment-not-live/);
    assert.deepEqual((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-guard").get()).data(), closedHeader);
    assert.deepEqual((await db.doc("workspaces/ws/assignedPrograms/asg-guard").get()).data(), closedIndex);
    assert.equal((await db.collection("assignmentCommands").get()).size, receiptsBefore);

    // The same operation ID replays through the caller-bound receipt without any rewrite.
    const replay = await closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-guard", uid: "client", workspaceId: "ws", assignmentId: "asg-guard", status: "archived", expectedRevision: 1 });
    assert.equal(replay.replayed, true);
    assert.equal(replay.revision, 2);
    assert.equal(replay.status, "archived");
    assert.deepEqual((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-guard").get()).data(), closedHeader);
    assert.equal((await db.collection("assignmentCommands").get()).size, receiptsBefore);

    // A replaced predecessor cannot be closed and its replacement metadata is immutable.
    await publishAssignment(db, { ...base, idempotencyKey: "publish-pred", assignmentId: "asg-pred" });
    await publishAssignment(db, { ...base, idempotencyKey: "publish-succ", assignmentId: "asg-succ", replacesAssignmentId: "asg-pred", replacesExpectedRevision: 1 });
    const replacedHeader = (await db.doc("users/client/workspaces/ws/assignedPrograms/asg-pred").get()).data();
    const replacedIndex = (await db.doc("workspaces/ws/assignedPrograms/asg-pred").get()).data();
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-replaced", uid: "client", workspaceId: "ws", assignmentId: "asg-pred", status: "archived", expectedRevision: 2 }), /assignment-not-live/);
    assert.deepEqual((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-pred").get()).data(), replacedHeader);
    assert.deepEqual((await db.doc("workspaces/ws/assignedPrograms/asg-pred").get()).data(), replacedIndex);
    assert.equal(replacedHeader!.replacedByAssignmentId, "asg-succ");
    assert.equal(replacedHeader!.accessStatus, "replaced");

    // Divergent or missing index representations fail closed without partial writes.
    await publishAssignment(db, { ...base, idempotencyKey: "publish-diverged", assignmentId: "asg-diverged" });
    await db.doc("workspaces/ws/assignedPrograms/asg-diverged").update({ revision: 7 });
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-diverged", uid: "client", workspaceId: "ws", assignmentId: "asg-diverged", status: "archived", expectedRevision: 1 }), /assignment-index-inconsistent/);
    await db.doc("workspaces/ws/assignedPrograms/asg-diverged").delete();
    await assert.rejects(() => closeAssignment(db, { callerUid: "trainer", idempotencyKey: "close-no-index", uid: "client", workspaceId: "ws", assignmentId: "asg-diverged", status: "archived", expectedRevision: 1 }), /assignment-index-missing/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-diverged").get()).get("lifecycleState"), "ready");
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-diverged").get()).get("revision"), 1);
  });

  it("allows exactly one of two competing closes to commit", async () => {
    await publishAssignment(db, { ...base, idempotencyKey: "publish-race", assignmentId: "asg-race" });
    const close = (key: string) => closeAssignment(db, { callerUid: "trainer", idempotencyKey: key, uid: "client", workspaceId: "ws", assignmentId: "asg-race", status: "archived", expectedRevision: 1 });
    const results = await Promise.allSettled([close("close-race-a"), close("close-race-b")]);
    assert.equal(results.filter((result) => result.status === "fulfilled").length, 1);
    assert.equal(results.filter((result) => result.status === "rejected").length, 1);
    const header = await db.doc("users/client/workspaces/ws/assignedPrograms/asg-race").get();
    assert.equal(header.get("revision"), 2);
    assert.equal(header.get("lifecycleState"), "terminal");
    assert.equal(header.get("accessStatus"), "archived");
    assert.equal((await db.doc("workspaces/ws/assignedPrograms/asg-race").get()).get("revision"), 2);
    assert.equal((await db.collection("assignmentCommands").where("commandKind", "==", "close-assignment").get()).size, 1);
  });

  it("rejects non-single-segment path IDs before any write", async () => {
    // The reported case: `day/exercises/item` would otherwise be accepted as a nested document path
    // and materialize a workout at an exercise path instead of failing closed.
    const cases: ReadonlyArray<readonly [string, Partial<PublishAssignmentRequest>, RegExp]> = [
      ["workout", { idempotencyKey: "nested-workout", assignmentId: "asg-nested-workout", workouts: [{ ...base.workouts[0]!, id: "day/exercises/item" }] }, /snapshot-workout-id-path-separator/],
      ["exercise", { idempotencyKey: "nested-exercise", assignmentId: "asg-nested-exercise", workouts: [{ ...base.workouts[0]!, exercises: [{ ...base.workouts[0]!.exercises[0]!, id: "squat/extra" }] }] }, /snapshot-exercise-id-path-separator/],
      ["plan", { idempotencyKey: "nested-plan", assignmentId: "asg-nested-plan", plans: [{ id: "plan/one", workoutId: "day-one", scheduledInstantMillis: Date.UTC(2027, 0, 1) }] }, /planned-workout-id-path-separator/],
      ["assignment", { idempotencyKey: "nested-assignment", assignmentId: "asg/other" }, /assignment-id-path-separator/],
      ["workspace", { idempotencyKey: "nested-workspace", workspaceId: "ws/other" }, /workspace-id-path-separator/],
      ["client-uid", { idempotencyKey: "nested-uid", uid: "client/other" }, /client-uid-path-separator/],
      ["predecessor", { idempotencyKey: "nested-predecessor", replacesAssignmentId: "asg-one/other", replacesExpectedRevision: 1 }, /replaces-assignment-id-path-separator/],
      ["reserved-document-id", { idempotencyKey: "nested-reserved", assignmentId: "__asg__" }, /assignment-id-invalid/],
      ["blank-workout", { idempotencyKey: "nested-blank", assignmentId: "asg-nested-blank", workouts: [{ ...base.workouts[0]!, id: "  " }] }, /snapshot-workout-id-invalid/]
    ];
    for (const [caseName, overrides, expected] of cases) {
      await assert.rejects(() => publishAssignment(db, { ...base, ...overrides }), expected, `${caseName}: publication must reject`);
      assert.equal((await db.collection("assignmentCommands").get()).size, 0, `${caseName}: no receipt`);
      assert.equal((await db.collection("users/client/workspaces/ws/assignedPrograms").get()).size, 0, `${caseName}: no assignment header`);
      assert.equal((await db.collection("workspaces/ws/assignedPrograms").get()).size, 0, `${caseName}: no discovery index`);
    }
    // Nothing was materialized at the nested path the reported case would have produced.
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-nested-workout/snapshots/content/workouts/day/exercises/item").get()).exists, false);

    // Closure builds its references from the same IDs, so it rejects identically and leaves the
    // published assignment and receipts untouched.
    await publishAssignment(db, base);
    const before = {
      header: (await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).data(),
      index: (await db.doc("workspaces/ws/assignedPrograms/asg-one").get()).data(),
      receipts: (await db.collection("assignmentCommands").get()).size
    };
    const closeCases: ReadonlyArray<readonly [string, Partial<CloseAssignmentRequest>, RegExp]> = [
      ["uid", { uid: "client/other" }, /client-uid-path-separator/],
      ["workspace", { workspaceId: "ws/other" }, /workspace-id-path-separator/],
      ["assignment", { assignmentId: "asg/other" }, /assignment-id-path-separator/]
    ];
    for (const [caseName, overrides, expected] of closeCases) {
      await assert.rejects(
        () => closeAssignment(db, { callerUid: "trainer", idempotencyKey: `close-nested-${caseName}`, uid: "client", workspaceId: "ws", assignmentId: "asg-one", status: "archived", expectedRevision: 1, ...overrides }),
        expected,
        `${caseName}: closure must reject`
      );
    }
    assert.deepEqual((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).data(), before.header, "rejected closure leaves the header unchanged");
    assert.deepEqual((await db.doc("workspaces/ws/assignedPrograms/asg-one").get()).data(), before.index, "rejected closure leaves the index unchanged");
    assert.equal((await db.collection("assignmentCommands").get()).size, before.receipts, "rejected closure records no receipt");
  });

  it("publishes punctuated UIDs and single-segment IDs at their exact paths", async () => {
    // The single-segment rule deliberately does not impose a stricter business whitelist: dots are
    // valid inside a document ID, so email-shaped UIDs and punctuated business IDs keep working and
    // still materialize at exactly their own document paths.
    const uid = "user.name+tag@example.com";
    const assignmentId = "asg.2027-01";
    const result = await publishAssignment(db, {
      ...base,
      idempotencyKey: "valid-punctuated",
      uid,
      assignmentId,
      workouts: [{ id: "day.one", position: 0, title: "Day one", exercises: [{ id: "squat.1", position: 0, displayName: "Squat", prescription: "3 x 5" }] }],
      plans: [{ id: "plan.one", workoutId: "day.one", scheduledInstantMillis: Date.UTC(2027, 0, 1) }]
    });
    assert.equal(result.replayed, false);
    const headerPath = `users/${uid}/workspaces/ws/assignedPrograms/${assignmentId}`;
    assert.equal((await db.doc(headerPath).get()).get("clientId"), uid);
    assert.equal((await db.doc(headerPath).get()).get("assignmentId"), assignmentId);
    assert.equal((await db.doc(`${headerPath}/snapshots/content/workouts/day.one/exercises/squat.1`).get()).get("itemId"), "squat.1");
    assert.equal((await db.doc(`${headerPath}/plannedWorkouts/plan.one`).get()).get("status"), "planned");
    assert.deepEqual((await db.doc(`${headerPath}/manifests/download`).get()).get("requiredPaths"), [
      "plannedWorkouts/plan.one",
      "snapshots/content",
      "snapshots/content/workouts/day.one",
      "snapshots/content/workouts/day.one/exercises/squat.1"
    ]);
    assert.equal((await db.doc(`workspaces/ws/assignedPrograms/${assignmentId}`).get()).get("clientId"), uid);
  });
});
