package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState
import com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope
import com.delminiusapps.tillfailure.persistence.AccountPersistencePrototype
import kotlin.test.*

class InvitationTest {
    private val link = InvitationLink("a".repeat(64), 1, "b".repeat(43))
    private fun gate(uid: String = "A", status: GateStatus = GateStatus.WorkspaceGate) = IdentityState(status = status, accountUid = uid)

    @Test fun malformedOrMismatchedCallableSuccessRetainsProtectedAcceptanceForRetry() {
        val request = InvitationRequest("accept", invitationId = "a".repeat(64), token = "b".repeat(43), expectedVersion = 1, idempotencyKey = "original")
        val valid = InvitationReply(invitationId = request.invitationId, workspaceId = "w", status = "accepted", tokenVersion = 1, expiresAtMillis = 9, replayed = true)
        val store = MemoryStore().apply { record = AccountPersistenceEnvelope(uid = "A") }
        val ledger = InvitationLedger(store)
        assertTrue(ledger.prepare("A", request))
        val malformed = listOf("{}", "{\"status\":\"accepted\"}") + listOf(
            valid.copy(invitationId = "c".repeat(64)), valid.copy(status = "pending"), valid.copy(tokenVersion = 2),
            valid.copy(deliveryToken = "d".repeat(43)), valid.copy(replayed = null), valid.copy(expiresAtMillis = -1)
        ).map { InvitationJson.format.encodeToString(InvitationReply.serializer(), it) }
        for (body in malformed) {
            val outcome = InvitationJson.outcome(request, body, null, false)
            assertTrue(outcome.ambiguous)
            assertNull(ledger.settle("A", request, outcome).reply)
            assertNotNull(ledger.pending("A"))
        }
        val success = InvitationJson.outcome(request, InvitationJson.format.encodeToString(InvitationReply.serializer(), valid), null, false)
        assertFalse(success.ambiguous)
        ledger.settle("A", request, success)
        assertNull(ledger.pending("A"))
    }

    @Test fun callableRequiresConsistentHttpStatusAndSingleAllowlistedEnvelope() {
        val request = InvitationRequest("accept", invitationId = "a".repeat(64), expectedVersion = 1, idempotencyKey = "key")
        val body = "{\"error\":{\"status\":\"FAILED_PRECONDITION\",\"message\":\"expired\"}}"
        assertEquals("expired", InvitationJson.callable(request, 400, body).error)
        assertFalse(InvitationJson.callable(request, 400, body).ambiguous)
        for ((status, invalid) in listOf(200 to body, 503 to body, 400 to body.replace("expired", "unexpected"),
            200 to "{\"result\":{},\"error\":{}}", 200 to "broken", 200 to "[]")) {
            assertTrue(InvitationJson.callable(request, status, invalid).ambiguous)
        }
    }

    @Test fun settlementNotificationsAreUidBoundAndCancelledObserversStaySilent() {
        val changes = InvitationChanges()
        var a = 0; var b = 0
        val old = changes.observe("A") { a++ }
        changes.observe("B") { b++ }
        changes.notify("A"); assertEquals(1, a); assertEquals(0, b)
        old.cancel(); changes.notify("A"); assertEquals(1, a)
        changes.notify("B"); assertEquals(1, b)
    }

    @Test fun entryIsBoundedAndTokensAreExcludedFromDiagnosticsAndSafeCommands() {
        assertNotNull(InvitationLink.parse(link.entry()))
        for (invalid in listOf(link.entry().replace("https", "http"), link.entry() + "?x=1", link.entry().replace(".invalid", ".com"),
            link.entry().replace("/1#", "/0#"), "x".repeat(513))) assertNull(InvitationLink.parse(invalid))
        val request = InvitationRequest("accept", invitationId = link.invitationId, token = link.token, expectedVersion = 1, idempotencyKey = "key")
        assertFalse(request.toString().contains(link.token))
        assertFalse(request.pending("A").toString().contains(link.token))
        assertFalse(link.toString().contains(link.token))
    }

