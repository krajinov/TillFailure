package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.clients.*
import com.delminiusapps.tillfailure.identity.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AndroidClientsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @Test fun realRosterDetailsRecreationForegroundAndCleanDeparture() {
        val args = InstrumentationRegistry.getArguments()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("m5_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "m5-native").apply { mkdirs() }
        }
        val client = AndroidFirebaseSpikeClient(instrumentation.targetContext,
            FirebaseEmulatorConfiguration(host = "10.0.2.2", authPort = 29099, firestorePort = 28080, functionsPort = 25001),
            AccountCallbackFence(), productMemoryCache = true, overrideProductAppName = "m5-native-android", protectedContextForTest = context)
        val uid = "m5_android_trainer"
        val wid = "m5_android_workspace"
        if (!client.hasPinnedIdentity()) assertNull(await<StableFirebaseFailure?> { client.signIn("m5-android-trainer@example.invalid", args.getString("m5Password")!!, 0, it) })
        var root: NativeClientsModels? = null
        main { root = NativeClientsModels(client) }; gate(root!!.identity, GateStatus.TrainerHome)
        assertEquals(uid, root!!.identity.currentState().accountUid)
        fun scope() = ClientScope(uid, wid, uid, root!!.identity.currentState().authorityEpoch)
        main { root!!.roster.bind(scope(), null) }; until { !root!!.roster.currentState().loading }
        assertNull(root!!.roster.currentState().error); assertEquals(2, root!!.roster.currentState().items.size); assertNotNull(root!!.roster.currentState().nextCursor)
        val target = root!!.roster.currentState().items.first().clientId
        main { root!!.details.bind(scope(), target) }; until { !root!!.details.currentState().loading }
        assertEquals("Android Fixture one", root!!.details.currentState().detail?.displayName)
        main { root!!.roster.onEvent(ClientsEvent.LoadMore) }; until { !root!!.roster.currentState().loading }
        assertEquals(3, root!!.roster.currentState().items.size); assertNull(root!!.roster.currentState().items.last().displayName); assertNull(root!!.roster.currentState().nextCursor)
        main { root!!.clear(); root = NativeClientsModels(client) }; gate(root!!.identity, GateStatus.TrainerHome)
        assertTrue(root!!.roster.currentState().items.isEmpty()); assertNull(root!!.details.currentState().detail)
        main { root!!.identity.onEvent(IdentityEvent.Suspend) }; assertEquals(GateStatus.Loading, root!!.identity.currentState().status)
        main { root!!.identity.onEvent(IdentityEvent.Foreground) }; gate(root!!.identity, GateStatus.TrainerHome)
        main { root!!.roster.bind(scope(), null) }; until { !root!!.roster.currentState().loading }
        assertEquals(2, root!!.roster.currentState().items.size)
        val foreign: ClientResult = await { client.clients.read(scope(), ClientRequest("get", wid, clientId = "m5_ios_one"), it) }
        assertEquals("access-lost", foreign.error)
        val beforeIssue = CountDownLatch(1); val releaseIssue = CountDownLatch(1); val tokenIssues = AtomicInteger()
        val heldScope = scope(); val rejected = AtomicReference<ClientResult>()
        client.beforeClientsTokenIssuanceForTest = { beforeIssue.countDown(); assertTrue(releaseIssue.await(15, TimeUnit.SECONDS)) }
        client.afterClientsTokenIssuedForTest = { tokenIssues.incrementAndGet() }
        val reader = Thread { client.clients.read(heldScope, ClientRequest("list", wid, 2), rejected::set) }
        reader.start()
        try {
            assertTrue(beforeIssue.await(15, TimeUnit.SECONDS)); assertTrue(client.freeze(uid))
        } finally { releaseIssue.countDown() }
        reader.join(15_000); assertFalse(reader.isAlive)
        assertEquals(0, tokenIssues.get(), "A roster read waiting for admission must not refresh Auth after freeze")
        assertEquals("unavailable", rejected.get()?.error)
        client.beforeClientsTokenIssuanceForTest = null; client.afterClientsTokenIssuedForTest = null; client.unfreeze(uid)
        main { root!!.roster.clear(); root!!.details.clear(); root!!.identity.onEvent(IdentityEvent.SignOut) }; gate(root!!.identity, GateStatus.SignedOut)
        main { root!!.clear() }
    }
    private fun gate(model: IdentityViewModel, status: GateStatus) { until { model.currentState().status == status }; assertEquals(status, model.currentState().status) }
    private fun until(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (!done() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(done(), "Native roster timed out")
    }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); val value = AtomicReference<T>()
        start { value.set(it); latch.countDown() }; assertTrue(latch.await(40, TimeUnit.SECONDS)); return value.get()
    }
}
