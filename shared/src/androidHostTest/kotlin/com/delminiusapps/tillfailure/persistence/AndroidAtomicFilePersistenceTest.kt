package com.delminiusapps.tillfailure.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AndroidAtomicFilePersistenceTest {
    @Test
    fun accountPartitionSurvivesRecreationAndDeletesOnlyItsOwnEnvelope() {
        val root = Files.createTempDirectory("tillfailure-persistence-test").toFile()
        val firstStore = AndroidAtomicFilePersistence(root)
        val first = AccountPersistenceEnvelope(
            uid = "uid_one",
            offlineAccessGrant = OfflineAccessGrant(uid = "uid_one", workspaceId = "ws", membershipRevision = 4, verifiedAtEpochMillis = 10, expiresAtEpochMillis = 20),
            mutationJournal = listOf(MutationJournalEntry(uid = "uid_one", workspaceId = "ws", operationId = "op", payload = "local-recovery", state = "PendingSync")),
            switchMarker = AccountSwitchMarker(departingUid = "uid_one", accountEpoch = 7, state = "SwitchingOut"),
        )
        val second = AccountPersistenceEnvelope(uid = "uid_two")
        firstStore.write(first)
        firstStore.write(second)

        val restartedStore = AndroidAtomicFilePersistence(root)
        assertEquals(first, restartedStore.read("uid_one"))
        assertEquals(second, restartedStore.read("uid_two"))
        restartedStore.delete("uid_one")
        assertNull(restartedStore.read("uid_one"))
        assertEquals(second, restartedStore.read("uid_two"))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }
}
