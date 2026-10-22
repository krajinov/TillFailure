// Trusted local fixture procedure, never an automatic history-loss detector or migration.
import { emulatorFirestore } from "../lib/src/environment.js";
import { requireProvisioningEmulators } from "../lib/src/provisioning.js";
import { RESOLUTION_CONFIG_PATH, RESOLUTION_GENERATION } from "../lib/src/command-identity.js";
requireProvisioningEmulators();
const db=emulatorFirestore(), provenance=process.env.TF_RESOLUTION_PROVENANCE_ID;
if(!provenance || !/^[A-Za-z0-9_-]{1,80}$/.test(provenance)) throw new Error("Trusted provenance ID required");
const action=process.argv[2], ref=db.doc(RESOLUTION_CONFIG_PATH);
if(action === "initialize") {
 if(process.env.TF_RESOLUTION_HISTORY_ATTESTATION !== "new-task-dataset-no-prior-effects") throw new Error("Explicit fresh-dataset provenance attestation required");
 for(const root of ["users","workspaces","invitations","lifecycleCommands"]) if(!(await db.collection(root).limit(1).get()).empty) throw new Error("Refusing initialization over existing business history");
 await ref.create({schemaVersion:1,generation:RESOLUTION_GENERATION,provenanceId:provenance,historyVerified:true,executionEnabled:true,absenceEnabled:true,maxFencesPerCaller:20});
} else if(action === "disable") {
 const current=await ref.get();if(current.get("provenanceId")!==provenance) throw new Error("Provenance mismatch");
 await ref.update({historyVerified:false,executionEnabled:false,absenceEnabled:false});
} else throw new Error("Choose initialize or disable; no repair/re-enable operation exists");
console.log("Command resolution fixture coverage configured");
