package com.delminiusapps.tillfailure.clients

import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class TrainerNotesTest {
    private val owner=NoteOwner(ClientScope("A","w","A",1),"C",1)
    private val authority=NoteAuthority(1,2,1,1)
    private fun payload(body:String="Synthetic 😀\nReview")=NoteSave("C",body,"ab".repeat(32),0,1,2,1,1)
    private fun request()=TrainerNoteProtocol.save("w",payload(),"key")
    private class Store:AccountPersistencePrototype {
        var value=AccountPersistenceEnvelope(uid="A");var writes=0;var failAt=0
        override fun read(uid:String)=value.also{require(it.uid==uid)}
        override fun write(envelope:AccountPersistenceEnvelope){writes++;if(writes==failAt)error("storage");value=envelope}
        override fun delete(uid:String)=error("no discard")
    }
    private class Client:InvitationClient {
        val calls=mutableListOf<InvitationRequest>();val callbacks=mutableListOf<(InvitationOutcome)->Unit>()
        override val memory=InvitationMemory();override fun pending(uid:String):PendingInvitationCommand?=null
        override fun newCommandKey()="new-key"
        override fun newNoteBindingNonce()="ab".repeat(32)
        override fun execute(uid:String,request:InvitationRequest,callback:(InvitationOutcome)->Unit):FirebaseCancellation {calls+=request;callbacks+=callback;return FirebaseCancellation{}}
        fun loaded(body:String?=null) {callbacks.last()(InvitationOutcome(reply=InvitationReply(noteRead=NoteRead("note","w","C",body?.let{NoteValue(it,1,1000,1000)},NoteAuthority(1,2,1,1)))))}
    }
    @Test fun canonicalBindingMatchesBackendAndInputBoundsAreStrict() {
        val p=request().pending("A")!!;assertTrue(validPendingCommand("A",p))
        assertEquals("f06b52a740f6545436c6d364bdce78c21593cce6817fd7818656a31afbeccadf",CommandResolution.hash(p))
        for(v in listOf(""," ","\uFEFF","\u2028","a\u0000","a\r","a\ud800","a\udc00","a\u0085","a".repeat(2001)))assertFalse(validNoteBody(v))
        assertTrue(validNoteBody("😀".repeat(2000)));assertEquals("a\nb\nc",canonicalNoteBody("a\r\nb\rc"))
        assertFalse(validPendingCommand("A",p.copy(noteSave=p.noteSave!!.copy(bindingNonce="not-random"))))
        assertFalse(validPendingCommand("A",p.copy(action="update-profile")))
    }
    @Test fun exactReadAndSaveWireRejectsTypesFieldsForeignBindingAndContentInAcknowledgment() {
        val json="""{"status":"note","workspaceId":"w","clientUid":"C","note":null,"authority":{"authorMembershipRevision":1,"subjectMembershipRevision":2,"authorLifecycleRevision":1,"subjectLifecycleRevision":1}}"""
        val r=TrainerNoteProtocol.read("w","C");assertNotNull(InvitationJson.outcome(r,json,null,false).reply?.noteRead)
        assertTrue(InvitationJson.outcome(r,json.replace("\"subjectMembershipRevision\":2","\"subjectMembershipRevision\":\"2\""),null,false).ambiguous)
        assertTrue(InvitationJson.outcome(r,json.replace("\"note\":null","\"extra\":1,\"note\":null"),null,false).ambiguous)
        val ack="""{"status":"note-saved","workspaceId":"w","clientUid":"C","noteRevision":1,"replayed":false}"""
        assertNotNull(InvitationJson.outcome(request(),ack,null,false).reply?.noteDecision)
        for(v in listOf(ack.replace("\"C\"","\"other\""),ack.replace("\"noteRevision\":1","\"noteRevision\":\"1\""),ack.dropLast(1)+",\"body\":\"secret\"}"))assertTrue(InvitationJson.outcome(request(),v,null,false).ambiguous)
    }
    @Test fun successConflictAndResolverProofCompactOnlyAfterDurableProofIncludingRestart() {
        for(mode in listOf("success","conflict","resolution"))for(failure in listOf(0,1,2)) {
            val store=Store();var ledger=InvitationLedger(store);val req=request();assertTrue(ledger.prepare("A",req));val p=ledger.pending("A")!!
            val resolve=mode=="resolution"
            val network=if(resolve) CommandResolution.resolveRequest(p) else req
            val result=if(resolve) InvitationReply(terminalOutcome=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),"not-applied-fenced",1000))
                else InvitationReply(noteDecision=NoteDecision(if(mode=="conflict")"note-conflict" else "note-saved","w","C",1,false))
            if(failure>0) store.failAt=store.writes+failure
            val outcome=ledger.settle("A",network,InvitationOutcome(reply=result))
            if(failure>0)assertEquals("storage-unavailable",outcome.error) else assertNull(outcome.error)
            store.failAt=0;ledger=InvitationLedger(store)
            if(failure==1) {assertNotNull(ledger.pending("A"));assertNull(ledger.settle("A",network,InvitationOutcome(reply=result)).error)}
            assertNull(ledger.pending("A"));val last=ledger.lastSettled("A")!!;assertTrue(last.valid());assertNull(last.command)
            val encoded=PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),store.value)
            assertFalse(encoded.contains("Synthetic"));assertFalse(encoded.contains("ab".repeat(32)));assertFalse(encoded.contains("noteSave"))
            assertFalse(ledger.prepare("A",req));assertEquals(ProtectedRecoveryState.StorageLocked,ledger.recoveryState("B"))
            assertTrue(ledger.prepare("A",req.copy(idempotencyKey="next")))
            assertTrue(ledger.settle("A",network,InvitationOutcome(reply=result)).ambiguous);assertEquals("next",ledger.pending("A")?.idempotencyKey)
        }
    }
    @Test fun editorDirtyCancelReloadAndConflictNeverSilentlyOverwrite() {
        val c=Client();val vm=TrainerNoteViewModel(c);vm.bind(owner);c.loaded("Original")
        vm.onEvent(owner,0,NoteEvent.Body("Draft"));vm.onEvent(owner,0,NoteEvent.Reload);val id=vm.state.value.promptId
        vm.onEvent(owner,id,NoteEvent.CancelPrompt);assertEquals("Draft",vm.state.value.body)
        vm.onEvent(owner,id,NoteEvent.Save);assertEquals("save-trainer-note",c.calls.last().action)
        c.callbacks.last()(InvitationOutcome(reply=InvitationReply(noteDecision=NoteDecision("note-conflict","w","C",2,false))))
        assertTrue(vm.state.value.conflict);assertEquals("Draft",vm.state.value.body)
        vm.onEvent(owner,id,NoteEvent.Save);assertEquals(2,c.calls.size)
        vm.onEvent(owner,id,NoteEvent.Reload);vm.onEvent(owner,vm.state.value.promptId,NoteEvent.Confirm)
        c.callbacks.last()(InvitationOutcome(error="unavailable"));assertEquals("Draft",vm.state.value.body)
        vm.onEvent(owner,0,NoteEvent.Reload);vm.onEvent(owner,vm.state.value.promptId,NoteEvent.Confirm);c.loaded("Current")
        assertEquals("Current",vm.state.value.body);assertFalse(vm.state.value.conflict)
    }
    @Test fun acceptedBackImmediatelyFencesQueuedSaveAndNavigationIsConsumedOnce()=runBlocking {
        val c=Client();val vm=TrainerNoteViewModel(c);vm.bind(owner);c.loaded()
        val event=async(start=CoroutineStart.UNDISPATCHED){vm.effect.first()}
        vm.onEvent(owner,0,NoteEvent.Leave());vm.onEvent(owner,0,NoteEvent.Save);vm.onEvent(owner,0,NoteEvent.Body("stale"))
        val n=event.await();assertTrue(vm.consumeNavigation(n));assertFalse(vm.consumeNavigation(n));assertEquals(1,c.calls.size)
    }
    @Test fun backgroundOwnerChangesDenialAndLateCallbacksClearContentAndCannotNavigate() {
        val c=Client();val vm=TrainerNoteViewModel(c);vm.bind(owner);c.loaded("Original");vm.onEvent(owner,0,NoteEvent.Body("Draft"));vm.clear()
        assertEquals("",vm.state.value.body);vm.bind(owner.copy(visit=2));c.callbacks.first()(InvitationOutcome(reply=InvitationReply(noteRead=NoteRead("note","w","C",NoteValue("Late",1,1,1),authority))))
        assertEquals("",vm.state.value.body);c.callbacks.last()(InvitationOutcome(error="unauthorized"));assertTrue(vm.state.value.denied);assertNull(vm.state.value.baseline)
        vm.onEvent(owner,0,NoteEvent.Save);assertEquals(2,c.calls.size)
    }
}
