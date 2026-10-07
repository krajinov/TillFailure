package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.FirebaseAuthSession
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.FirebaseDataOrigin
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentResult
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot
import com.delminiusapps.tillfailure.firebase.StableFirebaseErrorCode
import com.delminiusapps.tillfailure.firebase.StableFirebaseFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityViewModelTest {
    @Test fun restoreRequiresServerAccountAndExactMembershipThenRevocationRemovesAccess() {
        val client = FakeIdentityClient()
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals(GateStatus.MembershipVerifiedFeaturePending, model.state.value.status)
        assertEquals("client", model.state.value.verifiedRole)
        client.listeners["workspaces/w/memberships/alice"]?.invoke(
            FirebaseDocumentResult(document = client.documents.getValue("workspaces/w/memberships/alice")
                .copy(fields = client.documents.getValue("workspaces/w/memberships/alice").fields + ("status" to "revoked"))),
        )
        assertEquals(GateStatus.AccessLost, model.state.value.status)
        assertEquals(null, model.state.value.verifiedRole)
    }

    @Test fun cachedAccountAndUnavailableRefreshNeverUnlockRole() {
        val cached = FakeIdentityClient()
        cached.documents["users/alice"] = cached.documents.getValue("users/alice").copy(origin = FirebaseDataOrigin.CACHE)
        val cachedModel = IdentityViewModel(cached)
        cached.emitSession("alice")
        assertEquals(GateStatus.AccessLost, cachedModel.state.value.status)

        val offline = FakeIdentityClient()
        offline.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.UNAVAILABLE, true)
        val offlineModel = IdentityViewModel(offline)
        offline.emitSession("alice")
        assertEquals(GateStatus.ConnectToVerify, offlineModel.state.value.status)

        val expired = FakeIdentityClient()
        expired.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.UNAUTHENTICATED, false)
        val expiredModel = IdentityViewModel(expired)
        expired.emitSession("alice")
        assertEquals(GateStatus.SessionExpired, expiredModel.state.value.status)
    }

    @Test fun verifiedListenerReturningToCacheLocksUntilOnlineRetry() {
        val client = FakeIdentityClient()
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        val account = client.documents.getValue("users/alice")
        client.listeners["users/alice"]?.invoke(FirebaseDocumentResult(document = account))
        client.listeners["users/alice"]?.invoke(FirebaseDocumentResult(document = account.copy(origin = FirebaseDataOrigin.CACHE)))
        assertEquals(GateStatus.ConnectToVerify, model.state.value.status)
        assertEquals(null, model.state.value.verifiedRole)
    }

    @Test fun restoredOfflineSessionRetriesServerProofWithoutAnEnteredEmail() {
        val client = FakeIdentityClient()
        client.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.UNAVAILABLE, true)
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals("", model.state.value.email)
        assertEquals(GateStatus.ConnectToVerify, model.state.value.status)
        client.refreshFailure = null
        client.documents["users/alice"] = client.documents.getValue("users/alice")
            .copy(origin = FirebaseDataOrigin.CACHE)
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.AccessLost, model.state.value.status)
        assertEquals(null, model.state.value.verifiedRole)
        client.documents["users/alice"] = client.documents.getValue("users/alice")
            .copy(origin = FirebaseDataOrigin.SERVER)
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.MembershipVerifiedFeaturePending, model.state.value.status)
        assertEquals(3, client.refreshCount)
        assertEquals(listOf("users/alice", "users/alice"), client.getPaths.take(2))
    }

    @Test fun unsupportedFirestoreUidCanCleanlyDepartWithoutAnyAccountPath() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client)
        client.departurePort = port
        val model = IdentityViewModel(client)
        model.onEvent(IdentityEvent.EmailChanged("pr1-unsupported@example.invalid"))
        model.onEvent(IdentityEvent.PasswordChanged("sensitive-input"))
        client.emitSession("unsafe/uid")
        assertEquals(GateStatus.IdentityUnsupported, model.state.value.status)
        assertEquals(true, model.state.value.canSignOut)
        assertEquals("", model.state.value.email)
        assertEquals("", model.state.value.password)
        assertEquals(emptyList(), client.getPaths)
        assertEquals(0, client.discoveryCount)
        model.onEvent(IdentityEvent.SignOut)
        assertEquals(GateStatus.SignedOut, model.state.value.status)
        assertEquals(1, port.signOutCount)
        assertEquals(null, port.marker)
    }

    @Test fun unsupportedUidWithUnprovenLocalWorkKeepsSignOutHidden() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client).apply { cleanRegistry = false }
        client.departurePort = port
        val model = IdentityViewModel(client)
        client.emitSession("unsafe/uid")
        assertEquals(GateStatus.IdentityUnsupported, model.state.value.status)
        assertEquals(false, model.state.value.canSignOut)
        model.onEvent(IdentityEvent.SignOut)
        assertEquals(0, port.signOutCount)
    }

    @Test fun unsupportedUidCleanupFailureKeepsMarkerAndLocksAnotherSignIn() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client).apply { failLocalCleanup = true }
        client.departurePort = port
        val model = IdentityViewModel(client)
        client.emitSession("unsafe/uid")
        model.onEvent(IdentityEvent.SignOut)
        assertEquals(GateStatus.CleanupRequired, model.state.value.status)
        assertEquals("unsafe/uid", port.marker?.departingUid)
        assertEquals(false, model.state.value.canSignOut)
        model.onEvent(IdentityEvent.SignIn)
        assertEquals(GateStatus.CleanupRequired, model.state.value.status)
    }

    @Test fun restoredAnonymousSessionCanDepartWithoutReadingAccountPaths() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client)
        client.departurePort = port
        val model = IdentityViewModel(client)
        client.emitSession("anonymous_uid", anonymous = true)
        assertEquals(GateStatus.AnonymousSession, model.state.value.status)
        assertEquals(true, model.state.value.canSignOut)
        assertEquals(emptyList(), client.getPaths)
        assertEquals(0, client.discoveryCount)
        assertEquals(0, client.refreshCount)
        model.onEvent(IdentityEvent.Retry)
        assertEquals(emptyList(), client.getPaths)
        assertEquals(0, client.refreshCount)
        model.onEvent(IdentityEvent.SignOut)
        assertEquals(GateStatus.SignedOut, model.state.value.status)
        assertEquals(1, port.signOutCount)
        assertEquals(null, port.marker)
    }

    @Test fun anonymousSessionWithoutCleanProofCannotLeaveOrAdmitAnotherAccount() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client).apply { cleanRegistry = false }
        client.departurePort = port
        val model = IdentityViewModel(client)
        client.emitSession("anonymous_uid", anonymous = true)
        assertEquals(GateStatus.AnonymousSession, model.state.value.status)
        assertEquals(false, model.state.value.canSignOut)
        model.onEvent(IdentityEvent.SignOut)
        model.onEvent(IdentityEvent.SignIn)
        assertEquals(GateStatus.AnonymousSession, model.state.value.status)
        assertEquals(0, port.signOutCount)
    }

    @Test fun anonymousCleanupFailureRetainsMarkerUntilRecovery() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client).apply { failLocalCleanup = true }
        client.departurePort = port
        val model = IdentityViewModel(client)
        client.emitSession("anonymous_uid", anonymous = true)
        model.onEvent(IdentityEvent.SignOut)
        assertEquals(GateStatus.CleanupRequired, model.state.value.status)
        assertEquals("anonymous_uid", port.marker?.departingUid)
        assertEquals(false, model.state.value.canSignOut)
    }

    @Test fun lateServerCallbackAfterSignOutCannotRestorePreviousAccount() {
        val client = FakeIdentityClient()
        client.deferAccount = true
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals(GateStatus.Loading, model.state.value.status)
        val oldCallback = client.deferredAccount!!
        client.emitSession(null)
        oldCallback(FirebaseDocumentResult(document = client.documents.getValue("users/alice")))
        assertEquals(GateStatus.CleanupRequired, model.state.value.status)
        assertEquals(null, model.state.value.verifiedRole)
    }

    @Test fun pinnedInstallationBlocksAnotherUidAndSignInAfterSessionLoss() {
        val client = FakeIdentityClient()
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        client.emitSession(null)
        assertEquals(GateStatus.CleanupRequired, model.state.value.status)
        model.onEvent(IdentityEvent.SignIn)
        assertEquals(GateStatus.CleanupRequired, model.state.value.status)
        client.emitSession("bob")
        assertEquals(GateStatus.CleanupRequired, model.state.value.status)
        val restarted = IdentityViewModel(client)
        client.emitSession(null)
        assertEquals(GateStatus.CleanupRequired, restarted.state.value.status)
    }

    private class FakeIdentityClient : ProductIdentityClient {
        var departurePort: CleanDeparturePort? = null
        override val departure: CleanDeparturePort? get() = departurePort
        val documents = mutableMapOf(
            "users/alice" to doc("users/alice", mapOf("schemaVersion" to "1", "accountStatus" to "active")),
            "workspaces/w" to doc("workspaces/w", mapOf("schemaVersion" to "1", "status" to "active")),
            "workspaces/w/memberships/alice" to doc("workspaces/w/memberships/alice", mapOf(
                "schemaVersion" to "1", "workspaceId" to "w", "userId" to "alice", "status" to "active", "role" to "client")),
        )
        val listeners = mutableMapOf<String, (FirebaseDocumentResult) -> Unit>()
        var refreshFailure: StableFirebaseFailure? = null
        var deferAccount = false
        var deferredAccount: ((FirebaseDocumentResult) -> Unit)? = null
        val getPaths = mutableListOf<String>()
        var discoveryCount = 0
        var refreshCount = 0
        private var session: ((FirebaseAuthSession) -> Unit)? = null
        private var pinnedUid: String? = null

        override fun hasPinnedIdentity(): Boolean = pinnedUid != null
        override fun claimPinnedIdentity(uid: String): Boolean {
            if (pinnedUid != null && pinnedUid != uid) return false
            pinnedUid = uid
            return true
        }

        fun emitSession(uid: String?, anonymous: Boolean = false) {
            session?.invoke(FirebaseAuthSession(uid, anonymous))
        }

        override fun observeSession(epoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation {
            session = callback
            return FirebaseCancellation { session = null }
        }
        override fun signIn(email: String, password: String, epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            callback(null)
            return FirebaseCancellation {}
        }
        override fun refreshSession(epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            refreshCount++
            callback(refreshFailure)
            return FirebaseCancellation {}
        }
        override fun getDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
            getPaths += path
            if (deferAccount && path == "users/alice") deferredAccount = callback
            else callback(FirebaseDocumentResult(document = documents[path] ?: doc(path, emptyMap(), exists = false)))
            return FirebaseCancellation {}
        }
        override fun discoverMemberships(uid: String, epoch: Long, callback: (MembershipDiscoveryResult) -> Unit): FirebaseCancellation {
            discoveryCount++
            callback(MembershipDiscoveryResult(documents = listOf(documents.getValue("workspaces/w/memberships/alice"))))
            return FirebaseCancellation {}
        }
        override fun listenDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
            listeners[path] = callback
            return FirebaseCancellation { listeners.remove(path) }
        }
    }

    private class FakeDeparturePort(private val client: FakeIdentityClient) : CleanDeparturePort {
        var marker: com.delminiusapps.tillfailure.persistence.AccountSwitchMarker? = null
        var cleanRegistry = true
        var failLocalCleanup = false
        var signOutCount = 0
        override fun isRetired() = false
        override fun readMarker() = DepartureMarkerRead(marker)
        override fun freeze(uid: String) = true
        override fun unfreeze(uid: String) = Unit
        override fun hasProvenEmptyCriticalWork(uid: String) = cleanRegistry
        override fun drain(timeoutMillis: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            callback(null)
            return FirebaseCancellation {}
        }
        override fun persistMarker(uid: String): Boolean {
            marker = com.delminiusapps.tillfailure.persistence.AccountSwitchMarker(
                departingUid = uid, accountEpoch = 1, state = "SwitchingOut")
            return true
        }
        override fun fenceCallbacks() = Unit
        override fun signOut(callback: (StableFirebaseFailure?) -> Unit) {
            signOutCount++
            callback(null)
        }
        override fun retireFirestore(callback: (StableFirebaseFailure?) -> Unit) = callback(null)
        override fun cleanupLocal(uid: String) = !failLocalCleanup
        override fun completeMarker(uid: String): Boolean { marker = null; return true }
        override fun replacement(): ProductIdentityClient = client
    }

    companion object {
        private fun doc(path: String, fields: Map<String, String>, exists: Boolean = true) =
            FirebaseDocumentSnapshot(path, fields, exists, FirebaseDataOrigin.SERVER, false)
    }
}
