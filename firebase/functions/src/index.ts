import { onCall, HttpsError } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import { bookAppointment } from "./booking.js";
import { emulatorFirestore, requireEmulatorEnvironment } from "./environment.js";

setGlobalOptions({ region: "europe-west1", maxInstances: 1 });

const AUTHORIZATION_FAILURES = new Set([
  "caller-account-inactive",
  "workspace-inactive",
  "caller-membership-inactive",
  "caller-role-unsupported",
  "participant-not-authorized",
  "trainer-not-eligible",
  "client-not-eligible"
]);

function safeIdentifier(value: unknown, field: string): string {
  if (typeof value !== "string" || value.length === 0 || value.length > 128 || value.includes("/") || value.includes(".")) {
    throw new HttpsError("invalid-argument", `${field} must be a non-empty path-safe string`);
  }
  return value;
}

function safeEpochMillis(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0 || value > 8.64e15) {
    throw new HttpsError("invalid-argument", `${field} must be an integer epoch-millisecond value`);
  }
  return value;
}

function safeBufferMinutes(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0 || value > 240) {
    throw new HttpsError("invalid-argument", `${field} must be a non-negative integer minute count`);
  }
  return value;
}

export const bookAppointmentSpike = onCall(async (request) => {
  requireEmulatorEnvironment();
  if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required");
  const data = (request.data ?? {}) as Record<string, unknown>;
  const booking = {
    workspaceId: safeIdentifier(data.workspaceId, "workspaceId"),
    trainerId: safeIdentifier(data.trainerId, "trainerId"),
    clientId: safeIdentifier(data.clientId, "clientId"),
    appointmentId: safeIdentifier(data.appointmentId, "appointmentId"),
    idempotencyKey: safeIdentifier(data.idempotencyKey, "idempotencyKey"),
    startsAtMillis: safeEpochMillis(data.startsAtMillis, "startsAtMillis"),
    endsAtMillis: safeEpochMillis(data.endsAtMillis, "endsAtMillis"),
    bufferBeforeMinutes: safeBufferMinutes(data.bufferBeforeMinutes, "bufferBeforeMinutes"),
    bufferAfterMinutes: safeBufferMinutes(data.bufferAfterMinutes, "bufferAfterMinutes"),
    callerUid: request.auth.uid
  };
  try {
    return await bookAppointment(emulatorFirestore(), booking, {
      slotQuantumMinutes: 5,
      maxDurationMinutes: 180,
      maxBufferBeforeMinutes: 30,
      maxBufferAfterMinutes: 30,
      maxBuckets: 90,
      maxWrites: 200,
      scheduleRevision: 1
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "booking-failed";
    if (AUTHORIZATION_FAILURES.has(message)) throw new HttpsError("permission-denied", message);
    throw new HttpsError("failed-precondition", message);
  }
});
