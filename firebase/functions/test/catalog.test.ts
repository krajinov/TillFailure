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
    const suspended = await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "suspend", workspaceId: "ws", nextStatus: "suspended", expectedMembershipRevision: revision, maxMembershipsPerWorkspace: 20, maxWrites: 200 });
    assert.equal(suspended.affected, 1);
    assert.equal((await db.doc("users/client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 0);
    const restoreRevision = (await db.doc("workspaces/ws").get()).get("membershipRevision") as number;
    await transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "restore", workspaceId: "ws", nextStatus: "active", expectedMembershipRevision: restoreRevision, maxMembershipsPerWorkspace: 20, maxWrites: 200 });
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
    await assert.rejects(() => transitionWorkspace(db, { callerUid: "admin", idempotencyKey: "too-large", workspaceId: "ws", nextStatus: "suspended", expectedMembershipRevision: workspace.get("membershipRevision"), maxMembershipsPerWorkspace: 1, maxWrites: 200 }), /bound/);
    assert.equal((await db.doc("workspaces/ws").get()).get("status"), "active");
  });
});
