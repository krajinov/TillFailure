package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.profile.BasicProfile
import com.delminiusapps.tillfailure.profile.ProfileScope
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
import com.delminiusapps.tillfailure.onboarding.*
import com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState

@kotlinx.serialization.Serializable
enum class GateStatus {
    Onboarding, ClientHome,
    Loading, SignedOut, InvalidCredentials, IdentityUnsupported, AnonymousSession, NoAccount, AccountDisabled,
    UnverifiedEmail,
    WorkspaceGate, TrainerHome, MembershipVerifiedFeaturePending, UnsupportedSchema, AccessLost,
    ConnectToVerify, Retry, MultipleMemberships, Unconfigured, SessionExpired, CleanupRequired,
    SwitchingOut, DepartureBlocked, LockedRecovery, ReconciliationRequired,
}

data class WorkspaceOption(val workspaceId: String, val name: String, val role: String)

data class IdentityState(
    val status: GateStatus = GateStatus.Loading,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val canSignOut: Boolean = false,
    val verifiedWorkspaceId: String? = null,
    val verifiedWorkspaceName: String? = null,
    val verifiedRole: String? = null,
    val accountUid: String? = null,
    val authorityEpoch: Long = 0,
    val recovery: ProtectedRecoveryState = ProtectedRecoveryState.Clean,
    val recoveryCause: GateStatus? = null,
    val workspaces: List<WorkspaceOption> = emptyList(),
    val preferredWorkspaceId: String? = null,
    val accountProfileRevision: Int = 0, val onboardingRevision: Int = 0,
    val displayName: String = "", val locale: String = "", val timeZoneId: String = "",
)

sealed interface IdentityEvent {
    data class EmailChanged(val value: String) : IdentityEvent
    data class PasswordChanged(val value: String) : IdentityEvent
    data object SignIn : IdentityEvent
    data object Retry : IdentityEvent
    data object SignOut : IdentityEvent
    data class ChooseWorkspace(val workspaceId: String) : IdentityEvent
    data object ChangeWorkspace : IdentityEvent
    data object Suspend : IdentityEvent
    data object Foreground : IdentityEvent
    data class Connectivity(val connected: Boolean) : IdentityEvent
    data class ProfileRefreshed(val scope: ProfileScope,
        val profile: BasicProfile) : IdentityEvent
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
    private var foreground = true
    private var connectivity: Boolean? = null
    private var networkListener: FirebaseCancellation? = null
    private var observationGeneration = 0L
    private var proofDepartureGeneration = 0L
    private var requestedWorkspace: String? = null
    private var accountFields: Map<String, String> = emptyMap()

    init { recoverOnStartup() }

    /** Current immutable gate state; a small non-flow accessor for native hosts and diagnostics. */
    fun currentState(): IdentityState = mutableState.value
    fun activeClient(): ProductIdentityClient = client

