package com.delminiusapps.tillfailure.clients

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import app.cash.turbine.test
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClientInvitationsTest {
    private val gate = IdentityState(status = GateStatus.TrainerHome, accountUid = "trainer", verifiedRole = "trainer", verifiedWorkspaceId = "w", authorityEpoch = 7)
    private val owner = ShellOwner("trainer", "w", "trainer", "trainer", 7)
    private val summary = InvitationSummary("a".repeat(64), "c***@example.invalid", "client", "pending", 9000, 1, 1, 1)
    private class Client : InvitationClient {
        override val memory = InvitationMemory()
        val calls = mutableListOf<InvitationRequest>()
        val callbacks = mutableListOf<(InvitationOutcome) -> Unit>()
        val pending = mutableMapOf<String, PendingInvitationCommand>()
        var keys = 0; var cancelled = 0
        override fun pending(uid: String) = pending[uid]
        override fun newCommandKey() = "original-${++keys}"
        override fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation {
            calls += request; request.pending(uid)?.let { pending[uid] = it }
            callbacks += { result -> if (result.reply != null && !result.ambiguous) pending.remove(uid); callback(result) }
            return FirebaseCancellation { cancelled++ }
        }
    }
    private fun model(c: Client) = InvitationViewModel(c).also { it.updateIdentity(gate) }
    private fun finishList(c: Client, rows: List<InvitationSummary> = listOf(summary)) = c.callbacks.last()(InvitationOutcome(reply = InvitationReply(summaries = rows)))
    @BeforeTest fun dispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun reset() { Dispatchers.resetMain() }

    @Test fun clientsInviteConfirmationAndReturnAreOwnedAndDuplicateSafe() = runTest {
        val stack = mutableListOf<NavKey>(ShellDestination(ShellPage.Home, owner = owner), ShellDestination(ShellPage.Clients, owner = owner))
        openClientInvitations(stack, owner, InvitationEntry.Invite)
        val destination = stack.last() as ShellDestination
        openClientInvitations(stack, owner, InvitationEntry.Manage)
        assertEquals(3, stack.size); assertEquals(InvitationEntry.Invite, destination.invitationEntry)
        val client = Client(); val vm = model(client); vm.enterTrainer(destination.invitationEntry!!)
        vm.onEvent(InvitationEvent.Email("client@example.invalid")); vm.onEvent(InvitationEvent.Create); vm.onEvent(InvitationEvent.Create)
        assertEquals(1, client.calls.size)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(invitationId = summary.invitationId, deliveryToken = "b".repeat(43), tokenVersion = 1)))
        assertEquals("list", client.calls.last().action); assertTrue(vm.state.value.busy); finishList(client)
        assertEquals(TrainerInvitationPage.Result, vm.state.value.trainerPage); assertNotNull(vm.state.value.deliveryLink)
        val confirmedCalls = client.calls.size
        vm.onEvent(InvitationEvent.Email("client@example.invalid")); vm.onEvent(InvitationEvent.Create)
        assertEquals(confirmedCalls, client.calls.size) // Queued input from the retired form cannot create another command.
        vm.leaveTrainer("trainer", "w", 7)
        backFromInvitations(stack, destination); backFromInvitations(stack, destination)
        assertEquals(ShellPage.Clients, (stack.last() as ShellDestination).page); assertEquals(2, stack.size)
        assertEquals(destination.refreshVersion + 1, (stack.last() as ShellDestination).refreshVersion)
        assertNull(vm.state.value.deliveryLink); assertTrue(vm.state.value.email.isEmpty())
        openClientInvitations(stack, owner.copy(uid = "other"), InvitationEntry.Invite); assertEquals(2, stack.size)
    }
    @Test fun resendRevokeUseOneConfirmationThenReloadSafeSummaries() {
        val client = Client(); val vm = model(client); vm.enterTrainer(InvitationEntry.Manage); finishList(client)
        vm.onEvent(InvitationEvent.Resend(summary)); val confirmation = vm.state.value.confirmation
        vm.onEvent(InvitationEvent.Revoke(summary)); assertEquals(confirmation, vm.state.value.confirmation); assertEquals(1, client.keys)
        vm.onPresentationEvent("trainer", "w", 7, "retired-dialog", InvitationEvent.Confirm)
        assertEquals(1, client.calls.size); assertEquals(confirmation, vm.state.value.confirmation)
        vm.onPresentationEvent("trainer", "w", 6, confirmation?.idempotencyKey, InvitationEvent.Confirm)
        assertEquals(1, client.calls.size)
        vm.onPresentationEvent("trainer", "w", 7, confirmation?.idempotencyKey, InvitationEvent.Confirm); vm.onEvent(InvitationEvent.Confirm)
        assertEquals(listOf("list", "resend"), client.calls.map { it.action })
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(invitationId = summary.invitationId, tokenVersion = 2, status = "pending")))
        assertEquals("list", client.calls.last().action); assertTrue(vm.state.value.summaries.isEmpty())
        val current = summary.copy(tokenVersion = 2); finishList(client, listOf(current))
        vm.onEvent(InvitationEvent.Revoke(summary)); assertNull(vm.state.value.confirmation)
        vm.onEvent(InvitationEvent.Revoke(current)); vm.onEvent(InvitationEvent.Confirm)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(status = "revoked")))
        finishList(client, listOf(current.copy(status = "revoked")))
        assertEquals("revoked", vm.state.value.summaries.single().status); assertNull(vm.state.value.pending)
    }
    @Test fun leavingUnknownMutationRetainsOriginalRecoveryAfterScopeLossAndRecreation() {
        val client = Client(); val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { InvitationViewModel(client) } })[InvitationViewModel::class]
        vm.updateIdentity(gate); vm.enterTrainer(InvitationEntry.Invite)
        vm.onEvent(InvitationEvent.Email("client@example.invalid")); vm.onEvent(InvitationEvent.Create)
        val command = client.calls.last(); client.callbacks.last()(InvitationOutcome(error = "unavailable", ambiguous = true))
        assertEquals(command.pending("trainer"), vm.state.value.pending)
        vm.leaveTrainer("trainer", "w", 7); assertEquals(command.pending("trainer"), client.pending("trainer"))
        store.clear(); val next = model(client)
        next.updateIdentity(gate.copy(status = GateStatus.LockedRecovery, verifiedWorkspaceId = null))
        next.onPresentationEvent("trainer", null, 7, null, InvitationEvent.RetryCommand); assertEquals(command, client.calls.last())
        client.callbacks.last()(InvitationOutcome(error = "unauthorized", ambiguous = true))
        assertEquals(command.pending("trainer"), next.state.value.pending); assertTrue(next.state.value.message.contains("protected"))
        next.onEvent(InvitationEvent.Create); assertEquals(command, client.calls.last())
    }
    @Test fun accountWorkspaceEpochAndDepartureFenceOpenFormsListsAndLateReplies() {
        for (next in listOf(gate.copy(accountUid = "B"), gate.copy(verifiedWorkspaceId = "other"), gate.copy(authorityEpoch = 8), gate.copy(status = GateStatus.AccessLost))) {
            val client = Client(); val vm = model(client); vm.enterTrainer(InvitationEntry.Manage); finishList(client)
            vm.onEvent(InvitationEvent.Resend(summary)); vm.onEvent(InvitationEvent.Confirm)
            val callback = client.callbacks.last(); vm.updateIdentity(next)
            callback(InvitationOutcome(reply = InvitationReply(invitationId = summary.invitationId, tokenVersion = 2, deliveryToken = "b".repeat(43))))
            assertTrue(vm.state.value.summaries.isEmpty()); assertNull(vm.state.value.confirmation); assertNull(vm.state.value.deliveryLink)
            vm.onPresentationEvent("trainer", "w", 7, null, InvitationEvent.Email("retired@example.invalid"))
            assertTrue(vm.state.value.email.isEmpty())
            vm.onPresentationEvent("trainer", "w", 7, null, InvitationEvent.Confirm)
            vm.leaveTrainer("trainer", "w", 7); assertEquals(next.accountUid, vm.state.value.accountUid)
        }
    }
    @Test fun leavingListCancelsProjectionAndUnavailableDoesNotClaimEmptySuccess() {
        val client = Client(); val vm = model(client); vm.enterTrainer(InvitationEntry.Manage)
        val stale = client.callbacks.last(); vm.leaveTrainer("trainer", "w", 7)
        stale(InvitationOutcome(reply = InvitationReply(summaries = listOf(summary))))
        assertTrue(vm.state.value.summaries.isEmpty()); assertTrue(client.cancelled > 0)
        vm.enterTrainer(InvitationEntry.Manage); client.callbacks.last()(InvitationOutcome(error = "unauthorized"))
        assertTrue(vm.state.value.listFailed); assertFalse(vm.state.value.listLoaded)
        assertNull(vm.state.value.pending); assertTrue(vm.state.value.message.contains("workspace-owner"))
        vm.onEvent(InvitationEvent.ListInvitations); finishList(client, emptyList())
        assertTrue(vm.state.value.listLoaded); assertFalse(vm.state.value.listFailed)
    }
    @Test fun acceptedSummaryNeverInsertsRosterAndReturnRequiresFreshAuthorizedRead() = runTest {
        var reads = 0; var reply: ((ClientResult) -> Unit)? = null
        val repository = object : ClientRepository {
            override fun read(scope: ClientScope, request: ClientRequest, callback: (ClientResult) -> Unit): FirebaseCancellation {
                reads++; reply = callback; return FirebaseCancellation {}
            }
        }
        val roster = ClientsViewModel(repository); val scope = ClientScope("trainer", "w", "trainer", 7)
        roster.bind(scope, null); reply!!(ClientResult(page = ClientPage(emptyList(), null)))
        val client = Client(); val invites = model(client); invites.enterTrainer(InvitationEntry.Manage)
        finishList(client, listOf(summary, summary.copy(invitationId = "b".repeat(64), status = "revoked"), summary.copy(invitationId = "c".repeat(64), status = "accepted")))
        assertTrue(roster.state.value.items.isEmpty()); assertEquals(1, reads)
        roster.clear(); roster.bind(scope, null); assertEquals(2, reads); assertTrue(roster.state.value.loading)
        reply!!(ClientResult(page = ClientPage(listOf(ClientRecord("client", "w", null, "active", "pending")), null)))
        assertEquals("client", roster.state.value.items.single().clientId); roster.clear()
    }
    @Test fun duplicateClientsEntryQueuesOneEffectAndClearsWithAuthority() = runTest {
        val vm = ClientsViewModel(object : ClientRepository {
            override fun read(scope: ClientScope, request: ClientRequest, callback: (ClientResult) -> Unit) = FirebaseCancellation {}
        })
        vm.bind(ClientScope("trainer", "w", "trainer", 7), null)
        vm.onEvent(ClientsEvent.Invitations(true)); vm.onEvent(ClientsEvent.Invitations(false))
        vm.effect.test { assertEquals(ClientsEffect.Invitations(true), awaitItem()); expectNoEvents(); cancelAndIgnoreRemainingEvents() }
        vm.clear(); vm.onEvent(ClientsEvent.Invitations(true))
        vm.effect.test { expectNoEvents(); cancelAndIgnoreRemainingEvents() }
    }
    @Test fun disposedRootRejectsTrainerReentryAndClearsTransientPresentation() {
        val client = Client(); val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { InvitationViewModel(client) } })[InvitationViewModel::class]
        vm.updateIdentity(gate); vm.enterTrainer(InvitationEntry.Manage)
        val callback = client.callbacks.last(); store.clear()
        vm.enterTrainer(InvitationEntry.Invite); vm.leaveTrainer("trainer", "w", 7)
        callback(InvitationOutcome(reply = InvitationReply(summaries = listOf(summary))))
        assertEquals(InvitationState(), vm.state.value); assertEquals(1, client.calls.size)
    }
    @Test fun statusDistinguishesExpiredRevokedAndAcceptedWithoutGrantingMembership() {
        assertEquals("Pending acceptance", invitationStatus(summary, 1))
        assertEquals("Expired invitation", invitationStatus(summary, 9000))
        assertEquals("Revoked invitation", invitationStatus(summary.copy(status = "revoked"), 1))
        assertEquals("Accepted · recheck active clients", invitationStatus(summary.copy(status = "accepted"), 1))
    }
    @Test fun backFromAnEarlierVisitCannotPopAReopenedInvitation() {
        val stack = mutableListOf<NavKey>(ShellDestination(ShellPage.Home, owner = owner))
        openClientInvitations(stack, owner, InvitationEntry.Invite)
        val previous = stack.last() as ShellDestination
        backFromInvitations(stack, previous)
        openClientInvitations(stack, owner, InvitationEntry.Invite)
        val reopened = stack.last()
        backFromInvitations(stack, previous)
        assertEquals(reopened, stack.last())
        assertEquals(3, stack.size)
    }
    @Test fun queuedEntryAndClientsTabRequireTheCurrentDestination() {
        val source = ShellDestination(ShellPage.Clients, owner = owner)
        val stack = mutableListOf<NavKey>(ShellDestination(ShellPage.Home, owner = owner), source)
        openClientInvitations(stack, owner, InvitationEntry.Manage, source)
        val visit = stack.last() as ShellDestination
        openShellPage(stack, owner, ShellPage.Clients, visit)
        val freshClients = stack.last() as ShellDestination
        assertEquals(ShellPage.Clients, freshClients.page)
        assertTrue(freshClients.refreshVersion > source.refreshVersion)
        openClientInvitations(stack, owner, InvitationEntry.Invite, source)
        assertEquals(freshClients, stack.last())
        openShellPage(stack, owner, ShellPage.Home, visit)
        assertEquals(freshClients, stack.last())
        openShellPage(stack, owner, ShellPage.Home, freshClients)
        val home = stack.last() as ShellDestination
        openShellPage(stack, owner, ShellPage.Clients, home)
        val reopenedClients = stack.last() as ShellDestination
        assertNotEquals(freshClients, reopenedClients)
        openClientInvitations(stack, owner, InvitationEntry.Invite, freshClients)
        assertEquals(reopenedClients, stack.last())
        openClientInvitations(stack, owner, InvitationEntry.Invite)
        assertTrue((stack.last() as ShellDestination).refreshVersion > visit.refreshVersion)
    }
    @Test fun sameScopeVisitRejectsOldFieldsDisposalAndReplies() {
        val client = Client(); val vm = model(client)
        vm.attachTrainer(TrainerInvitationPresentation(InvitationEntry.Manage, 1))
        val oldReply = client.callbacks.last()
        vm.attachTrainer(TrainerInvitationPresentation(InvitationEntry.Invite, 2))
        vm.onPresentationEvent("trainer", "w", 7, null, InvitationEvent.Email("current@example.invalid"), 2)
        vm.onPresentationEvent("trainer", "w", 7, null, InvitationEvent.Email("retired@example.invalid"), 1)
        vm.onPresentationEvent("trainer", "w", 7, null, InvitationEvent.Create, 1)
        vm.detachTrainer("trainer", "w", 7, 1)
        oldReply(InvitationOutcome(reply = InvitationReply(summaries = listOf(summary))))
        assertEquals("current@example.invalid", vm.state.value.email)
        assertEquals(2, vm.state.value.trainerVisit); assertTrue(vm.state.value.summaries.isEmpty())
        assertEquals(1, client.calls.size)
        vm.onPresentationEvent("trainer", "w", 7, null, InvitationEvent.Create, 2)
        val original = client.calls.last()
        vm.detachTrainer("trainer", "w", 7, 2)
        assertEquals(original.pending("trainer"), vm.state.value.pending)
        vm.onPresentationEvent("trainer", "w", 7, null, InvitationEvent.Email("retired@example.invalid"), 2)
        assertTrue(vm.state.value.email.isEmpty()); assertNull(vm.state.value.trainerVisit)
        vm.updateIdentity(gate.copy(status = GateStatus.LockedRecovery, verifiedWorkspaceId = null))
        vm.onPresentationEvent("trainer", null, 7, null, InvitationEvent.RetryCommand)
        assertEquals(original, client.calls.last())
    }
    @Test fun failedPostMutationReloadIsRetryableWithoutRepeatingTheMutation() {
        val client = Client(); val vm = model(client); vm.enterTrainer(InvitationEntry.Invite)
        vm.onEvent(InvitationEvent.Email("client@example.invalid")); vm.onEvent(InvitationEvent.Create)
        client.callbacks.last()(InvitationOutcome(reply = InvitationReply(invitationId = summary.invitationId, deliveryToken = "b".repeat(43), tokenVersion = 1)))
        assertTrue(vm.state.value.busy); assertEquals("list", client.calls.last().action)
        client.callbacks.last()(InvitationOutcome(error = "unavailable"))
        assertFalse(vm.state.value.busy); assertTrue(vm.state.value.listFailed); assertFalse(vm.state.value.listLoaded)
        assertNull(vm.state.value.pending); assertNotNull(vm.state.value.deliveryLink)
        assertEquals(TrainerInvitationPage.Result, vm.state.value.trainerPage)
        vm.onEvent(InvitationEvent.ListInvitations); finishList(client, emptyList())
        assertEquals(listOf("create", "list", "list"), client.calls.map { it.action })
        assertEquals(1, client.keys); assertTrue(vm.state.value.listLoaded); assertFalse(vm.state.value.listFailed)
    }
}