    @Test fun signInAndEmailVerificationContinuationRetainsOnlyTheTransientLink() {
        val client = FakeClient(); val model = InvitationViewModel(client)
        model.updateIdentity(IdentityState(status = GateStatus.SignedOut))
        model.onEvent(InvitationEvent.Entry(link.entry())); model.onEvent(InvitationEvent.Open)
        assertTrue(model.state.value.hasLink); assertTrue(client.calls.isEmpty())
        assertEquals("", model.state.value.entry)
        model.updateIdentity(gate(status = GateStatus.UnverifiedEmail)); model.onEvent(InvitationEvent.Preview)
        assertTrue(client.calls.isEmpty()); assertNotNull(client.memory.link)
        model.updateIdentity(gate()); model.onEvent(InvitationEvent.Preview)
        assertEquals("preview", client.calls.single().action)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(workspaceName = "Workspace")))
        assertEquals("Workspace", model.state.value.workspaceName)
        model.onEvent(InvitationEvent.Accept); assertNotNull(model.state.value.confirmation)
        assertEquals(1, client.calls.size)
        model.onEvent(InvitationEvent.Cancel); assertEquals(1, client.calls.size)
    }

    @Test fun ambiguousAcceptanceRetriesSameCommandAndResponseDoesNotGrantRole() {
        val client = FakeClient(); val model = InvitationViewModel(client)
        model.updateIdentity(gate()); model.onEvent(InvitationEvent.Entry(link.entry())); model.onEvent(InvitationEvent.Open)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(workspaceName = "Workspace")))
        model.onEvent(InvitationEvent.Accept); model.onEvent(InvitationEvent.Confirm)
        val original = client.calls.last(); assertEquals("accept", original.action)
        client.callbacks.last()(InvitationOutcome(error = "unavailable", ambiguous = true))
        assertNotNull(model.state.value.pending)
        model.onEvent(InvitationEvent.RetryCommand); assertEquals(original, client.calls.last())
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(status = "accepted")))
        assertNull(model.state.value.pending); assertNull(client.memory.link)
        assertNull(model.state.value.workspaceName)
        // The invitation model emits a recheck request, never writes the identity gate's role.
        assertNull(gate().verifiedRole)
    }

    @Test fun staleCallbacksAndAccountACompletionCannotReachAccountB() {
        val client = FakeClient(); val model = InvitationViewModel(client)
        model.updateIdentity(gate()); model.onEvent(InvitationEvent.Entry(link.entry())); model.onEvent(InvitationEvent.Open)
        val delayedPreview = client.callbacks.last()
        model.updateIdentity(gate("B")); delayedPreview(InvitationOutcome(reply = InvitationReply(workspaceName = "A private workspace")))
        assertNull(model.state.value.workspaceName); assertTrue(model.state.value.summaries.isEmpty())
        model.onEvent(InvitationEvent.Preview)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(workspaceName = "B workspace")))
        model.onEvent(InvitationEvent.Accept); model.onEvent(InvitationEvent.Confirm)
        val delayedAccept = client.callbacks.last()
        model.updateIdentity(gate("A")); delayedAccept(InvitationOutcome(reply = InvitationReply(status = "accepted")))
        assertNotNull(client.memory.link); assertFalse(model.state.value.message.contains("accepted"))
    }

    @Test fun revocationDropsTrainerProjectionAndConfirmationWhileLinkRemainsAuthContinuation() {
        val client = FakeClient(); val model = InvitationViewModel(client)
        model.updateIdentity(gate(status = GateStatus.TrainerHome).copy(verifiedRole = "trainer", verifiedWorkspaceId = "w"))
        model.onEvent(InvitationEvent.ListInvitations)
        val summary = InvitationSummary("a".repeat(64), "a***@example.invalid", "client", "pending", 9, 1, 1, 1)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(summaries = listOf(summary))))
        model.onEvent(InvitationEvent.Resend(summary)); assertNotNull(model.state.value.confirmation)
        model.updateIdentity(gate(status = GateStatus.AccessLost))
        assertNull(model.state.value.confirmation); assertTrue(model.state.value.summaries.isEmpty())
        model.onEvent(InvitationEvent.Confirm); assertEquals(1, client.calls.size)
    }

    @Test fun protectedLedgerBlocksDepartureAcrossRestartAndRejectsConflictingCommands() {
        val store = MemoryStore(); store.record = AccountPersistenceEnvelope(uid = "A")
        val ledger = InvitationLedger(store)
        val request = InvitationRequest("accept", invitationId = link.invitationId, token = link.token, expectedVersion = 1, idempotencyKey = "same-key")
        assertTrue(ledger.prepare("A", request)); assertTrue(store.record!!.pendingInvitationCommands.isNotEmpty())
        assertFalse(ledger.prepare("A", request.copy(idempotencyKey = "another")))
        ledger.settle("A", request, InvitationOutcome(error = "unavailable", ambiguous = true))
        val restored = InvitationLedger(store)
        assertEquals(request.pending("A"), restored.pending("A"))
        assertNull(restored.pending("B"))
        assertTrue(restored.prepare("A", request))
        restored.settle("A", request, InvitationOutcome(reply = InvitationReply(status = "accepted", replayed = true)))
        assertTrue(store.record!!.pendingInvitationCommands.isEmpty())
        store.fails = true
        assertFalse(restored.prepare("A", request))
    }

    @Test fun everyMutationRetainsPendingWorkAcrossRejectedRetriesUntilReceiptConfirmation() {
        for (action in listOf("create", "resend", "revoke", "accept")) {
            val store = MemoryStore().apply { record = AccountPersistenceEnvelope(uid = "A") }
            val ledger = InvitationLedger(store)
            val request = InvitationRequest(action, workspaceId = if (action == "accept") null else "w",
                invitationId = if (action == "create") null else link.invitationId,
                email = if (action == "create") "fixture@example.invalid" else null,
                token = if (action == "accept") link.token else null,
                expectedVersion = if (action == "create") null else 1, idempotencyKey = "original-$action")
            assertTrue(ledger.prepare("A", request))
            val original = store.record
            // Lost response, quota exhaustion while another invocation is still in flight,
            // transient infrastructure, genuine current authorization denial, conflicts.
            val failures = listOf(InvitationOutcome(error = "unavailable", ambiguous = true),
                InvitationJson.callable(request, 429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"rate-limited\"}}"),
                InvitationJson.outcome(request, null, "invitation-unavailable", true),
                InvitationJson.outcome(request, null, "unauthorized", false),
                InvitationJson.outcome(request, null, "fixture-account-required", false),
                InvitationJson.outcome(request, null, "idempotency-key-reused", false))
            for (failure in failures) {
                val settled = ledger.settle("A", request, failure)
                assertTrue(settled.ambiguous, "$action retry cannot prove no earlier commit")
                assertEquals(original, store.record)
                assertNotNull(ledger.pending("A"))
                // This is the persisted critical-work predicate consumed by both departure ports.
                assertFalse(store.record!!.pendingInvitationCommands.isEmpty())
                assertFalse(ledger.prepare("A", request.copy(idempotencyKey = "replacement")))
                assertTrue(ledger.prepare("A", request))
            }
            val reply = InvitationReply(invitationId = link.invitationId, workspaceId = "w",
                status = when(action) { "accept" -> "accepted"; "revoke" -> "revoked"; else -> "pending" },
                tokenVersion = if (action == "resend") 2 else 1, expiresAtMillis = 9, replayed = true)
            val verified = InvitationJson.outcome(request, InvitationJson.format.encodeToString(InvitationReply.serializer(), reply), null, false)
            assertFalse(verified.ambiguous)
            ledger.settle("A", request, verified)
            assertNull(ledger.pending("A"))
            assertTrue(store.record!!.pendingInvitationCommands.isEmpty())
        }
    }

    private class MemoryStore : AccountPersistencePrototype {
        var record: AccountPersistenceEnvelope? = null; var fails = false
        override fun write(envelope: AccountPersistenceEnvelope) { check(!fails); record = envelope }
        override fun read(uid: String): AccountPersistenceEnvelope? { check(!fails); return record?.takeIf { it.uid == uid } }
        override fun delete(uid: String) { record = null }
    }
    private class FakeClient : InvitationClient {
        override val memory = InvitationMemory()
        val calls = mutableListOf<InvitationRequest>()
        val callbacks = mutableListOf<(InvitationOutcome) -> Unit>()
        val pending = mutableMapOf<String, PendingInvitationCommand>()
        override fun pending(uid: String): PendingInvitationCommand? = pending[uid]
        override fun newCommandKey() = "key-${calls.size}"
        override fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation {
            calls += request
            request.pending(uid)?.let { pending[uid] = it }
            callbacks += { result -> if (!result.ambiguous) pending.remove(uid); callback(result) }
            return FirebaseCancellation {} // Deliberately still delivers to test the ViewModel's fence.
        }
    }
}
