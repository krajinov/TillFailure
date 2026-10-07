package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.StableFirebaseErrorCode
import com.delminiusapps.tillfailure.firebase.StableFirebaseFailure
import com.delminiusapps.tillfailure.persistence.AccountSwitchMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private class FakePort : CleanDeparturePort {
        override fun isRetired() = false
        var marker: AccountSwitchMarker? = null
        var markerReadable = true
        var cleanRegistry = true
        var frozen = false
        var signedIn = true
        var drainFailure: StableFirebaseFailure? = null
        var failAt: String? = null
        var fenceCount = 0

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
