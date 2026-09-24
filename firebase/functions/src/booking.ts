import { FieldValue, Firestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { commandId, stableHash } from "./hashing.js";

export type BookingParticipantRole = "trainer" | "client";

export interface BookingPolicy {
  readonly slotQuantumMinutes: 1 | 5 | 10 | 15;
  readonly maxDurationMinutes: number;
  readonly maxBufferBeforeMinutes: number;
  readonly maxBufferAfterMinutes: number;
  readonly maxBuckets: number;
  readonly maxWrites: number;
  readonly scheduleRevision: number;
}

export interface BookingRange {
  readonly startsAtMillis: number;
  readonly endsAtMillis: number;
  readonly bufferBeforeMinutes: number;
  readonly bufferAfterMinutes: number;
}

export interface BookingRequest extends BookingRange {
  readonly workspaceId: string;
  readonly trainerId: string;
  readonly clientId: string;
  readonly appointmentId: string;
  readonly callerUid: string;
  readonly idempotencyKey: string;
}

export interface BookingResult {
  readonly appointmentId: string;
  readonly revision: number;
  readonly status: "confirmed" | "cancelled";
  readonly bucketIds: readonly string[];
  readonly replayed: boolean;
}

const SUPPORTED_QUANTA = new Set([1, 5, 10, 15]);

export function coveredUtcBucketMinutes(range: BookingRange, quantumMinutes: number): number[] {
  if (!SUPPORTED_QUANTA.has(quantumMinutes)) throw new Error("unsupported-quantum");
  if (range.endsAtMillis <= range.startsAtMillis) throw new Error("invalid-range");
  const quantumMillis = quantumMinutes * 60_000;
  const bufferedStart = range.startsAtMillis - range.bufferBeforeMinutes * 60_000;
  const bufferedEnd = range.endsAtMillis + range.bufferAfterMinutes * 60_000;
  const first = Math.floor(bufferedStart / quantumMillis);
  const exclusiveLast = Math.ceil(bufferedEnd / quantumMillis);
  return Array.from(
    { length: exclusiveLast - first },
    (_, offset) => (first + offset) * quantumMinutes
  );
}

function validateRange(range: BookingRange, policy: BookingPolicy): number[] {
  const durationMinutes = (range.endsAtMillis - range.startsAtMillis) / 60_000;
  if (durationMinutes <= 0 || durationMinutes > policy.maxDurationMinutes) throw new Error("unsupported-duration");
  if (range.bufferBeforeMinutes < 0 || range.bufferBeforeMinutes > policy.maxBufferBeforeMinutes) throw new Error("unsupported-buffer");
  if (range.bufferAfterMinutes < 0 || range.bufferAfterMinutes > policy.maxBufferAfterMinutes) throw new Error("unsupported-buffer");
  const buckets = coveredUtcBucketMinutes(range, policy.slotQuantumMinutes);
  if (buckets.length > policy.maxBuckets) throw new Error("bucket-budget-exceeded");
  return buckets;
}

function bucketIds(trainerId: string, minutes: readonly number[]): string[] {
  return minutes.map((minute) => `${trainerId}_${minute}`);
}

function lockDocument(workspaceId: string, trainerId: string, appointmentId: string, utcBucket: number, appointmentRevision: number) {
  return { schemaVersion: 1, workspaceId, trainerId, appointmentId, utcBucket, appointmentRevision };
}

function requireActiveAccount(snapshot: FirebaseFirestore.DocumentSnapshot, failure: string): void {
  if (!snapshot.exists || snapshot.get("schemaVersion") !== 1 || snapshot.get("accountStatus") !== "active") throw new Error(failure);
}

// Trusted-path membership authorization validates the stored tenant identity as well as the account
// identity. Memberships are addressed through `workspaces/{workspaceId}/memberships/{uid}`, so a
// path-correct document whose stored `workspaceId` names a different workspace (imported, legacy, or
// damaged data) would otherwise authorize booking, rescheduling, and cancellation inside a workspace
// the membership does not belong to; the catalog lifecycle validator rejects that same mismatch. Both
// immutable identity fields are checked before any appointment, lock, or receipt is written or
// released, and an identity mismatch fails closed with its own failure name instead of being treated
// as a merely inactive relationship.
function requireActiveMembership(
  snapshot: FirebaseFirestore.DocumentSnapshot,
  expected: { readonly uid: string; readonly workspaceId: string },
  failure: string,
  identityFailure: string
): void {
  if (!snapshot.exists || snapshot.get("schemaVersion") !== 1 || snapshot.get("status") !== "active") throw new Error(failure);
  if (snapshot.get("userId") !== expected.uid || snapshot.get("workspaceId") !== expected.workspaceId) throw new Error(identityFailure);
}

function requireActiveWorkspace(snapshot: FirebaseFirestore.DocumentSnapshot): void {
  if (!snapshot.exists || snapshot.get("schemaVersion") !== 1 || snapshot.get("status") !== "active") throw new Error("workspace-inactive");
}

function requireCallerRole(snapshot: FirebaseFirestore.DocumentSnapshot): BookingParticipantRole {
  const role = snapshot.get("role");
  if (role !== "trainer" && role !== "client") throw new Error("caller-role-unsupported");
  return role;
}

// Trusted-path authorization: Admin SDK bypasses Rules, so the authoritative account,
// workspace, membership and participant documents are read inside the booking transaction
// and never taken from request claims. The narrow documented participant policy is:
// trainer callers book only their own slots for an active client member; client callers
// book only themselves with an active trainer member of the same workspace. Broader
// booking rights remain an open product decision (open-questions.md, Milestone 10).
function authorizeBookingCaller(
  request: Pick<BookingRequest, "callerUid" | "workspaceId" | "trainerId" | "clientId">,
  callerAccount: FirebaseFirestore.DocumentSnapshot,
  workspace: FirebaseFirestore.DocumentSnapshot,
  callerMembership: FirebaseFirestore.DocumentSnapshot
): BookingParticipantRole {
  requireActiveAccount(callerAccount, "caller-account-inactive");
  requireActiveWorkspace(workspace);
  // The membership path is built from the requested workspace, so the stored tenant identity must
  // agree with it before any lock, appointment, or receipt is touched.
  requireActiveMembership(
    callerMembership,
    { uid: request.callerUid, workspaceId: request.workspaceId },
    "caller-membership-inactive",
    "caller-membership-identity-mismatch"
  );
  const callerRole = requireCallerRole(callerMembership);
  if (callerRole === "trainer" ? request.trainerId !== request.callerUid : request.clientId !== request.callerUid) {
    throw new Error("participant-not-authorized");
  }
  return callerRole;
}

function authorizeBookingCounterparts(
  request: Pick<BookingRequest, "workspaceId" | "trainerId" | "clientId">,
  trainerAccount: FirebaseFirestore.DocumentSnapshot,
  trainerMembership: FirebaseFirestore.DocumentSnapshot,
  clientAccount: FirebaseFirestore.DocumentSnapshot,
  clientMembership: FirebaseFirestore.DocumentSnapshot
): void {
  requireActiveAccount(trainerAccount, "trainer-not-eligible");
  requireActiveMembership(
    trainerMembership,
    { uid: request.trainerId, workspaceId: request.workspaceId },
    "trainer-not-eligible",
    "trainer-membership-identity-mismatch"
  );
  if (trainerMembership.get("role") !== "trainer") throw new Error("trainer-not-eligible");
  requireActiveAccount(clientAccount, "client-not-eligible");
  requireActiveMembership(
    clientMembership,
    { uid: request.clientId, workspaceId: request.workspaceId },
    "client-not-eligible",
    "client-membership-identity-mismatch"
  );
  if (clientMembership.get("role") !== "client") throw new Error("client-not-eligible");
}

function bookingAuthorizationRefs(db: Firestore, request: Pick<BookingRequest, "callerUid" | "workspaceId" | "trainerId" | "clientId">) {
  return [
    db.doc(`users/${request.callerUid}`),
    db.doc(`workspaces/${request.workspaceId}`),
    db.doc(`workspaces/${request.workspaceId}/memberships/${request.callerUid}`),
    db.doc(`users/${request.trainerId}`),
    db.doc(`workspaces/${request.workspaceId}/memberships/${request.trainerId}`),
    db.doc(`users/${request.clientId}`),
    db.doc(`workspaces/${request.workspaceId}/memberships/${request.clientId}`)
  ] as const;
}

function callerAuthorizationRefs(db: Firestore, callerUid: string, workspaceId: string) {
  return [
    db.doc(`users/${callerUid}`),
    db.doc(`workspaces/${workspaceId}`),
    db.doc(`workspaces/${workspaceId}/memberships/${callerUid}`)
  ] as const;
}

function receiptResult(data: FirebaseFirestore.DocumentData, requestHash: string): BookingResult {
  if (data.requestHash !== requestHash) throw new Error("idempotency-key-reused");
  return {
    appointmentId: data.appointmentId as string,
    revision: data.revision as number,
    status: data.status as "confirmed" | "cancelled",
    bucketIds: data.bucketIds as string[],
    replayed: true
  };
}

export async function bookAppointment(db: Firestore, request: BookingRequest, policy: BookingPolicy): Promise<BookingResult> {
  const buckets = validateRange(request, policy);
  const ids = bucketIds(request.trainerId, buckets);
  if (ids.length + 2 > policy.maxWrites) throw new Error("write-budget-exceeded");
  const receiptRef = db.doc(`workspaces/${request.workspaceId}/bookingCommands/${commandId(request.callerUid, request.idempotencyKey)}`);
  const appointmentRef = db.doc(`workspaces/${request.workspaceId}/appointments/${request.appointmentId}`);
  const requestHash = stableHash({ kind: "book", ...request });

  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(...bookingAuthorizationRefs(db, request), receiptRef);
    const [callerAccount, workspace, callerMembership, trainerAccount, trainerMembership, clientAccount, clientMembership, receipt] = documents;
    authorizeBookingCaller(request, callerAccount!, workspace!, callerMembership!);
    authorizeBookingCounterparts(request, trainerAccount!, trainerMembership!, clientAccount!, clientMembership!);
    if (receipt!.exists) return receiptResult(receipt!.data()!, requestHash);
    const lockRefs = ids.map((id) => db.doc(`workspaces/${request.workspaceId}/bookingSlots/${id}`));
    const locks = await transaction.getAll(...lockRefs);
    if (locks.some((lock) => lock.exists)) throw new Error("slot-conflict");

    lockRefs.forEach((ref, index) => transaction.create(ref, lockDocument(request.workspaceId, request.trainerId, request.appointmentId, buckets[index]!, 1)));
    transaction.create(appointmentRef, {
      schemaVersion: 1,
      workspaceId: request.workspaceId,
      trainerId: request.trainerId,
      clientId: request.clientId,
      startsAt: Timestamp.fromMillis(request.startsAtMillis),
      endsAt: Timestamp.fromMillis(request.endsAtMillis),
      bufferBeforeMinutes: request.bufferBeforeMinutes,
      bufferAfterMinutes: request.bufferAfterMinutes,
      status: "confirmed",
      revision: 1,
      scheduleRevision: policy.scheduleRevision,
      bucketIds: ids
    });
    transaction.create(receiptRef, {
      schemaVersion: 1,
      commandKind: "book",
      callerUid: request.callerUid,
      requestHash,
      appointmentId: request.appointmentId,
      revision: 1,
      status: "confirmed",
      bucketIds: ids,
      committedAt: FieldValue.serverTimestamp()
    });
    return { appointmentId: request.appointmentId, revision: 1, status: "confirmed", bucketIds: ids, replayed: false };
  });
}

