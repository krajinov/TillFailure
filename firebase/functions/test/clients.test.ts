import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { Timestamp, Firestore } from "firebase-admin/firestore";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { clientOperation, clientFailure, CLIENT_READ_BUDGET } from "../src/clients.js";
import { ClientCursorKeys, CursorScope, CursorState, CURSOR_WIRE_LENGTH } from "../src/client-cursor.js";
import { completeOnboarding } from "../src/onboarding.js";
import { initializeTestEnvironment, assertFails } from "@firebase/rules-unit-testing";
import { readFileSync } from "node:fs";
import { doc, getDoc, getDocs, collection } from "firebase/firestore";

const db = emulatorFirestore(), auth = getAuth(), wid = "roster_ws", trainer = "roster_trainer";
const member = (uid: string, role = "client", patch = {}) => ({ schemaVersion: 1, workspaceId: wid, userId: uid,
  role, status: "active", revision: 1, catalogContributionActive: true, ...patch });
const scope: CursorScope = { project: SPIKE_PROJECT_ID, environment: "loopback-emulator", uid: trainer, trainer, workspace: wid, pageSize: 1 };
let now = 1000000, keys = new ClientCursorKeys(() => now);
const request = (pageSize = 1, cursor: unknown = null) => ({ action: "list", workspaceId: wid, pageSize, cursor });
const list = async (size = 1, cursor: unknown = null, uid: string | undefined = trainer): Promise<any> => clientOperation(db, uid, request(size, cursor), keys, () => now);
const detail = (uid: string) => clientOperation(db, trainer, { action: "get", workspaceId: wid, clientId: uid }, keys, () => now);
async function add(uid: string, options: { trainerId?: string; status?: string; seconds?: number; nanos?: number; named?: boolean } = {}) {
  const b = db.batch();
  b.set(db.doc(`users/${uid}`), { schemaVersion: 1, accountStatus: options.status ?? "active", lifecycleRevision: 1,
    emailNormalized: `${uid}@example.invalid`, displayName: options.named ? uid : "",
    ...(options.named ? { profileRevision: 1, locale: "en", timeZoneId: "UTC" } : {}), contact: "private", health: "private", notes: "private" });
  b.set(db.doc(`workspaces/${wid}/memberships/${uid}`), member(uid));
  b.set(db.doc(`workspaces/${wid}/clientProfiles/${uid}`), { schemaVersion: 1, workspaceId: wid, userId: uid,
    trainerId: options.trainerId ?? trainer, createdAt: new Timestamp(options.seconds ?? 10, options.nanos ?? 123000), onboardingStatus: "pending" });
  b.update(db.doc(`workspaces/${wid}`), { activeRosterCount: (await db.collection(`workspaces/${wid}/memberships`).where("status", "==", "active").get()).size + 1,
    catalogContributionCount: (await db.collection(`workspaces/${wid}/memberships`).where("status", "==", "active").get()).size + 1 });
  await b.commit();
}
async function patch(path: string, fields: object, run: () => Promise<unknown>) {
  const ref = db.doc(path), original = (await ref.get()).data()!;
  await ref.update(fields); try { await run(); } finally { await ref.set(original); }
}
async function publicFailure(run: () => Promise<unknown>) { try { await run(); assert.fail("expected failure"); } catch (e) { return clientFailure(e); } }
// Instruments the real Admin transaction, retaining emulator query/index execution.
function measured() {
  const calls: string[] = [];
  const wrapper = { doc: db.doc.bind(db), collection: db.collection.bind(db), runTransaction: (fn: any, options: any) => db.runTransaction(async tx => {
    const proxy = new Proxy(tx, { get(target, property) {
      if (property === "getAll") return (...args: any[]) => { calls.push(...args.filter(a => a?.path).map(a => a.path)); return (target.getAll as any)(...args); };
      if (property === "get") return (q: any) => { calls.push("query"); return target.get(q); };
      return Reflect.get(target, property);
    } });
    return fn(proxy);
  }, options) } as unknown as Firestore;
  return { wrapper, calls };
}

