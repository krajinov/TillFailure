package com.delminiusapps.tillfailure.clients

import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

fun canonicalNoteBody(value:String)=value.replace("\r\n","\n").replace('\r','\n')
fun validNoteBody(value:String):Boolean {
    // ECMAScript TrimString whitespace, matching the server exactly (including BOM).
    val blank=value.all { val c=it.code; c in 9..13 || c in listOf(32,160,5760,8232,8233,8239,8287,12288,65279) || c in 8192..8202 }
    if(blank || value.encodeToByteArray().size>8000) return false
    var i=0;var scalars=0
    while(i<value.length) {
        val c=value[i++].code
        if(c in 0xd800..0xdbff) { if(i>=value.length || value[i++].code !in 0xdc00..0xdfff) return false }
        else if(c in 0xdc00..0xdfff || c<32 && c!=9 && c!=10 || c in 127..159) return false
        if(++scalars>2000) return false
    }
    return true
}
@Serializable
data class NoteAuthority(val authorMembershipRevision:Int,val subjectMembershipRevision:Int,val authorLifecycleRevision:Int,val subjectLifecycleRevision:Int) {
    fun valid()=listOf(authorMembershipRevision,subjectMembershipRevision,authorLifecycleRevision,subjectLifecycleRevision).all { it in 1..Int.MAX_VALUE-1 }
}
@Serializable
data class NoteSave(val clientUid:String,val body:String,val bindingNonce:String,val expectedRevision:Int,
    val authorMembershipRevision:Int,val subjectMembershipRevision:Int,val authorLifecycleRevision:Int,val subjectLifecycleRevision:Int) {
    fun valid()=isPathSafeAuthUid(clientUid) && validNoteBody(body) && Regex("[a-f0-9]{64}").matches(bindingNonce) && expectedRevision in 0..Int.MAX_VALUE-2 && authority().valid()
    fun authority()=NoteAuthority(authorMembershipRevision,subjectMembershipRevision,authorLifecycleRevision,subjectLifecycleRevision)
    override fun toString()="NoteSave(redacted)"
}
@Serializable
data class NoteValue(val body:String,val revision:Int,val createdAtMillis:Long,val updatedAtMillis:Long) {
    fun valid()=validNoteBody(body) && revision in 1..Int.MAX_VALUE-1 && createdAtMillis in 0..8_640_000_000_000_000L && updatedAtMillis in createdAtMillis..8_640_000_000_000_000L
    override fun toString()="NoteValue(redacted)"
}
@Serializable
data class NoteRead(val status:String,val workspaceId:String,val clientUid:String,val note:NoteValue?,val authority:NoteAuthority) {
    fun valid()=status=="note" && isPathSafeAuthUid(workspaceId) && '.' !in workspaceId && isPathSafeAuthUid(clientUid) && authority.valid() && note?.valid()!=false
    override fun toString()="NoteRead(redacted)"
}
@Serializable
data class NoteDecision(val status:String,val workspaceId:String,val clientUid:String,val noteRevision:Int,val replayed:Boolean) {
    fun valid(workspace:String,p:NoteSave)=workspaceId==workspace && clientUid==p.clientUid && noteRevision in 1..Int.MAX_VALUE-1 &&
        when(status) { "note-saved" -> noteRevision==p.expectedRevision+1; "note-conflict" -> noteRevision>p.expectedRevision; else -> false }
}
object TrainerNoteProtocol {
    fun saveFromRead(read:NoteRead,body:String,nonce:String,key:String):InvitationRequest {
        require(read.valid());val a=read.authority
        return save(read.workspaceId,NoteSave(read.clientUid,canonicalNoteBody(body),nonce,read.note?.revision?:0,a.authorMembershipRevision,a.subjectMembershipRevision,a.authorLifecycleRevision,a.subjectLifecycleRevision),key)
    }
    fun read(workspace:String,clientUid:String)=InvitationRequest("note-get",workspaceId=workspace,clientUid=clientUid)
    fun save(workspace:String,p:NoteSave,key:String)=InvitationRequest("save-trainer-note",workspaceId=workspace,idempotencyKey=key,noteSave=p)
    fun parse(request:InvitationRequest,json:String?):InvitationReply {
        require(json!=null && json.encodeToByteArray().size<=32768)
        val root=InvitationJson.format.parseToJsonElement(json) as? JsonObject ?: error("note-invalid")
        fun fields(o:JsonObject,strings:Set<String>,numbers:Set<String>,others:Set<String> = emptySet()) {
            require(o.keys==strings+numbers+others)
            require(strings.all { (o[it] as? JsonPrimitive)?.isString==true })
            require(numbers.all { (o[it] as? JsonPrimitive)?.let { n -> !n.isString && n.longOrNull!=null }==true })
        }
        if(request.action=="note-get") {
            fields(root,setOf("status","workspaceId","clientUid"),emptySet(),setOf("note","authority"))
            val a=root["authority"] as? JsonObject ?: error("note-invalid")
            fields(a,emptySet(),setOf("authorMembershipRevision","subjectMembershipRevision","authorLifecycleRevision","subjectLifecycleRevision"))
            if(root["note"]!=JsonNull) fields(root["note"] as? JsonObject ?: error("note-invalid"),setOf("body"),setOf("revision","createdAtMillis","updatedAtMillis"))
            return InvitationReply(noteRead=InvitationJson.format.decodeFromJsonElement<NoteRead>(root))
        }
        fields(root,setOf("status","workspaceId","clientUid"),setOf("noteRevision"),setOf("replayed"))
        require((root["replayed"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull!=null }==true)
        return InvitationReply(noteDecision=InvitationJson.format.decodeFromJsonElement<NoteDecision>(root))
    }
    fun valid(request:InvitationRequest,reply:InvitationReply):Boolean {
        if(reply.copy(noteRead=null,noteDecision=null)!=InvitationReply() || request.onboarding!=null || request.profileUpdate!=null || request.revocation!=null || request.resolution!=null ||
            request.email!=null || request.invitationId!=null || request.expectedVersion!=null || request.token!=null) return false
        return if(request.action=="note-get") request.idempotencyKey==null && request.noteSave==null && reply.noteDecision==null && reply.noteRead?.let { it.valid() && it.workspaceId==request.workspaceId && it.clientUid==request.clientUid }==true
        else request.clientUid==null && request.idempotencyKey!=null && reply.noteRead==null && request.noteSave?.let { it.valid() && reply.noteDecision?.valid(request.workspaceId?:"",it)==true }==true
    }
}
