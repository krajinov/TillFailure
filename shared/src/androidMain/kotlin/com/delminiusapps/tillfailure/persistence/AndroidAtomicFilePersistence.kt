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

    override fun write(envelope: AccountPersistenceEnvelope) {
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
            val temporary = File(rootDirectory, "${target.name}.tmp")
            check(!temporary.exists() || temporary.delete()) { "Unable to remove stale recovery temporary file" }
            val bytes = PersistenceJson.format.encodeToString(encrypted).encodeToByteArray()
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: RecoveryPersistenceLockedException) {
            throw error
        } catch (error: Exception) {
            throw RecoveryPersistenceLockedException("Unable to protect recovery data", error)
        }
    }

    override fun read(uid: String): AccountPersistenceEnvelope? {
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
        val file = fileFor(uid)
        if (!file.exists()) return
        read(uid)
        if (!file.delete()) throw RecoveryPersistenceLockedException("Account persistence deletion failed")
    }

    internal fun fileFor(uid: String): File {
        require(uid.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.encodeToByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        return File(rootDirectory, "account-$digest-v$RECOVERY_ENCRYPTION_VERSION.json")
    }

    internal fun deleteKeyForTest() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.deleteEntry(keyIdentifier)
    }

    private fun encryptionKey(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyIdentifier, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) throw RecoveryPersistenceLockedException("Recovery key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
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
    }

    private fun associatedData(uid: String): ByteArray =
        "TillFailureRecovery|$RECOVERY_ENCRYPTION_VERSION|$keyIdentifier|$uid".encodeToByteArray()

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
