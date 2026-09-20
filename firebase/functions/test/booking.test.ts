import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { bookAppointment, cancelAppointment, cleanupTerminalAppointmentLocks, coveredUtcBucketMinutes, rescheduleAppointment } from "../src/booking.js";
import { emulatorFirestore } from "../src/environment.js";

const db = emulatorFirestore();
const policy = {
  slotQuantumMinutes: 5 as const,
  maxDurationMinutes: 180,
  maxBufferBeforeMinutes: 30,
  maxBufferAfterMinutes: 30,
  maxBuckets: 90,
  maxWrites: 200,
  scheduleRevision: 1
};

describe("booking contention probe", () => {
  beforeEach(async () => {
    await db.recursiveDelete(db.collection("workspaces"));
  });

  it("outward-rounds the complete buffered UTC interval", () => {
    const base = Date.UTC(2026, 8, 19, 10, 2, 30);
    assert.deepEqual(coveredUtcBucketMinutes({ startsAtMillis: base, endsAtMillis: base + 31 * 60_000, bufferBeforeMinutes: 3, bufferAfterMinutes: 4 }, 5), [29830195, 29830200, 29830205, 29830210, 29830215, 29830220, 29830225, 29830230, 29830235]);
  });

  it("allows at most one overlapping first booking in an empty range", async () => {
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const common = { workspaceId: "ws_booking", trainerId: "trainer", clientId: "client", callerUid: "client", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 10, bufferAfterMinutes: 10 };
    const results = await Promise.allSettled([
      bookAppointment(db, { ...common, appointmentId: "appt_a", idempotencyKey: "key_a" }, policy),
      bookAppointment(db, { ...common, appointmentId: "appt_b", idempotencyKey: "key_b" }, policy)
    ]);
    assert.equal(results.filter((result) => result.status === "fulfilled").length, 1);
    assert.equal(results.filter((result) => result.status === "rejected").length, 1);
    const appointments = await db.collection("workspaces/ws_booking/appointments").get();
    const locks = await db.collection("workspaces/ws_booking/bookingSlots").get();
    const receipts = await db.collection("workspaces/ws_booking/bookingCommands").get();
    assert.equal(appointments.size, 1);
    assert.equal(receipts.size, 1);
    assert.ok(locks.size > 0);
    assert.ok(locks.docs.every((lock) => lock.get("appointmentId") === appointments.docs[0]!.id));
  });

  it("replays without duplicate locks and reschedules/cancels atomically", async () => {
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const request = { workspaceId: "ws_lifecycle", trainerId: "trainer", clientId: "client", callerUid: "client", appointmentId: "appt", idempotencyKey: "book", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 0, bufferAfterMinutes: 0 };
    const first = await bookAppointment(db, request, policy);
    const replay = await bookAppointment(db, request, policy);
    assert.equal(replay.replayed, true);
    assert.deepEqual(replay.bucketIds, first.bucketIds);
    const moved = await rescheduleAppointment(db, { ...request, idempotencyKey: "move", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy);
    assert.equal(moved.revision, 2);
    const movedReplay = await rescheduleAppointment(db, { ...request, idempotencyKey: "move", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy);
    assert.equal(movedReplay.replayed, true);
    assert.deepEqual(movedReplay.bucketIds, moved.bucketIds);
    for (const oldId of first.bucketIds) assert.equal((await db.doc(`workspaces/ws_lifecycle/bookingSlots/${oldId}`).get()).exists, false);
    for (const newId of moved.bucketIds) assert.equal((await db.doc(`workspaces/ws_lifecycle/bookingSlots/${newId}`).get()).get("appointmentId"), "appt");
    await assert.rejects(() => cleanupTerminalAppointmentLocks(db, "ws_lifecycle", "appt"), /live-appointment/);
    const cancelled = await cancelAppointment(db, { workspaceId: "ws_lifecycle", appointmentId: "appt", callerUid: "client", idempotencyKey: "cancel", expectedRevision: 2 });
    assert.equal(cancelled.status, "cancelled");
    assert.equal((await db.collection("workspaces/ws_lifecycle/bookingSlots").get()).size, 0);
    assert.equal(await cleanupTerminalAppointmentLocks(db, "ws_lifecycle", "appt"), 0);
  });

  it("rejects immutable trainer/client changes without touching the appointment or locks", async () => {
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const request = { workspaceId: "ws_identity", trainerId: "trainer", clientId: "client", callerUid: "client", appointmentId: "appt", idempotencyKey: "book", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 0, bufferAfterMinutes: 0 };
    const booked = await bookAppointment(db, request, policy);
    const appointmentPath = "workspaces/ws_identity/appointments/appt";
    const originalAppointment = (await db.doc(appointmentPath).get()).data();

    await assert.rejects(
      () => rescheduleAppointment(db, { ...request, trainerId: "alternate-trainer", idempotencyKey: "move-trainer", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy),
      /immutable-trainer-mismatch/
    );
    await assert.rejects(
      () => rescheduleAppointment(db, { ...request, clientId: "alternate-client", idempotencyKey: "move-client", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy),
      /immutable-client-mismatch/
    );

    assert.deepEqual((await db.doc(appointmentPath).get()).data(), originalAppointment);
    for (const originalId of booked.bucketIds) {
      assert.equal((await db.doc(`workspaces/ws_identity/bookingSlots/${originalId}`).get()).get("appointmentId"), "appt");
    }
    const allLocks = await db.collection("workspaces/ws_identity/bookingSlots").get();
    assert.equal(allLocks.size, booked.bucketIds.length);
    assert.ok(allLocks.docs.every((lock) => lock.get("trainerId") === "trainer"));
    assert.equal((await db.collection("workspaces/ws_identity/bookingCommands").get()).size, 1);
  });

  it("rejects an unsupported transaction budget before writing", async () => {
    const base = Date.UTC(2026, 8, 19, 10, 0);
    await assert.rejects(() => bookAppointment(db, { workspaceId: "ws_budget", trainerId: "trainer", clientId: "client", callerUid: "client", appointmentId: "appt", idempotencyKey: "key", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 0, bufferAfterMinutes: 0 }, { ...policy, maxWrites: 5 }), /write-budget/);
    assert.equal((await db.collection("workspaces/ws_budget/appointments").get()).size, 0);
  });
});
