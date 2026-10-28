package com.delminiusapps.tillfailure.exercises

import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlin.test.*

class CustomExercisesTest {
    private val id="00000000-0000-4000-8000-000000000001"
    private fun write(revision:Int=0)=CustomExerciseWrite(id,revision,ExerciseAuthority(1,2,3),"Synthetic 😀","Demo \"quote\" \\ step\nReview",listOf("legs"),listOf("barbell"))
    private fun request(revision:Int=0)=CustomExerciseProtocol.write("w",write(revision),"key")
    private class Store:AccountPersistencePrototype {
        var value=AccountPersistenceEnvelope(uid="A");var writes=0;var failAt=0
        override fun read(uid:String)=value.also {require(it.uid==uid)}
        override fun write(envelope:AccountPersistenceEnvelope){if(++writes==failAt)error("storage");value=envelope}
        override fun delete(uid:String)=error("no discard")
    }
    @Test fun frozenHashesAndWireTypesAgreeWithBackend() {
        for((revision,hash) in listOf(0 to "ae185ccb7e0cce0507213e64cde7f195e4403c637f3c7e25cdd522fc34f78838",2 to "33630c7796954279b0783f391d484a407414e31f901ad94e58a585d0964934c6")) {
            val p=request(revision).pending("A")!!;assertTrue(validPendingCommand("A",p));assertEquals(hash,CommandResolution.hash(p))
            assertEquals(CommandResolution.descriptor(p).toString(),InvitationJson.request(request(revision)))
            assertFalse(validPendingCommand("A",p.copy(noteSave=com.delminiusapps.tillfailure.clients.NoteSave("C","x","ab".repeat(32),0,1,1,1,1))))
        }
        for(v in listOf("", " a", "a ","a\r", "a\t", "a\u0000", "a\ud800", "a\udc00", "a\u0085"))assertFalse(CustomExerciseProtocol.text(v,2000,true))
        assertTrue(CustomExerciseProtocol.text("😀".repeat(2000),2000,true))
        assertFalse(write().copy(muscleGroups=listOf("legs","legs")).valid("create-custom-exercise"))
        assertFalse(write().copy(equipment=listOf("barbell","bodyweight")).valid("create-custom-exercise"))
        val ack="""{"status":"exercise-created","workspaceId":"w","exerciseId":"$id","exerciseRevision":1,"replayed":false}"""
        assertNotNull(InvitationJson.outcome(request(),ack,null,false).reply?.exerciseDecision)
        for(bad in listOf(ack.replace("\"exerciseRevision\":1","\"exerciseRevision\":\"1\""),ack.dropLast(1)+",\"instructions\":\"leak\"}"))assertTrue(InvitationJson.outcome(request(),bad,null,false).ambiguous)
    }
    @Test fun completeBudgetsIncludeLegacyFullProofAndDuplicatedPendingProof() {
        // Max valid code-unit IDs with JSON-escape expansion; no normalization on identity.
        val uid="\u0001".repeat(128);val wid="\u0002".repeat(128)
        val old=PendingInvitationCommand(uid,"create","old",workspaceId=wid,email="x".repeat(240)+"@example.test")
        val oldProof=CommandTerminalProof(old,"normal-v1",acknowledgment=InvitationReply(invitationId=CommandResolution.id(old),workspaceId=wid,status="pending",tokenVersion=1,expiresAtMillis=1000,replayed=true))
        assertTrue(oldProof.valid())
        val revocation=com.delminiusapps.tillfailure.clients.ClientRevocation("\u0003".repeat(128),Int.MAX_VALUE-2,Int.MAX_VALUE-2,Int.MAX_VALUE-2,Int.MAX_VALUE-2)
        val revokeRequest=InvitationRequest("revoke-client",workspaceId=wid,idempotencyKey="\u0004".repeat(128),revocation=revocation)
        val revokeProof=CommandTerminalProof(revokeRequest.pending(uid)!!,"normal-v1",acknowledgment=InvitationReply(replayed=true,revocationDecision=com.delminiusapps.tillfailure.clients.ClientRevocationDecision(wid,revocation.clientUid,"client-revoked",revokeRequest.idempotencyKey!!,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-2,Int.MAX_VALUE-2)))
        assertTrue(revokeProof.valid())
        val largestLegacy=listOf(oldProof,revokeProof).maxBy { PersistenceJson.format.encodeToString(CommandTerminalProof.serializer(),it).encodeToByteArray().size }
        val record=AccountPersistenceEnvelope(uid=uid,lastSettledCommand=largestLegacy,preferredWorkspaceId=wid)
        for(revision in listOf(0,Int.MAX_VALUE-2))for(content in listOf("😀".repeat(2000),"\"".repeat(2000),"\\".repeat(2000),"界".repeat(2000),"a"+"\n".repeat(1998)+"b")) {
            val p=CustomExerciseProtocol.write(wid,write(revision).copy(authority=ExerciseAuthority(Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1),name="😀".repeat(120),instructions=content,muscleGroups=listOf("arms","shoulders","whole-body"),equipment=listOf("barbell","dumbbell","machine")),resolutionCommandKey("a".repeat(80))).pending(uid)!!
            assertTrue(validPendingCommand(uid,p));assertTrue(CustomExerciseBudget.admission(record,p))
            val proof=CommandTerminalProof(p,"resolution-v1",resolution=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),"not-applied-conflict",8_640_000_000_000_000))
            val full=record.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=proof)
            val bytes=PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),full).encodeToByteArray().size
            assertTrue(bytes<=65536);assertTrue(((bytes+2)/3)*4+1024<=98304)
            println("CUSTOM_BUDGET revision=$revision resolverUnits=${CommandResolution.request(p).length} pendingBytes=${PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),p).encodeToByteArray().size} envelopeBytes=$bytes")
        }
        // Oversized existing legacy history is never removed to admit a new command.
        val tooLarge=record.copy(preferredWorkspaceId="x".repeat(65536))
        assertFalse(CustomExerciseBudget.admission(tooLarge,request().pending(uid)!!));assertEquals(65536,tooLarge.preferredWorkspaceId!!.length)
    }
    @Test fun bothKindsSettleSuccessConflictAndFenceOnlyAfterDurableProof() {
        for(revision in listOf(0,2))for(mode in listOf("success","conflict","fence"))for(failure in 0..2) {
            val store=Store();var ledger=InvitationLedger(store);val req=request(revision)
            assertTrue(ledger.customExerciseAdmission("A",req));assertTrue(ledger.prepare("A",req));val p=ledger.pending("A")!!
            val network=if(mode=="fence")CommandResolution.resolveRequest(p) else req
            val reply=if(mode=="fence") InvitationReply(terminalOutcome=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),"not-applied-fenced",1000))
                else InvitationReply(exerciseDecision=CustomExerciseDecision(if(mode=="conflict")"exercise-conflict" else if(revision==0)"exercise-created" else "exercise-corrected","w",id,revision+1,false))
            if(failure>0)store.failAt=store.writes+failure
            val result=ledger.settle("A",network,InvitationOutcome(reply=reply));if(failure>0)assertEquals("storage-unavailable",result.error) else assertNull(result.error)
            store.failAt=0;ledger=InvitationLedger(store)
            if(failure==1) {assertNotNull(ledger.pending("A"));assertNull(ledger.settle("A",network,InvitationOutcome(reply=reply)).error)}
            assertNull(ledger.pending("A"));val marker=ledger.lastSettled("A")!!;assertTrue(marker.valid());assertNull(marker.command)
            val encoded=PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),store.value)
            assertFalse(encoded.contains("Synthetic"));assertFalse(encoded.contains(id));assertFalse(encoded.contains("exerciseWrite"));assertFalse(ledger.prepare("A",req))
            assertEquals(ProtectedRecoveryState.StorageLocked,ledger.recoveryState("B"))
            assertTrue(ledger.prepare("A",req.copy(idempotencyKey="new")));assertTrue(ledger.settle("A",network,InvitationOutcome(reply=reply)).ambiguous);assertEquals("new",ledger.pending("A")!!.idempotencyKey)
        }
    }
}
