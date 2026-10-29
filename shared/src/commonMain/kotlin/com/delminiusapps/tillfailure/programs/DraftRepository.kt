package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.InvitationClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SavedDraft(val schemaVersion:Int,val policyVersion:Int,val workspaceId:String,val templateId:String,val ownerUid:String,
    val revision:Int,val createdAtMillis:Long,val updatedAtMillis:Long,val title:String,val description:String,val workouts:List<DraftWorkout>) {
    fun write(authority:ExerciseAuthority)=DraftSave(templateId,revision,authority,title,description,workouts)
    fun valid(uid:String,wid:String)=schemaVersion==1 && policyVersion==1 && workspaceId==wid && ownerUid==uid && revision in 1..Int.MAX_VALUE-1 &&
        createdAtMillis in 0..8640000000000000L && updatedAtMillis in createdAtMillis..8640000000000000L && write(ExerciseAuthority(1,1,1)).copy(expectedRevision=0).valid()
    override fun toString()="SavedDraft(redacted)"
}
@Serializable
data class DraftRow(val templateId:String,val title:String,val revision:Int)
data class DraftReadRequest(val workspaceId:String,val action:String,val templateId:String?=null,val afterId:String?=null) {
    fun valid()=CustomExerciseProtocol.workspace(workspaceId) && when(action) {
        "draft-prepare" -> templateId==null && afterId==null
        "draft-list" -> templateId==null && (afterId==null || CustomExerciseProtocol.validId(afterId))
        "draft-get" -> afterId==null && templateId?.let(CustomExerciseProtocol::validId)==true
        else -> false
    }
}
data class DraftReadResult(val authority:ExerciseAuthority?=null,val draft:SavedDraft?=null,val items:List<DraftRow> = emptyList(),val nextId:String?=null,val error:String?=null) {
    override fun toString()="DraftReadResult(redacted)"
}
interface DraftRepository {
    val commands:InvitationClient
    val systemExercises:SystemExerciseRepository
    val customExercises:CustomExerciseRepository
    fun newId():String
    fun read(scope:ExerciseScope,request:DraftReadRequest,callback:(DraftReadResult)->Unit):FirebaseCancellation
}
object DraftReads {
    private val json=Json { explicitNulls=true }
    fun request(r:DraftReadRequest):String {require(r.valid());return buildJsonObject {
        put("action",r.action);put("workspaceId",r.workspaceId)
        if(r.action=="draft-list")put("afterId",r.afterId?.let(::JsonPrimitive)?:JsonNull)
        r.templateId?.let { put("templateId",it) }
    }.toString()}
    private fun number(o:JsonObject,k:String)=require(o[k]?.jsonPrimitive?.let { !it.isString && it.longOrNull!=null }==true)
    private fun string(o:JsonObject,k:String)=require(o[k]?.jsonPrimitive?.isString==true)
    fun result(scope:ExerciseScope,r:DraftReadRequest,body:String?,error:String?):DraftReadResult {
        if(error!=null)return DraftReadResult(error=if(error in setOf("unauthorized","unauthenticated","unverified-email"))"unauthorized" else "unavailable")
        return runCatching {
            require(r.valid() && r.workspaceId==scope.workspaceId && body!=null && body.encodeToByteArray().size<=if(r.action=="draft-list")32768 else if(r.action=="draft-get")24576 else 4096)
            val o=json.parseToJsonElement(body).jsonObject
            require(o.keys==setOf("workspaceId","authority")+when(r.action){"draft-list"->setOf("items","nextId");"draft-get"->setOf("draft");else->emptySet()})
            string(o,"workspaceId");require(o.getValue("workspaceId").jsonPrimitive.content==scope.workspaceId)
            val a=o.getValue("authority").jsonObject
            listOf("authorLifecycleRevision","authorMembershipRevision","workspaceMembershipRevision").forEach { number(a,it) }
            val authority=json.decodeFromJsonElement<ExerciseAuthority>(a);require(authority.valid())
            when(r.action) {
                "draft-get" -> {
                    val d=o.getValue("draft").jsonObject
                    listOf("schemaVersion","policyVersion","revision","createdAtMillis","updatedAtMillis").forEach {number(d,it)}
                    listOf("workspaceId","templateId","ownerUid","title","description").forEach {string(d,it)}
                    // Re-encode/compare after strict typed nested decoding to reject coerced numbers and missing nulls.
                    val draft=json.decodeFromJsonElement<SavedDraft>(d)
                    require(json.encodeToJsonElement(draft)==d && draft.valid(scope.uid,scope.workspaceId) && draft.templateId==r.templateId && d.toString().encodeToByteArray().size<=16384)
                    DraftReadResult(authority=authority,draft=draft)
                }
                "draft-list" -> {
                    val rows=o.getValue("items").jsonArray.map { e->
                        val item=e.jsonObject;string(item,"templateId");string(item,"title");number(item,"revision")
                        json.decodeFromJsonElement<DraftRow>(e).also {require(CustomExerciseProtocol.validId(it.templateId) && (it.title.isEmpty() || CustomExerciseProtocol.text(it.title,80)) && it.revision in 1..Int.MAX_VALUE-1)}
                    }
                    val next=o.getValue("nextId").let {if(it==JsonNull)null else {require(it.jsonPrimitive.isString);it.jsonPrimitive.content}}
                    require(rows.size<=20 && rows.zipWithNext().all { (a,b)->a.templateId<b.templateId } && (r.afterId==null || rows.all {it.templateId>r.afterId}) && (next==null || rows.size==20 && next==rows.last().templateId))
                    DraftReadResult(authority=authority,items=rows,nextId=next)
                }
                else -> DraftReadResult(authority=authority)
            }
        }.getOrDefault(DraftReadResult(error="unavailable"))
    }
    fun callable(scope:ExerciseScope,r:DraftReadRequest,status:Int,body:String):DraftReadResult=runCatching {
        require(body.encodeToByteArray().size<=65536);val o=json.parseToJsonElement(body).jsonObject
        if(status==200 && o.keys==setOf("result"))result(scope,r,o.getValue("result").toString(),null)
        else result(scope,r,null,if(status==401 || status==403)"unauthorized" else "unavailable")
    }.getOrDefault(DraftReadResult(error="unavailable"))
}
