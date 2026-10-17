package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.persistence.*
import kotlin.test.*

class RecoveryHardeningTest {
    private class Store : AccountPersistencePrototype {
        var record: AccountPersistenceEnvelope? = AccountPersistenceEnvelope(uid = "A")
        var unreadable = false
        var failWrites = false
        var writes = 0
        override fun read(uid: String): AccountPersistenceEnvelope? {
            if (unreadable) throw RecoveryPersistenceLockedException("test damage")
            return record
        }
        override fun write(envelope: AccountPersistenceEnvelope) {
            if (failWrites) throw RecoveryPersistenceLockedException("test storage failure")
            record = envelope; writes++
        }
        override fun delete(uid: String) = error("Recovery must not delete")
    }
    private val request = InvitationRequest("create", workspaceId = "w", email = "client@example.invalid", idempotencyKey = "original")
    private val receipt = InvitationOutcome(reply = InvitationReply(invitationId = "a".repeat(64), workspaceId = "w", status = "pending",
        tokenVersion = 1, expiresAtMillis = 1000, replayed = true))

    @Test fun absentReceiptAndRepeatedAuthorityOrServiceFailuresRetainOriginalAcrossRecreation() {
        val store = Store(); var ledger = InvitationLedger(store)
        assertTrue(ledger.prepare("A", request))
        val original = store.record
        for (error in listOf("unauthorized", "unauthenticated", "rate-limited", "invitation-unavailable", "record-invalid", "unavailable")) {
            ledger = InvitationLedger(store)
            val result = ledger.settle("A", request, InvitationOutcome(error = error))
            assertTrue(result.ambiguous)
            assertEquals(original, store.record)
            assertEquals(request.pending("A"), ledger.pending("A"))
            assertFalse(ledger.prepare("A", request.copy(idempotencyKey = "replacement")))
        }
        assertEquals(ProtectedRecoveryState.PendingCommand, ledger.recoveryState("A"))
        assertNull(ledger.settle("A", request, receipt).error)
        assertEquals(ProtectedRecoveryState.Clean, ledger.recoveryState("A"))
    }

    @Test fun ambiguousSuccessNeverLeaksAReplyOrConsumesTheRecoveryLink() {
        val store = Store(); val ledger = InvitationLedger(store)
        assertTrue(ledger.prepare("A", request))
        val result = ledger.settle("A", request, receipt.copy(ambiguous = true))
        assertNull(result.reply)
        assertNotNull(ledger.pending("A"))
        val json = InvitationJson.format.encodeToString(InvitationReply.serializer(), receipt.reply!!)
        val decoded = InvitationJson.outcome(request, json, error = null, ambiguous = true)
        assertTrue(decoded.ambiguous)
        assertNull(decoded.reply)
        assertNull(ledger.settle("A", request, decoded).reply)
        assertNotNull(ledger.pending("A"))
    }

    @Test fun mutationsWithoutCommandIdentityNeverPassPreparation() {
        val store = Store(); val ledger = InvitationLedger(store)
        for (action in listOf("create", "accept", "resend", "revoke", "complete-onboarding", "future")) {
            assertFalse(ledger.prepare("A", request.copy(action = action, idempotencyKey = null)))
        }
        assertEquals(0, store.writes)
        assertEquals(ProtectedRecoveryState.Clean, ledger.recoveryState("A"))
        assertTrue(ledger.prepare("A", InvitationRequest("list", workspaceId = "w")))
        assertEquals(0, store.writes)
    }

    @Test fun failedSettlementIsAStorageFailureAndNeverErasesTheCommand() {
        val store = Store(); val ledger = InvitationLedger(store)
        assertTrue(ledger.prepare("A", request)); val original = store.record
        store.failWrites = true
        assertEquals("storage-unavailable", ledger.settle("A", request, receipt).error)
        assertEquals(original, store.record)
        store.failWrites = false
        assertEquals(receipt, ledger.settle("A", request, receipt))
        assertNull(ledger.pending("A"))
    }

