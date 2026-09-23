import assert from "node:assert/strict";
import { before, beforeEach, describe, it } from "node:test";
import { FirebaseApp, initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, signInAnonymously, signOut } from "firebase/auth";
import { connectFunctionsEmulator, FunctionsError, getFunctions, HttpsCallable, httpsCallable } from "firebase/functions";
import { emulatorFirestore, SPIKE_PROJECT_ID } from "../src/environment.js";
import { commandId } from "../src/hashing.js";

const db = emulatorFirestore();
let app: FirebaseApp;
let call: HttpsCallable;

before(() => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error("Callable tests require local emulators");
  app = initializeApp({ projectId: SPIKE_PROJECT_ID, apiKey: "fake-emulator-api-key" }, "callable-test");
  const auth = getAuth(app);
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  const functions = getFunctions(app, "europe-west1");
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  call = httpsCallable(functions, "bookAppointmentSpike");
});

async function freshAnonymousUid(): Promise<string> {
  const auth = getAuth(app);
  await signOut(auth);
  const credential = await signInAnonymously(auth);
  return credential.user.uid;
}

// Authoritative emulator fixture data created through isolated Admin-SDK test setup,
// exactly as a trusted bootstrap would; the callable authorization itself is never bypassed.
async function seedMember(uid: string, role: "trainer" | "client"): Promise<void> {
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}`), { schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1 });
  batch.set(db.doc("workspaces/ws_callable"), { schemaVersion: 1, status: "active", membershipRevision: 1, activeRosterCount: 2, catalogContributionCount: 2 });
  batch.set(db.doc(`workspaces/ws_callable/memberships/${uid}`), { schemaVersion: 1, workspaceId: "ws_callable", userId: uid, role, status: "active", revision: 1, catalogContributionActive: true });
  await batch.commit();
}

function bookingPayload(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const base = Date.UTC(2026, 8, 19, 10, 0);
  return {
    workspaceId: "ws_callable",
    trainerId: "trainer-fixture",
    clientId: "client-fixture",
    appointmentId: "appt-callable",
    idempotencyKey: "callable-key",
    startsAtMillis: base,
    endsAtMillis: base + 60 * 60_000,
    bufferBeforeMinutes: 0,
    bufferAfterMinutes: 0,
    ...overrides
  };
}

describe("bookAppointmentSpike callable authorization", () => {
  beforeEach(async () => {
    await db.recursiveDelete(db.collection("users"));
    await db.recursiveDelete(db.collection("workspaces"));
  });

  it("rejects unauthenticated callers and anonymous callers without authoritative data", async () => {
    await signOut(getAuth(app));
    await assert.rejects(() => call(bookingPayload()), (error: FunctionsError) => {
      assert.equal(error.code, "functions/unauthenticated");
      return true;
    });

    // An anonymous emulator identity gains no booking authority merely by being authenticated.
    await freshAnonymousUid();
    await assert.rejects(() => call(bookingPayload()), (error: FunctionsError) => {
      assert.equal(error.code, "functions/permission-denied");
      assert.match(error.message, /caller-account-inactive/);
      return true;
    });

    assert.equal((await db.collection("workspaces/ws_callable/appointments").get()).size, 0);
    assert.equal((await db.collection("workspaces/ws_callable/bookingSlots").get()).size, 0);
    assert.equal((await db.collection("workspaces/ws_callable/bookingCommands").get()).size, 0);
  });

  it("authorizes a seeded trainer caller end-to-end and replays idempotently", async () => {
    const trainerUid = await freshAnonymousUid();
    await seedMember(trainerUid, "trainer");
    await seedMember("client-fixture", "client");
    const booked = await call(bookingPayload({ trainerId: trainerUid, clientId: "client-fixture" }));
    const first = booked.data as { appointmentId: string; replayed: boolean; bucketIds: string[] };
    assert.equal(first.replayed, false);
    assert.equal(first.appointmentId, "appt-callable");
    assert.equal((await db.doc("workspaces/ws_callable/appointments/appt-callable").get()).get("trainerId"), trainerUid);
    assert.ok(first.bucketIds.length > 0);

    // The receipt is bound to the authorized caller identity.
    const receipt = await db.doc(`workspaces/ws_callable/bookingCommands/${commandId(trainerUid, "callable-key")}`).get();
    assert.equal(receipt.get("callerUid"), trainerUid);

    const replay = await call(bookingPayload({ trainerId: trainerUid, clientId: "client-fixture" }));
    const replayed = replay.data as { replayed: boolean; bucketIds: string[] };
    assert.equal(replayed.replayed, true);
    assert.deepEqual(replayed.bucketIds, first.bucketIds);
    assert.equal((await db.collection("workspaces/ws_callable/bookingCommands").get()).size, 1);
  });

  it("prevents a different caller from replaying the receipt as its own operation", async () => {
    const trainerUid = await freshAnonymousUid();
    await seedMember(trainerUid, "trainer");
    await seedMember("client-fixture", "client");
    await call(bookingPayload({ trainerId: trainerUid, clientId: "client-fixture" }));

    // A second anonymous identity, seeded as an authorized client member, reuses the same
    // idempotency key: its distinct caller-bound command identity finds no receipt, so it
    // can neither replay nor observe the trainer's operation and fails on the held locks.
    const clientUid = await freshAnonymousUid();
    await seedMember(clientUid, "client");
    await assert.rejects(() => call(bookingPayload({ trainerId: trainerUid, clientId: clientUid })), (error: FunctionsError) => {
      assert.equal(error.code, "functions/failed-precondition");
      assert.match(error.message, /slot-conflict/);
      return true;
    });
    assert.equal((await db.collection("workspaces/ws_callable/bookingCommands").get()).size, 1);
    assert.equal((await db.doc(`workspaces/ws_callable/bookingCommands/${commandId(trainerUid, "callable-key")}`).get()).get("callerUid"), trainerUid);
    assert.equal((await db.collection("workspaces/ws_callable/appointments").get()).size, 1);
  });

  it("rejects forged identifiers and malformed payloads at the callable boundary", async () => {
    const trainerUid = await freshAnonymousUid();
    await seedMember(trainerUid, "trainer");
    await seedMember("client-fixture", "client");

    // A client-role caller cannot claim to be the trainer.
    const clientUid = await freshAnonymousUid();
    await seedMember(clientUid, "client");
    await assert.rejects(() => call(bookingPayload({ trainerId: clientUid, clientId: "client-fixture" })), (error: FunctionsError) => {
      assert.equal(error.code, "functions/permission-denied");
      assert.match(error.message, /participant-not-authorized|trainer-not-eligible/);
      return true;
    });

    // Path-unsafe and malformed identifiers are rejected before any Firestore access.
    await signOut(getAuth(app));
    await freshAnonymousUid();
    await assert.rejects(() => call(bookingPayload({ workspaceId: "ws/memberships/admin" })), (error: FunctionsError) => {
      assert.equal(error.code, "functions/invalid-argument");
      return true;
    });
    // Email-shaped participant UIDs pass the boundary and are decided by authorization instead,
    // while a separator in any identifier still fails validation before Firestore access.
    await assert.rejects(() => call(bookingPayload({ trainerId: "trainer@example.com" })), (error: FunctionsError) => {
      assert.equal(error.code, "functions/permission-denied");
      return true;
    });
    for (const override of [{ clientId: "client/other" }, { trainerId: "trainer/other" }, { appointmentId: "appt/other" }, { idempotencyKey: "key/other" }]) {
      await assert.rejects(() => call(bookingPayload(override)), (error: FunctionsError) => {
        assert.equal(error.code, "functions/invalid-argument");
        return true;
      });
    }
    await assert.rejects(() => call(bookingPayload({ startsAtMillis: "tomorrow" })), (error: FunctionsError) => {
      assert.equal(error.code, "functions/invalid-argument");
      return true;
    });
    assert.equal((await db.collection("workspaces/ws_callable/appointments").get()).size, 0);
    assert.equal((await db.collection("workspaces/ws_callable/bookingSlots").get()).size, 0);
  });
});