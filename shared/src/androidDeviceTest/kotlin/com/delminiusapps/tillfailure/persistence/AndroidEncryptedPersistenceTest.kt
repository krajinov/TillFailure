package com.delminiusapps.tillfailure.persistence

import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidEncryptedPersistenceTest {
    @Test
    fun keystoreEnvelopeFailsClosedAndPreservesAtomicUidPartitions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val keyId = "$RECOVERY_KEY_IDENTIFIER.test.$suffix"
        val firstUid = "uid_one_$suffix"
        val secondUid = "uid_two_$suffix"
        val store = AndroidAtomicFilePersistence(context, keyId)
        val first = AccountPersistenceEnvelope(
            uid = firstUid,
            mutationJournal = listOf(
                MutationJournalEntry(
                    uid = firstUid,
                    workspaceId = "ws",
                    operationId = "op",
                    payload = "plaintext-recovery-fragment",
                    state = "PendingSync",
                ),
            ),
        )
        val second = AccountPersistenceEnvelope(uid = secondUid)

        try {
            store.write(first)
            val firstRaw = store.fileFor(firstUid).readText()
            val firstNonce = nonce(firstRaw)
            assertTrue(firstRaw.contains("\"encryptionVersion\":$RECOVERY_ENCRYPTION_VERSION"))
            assertTrue(firstRaw.contains("\"keyIdentifier\":\"$keyId\""))
            assertFalse(firstRaw.contains("plaintext-recovery-fragment"))
            assertTrue(store.rootDirectory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
            val storedKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(keyId, null)
            assertNull(storedKey.encoded, "Android Keystore key must remain non-exportable")

            store.write(first)
            val secondRaw = store.fileFor(firstUid).readText()
            assertNotEquals(firstNonce, nonce(secondRaw), "Every write must use a unique GCM nonce")

            store.write(second)
            val restarted = AndroidAtomicFilePersistence(context, keyId)
            assertEquals(first, restarted.read(firstUid))
            assertEquals(second, restarted.read(secondUid))
            assertFalse(store.rootDirectory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })

            val wrongKeyStore = AndroidAtomicFilePersistence(context, "$keyId.wrong")
            assertFailsWith<RecoveryPersistenceLockedException> { wrongKeyStore.read(firstUid) }

            val validRaw = store.fileFor(firstUid).readText()
            val tampered = validRaw.toByteArray().also { bytes -> bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte() }
            store.fileFor(firstUid).writeBytes(tampered)
            assertFailsWith<RecoveryPersistenceLockedException> { store.read(firstUid) }
            assertFailsWith<RecoveryPersistenceLockedException> { store.delete(firstUid) }
            assertTrue(store.fileFor(firstUid).exists(), "Unreadable critical recovery data must not be silently deleted")
            assertFailsWith<RecoveryPersistenceLockedException> { store.write(first) }

            store.fileFor(firstUid).writeText(validRaw)
            store.deleteKeyForTest()
            assertFailsWith<RecoveryPersistenceLockedException> { store.read(firstUid) }
            assertFailsWith<RecoveryPersistenceLockedException> { store.read(secondUid) }
            assertTrue(store.fileFor(firstUid).exists())
            assertTrue(store.fileFor(secondUid).exists())
        } finally {
            store.fileFor(firstUid).delete()
            store.fileFor(secondUid).delete()
            store.rootDirectory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            store.deleteKeyForTest()
            AndroidAtomicFilePersistence(context, "$keyId.wrong").deleteKeyForTest()
        }
    }

    private fun nonce(raw: String): String =
        requireNotNull(Regex("\\\"nonce\\\":\\\"([^\\\"]+)\\\"").find(raw)?.groupValues?.get(1))
}
