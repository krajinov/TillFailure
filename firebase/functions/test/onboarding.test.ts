import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import { emulatorFirestore } from "../src/environment.js";
import { completeOnboarding, onboardingFailure } from "../src/onboarding.js";
import { commandId, stableHash } from "../src/hashing.js";

const db = emulatorFirestore(), auth = getAuth();
const request = (key = "complete", patch = {}) => ({ action: "complete-onboarding", workspaceId: "onboard_ws", idempotencyKey: key,
  onboarding: { displayName: "Client", locale: "bs-BA", timeZoneId: "Europe/Sarajevo", acknowledged: true,
    expectedAccountRevision: 0, expectedOnboardingRevision: 0, ...patch } });
const call = (data: unknown = request(), uid: string | undefined = "onboard_client") => completeOnboarding(db, uid, data);
const paths = ["users/onboard_client", "users/onboard_client/authorizations/systemCatalog", "users/onboard_client/membershipRefs/onboard_ws",
  "workspaces/onboard_ws", "workspaces/onboard_ws/memberships/onboard_client", "workspaces/onboard_ws/clientProfiles/onboard_client"];
async function snapshot() {
  return stableHash([...(await db.getAll(...paths.map(p => db.doc(p)))), ...(await db.collection("lifecycleCommands").get()).docs]
    .map(d => ({ path: d.ref.path, data: d.data(), updated: d.updateTime?.toMillis() })));
}
async function rejected(data: unknown, reason: RegExp, uid: string | null = "onboard_client") {
  const before = await snapshot(); await assert.rejects(completeOnboarding(db, uid ?? undefined, data), reason); assert.equal(await snapshot(), before);
}
describe("minimal onboarding emulator transaction", () => {
  beforeEach(async () => {
    const host = process.env.FIRESTORE_EMULATOR_HOST;
    await fetch(`http://${host}/emulator/v1/projects/demo-tillfailure-m3/databases/(default)/documents`, { method: "DELETE" });
    for (const uid of ["onboard_client", "onboard_other"]) {
      await auth.deleteUser(uid).catch(() => {});
      await auth.createUser({ uid, email: `${uid}@example.invalid`, emailVerified: true });
    }
    const b = db.batch();
    b.set(db.doc(paths[0]!), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1,
      emailNormalized: "onboard_client@example.invalid", displayName: "", unrelatedPreference: "preserve" });
    b.set(db.doc(paths[1]!), { schemaVersion: 1, revision: 1, status: "active", activeMembershipCount: 1 });
    b.set(db.doc(paths[2]!), { schemaVersion: 1, status: "active" });
    b.set(db.doc(paths[3]!), { schemaVersion: 1, status: "active", ownerUid: "trainer", membershipRevision: 2,
      activeRosterCount: 2, catalogContributionCount: 2 });
    b.set(db.doc(paths[4]!), { schemaVersion: 1, workspaceId: "onboard_ws", userId: "onboard_client", role: "client",
      status: "active", revision: 1, catalogContributionActive: true });
    b.set(db.doc(paths[5]!), { schemaVersion: 1, workspaceId: "onboard_ws", userId: "onboard_client", trainerId: "trainer",
      onboardingStatus: "pending", createdAt: Timestamp.now() });
    await b.commit();
  });
  it("atomically completes, preserves unrelated global fields and all authorization/counters, and replays without writes", async () => {
    const unchanged = async () => stableHash((await db.getAll(...paths.slice(1,5).map(p => db.doc(p)))).map(d => ({ data: d.data(), time: d.updateTime?.toMillis() })));
    const before = await unchanged(); const result = await call();
    assert.deepEqual(result, { workspaceId: "onboard_ws", status: "complete", accountRevision: 1, onboardingRevision: 1, replayed: false });
    assert.equal(await unchanged(), before);
    const a = await db.doc(paths[0]!).get(), c = await db.doc(paths[5]!).get();
    assert.equal(a.get("unrelatedPreference"), "preserve"); assert.equal(a.get("lifecycleRevision"), 1);
    assert.equal(c.get("trainerId"), "trainer"); assert.equal(c.get("userId"), "onboard_client");
    const committed = await snapshot(); assert.equal((await call()).replayed, true); assert.equal(await snapshot(), committed);
    await rejected(request("complete", { displayName: "Different" }), /idempotency-key-reused/);
    await rejected(request("other"), /already-complete/);
  });
  it("has one concurrent winner and matching parallel replay changes revisions exactly once", async () => {
    const results = await Promise.all([call(), call()]); assert.equal(results.filter(r => !r.replayed).length, 1);
    assert.equal((await db.doc(paths[5]!).get()).get("onboardingRevision"), 1);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 1);
  });
  it("rejects stale revisions, conflicting identities, extra/immutable fields, bad types and size bounds atomically", async () => {
    await rejected(request("stale", { expectedAccountRevision: 1 }), /revision-conflict/);
    await rejected(request("stale2", { expectedOnboardingRevision: 1 }), /revision-conflict/);
    for (const patch of [{ role: "trainer" }, { userId: "onboard_other" }, { acknowledged: false }, { displayName: 123 },
      { displayName: "A".repeat(81) }, { displayName: "a\n" }, { locale: "not a locale" }, { timeZoneId: "fake/Zone" },
      { expectedAccountRevision: "0" }, { expectedOnboardingRevision: -1 }, { expectedOnboardingRevision: true }])
      await rejected(request("invalid", patch), /input-invalid/);
    await rejected({ ...request(), callerUid: "onboard_client" }, /input-invalid/);
    await rejected(request(), /unauthenticated/, null);
    await rejected(request(), /unauthorized/, "onboard_other");
    await rejected({ ...request(), workspaceId: "other_ws" }, /unauthorized/);
  });
  it("rechecks fresh Auth and current authority even for matching receipts", async () => {
    await call();
    await auth.updateUser("onboard_client", { emailVerified: false }); await rejected(request(), /unverified-email/);
    await auth.updateUser("onboard_client", { emailVerified: true, disabled: true }); await rejected(request(), /unauthorized/);
    await auth.updateUser("onboard_client", { disabled: false });
    await db.doc(paths[4]!).update({ status: "revoked" }); await rejected(request(), /unauthorized/);
  });
  it("never repairs malformed profile/account/membership records", async () => {
    for (const [path, changes] of [[paths[0], { profileRevision: "0" }], [paths[4], { workspaceId: "foreign" }],
      [paths[5], { userId: "foreign" }], [paths[5], { onboardingRevision: false }], [paths[5], { sensitiveField: "unexpected" }]] as const) {
      const original = (await db.doc(path!).get()).data()!;
      await db.doc(path!).update(changes); await rejected(request(), /unauthorized|record-invalid/);
      await db.doc(path!).set(original);
    }
  });
  it("preserves state on an Auth infrastructure outage and reports availability rather than revocation", async () => {
    const original = auth.getUser;
    auth.getUser = async () => { throw Object.assign(new Error("private-auth-diagnostic"), { code: "auth/internal-error" }); };
    try {
      await rejected(request(), /auth-lookup-unavailable/);
      assert.deepEqual(onboardingFailure(new Error("auth-lookup-unavailable")), { code: "internal", reason: "unavailable" });
    } finally { auth.getUser = original; }
  });

  it("rejects disabled/suspended/malformed authority without changing or repairing any target", async () => {
    for (const [path, patch] of [[paths[0], { accountStatus: "disabled" }], [paths[3], { status: "suspended" }],
      [paths[3], { activeRosterCount: "2" }], [paths[4], { role: "trainer" }], [paths[4], { revision: false }],
      [paths[2], { status: "revoked" }]] as const) {
      const original = (await db.doc(path!).get()).data()!;
      await db.doc(path!).update(patch); await rejected(request(), /unauthorized/); await db.doc(path!).set(original);
    }
    await rejected(request("huge", { expectedAccountRevision: 2147483646 }), /input-invalid/);
  });

  it("callable derives UID from Auth and rejects missing/forged authority", async () => {
    await auth.updateUser("onboard_client", { password: "fixture-emulator-only" });
    const login = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake-key`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "onboard_client@example.invalid", password: "fixture-emulator-only", returnSecureToken: true }) });
    const token = (await login.json() as { idToken: string }).idToken;
    assert.ok(token);
    const endpoint = `http://127.0.0.1:${process.env.TF_FUNCTIONS_EMULATOR_PORT ?? 5001}/demo-tillfailure-m3/europe-west1/onboardingEmulator`;
    const invoke = (data: unknown, bearer?: string) => fetch(endpoint, { method: "POST", headers: {
      "Content-Type": "application/json", ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) }, body: JSON.stringify({ data }) });
    const before = await snapshot();
    assert.equal((await invoke(request())).status, 401);
    assert.equal((await invoke({ ...request(), callerUid: "onboard_other" }, token)).status, 400);
    assert.equal(await snapshot(), before);
    assert.equal((await invoke(request(), token)).status, 200);
  });

  it("binds receipt identity and rejects a tampered receipt result without rewriting it", async () => {
    await call();
    await db.doc(`lifecycleCommands/${commandId("onboard_client", "complete")}`).update({ "result.accountRevision": 50 });
    await rejected(request(), /record-invalid/);
    assert.equal(onboardingFailure(new Error("private-diagnostic")).reason, "unavailable");
  });
});
