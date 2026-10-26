// Create-only synthetic text catalog and trainer fixtures, exclusively on named loopback emulators.
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
requireProvisioningEmulators();
const password = process.env.TF_M6_PASSWORD;
const prefix = process.env.TF_M6_PREFIX;
if (!password || password.length < 12 || !prefix || !/^m6_[a-z0-9_]{1,40}$/.test(prefix)) throw new Error("Require a fresh M6 prefix and external fixture password");
const db = emulatorFirestore(), auth = getAuth();
const identities = ["android", "ios", "android_b", "ios_b"].map(platform => ({uid:`${prefix}_${platform}`,email:`${prefix}-${platform}@example.invalid`,workspaceId:`${prefix}_${platform}_ws`}));
const exercises = Array.from({length:23},(_,i)=>({id:`ex_${String(i+1).padStart(2,"0")}`,data:{schemaVersion:1,name:`Synthetic exercise ${String(i+1).padStart(2,"0")}`,nameNormalized:`synthetic exercise ${String(i+1).padStart(2,"0")}`,muscleGroups:["demonstration"],equipment:["none"],instructions:"Synthetic exercise-library demonstration.\nThis original text is a UI fixture, with no personal or private metadata.",status:"published"}}));
// No replacement or initialization over retained state. Refuse an occupied catalog entirely.
if (!(await db.collection("systemExercises").limit(1).get()).empty) throw new Error("Catalog already occupied");
for(const identity of identities) {
  try {await auth.getUser(identity.uid);throw new Error("Fixture identity already occupied");} catch(e){if(e.code!=="auth/user-not-found")throw e;}
  if((await db.doc(`users/${identity.uid}`).get()).exists || (await db.doc(`workspaces/${identity.workspaceId}`).get()).exists) throw new Error("Fixture data already occupied");
}
for(const identity of identities) {
  await auth.createUser({uid:identity.uid,email:identity.email,emailVerified:true,password});
  await provisionTrainer(db,{uid:identity.uid,workspaceId:identity.workspaceId,workspaceName:"Synthetic exercise workspace",idempotencyKey:`${prefix}-${identity.uid}`,expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
    {...identity,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
}
await db.runTransaction(async tx=>{
  const occupied=await tx.get(db.collection("systemExercises").limit(1));if(!occupied.empty)throw new Error("Catalog became occupied");
  for(const exercise of exercises)tx.create(db.doc(`systemExercises/${exercise.id}`),exercise.data);
});
console.log("M6 create-only synthetic catalog/trainer fixtures ready; no credentials logged.");
