package com.delminiusapps.tillfailure.firebase

import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidFirebaseSpikeClientTest {
    @Test
    fun emulatorBackedNativeAdapterCoversCriticalLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fence = AccountCallbackFence()
        val epoch = fence.advance()
        val client = AndroidFirebaseSpikeClient(
            context = context,
            configuration = FirebaseEmulatorConfiguration(host = "10.0.2.2"),
            fence = fence,
        )

        val observedUid = AtomicReference<String>()
        val authObserved = CountDownLatch(1)
        val sessionCancellation = client.observeSession(epoch) { session ->
            session.uid?.let {
                observedUid.set(it)
                authObserved.countDown()
            }
        }
        val signedIn = AtomicReference<FirebaseUnitResult>()
        val signInFinished = CountDownLatch(1)
        client.signInAnonymously(epoch) {
            signedIn.set(it)
            signInFinished.countDown()
        }
        await(signInFinished, "anonymous sign-in")
        assertTrue(assertNotNull(signedIn.get()).isSuccess, "anonymous sign-in failed: ${signedIn.get()?.failure}")
        await(authObserved, "auth-state observer")

        val uid = assertNotNull(observedUid.get())
        val acceptedPath = "spikeEcho/$uid/documents/native-android"
        val listenerObserved = CountDownLatch(1)
        val switchedAccount = AtomicBoolean(false)
        val lateListenerCallback = AtomicBoolean(false)
        val listener = client.listenDocument(acceptedPath, epoch) { result ->
            if (switchedAccount.get()) lateListenerCallback.set(true)
            if (result.document?.exists == true) listenerObserved.countDown()
        }

        val acceptedWrite = AtomicReference<FirebaseUnitResult>()
        val acceptedFinished = CountDownLatch(1)
        client.writeDocument(
            acceptedPath,
            mapOf("ownerUid" to uid, "value" to "accepted", "counter" to "0"),
            epoch,
        ) {
            acceptedWrite.set(it)
            acceptedFinished.countDown()
        }
        await(acceptedFinished, "accepted write")
        assertTrue(assertNotNull(acceptedWrite.get()).isSuccess)
        await(listenerObserved, "metadata listener")

        val serverRead = AtomicReference<FirebaseDocumentResult>()
        val serverReadFinished = CountDownLatch(1)
        client.getDocument(acceptedPath, epoch) {
            serverRead.set(it)
            serverReadFinished.countDown()
        }
        await(serverReadFinished, "server read")
        assertEquals(FirebaseDataOrigin.SERVER, assertNotNull(serverRead.get()).document?.origin)

        val metadataPath = "spikeEcho/$uid/documents/native-android-metadata"
        val pendingMetadata = CountDownLatch(1)
        val acknowledgedMetadata = CountDownLatch(1)
        val pendingOrigin = AtomicReference<FirebaseDataOrigin>()
        val acknowledgedOrigin = AtomicReference<FirebaseDataOrigin>()
        val metadataCallbacks = AtomicInteger(0)
        val metadataListener = client.listenDocument(metadataPath, epoch) { result ->
            val document = result.document ?: return@listenDocument
            metadataCallbacks.incrementAndGet()
            if (document.exists && document.hasPendingWrites) {
                pendingOrigin.set(document.origin)
                pendingMetadata.countDown()
            } else if (document.exists && pendingMetadata.count == 0L) {
                acknowledgedOrigin.set(document.origin)
                acknowledgedMetadata.countDown()
            }
        }
        awaitNetwork(client, enabled = false)
        val stalledWriteFinished = CountDownLatch(1)
        client.writeDocument(
            metadataPath,
            mapOf("ownerUid" to uid, "value" to "stalled", "counter" to "0"),
            epoch,
        ) { stalledWriteFinished.countDown() }
        await(pendingMetadata, "local pending metadata")
        assertEquals(FirebaseDataOrigin.CACHE, pendingOrigin.get())

        val cancelledWaitResult = AtomicReference<FirebaseUnitResult>()
        val cancelledWaitCallbacks = AtomicInteger(0)
        val cancelledWaitFinished = CountDownLatch(1)
        val cancelledWait = client.waitForPendingWrites(epoch, timeoutMillis = 5_000) {
            cancelledWaitCallbacks.incrementAndGet()
            cancelledWaitResult.set(it)
            cancelledWaitFinished.countDown()
        }
        cancelledWait.cancel()
        await(cancelledWaitFinished, "explicit pending-write cancellation")
        assertEquals(StableFirebaseErrorCode.CANCELLED, cancelledWaitResult.get()?.failure?.code)

        val timedOutWaitResult = AtomicReference<FirebaseUnitResult>()
        val timedOutWaitCallbacks = AtomicInteger(0)
        val timedOutWaitFinished = CountDownLatch(1)
        client.waitForPendingWrites(epoch, timeoutMillis = 300) {
            timedOutWaitCallbacks.incrementAndGet()
            timedOutWaitResult.set(it)
            timedOutWaitFinished.countDown()
        }
        await(timedOutWaitFinished, "pending-write timeout")
        assertEquals(StableFirebaseErrorCode.DEADLINE_EXCEEDED, timedOutWaitResult.get()?.failure?.code)

        awaitNetwork(client, enabled = true)
        await(stalledWriteFinished, "stalled write settlement")
        await(acknowledgedMetadata, "acknowledged metadata")
        assertEquals(FirebaseDataOrigin.SERVER, acknowledgedOrigin.get())
        val settled = CountDownLatch(1)
        client.waitForPendingWrites(epoch, timeoutMillis = 5_000) { settled.countDown() }
        await(settled, "post-stall pending-write settlement")
        assertEquals(1, cancelledWaitCallbacks.get(), "SDK completion crossed the cancellation fence")
        assertEquals(1, timedOutWaitCallbacks.get(), "SDK completion crossed the timeout fence")

        metadataListener.cancel()
        val callbacksAtDisposal = metadataCallbacks.get()
        val postDisposalWrite = CountDownLatch(1)
        client.writeDocument(
            metadataPath,
            mapOf("ownerUid" to uid, "value" to "after-disposal", "counter" to "0"),
            epoch,
        ) { postDisposalWrite.countDown() }
        await(postDisposalWrite, "post-disposal write")
        val postDisposalDrain = CountDownLatch(1)
        client.waitForPendingWrites(epoch, timeoutMillis = 5_000) { postDisposalDrain.countDown() }
        await(postDisposalDrain, "post-disposal drain")
        assertEquals(callbacksAtDisposal, metadataCallbacks.get(), "Listener delivered after disposal")

        val rejectedWrite = AtomicReference<FirebaseUnitResult>()
        val rejectedFinished = CountDownLatch(1)
        client.writeDocument(
            "spikeEcho/other/documents/native-android",
            mapOf("ownerUid" to uid, "value" to "forged", "counter" to "0"),
            epoch,
        ) {
            rejectedWrite.set(it)
            rejectedFinished.countDown()
        }
        await(rejectedFinished, "rules-rejected write")
        assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED, rejectedWrite.get()?.failure?.code)

        val incremented = AtomicReference<FirebaseDocumentResult>()
        val incrementFinished = CountDownLatch(1)
        client.increment(acceptedPath, "counter", 1, epoch) {
            incremented.set(it)
            incrementFinished.countDown()
        }
        await(incrementFinished, "transaction")
        val incrementResult = assertNotNull(incremented.get())
        assertEquals(null, incrementResult.failure, "transaction failed")
        assertEquals("1", incrementResult.document?.fields?.get("counter"))

        val pendingWrites = AtomicReference<FirebaseUnitResult>()
        val pendingFinished = CountDownLatch(1)
        client.waitForPendingWrites(epoch) {
            pendingWrites.set(it)
            pendingFinished.countDown()
        }
        await(pendingFinished, "pending-write drain")
        assertTrue(assertNotNull(pendingWrites.get()).isSuccess)

        awaitNetwork(client, enabled = false)
        val epochFencedWrite = client.writeDocument(
            "spikeEcho/$uid/documents/native-android-epoch",
            mapOf("ownerUid" to uid, "value" to "old-epoch", "counter" to "0"),
            epoch,
        ) {}
        val stalePendingCallbacks = AtomicInteger(0)
        val stalePendingWait = client.waitForPendingWrites(epoch, timeoutMillis = 5_000) {
            stalePendingCallbacks.incrementAndGet()
        }
        switchedAccount.set(true)
        val replacementEpoch = fence.advance()
        awaitNetwork(client, enabled = true)
        val replacementDrain = CountDownLatch(1)
        client.waitForPendingWrites(replacementEpoch, timeoutMillis = 5_000) { replacementDrain.countDown() }
        await(replacementDrain, "replacement-epoch pending drain")
        stalePendingWait.cancel()
        epochFencedWrite.cancel()
        assertEquals(0, stalePendingCallbacks.get(), "Old-epoch pending wait crossed the account fence")

        val replacementWrite = AtomicReference<FirebaseUnitResult>()
        val replacementWriteFinished = CountDownLatch(1)
        client.writeDocument(
            acceptedPath,
            mapOf("ownerUid" to uid, "value" to "replacement-account-epoch", "counter" to "1"),
            replacementEpoch,
        ) {
            replacementWrite.set(it)
            replacementWriteFinished.countDown()
        }
        await(replacementWriteFinished, "replacement-epoch write")
        assertTrue(assertNotNull(replacementWrite.get()).isSuccess)
        assertFalse(lateListenerCallback.get())

        val cancelledCallbackObserved = AtomicBoolean(false)
        client.getDocument(acceptedPath, replacementEpoch) { cancelledCallbackObserved.set(true) }.cancel()
        Thread.sleep(500)
        assertFalse(cancelledCallbackObserved.get())

        listener.cancel()
        sessionCancellation.cancel()
        val cleared = AtomicReference<FirebaseUnitResult>()
        val clearFinished = CountDownLatch(1)
        client.terminateAndClear {
            cleared.set(it)
            clearFinished.countDown()
        }
        await(clearFinished, "terminate and clear persistence")
        assertTrue(assertNotNull(cleared.get()).isSuccess)
    }

    @Test
    fun counterValuesFollowTheSharedCanonicalContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = FirebaseEmulatorConfiguration(host = "10.0.2.2")
        val fence = AccountCallbackFence()
        val epoch = fence.advance()
        val client = AndroidFirebaseSpikeClient(context, configuration, fence)
        val observedUid = AtomicReference<String>()
        val authObserved = CountDownLatch(1)
        client.observeSession(epoch) { session ->
            session.uid?.let {
                observedUid.set(it)
                authObserved.countDown()
            }
        }
        val signedIn = AtomicReference<FirebaseUnitResult>()
        val signInFinished = CountDownLatch(1)
        client.signInAnonymously(epoch) {
            signedIn.set(it)
            signInFinished.countDown()
        }
        await(signInFinished, "anonymous sign-in")
        assertTrue(assertNotNull(signedIn.get()).isSuccess)
        await(authObserved, "auth-state observer")
        val uid = assertNotNull(observedUid.get())
        val path = "spikeEcho/$uid/documents/native-android-counter"
        val firestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance("tillfailure-${configuration.projectId}"))

        fun seedCounter(value: Any?) {
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Exception>()
            firestore.document(path).set(mapOf("ownerUid" to uid, "counter" to value))
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) failure.set(task.exception)
                    finished.countDown()
                }
            await(finished, "seed counter fixture")
            assertNull(failure.get(), "counter fixture seed failed: ${failure.get()}")
        }

        fun storedCounter(): Any? {
            val finished = CountDownLatch(1)
            val snapshot = AtomicReference<DocumentSnapshot>()
            firestore.document(path).get(Source.SERVER)
                .addOnCompleteListener { task ->
                    snapshot.set(task.result)
                    finished.countDown()
                }
            await(finished, "read stored counter")
            return assertNotNull(snapshot.get()).get("counter")
        }

        fun increment(by: Long): FirebaseDocumentResult {
            val result = AtomicReference<FirebaseDocumentResult>()
            val finished = CountDownLatch(1)
            client.increment(path, "counter", by, epoch) {
                result.set(it)
                finished.countDown()
            }
            await(finished, "counter increment")
            return assertNotNull(result.get())
        }

        fun assertIncrementResult(expected: Long, by: Long = 1) {
            val result = increment(by)
            assertNull(result.failure, "increment failed: ${result.failure}")
            assertEquals(expected.toString(), result.document?.fields?.get("counter"))
        }

        fun assertIncrementRejected(storedBefore: Any?) {
            val result = increment(1)
            assertEquals(StableFirebaseErrorCode.INVALID_ARGUMENT, result.failure?.code)
            assertEquals(false, result.failure?.retryable)
            assertEquals(storedBefore, storedCounter(), "a rejected increment must not overwrite the stored value")
        }

        // A missing field starts from zero, matching the documented adapter behavior.
        seedCounter(null)
        assertIncrementResult(1)
        // A canonical numeric string written through the string-typed bridge parses instead of resetting.
        seedCounter("5")
        assertIncrementResult(6)
        // The integral value produced by the previous increment increments again.
        assertIncrementResult(7)
        // Zero, negative values, and negative increments.
        seedCounter("0")
        assertIncrementResult(1)
        seedCounter("-3")
        assertIncrementResult(-2)
        seedCounter("5")
        assertIncrementResult(3, by = -2)
        // Sequential increments apply exactly once each.
        seedCounter("5")
        repeat(5) { assertIncrementResult((6 + it).toLong()) }
        // Malformed, ambiguous, and out-of-range strings are rejected without overwriting.
        for (value in listOf("abc", "5.5", " 5", "5 ", "+5", "")) {
            seedCounter(value)
            assertIncrementRejected(value)
        }
        // Typed unsupported values are rejected: boolean, floating point, and collection.
        for (value in listOf(true, 5.0, listOf("x"))) {
            seedCounter(value)
            assertIncrementRejected(value)
        }
        // Current-value and addition overflow are rejected without overwriting.
        seedCounter("9223372036854775807")
        assertIncrementRejected("9223372036854775807")
    }

    private fun await(latch: CountDownLatch, operation: String) {
        assertTrue(latch.await(15, TimeUnit.SECONDS), "$operation timed out")
    }

    private fun awaitNetwork(client: AndroidFirebaseSpikeClient, enabled: Boolean) {
        val result = AtomicReference<FirebaseUnitResult>()
        val finished = CountDownLatch(1)
        val callback: (FirebaseUnitResult) -> Unit = {
            result.set(it)
            finished.countDown()
        }
        if (enabled) client.enableNetwork(callback) else client.disableNetwork(callback)
        await(finished, if (enabled) "enable Firestore network" else "disable Firestore network")
        assertTrue(assertNotNull(result.get()).isSuccess)
    }
}
