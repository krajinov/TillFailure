package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.CustomExerciseProtocol
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.serialization.json.*

object DraftProtocol {
    fun write(workspace:String,draft:DraftSave,key:String)=InvitationRequest("save-program-draft",workspaceId=workspace,idempotencyKey=key,draftSave=draft)
    fun validRequest(request: InvitationRequest) = request.action=="save-program-draft" &&
        request.copy(workspaceId=null,idempotencyKey=null,draftSave=null)==InvitationRequest("save-program-draft") &&
        CustomExerciseProtocol.workspace(request.workspaceId?:"") && DraftSerialization.coveredKey(request.idempotencyKey?:"") && request.draftSave?.valid()==true
    fun parse(request: InvitationRequest, json: String?): InvitationReply {
        require(validRequest(request) && json!=null && json.encodeToByteArray().size<=4096)
        val o=InvitationJson.format.parseToJsonElement(json).jsonObject
        require(o.keys==setOf("status","workspaceId","templateId","templateRevision","replayed"))
        require(listOf("status","workspaceId","templateId").all { (o[it] as? JsonPrimitive)?.isString==true })
        require(o["templateRevision"]?.jsonPrimitive?.let { !it.isString && it.intOrNull!=null }==true)
        require(o["replayed"]?.jsonPrimitive?.let { !it.isString && it.booleanOrNull!=null }==true)
        return InvitationReply(draftDecision=InvitationJson.format.decodeFromJsonElement<DraftDecision>(o)).also {
            require(it.draftDecision!!.valid(request.workspaceId,request.draftSave!!))
        }
    }
}
