package com.delminiusapps.tillfailure.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlin.test.*

class OnboardingTest {
    private val gate = IdentityState(status = GateStatus.Onboarding, accountUid = "a", verifiedWorkspaceId = "w", verifiedRole = "client", authorityEpoch = 1)
    private val payload = OnboardingCompletion("Alice", "en", "Europe/Sarajevo", true, 0, 0)
    private fun request(key: String = "key") = InvitationRequest("complete-onboarding", workspaceId = "w", idempotencyKey = key, onboarding = payload)
    private fun success() = InvitationOutcome(reply = InvitationReply(workspaceId = "w", status = "complete", replayed = false, accountRevision = 1, onboardingRevision = 1))

    @Test fun resolutionStartedSuppressesOriginalCompletionTransport() {
        val client=Client();assertTrue(client.ledger.prepare("a",request()))
        assertTrue(client.ledger.prepare("a",CommandResolution.resolveRequest(client.pending("a")!!)))
        val model=OnboardingViewModel(client);model.updateIdentity(gate.copy(status=GateStatus.LockedRecovery))
        model.onEvent(OnboardingEvent.Retry)
        assertTrue(client.requests.isEmpty());assertTrue(client.pending("a")!!.resolutionStarted)
    }

    @Test fun originalCompletionSurvivesAmbiguityRestartAndBlocksAnotherPayloadOrAccount() {
        val store = Store(); store.write(AccountPersistenceEnvelope(uid = "a")); store.write(AccountPersistenceEnvelope(uid = "b"))
        val ledger = InvitationLedger(store)
        val command = request()
        assertTrue(ledger.prepare("a", command))
        assertTrue(ledger.settle("a", command, InvitationOutcome(error = "unavailable", ambiguous = true)).ambiguous)
        val restored = InvitationLedger(store)
        assertEquals(payload, restored.pending("a")?.onboarding)
        assertEquals(command.pending("a"), restored.pending("a"))
        assertNull(restored.pending("b"))
        assertFalse(restored.prepare("a", request("different-key")))
        assertFalse(restored.prepare("a", command.copy(onboarding = payload.copy(displayName = "Changed"))))
        assertEquals(command.pending("a"), restored.pending("a"))
        assertTrue(restored.settle("a", command, success().copy(reply = success().reply!!.copy(workspaceId = "foreign"))).ambiguous)
        assertNotNull(restored.pending("a"))
        assertEquals(success(), restored.settle("a", command, success()))
        assertNull(restored.pending("a"))
    }

    @Test fun validationDuplicateSuppressionAndReceiptRecheckUseOriginalCommand() {
        val client = Client(); val model = OnboardingViewModel(client); model.updateIdentity(gate)
        model.onEvent(OnboardingEvent.Complete)
        assertTrue(model.currentState().message.isNotEmpty()); assertEquals(0, client.requests.size)
        fill(model); model.onEvent(OnboardingEvent.Complete); model.onEvent(OnboardingEvent.Complete)
        assertEquals(1, client.requests.size)
        client.complete(InvitationOutcome(error = "unavailable", ambiguous = true))
        val original = client.requests.single()
        val recreated = OnboardingViewModel(client); recreated.updateIdentity(gate)
        recreated.onEvent(OnboardingEvent.Name("Changed")); recreated.onEvent(OnboardingEvent.Retry)
        assertEquals(original, client.requests.last())
        client.complete(success()); assertNull(recreated.currentState().pending)
        assertTrue(recreated.currentState().message.startsWith("Onboarding complete"))
    }

    @Test fun authorizationLossAndWorkspaceChangeFenceLatePresentationButRetainProtectedWork() {
        val client = Client(); val model = OnboardingViewModel(client); model.updateIdentity(gate); fill(model)
        model.onEvent(OnboardingEvent.Complete)
        val callback = client.callback!!
        model.updateIdentity(gate.copy(accountUid = "b", verifiedWorkspaceId = "other", authorityEpoch = 2))
        callback(success())
        assertEquals("b", model.currentState().uid); assertEquals("", model.currentState().name)
        assertNotNull(client.pending("a")); assertNull(model.currentState().pending)
        model.onEvent(OnboardingEvent.Complete); assertEquals(1, client.requests.size)
    }

    @Test fun clearedRootIgnoresLateCompletionAndFreshRootRecoversProtectedCommand() {
        val client = Client(); val store = ViewModelStore()
        val model = ViewModelProvider.create(store, viewModelFactory { initializer { OnboardingViewModel(client) } })[OnboardingViewModel::class]
        model.updateIdentity(gate); fill(model); model.onEvent(OnboardingEvent.Complete)
        val before = model.currentState(); store.clear(); client.callback!!(success())
        assertEquals(before, model.currentState())
        val next = OnboardingViewModel(client); next.updateIdentity(gate.copy(status = GateStatus.DepartureBlocked))
        assertNotNull(next.currentState().pending)
        next.onEvent(OnboardingEvent.Retry); assertEquals(client.requests[0], client.requests[1])
    }

