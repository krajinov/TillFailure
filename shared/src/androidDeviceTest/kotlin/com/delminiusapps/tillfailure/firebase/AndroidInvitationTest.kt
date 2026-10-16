package com.delminiusapps.tillfailure.firebase

import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Fresh seed:pr3 fixture; actual Auth/Functions/Firestore SDKs and protected Android storage. */
class AndroidInvitationTest {
    @Test fun trainerRotatesRevokesThenClientAcceptsThroughFreshGateAndCleanDeparture() {
        val args = InstrumentationRegistry.getArguments()
        val password = args.getString("pr3Password") ?: error("Pass local pr3Password fixture")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var client: ProductIdentityClient = AndroidFirebaseSpikeClient(context, FirebaseEmulatorConfiguration(host = "10.0.2.2"), AccountCallbackFence(), productMemoryCache = true)
        assertNull(await<StableFirebaseFailure?> { client.signIn("pr3-android-trainer@example.invalid", password, 0, it) })
        var gate = IdentityViewModel(client)
        awaitGate(gate, GateStatus.TrainerHome)
        val trainer = client.invitations!!
        fun command(port: InvitationClient, uid: String, request: InvitationRequest): InvitationOutcome {
            val outcome: InvitationOutcome = await { port.execute(uid, request, it) }
            if (request.action != "preview") assertNull(outcome.error, "${request.action} failed; ambiguous=${outcome.ambiguous}")
            return outcome
        }
        val created = command(trainer, "pr3_android_trainer", InvitationRequest("create", workspaceId = "pr3_android_workspace",
            email = "pr3-android-client@example.invalid", idempotencyKey = "native-create")).reply!!
        val rotated = command(trainer, "pr3_android_trainer", InvitationRequest("resend", workspaceId = "pr3_android_workspace",
            invitationId = created.invitationId, expectedVersion = 1, idempotencyKey = "native-resend")).reply!!
        assertEquals(2, rotated.tokenVersion)
        assertEquals("revoked", command(trainer, "pr3_android_trainer", InvitationRequest("revoke", workspaceId = "pr3_android_workspace",
            invitationId = rotated.invitationId, expectedVersion = 2, idempotencyKey = "native-revoke")).reply?.status)
        val live = command(trainer, "pr3_android_trainer", InvitationRequest("create", workspaceId = "pr3_android_workspace",
            email = "pr3-android-client@example.invalid", idempotencyKey = "native-live")).reply!!
        val list = command(trainer, "pr3_android_trainer", InvitationRequest("list", workspaceId = "pr3_android_workspace")).reply!!
        assertEquals(2, list.summaries!!.size)
        assertFalse(list.toString().contains(live.deliveryToken!!))
        trainer.memory.open(InvitationLink(live.invitationId!!, live.tokenVersion!!, live.deliveryToken!!).entry())
        gate.onEvent(IdentityEvent.SignOut); awaitGate(gate, GateStatus.SignedOut)
        client = gate.activeClient()
        assertNotNull(client.invitations!!.memory.link)
        assertNull(await<StableFirebaseFailure?> { client.signIn("pr3-android-client@example.invalid", password, 0, it) })
        awaitGate(gate, GateStatus.NoAccount)
        val port = client.invitations!!
        assertEquals("rotated", command(port, "pr3_android_client", InvitationRequest("preview", invitationId = created.invitationId,
            expectedVersion = 1, token = created.deliveryToken)).error)
        assertEquals("revoked", command(port, "pr3_android_client", InvitationRequest("preview", invitationId = rotated.invitationId,
            expectedVersion = 2, token = rotated.deliveryToken)).error)
        assertEquals("Android Training", command(port, "pr3_android_client", InvitationRequest("preview", invitationId = live.invitationId,
            expectedVersion = 1, token = live.deliveryToken)).reply?.workspaceName)
        assertNull(gate.currentState().verifiedRole)
        val accept = InvitationRequest("accept", invitationId = live.invitationId, expectedVersion = 1, token = live.deliveryToken,
            idempotencyKey = "native-accept")
        // Durable pending command proves clean departure cannot erase an ambiguous acceptance.
        assertTrue(InvitationLedger(AndroidAtomicFilePersistence(context)).prepare("pr3_android_client", accept))
        gate.onEvent(IdentityEvent.SignOut); awaitGate(gate, GateStatus.DepartureBlocked)
        assertEquals("pr3_android_client", gate.currentState().accountUid)
        assertEquals("accepted", command(port, "pr3_android_client", accept).reply?.status)
        assertNull(gate.currentState().verifiedRole)
        assertTrue(command(port, "pr3_android_client", accept).reply!!.replayed!!)
        assertNull(port.pending("pr3_android_client"))
        gate.onEvent(IdentityEvent.Retry); awaitGate(gate, GateStatus.Onboarding)
        assertEquals("client", gate.currentState().verifiedRole)
        assertEquals("pr3_android_workspace", gate.currentState().verifiedWorkspaceId)
        gate.onEvent(IdentityEvent.SignOut); awaitGate(gate, GateStatus.SignedOut)
    }

    private fun awaitGate(model: IdentityViewModel, expected: GateStatus) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (model.currentState().status != expected && System.nanoTime() < until) Thread.sleep(50)
        assertEquals(expected, model.currentState().status)
    }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); val result = AtomicReference<T>()
        start { result.set(it); latch.countDown() }
        assertTrue(latch.await(40, TimeUnit.SECONDS), "Native callback timed out")
        return result.get()
    }
}
