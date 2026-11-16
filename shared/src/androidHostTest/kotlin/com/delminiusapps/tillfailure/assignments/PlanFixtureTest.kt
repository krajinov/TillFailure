package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.clients.NoteSave
import com.delminiusapps.tillfailure.clients.ClientRevocation
import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.programs.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

class PlanFixtureTest {
    private fun oldAssignment()=AssignmentCreate("00000000-0000-4000-8000-000000000003","00000000-0000-4000-8000-000000000004","B","00000000-0000-4000-8000-000000000001","v0000000001",1,"4f9654a02f80d757330183becea5820127d1f346d1ca03f8d2f8424f652afcbe",1,"7a829022c9cb65899501c5e2da3f1286e97d65bcccd26395636290734cf84b78",AssignmentAuthority(1,1,1,1,1,1),1800000000000,1800000000001,1800086400001)
    @Test fun crossLanguageAndNativeExport() {
        assumeTrue("PR2a cross-language/native export requires explicit opt-in",System.getenv("TF_M7_PR2A_FIXTURES")!=null)
        assertEquals("1",System.getenv("TF_M7_PR2A_FIXTURES"))
        val root=Path.of(requireNotNull(System.getenv("TF_M7_PR2A_EVIDENCE")))
        val names=listOf("ascii","single","escaped")+listOf(128512,30028,34,47,92).flatMap{c->listOf(false,true).map{"boundary-$c-$it"}}
        val output=root.resolve("native-fixtures");Files.createDirectories(output)
        var maximum=0;var pendingMaximum=0;var states=0;var historiesCount=0
        for(name in names) {
            val file=root.resolve("ts/$name.json").toFile();assertTrue(file.isFile,"Missing explicit fixture $name")
            val f=AssignmentContract.json.parseToJsonElement(file.readText()).jsonObject
            val uid=f.getValue("uid").jsonPrimitive.content;val wid=f.getValue("wid").jsonPrimitive.content
            val d=PlanFeasibility.parse(uid,f.getValue("descriptor").toString())
            val version=AssignmentContract.json.decodeFromJsonElement<ProgramVersion>(f.getValue("version"))
            assertEquals(f.getValue("copy"),AssignmentContract.json.encodeToJsonElement(PlanFeasibility.copy(version,uid,wid,d.assignmentPlan.templateId)))
            assertEquals(f.getValue("binding").jsonObject.getValue("id").jsonPrimitive.content,PlanFeasibility.id(uid,d))
            assertEquals(f.getValue("binding").jsonObject.getValue("hash").jsonPrimitive.content,PlanFeasibility.hash(uid,d))
            assertEquals(d.assignmentPlan.snapshotHash,AssignmentContract.copyHash(PlanFeasibility.copy(version,uid,wid,d.assignmentPlan.templateId)))
            assertEquals(version.workouts.map{it.id},d.assignmentPlan.occurrences.map{it.workoutId})
            val expected=f.getValue("documents").jsonArray.associate { row->row.jsonArray[0].jsonPrimitive.content to row.jsonArray[1].jsonObject }
            val copied=PlanFeasibility.copy(version,uid,wid,d.assignmentPlan.templateId)
            assertEquals(expected,PlanGraphFeasibility.documents(uid,d,copied))
            val basePath="users/${d.assignmentPlan.clientUid}/workspaces/$wid/assignedPrograms/${d.assignmentPlan.assignmentId}"
            val own=expected.filterKeys{it==basePath || it.startsWith("$basePath/")}
            assertEquals(2,PlanGraphFeasibility.policy(d.assignmentPlan.clientUid,wid,d.assignmentPlan.assignmentId,own))
            assertFalse(AssignmentClientDocuments.valid(d.assignmentPlan.clientUid,wid,d.assignmentPlan.assignmentId,own))
            for((path,value) in own) {
                assertFalse(PlanGraphFeasibility.valid(d.assignmentPlan.clientUid,wid,d.assignmentPlan.assignmentId,own-path))
                assertFalse(PlanGraphFeasibility.valid(d.assignmentPlan.clientUid,wid,d.assignmentPlan.assignmentId,own+(path to JsonObject(value+("privateNote" to JsonPrimitive("excluded"))))))
            }

            assertFails{AssignmentContract.parseDescriptor(uid,f.getValue("descriptor").toString())}
            val note=InvitationRequest("save-trainer-note",workspaceId=wid,idempotencyKey="\u0004".repeat(128),noteSave=NoteSave("\u0003".repeat(128),"😀".repeat(2000),"a".repeat(64),Int.MAX_VALUE-2,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1)).pending(uid)!!
            val prior=CommandTerminalProof(note,"normal-v1",acknowledgment=InvitationReply(noteDecision=com.delminiusapps.tillfailure.clients.NoteDecision("note-saved",wid,"\u0003".repeat(128),Int.MAX_VALUE-1,false)))
            assertTrue(prior.valid())
            val clean=AccountPersistenceEnvelope(uid=uid,preferredWorkspaceId=wid)
            // Actual production legacy serializer, with original full text proof retained.
            val histories=mutableListOf("empty" to clean,"full-note" to clean.copy(lastSettledCommand=prior),"note-marker" to clean.copy(lastSettledCommand=prior.retired()))
            if(file.name=="boundary-128512-false.json") {
                val old=listOf(
                    InvitationRequest("create",workspaceId=wid,idempotencyKey="old-create",email="synthetic@example.test"),
                    InvitationRequest("resend",workspaceId=wid,idempotencyKey="old-resend",invitationId="a".repeat(64),expectedVersion=1),
                    InvitationRequest("revoke",workspaceId=wid,idempotencyKey="old-revoke",invitationId="a".repeat(64),expectedVersion=1),
                    InvitationRequest("accept",idempotencyKey=resolutionCommandKey("old-accept"),invitationId="a".repeat(64),expectedVersion=1,token="synthetic"),
                    InvitationRequest("complete-onboarding",workspaceId=wid,idempotencyKey="old-onboarding",onboarding=com.delminiusapps.tillfailure.onboarding.OnboardingCompletion("Synthetic","bs-BA","Europe/Sarajevo",true,1,1)),
                    InvitationRequest("update-profile",workspaceId=wid,idempotencyKey="old-profile",profileUpdate=com.delminiusapps.tillfailure.profile.ProfileUpdate("Synthetic","bs-BA","Europe/Sarajevo",1)),
                    InvitationRequest("revoke-client",workspaceId=wid,idempotencyKey="old-revocation",revocation=ClientRevocation("C",1,1,1,1)),
                    InvitationRequest("save-trainer-note",workspaceId=wid,idempotencyKey="old-note",noteSave=note.noteSave),
                    CustomExerciseProtocol.write(wid,CustomExerciseWrite(d.assignmentPlan.templateId,0,ExerciseAuthority(1,1,1),"Synthetic","Synthetic",emptyList(),emptyList()),"old-custom-create"),
                    CustomExerciseProtocol.write(wid,CustomExerciseWrite(d.assignmentPlan.templateId,1,ExerciseAuthority(1,1,1),"Synthetic","Synthetic",emptyList(),emptyList()),"old-custom-correct"),
                    DraftProtocol.write(wid,ProgramDraftTest().draft(),resolutionCommandKey("old-draft")),
                    PublicationProtocol.write(wid,ProgramPublish(d.assignmentPlan.templateId,1,ExerciseAuthority(1,1,1),"a".repeat(64),1),resolutionCommandKey("old-publication")),
                    AssignmentProtocol.write(wid,oldAssignment(),resolutionCommandKey("old-assignment"))
                )
                for(request in old) {
                    val p=request.pending(uid)!!;assertTrue(validPendingCommand(uid,p))
                    val proof=CommandTerminalProof(p,"resolution-v1",resolution=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),"applied",1000))
                    assertTrue(proof.valid())
                    for((n,settled) in listOf(proof,proof.retired()).withIndex())histories+="${CommandResolution.kind(p)}-$n" to clean.copy(lastSettledCommand=settled)
                }
            }
            for((history,base) in histories) {
                assertTrue(PlanFeasibility.admission(base,d));historiesCount++
                val oldBytes=PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),base)
                assertEquals(base,PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),oldBytes))
                for((index,state) in PlanFeasibility.states(base,d).withIndex()) {
                    val bytes=state.toString().encodeToByteArray();maximum=maxOf(maximum,bytes.size)
                    assertTrue(bytes.size<=36500 && DraftBudget.swiftEncryptedUpperBound(bytes.size)<=98304)
                    if(state.getValue("pendingInvitationCommands").jsonArray.isNotEmpty())assertFails{PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),state.toString())}
                    Files.write(output.resolve("$name-$history-$index.json"),bytes);states++
                }
                assertEquals(oldBytes,PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),base))
            }
            pendingMaximum=maxOf(pendingMaximum,PlanFeasibility.pending(uid,d,true).toString().encodeToByteArray().size)
            if(name=="boundary-128512-false") {
                val max=PlanFeasibility.states(clean,d).maxOf{it.toString().encodeToByteArray().size}
                val filled=clean.copy(preferredWorkspaceId=wid+"x".repeat(36500-max))
                assertTrue(PlanFeasibility.admission(filled,d))
                assertEquals(36500,PlanFeasibility.states(filled,d).maxOf{it.toString().encodeToByteArray().size})
                for((index,state) in PlanFeasibility.states(filled,d).withIndex()){Files.write(output.resolve("ceiling-$index.json"),state.toString().encodeToByteArray());states++}
                maximum=36500
            }
            val over=clean.copy(preferredWorkspaceId="x".repeat(36500))
            assertFalse(PlanFeasibility.admission(over,d))
            Files.write(output.resolve("$name-oversize-prior.json"),DraftBudget.plaintext(over).encodeToByteArray())
        }
        val summary="{\"states\":$states,\"histories\":$historiesCount,\"pending\":$pendingMaximum,\"maximumPlaintext\":$maximum,\"swiftBound\":${DraftBudget.swiftEncryptedUpperBound(maximum)}}"
        println("M7_PR2A_KOTLIN $summary");Files.write(root.resolve("kotlin-measurements.json"),summary.encodeToByteArray())
    }
}
