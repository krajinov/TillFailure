package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.profile.*
import com.delminiusapps.tillfailure.clients.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AndroidBasicProfileTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun until(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(ready(), "Profile native timeout")
    }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); val result = AtomicReference<T>()
        start { result.set(it); latch.countDown() }; assertTrue(latch.await(45, TimeUnit.SECONDS)); return result.get()
    }
    @Test fun realProfileCommandSurvivesActualHostRestart() {
        val args = InstrumentationRegistry.getArguments()
        val phase = requireNotNull(args.getString("m5p3Phase"))
        val password = requireNotNull(args.getString("m5p3Password"))
        val uid = "m5p3_android_client"; val wid = "m5p3_android_workspace"
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("m5p3_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "m5p3-native").apply { mkdirs() }
        }
        val client = AndroidFirebaseSpikeClient(instrumentation.targetContext,
            FirebaseEmulatorConfiguration(host = "10.0.2.2", authPort = 29599, firestorePort = 28580, functionsPort = 25501),
            AccountCallbackFence(), productMemoryCache = true, overrideProductAppName = "m5p3-native-android", protectedContextForTest = context)
        if (!client.hasPinnedIdentity()) assertNull(await<StableFirebaseFailure?> { client.signIn("m5p3-android-client@example.invalid", password, 0, it) })
        lateinit var root: NativeProfileModels
        main { root = NativeProfileModels(client) }
        until { root.identity.currentState().status in setOf(GateStatus.ClientHome, GateStatus.LockedRecovery) }
        main { root.refresh() }
        if (phase == "lifecycle") {
            main { root.profile.attach(1) }; until { !root.profile.currentState().busy && root.profile.currentState().baseline != null }
            val initial = root.profile.currentState().baseline
            main { val s = root.profile.currentState(); root.profile.onEvent(s.scope!!, s.visit, s.promptId, ProfileEvent.Leave(ProfileExit.Back)) }
            val dequeued = runBlocking { root.nextNavigation() }
            main {
                root.profile.suspendPresentation()
                assertFalse(root.profile.consumeNavigation(dequeued))
                assertNull(root.profile.currentState().baseline)
                root.identity.onEvent(IdentityEvent.Suspend); root.refresh()
                root.identity.onEvent(IdentityEvent.Foreground)
            }
            until { root.identity.currentState().status == GateStatus.ClientHome }
            main { root.refresh(); root.profile.attach(2) }; until { !root.profile.currentState().busy && root.profile.currentState().baseline != null }
            assertEquals(initial, root.profile.currentState().baseline)
            assertNull(client.invitations.pending(uid))
            main { val s = root.profile.currentState(); root.profile.onEvent(s.scope!!, s.visit, s.promptId, ProfileEvent.Leave(ProfileExit.Back)) }
            val current = runBlocking { root.nextNavigation() }
            main {
                assertFalse(root.profile.consumeNavigation(dequeued))
                assertTrue(root.profile.consumeNavigation(current))
                assertFalse(root.profile.consumeNavigation(current))
                root.clear(); assertFalse(root.profile.consumeNavigation(current))
                root = NativeProfileModels(client)
            }
            until { root.identity.currentState().status == GateStatus.ClientHome }
            main { root.refresh(); root.profile.attach(3) }; until { !root.profile.currentState().busy && root.profile.currentState().baseline != null }
            assertEquals(initial, root.profile.currentState().baseline)
            main { root.identity.onEvent(IdentityEvent.SignOut) }; until { root.identity.currentState().status == GateStatus.SignedOut }
            main { root.clear() }; return
        }
        if (phase.endsWith("stage")) {
            main { root.profile.attach(1) }; until { !root.profile.currentState().busy && root.profile.currentState().baseline != null }
            val held = AtomicReference<() -> Unit>()
            client.beforeInvitationCompletionForTest = { held.set(it) }
            if (phase == "stage") main {
                val owner = root.profile.currentState().scope!!
                root.profile.onEvent(owner, 1, 0, ProfileEvent.Name("Recovered Android"))
                root.profile.onEvent(owner, 1, 0, ProfileEvent.Locale(if (root.profile.currentState().locale == "en") "bs-BA" else "en"))
                root.profile.onEvent(owner, 1, 0, ProfileEvent.Save)
            } else client.invitations.execute(uid, InvitationRequest("update-profile", workspaceId = wid,
                idempotencyKey = "native-conflict-original", profileUpdate = ProfileUpdate("Stale native", "en", "UTC", 1))) {}
            until { held.get() != null }
            assertEquals("update-profile", client.invitations.pending(uid)?.action)
            main { root.identity.onEvent(IdentityEvent.SignOut) }; until { root.identity.currentState().status in setOf(GateStatus.DepartureBlocked, GateStatus.LockedRecovery) }
            assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION, await<StableFirebaseFailure?> {
                client.signIn("m5p3-android-trainer@example.invalid", password, 0, it)
            }?.code)
            println("M5P3_ANDROID_RESPONSE_HELD")
            CountDownLatch(1).await(120, TimeUnit.SECONDS); error("Host must terminate held process")
        }
        val original = client.invitations.pending(uid)!!
        assertTrue(original.profileUpdate!!.expectedRevision >= 1)
        main { root.identity.onEvent(IdentityEvent.SignOut) }; until { root.identity.currentState().status in setOf(GateStatus.DepartureBlocked, GateStatus.LockedRecovery) }
        assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION, await<StableFirebaseFailure?> {
            client.signIn("m5p3-android-trainer@example.invalid", password, 0, it)
        }?.code)
        main { root.refresh() }
        client.beforeInvitationTransportForTest = { issue -> assertEquals(original, client.invitations.pending(uid)); client.beforeInvitationTransportForTest = null; issue() }
        main { val s = root.profile.currentState(); root.profile.onEvent(s.scope!!, s.visit, s.promptId, ProfileEvent.RetryOriginal) }
        until { !root.profile.currentState().busy && client.invitations.pending(uid) == null }
        assertEquals(phase == "resume", root.profile.currentState().saved)
        assertEquals(phase == "conflict-resume", root.profile.currentState().conflict)
        main { root.identity.onEvent(IdentityEvent.Retry) }; until { root.identity.currentState().status == GateStatus.ClientHome }
        main { root.refresh(); root.profile.attach(2) }; until { !root.profile.currentState().busy && root.profile.currentState().baseline != null }
        assertEquals("Recovered Android", root.profile.currentState().name)
        if (phase == "resume") assertEquals(original.profileUpdate!!.expectedRevision + 1, root.profile.currentState().baseline!!.revision)
        else assertTrue(root.profile.currentState().baseline!!.revision > original.profileUpdate!!.expectedRevision)
        main { root.clear(); root = NativeProfileModels(client) }; until { root.identity.currentState().status == GateStatus.ClientHome }
        main { root.refresh(); root.profile.attach(3) }; until { !root.profile.currentState().busy && root.profile.currentState().baseline != null }
        assertEquals("Recovered Android", root.profile.currentState().name)
        main { root.identity.onEvent(IdentityEvent.SignOut) }; until { root.identity.currentState().status == GateStatus.SignedOut }
        val trainerClient = root.identity.activeClient()
        main { root.clear() }
        assertNull(await<StableFirebaseFailure?> { trainerClient.signIn("m5p3-android-trainer@example.invalid", password, 0, it) })
        main { root = NativeProfileModels(trainerClient) }; until { root.identity.currentState().status == GateStatus.TrainerHome }
        val detail = await<ClientResult> { trainerClient.clients!!.read(ClientScope("m5p3_android_trainer", wid, "m5p3_android_trainer", root.identity.currentState().authorityEpoch),
            ClientRequest("get", wid, clientId = uid), it) }
        assertNull(detail.error); assertEquals("Recovered Android", detail.detail!!.displayName)
        main { root.identity.onEvent(IdentityEvent.SignOut) }; until { root.identity.currentState().status == GateStatus.SignedOut }
        main { root.clear() }
    }
}
