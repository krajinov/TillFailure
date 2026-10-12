// Run only against guarded local emulators, before any native fixtures. Never edit tracked source.
import { execFileSync, spawnSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import ts from "typescript";
import { requireProvisioningEmulators } from "../lib/src/provisioning.js";
requireProvisioningEmulators();
const baseline = "3d18b549a770eedbeb42fc42db23ebf60fa579ff";
const generatedPath = new URL("../lib/src/invitations.js", import.meta.url);
const fixed = readFileSync(generatedPath, "utf8");
try {
  const source = execFileSync("git", ["show", `${baseline}:firebase/functions/src/invitations.ts`], { encoding: "utf8" });
  writeFileSync(generatedPath, ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ES2022,
    target: ts.ScriptTarget.ES2022 } }).outputText);
  const run = spawnSync(process.execPath, ["--test", "--test-concurrency=1", "--test-name-pattern=P1", "lib/test/invitations.test.js"], { encoding: "utf8" });
  const output = `${run.stdout}\n${run.stderr}`;
  const failures = Number(output.match(/(?:#|ℹ) fail (\d+)/)?.[1]);
  if (run.status === 0 || failures !== 14) throw new Error(`Unexpected sensitivity result: status=${run.status}, failures=${failures}`);
  console.log(`PR3_P1_SENSITIVITY baseline=${baseline} expectedFailures=${failures} PASS`);
} finally {
  writeFileSync(generatedPath, fixed);
}
