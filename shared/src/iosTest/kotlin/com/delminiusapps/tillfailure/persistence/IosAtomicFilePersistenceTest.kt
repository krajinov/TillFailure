package com.delminiusapps.tillfailure.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IosAtomicFilePersistenceTest {
    @Test
    fun nativeBridgeRoundTripsPartitionsAndPropagatesLockedRecovery() {
        val bridge = FakeNativeRecoveryBridge()
        val firstStore = IosAtomicFilePersistence(bridge)
        val first = AccountPersistenceEnvelope(
            uid = "uid_one",
            offlineAccessGrant = OfflineAccessGrant(
                uid = "uid_one",
                workspaceId = "ws",
                membershipRevision = 4,
                verifiedAtEpochMillis = 10,
                expiresAtEpochMillis = 20,
            ),
            mutationJournal = listOf(
                MutationJournalEntry(
                    uid = "uid_one",
                    workspaceId = "ws",
                    operationId = "op",
                    payload = "local-recovery",
                    state = "PendingSync",
                ),
            ),
            switchMarker = AccountSwitchMarker(
                departingUid = "uid_one",
                accountEpoch = 7,
                state = "SwitchingOut",
            ),
        )
        val second = AccountPersistenceEnvelope(uid = "uid_two")
        firstStore.write(first)
        firstStore.write(second)

        val restartedStore = IosAtomicFilePersistence(bridge)
        assertEquals(first, restartedStore.read("uid_one"))
        assertEquals(second, restartedStore.read("uid_two"))
        restartedStore.delete("uid_one")
        assertNull(restartedStore.read("uid_one"))
        assertEquals(second, restartedStore.read("uid_two"))

        bridge.failureCode = "LOCKED"
        assertFailsWith<RecoveryPersistenceLockedException> { restartedStore.read("uid_two") }
        assertFailsWith<RecoveryPersistenceLockedException> { restartedStore.delete("uid_two") }
    }

    private class FakeNativeRecoveryBridge : NativeRecoveryPersistenceBridge {
        override val keyIdentifier = RECOVERY_KEY_IDENTIFIER
        override val encryptionVersion = RECOVERY_ENCRYPTION_VERSION
        private val records = mutableMapOf<String, String>()
        var failureCode: String? = null

        override fun write(uid: String, plaintext: String): NativeRecoveryPersistenceResult {
            failureCode?.let { return NativeRecoveryPersistenceResult(null, it) }
            records[uid] = plaintext
            return NativeRecoveryPersistenceResult(null, null)
        }

        override fun read(uid: String): NativeRecoveryPersistenceResult =
            failureCode?.let { NativeRecoveryPersistenceResult(null, it) }
                ?: NativeRecoveryPersistenceResult(records[uid], null)

        override fun delete(uid: String): NativeRecoveryPersistenceResult {
            failureCode?.let { return NativeRecoveryPersistenceResult(null, it) }
            records.remove(uid)
            return NativeRecoveryPersistenceResult(null, null)
        }
    }
}
