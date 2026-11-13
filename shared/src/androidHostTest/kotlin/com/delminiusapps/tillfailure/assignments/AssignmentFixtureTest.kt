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
import kotlin.test.*

class AssignmentFixtureTest {
    @Test fun actualTsKotlinContractsAndProspectiveStateExport() {
        val repo=generateSequence(Path.of(System.getProperty("user.dir"))){it.parent}.first{Files.exists(it.resolve("firebase/functions/package.json"))}
        val root=repo.resolve(".firebase/m7-pr1a")
        val files=root.resolve("ts").toFile().listFiles().orEmpty().filter{it.extension=="json"}
        assertTrue(files.size>=7,"Run TS contract evidence export first")
        val output=root.resolve("native-fixtures");Files.createDirectories(output)
        var maximum=0;var pendingMaximum=0;var states=0
        for(file in files) {
            val f=AssignmentContract.json.parseToJsonElement(file.readText()).jsonObject
            val uid=f.getValue("uid").jsonPrimitive.content
            val wid=f.getValue("wid").jsonPrimitive.content
            val d=AssignmentContract.parseDescriptor(uid,f.getValue("descriptor").toString())
            val version=AssignmentContract.json.decodeFromJsonElement<ProgramVersion>(f.getValue("version"))
            val copied=AssignmentContract.copy(version,uid,wid,d.assignment.templateId)
            assertEquals(f.getValue("copy"),AssignmentContract.json.encodeToJsonElement(copied))
            assertEquals(d.assignment.snapshotHash,AssignmentContract.copyHash(copied))
            assertEquals(f.getValue("binding").jsonObject.getValue("id").jsonPrimitive.content,AssignmentContract.id(uid,d))
            assertEquals(f.getValue("binding").jsonObject.getValue("hash").jsonPrimitive.content,AssignmentContract.hash(uid,d))
            val basePath="users/${d.assignment.clientUid}/workspaces/$wid/assignedPrograms/${d.assignment.assignmentId}"
            val documents=f.getValue("documents").jsonArray.associate { row->row.jsonArray[0].jsonPrimitive.content to row.jsonArray[1].jsonObject }.filterKeys{it.startsWith(basePath)}
            assertTrue(AssignmentClientDocuments.valid(d.assignment.clientUid,wid,d.assignment.assignmentId,documents))
            if(file.name=="ascii.json") {
                for(client in listOf("../other","__reserved__","\uD800","","x".repeat(129))) {
                    val changedBase="users/$client/workspaces/$wid/assignedPrograms/${d.assignment.assignmentId}"
                    val changed=documents.map { (path,body)->
                        (changedBase+path.removePrefix(basePath)) to JsonObject(body+("clientId" to JsonPrimitive(client)))
                    }.toMap()
                    assertFalse(AssignmentClientDocuments.valid(client,wid,d.assignment.assignmentId,changed))
                }
                for(author in listOf("../other","__reserved__","\uD800")) {
                    val content=version.content().copy(ownerUid=author)
                    val invalid=version.copy(ownerUid=author,contentHash=PublicationProtocol.contentHash(content),
                        draftHash=PublicationProtocol.draftHash(content.draft(),author,wid))
                    assertFails { AssignmentContract.copy(invalid,author,wid,d.assignment.templateId) }
                }
            }
            for((path,body) in documents) {
                assertFalse(AssignmentClientDocuments.valid(d.assignment.clientUid,wid,d.assignment.assignmentId,documents-path))
                assertFalse(AssignmentClientDocuments.valid(d.assignment.clientUid,wid,d.assignment.assignmentId,documents+(path to JsonObject(body+("privateNote" to JsonPrimitive("excluded"))))))
            }
            val note=InvitationRequest("save-trainer-note",workspaceId=wid,idempotencyKey="\u0004".repeat(128),noteSave=NoteSave("\u0003".repeat(128),"😀".repeat(2000),"a".repeat(64),Int.MAX_VALUE-2,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1)).pending(uid)!!
            val prior=CommandTerminalProof(note,"normal-v1",acknowledgment=InvitationReply(noteDecision=com.delminiusapps.tillfailure.clients.NoteDecision("note-saved",wid,"\u0003".repeat(128),Int.MAX_VALUE-1,false)))
            assertTrue(prior.valid())
            val clean=AccountPersistenceEnvelope(uid=uid,preferredWorkspaceId=wid)
            // Actual production legacy serializer, with original full text proof retained.
            val histories=mutableListOf("empty" to clean,"full-note" to clean.copy(lastSettledCommand=prior),"note-marker" to clean.copy(lastSettledCommand=prior.retired()))
            if(file.name=="boundary-128512.json") {
                val old=listOf(
                    InvitationRequest("create",workspaceId=wid,idempotencyKey="old-create",email="synthetic@example.test"),
                    InvitationRequest("resend",workspaceId=wid,idempotencyKey="old-resend",invitationId="a".repeat(64),expectedVersion=1),
                    InvitationRequest("revoke",workspaceId=wid,idempotencyKey="old-revoke",invitationId="a".repeat(64),expectedVersion=1),
                    InvitationRequest("accept",idempotencyKey=resolutionCommandKey("old-accept"),invitationId="a".repeat(64),expectedVersion=1,token="synthetic"),
                    InvitationRequest("complete-onboarding",workspaceId=wid,idempotencyKey="old-onboarding",onboarding=com.delminiusapps.tillfailure.onboarding.OnboardingCompletion("Synthetic","bs-BA","Europe/Sarajevo",true,1,1)),
                    InvitationRequest("update-profile",workspaceId=wid,idempotencyKey="old-profile",profileUpdate=com.delminiusapps.tillfailure.profile.ProfileUpdate("Synthetic","bs-BA","Europe/Sarajevo",1)),
                    InvitationRequest("revoke-client",workspaceId=wid,idempotencyKey="old-revocation",revocation=ClientRevocation("C",1,1,1,1)),
                    InvitationRequest("save-trainer-note",workspaceId=wid,idempotencyKey="old-note",noteSave=note.noteSave),
                    CustomExerciseProtocol.write(wid,CustomExerciseWrite(d.assignment.templateId,0,ExerciseAuthority(1,1,1),"Synthetic","Synthetic",emptyList(),emptyList()),"old-custom-create"),
                    CustomExerciseProtocol.write(wid,CustomExerciseWrite(d.assignment.templateId,1,ExerciseAuthority(1,1,1),"Synthetic","Synthetic",emptyList(),emptyList()),"old-custom-correct"),
                    DraftProtocol.write(wid,ProgramDraftTest().draft(),resolutionCommandKey("old-draft")),
                    PublicationProtocol.write(wid,ProgramPublish(d.assignment.templateId,1,ExerciseAuthority(1,1,1),"a".repeat(64),1),resolutionCommandKey("old-publication"))
                )
                for(request in old) {
                    val p=request.pending(uid)!!;assertTrue(validPendingCommand(uid,p))
                    val proof=CommandTerminalProof(p,"resolution-v1",resolution=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),"applied",1000))
                    assertTrue(proof.valid())
                    for((n,settled) in listOf(proof,proof.retired()).withIndex())histories+="${CommandResolution.kind(p)}-$n" to clean.copy(lastSettledCommand=settled)
                }
            }
            for((history,base) in histories) {
                assertTrue(AssignmentProspective.admission(base,d))
                pendingMaximum=maxOf(pendingMaximum,AssignmentProspective.pending(uid,d,true).toString().encodeToByteArray().size)
                AssignmentProspective.states(base,d).forEachIndexed{index,state->
                    val bytes=state.toString().encodeToByteArray();maximum=maxOf(maximum,bytes.size)
                    assertTrue(bytes.size<=36500 && DraftBudget.swiftEncryptedUpperBound(bytes.size)<=98304)
                    Files.write(output.resolve("${file.nameWithoutExtension}-$history-$index.json"),bytes);states++
                }
            }
            if(file.name=="boundary-128512.json") {
                val base=clean.copy(lastSettledCommand=prior)
                val max=AssignmentProspective.states(base,d).maxOf{it.toString().encodeToByteArray().size}
                val filled=base.copy(preferredWorkspaceId=wid+"x".repeat(36500-max))
                assertTrue(AssignmentProspective.admission(filled,d))
                assertEquals(36500,AssignmentProspective.states(filled,d).maxOf{it.toString().encodeToByteArray().size})
                AssignmentProspective.states(filled,d).forEachIndexed{index,state->Files.write(output.resolve("ceiling-$index.json"),state.toString().encodeToByteArray());states++}
                maximum=36500
            }
            val over=clean.copy(preferredWorkspaceId="x".repeat(36500))
            assertFalse(AssignmentProspective.admission(over,d))
            Files.write(output.resolve("${file.nameWithoutExtension}-oversize-prior.json"),DraftBudget.plaintext(over).encodeToByteArray())
        }
        println("M7_KOTLIN_STATES count=$states pending=$pendingMaximum maximumPlaintext=$maximum swiftBound=${DraftBudget.swiftEncryptedUpperBound(maximum)}")
        Files.write(root.resolve("kotlin-measurements.json"),"{\"states\":$states,\"pending\":$pendingMaximum,\"maximumPlaintext\":$maximum,\"swiftBound\":${DraftBudget.swiftEncryptedUpperBound(maximum)}}".encodeToByteArray())
    }
}
