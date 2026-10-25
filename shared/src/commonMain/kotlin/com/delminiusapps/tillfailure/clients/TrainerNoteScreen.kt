package com.delminiusapps.tillfailure.clients

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import com.delminiusapps.tillfailure.invitations.InvitationClient
import com.delminiusapps.tillfailure.core.designsystem.*
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun TrainerNoteRoute(client:InvitationClient,owner:NoteOwner,isCurrent:()->Boolean,registerLeave:((String?)->Unit,Boolean)->Unit,onExit:(String?)->Unit,onRecovery:()->Unit) {
    val vm:TrainerNoteViewModel=koinViewModel(parameters={parametersOf(client)})
    val state by vm.state.collectAsStateWithLifecycle();val lifecycle=LocalLifecycleOwner.current
    val exit by rememberUpdatedState(onExit)
    val current by rememberUpdatedState(isCurrent)
    val leave=remember(vm,owner) { { page:String? -> vm.onEvent(owner,vm.state.value.promptId,NoteEvent.Leave(page)) } }
    DisposableEffect(vm,owner,lifecycle) {
        vm.bind(owner);registerLeave(leave,true)
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) vm.clear() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);registerLeave(leave,false);vm.clear() }
    }
    LaunchedEffect(vm,owner,lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.bind(owner)
            launch { vm.effect.collect { if(current() && vm.consumeNavigation(it)) exit(it.exit) } }
        }
    }
    TrainerNoteScreen(if(state.owner==owner && current()) state else TrainerNoteState(),{ if(current()) vm.onEvent(owner,state.promptId,it) },{ if(current()) onRecovery() })
}
@Composable
internal fun TrainerNoteScreen(state:TrainerNoteState,onEvent:(NoteEvent)->Unit,onRecovery:()->Unit) {
    val enabled=state.baseline!=null && !state.busy && !state.pending && !state.storageLocked && !state.conflict && !state.denied && state.prompt==null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TillFailureSpacing.xl),verticalArrangement=Arrangement.spacedBy(TillFailureSpacing.lg)) {
        Text("Private trainer note",Modifier.semantics { heading() },style=MaterialTheme.typography.headlineLarge,color=TillFailureTheme.colors.primaryText)
        Text("Synthetic coaching administration only. Do not enter health, injury or other sensitive information. Only the author can access this note while the current relationship remains eligible.",color=TillFailureTheme.colors.secondaryText)
        Text("Unsaved text is lost when the app backgrounds or access is lost. Submitted work remains protected until its outcome is confirmed.",color=TillFailureTheme.colors.secondaryText)
        if(state.baseline?.note==null && state.baseline!=null) Text("No note saved yet.",color=TillFailureTheme.colors.secondaryText)
        if(!state.denied) TillFailureTextField(state.body,{onEvent(NoteEvent.Body(it))},"Note",enabled=enabled,singleLine=false,maxLines=8,
            supportingText="Up to 2,000 characters; no attachments or sensitive data.",keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Default))
        TillFailurePrimaryButton("Save note",{onEvent(NoteEvent.Save)},enabled=enabled && state.dirty,loading=state.busy)
        if(state.pending) {
            Text("Submitted work is protected. Sign-out stays blocked until its outcome is settled.",color=TillFailureTheme.colors.warning)
            TillFailureSecondaryButton("Retry original note save",{onEvent(NoteEvent.RetryOriginal)},enabled=!state.busy)
            TillFailureSecondaryButton("Invitations and recovery",onRecovery)
        } else if(!state.denied) TillFailureSecondaryButton(if(state.conflict) "Reload current note" else "Reload note",{onEvent(NoteEvent.Reload)},enabled=!state.busy && !state.storageLocked)
        if(state.storageLocked) Text("Protected storage is unavailable. Nothing was discarded.",color=TillFailureTheme.colors.warning)
        if(state.message.isNotBlank()) Text(state.message,Modifier.semantics { liveRegion=LiveRegionMode.Polite },color=TillFailureTheme.colors.primaryText)
        TillFailureSecondaryButton("Cancel / Back",{onEvent(NoteEvent.Leave())})
        state.prompt?.let { prompt -> TillFailureConfirmationDialog(
            title=if(prompt==NotePrompt.Leave) "Leave unsaved changes?" else "Reload and replace your draft?",
            message=if(prompt==NotePrompt.Leave) "Your unsent draft will be lost. Submitted work stays protected." else "The draft is replaced only after a fresh authorized read succeeds.",
            confirmLabel=if(prompt==NotePrompt.Leave) "Leave" else "Reload",onConfirm={onEvent(NoteEvent.Confirm)},onDismiss={onEvent(NoteEvent.CancelPrompt)}) }
    }
}
