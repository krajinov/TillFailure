package com.delminiusapps.tillfailure.exercises

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.delminiusapps.tillfailure.core.designsystem.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.launch

@Composable
internal fun ExercisesRoute(repository: SystemExerciseRepository, owner: ExerciseVisit, isCurrent: () -> Boolean,
    onOpen: (String) -> Unit, onBack: () -> Unit, onCustom:(()->Unit)?=null) {
    val vm: ExercisesViewModel = koinViewModel(parameters = { parametersOf(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(isCurrent)
    val open by rememberUpdatedState(onOpen)
    val back by rememberUpdatedState(onBack)
    DisposableEffect(vm, owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.suspend(owner) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.clear(owner) }
    }
    LaunchedEffect(vm, owner, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (current()) vm.bind(owner)
            launch { vm.effect.collect { effect ->
                if (current() && effect.owner == owner && vm.consume(effect)) {
                    if (effect.id == null) back() else open(effect.id)
                }
            } }
        }
    }
    ExercisesScreen(if (state.owner == owner) state else ExercisesState(loading = true), owner.exerciseId != null, onCustom) { event ->
        if (current()) vm.onEvent(owner, event)
    }
}

@Composable
internal fun ExercisesScreen(state: ExercisesState, detail: Boolean, onCustom:(()->Unit)?=null, onEvent: (ExercisesEvent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(TillFailureSpacing.xl), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.lg)) {
        item { TillFailureSecondaryButton("Back", { onEvent(ExercisesEvent.Back) }) }
        item { Text(if (detail) "Exercise details" else "Exercises", Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText) }
        item { Text("Published system exercises · online only", color = TillFailureTheme.colors.secondaryText) }
        if(!detail && onCustom!=null)item { TillFailureSecondaryButton("My custom exercises",onCustom) }
        if (state.loading) item { Text("Loading exercises…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = TillFailureTheme.colors.secondaryText) }
        state.error?.let { error -> item {
            Text(when (error) {
                ExerciseError.Denied -> "Exercise access is unavailable. Recheck your account and try again."
                ExerciseError.Missing -> "This published exercise is no longer available."
                ExerciseError.Malformed -> "Exercise data could not be validated. Refresh or try again later."
                ExerciseError.Unavailable -> "Exercises are unavailable. Connect and retry."
            }, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = TillFailureTheme.colors.warning)
            TillFailureSecondaryButton("Retry", { onEvent(ExercisesEvent.Retry) }, enabled = !state.loading)
        } }
        if (detail) state.detail?.let { exercise -> item { ExerciseText(exercise) } }
        else {
            items(state.items, key = { it.id }) { exercise -> TillFailureCard {
                Column(Modifier.fillMaxWidth().padding(TillFailureSpacing.lg), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm)) {
                    Text(exercise.name, style = MaterialTheme.typography.titleLarge, color = TillFailureTheme.colors.primaryText)
                    Text("System exercise", color = TillFailureTheme.colors.secondaryText)
                    TillFailureSecondaryButton("View ${exercise.name}", { onEvent(ExercisesEvent.Open(exercise.id)) }, enabled = !state.loading)
                }
            } }
            if (!state.loading && state.error == null && state.items.isEmpty()) item { Text("No published exercises yet.", color = TillFailureTheme.colors.secondaryText) }
            if (state.nextId != null && state.error == null) item { TillFailurePrimaryButton("Load more", { onEvent(ExercisesEvent.LoadMore) }, enabled = !state.loading) }
        }
        item { TillFailureSecondaryButton("Refresh", { onEvent(ExercisesEvent.Refresh) }, enabled = !state.loading) }
    }
}
@Composable private fun ExerciseText(exercise: SystemExercise) {
    Column(verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.lg)) {
        Text(exercise.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = TillFailureTheme.colors.primaryText)
        Text("Muscle groups: ${exercise.muscleGroups.joinToString().ifEmpty { "Not specified" }}", color = TillFailureTheme.colors.secondaryText)
        Text("Equipment: ${exercise.equipment.joinToString().ifEmpty { "None specified" }}", color = TillFailureTheme.colors.secondaryText)
        Text(exercise.instructions, color = TillFailureTheme.colors.primaryText)
    }
}
