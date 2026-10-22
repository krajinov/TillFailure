package com.delminiusapps.tillfailure.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.delminiusapps.tillfailure.invitations.PendingInvitationCommand
import com.delminiusapps.tillfailure.invitations.CommandTerminalProof
import com.delminiusapps.tillfailure.invitations.CommandResolution

const val RECOVERY_ENCRYPTION_VERSION = 1
const val RECOVERY_KEY_IDENTIFIER = "tillfailure.recovery.v1"

class RecoveryPersistenceLockedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Serializable
data class OfflineAccessGrant(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val membershipRevision: Long,
    val verifiedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class DownloadManifest(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val assignmentId: String,
    val snapshotHash: String,
    val requiredPaths: List<String>,
)

@Serializable
data class WorkoutRecoverySnapshot(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val sessionId: String,
    val payload: String,
)

@Serializable
data class MutationJournalEntry(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val uid: String,
    val workspaceId: String,
    val operationId: String,
    val payload: String,
    val state: String,
)

@Serializable
data class PendingUpload(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val uid: String,
    val uploadId: String,
    val durableFileReference: String,
    val state: String,
)

@Serializable
data class AccountSwitchMarker(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val departingUid: String,
    val accountEpoch: Long,
    val state: String,
)

@Serializable
data class AccountPersistenceEnvelope(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val uid: String,
    val offlineAccessGrant: OfflineAccessGrant? = null,
    val downloadManifests: List<DownloadManifest> = emptyList(),
    val workoutRecoverySnapshot: WorkoutRecoverySnapshot? = null,
    val mutationJournal: List<MutationJournalEntry> = emptyList(),
    val pendingUploads: List<PendingUpload> = emptyList(),
    val switchMarker: AccountSwitchMarker? = null,
    val preferredWorkspaceId: String? = null,
    val pendingInvitationCommands: List<PendingInvitationCommand> = emptyList(),
    val pendingTerminalProof: CommandTerminalProof? = null,
    val lastSettledCommand: CommandTerminalProof? = null,
) {
    init {
        require(schemaVersion == 1)
        require(offlineAccessGrant?.schemaVersion == null || offlineAccessGrant.schemaVersion == 1)
        require(downloadManifests.all { it.schemaVersion == 1 })
        require(workoutRecoverySnapshot?.schemaVersion == null || workoutRecoverySnapshot.schemaVersion == 1)
        require(mutationJournal.all { it.schemaVersion == 1 } && pendingUploads.all { it.schemaVersion == 1 })
        require(switchMarker == null || switchMarker.schemaVersion == 1 && switchMarker.accountEpoch > 0 && switchMarker.state == "SwitchingOut")
        RecoveryUidContract.requireValid(uid)
        require(offlineAccessGrant?.uid == null || offlineAccessGrant.uid == uid)
        require(downloadManifests.all { it.uid == uid })
        require(workoutRecoverySnapshot?.uid == null || workoutRecoverySnapshot.uid == uid)
        require(mutationJournal.all { it.uid == uid })
        require(pendingUploads.all { it.uid == uid })
        require(switchMarker?.departingUid == null || switchMarker.departingUid == uid)
        require(pendingTerminalProof?.let { it.command.uid == uid && it.valid() && pendingInvitationCommands.singleOrNull()?.let { p -> CommandResolution.matches(p,it.command) } == true } != false)
        require(lastSettledCommand?.let { it.command.uid == uid && it.valid() } != false)
        require(pendingInvitationCommands.size <= 1 && pendingInvitationCommands.all { it.uid == uid })
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
