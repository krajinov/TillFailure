package com.delminiusapps.tillfailure.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
private data class AndroidEncryptedEnvelope(
    val encryptionVersion: Int,
    val keyIdentifier: String,
    val nonce: String,
    val ciphertext: String,
)

class AndroidAtomicFilePersistence(
    context: Context,
    internal val keyIdentifier: String = RECOVERY_KEY_IDENTIFIER,
) : AccountPersistencePrototype {
    internal val rootDirectory: File = File(context.noBackupFilesDir, "tillfailure-recovery")

    // The registry is process-wide: separate bridge instances for the same account must share
    // the lock. Entries are retained only while an operation owns or is waiting for the lock.
    private class PartitionLock {
        val monitor = Any()
        var users = 0
    }

    private class KeyLock {
        val monitor = Any()
        var users = 0
    }

    private inline fun <T> withPartition(uid: String, action: () -> T): T {
        val path = fileFor(uid).absolutePath
        val lock = synchronized(partitionLocks) {
            partitionLocks.getOrPut(path) { PartitionLock() }.also { it.users++ }
        }
        try {
            return synchronized(lock.monitor) { action() }
        } finally {
            synchronized(partitionLocks) {
                lock.users--
                if (lock.users == 0) partitionLocks.remove(path, lock)
            }
        }
    }

    private inline fun <T> withKeyIdentifier(action: () -> T): T {
        val lock = synchronized(keyLocks) {
            keyLocks.getOrPut(keyIdentifier) { KeyLock() }.also { it.users++ }
        }
        try {
            return synchronized(lock.monitor) { action() }
        } finally {
            synchronized(keyLocks) {
                lock.users--
                if (lock.users == 0) keyLocks.remove(keyIdentifier, lock)
            }
        }
    }

    internal var beforePartitionLockForTest: ((String) -> Unit)? = null
    internal var beforeCommitForTest: ((String, File) -> Unit)? = null
    internal var beforeDeleteForTest: ((String) -> Unit)? = null
    internal var beforeKeyLockForTest: (() -> Unit)? = null
    internal var beforeKeyGenerationForTest: (() -> Unit)? = null

    override fun write(envelope: AccountPersistenceEnvelope) {
        beforePartitionLockForTest?.invoke(envelope.uid)
        withPartition(envelope.uid) { writeLocked(envelope) }
    }

    private fun writeLocked(envelope: AccountPersistenceEnvelope) {
        try {
            check(rootDirectory.mkdirs() || rootDirectory.isDirectory) { "Unable to create recovery directory" }
            val target = fileFor(envelope.uid)
            if (target.exists()) read(envelope.uid)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(createIfMissing = true))
            cipher.updateAAD(associatedData(envelope.uid))
            val plaintext = PersistenceJson.format.encodeToString(envelope).encodeToByteArray()
            val encrypted = AndroidEncryptedEnvelope(
                encryptionVersion = RECOVERY_ENCRYPTION_VERSION,
                keyIdentifier = keyIdentifier,
                nonce = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP),
            )
            // createTempFile reserves this operation's path exclusively. Never remove a temp
            // owned by another writer, even if that writer uses another bridge instance.
            val temporary = File.createTempFile("${target.name}.", ".tmp", rootDirectory)
            val bytes = PersistenceJson.format.encodeToString(encrypted).encodeToByteArray()
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                beforeCommitForTest?.invoke(envelope.uid, temporary)
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                temporary.delete()
            }
        } catch (error: RecoveryPersistenceLockedException) {
            throw error
        } catch (error: Exception) {
            throw RecoveryPersistenceLockedException("Unable to protect recovery data", error)
        }
    }

    override fun read(uid: String): AccountPersistenceEnvelope? = withPartition(uid) { readLocked(uid) }

    private fun readLocked(uid: String): AccountPersistenceEnvelope? {
        val file = fileFor(uid)
        if (!file.exists()) return null
        try {
            val encrypted = PersistenceJson.format.decodeFromString<AndroidEncryptedEnvelope>(file.readText())
            if (encrypted.encryptionVersion != RECOVERY_ENCRYPTION_VERSION || encrypted.keyIdentifier != keyIdentifier) {
                throw RecoveryPersistenceLockedException("Unsupported recovery encryption envelope")
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val nonce = Base64.decode(encrypted.nonce, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(associatedData(uid))
            val plaintext = cipher.doFinal(Base64.decode(encrypted.ciphertext, Base64.NO_WRAP)).decodeToString()
            val envelope = PersistenceJson.format.decodeFromString<AccountPersistenceEnvelope>(plaintext)
            if (envelope.uid != uid) throw RecoveryPersistenceLockedException("Account partition mismatch")
            return envelope
        } catch (error: RecoveryPersistenceLockedException) {
            throw error
        } catch (error: AEADBadTagException) {
            throw RecoveryPersistenceLockedException("Recovery authentication failed", error)
        } catch (error: Exception) {
            throw RecoveryPersistenceLockedException("Recovery data is unreadable", error)
        }
    }

    override fun delete(uid: String) {
        beforePartitionLockForTest?.invoke(uid)
        withPartition(uid) { deleteLocked(uid) }
    }

    private fun deleteLocked(uid: String) {
        val file = fileFor(uid)
        if (!file.exists()) return
        readLocked(uid)
        beforeDeleteForTest?.invoke(uid)
        if (!file.delete()) throw RecoveryPersistenceLockedException("Account persistence deletion failed")
    }

    internal fun fileFor(uid: String): File {
        // Only the digest is used as a filename, so any valid Firebase UID (email-shaped,
        // punctuated, or Unicode) is safe and path traversal is impossible by construction.
        RecoveryUidContract.requireValid(uid)
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.encodeToByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        return File(rootDirectory, "account-$digest-v$RECOVERY_ENCRYPTION_VERSION.json")
    }

    internal fun deleteKeyForTest() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.deleteEntry(keyIdentifier)
    }

    private fun encryptionKey(createIfMissing: Boolean): SecretKey {
        beforeKeyLockForTest?.invoke()
        return withKeyIdentifier { encryptionKeyLocked(createIfMissing) }
    }

    private fun encryptionKeyLocked(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val stored = keyStore.getKey(keyIdentifier, null)
        if (stored != null) return stored as? SecretKey
            ?: throw RecoveryPersistenceLockedException("Recovery key has an unsupported type")
        if (!createIfMissing) throw RecoveryPersistenceLockedException("Recovery key is unavailable")
        beforeKeyGenerationForTest?.invoke()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyIdentifier,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
        // Always encrypt with the persisted alias, never an unpersisted or superseded generator
        // result. The process-wide key lock prevents another partition from replacing this alias.
        return KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            .getKey(keyIdentifier, null) as? SecretKey
            ?: throw RecoveryPersistenceLockedException("Recovery key is unavailable after creation")
    }

    private fun associatedData(uid: String): ByteArray =
        "TillFailureRecovery|$RECOVERY_ENCRYPTION_VERSION|$keyIdentifier|$uid".encodeToByteArray()

    private companion object {
        val partitionLocks = HashMap<String, PartitionLock>()
        val keyLocks = HashMap<String, KeyLock>()
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
