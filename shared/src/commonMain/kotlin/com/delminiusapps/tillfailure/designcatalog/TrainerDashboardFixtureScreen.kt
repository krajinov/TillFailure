package com.delminiusapps.tillfailure.designcatalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.delminiusapps.tillfailure.core.designsystem.TillFailureCard
import com.delminiusapps.tillfailure.core.designsystem.TillFailureEmptyState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureErrorState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureFilterChip
import com.delminiusapps.tillfailure.core.designsystem.TillFailureLoadingState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureNavigationItem
import com.delminiusapps.tillfailure.core.designsystem.TillFailureScreenPreviews
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSectionTitle
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSegmentItem
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSegmentedControl
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSegmentedControlLayout
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSizing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSpacing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusBadge
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusTone
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme
import com.delminiusapps.tillfailure.core.designsystem.tillFailureSegmentedControlLayout

private data class TrainerNavigationSpec(
    val destination: TrainerNavigationDestination,
    val label: String,
    val glyph: String,
)

private val TrainerNavigationSpecs = listOf(
    TrainerNavigationSpec(TrainerNavigationDestination.Dashboard, "Dashboard", "▦"),
    TrainerNavigationSpec(TrainerNavigationDestination.Clients, "Clients", "♙"),
    TrainerNavigationSpec(TrainerNavigationDestination.Programs, "Programs", "▤"),
    TrainerNavigationSpec(TrainerNavigationDestination.Schedule, "Schedule", "□"),
    TrainerNavigationSpec(TrainerNavigationDestination.Messages, "Messages", "✉"),
)

internal fun trainerNavigationItems(selectedDestination: TrainerNavigationDestination): List<TillFailureNavigationItem> =
    TrainerNavigationSpecs.map { item ->
        TillFailureNavigationItem(
            label = item.label,
            glyph = item.glyph,
            selected = item.destination == selectedDestination,
        )
    }

internal val TrainerDashboardSection.label: String
    get() = when (this) {
        TrainerDashboardSection.Active -> "Active"
        TrainerDashboardSection.Messages -> "Messages"
        TrainerDashboardSection.Appointments -> "Appointments"
    }

typealias TrainerDashboardSegmentLayout = TillFailureSegmentedControlLayout

internal fun trainerDashboardSegmentLayout(fontScale: Float): TrainerDashboardSegmentLayout =
    tillFailureSegmentedControlLayout(fontScale)

internal fun trainerDashboardSegmentWeight(label: String): Float = label.length + 3f

internal fun dispatchActivityClick(
    activity: ActivityFixtureUiModel,
    onActivityClick: (String) -> Unit,
) {
    onActivityClick(activity.title)
}

internal fun trainerDashboardSessionCount(count: Int): String =
    "$count ${if (count == 1) "session" else "sessions"}"

internal fun trainerDashboardUpdateCount(count: Int): String =
    "$count ${if (count == 1) "update" else "updates"}"

internal fun trainerDashboardClientSummary(count: Int): String {
    val normalizedCount = count.coerceAtLeast(0)
    val clientLabel = if (normalizedCount == 1) "client" else "clients"
    val verb = if (normalizedCount == 1) "needs" else "need"
    return "A steady day · $normalizedCount $clientLabel $verb attention"
}

internal fun trainerDashboardMessageDescription(count: Int): String {
    val normalizedCount = count.coerceAtLeast(0)
    val messageLabel = if (normalizedCount == 1) "message" else "messages"
    return "$normalizedCount unread $messageLabel"
}

internal fun activityGlyph(activity: ActivityFixtureUiModel): String =
    activity.glyph ?: initialsFromName(activity.title)

