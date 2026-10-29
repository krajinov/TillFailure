package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.ExerciseAuthority
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgramDraftTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private val authority = ExerciseAuthority(Int.MAX_VALUE-1, Int.MAX_VALUE-1, Int.MAX_VALUE-1)
    fun draft() = DraftSave(id(1), Int.MAX_VALUE-2, authority, "😀".repeat(80), "😀".repeat(240),
        (0..2).map { w -> DraftWorkout(id(w+2), "😀".repeat(40), (0..3).map { i ->
            val index = w*4+i
            DraftItem(id(index+10), "system", "z".repeat(128), null, "a".repeat(64),
                "😀".repeat(if (index == 0) 120 else if (index == 1) 80 else 1), DraftPrescription(null,1000,3600,1_000_000))
        }) })
    @Test fun boundarySerializationAndStructuralValidation() {
        val d = draft(); assertTrue(d.valid())
        val encoded = DraftSerialization.resolver("\u0002".repeat(128), "or1-b91f0176ec74475e8cf7406e25b7e9d3-" + "a".repeat(80), d)
        assertTrue(encoded.contains("\"sourceRevision\":null")); assertTrue(encoded.contains("\"sets\":null"))
        assertTrue(encoded.length <= 8192); assertTrue(encoded.encodeToByteArray().size <= 24576)
        println("DRAFT_RESOLVER units=${encoded.length} bytes=${encoded.encodeToByteArray().size}")
        val boundary=DraftProtocol.write("\u0002".repeat(128),d,resolutionCommandKey("a".repeat(80))).pending("\u0001".repeat(128))!!
        val descriptor=InvitationJson.request(CommandResolution.original(boundary))
        val saveCallable="{\"data\":$descriptor}".encodeToByteArray().size
        val resolveCallable="{\"data\":${CommandResolution.request(boundary)}}".encodeToByteArray().size
        assertEquals(8854,saveCallable);assertEquals(8889,resolveCallable)
        assertEquals(canonical(DraftSerialization.descriptor(boundary.workspaceId!!,boundary.idempotencyKey,d)),canonical(CommandResolution.descriptor(boundary)))
        println("DRAFT_CALLABLE save=$saveCallable resolve=$resolveCallable descriptorUnits=${descriptor.length} descriptorBytes=${descriptor.encodeToByteArray().size}")
        assertFalse(d.copy(title="😀".repeat(81)).valid())
        assertFalse(d.copy(workouts=d.workouts+d.workouts.first()).valid())
        assertFalse(d.copy(workouts=listOf(d.workouts.first().copy(items=listOf(d.workouts.first().items.first().copy(prescription=DraftPrescription(0,null,null,null)))))).valid())
    }
    @Test fun productionProofStatesAndNonMutatingAdmission() {
        val uid="A"
        val request=InvitationRequest("save-program-draft",workspaceId="w",idempotencyKey=resolutionCommandKey("a".repeat(80)),draftSave=draft())
        val p=request.pending(uid)!!
        assertTrue(validPendingCommand(uid,p))
        val old=InvitationRequest("create",workspaceId="w",email="synthetic@example.test",idempotencyKey="prior").pending(uid)!!
        val prior=CommandTerminalProof(old,"normal-v1",acknowledgment=InvitationReply(invitationId=CommandResolution.id(old),workspaceId="w",status="pending",tokenVersion=1,expiresAtMillis=1000,replayed=true))
        assertTrue(prior.valid())
        val record=AccountPersistenceEnvelope(uid=uid,lastSettledCommand=prior,preferredWorkspaceId="w")
        assertTrue(DraftBudget.admission(record,p))
        DraftBudget.proofs(p).forEach { assertTrue(it.valid()); assertTrue(it.retired().valid()) }
        val states=DraftBudget.states(record,p)
        assertTrue(states.all { DraftBudget.plaintext(it).encodeToByteArray().size <= 36500 })
        println("DRAFT_PRODUCTION pending=${PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),p).encodeToByteArray().size} maxEnvelope=${states.maxOf { DraftBudget.plaintext(it).encodeToByteArray().size }} hash=${CommandResolution.hash(p)}")
        val oversized=record.copy(preferredWorkspaceId="x".repeat(36500))
        val before=DraftBudget.plaintext(oversized)
        assertFalse(DraftBudget.admission(oversized,p));assertTrue(before==DraftBudget.plaintext(oversized),"Admission mutated the original record")
        class Store:AccountPersistencePrototype {
            var writes=0
            override fun read(uid:String)=oversized
            override fun write(envelope:AccountPersistenceEnvelope){writes++}
            override fun delete(uid:String)=error("forbidden")
        }
        val store=Store()
        assertFalse(InvitationLedger(store).prepare(uid,request));assertEquals(0,store.writes)
        assertEquals(97517,DraftBudget.swiftEncryptedUpperBound(36500))
    }
    @Test fun sourceHashNumbersAndStrictAcknowledgmentTypes() {
        val escaped=DraftSave(id(1),0,ExerciseAuthority(1,1,1),"Quote \" slash / backslash \\ café 😀","Line one\nLine two \u2028 漢",emptyList())
        val escapedCommand=DraftProtocol.write("w",escaped,resolutionCommandKey("escaped")).pending("A")!!
        assertTrue(escaped.valid())
        assertEquals("ba1653c9e5c922c96c380336e3c980645ddfb6251c0351904563ed43a1f60772",CommandResolution.hash(escapedCommand))
        val source=com.delminiusapps.tillfailure.exercises.SystemExercise("synthetic","Synthetic exercise","synthetic exercise",emptyList(),emptyList(),"Original synthetic text.")
        assertEquals("a7b1b8560a24779cf202e9618d4083da05dcc2b725bfdbfd77ee18d430899647",DraftSources.system(id(5),source).sourceHash)
        assertEquals(1234,DraftNumbers(kg="1.234").parse().loadGrams)
        assertEquals(0,DraftNumbers(rest="0",kg="0").parse().loadGrams)
        for(raw in listOf("1.","-1","NaN","1.0001","1000.001","1,2"))assertTrue(runCatching{DraftNumbers(kg=raw).parse()}.isFailure)
        val request=InvitationRequest("save-program-draft",workspaceId="w",idempotencyKey=resolutionCommandKey("strict"),draftSave=draft())
        val good="""{"status":"draft-saved","workspaceId":"w","templateId":"${id(1)}","templateRevision":2147483646,"replayed":false}"""
        assertTrue(DraftProtocol.parse(request,good).draftDecision!=null)
        assertTrue(runCatching{DraftProtocol.parse(request,good.replace(":2147483646",":\"2147483646\""))}.isFailure)
        assertTrue(runCatching{DraftProtocol.parse(request,good.dropLast(1)+",\"title\":\"extra\"}")}.isFailure)
    }
    @Test fun exactPrepareProofRetirementAndPreferenceFence() {
        class Store:AccountPersistencePrototype {
            var value=AccountPersistenceEnvelope(uid="A");var writes=0;var failAt=0
            override fun read(uid:String)=value
            override fun write(envelope:AccountPersistenceEnvelope){writes++;if(writes==failAt)error("storage");value=envelope}
            override fun delete(uid:String)=error("forbidden")
        }
        for(outcome in listOf("saved","conflict","source","applied","not-applied-conflict","not-applied-fenced"))for(failure in 0..2) {
            val store=Store();val ledger=InvitationLedger(store)
            val request=InvitationRequest("save-program-draft",workspaceId="w",idempotencyKey=resolutionCommandKey("settle"),draftSave=draft())
            assertTrue(ledger.draftAdmission("A",request));assertTrue(ledger.prepare("A",request))
            val original=DraftBudget.plaintext(store.value);ledger.saveWorkspacePreference("A","different");assertTrue(original==DraftBudget.plaintext(store.value),"Preference writer changed pending metadata")
            val p=request.pending("A")!!
            val resolving=outcome in setOf("applied","not-applied-conflict","not-applied-fenced")
            val network=if(resolving)CommandResolution.resolveRequest(p) else request
            if(resolving)assertTrue(ledger.prepare("A",network))
            val reply=if(resolving)InvitationReply(terminalOutcome=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),outcome,1000)) else InvitationReply(draftDecision=DraftDecision(if(outcome=="saved")"draft-saved" else if(outcome=="source")"draft-source-conflict" else "draft-conflict","w",p.draftSave!!.templateId,if(outcome=="source")p.draftSave.expectedRevision else Int.MAX_VALUE-1,false))
            if(failure>0)store.failAt=store.writes+failure
            val settled=ledger.settle("A",network,InvitationOutcome(reply=reply))
            if(failure==0)assertTrue(settled.error==null) else assertEquals("storage-unavailable",settled.error)
            store.failAt=0
            val restarted=InvitationLedger(store)
            if(failure==1)assertTrue(restarted.settle("A",network,InvitationOutcome(reply=reply)).error==null)
            assertTrue(restarted.pending("A")==null)
            val marker=restarted.lastSettled("A")!!;assertTrue(marker.valid());assertTrue(marker.command==null)
            assertFalse(DraftBudget.plaintext(store.value).contains("draftSave"));assertFalse(restarted.prepare("A",request))
        }
    }
    @Test fun departureMarkerIsDominatedByTheSmallestFullPendingProof() {
        val minimal=DraftSave(id(1),0,ExerciseAuthority(1,1,1),"","",emptyList())
        var minimumMargin=Int.MAX_VALUE
        for(uid in listOf("A","\u0001".repeat(128)))for(wid in listOf("w","\u0002".repeat(128))) {
            val p=InvitationRequest("save-program-draft",workspaceId=wid,idempotencyKey=resolutionCommandKey("a"),draftSave=minimal).pending(uid)!!.copy(resolutionStarted=true)
            val record=AccountPersistenceEnvelope(uid=uid)
            val shortest=CommandTerminalProof(p,"normal-v1",acknowledgment=InvitationReply(draftDecision=DraftDecision("draft-saved",wid,minimal.templateId,1,true)))
            val full=record.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=shortest)
            val longest=DraftBudget.proofs(p).maxBy { DraftBudget.plaintext(record.copy(lastSettledCommand=it.retired())).length }.retired()
            val departing=record.copy(lastSettledCommand=longest,switchMarker=AccountSwitchMarker(departingUid=uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))
            val margin=DraftBudget.plaintext(full).encodeToByteArray().size-DraftBudget.plaintext(departing).encodeToByteArray().size
            minimumMargin=minOf(minimumMargin,margin);assertTrue(margin>0)
        }
        // Other metadata is copied identically. UID escaping occurs twice in both compared states;
        // larger workspace/key/revision/content fields only grow the full pending proof.
        println("DRAFT_DEPARTURE minimumDominanceMargin=$minimumMargin")
    }
}
