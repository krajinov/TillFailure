import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { MembershipTransition, setAccountEnabled, transitionMembership, transitionWorkspace } from "../src/catalog.js";
import { emulatorFirestore } from "../src/environment.js";

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
    assert.deepEqual(await workspaceCounts("ws"), { roster: 2, contributions: 2, revision: 3, status: "active" });
    await assert.rejects(() => transitionWorkspace(db, workspaceTransition("ws", "too-large", 3, "suspended", 1)), /workspace-roster-bound-violated/);
    assert.deepEqual(await workspaceCounts("ws"), { roster: 2, contributions: 2, revision: 3, status: "active" });
  });

  it("restores below and exactly at the account cap, but rejects above it atomically", async () => {
    for (const [uid, startingCount, cap] of [["below", 18, 20], ["exact", 19, 20]] as const) {
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
    assert.equal((await db.collection("lifecycleCommands").where("commandKind", "==", "workspace-transition").get()).size, 2);
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
});
