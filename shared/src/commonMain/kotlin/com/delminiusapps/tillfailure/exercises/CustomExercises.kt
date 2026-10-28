package com.delminiusapps.tillfailure.exercises

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ExerciseAuthority(val authorLifecycleRevision:Int,val authorMembershipRevision:Int,val workspaceMembershipRevision:Int) {
    fun valid()=listOf(authorLifecycleRevision,authorMembershipRevision,workspaceMembershipRevision).all { it in 1..Int.MAX_VALUE-1 }
}
@Serializable
data class CustomExerciseWrite(val exerciseId:String,val expectedRevision:Int,val authority:ExerciseAuthority,
    val name:String,val instructions:String,val muscleGroups:List<String>,val equipment:List<String>) {
    fun valid(action:String)=CustomExerciseProtocol.validId(exerciseId) && authority.valid() &&
        (if(action=="create-custom-exercise") expectedRevision==0 else action=="correct-custom-exercise" && expectedRevision in 1..Int.MAX_VALUE-2) &&
        CustomExerciseProtocol.description(name,instructions,muscleGroups,equipment)
    override fun toString()="CustomExerciseWrite(redacted)"
}
@Serializable
data class CustomExercise(val schemaVersion:Int,val policyVersion:Int,val workspaceId:String,val exerciseId:String,val ownerUid:String,
    val name:String,val instructions:String,val muscleGroups:List<String>,val equipment:List<String>,val revision:Int,val createdAtMillis:Long,val updatedAtMillis:Long) {
    fun valid(uid:String,wid:String)=schemaVersion==1 && policyVersion==1 && workspaceId==wid && ownerUid==uid &&
        CustomExerciseProtocol.validId(exerciseId) && CustomExerciseProtocol.description(name,instructions,muscleGroups,equipment) &&
        revision in 1..Int.MAX_VALUE-1 && createdAtMillis in 0..8_640_000_000_000_000L && updatedAtMillis in createdAtMillis..8_640_000_000_000_000L
    override fun toString()="CustomExercise(redacted)"
}
@Serializable
data class CustomExerciseRow(val exerciseId:String,val name:String,val muscleGroups:List<String>,val equipment:List<String>,val revision:Int)
@Serializable
data class CustomExerciseDecision(val status:String,val workspaceId:String,val exerciseId:String,val exerciseRevision:Int,val replayed:Boolean) {
    fun valid(workspace:String,p:CustomExerciseWrite,action:String)=workspaceId==workspace && exerciseId==p.exerciseId && exerciseRevision in 1..Int.MAX_VALUE-1 &&
        when(status) { "exercise-conflict" -> exerciseRevision>p.expectedRevision
            "exercise-created" -> action=="create-custom-exercise" && exerciseRevision==1
            "exercise-corrected" -> action=="correct-custom-exercise" && exerciseRevision==p.expectedRevision+1
            else -> false }
}
data class CustomExerciseReadRequest(val workspaceId:String,val action:String,val exerciseId:String?=null,val afterId:String?=null) {
    fun valid()=CustomExerciseProtocol.workspace(workspaceId) && when(action) {
        "exercise-prepare" -> exerciseId==null && afterId==null
        "exercise-list" -> exerciseId==null && (afterId==null || CustomExerciseProtocol.validId(afterId))
        "exercise-get" -> exerciseId!=null && CustomExerciseProtocol.validId(exerciseId) && afterId==null
        else -> false
    }
}
data class CustomExerciseResult(val authority:ExerciseAuthority?=null,val items:List<CustomExerciseRow> = emptyList(),val nextId:String?=null,
    val exercise:CustomExercise?=null,val error:String?=null) { override fun toString()="CustomExerciseResult(redacted)" }
