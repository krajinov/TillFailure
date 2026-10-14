// Local test-delivery channel. Raw links exist only in this host process's memory.
import { createServer } from "node:http";
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { invitationOperation } from "../lib/src/invitations.js";
import { stableHash } from "../lib/src/hashing.js";
import { requireProvisioningEmulators } from "../lib/src/provisioning.js";

requireProvisioningEmulators();
await import("./seed-pr3.mjs");
const db = emulatorFirestore();
const auth = getAuth();
const entries = new Map();
for (const platform of ["android", "ios"]) {
  for (const kind of ["restart", "late", "ui"]) {
    const uid = `pr3_${platform}_${kind === "restart" ? "client" : kind}`;
    const email = `pr3-${platform}-${kind === "restart" ? "client" : kind}@example.invalid`;
    if (kind !== "restart") await auth.createUser({ uid, email, emailVerified: true, password: process.env.TF_PR3_TEST_PASSWORD });
    if (kind === "ui") continue; // The interactive trainer form creates this invitation.
    const result = await invitationOperation(db, `pr3_${platform}_trainer`, {
      action: "create", workspaceId: `pr3_${platform}_workspace`, email, idempotencyKey: `finalize-${kind}`
    });
    entries.set(`${platform}/${kind}`, { uid, result,
      entry: `https://invite.tillfailure.invalid/i/${result.invitationId}/${result.tokenVersion}#${result.deliveryToken}` });
  }
}

async function evidence(platform, kind) {
  const { uid, result } = entries.get(`${platform}/${kind}`);
  const wid = `pr3_${platform}_workspace`;
  const receipts = await db.collection("lifecycleCommands").where("callerUid", "==", uid)
    .where("commandKind", "==", "invitation-accept").where("invitationId", "==", result.invitationId).limit(2).get();
  const paths = [`users/${uid}`, `users/${uid}/authorizations/systemCatalog`, `users/${uid}/membershipRefs/${wid}`,
    `workspaces/${wid}`, `workspaces/${wid}/memberships/${uid}`, `workspaces/${wid}/clientProfiles/${uid}`,
    `invitations/${result.invitationId}`, `workspaces/${wid}/invitationSummaries/${result.invitationId}`];
  const docs = await db.getAll(...paths.map(path => db.doc(path)));
  const all = [...docs, ...receipts.docs];
  return { membershipExists: docs[4].exists, membershipRevision: docs[4].get("revision") ?? null,
    accountCount: docs[1].get("activeMembershipCount") ?? null, rosterCount: docs[3].get("activeRosterCount"),
    contributionCount: docs[3].get("catalogContributionCount"), receiptCount: receipts.size,
    invitationStatus: docs[6].get("status"), fingerprint: stableHash(all.map(d => ({ path: d.ref.path,
      data: d.data() ?? null, updatedAt: d.updateTime?.toMillis() ?? null }))) };
}

const server = createServer(async (request, response) => {
  response.setHeader("Content-Type", "application/json");
  response.setHeader("Cache-Control", "no-store");
  try {
    const parts = request.url?.match(/^\/(fixture|evidence)\/(android|ios)\/(restart|late)$/);
    if (request.method !== "GET" || !parts) throw new Error("fixture-invalid");
    const [, route, platform, kind] = parts;
    const fixture = entries.get(`${platform}/${kind}`);
    if (route === "fixture") {
      const bearer = request.headers.authorization?.match(/^Bearer ([^\s]+)$/)?.[1];
      if (!bearer) throw new Error("fixture-unauthorized");
      const decoded = await auth.verifyIdToken(bearer, true);
      const user = await auth.getUser(decoded.uid);
      if (decoded.uid !== fixture.uid || user.disabled || !user.emailVerified) throw new Error("fixture-unauthorized");
      response.end(JSON.stringify({ entry: fixture.entry }));
    } else response.end(JSON.stringify(await evidence(platform, kind)));
  } catch {
    response.statusCode = 400;
    response.end(JSON.stringify({ error: "fixture-request-rejected" }));
  }
});
server.listen(9693, "127.0.0.1", () => console.log("PR3_NATIVE_FIXTURE_READY (loopback; links are transient and never logged)"));
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close(() => process.exit(0)));
