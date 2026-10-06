package com.delminiusapps.tillfailure.firebase

import com.delminiusapps.tillfailure.identity.MembershipDiscoveryResult
import com.delminiusapps.tillfailure.identity.ProductIdentityClient
import com.delminiusapps.tillfailure.identity.CleanDeparturePort
import com.delminiusapps.tillfailure.identity.DepartureMarkerRead
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope
import com.delminiusapps.tillfailure.persistence.AccountSwitchMarker
import com.delminiusapps.tillfailure.persistence.IosAtomicFilePersistence
import com.delminiusapps.tillfailure.persistence.NativeRecoveryPersistenceBridge

class NativeFirebaseAuthState(
    val uid: String?,
    val isAnonymous: Boolean,
)

class NativeFirebaseFailure(
    val code: String,
    val retryable: Boolean,
)

class NativeFirebaseDocument(
    val path: String,
    val fields: Map<String, String>,
    val exists: Boolean,
    val isFromCache: Boolean,
    val hasPendingWrites: Boolean,
)

class NativeFirebaseDocumentResult(
    val document: NativeFirebaseDocument?,
    val failure: NativeFirebaseFailure?,
)

class NativeFirebaseUnitResult(
    val failure: NativeFirebaseFailure?,
)

interface NativeFirebaseBridge {
    fun observeSession(accountEpoch: Long, callback: (NativeFirebaseAuthState) -> Unit): String
    fun getDocument(path: String, accountEpoch: Long, callback: (NativeFirebaseDocumentResult) -> Unit): String
    fun listenDocument(path: String, accountEpoch: Long, callback: (NativeFirebaseDocumentResult) -> Unit): String
    fun writeDocument(path: String, fields: Map<String, String>, accountEpoch: Long, callback: (NativeFirebaseUnitResult) -> Unit): String
    fun increment(path: String, field: String, by: Long, accountEpoch: Long, callback: (NativeFirebaseDocumentResult) -> Unit): String
    fun waitForPendingWrites(accountEpoch: Long, timeoutMillis: Long, callback: (NativeFirebaseUnitResult) -> Unit): String
    fun cancel(token: String)
    fun terminateAndClear(callback: (NativeFirebaseUnitResult) -> Unit)
}

class NativeMembershipDiscoveryResult(
    val documents: List<NativeFirebaseDocument>?,
    val failure: NativeFirebaseFailure?,
)

class NativeDepartureMarkerRead(val uid: String?, val epoch: Long, val readable: Boolean)

interface NativeIdentityBridge : NativeFirebaseBridge {
    fun activeBridge(): NativeIdentityBridge
    fun isRetired(): Boolean
    fun hasPinnedIdentity(): Boolean
    fun claimPinnedIdentity(uid: String): Boolean
    fun signIn(email: String, password: String, accountEpoch: Long, callback: (NativeFirebaseUnitResult) -> Unit): String
    fun refreshSession(accountEpoch: Long, callback: (NativeFirebaseUnitResult) -> Unit): String
    fun discoverMemberships(uid: String, accountEpoch: Long, callback: (NativeMembershipDiscoveryResult) -> Unit): String
    fun readDepartureMarker(): NativeDepartureMarkerRead
    fun freezeAccount(uid: String): Boolean
    fun unfreezeAccount(uid: String)
    fun persistDepartureMarker(uid: String): Boolean
    fun fenceDepartureCallbacks()
    fun signOutProduct(callback: (NativeFirebaseUnitResult) -> Unit)
    fun completeDepartureMarker(uid: String): Boolean
    fun replacementBridge(): NativeIdentityBridge
}

