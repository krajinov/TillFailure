package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ProgramPublish(val templateId:String,val expectedRevision:Int,val authority:ExerciseAuthority,val draftHash:String,val contentPolicyVersion:Int) {
    fun valid()=CustomExerciseProtocol.validId(templateId) && expectedRevision in 1..Int.MAX_VALUE-2 && authority.valid() && PublicationProtocol.hash(draftHash) && contentPolicyVersion==1
    override fun toString()="ProgramPublish(redacted)"
}
@Serializable
data class PublicationItem(val id:String,val sourceKind:String,val sourceId:String,val sourceRevision:Int?,val sourceHash:String,
    val name:String,val prescription:DraftPrescription,val instructions:String,val muscleGroups:List<String>,val equipment:List<String>) {
    fun draft()=DraftItem(id,sourceKind,sourceId,sourceRevision,sourceHash,name,prescription)
    fun valid():Boolean {
        if(!draft().valid() || !CustomExerciseProtocol.text(instructions,2000,true))return false
        if(sourceKind=="system")return listOf(muscleGroups,equipment).all { a->a.size<=8 && a.distinct()==a && a.all{CustomExerciseProtocol.text(it,40)} }
        return muscleGroups.size<=3 && equipment.size<=3 && muscleGroups==muscleGroups.distinct().sorted() && equipment==equipment.distinct().sorted() &&
            muscleGroups.all{it in setOf("arms","back","chest","core","legs","shoulders","whole-body")} &&
            equipment.all{it in setOf("band","barbell","bodyweight","cable","dumbbell","machine")} && ("bodyweight" !in equipment || equipment.size==1)
    }
    override fun toString()="PublicationItem(redacted)"
}
@Serializable
data class PublicationWorkout(val id:String,val title:String,val items:List<PublicationItem>) {
    fun draft()=DraftWorkout(id,title,items.map{it.draft()})
    override fun toString()="PublicationWorkout(redacted)"
}
@Serializable
data class PublicationContent(val contentPolicyVersion:Int,val workspaceId:String,val templateId:String,val ownerUid:String,
    val title:String,val description:String,val workouts:List<PublicationWorkout>) {
    fun draft()=DraftSave(templateId,0,ExerciseAuthority(1,1,1),title,description,workouts.map{it.draft()})
    fun valid(uid:String,wid:String)=contentPolicyVersion==1 && ownerUid==uid && workspaceId==wid && CustomExerciseProtocol.workspace(wid) &&
        RecoveryUidContract.isValid(uid) && draft().valid() && PublicationProtocol.complete(draft()) && workouts.flatMap{it.items}.all{it.valid()}
    override fun toString()="PublicationContent(redacted)"
}
@Serializable
data class PublicationPreview(val workspaceId:String,val publish:ProgramPublish,val nextVersionNumber:Int,val preview:PublicationContent,val contentHash:String) {
    fun valid(uid:String,wid:String,tid:String,revision:Int,draftHash:String)=workspaceId==wid && publish.valid() && publish.templateId==tid && publish.expectedRevision==revision &&
        nextVersionNumber in 1..revision && preview.templateId==tid && preview.valid(uid,wid) && publish.draftHash==draftHash &&
        PublicationProtocol.draftHash(preview.draft(),uid,wid)==draftHash && PublicationProtocol.contentHash(preview)==contentHash
    override fun toString()="PublicationPreview(redacted)"
}
@Serializable
data class ProgramVersion(val schemaVersion:Int,val contentPolicyVersion:Int,val workspaceId:String,val templateId:String,val ownerUid:String,
    val title:String,val description:String,val workouts:List<PublicationWorkout>,val versionId:String,val versionNumber:Int,
    val draftRevision:Int,val draftHash:String,val contentHash:String,val publishedAtMillis:Long) {
    fun content()=PublicationContent(contentPolicyVersion,workspaceId,templateId,ownerUid,title,description,workouts)
    fun valid(uid:String,wid:String,tid:String,vid:String)=schemaVersion==1 && templateId==tid && versionId==vid && versionNumber in 1..Int.MAX_VALUE-2 &&
        versionId==PublicationProtocol.versionId(versionNumber) && draftRevision in versionNumber..Int.MAX_VALUE-2 && publishedAtMillis in 0..8640000000000000L &&
        content().valid(uid,wid) && PublicationProtocol.contentHash(content())==contentHash && PublicationProtocol.draftHash(content().draft(),uid,wid)==draftHash
    override fun toString()="ProgramVersion(redacted)"
}
@Serializable
data class PublicationDecision(val status:String,val workspaceId:String,val templateId:String,val templateRevision:Int,
    val versionId:String?,val versionNumber:Int?,val contentHash:String?,val replayed:Boolean) {
    fun valid(wid:String?,p:ProgramPublish)=workspaceId==wid && templateId==p.templateId && templateRevision in 1..Int.MAX_VALUE-1 &&
        if(status=="program-published")templateRevision==p.expectedRevision+1 && versionNumber!=null && versionNumber in 1..p.expectedRevision &&
            versionId==PublicationProtocol.versionId(versionNumber) && contentHash?.let(PublicationProtocol::hash)==true
        else versionId==null && versionNumber==null && contentHash==null && when(status) {
            "publication-conflict"->templateRevision>p.expectedRevision
            "publication-draft-conflict","publication-source-conflict","publication-incomplete"->templateRevision==p.expectedRevision
            else->false
        }
    override fun toString()="PublicationDecision(redacted)"
}
object PublicationProtocol {
    val json=Json { explicitNulls=true }
    // Enabled only after actual native sizing and parsing verification for this slice.
    const val ISSUANCE_ENABLED=false
    const val RESPONSE_BYTES=192*1024
    fun hash(v:String)=Regex("[a-f0-9]{64}").matches(v)
    fun versionId(n:Int)="v"+n.toString().padStart(10,'0')
    fun validVersionId(v:String)=Regex("v[0-9]{10}").matches(v) && v.drop(1).toLongOrNull() in 1L..2147483645L
    fun complete(d:DraftSave)=d.title.isNotEmpty() && d.workouts.isNotEmpty() && d.workouts.all{w->w.title.isNotEmpty() && w.items.isNotEmpty() && w.items.all{i->i.prescription.let{it.sets!=null && it.reps!=null && it.restSeconds!=null}}}
    fun draftHash(d:DraftSave,uid:String,wid:String)=commandDigest(canonical(buildJsonObject {
        put("hashVersion","program-draft-content-v1");put("contentPolicyVersion",1);put("workspaceId",wid);put("templateId",d.templateId);put("ownerUid",uid)
        put("title",d.title);put("description",d.description);put("workouts",json.encodeToJsonElement(d.workouts))
    }))
    fun contentHash(c:PublicationContent)=commandDigest(canonical(JsonObject(json.encodeToJsonElement(c).jsonObject+ ("hashVersion" to JsonPrimitive("program-version-content-v1")))))
    fun write(workspace:String,publish:ProgramPublish,key:String)=InvitationRequest("publish-program-template",workspaceId=workspace,idempotencyKey=key,publish=publish)
    fun validRequest(r:InvitationRequest)=r.action=="publish-program-template" && r.copy(workspaceId=null,idempotencyKey=null,publish=null)==InvitationRequest("publish-program-template") &&
        CustomExerciseProtocol.workspace(r.workspaceId?:"") && DraftSerialization.coveredKey(r.idempotencyKey?:"") && r.publish?.valid()==true
    fun parse(r:InvitationRequest,body:String?):InvitationReply {
        require(validRequest(r) && body!=null && body.encodeToByteArray().size<=4096)
        val o=json.parseToJsonElement(body);val d=json.decodeFromJsonElement<PublicationDecision>(o)
        require(json.encodeToJsonElement(d)==o && d.valid(r.workspaceId,r.publish!!))
        return InvitationReply(publicationDecision=d)
    }
}

