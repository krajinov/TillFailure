import { onCall, HttpsError } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import { bookAppointment } from "./booking.js";
import { emulatorFirestore, requireEmulatorEnvironment } from "./environment.js";
import { participantUid, safeBufferMinutes, safeEpochMillis, strictPathIdentifier } from "./input-validation.js";

setGlobalOptions({ region: "europe-west1", maxInstances: 1 });

const AUTHORIZATION_FAILURES = new Set([
  "caller-account-inactive",
  "workspace-inactive",
  "caller-membership-inactive",
  "caller-membership-identity-mismatch",
  "caller-role-unsupported",
  "participant-not-authorized",
  "trainer-not-eligible",
  "trainer-membership-identity-mismatch",
  "client-not-eligible",
  "client-membership-identity-mismatch"
]);

export const bookAppointmentSpike = onCall(async (request) => {
  requireEmulatorEnvironment();
  if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required");
  const data = (request.data ?? {}) as Record<string, unknown>;
  const booking = {
    workspaceId: strictPathIdentifier(data.workspaceId, "workspaceId"),
    // Participant IDs accept the Firebase UID domain that the raw users/{uid} path can represent,
    // including embedded dots and email-shaped identifiers; non-UID path identifiers stay strict.
    trainerId: participantUid(data.trainerId, "trainerId"),
    clientId: participantUid(data.clientId, "clientId"),
    appointmentId: strictPathIdentifier(data.appointmentId, "appointmentId"),
    idempotencyKey: strictPathIdentifier(data.idempotencyKey, "idempotencyKey"),
    startsAtMillis: safeEpochMillis(data.startsAtMillis, "startsAtMillis"),
    endsAtMillis: safeEpochMillis(data.endsAtMillis, "endsAtMillis"),
    bufferBeforeMinutes: safeBufferMinutes(data.bufferBeforeMinutes, "bufferBeforeMinutes"),
    bufferAfterMinutes: safeBufferMinutes(data.bufferAfterMinutes, "bufferAfterMinutes"),
    callerUid: participantUid(request.auth.uid, "callerUid")
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
