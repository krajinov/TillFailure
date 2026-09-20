package com.delminiusapps.tillfailure.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val RECOVERY_ENCRYPTION_VERSION = 1
const val RECOVERY_KEY_IDENTIFIER = "tillfailure.recovery.v1"

class RecoveryPersistenceLockedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Serializable
data class OfflineAccessGrant(
    val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val membershipRevision: Long,
    val verifiedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class DownloadManifest(
    val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val assignmentId: String,
    val snapshotHash: String,
    val requiredPaths: List<String>,
)

@Serializable
data class WorkoutRecoverySnapshot(
    val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val sessionId: String,
    val payload: String,
)

@Serializable
data class MutationJournalEntry(
    val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val operationId: String,
    val payload: String,
    val state: String,
)

@Serializable
data class PendingUpload(
    val schemaVersion: Int = 1,
    val uid: String,
    val uploadId: String,
    val durableFileReference: String,
    val state: String,
)

@Serializable
data class AccountSwitchMarker(
    val schemaVersion: Int = 1,
    val departingUid: String,
    val accountEpoch: Long,
    val state: String,
)

@Serializable
data class AccountPersistenceEnvelope(
    val schemaVersion: Int = 1,
    val uid: String,
    val offlineAccessGrant: OfflineAccessGrant? = null,
    val downloadManifests: List<DownloadManifest> = emptyList(),
    val workoutRecoverySnapshot: WorkoutRecoverySnapshot? = null,
    val mutationJournal: List<MutationJournalEntry> = emptyList(),
    val pendingUploads: List<PendingUpload> = emptyList(),
    val switchMarker: AccountSwitchMarker? = null,
) {
    init {
        require(uid.isNotBlank())
        require(offlineAccessGrant?.uid == null || offlineAccessGrant.uid == uid)
        require(downloadManifests.all { it.uid == uid })
        require(workoutRecoverySnapshot?.uid == null || workoutRecoverySnapshot.uid == uid)
        require(mutationJournal.all { it.uid == uid })
        require(pendingUploads.all { it.uid == uid })
        require(switchMarker?.departingUid == null || switchMarker.departingUid == uid)
    }
}

interface AccountPersistencePrototype {
    fun write(envelope: AccountPersistenceEnvelope)
    fun read(uid: String): AccountPersistenceEnvelope?
    fun delete(uid: String)
}

internal object PersistenceJson {
    val format = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }
}
