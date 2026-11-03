// Deterministic bounds for the implemented inline format. No random ciphertext assumption.
import assert from 'node:assert/strict';
const root = 16384; // enforced serialized stored root DTO, contains the complete B projection
const instruction = 2 + 2000 * 4; // JSON quotes + max UTF-8 bytes per permitted scalar
const labels = 2 + 8 * (2 + 40 * 4) + 7; // brackets, quoted labels and commas
const itemFields = 128; // three fixed field names, separators, and conservative extra punctuation
const content = root + 12 * (instruction + 2 * labels + itemFields);
const version = content + 8192; // fixed metadata, hashes, identifiers, numbers and timestamp
const response = version + 8192; // complete preview/DTO wrapper, publish tuple, authority, hash
// For these fixed maps/arrays, protobuf contributes <=32 extra bytes per JSON node:
// map/array wrappers and length varints, field tags, and worst-case varint numbers.
// The inline version + root + receipt contain fewer than 1,200 nodes; the generous
// 8 KiB reserve covers the three fixed demo-project document paths and commit metadata.
// Pinned emulator 1.22.0: FirestoreEmulatorConverters.toTransactionBytes delegates to
// LocalTransactionConverter.toTransactionProto, which sets only handle on a fresh
// internal Transaction builder. writeTo uses field 2 fixed64: at most 1+8 bytes.
// Jar SHA256: 9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c.
const emulatorTransactionBytes = 9;
assert(emulatorTransactionBytes + 2 < 8192);
const commit = version + root + 4096 + 1200 * 32 + 8192;
const plaintext = 36500;
const swift = 181 + 8 * Math.ceil(plaintext / 3); // slash escaping of every base64 symbol
const android = 108 + 4 * Math.ceil((plaintext + 16) / 3); // fixed production key identifier
assert(version <= 160 * 1024);assert(response <= 192 * 1024);assert(commit <= 256 * 1024);
assert(swift <= 98304);assert(android <= 98304);
console.log(JSON.stringify({content,version,response,commit,plaintext,swift,android,emulatorTransactionBytes},null,2));
