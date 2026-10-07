package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.delminiusapps.tillfailure.identity.MembershipDiscoveryResult
import com.delminiusapps.tillfailure.identity.ProductIdentityClient
import com.delminiusapps.tillfailure.identity.CleanDeparturePort
import com.delminiusapps.tillfailure.identity.DepartureMarkerRead
import com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope
import com.delminiusapps.tillfailure.persistence.AccountSwitchMarker
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import com.delminiusapps.tillfailure.persistence.RecoveryUidContract

class AndroidFirebaseSpikeClient(
    context: Context,
    private val configuration: FirebaseEmulatorConfiguration,
    private val fence: AccountCallbackFence,
    private val productMemoryCache: Boolean = false,
    private val overrideProductAppName: String? = null,
) : FirebaseSpikeClient, ProductIdentityClient, CleanDeparturePort {
    private val applicationContext = context.applicationContext
    private val recoveryStore = AndroidAtomicFilePersistence(applicationContext)
    private val generation: AndroidFirebaseAppRegistry.Generation
    private val app: FirebaseApp
    private val auth: FirebaseAuth
    private val firestore: FirebaseFirestore
    private val terminated = AtomicBoolean(false)
    private val identityPin = context.applicationContext.getSharedPreferences("tillfailure_identity_pin_v1", Context.MODE_PRIVATE)
    private var accountFrozen = false
    override val departure: CleanDeparturePort get() = this
    // Serializes SDK issuance and callback delivery with the moment teardown retires this client.
    // The gate is released as soon as an SDK operation is issued; asynchronous work never holds it.
    private val callbackLock = Any()
    internal var beforeWriteIssuanceForTest: (() -> Unit)? = null
    internal var afterWriteIssuedForTest: (() -> Unit)? = null
    internal var beforeDepartureLocalCleanupForTest: (() -> Unit)? = null

    init {
        // One generation per client: overlapping clients and later recreations receive distinct
        // SDK instances, so this client's teardown cannot terminate a peer's Firestore singleton.
        val preferredName = if (productMemoryCache) synchronized(identityPinLock) {
            val stable = identityPin.getString("productAppName", null) ?: run {
                val created = "tillfailure-${configuration.projectId}-product-${UUID.randomUUID()}"
                check(identityPin.edit().putString("productAppName", created).commit()) {
                    "Cannot persist product Firebase generation"
                }
                created
            }
            overrideProductAppName ?: stable
        } else null
        generation = AndroidFirebaseAppRegistry.acquire(context, configuration.projectId, preferredName)
        app = generation.app
        auth = FirebaseAuth.getInstance(app)
        firestore = FirebaseFirestore.getInstance(app)
        auth.useEmulator(configuration.host, configuration.authPort)
        if (productMemoryCache) firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        firestore.useEmulator(configuration.host, configuration.firestorePort)
    }

    /**
     * Fail-closed guard for every operation: once this client's teardown has run it must never touch
     * the SDK again, and callers receive an explicit non-retryable failure instead of silence or a
     * callback that could be mistaken for a newer generation's result.
     */
    private fun terminatedFailure(): StableFirebaseFailure? =
        if (terminated.get()) StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false) else null

    private fun <T : Any> issueIfLive(issue: () -> T?): T? = synchronized(callbackLock) {
        if (terminated.get()) null else issue()
    }

    private fun deliverIfCurrent(epoch: Long, cancelled: AtomicBoolean?, deliver: () -> Unit) {
        synchronized(callbackLock) {
            if (!terminated.get() && cancelled?.get() != true) {
                fence.deliverIfCurrent(epoch) {
                    if (!terminated.get() && cancelled?.get() != true) deliver()
                }
            }
        }
    }

    private fun deliverIfLive(deliver: () -> Unit) {
        synchronized(callbackLock) {
            if (!terminated.get()) deliver()
        }
    }

    /**
     * The named SDK app this client acquired. Device tests use it to seed or inspect fixtures on the
     * same instance the client operates on, which is required now that every client acquires its own
     * app generation instead of a process-wide fixed name.
     */
    internal val firebaseApp: FirebaseApp get() = app

    private companion object {
        val identityPinLock = Any()
    }

    override fun hasPinnedIdentity(): Boolean = runCatching {
        identityPin.contains("uidSha256")
    }.getOrDefault(true)

    override fun claimPinnedIdentity(uid: String): Boolean = synchronized(identityPinLock) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val existing = identityPin.getString("uidSha256", null)
            if (existing == null) {
                // A missing registry on an already-pinned installation is never guessed clean.
                if (recoveryStore.read(uid) == null) recoveryStore.write(AccountPersistenceEnvelope(uid = uid))
                identityPin.edit().putString("uidSha256", digest).commit()
            }
            else existing == digest
        }.getOrDefault(false)
    }

    override fun observeSession(accountEpoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val listener = FirebaseAuth.AuthStateListener { observed ->
            deliverIfCurrent(accountEpoch, cancelled) {
                callback(FirebaseAuthSession(observed.currentUser?.uid, observed.currentUser?.isAnonymous == true))
            }
        }
        if (issueIfLive { auth.addAuthStateListener(listener); true } == null) return FirebaseCancellation {}
        return FirebaseCancellation {
            if (cancelled.compareAndSet(false, true)) auth.removeAuthStateListener(listener)
        }
    }

    override fun getDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val task = issueIfLive { firestore.document(path).get(Source.SERVER) } ?: run {
            callback(FirebaseDocumentResult(failure = terminatedFailure()!!))
            return FirebaseCancellation {}
        }
        task
            .addOnSuccessListener { snapshot -> deliverDocument(snapshot, accountEpoch, cancelled, callback) }
            .addOnFailureListener { error -> deliverFailure(error, accountEpoch, cancelled, callback) }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun signIn(email: String, password: String, epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val task = issueIfLive { if (accountFrozen) null else auth.signInWithEmailAndPassword(email, password) } ?: run {
            callback(StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false))
            return FirebaseCancellation {}
        }
        task.addOnSuccessListener { deliverIfCurrent(epoch, cancelled) { callback(null) } }
            .addOnFailureListener { error -> deliverIfCurrent(epoch, cancelled) { callback(mapFailure(error)) } }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun refreshSession(epoch: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val user = auth.currentUser ?: run {
            callback(StableFirebaseFailure(StableFirebaseErrorCode.UNAUTHENTICATED, false))
            return FirebaseCancellation {}
        }
        val task = issueIfLive { user.getIdToken(true) } ?: run {
            callback(terminatedFailure())
            return FirebaseCancellation {}
        }
        task.addOnSuccessListener { deliverIfCurrent(epoch, cancelled) { callback(null) } }
            .addOnFailureListener { error -> deliverIfCurrent(epoch, cancelled) { callback(mapFailure(error)) } }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun discoverMemberships(uid: String, epoch: Long, callback: (MembershipDiscoveryResult) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val task = issueIfLive {
            firestore.collectionGroup("memberships")
                .whereEqualTo("userId", uid)
                .whereEqualTo("status", "active")
                .whereEqualTo("schemaVersion", 1)
                .limit(20)
                .get(Source.SERVER)
        } ?: run {
            callback(MembershipDiscoveryResult(failure = terminatedFailure()))
            return FirebaseCancellation {}
        }
        task.addOnSuccessListener { result ->
            deliverIfCurrent(epoch, cancelled) {
                callback(MembershipDiscoveryResult(documents = result.documents.map { it.toContract() }))
            }
        }.addOnFailureListener { error ->
            deliverIfCurrent(epoch, cancelled) { callback(MembershipDiscoveryResult(failure = mapFailure(error))) }
        }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun listenDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val registration = issueIfLive {
            firestore.document(path).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                deliverIfCurrent(accountEpoch, cancelled) {
                    when {
                        error != null -> callback(FirebaseDocumentResult(failure = mapFailure(error)))
                        snapshot != null -> callback(FirebaseDocumentResult(document = snapshot.toContract()))
                    }
                }
            }
        } ?: return FirebaseCancellation {}
        return FirebaseCancellation {
            if (cancelled.compareAndSet(false, true)) registration.remove()
        }
    }

    override fun writeDocument(
        path: String,
        fields: Map<String, String>,
        accountEpoch: Long,
        callback: (FirebaseUnitResult) -> Unit,
    ): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        beforeWriteIssuanceForTest?.invoke()
        val task = issueIfLive {
            if (accountFrozen) return@issueIfLive null
            firestore.document(path).set(fields).also { afterWriteIssuedForTest?.invoke() }
        } ?: run {
            callback(FirebaseUnitResult(StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false)))
            return FirebaseCancellation {}
        }
        task
            .addOnSuccessListener { deliverIfCurrent(accountEpoch, cancelled) { callback(FirebaseUnitResult()) } }
            .addOnFailureListener { error -> deliverIfCurrent(accountEpoch, cancelled) { callback(FirebaseUnitResult(mapFailure(error))) } }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun increment(
        path: String,
        field: String,
        by: Long,
        accountEpoch: Long,
        callback: (FirebaseDocumentResult) -> Unit,
    ): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val issued = issueIfLive {
            if (accountFrozen) return@issueIfLive null
            val reference = firestore.document(path)
            reference to firestore.runTransaction { transaction ->
                val snapshot = transaction.get(reference)
                val current = counterStartValue(snapshot.get(field))
                val next = CounterValueContract.addExact(current, by)
                    ?: throw CounterValueException("Counter increment overflow for '$field'")
                transaction.set(reference, mapOf(field to next), SetOptions.merge())
                next
            }
        } ?: run {
            callback(FirebaseDocumentResult(failure = StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false)))
            return FirebaseCancellation {}
        }
        val (reference, task) = issued
        task.addOnSuccessListener {
            deliverIfCurrent(accountEpoch, cancelled) {
                reference.get(Source.SERVER)
                    .addOnSuccessListener { snapshot -> deliverDocument(snapshot, accountEpoch, cancelled, callback) }
                    .addOnFailureListener { error -> deliverFailure(error, accountEpoch, cancelled, callback) }
            }
        }.addOnFailureListener { error -> deliverFailure(error, accountEpoch, cancelled, callback) }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun waitForPendingWrites(
        accountEpoch: Long,
        timeoutMillis: Long,
        callback: (FirebaseUnitResult) -> Unit,
    ): FirebaseCancellation {
        require(timeoutMillis > 0) { "Pending-write timeout must be positive" }
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        fun finish(result: FirebaseUnitResult) {
            if (completed.compareAndSet(false, true)) deliverIfCurrent(accountEpoch, null) { callback(result) }
        }
        val timeout = Runnable {
            finish(FirebaseUnitResult(StableFirebaseFailure(StableFirebaseErrorCode.DEADLINE_EXCEEDED, true)))
        }
        val task = issueIfLive {
            handler.postDelayed(timeout, timeoutMillis)
            firestore.waitForPendingWrites()
        } ?: run {
            callback(FirebaseUnitResult(terminatedFailure()!!))
            return FirebaseCancellation {}
        }
        task
            .addOnSuccessListener {
                handler.removeCallbacks(timeout)
                finish(FirebaseUnitResult())
            }
            .addOnFailureListener { error ->
                handler.removeCallbacks(timeout)
                finish(FirebaseUnitResult(mapFailure(error)))
            }
        return FirebaseCancellation {
            handler.removeCallbacks(timeout)
            finish(FirebaseUnitResult(StableFirebaseFailure(StableFirebaseErrorCode.CANCELLED, true)))
        }
    }

    override fun terminateAndClear(callback: (FirebaseUnitResult) -> Unit) {
        // Retire callback delivery synchronously before SDK teardown begins. Any callback already
        // delivering completes under this lock before termination can start.
        val firstTermination = synchronized(callbackLock) { terminated.compareAndSet(false, true) }
        AndroidFirebaseAppRegistry.close(generation)
        if (!firstTermination) {
            // Repeated cleanup is deterministic and idempotent: the instances have already been
            // retired, so there is nothing left to terminate or clear.
            callback(FirebaseUnitResult())
            return
        }
        firestore.terminate()
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Firestore termination failed")
                firestore.clearPersistence()
            }
            .addOnSuccessListener {
                // The retired app is deliberately left alive (never reissued): deleting it would break
                // unrelated SDK component lookups while its internals finish their asynchronous work.
                callback(FirebaseUnitResult())
            }
            .addOnFailureListener { error ->
                // Even a partial teardown retires the generation: the dead instances must never be
                // reissued, and the failure is reported instead of being hidden.
                callback(FirebaseUnitResult(mapFailure(error)))
            }
    }

    override fun readMarker(): DepartureMarkerRead = runCatching {
        val uid = identityPin.getString("switchUid", null)
        val epoch = identityPin.getLong("switchEpoch", 0L)
        when {
            uid == null && epoch == 0L -> DepartureMarkerRead()
            uid == null || !RecoveryUidContract.isValid(uid) || epoch <= 0L -> DepartureMarkerRead(readable = false)
            else -> DepartureMarkerRead(AccountSwitchMarker(departingUid = uid, accountEpoch = epoch, state = "SwitchingOut"))
        }
    }.getOrDefault(DepartureMarkerRead(readable = false))

    override fun isRetired(): Boolean = terminated.get()

    override fun freeze(uid: String): Boolean = synchronized(callbackLock) {
        if (terminated.get() || !RecoveryUidContract.isValid(uid)) false
        else if (accountFrozen && readMarker().marker?.departingUid == uid) true
        else if (accountFrozen) false
        else if (readMarker().marker == null && auth.currentUser?.uid != uid) false
        else if (readMarker().marker != null && auth.currentUser?.uid !in listOf(null, uid)) false
        else { accountFrozen = true; true }
    }

    override fun unfreeze(uid: String) { synchronized(callbackLock) { accountFrozen = false } }

    override fun hasProvenEmptyCriticalWork(uid: String): Boolean = runCatching {
        val envelope = recoveryStore.read(uid) ?: return@runCatching false
        envelope.schemaVersion == 1 && envelope.uid == uid && envelope.offlineAccessGrant == null &&
            envelope.downloadManifests.isEmpty() && envelope.workoutRecoverySnapshot == null &&
            envelope.mutationJournal.isEmpty() && envelope.pendingUploads.isEmpty() && envelope.switchMarker == null
    }.getOrDefault(false)

    override fun drain(timeoutMillis: Long, callback: (StableFirebaseFailure?) -> Unit): FirebaseCancellation =
        waitForPendingWrites(0L, timeoutMillis) { callback(it.failure) }

    override fun persistMarker(uid: String): Boolean = synchronized(identityPinLock) {
        runCatching {
            if (!accountFrozen || readMarker().marker != null || !RecoveryUidContract.isValid(uid)) return@runCatching false
            val epoch = identityPin.getLong("lastEpoch", 0L) + 1L
            if (epoch <= 0L) return@runCatching false
            identityPin.edit().putString("switchUid", uid).putLong("switchEpoch", epoch)
                .putLong("lastEpoch", epoch).commit()
        }.getOrDefault(false)
    }

    override fun fenceCallbacks() { fence.advance() }

    override fun signOut(callback: (StableFirebaseFailure?) -> Unit) {
        val failure = runCatching { auth.signOut() }.exceptionOrNull()
        callback(failure?.let { mapFailure(it as? Exception ?: Exception(it)) })
    }

    override fun retireFirestore(callback: (StableFirebaseFailure?) -> Unit) =
        terminateAndClear { result: FirebaseUnitResult -> callback(result.failure) }

    override fun cleanupLocal(uid: String): Boolean = runCatching {
        beforeDepartureLocalCleanupForTest?.invoke()
        val record = recoveryStore.read(uid)
        if (record != null && !hasProvenEmptyCriticalWork(uid)) return@runCatching false
        recoveryStore.delete(uid)
        recoveryStore.read(uid) == null
    }.getOrDefault(false)

    override fun completeMarker(uid: String): Boolean = synchronized(identityPinLock) {
        runCatching {
            if (readMarker().marker?.departingUid != uid) return@runCatching false
            val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val pin = identityPin.getString("uidSha256", null)
            if (pin != null && pin != digest) return@runCatching false
            val editor = identityPin.edit().remove("uidSha256")
            if (productMemoryCache) editor.putString("productAppName",
                "tillfailure-${configuration.projectId}-product-${UUID.randomUUID()}")
            if (!editor.commit()) return@runCatching false
            identityPin.edit().remove("switchUid").remove("switchEpoch").commit()
        }.getOrDefault(false)
    }

    override fun replacement(): ProductIdentityClient {
        val transient = if (productMemoryCache && readMarker().marker != null)
            "${generation.appName}-retry-${UUID.randomUUID()}" else null
        val fresh = AndroidFirebaseSpikeClient(
            applicationContext, configuration, AccountCallbackFence(), productMemoryCache, transient,
        )
        AndroidProductIdentitySession.replaceIfCurrent(this, fresh)
        return fresh
    }

    fun signInAnonymously(accountEpoch: Long, callback: (FirebaseUnitResult) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        val task = issueIfLive {
            if (accountFrozen) return@issueIfLive null
            auth.signOut()
            auth.signInAnonymously()
        } ?: run {
            callback(FirebaseUnitResult(StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false)))
            return FirebaseCancellation {}
        }
        task
            .addOnSuccessListener { deliverIfCurrent(accountEpoch, cancelled) { callback(FirebaseUnitResult()) } }
            .addOnFailureListener { error -> deliverIfCurrent(accountEpoch, cancelled) { callback(FirebaseUnitResult(mapFailure(error))) } }
        return FirebaseCancellation { cancelled.set(true) }
    }

    fun disableNetwork(callback: (FirebaseUnitResult) -> Unit) {
        val task = issueIfLive { firestore.disableNetwork() } ?: run {
            callback(FirebaseUnitResult(terminatedFailure()!!))
            return
        }
        task
            .addOnSuccessListener { deliverIfLive { callback(FirebaseUnitResult()) } }
            .addOnFailureListener { error -> deliverIfLive { callback(FirebaseUnitResult(mapFailure(error))) } }
    }

    fun enableNetwork(callback: (FirebaseUnitResult) -> Unit) {
        val task = issueIfLive { firestore.enableNetwork() } ?: run {
            callback(FirebaseUnitResult(terminatedFailure()!!))
            return
        }
        task
            .addOnSuccessListener { deliverIfLive { callback(FirebaseUnitResult()) } }
            .addOnFailureListener { error -> deliverIfLive { callback(FirebaseUnitResult(mapFailure(error))) } }
    }

    // Canonical shared counter contract: an integral value, a canonical signed integer string,
    // or a missing/null field starting from zero. Booleans, floating point, malformed strings,
    // and unsupported Firestore types are rejected instead of silently resetting the counter.
    private fun counterStartValue(stored: Any?): Long = when (stored) {
        null -> 0L
        is Long -> stored
        is Int -> stored.toLong()
        is Short -> stored.toLong()
        is Byte -> stored.toLong()
        is String -> CounterValueContract.parseCanonicalInt64(stored)
            ?: throw CounterValueException("Stored counter is not a canonical signed integer")
        is Boolean -> throw CounterValueException("Stored counter is a boolean")
        is Double, is Float -> throw CounterValueException("Stored counter is not an integer")
        else -> throw CounterValueException("Stored counter type is unsupported")
    }

    private fun deliverDocument(
        snapshot: DocumentSnapshot,
        epoch: Long,
        cancelled: AtomicBoolean,
        callback: (FirebaseDocumentResult) -> Unit,
    ) {
        deliverIfCurrent(epoch, cancelled) { callback(FirebaseDocumentResult(document = snapshot.toContract())) }
    }

    private fun deliverFailure(
        error: Exception,
        epoch: Long,
        cancelled: AtomicBoolean,
        callback: (FirebaseDocumentResult) -> Unit,
    ) {
        deliverIfCurrent(epoch, cancelled) { callback(FirebaseDocumentResult(failure = mapFailure(error))) }
    }

    private fun DocumentSnapshot.toContract() = FirebaseDocumentSnapshot(
        path = reference.path,
        fields = data.orEmpty().mapValues { (_, value) -> value?.toString().orEmpty() },
        exists = exists(),
        origin = if (metadata.isFromCache) FirebaseDataOrigin.CACHE else FirebaseDataOrigin.SERVER,
        hasPendingWrites = metadata.hasPendingWrites(),
    )

    private fun mapFailure(error: Exception): StableFirebaseFailure {
        if (error is FirebaseNetworkException) {
            return StableFirebaseFailure(StableFirebaseErrorCode.UNAVAILABLE, true)
        }
        if (error is FirebaseAuthException) {
            return when (error.errorCode) {
                "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_EMAIL", "ERROR_USER_NOT_FOUND" ->
                    StableFirebaseFailure(StableFirebaseErrorCode.INVALID_CREDENTIALS, false)
                "ERROR_USER_DISABLED", "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN" ->
                    StableFirebaseFailure(StableFirebaseErrorCode.UNAUTHENTICATED, false)
                else -> StableFirebaseFailure(StableFirebaseErrorCode.UNKNOWN, false)
            }
        }
        if (error is CounterValueException || error.cause is CounterValueException) {
            return StableFirebaseFailure(StableFirebaseErrorCode.INVALID_ARGUMENT, false)
        }
        val code = (error as? FirebaseFirestoreException)?.code
        return when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> StableFirebaseFailure(StableFirebaseErrorCode.PERMISSION_DENIED, false)
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> StableFirebaseFailure(StableFirebaseErrorCode.UNAUTHENTICATED, false)
            FirebaseFirestoreException.Code.UNAVAILABLE -> StableFirebaseFailure(StableFirebaseErrorCode.UNAVAILABLE, true)
            FirebaseFirestoreException.Code.CANCELLED -> StableFirebaseFailure(StableFirebaseErrorCode.CANCELLED, true)
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> StableFirebaseFailure(StableFirebaseErrorCode.DEADLINE_EXCEEDED, true)
            FirebaseFirestoreException.Code.ABORTED -> StableFirebaseFailure(StableFirebaseErrorCode.CONFLICT, true)
            FirebaseFirestoreException.Code.INVALID_ARGUMENT -> StableFirebaseFailure(StableFirebaseErrorCode.INVALID_ARGUMENT, false)
            FirebaseFirestoreException.Code.FAILED_PRECONDITION -> StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false)
            else -> StableFirebaseFailure(StableFirebaseErrorCode.UNKNOWN, false)
        }
    }
}
