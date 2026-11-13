package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.exercises.CustomExerciseProtocol
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.programs.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** PR 1a isolated value contracts. No ledger dispatch, transport or issuance adapter. */
@Serializable
data class AssignmentAuthority(val trainerLifecycleRevision:Int,val trainerMembershipRevision:Int,val clientLifecycleRevision:Int,
    val clientMembershipRevision:Int,val workspaceMembershipRevision:Int,val clientOnboardingRevision:Int) {
    fun valid()=listOf(trainerLifecycleRevision,trainerMembershipRevision,clientLifecycleRevision,clientMembershipRevision,clientOnboardingRevision).all{it in 1..2147483646} && workspaceMembershipRevision in 1..2147483645
}
@Serializable
data class AssignmentCreate(val assignmentId:String,val plannedWorkoutId:String,val clientUid:String,val templateId:String,
    val versionId:String,val versionNumber:Int,val sourceContentHash:String,val copyPolicyVersion:Int,val snapshotHash:String,
    val authority:AssignmentAuthority,val preparedAtMillis:Long,val scheduledInstantMillis:Long,val accessExpiresAtMillis:Long) {
    fun valid()=listOf(assignmentId,plannedWorkoutId,templateId).all(CustomExerciseProtocol::validId) &&
        isPathSafeAuthUid(clientUid) &&
        versionNumber in 1..2147483645 && versionId==PublicationProtocol.versionId(versionNumber) &&
        PublicationProtocol.hash(sourceContentHash) && PublicationProtocol.hash(snapshotHash) && copyPolicyVersion==1 && authority.valid() &&
        listOf(preparedAtMillis,scheduledInstantMillis,accessExpiresAtMillis).all(AssignmentContract::millis) &&
        scheduledInstantMillis>preparedAtMillis && scheduledInstantMillis-preparedAtMillis<=AssignmentContract.DAY_MS &&
        scheduledInstantMillis<=AssignmentContract.MAX_MILLIS-AssignmentContract.DAY_MS && accessExpiresAtMillis==scheduledInstantMillis+AssignmentContract.DAY_MS
    override fun toString()="AssignmentCreate(redacted)"
}
@Serializable
data class AssignmentDescriptor(val action:String,val workspaceId:String,val idempotencyKey:String,val assignment:AssignmentCreate) {
    fun valid(uid:String)=action=="assign-program-version" && CustomExerciseProtocol.workspace(workspaceId) && DraftSerialization.coveredKey(idempotencyKey) &&
        assignment.valid() && isPathSafeAuthUid(uid) && assignment.clientUid!=uid
    override fun toString()="AssignmentDescriptor(redacted)"
}
@Serializable
data class AssignmentCopy(val copyPolicyVersion:Int,val title:String,val description:String,val workouts:List<PublicationWorkout>) {
    override fun toString()="AssignmentCopy(redacted)"
}
@Serializable
data class AssignmentDecision(val status:String,val workspaceId:String,val clientUid:String,val assignmentId:String,
    val assignmentRevision:Int,val plannedWorkoutId:String?,val snapshotHash:String?)

object AssignmentContract {
    const val DAY_MS=86400000L
    const val MAX_MILLIS=253402300799999L
    const val KIND="program-assignment-create"
    const val HASH_VERSION="program-assignment-v1"
    val json=Json { explicitNulls=true }
    val statuses=listOf("assignment-created","assignment-schedule-conflict","assignment-source-conflict","assignment-copy-conflict","assignment-id-conflict")
    fun millis(n:Long)=n in 0..MAX_MILLIS
    fun id(uid:String,d:AssignmentDescriptor)=commandDigest(canonical(buildJsonObject {put("callerUid",uid);put("idempotencyKey",d.idempotencyKey)}))
    fun hash(uid:String,d:AssignmentDescriptor)=commandDigest(canonical(JsonObject(json.encodeToJsonElement(d).jsonObject+mapOf("callerUid" to JsonPrimitive(uid),"policyVersion" to JsonPrimitive(1)))))
    fun copy(v:ProgramVersion,uid:String,wid:String,tid:String):AssignmentCopy {
        require(isPathSafeAuthUid(uid) && CustomExerciseProtocol.workspace(wid) &&
            v.valid(uid,wid,tid,v.versionId) && millis(v.publishedAtMillis) && v.workouts.size==1 &&
            json.encodeToString(ProgramVersion.serializer(),v).encodeToByteArray().size<=163840)
        return AssignmentCopy(1,v.title,v.description,v.workouts)
    }
    fun copyHash(c:AssignmentCopy)=commandDigest(canonical(JsonObject(json.encodeToJsonElement(c).jsonObject+("hashVersion" to JsonPrimitive("program-assignment-copy-v1")))))
    fun executionTime(a:AssignmentCreate,now:Long):String {
        require(a.valid() && millis(now) && now>=a.preparedAtMillis)
        return if(now<a.scheduledInstantMillis)"current" else "assignment-schedule-conflict"
    }
    fun decision(d:AssignmentDescriptor,status:String):AssignmentDecision {
        require(status in statuses && d.assignment.valid())
        val a=d.assignment;val applied=status=="assignment-created"
        return AssignmentDecision(status,d.workspaceId,a.clientUid,a.assignmentId,if(applied)1 else 0,if(applied)a.plannedWorkoutId else null,if(applied)a.snapshotHash else null)
    }
    fun validDecision(d:AssignmentDescriptor,result:AssignmentDecision)=result.status in statuses && result==decision(d,result.status)
    fun parseDescriptor(uid:String,text:String):AssignmentDescriptor {
        require(text.encodeToByteArray().size<=32768)
        val element=json.parseToJsonElement(text);val d=json.decodeFromJsonElement<AssignmentDescriptor>(element)
        require(d.valid(uid) && json.encodeToJsonElement(d)==element && transportFits(uid,d))
        return d
    }
    fun transportFits(uid:String,d:AssignmentDescriptor):Boolean {
        if(!d.valid(uid))return false
        val descriptor=json.encodeToString(AssignmentDescriptor.serializer(),d)
        val resolver="{\"protocolVersion\":1,\"descriptor\":$descriptor}"
        return resolver.length<=8192 && resolver.encodeToByteArray().size<=24576 &&
            "{\"data\":$descriptor}".encodeToByteArray().size<=32768 && "{\"data\":$resolver}".encodeToByteArray().size<=32768
    }
}

