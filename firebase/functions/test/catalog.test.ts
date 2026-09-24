import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { FieldValue } from "firebase-admin/firestore";
import { AccountLifecycleResult, AccountLifecycleTransition, MembershipTransition, transitionAccountLifecycle, transitionMembership, transitionWorkspace } from "../src/catalog.js";
import { emulatorFirestore } from "../src/environment.js";
import { commandId } from "../src/hashing.js";

const db = emulatorFirestore();

// Emulator fixture: the workspace carries two distinct server-owned counters.
// `activeRosterCount` counts active relationships (memberships with status "active"),
// in an active or suspended workspace, and is bounded by maxMembershipsPerWorkspace.
// `catalogContributionCount` counts catalog contributions, a subset of the roster that is
// zero while the workspace is suspended.
async function seed(uid: string, workspaceId: string, workspaceStatus: "active" | "suspended" = "active"): Promise<void> {
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}`), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
  batch.set(db.doc(`users/${uid}/authorizations/systemCatalog`), { schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });
  batch.set(db.doc(`workspaces/${workspaceId}`), { schemaVersion: 1, status: workspaceStatus, membershipRevision: 1, activeRosterCount: 0, catalogContributionCount: 0 });
  await batch.commit();
}

interface WorkspaceCounts {
  readonly roster: number;
  readonly contributions: number;
  readonly revision: number;
  readonly status: string;
}

async function workspaceCounts(workspaceId: string): Promise<WorkspaceCounts> {
  const workspace = await db.doc(`workspaces/${workspaceId}`).get();
  return {
    roster: workspace.get("activeRosterCount") as number,
    contributions: workspace.get("catalogContributionCount") as number,
    revision: workspace.get("membershipRevision") as number,
    status: workspace.get("status") as string
  };
}

async function accountCount(uid: string): Promise<number> {
  return (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).get("activeMembershipCount") as number;
}

function workspaceTransition(workspaceId: string, idempotencyKey: string, expectedMembershipRevision: number, nextStatus: "active" | "suspended", maxMembershipsPerWorkspace = 20, maxMembershipsPerAccount = 20) {
  return { callerUid: "admin", idempotencyKey, workspaceId, nextStatus, expectedMembershipRevision, maxMembershipsPerAccount, maxMembershipsPerWorkspace, maxWrites: 200 };
}

// Account lifecycle fixtures: the account stores its lifecycle revision and the entitlement
// document may hold a malformed or out-of-range count on purpose, exactly as a corrupt or
// legacy record would.
async function seedAccount(uid: string, entitlement: Record<string, unknown>): Promise<void> {
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}`), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
  batch.set(db.doc(`users/${uid}/authorizations/systemCatalog`), { schemaVersion: 1, revision: 1, ...entitlement });
  await batch.commit();
}

function accountLifecycle(uid: string, enabled: boolean, expectedLifecycleRevision: number, overrides: Partial<AccountLifecycleTransition> = {}): AccountLifecycleTransition {
  return {
    callerUid: "admin",
    idempotencyKey: `${enabled ? "enable" : "disable"}-${uid}`,
    uid,
    enabled,
    expectedLifecycleRevision,
    maxMembershipsPerAccount: 20,
    ...overrides
  };
}

async function accountState(uid: string): Promise<{ accountStatus: unknown; lifecycleRevision: unknown }> {
  const snapshot = await db.doc(`users/${uid}`).get();
  return { accountStatus: snapshot.get("accountStatus"), lifecycleRevision: snapshot.get("lifecycleRevision") };
}

async function entitlementState(uid: string): Promise<{ status: unknown; activeMembershipCount: unknown; revision: unknown }> {
  const snapshot = await db.doc(`users/${uid}/authorizations/systemCatalog`).get();
  return { status: snapshot.get("status"), activeMembershipCount: snapshot.get("activeMembershipCount"), revision: snapshot.get("revision") };
}

async function lifecycleSnapshot(uid: string): Promise<Record<string, unknown>> {
  return {
    account: JSON.stringify(await accountState(uid)),
    entitlement: JSON.stringify(await entitlementState(uid))
  };
}