class IosProductIdentityClient(
    bridge: NativeIdentityBridge,
    private val recoveryBridge: NativeRecoveryPersistenceBridge,
) : ProductIdentityClient, CleanDeparturePort {
    private val bridge = bridge.activeBridge()
    private val spike = IosFirebaseSpikeClient(bridge)
    private val recoveryStore = IosAtomicFilePersistence(recoveryBridge)
    override val departure: CleanDeparturePort get() = this
    override fun isRetired(): Boolean = bridge.isRetired()

    override fun hasPinnedIdentity(): Boolean = bridge.hasPinnedIdentity()
    override fun claimPinnedIdentity(uid: String): Boolean = runCatching {
        if (!bridge.hasPinnedIdentity() && recoveryStore.read(uid) == null) {
            recoveryStore.write(AccountPersistenceEnvelope(uid = uid))
        }
        bridge.claimPinnedIdentity(uid)
    }.getOrDefault(false)

    override fun readMarker(): DepartureMarkerRead {
        val read = bridge.readDepartureMarker()
        return when {
            !read.readable -> DepartureMarkerRead(readable = false)
            read.uid == null && read.epoch == 0L -> DepartureMarkerRead()
            read.uid == null || !isPathSafeAuthUid(read.uid) || read.epoch <= 0L -> DepartureMarkerRead(readable = false)
            else -> DepartureMarkerRead(AccountSwitchMarker(departingUid = read.uid,
                accountEpoch = read.epoch, state = "SwitchingOut"))
        }
    }

    override fun freeze(uid: String): Boolean = bridge.freezeAccount(uid)
    override fun unfreeze(uid: String) = bridge.unfreezeAccount(uid)
    override fun hasProvenEmptyCriticalWork(uid: String): Boolean = runCatching {
        val record = recoveryStore.read(uid) ?: return@runCatching false
        record.schemaVersion == 1 && record.uid == uid && record.offlineAccessGrant == null &&
            record.downloadManifests.isEmpty() && record.workoutRecoverySnapshot == null &&
            record.mutationJournal.isEmpty() && record.pendingUploads.isEmpty() && record.switchMarker == null
    }.getOrDefault(false)
    override fun drain(timeoutMillis: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation =
        spike.waitForPendingWrites(0L, timeoutMillis) { callback(it.failure) }
    override fun persistMarker(uid: String): Boolean = bridge.persistDepartureMarker(uid)
    override fun fenceCallbacks() = bridge.fenceDepartureCallbacks()
    override fun signOut(callback: (StableFirebaseFailure?) -> Unit) =
        bridge.signOutProduct { callback(it.failure?.toStableFailure()) }
    override fun retireFirestore(callback: (StableFirebaseFailure?) -> Unit) =
        bridge.terminateAndClear { callback(it.failure?.toStableFailure()) }
    override fun cleanupLocal(uid: String): Boolean = runCatching {
        val record = recoveryStore.read(uid)
        if (record != null && !hasProvenEmptyCriticalWork(uid)) return@runCatching false
        recoveryStore.delete(uid)
        recoveryStore.read(uid) == null
    }.getOrDefault(false)
    override fun completeMarker(uid: String): Boolean = bridge.completeDepartureMarker(uid)
    override fun replacement(): ProductIdentityClient = IosProductIdentityClient(bridge.replacementBridge(), recoveryBridge)

    override fun observeSession(epoch: Long, callback: (FirebaseAuthSession) -> Unit) = spike.observeSession(epoch, callback)
    override fun getDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit) = spike.getDocument(path, epoch, callback)
    override fun listenDocument(path: String, epoch: Long, callback: (FirebaseDocumentResult) -> Unit) = spike.listenDocument(path, epoch, callback)

    override fun signIn(email: String, password: String, epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
        val token = bridge.signIn(email, password, epoch) { callback(it.failure?.toStableFailure()) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun refreshSession(epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
        val token = bridge.refreshSession(epoch) { callback(it.failure?.toStableFailure()) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun discoverMemberships(uid: String, epoch: Long, callback: (MembershipDiscoveryResult) -> Unit): FirebaseCancellation {
        val token = bridge.discoverMemberships(uid, epoch) { result ->
            callback(MembershipDiscoveryResult(
                documents = result.documents?.map {
                    FirebaseDocumentSnapshot(
                        path = it.path, fields = it.fields, exists = it.exists,
                        origin = if (it.isFromCache) FirebaseDataOrigin.CACHE else FirebaseDataOrigin.SERVER,
                        hasPendingWrites = it.hasPendingWrites,
                    )
                },
                failure = result.failure?.toStableFailure(),
            ))
        }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    private fun NativeFirebaseFailure.toStableFailure() = StableFirebaseFailure(
        StableFirebaseErrorCode.entries.firstOrNull { it.name == code } ?: StableFirebaseErrorCode.UNKNOWN,
        retryable,
    )
}

class IosFirebaseSpikeClient(
    private val bridge: NativeFirebaseBridge,
) : FirebaseSpikeClient {
    override fun observeSession(accountEpoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation {
        val token = bridge.observeSession(accountEpoch) { state -> callback(FirebaseAuthSession(state.uid, state.isAnonymous)) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun getDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
        val token = bridge.getDocument(path, accountEpoch) { callback(it.toContract()) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun listenDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
        val token = bridge.listenDocument(path, accountEpoch) { callback(it.toContract()) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun writeDocument(path: String, fields: Map<String, String>, accountEpoch: Long, callback: (FirebaseUnitResult) -> Unit): FirebaseCancellation {
        val token = bridge.writeDocument(path, fields, accountEpoch) { callback(it.toContract()) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun increment(path: String, field: String, by: Long, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
        val token = bridge.increment(path, field, by, accountEpoch) { callback(it.toContract()) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun waitForPendingWrites(
        accountEpoch: Long,
        timeoutMillis: Long,
        callback: (FirebaseUnitResult) -> Unit,
    ): FirebaseCancellation {
        val token = bridge.waitForPendingWrites(accountEpoch, timeoutMillis) { callback(it.toContract()) }
        return FirebaseCancellation { bridge.cancel(token) }
    }

    override fun terminateAndClear(callback: (FirebaseUnitResult) -> Unit) {
        bridge.terminateAndClear { callback(it.toContract()) }
    }

    private fun NativeFirebaseDocumentResult.toContract(): FirebaseDocumentResult = when {
        document != null -> FirebaseDocumentResult(
            document = FirebaseDocumentSnapshot(
                path = document.path,
                fields = document.fields,
                exists = document.exists,
                origin = if (document.isFromCache) FirebaseDataOrigin.CACHE else FirebaseDataOrigin.SERVER,
                hasPendingWrites = document.hasPendingWrites,
            ),
        )
        failure != null -> FirebaseDocumentResult(failure = failure.toContract())
        else -> FirebaseDocumentResult(failure = StableFirebaseFailure(StableFirebaseErrorCode.UNKNOWN, false))
    }

    private fun NativeFirebaseUnitResult.toContract() = FirebaseUnitResult(failure?.toContract())

    private fun NativeFirebaseFailure.toContract() = StableFirebaseFailure(
        code = StableFirebaseErrorCode.entries.firstOrNull { it.name == code } ?: StableFirebaseErrorCode.UNKNOWN,
        retryable = retryable,
    )
}
