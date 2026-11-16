// Accepted-shape upper bounds, separate from measured SDK/protobuf/native output.
// No dataset access, product mutation, SDK/dependency or encoding change.
import assert from 'node:assert/strict';
const W=3,I=12,P=3,writes=6+W+I+P;
const logicalWriteJson=I*24576+2*8192+(W+P+3)*4096+16384;
// <=2500 nodes: 12 fixed-shape items (incl. 16 labels each), <=3 workouts,
// <=3 plans, 19 inventory paths, safe header/snapshot/index/receipt/fence.
// 32/node reserves tags, wrappers, varints; JSON already bounds string bytes.
const protobufReserve=2500*32+writes*2048+8192;
const commitBound=logicalWriteJson+protobufReserve;
// Longest path has two <=128 UTF-16 segments (<=384 UTF-8 bytes each)
// and three UUIDs. Entry bound includes both document/parent names, 1500
// indexed-value bytes, 128 field-path and 128 entry-overhead bytes.
const longestDocumentName=Buffer.byteLength('projects/demo-tillfailure-m3/databases/(default)/documents/users//workspaces//assignedPrograms//snapshots/content/workouts//exercises/')+2*384+3*36;
const indexEntryBound=2*longestDocumentName+1500+128+128;
// All 23 new records including receipt <=64 entries (default plus the
// existing header composite/field override). Workspace update changes only
// membershipRevision (2 deletes +2 adds). Separate rate minute/count <=8
// changed entries. Unchanged arbitrary workspace fields are not reindexed.
const indexEntriesBound=23*64+4+8,indexBytesBound=indexEntriesBound*4096;
assert.equal(writes,24);assert.equal(logicalWriteJson,364544);assert.equal(commitBound,501888);
assert(commitBound<=524288);assert(indexEntryBound<=4096);assert(commitBound+indexBytesBound<10*1024*1024);
const n=36500,swift=181+8*Math.ceil(n/3),android=108+4*Math.ceil((n+16)/3);
assert.equal(swift,97517);assert.equal(android,48796);assert(swift<=98304);
console.log(JSON.stringify({classification:'modeled accepted-shape reserves; not production index internals or latency',writes,admissionWrites:1,logicalWriteJson,protobufReserve,commitBound,longestDocumentName,indexEntryBound,indexEntriesBound,indexBytesBound,commitAndIndexReserve:commitBound+indexBytesBound,plaintext:n,swift,android,graphDocuments:3+W+I+P,inventoryPaths:1+W+I+P,trainerCalls:21,trainerReads:630,trainerAdmissionReads:21,clientSdkReads:22,clientTimeBusinessReads:4,clientTimeAdmissionReads:1},null,2));
