import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { participantUid, safeBufferMinutes, safeEpochMillis, strictPathIdentifier } from "../src/input-validation.js";

function rejectionMessage(action: () => unknown): string {
  try {
    action();
  } catch (error) {
    return (error as { message?: string }).message ?? "";
  }
  return "<no-rejection>";
}

describe("callable input validation", () => {
  it("accepts the Firebase UID domain the booking path can represent", () => {
    // Embedded dots, email shapes, punctuation, Unicode, padding, and the maximum length of one
    // raw Firestore path segment are all accepted, because a dot is safe inside a document ID.
    const accepted = [
      "uid_one",
      "trainer@example.com",
      "user+alias@example.com",
      "dotted.name.with.many.parts",
      "a.b-c_d:e|f!g",
      "üser-Ω",
      "  padded uid  ",
      "..leading.and.trailing..",
      "a".repeat(128)
    ];
    for (const uid of accepted) assert.equal(participantUid(uid, "trainerId"), uid);
  });

  it("rejects any identifier that cannot be exactly one Firestore path segment", () => {
    const unrepresentable = ["", "   ", "\t\n", "a".repeat(129), "ws/memberships/admin", "a/b", "/leading", "trailing/", ".", "..", "__name__"];
    for (const uid of unrepresentable) {
      assert.match(
        rejectionMessage(() => participantUid(uid, "trainerId")),
        /must be a non-empty Firebase UID of at most 128 characters|must not contain a Firestore path separator|must be a valid Firestore document ID/
      );
    }
    for (const value of [undefined, null, 42, true, {}, []]) {
      assert.match(rejectionMessage(() => participantUid(value, "trainerId")), /must be a non-empty Firebase UID of at most 128 characters/);
    }
  });

  it("never normalizes distinct valid participant identifiers", () => {
    const distinct = ["user@example.com", "USER@example.com", "user@example.com ", " user@example.com"];
    assert.deepEqual(distinct.map((uid) => participantUid(uid, "trainerId")), distinct);
    assert.equal(new Set(distinct).size, distinct.length);
  });

  it("keeps non-UID path identifiers strict", () => {
    for (const value of ["ws_callable", "appt-1", "key_1", "ws name"]) assert.equal(strictPathIdentifier(value, "workspaceId"), value);
    for (const value of ["ws.dot", "ws/slash", "", "a".repeat(129)]) {
      assert.match(rejectionMessage(() => strictPathIdentifier(value, "workspaceId")), /path-safe/);
    }
  });

  it("rejects malformed epoch-millisecond and buffer values", () => {
    assert.equal(safeEpochMillis(0, "startsAtMillis"), 0);
    assert.equal(safeEpochMillis(8.64e15, "startsAtMillis"), 8.64e15);
    for (const value of [-1, 1.5, "0", Number.MAX_SAFE_INTEGER, undefined, null]) {
      assert.match(rejectionMessage(() => safeEpochMillis(value, "startsAtMillis")), /epoch-millisecond/);
    }
    assert.equal(safeBufferMinutes(0, "bufferBeforeMinutes"), 0);
    assert.equal(safeBufferMinutes(240, "bufferBeforeMinutes"), 240);
    for (const value of [-1, 241, 1.5, "10", undefined]) {
      assert.match(rejectionMessage(() => safeBufferMinutes(value, "bufferBeforeMinutes")), /minute count/);
    }
  });
});
