import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, describe, it } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from "firebase/firestore";
import { deleteObject, ref, uploadString } from "firebase/storage";
import { publishAssignment } from "../src/assigned-program.js";
import { transitionAccountLifecycle, transitionMembership, transitionWorkspace } from "../src/catalog.js";
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

  it("keeps catalog reads denied after a partial deactivation while the schema stays damaged", async () => {
    const adminDb = emulatorFirestore();
    // Two contributions for one account in two active workspaces, with a published exercise that
    // Rules authorize only through a schema-current active account and entitlement.
    const seedPartial = async (uid: string, workspaceA: string, workspaceB: string, entitlementStatus: string): Promise<void> => {
      await environment.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        await setDoc(doc(db, `users/${uid}`), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
        await setDoc(doc(db, `users/${uid}/authorizations/systemCatalog`), { schemaVersion: 1, status: entitlementStatus, activeMembershipCount: 2, revision: 1 });
        for (const workspaceId of [workspaceA, workspaceB]) {
          await setDoc(doc(db, `workspaces/${workspaceId}`), { schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 1, catalogContributionCount: 1 });
          await setDoc(doc(db, `workspaces/${workspaceId}/memberships/${uid}`), { schemaVersion: 1, workspaceId, userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: true });
        }
        await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
      });
    };
    const revokeOne = (uid: string, workspaceId: string, idempotencyKey: string) => transitionMembership(adminDb, {
      callerUid: "admin",
      idempotencyKey,
      workspaceId,
      uid,
      role: "client",
      nextStatus: "revoked",
      expectedRevision: 1,
      expectedWorkspaceRevision: 1,
      maxMembershipsPerAccount: 20,
      maxMembershipsPerWorkspace: 20
    });

    // Damaging the account schema and, separately, the entitlement schema must not block the
    // removal — and must never leave the damaged record readable.
    for (const damageAccountSchema of [true, false]) {
      const suffix = damageAccountSchema ? "account" : "entitlement";
      const uid = `partial_${suffix}`;
      const workspaceA = `ws_partial_${suffix}_a`;
      const workspaceB = `ws_partial_${suffix}_b`;
      await seedPartial(uid, workspaceA, workspaceB, "active");
      const client = environment.authenticatedContext(uid).firestore();
      await assertSucceeds(getDoc(doc(client, "systemExercises/published")));
      await environment.withSecurityRulesDisabled(async (context) => {
        const path = damageAccountSchema ? `users/${uid}` : `users/${uid}/authorizations/systemCatalog`;
        await updateDoc(doc(context.firestore(), path), { schemaVersion: 5 });
      });

      const revoked = await revokeOne(uid, workspaceA, `revoke-${suffix}`);
      assert.equal(revoked.activeMembershipCount, 1);
      assert.equal(revoked.entitlementStatus, "active");
      const entitlement = await adminDb.doc(`users/${uid}/authorizations/systemCatalog`).get();
      assert.equal(entitlement.get("activeMembershipCount"), 1);
      assert.equal(entitlement.get("status"), "active");
      assert.equal(entitlement.get("schemaVersion"), damageAccountSchema ? 1 : 5);
      assert.equal((await adminDb.doc(`workspaces/${workspaceB}/memberships/${uid}`).get()).get("catalogContributionActive"), true);

      // A positive count and an "active" status grant nothing while the schema is damaged.
      await assertFails(getDoc(doc(client, "systemExercises/published")));

      // A separate trusted repair restores exactly the readable state the remaining contribution
      // implies: the removal itself neither repaired nor activated anything.
      await environment.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        await updateDoc(doc(db, `users/${uid}`), { schemaVersion: 1 });
        await updateDoc(doc(db, `users/${uid}/authorizations/systemCatalog`), { schemaVersion: 1 });
      });
      await assertSucceeds(getDoc(doc(client, "systemExercises/published")));
    }

    // A removal never activates a drifted entitlement: a stored "inactive" status stays inactive
    // even after the schema is repaired, so only a genuine addition grants access again.
    const driftUid = "partial_drift";
    await seedPartial(driftUid, "ws_partial_drift_a", "ws_partial_drift_b", "inactive");
    const driftClient = environment.authenticatedContext(driftUid).firestore();
    await assertFails(getDoc(doc(driftClient, "systemExercises/published")));
    await environment.withSecurityRulesDisabled(async (context) => {
      await updateDoc(doc(context.firestore(), `users/${driftUid}/authorizations/systemCatalog`), { schemaVersion: 5 });
    });
    const driftRevoked = await revokeOne(driftUid, "ws_partial_drift_a", "revoke-drift");
    assert.equal(driftRevoked.activeMembershipCount, 1);
    assert.equal(driftRevoked.entitlementStatus, "inactive");
    assert.equal((await adminDb.doc(`users/${driftUid}/authorizations/systemCatalog`).get()).get("status"), "inactive");
    await environment.withSecurityRulesDisabled(async (context) => {
      await updateDoc(doc(context.firestore(), `users/${driftUid}/authorizations/systemCatalog`), { schemaVersion: 1 });
    });
    await assertFails(getDoc(doc(driftClient, "systemExercises/published")));

    // Only the genuine addition — re-activating the revoked membership — grants catalog access.
    await transitionMembership(adminDb, {
      callerUid: "admin",
      idempotencyKey: "reactivate-drift",
      workspaceId: "ws_partial_drift_a",
      uid: driftUid,
      role: "client",
      nextStatus: "active",
      expectedRevision: 2,
      expectedWorkspaceRevision: 2,
      maxMembershipsPerAccount: 20,
      maxMembershipsPerWorkspace: 20
    });
    assert.equal((await adminDb.doc(`users/${driftUid}/authorizations/systemCatalog`).get()).get("status"), "active");
    await assertSucceeds(getDoc(doc(driftClient, "systemExercises/published")));
  });

  it("keeps catalog reads denied when a zero-delta revocation preserves a drifted inactive entitlement", async () => {
    const adminDb = emulatorFirestore();
    const uid = "neutral_rules";
    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(doc(db, `users/${uid}`), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
      // Drifted state: a positive count contributed elsewhere with an inactive stored status.
      await setDoc(doc(db, `users/${uid}/authorizations/systemCatalog`), { schemaVersion: 1, status: "inactive", activeMembershipCount: 1, revision: 5 });
      await setDoc(doc(db, "workspaces/ws_neutral_contributing"), { schemaVersion: 1, status: "active", membershipRevision: 2, activeRosterCount: 1, catalogContributionCount: 1 });
      await setDoc(doc(db, `workspaces/ws_neutral_contributing/memberships/${uid}`), { schemaVersion: 1, workspaceId: "ws_neutral_contributing", userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: true });
      await setDoc(doc(db, "workspaces/ws_neutral_suspended"), { schemaVersion: 1, status: "suspended", membershipRevision: 3, activeRosterCount: 1, catalogContributionCount: 0 });
      await setDoc(doc(db, `workspaces/ws_neutral_suspended/memberships/${uid}`), { schemaVersion: 1, workspaceId: "ws_neutral_suspended", userId: uid, role: "client", status: "active", revision: 3, catalogContributionActive: false });
      await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
    });
    const client = environment.authenticatedContext(uid).firestore();
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    // Revoking the non-contributing relationship in the suspended workspace is a zero contribution
    // delta: it must succeed, keep the positive count, and leave the entitlement inactive.
    const revoked = await transitionMembership(adminDb, {
      callerUid: "admin",
      idempotencyKey: "neutral-rules-revoke",
      workspaceId: "ws_neutral_suspended",
      uid,
      role: "client",
      nextStatus: "revoked",
      expectedRevision: 3,
      expectedWorkspaceRevision: 3,
      maxMembershipsPerAccount: 20,
      maxMembershipsPerWorkspace: 20
    });
    assert.equal(revoked.activeMembershipCount, 1);
    assert.equal(revoked.entitlementStatus, "inactive");
    const entitlement = await adminDb.doc(`users/${uid}/authorizations/systemCatalog`).get();
    assert.equal(entitlement.get("status"), "inactive");
    assert.equal(entitlement.get("activeMembershipCount"), 1);
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    // Only a genuine contribution addition grants catalog access again.
    await environment.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), "workspaces/ws_neutral_new"), { schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 0, catalogContributionCount: 0 });
    });
    const activated = await transitionMembership(adminDb, {
      callerUid: "admin",
      idempotencyKey: "neutral-rules-activate",
      workspaceId: "ws_neutral_new",
      uid,
      role: "client",
      nextStatus: "active",
      expectedRevision: 0,
      expectedWorkspaceRevision: 1,
      maxMembershipsPerAccount: 20,
      maxMembershipsPerWorkspace: 20
    });
    assert.equal(activated.activeMembershipCount, 2);
    assert.equal(activated.entitlementStatus, "active");
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

  it("authorizes the documented assignment-header collection query and keeps cross-account denial", async () => {
    await seedEligibleAssignment();
    const collectionPath = "users/client/workspaces/ws/assignedPrograms";
    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(doc(db, `${collectionPath}/asg-ready-2`), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg-ready-2", snapshotId: "content", lifecycleState: "ready", accessStatus: "active", accessExpiresAt: new Date("2030-01-01T00:00:00Z") });
      await setDoc(doc(db, `${collectionPath}/asg-terminal`), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg-terminal", snapshotId: "content", lifecycleState: "terminal", accessStatus: "replaced", accessExpiresAt: new Date("2030-01-01T00:00:00Z") });
      await setDoc(doc(db, `${collectionPath}/asg-terminal/snapshots/content`), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg-terminal", snapshotId: "content", title: "Retired copy" });
      await setDoc(doc(db, "users/other"), { schemaVersion: 1, accountStatus: "active" });
    });

    // The trusted assignment lifecycle is the only writer of this collection and always creates a
    // header whose identity equals its document path — the invariant the path-bound list relies on.
    const adminDb = emulatorFirestore();
    await publishAssignment(adminDb, {
      callerUid: "trainer",
      idempotencyKey: "publish-trusted-header",
      uid: "client",
      workspaceId: "ws",
      trainerId: "trainer",
      assignmentId: "asg-trusted",
      sourceTemplateId: "template",
      sourceVersionId: "v1",
      sourceVersionNumber: 1,
      sourceContentHash: "sha256:source",
      snapshotHash: "sha256:snapshot",
      accessExpiresAtMillis: Date.UTC(2030, 0, 1),
      workouts: [{ id: "day-one", position: 0, title: "Day one", exercises: [{ id: "squat", position: 0, displayName: "Squat", prescription: "3 x 5" }] }],
      plans: [],
      maxWrites: 200
    });
    const trustedHeader = await adminDb.doc(`${collectionPath}/asg-trusted`).get();
    assert.equal(trustedHeader.get("clientId"), "client");
    assert.equal(trustedHeader.get("workspaceId"), "ws");
    assert.equal(trustedHeader.get("assignmentId"), "asg-trusted");
    assert.equal(trustedHeader.get("schemaVersion"), 1);

    const client = environment.authenticatedContext("client").firestore();
    const other = environment.authenticatedContext("other").firestore();

    // The documented discovery query: the owner's exact account/workspace header collection.
    const headers = await assertSucceeds(getDocs(collection(client, collectionPath)));
    assert.deepEqual(headers.docs.map((document) => document.id).sort(), ["asg", "asg-ready-2", "asg-terminal", "asg-trusted"]);
    // A constrained query is authorized as well and returns only the matching safe headers.
    const ready = await assertSucceeds(getDocs(query(collection(client, collectionPath), where("lifecycleState", "==", "ready"))));
    assert.deepEqual(ready.docs.map((document) => document.id).sort(), ["asg", "asg-ready-2", "asg-trusted"]);

    // Cross-account enumeration stays denied in both directions.
    await assertFails(getDocs(collection(other, collectionPath)));
    await assertFails(getDocs(collection(client, "users/other/workspaces/ws/assignedPrograms")));

    // Direct gets keep the stricter per-document identity proof, including the safe terminal header.
    await assertSucceeds(getDoc(doc(client, `${collectionPath}/asg`)));
    await assertSucceeds(getDoc(doc(client, `${collectionPath}/asg-terminal`)));
    await assertFails(getDoc(doc(other, `${collectionPath}/asg`)));
    // Listing a terminal header never grants its content.
    await assertFails(getDoc(doc(client, `${collectionPath}/asg-terminal/snapshots/content`)));

    // An ineligible owner cannot list: disabled account, revoked membership, suspended workspace.
    await environment.withSecurityRulesDisabled(async (context) => {
      await updateDoc(doc(context.firestore(), "users/client"), { accountStatus: "disabled" });
    });
    await assertFails(getDocs(collection(client, collectionPath)));
    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await updateDoc(doc(db, "users/client"), { accountStatus: "active" });
      await updateDoc(doc(db, "workspaces/ws/memberships/client"), { status: "revoked" });
    });
    await assertFails(getDocs(collection(client, collectionPath)));
    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await updateDoc(doc(db, "workspaces/ws/memberships/client"), { status: "active" });
      await updateDoc(doc(db, "workspaces/ws"), { status: "suspended" });
    });
    await assertFails(getDocs(collection(client, collectionPath)));
    await environment.withSecurityRulesDisabled(async (context) => {
      await updateDoc(doc(context.firestore(), "workspaces/ws"), { status: "active" });
    });

    // Forged or malformed headers are never client-writable and stay denied to a direct get, while a
    // trusted header for the same owner stays readable.
    await assertFails(setDoc(doc(client, `${collectionPath}/asg-forged`), { schemaVersion: 1, clientId: "client", workspaceId: "ws", assignmentId: "asg-forged", snapshotId: "content", lifecycleState: "ready", accessStatus: "active", accessExpiresAt: new Date("2030-01-01T00:00:00Z") }));
    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(doc(db, `${collectionPath}/asg-forged`), { schemaVersion: 1, clientId: "other", workspaceId: "ws", assignmentId: "asg-forged", snapshotId: "content", lifecycleState: "ready", accessStatus: "active", accessExpiresAt: new Date("2030-01-01T00:00:00Z") });
      await setDoc(doc(db, `${collectionPath}/asg-malformed`), { clientId: "client", workspaceId: "ws" });
    });
    await assertFails(getDoc(doc(client, `${collectionPath}/asg-forged`)));
    await assertFails(getDoc(doc(client, `${collectionPath}/asg-malformed`)));
    await assertSucceeds(getDoc(doc(client, `${collectionPath}/asg-ready-2`)));
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
    await assertFails(uploadString(ref(ownerStorage, "spikeUploads/client/no-metadata"), "safe", "raw", { contentType: "text/plain" }));
    await assertFails(uploadString(ref(ownerStorage, "spikeUploads/client/too-big"), "x".repeat(1024 * 1024 + 1), "raw", { contentType: "text/plain", customMetadata: { ownerUid: "client" } }));

    // A delete carries no `request.resource`, so it is authorized by path/resource ownership alone:
    // the owner may remove its own object, while another account, a foreign path, and a path that is
    // not a single object beneath the owner stay denied.
    await assertSucceeds(uploadString(ref(ownerStorage, "spikeUploads/client/again"), "safe", "raw", { contentType: "text/plain", customMetadata: { ownerUid: "client" } }));
    await assertSucceeds(deleteObject(ref(ownerStorage, "spikeUploads/client/ok")));
    await assertFails(deleteObject(ref(otherStorage, "spikeUploads/client/again")));
    await assertFails(deleteObject(ref(ownerStorage, "spikeUploads/other/again")));
    await assertFails(deleteObject(ref(ownerStorage, "spikeUploads/client")));
    await environment.withSecurityRulesDisabled(async (context) => {
      // An object written by a trusted path without the owner metadata is still bound to its path.
      await uploadString(ref(context.storage(), "spikeUploads/client/trusted"), "trusted", "raw", { contentType: "text/plain" });
    });
    await assertFails(deleteObject(ref(otherStorage, "spikeUploads/client/trusted")));
    assert.ok(true);
  });

  it("keeps catalog reads denied when a damaged workspace blocks the contribution increase", async () => {
    const adminDb = emulatorFirestore();
    // An active workspace missing its schema: Rules reject it as a workspace, so activating a
    // membership there must not raise the entitlement or authorize global catalog reads.
    await environment.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(doc(db, "users/ws_damaged"), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
      await setDoc(doc(db, "users/ws_damaged/authorizations/systemCatalog"), { schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });
      await setDoc(doc(db, "workspaces/ws_missing_schema"), { status: "active", membershipRevision: 1, activeRosterCount: 0, catalogContributionCount: 0 });
      await setDoc(doc(db, "systemExercises/published"), { schemaVersion: 1, name: "Squat", status: "published" });
    });
    const client = environment.authenticatedContext("ws_damaged").firestore();
    await assertFails(getDoc(doc(client, "systemExercises/published")));
    const before = {
      workspace: (await adminDb.doc("workspaces/ws_missing_schema").get()).data(),
      entitlement: (await adminDb.doc("users/ws_damaged/authorizations/systemCatalog").get()).data(),
      receipts: (await adminDb.collection("lifecycleCommands").get()).size
    };
    await assert.rejects(
      () => transitionMembership(adminDb, {
        callerUid: "admin",
        idempotencyKey: "ws-damaged-activate",
        workspaceId: "ws_missing_schema",
        uid: "ws_damaged",
        role: "client",
        nextStatus: "active",
        expectedRevision: 0,
        expectedWorkspaceRevision: 1,
        maxMembershipsPerAccount: 20,
        maxMembershipsPerWorkspace: 20
      }),
      /workspace-schema-unsupported/
    );
    assert.deepEqual((await adminDb.doc("workspaces/ws_missing_schema").get()).data(), before.workspace, "rejected activation leaves the workspace unchanged");
    assert.deepEqual((await adminDb.doc("users/ws_damaged/authorizations/systemCatalog").get()).data(), before.entitlement, "rejected activation leaves the entitlement unchanged");
    assert.equal((await adminDb.collection("lifecycleCommands").get()).size, before.receipts, "rejected activation records no receipt");
    await assertFails(getDoc(doc(client, "systemExercises/published")));

    // After a trusted repair the identical activation commits and the read is authorized.
    await environment.withSecurityRulesDisabled(async (context) => {
      await updateDoc(doc(context.firestore(), "workspaces/ws_missing_schema"), { schemaVersion: 1 });
    });
    const activated = await transitionMembership(adminDb, {
      callerUid: "admin",
      idempotencyKey: "ws-damaged-activate",
      workspaceId: "ws_missing_schema",
      uid: "ws_damaged",
      role: "client",
      nextStatus: "active",
      expectedRevision: 0,
      expectedWorkspaceRevision: 1,
      maxMembershipsPerAccount: 20,
      maxMembershipsPerWorkspace: 20
    });
    assert.equal(activated.activeMembershipCount, 1);
    assert.equal(activated.entitlementStatus, "active");
    await assertSucceeds(getDoc(doc(client, "systemExercises/published")));
  });
});
