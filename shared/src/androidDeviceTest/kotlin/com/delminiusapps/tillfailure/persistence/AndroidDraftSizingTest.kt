package com.delminiusapps.tillfailure.persistence

import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.exercises.ExerciseAuthority
import com.delminiusapps.tillfailure.programs.*
import com.delminiusapps.tillfailure.invitations.*
import java.util.UUID
import kotlin.test.*

class AndroidDraftSizingTest {
    @Test fun productionEncryptedStatesAndRejectedAdmission() {
        val originalContext=InstrumentationRegistry.getInstrumentation().targetContext
        val context=object:android.content.ContextWrapper(originalContext) {
            private val isolated=java.io.File(originalContext.noBackupFilesDir,"m6p3-sizing-${UUID.randomUUID()}")
            override fun getNoBackupFilesDir()=isolated
        }
        val suffix=UUID.randomUUID().toString()
        val uid="m6p3_sizing_$suffix"
        val store=AndroidAtomicFilePersistence(context,"$RECOVERY_KEY_IDENTIFIER.m6p3.$suffix")
        val d=DraftSave("00000000-0000-4000-8000-000000000001",0,ExerciseAuthority(1,1,1),"Synthetic", "",emptyList())
        val request=InvitationRequest("save-program-draft",workspaceId="w",idempotencyKey=resolutionCommandKey(UUID.randomUUID().toString()),draftSave=d)
        val p=request.pending(uid)!!
        val base=AccountPersistenceEnvelope(uid=uid,preferredWorkspaceId="w")
        store.write(base)
        val oversized=base.copy(preferredWorkspaceId="x".repeat(36500))
        store.write(oversized)
        val original=store.fileFor(uid).readBytes()
        assertFalse(DraftBudget.admission(oversized,p))
        assertTrue(original.contentEquals(store.fileFor(uid).readBytes()),"Existing encrypted bytes changed")
        assertFalse(InvitationLedger(store).prepare(uid,request))
        assertTrue(original.contentEquals(store.fileFor(uid).readBytes()),"Existing encrypted bytes changed")
        for(state in DraftBudget.states(base,p)) {
            store.write(state)
            assertTrue(state==AndroidAtomicFilePersistence(context,store.keyIdentifier).read(uid),"Round-trip state mismatch")
            val n=DraftBudget.plaintext(state).encodeToByteArray().size
            val encrypted=store.fileFor(uid).length()
            assertTrue(encrypted<=98304)
            println("ANDROID_DRAFT_STORE plaintext=$n encrypted=$encrypted")
        }
        val fixtures=java.io.File("/data/local/tmp/tillfailure-m6p3-native-fixtures").listFiles().orEmpty().filter { it.extension=="json" }
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
