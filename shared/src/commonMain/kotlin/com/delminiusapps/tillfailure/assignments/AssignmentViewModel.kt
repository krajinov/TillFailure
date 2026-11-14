package com.delminiusapps.tillfailure.assignments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delminiusapps.tillfailure.exercises.ExerciseScope
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.programs.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

data class AssignmentScope(val uid:String,val workspaceId:String,val clientUid:String,val role:String,val epoch:Long) {
    fun read()=ExerciseScope(uid,workspaceId,epoch)
}
data class AssignmentState(val scope:AssignmentScope?=null,val busy:Boolean=false,val error:String?=null,val headers:List<AssignmentHeader> = emptyList(),
    val after:AssignmentCursor?=null,val afterId:String?=null,val templates:List<DraftRow> = emptyList(),val templateNext:String?=null,val templateId:String?=null,
    val versions:List<VersionRow> = emptyList(),val versionNext:String?=null,val versionId:String?=null,val schedule:String="",val preparation:AssignmentPreparation?=null,
    val preview:AssignmentCopy?=null,val confirm:Boolean=false,val pending:PendingInvitationCommand?=null,val content:AssignmentCopy?=null,val detail:AssignmentHeader?=null,val plan:AssignmentPlanDocument?=null,val notice:String?=null)
sealed interface AssignmentEvent {
    data object Refresh:AssignmentEvent
    data object Next:AssignmentEvent
    data object NextTemplates:AssignmentEvent
    data object NextVersions:AssignmentEvent
    data class Template(val id:String):AssignmentEvent
    data class Version(val id:String):AssignmentEvent
    data class Schedule(val text:String):AssignmentEvent
    data object Prepare:AssignmentEvent
    data object Confirm:AssignmentEvent
    data object Submit:AssignmentEvent
    data object Cancel:AssignmentEvent
    data class Open(val id:String):AssignmentEvent
    data object CloseDetail:AssignmentEvent
}

/** All unsent content is destination memory. UID/role/workspace/epoch and generation fence
 * every callback. Transport cancellation never cancels an admitted protected command. */
