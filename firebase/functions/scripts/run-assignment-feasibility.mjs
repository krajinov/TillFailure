// Disposable probes only. No import/export/reset of a retained native dataset.
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const output = path.join(repo, '.firebase/m7-pr1a');
if (!process.version.startsWith('v22.')) throw new Error('Use isolated Node 22');
const full = process.argv.includes('--regression');
const focused = process.argv.includes('--focused-regression');
for (const port of [46799, 46780, 5001, 46719, 46700, 46720]) await new Promise((resolve, reject) => {
  const server = createServer(); server.once('error', reject); server.listen(port, '127.0.0.1', () => server.close(resolve));
});
await mkdir(output, { recursive: true });
for (const f of ['firestore.rules', 'storage.rules', 'firestore.indexes.json']) await writeFile(path.join(output, f), await readFile(path.join(repo, 'firebase', f)));
await writeFile(path.join(output, 'firebase.json'), JSON.stringify({ functions: { source: '../../firebase/functions', runtime: 'nodejs22' },
  firestore: { rules: 'firestore.rules', indexes: 'firestore.indexes.json' }, storage: { rules: 'storage.rules' }, emulators: {
    auth: { host: '127.0.0.1', port: 46799 }, firestore: { host: '127.0.0.1', port: 46780 }, functions: { host: '127.0.0.1', port: 5001 },
    storage: { host: '127.0.0.1', port: 46719 }, hub: { host: '127.0.0.1', port: 46700 }, logging: { host: '127.0.0.1', port: 46720 }, ui: { enabled: false }, singleProjectMode: false } }));
const env = { ...process.env, PATH: path.dirname(process.execPath) + path.delimiter + process.env.PATH,
  TF_M7_PR1A_EVIDENCE: output, TF_RESOLUTION_PROVENANCE_ID: 'publications-isolated' };
const run = (exe, args, cwd) => new Promise((resolve, reject) => {
  const child = spawn(exe, args, { cwd, env, stdio: 'inherit' });
  child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(new Error(`Child exit ${code}`)));
});
await run(process.execPath, ['node_modules/typescript/bin/tsc', '-p', 'tsconfig.json'], path.join(repo, 'firebase/functions'));
const tests = full ? 'lib/test/*.test.js' : (focused ? 'lib/test/client-revocation.test.js lib/test/program-publications.test.js ' : '') +
  'lib/test/assignment-pr1a-contract.test.js lib/test/assignment-pr1a-transactions.test.js lib/test/assignment-pr1a-rules.test.js';
await run(process.execPath, [path.join(repo, 'firebase/functions/node_modules/firebase-tools/lib/bin/firebase.js'), 'emulators:exec',
  '--config', path.join(output, 'firebase.json'), '--project', 'demo-tillfailure-m3', '--only', 'auth,firestore,storage,functions',
  `cd firebase/functions && node --test --test-concurrency=1 ${tests}`], repo);
