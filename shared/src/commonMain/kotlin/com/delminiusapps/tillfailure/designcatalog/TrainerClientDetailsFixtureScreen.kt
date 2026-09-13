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
import androidx.compose.ui.unit.dp
import com.delminiusapps.tillfailure.core.designsystem.TillFailureCard
import com.delminiusapps.tillfailure.core.designsystem.TillFailureFilterChip
import com.delminiusapps.tillfailure.core.designsystem.TillFailurePrimaryButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureProgressBar
import com.delminiusapps.tillfailure.core.designsystem.TillFailureScreenPreviews
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSecondaryButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSegmentItem
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSegmentedControl
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSpacing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusBanner
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusTone
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme


internal data class TrainerProgramProgressPresentation(
    val weekLabel: String,
    val detailLabel: String,
    val fraction: Float,
    val accessibilityDescription: String,
)

internal fun trainerProgramProgress(model: TrainerClientDetailsFixtureUiModel): TrainerProgramProgressPresentation {
    val week = model.programWeek.normalized()
    val progress = model.programProgress.normalized()
    val weeklyWorkoutTarget = model.weeklyWorkoutTarget.coerceAtLeast(0)
    val weeklyWorkoutLabel = if (weeklyWorkoutTarget == 1) "workout" else "workouts"
    return TrainerProgramProgressPresentation(
        weekLabel = "WEEK ${week.completed} OF ${week.total}",
        detailLabel = "$weeklyWorkoutTarget $weeklyWorkoutLabel weekly · ${progress.completed} of ${progress.total} completed",
        fraction = progress.fraction,
        accessibilityDescription = "${progress.completed} of ${progress.total} workouts completed",
    )
}

@Composable
fun TrainerClientDetailsFixtureScreen(
    model: TrainerClientDetailsFixtureUiModel,
    onBackClick: () -> Unit,
    onMoreClick: () -> Unit,
    onMessageClick: () -> Unit,
    onTabClick: (TrainerClientDetailsTab) -> Unit,
    onEditClick: () -> Unit,
    onAssignProgramClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val programProgress = trainerProgramProgress(model)
    CatalogScreenScaffold(
        title = "Client details",
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        topActionGlyph = "⋯",
        topActionDescription = "Client options",
        onTopActionClick = onMoreClick,
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TillFailureSpacing.md, vertical = TillFailureSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
            ) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(TillFailureTheme.colors.elevatedSurface).padding(TillFailureSpacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initialsFromName(model.name), color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text(model.name, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(model.since, color = TillFailureTheme.colors.success, style = MaterialTheme.typography.bodySmall)
                }
                TillFailurePrimaryButton("Message", onMessageClick)
            }
            TillFailureSegmentedControl(
                items = TrainerClientDetailsTab.entries.map { tab ->
                    TillFailureSegmentItem(
                        label = tab.label,
                        selected = tab == model.selectedTab,
                        onClick = { onTabClick(tab) },
                        weight = 1f,
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
            ) {
                ClientMetric("ADHERENCE", model.adherence, Modifier.weight(1f))
                ClientMetric("LAST WORKOUT", model.lastWorkout, Modifier.weight(1f))
                ClientMetric("NEXT SESSION", model.nextSession, Modifier.weight(1f))
            }
            TillFailureCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
                ) {
                    Row {
                        Text("CURRENT PROGRAM", modifier = Modifier.weight(1f), color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.labelSmall)
                        Text(programProgress.weekLabel, color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(model.programName, style = MaterialTheme.typography.titleLarge)
                    Text(programProgress.detailLabel, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                    TillFailureProgressBar(
                        progress = programProgress.fraction,
                        progressDescription = programProgress.accessibilityDescription,
                    )
                }
            }
            TillFailureStatusBanner(
                title = model.restriction.title,
                message = model.restriction.message,
                tone = TillFailureStatusTone.Warning,
            )
            TillFailureStatusBanner(
                title = "Trainer-only note",
                message = model.trainerNote,
                tone = TillFailureStatusTone.Info,
            )
            Text("RECENT ACTIVITY", color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.labelSmall)
            TillFailureCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(model.recentActivity.glyph, color = TillFailureTheme.colors.success, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f)) {
                        Text(model.recentActivity.title, style = MaterialTheme.typography.titleSmall)
                        Text(model.recentActivity.summary, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
            ) {
                TillFailureSecondaryButton("Edit", onEditClick, Modifier.weight(1f))
                TillFailurePrimaryButton("Assign program", onAssignProgramClick, Modifier.weight(2f))
            }
        }
    }
}


@Composable
private fun ClientMetric(label: String, value: String, modifier: Modifier = Modifier) {
    TillFailureCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(TillFailureSpacing.sm)) {
            Text(label, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@TillFailureScreenPreviews
@Composable
private fun TrainerClientDetailsFixtureScreenPreview() {
    TillFailureTheme {
        TrainerClientDetailsFixtureScreen(
            model = CatalogFixtures.trainerClientDetails,
            onBackClick = {},
            onMoreClick = {},
            onMessageClick = {},
            onTabClick = {},
            onEditClick = {},
            onAssignProgramClick = {},
        )
    }
}
