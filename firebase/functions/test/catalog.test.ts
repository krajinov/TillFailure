import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { MembershipTransition, setAccountEnabled, transitionMembership, transitionWorkspace } from "../src/catalog.js";
import { emulatorFirestore } from "../src/environment.js";

const db = emulatorFirestore();

async function seed(uid: string, workspaceId: string): Promise<void> {
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}`), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
  batch.set(db.doc(`users/${uid}/authorizations/systemCatalog`), { schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });
  batch.set(db.doc(`workspaces/${workspaceId}`), { schemaVersion: 1, status: "active", membershipRevision: 1, activeMembershipCount: 0 });
  await batch.commit();
}

function activation(uid: string, workspaceId: string, idempotencyKey: string, expectedRevision: number, expectedWorkspaceRevision: number, overrides: Partial<MembershipTransition> = {}): MembershipTransition {
  return {
    callerUid: "admin",
    idempotencyKey,
    workspaceId,
    uid,
    role: "client",
    nextStatus: "active",
    expectedRevision,
    expectedWorkspaceRevision,
    maxMembershipsPerAccount: 20,
    maxMembershipsPerWorkspace: 20,
    ...overrides
  };
}

describe("system catalog entitlement lifecycle", () => {
  beforeEach(async () => {
    await db.recursiveDelete(db.collection("users"));
    await db.recursiveDelete(db.collection("workspaces"));
    await db.recursiveDelete(db.collection("lifecycleCommands"));
  });

  it("activates, replays, revokes, and restores without count drift", async () => {
    await seed("client", "ws");
    const activate = activation("client", "ws", "activate", 0, 1);
    assert.equal((await transitionMembership(db, activate)).activeMembershipCount, 1);
    assert.equal((await transitionMembership(db, activate)).replayed, true);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 1);
    assert.equal((await db.doc("workspaces/ws").get()).get("activeMembershipCount"), 1);
    assert.equal((await db.doc("workspaces/ws").get()).get("membershipRevision"), 2);
    assert.equal((await transitionMembership(db, activation("client", "ws", "revoke", 1, 2, { nextStatus: "revoked" }))).activeMembershipCount, 0);
    assert.equal((await db.doc("workspaces/ws").get()).get("activeMembershipCount"), 0);
    assert.equal((await transitionMembership(db, activation("client", "ws", "restore", 2, 3))).activeMembershipCount, 1);
    assert.equal((await db.doc("workspaces/ws").get()).get("activeMembershipCount"), 1);
  });

  it("atomically suspends/restores a bounded workspace and disables an account", async () => {
    await seed("client", "ws");
    await transitionMembership(db, activation("client", "ws", "activate", 0, 1));
    const revision = (await db.doc("workspaces/ws").get()).get("membershipRevision") as number;
    const suspended = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "suspend", workspaceId: "ws", nextStatus: "suspended", expectedMembershipRevision: revision, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 20, maxWrites: 200 });
    assert.equal(suspended.affected, 1);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 0);
    assert.equal((await db.doc("workspaces/ws").get()).get("activeMembershipCount"), 0);
    const restoreRevision = (await db.doc("workspaces/ws").get()).get("membershipRevision") as number;
    const restored = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "restore", workspaceId: "ws", nextStatus: "active", expectedMembershipRevision: restoreRevision, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 20, maxWrites: 200 });
    assert.equal((await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "restore", workspaceId: "ws", nextStatus: "active", expectedMembershipRevision: restoreRevision, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 20, maxWrites: 200 })).replayed, true);
    assert.equal(restored.replayed, false);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 1);
    assert.equal((await db.doc("workspaces/ws").get()).get("activeMembershipCount"), 1);
    await setAccountEnabled(db, "client", false);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("status"), "inactive");
    await setAccountEnabled(db, "client", true);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("status"), "active");
  });

  it("rejects unsupported fan-out without partial state", async () => {
    await seed("a", "ws");
    await seed("b", "ws");
    await transitionMembership(db, activation("a", "ws", "a", 0, 1));
    await transitionMembership(db, activation("b", "ws", "b", 0, 2));
    const workspace = await db.doc("workspaces/ws").get();
    assert.equal(workspace.get("activeMembershipCount"), 2);
    await assert.rejects(() => transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "too-large", workspaceId: "ws", nextStatus: "suspended", expectedMembershipRevision: workspace.get("membershipRevision"), maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 1, maxWrites: 200 }), /bound/);
    assert.equal((await db.doc("workspaces/ws").get()).get("status"), "active");
    assert.equal((await db.doc("workspaces/ws").get()).get("activeMembershipCount"), 2);
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

  it("enforces the workspace membership cap on activation", async () => {
    for (const uid of ["m1", "m2", "m3"]) await seed(uid, "ws_cap");
    const capped = (uid: string, key: string, expectedWorkspaceRevision: number) =>
      activation(uid, "ws_cap", key, 0, expectedWorkspaceRevision, { maxMembershipsPerWorkspace: 2 });
    assert.equal((await transitionMembership(db, capped("m1", "act-m1", 1))).activeMembershipCount, 1);
    assert.equal((await transitionMembership(db, capped("m2", "act-m2", 2))).activeMembershipCount, 1);
    assert.equal((await db.doc("workspaces/ws_cap").get()).get("activeMembershipCount"), 2);

    const workspaceBefore = (await db.doc("workspaces/ws_cap").get()).data();
    const accountBefore = (await db.doc("users/m3").get()).data();
    const entitlementBefore = (await db.doc("users/m3/authorizations/systemCatalog").get()).data();
    await assert.rejects(() => transitionMembership(db, capped("m3", "act-m3", 3)), /workspace-membership-bound-violated/);
    assert.deepEqual((await db.doc("workspaces/ws_cap").get()).data(), workspaceBefore);
    assert.deepEqual((await db.doc("users/m3").get()).data(), accountBefore);
    assert.deepEqual((await db.doc("users/m3/authorizations/systemCatalog").get()).data(), entitlementBefore);
    assert.equal((await db.doc("workspaces/ws_cap/memberships/m3").get()).exists, false);
    assert.equal((await db.collection("lifecycleCommands").where("commandKind", "==", "membership-transition").get()).size, 2);
  });

  it("rejects account-cap and workspace-cap violations independently", async () => {
    await seed("a1", "ws_a");
    await seed("a2", "ws_a");
    await transitionMembership(db, activation("a1", "ws_a", "a1", 0, 1, { maxMembershipsPerWorkspace: 1 }));
    await assert.rejects(
      () => transitionMembership(db, activation("a2", "ws_a", "a2", 0, 2, { maxMembershipsPerWorkspace: 1 })),
      /workspace-membership-bound-violated/
    );
    assert.equal((await db.doc("users/a2/authorizations/systemCatalog").get()).get("activeMembershipCount"), 0);
    assert.equal((await db.doc("workspaces/ws_a").get()).get("activeMembershipCount"), 1);

    await seed("b1", "ws_b");
    await db.doc("users/b1/authorizations/systemCatalog").update({ activeMembershipCount: 20, status: "active" });
    await assert.rejects(() => transitionMembership(db, activation("b1", "ws_b", "b1", 0, 1, { maxMembershipsPerWorkspace: 5 })), /membership-bound-violated/);
    assert.equal((await db.doc("workspaces/ws_b").get()).get("activeMembershipCount"), 0);
    assert.equal((await db.doc("workspaces/ws_b/memberships/b1").get()).exists, false);
  });

  it("serializes activation through the workspace revision and fails closed on malformed counts", async () => {
    await seed("s1", "ws_rev");
    await assert.rejects(() => transitionMembership(db, activation("s1", "ws_rev", "stale-ws", 0, 99)), /stale-workspace-revision/);
    assert.equal((await db.doc("workspaces/ws_rev/memberships/s1").get()).exists, false);
    assert.equal((await db.doc("workspaces/ws_rev").get()).get("membershipRevision"), 1);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 0);

    const rejectsMalformed = async (count: unknown, pattern: RegExp, key: string) => {
      await db.doc("workspaces/ws_rev").update({ activeMembershipCount: count });
      await assert.rejects(() => transitionMembership(db, activation("s1", "ws_rev", key, 0, 1, { maxMembershipsPerWorkspace: 2 })), pattern);
      assert.equal((await db.doc("workspaces/ws_rev/memberships/s1").get()).exists, false);
    };
    await rejectsMalformed("2", /workspace-count-malformed/, "string-count");
    await rejectsMalformed(2.5, /workspace-count-malformed/, "fractional-count");
    await rejectsMalformed(-1, /workspace-membership-bound-violated/, "negative-count");
    await rejectsMalformed(3, /workspace-membership-bound-violated/, "oversized-count");

    await db.doc("workspaces/ws_rev").update({ activeMembershipCount: 0 });
    assert.equal((await transitionMembership(db, activation("s1", "ws_rev", "finally", 0, 1, { maxMembershipsPerWorkspace: 2 }))).activeMembershipCount, 1);
    assert.equal((await db.doc("workspaces/ws_rev").get()).get("activeMembershipCount"), 1);
  });

  it("keeps atomic suspension and restoration possible for a workspace filled to its cap", async () => {
    for (const uid of ["c1", "c2", "c3"]) await seed(uid, "ws_full");
    let workspaceRevision = 1;
    for (const uid of ["c1", "c2", "c3"]) {
      await transitionMembership(db, activation(uid, "ws_full", `fill-${uid}`, 0, workspaceRevision, { maxMembershipsPerWorkspace: 3 }));
      workspaceRevision += 1;
    }
    assert.equal((await db.doc("workspaces/ws_full").get()).get("activeMembershipCount"), 3);
    const suspended = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "suspend-full", workspaceId: "ws_full", nextStatus: "suspended", expectedMembershipRevision: workspaceRevision, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 3, maxWrites: 200 });
    assert.equal(suspended.affected, 3);
    assert.equal((await db.doc("workspaces/ws_full").get()).get("activeMembershipCount"), 0);
    for (const uid of ["c1", "c2", "c3"]) {
      assert.equal((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).get("activeMembershipCount"), 0);
      assert.equal((await db.doc(`workspaces/ws_full/memberships/${uid}`).get()).get("catalogContributionActive"), false);
    }
    const restored = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "restore-full", workspaceId: "ws_full", nextStatus: "active", expectedMembershipRevision: workspaceRevision + 1, maxMembershipsPerAccount: 20, maxMembershipsPerWorkspace: 3, maxWrites: 200 });
    assert.equal(restored.affected, 3);
    assert.equal((await db.doc("workspaces/ws_full").get()).get("activeMembershipCount"), 3);
    for (const uid of ["c1", "c2", "c3"]) assert.equal((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).get("activeMembershipCount"), 1);
  });
});
