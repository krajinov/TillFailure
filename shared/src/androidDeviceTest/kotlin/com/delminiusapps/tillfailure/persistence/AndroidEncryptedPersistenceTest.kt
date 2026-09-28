package com.delminiusapps.tillfailure.persistence

import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidEncryptedPersistenceTest {
    @Test
    fun overlappingOperationsSerializeByUidAndOwnTheirTemporaryFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val keyId = "$RECOVERY_KEY_IDENTIFIER.concurrent.$suffix"
        val uid = "concurrent_$suffix"
        val otherUid = "independent_$suffix"
        val thirdUid = "independent_success_$suffix"
        val first = AndroidAtomicFilePersistence(context, keyId)
        val second = AndroidAtomicFilePersistence(context, keyId)
        val pool = Executors.newFixedThreadPool(2)
        fun envelope(value: String, account: String = uid) = AccountPersistenceEnvelope(
            uid = account,
            mutationJournal = listOf(MutationJournalEntry(uid = account, workspaceId = "ws", operationId = "op", payload = value, state = "PendingSync")),
        )
        fun current(account: String = uid) = AndroidAtomicFilePersistence(context, keyId)
            .read(account)?.mutationJournal?.single()?.payload
        fun await(latch: CountDownLatch) = assertTrue(latch.await(20, TimeUnit.SECONDS))

        try {
            first.write(envelope("initial"))
            val initialBytes = first.fileFor(uid).readBytes()

            // A owns a fully written temporary file while B reaches the same partition lock.
            val aReady = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val bWaiting = CountDownLatch(1)
            first.beforeCommitForTest = { account, _ ->
                if (account == uid) { aReady.countDown(); await(releaseA) }
            }
            second.beforePartitionLockForTest = { if (it == uid) bWaiting.countDown() }
            val a = pool.submit { first.write(envelope("write-A")) }
            await(aReady)
            val b = pool.submit { second.write(envelope("write-B")) }
            await(bWaiting)
            try { assertTrue(initialBytes.contentEquals(first.fileFor(uid).readBytes())) } finally { releaseA.countDown() }
            a.get(20, TimeUnit.SECONDS)
            b.get(20, TimeUnit.SECONDS)
            assertEquals("write-B", current())
            first.beforeCommitForTest = null
            second.beforePartitionLockForTest = null

            // Write then delete is ordered; delete then write is ordered too.
            val writeReady = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val deleteWaiting = CountDownLatch(1)
            first.beforeCommitForTest = { _, _ -> writeReady.countDown(); await(releaseWrite) }
            second.beforePartitionLockForTest = { deleteWaiting.countDown() }
            val pendingWrite = pool.submit { first.write(envelope("before-delete")) }
            await(writeReady)
            val pendingDelete = pool.submit { second.delete(uid) }
            await(deleteWaiting)
            releaseWrite.countDown()
            pendingWrite.get(20, TimeUnit.SECONDS)
            pendingDelete.get(20, TimeUnit.SECONDS)
            assertNull(current())
            first.beforeCommitForTest = null
            second.beforePartitionLockForTest = null

            first.write(envelope("before-delete-write"))
            val deleteReady = CountDownLatch(1)
            val releaseDelete = CountDownLatch(1)
            val writeWaiting = CountDownLatch(1)
            first.beforeDeleteForTest = { deleteReady.countDown(); await(releaseDelete) }
            second.beforePartitionLockForTest = { writeWaiting.countDown() }
            val pendingDeleteFirst = pool.submit { first.delete(uid) }
            await(deleteReady)
            val pendingWriteSecond = pool.submit { second.write(envelope("after-delete")) }
            await(writeWaiting)
            releaseDelete.countDown()
            pendingDeleteFirst.get(20, TimeUnit.SECONDS)
            pendingWriteSecond.get(20, TimeUnit.SECONDS)
            assertEquals("after-delete", current())
            first.beforeDeleteForTest = null
            second.beforePartitionLockForTest = null

            // Different UIDs progress independently. B's failed write cleans only B's temp
            // while A's temp remains present and A's committed envelope is untouched.
            second.write(envelope("other-initial", otherUid))
            val beforeIndependent = first.fileFor(uid).readBytes()
            val independentReady = CountDownLatch(1)
            val releaseIndependent = CountDownLatch(1)
            val firstTemp = arrayOfNulls<java.io.File>(1)
            first.beforeCommitForTest = { _, temp ->
                firstTemp[0] = temp
                independentReady.countDown()
                await(releaseIndependent)
            }
            second.beforeCommitForTest = { account, _ ->
                if (account == otherUid) throw IllegalStateException("injected failure")
            }
            val pendingIndependent = pool.submit { first.write(envelope("after-overlap")) }
            await(independentReady)
            try {
                val failed = pool.submit {
                    assertFailsWith<RecoveryPersistenceLockedException> {
                        second.write(envelope("other-uncommitted", otherUid))
                    }
                }
                failed.get(20, TimeUnit.SECONDS)
                val independentSuccess = pool.submit { second.write(envelope("third-committed", thirdUid)) }
                independentSuccess.get(20, TimeUnit.SECONDS)
                assertTrue(firstTemp[0]?.exists() == true)
                assertTrue(beforeIndependent.contentEquals(first.fileFor(uid).readBytes()))
                assertEquals("other-initial", current(otherUid))
                assertEquals("third-committed", current(thirdUid))
            } finally { releaseIndependent.countDown() }
            pendingIndependent.get(20, TimeUnit.SECONDS)
            assertEquals("after-overlap", current())
            second.beforeCommitForTest = null
            first.beforeCommitForTest = null

            // A failed same-UID write also preserves the last committed bytes.
            val committed = first.fileFor(uid).readBytes()
            second.beforeCommitForTest = { _, _ -> throw IllegalStateException("injected failure") }
            assertFailsWith<RecoveryPersistenceLockedException> { second.write(envelope("not-committed")) }
            assertTrue(committed.contentEquals(first.fileFor(uid).readBytes()))
            val stale = java.io.File(first.rootDirectory, "${first.fileFor(uid).name}.stale.tmp")
            stale.writeText("stale and unreadable")
            assertEquals("after-overlap", current())
            assertTrue(stale.exists(), "A stale temp is not treated as a committed partition")
            assertEquals(1, first.rootDirectory.listFiles().orEmpty().count { it.name.endsWith(".tmp") })
            stale.delete()
        } finally {
            pool.shutdownNow()
            first.fileFor(uid).delete()
            first.fileFor(otherUid).delete()
            first.fileFor(thirdUid).delete()
            first.rootDirectory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            first.deleteKeyForTest()
        }
    }

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

    @Test
    fun acceptsTheCompleteFirebaseUidDomainWithDigestOnlyPartitions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val keyId = "$RECOVERY_KEY_IDENTIFIER.uid.$suffix"
        val store = AndroidAtomicFilePersistence(context, keyId)
        val emailUid = "user@example.com+$suffix"
        val distinctUid = "user+alias@example.com+$suffix"
        val punctuationUid = "a.b-c_d:e|f!g-$suffix"
        val unicodeUid = "üser-Ω-$suffix"
        val maxLengthUid = "u".repeat(RecoveryUidContract.MAX_UID_UTF16_LENGTH)
        val traversalUid = "../../etc/passwd-$suffix"
        val partitions = listOf(
            emailUid to "email",
            punctuationUid to "punctuation",
            unicodeUid to "unicode",
            maxLengthUid to "max-length",
            traversalUid to "traversal",
        )

        try {
            for ((uid, payload) in partitions) {
                store.write(
                    AccountPersistenceEnvelope(
                        uid = uid,
                        mutationJournal = listOf(
                            MutationJournalEntry(
                                uid = uid,
                                workspaceId = "ws",
                                operationId = "op",
                                payload = payload,
                                state = "PendingSync",
                            ),
                        ),
                    ),
                )
            }
            val restarted = AndroidAtomicFilePersistence(context, keyId)
            for ((uid, payload) in partitions) {
                assertEquals(payload, restarted.read(uid)?.mutationJournal?.single()?.payload)
            }

            // Distinct valid identifiers never collapse into one logical account.
            assertNotEquals(store.fileFor(emailUid).name, store.fileFor(distinctUid).name)
            assertNull(restarted.read(distinctUid))

            // Only the digest names the partition file, so traversal-like identifiers stay inside
            // the recovery root and can never escape it.
            val traversalFile = store.fileFor(traversalUid)
            assertTrue(Regex("^account-[0-9a-f]{64}-v$RECOVERY_ENCRYPTION_VERSION\\.json$").matches(traversalFile.name))
            assertEquals(store.rootDirectory.canonicalPath, traversalFile.parentFile?.canonicalPath)
            assertTrue(traversalFile.canonicalPath.startsWith(store.rootDirectory.canonicalPath))

            // Empty, blank, and overlength identifiers are rejected identically to iOS.
            assertFailsWith<IllegalArgumentException> { store.read("") }
            assertFailsWith<IllegalArgumentException> { store.write(AccountPersistenceEnvelope(uid = "")) }
            assertFailsWith<IllegalArgumentException> { store.read("   ") }
            assertFailsWith<IllegalArgumentException> { store.read("u".repeat(RecoveryUidContract.MAX_UID_UTF16_LENGTH + 1)) }

            // A partition copied under another UID cannot be decrypted: the UID remains
            // authenticated data and the envelope UID check is unchanged.
            store.fileFor(punctuationUid).writeBytes(store.fileFor(emailUid).readBytes())
            assertFailsWith<RecoveryPersistenceLockedException> { store.read(punctuationUid) }
        } finally {
            partitions.forEach { (uid, _) -> store.fileFor(uid).delete() }
            store.rootDirectory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            store.deleteKeyForTest()
        }
    }

    private fun nonce(raw: String): String =
        requireNotNull(Regex("\\\"nonce\\\":\\\"([^\\\"]+)\\\"").find(raw)?.groupValues?.get(1))
}
