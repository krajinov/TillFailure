// Create-only synthetic profiles via existing guarded provisioning, invitation and onboarding writers.
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
import { invitationOperation } from "../lib/src/invitations.js";
import { completeOnboarding } from "../lib/src/onboarding.js";
requireProvisioningEmulators();
const password = process.env.TF_M5_PR3_PASSWORD;
if (!password || password.length < 12) throw new Error("Set disposable fixture password");
const db = emulatorFirestore(), auth = getAuth();
for (const platform of ["android", "ios"]) {
  if ((await db.doc(`workspaces/m5p3_${platform}_workspace`).get()).exists) throw new Error("Workspace exists");
  for (const role of ["trainer", "client"]) {
    const uid = `m5p3_${platform}_${role}`;
    try { await auth.getUser(uid); throw new Error("Auth identity exists"); } catch (e) { if (e.code !== "auth/user-not-found") throw e; }
    if ((await db.doc(`users/${uid}`).get()).exists || !(await db.collection(`users/${uid}/membershipRefs`).limit(1).get()).empty ||
        (await db.doc(`users/${uid}/authorizations/systemCatalog`).get()).exists) throw new Error("Account data exists");
  }
}
for (const platform of ["android", "ios"]) {
  const trainer = `m5p3_${platform}_trainer`, client = `m5p3_${platform}_client`, workspaceId = `m5p3_${platform}_workspace`;
  for (const role of ["trainer", "client"]) await auth.createUser({ uid: `m5p3_${platform}_${role}`, email: `m5p3-${platform}-${role}@example.invalid`, emailVerified: true, password });
  await provisionTrainer(db, { uid: trainer, workspaceId, workspaceName: `${platform} Basic Profile`, idempotencyKey: `m5p3-provision-${platform}`,
    expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0 },
    { uid: trainer, email: `m5p3-${platform}-trainer@example.invalid`, emailVerified: true, disabled: false },
    { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false }, { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
  const invite = await invitationOperation(db, trainer, { action: "create", workspaceId, email: `m5p3-${platform}-client@example.invalid`, idempotencyKey: `m5p3-invite-${platform}` });
  await invitationOperation(db, client, { action: "accept", invitationId: invite.invitationId, token: invite.deliveryToken, expectedVersion: 1, idempotencyKey: "m5p3-accept" });
  await completeOnboarding(db, client, { action: "complete-onboarding", workspaceId, idempotencyKey: "m5p3-onboard", onboarding: {
    displayName: "Original fixture", locale: "en", timeZoneId: "UTC", acknowledged: true, expectedAccountRevision: 0, expectedOnboardingRevision: 0 } });
}
console.log("M5 PR3 create-only profiles ready");
