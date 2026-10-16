package com.delminiusapps.tillfailure.onboarding

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.FirebaseDataOrigin
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

@Serializable
data class OnboardingCompletion(
    val displayName: String, val locale: String, val timeZoneId: String, val acknowledged: Boolean,
    val expectedAccountRevision: Int, val expectedOnboardingRevision: Int,
) {
    override fun toString() = "OnboardingCompletion(redacted)"
}

fun validOnboardingBasics(name: String, locale: String, zone: String): Boolean =
    name.isNotBlank() && name == name.trim() && name.length <= 80 &&
        name.none { it.code < 32 || it.code in 127..159 } && validUnicodeName(name) &&
        validOnboardingLocale(locale) &&
        zone.length <= 64 && isKnownOnboardingZone(zone) && Regex("^(UTC|[A-Za-z_+-]+(/[A-Za-z0-9_+-]+)+)$").matches(zone)

private fun validOnboardingLocale(locale: String): Boolean {
    if (locale.length > 35 || !Regex("^[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?(-([A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*$").matches(locale)) return false
    // Intl.Locale rejects repeated variants case-insensitively. Reject before preparing durable work.
    val variants = locale.split('-').drop(1).filter { it.length >= 5 || it.length == 4 && it.first().isDigit() }.map { it.lowercase() }
    return variants.distinct().size == variants.size
}

private fun validUnicodeName(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        if (ch.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (ch.isLowSurrogate()) return false
    }
    return true
}

expect fun isKnownOnboardingZone(zone: String): Boolean

/** No completion is inferred from an absent/malformed profile, local hint or saved NavKey. */
fun onboardingRevision(uid: String, wid: String, document: FirebaseDocumentSnapshot?): Int? {
    val d = document ?: return null
    if (d.path != "workspaces/$wid/clientProfiles/$uid" || !d.exists || d.origin != FirebaseDataOrigin.SERVER || d.hasPendingWrites) return null
    val f = d.fields
    val allowed = setOf("schemaVersion", "workspaceId", "userId", "trainerId", "createdAt", "onboardingStatus", "onboardingRevision", "onboardingPolicyVersion", "completedAt")
    if (f.keys.any { it !in allowed } || f["schemaVersion"] != "1" || f["workspaceId"] != wid || f["userId"] != uid ||
        !isPathSafeAuthUid(f["trainerId"]) || f["createdAt"].isNullOrBlank()) return null
    val revision = f["onboardingRevision"]?.toIntOrNull() ?: if ("onboardingRevision" !in f && f["onboardingStatus"] == "pending") 0 else return null
    if (revision < 0 || revision == Int.MAX_VALUE) return null
    return when (f["onboardingStatus"]) {
        "pending" -> revision.takeIf { "completedAt" !in f && "onboardingPolicyVersion" !in f }
        "complete" -> revision.takeIf { it > 0 && f["onboardingPolicyVersion"] == "1" && !f["completedAt"].isNullOrBlank() }
        else -> null
    }
}

fun accountProfileRevision(fields: Map<String, String>): Int? {
    val legacyEmpty = ("displayName" !in fields || fields["displayName"] == "") && "locale" !in fields && "timeZoneId" !in fields
    val revision = fields["profileRevision"]?.toIntOrNull() ?: if ("profileRevision" !in fields && legacyEmpty) 0 else return null
    if (revision < 0 || revision == Int.MAX_VALUE || fields["_invalidOnboardingTypes"] != null) return null
    if (revision == 0) return revision.takeIf { legacyEmpty }
    return revision.takeIf { validOnboardingBasics(fields["displayName"].orEmpty(), fields["locale"].orEmpty(), fields["timeZoneId"].orEmpty()) }
}

data class OnboardingState(
    val uid: String? = null, val workspaceId: String? = null, val authorityEpoch: Long = 0,
    val name: String = "", val locale: String = "", val zone: String = "", val acknowledged: Boolean = false,
    val busy: Boolean = false, val completed: Boolean = false, val message: String = "", val pending: PendingInvitationCommand? = null,
) { override fun toString() = "OnboardingState(busy=$busy)" }
sealed interface OnboardingEvent {
    data class Name(val value: String) : OnboardingEvent
    data class Locale(val value: String) : OnboardingEvent
    data class Zone(val value: String) : OnboardingEvent
    data class Acknowledge(val value: Boolean) : OnboardingEvent
    data object Complete : OnboardingEvent
    data object Retry : OnboardingEvent
}