    private fun recoverOnStartup() {
        val port = client.departure
        if (port == null) { observeSession(); return }
        // A departure started by an earlier account graph may still be draining pending SDK writes
        // before its durable switch marker exists. Until it settles this recreated root must not
        // observe or verify the still signed-in session: that older departure can still sign out,
        // fence callbacks or retire this Firebase client. Join it and adopt its terminal outcome
        // instead of re-authorizing (and without starting a competing departure).
        if (port.ownership.observeInFlight(
                onJoined = {
                    // The owner lock is held here. Publish only; never acquire native/storage locks.
                    val previous = mutableState.value.status
                    mutableState.value = mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true,
                        canSignOut = false, verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null, workspaces = emptyList(),
                        displayName = "", locale = "", timeZoneId = "", authorityEpoch = revision)
                    if (previous != GateStatus.SwitchingOut) effectChannel.trySend(IdentityEffect.ReplaceRoot(GateStatus.SwitchingOut))
                },
                onSettled = ::onDepartureAdopted,
            ) != null) {
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
        cancelSessionAndOperations()
        val generation = observationGeneration
        connectivity = null
        val network = client.observeConnectivity {
            if (!cleared && generation == observationGeneration) onEvent(IdentityEvent.Connectivity(it))
        }
        if (generation == observationGeneration) networkListener = network else network.cancel()
        val listener = client.observeSession(0L) { session ->
            if (cleared || generation != observationGeneration) return@observeSession
            val uid = session.uid
            if (uid == null) {
                val mustBlock = observedUid != null || client.hasPinnedIdentity()
                observedUid = null
                restrictedSession = false
                cancelOperations()
                setState(mutableState.value.copy(
                    status = if (mustBlock) GateStatus.CleanupRequired else GateStatus.SignedOut, busy = false,
                    verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null))
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
                    verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null))
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
                        verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null))
                    return@observeSession
                }
                if (!client.claimPinnedIdentity(uid)) {
                    observedUid = null
                    cancelOperations()
                    setState(mutableState.value.copy(status = GateStatus.CleanupRequired, busy = false,
                        verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null))
                    return@observeSession
                }
                observedUid = uid
                restrictedSession = false
                verify(uid)
            }
        }
        if (generation == observationGeneration) sessionListener = listener else listener.cancel()
    }

    fun onEvent(event: IdentityEvent) {
        if (cleared) return
        when (event) {
            is IdentityEvent.ChooseWorkspace -> {
                if (mutableState.value.status != GateStatus.MultipleMemberships ||
                    mutableState.value.workspaces.none { it.workspaceId == event.workspaceId }) return
                observedUid?.let { verify(it, event.workspaceId) }
            }
            IdentityEvent.ChangeWorkspace -> {
                if (mutableState.value.status !in setOf(GateStatus.ClientHome, GateStatus.TrainerHome, GateStatus.Onboarding, GateStatus.AccessLost, GateStatus.LockedRecovery)) return
                observedUid?.let { verify(it) }
            }
            is IdentityEvent.Connectivity -> {
                val previous = connectivity
                connectivity = event.connected
                if (previous == event.connected || !foreground || observedUid == null || restrictedSession || mutableState.value.status in
                    setOf(GateStatus.SwitchingOut, GateStatus.CleanupRequired)) return
                if (!event.connected) {
                    cancelOperations()
                    finish(GateStatus.ConnectToVerify)
                } else if (previous == false) verify(observedUid!!, requestedWorkspace)
            }
            IdentityEvent.Suspend -> {
                foreground = false
                if (observedUid == null || restrictedSession) return
                if (mutableState.value.status in setOf(GateStatus.CleanupRequired, GateStatus.SwitchingOut)) return
                cancelOperations()
                finish(GateStatus.Loading)
            }
            IdentityEvent.Foreground -> {
                foreground = true
                onEvent(IdentityEvent.Retry)
            }
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
            IdentityEvent.Retry -> {
                if (!foreground) return
                if (mutableState.value.status in setOf(GateStatus.CleanupRequired, GateStatus.SwitchingOut)) return
                else if (observedUid != null && !restrictedSession) {
                    verify(observedUid!!, requestedWorkspace)
                } else if (mutableState.value.status == GateStatus.ConnectToVerify) {
                    onEvent(IdentityEvent.SignIn)
                }
            }
            is IdentityEvent.ProfileRefreshed -> {
                val next = refreshedProfilePresentation(mutableState.value, event.scope, event.profile)
                if (next != mutableState.value) setState(next)
            }
            IdentityEvent.SignOut -> {
                val uid = observedUid ?: return
                val port = client.departure ?: return
                if (!mutableState.value.canSignOut || mutableState.value.busy || mutableState.value.status in
                    setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired)) return
                setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true,
                    verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null))
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
                    // Marker absence does not prove Auth/pin/storage are clean. Wait for the
                    // replacement's observed session before exposing another sign-in.
                    setState(IdentityState(status = GateStatus.Loading))
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

    private fun verify(uid: String, selectedWorkspace: String? = null) {
        if (!foreground) return
        requestedWorkspace = selectedWorkspace
        cancelOperations()
        val request = revision
        proofDepartureGeneration = client.departure?.ownership?.admissionGeneration() ?: 0L
        setState(mutableState.value.copy(status = GateStatus.Loading, busy = true,
            password = "", verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null, workspaces = emptyList(),
            displayName = "", locale = "", timeZoneId = ""))
        if (!isPathSafeAuthUid(uid)) {
            setState(mutableState.value.copy(status = GateStatus.IdentityUnsupported, busy = false))
            return
        }
        val recovery = recoveryState(uid)
        if (recovery in setOf(ProtectedRecoveryState.StorageLocked, ProtectedRecoveryState.ReconciliationRequired)) {
            finish(GateStatus.LockedRecovery)
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
            if (session == null || session.uid != uid || session.isAnonymous || session.email == null) { finish(GateStatus.AccessLost); return@refreshSession }
            // A non-anonymous email/password session whose freshly refreshed Auth user is not
            // verified must stop before any account/workspace/membership authorization. A cached
            // observed flag or a client-entered email is never authority.
            if (requiresUnverifiedEmailGate(session)) {
                setState(mutableState.value.copy(status = GateStatus.UnverifiedEmail, busy = false))
                return@refreshSession
            }
            if (!session.emailVerified) { finish(GateStatus.UnverifiedEmail); return@refreshSession }
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
                null -> { accountFields = result.document!!.fields; discover(uid, request) }
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
            if (documents.size > 20 || documents.any { !it.exists || it.origin != FirebaseDataOrigin.SERVER || it.hasPendingWrites }) {
                finish(GateStatus.AccessLost); return@discoverMemberships
            }
            if (documents.isEmpty()) { finish(if (requestedWorkspace == null) GateStatus.WorkspaceGate else GateStatus.AccessLost); return@discoverMemberships }
            val ids = documents.map { discovered ->
                val pieces = discovered.path.split('/')
                if (pieces.size != 4 || pieces[0] != "users" || pieces[1] != uid || pieces[2] != "membershipRefs" ||
                    !isPathSafeAuthUid(pieces[3]) || discovered.fields["schemaVersion"] != "1" || discovered.fields["status"] != "active") {
                    finish(GateStatus.AccessLost); return@discoverMemberships
                }
                pieces[3]
            }
            if (ids.distinct().size != ids.size) { finish(GateStatus.AccessLost); return@discoverMemberships }
            val options = mutableListOf<WorkspaceOption>()
            val proofs = mutableMapOf<String, Pair<com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot, com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot>>()
            fun readNext(index: Int) {
                if (request != revision) return
                if (index == ids.size) {
                    // Losing a selected workspace must close that scope, never silently select another.
                    if (requestedWorkspace != null && options.none { it.workspaceId == requestedWorkspace }) {
                        finish(GateStatus.AccessLost); return
                    }
                    val chosen = if (options.size == 1) options.single() else options.find { it.workspaceId == requestedWorkspace }
                    if (chosen == null) {
                        val hint = runCatching { client.invitations?.workspacePreference(uid) }.getOrNull()?.takeIf { it in ids }
                        setState(mutableState.value.copy(status = GateStatus.MultipleMemberships, busy = false,
                            workspaces = options.toList(), preferredWorkspaceId = hint))
                        return
                    }
                    if (requestedWorkspace == chosen.workspaceId) runCatching { client.invitations?.saveWorkspacePreference(uid, chosen.workspaceId) }
                    val proof = proofs.getValue(chosen.workspaceId)
                    routeMembership(uid, chosen, request, proof.first, proof.second)
                    return
                }
                val wid = ids[index]
                operations += client.getDocument("workspaces/$wid", 0L) workspace@ { workspaceResult ->
                    if (request != revision) return@workspace
                    if (workspaceResult.failure != null) { fail(workspaceResult.failure); return@workspace }
                    operations += client.getDocument("workspaces/$wid/memberships/$uid", 0L) membership@ { memberResult ->
                        if (request != revision) return@membership
                        if (memberResult.failure != null) { fail(memberResult.failure); return@membership }
                        when (val decision = checkMembership(uid, wid, workspaceResult.document, memberResult.document)) {
                            is IdentityDecision.Verified -> {
                                val name = workspaceResult.document?.fields?.get("name") ?: wid
                                options += WorkspaceOption(wid, name.take(80), decision.role)
                                proofs[wid] = workspaceResult.document!! to memberResult.document!!
                                readNext(index + 1)
                            }
                            IdentityDecision.UnsupportedSchema -> finish(GateStatus.UnsupportedSchema)
                            else -> finish(GateStatus.AccessLost)
                        }
                    }
                }
            }
            readNext(0)
        }
    }

    private fun routeMembership(uid: String, selected: WorkspaceOption, request: Long,
        workspaceProof: com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot,
        membershipProof: com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot) {
        val wid = selected.workspaceId
        requestedWorkspace = wid
        if (selected.role == "trainer") {
            setState(mutableState.value.copy(status = GateStatus.TrainerHome, busy = false,
                verifiedWorkspaceId = wid, verifiedWorkspaceName = selected.name, verifiedRole = "trainer", workspaces = emptyList()))
            observeRevocation(uid, wid, request, workspaceProof, membershipProof)
            return
        }
        operations += client.getDocument("workspaces/$wid/clientProfiles/$uid", 0L) { result ->
            if (request != revision) return@getDocument
            if (result.failure != null) { fail(result.failure); return@getDocument }
            val profileRevision = onboardingRevision(uid, wid, result.document)
            val accountRevision = accountProfileRevision(accountFields)
            if (profileRevision == null || accountRevision == null) { finish(GateStatus.AccessLost); return@getDocument }
            val complete = result.document!!.fields["onboardingStatus"] == "complete"
            if (complete && accountRevision == 0) { finish(GateStatus.AccessLost); return@getDocument }
            setState(mutableState.value.copy(status = if (complete) GateStatus.ClientHome else GateStatus.Onboarding,
                busy = false, verifiedWorkspaceId = wid, verifiedWorkspaceName = selected.name, verifiedRole = "client", workspaces = emptyList(),
                accountProfileRevision = accountRevision, onboardingRevision = profileRevision,
                displayName = accountFields["displayName"].orEmpty(), locale = accountFields["locale"].orEmpty(),
                timeZoneId = accountFields["timeZoneId"].orEmpty()))
            observeRevocation(uid, wid, request, workspaceProof, membershipProof)
        }
    }

    private fun observeRevocation(uid: String, wid: String, request: Long,
        workspaceProof: com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot,
        membershipProof: com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot) {
        if (request != revision) return
        var latestWorkspace = workspaceProof
        var latestMembership = membershipProof
        val serverObserved = mutableSetOf<String>()
        val profilePath = "workspaces/$wid/clientProfiles/$uid"
        val paths = listOf("users/$uid", "workspaces/$wid", "workspaces/$wid/memberships/$uid") +
            if (mutableState.value.verifiedRole == "client") listOf(profilePath) else emptyList()
        for (path in paths) {
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
                if (path == "workspaces/$wid") latestWorkspace = document
                if (path.endsWith("/memberships/$uid")) latestMembership = document
                if (checkMembership(uid, wid, latestWorkspace, latestMembership) !is IdentityDecision.Verified) {
                    cancelOperations(); finish(GateStatus.AccessLost); return@listenDocument
                }
                if (path == "users/$uid" && checkAccount(uid, document) != null) {
                    cancelOperations(); finish(GateStatus.AccessLost); return@listenDocument
                }
                if (path == profilePath) {
                    val r = onboardingRevision(uid, wid, document)
                    if (r == null) { cancelOperations(); finish(GateStatus.AccessLost); return@listenDocument }
                    if (document.fields["onboardingStatus"] == "complete" && mutableState.value.status == GateStatus.Onboarding ||
                        document.fields["onboardingStatus"] == "pending" && mutableState.value.status == GateStatus.ClientHome) {
                        verify(uid, wid); return@listenDocument
                    }
                }
                if (path == "users/$uid" && mutableState.value.verifiedRole == "client" && accountProfileRevision(document.fields) == null) {
                    cancelOperations(); finish(GateStatus.AccessLost); return@listenDocument
                }
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
                    observedUid = null
                    restrictedSession = false
                    setState(mutableState.value.copy(status = GateStatus.Loading, busy = false))
                    observeSession()
                }
            }
            DepartureOutcome.CleanupRequired -> finish(GateStatus.CleanupRequired)
        }
    }

    private fun finish(status: GateStatus) {
        setState(mutableState.value.copy(status = status, busy = false,
            verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null, workspaces = emptyList(),
            displayName = "", locale = "", timeZoneId = ""))
    }

    private fun setState(next: IdentityState) {
        val previousStatus = mutableState.value.status
        val uid = observedUid
        val recovery = uid?.let(::recoveryState) ?: ProtectedRecoveryState.Clean
        val lockable = next.status !in setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired,
            GateStatus.SignedOut, GateStatus.InvalidCredentials, GateStatus.IdentityUnsupported, GateStatus.AnonymousSession)
        val protectedStatus = next.status in setOf(GateStatus.Onboarding, GateStatus.ClientHome, GateStatus.TrainerHome, GateStatus.MultipleMemberships)
        val locked = lockable && (recovery == ProtectedRecoveryState.StorageLocked || recovery == ProtectedRecoveryState.ReconciliationRequired ||
            recovery == ProtectedRecoveryState.PendingCommand && !protectedStatus)
        val effective = if (locked) next.copy(status = GateStatus.LockedRecovery,
            recoveryCause = next.status.takeUnless { it == GateStatus.LockedRecovery },
            verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null, workspaces = emptyList(), displayName = "", locale = "", timeZoneId = "")
            else next.copy(recoveryCause = null)
        val port = client.departure
        val canAttemptSignOut = uid != null && port != null &&
            effective.status !in setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired) &&
            recovery !in setOf(ProtectedRecoveryState.StorageLocked, ProtectedRecoveryState.ReconciliationRequired)
        val restrictedCleanupReady = if (canAttemptSignOut && restrictedSession) {
            runCatching {
                val marker = port.readMarker()
                marker.readable && marker.marker == null && port.hasProvenEmptyCriticalWork(uid)
            }.getOrDefault(false)
        } else true
        val publish = {
            mutableState.value = effective.copy(recovery = recovery, authorityEpoch = revision, canSignOut = canAttemptSignOut && restrictedCleanupReady, accountUid = uid)
            if (effective.status != previousStatus) effectChannel.trySend(IdentityEffect.ReplaceRoot(effective.status))
            Unit
        }
        if (!protectedStatus || port == null) publish()
        else if (!port.ownership.publishWhenIdle(proofDepartureGeneration, publish)) {
            // Another root can lease departure after this root's startup inspection. Join the
            // existing owner instead of publishing a proof obtained before its freeze/teardown.
            cancelSessionAndOperations()
            observedUid = null
            restrictedSession = false
            setState(mutableState.value.copy(status = GateStatus.SwitchingOut, busy = true,
                verifiedWorkspaceId = null, verifiedWorkspaceName = null, verifiedRole = null))
            if (port.isRetired()) client = port.replacement()
            recoverOnStartup()
        }
    }

    private fun recoveryState(uid: String): ProtectedRecoveryState = try {
        client.invitations?.recoveryState(uid) ?: ProtectedRecoveryState.Clean
    } catch (_: Exception) { ProtectedRecoveryState.StorageLocked }

    private fun fail(failure: StableFirebaseFailure) = finish(failureStatus(failure))

    private fun failureStatus(failure: StableFirebaseFailure): GateStatus = when (failure.code) {
        StableFirebaseErrorCode.UNAVAILABLE -> GateStatus.ConnectToVerify
        StableFirebaseErrorCode.DEADLINE_EXCEEDED -> GateStatus.Retry
        StableFirebaseErrorCode.UNAUTHENTICATED -> GateStatus.SessionExpired
        StableFirebaseErrorCode.PERMISSION_DENIED -> GateStatus.AccessLost
        else -> if (failure.retryable) GateStatus.Retry else GateStatus.ReconciliationRequired
    }

    private fun cancelOperations() {
        revision++
        operations.forEach(FirebaseCancellation::cancel)
        operations.clear()
    }

    private fun cancelSessionAndOperations() {
        observationGeneration++
        cancelOperations()
        sessionListener?.cancel()
        sessionListener = null
        networkListener?.cancel()
        networkListener = null
    }

    override fun onCleared() {
        cleared = true
        cancelSessionAndOperations()
        effectChannel.close()
    }
}

/** Own presentation only; an authorized read updates no role, workspace, authority or server data. */
internal fun refreshedProfilePresentation(state: IdentityState, scope: ProfileScope,
    profile: BasicProfile): IdentityState {
    if (state.status != GateStatus.ClientHome || state.verifiedRole != "client" || state.busy ||
        state.accountUid != scope.uid || state.verifiedWorkspaceId != scope.workspace || state.authorityEpoch != scope.epoch ||
        !profile.valid() || profile.revision < state.accountProfileRevision) return state
    return state.copy(displayName = profile.displayName, locale = profile.locale, timeZoneId = profile.timeZoneId, accountProfileRevision = profile.revision)
}
