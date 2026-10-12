package com.delminiusapps.tillfailure.invitations

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class InvitationState(
    val accountUid: String? = null,
    val email: String = "", val entry: String = "", val hasLink: Boolean = false,
    val busy: Boolean = false, val message: String = "", val workspaceName: String? = null, val expiresAtMillis: Long? = null,
    val summaries: List<InvitationSummary> = emptyList(), val confirmation: InvitationRequest? = null,
    val deliveryLink: String? = null, val pending: PendingInvitationCommand? = null,
) { override fun toString(): String = "InvitationState(busy=$busy)" }

sealed interface InvitationEvent {
    data class Email(val value: String) : InvitationEvent
    data class Entry(val value: String) : InvitationEvent { override fun toString() = "InvitationEvent.Entry(redacted)" }
    data object Open : InvitationEvent
    data object Preview : InvitationEvent
    data object Create : InvitationEvent
    data object ListInvitations : InvitationEvent
    data object Accept : InvitationEvent
    data class Resend(val summary: InvitationSummary) : InvitationEvent
    data class Revoke(val summary: InvitationSummary) : InvitationEvent
    data object Confirm : InvitationEvent
    data object Cancel : InvitationEvent
    data object RetryCommand : InvitationEvent
    data object DismissDelivery : InvitationEvent
    data object UseDelivery : InvitationEvent
    data object PopulateEntry : InvitationEvent
}

sealed interface InvitationEffect { data class RecheckMembership(val uid: String) : InvitationEffect }

class InvitationViewModel(private var client: InvitationClient) : ViewModel() {
    private val mutable = MutableStateFlow(InvitationState())
    val state = mutable.asStateFlow()
    private val effects = Channel<InvitationEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var identity = IdentityState()
    private var generation = 0L
    private var operation: FirebaseCancellation? = null
    private var pendingObserver: FirebaseCancellation? = null
    private var memoryRevision = -1L
    private var disposed = false
    fun currentState(): InvitationState = mutable.value

    fun updateClient(next: InvitationClient) {
        if (next === client) return
        generation++; operation?.cancel(); operation = null
        pendingObserver?.cancel(); pendingObserver = null
        client = next; memoryRevision = -1L
        mutable.value = InvitationState(hasLink = client.memory.link != null)
    }

    fun updateIdentity(next: IdentityState) {
        val changedAccount = next.accountUid != identity.accountUid
        val lostTrainer = identity.status == GateStatus.TrainerHome && next.status != GateStatus.TrainerHome
        val locked = next.status in setOf(GateStatus.AccessLost, GateStatus.AccountDisabled, GateStatus.CleanupRequired,
            GateStatus.SwitchingOut, GateStatus.IdentityUnsupported, GateStatus.AnonymousSession)
        identity = next
        if (changedAccount || lostTrainer || locked) {
            generation++; operation?.cancel(); operation = null
            mutable.value = InvitationState(accountUid = next.accountUid, hasLink = client.memory.link != null)
        }
        if (changedAccount || pendingObserver == null) {
            pendingObserver?.cancel()
            pendingObserver = next.accountUid?.let { uid -> client.observePending(uid) {
                if (!disposed && identity.accountUid == uid) refreshPending()
            } }
        }
        refreshPending()
        mutable.value = mutable.value.copy(accountUid = next.accountUid)
        if (client.memory.revision != memoryRevision) {
            memoryRevision = client.memory.revision
            mutable.value = mutable.value.copy(hasLink = client.memory.link != null, workspaceName = null, entry = "")
        }
    }

    private fun refreshPending() {
        val uid = identity.accountUid
        val read = runCatching { uid?.let(client::pending) }
        val previous = mutable.value.pending
        mutable.value = mutable.value.copy(pending = read.getOrNull(), message = if (read.isFailure)
            "Protected command recovery is unavailable. Sign-out stays blocked." else mutable.value.message)
        // A recreated root receives only a ledger change, never the old root's reply. It may
        // request the ordinary server gate, which independently decides current authority.
        if (previous != null && read.isSuccess && read.getOrNull() == null && !mutable.value.busy && uid != null)
            effects.trySend(InvitationEffect.RecheckMembership(uid))
    }

