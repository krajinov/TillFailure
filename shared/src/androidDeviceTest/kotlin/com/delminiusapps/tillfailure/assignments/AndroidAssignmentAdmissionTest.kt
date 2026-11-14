package com.delminiusapps.tillfailure.assignments

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.invitations.InvitationLedger
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.programs.DraftBudget
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import kotlin.test.*

/** Actual typed production writer and ledger refusal; no private raw-write reflection. */
class AndroidAssignmentAdmissionTest {
    @Test fun allProspectiveTypedStatesAndByteIdenticalLedgerRefusal() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val run=File(ctx.noBackupFilesDir,"m7-pr1b-typed-admission-${UUID.randomUUID()}")
        val fixtures=File("/data/local/tmp/tf-m7-pr1b-typed-fixtures").listFiles().orEmpty().filter{it.extension=="json" && !it.name.contains("oversize")}.sortedBy{it.name}
        val manifest=AssignmentContract.json.parseToJsonElement(File("/data/local/tmp/tf-m7-pr1b-typed-fixtures/inputs.sha256").readText()).jsonObject
        for((name,hash)in manifest) {
            val input=File("/data/local/tmp/tf-m7-pr1b-typed-fixtures",name).readBytes()
            val actual=java.security.MessageDigest.getInstance("SHA-256").digest(input).joinToString(""){"%02x".format(it.toInt() and 255)}
            assertEquals(hash.jsonPrimitive.content,actual,"Input checkpoint mismatch before native writes")
        }
        assertEquals(1748,fixtures.size,"Checkpoint every fresh input before this test")
        var maximum=0;var maximumPlaintext=0
        fun store(index:String)=AndroidAtomicFilePersistence(object:ContextWrapper(ctx){override fun getNoBackupFilesDir()=File(run,index)})
        for((index,file)in fixtures.withIndex()) {
            val record=PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),file.readText())
            val actual=DraftBudget.plaintext(record);assertEquals(file.readText().encodeToByteArray().size,actual.encodeToByteArray().size)
            val writer=store(index.toString());writer.write(record);assertEquals(record,writer.read(record.uid))
            val size=writer.rootDirectory.listFiles()!!.single{it.isFile}.length().toInt()
            maximum=maxOf(maximum,size);maximumPlaintext=maxOf(maximumPlaintext,actual.encodeToByteArray().size)
            assertTrue(actual.encodeToByteArray().size<=36500 && size<=98304)
        }
        val first=PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),fixtures.first().readText());val pending=first.pendingInvitationCommands.single()
        val prior=first.copy(preferredWorkspaceId="x".repeat(36500),pendingInvitationCommands=emptyList(),pendingTerminalProof=null,lastSettledCommand=null,switchMarker=null)
        val writer=store("refusal");writer.write(prior);val original=writer.rootDirectory.listFiles()!!.single{it.isFile}.readBytes()
        val ledger=InvitationLedger(writer);val request=AssignmentProtocol.write(pending.workspaceId!!,pending.assignment!!,pending.idempotencyKey)
        assertFalse(ledger.assignmentAdmission(prior.uid,request));assertFalse(ledger.prepare(prior.uid,request))
        assertTrue(original.contentEquals(writer.rootDirectory.listFiles()!!.single{it.isFile}.readBytes()))
        println("M7P1B_ANDROID_TYPED_ADMISSION states=${fixtures.size} maximumPlaintext=$maximumPlaintext maximumEncrypted=$maximum oversizeByteIdentical=true")
    }
}
