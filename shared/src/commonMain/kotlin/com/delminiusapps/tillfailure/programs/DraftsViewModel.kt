package com.delminiusapps.tillfailure.programs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DraftVisit(val scope:ExerciseScope,val visit:Long)
data class DraftChoice(val id:String,val name:String)
data class DraftPreview(val item:DraftItem,val instructions:String) {override fun toString()="DraftPreview(redacted)"}
data class DraftsState(val owner:DraftVisit?=null,val authority:ExerciseAuthority?=null,val draft:DraftSave?=null,val baseline:DraftSave?=null,
    val numbers:Map<String,DraftNumbers> = emptyMap(),val rows:List<DraftRow> = emptyList(),val nextId:String?=null,
    val pickerWorkout:String?=null,val pickerKind:String="system",val preview:DraftPreview?=null,val choices:List<DraftChoice> = emptyList(),val pickerNext:String?=null,
    val focusTarget:String?=null,val focusSequence:Long=0,val busy:Boolean=false,val needsRecovery:Boolean=false,val pending:Boolean=false,val locked:Boolean=false,val denied:Boolean=false,val conflict:String?=null,val message:String="",
    val prompt:String?=null,val promptId:Long=0,val exit:String?=null) {
    val dirty get()=draft!=baseline || numbers.any { (id,value)->value!=baseline?.workouts?.flatMap {it.items}?.find{it.id==id}?.prescription?.let(DraftNumbers::from) }
    override fun toString()="DraftsState(redacted)"
}
sealed interface DraftEvent {
    data object Create:DraftEvent
    data class Open(val id:String):DraftEvent
    data class Title(val value:String):DraftEvent {override fun toString()="DraftEvent(redacted)"}
    data class Description(val value:String):DraftEvent {override fun toString()="DraftEvent(redacted)"}
    data object AddWorkout:DraftEvent
    data class WorkoutTitle(val id:String,val value:String):DraftEvent {override fun toString()="DraftEvent(redacted)"}
    data class RemoveWorkout(val id:String):DraftEvent
    data class MoveWorkout(val id:String,val offset:Int):DraftEvent
    data class RemoveItem(val id:String):DraftEvent
    data class MoveItem(val id:String,val workout:String,val offset:Int=0):DraftEvent
    data class Numbers(val id:String,val field:String,val value:String):DraftEvent {override fun toString()="DraftEvent(redacted)"}
    data class Picker(val workout:String,val kind:String):DraftEvent
    data class Select(val id:String):DraftEvent
    data object AddSelection:DraftEvent
    data object BackToChoices:DraftEvent
    data object CancelPicker:DraftEvent
    data object MorePicker:DraftEvent
    data object More:DraftEvent
    data object Save:DraftEvent
    data object Reload:DraftEvent
    data class Leave(val page:String?=null):DraftEvent
    data object Confirm:DraftEvent
    data object Stay:DraftEvent
}
data class DraftNavigation(val owner:DraftVisit,val page:String?,val sequence:Long)
class DraftsViewModel(private val repository:DraftRepository):ViewModel() {
    private val mutable=MutableStateFlow(DraftsState());val state=mutable.asStateFlow()
    private val effects=Channel<DraftNavigation>(Channel.BUFFERED);val effect=effects.receiveAsFlow()
    private var operation:FirebaseCancellation?=null;private var observer:FirebaseCancellation?=null;private var timer:Job?=null
    private var generation=0L;private var sequence=0L;private var consumed=-1L;private var navigating=false
    fun bind(owner:DraftVisit) {if(mutable.value.owner==owner)return;clear();mutable.value=DraftsState(owner=owner)
        observer=repository.commands.observePending(owner.scope.uid){pending()};pending();read("draft-list")}
    fun clear(){generation++;operation?.cancel();observer?.cancel();timer?.cancel();operation=null;observer=null;timer=null;navigating=false;while(effects.tryReceive().isSuccess){};mutable.value=DraftsState()}
    private fun pending() {val s=mutable.value;val o=s.owner?:return;val result=runCatching{repository.commands.pending(o.scope.uid)};mutable.value=s.copy(pending=result.getOrNull()!=null,locked=result.isFailure)}
    private fun begin(submitting:Boolean=false):Pair<DraftVisit,Long>? {val o=mutable.value.owner?:return null;generation++;operation?.cancel();timer?.cancel();val g=generation
        mutable.value=mutable.value.copy(busy=true,message="")
        timer=viewModelScope.launch{delay(20000);if(accept(o,g)){generation++;operation?.cancel();mutable.value=mutable.value.copy(busy=false,needsRecovery=submitting || mutable.value.needsRecovery,message=if(submitting)"The Save outcome is unavailable. Check recovery and Reload before another Save." else "This read is unavailable. Try a fresh authorized read.");pending()}}
        return o to g}
    private fun accept(o:DraftVisit,g:Long)=!navigating && mutable.value.owner==o && generation==g
    private fun end(){generation++;timer?.cancel();timer=null;mutable.value=mutable.value.copy(busy=false)}
    private fun read(action:String,id:String?=null,more:Boolean=false) {
        val (o,g)=begin()?:return;val after=if(more)mutable.value.nextId else null
        operation=repository.read(o.scope,DraftReadRequest(o.scope.workspaceId,action,id,after)){r->
            if(!accept(o,g))return@read
            end();val s=mutable.value
            if(r.error!=null){mutable.value=if(r.error=="unauthorized")DraftsState(owner=o,denied=true,message="Access is unavailable. Unsaved edits were cleared.") else s.copy(message="Drafts are unavailable. Try a fresh read.");return@read}
            when(action){
                "draft-prepare"->{val d=DraftSave(repository.newId(),0,r.authority!!,"","",emptyList());mutable.value=s.copy(authority=r.authority,draft=d,baseline=d,numbers=emptyMap(),conflict=null,needsRecovery=false)}
                "draft-get"->{val d=r.draft!!.write(r.authority!!);mutable.value=s.copy(authority=r.authority,draft=d,baseline=d,numbers=d.workouts.flatMap{it.items}.associate{it.id to DraftNumbers.from(it.prescription)},pickerWorkout=null,conflict=null,needsRecovery=false,message="Current server draft loaded.")}
                else->mutable.value=s.copy(authority=r.authority,rows=if(more)(s.rows+r.items).distinctBy{it.templateId} else r.items,nextId=r.nextId,draft=null,baseline=null,numbers=emptyMap())
            }
        }
    }
    fun onEvent(owner:DraftVisit,promptId:Long,event:DraftEvent) {
        var s=mutable.value;if(s.owner!=owner || navigating)return
        if(event is DraftEvent.Leave) {
            if(s.prompt!=null)return
            if(s.pickerWorkout!=null && event.page==null){closePicker();return}
            if(s.dirty && !s.pending)mutable.value=s.copy(prompt="leave",promptId=++sequence,exit=event.page)
            else leave(event.page)
            return
        }
        if(event==DraftEvent.Confirm || event==DraftEvent.Stay) {
            if(s.prompt==null || promptId!=s.promptId)return
            mutable.value=s.copy(prompt=null)
            if(event==DraftEvent.Confirm)when {
                s.prompt=="leave"->leave(s.exit)
                s.prompt=="reload"->s.draft?.let{if(it.expectedRevision==0)read("draft-get",it.templateId) else read("draft-get",it.templateId)}
                s.prompt.startsWith("remove:")->edit{s.copy(prompt=null,draft=s.draft?.copy(workouts=s.draft.workouts.filter{it.id!=s.prompt.removePrefix("remove:")}),conflict=null,focusTarget="draft-heading",focusSequence=++sequence,message="Workout and its exercises removed.",numbers=s.numbers.filterKeys{id->s.draft?.workouts?.firstOrNull{it.id==s.prompt.removePrefix("remove:")}?.items?.none{it.id==id}!=false})}
            }
            return
        }
        if(s.busy || s.prompt!=null || s.denied)return
        if(event==DraftEvent.Reload){if(s.pending || s.locked)return;if(s.draft==null)read("draft-list") else if(s.dirty)mutable.value=s.copy(prompt="reload",promptId=++sequence) else read("draft-get",s.draft.templateId);return}
        if(s.pending || s.locked || s.conflict=="draft-conflict")return
        val oversized=when(event){is DraftEvent.Title->event.value.length>320;is DraftEvent.Description->event.value.length>960;is DraftEvent.WorkoutTitle->event.value.length>160;is DraftEvent.Numbers->event.value.length>16;else->false}
        if(oversized){mutable.value=s.copy(message="Input is too large. Nothing was truncated or submitted.");return}
        when(event) {
            DraftEvent.Create->if(s.draft==null)read("draft-prepare")
            is DraftEvent.Open->if(s.draft==null && s.rows.any{it.templateId==event.id})read("draft-get",event.id)
            DraftEvent.More->if(s.draft==null && s.nextId!=null)read("draft-list",more=true)
            DraftEvent.Save->save()
            is DraftEvent.Picker->if(s.draft?.workouts?.any{it.id==event.workout}==true && event.kind in setOf("system","custom")) {mutable.value=s.copy(pickerWorkout=event.workout,pickerKind=event.kind,preview=null,choices=emptyList(),pickerNext=null);picker(false)}
            DraftEvent.BackToChoices->mutable.value=s.copy(preview=null)
            DraftEvent.AddSelection->addSelection()
            DraftEvent.CancelPicker->closePicker()
            DraftEvent.MorePicker->if(s.pickerNext!=null)picker(true)
            is DraftEvent.Select->if(s.choices.any{it.id==event.id})select(event.id)
            else->if(s.draft!=null && s.pickerWorkout==null && s.conflict!="draft-conflict")when(event) {
                is DraftEvent.Title->if(event.value.length<=320)edit{it.copy(draft=it.draft!!.copy(title=event.value))}
                is DraftEvent.Description->if(event.value.length<=960)edit{it.copy(draft=it.draft!!.copy(description=event.value))}
                DraftEvent.AddWorkout->if(s.draft.workouts.size<3)edit{it.copy(draft=it.draft!!.copy(workouts=it.draft.workouts+DraftWorkout(repository.newId(),"",emptyList())))}
                is DraftEvent.WorkoutTitle->if(event.value.length<=160)edit{it.copy(draft=it.draft!!.copy(workouts=it.draft.workouts.map{w->if(w.id==event.id)w.copy(title=event.value) else w}))}
                is DraftEvent.RemoveWorkout->if(s.draft.workouts.any{it.id==event.id})mutable.value=s.copy(prompt="remove:${event.id}",promptId=++sequence)
                is DraftEvent.MoveWorkout->edit{val moved=move(it.draft!!.workouts,{w->w.id==event.id},event.offset);it.copy(draft=it.draft.copy(workouts=moved),focusTarget=event.id,focusSequence=++sequence,message="Workout moved to position ${moved.indexOfFirst{w->w.id==event.id}+1}.")}
                is DraftEvent.RemoveItem->edit{it.copy(draft=it.draft!!.copy(workouts=it.draft.workouts.map{w->w.copy(items=w.items.filter{i->i.id!=event.id})}),numbers=it.numbers-event.id,conflict=null,focusTarget=it.draft.workouts.firstOrNull{w->w.items.any{i->i.id==event.id}}?.id?:"draft-heading",focusSequence=++sequence,message="Exercise removed.")}
                is DraftEvent.MoveItem->edit{state->val d=state.draft!!;val from=d.workouts.firstOrNull{w->w.items.any{i->i.id==event.id}};val item=from?.items?.firstOrNull{it.id==event.id}
                    if(item==null || d.workouts.none{it.id==event.workout})state else state.copy(focusTarget=item.id,focusSequence=++sequence,message="Exercise moved to workout ${d.workouts.indexOfFirst{it.id==event.workout}+1}.",draft=d.copy(workouts=d.workouts.map{w->when {
                        w.id==from.id && from.id==event.workout->w.copy(items=move(w.items,{i->i.id==event.id},event.offset))
                        w.id==from.id->w.copy(items=w.items.filter{i->i.id!=event.id})
                        w.id==event.workout->w.copy(items=w.items+item)
                        else->w
                    }}))}
                is DraftEvent.Numbers->if(event.value.length<=16 && s.draft.workouts.any{w->w.items.any{i->i.id==event.id}})edit{state->val n=state.numbers[event.id]?:DraftNumbers();val changed=when(event.field){"sets"->n.copy(sets=event.value);"reps"->n.copy(reps=event.value);"rest"->n.copy(rest=event.value);"kg"->n.copy(kg=event.value);else->n};state.copy(numbers=state.numbers+(event.id to changed))}
                else->Unit
            }
        }
    }
    private fun edit(change:(DraftsState)->DraftsState){val before=mutable.value;val next=change(before);mutable.value=if(next.focusSequence!=before.focusSequence)next else next.copy(message="")}
    private fun <T> move(items:List<T>,matches:(T)->Boolean,offset:Int):List<T> {if(offset !in setOf(-1,1))return items;val i=items.indexOfFirst(matches);val j=i+offset;if(i<0 || j !in items.indices)return items;return items.toMutableList().apply{add(j,removeAt(i))}}
    private fun closePicker() {
        generation++;operation?.cancel();timer?.cancel();operation=null;timer=null
        mutable.value=mutable.value.copy(busy=false,pickerWorkout=null,preview=null,choices=emptyList(),pickerNext=null)
    }
    private fun picker(more:Boolean) {
        val initial=mutable.value;val (o,g)=begin()?:return;val after=if(more)initial.pickerNext else null
        fun deliver(choices:List<DraftChoice>,next:String?,error:Boolean){if(!accept(o,g))return;end();mutable.value=mutable.value.copy(choices=if(more)(initial.choices+choices).distinctBy{it.id} else choices,pickerNext=next,message=if(error)"Exercises are unavailable. Try a fresh picker read." else "")}
        operation=if(initial.pickerKind=="system")repository.systemExercises.read(o.scope.uid,after,null){r->deliver(r.items.map{DraftChoice(it.id,it.name)},r.nextId,r.error!=null)}
        else repository.customExercises.read(o.scope,CustomExerciseReadRequest(o.scope.workspaceId,"exercise-list",afterId=after)){r->if(r.error=="unauthorized" && accept(o,g)){end();mutable.value=DraftsState(owner=o,denied=true,message="Access is unavailable. Unsaved edits were cleared.")} else deliver(r.items.map{DraftChoice(it.exerciseId,it.name)},r.nextId,r.error!=null)}
    }
    private fun select(id:String) {
        val initial=mutable.value;val (o,g)=begin()?:return
        fun deliver(preview:DraftPreview?) {if(!accept(o,g))return;end();mutable.value=mutable.value.copy(preview=preview,message=if(preview==null)"This source is unavailable. Nothing was added." else "")}
        operation=if(initial.pickerKind=="system")repository.systemExercises.read(o.scope.uid,null,id){r->deliver(r.detail?.let{DraftPreview(DraftSources.system(repository.newId(),it),it.instructions)})}
        else repository.customExercises.read(o.scope,CustomExerciseReadRequest(o.scope.workspaceId,"exercise-get",exerciseId=id)){r->if(r.error=="unauthorized" && accept(o,g)){end();mutable.value=DraftsState(owner=o,denied=true,message="Access is unavailable. Unsaved edits were cleared.")} else deliver(r.exercise?.let{DraftPreview(DraftSources.custom(repository.newId(),o.scope.workspaceId,it),it.instructions)})}
    }
    private fun addSelection() {
        val s=mutable.value;val d=s.draft?:return;val item=s.preview?.item?:return
        if(s.pickerWorkout==null || d.workouts.none{it.id==s.pickerWorkout})return
        val next=d.copy(workouts=d.workouts.map{w->if(w.id==s.pickerWorkout)w.copy(items=w.items+item) else w})
        val strings=listOf(next.title,next.description)+next.workouts.map{it.title}+next.workouts.flatMap{it.items}.map{it.name}
        if(!item.valid() || next.workouts.sumOf{it.items.size}>12 || strings.sumOf{v->v.count{!it.isLowSurrogate()}}>650 || strings.sumOf{it.encodeToByteArray().size}>2600) {
            mutable.value=s.copy(message="This selection exceeds the 12-item / 650-character budget. Nothing was added or truncated.");return
        }
        mutable.value=s.copy(draft=next,numbers=s.numbers+(item.id to DraftNumbers()),pickerWorkout=null,preview=null,choices=emptyList(),conflict=null,message="Exercise added. Only its name and source identity are saved; instructions shown in the picker remain current-source content.")
    }

