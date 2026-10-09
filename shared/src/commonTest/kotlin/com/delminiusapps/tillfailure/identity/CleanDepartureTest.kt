package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.StableFirebaseErrorCode
import com.delminiusapps.tillfailure.firebase.StableFirebaseFailure
import com.delminiusapps.tillfailure.persistence.AccountSwitchMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanDepartureTest {
    @Test fun pendingRegistryAndStalledDrainKeepAuthAndWriteNoMarker() {
        val port = FakePort()
        port.cleanRegistry = false
        var outcome: DepartureOutcome? = null
        CleanDepartureCoordinator(port).depart("account_a", {}, { outcome = it })
        assertEquals(DepartureOutcome.PreflightBlocked, outcome)
        assertTrue(port.signedIn)
        assertEquals(null, port.marker)
        assertFalse(port.frozen)

        port.cleanRegistry = true
        port.drainFailure = StableFirebaseFailure(StableFirebaseErrorCode.DEADLINE_EXCEEDED, true)
        CleanDepartureCoordinator(port).depart("account_a", {}, { outcome = it })
        assertEquals(DepartureOutcome.PreflightBlocked, outcome)
        assertTrue(port.signedIn)
        assertEquals(null, port.marker)
        assertFalse(port.frozen)
    }

    @Test fun everyPostMarkerFailureRecoversWithoutAdmittingAnotherUid() {
        for (failure in listOf("signOut", "terminate", "local", "complete")) {
            val port = FakePort()
            port.failAt = failure
            var outcome: DepartureOutcome? = null
            CleanDepartureCoordinator(port).depart("account_a", {}, { outcome = it })
            assertEquals(DepartureOutcome.CleanupRequired, outcome, failure)
            assertEquals("account_a", port.marker?.departingUid, failure)
            assertFalse(port.canEnter("account_b"), failure)
            port.failAt = null
            port.frozen = false // a restarted adapter has a new in-memory freeze gate
            var recovered = false
            CleanDepartureCoordinator(port).recover({}, { result, hadMarker ->
                recovered = hadMarker && result == DepartureOutcome.Clean
            })
            assertTrue(recovered, failure)
            assertEquals(null, port.marker, failure)
            assertTrue(port.canEnter("account_b"), failure)
            assertTrue(port.fenceCount >= 2, failure)
        }
    }

    @Test fun unreadableMarkerFailsClosedBeforeAuthObservation() {
        val port = FakePort()
        port.markerReadable = false
        var result: DepartureOutcome? = null
        CleanDepartureCoordinator(port).recover({}, { outcome, _ -> result = outcome })
        assertEquals(DepartureOutcome.CleanupRequired, result)
        assertTrue(port.signedIn)
    }

    @Test fun ambiguousMarkerWriteNeverReopensTheAccount() {
        val port = FakePort()
        port.failAt = "markerAfterWrite"
        var result: DepartureOutcome? = null
        CleanDepartureCoordinator(port).depart("account_a", {}, { result = it })
        assertEquals(DepartureOutcome.CleanupRequired, result)
        assertEquals("account_a", port.marker?.departingUid)
        assertTrue(port.frozen)
        assertTrue(port.signedIn)
        assertFalse(port.canEnter("account_b"))
    }

    @Test fun unsupportedFirestorePathUidStillRecoversItsOwnDepartureMarker() {
        val port = FakePort()
        port.marker = AccountSwitchMarker(departingUid = "unsafe/uid", accountEpoch = 7, state = "SwitchingOut")
        var result: Pair<DepartureOutcome, Boolean>? = null
        CleanDepartureCoordinator(port).recover({}, { outcome, hadMarker -> result = outcome to hadMarker })
        assertEquals(DepartureOutcome.Clean to true, result)
        assertEquals(null, port.marker)
        assertFalse(port.signedIn)
    }

    @Test fun preMarkerIntervalIsVisibleAndJoinsExactlyOnce() {
        val ownership = CleanDepartureOwnership()
        assertEquals(DepartureProgress.Idle, ownership.progress())
        assertNull(ownership.currentUid())
        val ownerSeen = mutableListOf<DepartureOutcome>()
        val joinerSeen = mutableListOf<DepartureOutcome>()
        val publicationOrder = mutableListOf<String>()
        assertTrue(ownership.lease("account_a") { ownerSeen += it })
        assertEquals(DepartureProgress.PreMarker, ownership.progress())
        assertEquals("account_a", ownership.currentUid())
        // A recreated graph joins the in-flight departure instead of starting a competing one.
        assertEquals("account_a", ownership.observeInFlight(
            onJoined = { publicationOrder += "waiting" },
            onSettled = { publicationOrder += "settled"; joinerSeen += it },
        ))
        assertFalse(ownership.lease("account_a") { })
        ownership.markPostMarker("account_a")
        assertEquals(DepartureProgress.PostMarker, ownership.progress())
        ownership.settle("account_a", DepartureOutcome.Clean)
        assertEquals(listOf(DepartureOutcome.Clean), ownerSeen)
        assertEquals(listOf(DepartureOutcome.Clean), joinerSeen)
        assertEquals(listOf("waiting", "settled"), publicationOrder)
        assertEquals(DepartureProgress.Idle, ownership.progress())
        assertNull(ownership.currentUid())
        assertTrue(ownership.isSettled())
        assertNull(ownership.observeInFlight(
            onJoined = { publicationOrder += "unexpected-waiting" },
            onSettled = { publicationOrder += "unexpected-settlement" },
        ))
        assertEquals(listOf("waiting", "settled"), publicationOrder)
    }

    @Test fun coordinatorPublishesTheDurableMarkerBoundaryBeforeCleanup() {
        val port = FakePort()
        assertTrue(port.ownership.lease("account_a") { })
        var outcome: DepartureOutcome? = null
        CleanDepartureCoordinator(port).depart("account_a", {}, { outcome = it })
        assertEquals(DepartureOutcome.Clean, outcome)
        assertEquals(DepartureProgress.PostMarker, port.progressAtSignOut)
        port.ownership.settle("account_a", outcome!!)
        assertEquals(DepartureProgress.Idle, port.ownership.progress())
    }

    private class FakePort : CleanDeparturePort {
        override val ownership = CleanDepartureOwnership()
        override fun isRetired() = false
        var marker: AccountSwitchMarker? = null
        var markerReadable = true
        var cleanRegistry = true
        var frozen = false
        var signedIn = true
        var drainFailure: StableFirebaseFailure? = null
        var failAt: String? = null
        var fenceCount = 0
        var progressAtSignOut: DepartureProgress? = null

        fun canEnter(uid: String) = marker == null && !signedIn && uid == "account_b"
        override fun readMarker() = DepartureMarkerRead(marker, markerReadable)
        override fun freeze(uid: String): Boolean { if (frozen) return false; frozen = true; return true }
        override fun unfreeze(uid: String) { frozen = false }
        override fun hasProvenEmptyCriticalWork(uid: String) = cleanRegistry
        override fun drain(timeoutMillis: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
            callback(drainFailure)
            return FirebaseCancellation {}
        }
        override fun persistMarker(uid: String): Boolean {
            marker = AccountSwitchMarker(departingUid = uid, accountEpoch = 1, state = "SwitchingOut")
            return failAt != "markerAfterWrite"
        }
        override fun fenceCallbacks() { fenceCount++ }
        override fun signOut(callback: (StableFirebaseFailure?) -> Unit) {
            progressAtSignOut = ownership.progress()
            if (failAt == "signOut") callback(StableFirebaseFailure(StableFirebaseErrorCode.UNKNOWN, false))
            else { signedIn = false; callback(null) }
        }
        override fun retireFirestore(callback: (StableFirebaseFailure?) -> Unit) {
            callback(if (failAt == "terminate") StableFirebaseFailure(StableFirebaseErrorCode.UNKNOWN, false) else null)
        }
        override fun cleanupLocal(uid: String) = failAt != "local"
        override fun completeMarker(uid: String): Boolean {
            if (failAt == "complete") return false
            marker = null
            return true
        }
        override fun replacement(): ProductIdentityClient = error("not needed by coordinator")
    }
}
