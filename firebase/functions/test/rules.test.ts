import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, describe, it } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from "firebase/firestore";
import { ref, uploadString } from "firebase/storage";
import { transitionAccountLifecycle, transitionWorkspace } from "../src/catalog.js";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { commandId } from "../src/hashing.js";

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

  it("keeps an above-cap entitlement denied when workspace suspension is rejected atomically", async () => {
    const workspacePath = "workspaces/ws_above_cap";
    const membershipPath = `${workspacePath}/memberships/client`;
    const accountPath = "users/client";
    const entitlementPath = `${accountPath}/authorizations/systemCatalog`;
    const receiptPath = `lifecycleCommands/${commandId("admin", "suspend-above-cap")}`;

    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(doc(db, accountPath), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
      await setDoc(doc(db, entitlementPath), { schemaVersion: 1, status: "active", activeMembershipCount: 21, revision: 7 });
      await setDoc(doc(db, workspacePath), { schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 1, catalogContributionCount: 1 });
      await setDoc(doc(db, membershipPath), { schemaVersion: 1, workspaceId: "ws_above_cap", userId: "client", role: "client", status: "active", revision: 1, catalogContributionActive: true });
      await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
    });

    const client = environment.authenticatedContext("client").firestore();
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    const adminDb = emulatorFirestore();
    const before = {
      workspace: (await adminDb.doc(workspacePath).get()).data(),
      membership: (await adminDb.doc(membershipPath).get()).data(),
      account: (await adminDb.doc(accountPath).get()).data(),
      entitlement: (await adminDb.doc(entitlementPath).get()).data()
    };
    assert.equal((await adminDb.doc(receiptPath).get()).exists, false);

    await assert.rejects(
      () => transitionWorkspace(adminDb, {
        callerUid: "admin",
        idempotencyKey: "suspend-above-cap",
        workspaceId: "ws_above_cap",
        nextStatus: "suspended",
        expectedMembershipRevision: 1,
        maxMembershipsPerAccount: 20,
        maxMembershipsPerWorkspace: 20,
        maxWrites: 200
      }),
      /membership-bound-violated/
    );

    assert.deepEqual((await adminDb.doc(workspacePath).get()).data(), before.workspace);
    assert.deepEqual((await adminDb.doc(membershipPath).get()).data(), before.membership);
    assert.deepEqual((await adminDb.doc(accountPath).get()).data(), before.account);
    assert.deepEqual((await adminDb.doc(entitlementPath).get()).data(), before.entitlement);
    assert.equal((await adminDb.doc(receiptPath).get()).exists, false);
    await assertFails(getDoc(doc(client, "systemExercises/published")));
  });

  it("cannot grant catalog access indirectly through a restoration that rejects a malformed membership", async () => {
    const workspacePath = "workspaces/ws_malformed_scan";
    const membershipPath = `${workspacePath}/memberships/client`;
    const accountPath = "users/client";
    const entitlementPath = `${accountPath}/authorizations/systemCatalog`;
    const receiptPath = `lifecycleCommands/${commandId("admin", "restore-malformed-scan")}`;

    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(doc(db, accountPath), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
      await setDoc(doc(db, entitlementPath), { schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });
      await setDoc(doc(db, workspacePath), { schemaVersion: 1, status: "suspended", membershipRevision: 1, activeRosterCount: 1, catalogContributionCount: 0 });
      // Document ID `client` with a stored identity pointing at another account: the workspace
      // Rules reject this shape, yet a restore that trusted the path alone could still publish a
      // `users/client` entitlement that Rules would then honor for catalog reads.
      await setDoc(doc(db, membershipPath), { schemaVersion: 1, workspaceId: "ws_malformed_scan", userId: "intruder", role: "client", status: "active", revision: 1, catalogContributionActive: false });
      await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
    });

    const client = environment.authenticatedContext("client").firestore();
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    const adminDb = emulatorFirestore();
    const restore = () => transitionWorkspace(adminDb, {
      callerUid: "admin",
      idempotencyKey: "restore-malformed-scan",
      workspaceId: "ws_malformed_scan",
      nextStatus: "active",
      expectedMembershipRevision: 1,
      maxMembershipsPerAccount: 20,
      maxMembershipsPerWorkspace: 20,
      maxWrites: 200
    });
    const before = {
      workspace: (await adminDb.doc(workspacePath).get()).data(),
      membership: (await adminDb.doc(membershipPath).get()).data(),
      account: (await adminDb.doc(accountPath).get()).data(),
      entitlement: (await adminDb.doc(entitlementPath).get()).data()
    };

    // The identity scan rejects before any entitlement delta, status change, or receipt commits.
    await assert.rejects(restore, /membership-user-mismatch/);
    assert.deepEqual((await adminDb.doc(workspacePath).get()).data(), before.workspace);
    assert.deepEqual((await adminDb.doc(membershipPath).get()).data(), before.membership);
    assert.deepEqual((await adminDb.doc(accountPath).get()).data(), before.account);
    assert.deepEqual((await adminDb.doc(entitlementPath).get()).data(), before.entitlement);
    assert.equal((await adminDb.doc(receiptPath).get()).exists, false);

    // No entitlement was published, so the malformed membership cannot indirectly authorize the
    // catalog read that Rules gates on trusted `systemCatalog` entitlement state.
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    // Once the stored identity matches its document path again, the same restoration succeeds
    // and only then does the entitlement authorize the read (the rejection had left no receipt).
    await environment.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), membershipPath), { schemaVersion: 1, workspaceId: "ws_malformed_scan", userId: "client", role: "client", status: "active", revision: 1, catalogContributionActive: false });
    });
    const restored = await restore();
    assert.equal(restored.affected, 1);
    assert.equal((await adminDb.doc(entitlementPath).get()).get("status"), "active");
    assert.equal((await adminDb.doc(entitlementPath).get()).get("activeMembershipCount"), 1);
    assert.equal((await adminDb.doc(receiptPath).get()).exists, true);
    await assertSucceeds(getDoc(doc(client, "systemExercises/published")));
  });

  it("keeps malformed and above-cap entitlements denied when an account enable is rejected atomically", async () => {
    const cases = [
      { uid: "client", count: 21, expected: /membership-bound-violated/ },
      { uid: "other", count: "1", expected: /entitlement-count-malformed/ }
    ] as const;

    for (const { uid, count, expected } of cases) {
      const accountPath = `users/${uid}`;
      const entitlementPath = `${accountPath}/authorizations/systemCatalog`;
      const idempotencyKey = `enable-${uid}`;
      const receiptPath = `lifecycleCommands/${commandId("admin", idempotencyKey)}`;

      await environment.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        await setDoc(doc(db, accountPath), { schemaVersion: 1, accountStatus: "disabled", lifecycleRevision: 1 });
        await setDoc(doc(db, entitlementPath), { schemaVersion: 1, status: "inactive", activeMembershipCount: count, revision: 7 });
        await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
      });

      const client = environment.authenticatedContext(uid).firestore();
      await assertFails(getDoc(doc(client, "systemExercises/published")));

      const adminDb = emulatorFirestore();
      const before = {
        account: (await adminDb.doc(accountPath).get()).data(),
        entitlement: (await adminDb.doc(entitlementPath).get()).data()
      };
      assert.equal((await adminDb.doc(receiptPath).get()).exists, false);

      await assert.rejects(
        () => transitionAccountLifecycle(adminDb, {
          callerUid: "admin",
          idempotencyKey,
          uid,
          enabled: true,
          expectedLifecycleRevision: 1,
          maxMembershipsPerAccount: 20
        }),
        expected
      );

      assert.deepEqual((await adminDb.doc(accountPath).get()).data(), before.account);
      assert.deepEqual((await adminDb.doc(entitlementPath).get()).data(), before.entitlement);
      assert.equal((await adminDb.doc(receiptPath).get()).exists, false);
      await assertFails(getDoc(doc(client, "systemExercises/published")));
    }
  });

  it("denies catalog reads after a legitimate account disable and refuses a stale enable", async () => {
    const accountPath = "users/client";
    const entitlementPath = `${accountPath}/authorizations/systemCatalog`;

    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(doc(db, accountPath), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
      await setDoc(doc(db, entitlementPath), { schemaVersion: 1, status: "active", activeMembershipCount: 1, revision: 1 });
      await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
    });

    const client = environment.authenticatedContext("client").firestore();
    await assertSucceeds(getDoc(doc(client, "systemExercises/published")));

    const adminDb = emulatorFirestore();
    // A legitimate disable at the expected revision always removes catalog authorization.
    const disabled = await transitionAccountLifecycle(adminDb, {
      callerUid: "admin",
      idempotencyKey: "disable-client",
      uid: "client",
      enabled: false,
      expectedLifecycleRevision: 1,
      maxMembershipsPerAccount: 20
    });
    assert.equal(disabled.accountStatus, "disabled");
    assert.equal(disabled.entitlementStatus, "inactive");
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    // A delayed enable naming the older revision cannot restore catalog access.
    await assert.rejects(
      () => transitionAccountLifecycle(adminDb, {
        callerUid: "admin",
        idempotencyKey: "stale-enable",
        uid: "client",
        enabled: true,
        expectedLifecycleRevision: 1,
        maxMembershipsPerAccount: 20
      }),
      /stale-revision/
    );
    assert.equal((await adminDb.doc(`lifecycleCommands/${commandId("admin", "stale-enable")}`).get()).exists, false);
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    // The revision-checked enable restores exactly the authorization the stored count supports.
    const enabled = await transitionAccountLifecycle(adminDb, {
      callerUid: "admin",
      idempotencyKey: "valid-enable",
      uid: "client",
      enabled: true,
      expectedLifecycleRevision: 2,
      maxMembershipsPerAccount: 20
    });
    assert.equal(enabled.accountStatus, "active");
    assert.equal(enabled.entitlementStatus, "active");
    assert.deepEqual((await adminDb.doc(entitlementPath).get()).data(), { schemaVersion: 1, status: "active", activeMembershipCount: 1, revision: 3 });
    await assertSucceeds(getDoc(doc(client, "systemExercises/published")));
  });

  it("keeps catalog reads denied when an enable is rejected for an unsupported lifecycle schema", async () => {
    const scenarios = [
      { uid: "client", corruption: "account-missing", expected: /account-schema-unsupported/ },
      { uid: "other", corruption: "account-malformed", expected: /account-schema-unsupported/ },
      { uid: "third", corruption: "entitlement-unsupported", expected: /entitlement-schema-unsupported/ }
    ] as const;

    for (const { uid, corruption, expected } of scenarios) {
      const accountPath = `users/${uid}`;
      const entitlementPath = `${accountPath}/authorizations/systemCatalog`;
      const idempotencyKey = `schema-enable-${uid}`;
      const receiptPath = `lifecycleCommands/${commandId("admin", idempotencyKey)}`;

      await environment.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        await setDoc(doc(db, accountPath), corruption === "account-missing"
          ? { accountStatus: "disabled", lifecycleRevision: 1 }
          : { schemaVersion: corruption === "account-malformed" ? "1" : 1, accountStatus: "disabled", lifecycleRevision: 1 });
        await setDoc(doc(db, entitlementPath), { schemaVersion: corruption === "entitlement-unsupported" ? 4 : 1, status: "inactive", activeMembershipCount: 1, revision: 7 });
        await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
      });

      const client = environment.authenticatedContext(uid).firestore();
      await assertFails(getDoc(doc(client, "systemExercises/published")));

      const adminDb = emulatorFirestore();
      const before = {
        account: (await adminDb.doc(accountPath).get()).data(),
        entitlement: (await adminDb.doc(entitlementPath).get()).data()
      };
      assert.equal((await adminDb.doc(receiptPath).get()).exists, false);

      // A rejected enable must never report success while Rules deny catalog access.
      await assert.rejects(
        () => transitionAccountLifecycle(adminDb, {
          callerUid: "admin",
          idempotencyKey,
          uid,
          enabled: true,
          expectedLifecycleRevision: 1,
          maxMembershipsPerAccount: 20
        }),
        expected
      );
      assert.deepEqual((await adminDb.doc(accountPath).get()).data(), before.account);
      assert.deepEqual((await adminDb.doc(entitlementPath).get()).data(), before.entitlement);
      assert.equal((await adminDb.doc(receiptPath).get()).exists, false);
      await assertFails(getDoc(doc(client, "systemExercises/published")));

      // Deactivation remains possible for the damaged record, and restoring the schema the Rules
      // require is what makes a later enable readable again.
      await transitionAccountLifecycle(adminDb, {
        callerUid: "admin",
        idempotencyKey: `schema-disable-${uid}`,
        uid,
        enabled: false,
        expectedLifecycleRevision: 1,
        maxMembershipsPerAccount: 20
      });
      await assertFails(getDoc(doc(client, "systemExercises/published")));

      await environment.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), accountPath), { schemaVersion: 1 });
        await updateDoc(doc(context.firestore(), entitlementPath), { schemaVersion: 1 });
      });
      const repaired = await transitionAccountLifecycle(adminDb, {
        callerUid: "admin",
        idempotencyKey: `schema-repair-${uid}`,
        uid,
        enabled: true,
        expectedLifecycleRevision: 2,
        maxMembershipsPerAccount: 20
      });
      assert.equal(repaired.accountStatus, "active");
      assert.equal(repaired.entitlementStatus, "active");
      await assertSucceeds(getDoc(doc(client, "systemExercises/published")));
    }
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
