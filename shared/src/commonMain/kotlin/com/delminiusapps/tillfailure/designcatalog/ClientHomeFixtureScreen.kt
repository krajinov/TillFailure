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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delminiusapps.tillfailure.core.designsystem.TillFailureBottomNavigation
import com.delminiusapps.tillfailure.core.designsystem.TillFailureCard
import com.delminiusapps.tillfailure.core.designsystem.TillFailureEmptyState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureErrorState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureLoadingState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureMetric
import com.delminiusapps.tillfailure.core.designsystem.TillFailureNavigationItem
import com.delminiusapps.tillfailure.core.designsystem.TillFailurePrimaryButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureScreenPreviews
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSpacing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme

private data class ClientNavigationSpec(
    val destination: ClientNavigationDestination,
    val label: String,
    val glyph: String,
)

private val ClientNavigationSpecs = listOf(
    ClientNavigationSpec(ClientNavigationDestination.Home, "Home", "⌂"),
    ClientNavigationSpec(ClientNavigationDestination.Workouts, "Workouts", "W"),
    ClientNavigationSpec(ClientNavigationDestination.Schedule, "Schedule", "□"),
    ClientNavigationSpec(ClientNavigationDestination.Progress, "Progress", "↗"),
    ClientNavigationSpec(ClientNavigationDestination.Messages, "Messages", "✉"),
)

internal fun clientNavigationItems(selectedDestination: ClientNavigationDestination): List<TillFailureNavigationItem> =
    ClientNavigationSpecs.map { item ->
        TillFailureNavigationItem(
            label = item.label,
            glyph = item.glyph,
            selected = item.destination == selectedDestination,
        )
    }

internal data class ClientWeeklyProgressPresentation(
    val label: String,
    val completedIndicator: String,
    val accessibilityDescription: String,
)

internal fun clientWeeklyProgress(model: ClientHomeFixtureUiModel): ClientWeeklyProgressPresentation {
    val progress = model.weekProgress.normalized()
    val workoutLabel = if (progress.total == 1) "workout" else "workouts"
    val label = "${progress.completed} of ${progress.total} $workoutLabel"
    return ClientWeeklyProgressPresentation(
        label = label,
        completedIndicator = progress.completed.toString(),
        accessibilityDescription = "$label completed",
    )
}

internal fun clientHomeAppointmentDate(model: ClientHomeFixtureUiModel): AppointmentDateFixtureUiModel =
    model.appointmentDate

internal fun clientNextWorkoutLabel(model: ClientHomeFixtureUiModel): String =
    "NEXT WORKOUT · ${model.nextWorkoutSchedule}"

internal fun clientAppointmentTitle(model: ClientHomeFixtureUiModel): String =
    "Personal training with ${model.trainerName}"

internal fun clientTrainerNoteLabel(model: ClientHomeFixtureUiModel): String =
    "${model.trainerName.uppercase()} · ${model.trainerNoteTime.uppercase()}"

@Composable
fun ClientHomeFixtureScreen(
    model: ClientHomeFixtureUiModel,
    onBackClick: () -> Unit,
    onStartWorkoutClick: () -> Unit,
    onAppointmentClick: () -> Unit,
    onNavigationItemClick: (Int) -> Unit,
    onRetryClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val weeklyProgress = clientWeeklyProgress(model)
    CatalogScreenScaffold(
        title = "Good morning, ${model.firstName}",
        onBackClick = onBackClick,
        topActionGlyph = model.firstName.take(1),
        topActionDescription = "Open profile",
        onTopActionClick = onProfileClick,
        bottomItems = clientNavigationItems(model.selectedNavigationDestination),
        onBottomItemClick = onNavigationItemClick,
        snackbarHostState = snackbarHostState,
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TillFailureSpacing.md, vertical = TillFailureSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
        ) {
            Text("YOUR WEEK", color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.labelSmall)
            Row(
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = weeklyProgress.accessibilityDescription
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(weeklyProgress.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(TillFailureTheme.colors.accent)
                        .padding(horizontal = TillFailureSpacing.sm, vertical = TillFailureSpacing.xs),
                ) {
                    Text(weeklyProgress.completedIndicator, color = TillFailureTheme.colors.onAccent, fontWeight = FontWeight.Bold)
                }
            }
            when (model.contentState) {
                FixtureContentState.Loading -> TillFailureLoadingState("Loading your next workout")
                FixtureContentState.Empty -> TillFailureEmptyState(
                    title = "No workout assigned",
                    message = "Your trainer will add your next session here.",
                )
                FixtureContentState.Error -> TillFailureErrorState(
                    title = "Could not load your plan",
                    message = "Check your connection and try again.",
                    onRetryClick = onRetryClick,
                )
                FixtureContentState.Ready -> ClientHomeReadyContent(model, onStartWorkoutClick, onAppointmentClick)
            }
        }
    }
}

@Composable
private fun ClientHomeReadyContent(
    model: ClientHomeFixtureUiModel,
    onStartWorkoutClick: () -> Unit,
    onAppointmentClick: () -> Unit,
) {
    val appointmentDate = clientHomeAppointmentDate(model)
    Column(verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TillFailureSpacing.md))
                .background(TillFailureTheme.colors.accent)
                .padding(TillFailureSpacing.md),
            verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
        ) {
            Text(clientNextWorkoutLabel(model), color = TillFailureTheme.colors.onAccent, style = MaterialTheme.typography.labelSmall)
            Text(
                model.workoutTitle,
                modifier = Modifier.semantics { heading() },
                color = TillFailureTheme.colors.onAccent,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(model.workoutSummary, color = TillFailureTheme.colors.onAccent, style = MaterialTheme.typography.bodySmall)
            TillFailurePrimaryButton(
                text = "Start workout",
                onClick = onStartWorkoutClick,
                modifier = Modifier.fillMaxWidth(),
                inverted = true,
            )
        }
        TillFailureCard(modifier = Modifier.fillMaxWidth(), onClick = onAppointmentClick) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(TillFailureSpacing.xs))
                        .background(TillFailureTheme.colors.elevatedSurface)
                        .padding(TillFailureSpacing.xs)
                        .clearAndSetSemantics { contentDescription = appointmentDate.fullDate },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(appointmentDate.weekday, color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.labelSmall)
                    Text(appointmentDate.dayNumber, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(clientAppointmentTitle(model), style = MaterialTheme.typography.titleSmall)
                    Text(model.appointmentSummary, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = TillFailureTheme.colors.secondaryText)
            }
        }
        TillFailureCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(TillFailureSpacing.md)) {
                Text(
                    clientTrainerNoteLabel(model),
                    color = TillFailureTheme.colors.accent,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(model.trainerNote, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
        ) {
            TillFailureMetric("Body weight", model.bodyWeight, model.bodyWeightTrend, Modifier.weight(1f))
            TillFailureMetric("Consistency", model.consistency, model.consistencyPeriod, Modifier.weight(1f))
        }
    }
}

@TillFailureScreenPreviews
@Composable
private fun ClientHomeFixtureScreenPreview() {
    TillFailureTheme {
        ClientHomeFixtureScreen(
            model = CatalogFixtures.clientHome,
            onBackClick = {},
            onStartWorkoutClick = {},
            onAppointmentClick = {},
            onNavigationItemClick = {},
            onRetryClick = {},
            onProfileClick = {},
        )
    }
}