    @Test fun missingUnreadableAndInvalidCommandsCannotBecomeAnEmptyLedgerOrBeOverwritten() {
        val invalid = listOf(request.pending("A")!!.copy(action = "future"), request.pending("A")!!.copy(workspaceId = "../other"),
            request.pending("A")!!.copy(onboarding = com.delminiusapps.tillfailure.onboarding.OnboardingCompletion("X", "en", "UTC", true, 0, 0)),
            PendingInvitationCommand("A", "accept", "key", invitationId = "bad", expectedVersion = 1))
        for (command in invalid) {
            val store = Store(); store.record = AccountPersistenceEnvelope(uid = "A", pendingInvitationCommands = listOf(command))
            val ledger = InvitationLedger(store); val original = store.record
            assertEquals(ProtectedRecoveryState.StorageLocked, ledger.recoveryState("A"))
            assertFails { ledger.pending("A") }
            assertFalse(ledger.prepare("A", request))
            assertEquals("storage-unavailable", ledger.settle("A", request, receipt).error)
            assertEquals(original, store.record); assertEquals(0, store.writes)
        }
        for (missing in listOf(true, false)) {
            val store = Store().apply { if (missing) record = null else unreadable = true }
            val ledger = InvitationLedger(store)
            assertEquals(ProtectedRecoveryState.StorageLocked, ledger.recoveryState("A"))
            assertFails { ledger.pending("A") }; assertFalse(ledger.prepare("A", request))
            assertEquals(0, store.writes)
        }
    }

    @Test fun incompatibleAndPartiallyWrittenEnvelopesFailClosed() {
        val valid = PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(), AccountPersistenceEnvelope(uid = "A"))
        for (payload in listOf(valid.replace("\"schemaVersion\":1,", ""), valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), valid.dropLast(1), valid.replace("\"uid\":\"A\"", "\"uid\":null"))) {
            assertFails { PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(), payload) }
        }
        val store = Store().apply { record = AccountPersistenceEnvelope(uid = "A", mutationJournal = listOf(
            MutationJournalEntry(uid = "A", workspaceId = "w", operationId = "op", payload = "retained", state = "PendingSync"))) }
        assertEquals(ProtectedRecoveryState.ReconciliationRequired, InvitationLedger(store).recoveryState("A"))
        assertEquals(0, store.writes)
    }

    @Test fun priorEnvelopeShapesRemainReadableWithoutMigrationOrLosingCommands() {
        // Literal prior-writer payloads: schemaVersion was emitted by encodeDefaults=true.
        val pr1 = """{"schemaVersion":1,"uid":"A","offlineAccessGrant":null,"downloadManifests":[],"workoutRecoverySnapshot":null,"mutationJournal":[],"pendingUploads":[],"switchMarker":null}"""
        val pr3 = pr1.dropLast(1) + """, "pendingInvitationCommands":[{"uid":"A","action":"accept","idempotencyKey":"original","workspaceId":null,"invitationId":"${"a".repeat(64)}","expectedVersion":1,"email":null}]}"""
        val pr4 = pr1.dropLast(1) + """, "preferredWorkspaceId":"w","pendingInvitationCommands":[{"uid":"A","action":"complete-onboarding","idempotencyKey":"original","workspaceId":"w","invitationId":null,"expectedVersion":null,"email":null,"onboarding":{"displayName":"Client","locale":"en","timeZoneId":"UTC","acknowledged":true,"expectedAccountRevision":0,"expectedOnboardingRevision":0}}]}"""
        for ((payload, expected) in listOf(pr1 to ProtectedRecoveryState.Clean, pr3 to ProtectedRecoveryState.PendingCommand, pr4 to ProtectedRecoveryState.PendingCommand)) {
            val store = Store().apply { record = PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(), payload) }
            val original = store.record
            val ledger = InvitationLedger(store)
            assertEquals(expected, ledger.recoveryState("A"))
            if (expected == ProtectedRecoveryState.PendingCommand) {
                assertEquals("original", ledger.pending("A")?.idempotencyKey)
                assertEquals("original", InvitationLedger(store).pending("A")?.idempotencyKey)
            }
            assertEquals(original, store.record)
            assertEquals(0, store.writes)
        }
    }

    @Test fun priorHighestRepresentableInvitationVersionsRemainRecoverable() {
        for (action in listOf("accept", "revoke", "resend")) {
            val store = Store(); val ledger = InvitationLedger(store)
            val request = InvitationRequest(action, workspaceId = if (action == "accept") null else "w", invitationId = "a".repeat(64),
                expectedVersion = if (action == "resend") Int.MAX_VALUE - 1 else Int.MAX_VALUE, idempotencyKey = "original")
            store.record = AccountPersistenceEnvelope(uid = "A", pendingInvitationCommands = listOf(request.pending("A")!!))
            assertEquals(ProtectedRecoveryState.PendingCommand, ledger.recoveryState("A"))
            assertTrue(ledger.prepare("A", request))
            val receipt = InvitationOutcome(reply = InvitationReply(invitationId = request.invitationId, workspaceId = "w", tokenVersion = Int.MAX_VALUE,
                status = when (action) { "accept" -> "accepted"; "revoke" -> "revoked"; else -> "pending" }, expiresAtMillis = 1000, replayed = true))
            assertEquals(receipt, ledger.settle("A", request, receipt))
            assertNull(ledger.pending("A"))
        }
    }
}
