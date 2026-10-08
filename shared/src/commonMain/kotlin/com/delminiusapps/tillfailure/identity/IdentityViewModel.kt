package com.delminiusapps.tillfailure.identity

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.FirebaseDataOrigin
import com.delminiusapps.tillfailure.firebase.StableFirebaseErrorCode
import com.delminiusapps.tillfailure.firebase.StableFirebaseFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.delminiusapps.tillfailure.persistence.RecoveryUidContract

@kotlinx.serialization.Serializable
enum class GateStatus {
    Loading, SignedOut, InvalidCredentials, IdentityUnsupported, AnonymousSession, NoAccount, AccountDisabled,
    UnverifiedEmail,
    WorkspaceGate, MembershipVerifiedFeaturePending, UnsupportedSchema, AccessLost,
    ConnectToVerify, Retry, MultipleMemberships, Unconfigured, SessionExpired, CleanupRequired,
    SwitchingOut, DepartureBlocked,
}

data class IdentityState(
    val status: GateStatus = GateStatus.Loading,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val canSignOut: Boolean = false,
    val verifiedWorkspaceId: String? = null,
    val verifiedRole: String? = null,
)

sealed interface IdentityEvent {
    data class EmailChanged(val value: String) : IdentityEvent
    data class PasswordChanged(val value: String) : IdentityEvent
    data object SignIn : IdentityEvent
    data object Retry : IdentityEvent
    data object SignOut : IdentityEvent
    data object RetryCleanup : IdentityEvent
}

sealed interface IdentityEffect {
    data class ReplaceRoot(val status: GateStatus) : IdentityEffect
}

/** Authentication and authorization are separate. No role state is read from local storage. */
class IdentityViewModel(initialClient: ProductIdentityClient) : ViewModel() {
    private var client = initialClient
    private val mutableState = MutableStateFlow(IdentityState())
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<IdentityEffect>(Channel.BUFFERED)
    val effect = effectChannel.receiveAsFlow()
    private val operations = mutableListOf<FirebaseCancellation>()
    private var revision = 0L
    private var observedUid: String? = null
    private var restrictedSession = false
    private var sessionListener: FirebaseCancellation? = null
    private var cleared = false

    init { recoverOnStartup() }

    /** Current immutable gate state; a small non-flow accessor for native hosts and diagnostics. */
    fun currentState(): IdentityState = mutableState.value

