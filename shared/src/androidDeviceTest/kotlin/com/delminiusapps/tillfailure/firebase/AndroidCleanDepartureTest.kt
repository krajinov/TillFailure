package com.delminiusapps.tillfailure.firebase

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.CleanDepartureCoordinator
import com.delminiusapps.tillfailure.identity.DepartureOutcome
import com.delminiusapps.tillfailure.identity.DepartureProgress
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityViewModel
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import com.delminiusapps.tillfailure.persistence.MutationJournalEntry
import com.google.firebase.auth.FirebaseAuth
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against seeded Auth/Firestore emulators with the real Android SDK adapter. */
class AndroidCleanDepartureTest {
    private val uid = "pr1_client"
    private val password: String get() = InstrumentationRegistry.getArguments().getString("pr1Password")
        ?: error("Pass pr1Password for local emulator tests")
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().context
    private val store: AndroidAtomicFilePersistence get() = AndroidAtomicFilePersistence(context)

    private fun fresh(): AndroidFirebaseSpikeClient = AndroidFirebaseSpikeClient(
        context, FirebaseEmulatorConfiguration(host = "10.0.2.2"), AccountCallbackFence(), productMemoryCache = true,
    )

    private fun reset() {
        context.getSharedPreferences("tillfailure_identity_pin_v1", Context.MODE_PRIVATE).edit().clear().commit()
        for (account in listOf(uid, "pr1_trainer", "pr1_unsupported/uid")) runCatching { store.delete(account) }
    }

    private fun signedInA(): AndroidFirebaseSpikeClient {
        val client = fresh()
        FirebaseAuth.getInstance(client.firebaseApp).signOut()
        assertNull(await<StableFirebaseFailure?> { done ->
            client.signIn("pr1-client@example.invalid", password, 0L, done)
        })
        assertEquals(uid, FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
        assertTrue(client.claimPinnedIdentity(uid))
        assertTrue(client.hasProvenEmptyCriticalWork(uid))
        return client
    }

    @Test fun cleanDeparturePermitsBWithoutOldCacheOrCallbacks() {
        reset()
        val a = signedInA()
        // The product adapter's own fence is advanced before Auth and SDK teardown.
        val outcome = await<DepartureOutcome> { done ->
            CleanDepartureCoordinator(a).depart(uid, {}, done)
        }
        assertEquals(DepartureOutcome.Clean, outcome)
        assertNull(a.readMarker().marker)
        assertFalse(a.hasPinnedIdentity())
        assertNull(store.read(uid))
        val b = a.replacement() as AndroidFirebaseSpikeClient
        assertNull(await<StableFirebaseFailure?> { done ->
            b.signIn("pr1-trainer@example.invalid", password, 0L, done)
        })
        assertTrue(b.claimPinnedIdentity("pr1_trainer"))
        assertEquals("pr1_trainer", FirebaseAuth.getInstance(b.firebaseApp).currentUser?.uid)
        val crossAccount = await<FirebaseDocumentResult> { done -> b.getDocument("users/$uid", 0L, done) }
        assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED, crossAccount.failure?.code)
        val own = await<FirebaseDocumentResult> { done -> b.getDocument("users/pr1_trainer", 0L, done) }
        assertEquals(FirebaseDataOrigin.SERVER, own.document?.origin)
        assertNotNull(own.document)
        b.terminateAndClear { }
        reset()
    }

