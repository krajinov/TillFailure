package com.delminiusapps.tillfailure.firebase

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.CleanDepartureCoordinator
import com.delminiusapps.tillfailure.identity.DepartureOutcome
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
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

/** Run stage and resume in separate instrumentation processes with the same phase argument. */
class AndroidDepartureProcessTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().context
    private val phase: Int get() = InstrumentationRegistry.getArguments().getString("departurePhase")?.toInt()
        ?: error("Pass departurePhase=0..4")
    private val password: String get() = InstrumentationRegistry.getArguments().getString("pr1Password")
        ?: error("Pass pr1Password")
    private fun fresh() = AndroidFirebaseSpikeClient(context, FirebaseEmulatorConfiguration(host = "10.0.2.2"),
        AccountCallbackFence(), productMemoryCache = true)

    @Test fun stage() {
        require(phase in 0..4)
        context.getSharedPreferences("tillfailure_identity_pin_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = AndroidAtomicFilePersistence(context)
        for (uid in listOf("pr1_client", "pr1_trainer")) runCatching { store.delete(uid) }
        val a = fresh()
        FirebaseAuth.getInstance(a.firebaseApp).signOut()
        assertNull(await<StableFirebaseFailure?> { done ->
            a.signIn("pr1-client@example.invalid", password, 0L, done)
        })
        assertTrue(a.claimPinnedIdentity("pr1_client"))
        if (phase == 4) {
            assertEquals(DepartureOutcome.Clean, await<DepartureOutcome> { done ->
                CleanDepartureCoordinator(a).depart("pr1_client", {}, done)
            })
            verifyB(a.replacement() as AndroidFirebaseSpikeClient)
            return
        }
        assertTrue(a.freeze("pr1_client"))
        assertTrue(a.hasProvenEmptyCriticalWork("pr1_client"))
        assertNull(await<StableFirebaseFailure?> { done -> a.drain(5_000, done) })
        assertTrue(a.persistMarker("pr1_client"))
        if (phase >= 1) {
            a.fenceCallbacks()
            val late = CountDownLatch(1)
            a.getDocument("users/pr1_client", 0L) { late.countDown() }
            assertNull(await<StableFirebaseFailure?> { done -> a.signOut(done) })
            assertFalse(late.await(600, TimeUnit.MILLISECONDS))
        }
        if (phase >= 2) assertNull(await<StableFirebaseFailure?> { done -> a.retireFirestore(done) })
        if (phase >= 3) assertTrue(a.cleanupLocal("pr1_client"))
        assertEquals("pr1_client", a.readMarker().marker?.departingUid)
    }

    @Test fun resume() {
        require(phase in 0..3)
        val client = fresh()
        assertEquals("pr1_client", client.readMarker().marker?.departingUid)
        assertEquals(if (phase == 0) "pr1_client" else null,
            FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
        val result = await<Pair<DepartureOutcome, Boolean>> { done ->
            CleanDepartureCoordinator(client).recover({}, { outcome, marker -> done(outcome to marker) })
        }
        assertEquals(DepartureOutcome.Clean to true, result)
        assertNull(client.readMarker().marker)
        assertFalse(client.hasPinnedIdentity())
        assertNull(AndroidAtomicFilePersistence(context).read("pr1_client"))
        verifyB(client.replacement() as AndroidFirebaseSpikeClient)
    }

    private fun verifyB(b: AndroidFirebaseSpikeClient) {
        assertNull(await<StableFirebaseFailure?> { done ->
            b.signIn("pr1-trainer@example.invalid", password, 0L, done)
        })
        assertTrue(b.claimPinnedIdentity("pr1_trainer"))
        val denied = await<FirebaseDocumentResult> { done -> b.getDocument("users/pr1_client", 0L, done) }
        val own = await<FirebaseDocumentResult> { done -> b.getDocument("users/pr1_trainer", 0L, done) }
        assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED, denied.failure?.code)
        assertEquals(FirebaseDataOrigin.SERVER, own.document?.origin)
        assertNotNull(own.document)
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val result = AtomicReference<T>()
        val latch = CountDownLatch(1)
        start { result.set(it); latch.countDown() }
        check(latch.await(15, TimeUnit.SECONDS)) { "Native Firebase callback timed out" }
        return result.get()
    }
}