    private fun save() {
        val s=mutable.value;val o=s.owner?:return;if(s.needsRecovery || s.conflict!=null || s.draft==null || !s.dirty && s.draft.expectedRevision!=0)return
        val d=runCatching{s.draft.copy(workouts=s.draft.workouts.map{w->w.copy(items=w.items.map{i->i.copy(prescription=(s.numbers[i.id]?:DraftNumbers.from(i.prescription)).parse())})})}.getOrNull()
        if(d==null || !d.valid()){mutable.value=s.copy(message="Check the field limits, numeric values, 3 workouts, 12 items and 650 combined characters. Blank prescriptions are allowed.");return}
        val request=InvitationRequest("save-program-draft",workspaceId=o.scope.workspaceId,idempotencyKey=repository.commands.newCommandKey(),draftSave=d)
        if(!repository.commands.draftAdmission(o.scope.uid,request)){mutable.value=s.copy(message="This Save does not fit protected recovery or storage is unavailable. Nothing was submitted.");return}
        val (_,g)=begin(submitting=true)?:return
        operation=repository.commands.execute(o.scope.uid,request){outcome->
            if(!accept(o,g))return@execute;end();pending();val current=mutable.value
            if(outcome.error in setOf("unauthorized","unauthenticated","unverified-email")){mutable.value=DraftsState(owner=o,pending=current.pending,locked=current.locked,denied=true,message="Access is unavailable. Submitted work stays protected; unsaved presentation was cleared.");return@execute}
            if(outcome.error!=null || outcome.ambiguous || outcome.reply?.draftDecision==null){mutable.value=current.copy(needsRecovery=true,message="Save outcome is unavailable. Use original-command recovery; unsaved content is not a success.");return@execute}
            val decision=outcome.reply.draftDecision
            if(decision.status=="draft-saved")read("draft-get",d.templateId)
            else mutable.value=current.copy(conflict=decision.status,message=if(decision.status=="draft-conflict")"Another Save won. Reload explicitly before editing again." else "A new source changed or is unavailable. Remove or reselect it, or Reload; the previous command is settled.")
        }
        pending()
    }
    private fun leave(page:String?) {val s=mutable.value;val o=s.owner?:return;if(s.draft!=null && page==null){operation?.cancel();generation++;mutable.value=DraftsState(owner=o);pending();read("draft-list");return}
        navigating=true;generation++;operation?.cancel();timer?.cancel();viewModelScope.launch{effects.send(DraftNavigation(o,page,++sequence))}}
    fun consume(n:DraftNavigation):Boolean {if(n.owner!=mutable.value.owner || n.sequence<=consumed)return false;consumed=n.sequence;return true}
    override fun onCleared(){clear();super.onCleared()}
}
