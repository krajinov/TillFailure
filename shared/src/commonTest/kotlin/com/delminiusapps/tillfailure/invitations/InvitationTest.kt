package com.delminiusapps.tillfailure.invitations

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

    private fun ownedModel(client: FakeClient): Pair<ViewModelStore, InvitationViewModel> {
        val store = ViewModelStore()
        val provider = ViewModelProvider.create(store, viewModelFactory { initializer { InvitationViewModel(client) } })
        return store to provider[InvitationViewModel::class]
    }
    private fun unknownAcceptance(client: FakeClient, model: InvitationViewModel): InvitationRequest {
        model.updateIdentity(gate())
        model.onEvent(InvitationEvent.Entry(link.entry())); model.onEvent(InvitationEvent.Open)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(workspaceName = "Workspace")))
        model.onEvent(InvitationEvent.Accept); model.onEvent(InvitationEvent.Confirm)
        val original = client.calls.last()
        client.callbacks.last()(InvitationOutcome(error = "unavailable", ambiguous = true))
        assertNotNull(model.currentState().pending)
        return original
    }

    @Test fun pendingAcceptanceRetainsOriginalAcrossEntryAttemptsAndSameCommandRecovery() {
        val client = FakeClient(); val (owner, model) = ownedModel(client)
        val original = unknownAcceptance(client, model)
        val retained = client.memory.link!!
        val pending = model.currentState().pending
        val revision = client.memory.revision
        val candidates = listOf(InvitationLink("c".repeat(64), 1, "d".repeat(43)).entry(),
            InvitationLink(link.invitationId, 2, "d".repeat(43)).entry(), "invalid-entry",
            InvitationLink(link.invitationId, 1, "d".repeat(43)).entry())
        for (candidate in candidates) {
            model.onEvent(InvitationEvent.Entry(candidate)); model.onEvent(InvitationEvent.Open)
            assertTrue(client.memory.link === retained, "A rejected candidate must preserve the original bearer")
            assertEquals(revision, client.memory.revision)
            assertEquals(pending, model.currentState().pending)
            assertEquals(original.pending("A"), client.pending("A"))
            assertEquals(2, client.calls.size, "Rejected entries must not issue transport")
            assertTrue(model.currentState().hasLink)
            assertNull(model.currentState().workspaceName)
            assertEquals("", model.currentState().entry)
        }
        model.onEvent(InvitationEvent.PopulateEntry)
        assertTrue(model.currentState().entry == retained.entry())
        model.onEvent(InvitationEvent.Open)
        model.onEvent(InvitationEvent.RetryCommand)
        assertEquals(original, client.calls.last())
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(status = "accepted")))
        assertNull(client.pending("A")); assertNull(client.memory.link)
        assertNull(model.currentState().pending)
        owner.clear()
    }

    @Test fun rootRecreationRetainsOriginalAndDisposedAccountCallbacksCannotReplaceIt() {
        val client = FakeClient(); val (oldOwner, old) = ownedModel(client)
        val original = unknownAcceptance(client, old)
        val retained = client.memory.link!!
        oldOwner.clear()
        val (owner, recreated) = ownedModel(client)
        recreated.updateIdentity(gate())
        assertTrue(client.memory.link === retained)
        val b = InvitationLink("c".repeat(64), 1, "d".repeat(43)).entry()
        old.onEvent(InvitationEvent.Entry(b)); old.onEvent(InvitationEvent.Open)
        old.updateIdentity(gate("B"))
        recreated.updateIdentity(gate("B"))
        assertFalse(recreated.currentState().hasLink)
        recreated.onEvent(InvitationEvent.PopulateEntry)
        assertEquals("", recreated.currentState().entry)
        recreated.onEvent(InvitationEvent.Entry(b)); recreated.onEvent(InvitationEvent.Open)
        assertTrue(client.memory.link === retained)
        assertNull(client.memory.linkFor("B"))
        assertFalse(client.memory.clear("B"))
        recreated.updateIdentity(gate())
        recreated.onEvent(InvitationEvent.RetryCommand)
        assertEquals(original, client.calls.last())
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(status = "accepted")))
        assertNull(recreated.currentState().pending)
        owner.clear()
    }

    @Test fun processRestartHasOnlySafeMetadataAndRequiresExplicitOriginalEntry() {
        val client = FakeClient(); val (owner, model) = ownedModel(client)
        val original = unknownAcceptance(client, model)
        val safe = client.pending("A")!!
        val serialized = InvitationJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),
            AccountPersistenceEnvelope(uid = "A", pendingInvitationCommands = listOf(safe)))
        assertFalse(serialized.contains(link.token))
        assertFalse(serialized.contains("https://invite"))
        assertFalse(model.currentState().toString().contains(link.token))
        assertFalse(InvitationEvent.Entry(link.entry()).toString().contains(link.token))
        owner.clear()
        val restored = FakeClient().apply { pending["A"] = safe }
        val (newOwner, root) = ownedModel(restored)
        root.updateIdentity(gate())
        assertNull(restored.memory.link)
        root.onEvent(InvitationEvent.RetryCommand)
        assertTrue(restored.calls.isEmpty())
        root.onEvent(InvitationEvent.Entry(InvitationLink("c".repeat(64), 1, "d".repeat(43)).entry()))
        root.onEvent(InvitationEvent.Open)
        assertNull(restored.memory.link); assertEquals(safe, root.currentState().pending)
        root.onEvent(InvitationEvent.Entry(link.entry())); root.onEvent(InvitationEvent.Open)
        root.onEvent(InvitationEvent.RetryCommand)
        assertEquals(original, restored.calls.single())
        restored.callbacks.last()(InvitationOutcome(reply = InvitationReply(status = "accepted")))
        assertNull(root.currentState().pending); assertNull(restored.memory.link)
        newOwner.clear()
    }

    @Test fun metadataRestorationDoesNotBlessAnEntryOpenedBeforeSignIn() {
        val original = InvitationRequest("accept", invitationId = link.invitationId, token = link.token,
            expectedVersion = link.version, idempotencyKey = "original-command")
        val pending = original.pending("A")!!
        val client = FakeClient().apply { this.pending["A"] = pending }
        val (owner, root) = ownedModel(client)
        val forged = InvitationLink(link.invitationId, link.version, "d".repeat(43))
        root.onEvent(InvitationEvent.Entry(forged.entry())); root.onEvent(InvitationEvent.Open)
        assertTrue(client.memory.link?.token == forged.token)
        assertTrue(client.calls.isEmpty())
        root.updateIdentity(gate())
        assertNull(client.memory.link, "Restored metadata must not bless a pre-sign-in bearer")
        assertFalse(root.currentState().hasLink)
        assertEquals(pending, root.currentState().pending)
        root.onEvent(InvitationEvent.RetryCommand)
        assertTrue(client.calls.isEmpty(), "The unverified pre-sign-in entry must never reach transport")
        assertEquals(pending, client.pending("A"))
        root.onEvent(InvitationEvent.Entry(link.entry())); root.onEvent(InvitationEvent.Open)
        root.onEvent(InvitationEvent.RetryCommand)
        assertEquals(original, client.calls.single())
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(status = "accepted")))
        assertNull(client.pending("A")); assertNull(client.memory.link)
        owner.clear()
    }

    @Test fun metadataOnlyProtectionClearsAllUnverifiedEntriesButPreservesKnownIssuance() {
        val command = PendingInvitationCommand("A", "accept", "original", invitationId = link.invitationId, expectedVersion = link.version)
        val candidates = listOf(link, InvitationLink(link.invitationId, link.version, "d".repeat(43)),
            InvitationLink("c".repeat(64), link.version, "d".repeat(43)))
        for (candidate in candidates) {
            val memory = InvitationMemory()
            assertTrue(memory.open(candidate.entry()))
            val revision = memory.revision
            assertTrue(memory.protectAcceptance(command))
            assertNull(memory.link)
            assertEquals(revision + 1, memory.revision)
            assertFalse(memory.open(link.entry(), "B"))
            assertTrue(memory.open(link.entry(), "A"))
            val original = memory.link
            val protectedRevision = memory.revision
            assertTrue(memory.protectAcceptance(command))
            assertTrue(memory.link === original)
            assertEquals(protectedRevision, memory.revision)
        }
        val issued = InvitationMemory()
        assertTrue(issued.open(candidates[1].entry()))
        assertTrue(issued.protectAcceptance(command, link.token))
        val original = issued.link
        assertTrue(original?.token == link.token)
        assertTrue(issued.protectAcceptance(command))
        assertTrue(issued.link === original)
        assertFalse(issued.open(candidates[1].entry(), "A"))
        assertFalse(issued.protectAcceptance(command.copy(uid = "B")))
        assertTrue(issued.link === original)
    }

    @Test fun staleLocalDeliveryCannotDisplaceAnotherRootsPendingAcceptance() {
        val client = FakeClient(); val (deliveryOwner, delivery) = ownedModel(client)
        delivery.updateIdentity(gate(status = GateStatus.TrainerHome).copy(verifiedRole = "trainer", verifiedWorkspaceId = "w"))
        delivery.onEvent(InvitationEvent.Email("fixture@example.invalid")); delivery.onEvent(InvitationEvent.Create)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(invitationId = "c".repeat(64), tokenVersion = 1, deliveryToken = "d".repeat(43))))
        assertNotNull(delivery.currentState().deliveryLink)
        val (owner, model) = ownedModel(client)
        val original = unknownAcceptance(client, model)
        val retained = client.memory.link
        val revision = client.memory.revision
        delivery.onEvent(InvitationEvent.UseDelivery)
        assertTrue(client.memory.link === retained)
        assertEquals(revision, client.memory.revision)
        assertEquals(original.pending("A"), client.pending("A"))
        assertTrue(delivery.currentState().message.contains("pending acceptance"))
        assertNotNull(delivery.currentState().deliveryLink)
        model.onEvent(InvitationEvent.RetryCommand)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(status = "accepted")))
        deliveryOwner.clear(); owner.clear()
    }

    @Test fun allMemoryContinuationPathsRespectPendingOwnerAndUnreadableLedger() {
        val memory = InvitationMemory()
        assertTrue(memory.open(link.entry()))
        val request = InvitationRequest("accept", invitationId = link.invitationId, token = link.token,
            expectedVersion = 1, idempotencyKey = "original")
        assertTrue(memory.protectAcceptance(request.pending("A")!!, request.token))
        val retained = memory.link
        assertFalse(memory.open(InvitationLink("c".repeat(64), 1, "d".repeat(43)).entry(), "A"))
        assertFalse(memory.open(link.entry(), "B"))
        assertFalse(memory.protectAcceptance(request.pending("B")!!, request.token))
        memory.consume("B", InvitationReply(invitationId = link.invitationId, status = "accepted", tokenVersion = 1))
        assertTrue(memory.link === retained)
        val client = FakeClient(); val (owner, model) = ownedModel(client)
        unknownAcceptance(client, model)
        client.unreadable = true
        val known = client.memory.link
        model.onEvent(InvitationEvent.Entry(link.entry())); model.onEvent(InvitationEvent.Open)
        assertTrue(client.memory.link === known)
        assertNotNull(model.currentState().pending)
        assertTrue(model.currentState().message.contains("unavailable"))
        owner.clear()
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
        var unreadable = false
        override fun pending(uid: String): PendingInvitationCommand? { check(!unreadable); return pending[uid] }
        override fun newCommandKey() = "key-${calls.size}"
        override fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation {
            calls += request
            request.pending(uid)?.let {
                pending[uid] = it
                if (request.action == "accept") check(memory.protectAcceptance(it, request.token))
            }
            callbacks += { result -> if (!result.ambiguous) pending.remove(uid); callback(result) }
            return FirebaseCancellation {} // Deliberately still delivers to test the ViewModel's fence.
        }
    }
}
