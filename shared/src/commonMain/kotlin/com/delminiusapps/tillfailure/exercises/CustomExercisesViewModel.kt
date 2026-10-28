package com.delminiusapps.tillfailure.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class CustomExerciseMode { List, Detail, Editor }
data class CustomExerciseVisit(val scope:ExerciseScope,val visit:Long,val mode:CustomExerciseMode,val exerciseId:String?=null)
enum class CustomExercisePrompt { Leave, Reload, StartNew }
data class CustomExercisesState(val owner:CustomExerciseVisit?=null,val authority:ExerciseAuthority?=null,val baseline:CustomExercise?=null,
    val targetId:String?=null,val items:List<CustomExerciseRow> = emptyList(),val nextId:String?=null,val failedMore:Boolean=false,
    val name:String="",val instructions:String="",val muscles:List<String> = emptyList(),val equipment:List<String> = emptyList(),
    val busy:Boolean=false,val pending:Boolean=false,val retryOriginal:Boolean=false,val locked:Boolean=false,val denied:Boolean=false,val conflict:Boolean=false,val message:String="",
    val prompt:CustomExercisePrompt?=null,val promptId:Long=0,val exit:String?=null) {
    val dirty: Boolean get() = owner?.mode == CustomExerciseMode.Editor && (
        name != (baseline?.name ?: "") || instructions != (baseline?.instructions ?: "") ||
        muscles != (baseline?.muscleGroups ?: emptyList<String>()) || equipment != (baseline?.equipment ?: emptyList<String>()))
    override fun toString()="CustomExercisesState(redacted)"
}
sealed interface CustomExerciseEvent {
    data class Name(val value:String):CustomExerciseEvent {override fun toString()="Name(redacted)"}
    data class Instructions(val value:String):CustomExerciseEvent {override fun toString()="Instructions(redacted)"}
    data class Muscle(val token:String):CustomExerciseEvent
    data class Equipment(val token:String):CustomExerciseEvent
    data class Open(val id:String):CustomExerciseEvent
    data object Create:CustomExerciseEvent
    data object Edit:CustomExerciseEvent
    data object Save:CustomExerciseEvent
    data object Reload:CustomExerciseEvent
    data object LoadMore:CustomExerciseEvent
    data object StartNew:CustomExerciseEvent
    data object RetryOriginal:CustomExerciseEvent
    data class Leave(val exit:String?=null):CustomExerciseEvent
    data object Confirm:CustomExerciseEvent
    data object Stay:CustomExerciseEvent
}
data class CustomExerciseNavigation(val owner:CustomExerciseVisit,val kind:String,val id:String?,val exit:String?,val sequence:Long)
class CustomExercisesViewModel(private val repository:CustomExerciseRepository):ViewModel() {
    private val client get()=repository.commands
    private val mutable=MutableStateFlow(CustomExercisesState());val state=mutable.asStateFlow()
    private val effects=Channel<CustomExerciseNavigation>(Channel.BUFFERED);val effect=effects.receiveAsFlow()
    private var operation:FirebaseCancellation?=null;private var observer:FirebaseCancellation?=null;private var timeout:Job?=null
    private var generation=0L;private var sequence=0L;private var navigating=false;private var disposed=false;private var consumed=-1L
    fun bind(owner:CustomExerciseVisit) {
        if(disposed || mutable.value.owner==owner)return
        clear();mutable.value=CustomExercisesState(owner=owner,targetId=owner.exerciseId?:if(owner.mode==CustomExerciseMode.Editor)repository.newExerciseId() else null)
        observer=client.observePending(owner.scope.uid){if(mutable.value.owner==owner)pending()}
        pending();load()
    }
    fun clear() {generation++;operation?.cancel();observer?.cancel();timeout?.cancel();operation=null;observer=null;timeout=null;navigating=false
        while(effects.tryReceive().isSuccess){};mutable.value=CustomExercisesState()}
    private fun pending() {
        val s=mutable.value;val o=s.owner?:return;val r=runCatching{client.pending(o.scope.uid)};val p=r.getOrNull()
        mutable.value=s.copy(pending=p!=null,locked=r.isFailure,retryOriginal=p!=null && CustomExerciseProtocol.action(p.action) && !p.resolutionStarted && p.workspaceId==o.scope.workspaceId && p.exerciseWrite?.exerciseId==s.targetId)
    }
    fun onEvent(owner:CustomExerciseVisit,promptId:Long,event:CustomExerciseEvent) {
        val s=mutable.value;if(disposed || navigating || s.owner!=owner)return
        if(event is CustomExerciseEvent.Leave) {
            if(s.prompt!=null)return
            if(s.dirty && !s.pending)mutable.value=s.copy(prompt=CustomExercisePrompt.Leave,promptId=++sequence,exit=event.exit)
            else navigate("back",exit=event.exit)
            return
        }
        if(event==CustomExerciseEvent.Confirm || event==CustomExerciseEvent.Stay) {
            if(s.prompt==null || promptId!=s.promptId)return
            mutable.value=s.copy(prompt=null)
            if(event==CustomExerciseEvent.Confirm)when(s.prompt){CustomExercisePrompt.Leave->navigate("back",exit=s.exit);CustomExercisePrompt.Reload->load();CustomExercisePrompt.StartNew->startNew();null->Unit}
            return
        }
        if(s.busy || s.prompt!=null)return
        if(event==CustomExerciseEvent.RetryOriginal) {
            val p=runCatching{client.pending(owner.scope.uid)}.getOrNull()?:return
            if(CustomExerciseProtocol.action(p.action) && p.workspaceId==owner.scope.workspaceId && p.exerciseWrite?.exerciseId==s.targetId && !p.resolutionStarted)submit(CommandResolution.original(p))
            return
        }
        when(event) {
            is CustomExerciseEvent.Open -> if(owner.mode==CustomExerciseMode.List && s.items.any{it.exerciseId==event.id})navigate("detail",event.id)
            CustomExerciseEvent.Create -> if(owner.mode==CustomExerciseMode.List && !s.pending && !s.locked && !s.denied)navigate("create")
            CustomExerciseEvent.Edit -> if(owner.mode==CustomExerciseMode.Detail && s.baseline!=null && !s.denied && !s.pending && !s.locked)navigate("edit",s.baseline.exerciseId)
            CustomExerciseEvent.Reload -> if(!s.pending && !s.locked && !s.denied) {if(s.dirty)mutable.value=s.copy(prompt=CustomExercisePrompt.Reload,promptId=++sequence) else load()}
            CustomExerciseEvent.LoadMore -> if(s.nextId!=null && !s.denied)load(true)
            CustomExerciseEvent.StartNew -> if(s.conflict && s.baseline==null && !s.pending && !s.locked)mutable.value=s.copy(prompt=CustomExercisePrompt.StartNew,promptId=++sequence)
            else -> if(owner.mode==CustomExerciseMode.Editor && s.authority!=null && !s.pending && !s.locked && !s.denied && !s.conflict)when(event) {
                is CustomExerciseEvent.Name -> if(event.value.length<=480)mutable.value=s.copy(name=event.value,message="") else mutable.value=s.copy(message="Name input is too large. Nothing was submitted or truncated.")
                is CustomExerciseEvent.Instructions -> if(event.value.length<=8000)mutable.value=s.copy(instructions=event.value,message="") else mutable.value=s.copy(message="Instructions input is too large. Nothing was submitted or truncated.")
                is CustomExerciseEvent.Muscle -> if(event.token in CustomExerciseProtocol.muscles) {val next=toggle(s.muscles,event.token);if(next.size<=3)mutable.value=s.copy(muscles=next,message="") else mutable.value=s.copy(message="Choose up to three muscle groups.")}
                is CustomExerciseEvent.Equipment -> if(event.token in CustomExerciseProtocol.equipment) {val next=toggle(s.equipment,event.token);if(next.size<=3 && ("bodyweight" !in next || next.size==1))mutable.value=s.copy(equipment=next,message="") else mutable.value=s.copy(message="Choose up to three equipment types, or bodyweight alone.")}
                CustomExerciseEvent.Save -> if(s.dirty) {
                    val p=CustomExerciseWrite(s.targetId?:return,s.baseline?.revision?:0,s.authority,s.name,s.instructions,s.muscles,s.equipment)
                    if(!p.valid(if(p.expectedRevision==0)"create-custom-exercise" else "correct-custom-exercise")) {mutable.value=s.copy(message="Use a trimmed name (1–120 characters) and instructions (1–2,000 characters, up to 8,000 UTF-8 bytes). Use line breaks only; check taxonomy selections.");return}
                    val req=CustomExerciseProtocol.write(owner.scope.workspaceId,p,client.newCommandKey())
                    if(!client.customExerciseAdmission(owner.scope.uid,req)){mutable.value=s.copy(message="This submission does not fit the protected request or recovery budget, or protected storage is unavailable. Nothing was submitted. Shorten the text and retry, or open recovery.");return}
                    submit(req)
                }
                else -> Unit
            }
        }
    }
    private fun toggle(values:List<String>,token:String)=(if(token in values)values-token else values+token).sorted()
    private fun startNew() {val o=mutable.value.owner?:return;mutable.value=CustomExercisesState(owner=o,targetId=repository.newExerciseId());pending();load()}
    private fun navigate(kind:String,id:String?=null,exit:String?=null) {val o=mutable.value.owner?:return;navigating=true;generation++;operation?.cancel();timeout?.cancel()
        mutable.value=mutable.value.copy(name="",instructions="",baseline=null,authority=null,prompt=null,busy=false)
        effects.trySend(CustomExerciseNavigation(o,kind,id,exit,++sequence))}
    fun consume(n:CustomExerciseNavigation):Boolean {if(disposed || !navigating || n.owner!=mutable.value.owner || sequence!=n.sequence || consumed==n.sequence)return false;consumed=n.sequence;return true}
    private fun load(more:Boolean=false) {
        val old=mutable.value;val o=old.owner?:return;if(old.busy)return
        val request=when(o.mode){CustomExerciseMode.List->CustomExerciseReadRequest(o.scope.workspaceId,"exercise-list",afterId=if(more)old.nextId else null)
            else -> if(o.exerciseId!=null)CustomExerciseReadRequest(o.scope.workspaceId,"exercise-get",exerciseId=o.exerciseId) else CustomExerciseReadRequest(o.scope.workspaceId,"exercise-prepare")}
        val captured=++generation;operation?.cancel();timeout?.cancel();mutable.value=old.copy(busy=true,message="Loading…")
        fun complete(result:CustomExerciseResult) {
            if(disposed || navigating || generation!=captured || mutable.value.owner!=o)return
            generation++;timeout?.cancel();operation=null;val s=mutable.value
            mutable.value=when {
                result.error=="unauthorized" -> CustomExercisesState(owner=o,denied=true,message="Exercise access is unavailable. Submitted work remains protected in recovery.")
                result.error!=null -> s.copy(busy=false,failedMore=more,message="Exercises unavailable. Connect and retry.")
                o.mode==CustomExerciseMode.List -> s.copy(busy=false,authority=result.authority,items=((if(more)old.items else emptyList())+result.items).distinctBy{it.exerciseId},nextId=result.nextId,failedMore=false,message="")
                else -> s.copy(busy=false,authority=result.authority,baseline=result.exercise,name=result.exercise?.name?:"",instructions=result.exercise?.instructions?:"",muscles=result.exercise?.muscleGroups?:emptyList(),equipment=result.exercise?.equipment?:emptyList(),conflict=false,message="")
            };pending()
        }
        timeout=viewModelScope.launch {delay(20_000);operation?.cancel();complete(CustomExerciseResult(error="unavailable"))}
        operation=repository.read(o.scope,request){result->viewModelScope.launch{complete(result)}}
    }
    private fun submit(request:InvitationRequest) {
        val o=mutable.value.owner?:return;val captured=++generation;mutable.value=mutable.value.copy(busy=true,message="Saving…")
        operation=client.execute(o.scope.uid,request){result->viewModelScope.launch {
            if(disposed || navigating || generation!=captured || mutable.value.owner!=o)return@launch
            generation++;timeout?.cancel();operation=null
            val s=mutable.value;val d=result.reply?.exerciseDecision
            when {
                d?.status in setOf("exercise-created","exercise-corrected") -> {pending();navigate("saved",d!!.exerciseId);return@launch}
                d?.status=="exercise-conflict" -> mutable.value=s.copy(busy=false,conflict=true,message="This submission was not applied because the exercise changed or its ID is occupied. Reload current values before a correction; your foreground draft is still here.")
                result.error in setOf("unauthorized","unauthenticated","unverified-email") -> mutable.value=CustomExercisesState(owner=o,denied=true,message="Exercise access is unavailable. Submitted work remains protected in recovery.")
                else -> mutable.value=s.copy(busy=false,message="Save is unresolved. Retry the original or open Invitations and recovery. Sign-out stays blocked.")
            };pending()
        }};pending()
        timeout?.cancel();timeout=viewModelScope.launch {delay(20_000);if(generation==captured && mutable.value.owner==o){generation++;operation?.cancel();mutable.value=mutable.value.copy(busy=false,message="Save outcome is unknown. Submitted work remains protected; open recovery.");pending()}}
    }
    override fun onCleared(){clear();disposed=true;effects.close()}
}
