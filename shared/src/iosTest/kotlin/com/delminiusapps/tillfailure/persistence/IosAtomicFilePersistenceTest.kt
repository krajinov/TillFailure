package com.delminiusapps.tillfailure.persistence

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalForeignApi::class)
class IosAtomicFilePersistenceTest {
    @Test
    fun accountPartitionSurvivesRecreationAndDeletesOnlyItsOwnEnvelope() {
        val root = "${NSTemporaryDirectory()}tillfailure-${NSUUID().UUIDString}"
        try {
            val firstStore = IosAtomicFilePersistence(root)
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

            val restartedStore = IosAtomicFilePersistence(root)
            assertEquals(first, restartedStore.read("uid_one"))
            assertEquals(second, restartedStore.read("uid_two"))
            restartedStore.delete("uid_one")
            assertNull(restartedStore.read("uid_one"))
            assertEquals(second, restartedStore.read("uid_two"))
            assertFalse(NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, error = null).orEmpty().any {
                (it as String).endsWith(".tmp")
            })
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }
}
