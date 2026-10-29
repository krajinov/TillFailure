package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.serialization.Serializable

@Serializable
data class DraftDecision(val status: String, val workspaceId: String, val templateId: String,
    val templateRevision: Int, val replayed: Boolean) {
    fun valid(workspace: String?, draft: DraftSave) = workspaceId == workspace && templateId == draft.templateId &&
        templateRevision in 0..Int.MAX_VALUE-1 && when(status) {
            "draft-saved" -> templateRevision == draft.expectedRevision+1
            "draft-conflict" -> templateRevision > draft.expectedRevision
            "draft-source-conflict" -> templateRevision == draft.expectedRevision
            else -> false
        }
}

/** Pure prospective-state validation; it never mutates the supplied record. */
object DraftBudget {
    const val MAX_PLAINTEXT = 36500
    fun plaintext(record: AccountPersistenceEnvelope) = PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(), record)
    fun pendingFits(p: PendingInvitationCommand): Boolean = runCatching {
        require(DraftSerialization.coveredKey(p.idempotencyKey))
        val r = CommandResolution.request(p)
        r.length <= 8192 && r.encodeToByteArray().size <= 24576 &&
            ("{\"data\":$r}").encodeToByteArray().size <= 32768 &&
            ("{\"data\":${CommandResolution.descriptor(p)}}").encodeToByteArray().size <= 32768 &&
            PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),p).encodeToByteArray().size <= 16384
    }.getOrDefault(false)
    fun proofs(p: PendingInvitationCommand): List<CommandTerminalProof> {
        val d = requireNotNull(p.draftSave)
        val normal = listOf("draft-saved" to d.expectedRevision+1, "draft-source-conflict" to d.expectedRevision,
            "draft-conflict" to Int.MAX_VALUE-1).flatMap { (status,revision) -> listOf(false,true).map { replayed ->
            CommandTerminalProof(p,"normal-v1",acknowledgment=InvitationReply(draftDecision=DraftDecision(status,p.workspaceId!!,d.templateId,revision,replayed)))
        } }
        return normal + listOf("applied","not-applied-conflict","not-applied-fenced").map {
            CommandTerminalProof(p,"resolution-v1",resolution=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),it,8640000000000000))
        }
    }
    fun states(record: AccountPersistenceEnvelope,p: PendingInvitationCommand): List<AccountPersistenceEnvelope> =
        listOf(record.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=null)) + proofs(p).flatMap { proof ->
            listOf(record.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=proof),
                record.copy(pendingInvitationCommands=emptyList(),pendingTerminalProof=null,lastSettledCommand=proof.retired()))
        }
    // Production Swift wrapper: fixed ASCII header + base64 ciphertext, nonce and separate tag.
    // Base64 can only add one escape byte per symbol. Padding makes this bound conservative.
    fun swiftEncryptedUpperBound(n: Int): Int = 181 + 8*((n+2)/3)
    fun admission(record: AccountPersistenceEnvelope,p: PendingInvitationCommand): Boolean = runCatching {
        require(p.action=="save-program-draft" && validPendingCommand(p.uid,p) && record.uid==p.uid)
        require(record.switchMarker==null && record.offlineAccessGrant==null && record.downloadManifests.isEmpty() && record.workoutRecoverySnapshot==null && record.mutationJournal.isEmpty() && record.pendingUploads.isEmpty())
        require(record.pendingInvitationCommands.isEmpty() && record.pendingTerminalProof==null && record.lastSettledCommand?.consumed(p)!=true)
        listOf(p,p.copy(resolutionStarted=true)).all { command -> states(record,command).all {
            val n=plaintext(it).encodeToByteArray().size
            n<=MAX_PLAINTEXT && swiftEncryptedUpperBound(n)<=98304
        } }
    }.getOrDefault(false)
}
