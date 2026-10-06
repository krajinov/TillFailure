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
        private var session: ((FirebaseAuthSession) -> Unit)? = null
        private var pinnedUid: String? = null

        override fun hasPinnedIdentity(): Boolean = pinnedUid != null
        override fun claimPinnedIdentity(uid: String): Boolean {
            if (pinnedUid != null && pinnedUid != uid) return false
            pinnedUid = uid
            return true
        }

        fun emitSession(uid: String?) { session?.invoke(FirebaseAuthSession(uid, false)) }

        override fun observeSession(epoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation {
            session = callback
            return FirebaseCancellation { session = null }
        }
        override fun signIn(email: String, password: String, epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            callback(null)
            return FirebaseCancellation {}
        }
        override fun refreshSession(epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            callback(refreshFailure)
            return FirebaseCancellation {}
        }
        override fun getDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
            if (deferAccount && path == "users/alice") deferredAccount = callback
            else callback(FirebaseDocumentResult(document = documents[path] ?: doc(path, emptyMap(), exists = false)))
            return FirebaseCancellation {}
        }
        override fun discoverMemberships(uid: String, epoch: Long, callback: (MembershipDiscoveryResult) -> Unit): FirebaseCancellation {
            callback(MembershipDiscoveryResult(documents = listOf(documents.getValue("workspaces/w/memberships/alice"))))
            return FirebaseCancellation {}
        }
        override fun listenDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
            listeners[path] = callback
            return FirebaseCancellation { listeners.remove(path) }
        }
    }

    companion object {
        private fun doc(path: String, fields: Map<String, String>, exists: Boolean = true) =
            FirebaseDocumentSnapshot(path, fields, exists, FirebaseDataOrigin.SERVER, false)
    }
}
