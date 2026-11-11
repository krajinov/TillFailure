package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.clients.*
import com.delminiusapps.tillfailure.exercises.*
import java.nio.file.Files
import kotlin.test.*

class PublicationNativeFixtureTest {
    @Test fun actualWorstRetainedProofEnvelopes() {
        val root=java.nio.file.Path.of("/tmp/tillfailure-m6p4-native-fixtures");Files.createDirectories(root)
        val uid="\u0001".repeat(128);val wid="\u0002".repeat(128)
        val note=InvitationRequest("save-trainer-note",workspaceId=wid,idempotencyKey="\u0004".repeat(128),noteSave=NoteSave("\u0003".repeat(128),"😀".repeat(2000),"a".repeat(64),Int.MAX_VALUE-2,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1)).pending(uid)!!
        val prior=CommandTerminalProof(note,"normal-v1",acknowledgment=InvitationReply(noteDecision=NoteDecision("note-saved",wid,"\u0003".repeat(128),Int.MAX_VALUE-1,false)));assertTrue(prior.valid())
        val p=InvitationRequest("publish-program-template",workspaceId=wid,idempotencyKey=resolutionCommandKey("a".repeat(80)),publish=ProgramPublish("00000000-0000-4000-8000-000000000001",Int.MAX_VALUE-2,ExerciseAuthority(Int.MAX_VALUE-1,Int.MAX_VALUE-1,Int.MAX_VALUE-1),"f".repeat(64),1)).pending(uid)!!
        val base=AccountPersistenceEnvelope(uid=uid,preferredWorkspaceId=wid,lastSettledCommand=prior)
        assertTrue(PublicationBudget.admission(base,p));var maximum=0
        PublicationBudget.states(base,p).forEachIndexed{index,state->
            val bytes=DraftBudget.plaintext(state).encodeToByteArray();maximum=maxOf(maximum,bytes.size);Files.write(root.resolve("publication-$index.json"),bytes)
            assertTrue(bytes.size<=36500);assertTrue(DraftBudget.swiftEncryptedUpperBound(bytes.size)<=98304)
            if(state.pendingInvitationCommands.isEmpty()) {
                val departure=state.copy(switchMarker=AccountSwitchMarker(departingUid=uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))
                val encoded=DraftBudget.plaintext(departure).encodeToByteArray();assertTrue(encoded.size<=36500);Files.write(root.resolve("switch-$index.json"),encoded)
            }
        }
        println("PUBLICATION_NATIVE_FIXTURES maximumPlaintext=$maximum pending=${PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),p).encodeToByteArray().size} swiftBound=${DraftBudget.swiftEncryptedUpperBound(maximum)}")
    }
}