export interface RescheduleRequest extends BookingRequest {
  readonly expectedRevision: number;
}

export async function rescheduleAppointment(db: Firestore, request: RescheduleRequest, policy: BookingPolicy): Promise<BookingResult> {
  const appointmentRef = db.doc(`workspaces/${request.workspaceId}/appointments/${request.appointmentId}`);
  const receiptRef = db.doc(`workspaces/${request.workspaceId}/bookingCommands/${commandId(request.callerUid, request.idempotencyKey)}`);
  const requestHash = stableHash({ kind: "reschedule", ...request });

  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(...bookingAuthorizationRefs(db, request), receiptRef, appointmentRef);
    const [callerAccount, workspace, callerMembership, trainerAccount, trainerMembership, clientAccount, clientMembership, receipt, appointment] = documents;
    authorizeBookingCaller(request, callerAccount!, workspace!, callerMembership!);
    if (receipt!.exists) return receiptResult(receipt!.data()!, requestHash);
    if (!appointment!.exists || appointment!.get("status") !== "confirmed") throw new Error("appointment-not-live");
    if (appointment!.get("revision") !== request.expectedRevision) throw new Error("stale-revision");
    if (appointment!.get("trainerId") !== request.trainerId) throw new Error("immutable-trainer-mismatch");
    if (appointment!.get("clientId") !== request.clientId) throw new Error("immutable-client-mismatch");
    authorizeBookingCounterparts(request, trainerAccount!, trainerMembership!, clientAccount!, clientMembership!);
    const newBucketMinutes = validateRange(request, policy);
    const newBuckets = bucketIds(request.trainerId, newBucketMinutes);
    const utcBucketByLockId = new Map(newBuckets.map((id, index) => [id, newBucketMinutes[index]!]));
    const oldBuckets = appointment!.get("bucketIds") as string[];
    const allIds = [...new Set([...oldBuckets, ...newBuckets])];
    if (allIds.length + 2 > policy.maxWrites) throw new Error("write-budget-exceeded");
    const lockRefs = allIds.map((id) => db.doc(`workspaces/${request.workspaceId}/bookingSlots/${id}`));
    const locks = await transaction.getAll(...lockRefs);
    locks.forEach((lock, index) => {
      const id = allIds[index]!;
      if (oldBuckets.includes(id) && (!lock.exists || lock.get("appointmentId") !== request.appointmentId)) throw new Error("old-lock-inconsistent");
      if (oldBuckets.includes(id) && newBuckets.includes(id) && lock.get("utcBucket") !== utcBucketByLockId.get(id)) throw new Error("old-lock-inconsistent");
      if (newBuckets.includes(id) && lock.exists && lock.get("appointmentId") !== request.appointmentId) throw new Error("slot-conflict");
    });
    const revision = request.expectedRevision + 1;
    for (const id of allIds) {
      const ref = db.doc(`workspaces/${request.workspaceId}/bookingSlots/${id}`);
      if (newBuckets.includes(id)) {
        transaction.set(ref, lockDocument(request.workspaceId, request.trainerId, request.appointmentId, utcBucketByLockId.get(id)!, revision));
      } else {
        transaction.delete(ref);
      }
    }
    transaction.update(appointmentRef, {
      startsAt: Timestamp.fromMillis(request.startsAtMillis),
      endsAt: Timestamp.fromMillis(request.endsAtMillis),
      bufferBeforeMinutes: request.bufferBeforeMinutes,
      bufferAfterMinutes: request.bufferAfterMinutes,
      bucketIds: newBuckets,
      revision
    });
    transaction.create(receiptRef, { schemaVersion: 1, commandKind: "reschedule", callerUid: request.callerUid, requestHash, appointmentId: request.appointmentId, revision, status: "confirmed", bucketIds: newBuckets, committedAt: FieldValue.serverTimestamp() });
    return { appointmentId: request.appointmentId, revision, status: "confirmed", bucketIds: newBuckets, replayed: false };
  });
}