    fun onEvent(event: InvitationEvent) {
        val s = mutable.value
        if (s.busy && event !is InvitationEvent.Entry && event !is InvitationEvent.Email) return
        when (event) {
            is InvitationEvent.Email -> if (s.pending == null) mutable.value = s.copy(email = event.value.take(254))
            is InvitationEvent.Entry -> mutable.value = s.copy(entry = event.value.take(512))
            InvitationEvent.Open -> {
                if (!client.memory.open(s.entry.trim())) {
                    mutable.value = s.copy(message = "This invitation entry is invalid."); return
                }
                memoryRevision = client.memory.revision
                mutable.value = s.copy(entry = "", hasLink = true, workspaceName = null, deliveryLink = null,
                    message = "Sign in with the invited account and verify its email, then preview the invitation.")
                if (canUseInvitation() && s.pending == null) preview()
            }
            InvitationEvent.Preview -> if (s.pending == null) preview()
            InvitationEvent.ListInvitations -> if (canTrain() && s.pending == null)
                execute(InvitationRequest("list", workspaceId = identity.verifiedWorkspaceId))
            InvitationEvent.Create -> if (canTrain() && s.pending == null) {
                val email = s.email.trim().lowercase()
                if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) {
                    mutable.value = s.copy(message = "Enter a valid client email."); return
                }
                execute(InvitationRequest("create", workspaceId = identity.verifiedWorkspaceId,
                    email = email, idempotencyKey = client.newCommandKey()))
            }
            InvitationEvent.Accept -> if (canUseInvitation() && s.workspaceName != null && s.pending == null) {
                client.memory.link?.let { link -> mutable.value = s.copy(confirmation = InvitationRequest("accept",
                    invitationId = link.invitationId, token = link.token, expectedVersion = link.version,
                    idempotencyKey = client.newCommandKey())) }
            }
            is InvitationEvent.Resend -> confirmation(event.summary, "resend")
            is InvitationEvent.Revoke -> confirmation(event.summary, "revoke")
            InvitationEvent.Confirm -> s.confirmation?.let { command ->
                mutable.value = s.copy(confirmation = null)
                execute(command)
            }
            InvitationEvent.Cancel -> mutable.value = s.copy(confirmation = null)
            InvitationEvent.DismissDelivery -> mutable.value = s.copy(deliveryLink = null)
            InvitationEvent.UseDelivery -> s.deliveryLink?.let { value ->
                if (client.memory.open(value)) {
                    memoryRevision = client.memory.revision
                    mutable.value = s.copy(deliveryLink = null, hasLink = true, entry = "",
                        message = "Local test invitation opened. Safely sign out and use the invited account.")
                }
            }
            InvitationEvent.PopulateEntry -> client.memory.link?.let { mutable.value = s.copy(entry = it.entry()) }
            InvitationEvent.RetryCommand -> {
                val pending = s.pending ?: return
                if (pending.uid != identity.accountUid) return
                val link = client.memory.link
                if (pending.action == "accept" && (link == null || link.invitationId != pending.invitationId || link.version != pending.expectedVersion)) {
                    mutable.value = s.copy(message = "Reopen the original invitation to resolve the pending acceptance."); return
                }
                execute(InvitationRequest(pending.action, pending.workspaceId, pending.invitationId, pending.email,
                    if (pending.action == "accept") link?.token else null, pending.expectedVersion, pending.idempotencyKey))
            }
        }
    }

    private fun canTrain(): Boolean = identity.status == GateStatus.TrainerHome && identity.verifiedRole == "trainer" && !identity.busy
    private fun canUseInvitation(): Boolean = identity.accountUid != null && !identity.busy && identity.status in
        setOf(GateStatus.NoAccount, GateStatus.WorkspaceGate, GateStatus.MembershipVerifiedFeaturePending, GateStatus.MultipleMemberships, GateStatus.DepartureBlocked)
    private fun preview() {
        if (!canUseInvitation()) return
        client.memory.link?.let { execute(InvitationRequest("preview", invitationId = it.invitationId, token = it.token, expectedVersion = it.version)) }
    }
    private fun confirmation(summary: InvitationSummary, action: String) {
        if (!canTrain() || mutable.value.pending != null || summary.status != "pending") return
        mutable.value = mutable.value.copy(confirmation = InvitationRequest(action,
            workspaceId = identity.verifiedWorkspaceId, invitationId = summary.invitationId,
            expectedVersion = summary.tokenVersion, idempotencyKey = client.newCommandKey()))
    }
    private fun execute(request: InvitationRequest) {
        val uid = identity.accountUid ?: return
        if (request.action in setOf("create", "list", "resend", "revoke") && !canTrain()) return
        if (request.action in setOf("preview", "accept") && !canUseInvitation()) return
        val captured = ++generation
        mutable.value = mutable.value.copy(busy = true, message = "Contacting the invitation service…", deliveryLink = null)
        operation = client.execute(uid, request) { outcome ->
            if (disposed || generation != captured || identity.accountUid != uid) return@execute
            val reply = outcome.reply
            if (outcome.error != null) {
                mutable.value = mutable.value.copy(busy = false, workspaceName = null, message = message(outcome.error, outcome.ambiguous))
            } else if (reply == null) {
                mutable.value = mutable.value.copy(busy = false, message = "Invitation service unavailable. Retry while connected.")
            } else {
                val link = if (reply.deliveryToken != null && reply.invitationId != null && reply.tokenVersion != null)
                    InvitationLink(reply.invitationId, reply.tokenVersion, reply.deliveryToken).entry() else null
                mutable.value = mutable.value.copy(busy = false, email = if (request.action == "create") "" else mutable.value.email,
                    workspaceName = if (request.action == "preview") reply.workspaceName else null,
                    expiresAtMillis = if (request.action == "preview") reply.expiresAtMillis else null,
                    summaries = reply.summaries ?: mutable.value.summaries.map {
                        if (it.invitationId == reply.invitationId) it.copy(status = reply.status ?: it.status,
                            tokenVersion = reply.tokenVersion ?: it.tokenVersion, expiresAtMillis = reply.expiresAtMillis ?: it.expiresAtMillis) else it
                    }, deliveryLink = link, message = when (request.action) {
                        "accept" -> "Invitation accepted. Rechecking membership online…"
                        "preview" -> "Invitation verified online. Confirm to join this workspace."
                        "list" -> if (reply.summaries.isNullOrEmpty()) "No invitations yet." else "Invitations refreshed."
                        "revoke" -> "Invitation revoked."
                        else -> if (link != null) "Invitation ready for local testing. No email was sent."
                            else "Command confirmed. The delivery token is unavailable; resend to rotate a new local link."
                    })
                if (request.action == "accept") {
                    client.memory.clear()
                    mutable.value = mutable.value.copy(hasLink = false)
                    effects.trySend(InvitationEffect.RecheckMembership(uid))
                }
            }
            refreshPending()
        }
    }
    override fun onCleared() { disposed = true; generation++; operation?.cancel(); pendingObserver?.cancel(); effects.close() }
}

private fun message(error: String, ambiguous: Boolean): String = if (ambiguous)
    "The outcome is unknown. Retry the same command while connected. Sign-out stays blocked."
else when (error) {
    "expired" -> "This invitation expired. Ask the trainer to resend it."
    "rotated" -> "This link was replaced. Open the latest invitation."
    "revoked" -> "This invitation was revoked."
    "already-used" -> "This invitation was already used. Recheck your access."
    "wrong-account-email" -> "Use the invited account and email. Safely sign out to change accounts."
    "unverified-email" -> "Verify this account's email, then recheck and preview again."
    "unauthenticated", "unauthorized" -> "This account is not authorized. Recheck your access."
    "fixture-account-required" -> "Create the client Auth Emulator fixture before inviting this email."
    "membership-bound-violated", "workspace-roster-bound-violated" -> "The local fixture membership limit has been reached."
    "membership-conflict" -> "Existing membership or profile conflicts with this invitation. Contact support."
    "idempotency-key-reused", "record-invalid", "counter-inconsistent" -> "Invitation data needs trusted review. Contact support."
    "rate-limited" -> "Too many requests. Wait a minute and retry."
    else -> "Invitation service unavailable. Retry while connected."
}
