// Create-only synthetic fixtures; use guarded loopback emulators and an environment password.
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
requireProvisioningEmulators();
const password = process.env.TF_M5_PR2_PASSWORD;
if (!password || password.length < 12) throw new Error("Set TF_M5_PR2_PASSWORD for disposable fixtures");
const db = emulatorFirestore(), auth = getAuth();
const platforms = ["android", "ios"], roles = ["trainer", "accept", "revoke", "manual1", "manual2"];
for (const platform of platforms) {
  if ((await db.doc(`workspaces/m5p2_${platform}_workspace`).get()).exists) throw new Error("Fixture workspace already exists");
  for (const role of roles) {
    const uid = `m5p2_${platform}_${role}`;
    try { await auth.getUser(uid); throw new Error("Fixture Auth identity already exists"); }
    catch (e) { if (e.code !== "auth/user-not-found") throw e; }
    if ((await db.doc(`users/${uid}`).get()).exists || !(await db.collection(`users/${uid}/membershipRefs`).limit(1).get()).empty ||
        (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).exists) throw new Error("Fixture account data already exists");
  }
}
for (const platform of platforms) {
  for (const role of roles) await auth.createUser({ uid: `m5p2_${platform}_${role}`, email: `m5p2-${platform}-${role}@example.invalid`, password, emailVerified: true });
  const uid = `m5p2_${platform}_trainer`;
  await provisionTrainer(db, { uid, workspaceId: `m5p2_${platform}_workspace`, workspaceName: `${platform} Client Invitations`,
    idempotencyKey: `m5p2-seed-${platform}`, expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0 },
    { uid, email: `m5p2-${platform}-trainer@example.invalid`, emailVerified: true, disabled: false },
    { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false },
    { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
}
console.log("M5 PR2 create-only fixtures ready. No invitations or delivery were created.");
