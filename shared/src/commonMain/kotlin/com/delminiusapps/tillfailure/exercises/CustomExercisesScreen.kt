package com.delminiusapps.tillfailure.exercises

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import com.delminiusapps.tillfailure.core.designsystem.*
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun CustomExercisesRoute(repository:CustomExerciseRepository,owner:CustomExerciseVisit,isCurrent:()->Boolean,
    registerLeave:((String?)->Unit,Boolean)->Unit,onNavigation:(CustomExerciseNavigation)->Unit,onRecovery:()->Unit) {
    val vm:CustomExercisesViewModel=koinViewModel(parameters={parametersOf(repository)})
    val state by vm.state.collectAsStateWithLifecycle();val lifecycle=LocalLifecycleOwner.current
    val current by rememberUpdatedState(isCurrent);val navigate by rememberUpdatedState(onNavigation)
    val leave=remember(vm,owner){{page:String?->vm.onEvent(owner,vm.state.value.promptId,CustomExerciseEvent.Leave(page))}}
    DisposableEffect(vm,owner,lifecycle) {
        vm.bind(owner);registerLeave(leave,true)
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)vm.clear()};lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);registerLeave(leave,false);vm.clear()}
    }
    LaunchedEffect(vm,owner,lifecycle){lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){if(current())vm.bind(owner)
        launch{vm.effect.collect{if(current() && vm.consume(it))navigate(it)}}}}
    CustomExercisesScreen(if(state.owner==owner && current())state else CustomExercisesState(),{if(current())vm.onEvent(owner,state.promptId,it)},{if(current())onRecovery()})
}
@Composable
internal fun CustomExercisesScreen(s:CustomExercisesState,event:(CustomExerciseEvent)->Unit,recovery:()->Unit) {
    val editor=s.owner?.mode==CustomExerciseMode.Editor
    val enabled=s.authority!=null && !s.busy && !s.pending && !s.locked && !s.denied && !s.conflict && s.prompt==null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TillFailureSpacing.xl),verticalArrangement=Arrangement.spacedBy(TillFailureSpacing.lg)) {
        Text(if(editor)if(s.owner?.exerciseId==null)"Create custom exercise" else "Correct custom exercise" else if(s.owner?.mode==CustomExerciseMode.Detail)"Custom exercise" else "My custom exercises",Modifier.semantics{heading()},style=MaterialTheme.typography.headlineLarge,color=TillFailureTheme.colors.primaryText)
        Text("Synthetic exercise definitions · online only · author only",color=TillFailureTheme.colors.secondaryText)
        if(editor && !s.denied) {
            Text("Use original synthetic text only. Unsaved changes are lost when leaving, backgrounding or losing access. Submitted work remains protected until settled.",color=TillFailureTheme.colors.secondaryText)
            TillFailureTextField(s.name,{event(CustomExerciseEvent.Name(it))},"Exercise name",enabled=enabled,supportingText="1–120 characters; no leading or trailing spaces.",keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Next))
            TillFailureTextField(s.instructions,{event(CustomExerciseEvent.Instructions(it))},"Instructions",enabled=enabled,singleLine=false,maxLines=8,supportingText="1–2,000 characters, up to 8,000 UTF-8 bytes. Line breaks allowed.",keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Default))
            Text("Muscle groups · choose up to three",Modifier.semantics{heading()},color=TillFailureTheme.colors.primaryText)
            CustomExerciseProtocol.muscles.forEach {token->TillFailureSecondaryButton(if(token in s.muscles)"✓ $token selected" else "Select $token",{event(CustomExerciseEvent.Muscle(token))},enabled=enabled)}
            Text("Equipment · choose up to three, or bodyweight alone",Modifier.semantics{heading()},color=TillFailureTheme.colors.primaryText)
            CustomExerciseProtocol.equipment.forEach {token->TillFailureSecondaryButton(if(token in s.equipment)"✓ $token selected" else "Select $token",{event(CustomExerciseEvent.Equipment(token))},enabled=enabled)}
            TillFailurePrimaryButton("Save exercise",{event(CustomExerciseEvent.Save)},enabled=enabled && s.dirty,loading=s.busy)
        } else if(s.owner?.mode==CustomExerciseMode.Detail) s.baseline?.let {e->
            Text(e.name,Modifier.semantics{heading()},style=MaterialTheme.typography.titleLarge,color=TillFailureTheme.colors.primaryText)
            Text("Muscle groups: ${e.muscleGroups.joinToString().ifEmpty{"Not specified"}}",color=TillFailureTheme.colors.secondaryText)
            Text("Equipment: ${e.equipment.joinToString().ifEmpty{"Not specified"}}",color=TillFailureTheme.colors.secondaryText)
            Text(e.instructions,color=TillFailureTheme.colors.primaryText)
            TillFailurePrimaryButton("Correct exercise",{event(CustomExerciseEvent.Edit)},enabled=enabled)
        } else if(s.owner?.mode==CustomExerciseMode.List) {
            TillFailurePrimaryButton("Create exercise",{event(CustomExerciseEvent.Create)},enabled=enabled)
            s.items.forEach {e->TillFailureCard{Column(Modifier.fillMaxWidth().padding(TillFailureSpacing.lg)) {
                Text(e.name,style=MaterialTheme.typography.titleLarge,color=TillFailureTheme.colors.primaryText)
                Text("Custom · your definition",color=TillFailureTheme.colors.secondaryText)
                TillFailureSecondaryButton("View ${e.name}",{event(CustomExerciseEvent.Open(e.exerciseId))},enabled=!s.busy)
            }}}
            if(s.items.isEmpty() && !s.busy && s.authority!=null)Text("No custom exercises yet.",color=TillFailureTheme.colors.secondaryText)
            if(s.nextId!=null)TillFailureSecondaryButton(if(s.failedMore)"Retry more" else "Load more",{event(CustomExerciseEvent.LoadMore)},enabled=!s.busy)
        }
        if(s.message.isNotBlank())Text(s.message,Modifier.semantics{liveRegion=LiveRegionMode.Polite},color=TillFailureTheme.colors.primaryText)
        if(s.locked)Text("Protected storage is unavailable. Nothing was discarded.",color=TillFailureTheme.colors.warning)
        if(s.retryOriginal)TillFailureSecondaryButton("Retry original exercise save",{event(CustomExerciseEvent.RetryOriginal)},enabled=!s.busy)
        if(s.pending || s.locked)TillFailureSecondaryButton("Invitations and recovery",recovery)
        if(!s.pending && !s.locked && !s.denied) {
            if(s.conflict && s.owner?.exerciseId==null)TillFailureSecondaryButton("Start new exercise",{event(CustomExerciseEvent.StartNew)},enabled=!s.busy)
            else TillFailureSecondaryButton(if(s.conflict)"Reload current exercise" else "Refresh",{event(CustomExerciseEvent.Reload)},enabled=!s.busy)
        }
        TillFailureSecondaryButton(if(editor)"Cancel / Back" else "Back",{event(CustomExerciseEvent.Leave())})
        s.prompt?.let{p->TillFailureConfirmationDialog(title=if(p==CustomExercisePrompt.Leave)"Leave unsaved changes?" else if(p==CustomExercisePrompt.StartNew)"Start a new exercise?" else "Reload and replace your draft?",
            message=if(p==CustomExercisePrompt.Leave)"Your unsent draft will be lost. Submitted work stays protected." else "Your foreground draft will be replaced. This does not resubmit the previous command.",
            confirmLabel=if(p==CustomExercisePrompt.Leave)"Leave" else if(p==CustomExercisePrompt.StartNew)"Start new" else "Reload",onConfirm={event(CustomExerciseEvent.Confirm)},onDismiss={event(CustomExerciseEvent.Stay)})}
    }
}
