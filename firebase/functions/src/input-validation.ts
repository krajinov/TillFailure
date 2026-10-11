import { HttpsError } from "firebase-functions/v2/https";

// Firestore document IDs (and the path segments built from them) must be valid UTF-8, must not
// contain "/", must not be "." or "..", and must not match the reserved "__.*__" form. The project
// additionally bounds identifier length with the shared Firebase UID limit of 128 UTF-16 code
// units. The complete Firebase Auth UID domain also allows characters this raw:
// users/{uid} path scheme cannot represent (for example an embedded "/"), so such identities are
// rejected here instead of being silently mapped onto a different Firestore path.
const MAX_IDENTIFIER_LENGTH = 128;
const RESERVED_DOCUMENT_ID = /^__.*__$/;

function invalid(field: string, requirement: string): HttpsError {
  return new HttpsError("invalid-argument", `${field} ${requirement}`);
}

// Non-UID identifiers (workspace IDs, appointment IDs, idempotency keys) keep the existing
// stricter documented validation: one path-safe segment without separators or dots.
export function strictPathIdentifier(value: unknown, field: string): string {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_IDENTIFIER_LENGTH || value.includes("/") || value.includes(".")) {
    throw invalid(field, "must be a non-empty path-safe string");
  }
  return value;
}

// Participant identifiers are Firebase UIDs that the booking path uses as raw Firestore document
// path segments. An embedded dot is safe inside a document ID, so email-shaped and punctuated
// UIDs are accepted; a "/" is not, because it would create another path segment. Validation is
// deliberately not a character whitelist, so it stays consistent with the shared Firebase UID
// contract (non-blank, at most 128 UTF-16 code units) wherever the path model permits it, and
// never normalizes two distinct UIDs into one identity.
export function participantUid(value: unknown, field: string): string {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_IDENTIFIER_LENGTH || value.trim().length === 0) {
    throw invalid(field, "must be a non-empty Firebase UID of at most 128 characters");
  }
  if (value.includes("/")) throw invalid(field, "must not contain a Firestore path separator");
  if (value === "." || value === ".." || RESERVED_DOCUMENT_ID.test(value)) throw invalid(field, "must be a valid Firestore document ID");
  return value;
}

export function safeEpochMillis(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0 || value > 8.64e15) {
    throw invalid(field, "must be an integer epoch-millisecond value");
  }
  return value;
}

export function safeBufferMinutes(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0 || value > 240) {
    throw invalid(field, "must be a non-negative integer minute count");
  }
  return value;
}
