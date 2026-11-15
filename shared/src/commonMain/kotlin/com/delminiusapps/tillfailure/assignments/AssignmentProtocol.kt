package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.programs.DraftBudget
import kotlinx.serialization.json.*

object AssignmentProtocol {
    // Both actual native matrices and typed native admission passed for synthetic emulators.
    const val ISSUANCE_ENABLED=true
    fun write(workspace:String,assignment:AssignmentCreate,key:String)=InvitationRequest("assign-program-version",workspaceId=workspace,idempotencyKey=key,assignment=assignment)
    fun descriptor(p:PendingInvitationCommand)=AssignmentDescriptor(p.action,p.workspaceId?:"",p.idempotencyKey,requireNotNull(p.assignment))
    fun validRequest(r:InvitationRequest,uid:String?=null):Boolean {
        val caller=uid?:if(r.assignment?.clientUid=="wire")"wire-caller" else "wire"
        return r.action=="assign-program-version" && r.copy(workspaceId=null,idempotencyKey=null,assignment=null)==InvitationRequest("assign-program-version") &&
            r.assignment?.let { AssignmentContract.transportFits(caller,AssignmentDescriptor(r.action,r.workspaceId?:"",r.idempotencyKey?:"",it)) }==true
    }
    fun parse(r:InvitationRequest,body:String?):InvitationReply {
        require(validRequest(r) && body!=null && body.encodeToByteArray().size<=4096)
        val o=AssignmentContract.json.parseToJsonElement(body)
        val result=AssignmentContract.json.decodeFromJsonElement<AssignmentDecision>(o)
        require(result.replayed!=null && AssignmentContract.json.encodeToJsonElement(result)==o &&
            AssignmentContract.validDecision(AssignmentDescriptor(r.action,r.workspaceId!!,r.idempotencyKey!!,r.assignment!!),result.copy(replayed=null)))
        return InvitationReply(assignmentDecision=result)
    }
}

/** Full prospective admission uses the production types/serializer, with retained history. */
object AssignmentBudget {
    fun pendingFits(p:PendingInvitationCommand)=runCatching {
        val d=AssignmentProtocol.descriptor(p)
        AssignmentContract.transportFits(p.uid,d) && PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),p).encodeToByteArray().size<=16384
    }.getOrDefault(false)
    fun proofs(p:PendingInvitationCommand):List<CommandTerminalProof> {
        val d=AssignmentProtocol.descriptor(p)
        return AssignmentContract.statuses.flatMap { status->listOf(false,true).map { replayed->
            CommandTerminalProof(p,"normal-v1",acknowledgment=InvitationReply(assignmentDecision=AssignmentContract.decision(d,status).copy(replayed=replayed)))
        } }+listOf("applied","not-applied-conflict","not-applied-fenced").map { outcome->
            CommandTerminalProof(p,"resolution-v1",resolution=CommandResolutionReply(1,AssignmentContract.KIND,CommandResolution.id(p),AssignmentContract.HASH_VERSION,CommandResolution.hash(p),outcome,AssignmentContract.MAX_MILLIS))
        }
    }
    fun states(r:AccountPersistenceEnvelope,p:PendingInvitationCommand)=listOf(r.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=null))+proofs(p).flatMap { proof->
        listOf(r.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=proof),r.copy(pendingInvitationCommands=emptyList(),pendingTerminalProof=null,lastSettledCommand=proof.retired()))
    }
    fun admission(r:AccountPersistenceEnvelope,p:PendingInvitationCommand)=runCatching {
        require(p.action=="assign-program-version" && validPendingCommand(p.uid,p) && r.uid==p.uid && pendingFits(p))
        require(r.switchMarker==null && r.offlineAccessGrant==null && r.downloadManifests.isEmpty() && r.workoutRecoverySnapshot==null && r.mutationJournal.isEmpty() && r.pendingUploads.isEmpty())
        require(r.pendingInvitationCommands.isEmpty() && r.pendingTerminalProof==null && r.lastSettledCommand?.consumed(p)!=true)
        listOf(p,p.copy(resolutionStarted=true)).all { command->states(r,command).all { state->
            val candidates=if(state.pendingInvitationCommands.isEmpty())listOf(state,state.copy(switchMarker=AccountSwitchMarker(departingUid=p.uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))) else listOf(state)
            candidates.all { candidate->val n=DraftBudget.plaintext(candidate).encodeToByteArray().size
                n<=36500 && DraftBudget.swiftEncryptedUpperBound(n)<=98304
            }
        } }
    }.getOrDefault(false)
}
