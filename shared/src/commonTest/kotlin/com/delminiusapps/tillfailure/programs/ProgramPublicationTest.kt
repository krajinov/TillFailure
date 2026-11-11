package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProgramPublicationTest {
    val id="00000000-0000-4000-8000-000000000001"
    fun publish()=ProgramPublish(id,1,ExerciseAuthority(1,1,1),"a".repeat(64),1)
    fun request()=InvitationRequest("publish-program-template",workspaceId="w",idempotencyKey=resolutionCommandKey("publication-vector"),publish=publish())
    fun content():PublicationContent {
        val source=SystemExercise("synthetic","Synthetic","synthetic",listOf("legs"),listOf("bodyweight"),"Original synthetic instructions.")
        val selected=DraftSources.system(id,source)
        return PublicationContent(1,"w",id,"A","Synthetic program","",listOf(PublicationWorkout(id,"Workout",listOf(PublicationItem(id,"system","synthetic",null,selected.sourceHash,"Synthetic",DraftPrescription(3,10,60,null),source.instructions,source.muscleGroups,source.equipment)))))
    }
    @Test fun crossLanguageCanonicalVectorsAndOldFormOmission() {
        val p=request().pending("A")!!;assertTrue(validPendingCommand("A",p))
        assertEquals("f3305d4ae3cd01f3ff117fb22b54b078b2bda261d9632501087de6552b883f6c",CommandResolution.id(p))
        assertEquals("9f2f8f049c555ec488c4e01388432921094a3250b348175081a665338cb0584c",CommandResolution.hash(p))
        assertEquals("program-template-publish",CommandResolution.kind(p));assertEquals("program-publication-v1",CommandResolution.version(p))
        assertFalse(PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),InvitationRequest("create",workspaceId="w",email="a@b.c",idempotencyKey="old").pending("A")!!).contains("publish"))
        val c=content();assertTrue(c.valid("A","w"))
        println("PUBLICATION_CONTENT_VECTOR draft=${PublicationProtocol.draftHash(c.draft(),"A","w")} content=${PublicationProtocol.contentHash(c)}")
        val changed=c.copy(workouts=c.workouts.map{w->w.copy(items=w.items.map{it.copy(instructions="changed")})})
        assertNotEquals(PublicationProtocol.contentHash(c),PublicationProtocol.contentHash(changed))
        // Instructions are bound through the saved source hash, which the server must verify afresh.
        assertEquals(PublicationProtocol.draftHash(c.draft(),"A","w"),PublicationProtocol.draftHash(changed.draft(),"A","w"))
    }
    @Test fun escapedContentUsesTheTypeScriptCanonicalHashes() {
        val name="Quote \" slash / backslash \\ café 😀"
        val c=content().let{it.copy(title=name,description="Line one\nLine two \u2028 漢",workouts=it.workouts.map{w->w.copy(items=w.items.map{i->i.copy(name=name,instructions="Line one\nLine two \u2028 漢 😀",sourceHash="f9337399d0ffac4bdf940405dd80f4378d8d9cf731efadf384b41a6d7c4cc95b")})})}
        assertTrue(c.valid("A","w"))
        assertEquals("d4e65118252352d8332e2302fb4f039b9b265402c286ad3b39737764c0135de1",PublicationProtocol.draftHash(c.draft(),"A","w"))
        assertEquals("3435dade581cc2a0148fe948ca298a81b7bdd3bb0edef9defc3e5f408c9b40da",PublicationProtocol.contentHash(c))
    }
    @Test fun strictPreviewVersionAndDecisionParsing() {
        val c=content();val hash=PublicationProtocol.draftHash(c.draft(),"A","w");val p=publish().copy(draftHash=hash)
        val preview=PublicationPreview("w",p,1,c,PublicationProtocol.contentHash(c))
        val r=PublicationReadRequest("w",id,"publication-prepare",1,hash);val scope=ExerciseScope("A","w",1)
        val body=PublicationProtocol.json.encodeToString(PublicationPreview.serializer(),preview)
        assertEquals(preview,PublicationReads.result(scope,r,body,null).preview)
        for(bad in listOf(body.replace("\"expectedRevision\":1","\"expectedRevision\":\"1\""),body.replace("Original synthetic instructions.","Tampered"),body.replace("\"loadGrams\":null,",""),body.dropLast(1)+",\"extra\":true}"," ".repeat(PublicationProtocol.RESPONSE_BYTES)+body)) {
            if(bad!=body)assertNotNull(PublicationReads.result(scope,r,bad,null).error)
        }
        val version=ProgramVersion(1,1,"w",id,"A",c.title,c.description,c.workouts,PublicationProtocol.versionId(1),1,1,hash,preview.contentHash,1000)
        val vbody=buildJsonObject{put("workspaceId","w");put("version",PublicationProtocol.json.encodeToJsonElement(version))}.toString()
        assertEquals(version,PublicationReads.result(scope,PublicationReadRequest("w",id,"version-get",versionId=version.versionId),vbody,null).version)
        val request=request();val good=PublicationDecision("program-published","w",id,2,PublicationProtocol.versionId(1),1,preview.contentHash,false)
        val encoded=PublicationProtocol.json.encodeToString(PublicationDecision.serializer(),good)
        assertEquals(good,PublicationProtocol.parse(request,encoded).publicationDecision)
        assertTrue(runCatching{PublicationProtocol.parse(request,encoded.replace("\"versionNumber\":1","\"versionNumber\":\"1\""))}.isFailure)
        assertFalse(good.copy(templateId="wrong").valid("w",publish()));assertFalse(good.copy(status="publication-source-conflict").valid("w",publish()))
    }
    @Test fun exactProofBeforeRetirementRestartAndABIsolation() {
        class Store:AccountPersistencePrototype {
            val values=mutableMapOf("A" to AccountPersistenceEnvelope(uid="A"),"B" to AccountPersistenceEnvelope(uid="B"));var writes=0;var failAt=0
            override fun read(uid:String)=values[uid]
            override fun write(envelope:AccountPersistenceEnvelope){writes++;if(writes==failAt)error("storage");values[envelope.uid]=envelope}
            override fun delete(uid:String)=error("forbidden")
        }
        for(proof in PublicationBudget.proofs(request().pending("A")!!))for(failure in 0..2) {
            val store=Store();val ledger=InvitationLedger(store);val request=request();val p=request.pending("A")!!
            assertTrue(proof.valid());assertTrue(ledger.publicationAdmission("A",request));assertTrue(ledger.prepare("A",request))
            val b=DraftBudget.plaintext(store.values.getValue("B"));val before=DraftBudget.plaintext(store.values.getValue("A"));ledger.saveWorkspacePreference("A","new");assertEquals(before,DraftBudget.plaintext(store.values.getValue("A")))
            val network=if(proof.resolution!=null)CommandResolution.resolveRequest(p) else request
            if(proof.resolution!=null)assertTrue(ledger.prepare("A",network))
            val outcome=InvitationOutcome(reply=proof.acknowledgment?:InvitationReply(terminalOutcome=proof.resolution))
            if(failure>0)store.failAt=store.writes+failure
            ledger.settle("A",network,outcome);store.failAt=0
            val restarted=InvitationLedger(store)
            if(failure==1)assertNull(restarted.settle("A",network,outcome).error)
            assertNull(restarted.pending("A"));assertTrue(restarted.lastSettled("A")!!.valid());assertFalse(restarted.prepare("A",request))
            assertEquals(b,DraftBudget.plaintext(store.values.getValue("B")))
            val text=DraftBudget.plaintext(store.values.getValue("A"));assertFalse(text.contains("draftHash"));assertFalse(text.contains("templateId"));assertFalse(text.contains("versionId"))
        }
    }
    @Test fun actualProspectiveBoundsAndNonMutatingOversizeAdmission() {
        val p=request().pending("A")!!;val base=AccountPersistenceEnvelope(uid="A");assertTrue(PublicationBudget.admission(base,p))
        val states=PublicationBudget.states(base,p)
        assertTrue(states.all{DraftBudget.plaintext(it).encodeToByteArray().size<=36500})
        val oversize=base.copy(preferredWorkspaceId="x".repeat(36500));var writes=0
        val store=object:AccountPersistencePrototype {override fun read(uid:String)=oversize;override fun write(envelope:AccountPersistenceEnvelope){writes++};override fun delete(uid:String)=error("forbidden")}
        assertFalse(InvitationLedger(store).prepare("A",request()));assertEquals(0,writes)
        assertEquals(97517,DraftBudget.swiftEncryptedUpperBound(36500))
        println("PUBLICATION_PRODUCTION pending=${PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),p).encodeToByteArray().size} maximum=${states.maxOf{DraftBudget.plaintext(it).encodeToByteArray().size}}")
    }
    @Test fun admissionAtTheCeilingLeavesCapacityForProtectedCleanDeparture() {
        for(uid in listOf("A","\u0001".repeat(128))) {
            val p=request().pending(uid)!!
            val empty=AccountPersistenceEnvelope(uid=uid,preferredWorkspaceId="")
            val maximum=PublicationBudget.states(empty,p).maxOf{DraftBudget.plaintext(it).encodeToByteArray().size}
            val near=empty.copy(preferredWorkspaceId="x".repeat(36500-maximum))
            assertTrue(PublicationBudget.admission(near,p))
            for(proof in PublicationBudget.proofs(p)) {
                val departure=near.copy(lastSettledCommand=proof.retired(),switchMarker=AccountSwitchMarker(departingUid=uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))
                val n=DraftBudget.plaintext(departure).encodeToByteArray().size
                assertTrue(n<=36500);assertTrue(DraftBudget.swiftEncryptedUpperBound(n)<=98304)
            }
        }
    }
    @Test fun schemaTwoReadRetainsMetadataButWriteExcludesIt() {
        val c=content();val d=SavedDraft(2,1,"w",id,"A",2,0,1,c.title,c.description,c.draft().workouts,1,PublicationProtocol.versionId(1))
        assertTrue(d.valid("A","w"));assertFalse(d.copy(versionCount=2).valid("A","w"));assertFalse(d.copy(schemaVersion=1).valid("A","w"))
        assertFalse(DraftSerialization.element(d.write(ExerciseAuthority(1,1,1))).toString().contains("versionCount"))
    }
}
