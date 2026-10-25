package com.delminiusapps.tillfailure.invitations

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable

@Serializable enum class InvitationEntry { Invite, Manage }
data class TrainerInvitationPresentation(val entry: InvitationEntry, val visit: Long)
enum class TrainerInvitationPage { Form, List, Result }

data class InvitationState(
    val accountUid: String? = null,
    val workspaceId: String? = null, val authorityEpoch: Long = 0,
    val email: String = "", val entry: String = "", val hasLink: Boolean = false,
    val busy: Boolean = false, val message: String = "", val workspaceName: String? = null, val expiresAtMillis: Long? = null,
    val summaries: List<InvitationSummary> = emptyList(), val confirmation: InvitationRequest? = null,
    val deliveryLink: String? = null, val pending: PendingInvitationCommand? = null,
    val resolutionPrompt: PendingInvitationCommand? = null,
    val resolutionPromptId: Long = 0,
    val resolutionEligibility: String = "identity-required",
    val trainerPage: TrainerInvitationPage = TrainerInvitationPage.Form,
    val listLoaded: Boolean = false, val listFailed: Boolean = false, val trainerVisit: Long? = null,
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
    data object Resolve : InvitationEvent
    data class ConfirmResolution(val command: PendingInvitationCommand, val promptId: Long) : InvitationEvent
    data class CancelResolution(val promptId: Long) : InvitationEvent
    data object DismissDelivery : InvitationEvent
    data object UseDelivery : InvitationEvent
    data object PopulateEntry : InvitationEvent
    data object ShowForm : InvitationEvent
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
    private var resolutionSequence = 0L
    private var disposed = false
    private val trainerVisit get() = mutable.value.trainerVisit
    fun currentState(): InvitationState = mutable.value

    /** An old composition cannot edit or confirm a newer account, scope or dialog. */
    fun onPresentationEvent(uid: String?, workspaceId: String?, epoch: Long, confirmationKey: String?, event: InvitationEvent, visit: Long? = null) {
        if (disposed || uid != identity.accountUid || workspaceId != identity.verifiedWorkspaceId || epoch != identity.authorityEpoch) return
        if (visit != trainerVisit) return
        if (event in setOf(InvitationEvent.Confirm, InvitationEvent.Cancel) &&
            (confirmationKey == null || confirmationKey != mutable.value.confirmation?.idempotencyKey)) return
        onEvent(event)
    }

    /** The existing root owner outlives destinations; only its transient trainer surface leaves. */
    fun enterTrainer(entry: InvitationEntry) {
        if (disposed || !canTrain()) return
        mutable.value = mutable.value.copy(trainerPage = if (entry == InvitationEntry.Invite) TrainerInvitationPage.Form else TrainerInvitationPage.List)
        if (entry == InvitationEntry.Manage) onEvent(InvitationEvent.ListInvitations)
    }

    fun attachTrainer(presentation: TrainerInvitationPresentation) {
        if (disposed || !canTrain() || trainerVisit == presentation.visit) return
        leaveTrainer(identity.accountUid, identity.verifiedWorkspaceId, identity.authorityEpoch)
        mutable.value = mutable.value.copy(trainerVisit = presentation.visit)
        enterTrainer(presentation.entry)
    }

    fun detachTrainer(uid: String?, workspaceId: String?, epoch: Long, visit: Long) {
        if (trainerVisit == visit) leaveTrainer(uid, workspaceId, epoch)
    }

    fun leaveTrainer(uid: String?, workspaceId: String?, epoch: Long) {
        if (disposed || identity.accountUid != uid || identity.verifiedWorkspaceId != workspaceId || identity.authorityEpoch != epoch) return
        generation++; operation?.cancel(); operation = null
        mutable.value = InvitationState(accountUid = uid, workspaceId = workspaceId, authorityEpoch = epoch,
            hasLink = client.memory.linkFor(uid) != null)
        refreshPending() // Never discard a prepared command or its process-only acceptance entry.
    }

    fun updateClient(next: InvitationClient) {
        if (disposed) return
        if (next === client) return
        generation++; operation?.cancel(); operation = null
        pendingObserver?.cancel(); pendingObserver = null
        client = next; memoryRevision = -1L
        mutable.value = InvitationState(hasLink = client.memory.linkFor(identity.accountUid) != null)
    }

    fun updateIdentity(next: IdentityState) {
        if (disposed) return
        val changedAccount = next.accountUid != identity.accountUid
        val changedScope = next.verifiedWorkspaceId != identity.verifiedWorkspaceId || next.authorityEpoch != identity.authorityEpoch
        val lostTrainer = identity.status == GateStatus.TrainerHome && next.status != GateStatus.TrainerHome
        val locked = next.status in setOf(GateStatus.AccessLost, GateStatus.AccountDisabled, GateStatus.CleanupRequired,
            GateStatus.SwitchingOut, GateStatus.LockedRecovery, GateStatus.ReconciliationRequired, GateStatus.IdentityUnsupported, GateStatus.AnonymousSession)
        identity = next
        if (changedAccount || changedScope || lostTrainer || locked) {
            generation++; operation?.cancel(); operation = null
            mutable.value = InvitationState(accountUid = next.accountUid, hasLink = client.memory.linkFor(identity.accountUid) != null)
        }
        if (changedAccount || pendingObserver == null) {
            pendingObserver?.cancel()
            pendingObserver = next.accountUid?.let { uid -> client.observePending(uid) {
                if (!disposed && identity.accountUid == uid) refreshPending()
            } }
        }
        refreshPending()
        mutable.value = mutable.value.copy(accountUid = next.accountUid, workspaceId = next.verifiedWorkspaceId, authorityEpoch = next.authorityEpoch)
        if (client.memory.revision != memoryRevision) {
            memoryRevision = client.memory.revision
            mutable.value = mutable.value.copy(hasLink = client.memory.linkFor(identity.accountUid) != null, workspaceName = null, entry = "")
        }
    }

    private fun refreshPending() {
        val uid = identity.accountUid
        val read = runCatching { uid?.let(client::pending) }
        read.getOrNull()?.takeIf { it.action == "accept" && it.uid == uid }?.let { client.memory.protectAcceptance(it) }
        val previous = mutable.value.pending
        mutable.value = mutable.value.copy(pending = read.getOrNull(), message = if (read.isFailure)
            "Protected command recovery is unavailable. Sign-out stays blocked." else mutable.value.message)
        if (read.isSuccess && read.getOrNull() == null && uid != null) {
            val proof = runCatching { client.lastSettled(uid) }.getOrNull()
            if (proof?.source == "resolution-v1" || proof?.marker?.source == "resolution-v1") mutable.value=mutable.value.copy(message=if ((proof?.resolution?.outcome ?: proof?.marker?.outcome) == "applied")
                "The original request completed. Recheck access separately." else "The original request was not applied. It cannot execute later.")
        }
        val pending = read.getOrNull()
        val identityBlocked = setOf(GateStatus.AccountDisabled,GateStatus.UnverifiedEmail,GateStatus.AnonymousSession,GateStatus.SessionExpired,GateStatus.SignedOut,GateStatus.CleanupRequired,GateStatus.SwitchingOut,GateStatus.Loading,GateStatus.IdentityUnsupported,GateStatus.UnsupportedSchema,GateStatus.Unconfigured,GateStatus.ReconciliationRequired,GateStatus.NoAccount,GateStatus.InvalidCredentials)
        val identityReady = uid != null && identity.status !in identityBlocked && identity.recoveryCause !in identityBlocked
        val linked = pending?.action == "accept" && client.memory.linkFor(uid)?.let { it.invitationId == pending.invitationId && it.version == pending.expectedVersion } == true
        mutable.value=mutable.value.copy(resolutionEligibility=when {
            read.isFailure -> "storage-failure"
            !identityReady -> "identity-required"
            pending != null && (CommandResolution.bound(pending) || linked) -> "ready"
            else -> "binding-required"
        })
        // A recreated root receives only a ledger change, never the old root's reply. It may
        // request the ordinary server gate, which independently decides current authority.
        if (previous?.action == "accept" && read.isSuccess && read.getOrNull() == null && !mutable.value.busy && uid != null)
            effects.trySend(InvitationEffect.RecheckMembership(uid))
    }

    fun onEvent(event: InvitationEvent) {
        if (disposed) return
        val s = mutable.value
        if (s.busy && event !is InvitationEvent.Entry && event !is InvitationEvent.Email) return
        when (event) {
            is InvitationEvent.Email -> if (canTrain() && s.trainerPage == TrainerInvitationPage.Form && s.pending == null)
                mutable.value = s.copy(email = event.value.take(254))
            is InvitationEvent.Entry -> mutable.value = s.copy(entry = event.value.take(512))
            InvitationEvent.Open -> {
                if (!retainEntry(s.entry.trim())) return
                memoryRevision = client.memory.revision
                mutable.value = s.copy(entry = "", hasLink = true, workspaceName = null, expiresAtMillis = null, confirmation = null, deliveryLink = null,
                    message = "Sign in with the invited account and verify its email, then preview the invitation.")
                refreshPending()
                if (canUseInvitation() && s.pending == null) preview()
            }
            InvitationEvent.Preview -> if (s.pending == null) preview()
            InvitationEvent.ListInvitations -> if (canTrain() && s.pending == null && s.confirmation == null) {
                mutable.value = s.copy(trainerPage = TrainerInvitationPage.List)
                execute(InvitationRequest("list", workspaceId = identity.verifiedWorkspaceId))
            }
            InvitationEvent.ShowForm -> if (canTrain() && s.pending == null && s.confirmation == null)
                mutable.value = s.copy(trainerPage = TrainerInvitationPage.Form, deliveryLink = null, message = "")
            InvitationEvent.Create -> if (canTrain() && s.pending == null && s.confirmation == null && s.trainerPage == TrainerInvitationPage.Form) {
                val email = s.email.trim().lowercase()
                if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) {
                    mutable.value = s.copy(message = "Enter a valid client email."); return
                }
                execute(InvitationRequest("create", workspaceId = identity.verifiedWorkspaceId,
                    email = email, idempotencyKey = client.newCommandKey()))
            }
            InvitationEvent.Accept -> if (canUseInvitation() && s.workspaceName != null && s.pending == null && s.confirmation == null) {
                client.memory.linkFor(identity.accountUid)?.let { link -> mutable.value = s.copy(confirmation = InvitationRequest("accept",
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
                if (retainEntry(value)) {
                    memoryRevision = client.memory.revision
                    mutable.value = s.copy(deliveryLink = null, hasLink = true, entry = "", workspaceName = null, expiresAtMillis = null, confirmation = null,
                        message = "Local test invitation opened. Safely sign out and use the invited account.")
                }
            }
            InvitationEvent.PopulateEntry -> client.memory.linkFor(identity.accountUid)?.let { mutable.value = s.copy(entry = it.entry()) }
            InvitationEvent.Resolve -> {
                val pending = s.pending ?: return
                if (pending.uid != identity.accountUid) return
                if (s.resolutionEligibility != "ready") {
                    mutable.value = s.copy(message="Verify the original global identity before resolving."); return
                }
                val binding = if (pending.action == "accept" && pending.acceptanceTokenDigest == null) {
                    val link = client.memory.linkFor(pending.uid)?.takeIf { it.invitationId == pending.invitationId && it.version == pending.expectedVersion }
                    if (link == null) { mutable.value=s.copy(message="Original invitation binding required. Reopen the original link; nothing was discarded."); return }
                    pending.copy(acceptanceTokenDigest=commandDigest(link.token))
                } else pending
                if (CommandResolution.bound(binding)) mutable.value=s.copy(resolutionPrompt=binding,resolutionPromptId=++resolutionSequence)
            }
            is InvitationEvent.ConfirmResolution -> {
                val prompt = s.resolutionPrompt ?: return
                if (s.resolutionPromptId != event.promptId || prompt != event.command || s.pending?.let { CommandResolution.matches(it,prompt) } != true) return
                mutable.value=s.copy(resolutionPrompt=null)
                execute(InvitationRequest("resolve-command",resolution=prompt),recovery=true)
            }
            is InvitationEvent.CancelResolution -> if (event.promptId == s.resolutionPromptId) mutable.value=s.copy(resolutionPrompt=null)
            InvitationEvent.RetryCommand -> {
                val pending = s.pending ?: return
                if (pending.uid != identity.accountUid || pending.resolutionStarted) return
                val link = client.memory.linkFor(identity.accountUid)
                if (pending.action == "accept" && (link == null || link.invitationId != pending.invitationId || link.version != pending.expectedVersion)) {
                    mutable.value = s.copy(message = "Reopen the original invitation to resolve the pending acceptance."); return
                }
                execute(CommandResolution.original(pending).copy(token=if (pending.action == "accept") link?.token else null), recovery=true)
            }
        }
    }

    private fun retainEntry(value: String): Boolean {
        val candidate = InvitationLink.parse(value)
        if (candidate == null) {
            mutable.value = mutable.value.copy(entry = "", message = "This invitation entry is invalid."); return false
        }
        // Read current protected work, rather than trusting a possibly stale presentation snapshot.
        val read = runCatching { identity.accountUid?.let(client::pending) }
        val pending = read.getOrNull()
        if (read.isFailure || pending != null && pending.uid != identity.accountUid ||
            pending?.action == "accept" && !client.memory.protectAcceptance(pending)) {
            mutable.value = mutable.value.copy(entry = "", message = "Protected command recovery is unavailable. Sign-out stays blocked.")
            return false
        }
        if (!client.memory.open(candidate.entry(), identity.accountUid)) {
            mutable.value = mutable.value.copy(entry = "", message = "Resolve the pending acceptance with its original invitation before opening another link.")
            return false
        }
        return true
    }

    private fun canTrain(): Boolean = identity.status == GateStatus.TrainerHome && identity.verifiedRole == "trainer" && !identity.busy
    private fun canUseInvitation(): Boolean = identity.accountUid != null && !identity.busy && identity.status in
        setOf(GateStatus.NoAccount, GateStatus.WorkspaceGate, GateStatus.MembershipVerifiedFeaturePending, GateStatus.Onboarding, GateStatus.ClientHome, GateStatus.MultipleMemberships, GateStatus.DepartureBlocked)
    private fun preview() {
        if (!canUseInvitation()) return
        client.memory.linkFor(identity.accountUid)?.let { execute(InvitationRequest("preview", invitationId = it.invitationId, token = it.token, expectedVersion = it.version)) }
    }
    private fun confirmation(summary: InvitationSummary, action: String) {
        if (!canTrain() || mutable.value.pending != null || mutable.value.confirmation != null ||
            summary.status != "pending" || summary !in mutable.value.summaries) return
        mutable.value = mutable.value.copy(confirmation = InvitationRequest(action,
            workspaceId = identity.verifiedWorkspaceId, invitationId = summary.invitationId,
            expectedVersion = summary.tokenVersion, idempotencyKey = client.newCommandKey()))
    }
    private fun execute(request: InvitationRequest, recovery: Boolean = false) {
        val uid = identity.accountUid ?: return
        if (recovery && identity.status in setOf(GateStatus.CleanupRequired, GateStatus.SwitchingOut)) return
        if (!recovery && request.action in setOf("create", "list", "resend", "revoke") && !canTrain()) return
        if (!recovery && request.action in setOf("preview", "accept") && !canUseInvitation()) return
        val captured = ++generation
        val listing = request.action == "list"
        mutable.value = mutable.value.copy(busy = true, message = "Contacting the invitation service…",
            deliveryLink = if (listing) mutable.value.deliveryLink else null,
            summaries = if (listing) emptyList() else mutable.value.summaries,
            listLoaded = if (listing) false else mutable.value.listLoaded, listFailed = false)
        operation = client.execute(uid, request) { outcome ->
            if (disposed || generation != captured || identity.accountUid != uid) return@execute
            val reply = outcome.reply
            val refreshSummaries = reply != null && outcome.error == null &&
                request.action in setOf("create", "resend", "revoke") && canTrain()
            if (outcome.error != null) {
                mutable.value = mutable.value.copy(busy = false, workspaceName = null, listFailed = listing,
                    message = if (listing) {
                        if (outcome.error in setOf("unauthorized", "unauthenticated")) "Invitation management requires current workspace-owner access. Recheck access."
                        else "Invitation summaries are unavailable. Refresh while connected."
                    } else message(outcome.error, outcome.ambiguous))
            } else if (reply == null) {
                mutable.value = mutable.value.copy(busy = false, listFailed = listing, message = "Invitation service unavailable. Retry while connected.")
            } else {
                val link = if (reply.deliveryToken != null && reply.invitationId != null && reply.tokenVersion != null)
                    InvitationLink(reply.invitationId, reply.tokenVersion, reply.deliveryToken).entry() else null
                mutable.value = mutable.value.copy(busy = refreshSummaries, email = if (request.action == "create") "" else mutable.value.email,
                    workspaceName = if (request.action == "preview") reply.workspaceName else null,
                    expiresAtMillis = if (request.action == "preview") reply.expiresAtMillis else null,
                    summaries = reply.summaries ?: mutable.value.summaries,
                    listLoaded = listing || mutable.value.listLoaded, listFailed = false,
                    trainerPage = when (request.action) { "create", "resend" -> TrainerInvitationPage.Result; "revoke" -> TrainerInvitationPage.List; else -> mutable.value.trainerPage },
                    deliveryLink = if (listing) mutable.value.deliveryLink else link, message = when (request.action) {
                        "resolve-command" -> if (reply.terminalOutcome?.outcome == "applied") "The original request completed. Recheck access separately." else "The original request was not applied. This command cannot execute later."
                        "accept" -> "Invitation accepted. Rechecking membership online…"
                        "preview" -> "Invitation verified online. Confirm to join this workspace."
                        "list" -> if (reply.summaries.isNullOrEmpty()) "No invitations yet." else "Invitations refreshed."
                        "revoke" -> "Invitation revoked."
                        else -> if (link != null) "Invitation ready for local testing. No email was sent."
                            else "Command confirmed. The delivery token is unavailable; resend to rotate a new local link."
                    })
                if (request.action == "accept") {
                    client.memory.clear(uid)
                    mutable.value = mutable.value.copy(hasLink = false)
                    effects.trySend(InvitationEffect.RecheckMembership(uid))
                }
            }
            refreshPending()
            // A receipt does not supply the current list or an authorized roster row.
            if (refreshSummaries) {
                if (mutable.value.pending == null) execute(InvitationRequest("list", workspaceId = identity.verifiedWorkspaceId))
                else mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
    override fun onCleared() {
        disposed = true; generation++; operation?.cancel(); pendingObserver?.cancel()
        mutable.value = InvitationState(); effects.close()
    }
}

private fun message(error: String, ambiguous: Boolean): String = if (error == "storage-unavailable")
    "Protected storage could not confirm settlement. The original command remains locked; no cleanup or repair was performed."
else if (error == "identity-required") "Verify the original global identity before resolving; no repair was performed."
else if (error == "outcome-unavailable") "Outcome unavailable. The original command remains protected; binding or receipt history may be unavailable."
else if (error in setOf("unauthorized", "unauthenticated"))
    "Access was lost. The original command remains protected; a receipt cannot grant access. Recheck this account."
else if (error in setOf("record-invalid", "counter-inconsistent", "idempotency-key-reused", "revision-conflict"))
    "The original command needs trusted reconciliation. It remains locked; discard and automatic repair have no approved policy."
else if (error == "rate-limited") "Request limit reached. Retry can recover an existing outcome; a new fence may remain unavailable."
else if (ambiguous)
    "The outcome is unknown. The original command stays protected. Retry Resolve if resolution has started; sign-out remains blocked."
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
