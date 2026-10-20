import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import { readFileSync } from "node:fs";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, updateDoc } from "firebase/firestore";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { basicProfileOperation } from "../src/basic-profile.js";
import { clientOperation } from "../src/clients.js";
import { commandId, stableHash } from "../src/hashing.js";
const db = emulatorFirestore(), auth = getAuth(), uid = "basic_client", wid = "basic_one";
const request = (key = "save", patch = {}) => ({ action: "update-profile", workspaceId: wid, idempotencyKey: key,
  profileUpdate: { displayName: "Updated name", locale: "bs-BA", timeZoneId: "Europe/Sarajevo", expectedRevision: 1, ...patch } });
const call = (d: unknown = request(), caller: string | undefined = uid): Promise<any> => basicProfileOperation(db, caller, d);
async function snapshot() { return stableHash((await db.getAll(db.doc(`users/${uid}`),
  db.doc(`workspaces/${wid}/clientProfiles/${uid}`), db.doc(`workspaces/basic_two/clientProfiles/${uid}`),
  ...(await db.collection("lifecycleCommands").get()).docs.map(d => d.ref))).map(d => ({ path: d.ref.path, data: d.data(), time: d.updateTime?.toMillis() }))); }
async function rejected(d: unknown, reason: RegExp, caller = uid) {
  const before = await snapshot(); await assert.rejects(call(d, caller), reason); assert.equal(await snapshot(), before);
}
describe("client-owned global profile receipt transaction", () => {
  beforeEach(async () => {
    await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`, { method: "DELETE" });
    for (const id of [uid, "basic_trainer_one", "basic_trainer_two", "basic_other"]) {
      await auth.deleteUser(id).catch(() => {}); await auth.createUser({ uid: id, email: `${id}@example.invalid`, emailVerified: true });
      await db.doc(`users/${id}`).set({ schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1, emailNormalized: `${id}@example.invalid`,
        displayName: "Original", locale: "en", timeZoneId: "UTC", profileRevision: 1, unrelated: "preserve", health: "private" });
    }
    for (const [ws, trainer] of [[wid, "basic_trainer_one"], ["basic_two", "basic_trainer_two"]]) {
      const b = db.batch();
      b.set(db.doc(`workspaces/${ws}`), { schemaVersion: 1, status: "active", ownerUid: trainer, membershipRevision: 2, activeRosterCount: 2, catalogContributionCount: 2 });
      for (const [id, role] of [[uid, "client"], [trainer, "trainer"]]) {
        b.set(db.doc(`workspaces/${ws}/memberships/${id}`), { schemaVersion: 1, workspaceId: ws, userId: id, role, status: "active", revision: 1, catalogContributionActive: true });
        b.set(db.doc(`users/${id}/membershipRefs/${ws}`), { schemaVersion: 1, status: "active" });
      }
      b.set(db.doc(`workspaces/${ws}/clientProfiles/${uid}`), { schemaVersion: 1, workspaceId: ws, userId: uid, trainerId: trainer,
        createdAt: Timestamp.fromMillis(1000), onboardingStatus: "complete", onboardingRevision: 1, onboardingPolicyVersion: 1, completedAt: Timestamp.fromMillis(2000) });
      await b.commit();
    }
    await db.doc(`users/${uid}/authorizations/systemCatalog`).set({ schemaVersion: 1, revision: 1, status: "active", activeMembershipCount: 2 });
  });
  it("preserves protected fields/completion and shows updated names in both authorized workspaces; replays without writes", async () => {
    const profiles = await db.getAll(db.doc(`workspaces/${wid}/clientProfiles/${uid}`), db.doc(`workspaces/basic_two/clientProfiles/${uid}`));
    const result = await call(); assert.equal(result.status, "profile-updated"); assert.equal(result.accountRevision, 2);
    const a = (await db.doc(`users/${uid}`).get()).data()!;
    assert.equal(a.displayName, "Updated name"); assert.equal(a.unrelated, "preserve"); assert.equal(a.health, "private");
    assert.equal(a.emailNormalized, `${uid}@example.invalid`); assert.equal(a.lifecycleRevision, 1);
    for (const p of profiles) assert.deepEqual((await p.ref.get()).data(), p.data());
    for (const [ws, trainer] of [[wid, "basic_trainer_one"], ["basic_two", "basic_trainer_two"]])
      assert.deepEqual(await clientOperation(db, trainer, { action: "get", workspaceId: ws, clientId: uid }),
        { clientId: uid, workspaceId: ws, displayName: "Updated name", relationshipStatus: "active", onboardingStatus: "complete" });
    const committed = await snapshot(); assert.equal((await call()).replayed, true); assert.equal(await snapshot(), committed);
    await rejected(request("save", { displayName: "Key reuse" }), /idempotency-key-reused/);
  });
  it("concurrent identical retries update once; different saves have one update and one durable conflict", async () => {
    const results = await Promise.all([call(), call()]); assert.equal(results.filter(r => r.replayed === false).length, 1);
    const saves = await Promise.all([call(request("next-a", { expectedRevision: 2 })), call(request("next-b", { expectedRevision: 2 }))]);
    assert.deepEqual(saves.map(r => r.status).sort(), ["profile-conflict", "profile-updated"]);
    assert.equal((await db.doc(`users/${uid}`).get()).get("profileRevision"), 3);
  });
  it("stale commands preserve account fields, replay their rejection receipt, and require fresh reload/revision", async () => {
    await call(); const before = (await db.doc(`users/${uid}`).get()).updateTime!;
    const stale = request("conflict", { displayName: "Stale" }); assert.equal((await call(stale)).status, "profile-conflict");
    assert((await db.doc(`users/${uid}`).get()).updateTime!.isEqual(before));
    const after = await snapshot(); assert.equal((await call(stale)).replayed, true); assert.equal(await snapshot(), after);
    const fresh = await call({ action: "profile-get", workspaceId: wid }); assert.equal(fresh.profile!.revision, 2);
    assert.equal((await call(request("replace", { expectedRevision: fresh.profile!.revision }))).status, "profile-updated");
  });
  it("denies forged identity, trainer/foreign callers, protected fields, malformed inputs and future revisions without writes", async () => {
    for (const patch of [{ uid: "basic_other" }, { role: "trainer" }, { profileRevision: 9 }, { expectedRevision: true },
      { expectedRevision: 0 }, { expectedRevision: 2147483646 }, { displayName: "A".repeat(81) }, { displayName: "a\n" },
      { locale: "bad locale" }, { timeZoneId: "fake/Zone" }, { email: "other@example.invalid" }]) await rejected(request("invalid", patch), /input-invalid/);
    await rejected({ ...request(), callerUid: "basic_other" }, /input-invalid/);
    await rejected(request("future", { expectedRevision: 50 }), /record-invalid/);
    await rejected(request(), /unauthorized/, "basic_other"); await rejected(request(), /unauthorized/, "basic_trainer_one");
    await assert.rejects(basicProfileOperation(db, undefined, request()), /unauthenticated/);
  });
  it("fresh Auth/current authority gates reads and replay; malformed records and receipts cannot be repaired", async () => {
    await call();
    await auth.updateUser(uid, { emailVerified: false }); await rejected(request(), /unverified-email/);
    await auth.updateUser(uid, { emailVerified: true, disabled: true }); await rejected(request(), /unauthorized/);
    await auth.updateUser(uid, { disabled: false });
    for (const [path, patch, reason] of [[`users/${uid}`, { accountStatus: "disabled" }, /unauthorized/],
      [`workspaces/${wid}`, { status: "suspended" }, /unauthorized/], [`workspaces/${wid}/memberships/${uid}`, { status: "revoked" }, /unauthorized/],
      [`users/${uid}/membershipRefs/${wid}`, { status: "revoked" }, /unauthorized/], [`workspaces/${wid}/memberships/${uid}`, { workspaceId: "foreign" }, /unauthorized/],
      [`users/${uid}`, { profileRevision: "2" }, /record-invalid/], [`users/${uid}`, { locale: false }, /record-invalid/],
      [`workspaces/${wid}/clientProfiles/${uid}`, { onboardingRevision: false }, /record-invalid/],
      [`workspaces/${wid}/clientProfiles/${uid}`, { unexpected: "private" }, /record-invalid/]] as const) {
      const ref = db.doc(path), original = (await ref.get()).data()!;
      await ref.update(patch); await rejected(request(), reason); await assert.rejects(call({ action: "profile-get", workspaceId: wid }), reason); await ref.set(original);
    }
    await db.doc(`lifecycleCommands/${commandId(uid, "save")}`).update({ "result.accountRevision": 99 }); await rejected(request(), /record-invalid/);
  });
  it("direct mobile edits and all trainer global-account reads remain denied", async () => {
    const env = await initializeTestEnvironment({ projectId: SPIKE_PROJECT_ID, firestore: { rules: readFileSync("../firestore.rules", "utf8"),
      host: "127.0.0.1", port: Number(process.env.FIRESTORE_EMULATOR_HOST!.split(":").at(-1)) } });
    try {
      const own = env.authenticatedContext(uid, { email: `${uid}@example.invalid`, email_verified: true }).firestore();
      await assertSucceeds(getDoc(doc(own, `users/${uid}`)));
      await assertFails(updateDoc(doc(own, `users/${uid}`), { displayName: "Bypass", profileRevision: 2 }));
      for (const trainer of ["basic_trainer_one", "basic_trainer_two"])
        await assertFails(getDoc(doc(env.authenticatedContext(trainer, { email: `${trainer}@example.invalid`, email_verified: true }).firestore(), `users/${uid}`)));
    } finally { await env.cleanup(); }
  });
});