interface CustomExerciseRepository {
    val commands:InvitationClient
    fun newExerciseId():String
    fun read(scope:ExerciseScope,request:CustomExerciseReadRequest,callback:(CustomExerciseResult)->Unit):FirebaseCancellation
}
object CustomExerciseProtocol {
    val muscles=listOf("arms","back","chest","core","legs","shoulders","whole-body")
    val equipment=listOf("band","barbell","bodyweight","cable","dumbbell","machine")
    fun action(a:String)=a in setOf("create-custom-exercise","correct-custom-exercise")
    fun validId(id:String)=Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}").matches(id)
    fun workspace(id:String)=isPathSafeAuthUid(id) && '.' !in id
    // ECMAScript TrimString, shared with the TypeScript admission validator.
    private fun space(c:Char)=c.code in 9..13 || c.code in listOf(32,160,5760,8232,8233,8239,8287,12288,65279) || c.code in 8192..8202
    fun text(v:String,max:Int,multiline:Boolean=false):Boolean {
        if(v.isEmpty() || space(v.first()) || space(v.last()) || v.encodeToByteArray().size>max*4) return false
        var i=0;var count=0
        while(i<v.length) {val c=v[i++].code
            if(c in 0xd800..0xdbff) {if(i>=v.length || v[i++].code !in 0xdc00..0xdfff)return false}
            else if(c in 0xdc00..0xdfff || c<32 && !(multiline && c==10) || c in 127..159)return false
            if(++count>max)return false
        };return true
    }
    private fun labels(v:List<String>,tokens:List<String>)=v.size<=3 && v.all { it in tokens } && v.zipWithNext().all { (a,b)->a<b }
    fun description(name:String,instructions:String,muscleGroups:List<String>,selectedEquipment:List<String>)=text(name,120) && text(instructions,2000,true) &&
        labels(muscleGroups,muscles) && labels(selectedEquipment,equipment) && ("bodyweight" !in selectedEquipment || selectedEquipment.size==1)
    fun write(workspace:String,p:CustomExerciseWrite,key:String)=InvitationRequest(if(p.expectedRevision==0) "create-custom-exercise" else "correct-custom-exercise",workspaceId=workspace,idempotencyKey=key,exerciseWrite=p)
    fun request(r:CustomExerciseReadRequest):String {require(r.valid());return buildJsonObject {
        put("action",r.action);put("workspaceId",r.workspaceId)
        if(r.action=="exercise-list")put("afterId",r.afterId?.let(::JsonPrimitive)?:JsonNull)
        r.exerciseId?.let {put("exerciseId",it)}
    }.toString()}
    private fun numbers(o:JsonObject,keys:List<String>) {require(keys.all { (o[it] as? JsonPrimitive)?.let { n->!n.isString && n.longOrNull!=null }==true })}
    private fun strings(o:JsonObject,keys:List<String>) {require(keys.all { (o[it] as? JsonPrimitive)?.isString==true })}
    private fun stringArray(o:JsonObject,key:String) {require(o[key]?.jsonArray?.all { (it as? JsonPrimitive)?.isString==true }==true)}
    fun parseDecision(request:InvitationRequest,json:String?):InvitationReply {
        require(json!=null && json.encodeToByteArray().size<=4096)
        val o=InvitationJson.format.parseToJsonElement(json).jsonObject
        require(o.keys==setOf("status","workspaceId","exerciseId","exerciseRevision","replayed"));numbers(o,listOf("exerciseRevision"))
        require(listOf("status","workspaceId","exerciseId").all { o[it]?.jsonPrimitive?.isString==true })
        require(o["replayed"]?.jsonPrimitive?.let { !it.isString && it.booleanOrNull!=null }==true)
        return InvitationReply(exerciseDecision=InvitationJson.format.decodeFromJsonElement<CustomExerciseDecision>(o)).also {require(validDecision(request,it))}
    }
    fun writeRequestShape(r:InvitationRequest)=action(r.action) && r.copy(action="correct-custom-exercise",workspaceId=null,idempotencyKey=null,exerciseWrite=null)==InvitationRequest("correct-custom-exercise")
    fun validDecision(r:InvitationRequest,v:InvitationReply)=v.copy(exerciseDecision=null)==InvitationReply() && action(r.action) &&
        writeRequestShape(r) && workspace(r.workspaceId?:"") && r.idempotencyKey!=null &&
        r.exerciseWrite?.let { it.valid(r.action) && v.exerciseDecision?.valid(r.workspaceId?:"",it,r.action)==true }==true
    fun result(scope:ExerciseScope,r:CustomExerciseReadRequest,json:String?,error:String?):CustomExerciseResult {
        if(error!=null)return CustomExerciseResult(error=if(error in setOf("unauthorized","unauthenticated","unverified-email")) "unauthorized" else "unavailable")
        return runCatching {
            require(r.valid() && r.workspaceId==scope.workspaceId && json!=null && json.encodeToByteArray().size<=if(r.action=="exercise-list")65536 else if(r.action=="exercise-get")24576 else 4096)
            val o=InvitationJson.format.parseToJsonElement(json).jsonObject
            require(o.keys==setOf("workspaceId","authority")+when(r.action){"exercise-list"->setOf("items","nextId");"exercise-get"->setOf("exercise");else->emptySet()})
            require(o["workspaceId"]?.jsonPrimitive?.let {it.isString && it.content==scope.workspaceId}==true)
            val a=o.getValue("authority").jsonObject;numbers(a,listOf("authorLifecycleRevision","authorMembershipRevision","workspaceMembershipRevision"))
            val authority=InvitationJson.format.decodeFromJsonElement<ExerciseAuthority>(a);require(authority.valid())
            if(r.action=="exercise-get") {
                val e=o.getValue("exercise").jsonObject;strings(e,listOf("workspaceId","exerciseId","ownerUid","name","instructions"));stringArray(e,"muscleGroups");stringArray(e,"equipment");numbers(e,listOf("schemaVersion","policyVersion","revision","createdAtMillis","updatedAtMillis"))
                val exercise=InvitationJson.format.decodeFromJsonElement<CustomExercise>(e)
                require(exercise.valid(scope.uid,scope.workspaceId) && exercise.exerciseId==r.exerciseId && e.toString().encodeToByteArray().size<=16384)
                CustomExerciseResult(authority=authority,exercise=exercise)
            } else if(r.action=="exercise-list") {
                val rows=o.getValue("items").jsonArray.map {element->
                    numbers(element.jsonObject,listOf("revision"));strings(element.jsonObject,listOf("exerciseId","name"));stringArray(element.jsonObject,"muscleGroups");stringArray(element.jsonObject,"equipment")
                    InvitationJson.format.decodeFromJsonElement<CustomExerciseRow>(element).also {require(validId(it.exerciseId) && description(it.name,"x",it.muscleGroups,it.equipment) && it.revision in 1..Int.MAX_VALUE-1)}
                }
                val next=o["nextId"]?.let { if(it==JsonNull)null else {require(it.jsonPrimitive.isString);it.jsonPrimitive.content} }
                require(rows.size<=20 && rows.zipWithNext().all{(a,b)->a.exerciseId<b.exerciseId} && (r.afterId==null || rows.all{it.exerciseId>r.afterId}) &&
                    (next==null || rows.size==20 && next==rows.last().exerciseId))
                CustomExerciseResult(authority=authority,items=rows,nextId=next)
            } else CustomExerciseResult(authority=authority)
        }.getOrDefault(CustomExerciseResult(error="malformed"))
    }
    fun callable(scope:ExerciseScope,r:CustomExerciseReadRequest,status:Int,body:String):CustomExerciseResult=runCatching {
        require(body.encodeToByteArray().size<=65536);val o=InvitationJson.format.parseToJsonElement(body).jsonObject
        if(status==200 && o.keys==setOf("result"))result(scope,r,o.getValue("result").toString(),null)
        else result(scope,r,null,if(status==401 || status==403)"unauthorized" else "unavailable")
    }.getOrDefault(CustomExerciseResult(error="malformed"))
}
/** New-kind admission only: legacy envelopes are never rewritten to make space. */
object CustomExerciseBudget {
    fun pendingFits(p:PendingInvitationCommand):Boolean=runCatching {
        val resolver=CommandResolution.request(p)
        resolver.length<=8192 && resolver.encodeToByteArray().size<=24576 && ("{\"data\":$resolver}").encodeToByteArray().size<=32768 &&
            ("{\"data\":${CommandResolution.descriptor(p)}}").encodeToByteArray().size<=32768 &&
            PersistenceJson.format.encodeToString(PendingInvitationCommand.serializer(),p).encodeToByteArray().size<=16384
    }.getOrDefault(false)
    fun admission(record:AccountPersistenceEnvelope,p:PendingInvitationCommand):Boolean=runCatching {
        require(pendingFits(p))
        val d=p.exerciseWrite?:error("payload")
        val decisions=listOf(CustomExerciseDecision(if(p.action=="create-custom-exercise")"exercise-created" else "exercise-corrected",p.workspaceId!!,d.exerciseId,d.expectedRevision+1,true),
            CustomExerciseDecision("exercise-conflict",p.workspaceId,d.exerciseId,Int.MAX_VALUE-1,true))
        val proofs=decisions.map {CommandTerminalProof(p,"normal-v1",acknowledgment=InvitationReply(exerciseDecision=it))}+
            listOf("applied","not-applied-conflict","not-applied-fenced").map {CommandTerminalProof(p,"resolution-v1",resolution=CommandResolutionReply(1,CommandResolution.kind(p),CommandResolution.id(p),CommandResolution.version(p),CommandResolution.hash(p),it,8_640_000_000_000_000L))}
        (listOf(record.copy(pendingInvitationCommands=listOf(p)))+proofs.map {record.copy(pendingInvitationCommands=listOf(p),pendingTerminalProof=it)}).all { fits(it) }
    }.getOrDefault(false)
    fun fits(record:AccountPersistenceEnvelope)=PersistenceJson.format.encodeToString(AccountPersistenceEnvelope.serializer(),record).encodeToByteArray().size<=65536
}
