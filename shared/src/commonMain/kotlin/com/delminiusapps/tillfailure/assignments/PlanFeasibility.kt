package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.exercises.CustomExerciseProtocol
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import com.delminiusapps.tillfailure.programs.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Isolated PR2a schema. No product transport, ledger, reader or UI calls this object. */
@Serializable data class PlanOccurrence(val plannedWorkoutId:String,val workoutId:String,val scheduledInstantMillis:Long)
@Serializable data class PlanCreate(val assignmentId:String,val clientUid:String,val templateId:String,val versionId:String,val versionNumber:Int,
 val sourceContentHash:String,val copyPolicyVersion:Int,val snapshotHash:String,val authority:AssignmentAuthority,val preparedAtMillis:Long,
 val accessExpiresAtMillis:Long,val schedulePolicyVersion:Int,val expectedHorizonRevision:Int,val horizonRevision:Int,val planHash:String,val occurrences:List<PlanOccurrence>)
@Serializable data class PlanDescriptor(val action:String,val workspaceId:String,val idempotencyKey:String,val assignmentPlan:PlanCreate)
@Serializable data class PlanDecision(val status:String,val workspaceId:String,val clientUid:String,val assignmentId:String,val assignmentRevision:Int,
 val horizonRevision:Int,val planHash:String?,val plannedWorkoutCount:Int,val snapshotHash:String?)
