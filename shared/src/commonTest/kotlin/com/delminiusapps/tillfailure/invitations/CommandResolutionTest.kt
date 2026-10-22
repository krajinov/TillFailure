package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.profile.ProfileUpdate
import com.delminiusapps.tillfailure.identity.*
import kotlin.test.*

class CommandResolutionTest {
    private class Store : AccountPersistencePrototype {
        var value = AccountPersistenceEnvelope(uid="A")
        var writes=0; var failAt: Int?=null
        override fun read(uid: String)=value.also { require(it.uid==uid) }
        override fun write(envelope: AccountPersistenceEnvelope) { writes++; if(writes==failAt) error("simulated storage boundary");value=envelope }
        override fun delete(uid: String) { error("Unresolved data must not be deleted") }
    }
    private fun original(key: String="key")=InvitationRequest("update-profile",workspaceId="w",idempotencyKey=key,profileUpdate=ProfileUpdate("Žan 😀","bs-BA","Europe/Sarajevo",2))
    private fun outcome(p:PendingInvitationCommand, status:String="not-applied-fenced")=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),status,1000)
    private fun resolve(p:PendingInvitationCommand)=InvitationRequest("resolve-command",resolution=p)
    @Test fun shaAndCanonicalHashesMatchBackendIncludingUnicodeAndNull() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",commandDigest(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",commandDigest("abc"))
        val p=original().pending("A")!!
        assertEquals("3393e16babad34f10337e79fd4ffb966679b3b5a35b1a6f58a52e02349d36313",CommandResolution.id(p))
        assertEquals("69d54b93774f1e7fa5800b191caa9c30117d850628b23f5d6a79a273c92c5906",CommandResolution.hash(p))
        val create=PendingInvitationCommand("A","create","key",workspaceId="w",email="c@example.invalid")
        assertEquals("ae24898ad9863e5b3e535f670c55f6f7d67a0c7d468a799e2006e35dfc8c9796",CommandResolution.hash(create))
        for ((action, hash) in mapOf("resend" to "c2d934bf9da4013916244dfc034bc31801d0b20f86dc2b74e1f183d98f00f9cf",
            "revoke" to "aee6323b55551f5e383680e9fdf27eb414ca21b952b218865105b0d7b03f3a8d")) {
            assertEquals(hash, CommandResolution.hash(PendingInvitationCommand("A",action,"key",workspaceId="w",invitationId="a".repeat(64),expectedVersion=2)))
        }
        assertEquals("1d363ef29f11943185c11aabcc5c014e34bc08a4c27c981cb3dc06fce1acb7db",CommandResolution.hash(
            PendingInvitationCommand("A","accept","key",invitationId="a".repeat(64),expectedVersion=2,acceptanceTokenDigest="b".repeat(64))))
        assertEquals("936c9439422299d6d126278055676c7447fac6acdd31748680913e3f073aa76b",CommandResolution.hash(
            PendingInvitationCommand("A","complete-onboarding","key",workspaceId="w",onboarding=com.delminiusapps.tillfailure.onboarding.OnboardingCompletion("Žan 😀","bs-BA","Europe/Sarajevo",true,2,0))))
    }
    @Test fun terminalWireRequiresExactFieldsOriginalUidKeyKindPayloadAndOutcome() {
        val p=original().pending("A")!!;val r=outcome(p)
        val body=InvitationJson.format.encodeToString(CommandResolutionReply.serializer(),r)
        assertNotNull(CommandResolution.parse(p,body))
        for(invalid in listOf(body.dropLast(1)+",\"private\":\"secret\"}",body.replace("not-applied-fenced","unknown"),body.replace("profile-update","invitation-accept"),"{}")) assertNull(CommandResolution.parse(p,invalid))
        assertFalse(CommandResolution.valid(p.copy(uid="B"),r));assertFalse(CommandResolution.valid(p.copy(idempotencyKey="different"),r))
        assertFalse(CommandResolution.valid(p.copy(profileUpdate=ProfileUpdate("Other","en","UTC",2)),r))
        val legacy=PendingInvitationCommand("A","accept","legacy",invitationId="a".repeat(64),expectedVersion=1)
        assertFalse(CommandResolution.bound(legacy))
        val new=InvitationRequest("accept",invitationId="a".repeat(64),expectedVersion=1,idempotencyKey=resolutionCommandKey("nonce"),token="b".repeat(43)).pending("A")!!
        assertTrue(CommandResolution.bound(new));assertFalse(InvitationJson.format.encodeToString(PendingInvitationCommand.serializer(),new).contains("b".repeat(43)))
    }
    @Test fun resolutionStartsDurablyAndBlocksBusinessRetryWithoutRekeyOrReplacement() {
        val store=Store();val ledger=InvitationLedger(store);val request=original();assertTrue(ledger.prepare("A",request));val p=ledger.pending("A")!!
        assertTrue(ledger.prepare("A",resolve(p)));assertTrue(ledger.pending("A")!!.resolutionStarted)
        assertFalse(ledger.prepare("A",request));assertFalse(ledger.prepare("A",original("new-key")))
        assertFalse(ledger.prepare("A",resolve(p.copy(uid="B"))))
        val result=ledger.settle("A",resolve(p),InvitationOutcome(reply=InvitationReply(terminalOutcome=outcome(p))))
        assertNull(result.error);assertNull(ledger.pending("A"));assertEquals(p,store.value.lastSettledCommand?.command)
        assertNotNull(ledger.lastSettled("A"));assertEquals(ProtectedRecoveryState.Clean,ledger.recoveryState("A"))
    }
    @Test fun errorsDoNotSettleAndLateRepliesCannotClearNextCommand() {
        val store=Store();val ledger=InvitationLedger(store);val request=original();ledger.prepare("A",request);val p=ledger.pending("A")!!
        for(error in listOf("identity-required","outcome-unavailable","rate-limited","unavailable","unauthorized")) {
            ledger.settle("A",resolve(p),InvitationOutcome(error=error));assertEquals(p,ledger.pending("A"))
        }
        ledger.settle("A",resolve(p),InvitationOutcome(reply=InvitationReply(terminalOutcome=outcome(p))))
        val next=original("next");assertTrue(ledger.prepare("A",next));val retained=ledger.pending("A")
        assertTrue(ledger.settle("A",resolve(p),InvitationOutcome(reply=InvitationReply(terminalOutcome=outcome(p)))).ambiguous)
        assertEquals(retained,ledger.pending("A"))
    }
    @Test fun actualStoreBoundariesPreservePendingThenRecoverDurableProofOnRecreation() {
        for(boundary in listOf(1,2)) {
            val store=Store();val ledger=InvitationLedger(store);val req=original();ledger.prepare("A",req);val p=ledger.pending("A")!!
            store.failAt=store.writes+boundary
            val result=ledger.settle("A",resolve(p),InvitationOutcome(reply=InvitationReply(terminalOutcome=outcome(p))))
            assertEquals("storage-unavailable",result.error);assertTrue(store.value.pendingInvitationCommands.isNotEmpty())
            if(boundary==1) assertNull(store.value.pendingTerminalProof) else assertNotNull(store.value.pendingTerminalProof)
            store.failAt=null;val recreated=InvitationLedger(store)
            if(boundary==1) { assertNotNull(recreated.pending("A"));recreated.settle("A",resolve(p),InvitationOutcome(reply=InvitationReply(terminalOutcome=outcome(p)))) }
            assertNull(recreated.pending("A"));assertEquals(ProtectedRecoveryState.Clean,recreated.recoveryState("A"))
        }
    }
    @Test fun ordinarySuccessAndConflictUseDurableProofWithoutInventedServerFields() {
        for(status in listOf("profile-updated","profile-conflict")) {
            val store=Store();val ledger=InvitationLedger(store);val req=original();ledger.prepare("A",req)
            val reply=InvitationReply(workspaceId="w",status=status,accountRevision=3,profileCommandKey="key",replayed=false)
            ledger.settle("A",req,InvitationOutcome(reply=reply));assertNull(ledger.pending("A"))
            val proof=store.value.lastSettledCommand!!;assertEquals("normal-v1",proof.source);assertEquals(reply,proof.acknowledgment);assertNull(proof.resolution)
            assertEquals(3,store.writes)
        }
    }
    @Test fun corruptedProofAndForeignPartitionStayLocked() {
        val store=Store();val p=original().pending("A")!!
        assertFails { store.value.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=CommandTerminalProof(p,"resolution-v1",resolution=outcome(p).copy(requestHash="0".repeat(64)))) }
        val ledger=InvitationLedger(store);assertEquals(0,store.writes)
        assertEquals(ProtectedRecoveryState.StorageLocked,ledger.recoveryState("B"))
    }
    @Test fun resolveConfirmationIsExplicitVisitBoundAndNeverGrantsBusinessAuthority() {
        val store=Store();val ledger=InvitationLedger(store);ledger.prepare("A",original())
        val calls=mutableListOf<InvitationRequest>();var reply:((InvitationOutcome)->Unit)?=null
        val client=object:InvitationClient {
            override val memory=InvitationMemory()
            override fun pending(uid:String)=ledger.pending(uid)
            override fun lastSettled(uid:String)=ledger.lastSettled(uid)
            override fun newCommandKey()="unused"
            override fun execute(uid:String,request:InvitationRequest,callback:(InvitationOutcome)->Unit):com.delminiusapps.tillfailure.firebase.FirebaseCancellation {
                assertTrue(ledger.prepare(uid,request));calls+=request;reply={ callback(ledger.settle(uid,request,it)) }
                return com.delminiusapps.tillfailure.firebase.FirebaseCancellation {}
            }
        }
        val model=InvitationViewModel(client)
        val gate=IdentityState(accountUid="A",status=GateStatus.LockedRecovery,authorityEpoch=1)
        model.updateIdentity(gate);assertEquals("ready",model.state.value.resolutionEligibility)
        model.onEvent(InvitationEvent.Resolve);val first=model.state.value
        assertNotNull(first.resolutionPrompt);assertTrue(calls.isEmpty())
        model.onEvent(InvitationEvent.CancelResolution(first.resolutionPromptId));model.onEvent(InvitationEvent.Resolve);val current=model.state.value
        model.onEvent(InvitationEvent.CancelResolution(first.resolutionPromptId));assertEquals(current.resolutionPrompt,model.state.value.resolutionPrompt)
        model.onEvent(InvitationEvent.ConfirmResolution(first.resolutionPrompt!!,first.resolutionPromptId));assertTrue(calls.isEmpty())
        model.onEvent(InvitationEvent.ConfirmResolution(current.resolutionPrompt!!,current.resolutionPromptId));assertEquals(1,calls.size)
        val p=calls.single().resolution!!;assertTrue(ledger.pending("A")!!.resolutionStarted)
        reply!!(InvitationOutcome(reply=InvitationReply(terminalOutcome=outcome(p,"applied"))))
        assertNull(model.state.value.pending);assertTrue(model.state.value.message.contains("completed"));assertEquals(GateStatus.LockedRecovery,gate.status)
        assertEquals("resolution-v1",ledger.lastSettled("A")?.source)
        model.updateIdentity(gate.copy(accountUid="B",authorityEpoch=2));assertNull(model.state.value.pending)
        model.onEvent(InvitationEvent.ConfirmResolution(current.resolutionPrompt,current.resolutionPromptId));assertEquals(1,calls.size)
    }
    @Test fun lockedRecoveryPreservesKnownGlobalIdentityDenials() {
        val command=original().pending("A")!!
        val client=object:InvitationClient {
            override val memory=InvitationMemory()
            override fun pending(uid:String)=command
            override fun newCommandKey()="unused"
            override fun execute(uid:String,request:InvitationRequest,callback:(InvitationOutcome)->Unit):com.delminiusapps.tillfailure.firebase.FirebaseCancellation = error("No issuance")
        }
        val model=InvitationViewModel(client)
        for (cause in listOf(GateStatus.AccountDisabled,GateStatus.SessionExpired,GateStatus.UnverifiedEmail,GateStatus.IdentityUnsupported,GateStatus.NoAccount)) {
            model.updateIdentity(IdentityState(accountUid="A",status=GateStatus.LockedRecovery,recoveryCause=cause))
            assertEquals("identity-required",model.state.value.resolutionEligibility)
            model.onEvent(InvitationEvent.Resolve);assertNull(model.state.value.resolutionPrompt)
        }
        model.updateIdentity(IdentityState(accountUid="A",status=GateStatus.LockedRecovery,recoveryCause=GateStatus.AccessLost))
        assertEquals("ready",model.state.value.resolutionEligibility)
    }

    @Test fun terminalWireRejectsQuotedNumbers() {
        val p=original().pending("A")!!
        val json=InvitationJson.format.encodeToString(CommandResolutionReply.serializer(),outcome(p))
        assertNull(CommandResolution.parse(p,json.replace("\"protocolVersion\":1","\"protocolVersion\":\"1\"")))
        assertNull(CommandResolution.parse(p,json.replace("\"decidedAtMillis\":1000","\"decidedAtMillis\":\"1000\"")))
    }

    @Test fun reopeningLegacyAcceptanceRefreshesBindingWithoutReplacingProtectedWork() {
        val store=Store();val ledger=InvitationLedger(store)
        val link=InvitationLink("a".repeat(64),1,"b".repeat(43))
        assertTrue(ledger.prepare("A",InvitationRequest("accept",invitationId=link.invitationId,expectedVersion=1,token=link.token,idempotencyKey="legacy")))
        val pending=ledger.pending("A")!!
        val client=object:InvitationClient {
            override val memory=InvitationMemory()
            override fun pending(uid:String)=ledger.pending(uid)
            override fun newCommandKey()="unused"
            override fun execute(uid:String,request:InvitationRequest,callback:(InvitationOutcome)->Unit):com.delminiusapps.tillfailure.firebase.FirebaseCancellation = error("No network needed to bind")
        }
        val model=InvitationViewModel(client)
        model.updateIdentity(IdentityState(accountUid="A",status=GateStatus.LockedRecovery,recoveryCause=GateStatus.AccessLost))
        assertEquals("binding-required",model.state.value.resolutionEligibility)
        model.onEvent(InvitationEvent.Entry(link.entry()));model.onEvent(InvitationEvent.Open)
        assertEquals("ready",model.state.value.resolutionEligibility)
        assertEquals(pending,ledger.pending("A"));assertEquals("",model.state.value.entry)
        model.onEvent(InvitationEvent.Resolve)
        assertEquals(commandDigest(link.token),model.state.value.resolutionPrompt?.acceptanceTokenDigest)
        assertEquals("legacy",model.state.value.resolutionPrompt?.idempotencyKey)
    }

    @Test fun matchingTerminalAcceptanceReleasesTransientBindingButForeignOutcomesDoNot() {
        for (legacy in listOf(true,false)) {
            val link=InvitationLink("a".repeat(64),1,"b".repeat(43))
            val request=InvitationRequest("accept",invitationId=link.invitationId,expectedVersion=1,token=link.token,
                idempotencyKey=if(legacy) "legacy" else resolutionCommandKey("accept"))
            val p=request.pending("A")!!;val bound=p.copy(acceptanceTokenDigest=commandDigest(link.token))
            val memory=InvitationMemory();assertTrue(memory.protectAcceptance(p,link.token))
            val proof=CommandTerminalProof(bound,"resolution-v1",resolution=outcome(bound,"applied"))
            memory.consume("B",null,proof);assertNotNull(memory.linkFor("A"))
            memory.consume("A",null,proof.copy(resolution=proof.resolution!!.copy(commandId="0".repeat(64))))
            assertFalse(memory.open(InvitationLink("c".repeat(64),1,"d".repeat(43)).entry(),"A"))
            memory.consume("A",null,proof)
            assertNull(memory.link)
            assertTrue(memory.open(InvitationLink("c".repeat(64),1,"d".repeat(43)).entry(),"A"))
        }
    }

}
