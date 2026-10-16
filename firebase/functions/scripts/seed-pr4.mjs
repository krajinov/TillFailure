// Disposable emulator identities only. Refuse to overwrite prior accounts or protected records.
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
import { invitationOperation } from "../lib/src/invitations.js";
requireProvisioningEmulators();
const password = process.env.TF_PR4_PASSWORD;
if (!password || password.length < 8) throw new Error("Set TF_PR4_PASSWORD for disposable fixtures");
const db = emulatorFirestore(), auth = getAuth();
for (const platform of ["android", "ios"]) {
  const wid = `pr4_${platform}_workspace`, trainer = `pr4_${platform}_trainer`;
  if ((await db.doc(`workspaces/${wid}`).get()).exists) throw new Error("PR4 workspace already exists");
  for (const kind of ["trainer", "native", "ui", "large", "invitee"]) {
    const uid = `pr4_${platform}_${kind}`;
    try { await auth.getUser(uid); throw new Error("PR4 identity already exists"); }
    catch (error) { if (error.code !== "auth/user-not-found") throw error; }
    await auth.createUser({ uid, email: `pr4-${platform}-${kind}@example.invalid`, emailVerified: true, password });
  }
  await provisionTrainer(db, { uid: trainer, workspaceId: wid, workspaceName: `${platform === "ios" ? "Apple" : "Android"} Training`,
    idempotencyKey: `pr4-seed-${platform}`, expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0 },
    { uid: trainer, email: `pr4-${platform}-trainer@example.invalid`, emailVerified: true, disabled: false },
    { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false },
    { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
  for (const kind of ["native", "ui", "large"]) {
    const invite = await invitationOperation(db, trainer, { action: "create", workspaceId: wid,
      email: `pr4-${platform}-${kind}@example.invalid`, idempotencyKey: `pr4-${kind}` });
    await invitationOperation(db, `pr4_${platform}_${kind}`, { action: "accept", invitationId: invite.invitationId,
      token: invite.deliveryToken, expectedVersion: 1, idempotencyKey: "pr4-accept" });
  }
}
console.log("PR4 fixture accounts ready; production untouched; bearer material not logged.");