describe("assigned-client bounded trusted reads", () => {
  beforeEach(async () => {
    await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${SPIKE_PROJECT_ID}/databases/(default)/documents`, { method: "DELETE" });
    await auth.deleteUser(trainer).catch(() => {}); await auth.createUser({ uid: trainer, email: `${trainer}@example.invalid`, emailVerified: true });
    now = 1000000; keys = new ClientCursorKeys(() => now);
    const b = db.batch();
    b.set(db.doc(`users/${trainer}`), { schemaVersion: 1, accountStatus: "active", emailNormalized: `${trainer}@example.invalid`, lifecycleRevision: 1 });
    b.set(db.doc(`workspaces/${wid}`), { schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 1, catalogContributionCount: 1 });
    b.set(db.doc(`workspaces/${wid}/memberships/${trainer}`), member(trainer, "trainer")); await b.commit();
  });
  it("same empty envelope for absent, retained history, foreign and disabled sources; historical size never changes read work", async () => {
    assert.deepEqual(await list(), { items: [], nextCursor: null });
    const b = db.batch();
    for (let i = 0; i < 100; i++) {
      b.set(db.doc(`workspaces/${wid}/memberships/history${i}`), member(`history${i}`, "client", { status: "revoked", catalogContributionActive: false }));
      b.set(db.doc(`workspaces/${wid}/clientProfiles/history${i}`), { arbitrary: "retained" });
    }
    await b.commit();
    const m = measured(); assert.deepEqual(await clientOperation(m.wrapper, trainer, request(), keys, () => now), { items: [], nextCursor: null });
    assert.deepEqual(m.calls, [`users/${trainer}`, `workspaces/${wid}`, `workspaces/${wid}/memberships/${trainer}`, "query"]);
    await add("foreign", { trainerId: "other" }); await add("disabled", { status: "disabled" });
    assert.deepEqual(await list(2), { items: [], nextCursor: null });
    await assert.rejects(detail("foreign"), /unauthorized/); await assert.rejects(detail("disabled"), /unauthorized/);
  });
  it("fills only authorized rows, exact minimal shape, authorized lookahead and no hidden suffix token", async () => {
    await add("a", { named: true }); await add("b", { status: "disabled", nanos: 124000 });
    await add("c", { named: true, nanos: 125000 }); await add("z", { trainerId: "other", nanos: 126000 });
    const first = await list();
    assert.deepEqual(first.items, [{ clientId: "a", workspaceId: wid, displayName: "a", relationshipStatus: "active", onboardingStatus: "pending" }]);
    assert.deepEqual(keys.open(scope, first.nextCursor).last, { clientId: "a", seconds: 10, nanoseconds: 123000 });
    assert.equal((await list(2)).nextCursor, null);
    assert.deepEqual((await list(1, first.nextCursor)), { items: [{ ...first.items[0], clientId: "c", displayName: "c" }], nextCursor: null });
    assert.deepEqual(await detail("a"), first.items[0]);
    const m = measured(); await clientOperation(m.wrapper, trainer, request(1), keys, () => now);
    assert.equal(m.calls.filter(p => p === "query").length, 1);
    assert.equal(m.calls.filter(p => p.includes("clientProfiles")).length, 4);
    assert.equal(m.calls.filter(p => p.startsWith("users/")).length, 4); // no foreign account lookup
    assert(m.calls.length <= CLIENT_READ_BUDGET.maxReads);
  });
  it("orders equal timestamps by unique ID, retaining submillisecond precision", async () => {
    await add("b"); await add("a"); await add("c", { nanos: 124000 });
    const a = await list(), b = await list(1, a.nextCursor), c = await list(1, b.nextCursor);
    assert.deepEqual([a.items[0].clientId, b.items[0].clientId, c.items[0].clientId], ["a", "b", "c"]);
    assert.equal(c.nextCursor, null);
  });
  it("removed, revoked, reassigned or disabled anchor is a position, never an existence probe", async () => {
    await add("a"); await add("b"); const first = await list();
    await db.doc(`workspaces/${wid}/memberships/a`).update({ status: "revoked", catalogContributionActive: false });
    await db.doc(`workspaces/${wid}`).update({ activeRosterCount: 2, catalogContributionCount: 2, membershipRevision: 9 });
    const m = measured(); const page: any = await clientOperation(m.wrapper, trainer, request(1, first.nextCursor), keys, () => now);
    assert.equal(page.items[0].clientId, "b"); assert(!m.calls.some(p => p.endsWith("/a")));
    await assert.rejects(detail("a"), /unauthorized/);
    await db.doc(`users/b`).update({ accountStatus: "disabled" });
    assert.deepEqual(await list(1, first.nextCursor), { items: [], nextCursor: null });
  });
  it("hidden relationship changes do not invalidate an authorized cursor", async () => {
    await add("a"); await add("b"); const first = await list();
    await add("hidden", { trainerId: "other" });
    await db.doc(`workspaces/${wid}`).update({ membershipRevision: 99 });
    assert.equal((await list(1, first.nextCursor)).items[0].clientId, "b");
    await db.doc(`workspaces/${wid}/clientProfiles/b`).update({ trainerId: "other" });
    assert.deepEqual(await list(1, first.nextCursor), { items: [], nextCursor: null });
    await assert.rejects(detail("b"), /unauthorized/);
  });
  it("fresh caller Auth/account/workspace/path-bound trainer membership on every read", async () => {
    await add("a"); await add("b"); const token = (await list()).nextCursor;
    for (const [path, changes] of [
      [`users/${trainer}`, { accountStatus: "disabled" }], [`users/${trainer}`, { lifecycleRevision: false }],
      [`users/${trainer}`, { emailNormalized: "forged" }], [`workspaces/${wid}`, { status: "suspended" }],
      [`workspaces/${wid}/memberships/${trainer}`, { status: "revoked" }], [`workspaces/${wid}/memberships/${trainer}`, { workspaceId: "foreign" }],
      [`workspaces/${wid}/memberships/${trainer}`, { userId: "other" }], [`workspaces/${wid}/memberships/${trainer}`, { role: "client" }],
      [`workspaces/${wid}/memberships/${trainer}`, { schemaVersion: 2 }]] as const) {
      await patch(path, changes, async () => { assert.equal((await publicFailure(() => list(1, token))).code, "permission-denied"); await assert.rejects(detail("a")); });
    }
    await auth.updateUser(trainer, { disabled: true }); await assert.rejects(list(), /unauthorized/);
    await auth.updateUser(trainer, { disabled: false, emailVerified: false }); await assert.rejects(list(), /unverified-email/);
    await assert.rejects(clientOperation(db, undefined, request()), /unauthenticated/);
    await assert.rejects(list(1, null, "bad/uid"));
  });
  it("denies forged caller/filter/cursor inputs and all foreign details", async () => {
    for (const r of [{ ...request(), trainerId: trainer }, { ...request(), callerUid: trainer }, { ...request(), pageSize: 0 },
      { ...request(), pageSize: 21 }, { ...request(), pageSize: true }, { ...request(), action: "search" },
      { ...request(), workspaceId: "foreign" }]) await assert.rejects(clientOperation(db, trainer, r));
    await assert.rejects(detail("missing"), /unauthorized/);
  });
  it("fails closed on cap+1, counter drift, malformed membership/profile/name and oversize fields without partial progress", async () => {
    await add("a");
    for (const [path, changes] of [[`workspaces/${wid}`, { activeRosterCount: 1 }], [`workspaces/${wid}`, { catalogContributionCount: 0 }],
      [`workspaces/${wid}/memberships/a`, { userId: "forged" }], [`workspaces/${wid}/clientProfiles/a`, { workspaceId: "forged" }],
      [`workspaces/${wid}/clientProfiles/a`, { createdAt: null }], [`workspaces/${wid}/clientProfiles/a`, { onboardingStatus: "complete" }],
      [`users/a`, { displayName: "x".repeat(81) }], [`users/a`, { locale: "en" }], [`users/a`, { profileRevision: true }]] as const)
      await patch(path, changes, async () => assert.deepEqual(await publicFailure(() => list()), { code: "unavailable", reason: "unavailable" }));
    for (const id of ["b", "c", "d", "e"]) await add(id);
    assert.deepEqual(await publicFailure(() => list()), { code: "unavailable", reason: "unavailable" });
  });
  it("refuses undersized budget independent of dataset and bounds timeout/retries", async () => {
    for (const budget of [{ ...CLIENT_READ_BUDGET, maxReads: 18 }, { ...CLIENT_READ_BUDGET, maxBytes: 1 }, { ...CLIENT_READ_BUDGET, cap: 99 }]) {
      const m = measured(); await assert.rejects(clientOperation(m.wrapper, trainer, request(), keys, () => now, budget), /unavailable/); assert.equal(m.calls.length, 0);
    }
    let attempts = 0;
    const broken = { runTransaction: async (_fn: unknown, opts: any) => { attempts = opts.maxAttempts; throw new Error("retry exhausted"); } } as unknown as Firestore;
    assert.equal((await publicFailure(() => clientOperation(broken, trainer, request()))).reason, "unavailable"); assert.equal(attempts, 3);
    const hanging = { runTransaction: () => new Promise(() => {}) } as unknown as Firestore;
    await assert.rejects(clientOperation(hanging, trainer, request(), keys, () => now, { ...CLIENT_READ_BUDGET, deadlineMs: 1 }), /unavailable/);
  });
  it("live authoritative name follows atomic onboarding; a second pending workspace uses the global name", async () => {
    await add("a"); await auth.deleteUser("a").catch(() => {}); await auth.createUser({ uid: "a", email: "a@example.invalid", emailVerified: true });
    await db.doc(`users/a/membershipRefs/${wid}`).set({ schemaVersion: 1, status: "active" });
    const before = (await list()).items[0]; assert.equal(before.displayName, null);
    // Injection fields are not part of onboarding's strict profile schema.
    const completing = completeOnboarding(db, "a", { action: "complete-onboarding", workspaceId: wid, idempotencyKey: "rosterComplete",
      onboarding: { displayName: "Current name", locale: "en", timeZoneId: "UTC", acknowledged: true, expectedAccountRevision: 0, expectedOnboardingRevision: 0 } });
    const racing: any = await list(); await completing;
    assert(racing.items[0].displayName === null && racing.items[0].onboardingStatus === "pending" || racing.items[0].displayName === "Current name" && racing.items[0].onboardingStatus === "complete");
    assert.equal((await detail("a") as any).displayName, "Current name");
    const other = "pending_workspace";
    const second = db.batch();
    second.set(db.doc(`workspaces/${other}`), { schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 2, catalogContributionCount: 2 });
    second.set(db.doc(`workspaces/${other}/memberships/${trainer}`), { ...member(trainer, "trainer"), workspaceId: other });
    second.set(db.doc(`workspaces/${other}/memberships/a`), { ...member("a"), workspaceId: other });
    second.set(db.doc(`workspaces/${other}/clientProfiles/a`), { schemaVersion: 1, workspaceId: other, userId: "a", trainerId: trainer,
      createdAt: Timestamp.now(), onboardingStatus: "pending" });
    await second.commit();
    const otherDetail = () => clientOperation(db, trainer, { action: "get", workspaceId: other, clientId: "a" }, keys, () => now) as Promise<any>;
    assert.equal((await otherDetail()).displayName, "Current name");
    assert.equal((await otherDetail()).onboardingStatus, "pending");
    assert.equal((await detail("a") as any).onboardingStatus, "complete");
    await db.doc(`users/a`).update({ displayName: "Updated source", profileRevision: 2 });
    assert.equal((await list()).items[0].displayName, "Updated source");
    assert.equal((await otherDetail()).displayName, "Updated source");
  });
  it("all invalid cursors share one failure before target queries; changed caller revision reloads", async () => {
    await add("a"); await add("b"); const token = (await list()).nextCursor;
    const failures = ["", "a".repeat(6145), token.slice(1), token + "a", "{signedAnchor:active}"];
    const wire = Buffer.from(token, "base64url");
    for (const index of [0, 1, 17, 29, wire.length - 1]) { const changed = Buffer.from(wire); changed[index] = changed[index]! ^ 1; failures.push(changed.toString("base64url")); }
    for (const id of ["active", "revoked", "disabled", "moved", "deleted", "never"]) failures.push(Buffer.from(JSON.stringify({ last: id })).toString("base64url"));
    for (const invalid of failures) {
      const m = measured(); const error = await publicFailure(() => clientOperation(m.wrapper, trainer, request(1, invalid), keys, () => now));
      assert.deepEqual(error, { code: "failed-precondition", reason: "invalid-cursor" }); assert.equal(m.calls.length, 3); assert(!m.calls.includes("query"));
    }
    assert.equal((await publicFailure(() => list(2, token))).reason, "invalid-cursor");
    now += 300000; assert.equal((await publicFailure(() => list(1, token))).reason, "invalid-cursor"); now -= 300000;
    await patch(`workspaces/${wid}/memberships/${trainer}`, { revision: 2 }, async () => assert.equal((await publicFailure(() => list(1, token))).reason, "invalid-cursor"));
    keys.retireAll(); assert.equal((await publicFailure(() => list(1, token))).reason, "invalid-cursor");
  });
  it("same-workspace second active trainer cannot get another trainer's assigned clients", async () => {
    await auth.deleteUser("other").catch(() => {}); await auth.createUser({ uid: "other", email: "other@example.invalid", emailVerified: true });
    await add("other"); await db.doc(`workspaces/${wid}/memberships/other`).update({ role: "trainer" });
    await add("a"); assert.deepEqual(await list(1, null, "other"), { items: [], nextCursor: null });
    await assert.rejects(clientOperation(db, "other", { action: "get", workspaceId: wid, clientId: "a" }), /unauthorized/);
  });
  it("preserves traversal expiry, and foreign workspace replay fails before source queries", async () => {
    await add("a"); await add("b"); await add("c");
    const first = await list(); const original = keys.open(scope, first.nextCursor);
    now += 200000; const second = await list(1, first.nextCursor);
    assert.equal(keys.open(scope, second.nextCursor).expiresAt, original.expiresAt);
    assert.equal(keys.open(scope, second.nextCursor).issuedAt, original.issuedAt);
    await db.doc("workspaces/second").set({ schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 1, catalogContributionCount: 1 });
    await db.doc(`workspaces/second/memberships/${trainer}`).set({ ...member(trainer, "trainer"), workspaceId: "second" });
    const m = measured();
    assert.deepEqual(await publicFailure(() => clientOperation(m.wrapper, trainer, { ...request(1, first.nextCursor), workspaceId: "second" }, keys, () => now)), { code: "failed-precondition", reason: "invalid-cursor" });
    assert.equal(m.calls.length, 3);
  });
  it("concurrent revocation, disablement and suspension yield only coherent authorized results or safe failure", async () => {
    await add("a", { named: true });
    const mutations = [
      async () => db.runTransaction(async tx => {
        await tx.get(db.doc(`workspaces/${wid}`));
        tx.update(db.doc(`workspaces/${wid}/memberships/a`), { status: "revoked", revision: 2, catalogContributionActive: false });
        tx.update(db.doc(`workspaces/${wid}`), { activeRosterCount: 1, catalogContributionCount: 1, membershipRevision: 2 });
      }),
      async () => db.doc("users/a").update({ accountStatus: "disabled", lifecycleRevision: 2 }),
      async () => db.doc(`workspaces/${wid}`).update({ status: "suspended", membershipRevision: 3 })
    ];
    for (const mutate of mutations) {
      const results = await Promise.allSettled([list(), mutate()]);
      assert.equal(results[1]!.status, "fulfilled");
      const read = results[0]!;
      if (read.status === "fulfilled") {
        assert(read.value.items.length <= 1); assert.equal(read.value.nextCursor, null);
        if (read.value.items.length) assert.equal(read.value.items[0].clientId, "a");
      } else assert(["permission-denied", "unavailable"].includes(clientFailure(read.reason).code));
      await db.doc(`workspaces/${wid}/memberships/a`).set(member("a"));
      await db.doc("users/a").update({ accountStatus: "active", lifecycleRevision: 3 });
      await db.doc(`workspaces/${wid}`).update({ status: "active", membershipRevision: 4, activeRosterCount: 2, catalogContributionCount: 2 });
    }
  });
  it("real callable derives Auth, returns minimal records and gives identical invalid-cursor bodies", async () => {
    await add("a"); await add("b");
    await auth.updateUser(trainer, { password: "local-emulator-fixture-only" });
    const login = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake-key`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: `${trainer}@example.invalid`, password: "local-emulator-fixture-only", returnSecureToken: true }) });
    const token = (await login.json() as { idToken: string }).idToken; assert(token);
    const endpoint = `http://127.0.0.1:${process.env.TF_FUNCTIONS_EMULATOR_PORT ?? 5001}/${SPIKE_PROJECT_ID}/europe-west1/clientsEmulator`;
    const invoke = (data: unknown, bearer?: string) => fetch(endpoint, { method: "POST", headers: { "Content-Type": "application/json",
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) }, body: JSON.stringify({ data }) });
    assert.equal((await invoke(request())).status, 401);
    assert.equal((await invoke({ ...request(), callerUid: "foreign" }, token)).status, 400);
    const response = await invoke(request(), token); assert.equal(response.status, 200);
    const result = (await response.json() as any).result;
    assert.deepEqual(Object.keys(result).sort(), ["items", "nextCursor"]);
    assert.deepEqual(Object.keys(result.items[0]).sort(), ["clientId", "displayName", "onboardingStatus", "relationshipStatus", "workspaceId"]);
    const bodies = [];
    for (const cursor of ["bad", result.nextCursor.slice(1), "x".repeat(6145)]) {
      const r = await invoke(request(1, cursor), token); assert.equal(r.status, 400); bodies.push(await r.json());
    }
    assert.deepEqual(bodies[0], bodies[1]); assert.deepEqual(bodies[0], bodies[2]);
    assert.deepEqual(bodies[0], { error: { message: "invalid-cursor", status: "FAILED_PRECONDITION" } });
  });
  it("direct mobile private account/profile and enumeration denial remains in actual Rules", async () => {
    await add("a"); const [host, port] = process.env.FIRESTORE_EMULATOR_HOST!.split(":");
    const env = await initializeTestEnvironment({ projectId: SPIKE_PROJECT_ID, firestore: { host: host!, port: Number(port), rules: readFileSync("../firestore.rules", "utf8") } });
    try {
      const mobile = env.authenticatedContext(trainer, { email: `${trainer}@example.invalid`, email_verified: true }).firestore();
      for (const path of ["users/a", `workspaces/${wid}/clientProfiles/a`]) await assertFails(getDoc(doc(mobile, path)));
      for (const path of ["users", `workspaces/${wid}/clientProfiles`, `workspaces/${wid}/memberships`]) await assertFails(getDocs(collection(mobile, path)));
    } finally { await env.cleanup(); }
  });
});