    private fun recoverOnStartup() {
        val port = client.departure
        if (port == null) { observeSession(); return }
        // A departure started by an earlier account graph may still be draining pending SDK writes
        // before its durable switch marker exists. Until it settles this recreated root must not
        // observe or verify the still signed-in session: that older departure can still sign out,
        // fence callbacks or retire this Firebase client. Join it and adopt its terminal outcome
        // instead of re-authorizing (and without starting a competing departure).
        if (port.ownership.observeInFlight(::onDepartureAdopted) != null) {
            setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true))
            return
        }
        val read = port.readMarker()
        if (!read.readable) { finish(GateStatus.CleanupRequired); return }
        val marker = read.marker
        if (marker == null) { observeSession(); return }
        // A durable marker from an earlier run: resume its cleanup fail-closed before another login.
        setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true))
        if (!port.ownership.lease(marker.departingUid, ::onDepartureAdopted)) return
        CleanDepartureCoordinator(port).recover(::cancelSessionAndOperations) { outcome, _ ->
            port.ownership.settle(marker.departingUid, outcome)
        }
    }

    private fun observeSession() {
        sessionListener = client.observeSession(0L) { session ->
            val uid = session.uid
            if (uid == null) {
                val mustBlock = observedUid != null || client.hasPinnedIdentity()
                observedUid = null
                restrictedSession = false
                cancelOperations()
                setState(mutableState.value.copy(
                    status = if (mustBlock) GateStatus.CleanupRequired else GateStatus.SignedOut, busy = false,
                    verifiedWorkspaceId = null, verifiedRole = null))
            } else if (session.isAnonymous) {
                if (uid == observedUid && mutableState.value.status == GateStatus.AnonymousSession) return@observeSession
                cancelOperations()
                // Anonymous Auth is never authority for an account path. Keep only the UID needed
                // to prove and perform the same clean departure as any other restricted session.
                val recoveryValid = RecoveryUidContract.isValid(uid)
                val canPinForRecovery = recoveryValid && client.claimPinnedIdentity(uid)
                observedUid = if (canPinForRecovery) uid else null
                restrictedSession = true
                setState(mutableState.value.copy(
                    status = if (canPinForRecovery || !recoveryValid) GateStatus.AnonymousSession
                        else GateStatus.CleanupRequired,
                    email = "", password = "", busy = false,
                    verifiedWorkspaceId = null, verifiedRole = null))
            } else if (uid != observedUid || mutableState.value.status == GateStatus.AnonymousSession) {
                if (!isPathSafeAuthUid(uid)) {
                    cancelOperations()
                    // Keep only the Auth UID needed for UID-bound local cleanup. It is never used
                    // to construct a Firestore account or membership path.
                    val canPinForRecovery = RecoveryUidContract.isValid(uid) && client.claimPinnedIdentity(uid)
                    observedUid = if (canPinForRecovery) uid else null
                    restrictedSession = true
                    setState(mutableState.value.copy(
                        status = if (canPinForRecovery || !RecoveryUidContract.isValid(uid))
                            GateStatus.IdentityUnsupported else GateStatus.CleanupRequired,
                        email = "", password = "", busy = false,
                        verifiedWorkspaceId = null, verifiedRole = null))
                    return@observeSession
                }
                if (!client.claimPinnedIdentity(uid)) {
                    observedUid = null
                    cancelOperations()
                    setState(mutableState.value.copy(status = GateStatus.CleanupRequired, busy = false,
                        verifiedWorkspaceId = null, verifiedRole = null))
                    return@observeSession
                }
                observedUid = uid
                restrictedSession = false
                verify(uid)
            }
        }
    }

    fun onEvent(event: IdentityEvent) {
        when (event) {
            is IdentityEvent.EmailChanged -> mutableState.value = mutableState.value.copy(email = event.value)
            is IdentityEvent.PasswordChanged -> mutableState.value = mutableState.value.copy(password = event.value)
            IdentityEvent.SignIn -> {
                if (mutableState.value.busy ||
                    mutableState.value.status !in setOf(GateStatus.SignedOut, GateStatus.InvalidCredentials,
                        GateStatus.ConnectToVerify) ||
                    client.hasPinnedIdentity()) return
                val email = mutableState.value.email.trim()
                val password = mutableState.value.password
                if (email.isEmpty() || password.isEmpty()) {
                    setState(mutableState.value.copy(status = GateStatus.InvalidCredentials))
                    return
                }
                setState(mutableState.value.copy(status = GateStatus.Loading, busy = true))
                val request = ++revision
                operations += client.signIn(email, password, 0L) { failure ->
                    if (request == revision && failure != null) {
                        setState(mutableState.value.copy(
                            status = if (failure.code == StableFirebaseErrorCode.INVALID_CREDENTIALS) GateStatus.InvalidCredentials
                            else failureStatus(failure), busy = false,
                        ))
                    }
                }
            }
            IdentityEvent.Retry -> if (observedUid != null && !restrictedSession) {
                verify(observedUid!!)
            } else if (mutableState.value.status == GateStatus.ConnectToVerify) {
                onEvent(IdentityEvent.SignIn)
            }
            IdentityEvent.SignOut -> {
                val uid = observedUid ?: return
                val port = client.departure ?: return
                if (!mutableState.value.canSignOut || mutableState.value.busy || mutableState.value.status in
                    setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired)) return
                setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true,
                    verifiedWorkspaceId = null, verifiedRole = null))
                // The process-level owner claims the single departure slot; a competing graph joins.
                if (!port.ownership.lease(uid, ::onDepartureStarted)) return
                CleanDepartureCoordinator(port).depart(uid, ::cancelSessionAndOperations) { outcome ->
                    port.ownership.settle(uid, outcome)
                }
            }
            IdentityEvent.RetryCleanup -> {
                if (mutableState.value.status != GateStatus.CleanupRequired) return
                val oldPort = client.departure ?: return
                if (oldPort.isRetired()) client = oldPort.replacement()
                val port = client.departure ?: return
                setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true))
                val read = port.readMarker()
                if (!read.readable) { finish(GateStatus.CleanupRequired); return }
                val marker = read.marker
                if (marker == null) {
                    observedUid = null
                    restrictedSession = false
                    client = port.replacement()
                    setState(IdentityState(status = GateStatus.SignedOut))
                    observeSession()
                    return
                }
                if (!port.ownership.lease(marker.departingUid, ::onDepartureStarted)) return
                CleanDepartureCoordinator(port).recover(::cancelSessionAndOperations) { outcome, _ ->
                    port.ownership.settle(marker.departingUid, outcome)
                }
            }
        }
    }

    private fun verify(uid: String) {
        cancelOperations()
        val request = revision
        setState(mutableState.value.copy(status = GateStatus.Loading, busy = true,
            password = "", verifiedWorkspaceId = null, verifiedRole = null))
        if (!isPathSafeAuthUid(uid)) {
            setState(mutableState.value.copy(status = GateStatus.IdentityUnsupported, busy = false))
            return
        }
        operations += client.refreshSession(0L) { refresh ->
            if (request != revision) return@refreshSession
            val failure = refresh.failure
            if (failure != null) {
                setState(mutableState.value.copy(status = failureStatus(failure), busy = false))
                return@refreshSession
            }
            val session = refresh.session
            if (session == null) { finish(GateStatus.AccessLost); return@refreshSession }
            // A non-anonymous email/password session whose freshly refreshed Auth user is not
            // verified must stop before any account/workspace/membership authorization. A cached
            // observed flag or a client-entered email is never authority.
            if (requiresUnverifiedEmailGate(session)) {
                setState(mutableState.value.copy(status = GateStatus.UnverifiedEmail, busy = false))
                return@refreshSession
            }
            readAccount(uid, request)
        }
    }

    private fun readAccount(uid: String, request: Long) {
        operations += client.getDocument("users/$uid", 0L) { result ->
            if (request != revision) return@getDocument
            if (result.failure != null) {
                fail(result.failure)
                return@getDocument
            }
            when (val decision = checkAccount(uid, result.document)) {
                null -> discover(uid, request)
                IdentityDecision.NoAccount -> finish(GateStatus.NoAccount)
                IdentityDecision.AccountDisabled -> finish(GateStatus.AccountDisabled)
                IdentityDecision.UnsupportedSchema -> finish(GateStatus.UnsupportedSchema)
                else -> finish(GateStatus.AccessLost)
            }
        }
    }

    private fun discover(uid: String, request: Long) {
        operations += client.discoverMemberships(uid, 0L) { result ->
            if (request != revision) return@discoverMemberships
            if (result.failure != null) { fail(result.failure); return@discoverMemberships }
            val documents = result.documents ?: run { finish(GateStatus.AccessLost); return@discoverMemberships }
            if (documents.any { it.origin != FirebaseDataOrigin.SERVER || it.hasPendingWrites }) {
                finish(GateStatus.AccessLost); return@discoverMemberships
            }
            if (documents.isEmpty()) { finish(GateStatus.WorkspaceGate); return@discoverMemberships }
            if (documents.size > 1) { finish(GateStatus.MultipleMemberships); return@discoverMemberships }
            val discovered = documents.single()
            val pieces = discovered.path.split('/')
            if (pieces.size != 4 || pieces[0] != "workspaces" || pieces[2] != "memberships" || pieces[3] != uid ||
                discovered.fields["schemaVersion"] != "1" || discovered.fields["userId"] != uid ||
                discovered.fields["workspaceId"] != pieces[1] || discovered.fields["status"] != "active"
            ) { finish(GateStatus.AccessLost); return@discoverMemberships }
            val wid = pieces[1]
            operations += client.getDocument("workspaces/$wid", 0L) { workspaceResult ->
                if (request != revision) return@getDocument
                if (workspaceResult.failure != null) { fail(workspaceResult.failure); return@getDocument }
                operations += client.getDocument("workspaces/$wid/memberships/$uid", 0L) { memberResult ->
                    if (request != revision) return@getDocument
                    if (memberResult.failure != null) { fail(memberResult.failure); return@getDocument }
                    when (val decision = checkMembership(uid, wid, workspaceResult.document, memberResult.document)) {
                        is IdentityDecision.Verified -> {
                            setState(mutableState.value.copy(
                                status = GateStatus.MembershipVerifiedFeaturePending, busy = false,
                                verifiedWorkspaceId = decision.workspaceId, verifiedRole = decision.role,
                            ))
                            observeRevocation(uid, wid, request)
                        }
                        IdentityDecision.UnsupportedSchema -> finish(GateStatus.UnsupportedSchema)
                        else -> finish(GateStatus.AccessLost)
                    }
                }
            }
        }
    }

    private fun observeRevocation(uid: String, wid: String, request: Long) {
        val serverObserved = mutableSetOf<String>()
        for (path in listOf("users/$uid", "workspaces/$wid", "workspaces/$wid/memberships/$uid")) {
            operations += client.listenDocument(path, 0L) { result ->
                if (request != revision) return@listenDocument
                if (result.failure != null) { cancelOperations(); finish(failureStatus(result.failure)); return@listenDocument }
                val document = result.document ?: return@listenDocument
                if (document.origin != FirebaseDataOrigin.SERVER) {
                    if (path in serverObserved) {
                        cancelOperations()
                        finish(GateStatus.ConnectToVerify)
                    }
                    return@listenDocument
                }
                serverObserved += path
                if (document.hasPendingWrites || !document.exists || document.fields["schemaVersion"] != "1" ||
                    (path == "users/$uid" && document.fields["accountStatus"] != "active") ||
                    (path == "workspaces/$wid" && document.fields["status"] != "active") ||
                    (path.endsWith("/memberships/$uid") && (document.fields["status"] != "active" ||
                        document.fields["userId"] != uid || document.fields["workspaceId"] != wid ||
                        document.fields["role"] != mutableState.value.verifiedRole))) {
                    cancelOperations()
                    finish(GateStatus.AccessLost)
                }
            }
        }
    }

    /** Terminal outcome of a departure this graph started, or one it joined at startup. */
    private fun onDepartureStarted(outcome: DepartureOutcome) = onDepartureSettled(outcome, startedHere = true)
    private fun onDepartureAdopted(outcome: DepartureOutcome) = onDepartureSettled(outcome, startedHere = false)

    private fun onDepartureSettled(outcome: DepartureOutcome, startedHere: Boolean) {
        // A graph cleared by Activity/root recreation must not act on the shared outcome; the live
        // recreated graph is the one that adopts it, so exactly one graph rebuilds the root.
        if (cleared) return
        val port = client.departure
        when (outcome) {
            DepartureOutcome.Clean -> {
                observedUid = null
                restrictedSession = false
                if (port != null) client = port.replacement()
                setState(IdentityState(status = GateStatus.SignedOut))
                observeSession()
            }
            DepartureOutcome.PreflightBlocked -> {
                if (startedHere) {
                    setState(mutableState.value.copy(status = GateStatus.DepartureBlocked, busy = false))
                } else {
                    // The joined departure was cancelled before any durable change; the account is
                    // intact and unfrozen, so this recreated root safely resumes verification.
                    setState(mutableState.value.copy(status = GateStatus.SignedOut, busy = false))
                    observeSession()
                }
            }
            DepartureOutcome.CleanupRequired -> finish(GateStatus.CleanupRequired)
        }
    }

    private fun finish(status: GateStatus) {
        setState(mutableState.value.copy(status = status, busy = false,
            verifiedWorkspaceId = null, verifiedRole = null))
    }

    private fun setState(next: IdentityState) {
        val previousStatus = mutableState.value.status
        val uid = observedUid
        val port = client.departure
        val canAttemptSignOut = uid != null && port != null &&
            next.status !in setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired)
        val restrictedCleanupReady = if (canAttemptSignOut && restrictedSession) {
            runCatching {
                val marker = port.readMarker()
                marker.readable && marker.marker == null && port.hasProvenEmptyCriticalWork(uid)
            }.getOrDefault(false)
        } else true
        mutableState.value = next.copy(canSignOut = canAttemptSignOut && restrictedCleanupReady)
        if (next.status != previousStatus) effectChannel.trySend(IdentityEffect.ReplaceRoot(next.status))
    }

    private fun fail(failure: StableFirebaseFailure) = finish(failureStatus(failure))

    private fun failureStatus(failure: StableFirebaseFailure): GateStatus = when (failure.code) {
        StableFirebaseErrorCode.UNAVAILABLE -> GateStatus.ConnectToVerify
        StableFirebaseErrorCode.DEADLINE_EXCEEDED -> GateStatus.Retry
        StableFirebaseErrorCode.UNAUTHENTICATED -> GateStatus.SessionExpired
        else -> GateStatus.AccessLost
    }

    private fun cancelOperations() {
        revision++
        operations.forEach(FirebaseCancellation::cancel)
        operations.clear()
    }

    private fun cancelSessionAndOperations() {
        cancelOperations()
        sessionListener?.cancel()
        sessionListener = null
    }

    override fun onCleared() {
        cleared = true
        cancelOperations()
        sessionListener?.cancel()
        effectChannel.close()
    }
}
