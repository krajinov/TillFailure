import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const projectId = "demo-tillfailure-m3";
if (!projectId.startsWith("demo-")) {
  throw new Error("Refusing to run against a non-demo Firebase project ID");
}

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const functionsDir = path.resolve(scriptDir, "..");
const repoRoot = path.resolve(functionsDir, "../..");
const firebaseBin = path.join(functionsDir, "node_modules", ".bin", "firebase");

const child = spawn(
  firebaseBin,
  [
    "emulators:exec",
    "--config", path.join(repoRoot, "firebase.json"),
    "--project", projectId,
    "--only", "auth,firestore,storage,functions",
    "npm test"
  ],
  {
    cwd: functionsDir,
    stdio: "inherit",
    env: { ...process.env, GCLOUD_PROJECT: projectId }
  }
);

child.on("exit", (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  process.exit(code ?? 1);
});
