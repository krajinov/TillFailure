import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";

requireProvisioningEmulators();
const password = process.env.TF_PR3_TEST_PASSWORD;
if (!password || password.length < 8) throw new Error("Set TF_PR3_TEST_PASSWORD for local fixture accounts");
const db = emulatorFirestore();
const auth = getAuth();
const identities = ["android", "ios"].flatMap(platform => ["trainer", "client"].map(role => ({
  uid: `pr3_${platform}_${role}`, email: `pr3-${platform}-${role}@example.invalid`
})));
// Refuse reused fixtures; do not reset protected lifecycle records or Auth identities.
for (const identity of identities) {
  try { await auth.getUser(identity.uid); throw new Error("PR 3 Auth fixture already exists"); }
  catch (error) { if (error.code !== "auth/user-not-found") throw error; }
  if ((await db.doc(`users/${identity.uid}`).get()).exists ||
      !(await db.collection(`users/${identity.uid}/membershipRefs`).limit(1).get()).empty ||
      (await db.doc(`users/${identity.uid}/authorizations/systemCatalog`).get()).exists) throw new Error("PR 3 Firestore fixture already exists");
}
for (const platform of ["android", "ios"]) {
  const wid = `pr3_${platform}_workspace`;
  if ((await db.doc(`workspaces/${wid}`).get()).exists) throw new Error("PR 3 workspace already exists");
}
for (const identity of identities) await auth.createUser({ ...identity, password, emailVerified: true });
for (const platform of ["android", "ios"]) {
  const uid = `pr3_${platform}_trainer`;
  await provisionTrainer(db, { uid, workspaceId: `pr3_${platform}_workspace`, workspaceName: `${platform === "ios" ? "Apple" : "Android"} Training`,
    idempotencyKey: `seed-pr3-${platform}`, expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0 },
  { uid, email: `pr3-${platform}-trainer@example.invalid`, emailVerified: true, disabled: false },
  { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false },
  { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
}
console.log("Created named PR 3 emulator fixtures. No invitations or emails were sent.");
