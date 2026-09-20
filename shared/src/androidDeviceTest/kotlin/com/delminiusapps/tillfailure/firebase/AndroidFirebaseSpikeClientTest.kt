package com.delminiusapps.tillfailure.firebase

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

        switchedAccount.set(true)
        val replacementEpoch = fence.advance()
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
        Thread.sleep(500)
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

    private fun await(latch: CountDownLatch, operation: String) {
        assertTrue(latch.await(15, TimeUnit.SECONDS), "$operation timed out")
    }
}
