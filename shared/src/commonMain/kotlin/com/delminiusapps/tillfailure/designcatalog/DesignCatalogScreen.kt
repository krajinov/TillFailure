package com.delminiusapps.tillfailure.designcatalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.delminiusapps.tillfailure.core.designsystem.ExerciseRow
import com.delminiusapps.tillfailure.core.designsystem.LoggedSetRow
import com.delminiusapps.tillfailure.core.designsystem.LoggedSetStatus
import com.delminiusapps.tillfailure.core.designsystem.TillFailureActionSheet
import com.delminiusapps.tillfailure.core.designsystem.TillFailureConfirmationDialog
import com.delminiusapps.tillfailure.core.designsystem.TillFailureEmptyState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureErrorState
import com.delminiusapps.tillfailure.core.designsystem.TillFailureFilterChip
import com.delminiusapps.tillfailure.core.designsystem.TillFailureIconButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureLoadingState
import com.delminiusapps.tillfailure.core.designsystem.TillFailurePrimaryButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSecondaryButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSnackbar
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSpacing
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusBadge
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusBanner
import com.delminiusapps.tillfailure.core.designsystem.TillFailureStatusTone
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTextButton
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTextField
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme
import com.delminiusapps.tillfailure.core.designsystem.WorkoutRow

enum class CatalogPreviewDestination {
    Authentication,
    ClientHome,
    ActiveWorkout,
    TrainerDashboard,
    TrainerClientDetails,
}

@Composable
fun DesignCatalogScreen(
    onBackClick: () -> Unit,
    onOpenPreview: (CatalogPreviewDestination) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    var fieldValue by rememberSaveable { mutableStateOf("Alex Morgan") }
    var invalidFieldValue by rememberSaveable { mutableStateOf("Invalid value") }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var filterSelected by rememberSaveable { mutableStateOf(true) }
    var catalogFeedback by rememberSaveable { mutableStateOf<String?>(null) }

    CatalogScreenScaffold(
        title = "Development UI catalog",
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(TillFailureSpacing.md),
            verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.md),
        ) {
            Text(
                text = "Milestone 2 fixtures",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Development-only visual compositions. They do not authenticate, persist data, or bypass a production shell.",
                color = TillFailureTheme.colors.secondaryText,
            )
            CatalogSection("Reference screens") {
                CatalogPreviewDestination.entries.forEach { destination ->
                    TillFailureSecondaryButton(
                        text = destination.displayName,
                        onClick = { onOpenPreview(destination) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            CatalogSection("Actions") {
                TillFailurePrimaryButton("Primary", { catalogFeedback = "Primary action tapped" }, Modifier.fillMaxWidth())
                TillFailurePrimaryButton("Loading", {}, Modifier.fillMaxWidth(), loading = true)
                TillFailurePrimaryButton("Loading inverted", {}, Modifier.fillMaxWidth(), loading = true, inverted = true)
                TillFailureSecondaryButton("Secondary", { catalogFeedback = "Secondary action tapped" }, Modifier.fillMaxWidth())
                TillFailureSecondaryButton("Disabled", {}, Modifier.fillMaxWidth(), enabled = false)
                Row(horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs)) {
                    TillFailureTextButton("Text action", { catalogFeedback = "Text action tapped" })
                    TillFailureIconButton("+", "Add item", { catalogFeedback = "Add item tapped" })
                }
            }
            CatalogSection("Inputs and status") {
                TillFailureTextField(fieldValue, { fieldValue = it }, "Client name", supportingText = "Shared field styling")
                TillFailureTextField(invalidFieldValue, { invalidFieldValue = it }, "Weight", supportingText = "Enter a valid weight", isError = true)
                Row(horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs)) {
                    TillFailureFilterChip("Selected", filterSelected, { filterSelected = true })
                    TillFailureFilterChip("Default", !filterSelected, { filterSelected = false })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs)) {
                    TillFailureFilterChip("Disabled selected", selected = true, onClick = {}, enabled = false)
                    TillFailureFilterChip("Disabled", selected = false, onClick = {}, enabled = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs)) {
                    TillFailureStatusBadge("Complete", TillFailureStatusTone.Success)
                    TillFailureStatusBadge("Review", TillFailureStatusTone.Warning)
                }
                TillFailureStatusBanner("Offline", "Showing downloaded workout data.", TillFailureStatusTone.Info)
                TillFailureSnackbar(catalogFeedback ?: "Workout saved")
            }
            CatalogSection("Workout language") {
                WorkoutRow("Upper Body Strength", "7 exercises · 55 min", "Assigned")
                ExerciseRow("Barbell Bench Press", "4 sets · 6–8 reps", status = "Current")
                LoggedSetRow(1, "72.5 kg", "8 reps", LoggedSetStatus.Completed)
                LoggedSetRow(2, "72.5 kg", "9 reps", LoggedSetStatus.Edited)
                LoggedSetRow(3, "72.5 kg", "8 reps", LoggedSetStatus.Current)
            }
            CatalogSection("Feedback states") {
                TillFailureLoadingState("Loading client data")
                TillFailureEmptyState("Nothing here yet", "Assigned workouts will appear here.")
                TillFailureErrorState("Could not load", "Try the fixture again.", onRetryClick = { catalogFeedback = "Retry tapped" })
                TillFailureSecondaryButton("Open confirmation dialog", { showDialog = true }, Modifier.fillMaxWidth())
                TillFailureSecondaryButton("Open action sheet", { showSheet = true }, Modifier.fillMaxWidth())
            }
        }
    }

    if (showDialog) {
        TillFailureConfirmationDialog(
            title = "Finish workout?",
            message = "One set is still pending. Your logged sets will remain visible.",
            confirmLabel = "Finish workout",
            onConfirm = { showDialog = false },
            onDismiss = { showDialog = false },
        )
    }
    if (showSheet) {
        TillFailureActionSheet(
            title = "Workout options",
            message = "This is a visual fixture; no workout data is changed.",
            primaryLabel = "Continue workout",
            onPrimaryClick = { showSheet = false },
            onDismiss = { showSheet = false },
        )
    }
}

private val CatalogPreviewDestination.displayName: String
    get() = when (this) {
        CatalogPreviewDestination.Authentication -> "Authentication · QVhze"
        CatalogPreviewDestination.ClientHome -> "Client Home · OmHDs"
        CatalogPreviewDestination.ActiveWorkout -> "Active Workout · v66zG"
        CatalogPreviewDestination.TrainerDashboard -> "Trainer Dashboard · eq0D5"
        CatalogPreviewDestination.TrainerClientDetails -> "Trainer Client Details · bLqqR"
    }

@Composable
private fun CatalogSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
    ) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        content()
    }
}