object PlanFeasibility {
 const val KIND="program-assignment-plan-create"
 const val HASH_VERSION="program-assignment-plan-v1"
 private val json=AssignmentContract.json
 val statuses=listOf("plan-created","plan-schedule-conflict","plan-source-conflict","plan-copy-conflict","plan-id-conflict")
 fun planHash(occurrences:List<PlanOccurrence>)=commandDigest(canonical(buildJsonObject {
  put("hashVersion","program-assignment-horizon-v1");put("schedulePolicyVersion",2);put("horizonRevision",1);put("occurrences",json.encodeToJsonElement(occurrences))
 }))
 fun valid(uid:String,d:PlanDescriptor):Boolean {
  val a=d.assignmentPlan;val o=a.occurrences
  return d.action=="assign-program-plan" && CustomExerciseProtocol.workspace(d.workspaceId) && DraftSerialization.coveredKey(d.idempotencyKey) && isPathSafeAuthUid(uid) && isPathSafeAuthUid(a.clientUid) && uid!=a.clientUid &&
   listOf(a.assignmentId,a.templateId).all(CustomExerciseProtocol::validId) && a.versionNumber in 1..2147483645 && a.versionId==PublicationProtocol.versionId(a.versionNumber) &&
   listOf(a.sourceContentHash,a.snapshotHash,a.planHash).all(PublicationProtocol::hash) && a.copyPolicyVersion==1 && a.schedulePolicyVersion==2 && a.expectedHorizonRevision==0 && a.horizonRevision==1 && a.authority.valid() &&
   AssignmentContract.millis(a.preparedAtMillis) && AssignmentContract.millis(a.accessExpiresAtMillis) && o.size in 1..3 &&
   o.all{CustomExerciseProtocol.validId(it.plannedWorkoutId) && CustomExerciseProtocol.validId(it.workoutId) && AssignmentContract.millis(it.scheduledInstantMillis) && it.scheduledInstantMillis>a.preparedAtMillis && it.scheduledInstantMillis-a.preparedAtMillis<=AssignmentContract.DAY_MS} &&
   o.map{it.workoutId}.distinct().size==o.size && o.map{it.plannedWorkoutId}.distinct().size==o.size && o.zipWithNext().all{(x,y)->x.scheduledInstantMillis<y.scheduledInstantMillis} &&
   o.last().scheduledInstantMillis<=AssignmentContract.MAX_MILLIS-AssignmentContract.DAY_MS && a.accessExpiresAtMillis==o.last().scheduledInstantMillis+AssignmentContract.DAY_MS && a.planHash==planHash(o)
 }
 fun parse(uid:String,text:String):PlanDescriptor {
  require(text.encodeToByteArray().size<=32768)
  val element=json.parseToJsonElement(text);val d=json.decodeFromJsonElement<PlanDescriptor>(element)
  require(json.encodeToJsonElement(d)==element && valid(uid,d) && transportFits(uid,d));return d
 }
 fun transportFits(uid:String,d:PlanDescriptor):Boolean {
  if(!valid(uid,d))return false
  val v=json.encodeToString(PlanDescriptor.serializer(),d);val r="{\"protocolVersion\":1,\"descriptor\":$v}"
  return r.length<=8192 && r.encodeToByteArray().size<=24576 && "{\"data\":$v}".encodeToByteArray().size<=32768 && "{\"data\":$r}".encodeToByteArray().size<=32768
 }
 fun id(uid:String,d:PlanDescriptor)=commandDigest(canonical(buildJsonObject{put("callerUid",uid);put("idempotencyKey",d.idempotencyKey)}))
 fun hash(uid:String,d:PlanDescriptor)=commandDigest(canonical(JsonObject(json.encodeToJsonElement(d).jsonObject+mapOf("callerUid" to JsonPrimitive(uid),"policyVersion" to JsonPrimitive(1)))))
 fun copy(v:ProgramVersion,uid:String,wid:String,tid:String):AssignmentCopy {
  require(v.valid(uid,wid,tid,v.versionId) && AssignmentContract.millis(v.publishedAtMillis) && json.encodeToString(ProgramVersion.serializer(),v).encodeToByteArray().size<=163840)
  return AssignmentCopy(1,v.title,v.description,v.workouts)
 }
 fun decision(d:PlanDescriptor,status:String):PlanDecision {
  require(status in statuses);val a=d.assignmentPlan;val ok=status=="plan-created"
  return PlanDecision(status,d.workspaceId,a.clientUid,a.assignmentId,if(ok)1 else 0,if(ok)1 else 0,if(ok)a.planHash else null,if(ok)a.occurrences.size else 0,if(ok)a.snapshotHash else null)
 }
 fun pending(uid:String,d:PlanDescriptor,started:Boolean):JsonObject {
  val existing=PendingInvitationCommand(uid,d.action,d.idempotencyKey,workspaceId=d.workspaceId,resolutionStarted=started)
  return JsonObject(PersistenceJson.format.encodeToJsonElement(existing).jsonObject+("assignmentPlan" to json.encodeToJsonElement(d.assignmentPlan)))
 }
 fun states(base:AccountPersistenceEnvelope,d:PlanDescriptor):List<JsonObject> {
  require(valid(base.uid,d));val record=PersistenceJson.format.encodeToJsonElement(base).jsonObject;val out=mutableListOf<JsonObject>()
  fun state(p:JsonElement,proof:JsonElement,settled:JsonElement=record.getValue("lastSettledCommand"))=JsonObject(record+mapOf("pendingInvitationCommands" to p,"pendingTerminalProof" to proof,"lastSettledCommand" to settled))
  for(started in listOf(false,true)) {
   val p=pending(base.uid,d,started);out+=state(JsonArray(listOf(p)),JsonNull)
   for(status in statuses)for(replayed in listOf(false,true)) {
    val reply=JsonObject(PersistenceJson.format.encodeToJsonElement(InvitationReply()).jsonObject+("planDecision" to JsonObject(json.encodeToJsonElement(decision(d,status)).jsonObject+("replayed" to JsonPrimitive(replayed)))))
    out+=state(JsonArray(listOf(p)),buildJsonObject{put("command",p);put("source","normal-v1");put("resolution",JsonNull);put("acknowledgment",reply)})
   }
   for(outcome in listOf("applied","not-applied-conflict","not-applied-fenced")) {
    val resolution=json.encodeToJsonElement(CommandResolutionReply(1,KIND,id(base.uid,d),HASH_VERSION,hash(base.uid,d),outcome,AssignmentContract.MAX_MILLIS))
    out+=state(JsonArray(listOf(p)),buildJsonObject{put("command",p);put("source","resolution-v1");put("resolution",resolution);put("acknowledgment",JsonNull)})
   }
  }
  for(source in listOf("normal-v1","resolution-v1"))for(outcome in if(source=="normal-v1")listOf("applied","not-applied-conflict") else listOf("applied","not-applied-conflict","not-applied-fenced")) {
   val marker=buildJsonObject {put("schemaVersion",1);put("uid",base.uid);put("commandKind",KIND);put("commandId",id(base.uid,d));put("requestHashVersion",HASH_VERSION);put("requestHash",hash(base.uid,d));put("source",source);put("outcome",outcome);put("decidedAtMillis",if(source=="resolution-v1")JsonPrimitive(AssignmentContract.MAX_MILLIS) else JsonNull)}
   val retired=state(JsonArray(emptyList()),JsonNull,buildJsonObject{put("command",JsonNull);put("source","compacted-v1");put("resolution",JsonNull);put("acknowledgment",JsonNull);put("marker",marker)})
   out+=retired;out+=JsonObject(retired+("switchMarker" to PersistenceJson.format.encodeToJsonElement(AccountSwitchMarker(departingUid=base.uid,accountEpoch=Long.MAX_VALUE,state="SwitchingOut"))))
  }
  return out
 }
 fun admission(base:AccountPersistenceEnvelope,d:PlanDescriptor)=runCatching {
  require(base.pendingInvitationCommands.isEmpty() && base.pendingTerminalProof==null && base.switchMarker==null && base.offlineAccessGrant==null && base.downloadManifests.isEmpty() && base.workoutRecoverySnapshot==null && base.mutationJournal.isEmpty() && base.pendingUploads.isEmpty())
  require(transportFits(base.uid,d) && base.lastSettledCommand?.command?.idempotencyKey!=d.idempotencyKey && base.lastSettledCommand?.marker?.commandId!=id(base.uid,d))
  pending(base.uid,d,true).toString().encodeToByteArray().size<=16384 && states(base,d).all{val n=it.toString().encodeToByteArray().size;n<=36500 && DraftBudget.swiftEncryptedUpperBound(n)<=98304}
 }.getOrDefault(false)
 fun admissionJson(prior:String,descriptor:String)=runCatching{val base=PersistenceJson.format.decodeFromString(AccountPersistenceEnvelope.serializer(),prior);admission(base,parse(base.uid,descriptor))}.getOrDefault(false)
}