/** Prospective JSON for a future optional assignment field. Existing twelve-kind readers stay unchanged and reject it. */
object AssignmentProspective {
    fun admissionJson(prior:String,descriptor:String)=runCatching {
        val base=PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),prior)
        admission(base,AssignmentContract.parseDescriptor(base.uid,descriptor))
    }.getOrDefault(false)
    fun pending(uid:String,d:AssignmentDescriptor,started:Boolean):JsonObject {
        val legacy=PendingInvitationCommand(uid=uid,action=d.action,idempotencyKey=d.idempotencyKey,workspaceId=d.workspaceId,resolutionStarted=started)
        return JsonObject(PersistenceJson.format.encodeToJsonElement(legacy).jsonObject+("assignment" to AssignmentContract.json.encodeToJsonElement(d.assignment)))
    }
    fun states(base:AccountPersistenceEnvelope,d:AssignmentDescriptor):List<JsonObject> {
        require(d.valid(base.uid))
        val record=PersistenceJson.format.encodeToJsonElement(base).jsonObject
        val out=mutableListOf<JsonObject>()
        fun state(p:JsonElement,proof:JsonElement,settled:JsonElement=record.getValue("lastSettledCommand"))=
            JsonObject(record+mapOf("pendingInvitationCommands" to p,"pendingTerminalProof" to proof,"lastSettledCommand" to settled))
        for(started in listOf(false,true)) {
            val p=pending(base.uid,d,started)
            out+=state(JsonArray(listOf(p)),JsonNull)
            for(status in AssignmentContract.statuses) for(replayed in listOf(false,true)) {
                val result=AssignmentContract.json.encodeToJsonElement(AssignmentContract.decision(d,status)).jsonObject
                // Retain the actual existing reply defaults, adding only the proposed optional field.
                val acknowledgment=JsonObject(PersistenceJson.format.encodeToJsonElement(InvitationReply()).jsonObject+
                    ("assignmentDecision" to JsonObject(result+("replayed" to JsonPrimitive(replayed)))))
                val proof=buildJsonObject {put("command",p);put("source","normal-v1");put("resolution",JsonNull);put("acknowledgment",acknowledgment)}
                out+=state(JsonArray(listOf(p)),proof)
            }
            for(outcome in listOf("applied","not-applied-conflict","not-applied-fenced")) {
                val resolution=buildJsonObject {put("protocolVersion",1);put("commandKind",AssignmentContract.KIND);put("commandId",AssignmentContract.id(base.uid,d))
                    put("requestHashVersion",AssignmentContract.HASH_VERSION);put("requestHash",AssignmentContract.hash(base.uid,d));put("outcome",outcome);put("decidedAtMillis",AssignmentContract.MAX_MILLIS)}
                out+=state(JsonArray(listOf(p)),buildJsonObject {put("command",p);put("source","resolution-v1");put("resolution",resolution);put("acknowledgment",JsonNull)})
            }
        }
        for(source in listOf("normal-v1","resolution-v1"))for(outcome in if(source=="normal-v1")listOf("applied","not-applied-conflict") else listOf("applied","not-applied-conflict","not-applied-fenced")) {
            val marker=buildJsonObject {put("schemaVersion",1);put("uid",base.uid);put("commandKind",AssignmentContract.KIND);put("commandId",AssignmentContract.id(base.uid,d))
                put("requestHashVersion",AssignmentContract.HASH_VERSION);put("requestHash",AssignmentContract.hash(base.uid,d));put("source",source);put("outcome",outcome)
                put("decidedAtMillis",if(source=="resolution-v1")JsonPrimitive(AssignmentContract.MAX_MILLIS) else JsonNull)}
            val retired=state(JsonArray(emptyList()),JsonNull,buildJsonObject {put("command",JsonNull);put("source","compacted-v1");put("resolution",JsonNull);put("acknowledgment",JsonNull);put("marker",marker)})
            out+=retired
            out+=JsonObject(retired+("switchMarker" to PersistenceJson.format.encodeToJsonElement(AccountSwitchMarker(departingUid=base.uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))))
        }
        return out
    }
    fun admission(base:AccountPersistenceEnvelope,d:AssignmentDescriptor)=runCatching {
        require(base.pendingInvitationCommands.isEmpty() && base.pendingTerminalProof==null && base.switchMarker==null && base.offlineAccessGrant==null &&
            base.downloadManifests.isEmpty() && base.workoutRecoverySnapshot==null && base.mutationJournal.isEmpty() && base.pendingUploads.isEmpty())
        require(AssignmentContract.transportFits(base.uid,d))
        val existing=base.lastSettledCommand
        require(existing?.command?.idempotencyKey!=d.idempotencyKey && existing?.marker?.commandId!=AssignmentContract.id(base.uid,d))
        pending(base.uid,d,true).toString().encodeToByteArray().size<=16384 && states(base,d).all {
            val n=it.toString().encodeToByteArray().size
            n<=36500 && DraftBudget.swiftEncryptedUpperBound(n)<=98304
        }
    }.getOrDefault(false)
}
