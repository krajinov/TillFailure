package com.delminiusapps.tillfailure.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
    private val app: FirebaseApp
    private val auth: FirebaseAuth
    private val firestore: FirebaseFirestore

    init {
        val appName = "tillfailure-${configuration.projectId}"
        app = FirebaseApp.getApps(context).firstOrNull { it.name == appName } ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(configuration.projectId)
                .setApplicationId("1:1234567890:android:0000000000000000")
                .setApiKey("fake-emulator-api-key")
                .setStorageBucket("${configuration.projectId}.appspot.com")
                .build(),
            appName,
        )
        auth = FirebaseAuth.getInstance(app).also { it.useEmulator(configuration.host, configuration.authPort) }
        firestore = FirebaseFirestore.getInstance(app).also { it.useEmulator(configuration.host, configuration.firestorePort) }
    }

    override fun observeSession(accountEpoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation {
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
        val cancelled = AtomicBoolean(false)
        firestore.document(path).get(Source.SERVER)
            .addOnSuccessListener { snapshot -> deliverDocument(snapshot, accountEpoch, cancelled, callback) }
            .addOnFailureListener { error -> deliverFailure(error, accountEpoch, cancelled, callback) }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun listenDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation {
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
        val cancelled = AtomicBoolean(false)
        val reference = firestore.document(path)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(reference)
            val current = when (val stored = snapshot.get(field)) {
                is Number -> stored.toLong()
                is String -> stored.toLongOrNull() ?: 0L
                else -> 0L
            }
            val next = current + by
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

    override fun waitForPendingWrites(accountEpoch: Long, callback: (FirebaseUnitResult) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        firestore.waitForPendingWrites()
            .addOnSuccessListener { if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult()) }
            .addOnFailureListener { error -> if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult(mapFailure(error))) }
        return FirebaseCancellation { cancelled.set(true) }
    }

    override fun terminateAndClear(callback: (FirebaseUnitResult) -> Unit) {
        firestore.terminate()
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Firestore termination failed")
                firestore.clearPersistence()
            }
            .addOnSuccessListener { callback(FirebaseUnitResult()) }
            .addOnFailureListener { callback(FirebaseUnitResult(mapFailure(it))) }
    }

    fun signInAnonymously(accountEpoch: Long, callback: (FirebaseUnitResult) -> Unit): FirebaseCancellation {
        val cancelled = AtomicBoolean(false)
        auth.signInAnonymously()
            .addOnSuccessListener { if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult()) }
            .addOnFailureListener { error -> if (!cancelled.get() && fence.accepts(accountEpoch)) callback(FirebaseUnitResult(mapFailure(error))) }
        return FirebaseCancellation { cancelled.set(true) }
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
        val code = (error as? FirebaseFirestoreException)?.code
        return when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> StableFirebaseFailure(StableFirebaseErrorCode.PERMISSION_DENIED, false)
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> StableFirebaseFailure(StableFirebaseErrorCode.UNAUTHENTICATED, false)
            FirebaseFirestoreException.Code.UNAVAILABLE -> StableFirebaseFailure(StableFirebaseErrorCode.UNAVAILABLE, true)
            FirebaseFirestoreException.Code.CANCELLED -> StableFirebaseFailure(StableFirebaseErrorCode.CANCELLED, true)
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> StableFirebaseFailure(StableFirebaseErrorCode.DEADLINE_EXCEEDED, true)
            FirebaseFirestoreException.Code.ABORTED -> StableFirebaseFailure(StableFirebaseErrorCode.CONFLICT, true)
            FirebaseFirestoreException.Code.INVALID_ARGUMENT -> StableFirebaseFailure(StableFirebaseErrorCode.INVALID_ARGUMENT, false)
            else -> StableFirebaseFailure(StableFirebaseErrorCode.UNKNOWN, false)
        }
    }
}