export interface CancelRequest {
  readonly workspaceId: string;
  readonly appointmentId: string;
  readonly callerUid: string;
  readonly idempotencyKey: string;
  readonly expectedRevision: number;
}

export async function cancelAppointment(db: Firestore, request: CancelRequest, maxWrites = 200): Promise<BookingResult> {
  const appointmentRef = db.doc(`workspaces/${request.workspaceId}/appointments/${request.appointmentId}`);
  const receiptRef = db.doc(`workspaces/${request.workspaceId}/bookingCommands/${commandId(request.callerUid, request.idempotencyKey)}`);
  const requestHash = stableHash({ kind: "cancel", ...request });
  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(...callerAuthorizationRefs(db, request.callerUid, request.workspaceId), receiptRef, appointmentRef);
    const [callerAccount, workspace, callerMembership, receipt, appointment] = documents;
    requireActiveAccount(callerAccount!, "caller-account-inactive");
    requireActiveWorkspace(workspace!);
    // Cancellation authorizes only the caller, who must be the stored trainer or client participant,
    // so the same tenant/account identity checks apply to that membership.
    requireActiveMembership(
      callerMembership!,
      { uid: request.callerUid, workspaceId: request.workspaceId },
      "caller-membership-inactive",
      "caller-membership-identity-mismatch"
    );
    const callerRole = requireCallerRole(callerMembership!);
    if (!appointment!.exists) throw new Error("appointment-not-live");
    if (callerRole === "trainer" ? appointment!.get("trainerId") !== request.callerUid : appointment!.get("clientId") !== request.callerUid) {
      throw new Error("participant-not-authorized");
    }
    if (receipt!.exists) return receiptResult(receipt!.data()!, requestHash);
    if (appointment!.get("status") !== "confirmed") throw new Error("appointment-not-live");
    if (appointment!.get("revision") !== request.expectedRevision) throw new Error("stale-revision");
    const ids = appointment!.get("bucketIds") as string[];
    if (ids.length + 2 > maxWrites) throw new Error("write-budget-exceeded");
    const refs = ids.map((id) => db.doc(`workspaces/${request.workspaceId}/bookingSlots/${id}`));
    const locks = await transaction.getAll(...refs);
    if (locks.some((lock) => !lock.exists || lock.get("appointmentId") !== request.appointmentId)) throw new Error("old-lock-inconsistent");
    refs.forEach((ref) => transaction.delete(ref));
    const revision = request.expectedRevision + 1;
    transaction.update(appointmentRef, { status: "cancelled", revision, bucketIds: [] });
    transaction.create(receiptRef, { schemaVersion: 1, commandKind: "cancel", callerUid: request.callerUid, requestHash, appointmentId: request.appointmentId, revision, status: "cancelled", bucketIds: [], committedAt: FieldValue.serverTimestamp() });
    return { appointmentId: request.appointmentId, revision, status: "cancelled", bucketIds: [], replayed: false };
  });
}