    @Test fun revisionsAndAcknowledgmentArePartOfTheCallerBoundPayloadAndMalformedSuccessNeverSettles() {
        val command = request()
        assertFalse(InvitationJson.validReply(command, success().reply!!.copy(accountRevision = 2)))
        assertFalse(InvitationJson.validReply(command, success().reply!!.copy(onboardingRevision = 0)))
        assertFalse(InvitationJson.validReply(command, success().reply!!.copy(deliveryToken = "unexpected")))
        assertTrue(InvitationJson.validReply(command, success().reply!!))
        for (error in listOf("revision-conflict", "unauthorized", "already-complete", "record-invalid")) {
            val client = Client(); assertTrue(client.ledger.prepare("a", command))
            assertTrue(client.ledger.settle("a", command, InvitationOutcome(error = error)).ambiguous)
            assertEquals(command.pending("a"), client.pending("a"))
        }
    }

    @Test fun preferencesRemainUidBoundHintsAndDoNotReplaceProtectedWork() {
        val client = Client(); val command = request(); assertTrue(client.ledger.prepare("a", command))
        client.ledger.saveWorkspacePreference("a", "stale-workspace")
        assertEquals("stale-workspace", client.ledger.workspacePreference("a"))
        assertNull(client.ledger.workspacePreference("b"))
        assertEquals(command.pending("a"), client.ledger.pending("a"))
    }

    @Test fun invalidRegionOrZoneIsRejectedBeforeIssuingAProtectedCommand() {
        assertFalse(validOnboardingBasics("Alice", "en-ab", "UTC"))
        assertFalse(validOnboardingBasics("Alice", "en", "fake/Zone"))
        assertTrue(validOnboardingBasics("Alice 😀", "bs-BA", "Europe/Sarajevo"))
    }

    @Test fun duplicateLocaleVariantsNeverPrepareAnUnrecoverableCompletion() {
        val client = Client(); val model = OnboardingViewModel(client); model.updateIdentity(gate); fill(model)
        for (locale in listOf("en-1901-1901", "sl-rozaj-ROZAJ")) {
            model.onEvent(OnboardingEvent.Locale(locale)); model.onEvent(OnboardingEvent.Complete)
            assertTrue(model.currentState().message.isNotEmpty())
            assertTrue(client.requests.isEmpty()); assertNull(client.pending("a"))
        }
        model.onEvent(OnboardingEvent.Locale("sl-rozaj-biske")); model.onEvent(OnboardingEvent.Complete)
        assertEquals(1, client.requests.size)
        assertEquals("sl-rozaj-biske", client.pending("a")?.onboarding?.locale)
    }

    @Test fun exhaustedOrInvalidRevisionsNeverPrepareARejectedCompletion() {
        for (identity in listOf(gate.copy(accountProfileRevision = Int.MAX_VALUE - 1),
            gate.copy(onboardingRevision = Int.MAX_VALUE - 1), gate.copy(accountProfileRevision = -1))) {
            val client = Client(); val model = OnboardingViewModel(client); model.updateIdentity(identity); fill(model)
            model.onEvent(OnboardingEvent.Complete)
            assertTrue(model.currentState().message.isNotEmpty())
            assertTrue(client.requests.isEmpty()); assertNull(client.pending("a"))
        }
    }

    private fun fill(model: OnboardingViewModel) {
        model.onEvent(OnboardingEvent.Name(payload.displayName)); model.onEvent(OnboardingEvent.Locale(payload.locale))
        model.onEvent(OnboardingEvent.Zone(payload.timeZoneId)); model.onEvent(OnboardingEvent.Acknowledge(true))
    }
    private class Store : AccountPersistencePrototype {
        private val records = mutableMapOf<String, AccountPersistenceEnvelope>()
        override fun read(uid: String) = records[uid]
        override fun write(envelope: AccountPersistenceEnvelope) { records[envelope.uid] = envelope }
        override fun delete(uid: String) { records.remove(uid) }
    }
    private class Client : InvitationClient {
        val store = Store().apply { write(AccountPersistenceEnvelope(uid = "a")); write(AccountPersistenceEnvelope(uid = "b")) }
        val ledger = InvitationLedger(store)
        val requests = mutableListOf<InvitationRequest>()
        var callback: ((InvitationOutcome) -> Unit)? = null
        override val memory = InvitationMemory()
        override fun pending(uid: String) = ledger.pending(uid)
        override fun newCommandKey() = "key"
        override fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation {
            assertTrue(ledger.prepare(uid, request)); requests += request; this.callback = callback
            return FirebaseCancellation {}
        }
        fun complete(outcome: InvitationOutcome) {
            val request = requests.last(); val settled = ledger.settle("a", request, outcome); callback!!(settled)
        }
    }
}
