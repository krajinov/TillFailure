import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "../lib/src/environment.js";
import { provisionTrainer, requireProvisioningEmulators } from "../lib/src/provisioning.js";
import { invitationOperation } from "../lib/src/invitations.js";
import { completeOnboarding } from "../lib/src/onboarding.js";
requireProvisioningEmulators();
const password=process.env.TF_PR5_FIXTURE_PASSWORD;
if(!password || password.length<12) throw new Error("Temporary fixture credential required");
const db=emulatorFirestore(),auth=getAuth();
const prefix=process.env.TF_PR5_FIXTURE_PREFIX;
if(!prefix || !/^m5p5r[0-9]{6,20}$/.test(prefix))throw new Error("Invalid task fixture prefix");
const platforms=process.env.TF_PR5_FIXTURE_PLATFORM ? [process.env.TF_PR5_FIXTURE_PLATFORM] : ["android","ios"];
if(platforms.some(p=>!["android","ios"].includes(p)))throw new Error("Invalid fixture platform");
const modes=process.env.TF_PR5_FIXTURE_MODE ? [process.env.TF_PR5_FIXTURE_MODE] : ["applied","fenced","proof","conflict","settled","ui"];
if(modes.some(m=>!["applied","fenced","proof","conflict","settled","ui"].includes(m)))throw new Error("Invalid fixture mode");
for(const platform of platforms) for(const mode of modes) {
 const base=`${prefix}_${platform}_${mode}`,wid=`${base}_workspace`,trainer=`${base}_trainer`,uid=`${base}_client`;
 if((await db.doc(`workspaces/${wid}`).get()).exists) throw new Error("Existing workspace: refuse reset");
 for(const id of [trainer,uid]) {try {await auth.getUser(id);throw new Error("Existing identity: refuse replacement");}catch(e){if(e.code!=="auth/user-not-found")throw e;}
  if((await db.doc(`users/${id}`).get()).exists)throw new Error("Existing account: refuse replacement");}
 for(const id of [trainer,uid])await auth.createUser({uid:id,email:`${id}@example.invalid`,password,emailVerified:true});
 await provisionTrainer(db,{uid:trainer,workspaceId:wid,workspaceName:`${platform} resolution ${mode}`,idempotencyKey:`${base}-bootstrap`,expectedAccountRevision:0,expectedEntitlementRevision:0,expectedWorkspaceRevision:0},
 {uid:trainer,email:`${trainer}@example.invalid`,emailVerified:true,disabled:false},{uid:"pr2_operator",allowedUid:"pr2_operator",emailVerified:true,disabled:false},{maxMembershipsPerAccount:2,maxMembershipsPerWorkspace:5});
 const invite=await invitationOperation(db,trainer,{action:"create",workspaceId:wid,email:`${uid}@example.invalid`,idempotencyKey:`${base}-invite`});
 await invitationOperation(db,uid,{action:"accept",invitationId:invite.invitationId,token:invite.deliveryToken,expectedVersion:1,idempotencyKey:`${base}-accept`});
 if(mode!=="fenced") await completeOnboarding(db,uid,{action:"complete-onboarding",workspaceId:wid,idempotencyKey:`${base}-onboard`,onboarding:{displayName:"Original fixture",locale:"en",timeZoneId:"UTC",acknowledged:true,expectedAccountRevision:0,expectedOnboardingRevision:0}});

}
console.log("Create-only PR 5 synthetic fixtures ready");
