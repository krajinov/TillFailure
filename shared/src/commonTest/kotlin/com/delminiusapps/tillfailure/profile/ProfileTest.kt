package com.delminiusapps.tillfailure.profile

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileTest {
    private val gate = IdentityState(status = GateStatus.ClientHome, accountUid = "client", verifiedWorkspaceId = "w", verifiedRole = "client", authorityEpoch = 7)
    private val scope = ProfileScope("client", "w", 7)
    private val original = BasicProfile("Original", "en", "UTC", 1)
    private class Store : AccountPersistencePrototype {
        var record: AccountPersistenceEnvelope? = AccountPersistenceEnvelope(uid = "client")
        var fails = false
        override fun read(uid: String) = record?.takeIf { it.uid == uid }
        override fun write(envelope: AccountPersistenceEnvelope) { check(!fails); record = envelope }
        override fun delete(uid: String) { error("No discard") }
    }
    private class Client(val store: Store = Store()) : InvitationClient {
        override val memory = InvitationMemory()
        val ledger = InvitationLedger(store)
        val calls = mutableListOf<InvitationRequest>(); val replies = mutableListOf<(InvitationOutcome) -> Unit>()
        var keys = 0; var cancellations = 0
        override fun pending(uid: String) = ledger.pending(uid)
        override fun newCommandKey() = "key-${++keys}"
        override fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation {
            check(ledger.prepare(uid, request)); calls += request
            replies += { result -> callback(ledger.settle(uid, request, result)) }
            return FirebaseCancellation { cancellations++ }
        }
    }
    private fun model(c: Client) = ProfileViewModel(c).also { it.update(c, gate); it.attach(1) }
    private fun load(c: Client, p: BasicProfile = original) = c.replies.last()(InvitationOutcome(reply = InvitationReply(workspaceId = "w", status = "profile", profile = p)))
    private fun event(vm: ProfileViewModel, e: ProfileEvent, visit: Long? = vm.state.value.visit, prompt: Long = vm.state.value.promptId) = vm.onEvent(scope, visit, prompt, e)
    private fun receipt(c: Client, status: String = "profile-updated", revision: Int = 2, key: String? = c.calls.last().idempotencyKey) =
        InvitationOutcome(reply = InvitationReply(workspaceId = "w", status = status, accountRevision = revision, replayed = true, profileCommandKey = key))
    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    @Test fun freshReadValidationDuplicateSaveAndCurrentReload() {
        val c = Client(); val vm = model(c); assertTrue(vm.state.value.busy); load(c)
        event(vm, ProfileEvent.Name("Changed")); event(vm, ProfileEvent.Save); event(vm, ProfileEvent.Save)
        assertEquals(listOf("profile-get", "update-profile"), c.calls.map { it.action })
        assertEquals(ProfileUpdate("Changed", "en", "UTC", 1), c.pending("client")!!.profileUpdate)
        c.replies.last()(receipt(c)); assertEquals("profile-get", c.calls.last().action)
        assertNull(c.pending("client")); load(c, original.copy(displayName = "Changed", revision = 2))
        assertTrue(vm.state.value.saved); assertFalse(vm.state.value.dirty); assertFalse(vm.state.value.busy)
    }
    @Test fun conflictReceiptRequiresExplicitConfirmedReloadWithoutOverwritingDraft() {
        val c = Client(); val vm = model(c); load(c); event(vm, ProfileEvent.Name("Draft")); event(vm, ProfileEvent.Save)
        c.replies.last()(receipt(c, "profile-conflict", 3)); assertTrue(vm.state.value.conflict); assertNull(c.pending("client"))
        event(vm, ProfileEvent.Save); assertEquals(2, c.calls.size)
        event(vm, ProfileEvent.Reload); val prompt = vm.state.value.promptId
        event(vm, ProfileEvent.Confirm, prompt = prompt - 1); assertEquals(2, c.calls.size)
        event(vm, ProfileEvent.Cancel); assertEquals("Draft", vm.state.value.name)
        event(vm, ProfileEvent.Reload); event(vm, ProfileEvent.Confirm)
        c.replies.last()(InvitationOutcome(error = "unavailable")); assertEquals("Draft", vm.state.value.name)
        event(vm, ProfileEvent.Reload); event(vm, ProfileEvent.Confirm); load(c, original.copy(displayName = "Elsewhere", revision = 3))
        assertEquals("Elsewhere", vm.state.value.name); assertFalse(vm.state.value.conflict)
    }
    @Test fun modifiedDraftBackHomeAndProfileNeedOneCurrentConfirmation() = runTest {
        for (exit in ProfileExit.entries) {
            val c = Client(); val vm = model(c); load(c); event(vm, ProfileEvent.Name("Draft"))
            event(vm, ProfileEvent.Leave(exit)); val prompt = vm.state.value.promptId
            event(vm, ProfileEvent.Leave(ProfileExit.Home)); assertEquals(prompt, vm.state.value.promptId)
            vm.effect.test {
                expectNoEvents(); event(vm, ProfileEvent.Cancel); expectNoEvents()
                event(vm, ProfileEvent.Leave(exit)); event(vm, ProfileEvent.Confirm); event(vm, ProfileEvent.Confirm)
                assertEquals(exit, awaitItem().exit); expectNoEvents(); cancelAndIgnoreRemainingEvents()
            }
        }
    }
    @Test fun scopeVisitDisposalAndLateReplyFences() {
        for (next in listOf(gate.copy(accountUid = "other"), gate.copy(verifiedWorkspaceId = "other"), gate.copy(authorityEpoch = 8), gate.copy(status = GateStatus.AccessLost))) {
            val c = Client(); val vm = model(c); val late = c.replies.last(); vm.update(c, next)
            late(InvitationOutcome(reply = InvitationReply(workspaceId = "w", status = "profile", profile = original)))
            event(vm, ProfileEvent.Name("Retired"), 1); assertEquals("", vm.state.value.name); assertNull(vm.state.value.baseline)
        }
        val c = Client(); val vm = model(c); load(c); vm.attach(2)
        vm.detach(scope, 1); event(vm, ProfileEvent.Name("Retired"), 1); load(c)
        assertEquals(2, vm.state.value.visit); assertEquals("Original", vm.state.value.name)
    }
    @Test fun restartRetainsExactCommandAndOnlyValidatedMatchingReceiptCanSettle() {
        val c = Client(); val vm = model(c); load(c); event(vm, ProfileEvent.Name("Original payload")); event(vm, ProfileEvent.Save)
        val command = c.pending("client")!!; c.replies.last()(InvitationOutcome(error = "unavailable", ambiguous = true))
        vm.detach(scope, 1); val restarted = ProfileViewModel(c)
        val recovery = gate.copy(status = GateStatus.LockedRecovery, verifiedWorkspaceId = null)
        restarted.update(c, recovery); val recoveryScope = ProfileScope("client", null, 7)
        restarted.onEvent(recoveryScope, null, 0, ProfileEvent.RetryOriginal)
        assertEquals(command, c.calls.last().pending("client"))
        c.replies.last()(receipt(c, key = "foreign")); assertEquals(command, c.pending("client"))
        restarted.onEvent(recoveryScope, null, 0, ProfileEvent.RetryOriginal)
        c.replies.last()(receipt(c)); assertNull(c.pending("client")); assertTrue(restarted.state.value.saved)
    }
    @Test fun storageFailureAndRejectedCommandCannotDiscardOrReplaceWork() {
        val c = Client(); val vm = model(c); load(c); event(vm, ProfileEvent.Name("Changed")); event(vm, ProfileEvent.Save)
        val command = c.pending("client"); c.store.fails = true; c.replies.last()(receipt(c))
        assertEquals(command, c.pending("client")); assertTrue(vm.state.value.unavailable)
        c.store.fails = false; event(vm, ProfileEvent.RetryOriginal); c.replies.last()(InvitationOutcome(error = "unauthorized"))
        event(vm, ProfileEvent.Save); assertEquals(command, c.pending("client")); assertEquals(1, c.keys)
    }
    @Test fun disposedRootDropsFieldsAndCallbacks() {
        val c = Client(); val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { ProfileViewModel(c) } })[ProfileViewModel::class]
        vm.update(c, gate); vm.attach(1); val late = c.replies.last(); store.clear()
        late(InvitationOutcome(reply = InvitationReply(workspaceId = "w", status = "profile", profile = original)))
        vm.attach(2); assertEquals(ProfileState(), vm.state.value)
    }
    @Test fun strictProtocolAndProtectedPayloadAreBoundToRevisionKeyAndAllowlist() {
        val p = ProfileUpdate("Name", "en", "UTC", 1)
        val request = InvitationRequest("update-profile", workspaceId = "w", idempotencyKey = "k", profileUpdate = p)
        val valid = InvitationReply(workspaceId = "w", status = "profile-conflict", accountRevision = 2, profileCommandKey = "k", replayed = true)
        assertTrue(InvitationJson.validReply(request, valid))
        for (bad in listOf(valid.copy(profileCommandKey = "other"), valid.copy(accountRevision = 1), valid.copy(deliveryToken = "secret"), valid.copy(profile = original), valid.copy(workspaceId = "other")))
            assertFalse(InvitationJson.validReply(request, bad))
        assertFalse(InvitationLedger(Store()).prepare("client", request.copy(profileUpdate = p.copy(expectedRevision = 0))))
        assertFalse(InvitationLedger(Store()).prepare("client", InvitationRequest("create", workspaceId = "w", email = "a@example.invalid", idempotencyKey = "k", profileUpdate = p)))
        val encoded = InvitationJson.request(request)
        assertTrue(InvitationJson.outcome(request, "{\"unexpected\":true}", null, false).ambiguous)
        assertTrue(encoded.contains("expectedRevision")); assertFalse(p.toString().contains("Name"))
    }
    @Test fun reopenedEditorsUseDistinctOwnedVisitsAndTrainerEntryIsDenied() {
        val owner = ShellOwner("client", "w", null, "client", 7)
        val source = ShellDestination(ShellPage.Profile, owner = owner)
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(ShellDestination(ShellPage.Home, owner = owner), source)
        openBasicProfile(stack, owner, source); val old = stack.last() as ShellDestination
        stack.removeLast(); val current = stack.last() as ShellDestination
        openBasicProfile(stack, owner, source); assertEquals(current, stack.last())
        openBasicProfile(stack, owner, current); assertNotEquals(old, stack.last())
        assertEquals(3, stack.size)
        val trainer = owner.copy(role = "trainer", trainer = "client")
        val trainerSource = source.copy(owner = trainer)
        val denied = mutableListOf<androidx.navigation3.runtime.NavKey>(trainerSource)
        openBasicProfile(denied, trainer, trainerSource); assertEquals(1, denied.size)
    }

    @Test fun freshOwnBasicsUpdatePresentationWithoutReplacingTheProfileDestinationOrGrantingAuthority() {
        val fresh = original.copy(displayName = "Updated", revision = 2)
        val next = refreshedProfilePresentation(gate, scope, fresh)
        assertEquals("Updated", next.displayName)
        assertEquals(gate, next.copy(displayName = gate.displayName, locale = gate.locale, timeZoneId = gate.timeZoneId, accountProfileRevision = gate.accountProfileRevision))
        for (invalid in listOf(scope.copy(uid = "other"), scope.copy(workspace = "other"), scope.copy(epoch = 8)))
            assertEquals(gate, refreshedProfilePresentation(gate, invalid, fresh))
        val trainer = gate.copy(verifiedRole = "trainer", status = GateStatus.TrainerHome)
        assertEquals(trainer, refreshedProfilePresentation(trainer, scope, fresh))
    }

    @Test fun backgroundClearsDraftAndRejectsLateRepliesUntilAFreshAuthorityEpoch() {
        val c = Client(); val vm = model(c); val late = c.replies.last()
        vm.suspendPresentation(); vm.update(c, gate); vm.attach(2)
        late(InvitationOutcome(reply = InvitationReply(workspaceId = "w", status = "profile", profile = original)))
        assertNull(vm.state.value.baseline); assertEquals("", vm.state.value.name); assertEquals(1, c.calls.size)
        vm.update(c, gate.copy(authorityEpoch = 8)); vm.attach(3); load(c)
        assertEquals("Original", vm.state.value.name)
        event(vm, ProfileEvent.Name("Retired epoch"), 3); assertEquals("Original", vm.state.value.name)
    }

    @Test fun dequeuedNavigationCannotSurviveBackgroundEvenWhenTheRouteHasNotRecomposed() = runTest {
        val c = Client(); val vm = model(c); load(c)
        val owner = ShellOwner("client", "w", null, "client", 7)
        val top = ShellDestination(ShellPage.EditProfile, owner = owner, refreshVersion = 1)
        vm.effect.test {
            event(vm, ProfileEvent.Leave(ProfileExit.Back)); val dequeued = awaitItem()
            vm.suspendPresentation()
            assertFalse(acceptProfileNavigation(vm, dequeued, owner, top))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun navigationIsConsumedOnceAndCannotMatchAReattachedVisitOrNewOwner() = runTest {
        val c = Client(); val vm = model(c); load(c)
        val owner = ShellOwner("client", "w", null, "client", 7)
        val top = ShellDestination(ShellPage.EditProfile, owner = owner, refreshVersion = 1)
        vm.effect.test {
            event(vm, ProfileEvent.Leave(ProfileExit.Back)); val old = awaitItem()
            vm.detach(scope, 1); vm.attach(1); load(c)
            event(vm, ProfileEvent.Leave(ProfileExit.Back)); val current = awaitItem()
            assertFalse(acceptProfileNavigation(vm, old, owner, top))
            assertFalse(acceptProfileNavigation(vm, current, owner.copy(uid = "other"), top))
            assertFalse(acceptProfileNavigation(vm, current, owner, top.copy(page = ShellPage.Profile)))
            assertTrue(acceptProfileNavigation(vm, current, owner, top))
            assertFalse(acceptProfileNavigation(vm, current, owner, top))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun everyRetryErrorAndMissingReceiptRetainsTheExactProtectedCommand() {
        val request = InvitationRequest("update-profile", workspaceId = "w", idempotencyKey = "original",
            profileUpdate = ProfileUpdate("Submitted fixture", "en", "UTC", 1))
        val success = InvitationReply(workspaceId = "w", status = "profile-updated", accountRevision = 2,
            profileCommandKey = "original", replayed = true)
        val failures = listOf("unauthorized", "unauthenticated", "unverified-email", "rate-limited", "revision-conflict",
            "record-invalid", "input-invalid", "idempotency-key-reused", "unavailable").map { InvitationOutcome(error = it) } +
            listOf(InvitationOutcome(), InvitationOutcome(reply = success, ambiguous = true),
                InvitationOutcome(reply = success, error = "rate-limited"),
                InvitationOutcome(reply = success.copy(profileCommandKey = "foreign")))
        val c = Client(); assertTrue(c.ledger.prepare("client", request))
        val pending = c.pending("client")
        for (failure in failures) {
            assertTrue(c.ledger.settle("client", request, failure).ambiguous)
            assertEquals(pending, c.pending("client"))
            assertFalse(c.ledger.prepare("client", request.copy(idempotencyKey = "replacement")))
        }
        assertEquals(InvitationOutcome(reply = success), c.ledger.settle("client", request, InvitationOutcome(reply = success)))
        assertNull(c.pending("client"))
    }

}
