// Create-only, original synthetic emulator identities. Never initializes over old partitions/history.
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
requireProvisioningEmulators();
const prefix=process.env.TF_M6P2_PREFIX,password=process.env.TF_M6P2_PASSWORD;
if(!prefix || !/^m6p2r[0-9a-z]{6,24}$/.test(prefix) || !password || password.length<12)throw new Error("Require fresh prefix and external fixture password");
const db=emulatorFirestore(),auth=getAuth(),uids=[];
const admissionOnly=process.env.TF_M6P2_ADMISSION_ONLY==="1";
for(const platform of ["android","ios"]) {
  if(!admissionOnly)uids.push(`${prefix}_${platform}_other`,`${prefix}_${platform}_ui`);
  for(const kind of ["create","correct"])for(const mode of admissionOnly?["admission"]:["applied","fenced","conflict","proof","failure","settled"])uids.push(`${prefix}_${platform}_${kind}_${mode}`);
}
for(const uid of uids){try{await auth.getUser(uid);throw new Error("Occupied fixture identity");}catch(e){if(e.code!=="auth/user-not-found")throw e;}if((await db.doc(`users/${uid}`).get()).exists || (await db.doc(`workspaces/${uid}_ws`).get()).exists)throw new Error("Occupied fixture data");}
for(const uid of uids){await auth.createUser({uid,email:`${uid}@example.invalid`,emailVerified:true,password});await provisionTrainer(db,{uid,workspaceId:`${uid}_ws`,workspaceName:"Synthetic custom exercises",idempotencyKey:`${uid}-bootstrap`,expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},{uid,email:`${uid}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});}
console.log(`Created ${uids.length} synthetic trainer fixtures; no credentials logged.`);
