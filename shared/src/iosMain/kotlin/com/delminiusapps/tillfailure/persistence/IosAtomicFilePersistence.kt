package com.delminiusapps.tillfailure.persistence

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import platform.Foundation.NSFileManager
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fsync
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename
import platform.posix.rewind

@OptIn(ExperimentalForeignApi::class)
class IosAtomicFilePersistence(
    private val rootPath: String,
) : AccountPersistencePrototype {
    override fun write(envelope: AccountPersistenceEnvelope) {
        val manager = NSFileManager.defaultManager
        check(manager.createDirectoryAtPath(rootPath, withIntermediateDirectories = true, attributes = null, error = null))
        val target = fileFor(envelope.uid)
        val temporary = "$target.tmp"
        val bytes = PersistenceJson.format.encodeToString(envelope).encodeToByteArray()
        val file = checkNotNull(fopen(temporary, "wb")) { "Unable to open persistence temporary file" }
        try {
            val written = bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file) }
            check(written.toLong() == bytes.size.toLong()) { "Incomplete persistence write" }
            check(fsync(fileno(file)) == 0) { "Persistence fsync failed" }
        } finally {
            fclose(file)
        }
        check(rename(temporary, target) == 0) { "Atomic persistence replacement failed" }
    }

    override fun read(uid: String): AccountPersistenceEnvelope? {
        val path = fileFor(uid)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val file = checkNotNull(fopen(path, "rb")) { "Unable to open persistence file" }
        val bytes = try {
            check(fseek(file, 0, SEEK_END) == 0)
            val size = ftell(file)
            check(size >= 0)
            rewind(file)
            ByteArray(size.toInt()).also { buffer ->
                val read = buffer.usePinned { pinned -> fread(pinned.addressOf(0), 1.convert(), buffer.size.convert(), file) }
                check(read.toLong() == buffer.size.toLong()) { "Incomplete persistence read" }
            }
        } finally {
            fclose(file)
        }
        val json = bytes.decodeToString()
        val envelope = PersistenceJson.format.decodeFromString<AccountPersistenceEnvelope>(json)
        check(envelope.uid == uid) { "Account partition mismatch" }
        return envelope
    }

    override fun delete(uid: String) {
        val path = fileFor(uid)
        val manager = NSFileManager.defaultManager
        check(!manager.fileExistsAtPath(path) || remove(path) == 0)
    }

    private fun fileFor(uid: String): String {
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Unsupported account identifier" }
        return "$rootPath/account-$uid-v1.json"
    }
}
