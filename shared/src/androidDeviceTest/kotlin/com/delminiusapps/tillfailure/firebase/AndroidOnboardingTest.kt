package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.onboarding.*
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AndroidOnboardingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @Test fun nativeCompletionRestoresRoutingAndProtectsOriginalCommandAcrossRootRecreation() {
        val args = InstrumentationRegistry.getArguments()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("pr4_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "pr4-native").apply { mkdirs() }
        }
        val client = AndroidFirebaseSpikeClient(instrumentation.targetContext,
            FirebaseEmulatorConfiguration(host = "10.0.2.2", authPort = 19099, firestorePort = 18080, functionsPort = 15001),
            AccountCallbackFence(), productMemoryCache = true, protectedContextForTest = context)
        assertNull(await<StableFirebaseFailure?> { client.signIn("pr4-android-native@example.invalid", args.getString("pr4Password")!!, 0, it) })
        var root: NativeOnboardingModels? = null
        main { root = NativeOnboardingModels(client) }
        gate(root!!.identity, GateStatus.Onboarding)
        val uid = "pr4_android_native"
        val wid = "pr4_android_workspace"
        val completion = InvitationRequest("complete-onboarding", workspaceId = wid, idempotencyKey = "native-complete",
            onboarding = OnboardingCompletion("Android Client", "en", "UTC", true, 0, 0))
        assertTrue(InvitationLedger(AndroidAtomicFilePersistence(context)).prepare(uid, completion))
        assertFalse(client.hasProvenEmptyCriticalWork(uid))
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }; gate(root!!.identity, GateStatus.DepartureBlocked)
        main { root!!.clear(); root = NativeOnboardingModels(client) }; gate(root!!.identity, GateStatus.Onboarding)
        main { root!!.refresh() }
        assertEquals(completion.pending(uid), root!!.onboarding.currentState().pending)
        main { root!!.onboarding.onEvent(OnboardingEvent.Retry) }
        until { client.invitations.pending(uid) == null }
        main { root!!.identity.onEvent(IdentityEvent.Retry) }; gate(root!!.identity, GateStatus.ClientHome)
        assertTrue(client.hasProvenEmptyCriticalWork(uid))
        val replay: InvitationOutcome = await { client.invitations.execute(uid, completion, it) }
        assertEquals(true, replay.reply?.replayed); assertNull(replay.error)
        main { root!!.clear(); root = NativeOnboardingModels(client) }; gate(root!!.identity, GateStatus.ClientHome)
        assertEquals("Android Client", root!!.identity.currentState().displayName)
        main { root!!.identity.onEvent(IdentityEvent.Suspend) }; assertEquals(GateStatus.Loading, root!!.identity.currentState().status)
        main { root!!.identity.onEvent(IdentityEvent.Foreground) }; gate(root!!.identity, GateStatus.ClientHome)
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }; gate(root!!.identity, GateStatus.SignedOut)
        main { root!!.clear() }
    }
    private fun gate(model: IdentityViewModel, status: GateStatus) { until { model.currentState().status == status }; assertEquals(status, model.currentState().status) }
    private fun until(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (!done() && System.nanoTime() < until) Thread.sleep(50)
        assertTrue(done(), "Native onboarding timed out")
    }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); val value = AtomicReference<T>()
        start { value.set(it); latch.countDown() }; assertTrue(latch.await(40, TimeUnit.SECONDS)); return value.get()
    }
}
