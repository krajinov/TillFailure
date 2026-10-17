package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.FirebaseAuthSession
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.FirebaseDataOrigin
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentResult
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot
import com.delminiusapps.tillfailure.firebase.FirebaseSessionRefresh
import com.delminiusapps.tillfailure.firebase.StableFirebaseErrorCode
import com.delminiusapps.tillfailure.firebase.StableFirebaseFailure
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityViewModelTest {
    @Test fun pendingTrainerRequiresFreshServerRecheckBeforeMinimalHomeAndRevocationClosesIt() {
        val client = FakeIdentityClient()
        client.discoveryDocuments = emptyList()
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals(GateStatus.WorkspaceGate, model.state.value.status)
        assertNull(model.state.value.verifiedRole)
        client.documents["workspaces/w/memberships/alice"] = client.documents.getValue("workspaces/w/memberships/alice")
            .copy(fields = client.documents.getValue("workspaces/w/memberships/alice").fields + ("role" to "trainer"))
        client.discoveryDocuments = listOf(client.documents.getValue("users/alice/membershipRefs/w")
            .copy(origin = FirebaseDataOrigin.CACHE))
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.AccessLost, model.state.value.status)
        client.discoveryDocuments = listOf(client.documents.getValue("users/alice/membershipRefs/w"))
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.TrainerHome, model.state.value.status)
        assertEquals("trainer", model.state.value.verifiedRole)
        client.listeners["workspaces/w/memberships/alice"]?.invoke(FirebaseDocumentResult(
            document = client.documents.getValue("workspaces/w/memberships/alice").copy(
                fields = client.documents.getValue("workspaces/w/memberships/alice").fields + ("status" to "revoked"))))
        assertEquals(GateStatus.AccessLost, model.state.value.status)
        assertNull(model.state.value.verifiedRole)
    }

    @Test fun idleStartupDoesNotPublishASwitchingOutState() {
        val model = IdentityViewModel(FakeIdentityClient().apply {
            departurePort = FakeDeparturePort(this)
        })
        assertEquals(GateStatus.Loading, model.state.value.status)
        assertFalse(model.state.value.busy)
    }

    @Test fun restoreRequiresServerAccountAndExactMembershipThenRevocationRemovesAccess() {
        val client = FakeIdentityClient()
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals(GateStatus.Onboarding, model.state.value.status)
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
        assertEquals(GateStatus.Onboarding, model.state.value.status)
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

    @Test fun unverifiedEmailWithValidAccountAndMembershipStopsBeforeAuthorization() {
        val client = FakeIdentityClient()
        // The freshly refreshed Auth user is unverified, while the *observed* session and the entered
        // form value both claim otherwise; the observed/entered values must not be authority.
        client.freshSession = FirebaseAuthSession(
            uid = "alice", isAnonymous = false, email = "alice@example.invalid", emailVerified = false)
        val model = IdentityViewModel(client)
        model.onEvent(IdentityEvent.EmailChanged("spoofed@example.invalid"))
        client.emitSession("alice")
        assertEquals(GateStatus.UnverifiedEmail, model.state.value.status)
        assertEquals(null, model.state.value.verifiedRole)
        assertEquals(emptyList(), client.getPaths)
        assertEquals(0, client.discoveryCount)

        // After the account email becomes verified, the safe retry reaches authorization.
        client.freshSession = client.freshSession.copy(emailVerified = true)
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.Onboarding, model.state.value.status)
        assertEquals("client", model.state.value.verifiedRole)
    }

    @Test fun recreatedRootJoinsStalledPreMarkerDepartureInsteadOfVerifyingThenRecovers() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client).apply { drainStalled = true }
        client.departurePort = port

        val (firstStore, first) = scoped(client)
        client.emitSession("alice")
        assertEquals(GateStatus.Onboarding, first.state.value.status)

        first.onEvent(IdentityEvent.SignOut)
        assertEquals(GateStatus.SwitchingOut, first.state.value.status)
        assertEquals(0, port.signOutCount)
        assertNull(port.marker)
        assertEquals(DepartureProgress.PreMarker, port.ownership.progress())

        // The Activity/root is recreated mid-drain: the old graph is cleared and a new root is built
        // on the same process-level client, therefore the same in-flight departure owner.
        firstStore.clear()
        val readsBefore = client.getPaths.size
        val discoveriesBefore = client.discoveryCount
        val (_, second) = scoped(client)

        // Pre-fix the recreated root re-observed the still-signed-in session and reached
        // MembershipVerifiedFeaturePending; it must instead join the in-flight departure.
        assertEquals(GateStatus.SwitchingOut, second.state.value.status)
        assertNull(second.state.value.verifiedRole)
        assertEquals(readsBefore, client.getPaths.size)
        assertEquals(discoveriesBefore, client.discoveryCount)

        // The stalled drain completes late.
        port.completeDrain(null)
        assertEquals(GateStatus.SignedOut, second.state.value.status)
        assertEquals(1, port.signOutCount)
        assertEquals(1, port.replacementCount)
        assertNull(port.marker)
        assertEquals(DepartureProgress.Idle, port.ownership.progress())
    }

    @Test fun recreatedRootAdoptsCancelledStalledDepartureAndResumesFreshAuthorization() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client).apply { drainStalled = true }
        client.departurePort = port
        val (firstStore, first) = scoped(client)
        client.emitSession("alice")
        first.onEvent(IdentityEvent.SignOut)
        assertEquals(GateStatus.SwitchingOut, first.state.value.status)
        firstStore.clear()

        val (_, second) = scoped(client)
        assertEquals(GateStatus.SwitchingOut, second.state.value.status)

        // Offline, the drain fails: the departure is cancelled before any durable change, Auth is
        // unchanged, and the recreated root safely resumes fresh server authorization.
        port.completeDrain(StableFirebaseFailure(StableFirebaseErrorCode.DEADLINE_EXCEEDED, true))
        assertEquals(GateStatus.Onboarding, second.state.value.status)
        assertEquals("client", second.state.value.verifiedRole)
        assertEquals(0, port.signOutCount)
        assertNull(port.marker)
    }

    @Test fun discoveryRejectsACollectionGroupMembershipPayloadBeforeReadingWorkspace() {
        val client = FakeIdentityClient()
        client.discoveryDocuments = listOf(doc("workspaces/w/memberships/bob", mapOf(
            "schemaVersion" to "1", "workspaceId" to "w", "userId" to "alice",
            "status" to "active", "role" to "trainer", "privatePayload" to "bob-only")))
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals(GateStatus.AccessLost, model.state.value.status)
        assertEquals(null, model.state.value.verifiedRole)
        assertEquals(listOf("users/alice"), client.getPaths)
    }

    @Test fun recreatedRootAdoptsCleanupFailureWithoutLeavingSwitchingOut() {
        val client = FakeIdentityClient()
        val port = FakeDeparturePort(client).apply { drainStalled = true; failLocalCleanup = true }
        client.departurePort = port
        val (firstStore, first) = scoped(client)
        client.emitSession("alice")
        first.onEvent(IdentityEvent.SignOut)
        firstStore.clear()
        val (_, second) = scoped(client)
        assertEquals(GateStatus.SwitchingOut, second.state.value.status)
        port.completeDrain(null)
        assertEquals(GateStatus.CleanupRequired, second.state.value.status)
        assertFalse(second.state.value.busy)
        assertFalse(second.state.value.canSignOut)
        assertEquals("alice", port.marker?.departingUid)
    }

    @Test fun clientRootRequiresCompleteServerProfileAndSurvivesRestorationWithoutReset() {
        val client = FakeIdentityClient(); val model = IdentityViewModel(client); client.emitSession("alice")
        assertEquals(GateStatus.Onboarding, model.state.value.status)
        val path = "workspaces/w/clientProfiles/alice"
        client.documents[path] = client.documents.getValue(path).copy(fields = client.documents.getValue(path).fields + mapOf(
            "onboardingStatus" to "complete", "onboardingRevision" to "1", "onboardingPolicyVersion" to "1", "completedAt" to "timestamp"))
        client.documents["users/alice"] = client.documents.getValue("users/alice").copy(fields = client.documents.getValue("users/alice").fields + mapOf(
            "displayName" to "Alice", "locale" to "en", "timeZoneId" to "UTC", "profileRevision" to "1"))
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.ClientHome, model.state.value.status)
        assertEquals(GateStatus.ClientHome, IdentityViewModel(client).state.value.status)
        assertEquals("complete", client.documents.getValue(path).fields["onboardingStatus"])
        model.onEvent(IdentityEvent.Suspend); assertEquals(GateStatus.Loading, model.state.value.status)
        client.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.UNAVAILABLE, true)
        model.onEvent(IdentityEvent.Foreground); assertEquals(GateStatus.ConnectToVerify, model.state.value.status)
    }

    @Test fun multipleMembershipsRequireExplicitSelectionAndRevalidateForgedOrRevokedChoices() {
        val client = FakeIdentityClient()
        client.documents["users/alice/membershipRefs/other"] = doc("users/alice/membershipRefs/other", mapOf("schemaVersion" to "1", "status" to "active"))
        client.documents["workspaces/other"] = doc("workspaces/other", mapOf("schemaVersion" to "1", "status" to "active", "name" to "Other workspace", "ownerUid" to "trainer", "membershipRevision" to "1", "activeRosterCount" to "1", "catalogContributionCount" to "1"))
        client.documents["workspaces/other/memberships/alice"] = doc("workspaces/other/memberships/alice", mapOf(
            "schemaVersion" to "1", "workspaceId" to "other", "userId" to "alice", "status" to "active", "role" to "trainer", "revision" to "1", "catalogContributionActive" to "true"))
        client.discoveryDocuments = listOf(client.documents.getValue("users/alice/membershipRefs/w"), client.documents.getValue("users/alice/membershipRefs/other"))
        val model = IdentityViewModel(client); client.emitSession("alice")
        assertEquals(GateStatus.MultipleMemberships, model.state.value.status); assertEquals(2, model.state.value.workspaces.size)
        model.onEvent(IdentityEvent.ChooseWorkspace("forged")); assertEquals(GateStatus.MultipleMemberships, model.state.value.status)
        model.onEvent(IdentityEvent.ChooseWorkspace("other")); assertEquals(GateStatus.TrainerHome, model.state.value.status)
        model.onEvent(IdentityEvent.ChangeWorkspace); assertEquals(GateStatus.MultipleMemberships, model.state.value.status)
        client.documents["workspaces/other/memberships/alice"] = client.documents.getValue("workspaces/other/memberships/alice").copy(
            fields = client.documents.getValue("workspaces/other/memberships/alice").fields + ("status" to "revoked"))
        model.onEvent(IdentityEvent.ChooseWorkspace("other")); assertEquals(GateStatus.AccessLost, model.state.value.status)
        assertNull(model.state.value.verifiedWorkspaceId)
    }

    @Test fun signedOutAndRestrictedRootsRemainUsableAcrossBackgroundForeground() {
        val signedOut = FakeIdentityClient(); val auth = IdentityViewModel(signedOut); signedOut.emitSession(null)
        auth.onEvent(IdentityEvent.Suspend); auth.onEvent(IdentityEvent.Foreground)
        assertEquals(GateStatus.SignedOut, auth.state.value.status)
        val anonymous = FakeIdentityClient(); val restricted = IdentityViewModel(anonymous); anonymous.emitSession("guest", anonymous = true)
        restricted.onEvent(IdentityEvent.Suspend); restricted.onEvent(IdentityEvent.Foreground)
        assertEquals(GateStatus.AnonymousSession, restricted.state.value.status)
        assertEquals(0, anonymous.discoveryCount)
    }

    @Test fun staleWorkspaceHintDoesNotChooseAMultipleMembershipRoot() {
        val client = FakeIdentityClient()
        client.hint = "removed-workspace"
        client.documents["users/alice/membershipRefs/other"] = doc("users/alice/membershipRefs/other", mapOf("schemaVersion" to "1", "status" to "active"))
        client.documents["workspaces/other"] = client.documents.getValue("workspaces/w").copy(path = "workspaces/other")
        client.documents["workspaces/other/memberships/alice"] = client.documents.getValue("workspaces/w/memberships/alice").copy(
            path = "workspaces/other/memberships/alice", fields = client.documents.getValue("workspaces/w/memberships/alice").fields + ("workspaceId" to "other"))
        client.discoveryDocuments = listOf(client.documents.getValue("users/alice/membershipRefs/w"), client.documents.getValue("users/alice/membershipRefs/other"))
        val model = IdentityViewModel(client); client.emitSession("alice")
        assertEquals(GateStatus.MultipleMemberships, model.state.value.status); assertNull(model.state.value.preferredWorkspaceId)
        client.hint = "other"; model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.MultipleMemberships, model.state.value.status); assertEquals("other", model.state.value.preferredWorkspaceId)
        assertNull(model.state.value.verifiedRole)
    }

    @Test fun malformedMissingOrForeignOnboardingNeverEntersClientShell() {
        val client = FakeIdentityClient(); val path = "workspaces/w/clientProfiles/alice"; val profile = client.documents.getValue(path)
        val model = IdentityViewModel(client); client.emitSession("alice")
        for (patch in listOf(mapOf("userId" to "b"), mapOf("workspaceId" to "foreign"), mapOf("onboardingRevision" to "false"),
            mapOf("onboardingStatus" to "complete"), mapOf("injury" to "unapproved"))) {
            client.documents[path] = profile.copy(fields = profile.fields + patch)
            model.onEvent(IdentityEvent.Retry); assertEquals(GateStatus.AccessLost, model.state.value.status)
        }
        client.documents[path] = profile.copy(exists = false)
        model.onEvent(IdentityEvent.Retry); assertEquals(GateStatus.AccessLost, model.state.value.status)
    }

    @Test fun cleanupRetryWithoutAMarkerWaitsForAuthInsteadOfExposingAnotherSignIn() {
        val client = FakeIdentityClient().apply { pinAllowed = false }
        client.departurePort = FakeDeparturePort(client)
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals(GateStatus.CleanupRequired, model.currentState().status)
        client.deferSessionRegistration = true
        model.onEvent(IdentityEvent.RetryCleanup)
        assertEquals(GateStatus.Loading, model.currentState().status)
        model.onEvent(IdentityEvent.SignIn)
        assertEquals(GateStatus.Loading, model.currentState().status)
        assertFalse(model.currentState().canSignOut)
    }

    @Test fun damagedProtectedRegistryLocksBeforeServerReadsAndCanRetryWithoutDeletion() {
        val client = FakeIdentityClient()
        client.recovery = com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.StorageLocked
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        assertEquals(GateStatus.LockedRecovery, model.currentState().status)
        assertTrue(client.getPaths.isEmpty())
        assertEquals(0, client.refreshCount)
        assertFalse(model.currentState().canSignOut)
        client.recovery = com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.Clean
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.Onboarding, model.currentState().status)
    }

    @Test fun authorityLossWithPendingCommandLocksAndLateCallbackCannotReopenShell() {
        for (path in listOf("users/alice", "workspaces/w", "workspaces/w/memberships/alice")) {
            val client = FakeIdentityClient()
            val model = IdentityViewModel(client)
            client.emitSession("alice")
            val late = client.listeners.getValue("workspaces/w/memberships/alice")
            client.recovery = com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.PendingCommand
            client.listeners.getValue(path)(FirebaseDocumentResult(failure = StableFirebaseFailure(StableFirebaseErrorCode.PERMISSION_DENIED, false)))
            assertEquals(GateStatus.LockedRecovery, model.currentState().status, path)
            assertEquals(GateStatus.AccessLost, model.currentState().recoveryCause)
            assertNull(model.currentState().verifiedRole)
            late(FirebaseDocumentResult(document = client.documents.getValue("workspaces/w/memberships/alice")))
            assertEquals(GateStatus.LockedRecovery, model.currentState().status)
            // Settling a receipt does not grant access; a fresh denied server check still wins.
            client.recovery = com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.Clean
            client.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.PERMISSION_DENIED, false)
            model.onEvent(IdentityEvent.Retry)
            assertEquals(GateStatus.AccessLost, model.currentState().status)
        }
    }

    @Test fun connectivityAndForegroundAlwaysRevalidateAndNeverUseAnOfflineGrant() {
        val client = FakeIdentityClient()
        val model = IdentityViewModel(client)
        client.emitSession("alice")
        val initial = client.refreshCount
        model.onEvent(IdentityEvent.Connectivity(false))
        assertEquals(GateStatus.ConnectToVerify, model.currentState().status)
        assertNull(model.currentState().verifiedRole)
        model.onEvent(IdentityEvent.Connectivity(true))
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        assertEquals(initial + 1, client.refreshCount)
        model.onEvent(IdentityEvent.Suspend)
        model.onEvent(IdentityEvent.Connectivity(false))
        model.onEvent(IdentityEvent.Connectivity(true))
        assertEquals(GateStatus.Loading, model.currentState().status)
        assertEquals(initial + 1, client.refreshCount)
        client.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.UNAVAILABLE, true)
        model.onEvent(IdentityEvent.Foreground)
        assertEquals(GateStatus.ConnectToVerify, model.currentState().status)
        client.recovery = com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.PendingCommand
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.LockedRecovery, model.currentState().status)
        assertEquals(GateStatus.ConnectToVerify, model.currentState().recoveryCause)
    }

    @Test fun invalidSelectedWorkspaceDoesNotAutomaticallyOpenTheRemainingWorkspace() {
        val client = FakeIdentityClient(); val model = IdentityViewModel(client)
        client.emitSession("alice")
        for ((path, document) in client.documents.toMap()) {
            if (path.contains("workspaces/w") || path.endsWith("membershipRefs/w"))
                client.documents[path.replace("workspaces/w", "workspaces/other").replace("membershipRefs/w", "membershipRefs/other")] =
                    document.copy(path = document.path.replace("workspaces/w", "workspaces/other").replace("membershipRefs/w", "membershipRefs/other"),
                        fields = document.fields.mapValues { if (it.key == "workspaceId") "other" else it.value })
        }
        client.discoveryDocuments = listOf(client.documents.getValue("users/alice/membershipRefs/other"))
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.AccessLost, model.currentState().status)
        assertNull(model.currentState().verifiedWorkspaceId)
        model.onEvent(IdentityEvent.ChangeWorkspace)
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        assertEquals("other", model.currentState().verifiedWorkspaceId)
    }

    @Test fun serviceAndReconciliationFailuresDoNotMasqueradeAsConfirmedRevocation() {
        val client = FakeIdentityClient(); val model = IdentityViewModel(client)
        client.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.UNKNOWN, true)
        client.emitSession("alice")
        assertEquals(GateStatus.Retry, model.currentState().status)
        client.refreshFailure = StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false)
        model.onEvent(IdentityEvent.Retry)
        assertEquals(GateStatus.ReconciliationRequired, model.currentState().status)
    }

    @Test fun retryAndLateAuthCannotReopenABackgroundRoot() {
        val client = FakeIdentityClient(); val model = IdentityViewModel(client)
        model.onEvent(IdentityEvent.Suspend)
        client.emitSession("alice")
        model.onEvent(IdentityEvent.Retry) // Feature completion is not a host foreground event.
        assertEquals(GateStatus.Loading, model.currentState().status)
        assertNull(model.currentState().verifiedRole)
        assertEquals(0, client.refreshCount)
        model.onEvent(IdentityEvent.Foreground)
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        assertEquals(1, client.refreshCount)
    }

    @Test fun duplicateConnectivityAndDisposedObserversCannotCancelANewProof() {
        val client = FakeIdentityClient().apply { pinAllowed = false }
        client.departurePort = FakeDeparturePort(client)
        val (store, model) = scoped(client)
        client.emitSession("alice")
        val oldAuth = client.sessionCallbacks.single()
        val oldNetwork = client.networkCallbacks.single()
        client.pinAllowed = true
        model.onEvent(IdentityEvent.RetryCleanup)
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        assertEquals(1, client.sessionCancellations)
        assertEquals(1, client.networkCancellations)
        oldAuth(FirebaseAuthSession(null, false)); oldNetwork(false)
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        val network = client.networkCallbacks.last()
        network(false)
        val epoch = model.currentState().authorityEpoch
        network(false)
        assertEquals(epoch, model.currentState().authorityEpoch)
        network(true)
        val checks = client.refreshCount
        network(true)
        assertEquals(checks, client.refreshCount)
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        store.clear()
        val disposedState = model.currentState()
        client.sessionCallbacks.last()(FirebaseAuthSession(null, false)); network(false)
        assertEquals(disposedState, model.currentState())
        assertEquals(2, client.sessionCancellations)
        assertEquals(2, client.networkCancellations)
    }

    @Test fun departureLeasedDuringFinalRegistryInspectionPreventsRoleAdmission() {
        val client = FakeIdentityClient().apply { deferAccount = true }
        val port = FakeDeparturePort(client); client.departurePort = port
        val model = IdentityViewModel(client); client.emitSession("alice")
        val account = client.deferredAccount!!
        client.beforeRecoveryRead = {
            client.beforeRecoveryRead = null
            assertTrue(port.ownership.lease("alice") {})
        }
        account(FirebaseDocumentResult(document = client.documents.getValue("users/alice")))
        assertEquals(GateStatus.SwitchingOut, model.currentState().status)
        assertNull(model.currentState().verifiedRole)
        assertTrue(client.listeners.isEmpty())
        account(FirebaseDocumentResult(document = client.documents.getValue("users/alice")))
        assertEquals(GateStatus.SwitchingOut, model.currentState().status)
        client.deferAccount = false
        port.ownership.settle("alice", DepartureOutcome.PreflightBlocked)
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        assertEquals(2, client.refreshCount)
    }

    @Test fun departureSettledDuringFinalRegistryInspectionRequiresANewProof() {
        val client = FakeIdentityClient().apply { deferAccount = true }
        val port = FakeDeparturePort(client); client.departurePort = port
        val model = IdentityViewModel(client); client.emitSession("alice")
        val account = client.deferredAccount!!
        client.beforeRecoveryRead = {
            client.beforeRecoveryRead = null
            client.deferAccount = false
            assertTrue(port.ownership.lease("alice") {})
            port.ownership.settle("alice", DepartureOutcome.PreflightBlocked)
        }
        account(FirebaseDocumentResult(document = client.documents.getValue("users/alice")))
        assertEquals(GateStatus.Onboarding, model.currentState().status)
        assertEquals(2, client.refreshCount)
        assertEquals(4, client.listeners.size)
    }

    private class FakeIdentityClient : ProductIdentityClient {
        var pinAllowed = true
        var deferSessionRegistration = false
        var recovery = com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.Clean
        var hint: String? = null
        var beforeRecoveryRead: (() -> Unit)? = null
        override val invitations = object : com.delminiusapps.tillfailure.invitations.InvitationClient {
            override fun recoveryState(uid: String): com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState {
                beforeRecoveryRead?.invoke()
                return recovery
            }
            override val memory = com.delminiusapps.tillfailure.invitations.InvitationMemory()
            override fun workspacePreference(uid: String) = hint
            override fun saveWorkspacePreference(uid: String, workspaceId: String) { hint = workspaceId }
            override fun pending(uid: String): com.delminiusapps.tillfailure.invitations.PendingInvitationCommand? = null
            override fun newCommandKey() = "test"
            override fun execute(uid: String, request: com.delminiusapps.tillfailure.invitations.InvitationRequest,
                callback: (com.delminiusapps.tillfailure.invitations.InvitationOutcome) -> Unit) = FirebaseCancellation {}
        }

        var departurePort: CleanDeparturePort? = null
        override val departure: CleanDeparturePort? get() = departurePort
        val documents = mutableMapOf(
            "workspaces/w/clientProfiles/alice" to doc("workspaces/w/clientProfiles/alice", mapOf(
                "schemaVersion" to "1", "workspaceId" to "w", "userId" to "alice", "trainerId" to "trainer",
                "createdAt" to "timestamp", "onboardingStatus" to "pending")),
            "users/alice" to doc("users/alice", mapOf("schemaVersion" to "1", "accountStatus" to "active", "displayName" to "")),
            "users/alice/membershipRefs/w" to doc("users/alice/membershipRefs/w", mapOf(
                "schemaVersion" to "1", "status" to "active")),
            "workspaces/w" to doc("workspaces/w", mapOf("schemaVersion" to "1", "status" to "active", "ownerUid" to "trainer", "membershipRevision" to "1", "activeRosterCount" to "1", "catalogContributionCount" to "1")),
            "workspaces/w/memberships/alice" to doc("workspaces/w/memberships/alice", mapOf(
                "schemaVersion" to "1", "workspaceId" to "w", "userId" to "alice", "status" to "active", "role" to "client", "revision" to "1", "catalogContributionActive" to "true")),
        )
        val listeners = mutableMapOf<String, (FirebaseDocumentResult) -> Unit>()
        var refreshFailure: StableFirebaseFailure? = null
        var freshSession: FirebaseAuthSession = FirebaseAuthSession(
            uid = "alice", isAnonymous = false, email = "alice@example.invalid", emailVerified = true)
        var deferAccount = false
        var deferredAccount: ((FirebaseDocumentResult) -> Unit)? = null
        val getPaths = mutableListOf<String>()
        var discoveryCount = 0
        var discoveryDocuments: List<FirebaseDocumentSnapshot>? = null
        var refreshCount = 0
        private var session: ((FirebaseAuthSession) -> Unit)? = null
        val sessionCallbacks = mutableListOf<(FirebaseAuthSession) -> Unit>()
        val networkCallbacks = mutableListOf<(Boolean) -> Unit>()
        var sessionCancellations = 0
        var networkCancellations = 0
        private var currentSession: FirebaseAuthSession? = null
        private var pinnedUid: String? = null

        override fun hasPinnedIdentity(): Boolean = pinnedUid != null
        override fun claimPinnedIdentity(uid: String): Boolean {
            if (!pinAllowed) return false
            if (pinnedUid != null && pinnedUid != uid) return false
            pinnedUid = uid
            return true
        }

        /** Models Auth sign-out clearing the observed session. */
        fun clearSession() {
            val delivered = FirebaseAuthSession(uid = null, isAnonymous = false)
            currentSession = delivered
            session?.invoke(delivered)
        }

        /** Models the durable marker completion clearing the installation identity pin. */
        fun clearPin() { pinnedUid = null }

        fun emitSession(uid: String?, anonymous: Boolean = false) {
            val delivered = FirebaseAuthSession(
                uid = uid, isAnonymous = anonymous,
                email = if (uid != null && !anonymous) "alice@example.invalid" else null,
                emailVerified = true)
            currentSession = delivered
            session?.invoke(delivered)
        }

        override fun observeSession(epoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation {
            sessionCallbacks += callback
            session = callback
            // The real SDK delivers the current Auth state on registration; a recreated root would
            // otherwise re-authorize the still-signed-in session before a departure settles.
            if (!deferSessionRegistration) currentSession?.let(callback)
            return FirebaseCancellation { sessionCancellations++; if (session === callback) session = null }
        }
        override fun observeConnectivity(callback: (Boolean) -> Unit): FirebaseCancellation {
            networkCallbacks += callback
            return FirebaseCancellation { networkCancellations++ }
        }
        override fun signIn(email: String, password: String, epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            callback(null)
            return FirebaseCancellation {}
        }
        override fun refreshSession(epoch: Long, callback: (FirebaseSessionRefresh) -> Unit): FirebaseCancellation {
            refreshCount++
            val failure = refreshFailure
            if (failure != null) callback(FirebaseSessionRefresh(failure = failure))
            else callback(FirebaseSessionRefresh(session = freshSession))
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
            callback(MembershipDiscoveryResult(documents = discoveryDocuments
                ?: listOf(documents.getValue("users/alice/membershipRefs/w"))))
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
        var replacementCount = 0
        var drainStalled = false
        private var pendingDrain: ((StableFirebaseFailure?) -> Unit)? = null
        override val ownership = CleanDepartureOwnership()
        fun completeDrain(failure: StableFirebaseFailure?) {
            val callback = pendingDrain
            pendingDrain = null
            callback?.invoke(failure)
        }
        override fun isRetired() = false
        override fun readMarker() = DepartureMarkerRead(marker)
        override fun freeze(uid: String) = true
        override fun unfreeze(uid: String) = Unit
        override fun hasProvenEmptyCriticalWork(uid: String) = cleanRegistry
        override fun drain(timeoutMillis: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            if (drainStalled) {
                pendingDrain = callback
                return FirebaseCancellation { pendingDrain = null }
            }
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
            client.clearSession()
            callback(null)
        }
        override fun retireFirestore(callback: (StableFirebaseFailure?) -> Unit) = callback(null)
        override fun cleanupLocal(uid: String) = !failLocalCleanup
        override fun completeMarker(uid: String): Boolean { marker = null; client.clearPin(); return true }
        override fun replacement(): ProductIdentityClient { replacementCount++; return client }
    }

    /** Builds a ViewModel inside a store so a test can clear it, exactly as an Activity/root would. */
    private fun scoped(client: ProductIdentityClient): Pair<ViewModelStore, IdentityViewModel> {
        val store = ViewModelStore()
        val provider = ViewModelProvider.create(store, viewModelFactory {
            initializer { IdentityViewModel(client) }
        })
        return store to provider[IdentityViewModel::class]
    }

    companion object {
        private fun doc(path: String, fields: Map<String, String>, exists: Boolean = true) =
            FirebaseDocumentSnapshot(path, fields, exists, FirebaseDataOrigin.SERVER, false)
    }
}
