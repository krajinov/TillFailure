package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.clients.NativeClientsModels
import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.InvitationRequest
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AndroidSystemExercisesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @Test fun realServerReadsCancellationRecreationAndProtectedDeparture() {
        val args = InstrumentationRegistry.getArguments()
        val prefix = requireNotNull(args.getString("m6Prefix")); require(prefix.matches(Regex("m6_[a-z0-9_]{1,40}")))
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${prefix}_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), prefix).apply { mkdirs() }
        }
        var client = AndroidFirebaseSpikeClient(instrumentation.targetContext,
            FirebaseEmulatorConfiguration(host = "10.0.2.2", authPort = 29599, firestorePort = 28580, functionsPort = 25501),
            AccountCallbackFence(), productMemoryCache = true, overrideProductAppName = if (args.getString("m6Phase") == "denied") null else "$prefix-native", protectedContextForTest = context)
        val uid = "${prefix}_android"; val other = "${prefix}_android_b"
        fun read(user: String, after: String? = null, id: String? = null): ExerciseResult = await { client.exercises.read(user, after, id, it) }
        if (args.getString("m6Phase") == "denied") {
            val original = client.invitations.pending(other); assertNotNull(original)
            assertEquals(ExerciseError.Denied, read(other).error)
            assertEquals(original, client.invitations.pending(other)); return
        }
        if (!client.hasPinnedIdentity()) assertNull(await<StableFirebaseFailure?> { client.signIn("$prefix-android@example.invalid", args.getString("m6Password")!!, 0, it) })
        var root: NativeClientsModels? = null
        main { root = NativeClientsModels(client) }; until { root!!.identity.currentState().status == GateStatus.TrainerHome }
        assertEquals(uid, root!!.identity.currentState().accountUid)
        val first = read(uid); assertNull(first.error); assertEquals(20, first.items.size); assertEquals("ex_20", first.nextId)
        assertEquals("ex_01", read(uid, id = "ex_01").detail?.id)
        val last = read(uid, after = first.nextId); assertEquals(3, last.items.size); assertNull(last.nextId)
        val callbacks = AtomicInteger()
        main { val token = client.exercises.read(uid, null, null) { callbacks.incrementAndGet() }; token.cancel() }
        Thread.sleep(500); assertEquals(0, callbacks.get())
        assertTrue(client.freeze(uid)); assertEquals(ExerciseError.Unavailable, read(uid).error); client.unfreeze(uid)
        assertNull(await<FirebaseUnitResult> { client.disableNetwork(it) }.failure)
        val offline = AtomicReference<ExerciseResult>()
        val offlineRead = client.exercises.read(uid, null, null, offline::set)
        Thread.sleep(1500)
        assertTrue(offline.get() == null || offline.get().error != null, "Disabled transport must not return cached success")
        offlineRead.cancel()
        assertNull(await<FirebaseUnitResult> { client.enableNetwork(it) }.failure)
        // Enabling transport is not an acknowledgment that its reconnect handshake completed.
        var reconnected = read(uid)
        val reconnectEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (reconnected.error != null && System.nanoTime() < reconnectEnd) { Thread.sleep(200); reconnected = read(uid) }
        assertNull(reconnected.error, "Fresh reconnect read"); assertEquals(20, reconnected.items.size)
        main { root!!.clear(); root = NativeClientsModels(client) }; until { root!!.identity.currentState().status == GateStatus.TrainerHome }
        assertEquals(20, read(uid).items.size)
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }; until { root!!.identity.currentState().status == GateStatus.SignedOut }
        client = root!!.identity.activeClient() as AndroidFirebaseSpikeClient; main { root!!.clear() }
        assertNull(await<StableFirebaseFailure?> { client.signIn("$prefix-android_b@example.invalid", args.getString("m6Password")!!, 0, it) })
        main { root = NativeClientsModels(client) }; until { root!!.identity.currentState().status == GateStatus.TrainerHome }
        assertNull(client.invitations.pending(other)); assertEquals(20, read(other).items.size)
        client.beforeInvitationTransportForTest = { /* Keep the existing command before transport. */ }
        val request = InvitationRequest("create", workspaceId = "${other}_ws", email = "synthetic-pending@example.invalid", idempotencyKey = client.invitations.newCommandKey())
        client.invitations.execute(other, request) {}
        until { client.invitations.pending(other) != null }
        val original = client.invitations.pending(other)
        assertEquals(20, read(other).items.size); assertEquals(original, client.invitations.pending(other))
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }; until { root!!.identity.currentState().status in setOf(GateStatus.LockedRecovery, GateStatus.DepartureBlocked) }
        assertEquals(original, client.invitations.pending(other))
        assertNotNull(await<StableFirebaseFailure?> { client.signIn("$prefix-android@example.invalid", args.getString("m6Password")!!, 0, it) })
        main { root!!.clear() }
        // Preserve this original held command and its UID partition; browsing never settles it.
    }
    private fun until(done: () -> Boolean) { val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40); while (!done() && System.nanoTime() < end) Thread.sleep(50); assertTrue(done(), "Native exercise timeout") }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T { val latch = CountDownLatch(1); val result = AtomicReference<T>(); start { result.set(it); latch.countDown() }; assertTrue(latch.await(40, TimeUnit.SECONDS)); return result.get() }
}