// Full document comparison for assertions that must include every stored field, such as the
// lifecycle schema version.
async function lifecycleDocuments(uid: string): Promise<{ account: unknown; entitlement: unknown }> {
  return {
    account: (await db.doc(`users/${uid}`).get()).data(),
    entitlement: (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data()
  };
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
    assert.equal(await accountCount("client"), 1);
    assert.deepEqual(await workspaceCounts("ws"), { roster: 1, contributions: 1, revision: 2, status: "active" });
    assert.equal((await transitionMembership(db, activation("client", "ws", "revoke", 1, 2, { nextStatus: "revoked" }))).activeMembershipCount, 0);
    assert.deepEqual(await workspaceCounts("ws"), { roster: 0, contributions: 0, revision: 3, status: "active" });
    assert.equal((await transitionMembership(db, activation("client", "ws", "restore", 2, 3))).activeMembershipCount, 1);
    assert.deepEqual(await workspaceCounts("ws"), { roster: 1, contributions: 1, revision: 4, status: "active" });
    assert.equal(await accountCount("client"), 1);
  });

  it("atomically suspends/restores a bounded workspace and disables an account", async () => {
    await seed("client", "ws");
    await transitionMembership(db, activation("client", "ws", "activate", 0, 1));
    const suspended = await transitionWorkspace(db, workspaceTransition("ws", "suspend", 2, "suspended"));
    assert.equal(suspended.affected, 1);
    assert.equal(await accountCount("client"), 0);
    // Suspension disables entitlement contributions but preserves the active relationship roster.
    assert.deepEqual(await workspaceCounts("ws"), { roster: 1, contributions: 0, revision: 3, status: "suspended" });
    const restored = await transitionWorkspace(db, workspaceTransition("ws", "restore", 3, "active"));
    assert.equal((await transitionWorkspace(db, workspaceTransition("ws", "restore", 3, "active"))).replayed, true);
    assert.equal(restored.replayed, false);
    assert.equal(await accountCount("client"), 1);
    assert.deepEqual(await workspaceCounts("ws"), { roster: 1, contributions: 1, revision: 4, status: "active" });
    await transitionAccountLifecycle(db, accountLifecycle("client", false, 1));
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("status"), "inactive");
    await transitionAccountLifecycle(db, accountLifecycle("client", true, 2));
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("status"), "active");
  });

  it("rejects unsupported fan-out without partial state", async () => {
    await seed("a", "ws");
    await seed("b", "ws");
    await transitionMembership(db, activation("a", "ws", "a", 0, 1));
    await transitionMembership(db, activation("b", "ws", "b", 0, 2));
    assert.deepEqual(await workspaceCounts("ws"), { roster: 2, contributions: 2, revision: 3, status: "active" });
    await assert.rejects(() => transitionWorkspace(db, workspaceTransition("ws", "too-large", 3, "suspended", 1)), /workspace-roster-bound-violated/);
    assert.deepEqual(await workspaceCounts("ws"), { roster: 2, contributions: 2, revision: 3, status: "active" });
  });

  it("restores below and exactly at the account cap, but rejects above it atomically", async () => {
    for (const [uid, startingCount, cap] of [["zero", 0, 20], ["below", 18, 20], ["exact", 19, 20]] as const) {
      await seed(uid, `ws_${uid}`, "suspended");
      await db.doc(`workspaces/ws_${uid}`).update({ activeRosterCount: 1 });
      await db.doc(`workspaces/ws_${uid}/memberships/${uid}`).set({ schemaVersion: 1, workspaceId: `ws_${uid}`, userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: false });
      await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ activeMembershipCount: startingCount, status: "active" });
      const result = await transitionWorkspace(db, workspaceTransition(`ws_${uid}`, `restore-${uid}`, 1, "active", 20, cap));
      assert.equal(result.affected, 1);
      assert.equal(await accountCount(uid), startingCount + 1);
      assert.equal((await db.doc(`workspaces/ws_${uid}/memberships/${uid}`).get()).get("catalogContributionActive"), true);
      assert.deepEqual(await workspaceCounts(`ws_${uid}`), { roster: 1, contributions: 1, revision: 2, status: "active" });
    }

    await seed("above", "ws_above", "suspended");
    await db.doc("workspaces/ws_above").update({ activeRosterCount: 1 });
    await db.doc("workspaces/ws_above/memberships/above").set({ schemaVersion: 1, workspaceId: "ws_above", userId: "above", role: "client", status: "active", revision: 1, catalogContributionActive: false });
    await db.doc("users/above/authorizations/systemCatalog").update({ activeMembershipCount: 20, status: "active", revision: 7 });
    const workspaceBefore = (await db.doc("workspaces/ws_above").get()).data();
    const membershipBefore = (await db.doc("workspaces/ws_above/memberships/above").get()).data();
    const entitlementBefore = (await db.doc("users/above/authorizations/systemCatalog").get()).data();
    await assert.rejects(
      () => transitionWorkspace(db, workspaceTransition("ws_above", "restore-above", 1, "active")),
      /membership-bound-violated/
    );
    assert.deepEqual((await db.doc("workspaces/ws_above").get()).data(), workspaceBefore);
    assert.deepEqual((await db.doc("workspaces/ws_above/memberships/above").get()).data(), membershipBefore);
    assert.deepEqual((await db.doc("users/above/authorizations/systemCatalog").get()).data(), entitlementBefore);
    await seed("neg", "ws_neg", "suspended");
    await db.doc("workspaces/ws_neg").update({ activeRosterCount: 1 });
    await db.doc("workspaces/ws_neg/memberships/neg").set({ schemaVersion: 1, workspaceId: "ws_neg", userId: "neg", role: "client", status: "active", revision: 1, catalogContributionActive: false });
    await db.doc("users/neg/authorizations/systemCatalog").update({ activeMembershipCount: -1, status: "active", revision: 1 });
    await assert.rejects(
      () => transitionWorkspace(db, workspaceTransition("ws_neg", "restore-neg", 1, "active")),
      /membership-bound-violated/
    );
    assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "restore-neg")}`).get()).exists, false);
  });

  it("validates every existing entitlement before suspension and preserves all state on rejection", async () => {
    const rejectedCounts: ReadonlyArray<readonly [string, unknown]> = [
      ["above-cap", 21],
      ["negative", -1],
      ["string", "1"],
      ["fractional", 1.5]
    ];

    for (const [caseName, existingCount] of rejectedCounts) {
      const uid = `susp-${caseName}`;
      const workspaceId = `ws-susp-${caseName}`;
      const idempotencyKey = `suspend-${caseName}`;
      const workspaceRef = db.doc(`workspaces/${workspaceId}`);
      const membershipRef = db.doc(`workspaces/${workspaceId}/memberships/${uid}`);
      const accountRef = db.doc(`users/${uid}`);
      const entitlementRef = db.doc(`users/${uid}/authorizations/systemCatalog`);
      const receiptRef = db.doc(`lifecycleCommands/${commandId("admin", idempotencyKey)}`);

      await seed(uid, workspaceId, "active");
      await workspaceRef.update({ activeRosterCount: 1, catalogContributionCount: 1 });
      await membershipRef.set({
        schemaVersion: 1,
        workspaceId,
        userId: uid,
        role: "client",
        status: "active",
        revision: 1,
        catalogContributionActive: true
      });
      await entitlementRef.update({ activeMembershipCount: existingCount, status: "active", revision: 7 });

      const before = {
        workspace: (await workspaceRef.get()).data(),
        membership: (await membershipRef.get()).data(),
        account: (await accountRef.get()).data(),
        entitlement: (await entitlementRef.get()).data()
      };
      assert.equal((await receiptRef.get()).exists, false);

      await assert.rejects(
        () => transitionWorkspace(db, workspaceTransition(workspaceId, idempotencyKey, 1, "suspended")),
        /membership-bound-violated/
      );

      assert.deepEqual((await workspaceRef.get()).data(), before.workspace);
      assert.deepEqual((await membershipRef.get()).data(), before.membership);
      assert.deepEqual((await accountRef.get()).data(), before.account);
      assert.deepEqual((await entitlementRef.get()).data(), before.entitlement);
      assert.equal((await receiptRef.get()).exists, false);
    }

    for (const [caseName, existingCount, expectedCount, expectedStatus] of [
      ["at-cap", 20, 19, "active"],
      ["last-contribution", 1, 0, "inactive"]
    ] as const) {
      const uid = `valid-${caseName}`;
      const workspaceId = `ws-valid-${caseName}`;
      await seed(uid, workspaceId, "active");
      await db.doc(`workspaces/${workspaceId}`).update({ activeRosterCount: 1, catalogContributionCount: 1 });
      await db.doc(`workspaces/${workspaceId}/memberships/${uid}`).set({
        schemaVersion: 1,
        workspaceId,
        userId: uid,
        role: "client",
        status: "active",
        revision: 1,
        catalogContributionActive: true
      });
      await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ activeMembershipCount: existingCount, status: "active", revision: 1 });

      const result = await transitionWorkspace(db, workspaceTransition(workspaceId, `suspend-${caseName}`, 1, "suspended"));
      const entitlement = await db.doc(`users/${uid}/authorizations/systemCatalog`).get();
      assert.equal(result.affected, 1);
      assert.equal(entitlement.get("activeMembershipCount"), expectedCount);
      assert.equal(entitlement.get("status"), expectedStatus);
      assert.equal(entitlement.get("revision"), 2);
      assert.deepEqual(await workspaceCounts(workspaceId), { roster: 1, contributions: 0, revision: 2, status: "suspended" });
    }
  });

  it("enforces the workspace roster cap on activation", async () => {
    for (const uid of ["m1", "m2", "m3"]) await seed(uid, "ws_cap");
    const capped = (uid: string, key: string, expectedWorkspaceRevision: number) =>
      activation(uid, "ws_cap", key, 0, expectedWorkspaceRevision, { maxMembershipsPerWorkspace: 2 });
    assert.equal((await transitionMembership(db, capped("m1", "act-m1", 1))).activeMembershipCount, 1);
    assert.equal((await transitionMembership(db, capped("m2", "act-m2", 2))).activeMembershipCount, 1);
    assert.deepEqual(await workspaceCounts("ws_cap"), { roster: 2, contributions: 2, revision: 3, status: "active" });

    const workspaceBefore = (await db.doc("workspaces/ws_cap").get()).data();
    const accountBefore = (await db.doc("users/m3").get()).data();
    const entitlementBefore = (await db.doc("users/m3/authorizations/systemCatalog").get()).data();
    await assert.rejects(() => transitionMembership(db, capped("m3", "act-m3", 3)), /workspace-roster-bound-violated/);
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
      /workspace-roster-bound-violated/
    );
    assert.equal(await accountCount("a2"), 0);
    assert.deepEqual(await workspaceCounts("ws_a"), { roster: 1, contributions: 1, revision: 2, status: "active" });

    await seed("b1", "ws_b");
    await db.doc("users/b1/authorizations/systemCatalog").update({ activeMembershipCount: 20, status: "active" });
    await assert.rejects(() => transitionMembership(db, activation("b1", "ws_b", "b1", 0, 1, { maxMembershipsPerWorkspace: 5 })), /membership-bound-violated/);
    assert.deepEqual(await workspaceCounts("ws_b"), { roster: 0, contributions: 0, revision: 1, status: "active" });
    assert.equal((await db.doc("workspaces/ws_b/memberships/b1").get()).exists, false);
  });

  it("serializes activation through the workspace revision and fails closed on malformed counters", async () => {
    await seed("s1", "ws_rev");
    await assert.rejects(() => transitionMembership(db, activation("s1", "ws_rev", "stale-ws", 0, 99)), /stale-workspace-revision/);
    assert.equal((await db.doc("workspaces/ws_rev/memberships/s1").get()).exists, false);
    assert.deepEqual(await workspaceCounts("ws_rev"), { roster: 0, contributions: 0, revision: 1, status: "active" });
    assert.equal((await db.collection("lifecycleCommands").get()).size, 0);

    const rejectsMalformed = async (fields: Record<string, unknown>, pattern: RegExp, key: string) => {
      await db.doc("workspaces/ws_rev").update(fields);
      await assert.rejects(() => transitionMembership(db, activation("s1", "ws_rev", key, 0, 1, { maxMembershipsPerWorkspace: 2 })), pattern);
      assert.equal((await db.doc("workspaces/ws_rev/memberships/s1").get()).exists, false);
    };
    await rejectsMalformed({ activeRosterCount: "2" }, /workspace-roster-malformed/, "roster-string");
    await rejectsMalformed({ activeRosterCount: 2.5 }, /workspace-roster-malformed/, "roster-fractional");
    await rejectsMalformed({ activeRosterCount: -1 }, /workspace-roster-bound-violated/, "roster-negative");
    await rejectsMalformed({ activeRosterCount: 3 }, /workspace-roster-bound-violated/, "roster-oversized");
    await rejectsMalformed({ activeRosterCount: 1, catalogContributionCount: "1" }, /workspace-contribution-malformed/, "contribution-string");
    await rejectsMalformed({ activeRosterCount: 1, catalogContributionCount: -1 }, /workspace-contribution-bound-violated/, "contribution-negative");
    await rejectsMalformed({ activeRosterCount: 1, catalogContributionCount: 2 }, /workspace-contribution-bound-violated/, "contribution-exceeds-roster");

    await db.doc("workspaces/ws_rev").update({ activeRosterCount: 0, catalogContributionCount: 0 });
    assert.equal((await transitionMembership(db, activation("s1", "ws_rev", "finally", 0, 1, { maxMembershipsPerWorkspace: 2 }))).activeMembershipCount, 1);
    assert.deepEqual(await workspaceCounts("ws_rev"), { roster: 1, contributions: 1, revision: 2, status: "active" });
  });

  it("keeps atomic suspension and restoration possible for a workspace filled to its cap", async () => {
    for (const uid of ["c1", "c2", "c3"]) await seed(uid, "ws_full");
    let workspaceRevision = 1;
    for (const uid of ["c1", "c2", "c3"]) {
      await transitionMembership(db, activation(uid, "ws_full", `fill-${uid}`, 0, workspaceRevision, { maxMembershipsPerWorkspace: 3 }));
      workspaceRevision += 1;
    }
    assert.deepEqual(await workspaceCounts("ws_full"), { roster: 3, contributions: 3, revision: 4, status: "active" });
    const suspended = await transitionWorkspace(db, workspaceTransition("ws_full", "suspend-full", workspaceRevision, "suspended", 3));
    assert.equal(suspended.affected, 3);
    assert.deepEqual(await workspaceCounts("ws_full"), { roster: 3, contributions: 0, revision: 5, status: "suspended" });
    for (const uid of ["c1", "c2", "c3"]) {
      assert.equal(await accountCount(uid), 0);
      assert.equal((await db.doc(`workspaces/ws_full/memberships/${uid}`).get()).get("catalogContributionActive"), false);
    }
    const restored = await transitionWorkspace(db, workspaceTransition("ws_full", "restore-full", workspaceRevision + 1, "active", 3));
    assert.equal(restored.affected, 3);
    assert.deepEqual(await workspaceCounts("ws_full"), { roster: 3, contributions: 3, revision: 6, status: "active" });
    for (const uid of ["c1", "c2", "c3"]) assert.equal(await accountCount(uid), 1);
  });

  it("enforces the roster cap for activations into a suspended workspace", async () => {
    for (const uid of ["s1", "s2", "s3", "s4"]) await seed(uid, "ws_susp", "suspended");
    const capped = (uid: string, key: string, expectedWorkspaceRevision: number) =>
      activation(uid, "ws_susp", key, 0, expectedWorkspaceRevision, { maxMembershipsPerWorkspace: 3 });
    // Below the cap: the relationship roster grows even though nothing contributes.
    assert.equal((await transitionMembership(db, capped("s1", "s1", 1))).activeMembershipCount, 0);
    assert.deepEqual(await workspaceCounts("ws_susp"), { roster: 1, contributions: 0, revision: 2, status: "suspended" });
    // Exactly at the cap.
    assert.equal((await transitionMembership(db, capped("s2", "s2", 2))).activeMembershipCount, 0);
    assert.equal((await transitionMembership(db, capped("s3", "s3", 3))).activeMembershipCount, 0);
    assert.deepEqual(await workspaceCounts("ws_susp"), { roster: 3, contributions: 0, revision: 4, status: "suspended" });
    // Above the cap rejects atomically, and repeated attempts cannot bypass the cap.
    const workspaceBefore = (await db.doc("workspaces/ws_susp").get()).data();
    const accountBefore = (await db.doc("users/s4").get()).data();
    const entitlementBefore = (await db.doc("users/s4/authorizations/systemCatalog").get()).data();
    await assert.rejects(() => transitionMembership(db, capped("s4", "s4", 4)), /workspace-roster-bound-violated/);
    await assert.rejects(() => transitionMembership(db, capped("s4", "s4-again", 4)), /workspace-roster-bound-violated/);
    await assert.rejects(() => transitionMembership(db, capped("s4", "s4-third", 4)), /workspace-roster-bound-violated/);
    assert.deepEqual((await db.doc("workspaces/ws_susp").get()).data(), workspaceBefore);
    assert.deepEqual((await db.doc("users/s4").get()).data(), accountBefore);
    assert.deepEqual((await db.doc("users/s4/authorizations/systemCatalog").get()).data(), entitlementBefore);
    assert.equal((await db.doc("workspaces/ws_susp/memberships/s4").get()).exists, false);
    assert.equal((await db.collection("lifecycleCommands").where("commandKind", "==", "membership-transition").get()).size, 3);
    // The full roster is still restorable within the same cap.
    const restored = await transitionWorkspace(db, workspaceTransition("ws_susp", "restore-susp", 4, "active", 3));
    assert.equal(restored.affected, 3);
    assert.deepEqual(await workspaceCounts("ws_susp"), { roster: 3, contributions: 3, revision: 5, status: "active" });
    for (const uid of ["s1", "s2", "s3"]) assert.equal(await accountCount(uid), 1);
  });

  it("revokes active relationships in active and suspended workspaces without drift", async () => {
    await seed("r1", "ws_revoke");
    await seed("r2", "ws_revoke");
    await transitionMembership(db, activation("r1", "ws_revoke", "r1", 0, 1));
    await transitionMembership(db, activation("r2", "ws_revoke", "r2", 0, 2));
    assert.deepEqual(await workspaceCounts("ws_revoke"), { roster: 2, contributions: 2, revision: 3, status: "active" });
    // Revoke while active: roster and contributions both decrease exactly once.
    await transitionMembership(db, activation("r1", "ws_revoke", "r1-revoke", 1, 3, { nextStatus: "revoked" }));
    assert.deepEqual(await workspaceCounts("ws_revoke"), { roster: 1, contributions: 1, revision: 4, status: "active" });
    assert.equal(await accountCount("r1"), 0);
    // Duplicate revocation replays through the receipt without double counting.
    assert.equal((await transitionMembership(db, activation("r1", "ws_revoke", "r1-revoke", 1, 3, { nextStatus: "revoked" }))).replayed, true);
    assert.deepEqual(await workspaceCounts("ws_revoke"), { roster: 1, contributions: 1, revision: 4, status: "active" });
    // Revoke while suspended: roster decreases, contributions stay zero. The suspension
    // advanced this membership's revision, so the expected revision is the post-suspend one.
    await transitionWorkspace(db, workspaceTransition("ws_revoke", "suspend-revoke", 4, "suspended"));
    assert.deepEqual(await workspaceCounts("ws_revoke"), { roster: 1, contributions: 0, revision: 5, status: "suspended" });
    await transitionMembership(db, activation("r2", "ws_revoke", "r2-revoke", 2, 5, { nextStatus: "revoked" }));
    assert.deepEqual(await workspaceCounts("ws_revoke"), { roster: 0, contributions: 0, revision: 6, status: "suspended" });
    assert.equal(await accountCount("r2"), 0);
  });

  it("keeps roster and contribution counters distinct and consistent", async () => {
    await seed("d1", "ws_distinct", "suspended");
    await transitionMembership(db, activation("d1", "ws_distinct", "d1", 0, 1));
    // Suspended: an active relationship exists, but it contributes nothing.
    assert.deepEqual(await workspaceCounts("ws_distinct"), { roster: 1, contributions: 0, revision: 2, status: "suspended" });
    assert.equal(await accountCount("d1"), 0);
    // Restoring converts the existing roster into contributions without changing the roster.
    await transitionWorkspace(db, workspaceTransition("ws_distinct", "restore-distinct", 2, "active"));
    assert.deepEqual(await workspaceCounts("ws_distinct"), { roster: 1, contributions: 1, revision: 3, status: "active" });
    assert.equal(await accountCount("d1"), 1);
    // A revoked membership is never restored as a contribution. The restore advanced this
    // membership's revision, so the expected revision is the post-restore one.
    await transitionMembership(db, activation("d1", "ws_distinct", "d1-revoke", 2, 3, { nextStatus: "revoked" }));
    assert.deepEqual(await workspaceCounts("ws_distinct"), { roster: 0, contributions: 0, revision: 4, status: "active" });
    await transitionWorkspace(db, workspaceTransition("ws_distinct", "suspend-distinct", 4, "suspended"));
    const restored = await transitionWorkspace(db, workspaceTransition("ws_distinct", "restore-distinct-2", 5, "active"));
    assert.equal(restored.affected, 0);
    assert.deepEqual(await workspaceCounts("ws_distinct"), { roster: 0, contributions: 0, revision: 6, status: "active" });
    assert.equal(await accountCount("d1"), 0);
  });

  it("fails closed on internally inconsistent or oversized workspace counters", async () => {
    await seed("i1", "ws_inconsistent");
    await transitionMembership(db, activation("i1", "ws_inconsistent", "i1", 0, 1));
    // Stored roster diverges from the authoritative active-roster scan.
    await db.doc("workspaces/ws_inconsistent").update({ activeRosterCount: 2 });
    const rosterDrift = (await db.doc("workspaces/ws_inconsistent").get()).data();
    await assert.rejects(() => transitionWorkspace(db, workspaceTransition("ws_inconsistent", "suspend-drift", 2, "suspended", 5)), /workspace-roster-inconsistent/);
    assert.deepEqual((await db.doc("workspaces/ws_inconsistent").get()).data(), rosterDrift);
    // Stored contribution count diverges from the scanned contributions.
    await db.doc("workspaces/ws_inconsistent").update({ activeRosterCount: 1, catalogContributionCount: 0 });
    const contributionDrift = (await db.doc("workspaces/ws_inconsistent").get()).data();
    await assert.rejects(() => transitionWorkspace(db, workspaceTransition("ws_inconsistent", "suspend-contribution-drift", 2, "suspended", 5)), /workspace-contribution-inconsistent/);
    assert.deepEqual((await db.doc("workspaces/ws_inconsistent").get()).data(), contributionDrift);
    // An already oversized roster cannot silently transition.
    await db.doc("workspaces/ws_inconsistent").update({ activeRosterCount: 4, catalogContributionCount: 1 });
    const oversized = (await db.doc("workspaces/ws_inconsistent").get()).data();
    await assert.rejects(() => transitionWorkspace(db, workspaceTransition("ws_inconsistent", "suspend-oversized", 2, "suspended", 3)), /workspace-roster-bound-violated/);
    assert.deepEqual((await db.doc("workspaces/ws_inconsistent").get()).data(), oversized);
    // Malformed counters fail closed for membership transitions too. Only the account and
    // entitlement are seeded here so the drifted workspace counters stay in place.
    await db.doc("users/i2").set({ schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
    await db.doc("users/i2/authorizations/systemCatalog").set({ schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });
    await db.doc("workspaces/ws_inconsistent").update({ activeRosterCount: "1", catalogContributionCount: 1 });
    await assert.rejects(() => transitionMembership(db, activation("i2", "ws_inconsistent", "i2", 0, 2, { maxMembershipsPerWorkspace: 5 })), /workspace-roster-malformed/);
    await db.doc("workspaces/ws_inconsistent").update({ activeRosterCount: 1, catalogContributionCount: "1" });
    await assert.rejects(() => transitionMembership(db, activation("i2", "ws_inconsistent", "i2", 0, 2, { maxMembershipsPerWorkspace: 5 })), /workspace-contribution-malformed/);
    await db.doc("workspaces/ws_inconsistent").update({ catalogContributionCount: 2 });
    await assert.rejects(() => transitionMembership(db, activation("i2", "ws_inconsistent", "i2", 0, 2, { maxMembershipsPerWorkspace: 5 })), /workspace-contribution-bound-violated/);
    assert.equal((await db.doc("workspaces/ws_inconsistent/memberships/i2").get()).exists, false);
    assert.equal((await db.collection("lifecycleCommands").where("commandKind", "==", "membership-transition").get()).size, 1);
  });

  it("leaves every related document unchanged when a lifecycle operation is rejected", async () => {
    await seed("x1", "ws_atomic");
    await seed("x2", "ws_atomic");
    await transitionMembership(db, activation("x1", "ws_atomic", "x1", 0, 1));
    const before = {
      workspace: (await db.doc("workspaces/ws_atomic").get()).data(),
      membership: (await db.doc("workspaces/ws_atomic/memberships/x1").get()).data(),
      account: (await db.doc("users/x2").get()).data(),
      entitlement: (await db.doc("users/x2/authorizations/systemCatalog").get()).data(),
      receipts: (await db.collection("lifecycleCommands").get()).size
    };
    await assert.rejects(() => transitionMembership(db, activation("x2", "ws_atomic", "x2", 0, 2, { maxMembershipsPerWorkspace: 1 })), /workspace-roster-bound-violated/);
    await assert.rejects(() => transitionMembership(db, activation("x2", "ws_atomic", "x2-stale", 0, 99)), /stale-workspace-revision/);
    await assert.rejects(() => transitionWorkspace(db, workspaceTransition("ws_atomic", "suspend-stale", 99, "suspended", 5)), /stale-revision/);
    assert.deepEqual((await db.doc("workspaces/ws_atomic").get()).data(), before.workspace);
    assert.deepEqual((await db.doc("workspaces/ws_atomic/memberships/x1").get()).data(), before.membership);
    assert.deepEqual((await db.doc("users/x2").get()).data(), before.account);
    assert.deepEqual((await db.doc("users/x2/authorizations/systemCatalog").get()).data(), before.entitlement);
    assert.equal((await db.doc("workspaces/ws_atomic/memberships/x2").get()).exists, false);
    assert.equal((await db.collection("lifecycleCommands").get()).size, before.receipts);
  });

  it("validates every scanned membership identity before deriving account entitlements", async () => {
    // A second account represents the identity a malformed membership claims to point at; its
    // entitlement must never be touched by a scan of another workspace's document either.
    await db.doc("users/other").set({ schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
    await db.doc("users/other/authorizations/systemCatalog").set({ schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });

    const validMembership = (workspaceId: string, uid: string) => ({
      schemaVersion: 1, workspaceId, userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: false
    });
    // Each case writes one malformed membership document (everything else stays well formed) and
    // expects the shared suspension/restoration scan to reject the whole transition for that defect.
    const cases: ReadonlyArray<readonly [string, RegExp, (workspaceId: string, uid: string) => Record<string, unknown>]> = [
      ["user-mismatch", /membership-user-mismatch/, (workspaceId, uid) => ({ ...validMembership(workspaceId, uid), userId: "other" })],
      ["workspace-mismatch", /membership-workspace-mismatch/, (workspaceId) => ({ ...validMembership(workspaceId, "ignored"), workspaceId: "ws_elsewhere" })],
      ["schema-missing", /membership-schema-unsupported/, (workspaceId, uid) => ({ workspaceId, userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: false })],
      ["schema-unsupported", /membership-schema-unsupported/, (workspaceId, uid) => ({ ...validMembership(workspaceId, uid), schemaVersion: 2 })],
      ["role-invalid", /membership-role-invalid/, (workspaceId, uid) => ({ ...validMembership(workspaceId, uid), role: "owner" })],
      ["contribution-non-boolean", /membership-contribution-malformed/, (workspaceId, uid) => ({ ...validMembership(workspaceId, uid), catalogContributionActive: "true" })],
      ["revision-malformed", /membership-revision-malformed/, (workspaceId, uid) => ({ ...validMembership(workspaceId, uid), revision: "1" })]
    ];

    for (const [caseName, expected, malformed] of cases) {
      const uid = `scan_${caseName.replace(/-/g, "_")}`;
      const workspaceId = `ws_scan_${caseName.replace(/-/g, "_")}`;
      const workspaceRef = db.doc(`workspaces/${workspaceId}`);
      const membershipRef = db.doc(`workspaces/${workspaceId}/memberships/${uid}`);
      const idempotencyKey = `restore-${caseName}`;

      // Suspended workspace with one active member, the normal pre-restoration state.
      await seed(uid, workspaceId, "suspended");
      await workspaceRef.update({ activeRosterCount: 1 });
      await membershipRef.set(malformed(workspaceId, uid));

      const before = {
        workspace: (await workspaceRef.get()).data(),
        membership: (await membershipRef.get()).data(),
        account: (await db.doc(`users/${uid}`).get()).data(),
        entitlement: (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(),
        otherEntitlement: (await db.doc("users/other/authorizations/systemCatalog").get()).data()
      };

      await assert.rejects(() => transitionWorkspace(db, workspaceTransition(workspaceId, idempotencyKey, 1, "active")), expected);

      // Atomic rejection: workspace status/revision, the malformed membership, both the path
      // account and the claimed account's entitlements, and the command receipt all stay put.
      assert.deepEqual((await workspaceRef.get()).data(), before.workspace, `${caseName}: workspace must stay unchanged`);
      assert.deepEqual((await membershipRef.get()).data(), before.membership, `${caseName}: membership must stay unchanged`);
      assert.deepEqual((await db.doc(`users/${uid}`).get()).data(), before.account, `${caseName}: account must stay unchanged`);
      assert.deepEqual((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(), before.entitlement, `${caseName}: entitlement must stay unchanged`);
      assert.deepEqual((await db.doc("users/other/authorizations/systemCatalog").get()).data(), before.otherEntitlement, `${caseName}: must not touch the claimed account`);
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", idempotencyKey)}`).get()).exists, false, `${caseName}: must not record a receipt`);
      assert.equal((await db.collection("lifecycleCommands").get()).size, 0, `${caseName}: must record no receipts`);
      // Authorization state specifically: no rejection may publish an active entitlement.
      assert.equal((await entitlementState(uid)).status, "inactive");
      assert.equal(await accountCount(uid), 0);
    }

    // The same shared scan guards suspension: a malformed member rejects before any write too.
    await seed("scan_suspend", "ws_scan_suspend", "active");
    const suspendWorkspaceRef = db.doc("workspaces/ws_scan_suspend");
    const suspendMembershipRef = db.doc("workspaces/ws_scan_suspend/memberships/scan_suspend");
    const suspendEntitlementRef = db.doc("users/scan_suspend/authorizations/systemCatalog");
    await suspendWorkspaceRef.update({ activeRosterCount: 1, catalogContributionCount: 1 });
    await suspendEntitlementRef.update({ activeMembershipCount: 1, status: "active" });
    await suspendMembershipRef.set({ schemaVersion: 1, workspaceId: "ws_scan_suspend", userId: "scan_suspend", role: "client", status: "active", revision: 1, catalogContributionActive: true });
    await suspendMembershipRef.update({ userId: "other" });
    const suspendBefore = {
      workspace: (await suspendWorkspaceRef.get()).data(),
      membership: (await suspendMembershipRef.get()).data(),
      entitlement: (await suspendEntitlementRef.get()).data()
    };
    await assert.rejects(
      () => transitionWorkspace(db, workspaceTransition("ws_scan_suspend", "suspend-user-mismatch", 1, "suspended")),
      /membership-user-mismatch/
    );
    assert.deepEqual((await suspendWorkspaceRef.get()).data(), suspendBefore.workspace, "suspension: workspace must stay unchanged");
    assert.deepEqual((await suspendMembershipRef.get()).data(), suspendBefore.membership, "suspension: membership must stay unchanged");
    assert.deepEqual((await suspendEntitlementRef.get()).data(), suspendBefore.entitlement, "suspension: entitlement must stay unchanged");
    assert.equal((await db.collection("lifecycleCommands").get()).size, 0, "suspension: must record no receipt");

    // A fully valid membership restores normally, proving validation never rejects good data.
    await seed("scan_valid", "ws_scan_valid", "suspended");
    await db.doc("workspaces/ws_scan_valid").update({ activeRosterCount: 1 });
    await db.doc("workspaces/ws_scan_valid/memberships/scan_valid").set({ schemaVersion: 1, workspaceId: "ws_scan_valid", userId: "scan_valid", role: "trainer", status: "active", revision: 1, catalogContributionActive: false });
    const restored = await transitionWorkspace(db, workspaceTransition("ws_scan_valid", "restore-valid", 1, "active"));
    assert.equal(restored.affected, 1);
    assert.deepEqual(await workspaceCounts("ws_scan_valid"), { roster: 1, contributions: 1, revision: 2, status: "active" });
    assert.equal(await accountCount("scan_valid"), 1);
    assert.equal((await entitlementState("scan_valid")).status, "active");
    assert.equal((await db.doc("workspaces/ws_scan_valid/memberships/scan_valid").get()).get("catalogContributionActive"), true);
    assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "restore-valid")}`).get()).get("commandKind"), "workspace-transition");
    assert.equal((await db.collection("lifecycleCommands").get()).size, 1);
  });

  it("validates a stored membership before deriving membership-transition deltas", async () => {
    const storedMembership = (workspaceId: string, uid: string, overrides: Record<string, unknown> = {}) => ({
      schemaVersion: 1, workspaceId, userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: true, ...overrides
    });
    // Each case damages one active membership of an active workspace (entitlement count 1, roster 1,
    // contribution 1) and then re-activates it. Without the pre-delta check a missing or non-boolean
    // contribution flag reads as "not contributing", so the re-activation would increment both
    // counters and overwrite the damaged document instead of failing closed.
    const cases: ReadonlyArray<readonly [string, RegExp, (workspaceId: string, uid: string) => Record<string, unknown>]> = [
      ["contribution-missing", /membership-contribution-malformed/, (workspaceId, uid) => ({ schemaVersion: 1, workspaceId, userId: uid, role: "client", status: "active", revision: 1 })],
      ["contribution-non-boolean", /membership-contribution-malformed/, (workspaceId, uid) => storedMembership(workspaceId, uid, { catalogContributionActive: "true" })],
      ["user-mismatch", /membership-user-mismatch/, (workspaceId, uid) => storedMembership(workspaceId, uid, { userId: "other" })],
      ["workspace-mismatch", /membership-workspace-mismatch/, (workspaceId, uid) => storedMembership(workspaceId, uid, { workspaceId: "ws_elsewhere" })],
      ["schema-missing", /membership-schema-unsupported/, (workspaceId, uid) => ({ workspaceId, userId: uid, role: "client", status: "active", revision: 1, catalogContributionActive: true })],
      ["schema-unsupported", /membership-schema-unsupported/, (workspaceId, uid) => storedMembership(workspaceId, uid, { schemaVersion: 2 })],
      ["role-invalid", /membership-role-invalid/, (workspaceId, uid) => storedMembership(workspaceId, uid, { role: "owner" })],
      ["status-invalid", /membership-status-invalid/, (workspaceId, uid) => storedMembership(workspaceId, uid, { status: "pending" })],
      ["revision-malformed", /membership-revision-malformed/, (workspaceId, uid) => storedMembership(workspaceId, uid, { revision: "1" })]
    ];

    for (const [caseName, expected, damaged] of cases) {
      const uid = `delta_${caseName.replace(/-/g, "_")}`;
      const workspaceId = `ws_delta_${caseName.replace(/-/g, "_")}`;
      const membershipRef = db.doc(`workspaces/${workspaceId}/memberships/${uid}`);
      const idempotencyKey = `delta-${caseName}`;

      await seed(uid, workspaceId);
      assert.equal((await transitionMembership(db, activation(uid, workspaceId, `activate-${caseName}`, 0, 1))).activeMembershipCount, 1);
      assert.deepEqual(await workspaceCounts(workspaceId), { roster: 1, contributions: 1, revision: 2, status: "active" });
      await membershipRef.set(damaged(workspaceId, uid));

      const before = {
        workspace: (await db.doc(`workspaces/${workspaceId}`).get()).data(),
        membership: (await membershipRef.get()).data(),
        account: (await db.doc(`users/${uid}`).get()).data(),
        entitlement: (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(),
        receipts: (await db.collection("lifecycleCommands").get()).size
      };

      await assert.rejects(
        () => transitionMembership(db, activation(uid, workspaceId, idempotencyKey, 1, 2)),
        expected
      );

      // Atomic rejection: no counter, document, or receipt changes, and no active entitlement is
      // published for the damaged membership.
      assert.deepEqual((await db.doc(`workspaces/${workspaceId}`).get()).data(), before.workspace, `${caseName}: workspace must stay unchanged`);
      assert.deepEqual((await membershipRef.get()).data(), before.membership, `${caseName}: membership must not be repaired`);
      assert.deepEqual((await db.doc(`users/${uid}`).get()).data(), before.account, `${caseName}: account must stay unchanged`);
      assert.deepEqual((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(), before.entitlement, `${caseName}: entitlement must stay unchanged`);
      assert.equal((await entitlementState(uid)).activeMembershipCount, 1, `${caseName}: count must not be incremented`);
      assert.equal((await db.collection("lifecycleCommands").get()).size, before.receipts, `${caseName}: must record no receipt`);
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", idempotencyKey)}`).get()).exists, false, `${caseName}: must record no receipt`);
    }

    // A valid stored membership still transitions: revoke then restore the intact document, which
    // proves the pre-delta check accepts the documented inactive state instead of only "active".
    await seed("delta_valid", "ws_delta_valid");
    const validMembershipRef = db.doc("workspaces/ws_delta_valid/memberships/delta_valid");
    await transitionMembership(db, activation("delta_valid", "ws_delta_valid", "delta-valid-activate", 0, 1));
    const revoked = await transitionMembership(db, activation("delta_valid", "ws_delta_valid", "delta-valid-revoke", 1, 2, { nextStatus: "revoked" }));
    assert.equal(revoked.entitlementStatus, "inactive");
    assert.equal((await validMembershipRef.get()).get("status"), "revoked");
    assert.equal((await validMembershipRef.get()).get("catalogContributionActive"), false);
    const restored = await transitionMembership(db, activation("delta_valid", "ws_delta_valid", "delta-valid-restore", 2, 3));
    assert.equal(restored.entitlementStatus, "active");
    assert.deepEqual(await workspaceCounts("ws_delta_valid"), { roster: 1, contributions: 1, revision: 4, status: "active" });
    assert.equal(await accountCount("delta_valid"), 1);
    assert.equal((await entitlementState("delta_valid")).status, "active");
    assert.equal((await entitlementState("delta_valid")).activeMembershipCount, 1);
    assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "delta-valid-restore")}`).get()).get("commandKind"), "membership-transition");
  });

  it("permits a partial deactivation while the account or entitlement schema is damaged", async () => {
    // Revoking one of two contributions must stay possible for a schema-damaged account or
    // entitlement: the command only lowers access, so Rules keep denying the damaged record even
    // though a positive count remains. Adding a contribution back requires the schema again.
    const cases: ReadonlyArray<readonly [string, (uid: string) => Promise<void>, "active" | "inactive"]> = [
      ["account-schema", async (uid) => { await db.doc(`users/${uid}`).update({ schemaVersion: 5 }); }, "active"],
      ["entitlement-schema", async (uid) => { await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ schemaVersion: 5 }); }, "active"],
      ["entitlement-inactive-drift", async (uid) => { await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ schemaVersion: 5, status: "inactive" }); }, "inactive"]
    ];

    for (const [caseName, damage, expectedStatus] of cases) {
      const uid = `deact_${caseName.replace(/-/g, "_")}`;
      const workspaceA = `ws_${uid}_a`;
      const workspaceB = `ws_${uid}_b`;
      const revokeKey = `${caseName}-revoke`;
      await seed(uid, workspaceA);
      await seed(uid, workspaceB);
      await transitionMembership(db, activation(uid, workspaceA, `${caseName}-a`, 0, 1));
      await transitionMembership(db, activation(uid, workspaceB, `${caseName}-b`, 0, 1));
      assert.equal((await entitlementState(uid)).activeMembershipCount, 2, `${caseName}: two contributions before removal`);
      await damage(uid);
      const damaged = {
        account: (await db.doc(`users/${uid}`).get()).data(),
        entitlement: (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data()
      };

      const revoked = await transitionMembership(db, activation(uid, workspaceA, revokeKey, 1, 2, { nextStatus: "revoked" }));
      assert.equal(revoked.activeMembershipCount, 1, `${caseName}: one contribution remains`);
      assert.equal(revoked.entitlementStatus, expectedStatus, `${caseName}: stored status is preserved, never elevated`);
      const membership = await db.doc(`workspaces/${workspaceA}/memberships/${uid}`).get();
      assert.equal(membership.get("status"), "revoked");
      assert.equal(membership.get("catalogContributionActive"), false);
      assert.equal(membership.get("revision"), 2);
      const entitlementAfter = await db.doc(`users/${uid}/authorizations/systemCatalog`).get();
      assert.equal(entitlementAfter.get("activeMembershipCount"), 1);
      assert.equal(entitlementAfter.get("status"), expectedStatus);
      // The damaged documents stay exactly as damaged as they were: no repair, no activation.
      assert.equal(entitlementAfter.get("schemaVersion"), damaged.entitlement!.schemaVersion, `${caseName}: entitlement schema is not repaired`);
      assert.equal((await db.doc(`users/${uid}`).get()).get("schemaVersion"), damaged.account!.schemaVersion, `${caseName}: account schema is not repaired`);
      assert.deepEqual(await workspaceCounts(workspaceA), { roster: 0, contributions: 0, revision: 3, status: "active" });
      assert.deepEqual(await workspaceCounts(workspaceB), { roster: 1, contributions: 1, revision: 2, status: "active" });
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", revokeKey)}`).get()).get("commandKind"), "membership-transition", `${caseName}: the removal is receipt-backed`);

      // Replay stays idempotent: the same recorded result, no extra revision or write.
      const replayed = await transitionMembership(db, activation(uid, workspaceA, revokeKey, 1, 2, { nextStatus: "revoked" }));
      assert.equal(replayed.replayed, true, `${caseName}: replay is reported`);
      assert.equal(replayed.activeMembershipCount, 1, `${caseName}: replay keeps the recorded result`);
      assert.equal(
        (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).get("revision"),
        entitlementAfter.get("revision"),
        `${caseName}: replay rewrites no entitlement revision`
      );
      assert.equal((await db.doc(`workspaces/${workspaceA}/memberships/${uid}`).get()).get("revision"), 2, `${caseName}: replay rewrites no membership revision`);

      // Adding a contribution on the same damaged record still rejects atomically.
      const beforeAdd = {
        workspace: (await db.doc(`workspaces/${workspaceA}`).get()).data(),
        membership: (await db.doc(`workspaces/${workspaceA}/memberships/${uid}`).get()).data(),
        account: (await db.doc(`users/${uid}`).get()).data(),
        entitlement: (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(),
        receipts: (await db.collection("lifecycleCommands").get()).size
      };
      await assert.rejects(
        () => transitionMembership(db, activation(uid, workspaceA, `${caseName}-reactivate`, 2, 3)),
        /schema-unsupported/,
        `${caseName}: an addition still needs the schema`
      );
      assert.deepEqual((await db.doc(`workspaces/${workspaceA}`).get()).data(), beforeAdd.workspace, `${caseName}: rejected addition leaves the workspace unchanged`);
      assert.deepEqual((await db.doc(`workspaces/${workspaceA}/memberships/${uid}`).get()).data(), beforeAdd.membership, `${caseName}: rejected addition leaves the membership unchanged`);
      assert.deepEqual((await db.doc(`users/${uid}`).get()).data(), beforeAdd.account, `${caseName}: rejected addition leaves the account unchanged`);
      assert.deepEqual((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(), beforeAdd.entitlement, `${caseName}: rejected addition leaves the entitlement unchanged`);
      assert.equal((await db.collection("lifecycleCommands").get()).size, beforeAdd.receipts, `${caseName}: rejected addition records no receipt`);

      // After a separate trusted repair the same addition (same key — no receipt was written)
      // commits normally, proving valid-schema transitions are unaffected.
      await db.doc(`users/${uid}`).update({ schemaVersion: 1 });
      await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ schemaVersion: 1 });
      const reactivated = await transitionMembership(db, activation(uid, workspaceA, `${caseName}-reactivate`, 2, 3));
      assert.equal(reactivated.activeMembershipCount, 2, `${caseName}: the repaired schema allows the addition`);
      assert.equal(reactivated.entitlementStatus, "active");
      assert.deepEqual(await workspaceCounts(workspaceA), { roster: 1, contributions: 1, revision: 4, status: "active" });
      assert.equal(
        (await db.doc(`lifecycleCommands/${commandId("admin", `${caseName}-reactivate`)}`).get()).get("commandKind"),
        "membership-transition",
        `${caseName}: the addition is receipt-backed`
      );
    }
  });

  it("permits suspending a workspace while the account keeps a contribution elsewhere despite a damaged schema", async () => {
    await seed("suspend_multi", "ws_suspend_a");
    await seed("suspend_multi", "ws_suspend_b");
    await transitionMembership(db, activation("suspend_multi", "ws_suspend_a", "suspend-multi-a", 0, 1));
    await transitionMembership(db, activation("suspend_multi", "ws_suspend_b", "suspend-multi-b", 0, 1));
    assert.equal((await entitlementState("suspend_multi")).activeMembershipCount, 2);
    // Damage the account schema: suspending one workspace must still withdraw that workspace's
    // contribution even though the member keeps a positive count in another workspace.
    await db.doc("users/suspend_multi").update({ schemaVersion: 7 });
    const entitlementBefore = (await db.doc("users/suspend_multi/authorizations/systemCatalog").get()).data();

    const suspended = await transitionWorkspace(db, workspaceTransition("ws_suspend_a", "suspend-multi-a-suspend", 2, "suspended"));
    assert.equal(suspended.affected, 1);
    const membership = await db.doc("workspaces/ws_suspend_a/memberships/suspend_multi").get();
    assert.equal(membership.get("status"), "active", "the relationship survives suspension");
    assert.equal(membership.get("catalogContributionActive"), false);
    assert.equal(membership.get("revision"), 2);
    const entitlementAfter = await db.doc("users/suspend_multi/authorizations/systemCatalog").get();
    assert.equal(entitlementAfter.get("activeMembershipCount"), 1, "the retained contribution keeps a positive count");
    assert.equal(entitlementAfter.get("status"), "active", "the stored status is preserved, never elevated");
    assert.equal(entitlementAfter.get("schemaVersion"), entitlementBefore!.schemaVersion, "the entitlement schema is not repaired");
    assert.equal((await db.doc("users/suspend_multi").get()).get("schemaVersion"), 7, "the account schema is not repaired");
    assert.deepEqual(await workspaceCounts("ws_suspend_a"), { roster: 1, contributions: 0, revision: 3, status: "suspended" });
    assert.deepEqual(await workspaceCounts("ws_suspend_b"), { roster: 1, contributions: 1, revision: 2, status: "active" });

    // Restoration adds contributions, so the damaged schema still blocks it atomically.
    const beforeRestore = {
      workspace: (await db.doc("workspaces/ws_suspend_a").get()).data(),
      membership: (await db.doc("workspaces/ws_suspend_a/memberships/suspend_multi").get()).data(),
      account: (await db.doc("users/suspend_multi").get()).data(),
      entitlement: (await db.doc("users/suspend_multi/authorizations/systemCatalog").get()).data(),
      receipts: (await db.collection("lifecycleCommands").get()).size
    };
    await assert.rejects(
      () => transitionWorkspace(db, workspaceTransition("ws_suspend_a", "suspend-multi-a-restore", 3, "active")),
      /account-schema-unsupported/
    );
    assert.deepEqual((await db.doc("workspaces/ws_suspend_a").get()).data(), beforeRestore.workspace, "rejected restore leaves the workspace unchanged");
    assert.deepEqual((await db.doc("workspaces/ws_suspend_a/memberships/suspend_multi").get()).data(), beforeRestore.membership, "rejected restore leaves the membership unchanged");
    assert.deepEqual((await db.doc("users/suspend_multi").get()).data(), beforeRestore.account, "rejected restore leaves the account unchanged");
    assert.deepEqual((await db.doc("users/suspend_multi/authorizations/systemCatalog").get()).data(), beforeRestore.entitlement, "rejected restore leaves the entitlement unchanged");
    assert.equal((await db.collection("lifecycleCommands").get()).size, beforeRestore.receipts, "rejected restore records no receipt");

    // Suspending the second workspace removes the last contribution: a full deactivation that is
    // always allowed, leaving an inactive entitlement with a zero count.
    assert.equal((await transitionWorkspace(db, workspaceTransition("ws_suspend_b", "suspend-multi-b-suspend", 2, "suspended"))).affected, 1);
    assert.equal((await entitlementState("suspend_multi")).status, "inactive");
    assert.equal((await entitlementState("suspend_multi")).activeMembershipCount, 0);

    // After a separate trusted repair the same restore (same key — no receipt was written) commits
    // and the retained contribution returns.
    await db.doc("users/suspend_multi").update({ schemaVersion: 1 });
    const restored = await transitionWorkspace(db, workspaceTransition("ws_suspend_a", "suspend-multi-a-restore", 3, "active"));
    assert.equal(restored.affected, 1);
    assert.deepEqual(await workspaceCounts("ws_suspend_a"), { roster: 1, contributions: 1, revision: 4, status: "active" });
    assert.equal((await entitlementState("suspend_multi")).activeMembershipCount, 1);
    assert.equal((await entitlementState("suspend_multi")).status, "active");
    assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "suspend-multi-a-restore")}`).get()).get("commandKind"), "workspace-transition");
  });

  it("preserves a drifted inactive entitlement through zero-delta lifecycle changes", async () => {
    // A revocation in a suspended workspace changes no contribution at all (an active relationship
    // there contributes nothing). With a drifted `inactive` status and a positive count contributed
    // by another workspace, that neutral command must preserve `inactive` instead of granting
    // catalog access as a side effect of a revocation.
    const uid = "neutral_revoke";
    const suspendedWorkspace = "ws_neutral_suspended";
    const contributingWorkspace = "ws_neutral_contributing";
    await seed(uid, suspendedWorkspace);
    await seed(uid, contributingWorkspace);
    await transitionMembership(db, activation(uid, suspendedWorkspace, "neutral-suspend-activate", 0, 1));
    await transitionWorkspace(db, workspaceTransition(suspendedWorkspace, "neutral-suspend", 2, "suspended"));
    await transitionMembership(db, activation(uid, contributingWorkspace, "neutral-contribute", 0, 1));
    await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ status: "inactive" });
    const entitlementBefore = await db.doc(`users/${uid}/authorizations/systemCatalog`).get();
    assert.equal(entitlementBefore.get("activeMembershipCount"), 1, "another workspace keeps the count positive");
    assert.equal(entitlementBefore.get("status"), "inactive", "drifted status under test");

    const revoked = await transitionMembership(db, activation(uid, suspendedWorkspace, "neutral-revoke", 2, 3, { nextStatus: "revoked" }));
    assert.equal(revoked.activeMembershipCount, 1, "the zero delta keeps the other contribution");
    assert.equal(revoked.entitlementStatus, "inactive", "a zero contribution delta never activates");
    const entitlementAfter = await db.doc(`users/${uid}/authorizations/systemCatalog`).get();
    assert.equal(entitlementAfter.get("status"), "inactive");
    assert.equal(entitlementAfter.get("activeMembershipCount"), 1);
    assert.equal(entitlementAfter.get("revision"), entitlementBefore.get("revision") as number + 1);
    const membership = await db.doc(`workspaces/${suspendedWorkspace}/memberships/${uid}`).get();
    assert.equal(membership.get("status"), "revoked");
    assert.equal(membership.get("catalogContributionActive"), false);
    assert.equal(membership.get("revision"), 3);
    assert.deepEqual(await workspaceCounts(suspendedWorkspace), { roster: 0, contributions: 0, revision: 4, status: "suspended" });
    assert.deepEqual(await workspaceCounts(contributingWorkspace), { roster: 1, contributions: 1, revision: 2, status: "active" });
    assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "neutral-revoke")}`).get()).get("commandKind"), "membership-transition");

    // Replay is idempotent and reports the same preserved state without rewriting revisions.
    const replayed = await transitionMembership(db, activation(uid, suspendedWorkspace, "neutral-revoke", 2, 3, { nextStatus: "revoked" }));
    assert.equal(replayed.replayed, true);
    assert.equal(replayed.activeMembershipCount, 1);
    assert.equal(replayed.entitlementStatus, "inactive");
    assert.equal((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).get("revision"), entitlementAfter.get("revision"));

    // The workspace path uses the same derivation: re-suspending an already suspended workspace is
    // a zero contribution delta for that member and must not activate the drifted entitlement.
    const workspaceUid = "neutral_workspace";
    const alreadySuspended = "ws_neutral_already_suspended";
    const workspaceContributor = "ws_neutral_workspace_contributor";
    await seed(workspaceUid, alreadySuspended);
    await seed(workspaceUid, workspaceContributor);
    await transitionMembership(db, activation(workspaceUid, alreadySuspended, "neutral-ws-activate", 0, 1));
    await transitionWorkspace(db, workspaceTransition(alreadySuspended, "neutral-ws-suspend", 2, "suspended"));
    await transitionMembership(db, activation(workspaceUid, workspaceContributor, "neutral-ws-contribute", 0, 1));
    await db.doc(`users/${workspaceUid}/authorizations/systemCatalog`).update({ status: "inactive" });
    assert.equal((await db.doc(`users/${workspaceUid}/authorizations/systemCatalog`).get()).get("status"), "inactive");
    assert.equal((await transitionWorkspace(db, workspaceTransition(alreadySuspended, "neutral-ws-resuspend", 3, "suspended"))).affected, 1);
    const workspaceEntitlement = await db.doc(`users/${workspaceUid}/authorizations/systemCatalog`).get();
    assert.equal(workspaceEntitlement.get("status"), "inactive", "the workspace path preserves the drifted status");
    assert.equal(workspaceEntitlement.get("activeMembershipCount"), 1);
    assert.deepEqual(await workspaceCounts(alreadySuspended), { roster: 1, contributions: 0, revision: 4, status: "suspended" });

    // A genuine addition still grants access: activating a new contribution re-derives `active`.
    // Only the workspace is seeded here so the drifted account/entitlement state stays untouched.
    await db.doc("workspaces/ws_neutral_new").set({ schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 0, catalogContributionCount: 0 });
    const activated = await transitionMembership(db, activation(uid, "ws_neutral_new", "neutral-activate-new", 0, 1));
    assert.equal(activated.activeMembershipCount, 2);
    assert.equal(activated.entitlementStatus, "active");
    assert.equal((await entitlementState(uid)).status, "active");
    assert.deepEqual(await workspaceCounts("ws_neutral_new"), { roster: 1, contributions: 1, revision: 2, status: "active" });
  });

  it("guards account lifecycle commands with revisions, receipts, and idempotent replay", async () => {
    await seedAccount("acct", { status: "active", activeMembershipCount: 1 });

    // Valid active -> disabled, then valid disabled -> active; each advances the revision once.
    const disabled = await transitionAccountLifecycle(db, accountLifecycle("acct", false, 1));
    assert.deepEqual(disabled, { uid: "acct", accountStatus: "disabled", entitlementStatus: "inactive", lifecycleRevision: 2, replayed: false });
    assert.deepEqual(await accountState("acct"), { accountStatus: "disabled", lifecycleRevision: 2 });
    assert.deepEqual(await entitlementState("acct"), { status: "inactive", activeMembershipCount: 1, revision: 2 });

    const receiptRef = db.doc(`lifecycleCommands/${commandId("admin", "disable-acct")}`);
    const receiptBefore = (await receiptRef.get()).data();
    assert.equal(receiptBefore?.commandKind, "account-lifecycle");
    assert.equal(receiptBefore?.callerUid, "admin");
    assert.equal(receiptBefore?.enabled, false);
    assert.equal(receiptBefore?.expectedLifecycleRevision, 1);
    assert.equal(receiptBefore?.storedEntitlementCount, 1);

    // The identical command replays without incrementing the revision or rewriting the receipt.
    const replay = await transitionAccountLifecycle(db, accountLifecycle("acct", false, 1));
    assert.equal(replay.replayed, true);
    assert.deepEqual({ ...replay, replayed: false }, disabled);
    assert.deepEqual(await accountState("acct"), { accountStatus: "disabled", lifecycleRevision: 2 });
    assert.deepEqual((await receiptRef.get()).data(), receiptBefore);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 1);

    const enabled = await transitionAccountLifecycle(db, accountLifecycle("acct", true, 2));
    assert.deepEqual(enabled, { uid: "acct", accountStatus: "active", entitlementStatus: "active", lifecycleRevision: 3, replayed: false });
    assert.deepEqual(await accountState("acct"), { accountStatus: "active", lifecycleRevision: 3 });
    assert.deepEqual(await entitlementState("acct"), { status: "active", activeMembershipCount: 1, revision: 3 });
    assert.equal((await db.collection("lifecycleCommands").get()).size, 2);
  });

  it("binds account lifecycle commands to their caller and payload", async () => {
    await seedAccount("acct", { status: "active", activeMembershipCount: 1 });
    await transitionAccountLifecycle(db, accountLifecycle("acct", false, 1));

    // A different caller reusing the same idempotency key finds no receipt of its own and cannot
    // observe or replay the first command: it is rejected on the revision it no longer matches.
    await assert.rejects(
      () => transitionAccountLifecycle(db, accountLifecycle("acct", false, 1, { callerUid: "other-admin" })),
      /stale-revision/
    );
    assert.equal((await db.doc(`lifecycleCommands/${commandId("other-admin", "disable-acct")}`).get()).exists, false);
    assert.deepEqual(await accountState("acct"), { accountStatus: "disabled", lifecycleRevision: 2 });

    // The same caller and key with a changed payload must not replay another command.
    const crossCallerEnable = accountLifecycle("acct", true, 2, { callerUid: "other-admin", idempotencyKey: "other-enable" });
    assert.equal((await transitionAccountLifecycle(db, crossCallerEnable)).accountStatus, "active");
    await assert.rejects(
      () => transitionAccountLifecycle(db, { ...crossCallerEnable, enabled: false }),
      /idempotency-key-reused/
    );
    assert.deepEqual(await accountState("acct"), { accountStatus: "active", lifecycleRevision: 3 });
    assert.equal((await db.collection("lifecycleCommands").get()).size, 2);
  });

  it("rejects stale or malformed account lifecycle commands without changing state", async () => {
    await seedAccount("acct", { status: "active", activeMembershipCount: 1 });
    // A newer disable commits at revision 1.
    await transitionAccountLifecycle(db, accountLifecycle("acct", false, 1));

    // A delayed enable naming the older revision must fail instead of restoring access.
    const beforeStaleEnable = await lifecycleSnapshot("acct");
    await assert.rejects(() => transitionAccountLifecycle(db, accountLifecycle("acct", true, 1, { idempotencyKey: "delayed-enable" })), /stale-revision/);
    assert.deepEqual(await lifecycleSnapshot("acct"), beforeStaleEnable);
    assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "delayed-enable")}`).get()).exists, false);
    assert.equal((await accountState("acct")).accountStatus, "disabled");

    // A newer enable commits at revision 2, so a stale disable naming revision 2 must not revoke it.
    await transitionAccountLifecycle(db, accountLifecycle("acct", true, 2, { idempotencyKey: "newer-enable" }));
    const beforeStaleDisable = await lifecycleSnapshot("acct");
    await assert.rejects(() => transitionAccountLifecycle(db, accountLifecycle("acct", false, 2, { idempotencyKey: "late-disable" })), /stale-revision/);
    assert.deepEqual(await lifecycleSnapshot("acct"), beforeStaleDisable);
    assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "late-disable")}`).get()).exists, false);
    assert.equal((await accountState("acct")).accountStatus, "active");

    // Missing, malformed, negative, and fractional expected revisions are rejected before any write.
    for (const [key, expected] of [["missing", undefined], ["string", "3"], ["fractional", 3.5], ["negative", -1], ["boolean", true]] as const) {
      const before = await lifecycleSnapshot("acct");
      await assert.rejects(
        () => transitionAccountLifecycle(db, { ...accountLifecycle("acct", true, 3, { idempotencyKey: `malformed-${key}` }), expectedLifecycleRevision: expected as number }),
        /invalid-expected-revision/
      );
      assert.deepEqual(await lifecycleSnapshot("acct"), before);
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", `malformed-${key}`)}`).get()).exists, false);
    }

    // Lower and higher revisions are both rejected as stale instead of applying last-writer-wins.
    await assert.rejects(() => transitionAccountLifecycle(db, accountLifecycle("acct", true, 1, { idempotencyKey: "lower" })), /stale-revision/);
    await assert.rejects(() => transitionAccountLifecycle(db, accountLifecycle("acct", true, 99, { idempotencyKey: "higher" })), /stale-revision/);
    // A malformed stored account revision fails closed rather than being coerced.
    await db.doc("users/acct").update({ lifecycleRevision: "3" });
    await assert.rejects(() => transitionAccountLifecycle(db, accountLifecycle("acct", false, 3, { idempotencyKey: "malformed-stored" })), /account-revision-malformed/);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 2);
  });

  it("commits at most one of two concurrent opposite account commands at the same revision", async () => {
    await seedAccount("acct", { status: "active", activeMembershipCount: 1 });
    const results = await Promise.allSettled([
      transitionAccountLifecycle(db, accountLifecycle("acct", true, 1, { callerUid: "admin-a", idempotencyKey: "concurrent-enable" })),
      transitionAccountLifecycle(db, accountLifecycle("acct", false, 1, { callerUid: "admin-b", idempotencyKey: "concurrent-disable" }))
    ]);
    assert.equal(results.filter((result) => result.status === "fulfilled").length, 1);
    assert.equal(results.filter((result) => result.status === "rejected").length, 1);
    const winner = results.find((result) => result.status === "fulfilled") as PromiseFulfilledResult<AccountLifecycleResult>;
    const loser = results.find((result) => result.status === "rejected") as PromiseRejectedResult;
    assert.match(String((loser.reason as Error).message), /stale-revision/);
    assert.equal(winner.value.lifecycleRevision, 2);
    assert.deepEqual(await accountState("acct"), { accountStatus: winner.value.accountStatus, lifecycleRevision: 2 });
    assert.deepEqual(await entitlementState("acct"), { status: winner.value.entitlementStatus, activeMembershipCount: 1, revision: 2 });
    assert.equal((await db.collection("lifecycleCommands").get()).size, 1);

    // The winning command stays idempotent after the race and never double-applies.
    const winnerEnabled = winner.value.accountStatus === "active";
    const replay = await transitionAccountLifecycle(db, accountLifecycle("acct", winnerEnabled, 1, {
      callerUid: winnerEnabled ? "admin-a" : "admin-b",
      idempotencyKey: winnerEnabled ? "concurrent-enable" : "concurrent-disable"
    }));
    assert.equal(replay.replayed, true);
    assert.equal((await accountState("acct")).lifecycleRevision, 2);
  });

  it("validates stored entitlement counts strictly before enabling an account", async () => {
    // Valid counts inside 0..cap enable the account; only a positive count activates the entitlement.
    for (const [uid, count, status] of [["zero", 0, "inactive"], ["one", 1, "active"], ["at-cap", 20, "active"]] as const) {
      await seedAccount(uid, { status: "inactive", activeMembershipCount: count });
      const result = await transitionAccountLifecycle(db, accountLifecycle(uid, true, 1));
      assert.deepEqual(result, { uid, accountStatus: "active", entitlementStatus: status, lifecycleRevision: 2, replayed: false });
      assert.deepEqual(await accountState(uid), { accountStatus: "active", lifecycleRevision: 2 });
      assert.deepEqual(await entitlementState(uid), { status, activeMembershipCount: count, revision: 2 });
    }

    // Values the old `count > 0` coercion accepted (or silently repaired) are rejected without
    // touching the account, the entitlement, or the receipt collection.
    const rejected: ReadonlyArray<readonly [string, unknown]> = [
      ["above-cap", 21],
      ["negative", -1],
      ["numeric-string", "1"],
      ["fractional", 1.5],
      ["boolean", true],
      ["null", null],
      ["missing", undefined],
      ["array", [1]],
      ["object", { count: 1 }]
    ];
    for (const [uid, count] of rejected) {
      await seedAccount(uid, count === undefined ? { status: "inactive" } : { status: "inactive", activeMembershipCount: count });
      const before = await lifecycleSnapshot(uid);
      const outOfRange = typeof count === "number" && Number.isInteger(count);
      await assert.rejects(
        () => transitionAccountLifecycle(db, accountLifecycle(uid, true, 1)),
        outOfRange ? /membership-bound-violated/ : /entitlement-count-malformed/
      );
      assert.deepEqual(await lifecycleSnapshot(uid), before);
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", `enable-${uid}`)}`).get()).exists, false);
    }

    // A malformed or over-large configured maximum is rejected instead of authorizing a state the
    // Firestore Rules bound cannot support.
    for (const max of [0, -1, 1.5, "20", true, 21, 100]) {
      await seedAccount("bound", { status: "inactive", activeMembershipCount: 1 });
      const before = await lifecycleSnapshot("bound");
      await assert.rejects(
        () => transitionAccountLifecycle(db, accountLifecycle("bound", true, 1, { maxMembershipsPerAccount: max as number })),
        /invalid-maximum-memberships/
      );
      assert.deepEqual(await lifecycleSnapshot("bound"), before);
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", "enable-bound")}`).get()).exists, false);
    }
  });

  it("keeps disable security-safe and preserves malformed forensic counts", async () => {
    // A legitimate disable at the expected revision always removes catalog authorization, even when
    // the stored count is malformed or above the Rules cap, and never repairs the stored value.
    for (const [uid, count] of [["malformed", "1"], ["above-cap", 21], ["fractional", 1.5], ["missing", undefined]] as const) {
      await seedAccount(uid, count === undefined ? { status: "active" } : { status: "active", activeMembershipCount: count });
      const result = await transitionAccountLifecycle(db, accountLifecycle(uid, false, 1));
      assert.deepEqual(result, { uid, accountStatus: "disabled", entitlementStatus: "inactive", lifecycleRevision: 2, replayed: false });
      assert.deepEqual(await accountState(uid), { accountStatus: "disabled", lifecycleRevision: 2 });
      const entitlement = await entitlementState(uid);
      assert.equal(entitlement.status, "inactive");
      assert.deepEqual(entitlement.activeMembershipCount, count);
      assert.equal(entitlement.revision, 2);

      // Malformed state blocks re-enable until a separate trusted reconciliation repairs it, and
      // the rejected command still changes nothing.
      const before = await lifecycleSnapshot(uid);
      await assert.rejects(
        () => transitionAccountLifecycle(db, accountLifecycle(uid, true, 2)),
        /entitlement-count-malformed|membership-bound-violated/
      );
      assert.deepEqual(await lifecycleSnapshot(uid), before);
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", `enable-${uid}`)}`).get()).exists, false);
    }

    // The valid path still round-trips: disable preserves the count and re-enable restores it.
    await seedAccount("valid", { status: "active", activeMembershipCount: 1 });
    await transitionAccountLifecycle(db, accountLifecycle("valid", false, 1));
    assert.deepEqual(await entitlementState("valid"), { status: "inactive", activeMembershipCount: 1, revision: 2 });
    await transitionAccountLifecycle(db, accountLifecycle("valid", true, 2));
    assert.deepEqual(await entitlementState("valid"), { status: "active", activeMembershipCount: 1, revision: 3 });
  });

  it("rejects enabling while a lifecycle schema is missing, malformed, or unsupported", async () => {
    const corruptions: ReadonlyArray<readonly [string, RegExp]> = [
      ["account-missing", /account-schema-unsupported/],
      ["account-string", /account-schema-unsupported/],
      ["account-future", /account-schema-unsupported/],
      ["entitlement-missing", /entitlement-schema-unsupported/],
      ["entitlement-string", /entitlement-schema-unsupported/],
      ["entitlement-future", /entitlement-schema-unsupported/]
    ];
    const corrupt = async (uid: string, name: string): Promise<void> => {
      const corruptSchema = name.endsWith("string") ? "1" : 4;
      if (name.startsWith("account")) {
        if (name.endsWith("missing")) {
          // Overwrite without a schema version, exactly as a foreign or partially migrated record.
          await db.doc(`users/${uid}`).set({ accountStatus: "active", lifecycleRevision: 1 });
        } else {
          await db.doc(`users/${uid}`).update({ schemaVersion: corruptSchema });
        }
      } else if (name.endsWith("missing")) {
        await db.doc(`users/${uid}/authorizations/systemCatalog`).set({ status: "inactive", activeMembershipCount: 1, revision: 1 });
      } else {
        await db.doc(`users/${uid}/authorizations/systemCatalog`).update({ schemaVersion: corruptSchema });
      }
    };

    for (const [name, expected] of corruptions) {
      const uid = `schema_${name.replace("-", "_")}`;
      await seedAccount(uid, { status: "inactive", activeMembershipCount: 1 });
      await corrupt(uid, name);
      const before = await lifecycleDocuments(uid);
      await assert.rejects(() => transitionAccountLifecycle(db, accountLifecycle(uid, true, 1)), expected);
      assert.deepEqual(await lifecycleDocuments(uid), before, `${name} must stay unchanged`);
      assert.equal((await db.doc(`lifecycleCommands/${commandId("admin", `enable-${uid}`)}`).get()).exists, false);
    }
  });

  it("never publishes active access for a schema-damaged document but still allows deactivation", async () => {
    // Membership activation would publish an active entitlement, so an unsupported account schema
    // fails closed before any write.
    await seed("schema_member", "ws_schema_member");
    await db.doc("users/schema_member").update({ schemaVersion: 2 });
    const workspaceBefore = await workspaceCounts("ws_schema_member");
    const accountBefore = await lifecycleDocuments("schema_member");
    await assert.rejects(() => transitionMembership(db, activation("schema_member", "ws_schema_member", "schema-member", 0, 1)), /account-schema-unsupported/);
    assert.deepEqual(await workspaceCounts("ws_schema_member"), workspaceBefore);
    assert.deepEqual(await lifecycleDocuments("schema_member"), accountBefore);
    assert.equal((await db.doc("workspaces/ws_schema_member/memberships/schema_member").get()).exists, false);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 0);

    // The same command succeeds once the schema is current.
    await db.doc("users/schema_member").update({ schemaVersion: 1 });
    assert.equal((await transitionMembership(db, activation("schema_member", "ws_schema_member", "schema-member", 0, 1))).entitlementStatus, "active");

    // Damaging the schema afterwards never blocks revoking that relationship.
    await db.doc("users/schema_member").update({ schemaVersion: 5 });
    const revoked = await transitionMembership(db, activation("schema_member", "ws_schema_member", "schema-member-revoke", 1, 2, { nextStatus: "revoked" }));
    assert.equal(revoked.entitlementStatus, "inactive");
    assert.equal((await entitlementState("schema_member")).status, "inactive");
    assert.equal(await accountCount("schema_member"), 0);

    // Workspace suspension is a deactivation path and stays possible, while restoring would publish
    // active access and therefore fails closed until the schema is repaired.
    await seed("schema_suspend", "ws_schema_suspend");
    await transitionMembership(db, activation("schema_suspend", "ws_schema_suspend", "schema-suspend-activate", 0, 1));
    await db.doc("users/schema_suspend").update({ schemaVersion: 9 });
    const suspended = await transitionWorkspace(db, workspaceTransition("ws_schema_suspend", "schema-suspend", 2, "suspended"));
    assert.equal(suspended.affected, 1);
    assert.deepEqual(await workspaceCounts("ws_schema_suspend"), { roster: 1, contributions: 0, revision: 3, status: "suspended" });
    assert.equal((await entitlementState("schema_suspend")).status, "inactive");
    const suspendedBefore = await lifecycleDocuments("schema_suspend");
    await assert.rejects(() => transitionWorkspace(db, workspaceTransition("ws_schema_suspend", "schema-restore", 3, "active")), /account-schema-unsupported/);
    assert.deepEqual(await lifecycleDocuments("schema_suspend"), suspendedBefore);
    assert.deepEqual(await workspaceCounts("ws_schema_suspend"), { roster: 1, contributions: 0, revision: 3, status: "suspended" });

    // Account disable still revokes access for schema-damaged records and never repairs them.
    await seedAccount("schema_disable", { status: "active", activeMembershipCount: 1 });
    await db.doc("users/schema_disable").update({ schemaVersion: 6 });
    await db.doc("users/schema_disable/authorizations/systemCatalog").update({ schemaVersion: 6 });
    const disableBefore = await lifecycleDocuments("schema_disable");
    assert.deepEqual(await transitionAccountLifecycle(db, accountLifecycle("schema_disable", false, 1)), { uid: "schema_disable", accountStatus: "disabled", entitlementStatus: "inactive", lifecycleRevision: 2, replayed: false });
    const disableAfter = await lifecycleDocuments("schema_disable");
    assert.notDeepEqual(disableAfter, disableBefore);
    assert.equal((disableAfter.account as Record<string, unknown>).schemaVersion, 6);
    assert.equal((disableAfter.account as Record<string, unknown>).lifecycleRevision, 2);
    assert.equal((disableAfter.entitlement as Record<string, unknown>).schemaVersion, 6);
    assert.equal((disableAfter.entitlement as Record<string, unknown>).status, "inactive");
    assert.equal((disableAfter.entitlement as Record<string, unknown>).activeMembershipCount, 1);

    // Re-enabling stays blocked until a trusted process restores the current schema.
    await assert.rejects(() => transitionAccountLifecycle(db, accountLifecycle("schema_disable", true, 2)), /account-schema-unsupported/);
    await db.doc("users/schema_disable").update({ schemaVersion: 1 });
    await db.doc("users/schema_disable/authorizations/systemCatalog").update({ schemaVersion: 1 });
    assert.equal((await transitionAccountLifecycle(db, accountLifecycle("schema_disable", true, 2))).entitlementStatus, "active");
  });

  it("requires a schema-current recognized workspace before adding catalog contributions", async () => {
    // An active workspace that Rules reject must never be the source of a catalog contribution: an
    // activation there would otherwise raise the account count and let global catalog reads pass.
    const cases: ReadonlyArray<readonly [string, (workspaceId: string) => Promise<void>, RegExp]> = [
      ["missing-schema", async (workspaceId) => { await db.doc(`workspaces/${workspaceId}`).update({ schemaVersion: FieldValue.delete() }); }, /workspace-schema-unsupported/],
      ["unsupported-schema", async (workspaceId) => { await db.doc(`workspaces/${workspaceId}`).update({ schemaVersion: 4 }); }, /workspace-schema-unsupported/],
      ["unrecognized-status", async (workspaceId) => { await db.doc(`workspaces/${workspaceId}`).update({ status: "archived" }); }, /workspace-status-invalid/]
    ];
    for (const [caseName, damage, expected] of cases) {
      const uid = `ws_schema_${caseName.replace(/-/g, "_")}`;
      const workspaceId = `ws_guarded_${caseName.replace(/-/g, "_")}`;
      const idempotencyKey = `ws-schema-${caseName}`;
      await seed(uid, workspaceId);
      await damage(workspaceId);
      const before = {
        workspace: (await db.doc(`workspaces/${workspaceId}`).get()).data(),
        account: (await db.doc(`users/${uid}`).get()).data(),
        entitlement: (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(),
        receipts: (await db.collection("lifecycleCommands").get()).size
      };
      await assert.rejects(() => transitionMembership(db, activation(uid, workspaceId, idempotencyKey, 0, 1)), expected, `${caseName}: activation must reject`);
      assert.deepEqual((await db.doc(`workspaces/${workspaceId}`).get()).data(), before.workspace, `${caseName}: workspace unchanged`);
      assert.deepEqual((await db.doc(`users/${uid}`).get()).data(), before.account, `${caseName}: account unchanged`);
      assert.deepEqual((await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).data(), before.entitlement, `${caseName}: entitlement unchanged`);
      assert.equal((await entitlementState(uid)).activeMembershipCount, 0, `${caseName}: no contribution added`);
      assert.equal((await entitlementState(uid)).status, "inactive", `${caseName}: no entitlement activated`);
      assert.equal((await db.doc(`workspaces/${workspaceId}/memberships/${uid}`).get()).exists, false, `${caseName}: no membership written`);
      assert.equal((await db.collection("lifecycleCommands").get()).size, before.receipts, `${caseName}: no receipt`);

      // A valid workspace still activates normally, and the same damaged workspace still allows a
      // withdrawal once it has contributed: activate while valid, damage it, then revoke.
      const validUid = `${uid}_valid`;
      const validWorkspace = `${workspaceId}_valid`;
      await seed(validUid, validWorkspace);
      const activated = await transitionMembership(db, activation(validUid, validWorkspace, `${idempotencyKey}-valid`, 0, 1));
      assert.equal(activated.entitlementStatus, "active", `${caseName}: valid activation still works`);
      await damage(validWorkspace);
      const revoked = await transitionMembership(db, activation(validUid, validWorkspace, `${idempotencyKey}-revoke`, 1, 2, { nextStatus: "revoked" }));
      assert.equal(revoked.activeMembershipCount, 0, `${caseName}: withdrawal still removes the contribution`);
      assert.equal(revoked.entitlementStatus, "inactive", `${caseName}: withdrawal never activates`);
      assert.equal((await entitlementState(validUid)).status, "inactive");
    }

    // Suspension only removes contributions, so a schema-damaged workspace can still be suspended.
    const suspendUid = "ws_schema_suspend";
    const suspendWorkspace = "ws_guarded_suspend";
    await seed(suspendUid, suspendWorkspace);
    await transitionMembership(db, activation(suspendUid, suspendWorkspace, "ws-schema-suspend-activate", 0, 1));
    await db.doc(`workspaces/${suspendWorkspace}`).update({ schemaVersion: 9 });
    assert.equal((await transitionWorkspace(db, workspaceTransition(suspendWorkspace, "ws-schema-suspend", 2, "suspended"))).affected, 1);
    assert.deepEqual(await workspaceCounts(suspendWorkspace), { roster: 1, contributions: 0, revision: 3, status: "suspended" });
    assert.equal((await entitlementState(suspendUid)).activeMembershipCount, 0);
    assert.equal((await entitlementState(suspendUid)).status, "inactive");

    // Restoration adds contributions again, so a schema-damaged suspended workspace must not
    // restore: the rejection is atomic and a trusted repair restores it normally.
    const restoreUid = "ws_schema_restore";
    const restoreWorkspace = "ws_guarded_restore";
    await seed(restoreUid, restoreWorkspace);
    await transitionMembership(db, activation(restoreUid, restoreWorkspace, "ws-schema-restore-activate", 0, 1));
    await transitionWorkspace(db, workspaceTransition(restoreWorkspace, "ws-schema-restore-suspend", 2, "suspended"));
    await db.doc(`workspaces/${restoreWorkspace}`).update({ schemaVersion: 3 });
    const beforeRestore = {
      workspace: (await db.doc(`workspaces/${restoreWorkspace}`).get()).data(),
      membership: (await db.doc(`workspaces/${restoreWorkspace}/memberships/${restoreUid}`).get()).data(),
      entitlement: (await db.doc(`users/${restoreUid}/authorizations/systemCatalog`).get()).data(),
      receipts: (await db.collection("lifecycleCommands").get()).size
    };
    await assert.rejects(
      () => transitionWorkspace(db, workspaceTransition(restoreWorkspace, "ws-schema-restore", 3, "active")),
      /workspace-schema-unsupported/
    );
    assert.deepEqual((await db.doc(`workspaces/${restoreWorkspace}`).get()).data(), beforeRestore.workspace, "rejected restore leaves the workspace unchanged");
    assert.deepEqual((await db.doc(`workspaces/${restoreWorkspace}/memberships/${restoreUid}`).get()).data(), beforeRestore.membership, "rejected restore leaves the membership unchanged");
    assert.deepEqual((await db.doc(`users/${restoreUid}/authorizations/systemCatalog`).get()).data(), beforeRestore.entitlement, "rejected restore leaves the entitlement unchanged");
    assert.equal((await db.collection("lifecycleCommands").get()).size, beforeRestore.receipts, "rejected restore records no receipt");

    await db.doc(`workspaces/${restoreWorkspace}`).update({ schemaVersion: 1 });
    assert.equal((await transitionWorkspace(db, workspaceTransition(restoreWorkspace, "ws-schema-restore", 3, "active"))).affected, 1);
    assert.deepEqual(await workspaceCounts(restoreWorkspace), { roster: 1, contributions: 1, revision: 4, status: "active" });
    assert.equal((await entitlementState(restoreUid)).activeMembershipCount, 1);
    assert.equal((await entitlementState(restoreUid)).status, "active");
  });
});
