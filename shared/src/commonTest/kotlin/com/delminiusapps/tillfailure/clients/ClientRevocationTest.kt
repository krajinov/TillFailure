package com.delminiusapps.tillfailure.clients

import app.cash.turbine.test
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClientRevocationTest {
    private val scope=ClientScope("A","w","A",1)
    private val binding=ClientRevocation("C",2,3,1,1)
    private fun command(key: String="key")=ClientRevocationProtocol.command("w",binding,key)
    private fun decision(status: String="client-revoked")=ClientRevocationDecision("w","C",status,"key",3,4,1,1)
    private class Store: AccountPersistencePrototype {
        var value=AccountPersistenceEnvelope(uid="A");var writes=0;var failAt: Int?=null
        override fun read(uid: String)=value.also { require(it.uid==uid) }
        override fun write(envelope: AccountPersistenceEnvelope) { writes++;if(writes==failAt)error("storage boundary");value=envelope }
        override fun delete(uid: String)=error("No discard")
    }
    private inner class Repository: ClientRepository {
        val requests=mutableListOf<InvitationRequest>();val callbacks=mutableListOf<(InvitationOutcome)->Unit>()
        var readCallback: ((ClientResult)->Unit)?=null
        val store=Store();val ledger=InvitationLedger(store)
        override val commands=object: InvitationClient {
            override val memory=InvitationMemory()
            override fun pending(uid: String)=ledger.pending(uid)
            override fun newCommandKey()="key"
            override fun execute(uid: String,request: InvitationRequest,callback:(InvitationOutcome)->Unit): FirebaseCancellation {
                requests+=request
                assertTrue(ledger.prepare(uid,request));callbacks+={callback(ledger.settle(uid,request,it))}
                return FirebaseCancellation {}
            }
        }
        override fun read(scope: ClientScope,request: ClientRequest,callback:(ClientResult)->Unit): FirebaseCancellation {
            readCallback=callback;return FirebaseCancellation {}
        }
        fun ready(vm: ClientsViewModel) {
            vm.bind(scope,"C");readCallback!!(ClientResult(detail=ClientRecord("C","w","Client","active","pending")))
            callbacks.last()(InvitationOutcome(reply=InvitationReply(revocationPreparation=binding)))
        }
    }
    @BeforeTest fun dispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun reset() { Dispatchers.resetMain() }

    @Test fun seventhVariantHasFrozenCanonicalBindingAndExactDto() {
        val p=command().pending("A")!!
        assertEquals("0fbea0b0fed9442012962dc68aa22f580ede004db58ea65521b10ebe382ebde3",CommandResolution.hash(p))
        assertEquals("client-membership-revoke",CommandResolution.kind(p));assertEquals("client-revocation-v1",CommandResolution.version(p))
        assertEquals(command(),CommandResolution.original(p))
        val reply=InvitationReply(revocationDecision=decision(),replayed=false)
        val body=InvitationJson.format.encodeToString(InvitationReply.serializer(),reply)
        assertNotNull(InvitationJson.outcome(command(),body,null,false).reply)
        for(bad in listOf(body.replace("\"workspaceRevision\":4","\"workspaceRevision\":\"4\""),body.replace("\"replayed\":false","\"replayed\":\"false\""),body.dropLast(1)+",\"private\":true}"))
            assertNull(InvitationJson.outcome(command(),bad,null,false).reply)
        assertFalse(InvitationJson.validReply(command(),reply.copy(revocationDecision=decision().copy(clientUid="B"))))
        assertFalse(InvitationJson.validReply(command(),reply.copy(revocationDecision=decision().copy(workspaceRevision=3))))
    }
    @Test fun normalSuccessConflictAndResolverProofRetireOnlyExactOriginalAfterDurableProof() {
        for(status in listOf("client-revoked","revocation-conflict")) {
            val store=Store();val ledger=InvitationLedger(store);val request=command()
            assertTrue(ledger.prepare("A",request))
            val outcome=InvitationOutcome(reply=InvitationReply(revocationDecision=decision(status),replayed=false))
            assertNull(ledger.settle("A",request,outcome).error);assertNull(ledger.pending("A"));assertTrue(ledger.lastSettled("A")!!.valid())
        }
        val store=Store();val ledger=InvitationLedger(store);assertTrue(ledger.prepare("A",command()))
        val original=ledger.pending("A")!!;val resolve=CommandResolution.resolveRequest(original);assertTrue(ledger.prepare("A",resolve))
        assertFalse(ledger.prepare("A",command()))
        val r=CommandResolutionReply(1,CommandResolution.kind(original),CommandResolution.id(original),CommandResolution.version(original),CommandResolution.hash(original),"not-applied-fenced",1000)
        assertNull(ledger.settle("A",resolve,InvitationOutcome(reply=InvitationReply(terminalOutcome=r))).error)
        assertNull(ledger.pending("A"));assertTrue(ledger.lastSettled("A")!!.valid())
    }
    @Test fun proofBoundaryFailuresKeepWorkAndRestartFinishesOnlyValidatedProof() {
        for(boundary in listOf(2,3)) {
            val store=Store();val ledger=InvitationLedger(store);assertTrue(ledger.prepare("A",command()));store.failAt=boundary
            assertEquals("storage-unavailable",ledger.settle("A",command(),InvitationOutcome(reply=InvitationReply(revocationDecision=decision(),replayed=false))).error)
            store.failAt=null
            val restored=InvitationLedger(store)
            if(boundary==2)assertNotNull(restored.pending("A")) else {assertNull(restored.pending("A"));assertTrue(restored.lastSettled("A")!!.valid())}
        }
    }
    @Test fun confirmationCancelBackDuplicateAndStaleScopeCannotIssueOrNavigate() = runTest {
        val repo=Repository();val vm=ClientsViewModel(repo);repo.ready(vm)
        vm.onEvent(ClientsEvent.Revoke);val old=vm.currentState().revocationPrompt!!
        vm.onEvent(ClientsEvent.CancelRevocation(old));vm.onEvent(ClientsEvent.Revoke);val current=vm.currentState().revocationPrompt!!
        vm.onEvent(ClientsEvent.CancelRevocation(old));assertEquals(current,vm.currentState().revocationPrompt)
        vm.onEvent(ClientsEvent.ConfirmRevocation(old));assertEquals(1,repo.requests.size)
        vm.onEvent(ClientsEvent.ConfirmRevocation(current));vm.onEvent(ClientsEvent.ConfirmRevocation(current));assertEquals(2,repo.requests.size)
        val protected=repo.ledger.pending("A")!!;assertEquals(binding,protected.revocation)
        vm.bind(scope.copy(authorityEpoch=2),"C")
        repo.callbacks[1](InvitationOutcome(reply=InvitationReply(revocationDecision=decision(),replayed=false)))
        assertNull(repo.ledger.pending("A"));assertNull(vm.currentState().revocationMessage)
        vm.clear()
    }
    @Test fun successfulEffectIsConsumedOnceAndCannotSurviveBackgroundOrNewerScope() = runTest {
        val repo=Repository();val vm=ClientsViewModel(repo);repo.ready(vm)
        vm.effect.test {
            vm.onEvent(ClientsEvent.Revoke);vm.onEvent(ClientsEvent.ConfirmRevocation(vm.currentState().revocationPrompt!!))
            repo.callbacks.last()(InvitationOutcome(reply=InvitationReply(revocationDecision=decision(),replayed=false)))
            val effect=awaitItem() as ClientsEffect.Revoked
            assertTrue(vm.consumeRevocationEffect(effect,scope,"C"));assertFalse(vm.consumeRevocationEffect(effect,scope,"C"))
            vm.clear();assertFalse(vm.consumeRevocationEffect(effect,scope,"C"));cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun backFencesQueuedConfirmationBeforeDestinationDisposal() = runTest {
        val repo=Repository();val vm=ClientsViewModel(repo);repo.ready(vm)
        vm.effect.test {
            vm.onEvent(ClientsEvent.Revoke)
            val prompt=vm.currentState().revocationPrompt!!
            vm.onEvent(ClientsEvent.Back)
            // The destination has not disposed yet; old UI callbacks can still arrive.
            vm.onEvent(ClientsEvent.ConfirmRevocation(prompt))
            vm.onEvent(ClientsEvent.Revoke)
            vm.onEvent(ClientsEvent.Back)
            assertEquals(1,repo.requests.size)
            assertNull(repo.ledger.pending("A"))
            assertNull(vm.currentState().revocationPrompt)
            assertEquals(ClientsEffect.Back,awaitItem())
            expectNoEvents()
            vm.clear();cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun failedPreparationOrExistingPendingNeverEnablesRevocation() = runTest {
        val repo=Repository();val vm=ClientsViewModel(repo)
        vm.bind(scope,"C");repo.readCallback!!(ClientResult(detail=ClientRecord("C","w",null,"active","pending")))
        repo.callbacks.last()(InvitationOutcome(error="assignment-artifacts"));vm.onEvent(ClientsEvent.Revoke)
        assertNull(vm.currentState().revocationPrompt);assertNull(vm.currentState().revocationPreparation)
        assertTrue(repo.ledger.prepare("A",command()))
        vm.clear();vm.bind(scope,"C");repo.readCallback!!(ClientResult(detail=ClientRecord("C","w",null,"active","pending")))
        assertEquals(1,repo.requests.size);assertNull(vm.currentState().revocationPreparation);vm.clear()
    }
}
