// Create-only, named loopback demo fixtures. No credentials or bearer material are logged.
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
import { invitationOperation } from "../lib/src/invitations.js";
import { completeOnboarding } from "../lib/src/onboarding.js";
requireProvisioningEmulators();
const password = process.env.TF_M5_PASSWORD;
if (!password || password.length < 12) throw new Error("Set TF_M5_PASSWORD for disposable fixtures");
const db = emulatorFirestore(), auth = getAuth();
const platforms = ["android", "ios"];
for (const platform of platforms) {
  const wid = `m5_${platform}_workspace`;
  if ((await db.doc(`workspaces/${wid}`).get()).exists) throw new Error("Fixture workspace already exists");
  for (const role of ["trainer", "one", "two", "pending"]) {
    const uid = `m5_${platform}_${role}`;
    try { await auth.getUser(uid); throw new Error("Fixture Auth identity already exists"); }
    catch (e) { if (e.code !== "auth/user-not-found") throw e; }
    if ((await db.doc(`users/${uid}`).get()).exists || !(await db.collection(`users/${uid}/membershipRefs`).limit(1).get()).empty ||
        (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).exists) throw new Error("Fixture account data already exists");
  }
}
for (const platform of platforms) {
  const trainer = `m5_${platform}_trainer`, wid = `m5_${platform}_workspace`;
  for (const role of ["trainer", "one", "two", "pending"]) await auth.createUser({ uid: `m5_${platform}_${role}`, email: `m5-${platform}-${role}@example.invalid`, emailVerified: true, password });
  await provisionTrainer(db, { uid: trainer, workspaceId: wid, workspaceName: `${platform} Roster Fixture`, idempotencyKey: `m5-seed-trainer-${platform}`,
    expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0 },
    { uid: trainer, email: `m5-${platform}-trainer@example.invalid`, emailVerified: true, disabled: false },
    { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false },
    { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
  for (const role of ["one", "two", "pending"]) {
    const invite = await invitationOperation(db, trainer, { action: "create", workspaceId: wid, email: `m5-${platform}-${role}@example.invalid`, idempotencyKey: `m5-invite-${role}` });
    const uid = `m5_${platform}_${role}`;
    await invitationOperation(db, uid, { action: "accept", invitationId: invite.invitationId, token: invite.deliveryToken, expectedVersion: 1, idempotencyKey: "m5-accept" });
    if (role !== "pending") await completeOnboarding(db, uid, { action: "complete-onboarding", workspaceId: wid, idempotencyKey: "m5-complete",
      onboarding: { displayName: `${platform === "ios" ? "Apple" : "Android"} Fixture ${role}`, locale: "en", timeZoneId: "UTC", acknowledged: true, expectedAccountRevision: 0, expectedOnboardingRevision: 0 } });
  }
}
console.log("M5 PR1 create-only emulator roster fixtures ready.");
