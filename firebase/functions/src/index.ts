import { resolveOwnCommand } from "./command-resolution.js";
import { getAuth } from "firebase-admin/auth";
import { basicProfileOperation } from "./basic-profile.js";
import { clientOperation, clientFailure } from "./clients.js";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import { bookAppointment } from "./booking.js";
import { emulatorFirestore, requireEmulatorEnvironment } from "./environment.js";
import { participantUid, safeBufferMinutes, safeEpochMillis, strictPathIdentifier } from "./input-validation.js";
import { invitationOperation, invitationFailure } from "./invitations.js";
import { requireProvisioningEmulators } from "./provisioning.js";

import { completeOnboarding, onboardingFailure } from "./onboarding.js";

setGlobalOptions({ region: "europe-west1", maxInstances: 1 });

export const onboardingEmulator = onCall(async request => {
  try {
    requireProvisioningEmulators();
    return await completeOnboarding(emulatorFirestore(), request.auth?.uid, request.data);
  } catch (error) {
    const failure = onboardingFailure(error);
    throw new HttpsError(failure.code, failure.reason);
  }
});

// The primitive guards BOTH named loopback emulators before Auth/Firestore acquisition.
// Deploying this export does not enable production invitation operations.
export const invitationEmulator = onCall(async request => {
  try {
    requireProvisioningEmulators();
    return await invitationOperation(emulatorFirestore(), request.auth?.uid, request.data);
  } catch (error) {
    const failure = invitationFailure(error);
    throw new HttpsError(failure.code, failure.reason);
  }
});

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

export const clientsEmulator = onCall(async request => {
  try {
    requireProvisioningEmulators();
    return await clientOperation(emulatorFirestore(), request.auth?.uid, request.data);
  } catch (error) {
    const failure = clientFailure(error);
    throw new HttpsError(failure.code, failure.reason);
  }
});

export const basicProfileEmulator = onCall(async request => {
  try {
    requireProvisioningEmulators();
    return await basicProfileOperation(emulatorFirestore(), request.auth?.uid, request.data);
  } catch (error) {
    const failure = onboardingFailure(error);
    throw new HttpsError(failure.code, failure.reason);
  }
});

export const commandResolutionEmulator = onCall(async request => {
  try {
    requireProvisioningEmulators();
    const db = emulatorFirestore();
    const header = request.rawRequest.get("authorization");
    if (!header?.startsWith("Bearer ") || !request.auth) throw new Error("identity-required");
    const token = await getAuth().verifyIdToken(header.slice(7), true).catch(() => { throw new Error("identity-required"); });
    if (token.uid !== request.auth.uid || token.firebase.sign_in_provider === "anonymous") throw new Error("identity-required");
    return await resolveOwnCommand(db, request.auth.uid, request.data);
  } catch (error) {
    const reason = error instanceof Error ? error.message : "";
    throw new HttpsError(reason === "rate-limited" ? "resource-exhausted" : reason === "identity-required" ? "permission-denied" : "failed-precondition",
      reason === "rate-limited" || reason === "identity-required" ? reason : "outcome-unavailable");
  }
});
