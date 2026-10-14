package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.DepartureOwnershipLock

/** UID-bound settlement notifications, owned by the native SDK generation across root recreation.
 * No reply, email, or token is carried to observers. */
class InvitationChanges {
    private val lock = DepartureOwnershipLock()
    private var sequence = 0L
    private val observers = mutableMapOf<Long, Pair<String, () -> Unit>>()
    fun observe(uid: String, callback: () -> Unit): FirebaseCancellation {
        val id = lock.locked { (++sequence).also { observers[it] = uid to callback } }
        return FirebaseCancellation { lock.locked { observers.remove(id) } }
    }
    fun notify(uid: String) {
        val callbacks = lock.locked { observers.values.filter { it.first == uid }.map { it.second } }
        callbacks.forEach { it() }
    }
}
