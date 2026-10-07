package com.delminiusapps.tillfailure.firebase

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.CleanDepartureCoordinator
import com.delminiusapps.tillfailure.identity.DepartureOutcome
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

    private fun <T> await(seconds: Long = 15, start: ((T) -> Unit) -> Unit): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        start { result.set(it); done.countDown() }
        check(done.await(seconds, TimeUnit.SECONDS)) { "Native Firebase callback timed out" }
        return result.get()
    }
}
