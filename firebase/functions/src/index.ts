import { onCall, HttpsError } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import { bookAppointment } from "./booking.js";
import { emulatorFirestore, requireEmulatorEnvironment } from "./environment.js";

setGlobalOptions({ region: "europe-west1", maxInstances: 1 });

export const bookAppointmentSpike = onCall(async (request) => {
  requireEmulatorEnvironment();
  if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required");
  try {
    return await bookAppointment(emulatorFirestore(), {
      ...(request.data as Omit<Parameters<typeof bookAppointment>[1], "callerUid">),
      callerUid: request.auth.uid
    }, {
      slotQuantumMinutes: 5,
      maxDurationMinutes: 180,
      maxBufferBeforeMinutes: 30,
      maxBufferAfterMinutes: 30,
      maxBuckets: 90,
      maxWrites: 200,
      scheduleRevision: 1
    });
  } catch (error) {
    throw new HttpsError("failed-precondition", error instanceof Error ? error.message : "booking-failed");
  }
});
