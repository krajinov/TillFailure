package com.delminiusapps.tillfailure.persistence

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class NativeRecoveryPersistenceResult(
    val payload: String?,
    val failureCode: String?,
)

interface NativeRecoveryPersistenceBridge {
    val keyIdentifier: String
    val encryptionVersion: Int
    fun write(uid: String, plaintext: String): NativeRecoveryPersistenceResult
    fun read(uid: String): NativeRecoveryPersistenceResult
    fun delete(uid: String): NativeRecoveryPersistenceResult
}

class IosAtomicFilePersistence(
    private val bridge: NativeRecoveryPersistenceBridge,
) : AccountPersistencePrototype {
    init {
        require(bridge.keyIdentifier == RECOVERY_KEY_IDENTIFIER)
        require(bridge.encryptionVersion == RECOVERY_ENCRYPTION_VERSION)
    }

    override fun write(envelope: AccountPersistenceEnvelope) {
        bridge.write(envelope.uid, PersistenceJson.format.encodeToString(envelope)).requireSuccess("write")
    }

    override fun read(uid: String): AccountPersistenceEnvelope? {
        val result = bridge.read(uid).requireSuccess("read")
        val payload = result.payload ?: return null
        try {
            val envelope = PersistenceJson.format.decodeFromString<AccountPersistenceEnvelope>(payload)
            if (envelope.uid != uid) throw RecoveryPersistenceLockedException("Account partition mismatch")
            return envelope
        } catch (error: RecoveryPersistenceLockedException) {
            throw error
        } catch (error: Exception) {
            throw RecoveryPersistenceLockedException("Recovery payload is unreadable", error)
        }
    }

    override fun delete(uid: String) {
        bridge.delete(uid).requireSuccess("delete")
    }

    private fun NativeRecoveryPersistenceResult.requireSuccess(operation: String): NativeRecoveryPersistenceResult {
        if (failureCode != null) throw RecoveryPersistenceLockedException("Native recovery $operation failed: $failureCode")
        return this
    }
}
