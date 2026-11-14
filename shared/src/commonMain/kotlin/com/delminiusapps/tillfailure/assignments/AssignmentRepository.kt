package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.InvitationClient
import com.delminiusapps.tillfailure.programs.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class AssignmentPreviewWorkout(val id:String,val title:String,val itemIds:List<String>)
@Serializable data class AssignmentPreview(val copyPolicyVersion:Int,val title:String,val description:String,val workouts:List<AssignmentPreviewWorkout>)
@Serializable data class AssignmentPreparation(val workspaceId:String,val assignment:AssignmentCreate,val preview:AssignmentPreview) {
    fun complete(uid:String,items:List<PublicationItem>):AssignmentCopy {
        val w=preview.workouts.single()
        require(items.map{it.id}==w.itemIds && items.all{it.valid()})
        val copy=AssignmentCopy(1,preview.title,preview.description,listOf(PublicationWorkout(w.id,w.title,items)))
        val content=PublicationContent(1,workspaceId,assignment.templateId,uid,copy.title,copy.description,copy.workouts)
        require(content.valid(uid,workspaceId) && PublicationProtocol.contentHash(content)==assignment.sourceContentHash && AssignmentContract.copyHash(copy)==assignment.snapshotHash)
        return copy
    }
    override fun toString()="AssignmentPreparation(redacted)"
}
@Serializable data class AssignmentCursor(val accessExpiresAtMillis:Long,val assignmentId:String)
data class AssignmentReadRequest(val action:String,val workspaceId:String,val clientUid:String,val templateId:String?=null,val versionId:String?=null,
    val scheduledInstantMillis:Long?=null,val assignment:AssignmentCreate?=null,val itemId:String?=null,val assignmentId:String?=null,
    val relativePath:String?=null,val after:AssignmentCursor?=null,val afterId:String?=null) {
    fun valid()=CustomExerciseProtocol.workspace(workspaceId) && isPathSafeAuthUid(clientUid) && when(action) {
        "assignment-prepare"->templateId?.let(CustomExerciseProtocol::validId)==true && versionId?.let(PublicationProtocol::validVersionId)==true && scheduledInstantMillis?.let(AssignmentContract::millis)==true && copy(templateId=null,versionId=null,scheduledInstantMillis=null)==AssignmentReadRequest(action,workspaceId,clientUid)
        "assignment-preview-item"->assignment?.valid()==true && assignment.clientUid==clientUid && itemId?.let(CustomExerciseProtocol::validId)==true && copy(assignment=null,itemId=null)==AssignmentReadRequest(action,workspaceId,clientUid)
        "assignment-list"->(after==null || AssignmentContract.millis(after.accessExpiresAtMillis) && CustomExerciseProtocol.validId(after.assignmentId)) && copy(after=null)==AssignmentReadRequest(action,workspaceId,clientUid)
        "client-assignment-time","assignment-get"->assignmentId?.let(CustomExerciseProtocol::validId)==true && copy(assignmentId=null)==AssignmentReadRequest(action,workspaceId,clientUid)
        "assignment-document","client-assignment-document"->assignmentId?.let(CustomExerciseProtocol::validId)==true && relativePath!=null && AssignmentReads.relative(relativePath) && copy(assignmentId=null,relativePath=null)==AssignmentReadRequest(action,workspaceId,clientUid)
        "client-assignment-list"->(afterId==null || CustomExerciseProtocol.validId(afterId)) && copy(afterId=null)==AssignmentReadRequest(action,workspaceId,clientUid)
        else->false
    }
    override fun toString()="AssignmentReadRequest(redacted)"
}
data class AssignmentReadResult(val preparation:AssignmentPreparation?=null,val item:PublicationItem?=null,val headers:List<AssignmentHeader> = emptyList(),
    val after:AssignmentCursor?=null,val afterId:String?=null,val document:JsonObject?=null,val inventory:AssignmentInventory?=null,val serverNowMillis:Long?=null,val error:String?=null) {
    override fun toString()="AssignmentReadResult(redacted)"
}
interface AssignmentRepository {
    val issuanceEnabled:Boolean get()=false
    val commands:InvitationClient
    fun read(scope:ExerciseScope,request:AssignmentReadRequest,callback:(AssignmentReadResult)->Unit):FirebaseCancellation
}
object AssignmentReads {
    private val json=AssignmentContract.json
    fun relative(p:String)=p.isEmpty() || p=="manifests/download" || Regex("snapshots/content(?:/workouts/[a-f0-9-]{36}(?:/exercises/[a-f0-9-]{36})?)?|plannedWorkouts/[a-f0-9-]{36}").matches(p)
    fun request(r:AssignmentReadRequest):String {require(r.valid());return buildJsonObject {
        put("action",r.action);put("workspaceId",r.workspaceId);put("clientUid",r.clientUid)
        r.templateId?.let{put("templateId",it)};r.versionId?.let{put("versionId",it)};r.scheduledInstantMillis?.let{put("scheduledInstantMillis",it)}
        r.assignment?.let{put("assignment",json.encodeToJsonElement(it))};r.itemId?.let{put("itemId",it)};r.assignmentId?.let{put("assignmentId",it)};r.relativePath?.let{put("relativePath",it)}
        if(r.action=="assignment-list")put("after",json.encodeToJsonElement(r.after))
        if(r.action=="client-assignment-list")put("afterId",r.afterId?.let(::JsonPrimitive)?:JsonNull)
    }.toString()}
    fun header(o:JsonElement,client:String,wid:String,aid:String?=null):AssignmentHeader {
        val h=json.decodeFromJsonElement<AssignmentHeader>(o)
        require(json.encodeToJsonElement(h)==o && h.schemaVersion==1 && h.workspaceId==wid && h.clientId==client && CustomExerciseProtocol.validId(h.assignmentId) && (aid==null || h.assignmentId==aid) &&
            h.snapshotId=="content" && h.revision==1 && h.lifecycleState=="ready" && h.accessStatus=="active" && h.timeZoneId=="UTC" &&
            CustomExerciseProtocol.validId(h.sourceTemplateId) && h.sourceVersionNumber in 1..2147483645 && h.sourceVersionId==PublicationProtocol.versionId(h.sourceVersionNumber) &&
            isPathSafeAuthUid(h.trainerId) && PublicationProtocol.hash(h.snapshotHash) && PublicationProtocol.hash(h.sourceContentHash) &&
            AssignmentContract.millis(h.scheduledInstantMillis) && AssignmentContract.millis(h.accessExpiresAtMillis) && h.accessExpiresAtMillis-h.scheduledInstantMillis==AssignmentContract.DAY_MS)
        return h
    }
    fun result(scope:ExerciseScope,r:AssignmentReadRequest,body:String?,error:String?):AssignmentReadResult {
        if(error!=null)return AssignmentReadResult(error=if(error=="unauthorized")error else "unavailable")
        return runCatching {
            require(r.valid() && scope.workspaceId==r.workspaceId && body!=null && body.encodeToByteArray().size+11<=65536)
            val o=json.parseToJsonElement(body).jsonObject
            require(o["workspaceId"]==JsonPrimitive(r.workspaceId))
            if(r.action=="assignment-prepare") {
                val p=json.decodeFromJsonElement<AssignmentPreparation>(o);val a=p.assignment;val w=p.preview.workouts.single()
                require(json.encodeToJsonElement(p)==o && AssignmentDescriptor("assign-program-version",r.workspaceId,"or1-b91f0176ec74475e8cf7406e25b7e9d3-preview",a).valid(scope.uid) &&
                    a.clientUid==r.clientUid && a.templateId==r.templateId && a.versionId==r.versionId && a.scheduledInstantMillis==r.scheduledInstantMillis && p.preview.copyPolicyVersion==1 &&
                    CustomExerciseProtocol.text(p.preview.title,80) && CustomExerciseProtocol.text(p.preview.description,240,true) && CustomExerciseProtocol.validId(w.id) && CustomExerciseProtocol.text(w.title,40) &&
                    w.itemIds.size in 1..12 && w.itemIds.distinct()==w.itemIds && w.itemIds.all(CustomExerciseProtocol::validId))
                AssignmentReadResult(preparation=p)
            } else {
                require(o["clientUid"]==JsonPrimitive(r.clientUid))
                when(r.action) {
                    "assignment-preview-item"->{require(o.keys==setOf("workspaceId","clientUid","sourceContentHash","snapshotHash","item") && o["sourceContentHash"]==JsonPrimitive(r.assignment!!.sourceContentHash) && o["snapshotHash"]==JsonPrimitive(r.assignment.snapshotHash))
                        val item=json.decodeFromJsonElement<PublicationItem>(o.getValue("item"));require(json.encodeToJsonElement(item)==o["item"] && item.valid() && item.id==r.itemId && o.getValue("item").toString().encodeToByteArray().size<=24576);AssignmentReadResult(item=item)}
                    "assignment-list","client-assignment-list"->{val own=r.action.startsWith("client-");val cursorKey=if(own)"afterId" else "after"
                        require(o.keys==setOf("workspaceId","clientUid","items",cursorKey))
                        val rows=o.getValue("items").jsonArray.map{header(it,r.clientUid,r.workspaceId)}
                        require(rows.size<=(if(own)6 else 5) && rows.map{it.assignmentId}.distinct().size==rows.size)
                        if(own) {require(r.clientUid==scope.uid && rows.zipWithNext().all{(a,b)->a.assignmentId<b.assignmentId} && (r.afterId==null || rows.all{it.assignmentId>r.afterId}))
                            val after=o.getValue("afterId").let{if(it==JsonNull)null else {require(it.jsonPrimitive.isString);it.jsonPrimitive.content}}
                            require(after==null || rows.size==5 && after==rows.last().assignmentId);AssignmentReadResult(headers=rows.take(5),afterId=if(rows.size==6)rows[4].assignmentId else after)
                        } else {val after=json.decodeFromJsonElement<AssignmentCursor?>(o.getValue("after"));require(json.encodeToJsonElement(after)==o["after"])
                            require(rows.all{it.trainerId==scope.uid} && rows.zipWithNext().all{(a,b)->a.accessExpiresAtMillis<b.accessExpiresAtMillis || a.accessExpiresAtMillis==b.accessExpiresAtMillis && a.assignmentId<b.assignmentId})
                            require(after==null || rows.size==5 && after==AssignmentCursor(rows.last().accessExpiresAtMillis,rows.last().assignmentId));AssignmentReadResult(headers=rows,after=after)} }
                    "client-assignment-time"->{require(scope.uid==r.clientUid && o.keys==setOf("workspaceId","clientUid","assignmentId","snapshotHash","accessExpiresAtMillis","serverNowMillis") && o["assignmentId"]==JsonPrimitive(r.assignmentId))
                        val now=o.getValue("serverNowMillis").jsonPrimitive.long;val expiry=o.getValue("accessExpiresAtMillis").jsonPrimitive.long
                        require(o["serverNowMillis"]==JsonPrimitive(now) && o["accessExpiresAtMillis"]==JsonPrimitive(expiry) && AssignmentContract.millis(now) && AssignmentContract.millis(expiry) && now<expiry && PublicationProtocol.hash(o.getValue("snapshotHash").jsonPrimitive.content));AssignmentReadResult(serverNowMillis=now,document=o)}
                    "assignment-get"->{require(o.keys==setOf("workspaceId","clientUid","header","inventory"));val h=header(o.getValue("header"),r.clientUid,r.workspaceId,r.assignmentId)
                        require(h.trainerId==scope.uid);val inv=json.decodeFromJsonElement<AssignmentInventory>(o.getValue("inventory"));require(json.encodeToJsonElement(inv)==o["inventory"] && inventory(inv,r.clientUid,r.workspaceId,h.assignmentId,h.snapshotHash));AssignmentReadResult(headers=listOf(h),inventory=inv)}
                    else->{require(o.keys==setOf("workspaceId","clientUid","assignmentId","relativePath","document") && o["assignmentId"]==JsonPrimitive(r.assignmentId) && o["relativePath"]==JsonPrimitive(r.relativePath))
                        if(r.action.startsWith("client-"))require(scope.uid==r.clientUid)
                        val document=o.getValue("document").jsonObject;require(document.toString().encodeToByteArray().size<=24576);AssignmentReadResult(document=document)}
                }
            }
        }.getOrDefault(AssignmentReadResult(error="unavailable"))
    }
    fun inventory(v:AssignmentInventory,client:String,wid:String,aid:String,hash:String)=v.schemaVersion==1 && v.workspaceId==wid && v.clientId==client && v.assignmentId==aid && v.snapshotId=="content" && v.assignmentRevision==1 && v.snapshotHash==hash &&
        v.requiredPaths.size in 4..15 && v.requiredPaths==v.requiredPaths.distinct().sorted() && v.requiredPaths.all{it.isNotEmpty() && it!="manifests/download" && relative(it)}
    fun callable(s:ExerciseScope,r:AssignmentReadRequest,status:Int,body:String)=runCatching {
        require(body.encodeToByteArray().size<=65536);val o=json.parseToJsonElement(body).jsonObject
        if(status==200 && o.keys==setOf("result"))result(s,r,o.getValue("result").toString(),null)
        else result(s,r,null,if(status==401 || status==403)"unauthorized" else "unavailable")
    }.getOrDefault(AssignmentReadResult(error="unavailable"))
}