class AssignmentViewModel(private val repository:AssignmentRepository,private val drafts:DraftRepository?=null):ViewModel() {
    private val mutable=MutableStateFlow(AssignmentState());val state=mutable.asStateFlow()
    private var generation=0L;private var active=false;private val operations=mutableListOf<FirebaseCancellation>();private var expiry:Job?=null
    private fun invalidate(){generation++;operations.forEach{it.cancel()};operations.clear();expiry?.cancel();expiry=null}
    fun attach(scope:AssignmentScope) {invalidate();active=true;mutable.value=AssignmentState(scope=scope,schedule=Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()+3600000).toString());refresh()}
    fun clear(){invalidate();active=false;mutable.value=AssignmentState()}
    private fun owned(scope:AssignmentScope,g:Long)=active && mutable.value.scope==scope && generation==g
    private fun read(r:AssignmentReadRequest,block:(AssignmentReadResult)->Unit) {
        val scope=mutable.value.scope?:return;val g=generation
        operations+=repository.read(scope.read(),r) { result->if(owned(scope,g)) {if(result.error!=null)mutable.value=mutable.value.copy(busy=false,error="Content unavailable. Refresh to recheck access.",content=null,preview=null,confirm=false) else block(result)} }
    }
    private fun refresh() {
        val scope=mutable.value.scope?:return;invalidate();mutable.value=mutable.value.copy(busy=true,error=null,content=null,detail=null,preview=null,preparation=null,confirm=false,pending=repository.commands.pending(scope.uid))
        read(AssignmentReadRequest(if(scope.role=="trainer")"assignment-list" else "client-assignment-list",scope.workspaceId,scope.clientUid)) {r->
            mutable.value=mutable.value.copy(busy=false,headers=r.headers,after=r.after,afterId=r.afterId)
            if(scope.role=="trainer" && drafts!=null && repository.commands.pending(scope.uid)==null)templates(null)
        }
    }
    private fun templates(after:String?) {
        val scope=mutable.value.scope?:return;val g=generation
        operations+=drafts?.read(scope.read(),DraftReadRequest(scope.workspaceId,"draft-list",afterId=after)) {r->if(owned(scope,g))mutable.value=mutable.value.copy(templates=if(r.error==null)r.items else emptyList(),templateNext=r.nextId,error=r.error?.let{"Versions unavailable. Refresh to recheck access."}) }?:return
    }
    private fun versions(id:String,before:String?=null) {
        val scope=mutable.value.scope?:return;invalidate();mutable.value=mutable.value.copy(busy=true,templateId=id,versionId=null,versions=emptyList(),preparation=null,preview=null,confirm=false,error=null);val g=generation
        operations+=drafts?.publication(scope.read(),PublicationReadRequest(scope.workspaceId,id,"version-list",beforeId=before)) {r->if(owned(scope,g))mutable.value=mutable.value.copy(busy=false,versions=r.items,versionNext=r.nextId,error=r.error?.let{"Versions unavailable."}) }?:return
    }
    fun onEvent(event:AssignmentEvent) {
        val s=mutable.value;val scope=s.scope?:return
        when(event) {
            AssignmentEvent.Refresh->refresh()
            AssignmentEvent.Next->{if(!s.busy && (s.after!=null || s.afterId!=null)){invalidate();mutable.value=s.copy(busy=true,error=null);read(AssignmentReadRequest(if(scope.role=="trainer")"assignment-list" else "client-assignment-list",scope.workspaceId,scope.clientUid,after=s.after,afterId=s.afterId)){r->mutable.value=mutable.value.copy(busy=false,headers=r.headers,after=r.after,afterId=r.afterId)}}}
            AssignmentEvent.NextTemplates->if(!s.busy && s.templateNext!=null)templates(s.templateNext)
            AssignmentEvent.NextVersions->if(!s.busy && s.templateId!=null && s.versionNext!=null)versions(s.templateId,s.versionNext)
            is AssignmentEvent.Template->if(!s.busy && s.templates.any{it.templateId==event.id})versions(event.id)
            is AssignmentEvent.Version->if(!s.busy && s.versions.any{it.versionId==event.id})mutable.value=s.copy(versionId=event.id,preparation=null,preview=null,confirm=false)
            is AssignmentEvent.Schedule->if(!s.busy && event.text.length<=40)mutable.value=s.copy(schedule=event.text,preparation=null,preview=null,confirm=false)
            AssignmentEvent.Prepare->{if(s.busy || scope.role!="trainer" || s.pending!=null || s.templateId==null || s.versionId==null)return
                val instant=runCatching{require(s.schedule.endsWith("Z"));Instant.parse(s.schedule).toEpochMilliseconds()}.getOrNull()
                if(instant==null){mutable.value=s.copy(error="Enter an explicit UTC instant ending in Z.");return}
                invalidate();mutable.value=s.copy(busy=true,error=null,preview=null,preparation=null,confirm=false)
                read(AssignmentReadRequest("assignment-prepare",scope.workspaceId,scope.clientUid,s.templateId,s.versionId,instant)){r->
                    val p=r.preparation?:return@read;val items=mutableListOf<PublicationItem>();val ids=p.preview.workouts.single().itemIds
                    fun next(index:Int) {if(index==ids.size){val copy=runCatching{p.complete(scope.uid,items)}.getOrNull();mutable.value=mutable.value.copy(busy=false,preparation=if(copy!=null)p else null,preview=copy,error=if(copy==null)"Complete preview could not be verified." else null);return}
                        read(AssignmentReadRequest("assignment-preview-item",scope.workspaceId,scope.clientUid,assignment=p.assignment,itemId=ids[index])){item->items+=item.item?:return@read;next(index+1)}
                    };next(0)
                }
            }
            AssignmentEvent.Confirm->if(!s.busy && s.preview!=null && s.preparation!=null && s.pending==null)mutable.value=s.copy(confirm=true)
            AssignmentEvent.Cancel->if(!s.busy)mutable.value=s.copy(confirm=false,preview=null,preparation=null)
            AssignmentEvent.Submit->{if(!repository.issuanceEnabled || !AssignmentProtocol.ISSUANCE_ENABLED || s.busy || !s.confirm || s.preview==null || s.preparation==null || s.pending!=null)return
                val request=AssignmentProtocol.write(scope.workspaceId,s.preparation.assignment,repository.commands.newCommandKey())
                if(!repository.commands.assignmentAdmission(scope.uid,request)){mutable.value=s.copy(error="Protected storage cannot admit this assignment.",confirm=false);return}
                invalidate();val g=generation;mutable.value=s.copy(busy=true,confirm=false)
                operations+=repository.commands.execute(scope.uid,request){outcome->if(owned(scope,g)) {
                    mutable.value=mutable.value.copy(busy=false,preview=null,preparation=null,pending=repository.commands.pending(scope.uid),notice=outcome.reply?.assignmentDecision?.status,error=outcome.error?.let{"Outcome unresolved. Open Invitations and recovery."})
                    if(outcome.error==null && !outcome.ambiguous)refresh()
                }}
            }
            is AssignmentEvent.Open->if(!s.busy && s.headers.any{it.assignmentId==event.id})load(event.id)
            AssignmentEvent.CloseDetail->refresh()
        }
    }
    private fun load(aid:String) {
        val s=mutable.value;val scope=s.scope?:return;invalidate();mutable.value=s.copy(busy=true,error=null,detail=null,content=null,plan=null,preview=null,preparation=null)
        val base="users/${scope.clientUid}/workspaces/${scope.workspaceId}/assignedPrograms/$aid";val docs=mutableMapOf<String,JsonObject>();val own=scope.role=="client"
        fun document(relative:String,done:(JsonObject)->Unit)=read(AssignmentReadRequest(if(own)"client-assignment-document" else "assignment-document",scope.workspaceId,scope.clientUid,assignmentId=aid,relativePath=relative)){r->r.document?.let(done)}
        fun content(h:AssignmentHeader,inventory:AssignmentInventory) {
            if(!AssignmentReads.inventory(inventory,scope.clientUid,scope.workspaceId,aid,h.snapshotHash)){mutable.value=mutable.value.copy(busy=false,error="Incomplete assignment.");return}
            val paths=inventory.requiredPaths
            fun finish() {
                document(""){last->if(last!=docs[base]){mutable.value=mutable.value.copy(busy=false,error="Assignment changed. Refresh.",content=null);return@document}
                    if(!AssignmentClientDocuments.valid(scope.clientUid,scope.workspaceId,aid,docs)){mutable.value=mutable.value.copy(busy=false,error="Complete assignment could not be verified.",content=null);return@document}
                    val snapshot=AssignmentContract.json.decodeFromJsonElement<AssignmentSnapshot>(docs.getValue("$base/snapshots/content"));val wid=snapshot.workoutIds.single();val workout=AssignmentContract.json.decodeFromJsonElement<AssignmentWorkoutDocument>(docs.getValue("$base/snapshots/content/workouts/$wid"))
                    val items=workout.itemIds.map{AssignmentContract.json.decodeFromJsonElement<AssignmentItemDocument>(docs.getValue("$base/snapshots/content/workouts/$wid/exercises/$it")).item()}
                    val copied=AssignmentCopy(1,snapshot.title,snapshot.description,listOf(PublicationWorkout(wid,workout.title,items)))
                    val plan=docs.filterKeys{it.startsWith("$base/plannedWorkouts/")}.values.single().let{AssignmentContract.json.decodeFromJsonElement<AssignmentPlanDocument>(it)}
                    fun display(remaining:Long) {
                        if(remaining<=0){mutable.value=mutable.value.copy(busy=false,error="Assignment expired.",content=null);return}
                        mutable.value=mutable.value.copy(busy=false,detail=h,content=copied,plan=plan)
                        expiry=viewModelScope.launch{delay(remaining.coerceAtMost(86400000L*2));mutable.value=mutable.value.copy(content=null,detail=null,plan=null,error="Assignment expired. Refresh to recheck access.")}
                    }
                    if(own){val started=TimeSource.Monotonic.markNow();read(AssignmentReadRequest("client-assignment-time",scope.workspaceId,scope.clientUid,assignmentId=aid)){time->
                        val o=time.document;val now=time.serverNowMillis
                        if(now==null || o?.get("snapshotHash")!=JsonPrimitive(h.snapshotHash) || o["accessExpiresAtMillis"]!=JsonPrimitive(h.accessExpiresAtMillis)){mutable.value=mutable.value.copy(busy=false,error="Assignment unavailable.",content=null);return@read}
                        display(h.accessExpiresAtMillis-now-started.elapsedNow().inWholeMilliseconds)
                    }}else display(h.accessExpiresAtMillis-Clock.System.now().toEpochMilliseconds())
                }
            }
            fun next(index:Int){if(index==paths.size){finish();return};document(paths[index]){value->docs["$base/${paths[index]}"]=value;next(index+1)}};next(0)
        }
        if(own)document(""){raw->val h=runCatching{AssignmentReads.header(raw,scope.clientUid,scope.workspaceId,aid)}.getOrNull();if(h==null){mutable.value=mutable.value.copy(busy=false,error="Assignment unavailable.");return@document};docs[base]=raw
            document("manifests/download"){rawInventory->val inventory=runCatching{AssignmentContract.json.decodeFromJsonElement<AssignmentInventory>(rawInventory).also{require(AssignmentContract.json.encodeToJsonElement(it)==rawInventory)}}.getOrNull()
                if(inventory==null){mutable.value=mutable.value.copy(busy=false,error="Incomplete assignment.");return@document};docs["$base/manifests/download"]=rawInventory;content(h,inventory)}
        }else read(AssignmentReadRequest("assignment-get",scope.workspaceId,scope.clientUid,assignmentId=aid)){r->val h=r.headers.single();docs[base]=AssignmentContract.json.encodeToJsonElement(h).jsonObject;docs["$base/manifests/download"]=AssignmentContract.json.encodeToJsonElement(r.inventory!!).jsonObject;content(h,r.inventory)}
    }
    override fun onCleared(){clear()}
}