    @Test fun pendingJournalBlocksBeforeAuthChangesAndCleanupFailureKeepsMarker() {
        reset()
        val client = signedInA()
        store.write(AccountPersistenceEnvelope(uid = uid, mutationJournal = listOf(
            MutationJournalEntry(uid = uid, workspaceId = "pr1_workspace", operationId = "pending", payload = "local", state = "PendingSync")
        )))
        assertEquals(DepartureOutcome.PreflightBlocked, await<DepartureOutcome> { done ->
            CleanDepartureCoordinator(client).depart(uid, {}, done)
        })
        assertEquals(uid, FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
        assertNull(client.readMarker().marker)
        store.write(AccountPersistenceEnvelope(uid = uid))
        client.beforeDepartureLocalCleanupForTest = {
            store.write(AccountPersistenceEnvelope(uid = uid, mutationJournal = listOf(
                MutationJournalEntry(uid = uid, workspaceId = "pr1_workspace", operationId = "late", payload = "test", state = "PendingSync")
            )))
        }
        assertEquals(DepartureOutcome.CleanupRequired, await<DepartureOutcome> { done ->
            CleanDepartureCoordinator(client).depart(uid, {}, done)
        })
        assertEquals(uid, client.readMarker().marker?.departingUid)
        assertTrue(client.hasPinnedIdentity())
        store.write(AccountPersistenceEnvelope(uid = uid)) // test fixture repair, not product discard
        val restarted = client.replacement() as AndroidFirebaseSpikeClient
        val recovery = await<Pair<DepartureOutcome, Boolean>> { done ->
            CleanDepartureCoordinator(restarted).recover({}, { outcome, marker -> done(outcome to marker) })
        }
        assertEquals(DepartureOutcome.Clean to true, recovery)
        assertNull(restarted.readMarker().marker)
        reset()
    }

    @Test fun stalledSdkWriteBlocksDepartureWithoutChangingAuth() {
        reset()
        val client = signedInA()
        val network = CountDownLatch(1)
        client.disableNetwork { network.countDown() }
        assertTrue(network.await(10, TimeUnit.SECONDS))
        client.writeDocument("spikeEcho/pending-departure", mapOf("value" to "pending"), 0L) { }
        assertEquals(DepartureOutcome.PreflightBlocked, await<DepartureOutcome>(25) { done ->
            CleanDepartureCoordinator(client).depart(uid, {}, done)
        })
        assertEquals(uid, FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
        assertNull(client.readMarker().marker)
        val online = CountDownLatch(1)
        client.enableNetwork { online.countDown() }
        online.await(10, TimeUnit.SECONDS)
        reset()
    }

    @Test fun markerRecoversAcrossEachInterruptedDurablePhase() {
        for (phase in 0..3) {
            reset()
            val client = signedInA()
            assertTrue(client.freeze(uid))
            assertTrue(client.hasProvenEmptyCriticalWork(uid))
            assertNull(await<StableFirebaseFailure?> { done -> client.drain(5_000, done) })
            assertTrue(client.persistMarker(uid))
            if (phase >= 1) assertNull(await<StableFirebaseFailure?> { done -> client.signOut(done) })
            if (phase >= 2) assertNull(await<StableFirebaseFailure?> { done -> client.retireFirestore(done) })
            if (phase >= 3) assertTrue(client.cleanupLocal(uid))
            val restarted = client.replacement() as AndroidFirebaseSpikeClient
            assertEquals(uid, restarted.readMarker().marker?.departingUid)
            val result = await<Pair<DepartureOutcome, Boolean>> { done ->
                CleanDepartureCoordinator(restarted).recover({}, { outcome, marker -> done(outcome to marker) })
            }
            assertEquals(DepartureOutcome.Clean to true, result, "phase=$phase")
            assertNull(restarted.readMarker().marker)
            assertFalse(restarted.hasPinnedIdentity())
        }
        reset()
    }

    @Test fun unsupportedAuthUidRecoversItsMarkerAndNeverNeedsAnUnsafeFirestorePath() {
        reset()
        val unsafeUid = "pr1_unsupported/uid"
        assertFalse(isPathSafeAuthUid(unsafeUid))
        val client = fresh()
        FirebaseAuth.getInstance(client.firebaseApp).signOut()
        assertNull(await<StableFirebaseFailure?> { done ->
            client.signIn("pr1-unsupported@example.invalid", password, 0L, done)
        })
        assertEquals(unsafeUid, FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
        assertTrue(client.claimPinnedIdentity(unsafeUid))
        assertTrue(client.hasProvenEmptyCriticalWork(unsafeUid))
        assertTrue(client.freeze(unsafeUid))
        assertNull(await<StableFirebaseFailure?> { done -> client.drain(5_000, done) })
        assertTrue(client.persistMarker(unsafeUid))
        val restarted = client.replacement() as AndroidFirebaseSpikeClient
        assertEquals(unsafeUid, restarted.readMarker().marker?.departingUid)
        assertEquals(DepartureOutcome.Clean to true, await<Pair<DepartureOutcome, Boolean>> { done ->
            CleanDepartureCoordinator(restarted).recover({}, { outcome, hadMarker -> done(outcome to hadMarker) })
        })
        assertNull(store.read(unsafeUid))
        assertNull(restarted.readMarker().marker)
        val b = restarted.replacement() as AndroidFirebaseSpikeClient
        assertNull(await<StableFirebaseFailure?> { done ->
            b.signIn("pr1-trainer@example.invalid", password, 0L, done)
        })
        assertTrue(b.claimPinnedIdentity("pr1_trainer"))
        assertEquals(FirebaseDataOrigin.SERVER,
            await<FirebaseDocumentResult> { done -> b.getDocument("users/pr1_trainer", 0L, done) }.document?.origin)
        reset()
    }

    @Test fun restoredAnonymousAuthSessionCanCleanlyDepartBeforeBEnters() {
        reset()
        val client = fresh()
        FirebaseAuth.getInstance(client.firebaseApp).signOut()
        val signedIn = await<FirebaseUnitResult> { done -> client.signInAnonymously(0L, done) }
        assertNull(signedIn.failure)
        val anonymous = FirebaseAuth.getInstance(client.firebaseApp).currentUser
        assertNotNull(anonymous)
        assertTrue(anonymous.isAnonymous)
        val anonymousUid = anonymous.uid
        assertTrue(client.claimPinnedIdentity(anonymousUid))
        assertTrue(client.hasProvenEmptyCriticalWork(anonymousUid))
        assertEquals(DepartureOutcome.Clean, await<DepartureOutcome> { done ->
            CleanDepartureCoordinator(client).depart(anonymousUid, {}, done)
        })
        assertNull(store.read(anonymousUid))
        assertNull(client.readMarker().marker)
        val b = client.replacement() as AndroidFirebaseSpikeClient
        assertNull(await<StableFirebaseFailure?> { done ->
            b.signIn("pr1-trainer@example.invalid", password, 0L, done)
        })
        assertTrue(b.claimPinnedIdentity("pr1_trainer"))
        assertEquals(FirebaseDataOrigin.SERVER,
            await<FirebaseDocumentResult> { done -> b.getDocument("users/pr1_trainer", 0L, done) }.document?.origin)
        b.terminateAndClear { }
        reset()
    }

    @Test fun hostResolvesLiveGenerationWhenProductGateReturnsAfterDeparture() {
        reset()
        val initial = AndroidProductIdentitySession.get(context)
        FirebaseAuth.getInstance(initial.firebaseApp).signOut()
        assertNull(await<FirebaseUnitResult> { done -> initial.signInAnonymously(0L, done) }.failure)
        val departingUid = FirebaseAuth.getInstance(initial.firebaseApp).currentUser?.uid
        assertNotNull(departingUid)
        assertTrue(initial.claimPinnedIdentity(departingUid))
        assertEquals(DepartureOutcome.Clean, await<DepartureOutcome> { done ->
            CleanDepartureCoordinator(initial).depart(departingUid, {}, done)
        })
        // The ViewModel replaces its client after clean departure. The host supplier must then
        // return that live generation when the Debug catalog is closed and the gate remounts.
        val replacement = initial.replacement() as AndroidFirebaseSpikeClient
        val remounted = AndroidProductIdentitySession.get(context)
        assertTrue(remounted === replacement)
        assertFalse(remounted.isRetired())
        assertNull(await<FirebaseAuthSession> { done -> remounted.observeSession(0L, done) }.uid)
        assertNull(store.read(departingUid))
        reset()
    }

    @Test fun stalledPreMarkerDepartureSurvivesRootRecreationAndCompletesOnce() {
        reset()
        // The Android host resolves one process-level generation; a recreated root and the departing
        // graph therefore share the same in-flight departure owner.
        val existing = AndroidProductIdentitySession.get(context)
        val client = if (existing.isRetired()) {
            fresh().also { AndroidProductIdentitySession.replaceIfCurrent(existing, it) }
        } else existing
        FirebaseAuth.getInstance(client.firebaseApp).signOut()
        assertNull(await<StableFirebaseFailure?> { done ->
            client.signIn("pr1-client@example.invalid", password, 0L, done)
        })
        assertTrue(client.claimPinnedIdentity(uid))
        val port = client.departure

        // The departing graph freezes A and stalls the SDK write drain while offline.
        val offline = CountDownLatch(1)
        client.disableNetwork { offline.countDown() }
        assertTrue(offline.await(10, TimeUnit.SECONDS))
        client.writeDocument("spikeEcho/pending-root-recreation", mapOf("value" to "pending"), 0L) { }
        var ownerOutcome: DepartureOutcome? = null
        assertTrue(port.ownership.lease(uid) { ownerOutcome = it })
        CleanDepartureCoordinator(port).depart(uid, {}, { outcome -> port.ownership.settle(uid, outcome) })
        assertEquals(DepartureProgress.PreMarker, port.ownership.progress())
        assertNull(port.readMarker().marker)

        // Recreate the root mid-drain on the same process-level client: it must join the in-flight
        // departure and must not verify or display protected membership for the still signed-in A.
        val recreated = IdentityViewModel(client)
        assertEquals(GateStatus.SwitchingOut, recreated.state.value.status)
        assertNull(recreated.state.value.verifiedRole)

        // The network returns; the stalled drain completes and the single departure finishes cleanly.
        val online = CountDownLatch(1)
        client.enableNetwork { online.countDown() }
        assertTrue(online.await(10, TimeUnit.SECONDS))
        assertTrue(awaitUntil(25_000) { ownerOutcome != null && port.ownership.progress() == DepartureProgress.Idle })
        assertEquals(DepartureOutcome.Clean, ownerOutcome)
        assertNull(port.readMarker().marker)
        assertNull(store.read(uid))
        assertTrue(awaitUntil(10_000) { recreated.state.value.status == GateStatus.SignedOut })

        // Account B enters on the generation the recreated root now uses, with fresh server proof,
        // and cannot read A.
        val b = AndroidProductIdentitySession.get(context)
        assertTrue(b !== client)
        assertNull(await<StableFirebaseFailure?> { done ->
            b.signIn("pr1-trainer@example.invalid", password, 0L, done)
        })
        assertTrue(b.claimPinnedIdentity("pr1_trainer"))
        assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED,
            await<FirebaseDocumentResult> { done -> b.getDocument("users/$uid", 0L, done) }.failure?.code)
        assertEquals(FirebaseDataOrigin.SERVER,
            await<FirebaseDocumentResult> { done -> b.getDocument("users/pr1_trainer", 0L, done) }.document?.origin)
        reset()
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun <T> await(seconds: Long = 15, start: ((T) -> Unit) -> Unit): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        start { result.set(it); done.countDown() }
        check(done.await(seconds, TimeUnit.SECONDS)) { "Native Firebase callback timed out" }
        return result.get()
    }
}
