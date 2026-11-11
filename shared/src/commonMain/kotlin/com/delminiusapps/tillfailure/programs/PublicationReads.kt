package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class VersionRow(val versionId:String,val versionNumber:Int,val title:String,val draftRevision:Int,val publishedAtMillis:Long,val contentHash:String)
data class PublicationReadRequest(val workspaceId:String,val templateId:String,val action:String,val expectedRevision:Int?=null,
    val expectedDraftHash:String?=null,val versionId:String?=null,val beforeId:String?=null) {
    fun valid()=CustomExerciseProtocol.workspace(workspaceId) && CustomExerciseProtocol.validId(templateId) && when(action) {
        "publication-prepare"->expectedRevision in 1..Int.MAX_VALUE-2 && expectedDraftHash?.let(PublicationProtocol::hash)==true && versionId==null && beforeId==null
        "version-get"->versionId?.let(PublicationProtocol::validVersionId)==true && expectedRevision==null && expectedDraftHash==null && beforeId==null
        "version-list"->(beforeId==null || PublicationProtocol.validVersionId(beforeId)) && expectedRevision==null && expectedDraftHash==null && versionId==null
        else->false
    }
    val responseLimit get()=if(action in setOf("publication-prepare","version-get"))PublicationProtocol.RESPONSE_BYTES else 16384
}
data class PublicationReadResult(val preview:PublicationPreview?=null,val version:ProgramVersion?=null,val items:List<VersionRow> = emptyList(),val nextId:String?=null,val error:String?=null) {
    override fun toString()="PublicationReadResult(redacted)"
}
object PublicationReads {
    private val json=PublicationProtocol.json
    fun request(r:PublicationReadRequest):String {require(r.valid());return buildJsonObject {
        put("action",r.action);put("workspaceId",r.workspaceId);put("templateId",r.templateId)
        r.expectedRevision?.let{put("expectedRevision",it)};r.versionId?.let{put("versionId",it)}
        if(r.action=="version-list")put("beforeId",r.beforeId?.let(::JsonPrimitive)?:JsonNull)
    }.toString()}
    fun result(s:ExerciseScope,r:PublicationReadRequest,body:String?,error:String?):PublicationReadResult {
        if(error!=null)return PublicationReadResult(error=if(error in setOf("unauthorized","unauthenticated","unverified-email"))"unauthorized" else "unavailable")
        return runCatching {
            require(r.valid() && r.workspaceId==s.workspaceId && body!=null && body.encodeToByteArray().size+11<=r.responseLimit)
            val o=json.parseToJsonElement(body).jsonObject
            require(o["workspaceId"]==JsonPrimitive(s.workspaceId))
            when(r.action) {
                "publication-prepare"->{val p=json.decodeFromJsonElement<PublicationPreview>(o)
                    require(json.encodeToJsonElement(p)==o && p.valid(s.uid,s.workspaceId,r.templateId,r.expectedRevision!!,r.expectedDraftHash!!));PublicationReadResult(preview=p)}
                "version-get"->{require(o.keys==setOf("workspaceId","version"));val element=o.getValue("version");val v=json.decodeFromJsonElement<ProgramVersion>(element)
                    require(element.toString().encodeToByteArray().size<=160*1024 && json.encodeToJsonElement(v)==element && v.valid(s.uid,s.workspaceId,r.templateId,r.versionId!!));PublicationReadResult(version=v)}
                else->{require(o.keys==setOf("workspaceId","templateId","items","nextId") && o["templateId"]==JsonPrimitive(r.templateId))
                    val rows=json.decodeFromJsonElement<List<VersionRow>>(o.getValue("items"));require(json.encodeToJsonElement(rows)==o["items"])
                    val next=o.getValue("nextId").let{if(it==JsonNull)null else {require(it.jsonPrimitive.isString);it.jsonPrimitive.content}}
                    require(rows.size<=5 && rows.all{it.versionNumber in 1..Int.MAX_VALUE-2 && it.versionId==PublicationProtocol.versionId(it.versionNumber) &&
                        it.draftRevision in it.versionNumber..Int.MAX_VALUE-2 && CustomExerciseProtocol.text(it.title,80) && PublicationProtocol.hash(it.contentHash) && it.publishedAtMillis in 0..8640000000000000L} &&
                        rows.zipWithNext().all{(a,b)->a.versionId>b.versionId} && (r.beforeId==null || rows.all{it.versionId<r.beforeId}) && (next==null || rows.size==5 && next==rows.last().versionId))
                    PublicationReadResult(items=rows,nextId=next)}
            }
        }.getOrDefault(PublicationReadResult(error="unavailable"))
    }
    fun callable(s:ExerciseScope,r:PublicationReadRequest,status:Int,body:String)=runCatching {
        require(body.encodeToByteArray().size<=r.responseLimit);val o=json.parseToJsonElement(body).jsonObject
        if(status==200 && o.keys==setOf("result"))result(s,r,o.getValue("result").toString(),null)
        else result(s,r,null,if(status==401 || status==403)"unauthorized" else "unavailable")
    }.getOrDefault(PublicationReadResult(error="unavailable"))
}
