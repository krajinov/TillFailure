import { readFile } from "node:fs/promises";
import { getAuth } from "firebase-admin/auth";
import { emulatorFirestore } from "./environment.js";
import { PR2_EMULATOR_OPERATOR_UID, provisionTrainer, requireProvisioningEmulators, TrainerProvisioningInput } from "./provisioning.js";

// This is a local operator process, not an HTTP/callable export. Its credentials are read only
// from the operator's environment and never bundled in either mobile application.
async function main(): Promise<void> {
  requireProvisioningEmulators();
  const allowedUid = PR2_EMULATOR_OPERATOR_UID;
  const email = process.env.TF_PR2_OPERATOR_EMAIL;
  const password = process.env.TF_PR2_OPERATOR_PASSWORD;
  const inputFile = process.argv[2];
  if (!email || !password || !inputFile || process.argv.length !== 3) throw new Error("operator-credentials-and-input-file-required");
  const db = emulatorFirestore();
  const auth = getAuth();
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true })
  });
  if (!response.ok) throw new Error("operator-authentication-failed");
  const credential = await response.json() as { idToken?: string };
  if (!credential.idToken) throw new Error("operator-authentication-failed");
  const claims = await auth.verifyIdToken(credential.idToken, true);
  const operatorUser = await auth.getUser(claims.uid);
  if (claims.uid !== allowedUid || claims.email_verified !== true || operatorUser.disabled || !operatorUser.emailVerified) {
    throw new Error("operator-unauthorized");
  }
  const input = JSON.parse(await readFile(inputFile, "utf8")) as TrainerProvisioningInput;
  const trainerUser = await auth.getUser(input.uid);
  const result = await provisionTrainer(db, input, {
    uid: trainerUser.uid, email: trainerUser.email ?? "", emailVerified: trainerUser.emailVerified,
    disabled: trainerUser.disabled
  }, {
    uid: claims.uid, allowedUid, emailVerified: operatorUser.emailVerified, disabled: operatorUser.disabled
  }, { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 });
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.message : "provisioning-failed"}\n`);
  process.exitCode = 1;
});
