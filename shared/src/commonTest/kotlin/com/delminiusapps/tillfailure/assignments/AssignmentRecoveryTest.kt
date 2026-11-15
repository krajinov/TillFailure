package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AssignmentRecoveryTest {
    private fun request(client:String="B")=AssignmentProtocol.write("w",AssignmentCreate("00000000-0000-4000-8000-000000000003","00000000-0000-4000-8000-000000000004",client,
        "00000000-0000-4000-8000-000000000001","v0000000001",1,"a".repeat(64),1,"b".repeat(64),AssignmentAuthority(1,1,1,1,1,1),1800000000000,1800000000001,1800086400001),resolutionCommandKey("assignment-recovery"))
    private class Store(var value:AccountPersistenceEnvelope=AccountPersistenceEnvelope(uid="A")):AccountPersistencePrototype {
        var writes=0;var failAt:Int?=null
        override fun read(uid:String)=value.also{require(uid==it.uid)}
        override fun write(envelope:AccountPersistenceEnvelope){writes++;if(writes==failAt)error("proof-write-failure");value=envelope}
        override fun delete(uid:String)=error("No destructive discard in this test")
    }
    private fun reply(r:InvitationRequest,status:String="assignment-created")=InvitationOutcome(reply=InvitationReply(assignmentDecision=
        AssignmentContract.decision(AssignmentDescriptor(r.action,r.workspaceId!!,r.idempotencyKey!!,r.assignment!!),status).copy(replayed=false)))
    @Test fun fullProofPrecedesRetirementAndColdStartCompletesInterruptedRetirement() {
        val store=Store();val ledger=InvitationLedger(store);val request=request();assertTrue(ledger.prepare("A",request));val original=store.value.pendingInvitationCommands.single()
        ledger.beforeRetirementForTest={error("process-interrupted")}
        assertEquals("storage-unavailable",ledger.settle("A",request,reply(request)).error)
        assertEquals(original,store.value.pendingInvitationCommands.single());assertEquals(original,store.value.pendingTerminalProof?.command)
        assertNull(store.value.lastSettledCommand)
        val cold=InvitationLedger(store);assertNull(cold.pending("A"));val proof=cold.lastSettled("A")!!
        assertTrue(proof.valid());assertNull(proof.command);assertEquals(CommandResolution.hash(original),proof.marker?.requestHash)
        val serialized=PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),store.value)
        assertFalse(serialized.contains(request.assignment!!.assignmentId));assertFalse(serialized.contains(request.idempotencyKey!!));assertFalse(serialized.contains("sourceContentHash"))
        assertFalse(cold.prepare("A",request));assertTrue(cold.prepare("A",request.copy(idempotencyKey=resolutionCommandKey("new-confirmation"))))
    }
    @Test fun failedProofOrMalformedAckCannotEraseOriginalOrAdmitNextCommand() {
        val request=request();val store=Store();val ledger=InvitationLedger(store);assertTrue(ledger.prepare("A",request));store.failAt=2
        assertEquals("storage-unavailable",ledger.settle("A",request,reply(request)).error)
        assertEquals(request.pending("A"),store.value.pendingInvitationCommands.single());assertNull(store.value.pendingTerminalProof)
        assertFalse(ledger.prepare("A",request.copy(idempotencyKey=resolutionCommandKey("other"))))
        val original=store.value;val wrong=reply(request).copy(reply=reply(request).reply!!.copy(assignmentDecision=reply(request).reply!!.assignmentDecision!!.copy(assignmentId="00000000-0000-4000-8000-000000000099")))
        assertTrue(ledger.settle("A",request,wrong).ambiguous);assertEquals(original,store.value)
    }
    @Test fun allFrozenNormalConflictsAndOwnOutcomesCompactExactly() {
        val request=request();val original=request.pending("A")!!
        for(status in AssignmentContract.statuses) {
            val store=Store();val ledger=InvitationLedger(store);assertTrue(ledger.prepare("A",request));assertNull(ledger.settle("A",request,reply(request,status)).error)
            assertEquals(if(status=="assignment-created")"applied" else "not-applied-conflict",ledger.lastSettled("A")?.marker?.outcome)
        }
        for(outcome in listOf("applied","not-applied-conflict","not-applied-fenced")) {
            val store=Store();val ledger=InvitationLedger(store);assertTrue(ledger.prepare("A",request));val resolve=CommandResolution.resolveRequest(original);assertTrue(ledger.prepare("A",resolve))
            val proof=CommandResolutionReply(1,AssignmentContract.KIND,CommandResolution.id(original),AssignmentContract.HASH_VERSION,CommandResolution.hash(original),outcome,AssignmentContract.MAX_MILLIS)
            assertNull(ledger.settle("A",resolve,InvitationOutcome(reply=InvitationReply(terminalOutcome=proof))).error);assertEquals(outcome,ledger.lastSettled("A")?.marker?.outcome)
            assertFalse(CommandResolution.valid(original,proof.copy(decidedAtMillis=AssignmentContract.MAX_MILLIS+1)))
        }
    }
    @Test fun refusedProspectiveAdmissionDoesNotWritePriorRecordAndWireIsALegalTargetUid() {
        val store=Store(AccountPersistenceEnvelope(uid="A",preferredWorkspaceId="x".repeat(36500)));val ledger=InvitationLedger(store);val before=store.value
        assertFalse(ledger.assignmentAdmission("A",request()));assertFalse(ledger.prepare("A",request()));assertEquals(0,store.writes);assertEquals(before,store.value)
        val wire=request("wire");assertTrue(AssignmentProtocol.validRequest(wire));assertTrue(InvitationJson.validReply(wire,reply(wire).reply!!));assertFalse(AssignmentProtocol.validRequest(wire,"wire"))
    }
    @Test fun responseRejectsUnknownFieldsAndCoercedNumbersAndOwnTimeIsStrictlyNumeric() {
        val request=request();val p=request.pending("A")!!;val decision=reply(request).reply!!.assignmentDecision!!
        val body=AssignmentContract.json.encodeToJsonElement(decision).jsonObject
        assertFalse(InvitationJson.outcome(request,JsonObject(body+("assignmentRevision" to JsonPrimitive("1"))).toString(),null,false).reply!=null)
        assertNull(InvitationJson.outcome(request,JsonObject(body+("private" to JsonPrimitive(true))).toString(),null,false).reply)
        assertEquals(AssignmentContract.hash("A",AssignmentProtocol.descriptor(p)),CommandResolution.hash(p))
        val scope=com.delminiusapps.tillfailure.exercises.ExerciseScope("B","w",1);val r=AssignmentReadRequest("client-assignment-time","w","B",assignmentId=request.assignment!!.assignmentId)
        val time=buildJsonObject{put("workspaceId","w");put("clientUid","B");put("assignmentId",request.assignment.assignmentId);put("snapshotHash","b".repeat(64));put("accessExpiresAtMillis",1800086400001);put("serverNowMillis",1800000000000)}
        assertNull(AssignmentReads.result(scope,r,time.toString(),null).error)
        assertNotNull(AssignmentReads.result(scope,r,JsonObject(time+("serverNowMillis" to JsonPrimitive("1800000000000"))).toString(),null).error)
    }
}
