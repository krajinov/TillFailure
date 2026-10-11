import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const projectId = "demo-tillfailure-m3";
const endpoint = /^(127\.0\.0\.1|localhost):([0-9]{1,5})$/;
const isLoopback = value => {
  const match = value?.match(endpoint);
  return !!match && Number(match[2]) > 0 && Number(match[2]) <= 65535;
};
const configuredProjects = [process.env.GCLOUD_PROJECT, process.env.GOOGLE_CLOUD_PROJECT].filter(Boolean);
if (configuredProjects.length === 0 || configuredProjects.some(id => id !== projectId) ||
    !isLoopback(process.env.FIREBASE_AUTH_EMULATOR_HOST) ||
    !isLoopback(process.env.FIRESTORE_EMULATOR_HOST)) throw new Error("PR 2 fixtures require the named local emulators");
const password = process.env.TF_PR2_TEST_PASSWORD;
if (!password || password.length < 8) throw new Error("Set TF_PR2_TEST_PASSWORD (at least 8 characters)");

initializeApp({ projectId });
const auth = getAuth();
const db = getFirestore();
const identities = [
  { uid: "pr2_operator", email: "pr2-operator@example.invalid" },
  { uid: "pr2_trainer", email: "pr2-trainer@example.invalid" }
];
// Fixtures are create-only. Refuse a reused project before modifying Auth or Firestore.
for (const identity of identities) {
  try {
    await auth.getUser(identity.uid);
    throw new Error(`PR 2 Auth fixture ${identity.uid} already exists`);
  } catch (error) {
    if (error.code !== "auth/user-not-found") throw error;
  }
}
const protectedPaths = [
  "users/pr2_operator", "users/pr2_trainer", "users/pr2_trainer/authorizations/systemCatalog",
  "workspaces/pr2_workspace", "workspaces/pr2_workspace/memberships/pr2_trainer",
  "workspaces/pr2_workspace/trainerProfiles/pr2_trainer",
  "users/pr2_trainer/membershipRefs/pr2_workspace"
];
if ((await db.getAll(...protectedPaths.map(path => db.doc(path)))).some(document => document.exists) ||
    !(await db.collection("users/pr2_trainer/membershipRefs").limit(1).get()).empty) {
  throw new Error("PR 2 Firestore fixture already exists; refusing to replace protected state");
}
for (const identity of identities) {
  await auth.createUser({ ...identity, password, emailVerified: true });
}
// The trainer has an Auth identity and a valid empty account but no membership or role.
// Auth cannot join this transaction; a failure may leave only the new Auth fixture users.
await db.runTransaction(async transaction => {
  const refs = await transaction.get(db.collection("users/pr2_trainer/membershipRefs").limit(1));
  if (!refs.empty) throw new Error("PR 2 trainer already has a membership reference");
  transaction.create(db.doc("users/pr2_trainer"), {
    schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1,
    emailNormalized: "pr2-trainer@example.invalid", displayName: ""
  });
  transaction.create(db.doc("users/pr2_trainer/authorizations/systemCatalog"), {
    schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 1
  });
});
console.log("Seeded PR 2 emulator-only operator and pending trainer identities; no workspace was created.");