/** Client-only graph contract. This does not replace any product reader. */
object PlanGraphFeasibility {
 private val json=AssignmentContract.json
 fun documents(uid:String,d:PlanDescriptor,copy:AssignmentCopy):Map<String,JsonObject> {
  require(PlanFeasibility.valid(uid,d) && AssignmentContract.copyHash(copy)==d.assignmentPlan.snapshotHash)
  val a=d.assignmentPlan
  require(copy.workouts.map{it.id}==a.occurrences.map{it.workoutId})
  val content=PublicationContent(1,d.workspaceId,a.templateId,uid,copy.title,copy.description,copy.workouts)
  require(content.valid(uid,d.workspaceId) && PublicationProtocol.contentHash(content)==a.sourceContentHash)
  val id=buildJsonObject{put("schemaVersion",1);put("workspaceId",d.workspaceId);put("clientId",a.clientUid);put("assignmentId",a.assignmentId);put("schedulePolicyVersion",2)}
  val ci=JsonObject(id+("snapshotId" to JsonPrimitive("content")))
  val provenance=buildJsonObject{put("sourceTemplateId",a.templateId);put("sourceVersionId",a.versionId);put("sourceVersionNumber",a.versionNumber);put("sourceContentHash",a.sourceContentHash)}
  val b="users/${a.clientUid}/workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}"
  val out=linkedMapOf<String,JsonObject>()
  out[b]=JsonObject(ci+provenance+buildJsonObject {put("trainerId",uid);put("snapshotHash",a.snapshotHash);put("revision",1);put("lifecycleState","ready");put("accessStatus","active");put("scheduledInstantMillis",a.occurrences.first().scheduledInstantMillis);put("accessExpiresAtMillis",a.accessExpiresAtMillis);put("timeZoneId","UTC");put("preparedAtMillis",a.preparedAtMillis);put("horizonRevision",1);put("planHash",a.planHash);put("plannedWorkoutIds",json.encodeToJsonElement(a.occurrences.map{it.plannedWorkoutId}))})
  out["$b/snapshots/content"]=JsonObject(ci+provenance+buildJsonObject{put("contentHash",a.snapshotHash);put("copyPolicyVersion",1);put("title",copy.title);put("description",copy.description);put("workoutIds",json.encodeToJsonElement(copy.workouts.map{it.id}))})
  for(w in copy.workouts) {
   out["$b/snapshots/content/workouts/${w.id}"]=JsonObject(ci+buildJsonObject{put("workoutId",w.id);put("title",w.title);put("itemIds",json.encodeToJsonElement(w.items.map{it.id}))})
   for(i in w.items)out["$b/snapshots/content/workouts/${w.id}/exercises/${i.id}"]=JsonObject(ci+buildJsonObject{put("workoutId",w.id);put("itemId",i.id)}+(json.encodeToJsonElement(i).jsonObject-"id"))
  }
  for(o in a.occurrences)out["$b/plannedWorkouts/${o.plannedWorkoutId}"]=JsonObject(ci+json.encodeToJsonElement(o).jsonObject+buildJsonObject{put("timeZoneId","UTC");put("status","planned");put("revision",1);put("horizonRevision",1)})
  val required=out.keys.filter{it!=b}.map{it.removePrefix("$b/")}.sorted()
  out["$b/manifests/download"]=JsonObject(ci+buildJsonObject{put("assignmentRevision",1);put("horizonRevision",1);put("snapshotHash",a.snapshotHash);put("planHash",a.planHash);put("requiredPaths",json.encodeToJsonElement(required))})
  out["workspaces/${d.workspaceId}/assignedPrograms/${a.assignmentId}"]=JsonObject(id+buildJsonObject{put("trainerId",uid);put("status","active");put("revision",1);put("horizonRevision",1);put("planHash",a.planHash)})
  for((path,value) in out)require(value.toString().encodeToByteArray().size<=if("/exercises/" in path)24576 else if(path.endsWith("/snapshots/content") || path.endsWith("/manifests/download"))8192 else 4096)
  return out
 }
 fun valid(client:String,wid:String,aid:String,docs:Map<String,JsonObject>):Boolean=runCatching {
  require(docs.size in 6..21)
  val b="users/$client/workspaces/$wid/assignedPrograms/$aid";val h=docs.getValue(b);val s=docs.getValue("$b/snapshots/content")
  fun JsonObject.text(key:String)=getValue(key).jsonPrimitive.content
  val workouts=s.getValue("workoutIds").jsonArray.map { id->val wId=id.jsonPrimitive.content;val w=docs.getValue("$b/snapshots/content/workouts/$wId")
   PublicationWorkout(wId,w.text("title"),w.getValue("itemIds").jsonArray.map{itemId->val i=docs.getValue("$b/snapshots/content/workouts/$wId/exercises/${itemId.jsonPrimitive.content}")
    val fields=i-setOf("schemaVersion","workspaceId","clientId","assignmentId","schedulePolicyVersion","snapshotId","workoutId","itemId")
    json.decodeFromJsonElement<PublicationItem>(JsonObject(fields+("id" to i.getValue("itemId"))))
   })
  }
  val occurrences=h.getValue("plannedWorkoutIds").jsonArray.map { id->val p=docs.getValue("$b/plannedWorkouts/${id.jsonPrimitive.content}");PlanOccurrence(id.jsonPrimitive.content,p.text("workoutId"),p.getValue("scheduledInstantMillis").jsonPrimitive.long) }
  val a=PlanCreate(aid,client,h.text("sourceTemplateId"),h.text("sourceVersionId"),h.getValue("sourceVersionNumber").jsonPrimitive.int,h.text("sourceContentHash"),1,h.text("snapshotHash"),AssignmentAuthority(1,1,1,1,1,1),h.getValue("preparedAtMillis").jsonPrimitive.long,h.getValue("accessExpiresAtMillis").jsonPrimitive.long,2,0,1,h.text("planHash"),occurrences)
  val d=PlanDescriptor("assign-program-plan",wid,resolutionCommandKey("validation"),a)
  val expected=documents(h.text("trainerId"),d,AssignmentCopy(1,s.text("title"),s.text("description"),workouts)).filterKeys{it==b || it.startsWith("$b/")}
  expected==docs
 }.getOrDefault(false)
 fun policy(client:String,wid:String,aid:String,docs:Map<String,JsonObject>):Int {
  val h=docs["users/$client/workspaces/$wid/assignedPrograms/$aid"]?:error("missing-header")
  if("schedulePolicyVersion" !in h && AssignmentClientDocuments.valid(client,wid,aid,docs))return 1
  require(h["schedulePolicyVersion"]==JsonPrimitive(2) && valid(client,wid,aid,docs));return 2
 }
}
