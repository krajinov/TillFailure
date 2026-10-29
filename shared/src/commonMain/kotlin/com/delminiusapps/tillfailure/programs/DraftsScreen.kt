package com.delminiusapps.tillfailure.programs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.delminiusapps.tillfailure.core.designsystem.*
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun DraftsRoute(repository:DraftRepository,owner:DraftVisit,isCurrent:()->Boolean,
    registerLeave:((String?)->Unit,Boolean)->Unit,onNavigation:(DraftNavigation)->Unit,recovery:()->Unit) {
    val vm:DraftsViewModel=koinViewModel(parameters={parametersOf(repository)})
    val state by vm.state.collectAsStateWithLifecycle();val lifecycle=LocalLifecycleOwner.current
    val current by rememberUpdatedState(isCurrent);val navigate by rememberUpdatedState(onNavigation)
    val leave=remember(vm,owner){{page:String?->vm.onEvent(owner,vm.state.value.promptId,DraftEvent.Leave(page))}}
    DisposableEffect(vm,owner,lifecycle){vm.bind(owner);registerLeave(leave,true)
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)vm.clear()};lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);registerLeave(leave,false);vm.clear()}}
    LaunchedEffect(vm,owner,lifecycle){lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){if(current())vm.bind(owner)
        launch{vm.effect.collect{if(current() && vm.consume(it))navigate(it)}}}}
    DraftsScreen(if(state.owner==owner && current())state else DraftsState(),{if(current())vm.onEvent(owner,state.promptId,it)},{if(current())vm.onEvent(owner,state.promptId,DraftEvent.Leave("Invitations"))})
}
@Composable
internal fun DraftsScreen(s:DraftsState,event:(DraftEvent)->Unit,recovery:()->Unit) {
    val enabled=s.authority!=null && !s.busy && !s.pending && !s.locked && !s.denied && s.prompt==null
    val editable=enabled && s.conflict!="draft-conflict"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TillFailureSpacing.xl),verticalArrangement=Arrangement.spacedBy(TillFailureSpacing.lg)) {
        DraftHeading(if(s.pickerWorkout!=null)"Choose an exercise" else if(s.draft!=null)"Program draft" else "My program drafts","draft-heading",s,MaterialTheme.typography.headlineLarge)
        Text("Synthetic programs · online only · author only",color=TillFailureTheme.colors.secondaryText)
        if(s.busy)Text("Working…",Modifier.semantics{liveRegion=LiveRegionMode.Polite},color=TillFailureTheme.colors.primaryText)
        if(s.pickerWorkout!=null) {
            Text(if(s.pickerKind=="system")"System exercises" else "Your custom exercises",Modifier.semantics{heading()},color=TillFailureTheme.colors.primaryText)
            s.preview?.let{preview->
                Text(preview.item.name,Modifier.semantics{heading()},style=MaterialTheme.typography.titleLarge,color=TillFailureTheme.colors.primaryText)
                Text("Current authorized source instructions · these instructions are not copied into the saved draft",color=TillFailureTheme.colors.secondaryText)
                Text(preview.instructions,color=TillFailureTheme.colors.primaryText)
                TillFailurePrimaryButton("Add this exercise",{event(DraftEvent.AddSelection)},enabled=editable)
                TillFailureSecondaryButton("Back to exercise list",{event(DraftEvent.BackToChoices)},enabled=!s.busy)
            }
            if(s.preview==null)s.choices.forEach{choice->TillFailureSecondaryButton("View ${choice.name}",{event(DraftEvent.Select(choice.id))},enabled=editable)}
            if(s.preview==null && s.choices.isEmpty() && !s.busy)Text("No exercises loaded.",color=TillFailureTheme.colors.secondaryText)
            if(s.preview==null && s.pickerNext!=null)TillFailureSecondaryButton("Load more exercises",{event(DraftEvent.MorePicker)},enabled=editable)
            TillFailureSecondaryButton("Cancel exercise selection",{event(DraftEvent.CancelPicker)},enabled=!s.busy)
        } else s.draft?.let {d->
            Text("Unsaved edits are lost when leaving, backgrounding or losing access. Explicitly Save to keep this draft. Submitted work stays protected.",color=TillFailureTheme.colors.secondaryText)
            Text("A draft may be incomplete. Maximum 3 workouts, 12 total exercises and 650 combined characters, including copied exercise names.",color=TillFailureTheme.colors.secondaryText)
            TillFailureTextField(d.title,{event(DraftEvent.Title(it))},"Template title",enabled=editable,supportingText="Up to 80 characters; may be blank.")
            TillFailureTextField(d.description,{event(DraftEvent.Description(it))},"Description",enabled=editable,singleLine=false,maxLines=6,supportingText="Up to 240 characters; may be blank.")
            d.workouts.forEachIndexed {wi,w->key(w.id){
                DraftHeading("Workout ${wi+1}",w.id,s,MaterialTheme.typography.titleLarge)
                TillFailureTextField(w.title,{event(DraftEvent.WorkoutTitle(w.id,it))},"Workout ${wi+1} title",enabled=editable,supportingText="Up to 40 characters; may be blank.")
                TillFailureSecondaryButton("Move workout ${wi+1} up",{event(DraftEvent.MoveWorkout(w.id,-1))},enabled=editable && wi>0)
                TillFailureSecondaryButton("Move workout ${wi+1} down",{event(DraftEvent.MoveWorkout(w.id,1))},enabled=editable && wi<d.workouts.lastIndex)
                TillFailureSecondaryButton("Remove workout ${wi+1}",{event(DraftEvent.RemoveWorkout(w.id))},enabled=editable)
                w.items.forEachIndexed {ii,item->key(item.id){
                    DraftHeading("${ii+1}. ${item.name}",item.id,s,MaterialTheme.typography.titleMedium)
                    Text(if(item.sourceKind=="system")"Saved system exercise name" else "Saved custom exercise name",color=TillFailureTheme.colors.secondaryText)
                    val n=s.numbers[item.id]?:DraftNumbers.from(item.prescription)
                    val prefix="Workout ${wi+1}, exercise ${ii+1}"
                    listOf(Triple("sets","Sets (1–20)",n.sets),Triple("reps","Reps per set (1–1,000)",n.reps),Triple("rest","Rest seconds (0–3,600)",n.rest),Triple("kg","External load kg (0–1,000)",n.kg)).forEach{(field,label,value)->
                        TillFailureTextField(value,{event(DraftEvent.Numbers(item.id,field,it))},"$prefix · $label",enabled=editable,supportingText=if(field=="kg")"Blank means unspecified; use a decimal point and up to 3 decimal places." else "Blank means unspecified.",keyboardOptions=KeyboardOptions(keyboardType=if(field=="kg")KeyboardType.Decimal else KeyboardType.Number,imeAction=ImeAction.Next))
                    }
                    TillFailureSecondaryButton("$prefix · Move ${item.name} up",{event(DraftEvent.MoveItem(item.id,w.id,-1))},enabled=editable && ii>0)
                    TillFailureSecondaryButton("$prefix · Move ${item.name} down",{event(DraftEvent.MoveItem(item.id,w.id,1))},enabled=editable && ii<w.items.lastIndex)
                    d.workouts.filter{it.id!=w.id}.forEach{target->TillFailureSecondaryButton("$prefix · Move ${item.name} to workout ${d.workouts.indexOf(target)+1}",{event(DraftEvent.MoveItem(item.id,target.id))},enabled=editable)}
                    TillFailureSecondaryButton("$prefix · Remove ${item.name}",{event(DraftEvent.RemoveItem(item.id))},enabled=editable)
                }}
                val canAdd=editable && d.workouts.sumOf{it.items.size}<12
                TillFailureSecondaryButton("Add system exercise to workout ${wi+1}",{event(DraftEvent.Picker(w.id,"system"))},enabled=canAdd)
                TillFailureSecondaryButton("Add custom exercise to workout ${wi+1}",{event(DraftEvent.Picker(w.id,"custom"))},enabled=canAdd)
            }}
            TillFailureSecondaryButton("Add workout",{event(DraftEvent.AddWorkout)},enabled=editable && d.workouts.size<3)
            TillFailurePrimaryButton("Save draft",{event(DraftEvent.Save)},enabled=editable && !s.needsRecovery && (s.dirty || d.expectedRevision==0) && s.conflict==null,loading=s.busy)
            if(!s.pending && !s.locked)TillFailureSecondaryButton("Reload current server draft",{event(DraftEvent.Reload)},enabled=enabled)
        } ?: run {
            TillFailurePrimaryButton("Create program draft",{event(DraftEvent.Create)},enabled=enabled)
            s.rows.forEach{row->TillFailureSecondaryButton("Open ${row.title.ifEmpty{"Untitled draft"}}",{event(DraftEvent.Open(row.templateId))},enabled=enabled)}
            if(s.rows.isEmpty() && !s.busy && s.authority!=null)Text("No saved drafts.",color=TillFailureTheme.colors.secondaryText)
            if(s.nextId!=null)TillFailureSecondaryButton("Load more drafts",{event(DraftEvent.More)},enabled=enabled)
            TillFailureSecondaryButton("Refresh drafts",{event(DraftEvent.Reload)},enabled=enabled)
        }
        if(s.message.isNotEmpty())Text(s.message,Modifier.semantics{liveRegion=LiveRegionMode.Polite},color=TillFailureTheme.colors.primaryText)
        if(s.pending)Text("Leaving does not cancel the submitted Save. Its exact outcome remains in recovery.",color=TillFailureTheme.colors.secondaryText)
        if(s.pending || s.locked || s.needsRecovery)TillFailureSecondaryButton("Invitations and recovery",recovery)
        TillFailureSecondaryButton(if(s.draft!=null)"Cancel / Back" else "Back",{event(DraftEvent.Leave())})
        s.prompt?.let{p->TillFailureConfirmationDialog(title=if(p=="leave")"Leave unsaved changes?" else if(p=="reload")"Replace unsaved edits?" else "Remove workout and its exercises?",
            message=if(p=="leave")"Your unsent edits will be lost. Submitted work stays protected." else if(p=="reload")"A fresh authorized read will replace your foreground edits only if it succeeds." else "Removal changes the foreground draft. Save explicitly to change the server draft.",
            confirmLabel=if(p=="leave")"Leave" else if(p=="reload")"Reload" else "Remove",onConfirm={event(DraftEvent.Confirm)},onDismiss={event(DraftEvent.Stay)})}
    }
}

@Composable
private fun DraftHeading(label:String,id:String,state:DraftsState,style:TextStyle) {
    val focus=remember(id){FocusRequester()}
    LaunchedEffect(state.focusSequence,id){if(state.focusTarget==id)focus.requestFocus()}
    Text(label,Modifier.focusRequester(focus).focusable().semantics{heading()},style=style,color=TillFailureTheme.colors.primaryText)
}