@Composable
fun TrainerDashboardFixtureScreen(
    model: TrainerDashboardFixtureUiModel,
    onBackClick: () -> Unit,
    onNavigationItemClick: (Int) -> Unit,
    onAppointmentClick: (String) -> Unit,
    onActivityClick: (String) -> Unit,
    onSectionClick: (TrainerDashboardSection) -> Unit,
    onViewScheduleClick: () -> Unit,
    onReviewActivitiesClick: () -> Unit,
    onRetryClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    CatalogScreenScaffold(
        title = model.date,
        onBackClick = onBackClick,
        topActionGlyph = initialsFromName(model.trainerName).take(1),
        topActionDescription = "Open trainer profile",
        onTopActionClick = onProfileClick,
        bottomItems = trainerNavigationItems(model.selectedNavigationDestination),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Good morning, ${model.trainerName}",
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
                TillFailureStatusBadge(
                    label = model.messages.coerceAtLeast(0).toString(),
                    tone = TillFailureStatusTone.Warning,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = trainerDashboardMessageDescription(model.messages)
                    },
                )
            }
            Text(trainerDashboardClientSummary(model.activeClients), color = TillFailureTheme.colors.secondaryText)
            TrainerDashboardSegmentedControl(model.selectedSection, onSectionClick)
            when (model.contentState) {
                FixtureContentState.Loading -> TillFailureLoadingState("Loading today’s schedule")
                FixtureContentState.Empty -> TillFailureEmptyState("No appointments", "Your schedule is clear for today.")
                FixtureContentState.Error -> TillFailureErrorState(
                    title = "Dashboard unavailable",
                    message = "The fixture demonstrates the approved error language.",
                    onRetryClick = onRetryClick,
                )
                FixtureContentState.Ready -> DashboardReadyContent(
                    model = model,
                    onAppointmentClick = onAppointmentClick,
                    onActivityClick = onActivityClick,
                    onViewScheduleClick = onViewScheduleClick,
                    onReviewActivitiesClick = onReviewActivitiesClick,
                )
            }
        }
    }
}

@Composable
private fun TrainerDashboardSegmentedControl(
    selectedSection: TrainerDashboardSection,
    onSectionClick: (TrainerDashboardSection) -> Unit,
) {
    TillFailureSegmentedControl(
        items = TrainerDashboardSection.entries.map { section ->
            TillFailureSegmentItem(
                label = section.label,
                selected = section == selectedSection,
                onClick = { onSectionClick(section) },
                weight = trainerDashboardSegmentWeight(section.label),
            )
        },
    )
}

@Composable
private fun DashboardReadyContent(
    model: TrainerDashboardFixtureUiModel,
    onAppointmentClick: (String) -> Unit,
    onActivityClick: (String) -> Unit,
    onViewScheduleClick: () -> Unit,
    onReviewActivitiesClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm)) {
        TillFailureSectionTitle(
            eyebrow = "Today’s appointments",
            title = trainerDashboardSessionCount(model.appointments.size),
            action = "View schedule",
            onActionClick = onViewScheduleClick,
        )
        model.appointments.forEach { appointment ->
            TillFailureCard(Modifier.fillMaxWidth(), onClick = { onAppointmentClick(appointment.client) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
                ) {
                    Text(appointment.time, color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f)) {
                        Text(appointment.client, style = MaterialTheme.typography.titleSmall)
                        Text(appointment.summary, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(appointment.status, color = TillFailureTheme.colors.success, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        TillFailureSectionTitle(
            eyebrow = "Needs attention",
            title = trainerDashboardUpdateCount(model.activities.size),
            action = "Review",
            onActionClick = onReviewActivitiesClick,
        )
        model.activities.forEach { activity ->
            val activityShape = RoundedCornerShape(TillFailureSpacing.sm)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TillFailureSizing.minimumTouchTarget)
                    .clip(activityShape)
                    .clickable(
                        role = Role.Button,
                        onClick = { dispatchActivityClick(activity, onActivityClick) },
                    )
                    .background(TillFailureTheme.colors.surface)
                    .padding(TillFailureSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
            ) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(TillFailureTheme.colors.elevatedSurface).padding(TillFailureSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(activityGlyph(activity), color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.labelMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text(activity.title, style = MaterialTheme.typography.titleSmall)
                    Text(activity.detail, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = TillFailureTheme.colors.secondaryText)
            }
        }
    }
}

@TillFailureScreenPreviews
@Composable
private fun TrainerDashboardFixtureScreenPreview() {
    TillFailureTheme {
        TrainerDashboardFixtureScreen(
            model = CatalogFixtures.trainerDashboard,
            onBackClick = {},
            onNavigationItemClick = {},
            onAppointmentClick = {},
            onActivityClick = {},
            onSectionClick = {},
            onViewScheduleClick = {},
            onReviewActivitiesClick = {},
            onRetryClick = {},
            onProfileClick = {},
        )
    }
}
