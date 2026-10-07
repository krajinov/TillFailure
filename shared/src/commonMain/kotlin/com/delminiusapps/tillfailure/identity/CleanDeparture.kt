package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.DEFAULT_PENDING_WRITE_TIMEOUT_MILLIS
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.StableFirebaseFailure
import com.delminiusapps.tillfailure.persistence.AccountSwitchMarker
import com.delminiusapps.tillfailure.persistence.RecoveryUidContract

/** A marker is written only after a clean preflight. Its presence always blocks another login. */
data class DepartureMarkerRead(val marker: AccountSwitchMarker? = null, val readable: Boolean = true)

interface CleanDeparturePort {
    fun isRetired(): Boolean
    fun readMarker(): DepartureMarkerRead
    fun freeze(uid: String): Boolean
    fun unfreeze(uid: String)
    /** Requires a readable, initialized UID partition with no unresolved account-owned data. */
    fun hasProvenEmptyCriticalWork(uid: String): Boolean
    fun drain(timeoutMillis: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation
    fun persistMarker(uid: String): Boolean
    /** Stops all callbacks from the departing generation; caller has already disposed listeners. */
    fun fenceCallbacks()
    fun signOut(callback: (StableFirebaseFailure?) -> Unit)
    fun retireFirestore(callback: (StableFirebaseFailure?) -> Unit)
    /** Deletes only the proven-empty account partition; retry after interruption is idempotent. */
    fun cleanupLocal(uid: String): Boolean
    /** Clears the identity pin before removing the marker, so a crash remains recoverable. */
    fun completeMarker(uid: String): Boolean
    fun replacement(): ProductIdentityClient
}

enum class DepartureOutcome { Clean, PreflightBlocked, CleanupRequired }

/** Shared ordering for both native SDKs. Every post-marker failure leaves the marker durable. */
class CleanDepartureCoordinator(private val port: CleanDeparturePort) {
    fun depart(uid: String, disposeAccountCallbacks: () -> Unit, done: (DepartureOutcome) -> Unit) {
        val marker = port.readMarker()
        if (!marker.readable || marker.marker != null || !port.freeze(uid)) {
            done(DepartureOutcome.PreflightBlocked)
            return
        }
        if (!port.hasProvenEmptyCriticalWork(uid)) {
            port.unfreeze(uid)
            done(DepartureOutcome.PreflightBlocked)
            return
        }
        port.drain(DEFAULT_PENDING_WRITE_TIMEOUT_MILLIS) { failure ->
            if (failure != null) {
                port.unfreeze(uid)
                done(DepartureOutcome.PreflightBlocked)
            } else if (!port.persistMarker(uid)) {
                // A failed durable-store acknowledgement is ambiguous: the marker may have reached
                // storage. Never resume ordinary account operations until its absence is proved.
                val after = port.readMarker()
                if (!after.readable || after.marker != null) {
                    done(DepartureOutcome.CleanupRequired)
                } else {
                    port.unfreeze(uid)
                    done(DepartureOutcome.PreflightBlocked)
                }
            } else cleanup(uid, disposeAccountCallbacks, done)
        }
    }

    fun recover(disposeAccountCallbacks: () -> Unit, done: (DepartureOutcome, Boolean) -> Unit) {
        val read = port.readMarker()
        if (!read.readable) { done(DepartureOutcome.CleanupRequired, false); return }
        val marker = read.marker
        if (marker == null) { done(DepartureOutcome.Clean, false); return }
        if (marker.schemaVersion != 1 || marker.state != "SwitchingOut" ||
            !RecoveryUidContract.isValid(marker.departingUid) || !port.freeze(marker.departingUid)) {
            done(DepartureOutcome.CleanupRequired, false)
            return
        }
        cleanup(marker.departingUid, disposeAccountCallbacks) { done(it, true) }
    }

    private fun cleanup(uid: String, disposeAccountCallbacks: () -> Unit, done: (DepartureOutcome) -> Unit) {
        disposeAccountCallbacks()
        port.fenceCallbacks()
        port.signOut { signOutFailure ->
            if (signOutFailure != null) { done(DepartureOutcome.CleanupRequired); return@signOut }
            port.retireFirestore { teardownFailure ->
                if (teardownFailure != null || !port.cleanupLocal(uid) || !port.completeMarker(uid)) {
                    done(DepartureOutcome.CleanupRequired)
                } else done(DepartureOutcome.Clean)
            }
        }
    }
}
