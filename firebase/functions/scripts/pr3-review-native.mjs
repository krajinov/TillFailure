// Guarded native recovery fixture and deterministic callable wire faults. Never log raw requests.
import { createServer } from "node:http";
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
import { invitationOperation } from "../lib/src/invitations.js";
import { commandId, stableHash } from "../lib/src/hashing.js";
requireProvisioningEmulators();
const password = process.env.TF_PR3_TEST_PASSWORD;
if (!password || password.length < 8) throw new Error("Local fixture password required");
const db = emulatorFirestore(); const auth = getAuth(); const trials = new Map();
for (const platform of ["android", "ios"]) {
  for (const role of ["trainer", "client"]) {
    await auth.createUser({ uid: `p1_${platform}_${role}`, email: `p1-${platform}-${role}@example.invalid`, password, emailVerified: true });
  }
  const uid = `p1_${platform}_trainer`;
  await provisionTrainer(db, { uid, workspaceId: `p1_${platform}_workspace`, workspaceName: "P1 Recovery Fixture",
    idempotencyKey: `p1-bootstrap-${platform}`, expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0 },
    { uid, email: `p1-${platform}-trainer@example.invalid`, emailVerified: true, disabled: false },
    { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false },
    { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
}
async function snapshot(platform, uid, request) {
  const wid = `p1_${platform}_workspace`;
  const iid = request.action === "create" ? commandId(uid, request.idempotencyKey) : request.invitationId;
  const paths = [`users/${uid}`, `users/${uid}/authorizations/systemCatalog`, `users/${uid}/membershipRefs/${wid}`,
    `workspaces/${wid}`, `workspaces/${wid}/memberships/${uid}`, `workspaces/${wid}/clientProfiles/${uid}`,
    `invitations/${iid}`, `workspaces/${wid}/invitationSummaries/${iid}`, `lifecycleCommands/${commandId(uid, request.idempotencyKey)}`];
  const docs = await db.getAll(...paths.map(path => db.doc(path)));
  return { fingerprint: stableHash(docs.map(d => ({ path: d.ref.path, data: d.data() ?? null, updatedAt: d.updateTime?.toMillis() ?? null }))),
    receiptExists: docs[8].exists, membershipRevision: docs[4].get("revision") ?? null,
    rosterCount: docs[3].get("activeRosterCount"), contributionCount: docs[3].get("catalogContributionCount"),
    accountCount: docs[1].get("activeMembershipCount") ?? null };
}
async function uidFor(req) {
  const bearer = req.headers.authorization?.match(/^Bearer ([^\s]+)$/)?.[1];
  if (!bearer) throw new Error("fixture-denied");
  const token = await auth.verifyIdToken(bearer, true); const user = await auth.getUser(token.uid);
  if (user.disabled || !user.emailVerified) throw new Error("fixture-denied");
  return token.uid;
}
async function body(req) {
  let text = "";
  for await (const chunk of req) { text += chunk; if (text.length > 8192) throw new Error("fixture-bounded"); }
  return JSON.parse(text || "{}");
}
const server = createServer(async (req, res) => {
  res.setHeader("Content-Type", "application/json"); res.setHeader("Cache-Control", "no-store");
  try {
    const uid = await uidFor(req);
    const route = req.url?.match(/^\/review\/(android|ios)\/(create|resend|revoke|accept)\/(setup|mode|evidence)$/);
    if (route) {
      const [, platform, action, operation] = route;
      if (uid !== `p1_${platform}_${action === "accept" ? "client" : "trainer"}`) throw new Error("fixture-denied");
      const key = `${platform}/${action}`;
      if (operation === "setup" && req.method === "POST") {
        if (trials.has(key)) throw new Error("fixture-reused");
        for (const accountUid of new Set([uid, `p1_${platform}_trainer`]))
          for (const collection of ["invitationRateLimits", "invitationRequestLimits"]) await db.doc(`${collection}/${stableHash(accountUid)}`).delete();
        let request = { action, workspaceId: `p1_${platform}_workspace`, email: `p1-${platform}-client@example.invalid`, idempotencyKey: `p1-${action}` };
        if (action !== "create") {
          const seed = await invitationOperation(db, `p1_${platform}_trainer`, { action: "create", workspaceId: `p1_${platform}_workspace`,
            email: `p1-${platform}-client@example.invalid`, idempotencyKey: `p1-base-${action}` });
          request = { action, invitationId: seed.invitationId, expectedVersion: 1, idempotencyKey: `p1-${action}`,
            ...(action === "accept" ? { token: seed.deliveryToken } : { workspaceId: `p1_${platform}_workspace` }) };
        }
        await invitationOperation(db, uid, request); // Real commit; its result is intentionally lost to the native caller.
        const before = await snapshot(platform, uid, request);
        trials.set(key, { uid, request, before, mode: "quota" });
        await db.doc(`invitationRateLimits/${stableHash(uid)}`).set({ minute: Math.floor(Date.now() / 60000), count: 30 });
        await db.doc(`invitationRequestLimits/${stableHash(uid)}`).set({ minute: Math.floor(Date.now() / 60000), count: 120 });
        res.end(JSON.stringify({ request })); return;
      }
      const trial = trials.get(key); if (!trial) throw new Error("fixture-missing");
      if (operation === "mode" && req.method === "POST") {
        const { mode } = await body(req);
        if (!["internal", "unauthorized", "conflict", "forward"].includes(mode)) throw new Error("fixture-mode");
        trial.mode = mode;
        if (mode === "forward") {
          await db.doc(`invitationRequestLimits/${stableHash(uid)}`).delete();
          await db.doc(`invitationRateLimits/${stableHash(uid)}`).set({ minute: Math.floor(Date.now() / 60000), count: 30 });
        }
        res.end("{}"); return;
      }
      if (operation === "evidence" && req.method === "GET") {
        // Freeze the evidence when the native caller verifies this trial. Later independent
        // trials legitimately update shared workspace counters; they must not overwrite proof.
        if (!trial.evidence) {
          const after = await snapshot(platform, uid, trial.request);
          trial.evidence = { before: trial.before, after, unchanged: trial.before.fingerprint === after.fingerprint,
            admissionQuota: (await db.doc(`invitationRateLimits/${stableHash(uid)}`).get()).get("count") };
        }
        res.end(JSON.stringify(trial.evidence)); return;
      }
      throw new Error("fixture-route");
    }
    if (req.method !== "POST" || req.url !== "/demo-tillfailure-m3/europe-west1/invitationEmulator") throw new Error("fixture-route");
    const data = await body(req); const action = data.data?.action;
    const trial = [...trials.values()].find(t => t.uid === uid && t.request.action === action);
    if (!trial) throw new Error("fixture-missing");
    // These three wire faults exercise native classification. Actual Admin lookup injection is
    // separately tested against the backend. Quota and recovered receipts use the real endpoint.
    const fault = { internal: [500, "INTERNAL", "invitation-unavailable"], unauthorized: [403, "PERMISSION_DENIED", "unauthorized"],
      conflict: [400, "FAILED_PRECONDITION", "idempotency-key-reused"] }[trial.mode];
    if (fault) { res.statusCode = fault[0]; res.end(JSON.stringify({ error: { status: fault[1], message: fault[2] } })); return; }
    if (trial.mode === "quota") await db.doc(`invitationRequestLimits/${stableHash(uid)}`).set({ minute: Math.floor(Date.now() / 60000), count: 120 });
    const response = await fetch("http://127.0.0.1:5001/demo-tillfailure-m3/europe-west1/invitationEmulator", {
      method: "POST", headers: { "Content-Type": "application/json", Authorization: req.headers.authorization }, body: JSON.stringify(data) });
    res.statusCode = response.status; res.end(await response.text());
  } catch { res.statusCode = 400; res.end(JSON.stringify({ error: "fixture-request-rejected" })); }
});
server.listen(9695, "127.0.0.1", () => console.log("PR3_REVIEW_NATIVE_READY (loopback; no bearer logging)"));
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close(() => process.exit(0)));
