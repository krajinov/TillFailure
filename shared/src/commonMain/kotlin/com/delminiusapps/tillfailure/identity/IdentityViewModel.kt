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

@kotlinx.serialization.Serializable
enum class GateStatus {
    Loading, SignedOut, InvalidCredentials, IdentityUnsupported, NoAccount, AccountDisabled,
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
    private var sessionListener: FirebaseCancellation? = null

    init { recoverOnStartup() }

    private fun recoverOnStartup() {
        val port = client.departure
        if (port == null) { observeSession(); return }
        CleanDepartureCoordinator(port).recover(::cancelSessionAndOperations) { outcome, hadMarker ->
            if (outcome != DepartureOutcome.Clean) {
                finish(GateStatus.CleanupRequired)
            } else {
                if (hadMarker) client = port.replacement()
                observeSession()
            }
        }
    }

    private fun observeSession() {
        sessionListener = client.observeSession(0L) { session ->
            val uid = session.uid
            if (uid == null || session.isAnonymous) {
                val mustBlock = session.isAnonymous || observedUid != null || client.hasPinnedIdentity()
                observedUid = null
                cancelOperations()
                setState(mutableState.value.copy(
                    status = if (mustBlock) GateStatus.CleanupRequired else GateStatus.SignedOut, busy = false,
                    verifiedWorkspaceId = null, verifiedRole = null))
            } else if (uid != observedUid) {
                if (!isPathSafeAuthUid(uid)) {
                    observedUid = null
                    cancelOperations()
                    setState(mutableState.value.copy(status = GateStatus.IdentityUnsupported, busy = false,
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
                    mutableState.value.status !in setOf(GateStatus.SignedOut, GateStatus.InvalidCredentials) ||
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
            IdentityEvent.Retry -> observedUid?.let(::verify)
            IdentityEvent.SignOut -> {
                val uid = observedUid ?: return
                val port = client.departure ?: return
                if (mutableState.value.busy || mutableState.value.status in
                    setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired)) return
                setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true,
                    verifiedWorkspaceId = null, verifiedRole = null))
                CleanDepartureCoordinator(port).depart(uid, ::cancelSessionAndOperations) { outcome ->
                    when (outcome) {
                        DepartureOutcome.Clean -> {
                            observedUid = null
                            client = port.replacement()
                            setState(IdentityState(status = GateStatus.SignedOut))
                            observeSession()
                        }
                        DepartureOutcome.PreflightBlocked -> {
                            setState(mutableState.value.copy(status = GateStatus.DepartureBlocked, busy = false))
                        }
                        DepartureOutcome.CleanupRequired -> finish(GateStatus.CleanupRequired)
                    }
                }
            }
            IdentityEvent.RetryCleanup -> {
                if (mutableState.value.status != GateStatus.CleanupRequired) return
                val oldPort = client.departure ?: return
                if (oldPort.isRetired()) client = oldPort.replacement()
                val port = client.departure ?: return
                setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true))
                CleanDepartureCoordinator(port).recover(::cancelSessionAndOperations) { outcome, hadMarker ->
                    if (outcome == DepartureOutcome.Clean && hadMarker) {
                        observedUid = null
                        client = port.replacement()
                        setState(IdentityState(status = GateStatus.SignedOut))
                        observeSession()
                    } else finish(GateStatus.CleanupRequired)
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
        operations += client.refreshSession(0L) { refreshFailure ->
            if (request != revision) return@refreshSession
            if (refreshFailure != null) {
                setState(mutableState.value.copy(status = failureStatus(refreshFailure), busy = false))
            } else readAccount(uid, request)
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

    private fun finish(status: GateStatus) {
        setState(mutableState.value.copy(status = status, busy = false,
            verifiedWorkspaceId = null, verifiedRole = null))
    }

    private fun setState(next: IdentityState) {
        val previousStatus = mutableState.value.status
        mutableState.value = next.copy(canSignOut = observedUid != null && client.departure != null &&
            next.status !in setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired))
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
        cancelOperations()
        sessionListener?.cancel()
        effectChannel.close()
    }
}