describe("fixed-size confidential cursor and ephemeral key lifecycle", () => {
  const state = (patch = {}): CursorState => ({ version: 2, purpose: "clients-list", filter: "assigned-active", order: "createdAt-id-v2",
    uid: trainer, trainer, workspace: wid, pageSize: 1, revision: 1, issuedAt: 1000000, expiresAt: 1300000,
    last: { seconds: 10, nanoseconds: 999999999, clientId: "a" }, ...patch });
  it("same wire size across ID lengths, randomized sealing, confidentiality and exact roundtrip", () => {
    const k = new ClientCursorKeys(() => 1000000);
    const tokens = ["a", "a".repeat(128), "😀".repeat(64)].map(clientId => k.seal(scope, state({ last: { seconds: 10, nanoseconds: 999999999, clientId } })));
    for (const t of tokens) { assert.equal(t.length, CURSOR_WIRE_LENGTH); assert(!Buffer.from(t, "base64url").includes(Buffer.from(trainer))); }
    assert.notEqual(k.seal(scope, state()), k.seal(scope, state())); assert.deepEqual(k.open(scope, tokens[0]), state());
  });
  it("binds every AAD context, schema, purpose, order, version, timestamps and lifetime", () => {
    const k = new ClientCursorKeys(() => 1000000), token = k.seal(scope, state());
    for (const patch of [{ uid: "foreign" }, { trainer: "foreign" }, { workspace: "foreign" }, { environment: "production" }, { project: "foreign" }, { pageSize: 2 }])
      assert.throws(() => k.open({ ...scope, ...patch }, token), /invalid-cursor/);
    for (const patch of [{ purpose: "get" }, { filter: "all" }, { order: "old" }, { version: 1 }, { revision: false }, { extra: "private" },
      { issuedAt: 1000001 }, { expiresAt: 1000000 }, { expiresAt: 1300001 }, { last: { clientId: "a", seconds: 1, nanoseconds: 1000000000 } }, { last: { clientId: "bad/id", seconds: 1, nanoseconds: 1 } }])
      assert.throws(() => k.open(scope, k.seal(scope, state(patch) as CursorState)), /invalid-cursor/);
    assert.throws(() => new ClientCursorKeys(() => 1000000).open(scope, token), /invalid-cursor/);
  });
  it("rotates at hour or issuance cap, retains old decryption only to lifetime, emergency retirement drops all", () => {
    let time = 1000000; const k = new ClientCursorKeys(() => time);
    const old = k.seal(scope, state());
    (k as any).current.issues = 2 ** 20;
    const rotated = k.seal(scope, state()); assert.notEqual(old.slice(0, 23), rotated.slice(0, 23)); assert.deepEqual(k.open(scope, old), state());
    time = 1300000; assert.throws(() => k.open(scope, old), /invalid-cursor/); assert.equal((k as any).prior.length, 0);
    const before = k.seal(scope, state({ issuedAt: time, expiresAt: time + 300000 }));
    time += 3600000;
    const after = k.seal(scope, state({ issuedAt: time, expiresAt: time + 300000 })); assert.notEqual(before.slice(0, 23), after.slice(0, 23));
    k.retireAll(); assert.throws(() => k.open(scope, after), /invalid-cursor/);
  });
});
