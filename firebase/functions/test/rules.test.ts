import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, describe, it } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, query, setDoc, where } from "firebase/firestore";
import { ref, uploadString } from "firebase/storage";
import { SPIKE_PROJECT_ID } from "../src/environment.js";

let environment: RulesTestEnvironment;

before(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST) throw new Error("Rules tests require local emulators");
  environment = await initializeTestEnvironment({
    projectId: SPIKE_PROJECT_ID,
    firestore: { rules: await readFile("../firestore.rules", "utf8"), host: "127.0.0.1", port: 8080 },
    storage: { rules: await readFile("../storage.rules", "utf8"), host: "127.0.0.1", port: 9199 }
  });
});

beforeEach(async () => {
  await environment.clearFirestore();
  await environment.clearStorage();
});

after(async () => {
  await environment.cleanup();
});

async function seedEligibleAssignment(): Promise<void> {
  await environment.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, "users/client"), { schemaVersion: 1, accountStatus: "active" });
    await setDoc(doc(db, "users/client/authorizations/systemCatalog"), { schemaVersion: 1, status: "active", activeMembershipCount: 1 });
    await setDoc(doc(db, "workspaces/ws"), { schemaVersion: 1, status: "active" });
    await setDoc(doc(db, "workspaces/ws/memberships/client"), { schemaVersion: 1, userId: "client", role: "client", status: "active" });
    await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
    await setDoc(doc(db, "systemExercises/draft"), { schemaVersion: 1, name: "Draft", status: "draft" });
    await setDoc(doc(db, "users/client/workspaces/ws/assignedPrograms/asg"), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg", snapshotId: "content", lifecycleState: "ready", accessStatus: "active", accessExpiresAt: new Date("2030-01-01T00:00:00Z") });
    await setDoc(doc(db, "users/client/workspaces/ws/assignedPrograms/asg/snapshots/content"), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg", snapshotId: "content", title: "Safe copy" });
    await setDoc(doc(db, "users/client/workspaces/ws/assignedPrograms/asg/snapshots/content/workouts/day/exercises/item"), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg", snapshotId: "content", displayName: "Squat" });
    await setDoc(doc(db, "workspaces/ws/programTemplates/template/versions/v1"), { title: "Secret source" });
  });
}

describe("Firestore and Storage rules", () => {
  it("requires active trusted entitlement and published status for catalog reads", async () => {
    await seedEligibleAssignment();
    const client = environment.authenticatedContext("client").firestore();
    await assertSucceeds(getDoc(doc(client, "systemExercises/published")));
    await assertFails(getDoc(doc(client, "systemExercises/draft")));
    await assertSucceeds(getDocs(query(collection(client, "systemExercises"), where("status", "==", "published"))));
    await assertFails(getDocs(collection(client, "systemExercises")));
    await assertFails(setDoc(doc(client, "users/client/authorizations/systemCatalog"), { status: "active", activeMembershipCount: 99 }));
  });

  it("gates snapshots by direct account/workspace/membership/assignment checks and hides sources", async () => {
    await seedEligibleAssignment();
    const client = environment.authenticatedContext("client").firestore();
    const other = environment.authenticatedContext("other").firestore();
    const snapshotPath = "users/client/workspaces/ws/assignedPrograms/asg/snapshots/content";
    await assertSucceeds(getDoc(doc(client, snapshotPath)));
    await assertSucceeds(getDoc(doc(client, `${snapshotPath}/workouts/day/exercises/item`)));
    await assertFails(getDoc(doc(other, snapshotPath)));
    await assertFails(getDoc(doc(client, "workspaces/ws/programTemplates/template/versions/v1")));
    await assertFails(setDoc(doc(client, snapshotPath), { clientId: "client", workspaceId: "ws", assignmentId: "asg", snapshotId: "content" }));
    await environment.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), "users/client/workspaces/ws/assignedPrograms/asg"), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg", snapshotId: "content", lifecycleState: "terminal", accessStatus: "revoked", accessExpiresAt: new Date("2030-01-01T00:00:00Z") });
    });
    await assertFails(getDoc(doc(client, snapshotPath)));
  });

  it("allows an owner write and rejects forged ownership with stable permission denial", async () => {
    const client = environment.authenticatedContext("client").firestore();
    await assertSucceeds(setDoc(doc(client, "spikeEcho/client/documents/doc"), { ownerUid: "client", value: "accepted", counter: 0 }));
    await assertFails(setDoc(doc(client, "spikeEcho/other/documents/doc"), { ownerUid: "client", value: "forged", counter: 0 }));
  });

  it("denies client writes to server-authoritative lifecycle, count, lock, and index documents", async () => {
    await seedEligibleAssignment();
    const client = environment.authenticatedContext("client").firestore();
    await assertFails(setDoc(doc(client, "workspaces/ws"), { schemaVersion: 1, status: "active", membershipRevision: 99, activeMembershipCount: 99 }));
    await assertFails(setDoc(doc(client, "workspaces/ws/memberships/client"), { schemaVersion: 1, userId: "client", role: "trainer", status: "active", revision: 99, catalogContributionActive: true }));
    await assertFails(setDoc(doc(client, "workspaces/ws/assignedPrograms/asg"), { schemaVersion: 1, status: "active", revision: 99 }));
    await assertFails(setDoc(doc(client, "workspaces/ws/bookingSlots/trainer_1"), { schemaVersion: 1, utcBucket: 1, appointmentRevision: 99 }));
    await assertFails(setDoc(doc(client, "lifecycleCommands/cmd"), { schemaVersion: 1, requestHash: "forged" }));
    await assertFails(setDoc(doc(client, "assignmentCommands/cmd"), { schemaVersion: 1, requestHash: "forged" }));
    await assertFails(setDoc(doc(client, "users/client/workspaces/ws/assignedPrograms/asg"), { schemaVersion: 1, lifecycleState: "ready", accessStatus: "active", revision: 99 }));
  });

  it("enforces owner, MIME, metadata, and size for Storage", async () => {
    const ownerStorage = environment.authenticatedContext("client").storage();
    const otherStorage = environment.authenticatedContext("other").storage();
    await assertSucceeds(uploadString(ref(ownerStorage, "spikeUploads/client/ok"), "safe", "raw", { contentType: "text/plain", customMetadata: { ownerUid: "client" } }));
    await assertFails(uploadString(ref(otherStorage, "spikeUploads/client/no"), "safe", "raw", { contentType: "text/plain", customMetadata: { ownerUid: "other" } }));
    await assertFails(uploadString(ref(ownerStorage, "spikeUploads/client/no-mime"), "safe", "raw", { contentType: "application/octet-stream", customMetadata: { ownerUid: "client" } }));
    assert.ok(true);
  });
});
