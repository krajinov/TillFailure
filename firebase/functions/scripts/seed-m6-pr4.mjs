// Create-only, original synthetic emulator identities. Never initializes over old partitions/history.
import { RESOLUTION_CONFIG_PATH, RESOLUTION_GENERATION } from "../lib/src/command-identity.js";
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
import { programDraftOperation, sourceIdentity } from "../lib/src/program-drafts.js";
requireProvisioningEmulators();
const prefix=process.env.TF_M6P4_PREFIX,password=process.env.TF_M6P4_PASSWORD;
if(!prefix || !/^m6p4r[0-9a-z]{6,24}$/.test(prefix) || !password || password.length<12)throw new Error("Require fresh prefix and external fixture password");
const db=emulatorFirestore(),auth=getAuth(),uids=[];
const provenance=process.env.TF_RESOLUTION_PROVENANCE_ID;
if(!provenance || !/^[A-Za-z0-9_-]{1,80}$/.test(provenance))throw new Error("Require intact fixture provenance");
const coverage=db.doc(RESOLUTION_CONFIG_PATH),existing=await coverage.get();
if(existing.exists) {
  if(existing.get("schemaVersion")!==1 || existing.get("generation")!==RESOLUTION_GENERATION || existing.get("provenanceId")!==provenance || existing.get("historyVerified")!==true || existing.get("executionEnabled")!==true || existing.get("absenceEnabled")!==true)throw new Error("Coverage mismatch; nothing replaced");
} else await coverage.create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:provenance,historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:30});

const admissionOnly=process.env.TF_M6P4_ADMISSION_ONLY==="1";
for(const platform of ["android","ios"]) {
  if(!admissionOnly)uids.push(`${prefix}_${platform}_other`,`${prefix}_${platform}_ui`);
  for(const kind of ["publish"])for(const mode of admissionOnly?["admission"]:["applied","fenced","conflict","proof","failure","settled","admission"])uids.push(`${prefix}_${platform}_${kind}_${mode}`);
}
for(const uid of uids){try{await auth.getUser(uid);throw new Error("Occupied fixture identity");}catch(e){if(e.code!=="auth/user-not-found")throw e;}if((await db.doc(`users/${uid}`).get()).exists || (await db.doc(`workspaces/${uid}_ws`).get()).exists)throw new Error("Occupied fixture data");}
for(const uid of uids){await auth.createUser({uid,email:`${uid}@example.invalid`,emailVerified:true,password});await provisionTrainer(db,{uid,workspaceId:`${uid}_ws`,workspaceName:"Synthetic program drafts",idempotencyKey:`${uid}-bootstrap`,expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},{uid,email:`${uid}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});}
console.log(`Created ${uids.length} synthetic trainer fixtures; no credentials logged.`);

const sourceId=prefix+"_publication_source",source={schemaVersion:1,name:"Synthetic squat",nameNormalized:"synthetic squat",instructions:"Stand with control. Bend your knees and return upright. Synthetic demonstration only.",muscleGroups:["legs"],equipment:["bodyweight"],status:"published"};
await db.doc(`systemExercises/${sourceId}`).create(source);
const id="00000000-0000-4000-8000-000000000001";
for(const uid of uids.filter(u=>!u.endsWith("_other"))) {
 const wid=uid+"_ws",authority=(await programDraftOperation(db,uid,{action:"draft-prepare",workspaceId:wid})).authority;
 await programDraftOperation(db,uid,{action:"save-program-draft",workspaceId:wid,idempotencyKey:`or1-${RESOLUTION_GENERATION}-${uid}-seed`,draftSave:{templateId:id,expectedRevision:0,authority,title:"Synthetic publication",description:"Confirm this exact saved content.",workouts:[{id,title:"Strength",items:[{id,...sourceIdentity("system",sourceId,wid,uid,source),prescription:{sets:3,reps:10,restSeconds:60,loadGrams:null}}]}]}});
}
