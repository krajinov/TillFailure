package com.delminiusapps.tillfailure.profile

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class ProfileScope(val uid: String?, val workspace: String?, val epoch: Long)
enum class ProfileExit { Back, Home, Profile }
enum class ProfilePrompt { Leave, Reload }
data class ProfileState(
    val scope: ProfileScope? = null, val visit: Long? = null, val baseline: BasicProfile? = null,
    val name: String = "", val locale: String = "", val zone: String = "",
    val busy: Boolean = false, val conflict: Boolean = false, val saved: Boolean = false,
    val unavailable: Boolean = false, val accessLost: Boolean = false,
    val pending: PendingInvitationCommand? = null, val storageLocked: Boolean = false,
    val prompt: ProfilePrompt? = null, val promptId: Long = 0, val exit: ProfileExit = ProfileExit.Back,
    val message: String = "",
) {
    val dirty get() = baseline?.let { name != it.displayName || locale != it.locale || zone != it.timeZoneId } == true
    override fun toString() = "ProfileState(busy=$busy)"
}
sealed interface ProfileEvent {
    data class Name(val value: String) : ProfileEvent { override fun toString() = "ProfileEvent.Name(redacted)" }
    data class Locale(val value: String) : ProfileEvent
    data class Zone(val value: String) : ProfileEvent
    data object Save : ProfileEvent
    data object Reload : ProfileEvent
    data object RetryOriginal : ProfileEvent
    data class Leave(val exit: ProfileExit) : ProfileEvent
    data object Confirm : ProfileEvent
    data object Cancel : ProfileEvent
}
data class ProfileNavigation(val scope: ProfileScope, val visit: Long, val exit: ProfileExit, val snapshot: BasicProfile?, val sequence: Long)

