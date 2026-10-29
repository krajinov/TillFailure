package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.clients.*
import com.delminiusapps.tillfailure.exercises.*
import java.nio.file.Files
import kotlin.test.*

class DraftNativeFixtureTest {
    @Test fun exportOnlySyntheticProductionEnvelopesAndAssessCustomBound() {
        val root=java.nio.file.Path.of("/tmp/tillfailure-m6p3-native-fixtures")
        Files.createDirectories(root)
        val uid="\u0001".repeat(128);val wid="\u0002".repeat(128)
        val old=InvitationRequest("save-trainer-note",workspaceId=wid,idempotencyKey="\u0004".repeat(128),noteSave=NoteSave("\u0003".repeat(128),"😀".repeat(2000),"a".repeat(64),Int.MAX_VALUE-2,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1)).pending(uid)!!
        val prior=CommandTerminalProof(old,"normal-v1",acknowledgment=InvitationReply(noteDecision=NoteDecision("note-saved",wid,"\u0003".repeat(128),Int.MAX_VALUE-1,false)))
        assertTrue(prior.valid())
        val base=AccountPersistenceEnvelope(uid=uid,lastSettledCommand=prior,preferredWorkspaceId=wid)
        val p=InvitationRequest("save-program-draft",workspaceId=wid,idempotencyKey=resolutionCommandKey("a".repeat(80)),draftSave=ProgramDraftTest().draft()).pending(uid)!!
        assertTrue(DraftBudget.admission(base,p))
        var maximum=0
        DraftBudget.states(base,p).forEachIndexed { index,state ->
            val data=DraftBudget.plaintext(state).encodeToByteArray();maximum=maxOf(maximum,data.size)
            Files.write(root.resolve("draft-$index.json"),data)
            if(state.pendingInvitationCommands.isEmpty()) {
                val departure=state.copy(switchMarker=AccountSwitchMarker(departingUid=uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))
                val switched=DraftBudget.plaintext(departure).encodeToByteArray();assertTrue(switched.size<=36500)
                Files.write(root.resolve("switch-$index.json"),switched)
            }
        }
        val older=listOf(
            InvitationRequest("create",workspaceId=wid,idempotencyKey="old-create",email="synthetic@example.test"),
            InvitationRequest("resend",workspaceId=wid,idempotencyKey="old-resend",invitationId="a".repeat(64),expectedVersion=1),
            InvitationRequest("revoke",workspaceId=wid,idempotencyKey="old-revoke",invitationId="a".repeat(64),expectedVersion=1),
            InvitationRequest("accept",idempotencyKey=resolutionCommandKey("old-accept"),invitationId="a".repeat(64),expectedVersion=1,token="synthetic"),
            InvitationRequest("complete-onboarding",workspaceId=wid,idempotencyKey="old-onboarding",onboarding=com.delminiusapps.tillfailure.onboarding.OnboardingCompletion("Synthetic","bs-BA","Europe/Sarajevo",true,1,1)),
            InvitationRequest("update-profile",workspaceId=wid,idempotencyKey="old-profile",profileUpdate=com.delminiusapps.tillfailure.profile.ProfileUpdate("Synthetic","bs-BA","Europe/Sarajevo",1)),
            InvitationRequest("revoke-client",workspaceId=wid,idempotencyKey="old-revocation",revocation=ClientRevocation("C",1,1,1,1)),
            InvitationRequest("save-trainer-note",workspaceId=wid,idempotencyKey="old-note",noteSave=old.noteSave),
            CustomExerciseProtocol.write(wid,CustomExerciseWrite("00000000-0000-4000-8000-000000000001",0,ExerciseAuthority(1,1,1),"Synthetic","Synthetic",emptyList(),emptyList()),"old-custom-create"),
            CustomExerciseProtocol.write(wid,CustomExerciseWrite("00000000-0000-4000-8000-000000000001",1,ExerciseAuthority(1,1,1),"Synthetic","Synthetic",emptyList(),emptyList()),"old-custom-correct")
        )
        for(request in older) {
            val command=request.pending(uid)!!
            assertTrue(validPendingCommand(uid,command))
            val proof=CommandTerminalProof(command,"resolution-v1",resolution=CommandResolutionReply(1,CommandResolution.kind(command),CommandResolution.id(command),CommandResolution.version(command),CommandResolution.hash(command),"applied",1000))
            assertTrue(proof.valid())
            for((index,settled) in listOf(proof,proof.retired()).withIndex()) {
                val record=base.copy(lastSettledCommand=settled)
                assertTrue(DraftBudget.admission(record,p))
                val state=DraftBudget.states(record,p).maxBy { DraftBudget.plaintext(it).encodeToByteArray().size }
                Files.write(root.resolve("prior-${CommandResolution.kind(command)}-$index.json"),DraftBudget.plaintext(state).encodeToByteArray())
            }
        }
        println("DRAFT_NATIVE_FIXTURES maximumPlaintext=$maximum")
        val custom=CustomExerciseProtocol.write("w",CustomExerciseWrite("00000000-0000-4000-8000-000000000001",0,ExerciseAuthority(1,1,1),"Synthetic","Synthetic",emptyList(),emptyList()),resolutionCommandKey("c")).pending("A")!!
        val customBase=AccountPersistenceEnvelope(uid="A",preferredWorkspaceId="x".repeat(50000))
        assertTrue(CustomExerciseBudget.admission(customBase,custom))
        val proof=CommandTerminalProof(custom,"normal-v1",acknowledgment=InvitationReply(exerciseDecision=CustomExerciseDecision("exercise-created","w",custom.exerciseWrite!!.exerciseId,1,false)))
        assertTrue(proof.valid())
        val customState=customBase.copy(pendingInvitationCommands=listOf(custom),pendingTerminalProof=proof)
        val data=DraftBudget.plaintext(customState).encodeToByteArray()
        assertTrue(data.size<65536);assertTrue(DraftBudget.swiftEncryptedUpperBound(data.size)>98304)
        Files.write(root.resolve("custom-admitted.json"),data)
        println("CUSTOM_ADMITTED plaintext=${data.size} swiftUpperBound=${DraftBudget.swiftEncryptedUpperBound(data.size)}")
        assertFalse(DraftBudget.admission(base.copy(switchMarker=AccountSwitchMarker(departingUid=uid,accountEpoch=1,state="SwitchingOut")),p))
    }
}
