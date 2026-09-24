package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.concurrent.atomic.AtomicBoolean

class AndroidFirebaseSpikeClient(
    context: Context,
    private val configuration: FirebaseEmulatorConfiguration,
    private val fence: AccountCallbackFence,
) : FirebaseSpikeClient {
    private val generation: AndroidFirebaseAppRegistry.Generation
    private val app: FirebaseApp
    private val auth: FirebaseAuth
    private val firestore: FirebaseFirestore
    private val terminated = AtomicBoolean(false)

    init {
        // One generation per project: a client constructed after a completed teardown receives fresh
        // SDK instances instead of the terminated Firestore singleton its predecessor retired.
        generation = AndroidFirebaseAppRegistry.acquire(context, configuration.projectId)
        app = generation.app
        auth = FirebaseAuth.getInstance(app)
        firestore = FirebaseFirestore.getInstance(app)
        if (generation.claimEmulatorConfiguration()) {
            auth.useEmulator(configuration.host, configuration.authPort)
            firestore.useEmulator(configuration.host, configuration.firestorePort)
        }
    }

    /**
     * Fail-closed guard for every operation: once this client's teardown has run it must never touch
     * the SDK again, and callers receive an explicit non-retryable failure instead of silence or a
     * callback that could be mistaken for a newer generation's result.
     */
    private fun terminatedFailure(): StableFirebaseFailure? =
        if (terminated.get()) StableFirebaseFailure(StableFirebaseErrorCode.FAILED_PRECONDITION, false) else null

    /**
     * The named SDK app this client acquired. Device tests use it to seed or inspect fixtures on the
     * same instance the client operates on, which is required now that every client acquires its own
     * app generation instead of a process-wide fixed name.
     */
    internal val firebaseApp: FirebaseApp get() = app

    override fun observeSession(accountEpoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation {
        if (terminatedFailure() != null) return FirebaseCancellation {}
        val cancelled = AtomicBoolean(false)
        val listener = FirebaseAuth.AuthStateListener { observed ->
            if (!cancelled.get() && fence.accepts(accountEpoch)) {
                callback(FirebaseAuthSession(observed.currentUser?.uid, observed.currentUser?.isAnonymous == true))
            }
        }
        auth.addAuthStateListener(listener)
        return FirebaseCancellation {
            if (cancelled.compareAndSet(false, true)) auth.removeAuthStateListener(listener)
        }
    }

    override fun getDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
        terminatedFailure()?.let { failure ->
            callback(FirebaseDocumentResult(failure = failure))
            return FirebaseCancellation {}
        }
        val cancelled = AtomicBoolean(false)
        firestore.document(path).get(Source.SERVER)
            .addOnSuccessListener { snapshot -> deliverDocument(snapshot, accountEpoch, cancelled, callback) }
            .addOnFailureListener { error -> deliverFailure(error, accountEpoch, cancelled, callback) }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun listenDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
        if (terminatedFailure() != null) return FirebaseCancellation {}
        val cancelled = AtomicBoolean(false)
        val registration = firestore.document(path).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                cancelled.get() || !fence.accepts(accountEpoch) -> Unit
                error != null -> callback(FirebaseDocumentResult(failure = mapFailure(error)))
                snapshot != null -> callback(FirebaseDocumentResult(document = snapshot.toContract()))
            }
        }
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
        terminatedFailure()?.let { failure ->
            callback(FirebaseUnitResult(failure))
            return FirebaseCancellation {}
        }
        val cancelled = AtomicBoolean(false)
        firestore.document(path).set(fields)
            .addOnSuccessListener { if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult()) }
            .addOnFailureListener { error -> if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult(mapFailure(error))) }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun increment(
        path: String,
        field: String,
        by: Long,
        accountEpoch: Long,
        callback: (FirebaseDocumentResult) -> Unit,
    ): FirebaseCancellation {
        terminatedFailure()?.let { failure ->
            callback(FirebaseDocumentResult(failure = failure))
            return FirebaseCancellation {}
        }
        val cancelled = AtomicBoolean(false)
        val reference = firestore.document(path)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(reference)
            val current = counterStartValue(snapshot.get(field))
            val next = CounterValueContract.addExact(current, by)
                ?: throw CounterValueException("Counter increment overflow for '$field'")
            transaction.set(reference, mapOf(field to next), SetOptions.merge())
            next
        }.addOnSuccessListener {
            if (!cancelled.get() && fence.accepts(accountEpoch)) {
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
        terminatedFailure()?.let { failure ->
            callback(FirebaseUnitResult(failure))
            return FirebaseCancellation {}
        }
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        fun finish(result: FirebaseUnitResult) {
            if (completed.compareAndSet(false, true) && fence.accepts(accountEpoch)) callback(result)
        }
        val timeout = Runnable {
            finish(FirebaseUnitResult(StableFirebaseFailure(StableFirebaseErrorCode.DEADLINE_EXCEEDED, true)))
        }
        handler.postDelayed(timeout, timeoutMillis)
        firestore.waitForPendingWrites()
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
        // Close the generation before touching the SDK: a client constructed concurrently with (or
        // after) this teardown must never be handed these instances, and this client is fenced at once.
        AndroidFirebaseAppRegistry.close(generation)
        val firstTermination = terminated.compareAndSet(false, true)
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

    fun signInAnonymously(accountEpoch: Long, callback: (FirebaseUnitResult) -> Unit): FirebaseCancellation {
        terminatedFailure()?.let { failure ->
            callback(FirebaseUnitResult(failure))
            return FirebaseCancellation {}
        }
        val cancelled = AtomicBoolean(false)
        auth.signOut()
        auth.signInAnonymously()
            .addOnSuccessListener { if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult()) }
            .addOnFailureListener { error -> if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult(mapFailure(error))) }
        return FirebaseCancellation { cancelled.set(true) }
    }

    fun disableNetwork(callback: (FirebaseUnitResult) -> Unit) {
        terminatedFailure()?.let { failure ->
            callback(FirebaseUnitResult(failure))
            return
        }
        firestore.disableNetwork()
            .addOnSuccessListener { callback(FirebaseUnitResult()) }
            .addOnFailureListener { callback(FirebaseUnitResult(mapFailure(it))) }
    }

    fun enableNetwork(callback: (FirebaseUnitResult) -> Unit) {
        terminatedFailure()?.let { failure ->
            callback(FirebaseUnitResult(failure))
            return
        }
        firestore.enableNetwork()
            .addOnSuccessListener { callback(FirebaseUnitResult()) }
            .addOnFailureListener { callback(FirebaseUnitResult(mapFailure(it))) }
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
        if (!cancelled.get() && fence.accepts(epoch)) callback(FirebaseDocumentResult(document = snapshot.toContract()))
    }

    private fun deliverFailure(
        error: Exception,
        epoch: Long,
        cancelled: AtomicBoolean,
        callback: (FirebaseDocumentResult) -> Unit,
    ) {
        if (!cancelled.get() && fence.accepts(epoch)) callback(FirebaseDocumentResult(failure = mapFailure(error)))
    }

    private fun DocumentSnapshot.toContract() = FirebaseDocumentSnapshot(
        path = reference.path,
        fields = data.orEmpty().mapValues { (_, value) -> value?.toString().orEmpty() },
        exists = exists(),
        origin = if (metadata.isFromCache) FirebaseDataOrigin.CACHE else FirebaseDataOrigin.SERVER,
        hasPendingWrites = metadata.hasPendingWrites(),
    )

    private fun mapFailure(error: Exception): StableFirebaseFailure {
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
