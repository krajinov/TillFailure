package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.FirebaseAuthSession
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentResult
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot
import com.delminiusapps.tillfailure.firebase.FirebaseSessionRefresh
import com.delminiusapps.tillfailure.firebase.StableFirebaseFailure

data class MembershipDiscoveryResult(
    val documents: List<FirebaseDocumentSnapshot>? = null,
    val failure: StableFirebaseFailure? = null,
)

/** Narrow product surface. All reads used for authorization must be server sourced. */
interface ProductIdentityClient {
    val departure: CleanDeparturePort? get() = null
    /** Installation-local account pin. A read failure must be reported as pinned/blocked. */
    fun hasPinnedIdentity(): Boolean
    /** Atomically pins the first UID, or accepts the same UID on restore. Never replaces another UID. */
    fun claimPinnedIdentity(uid: String): Boolean
    fun observeSession(epoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation
    fun signIn(email: String, password: String, epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation
    /** Forces a server refresh and returns the freshly checked session, including email verification. */
    fun refreshSession(epoch: Long, callback: (FirebaseSessionRefresh) -> Unit): FirebaseCancellation
    fun getDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation
    fun discoverMemberships(uid: String, epoch: Long, callback: (MembershipDiscoveryResult) -> Unit): FirebaseCancellation
    fun listenDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation
}
