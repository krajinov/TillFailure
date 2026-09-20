import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { bookAppointment, BookingRequest, cancelAppointment, cleanupTerminalAppointmentLocks, coveredUtcBucketMinutes, rescheduleAppointment } from "../src/booking.js";
import { emulatorFirestore } from "../src/environment.js";
import { commandId } from "../src/hashing.js";

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

interface SeedOptions {
  readonly workspaceStatus?: string;
  readonly trainerMembershipStatus?: string;
  readonly clientMembershipStatus?: string;
}

// Authoritative emulator fixture data created through isolated test setup (Admin SDK),
// exactly as a trusted bootstrap would; authorization itself is never bypassed.
async function seedBookingWorkspace(workspaceId: string, options: SeedOptions = {}): Promise<void> {
  const batch = db.batch();
  batch.set(db.doc("users/trainer"), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
  batch.set(db.doc("users/client"), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
  batch.set(db.doc(`workspaces/${workspaceId}`), { schemaVersion: 1, status: options.workspaceStatus ?? "active", membershipRevision: 1, activeMembershipCount: 2 });
  batch.set(db.doc(`workspaces/${workspaceId}/memberships/trainer`), { schemaVersion: 1, workspaceId, userId: "trainer", role: "trainer", status: options.trainerMembershipStatus ?? "active", revision: 1, catalogContributionActive: true });
  batch.set(db.doc(`workspaces/${workspaceId}/memberships/client`), { schemaVersion: 1, workspaceId, userId: "client", role: "client", status: options.clientMembershipStatus ?? "active", revision: 1, catalogContributionActive: true });
  await batch.commit();
}

function bookingRequest(workspaceId: string, overrides: Partial<BookingRequest> = {}): BookingRequest {
  const base = Date.UTC(2026, 8, 19, 10, 0);
  return {
    workspaceId,
    trainerId: "trainer",
    clientId: "client",
    callerUid: "client",
    appointmentId: "appt",
    idempotencyKey: "book",
    startsAtMillis: base,
    endsAtMillis: base + 60 * 60_000,
    bufferBeforeMinutes: 0,
    bufferAfterMinutes: 0,
    ...overrides
  };
}

describe("booking contention probe", () => {
  beforeEach(async () => {
    await db.recursiveDelete(db.collection("workspaces"));
    await db.recursiveDelete(db.collection("users"));
  });

  it("outward-rounds the complete buffered UTC interval", () => {
    const base = Date.UTC(2026, 8, 19, 10, 2, 30);
    assert.deepEqual(coveredUtcBucketMinutes({ startsAtMillis: base, endsAtMillis: base + 31 * 60_000, bufferBeforeMinutes: 3, bufferAfterMinutes: 4 }, 5), [29830195, 29830200, 29830205, 29830210, 29830215, 29830220, 29830225, 29830230, 29830235]);
  });

  it("allows at most one overlapping first booking in an empty range", async () => {
    await seedBookingWorkspace("ws_booking");
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
    await seedBookingWorkspace("ws_lifecycle");
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
    await seedBookingWorkspace("ws_identity");
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const request = { workspaceId: "ws_identity", trainerId: "trainer", clientId: "client", callerUid: "client", appointmentId: "appt", idempotencyKey: "book", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 0, bufferAfterMinutes: 0 };
    const booked = await bookAppointment(db, request, policy);
    const appointmentPath = "workspaces/ws_identity/appointments/appt";
    const originalAppointment = (await db.doc(appointmentPath).get()).data();

    await assert.rejects(
      () => rescheduleAppointment(db, { ...request, trainerId: "alternate-trainer", idempotencyKey: "move-trainer", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy),
      /immutable-trainer-mismatch/
    );
    // A client caller cannot reschedule on behalf of another client at all.
    await assert.rejects(
      () => rescheduleAppointment(db, { ...request, clientId: "alternate-client", idempotencyKey: "move-client", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy),
      /participant-not-authorized/
    );
    // An authorized trainer caller still receives the immutable-identity rejection.
    await assert.rejects(
      () => rescheduleAppointment(db, { ...request, callerUid: "trainer", clientId: "alternate-client", idempotencyKey: "move-client-as-trainer", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy),
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

  it("preserves utcBucket identity on a same-interval reschedule", async () => {
    await seedBookingWorkspace("ws_bucket");
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const request = { workspaceId: "ws_bucket", trainerId: "trainer", clientId: "client", callerUid: "client", appointmentId: "appt", idempotencyKey: "book", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 0, bufferAfterMinutes: 0 };
    const booked = await bookAppointment(db, request, policy);
    const originalBuckets = new Map<string, number>();
    for (const id of booked.bucketIds) {
      originalBuckets.set(id, (await db.doc(`workspaces/ws_bucket/bookingSlots/${id}`).get()).get("utcBucket") as number);
    }
    const moved = await rescheduleAppointment(db, { ...request, idempotencyKey: "same-interval", expectedRevision: 1 }, policy);
    assert.deepEqual(moved.bucketIds, booked.bucketIds);
    for (const id of moved.bucketIds) {
      const lock = await db.doc(`workspaces/ws_bucket/bookingSlots/${id}`).get();
      assert.equal(lock.get("utcBucket"), originalBuckets.get(id));
      assert.equal(lock.get("utcBucket"), Number(id.slice("trainer_".length)));
      assert.equal(lock.get("appointmentRevision"), 2);
    }
  });

  it("writes retained and acquired reschedule locks with the initial-booking schema", async () => {
    await seedBookingWorkspace("ws_schema");
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const request = { workspaceId: "ws_schema", trainerId: "trainer", clientId: "client", callerUid: "client", appointmentId: "appt", idempotencyKey: "book", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 0, bufferAfterMinutes: 0 };
    const booked = await bookAppointment(db, request, policy);
    const bookingSchema = Object.keys((await db.doc(`workspaces/ws_schema/bookingSlots/${booked.bucketIds[0]}`).get()).data()!).sort();
    const moved = await rescheduleAppointment(db, { ...request, idempotencyKey: "shift", expectedRevision: 1, startsAtMillis: base + 30 * 60_000, endsAtMillis: base + 90 * 60_000 }, policy);
    const retained = booked.bucketIds.filter((id) => moved.bucketIds.includes(id));
    const acquired = moved.bucketIds.filter((id) => !booked.bucketIds.includes(id));
    const removed = booked.bucketIds.filter((id) => !moved.bucketIds.includes(id));
    assert.ok(retained.length > 0 && acquired.length > 0 && removed.length > 0);
    for (const id of removed) assert.equal((await db.doc(`workspaces/ws_schema/bookingSlots/${id}`).get()).exists, false);
    for (const id of [...retained, ...acquired]) {
      const lock = (await db.doc(`workspaces/ws_schema/bookingSlots/${id}`).get()).data()!;
      assert.deepEqual(Object.keys(lock).sort(), bookingSchema);
      assert.equal(lock.utcBucket, Number(id.slice("trainer_".length)));
      assert.equal(lock.workspaceId, "ws_schema");
      assert.equal(lock.trainerId, "trainer");
      assert.equal(lock.appointmentId, "appt");
      assert.equal(lock.appointmentRevision, 2);
    }
    const allLocks = await db.collection("workspaces/ws_schema/bookingSlots").get();
    assert.equal(allLocks.size, moved.bucketIds.length);
  });

  it("keeps lock documents stable across reschedule replay and rejection", async () => {
    await seedBookingWorkspace("ws_stable");
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const request = { workspaceId: "ws_stable", trainerId: "trainer", clientId: "client", callerUid: "client", appointmentId: "appt", idempotencyKey: "book", startsAtMillis: base, endsAtMillis: base + 60 * 60_000, bufferBeforeMinutes: 0, bufferAfterMinutes: 0 };
    await bookAppointment(db, request, policy);
    const move = { ...request, idempotencyKey: "move", expectedRevision: 1, startsAtMillis: base + 30 * 60_000, endsAtMillis: base + 90 * 60_000 };
    const first = await rescheduleAppointment(db, move, policy);
    const captureLocks = async () => {
      const locks = await db.collection("workspaces/ws_stable/bookingSlots").orderBy("__name__").get();
      return locks.docs.map((document) => [document.id, document.data()]);
    };
    const afterMove = await captureLocks();
    const replay = await rescheduleAppointment(db, move, policy);
    assert.equal(replay.replayed, true);
    assert.deepEqual(replay.bucketIds, first.bucketIds);
    assert.deepEqual(await captureLocks(), afterMove);

    const appointmentBefore = (await db.doc("workspaces/ws_stable/appointments/appt").get()).data();
    await assert.rejects(() => rescheduleAppointment(db, { ...request, idempotencyKey: "stale-move", expectedRevision: 1, startsAtMillis: base + 4 * 60 * 60_000, endsAtMillis: base + 5 * 60 * 60_000 }, policy), /stale-revision/);
    assert.deepEqual(await captureLocks(), afterMove);
    assert.deepEqual((await db.doc("workspaces/ws_stable/appointments/appt").get()).data(), appointmentBefore);
  });

  it("rejects unauthorized booking callers and participants without partial state", async () => {
    const assertNoBookingState = async (workspaceId: string) => {
      assert.equal((await db.collection(`workspaces/${workspaceId}/appointments`).get()).size, 0);
      assert.equal((await db.collection(`workspaces/${workspaceId}/bookingSlots`).get()).size, 0);
      assert.equal((await db.collection(`workspaces/${workspaceId}/bookingCommands`).get()).size, 0);
    };

    // Anonymous-style caller with no authoritative account or membership data at all.
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_absent", { callerUid: "anonymous-uid" }), policy), /caller-account-inactive/);
    await assertNoBookingState("ws_absent");

    // Disabled caller account.
    await seedBookingWorkspace("ws_disabled");
    await db.doc("users/client").update({ accountStatus: "disabled" });
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_disabled"), policy), /caller-account-inactive/);
    await assertNoBookingState("ws_disabled");

    // Suspended workspace.
    await seedBookingWorkspace("ws_suspended", { workspaceStatus: "suspended" });
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_suspended"), policy), /workspace-inactive/);
    await assertNoBookingState("ws_suspended");

    // Missing caller membership.
    await seedBookingWorkspace("ws_nomember");
    await db.doc("workspaces/ws_nomember/memberships/client").delete();
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_nomember"), policy), /caller-membership-inactive/);
    await assertNoBookingState("ws_nomember");

    // Revoked caller membership.
    await seedBookingWorkspace("ws_revoked", { clientMembershipStatus: "revoked" });
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_revoked"), policy), /caller-membership-inactive/);
    await assertNoBookingState("ws_revoked");

    // Cross-workspace identifiers: the caller's membership lives in another workspace.
    await seedBookingWorkspace("ws_home");
    await db.doc("workspaces/ws_foreign").set({ schemaVersion: 1, status: "active", membershipRevision: 1, activeMembershipCount: 0 });
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_foreign"), policy), /caller-membership-inactive/);
    await assertNoBookingState("ws_foreign");

    // Forged role: the caller membership says trainer, so trainerId must be the caller.
    await seedBookingWorkspace("ws_roles");
    await db.doc("workspaces/ws_roles/memberships/client").update({ role: "trainer" });
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_roles"), policy), /participant-not-authorized/);
    await assertNoBookingState("ws_roles");

    // Unrelated trainer named by an authorized client caller.
    await seedBookingWorkspace("ws_outsider_trainer");
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_outsider_trainer", { trainerId: "trainer-outsider" }), policy), /trainer-not-eligible/);
    await assertNoBookingState("ws_outsider_trainer");

    // Unrelated client named by an authorized trainer caller.
    await seedBookingWorkspace("ws_outsider_client");
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_outsider_client", { callerUid: "trainer", clientId: "client-outsider" }), policy), /client-not-eligible/);
    await assertNoBookingState("ws_outsider_client");

    // Forged participant fields against a fully seeded workspace.
    await seedBookingWorkspace("ws_forged");
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_forged", { callerUid: "trainer", trainerId: "other-trainer" }), policy), /participant-not-authorized/);
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_forged", { clientId: "other-client" }), policy), /participant-not-authorized/);
    await assertNoBookingState("ws_forged");

    // Positive controls: an authorized trainer books for their client, and an
    // authorized client self-books with the workspace trainer.
    const trainerBooking = await bookAppointment(db, bookingRequest("ws_forged", { callerUid: "trainer", appointmentId: "appt-trainer" }), policy);
    assert.equal(trainerBooking.replayed, false);
    const base = Date.UTC(2026, 8, 19, 10, 0);
    const clientBooking = await bookAppointment(db, bookingRequest("ws_forged", { appointmentId: "appt-client", idempotencyKey: "book-client", startsAtMillis: base + 6 * 60 * 60_000, endsAtMillis: base + 7 * 60 * 60_000 }), policy);
    assert.equal(clientBooking.replayed, false);
    assert.equal((await db.collection("workspaces/ws_forged/appointments").get()).size, 2);
  });

  it("enforces the same authorization boundary on reschedule and cancellation", async () => {
    await seedBookingWorkspace("ws_authz");
    const request = bookingRequest("ws_authz");
    await bookAppointment(db, request, policy);
    const appointmentBefore = (await db.doc("workspaces/ws_authz/appointments/appt").get()).data();
    const captureLocks = async () => (await db.collection("workspaces/ws_authz/bookingSlots").orderBy("__name__").get()).docs.map((document) => [document.id, document.data()]);
    const locksBefore = await captureLocks();

    // An active member who is not a participant of this appointment cannot reschedule or cancel it.
    await db.doc("users/outsider").set({ schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
    await db.doc("workspaces/ws_authz/memberships/outsider").set({ schemaVersion: 1, workspaceId: "ws_authz", userId: "outsider", role: "client", status: "active", revision: 1, catalogContributionActive: false });
    const base = Date.UTC(2026, 8, 19, 10, 0);
    await assert.rejects(() => rescheduleAppointment(db, { ...request, callerUid: "outsider", idempotencyKey: "move-outsider", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy), /participant-not-authorized/);
    await assert.rejects(() => cancelAppointment(db, { workspaceId: "ws_authz", appointmentId: "appt", callerUid: "outsider", idempotencyKey: "cancel-outsider", expectedRevision: 1 }), /participant-not-authorized/);

    // A participant whose membership was revoked loses reschedule and cancellation authority.
    await db.doc("workspaces/ws_authz/memberships/client").update({ status: "revoked" });
    await assert.rejects(() => rescheduleAppointment(db, { ...request, idempotencyKey: "move-revoked", expectedRevision: 1, startsAtMillis: base + 2 * 60 * 60_000, endsAtMillis: base + 3 * 60 * 60_000 }, policy), /caller-membership-inactive/);
    await assert.rejects(() => cancelAppointment(db, { workspaceId: "ws_authz", appointmentId: "appt", callerUid: "client", idempotencyKey: "cancel-revoked", expectedRevision: 1 }), /caller-membership-inactive/);

    // Every rejection left the appointment, locks and receipts untouched.
    assert.deepEqual((await db.doc("workspaces/ws_authz/appointments/appt").get()).data(), appointmentBefore);
    assert.deepEqual(await captureLocks(), locksBefore);
    assert.equal((await db.collection("workspaces/ws_authz/bookingCommands").get()).size, 1);

    // Restoring the membership restores the participant's cancellation authority.
    await db.doc("workspaces/ws_authz/memberships/client").update({ status: "active" });
    const cancelled = await cancelAppointment(db, { workspaceId: "ws_authz", appointmentId: "appt", callerUid: "client", idempotencyKey: "cancel-ok", expectedRevision: 1 });
    assert.equal(cancelled.status, "cancelled");
    assert.equal((await db.collection("workspaces/ws_authz/bookingSlots").get()).size, 0);
  });

  it("binds booking receipts to the authorized caller", async () => {
    await seedBookingWorkspace("ws_receipt");
    const request = bookingRequest("ws_receipt", { idempotencyKey: "shared-key" });
    const first = await bookAppointment(db, request, policy);
    const replay = await bookAppointment(db, request, policy);
    assert.equal(replay.replayed, true);
    assert.deepEqual(replay.bucketIds, first.bucketIds);
    const receipt = await db.doc(`workspaces/ws_receipt/bookingCommands/${commandId("client", "shared-key")}`).get();
    assert.equal(receipt.get("callerUid"), "client");

    // A different authorized caller reusing the same idempotency key gets a distinct
    // command identity: it cannot replay or observe the original receipt and its own
    // attempt fails on the existing locks without creating a second receipt.
    await assert.rejects(() => bookAppointment(db, bookingRequest("ws_receipt", { callerUid: "trainer", idempotencyKey: "shared-key" }), policy), /slot-conflict/);
    assert.equal((await db.collection("workspaces/ws_receipt/bookingCommands").get()).size, 1);
    assert.equal((await db.doc(`workspaces/ws_receipt/bookingCommands/${commandId("client", "shared-key")}`).get()).get("callerUid"), "client");
    assert.equal((await db.collection("workspaces/ws_receipt/appointments").get()).size, 1);
  });

  it("fails closed when the caller membership is revoked concurrently with booking", async () => {
    await seedBookingWorkspace("ws_race");
    const request = bookingRequest("ws_race");
    // Deterministic barrier: the booking transaction and the authoritative membership
    // revocation race without sleeps. Firestore serializes them; whichever order wins,
    // the final state must be consistent and a revoked member can never book afterwards.
    const [bookOutcome] = await Promise.all([
      bookAppointment(db, request, policy).then(
        (value) => ({ status: "fulfilled" as const, value }),
        (reason: Error) => ({ status: "rejected" as const, reason })
      ),
      db.doc("workspaces/ws_race/memberships/client").update({ status: "revoked" })
    ]);
    if (bookOutcome.status === "rejected") {
      assert.match(bookOutcome.reason.message, /caller-membership-inactive/);
      assert.equal((await db.doc("workspaces/ws_race/appointments/appt").get()).exists, false);
      assert.equal((await db.collection("workspaces/ws_race/bookingSlots").get()).size, 0);
      assert.equal((await db.collection("workspaces/ws_race/bookingCommands").get()).size, 0);
    } else {
      // The booking committed before the revocation became visible; the membership is
      // now revoked and any subsequent booking by the same caller must fail closed.
      assert.equal((await db.doc("workspaces/ws_race/memberships/client").get()).get("status"), "revoked");
      await assert.rejects(() => bookAppointment(db, bookingRequest("ws_race", { appointmentId: "appt-2", idempotencyKey: "book-2" }), policy), /caller-membership-inactive/);
      assert.equal((await db.doc("workspaces/ws_race/appointments/appt-2").get()).exists, false);
    }
  });
});
