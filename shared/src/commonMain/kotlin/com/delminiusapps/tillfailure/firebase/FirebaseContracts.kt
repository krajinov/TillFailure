package com.delminiusapps.tillfailure.firebase

data class FirebaseEmulatorConfiguration(
    val projectId: String = "demo-tillfailure-m3",
    val host: String,
    val authPort: Int = 9099,
    val firestorePort: Int = 8080,
    val storagePort: Int = 9199,
) {
    init {
        require(projectId.startsWith("demo-")) { "Firebase spike project ID must use the emulator-only demo- prefix" }
        require(host == "127.0.0.1" || host == "10.0.2.2" || host == "localhost") {
            "Firebase spike host must be loopback or the Android emulator loopback alias"
        }
    }
}

enum class FirebaseDataOrigin {
    CACHE,
    SERVER,
}

enum class StableFirebaseErrorCode {
    PERMISSION_DENIED,
    UNAUTHENTICATED,
    UNAVAILABLE,
    CANCELLED,
    DEADLINE_EXCEEDED,
    CONFLICT,
    INVALID_ARGUMENT,

    // The client instance can no longer serve requests: it was terminated (or the SDK reports
    // FAILED_PRECONDITION for the operation). Callers must construct a new client, which obtains a
    // fresh SDK instance, instead of retrying against the terminated one.
    FAILED_PRECONDITION,
    UNKNOWN,
}

data class StableFirebaseFailure(
    val code: StableFirebaseErrorCode,
    val retryable: Boolean,
)

data class FirebaseAuthSession(
    val uid: String?,
    val isAnonymous: Boolean,
)

data class FirebaseDocumentSnapshot(
    val path: String,
    val fields: Map<String, String>,
    val exists: Boolean,
    val origin: FirebaseDataOrigin,
    val hasPendingWrites: Boolean,
)

data class FirebaseDocumentResult(
    val document: FirebaseDocumentSnapshot? = null,
    val failure: StableFirebaseFailure? = null,
) {
    init {
        require((document == null) != (failure == null)) { "Exactly one result branch is required" }
    }
}

data class FirebaseUnitResult(
    val failure: StableFirebaseFailure? = null,
) {
    val isSuccess: Boolean get() = failure == null
}

fun interface FirebaseCancellation {
    fun cancel()
}

const val DEFAULT_PENDING_WRITE_TIMEOUT_MILLIS = 15_000L

interface FirebaseSpikeClient {
    fun observeSession(accountEpoch: Long, callback: (FirebaseAuthSession) -> Unit): FirebaseCancellation
    fun getDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation
    fun listenDocument(path: String, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation
    fun writeDocument(path: String, fields: Map<String, String>, accountEpoch: Long, callback: (FirebaseUnitResult) -> Unit): FirebaseCancellation
    fun increment(path: String, field: String, by: Long, accountEpoch: Long, callback: (FirebaseDocumentResult) -> Unit): FirebaseCancellation
    fun waitForPendingWrites(
        accountEpoch: Long,
        timeoutMillis: Long = DEFAULT_PENDING_WRITE_TIMEOUT_MILLIS,
        callback: (FirebaseUnitResult) -> Unit,
    ): FirebaseCancellation
    fun terminateAndClear(callback: (FirebaseUnitResult) -> Unit)
}

class AccountCallbackFence(initialEpoch: Long = 0L) {
    private var currentEpoch = initialEpoch
    private var disposed = false

    fun advance(): Long {
        currentEpoch += 1
        disposed = false
        return currentEpoch
    }

    fun dispose() {
        disposed = true
    }

    fun accepts(epoch: Long): Boolean = !disposed && epoch == currentEpoch
}
