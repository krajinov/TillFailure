import { Firestore, Transaction } from 'firebase-admin/firestore';
import { requireCommandGeneration, RESOLUTION_GENERATION } from './command-identity.js';
import { exerciseExact as exact, exerciseBytes as bytes } from './custom-exercise-contract.js';
import { stableHash } from './hashing.js';

// This attestation is create-only, operator-owned, and never synthesized by a callable.
// Only a verified independent empty-history fixture is admitted in PR 1b. Historical
// receipts are neither rehashed nor migrated. Installing a boolean cannot unlock one.
export const ASSIGNMENT_BARRIERS = 'm7-pr1b-legacy-prefix-dual-namespace-index-denial-v1';
export async function requireAssignmentCoverage(tx: Transaction, db: Firestore, key: string) {
  const config = await requireCommandGeneration(tx, db, key);
  const provenance = process.env.TF_RESOLUTION_PROVENANCE_ID;
  const expected = process.env.TF_ASSIGNMENT_COVERAGE_SHA ?? config?.get('assignmentCoverageSha256');
  if (!config || bytes(config.data()??null)>4096 || !provenance || !expected || !/^[a-f0-9]{64}$/.test(expected)) throw new Error('outcome-unavailable');
  const s = await tx.get(db.doc(`commandResolutionCoverage/${provenance}`)), d = s.data();
  if (!exact(d, ['schemaVersion','generation','provenanceId','barriers','sourceFingerprint','exportSha256','historyKind','lifecycleCount','legacyCount']) ||
    bytes(d) > 4096 || stableHash(d) !== expected || d.schemaVersion !== 1 || d.generation !== RESOLUTION_GENERATION ||
    d.provenanceId !== provenance || d.barriers !== ASSIGNMENT_BARRIERS || d.historyKind !== 'independent-empty-before-protected-command' ||
    d.lifecycleCount !== 0 || d.legacyCount !== 0 || !/^[a-f0-9]{64}$/.test(d.sourceFingerprint) || !/^[a-f0-9]{64}$/.test(d.exportSha256)) throw new Error('outcome-unavailable');
  return config;
}
