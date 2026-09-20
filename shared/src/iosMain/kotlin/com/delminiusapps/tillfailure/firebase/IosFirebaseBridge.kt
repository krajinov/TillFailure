package com.delminiusapps.tillfailure.firebase

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
    fun waitForPendingWrites(accountEpoch: Long, callback: (NativeFirebaseUnitResult) -> Unit): String
    fun cancel(token: String)
    fun terminateAndClear(callback: (NativeFirebaseUnitResult) -> Unit)
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

    override fun waitForPendingWrites(accountEpoch: Long, callback: (FirebaseUnitResult) -> Unit): FirebaseCancellation {
        val token = bridge.waitForPendingWrites(accountEpoch) { callback(it.toContract()) }
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
