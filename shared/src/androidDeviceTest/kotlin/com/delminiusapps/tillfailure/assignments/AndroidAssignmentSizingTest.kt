package com.delminiusapps.tillfailure.assignments

import android.content.ContextWrapper
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.*

class AndroidAssignmentSizingTest {
    @Test fun futureBytesThroughProductionAtomicEncryptionWithoutNewCommandAdmission() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val run=File(ctx.noBackupFilesDir,"m7-pr1a-sizing-${UUID.randomUUID()}")
        val fixtures=File("/data/local/tmp/tf-m7-pr1a-fixtures").listFiles().orEmpty().filter{it.extension=="json" && !it.name.contains("oversize")}.sortedBy{it.name}
        assertTrue(fixtures.isNotEmpty(),"Independent checkpointed fixtures must be pushed first")
        val writer=AndroidAtomicFilePersistence::class.java.getDeclaredMethod("writePlaintextLocked",AccountPersistenceEnvelope::class.java,Function0::class.java).apply{isAccessible=true}
        val keyMethod=AndroidAtomicFilePersistence::class.java.getDeclaredMethod("encryptionKey",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        var maximum=0L
        for((index,file) in fixtures.withIndex()) {
            val plaintext=file.readText();val objectValue=AssignmentContract.json.parseToJsonElement(plaintext).jsonObject
            val uid=objectValue.getValue("uid").jsonPrimitive.content
            val context=object:ContextWrapper(ctx){override fun getNoBackupFilesDir()=File(run,index.toString())}
            val store=AndroidAtomicFilePersistence(context)
            val prior=JsonObject(objectValue+mapOf("pendingInvitationCommands" to JsonArray(emptyList()),"pendingTerminalProof" to JsonNull,"switchMarker" to JsonNull))
            val base=runCatching{PersistenceJson.format.decodeFromJsonElement<AccountPersistenceEnvelope>(prior)}.getOrElse{AccountPersistenceEnvelope(uid=uid)}
            store.write(base) // Establish actual retained bytes before the prospective write.
            // Each future-format state is written once to its own fresh synthetic partition.
            writer.invoke(store,base,{plaintext})
            val raw=store.fileFor(uid).readBytes();maximum=maxOf(maximum,raw.size.toLong())
            val encrypted=AssignmentContract.json.parseToJsonElement(raw.decodeToString()).jsonObject
            val key=keyMethod.invoke(store,false) as SecretKey
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,Base64.decode(encrypted.getValue("nonce").jsonPrimitive.content,Base64.NO_WRAP)))
            cipher.updateAAD("TillFailureRecovery|1|tillfailure.recovery.v1|$uid".encodeToByteArray())
            val restored=cipher.doFinal(Base64.decode(encrypted.getValue("ciphertext").jsonPrimitive.content,Base64.NO_WRAP)).decodeToString()
            assertEquals(plaintext,restored)
            val n=plaintext.encodeToByteArray().size
            assertTrue(raw.size<=108+4*((n+18)/3) && raw.size<=98304)
        }
        // An overlarge actual retained record is preserved; pure admission runs before any store call.
        val uid="m7-pr1a-oversize-${UUID.randomUUID()}"
        val context=object:ContextWrapper(ctx){override fun getNoBackupFilesDir()=File(run,"oversize")}
        val store=AndroidAtomicFilePersistence(context)
        val original=AccountPersistenceEnvelope(uid=uid,preferredWorkspaceId="x".repeat(36500));store.write(original)
        val before=store.fileFor(uid).readBytes()
        val first=AssignmentContract.json.parseToJsonElement(fixtures.first().readText()).jsonObject
        // Decode only the proposed descriptor from the prospective pending JSON.
        val pending=first.getValue("pendingInvitationCommands").jsonArray.singleOrNull()?.jsonObject
            ?: fixtures.asSequence().map{AssignmentContract.json.parseToJsonElement(it.readText()).jsonObject}.mapNotNull{it["pendingInvitationCommands"]?.jsonArray?.singleOrNull()?.jsonObject}.first()
        val d=AssignmentDescriptor("assign-program-version",pending.getValue("workspaceId").jsonPrimitive.content,pending.getValue("idempotencyKey").jsonPrimitive.content,
            AssignmentContract.json.decodeFromJsonElement(pending.getValue("assignment")))
        assertFalse(AssignmentProspective.admission(original,d))
        assertContentEquals(before,store.fileFor(uid).readBytes())
        println("M7_ANDROID_NATIVE fixtures=${fixtures.size} maximumEncrypted=$maximum oversizeByteIdentical=true")
        // Retain all new partitions/key. No application identity, old partition or key is touched.
    }
}
