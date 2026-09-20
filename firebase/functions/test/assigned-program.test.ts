import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { closeAssignment, publishAssignment, PublishAssignmentRequest } from "../src/assigned-program.js";
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

  it("replacement uses new identity and terminal states deny the old parent", async () => {
    await publishAssignment(db, base);
    await publishAssignment(db, { ...base, idempotencyKey: "assign-two", assignmentId: "asg-two", sourceVersionId: "v2", sourceVersionNumber: 2, replacesAssignmentId: "asg-one" });
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).get("lifecycleState"), "terminal");
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-two").get()).get("lifecycleState"), "ready");
    await closeAssignment(db, "client", "ws", "asg-two", "revoked");
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-two").get()).get("accessStatus"), "revoked");
  });

  it("rejects an oversized publication without partial state", async () => {
    await assert.rejects(() => publishAssignment(db, { ...base, maxWrites: 2 }), /write-budget/);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one").get()).exists, false);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one/manifests/download").get()).exists, false);
    assert.equal((await db.doc("users/client/workspaces/ws/assignedPrograms/asg-one/plannedWorkouts/plan-one").get()).exists, false);
  });
});
