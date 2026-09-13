package com.delminiusapps.tillfailure.designcatalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.delminiusapps.tillfailure.core.designsystem.LoggedSetRow
import com.delminiusapps.tillfailure.core.designsystem.LoggedSetStatus
import com.delminiusapps.tillfailure.core.designsystem.TillFailureCard
import com.delminiusapps.tillfailure.core.designsystem.TillFailurePrimaryButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureProgressBar
import com.delminiusapps.tillfailure.core.designsystem.TillFailureScreenPreviews
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSpacing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusBanner
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusTone
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTextButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme

internal enum class CurrentSetResolution { Ready, Missing, Ambiguous }

internal data class CompleteSetActionPresentation(
    val label: String,
    val enabled: Boolean,
    val resolution: CurrentSetResolution,
)

internal fun activeWorkoutCompleteSetAction(sets: List<LoggedSetFixtureUiModel>): CompleteSetActionPresentation {
    val currentSets = sets.filter { it.status == LoggedSetStatus.Current }
    return when (currentSets.size) {
        1 -> CompleteSetActionPresentation(
            label = "Complete set ${currentSets.single().number}",
            enabled = true,
            resolution = CurrentSetResolution.Ready,
        )
        0 -> CompleteSetActionPresentation(
            label = "No current set",
            enabled = false,
            resolution = CurrentSetResolution.Missing,
        )
        else -> CompleteSetActionPresentation(
            label = "Resolve current set",
            enabled = false,
            resolution = CurrentSetResolution.Ambiguous,
        )
    }
}

internal data class ActiveWorkoutProgressPresentation(
    val label: String,
    val fraction: Float,
    val accessibilityDescription: String,
)

internal fun activeWorkoutProgress(model: ActiveWorkoutFixtureUiModel): ActiveWorkoutProgressPresentation {
    val progress = model.exerciseProgress.normalized()
    return ActiveWorkoutProgressPresentation(
        label = "${progress.completed} / ${progress.total}",
        fraction = progress.fraction,
        accessibilityDescription = "${progress.completed} of ${progress.total} exercises completed",
    )
}

internal fun activeWorkoutExerciseSummary(model: ActiveWorkoutFixtureUiModel): String {
    val setLabel = if (model.sets.size == 1) "set" else "sets"
    return "${model.sets.size} $setLabel · ${model.exerciseTargetReps} · Rest ${model.restDuration}"
}

@Composable
fun ActiveWorkoutFixtureScreen(
    model: ActiveWorkoutFixtureUiModel,
    onBackClick: () -> Unit,
    onMoreClick: () -> Unit,
    onReplaceExerciseClick: () -> Unit,
    onAddSetClick: () -> Unit,
    onSetClick: (Int) -> Unit,
    onNoteClick: () -> Unit,
    onCompleteSetClick: () -> Unit,
    onFinishClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val completeSetAction = activeWorkoutCompleteSetAction(model.sets)
    val workoutProgress = activeWorkoutProgress(model)
    CatalogScreenScaffold(
        title = model.title,
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        topActionGlyph = "⋯",
        topActionDescription = "Workout options",
        onTopActionClick = onMoreClick,
        bottomContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TillFailureTextButton("Note", onNoteClick)
                TillFailurePrimaryButton(
                    text = completeSetAction.label,
                    onClick = onCompleteSetClick,
                    modifier = Modifier.weight(1f),
                    enabled = completeSetAction.enabled,
                )
                TillFailureTextButton("Finish", onFinishClick)
            }
        },
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TillFailureSpacing.md, vertical = TillFailureSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(workoutProgress.label, modifier = Modifier.weight(1f), color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.labelMedium)
                Text(model.elapsedDuration, style = MaterialTheme.typography.titleMedium)
            }
            TillFailureProgressBar(
                progress = workoutProgress.fraction,
                progressDescription = workoutProgress.accessibilityDescription,
            )
            TillFailureCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CURRENT EXERCISE", modifier = Modifier.weight(1f), color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.labelSmall)
                        TillFailureTextButton("Replace", onReplaceExerciseClick)
                    }
                    Text(
                        model.exerciseName,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(activeWorkoutExerciseSummary(model), color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
            ) {
                WorkoutMetric("PREVIOUS", model.previousWeight, Modifier.weight(1f))
                WorkoutMetric("TODAY'S TARGET", model.targetWeight, Modifier.weight(1f), highlighted = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SETS", modifier = Modifier.weight(1f), color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.labelSmall)
                TillFailureTextButton("+ Add set", onAddSetClick)
            }
            model.sets.forEach { set ->
                LoggedSetRow(
                    setNumber = set.number,
                    weight = set.weight,
                    reps = set.reps,
                    status = set.status,
                    onClick = { onSetClick(set.number) },
                )
            }
            TillFailureStatusBanner(
                title = "Rest timer active",
                message = model.timer,
                tone = TillFailureStatusTone.Warning,
            )
        }
    }
}

@Composable
private fun WorkoutMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(TillFailureSpacing.sm))
            .background(TillFailureTheme.colors.surface)
            .padding(TillFailureSpacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs)) {
            Text(label, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.labelSmall)
            Text(
                value,
                color = if (highlighted) TillFailureTheme.colors.accent else TillFailureTheme.colors.primaryText,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@TillFailureScreenPreviews
@Composable
private fun ActiveWorkoutFixtureScreenPreview() {
    TillFailureTheme {
        ActiveWorkoutFixtureScreen(
            model = CatalogFixtures.activeWorkout,
            onBackClick = {},
            onMoreClick = {},
            onReplaceExerciseClick = {},
            onAddSetClick = {},
            onSetClick = {},
            onNoteClick = {},
            onCompleteSetClick = {},
            onFinishClick = {},
        )
    }
}
