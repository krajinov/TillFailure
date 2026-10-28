package com.delminiusapps.tillfailure.firebase

import com.delminiusapps.tillfailure.identity.MembershipDiscoveryResult
import com.delminiusapps.tillfailure.identity.ProductIdentityClient
import com.delminiusapps.tillfailure.identity.CleanDepartureOwnership
import com.delminiusapps.tillfailure.identity.CleanDeparturePort
import com.delminiusapps.tillfailure.identity.DepartureMarkerRead
import com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope
import com.delminiusapps.tillfailure.persistence.AccountSwitchMarker
import com.delminiusapps.tillfailure.persistence.IosAtomicFilePersistence
import com.delminiusapps.tillfailure.persistence.NativeRecoveryPersistenceBridge
import com.delminiusapps.tillfailure.persistence.RecoveryUidContract
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.clients.*
import com.delminiusapps.tillfailure.exercises.*
import platform.Foundation.NSUUID

class NativeFirebaseAuthState(
    val uid: String?,
    val isAnonymous: Boolean,
    val email: String?,
    val emailVerified: Boolean,
)

class NativeFirebaseSessionRefresh(
    val uid: String?,
    val isAnonymous: Boolean,
    val email: String?,
    val emailVerified: Boolean,
    val failure: NativeFirebaseFailure?,
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

class NativeClientOutcome(val json: String?, val error: String?)

class NativeInvitationOutcome(val json: String?, val error: String?, val ambiguous: Boolean)

interface NativeIdentityBridge : NativeFirebaseBridge {
    fun callCustomExercises(uid:String,json:String,callback:(NativeClientOutcome)->Unit):String { callback(NativeClientOutcome(null,"unavailable"));return "" }
    fun readSystemExercises(uid: String, afterId: String?, exerciseId: String?, callback: (NativeClientOutcome) -> Unit): String {
        callback(NativeClientOutcome(null, "UNAVAILABLE")); return ""
    }
    fun beforeCommandRetirement() {}
    fun noteBindingNonce(): String = error("unavailable")
    fun callClients(uid: String, json: String, callback: (NativeClientOutcome) -> Unit): String
    val invitationChanges: InvitationChanges
    /** Shared native issuance/freeze lock, including all protected read-modify-write operations. */
    fun withWritableAccount(uid: String, operation: () -> Boolean): Boolean
    /** Terminal-proof retirement may run while departure is frozen, but shares its ownership lock. */
    fun withRecoveryLock(operation: () -> Unit)
    fun observeConnectivity(callback: (Boolean) -> Unit): String
    fun hasInvitationInFlight(): Boolean
    /** prepare executes under the native issuance lock before the SDK call, after writable/UID checks. */
    fun callInvitation(uid: String, json: String, prepare: () -> Boolean, callback: (NativeInvitationOutcome) -> Unit): String
    fun activeBridge(): NativeIdentityBridge
    /** Process-level departure owner, held by the hosted bridge that survives host recreation. */
    val ownership: CleanDepartureOwnership
    fun isRetired(): Boolean
    fun hasPinnedIdentity(): Boolean
    fun claimPinnedIdentity(uid: String): Boolean
    fun signIn(email: String, password: String, accountEpoch: Long, callback: (NativeFirebaseUnitResult) -> Unit): String
    fun refreshSession(accountEpoch: Long, callback: (NativeFirebaseSessionRefresh) -> Unit): String
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
    private val invitationLedger = InvitationLedger(recoveryStore).apply { beforeRetirementForTest = { bridge.beforeCommandRetirement() } }
    private fun <T> protectedRead(read: () -> T): T {
        var result: Result<T>? = null
        bridge.withRecoveryLock { result = runCatching(read) }
        return requireNotNull(result).getOrThrow()
    }
    override val invitations: InvitationClient = object : InvitationClient {
        override fun lastSettled(uid: String) = protectedRead {
            invitationLedger.lastSettled(uid).also { memory.consume(uid, null, it) }
        }
        override fun workspacePreference(uid: String) = protectedRead { invitationLedger.workspacePreference(uid) }
        override fun saveWorkspacePreference(uid: String, workspaceId: String) {
            bridge.withWritableAccount(uid) {
                runCatching { invitationLedger.saveWorkspacePreference(uid, workspaceId); true }.getOrDefault(false)
            }
        }
        override fun recoveryState(uid: String) = protectedRead { invitationLedger.recoveryState(uid) }
        override val memory get() = PendingInvitationLinks.memory
        override fun customExerciseAdmission(uid:String,request:InvitationRequest)=bridge.withWritableAccount(uid) { invitationLedger.customExerciseAdmission(uid,request) }
        override fun newNoteBindingNonce(): String = bridge.noteBindingNonce()
        override fun newCommandKey(): String = resolutionCommandKey(NSUUID().UUIDString)
        override fun pending(uid: String): PendingInvitationCommand? = protectedRead { invitationLedger.pending(uid) }
        override fun observePending(uid: String, callback: () -> Unit) = bridge.invitationChanges.observe(uid, callback)
        override fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation {
            var cancelled = false
            bridge.callInvitation(uid, InvitationJson.request(request), {
                (invitationLedger.prepare(uid, request) && (request.action != "accept" ||
                    memory.protectAcceptance(request.pending(uid)!!, request.token))).also { if (it) bridge.invitationChanges.notify(uid) }
            }) { result ->
                val settled = invitationLedger.settle(uid, request, InvitationJson.outcome(request, result.json, result.error, result.ambiguous))
                memory.consume(uid, settled.reply, runCatching { invitationLedger.lastSettled(uid) }.getOrNull())
                bridge.invitationChanges.notify(uid)
                if (!cancelled) callback(settled)
            }
            // Keep native settlement alive to reconcile durable work; suppress only presentation.
            return FirebaseCancellation { cancelled = true }
        }
    }
    override val exercises: SystemExerciseRepository = object : SystemExerciseRepository {
        override fun read(uid: String, afterId: String?, exerciseId: String?, callback: (ExerciseResult) -> Unit): FirebaseCancellation {
            if (!SystemExerciseProtocol.validRequest(uid, afterId, exerciseId)) {
                callback(ExerciseResult(error = ExerciseError.Unavailable)); return FirebaseCancellation {}
            }
            var cancelled = false
            val token = bridge.readSystemExercises(uid, afterId, exerciseId) { result ->
                if (!cancelled) callback(SystemExerciseProtocol.result(afterId, exerciseId, result.json, result.error))
            }
            return FirebaseCancellation { cancelled = true; bridge.cancel(token) }
        }
    }
    override val customExercises: CustomExerciseRepository = object : CustomExerciseRepository {
        override val commands get() = invitations
        override fun newExerciseId()=NSUUID().UUIDString.lowercase()
        override fun read(scope: ExerciseScope, request: CustomExerciseReadRequest, callback: (CustomExerciseResult) -> Unit): FirebaseCancellation {
            if (!request.valid() || request.workspaceId != scope.workspaceId) {
                callback(CustomExerciseResult(error = "unavailable")); return FirebaseCancellation {}
            }
            var cancelled = false
            val token = bridge.callCustomExercises(scope.uid, CustomExerciseProtocol.request(request)) { result ->
                if (!cancelled) callback(CustomExerciseProtocol.result(scope, request, result.json, result.error))
            }
            return FirebaseCancellation { cancelled = true; bridge.cancel(token) }
        }
    }
    override val clients: ClientRepository = object : ClientRepository {
        override val commands get() = invitations
        override fun read(scope: ClientScope, request: ClientRequest, callback: (ClientResult) -> Unit): FirebaseCancellation {
            if (!ClientJson.valid(request) || request.workspaceId != scope.workspaceId) {
                callback(ClientResult(error = "unavailable")); return FirebaseCancellation {}
            }
            var cancelled = false
            val token = bridge.callClients(scope.uid, ClientJson.request(request)) { result ->
                if (!cancelled) callback(ClientJson.result(request, result.json, result.error))
            }
            return FirebaseCancellation { cancelled = true; bridge.cancel(token) }
        }
    }
    override val departure: CleanDeparturePort get() = this
    /** Forwarded to the process-level bridge so it survives host/root recreation. */
    override val ownership: CleanDepartureOwnership get() = bridge.ownership
    override fun isRetired(): Boolean = bridge.isRetired()

    override fun observeConnectivity(callback: (Boolean) -> Unit): FirebaseCancellation {
        val token = bridge.observeConnectivity(callback)
        return FirebaseCancellation { bridge.cancel(token) }
    }
    override fun hasPinnedIdentity(): Boolean = bridge.hasPinnedIdentity()
    override fun claimPinnedIdentity(uid: String): Boolean = bridge.withWritableAccount(uid) {
        runCatching {
            if (!bridge.hasPinnedIdentity() && recoveryStore.read(uid) == null) {
                recoveryStore.write(AccountPersistenceEnvelope(uid = uid))
            }
            bridge.claimPinnedIdentity(uid)
        }.getOrDefault(false)
    }

    override fun readMarker(): DepartureMarkerRead {
        val read = bridge.readDepartureMarker()
        return when {
            !read.readable -> DepartureMarkerRead(readable = false)
            read.uid == null && read.epoch == 0L -> DepartureMarkerRead()
            read.uid == null || !RecoveryUidContract.isValid(read.uid) || read.epoch <= 0L -> DepartureMarkerRead(readable = false)
            else -> DepartureMarkerRead(AccountSwitchMarker(departingUid = read.uid,
                accountEpoch = read.epoch, state = "SwitchingOut"))
        }
    }

    override fun freeze(uid: String): Boolean = RecoveryUidContract.isValid(uid) && bridge.freezeAccount(uid)
    override fun unfreeze(uid: String) = bridge.unfreezeAccount(uid)
    override fun hasProvenEmptyCriticalWork(uid: String): Boolean = runCatching {
        if (bridge.hasInvitationInFlight()) return@runCatching false
        val record = recoveryStore.read(uid) ?: return@runCatching false
        record.schemaVersion == 1 && record.uid == uid && record.offlineAccessGrant == null &&
            record.downloadManifests.isEmpty() && record.workoutRecoverySnapshot == null &&
            record.mutationJournal.isEmpty() && record.pendingUploads.isEmpty() && record.switchMarker == null &&
            record.pendingInvitationCommands.isEmpty()
    }.getOrDefault(false)
    override fun drain(timeoutMillis: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation =
        spike.waitForPendingWrites(0L, timeoutMillis) { callback(it.failure) }
    override fun persistMarker(uid: String): Boolean = RecoveryUidContract.isValid(uid) && bridge.persistDepartureMarker(uid)
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

    override fun refreshSession(epoch: Long, callback: (FirebaseSessionRefresh) -> Unit): FirebaseCancellation {
        val token = bridge.refreshSession(epoch) { result ->
            val failure = result.failure
            if (failure != null) {
                callback(FirebaseSessionRefresh(failure = failure.toStableFailure()))
            } else {
                callback(FirebaseSessionRefresh(session = FirebaseAuthSession(
                    uid = result.uid, isAnonymous = result.isAnonymous,
                    email = result.email, emailVerified = result.emailVerified)))
            }
        }
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
        val token = bridge.observeSession(accountEpoch) { state ->
            callback(FirebaseAuthSession(state.uid, state.isAnonymous, state.email, state.emailVerified))
        }
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
