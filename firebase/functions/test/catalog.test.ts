import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { setAccountEnabled, transitionMembership, transitionWorkspace } from "../src/catalog.js";
import { emulatorFirestore } from "../src/environment.js";

const db = emulatorFirestore();

async function seed(uid: string, workspaceId: string): Promise<void> {
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}`), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
  batch.set(db.doc(`users/${uid}/authorizations/systemCatalog`), { schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });
  batch.set(db.doc(`workspaces/${workspaceId}`), { schemaVersion: 1, status: "active", membershipRevision: 1 });
  await batch.commit();
}

describe("system catalog entitlement lifecycle", () => {
  beforeEach(async () => {
    await db.recursiveDelete(db.collection("users"));
    await db.recursiveDelete(db.collection("workspaces"));
    await db.recursiveDelete(db.collection("lifecycleCommands"));
  });

  it("activates, replays, revokes, and restores without count drift", async () => {
    await seed("client", "ws");
    const activate = { callerUid: "admin", idempotencyKey: "activate", workspaceId: "ws", uid: "client", role: "client" as const, nextStatus: "active" as const, expectedRevision: 0, maxMembershipsPerAccount: 20 };
    assert.equal((await transitionMembership(db, activate)).activeMembershipCount, 1);
    assert.equal((await transitionMembership(db, activate)).replayed, true);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 1);
    assert.equal((await transitionMembership(db, { ...activate, idempotencyKey: "revoke", nextStatus: "revoked", expectedRevision: 1 })).activeMembershipCount, 0);
    assert.equal((await transitionMembership(db, { ...activate, idempotencyKey: "restore", expectedRevision: 2 })).activeMembershipCount, 1);
  });

  it("atomically suspends/restores a bounded workspace and disables an account", async () => {
    await seed("client", "ws");
    await transitionMembership(db, { callerUid: "admin", idempotencyKey: "activate", workspaceId: "ws", uid: "client", role: "client", nextStatus: "active", expectedRevision: 0, maxMembershipsPerAccount: 20 });
    const revision = (await db.doc("workspaces/ws").get()).get("membershipRevision") as number;
    const suspended = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "suspend", workspaceId: "ws", nextStatus: "suspended", expectedMembershipRevision: revision, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 20, maxWrites: 200 });
    assert.equal(suspended.affected, 1);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 0);
    const restoreRevision = (await db.doc("workspaces/ws").get()).get("membershipRevision") as number;
    const restored = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "restore", workspaceId: "ws", nextStatus: "active", expectedMembershipRevision: restoreRevision, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 20, maxWrites: 200 });
    assert.equal((await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "restore", workspaceId: "ws", nextStatus: "active", expectedMembershipRevision: restoreRevision, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 20, maxWrites: 200 })).replayed, true);
    assert.equal(restored.replayed, false);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 1);
    await setAccountEnabled(db, "client", false);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("status"), "inactive");
    await setAccountEnabled(db, "client", true);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("status"), "active");
  });

  it("rejects unsupported fan-out without partial state", async () => {
    await seed("a", "ws");
    await seed("b", "ws");
    await transitionMembership(db, { callerUid: "admin", idempotencyKey: "a", workspaceId: "ws", uid: "a", role: "client", nextStatus: "active", expectedRevision: 0, maxMembershipsPerAccount: 20 });
    await transitionMembership(db, { callerUid: "admin", idempotencyKey: "b", workspaceId: "ws", uid: "b", role: "client", nextStatus: "active", expectedRevision: 0, maxMembershipsPerAccount: 20 });
    const workspace = await db.doc("workspaces/ws").get();
    await assert.rejects(() => transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "too-large", workspaceId: "ws", nextStatus: "suspended", expectedMembershipRevision: workspace.get("membershipRevision"), maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 1, maxWrites: 200 }), /bound/);
    assert.equal((await db.doc("workspaces/ws").get()).get("status"), "active");
  });

  it("restores below and exactly at the account cap, but rejects above it atomically", async () => {
    for (const [uid, startingCount, cap] of [["below", 18, 20], ["exact", 19, 20]] as const) {
      await seed(uid, `ws_${uid}`);
      await db.doc(`workspaces/ws_${uid}`).update({ status: "suspended" });
      await db.doc(`workspaces/ws_${uid}/memberships/${uid}`).set({ schemaVersion: 1, workspaceId: `ws_${uid}`, userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: false });
      await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ activeMembershipCount: startingCount, status: "active" });
      const result = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: `restore-${uid}`, workspaceId: `ws_${uid}`, nextStatus: "active", expectedMembershipRevision: 1, maxMembershipsPerAccount: cap, maxMembershipsPerWorkspace: 20, maxWrites: 200 });
      assert.equal(result.affected, 1);
      assert.equal((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).get("activeMembershipCount"), startingCount + 1);
      assert.equal((await db.doc(`workspaces/ws_${uid}/memberships/${uid}`).get()).get("catalogContributionActive"), true);
    }

    await seed("above", "ws_above");
    await db.doc("workspaces/ws_above").update({ status: "suspended" });
    await db.doc("workspaces/ws_above/memberships/above").set({ schemaVersion: 1, workspaceId: "ws_above", userId: "above", role: "client", status: "active", revision: 1, catalogContributionActive: false });
    await db.doc("users/above/authorizations/systemCatalog").update({ activeMembershipCount: 20, status: "active", revision: 7 });
    const workspaceBefore = (await db.doc("workspaces/ws_above").get()).data();
    const membershipBefore = (await db.doc("workspaces/ws_above/memberships/above").get()).data();
    const entitlementBefore = (await db.doc("users/above/authorizations/systemCatalog").get()).data();
    await assert.rejects(
      () => transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "restore-above", workspaceId: "ws_above", nextStatus: "active", expectedMembershipRevision: 1, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 20, maxWrites: 200 }),
      /membership-bound-violated/
    );
    assert.deepEqual((await db.doc("workspaces/ws_above").get()).data(), workspaceBefore);
    assert.deepEqual((await db.doc("workspaces/ws_above/memberships/above").get()).data(), membershipBefore);
    assert.deepEqual((await db.doc("users/above/authorizations/systemCatalog").get()).data(), entitlementBefore);
    assert.equal((await db.collection("lifecycleCommands").where("commandKind", "==", "workspace-transition").get()).size, 2);
  });
});
