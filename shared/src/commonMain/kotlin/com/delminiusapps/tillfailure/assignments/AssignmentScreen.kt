package com.delminiusapps.tillfailure.assignments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.delminiusapps.tillfailure.core.designsystem.*
import com.delminiusapps.tillfailure.programs.DraftRepository
import kotlin.time.Instant

@Composable internal fun AssignmentRoute(repository:AssignmentRepository,drafts:DraftRepository?,scope:AssignmentScope,back:()->Unit,recovery:()->Unit) {
    val vm:AssignmentViewModel=viewModel(factory=viewModelFactory{initializer{AssignmentViewModel(repository,drafts)}})
    val state by vm.state.collectAsStateWithLifecycle();val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(vm,scope,lifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)vm.clear()};lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);vm.clear()}
    }
    LaunchedEffect(vm,scope,lifecycle){lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){vm.attach(scope);kotlinx.coroutines.awaitCancellation()}}
    AssignmentScreen(if(state.scope==scope)state else AssignmentState(busy=true),vm::onEvent,back,recovery,repository.issuanceEnabled)
}
@Composable internal fun AssignmentScreen(s:AssignmentState,event:(AssignmentEvent)->Unit,back:()->Unit,recovery:()->Unit,issuanceEnabled:Boolean=false) {
    val enabled=!s.busy && s.pending==null
    if(s.confirm && s.preparation!=null) {
        val a=s.preparation.assignment
        TillFailureConfirmationDialog(title="Assign this exact version?",message="Client: ${a.clientUid}\nVersion ${a.versionNumber}\nScheduled: ${Instant.fromEpochMilliseconds(a.scheduledInstantMillis)} (UTC)\nExpires: ${Instant.fromEpochMilliseconds(a.accessExpiresAtMillis)} (UTC)\n\nThis creates one independent assignment. A newly confirmed assignment can repeat the same content.",confirmLabel="Assign",onConfirm={event(AssignmentEvent.Submit)},onDismiss={event(AssignmentEvent.Cancel)})
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TillFailureSpacing.xl),verticalArrangement=Arrangement.spacedBy(TillFailureSpacing.lg)) {
        Text(if(s.scope?.role=="trainer")"Program assignments" else "Planned workouts",Modifier.semantics{heading()},style=MaterialTheme.typography.headlineLarge,color=TillFailureTheme.colors.primaryText)
        Text("Synthetic · online only",color=TillFailureTheme.colors.secondaryText)
        if(s.busy)Text("Loading complete content…",Modifier.semantics{liveRegion=LiveRegionMode.Polite},color=TillFailureTheme.colors.primaryText)
        s.error?.let{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Assertive},color=TillFailureTheme.colors.warning)}
        s.notice?.let{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Polite},color=TillFailureTheme.colors.primaryText)}
        s.pending?.let{Text("An original command is pending. Its target, version, schedule and key remain protected.",color=TillFailureTheme.colors.warning);TillFailureSecondaryButton("Invitations and recovery",recovery)}
        val copied=s.content?:s.preview
        if(copied!=null) {
            Text(copied.title,Modifier.semantics{heading()},style=MaterialTheme.typography.titleLarge,color=TillFailureTheme.colors.primaryText)
            Text(copied.description,color=TillFailureTheme.colors.primaryText)
            copied.workouts.forEach{w->Text(w.title,Modifier.semantics{heading()},style=MaterialTheme.typography.titleMedium,color=TillFailureTheme.colors.primaryText)
                w.items.forEachIndexed{index,item->
                    Text("${index+1}. ${item.name}",Modifier.semantics{heading()},style=MaterialTheme.typography.titleMedium,color=TillFailureTheme.colors.primaryText)
                    Text(item.instructions,color=TillFailureTheme.colors.primaryText)
                    Text("Muscle groups: ${item.muscleGroups.joinToString()}\nEquipment: ${item.equipment.joinToString()}",color=TillFailureTheme.colors.secondaryText)
                    val p=item.prescription
                    Text("${p.sets} sets · ${p.reps} reps · ${p.restSeconds} seconds rest"+(p.loadGrams?.let{" · $it grams"}?:""),color=TillFailureTheme.colors.primaryText)
                }
            }
            val assignment=s.preparation?.assignment
            val schedule=assignment?.scheduledInstantMillis?:s.plan?.scheduledInstantMillis
            val expiry=assignment?.accessExpiresAtMillis?:s.detail?.accessExpiresAtMillis
            if(schedule!=null && expiry!=null)Text("Scheduled: ${Instant.fromEpochMilliseconds(schedule)} (UTC)\nExpires: ${Instant.fromEpochMilliseconds(expiry)} (UTC)",color=TillFailureTheme.colors.primaryText)
            if(s.preview!=null) {
                TillFailurePrimaryButton("Assign",{event(AssignmentEvent.Confirm)},enabled=enabled && issuanceEnabled && AssignmentProtocol.ISSUANCE_ENABLED)
                if(!issuanceEnabled || !AssignmentProtocol.ISSUANCE_ENABLED)Text("Assign is unavailable while native verification is incomplete.",color=TillFailureTheme.colors.secondaryText)
                TillFailureSecondaryButton("Cancel preview",{event(AssignmentEvent.Cancel)},enabled=!s.busy)
            } else TillFailureSecondaryButton("Back to planned workouts",{event(AssignmentEvent.CloseDetail)},enabled=!s.busy)
        } else {
            s.headers.forEach{h->TillFailureSecondaryButton("Version ${h.sourceVersionNumber} · ${Instant.fromEpochMilliseconds(h.scheduledInstantMillis)} UTC",{event(AssignmentEvent.Open(h.assignmentId))},enabled=!s.busy)}
            if(s.headers.isEmpty() && !s.busy && s.error==null)Text("No assignments found.",color=TillFailureTheme.colors.secondaryText)
            if(s.after!=null || s.afterId!=null)TillFailureSecondaryButton("Next assignments",{event(AssignmentEvent.Next)},enabled=!s.busy)
            if(s.scope?.role=="trainer" && s.pending==null) {
                Text("Only versions with one complete workout and 1–12 exercises are supported.",color=TillFailureTheme.colors.secondaryText)
                Text("Choose an immutable program version",Modifier.semantics{heading()},style=MaterialTheme.typography.titleLarge,color=TillFailureTheme.colors.primaryText)
                s.templates.forEach{t->TillFailureSecondaryButton(t.title.ifEmpty{"Untitled program"},{event(AssignmentEvent.Template(t.templateId))},enabled=enabled)}
                if(s.templateNext!=null)TillFailureSecondaryButton("Next programs",{event(AssignmentEvent.NextTemplates)},enabled=enabled)
                s.versions.forEach{v->TillFailureSecondaryButton("${if(v.versionId==s.versionId)"Selected · " else ""}Version ${v.versionNumber}: ${v.title}",{event(AssignmentEvent.Version(v.versionId))},enabled=enabled)}
                if(s.versionNext!=null)TillFailureSecondaryButton("Older versions",{event(AssignmentEvent.NextVersions)},enabled=enabled)
                if(s.versionId!=null){TillFailureTextField(s.schedule,{event(AssignmentEvent.Schedule(it))},"Scheduled UTC instant",enabled=enabled,supportingText="ISO UTC, ending in Z. Within the next 24 hours.");TillFailurePrimaryButton("Prepare complete preview",{event(AssignmentEvent.Prepare)},enabled=enabled)}
            }
        }
        TillFailureSecondaryButton("Refresh",{event(AssignmentEvent.Refresh)},enabled=!s.busy)
        TillFailureSecondaryButton("Back",back)
    }
}
