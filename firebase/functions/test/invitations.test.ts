import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { emulatorFirestore } from "../src/environment.js";
import { invitationOperation, invitationFailure, InvitationResult } from "../src/invitations.js";
import { provisionTrainer } from "../src/provisioning.js";
import { commandId, stableHash } from "../src/hashing.js";

const db = emulatorFirestore();
const auth = getAuth();
const password = "emulatorFixtureOnly!";
const call = (uid: string | undefined, data: unknown) => invitationOperation(db, uid, data);
const create = (key = "create", wid = "invite_ws", address = "invite-client@example.invalid") =>
  call("invite_trainer", { action: "create", workspaceId: wid, email: address, idempotencyKey: key });
const clientCommand = (r: InvitationResult, action = "accept", key = "accept") => ({ action,
  invitationId: r.invitationId, token: r.deliveryToken, expectedVersion: r.tokenVersion,
  ...(action === "accept" ? { idempotencyKey: key } : {}) });
const trainerCommand = (r: InvitationResult, action: string, key = action) => ({ action,
  invitationId: r.invitationId, workspaceId: "invite_ws", expectedVersion: r.tokenVersion, idempotencyKey: key });
async function protectedState() {
  const paths = ["users/invite_client", "users/invite_client/authorizations/systemCatalog", "users/invite_client/membershipRefs/invite_ws",
    "workspaces/invite_ws", "workspaces/invite_ws/memberships/invite_client", "workspaces/invite_ws/clientProfiles/invite_client"];
  const docs = await db.getAll(...paths.map(p => db.doc(p)));
  const invitations = await db.collection("invitations").get();
  const summaries = await db.collection("workspaces/invite_ws/invitationSummaries").get();
  const receipts = await db.collection("lifecycleCommands").get();
  return [...docs, ...invitations.docs, ...summaries.docs, ...receipts.docs].map(d => ({ path: d.ref.path, data: d.data(), time: d.updateTime?.toMillis() }));
}
async function rejection(uid: string | undefined, request: unknown, reason: RegExp) {
  const before = await protectedState();
  await assert.rejects(call(uid, request), reason);
  assert.equal(stableHash(await protectedState()), stableHash(before));
}
it("keeps unexpected backend failures ambiguous and redacts their diagnostics", () => {
  assert.deepEqual(invitationFailure(new Error("transport-after-commit-private-diagnostic")),
    { code: "internal", reason: "invitation-unavailable" });
  assert.deepEqual(invitationFailure(null), { code: "internal", reason: "invitation-unavailable" });
  assert.deepEqual(invitationFailure(new Error("expired")), { code: "failed-precondition", reason: "expired" });
  assert.deepEqual(invitationFailure(new Error("invitation-unavailable")), { code: "failed-precondition", reason: "invitation-unavailable" });
});
describe("guarded local invitations", () => {
  beforeEach(async () => {
    for (const name of ["users", "workspaces", "invitations", "invitationRateLimits", "invitationRequestLimits", "lifecycleCommands", "operatorAudit"]) await db.recursiveDelete(db.collection(name));
    for (const uid of ["invite_trainer", "invite_client", "invite_other"]) await auth.deleteUser(uid).catch(() => {});
    for (const [uid, address] of [["invite_trainer", "invite-trainer@example.invalid"], ["invite_client", "invite-client@example.invalid"], ["invite_other", "invite-other@example.invalid"]]) {
      await auth.createUser({ uid: uid!, email: address!, emailVerified: true, password });
    }
    await provisionTrainer(db, { uid: "invite_trainer", workspaceId: "invite_ws", workspaceName: "Invitation Workspace",
      idempotencyKey: "bootstrap", expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0 },
    { uid: "invite_trainer", email: "invite-trainer@example.invalid", emailVerified: true, disabled: false },
    { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false },
    { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
  });

  it("creates safe summary, previews without a grant, accepts all records once and freshly authorized replay changes nothing", async () => {
    const r = await create();
    assert.ok(/^[A-Za-z0-9_-]{43}$/.test(r.deliveryToken!), "Token shape invalid");
    const internal = (await db.doc(`invitations/${r.invitationId}`).get()).data()!;
    assert.equal(internal.tokenVersion, 1); assert.ok(!JSON.stringify(internal).includes(r.deliveryToken!));
    const list = await call("invite_trainer", { action: "list", workspaceId: "invite_ws" });
    assert.equal(list.summaries?.[0]?.emailMasked, "i***@example.invalid");
    assert.ok(!JSON.stringify(list).includes(internal.tokenHash));
    assert.equal((await call("invite_client", clientCommand(r, "preview"))).workspaceName, "Invitation Workspace");
    assert.equal((await db.doc("users/invite_client").get()).exists, false);
    const beforeCreateReplay = await protectedState();
    const repeatedCreate = await create(); assert.equal(repeatedCreate.replayed, true); assert.ok(repeatedCreate.deliveryToken === undefined, "Receipt must not expose a token");
    assert.equal(stableHash(await protectedState()), stableHash(beforeCreateReplay));
    const accepted = await call("invite_client", clientCommand(r)); assert.equal(accepted.status, "accepted");
    const after = await protectedState();
    assert.equal((await db.doc("workspaces/invite_ws").get()).get("activeRosterCount"), 2);
    assert.equal((await db.doc("workspaces/invite_ws").get()).get("catalogContributionCount"), 2);
    assert.equal((await db.doc("users/invite_client/authorizations/systemCatalog").get()).get("activeMembershipCount"), 1);
    assert.equal((await db.doc("workspaces/invite_ws/clientProfiles/invite_client").get()).get("onboardingStatus"), "pending");
    assert.equal((await call("invite_client", clientCommand(r))).replayed, true);
    assert.equal(stableHash(await protectedState()), stableHash(after));
    await rejection("invite_client", clientCommand(r, "accept", "new-key"), /already-used/);
    await rejection("invite_client", { ...clientCommand(r), token: "A".repeat(43) }, /idempotency-key-reused/);
    await rejection("invite_other", clientCommand(r), /wrong-account-email/);
    await rejection("invite_trainer", { action: "create", workspaceId: "invite_ws", email: "invite-other@example.invalid", idempotencyKey: "create" }, /idempotency-key-reused/);
  });

  it("rotates old tokens, replays resend without retaining a token, then revokes immediately", async () => {
    const old = await create();
    const rotated = await call("invite_trainer", trainerCommand(old, "resend"));
    assert.equal(rotated.tokenVersion, 2); assert.ok(rotated.deliveryToken !== old.deliveryToken, "Expected token rotation");
    const before = await protectedState();
    assert.equal((await call("invite_trainer", trainerCommand(old, "resend"))).replayed, true);
    assert.equal(stableHash(await protectedState()), stableHash(before));
    await rejection("invite_client", clientCommand(old), /rotated/);
    await rejection("invite_client", { ...clientCommand(rotated), token: old.deliveryToken }, /invitation-unavailable/);
    await call("invite_trainer", trainerCommand(rotated, "revoke"));
    await rejection("invite_client", clientCommand(rotated), /revoked/);
    assert.equal((await call("invite_trainer", trainerCommand(rotated, "revoke"))).replayed, true);
    await rejection("invite_trainer", trainerCommand(rotated, "resend", "after-revoke"), /revoked/);
  });

  it("rejects unauthenticated, forged identity, malformed input and cross-tenant operations atomically", async () => {
    const r = await create();
    await rejection(undefined, clientCommand(r), /unauthenticated/);
    for (const request of [{ ...clientCommand(r), callerUid: "invite_client" }, { ...clientCommand(r), email: "invite-client@example.invalid" },
      { ...clientCommand(r), invitationId: "bad/path" }, { ...clientCommand(r), expectedVersion: 1.5 },
      { ...clientCommand(r), token: "short" }, { ...trainerCommand(r, "resend"), workspaceId: "other_ws" }]) {
      await rejection("invite_client", request, /invalid|path|unauthorized/);
    }
    await rejection("invite_other", trainerCommand(r, "revoke"), /unauthorized/);
    await rejection("invite_other", { action: "list", workspaceId: "invite_ws" }, /unauthorized/);
  });

  it("uses fresh Auth email/verification and rejects disabled, changed or same-email different UID identities", async () => {
    const r = await create();
    await auth.updateUser("invite_client", { emailVerified: false });
    await rejection("invite_client", clientCommand(r), /unverified-email/);
    await auth.updateUser("invite_client", { emailVerified: true, email: "changed@example.invalid" });
    await auth.updateUser("invite_client", { emailVerified: true });
    await rejection("invite_client", clientCommand(r), /wrong-account-email/);
    await auth.updateUser("invite_other", { email: "invite-client@example.invalid" });
    await auth.updateUser("invite_other", { emailVerified: true });
    await rejection("invite_other", clientCommand(r), /wrong-account-email/);
    await auth.updateUser("invite_client", { email: "invite-client@example.invalid", disabled: true }).catch(async () => {
      await auth.updateUser("invite_other", { email: "invite-other@example.invalid" });
      await auth.updateUser("invite_client", { email: "invite-client@example.invalid", disabled: true });
    });
    await rejection("invite_client", clientCommand(r), /unauthorized/);
  });

  it("expires by server time and allows explicit resend of pending expired links", async () => {
    const r = await create();
    const expiry = Timestamp.fromMillis(Date.now() - 1000);
    await db.doc(`invitations/${r.invitationId}`).update({ expiresAt: expiry });
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r.invitationId}`).update({ expiresAt: expiry });
    await rejection("invite_client", clientCommand(r), /expired/);
    const newer = await call("invite_trainer", trainerCommand(r, "resend"));
    assert.equal((await call("invite_client", clientCommand(newer))).status, "accepted");
  });

  it("concurrent acceptance commits once; same command contenders replay and different commands lose", async () => {
    const r = await create();
    const results = await Promise.all([call("invite_client", clientCommand(r)), call("invite_client", clientCommand(r))]);
    assert.equal(results.filter(r => r.replayed === false).length, 1);
    assert.equal((await db.doc("workspaces/invite_ws").get()).get("activeRosterCount"), 2);
    const second = await create("second", "invite_ws", "invite-other@example.invalid");
    const contenders = await Promise.allSettled([call("invite_other", clientCommand(second, "accept", "a")), call("invite_other", clientCommand(second, "accept", "b"))]);
    assert.equal(contenders.filter(r => r.status === "fulfilled").length, 1);
    assert.equal((await db.doc("workspaces/invite_ws").get()).get("activeRosterCount"), 3);
  });

  for (const action of ["revoke", "resend"]) it(`accept/${action} race has one valid transition with no losing receipt`, async () => {
    const r = await create();
    const contenders = await Promise.allSettled([call("invite_client", clientCommand(r)), call("invite_trainer", trainerCommand(r, action))]);
    assert.equal(contenders.filter(r => r.status === "fulfilled").length, 1);
    const d = await db.doc(`invitations/${r.invitationId}`).get();
    const accepted = d.get("status") === "accepted";
    assert.equal((await db.doc("users/invite_client").get()).exists, accepted);
    assert.equal((await db.doc("workspaces/invite_ws").get()).get("activeRosterCount"), accepted ? 2 : 1);
    const receipts = await db.getAll(db.doc(`lifecycleCommands/${commandId("invite_client", "accept")}`), db.doc(`lifecycleCommands/${commandId("invite_trainer", action)}`));
    assert.equal(receipts.filter(d => d.exists).length, 1);
  });

  it("rejects revoked authority, malformed identities, conflicting profile and drifted counters without repair", async () => {
    const r = await create();
    for (const [path, field, value] of [
      ["users/invite_trainer", "accountStatus", "disabled"], ["users/invite_trainer", "emailNormalized", "changed@example.invalid"],
      ["workspaces/invite_ws", "status", "suspended"], ["workspaces/invite_ws", "activeRosterCount", 4],
      ["workspaces/invite_ws/memberships/invite_trainer", "status", "revoked"],
      ["workspaces/invite_ws/memberships/invite_trainer", "workspaceId", "other"],
      ["workspaces/invite_ws/memberships/invite_trainer", "userId", "other"],
      ["workspaces/invite_ws/memberships/invite_trainer", "role", "client"],
      ["workspaces/invite_ws/memberships/invite_trainer", "schemaVersion", 2]
    ] as const) {
      const ref = db.doc(path); const before = (await ref.get()).data()!;
      await ref.update({ [field]: value });
      await rejection("invite_client", clientCommand(r), /unauthorized|counter-inconsistent/);
      await ref.set(before);
    }
    await db.doc("workspaces/invite_ws/clientProfiles/invite_client").set({ schemaVersion: 1, userId: "other" });
    await rejection("invite_client", clientCommand(r), /membership-conflict/);
    await db.doc("workspaces/invite_ws/clientProfiles/invite_client").delete();
    await db.doc("users/invite_client").set({ schemaVersion: 1, accountStatus: "disabled", emailNormalized: "invite-client@example.invalid", lifecycleRevision: 1 });
    await rejection("invite_client", clientCommand(r), /unauthorized/);
    await db.doc("users/invite_client").delete();
    await auth.updateUser("invite_trainer", { disabled: true });
    await rejection("invite_client", clientCommand(r), /unauthorized/);
  });

  it("enforces roster cap against authoritative scan and leaves entitlement/counters unchanged", async () => {
    const r = await create();
    for (let i = 0; i < 4; i++) await db.doc(`workspaces/invite_ws/memberships/roster${i}`).set({ schemaVersion: 1,
      workspaceId: "invite_ws", userId: `roster${i}`, role: "client", status: "active", catalogContributionActive: true, revision: 1 });
    await db.doc("workspaces/invite_ws").update({ activeRosterCount: 5, catalogContributionCount: 5 });
    await rejection("invite_client", clientCommand(r), /workspace-roster-bound-violated/);
  });

  it("enforces account contribution cap and rejects malformed entitlement/reference state", async () => {
    const r = await create();
    await db.doc("users/invite_client").set({ schemaVersion: 1, accountStatus: "active", emailNormalized: "invite-client@example.invalid", lifecycleRevision: 1 });
    await db.doc("users/invite_client/authorizations/systemCatalog").set({ schemaVersion: 1, status: "active", activeMembershipCount: 2, revision: 1 });
    for (const wid of ["one", "two"]) {
      await db.doc(`users/invite_client/membershipRefs/${wid}`).set({ schemaVersion: 1, status: "active" });
      await db.doc(`workspaces/${wid}`).set({ schemaVersion: 1, status: "active" });
      await db.doc(`workspaces/${wid}/memberships/invite_client`).set({ schemaVersion: 1, workspaceId: wid,
        userId: "invite_client", role: "client", status: "active", catalogContributionActive: true, revision: 1 });
    }
    await rejection("invite_client", clientCommand(r), /membership-bound-violated/);
    await db.doc("users/invite_client/authorizations/systemCatalog").update({ activeMembershipCount: "2" });
    await rejection("invite_client", clientCommand(r), /counter-inconsistent/);
  });

  it("rejects an orphan active membership omitted from the caller directory atomically", async () => {
    const r = await create();
    await db.doc("users/invite_client").set({ schemaVersion: 1, accountStatus: "active", emailNormalized: "invite-client@example.invalid", lifecycleRevision: 1 });
    await db.doc("users/invite_client/authorizations/systemCatalog").set({ schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1 });
    const orphan = db.doc("workspaces/orphan/memberships/invite_client");
    await orphan.set({ schemaVersion: 1, workspaceId: "orphan", userId: "invite_client", role: "client", status: "active", catalogContributionActive: true, revision: 1 });
    const before = await orphan.get();
    await rejection("invite_client", clientCommand(r), /counter-inconsistent/);
    assert.equal((await orphan.get()).updateTime?.toMillis(), before.updateTime?.toMillis());
  });

  it("rejects retained assignment headers beneath an absent relationship without exposing or rewriting them", async () => {
    const r = await create();
    const header = db.doc("users/invite_client/workspaces/invite_ws/assignedPrograms/retained");
    await header.set({ schemaVersion: 1, clientId: "invite_client", workspaceId: "invite_ws", assignmentId: "retained", lifecycleState: "ready", accessStatus: "active" });
    const before = await header.get();
    await rejection("invite_client", clientCommand(r), /membership-conflict/);
    assert.equal((await header.get()).updateTime?.toMillis(), before.updateTime?.toMillis());
  });

  it("rejects replay after membership revocation and corrupt projection/receipt without modifying authority", async () => {
    const r = await create(); await call("invite_client", clientCommand(r));
    await db.doc("workspaces/invite_ws/memberships/invite_client").update({ status: "revoked" });
    await rejection("invite_client", clientCommand(r), /unauthorized|counter-inconsistent/);
    await db.doc("workspaces/invite_ws/memberships/invite_client").update({ status: "active" });
    const receipt = db.doc(`lifecycleCommands/${commandId("invite_client", "accept")}`);
    const original = (await receipt.get()).data()!;
    for (const corrupt of [{ status: "pending" }, { tokenVersion: 2 }, { expiresAtMillis: 0 }, { workspaceId: "other" }]) {
      await receipt.update({ result: { ...original.result, ...corrupt } });
      await rejection("invite_client", clientCommand(r), /record-invalid/);
      await receipt.set(original);
    }
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r.invitationId}`).update({ tokenHash: "not-safe" });
    await rejection("invite_client", clientCommand(r), /record-invalid/);
  });

  it("derives caller from callable Auth and rejects missing authentication and forged caller fields", async () => {
    const endpoint = `http://127.0.0.1:${process.env.TF_FUNCTIONS_EMULATOR_PORT ?? 5001}/demo-tillfailure-m3/europe-west1/invitationEmulator`;
    const invoke = async (data: unknown, token?: string) => {
      const response = await fetch(endpoint, { method: "POST", headers: { "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify({ data }) });
      return { status: response.status, data: await response.json() as { result?: InvitationResult; error?: { message: string } } };
    };
    const body = { action: "create", workspaceId: "invite_ws", email: "invite-client@example.invalid", idempotencyKey: "callable" };
    assert.equal((await invoke(body)).status, 401);
    const session = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fixture`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "invite-trainer@example.invalid", password, returnSecureToken: true })
    });
    const token = (await session.json() as { idToken: string }).idToken;
    assert.ok(token);
    const forged = await invoke({ ...body, callerUid: "invite_client" }, token);
    assert.equal(forged.data.error?.message, "input-invalid");
    const made = await invoke(body, token);
    assert.equal(made.status, 200); assert.ok(made.data.result?.deliveryToken);
    const before = await protectedState();
    assert.equal((await invoke(body, token)).data.result?.replayed, true);
    assert.equal(stableHash(await protectedState()), stableHash(before));
  });

  async function unknownCommand(action: string) {
    const request = action === "create"
      ? { action, workspaceId: "invite_ws", email: "invite-client@example.invalid", idempotencyKey: "unknown" }
      : action === "accept" ? clientCommand(await create(), action, "unknown")
      : trainerCommand(await create(), action, "unknown");
    return { uid: action === "accept" ? "invite_client" : "invite_trainer", request };
  }
  async function exhaust(uid: string) {
    await db.doc(`invitationRateLimits/${stableHash(uid)}`).set({ minute: Math.floor(Date.now() / 60000), count: 30 });
  }
  for (const action of ["create", "resend", "revoke", "accept"]) {
    it(`P1 ${action}: lost response replays through exhausted admission quota with one protected commit`, async () => {
      const { uid, request } = await unknownCommand(action);
      await call(uid, request); // Commit happened; the caller loses this reply.
      const protectedBefore = stableHash(await protectedState());
      await exhaust(uid);
      const quotaBefore = (await db.doc(`invitationRateLimits/${stableHash(uid)}`).get()).data();
      const replay = await call(uid, request);
      assert.equal(replay.replayed, true);
      assert.equal(stableHash(await protectedState()), protectedBefore);
      assert.deepEqual((await db.doc(`invitationRateLimits/${stableHash(uid)}`).get()).data(), quotaBefore);
      const receipts = await db.collection("lifecycleCommands").where("callerUid", "==", uid)
        .where("commandKind", "==", `invitation-${action}`).get();
      assert.equal(receipts.size, 1);
      // Conflicting payloads and fresh authority revocation still reject under exhausted quota.
      const changed = action === "create" ? { ...request, email: "invite-other@example.invalid" }
        : { ...request, expectedVersion: 9 };
      await rejection(uid, changed, /idempotency-key-reused/);
      await auth.updateUser(uid, { disabled: true });
      await rejection(uid, request, /unauthorized/);
      await auth.updateUser(uid, { disabled: false });
      await auth.deleteUser(uid);
      await rejection(uid, request, /unauthorized/);
      await auth.createUser({ uid, email: uid === "invite_client" ? "invite-client@example.invalid" : "invite-trainer@example.invalid",
        emailVerified: true, password });
      assert.equal((await call(uid, request)).replayed, true);
    });
    it(`P1 ${action}: a throttled retry cannot rule out the earlier in-flight invocation`, async t => {
      const { uid, request } = await unknownCommand(action);
      const original = auth.getUser.bind(auth);
      let release!: () => void; let entered!: () => void;
      const held = new Promise<void>(resolve => { release = resolve; });
      const started = new Promise<void>(resolve => { entered = resolve; });
      let first = true;
      const lookup = t.mock.method(auth, "getUser", async (value: string) => {
        if (first) { first = false; entered(); await held; }
        return original(value);
      });
      const earlier = call(uid, request); await started;
      try {
        await exhaust(uid);
        await rejection(uid, request, /rate-limited/);
        assert.equal((await db.doc(`lifecycleCommands/${commandId(uid, "unknown")}`).get()).exists, false);
        // Service/quota recovery permits the still-running original invocation to commit later.
        await db.doc(`invitationRateLimits/${stableHash(uid)}`).delete();
      } finally { release(); lookup.mock.restore(); }
      await earlier;
      const committed = stableHash(await protectedState());
      await exhaust(uid);
      assert.equal((await call(uid, request)).replayed, true);
      assert.equal(stableHash(await protectedState()), committed);
    });
    it(`P1 ${action}: transient getUser failures stay redacted and recover the same receipt`, async t => {
      const { uid, request } = await unknownCommand(action);
      await call(uid, request); const committed = stableHash(await protectedState());
      for (const code of ["auth/network-request-failed", "auth/internal-error", "ETIMEDOUT", "unexpected-outage"]) {
        const lookup = t.mock.method(auth, "getUser", async () => { throw Object.assign(new Error("private-infrastructure-diagnostic"), { code }); });
        try {
          await rejection(uid, request, /auth-lookup-unavailable/);
          try { await call(uid, request); assert.fail("Expected outage"); }
          catch (error) { assert.deepEqual(invitationFailure(error), { code: "internal", reason: "invitation-unavailable" }); }
        } finally { lookup.mock.restore(); }
      }
      assert.equal((await call(uid, request)).replayed, true);
      assert.equal(stableHash(await protectedState()), committed);
    });
  }
  it("P1 create: getUserByEmail outages are ambiguous while real missing/disabled targets reject", async t => {
    const { uid, request } = await unknownCommand("create");
    await call(uid, request); const committed = stableHash(await protectedState());
    for (const code of ["auth/network-request-failed", "auth/internal-error", "ETIMEDOUT", "unexpected-outage"]) {
      const lookup = t.mock.method(auth, "getUserByEmail", async () => { throw Object.assign(new Error("private-email-lookup-diagnostic"), { code }); });
      try {
        await rejection(uid, request, /auth-lookup-unavailable/);
      } finally { lookup.mock.restore(); }
    }
    await auth.deleteUser("invite_client");
    await rejection(uid, request, /fixture-account-required/);
    await auth.createUser({ uid: "invite_client", email: "invite-client@example.invalid", emailVerified: true, password, disabled: true });
    await rejection(uid, request, /unauthorized/);
    await auth.updateUser("invite_client", { disabled: false });
    assert.equal((await call(uid, request)).replayed, true);
    assert.equal(stableHash(await protectedState()), committed);
  });
  it("P1 request abuse remains bounded independently of admission-free receipt replay", async () => {
    const { uid, request } = await unknownCommand("create"); await call(uid, request);
    await db.doc(`invitationRequestLimits/${stableHash(uid)}`).set({ minute: Math.floor(Date.now() / 60000), count: 120 });
    await rejection(uid, request, /rate-limited/);
    await db.doc(`invitationRequestLimits/${stableHash(uid)}`).delete();
    await exhaust(uid); assert.equal((await call(uid, request)).replayed, true);
  });

  it("throttles preview attempts without storing token material or modifying invitation authority", async () => {
    const r = await create();
    await db.doc(`invitationRateLimits/${stableHash("invite_client")}`).set({ minute: Math.floor(Date.now() / 60000), count: 30 });
    await rejection("invite_client", clientCommand(r, "preview"), /rate-limited/);
    const throttle = await db.collection("invitationRateLimits").get();
    assert.ok(throttle.docs.every(d => !JSON.stringify(d.data()).includes(r.deliveryToken!)));
  });

  it("P2 invitation summary read boundary: bounded ordered application query enforces strict safe projection and fails safely on corrupt/leaky summaries", async () => {
    const r1 = await create("create-p2-test", "invite_ws", "invite-client@example.invalid");
    // The actual application query returns valid safe summaries conforming to projection
    const listResult = await call("invite_trainer", { action: "list", workspaceId: "invite_ws" });
    assert.equal(listResult.summaries?.length, 1);
    const item = listResult.summaries![0]!;
    assert.equal(item.invitationId, r1.invitationId);
    assert.equal(item.emailMasked, "i***@example.invalid");
    assert.equal(item.role, "client");
    assert.equal(item.status, "pending");
    assert.equal(item.tokenVersion, 1);
    assert.ok(typeof item.createdAtMillis === "number");
    assert.ok(typeof item.expiresAtMillis === "number");
    assert.ok(typeof item.lastSentAtMillis === "number");
    // Ensure no unexpected fields exist in the returned projection
    assert.deepEqual(Object.keys(item).sort(), [
      "createdAtMillis", "emailMasked", "expiresAtMillis", "invitationId",
      "lastSentAtMillis", "role", "status", "tokenVersion"
    ]);

    // A summary containing tokenHash cannot expose that data through list
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ tokenHash: "leaked-token-hash" });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /record-invalid/);
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ tokenHash: FieldValue.delete() });

    // A summary containing invitedEmailNormalized cannot expose that data through list
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ invitedEmailNormalized: "secret@example.invalid" });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /record-invalid/);
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ invitedEmailNormalized: FieldValue.delete() });

    // A summary containing another unexpected field cannot expose data through list
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ unexpectedSecret: "forged" });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /record-invalid/);
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ unexpectedSecret: FieldValue.delete() });

    // Missing/unsupported schema fails safely: schemaVersion 2
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ schemaVersion: 2 });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /record-invalid/);
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ schemaVersion: 1 });

    // Missing required field fails safely
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ status: FieldValue.delete() });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /record-invalid/);
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ status: "pending" });

    // Malformed projection field (e.g. non-timestamp expiresAt) fails safely
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ expiresAt: "not-a-timestamp" });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /record-invalid/);
    await db.doc(`workspaces/invite_ws/invitationSummaries/${r1.invitationId}`).update({ expiresAt: Timestamp.now() });

    // Sibling malformed document: if ONE document in the collection contains unexpected fields or malformed schema,
    // the entire query fails safely (endpoint does not redact or leak partial safe items)
    const secondSummaryRef = db.doc("workspaces/invite_ws/invitationSummaries/second-summary");
    await secondSummaryRef.set({
      schemaVersion: 1, invitationId: "second-summary", emailMasked: "s***@example.invalid",
      role: "client", status: "pending", tokenVersion: 1,
      createdAt: Timestamp.now(), expiresAt: Timestamp.now(), lastSentAt: Timestamp.now(),
      tokenHash: "corrupt-sibling"
    });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /record-invalid/);
    await secondSummaryRef.delete();

    // Cross-tenant, revoked, disabled, unverified and unauthorized readers remain denied
    await rejection("invite_other", { action: "list", workspaceId: "invite_ws" }, /unauthorized/);
    await rejection(undefined, { action: "list", workspaceId: "invite_ws" }, /unauthenticated/);

    await db.doc("workspaces/invite_ws/memberships/invite_trainer").update({ status: "revoked" });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /unauthorized/);
    await db.doc("workspaces/invite_ws/memberships/invite_trainer").update({ status: "active" });

    await auth.updateUser("invite_trainer", { disabled: true });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /unauthorized/);
    await auth.updateUser("invite_trainer", { disabled: false });

    await auth.updateUser("invite_trainer", { emailVerified: false });
    await rejection("invite_trainer", { action: "list", workspaceId: "invite_ws" }, /unverified-email/);
    await auth.updateUser("invite_trainer", { emailVerified: true });

    // After cleanup, the application query returns valid safe summaries again
    const restored = await call("invite_trainer", { action: "list", workspaceId: "invite_ws" });
    assert.equal(restored.summaries?.length, 1);
    assert.equal(restored.summaries![0]!.invitationId, r1.invitationId);
  });
});
