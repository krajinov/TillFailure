// Deterministic accepted-state bounds for the reviewed first-slice child layout.
// No network, stored-user-data access, source transpilation or application build.
import assert from 'node:assert/strict';
const itemCount = 12, documentCount = 20, attempts = 3;
// Header/snapshot/workout/items/plan/inventory/index/receipt/workspace update.
const logicalWriteJson = itemCount * 24576 + 2 * 8192 + 5 * 4096 + 16384;
// <=2,000 nodes across these fixed-shape maps/arrays. 32 bytes/node covers tags,
// wrapper/length varints and worst-width integers. Strings are already in JSON.
// Each UTF-16-bounded UID/workspace has <=384 UTF-8 path bytes. At most two such
// segments plus fixed collection names and UUIDs fit 2,048 bytes/document name.
const protobufReserve = 2000 * 32 + documentCount * 2048 + 8192;
const commit = logicalWriteJson + protobufReserve;
// Fixed allowlists: item including nested prescriptions and both label arrays has
// <=64 default automatic entries. 4 KiB includes two bounded document names,
// field names, 1,500-byte indexed value and entry overhead. No copied text query.
// Include old workspace and separate admission entries, not just new documents.
const changedIndexBytes = (documentCount + 2) * 64 * 4096;
const requestAndIndexReserve = commit + changedIndexBytes;
const acceptedReadJson = 10 * 16384 + 163840 + 5 * 4096; // 16 read observations, including duplicate caller account
const plaintext = 36500;
const swift = 181 + 8 * Math.ceil(plaintext / 3);
const android = 108 + 4 * Math.ceil((plaintext + 16) / 3);
assert.equal(logicalWriteJson, 348160);
assert.equal(commit, 461312);
assert(commit <= 524288);
assert(requestAndIndexReserve < 10 * 1024 * 1024);
assert(swift === 97517 && android === 48796 && swift <= 98304);
console.log(JSON.stringify({ classification: 'deterministic bounds, not measured network traffic or production index internals',
  logicalWriteJson, protobufReserve, commit, changedIndexBytes, requestAndIndexReserve, acceptedReadJson,
  readsIncludingAdmission: 17, committedWritesIncludingAdmission: 21, maxReadAttempts: 17 * attempts,
  maxWriteAttempts: 21 * attempts, plaintext, swift, android, packet: 65536, mutation: 32768,
  resolverUnits: 8192, resolverBytes: 24576, pending: 16384 }, null, 2));
