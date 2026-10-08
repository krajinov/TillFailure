package com.delminiusapps.tillfailure.identity

/** Progress of the single installation-wide departure, visible to any recreated account graph. */
enum class DepartureProgress {
    /** No departure is in flight. */
    Idle,

    /** The account is frozen and SDK writes are draining; the durable switch marker does not exist yet. */
    PreMarker,

    /** The durable switch marker exists; sign-out/teardown/local cleanup is in flight. */
    PostMarker,
}

/**
 * One process-level owner for the single in-flight clean departure.
 *
 * A departure is never owned by an Activity, identity root or ViewModel. The owner lives with the
 * process-level native client/bridge that survives recreation (Android retains one client in
 * `AndroidProductIdentitySession`; Apple retains one bridge in the hosted `FirebaseNativeBridge`).
 * It resolves the pre-marker gap in the clean-departure protocol:
 *
 *  - while a departure is draining pending SDK writes but has not yet persisted the durable switch
 *    marker, a recreated account graph must not observe, verify or display protected membership —
 *    the still-signed-in session may yet sign out, fence callbacks or retire the Firebase client;
 *  - the recreated graph *joins* the in-flight departure and adopts its terminal outcome, so exactly
 *    one live graph acts on completion instead of starting a competing departure or re-authorizing;
 *  - the durable post-marker restart path stays with [CleanDepartureCoordinator.recover], which runs
 *    only when the in-memory owner is idle (a genuinely new process).
 *
 * All transitions are synchronous and single-slot: no operation is orphaned, and no second departure
 * can start until the first settles.
 */
class CleanDepartureOwnership {
    private val lock = DepartureOwnershipLock()
    private var departingUid: String? = null
    private var postMarker = false
    private var settled = false
    private val observers = mutableListOf<(DepartureOutcome) -> Unit>()

    fun progress(): DepartureProgress = lock.locked {
        when {
            departingUid == null -> DepartureProgress.Idle
            postMarker -> DepartureProgress.PostMarker
            else -> DepartureProgress.PreMarker
        }
    }

    /** The departing UID, or null when idle. */
    fun currentUid(): String? = lock.locked { departingUid }

    /** True once a departure has settled; cleared when the next one is leased. */
    fun isSettled(): Boolean = lock.locked { settled }

    /**
     * Atomically starts a departure when the slot is free, or joins the in-flight one. Always
     * registers [onSettled]. Returns true only for the single caller that now owns the departure and
     * must run [CleanDepartureCoordinator]; every later caller joins and only waits.
     */
    fun lease(uid: String, onSettled: (DepartureOutcome) -> Unit): Boolean = lock.locked {
        observers += onSettled
        if (departingUid != null) {
            false
        } else {
            departingUid = uid
            postMarker = false
            settled = false
            true
        }
    }

    /** Joins an in-flight departure without starting one; returns its UID or null when idle. */
    fun observeInFlight(onSettled: (DepartureOutcome) -> Unit): String? = lock.locked {
        val uid = departingUid
        if (uid == null) {
            null
        } else {
            observers += onSettled
            uid
        }
    }

    /** Records that the durable switch marker now exists for the in-flight departure. */
    fun markPostMarker(uid: String) {
        lock.locked { if (departingUid == uid) postMarker = true }
    }

    /** Releases the slot once the owner reports its terminal [outcome] and notifies every observer. */
    fun settle(uid: String, outcome: DepartureOutcome) {
        val pending: List<(DepartureOutcome) -> Unit> = lock.locked {
            if (departingUid != uid) {
                emptyList()
            } else {
                departingUid = null
                postMarker = false
                settled = true
                val current = observers.toList()
                observers.clear()
                current
            }
        }
        pending.forEach { it(outcome) }
    }
}
