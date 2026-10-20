package com.delminiusapps.tillfailure.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import com.delminiusapps.tillfailure.core.designsystem.*

@Composable
fun ProfileScreen(state: ProfileState, onEvent: (ProfileEvent) -> Unit, onRecheck: () -> Unit, recoveryOnly: Boolean = false) {
    if (recoveryOnly && state.pending?.action != "update-profile") return
    Column(verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.md)) {
        if (state.pending?.action == "update-profile") {
            Text("A basic-profile command remains protected. Its original fields, revision and key will be retried. Sign-out and account changes stay blocked.", color = TillFailureTheme.colors.warning)
            TillFailurePrimaryButton("Retry original profile save", { onEvent(ProfileEvent.RetryOriginal) }, enabled = !state.busy, loading = state.busy)
        }
        if (recoveryOnly) return@Column
        Text("Edit basic profile", style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText)
        Text("Your display name and regional preferences apply across your account’s workspaces. Your currently assigned trainers can see your display name.", color = TillFailureTheme.colors.secondaryText)
        val enabled = !state.busy && state.pending == null && !state.storageLocked && !state.accessLost && !state.unavailable && !state.conflict && state.baseline != null
        TillFailureTextField(state.name, { onEvent(ProfileEvent.Name(it)) }, "Display name", enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        TillFailureTextField(state.locale, { onEvent(ProfileEvent.Locale(it)) }, "Locale", supportingText = "For example: bs-BA or en", enabled = enabled,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        TillFailureTextField(state.zone, { onEvent(ProfileEvent.Zone(it)) }, "Time zone", supportingText = "For example: Europe/Sarajevo or UTC", enabled = enabled,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done))
        TillFailurePrimaryButton("Save basic profile", { onEvent(ProfileEvent.Save) }, enabled = enabled && state.dirty, loading = state.busy)
        if (state.conflict || state.unavailable || state.baseline == null)
            TillFailureSecondaryButton(if (state.conflict) "Reload current profile" else "Retry loading profile", { onEvent(ProfileEvent.Reload) }, enabled = !state.busy && state.pending == null && !state.storageLocked)
        if (state.storageLocked) Text("Protected recovery is unavailable. Nothing was discarded.", color = TillFailureTheme.colors.warning)
        if (state.accessLost) TillFailureSecondaryButton("Recheck access", onRecheck, enabled = !state.busy)
        if (state.message.isNotBlank()) Text(state.message, color = TillFailureTheme.colors.primaryText)
        TillFailureSecondaryButton("Back to Profile", { onEvent(ProfileEvent.Leave(ProfileExit.Back)) }, enabled = !state.busy)
        state.prompt?.let { prompt ->
            TillFailureConfirmationDialog(title = if (prompt == ProfilePrompt.Reload) "Reload and replace your draft?" else "Leave unsaved changes?",
                message = if (prompt == ProfilePrompt.Reload) "Your modified draft will be replaced only after the current profile loads successfully." else "Your unsent draft will be discarded. Submitted commands remain protected.",
                confirmLabel = if (prompt == ProfilePrompt.Reload) "Reload" else "Leave",
                onConfirm = { onEvent(ProfileEvent.Confirm) }, onDismiss = { onEvent(ProfileEvent.Cancel) })
        }
    }
}