/** One root owner, the existing UID command registry, transient bounded drafts. */
class ProfileViewModel(private var client: InvitationClient) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileState())
    val state = mutable.asStateFlow()
    private val effects = Channel<ProfileNavigation>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var identity = IdentityState()
    private var operation: FirebaseCancellation? = null
    private var observer: FirebaseCancellation? = null
    private var generation = 0L
    private var promptSequence = 0L
    private var disposed = false
    private var navigationPending = false
    private var navigationSequence = 0L
    private var suspendedAt: ProfileScope? = null
    fun currentState() = mutable.value
    private fun scope() = ProfileScope(identity.accountUid, identity.verifiedWorkspaceId, identity.authorityEpoch)
    private fun eligible() = suspendedAt == null && identity.status == GateStatus.ClientHome && identity.verifiedRole == "client" && !identity.busy
    fun update(client: InvitationClient, gate: IdentityState) {
        if (disposed) return
        val changed = this.client !== client || scope() != ProfileScope(gate.accountUid, gate.verifiedWorkspaceId, gate.authorityEpoch) ||
            identity.status != gate.status
        this.client = client; identity = gate
        if (suspendedAt != scope()) suspendedAt = null
        if (changed) {
            generation++; operation?.cancel(); observer?.cancel(); navigationPending = false
            while (effects.tryReceive().isSuccess) { /* Retire old destination requests. */ }
            mutable.value = ProfileState(scope = scope(), accessLost = !eligible())
            observer = gate.accountUid?.let { uid -> client.observePending(uid) { if (!disposed && identity.accountUid == uid) pending() } }
        }
        pending()
    }
    private fun pending() {
        val read = runCatching { identity.accountUid?.let(client::pending) }
        mutable.value = mutable.value.copy(pending = read.getOrNull(), storageLocked = read.isFailure)
    }
    fun suspendPresentation() {
        if (disposed) return
        suspendedAt = scope(); generation++; operation?.cancel(); navigationPending = false
        while (effects.tryReceive().isSuccess) { /* No queued navigation survives background. */ }
        mutable.value = ProfileState(scope = scope(), accessLost = true); pending()
    }
    fun attach(visit: Long) {
        if (disposed || !eligible() || mutable.value.visit == visit) return
        generation++; operation?.cancel(); navigationPending = false
        mutable.value = ProfileState(scope = scope(), visit = visit)
        pending()
        if (mutable.value.pending == null && !mutable.value.storageLocked) load()
    }
    fun detach(owner: ProfileScope, visit: Long) {
        if (disposed || mutable.value.scope != owner || mutable.value.visit != visit) return
        generation++; operation?.cancel()
        mutable.value = ProfileState(scope = scope(), accessLost = !eligible()); pending()
    }
    fun onEvent(owner: ProfileScope, visit: Long?, promptId: Long, event: ProfileEvent) {
        if (disposed || suspendedAt != null || owner != scope() || mutable.value.scope != owner || visit != mutable.value.visit || navigationPending) return
        val s = mutable.value
        if (event == ProfileEvent.RetryOriginal) {
            if (s.busy || identity.status in setOf(GateStatus.SwitchingOut, GateStatus.CleanupRequired)) return
            val p = s.pending?.takeIf { it.uid == owner.uid && it.action == "update-profile" && !it.resolutionStarted } ?: return
            execute(InvitationRequest(p.action, workspaceId = p.workspaceId, idempotencyKey = p.idempotencyKey, profileUpdate = p.profileUpdate))
            return
        }
        if (!eligible() || s.busy) return
        val editable = s.pending == null && !s.storageLocked && s.baseline != null && !s.conflict && !s.accessLost && !s.unavailable && s.prompt == null
        when (event) {
            is ProfileEvent.Name -> if (editable) mutable.value = s.copy(name = event.value.take(80), message = "")
            is ProfileEvent.Locale -> if (editable) mutable.value = s.copy(locale = event.value.take(35), message = "")
            is ProfileEvent.Zone -> if (editable) mutable.value = s.copy(zone = event.value.take(64), message = "")
            ProfileEvent.Save -> if (editable && s.dirty) {
                val update = ProfileUpdate(s.name.trim(), s.locale, s.zone, s.baseline!!.revision)
                if (!update.valid()) { mutable.value = s.copy(message = "Enter a valid display name, locale and time zone."); return }
                execute(InvitationRequest("update-profile", workspaceId = owner.workspace, idempotencyKey = client.newCommandKey(), profileUpdate = update))
            }
            ProfileEvent.Reload -> if (s.pending == null && !s.storageLocked && s.prompt == null) {
                if (s.dirty) mutable.value = s.copy(prompt = ProfilePrompt.Reload, promptId = ++promptSequence)
                else load()
            }
            is ProfileEvent.Leave -> if (s.prompt == null) {
                if (s.dirty && s.pending == null) mutable.value = s.copy(prompt = ProfilePrompt.Leave, promptId = ++promptSequence, exit = event.exit)
                else navigate(event.exit)
            }
            ProfileEvent.Confirm -> if (s.prompt != null && promptId == s.promptId) {
                mutable.value = s.copy(prompt = null)
                if (s.prompt == ProfilePrompt.Leave) navigate(s.exit) else load()
            }
            ProfileEvent.Cancel -> if (promptId == s.promptId) mutable.value = s.copy(prompt = null)
            ProfileEvent.RetryOriginal -> Unit
        }
    }
    private fun navigate(exit: ProfileExit) {
        val s = mutable.value; val visit = s.visit ?: return
        navigationPending = effects.trySend(ProfileNavigation(scope(), visit, exit, s.baseline, ++navigationSequence)).isSuccess
    }
    /** Recheck at consumption: draining the channel cannot recall an already received effect. */
    fun consumeNavigation(result: ProfileNavigation): Boolean {
        val s = mutable.value
        if (disposed || !eligible() || !navigationPending || result.sequence != navigationSequence ||
            result.scope != scope() || s.scope != result.scope || s.visit != result.visit) return false
        navigationPending = false
        return true
    }
    private fun load() = execute(InvitationRequest("profile-get", workspaceId = identity.verifiedWorkspaceId))
    private fun execute(request: InvitationRequest) {
        val uid = identity.accountUid ?: return
        val captured = ++generation
        mutable.value = mutable.value.copy(busy = true, unavailable = false, message = "Contacting the profile service…")
        operation = client.execute(uid, request) { outcome ->
            if (disposed || generation != captured || identity.accountUid != uid) return@execute
            val r = outcome.reply
            when {
                r?.status == "profile" -> {
                    val p = r.profile!!
                    mutable.value = mutable.value.copy(busy = false, baseline = p, name = p.displayName, locale = p.locale, zone = p.timeZoneId,
                        conflict = false, accessLost = false, message = if (mutable.value.saved) "Basic profile saved." else "")
                }
                r?.status == "profile-updated" -> {
                    mutable.value = mutable.value.copy(busy = false, saved = true, baseline = null, message = "Saved. Reloading current values…")
                    pending()
                    if (eligible() && mutable.value.visit != null) load()
                }
                r?.status == "profile-conflict" -> mutable.value = mutable.value.copy(busy = false, conflict = true,
                    message = "Your profile changed elsewhere. Reload current values before saving again. Your draft has not been overwritten.")
                else -> mutable.value = mutable.value.copy(busy = false, unavailable = true,
                    accessLost = outcome.error in setOf("unauthorized", "unauthenticated", "unverified-email"),
                    message = if (request.action == "update-profile") "Outcome unresolved. Retry the original command; departure remains blocked."
                        else "Current profile is unavailable. Recheck access or retry while connected.")
            }
            pending()
            if (outcome.error != null && request.action == "update-profile" && mutable.value.pending == null && !mutable.value.storageLocked)
                mutable.value = mutable.value.copy(message = "Save outcome is unavailable. Reload current values before trying again.")
        }
        pending()
    }
    override fun onCleared() {
        disposed = true; generation++; operation?.cancel(); observer?.cancel(); effects.close(); mutable.value = ProfileState()
    }
}