/** Prospective states use the actual production serializer and include existing retained proofs. */
object PublicationBudget {
    fun pendingFits(p:PendingInvitationCommand)=DraftBudget.pendingFits(p)
    fun proofs(p:PendingInvitationCommand):List<CommandTerminalProof> {
        val d=requireNotNull(p.publish)
        val normal=listOf("program-published","publication-conflict","publication-draft-conflict","publication-source-conflict","publication-incomplete").flatMap { status->
            listOf(false,true).map { replayed->
                val success=status=="program-published"
                CommandTerminalProof(p,"normal-v1",acknowledgment=InvitationReply(publicationDecision=PublicationDecision(status,p.workspaceId!!,d.templateId,
                    if(success)d.expectedRevision+1 else if(status=="publication-conflict")Int.MAX_VALUE-1 else d.expectedRevision,
                    if(success)PublicationProtocol.versionId(d.expectedRevision) else null,if(success)d.expectedRevision else null,if(success)"f".repeat(64) else null,replayed)))
            }
        }
        return normal+listOf("applied","not-applied-conflict","not-applied-fenced").map {
            CommandTerminalProof(p,"resolution-v1",resolution=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),it,8640000000000000))
        }
    }
    fun states(r:AccountPersistenceEnvelope,p:PendingInvitationCommand)=listOf(r.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=null))+proofs(p).flatMap { proof->
        listOf(r.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=proof),r.copy(pendingInvitationCommands=emptyList(),pendingTerminalProof=null,lastSettledCommand=proof.retired()))
    }
    fun admission(r:AccountPersistenceEnvelope,p:PendingInvitationCommand)=runCatching {
        require(p.action=="publish-program-template" && validPendingCommand(p.uid,p) && r.uid==p.uid)
        require(r.switchMarker==null && r.offlineAccessGrant==null && r.downloadManifests.isEmpty() && r.workoutRecoverySnapshot==null && r.mutationJournal.isEmpty() && r.pendingUploads.isEmpty())
        require(r.pendingInvitationCommands.isEmpty() && r.pendingTerminalProof==null && r.lastSettledCommand?.consumed(p)!=true)
        listOf(p,p.copy(resolutionStarted=true)).all { command->states(r,command).all { state->
            val candidates=if(state.pendingInvitationCommands.isEmpty())listOf(state,state.copy(
                switchMarker=AccountSwitchMarker(departingUid=p.uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))) else listOf(state)
            candidates.all { candidate->
                val n=DraftBudget.plaintext(candidate).encodeToByteArray().size
                n<=36500 && DraftBudget.swiftEncryptedUpperBound(n)<=98304
            }
        } }
    }.getOrDefault(false)
}
