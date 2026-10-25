package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.profile.ProfileUpdate
import kotlin.test.*

class SettledCommandMarkerTest {
    private val request=InvitationRequest("update-profile",workspaceId="w",idempotencyKey="key",profileUpdate=ProfileUpdate("Synthetic private value","en","UTC",2))
    private fun proof()=CommandTerminalProof(request.pending("A")!!,"normal-v1",acknowledgment=InvitationReply(workspaceId="w",status="profile-updated",accountRevision=3,profileCommandKey="key",replayed=false))
    @Test fun compactedRecordIsContentFreeAndRejectsSameKeyAcrossKindsAfterRestart() {
        val marker=proof().compacted()
        val encoded=PersistenceJson.format.encodeToString(CommandTerminalProof.serializer(),marker)
        assertFalse(encoded.contains("Synthetic"));assertFalse(encoded.contains("profileUpdate"));assertFalse(encoded.contains("\"key\""))
        val restored=PersistenceJson.format.decodeFromString(CommandTerminalProof.serializer(),encoded)
        assertTrue(restored.ownedBy("A"));assertFalse(restored.ownedBy("B"));assertNull(restored.command)
        val store=object:AccountPersistencePrototype {
            var record=AccountPersistenceEnvelope(uid="A",lastSettledCommand=restored)
            override fun read(uid:String)=record.also { require(it.uid==uid) }
            override fun write(envelope:AccountPersistenceEnvelope) { record=envelope }
            override fun delete(uid:String)=error("No cleanup")
        }
        val ledger=InvitationLedger(store)
        assertEquals(ProtectedRecoveryState.Clean,ledger.recoveryState("A"))
        assertFalse(ledger.prepare("A",request))
        assertFalse(ledger.prepare("A",InvitationRequest("create",workspaceId="w",email="synthetic@example.invalid",idempotencyKey="key")))
        assertTrue(ledger.prepare("A",request.copy(idempotencyKey="new")))
        assertEquals(ProtectedRecoveryState.StorageLocked,ledger.recoveryState("B"))
    }
    @Test fun compactMarkerCannotStandInForFullPendingProofOrForeignPartition() {
        val marker=proof().compacted()
        assertFails { AccountPersistenceEnvelope(uid="A",pendingInvitationCommands=listOf(request.pending("A")!!),pendingTerminalProof=marker) }
        assertFails { AccountPersistenceEnvelope(uid="B",lastSettledCommand=marker) }
        assertFails { AccountPersistenceEnvelope(uid="A",lastSettledCommand=marker,pendingInvitationCommands=listOf(request.pending("A")!!)) }
        assertFalse(marker.copy(acknowledgment=proof().acknowledgment).valid())
        assertFalse(marker.copy(marker=marker.marker!!.copy(source="normal-v1",decidedAtMillis=1000)).valid())
        assertFalse(marker.copy(marker=marker.marker!!.copy(commandId="wrong")).valid())
        assertFalse(marker.copy(marker=marker.marker!!.copy(schemaVersion=2)).valid())
    }
    @Test fun legacyFullProofStillRoundTripsAndResolverMarkerHasOnlyValidatedTime() {
        val legacy=proof()
        val oldWire=PersistenceJson.format.encodeToString(CommandTerminalProof.serializer(),legacy)
        for(key in listOf("marker","noteSave","noteRead","noteDecision")) assertFalse(oldWire.contains("\"$key\""))
        assertEquals(legacy,PersistenceJson.format.decodeFromString(CommandTerminalProof.serializer(),PersistenceJson.format.encodeToString(CommandTerminalProof.serializer(),legacy)))
        val p=request.pending("A")!!
        val resolution=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),"not-applied-fenced",1000)
        val compact=CommandTerminalProof(p,"resolution-v1",resolution=resolution).compacted()
        assertEquals(1000,compact.marker!!.decidedAtMillis);assertEquals("not-applied-fenced",compact.marker!!.outcome)
        assertFalse(compact.copy(marker=compact.marker!!.copy(decidedAtMillis=null)).valid())
        assertNull(legacy.compacted().marker!!.decidedAtMillis)
    }
}