// Only a status the current schema explicitly recognizes as terminal may release leftover capacity
// locks. Milestone 3 implements the live `confirmed` state and the single terminal transition
// `cancelled`; a missing, malformed, unknown, or not-yet-implemented status is treated as potentially
// live and fails closed, because deleting the locks of an appointment that may still be live would
// let a conflicting booking occupy the same trainer bucket while that appointment still exists.
// Trusted repair never infers a release from elapsed wall time or from the absence of a recognized
// status, and it leaves receipts untouched.
const LIVE_APPOINTMENT_STATUSES: readonly string[] = ["confirmed"];
const TERMINAL_APPOINTMENT_STATUSES: readonly string[] = ["cancelled"];

export async function cleanupTerminalAppointmentLocks(db: Firestore, workspaceId: string, appointmentId: string): Promise<number> {
  const appointmentRef = db.doc(`workspaces/${workspaceId}/appointments/${appointmentId}`);
  return db.runTransaction(async (transaction) => {
    const appointment = await transaction.get(appointmentRef);
    if (!appointment.exists) throw new Error("appointment-missing");
    if (appointment.get("schemaVersion") !== 1) throw new Error("appointment-schema-unsupported");
    // The appointment path is built from the requested workspace, so its stored tenant identity must
    // agree with it before its locks count as owned by this workspace.
    if (appointment.get("workspaceId") !== workspaceId) throw new Error("appointment-workspace-mismatch");
    const status = appointment.get("status");
    if (LIVE_APPOINTMENT_STATUSES.includes(status)) throw new Error("live-appointment-locks-protected");
    if (typeof status !== "string" || !TERMINAL_APPOINTMENT_STATUSES.includes(status)) throw new Error("appointment-status-unrecognized");
    const query = db.collection(`workspaces/${workspaceId}/bookingSlots`).where("appointmentId", "==", appointmentId);
    const locks = await transaction.get(query);
    locks.docs.forEach((lock) => transaction.delete(lock.ref));
    return locks.size;
  });
}
