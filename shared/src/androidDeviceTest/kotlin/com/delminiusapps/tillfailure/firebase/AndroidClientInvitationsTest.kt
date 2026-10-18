package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.clients.*
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real existing native transports, one invitation owner, and fresh server-authorized roster reads. */
class AndroidClientInvitationsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @Test fun connectedInvitationLifecycleNeverTreatsSummariesAsRosterAuthority() {
        val resume = InstrumentationRegistry.getArguments().getString("m5p2Resume") == "1"
        val password = requireNotNull(InstrumentationRegistry.getArguments().getString("m5p2Password"))
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("m5p2_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "m5p2-native").apply { mkdirs() }
        }
        var client: ProductIdentityClient = AndroidFirebaseSpikeClient(instrumentation.targetContext,
            FirebaseEmulatorConfiguration(host = "10.0.2.2", authPort = 29499, firestorePort = 28480, functionsPort = 25401),
            AccountCallbackFence(), productMemoryCache = true, overrideProductAppName = "m5p2-native-android", protectedContextForTest = context)
        lateinit var root: NativeClientsModels
        fun login(role: String, expected: GateStatus) {
            if (!client.hasPinnedIdentity()) assertNull(await<StableFirebaseFailure?> { client.signIn("m5p2-android-$role@example.invalid", password, 0, it) })
            main { root = NativeClientsModels(client) }; until { root.identity.currentState().status == expected }
            main { root.invitations.updateIdentity(root.identity.currentState()) }
        }
        fun leave() {
            main { root.identity.onEvent(IdentityEvent.SignOut) }; until { root.identity.currentState().status == GateStatus.SignedOut }
            client = root.identity.activeClient(); main { root.clear() }
        }
        fun scope() = ClientScope("m5p2_android_trainer", "m5p2_android_workspace", "m5p2_android_trainer", root.identity.currentState().authorityEpoch)
        fun roster(expected: Int) {
            main { root.roster.clear(); root.roster.bind(scope(), null) }; until { !root.roster.currentState().loading }
            assertNull(root.roster.currentState().error); assertEquals(expected, root.roster.currentState().items.size)
        }
        fun event(event: InvitationEvent) { main { root.invitations.onEvent(event) }; until { !root.invitations.currentState().busy } }
        fun list() = event(InvitationEvent.ListInvitations)
        fun create(role: String) {
            main { root.invitations.enterTrainer(InvitationEntry.Invite) }
            event(InvitationEvent.Email("m5p2-android-$role@example.invalid")); event(InvitationEvent.Create)
            assertNull(root.invitations.currentState().pending); assertNotNull(root.invitations.currentState().deliveryLink)
            assertTrue(root.invitations.currentState().listLoaded)
        }
        login("trainer", GateStatus.TrainerHome); roster(0)
        main { root.invitations.enterTrainer(InvitationEntry.Manage) }; until { !root.invitations.currentState().busy }
        assertEquals(if (resume) 1 else 0, root.invitations.currentState().summaries.size)
        if (!resume) create("revoke")
        roster(0); list()
        var summary = root.invitations.currentState().summaries.single()
        event(InvitationEvent.Resend(summary)); assertNotNull(root.invitations.currentState().confirmation)
        event(InvitationEvent.Confirm); list(); summary = root.invitations.currentState().summaries.single()
        assertEquals(2, summary.tokenVersion)
        event(InvitationEvent.Revoke(summary)); event(InvitationEvent.Confirm)
        assertEquals("revoked", root.invitations.currentState().summaries.single().status); roster(0)
        create("accept"); roster(0); event(InvitationEvent.UseDelivery)
        val denied: InvitationOutcome = await { client.invitations!!.execute("m5p2_android_trainer", InvitationRequest("list", workspaceId = "m5p2_ios_workspace"), it) }
        assertEquals("unauthorized", denied.error)
        leave(); login("accept", GateStatus.NoAccount)
        event(InvitationEvent.Preview); assertNotNull(root.invitations.currentState().workspaceName)
        event(InvitationEvent.Accept); assertNotNull(root.invitations.currentState().confirmation)
        event(InvitationEvent.Confirm); assertNull(root.invitations.currentState().pending)
        main { root.identity.onEvent(IdentityEvent.Retry) }; until { root.identity.currentState().status == GateStatus.Onboarding }
        leave(); login("trainer", GateStatus.TrainerHome); roster(1)
        assertEquals("m5p2_android_accept", root.roster.currentState().items.single().clientId)
        assertNull(root.roster.currentState().items.single().displayName)
        list(); assertEquals(setOf("accepted", "revoked"), root.invitations.currentState().summaries.map { it.status }.toSet())
        main { root.clear(); root = NativeClientsModels(client) }; until { root.identity.currentState().status == GateStatus.TrainerHome }
        assertTrue(root.roster.currentState().items.isEmpty())
        main { root.invitations.updateIdentity(root.identity.currentState()) }; roster(1)
        leave()
    }
    private fun until(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(ready(), "Connected native flow timed out")
    }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); val result = AtomicReference<T>()
        start { result.set(it); latch.countDown() }; assertTrue(latch.await(40, TimeUnit.SECONDS)); return result.get()
    }
}
