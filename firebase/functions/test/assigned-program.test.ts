import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { AssignmentResult, closeAssignment, publishAssignment, PublishAssignmentRequest } from "../src/assigned-program.js";
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
    await closeAssignment(db, "client", "ws", "asg-two", "revoked");
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
    await closeAssignment(db, "client", "ws", "asg-one", "cancelled");
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
});
