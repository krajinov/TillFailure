package com.delminiusapps.tillfailure.persistence

import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.exercises.ExerciseAuthority
import com.delminiusapps.tillfailure.programs.*
import com.delminiusapps.tillfailure.invitations.*
import java.util.UUID
import kotlin.test.*

class AndroidPublicationSizingTest {
    @Test fun productionEncryptedStatesAndRejectedAdmission() {
        val originalContext=InstrumentationRegistry.getInstrumentation().targetContext
        val context=object:android.content.ContextWrapper(originalContext) {
            private val isolated=java.io.File(originalContext.noBackupFilesDir,"m6p4-sizing-${UUID.randomUUID()}")
            override fun getNoBackupFilesDir()=isolated
        }
        val suffix=UUID.randomUUID().toString()
        val uid="m6p4_sizing_$suffix"
        val store=AndroidAtomicFilePersistence(context,"$RECOVERY_KEY_IDENTIFIER.m6p4.$suffix")
        val d=ProgramPublish("00000000-0000-4000-8000-000000000001",1,ExerciseAuthority(1,1,1),"a".repeat(64),1)
        val request=InvitationRequest("publish-program-template",workspaceId="w",idempotencyKey=resolutionCommandKey(UUID.randomUUID().toString()),publish=d)
        val p=request.pending(uid)!!
        val base=AccountPersistenceEnvelope(uid=uid,preferredWorkspaceId="w")
        store.write(base)
        val oversized=base.copy(preferredWorkspaceId="x".repeat(36500))
        store.write(oversized)
        val original=store.fileFor(uid).readBytes()
        assertFalse(PublicationBudget.admission(oversized,p))
        assertTrue(original.contentEquals(store.fileFor(uid).readBytes()),"Existing encrypted bytes changed")
        assertFalse(InvitationLedger(store).prepare(uid,request))
        assertTrue(original.contentEquals(store.fileFor(uid).readBytes()),"Existing encrypted bytes changed")
        for(state in PublicationBudget.states(base,p)) {
            store.write(state)
            assertTrue(state==AndroidAtomicFilePersistence(context,store.keyIdentifier).read(uid),"Round-trip state mismatch")
            val n=DraftBudget.plaintext(state).encodeToByteArray().size
            val encrypted=store.fileFor(uid).length()
            assertTrue(encrypted<=98304)
            println("ANDROID_PUBLICATION_STORE plaintext=$n encrypted=$encrypted")
        }
        val fixtures=java.io.File("/data/local/tmp/tillfailure-m6p4-native-fixtures").listFiles().orEmpty().filter { it.extension=="json" }
        assertTrue(fixtures.isNotEmpty(),"Push the synthetic host fixtures before running")
        for(fixture in fixtures) {
            val state=PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),fixture.readText())
            store.write(state)
            assertTrue(state==store.read(state.uid),"Boundary round-trip mismatch")
            println("ANDROID_BOUNDARY fixture=${fixture.name} plaintext=${fixture.length()} encrypted=${store.fileFor(state.uid).length()}")
        }
        // Deliberately keep this new synthetic partition and key. Never clean unrelated state.
    }
}
