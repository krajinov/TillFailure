import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const projectId = "demo-tillfailure-m3";
const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST;
const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
const password = process.env.TF_PR1_TEST_PASSWORD;
if (!["127.0.0.1:9099", "localhost:9099"].includes(authHost) ||
    !["127.0.0.1:8080", "localhost:8080"].includes(firestoreHost)) {
  throw new Error("PR 1 seeding requires both local Auth and Firestore emulators");
}
if (!password || password.length < 8) throw new Error("Set TF_PR1_TEST_PASSWORD (at least 8 characters)");

initializeApp({ projectId });
const auth = getAuth();
const db = getFirestore();
const workspaceId = "pr1_workspace";
const identities = [
  { uid: "pr1_client", email: "pr1-client@example.invalid", account: "active", membership: "active", role: "client" },
  { uid: "pr1_trainer", email: "pr1-trainer@example.invalid", account: "active", membership: "active", role: "trainer" },
  { uid: "pr1_waiting", email: "pr1-waiting@example.invalid", account: "active" },
  { uid: "pr1_no_account", email: "pr1-no-account@example.invalid" },
  { uid: "pr1_disabled", email: "pr1-disabled@example.invalid", account: "disabled", membership: "active", role: "client" },
  { uid: "pr1_revoked", email: "pr1-revoked@example.invalid", account: "active", membership: "revoked", role: "client" },
  // Valid Firebase Auth/recovery UID, deliberately invalid as a Firestore path segment.
  { uid: "pr1_unsupported/uid", email: "pr1-unsupported@example.invalid" },
];

for (const identity of identities) {
  try {
    await auth.createUser({ uid: identity.uid, email: identity.email, password, emailVerified: true });
  } catch (error) {
    if (error.code !== "auth/uid-already-exists" && error.code !== "auth/email-already-exists") throw error;
    await auth.updateUser(identity.uid, { email: identity.email, password, emailVerified: true });
  }
  if (identity.account) {
    await db.doc(`users/${identity.uid}`).set({ schemaVersion: 1, accountStatus: identity.account, lifecycleRevision: 1 });
    await db.doc(`users/${identity.uid}/authorizations/systemCatalog`).set({
      schemaVersion: 1, status: identity.membership === "active" && identity.account === "active" ? "active" : "inactive",
      activeMembershipCount: identity.membership === "active" ? 1 : 0,
      revision: 1,
    });
  }
  if (identity.membership) {
    await db.doc(`workspaces/${workspaceId}/memberships/${identity.uid}`).set({
      schemaVersion: 1, workspaceId, userId: identity.uid, role: identity.role,
      status: identity.membership, revision: 1,
      catalogContributionActive: identity.membership === "active",
    });
  }
}
await db.doc(`workspaces/${workspaceId}`).set({
  schemaVersion: 1, status: "active", ownerUid: "pr1_trainer",
  membershipRevision: 1, activeRosterCount: 3, catalogContributionCount: 3,
});
console.log(`Seeded ${identities.length} deterministic PR 1 identities in ${projectId}; password was not printed.`);
