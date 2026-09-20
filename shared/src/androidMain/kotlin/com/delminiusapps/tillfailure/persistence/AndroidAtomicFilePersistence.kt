package com.delminiusapps.tillfailure.persistence

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class AndroidAtomicFilePersistence(
    private val rootDirectory: File,
) : AccountPersistencePrototype {
    override fun write(envelope: AccountPersistenceEnvelope) {
        rootDirectory.mkdirs()
        val target = fileFor(envelope.uid)
        val temporary = File(rootDirectory, "${target.name}.tmp")
        val bytes = PersistenceJson.format.encodeToString(envelope).encodeToByteArray()
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(target)) { "Atomic persistence replacement failed" }
    }

    override fun read(uid: String): AccountPersistenceEnvelope? {
        val file = fileFor(uid)
        if (!file.exists()) return null
        val envelope = PersistenceJson.format.decodeFromString<AccountPersistenceEnvelope>(file.readText())
        check(envelope.uid == uid) { "Account partition mismatch" }
        return envelope
    }

    override fun delete(uid: String) {
        val file = fileFor(uid)
        check(!file.exists() || file.delete()) { "Account persistence deletion failed" }
    }

    private fun fileFor(uid: String): File {
        require(uid.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.encodeToByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        return File(rootDirectory, "account-$digest-v1.json")
    }
}