/** Uses the existing protected, UID-bound transport/ledger; cancellation never erases a command. */
class OnboardingViewModel(private val client: InvitationClient) : ViewModel() {
    private val mutable = MutableStateFlow(OnboardingState())
    val state = mutable.asStateFlow()
    private val effects = Channel<String>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var identity = IdentityState()
    private var generation = 0L
    private var disposed = false
    private var operation: FirebaseCancellation? = null
    private var observer: FirebaseCancellation? = null
    fun currentState() = mutable.value
    fun updateIdentity(next: IdentityState) {
        if (disposed) return
        val scopeChanged = next.accountUid != identity.accountUid || next.verifiedWorkspaceId != identity.verifiedWorkspaceId || next.authorityEpoch != identity.authorityEpoch
        identity = next
        if (scopeChanged) {
            generation++; operation?.cancel()
            observer?.cancel()
            mutable.value = OnboardingState(uid = next.accountUid, workspaceId = next.verifiedWorkspaceId, authorityEpoch = next.authorityEpoch,
                name = next.displayName, locale = next.locale, zone = next.timeZoneId)
            observer = next.accountUid?.let { uid -> client.observePending(uid) { if (!disposed && identity.accountUid == uid) refreshPending() } }
        }
        refreshPending()
    }
    private fun refreshPending() {
        val read = runCatching { identity.accountUid?.let(client::pending) }
        val old = mutable.value.pending
        val pending = read.getOrNull()?.takeIf { it.action == "complete-onboarding" }
        mutable.value = mutable.value.copy(pending = pending,
            message = if (read.isFailure) "Protected recovery is unavailable. Sign-out stays blocked." else mutable.value.message)
        if (old != null && pending == null && read.isSuccess && !mutable.value.busy)
            identity.accountUid?.let { effects.trySend(it) }
    }
    fun onEvent(event: OnboardingEvent) {
        if (disposed || mutable.value.busy) return
        val s = mutable.value
        when (event) {
            is OnboardingEvent.Name -> if (s.pending == null) mutable.value = s.copy(name = event.value.take(80))
            is OnboardingEvent.Locale -> if (s.pending == null) mutable.value = s.copy(locale = event.value.take(35))
            is OnboardingEvent.Zone -> if (s.pending == null) mutable.value = s.copy(zone = event.value.take(64))
            is OnboardingEvent.Acknowledge -> if (s.pending == null) mutable.value = s.copy(acknowledged = event.value)
            OnboardingEvent.Complete -> {
                if (identity.status != GateStatus.Onboarding || identity.busy || s.pending != null || s.completed) return
                // The service must be able to increment both revisions below its reserved ceiling.
                val expectedRevisions = 0..Int.MAX_VALUE - 2
                if (identity.accountProfileRevision !in expectedRevisions || identity.onboardingRevision !in expectedRevisions) {
                    mutable.value = s.copy(message = "Profile cannot be updated. Recheck access or contact support."); return
                }
                if (!validOnboardingBasics(s.name.trim(), s.locale, s.zone) || !s.acknowledged) {
                    mutable.value = s.copy(message = "Enter a display name, locale (for example en), time zone (for example Europe/Sarajevo) and confirm completion."); return
                }
                execute(InvitationRequest("complete-onboarding", workspaceId = identity.verifiedWorkspaceId,
                    idempotencyKey = client.newCommandKey(), onboarding = OnboardingCompletion(s.name.trim(), s.locale, s.zone, true,
                        identity.accountProfileRevision, identity.onboardingRevision)))
            }
            OnboardingEvent.Retry -> {
                val p = s.pending ?: return
                if (p.uid != identity.accountUid || identity.status in setOf(GateStatus.SwitchingOut, GateStatus.CleanupRequired)) return
                execute(InvitationRequest(p.action, workspaceId = p.workspaceId, idempotencyKey = p.idempotencyKey, onboarding = p.onboarding))
            }
        }
    }
    private fun execute(request: InvitationRequest) {
        val uid = identity.accountUid ?: return
        val captured = ++generation
        mutable.value = mutable.value.copy(busy = true, message = "Saving completion online…")
        operation = client.execute(uid, request) { result ->
            if (disposed || captured != generation || identity.accountUid != uid) return@execute
            mutable.value = mutable.value.copy(busy = false, completed = result.reply?.status == "complete", message = when {
                result.reply?.status == "complete" -> "Onboarding complete. Rechecking access online…"
                result.error == "storage-unavailable" -> "Protected storage could not confirm settlement. Recovery stays locked; nothing was deleted."
                result.error == "record-invalid" -> "Protected data needs trusted reconciliation. The original command remains locked."
                result.error == "revision-conflict" -> "Profile changed. The original command needs trusted reconciliation; sign-out stays blocked."
                result.error == "already-complete" -> "Already completed elsewhere. Recheck access; the unresolved command remains protected."
                result.error == "unverified-email" -> "Verify your email and recheck access; the original command remains protected."
                result.error == "unauthorized" -> "Access lost. Recheck access; the original command remains protected."
                result.error == "input-invalid" -> "The service rejected these fields. The original command remains protected for review."
                else -> "Outcome unavailable. Retry the original completion. Sign-out stays blocked."
            })
            refreshPending()
            if (result.reply?.status == "complete") effects.trySend(uid)
        }
        refreshPending()
    }
    override fun onCleared() { disposed = true; generation++; operation?.cancel(); observer?.cancel(); effects.close() }
}
