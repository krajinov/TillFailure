package com.delminiusapps.tillfailure.invitations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.delminiusapps.tillfailure.core.designsystem.*
import kotlin.time.Clock
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState

@Composable
fun InvitationScreen(state: InvitationState, gate: IdentityState, onEvent: (InvitationEvent) -> Unit, onBack: (() -> Unit)? = null) {
    if (state.pending != null) {
        Text("Retry resubmits the original request. Resolve determines its outcome and can prevent an uncommitted request from executing.", color=TillFailureTheme.colors.warning)
        TillFailureSecondaryButton("Resolve original command", { onEvent(InvitationEvent.Resolve) }, enabled=!state.busy && state.resolutionEligibility == "ready")
        if (state.resolutionEligibility != "ready") Text(when(state.resolutionEligibility) {
            "binding-required" -> "Original invitation binding required. Reopen the original link."
            "storage-failure" -> "Protected storage cannot be read safely. Nothing was discarded."
            else -> "Verify the original global identity to resolve."
        }, color=TillFailureTheme.colors.warning)
        state.resolutionPrompt?.let { command ->
            AlertDialog(onDismissRequest={ onEvent(InvitationEvent.CancelResolution(state.resolutionPromptId)) },
                title={ Text("Resolve original command?") },
                text={ Text("If it has not committed, this prevents that command from executing. It does not restore workspace access or undo completed work.") },
                confirmButton={ TillFailureTextButton("Confirm resolve", { onEvent(InvitationEvent.ConfirmResolution(command,state.resolutionPromptId)) },enabled=!state.busy) },
                dismissButton={ TillFailureTextButton("Cancel", { onEvent(InvitationEvent.CancelResolution(state.resolutionPromptId)) }) })
        }
        if (state.pending.action == "accept" && gate.status !in setOf(GateStatus.CleanupRequired, GateStatus.SwitchingOut)) {
            TillFailureTextField(state.entry, { onEvent(InvitationEvent.Entry(it)) }, "Original invitation link",
                supportingText = "Reopen the original link to retry acceptance or supply missing legacy binding.",
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            TillFailureSecondaryButton("Open original invitation", { onEvent(InvitationEvent.Open) }, enabled = !state.busy)
        }
    }
    if (gate.status in setOf(GateStatus.Unconfigured, GateStatus.Loading, GateStatus.CleanupRequired, GateStatus.SwitchingOut,
            GateStatus.IdentityUnsupported, GateStatus.AnonymousSession, GateStatus.AccountDisabled, GateStatus.AccessLost)) {
        if (state.pending != null && gate.status !in setOf(GateStatus.CleanupRequired, GateStatus.SwitchingOut)) {
            Text("An original command remains protected. Recheck access or retry with its original identity.", color = TillFailureTheme.colors.warning)
            TillFailureSecondaryButton("Retry original command", { onEvent(InvitationEvent.RetryCommand) }, enabled = !state.busy && state.pending?.resolutionStarted != true)
        }
        if (state.message.isNotEmpty()) Text(state.message, color = TillFailureTheme.colors.primaryText)
        return
    }
    val trainer = gate.status == GateStatus.TrainerHome && gate.verifiedRole == "trainer"
    Column(verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.md)) {
        Text(if (state.pending != null) "Original command recovery" else if (trainer) "Client invitations" else "Your invitation", style = MaterialTheme.typography.titleLarge,
            color = TillFailureTheme.colors.primaryText)
        if (state.pending != null) {
            Text("Locked command recovery: an original request needs confirmation. Sign-out stays blocked. Resolve can determine the original outcome without workspace access. Identity, binding or storage limitations can still keep work protected.",
                color = TillFailureTheme.colors.warning)
            TillFailurePrimaryButton("Retry same command", { onEvent(InvitationEvent.RetryCommand) }, enabled = !state.busy && state.pending?.resolutionStarted != true)
        }
        if (state.pending != null) {
            if (state.message.isNotEmpty()) Text(state.message, color = TillFailureTheme.colors.primaryText)
            return@Column
        }
        if (trainer) {
            onBack?.let { back -> TillFailureSecondaryButton("Back to Clients", back) }
            Text("Only the workspace owner can manage invitations.", color = TillFailureTheme.colors.secondaryText)
            if (state.trainerPage == TrainerInvitationPage.Form) {
                Text("Invite client", style = MaterialTheme.typography.headlineSmall, color = TillFailureTheme.colors.primaryText)
                TillFailureTextField(state.email, { onEvent(InvitationEvent.Email(it)) }, "Client email",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    enabled = !state.busy && state.pending == null)
                TillFailurePrimaryButton("Create invitation", { onEvent(InvitationEvent.Create) }, enabled = !state.busy && state.pending == null)
            }
            if (state.trainerPage == TrainerInvitationPage.Result) {
                Text("Invitation confirmed", style = MaterialTheme.typography.headlineSmall, color = TillFailureTheme.colors.primaryText)
                Text("No email was sent. View invitations for current status. After acceptance, return to Clients and refresh.", color = TillFailureTheme.colors.secondaryText)
            }
            TillFailureSecondaryButton(if (state.trainerPage == TrainerInvitationPage.List) "Refresh invitations" else "View invitations",
                { onEvent(InvitationEvent.ListInvitations) }, enabled = !state.busy && state.pending == null)
            if (state.trainerPage == TrainerInvitationPage.List) {
                Text("Newest 20 invitation summaries", style = MaterialTheme.typography.headlineSmall, color = TillFailureTheme.colors.primaryText)
                Text("Pending and past invitations. Active clients are shown in Clients.", color = TillFailureTheme.colors.secondaryText)
                TillFailurePrimaryButton("Invite client", { onEvent(InvitationEvent.ShowForm) }, enabled = !state.busy && state.pending == null)
                if (!state.busy && state.listLoaded && state.summaries.isEmpty()) Text("No invitations yet.", color = TillFailureTheme.colors.secondaryText)
                if (state.listFailed) Text("Invitation summaries are unavailable. Refresh while connected.", color = TillFailureTheme.colors.warning)
                for (summary in state.summaries) {
                    TillFailureCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(TillFailureSpacing.lg), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm)) {
                            Text(summary.emailMasked, color = TillFailureTheme.colors.primaryText)
                            Text(invitationStatus(summary, Clock.System.now().toEpochMilliseconds()), color = TillFailureTheme.colors.secondaryText)
                            Text("Expires ${expiry(summary.expiresAtMillis)}", color = TillFailureTheme.colors.secondaryText)
                            if (summary.status == "pending") {
                                TillFailureSecondaryButton("Resend", { onEvent(InvitationEvent.Resend(summary)) }, enabled = !state.busy && state.pending == null)
                                TillFailureTextButton("Revoke", { onEvent(InvitationEvent.Revoke(summary)) }, enabled = !state.busy && state.pending == null)
                            }
                        }
                    }
                }
                onBack?.let { back -> TillFailureSecondaryButton("Recheck active clients", back) }
            }
        } else {
            TillFailureTextField(state.entry, { onEvent(InvitationEvent.Entry(it)) }, "Invitation link",
                supportingText = "Reopen the invitation after restarting the app.", enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            if (state.hasLink) TillFailureTextButton("Use opened invitation", { onEvent(InvitationEvent.PopulateEntry) }, enabled = !state.busy)
            TillFailureSecondaryButton("Open invitation", { onEvent(InvitationEvent.Open) }, enabled = !state.busy)
            if (state.hasLink) {
                Text("Invitation held for sign-in and email verification.", color = TillFailureTheme.colors.secondaryText)
                TillFailureSecondaryButton("Preview invitation", { onEvent(InvitationEvent.Preview) }, enabled = !state.busy && state.pending == null)
            }
            state.workspaceName?.let {
                TillFailureCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(TillFailureSpacing.lg), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.md)) {
                        Text("Join $it", style = MaterialTheme.typography.titleLarge, color = TillFailureTheme.colors.primaryText)
                        state.expiresAtMillis?.let { time -> Text("Expires ${expiry(time)}", color = TillFailureTheme.colors.warning) }
                        Text("Your account's verified email must match the invitation. Membership is checked online after acceptance.",
                            color = TillFailureTheme.colors.secondaryText)
                        TillFailurePrimaryButton("Accept invitation", { onEvent(InvitationEvent.Accept) }, enabled = !state.busy && state.pending == null)
                    }
                }
            }
        }
        if (state.message.isNotEmpty()) Text(state.message, color = TillFailureTheme.colors.primaryText)
        state.deliveryLink?.let {
            Text("Local test delivery only. No email was sent. Open this invitation before safely changing to the invited account.", color = TillFailureTheme.colors.secondaryText)
            TillFailureSecondaryButton("Open local test invitation", { onEvent(InvitationEvent.UseDelivery) }, enabled = !state.busy)
            TillFailureTextButton("Dismiss local link", { onEvent(InvitationEvent.DismissDelivery) })
        }
        Text("Development emulator invitations only", color = TillFailureTheme.colors.secondaryText)
    }
    state.confirmation?.let { command ->
        TillFailureConfirmationDialog(
            title = when (command.action) { "accept" -> "Accept invitation?"; "resend" -> "Replace invitation link?"; else -> "Revoke invitation?" },
            message = when (command.action) {
                "accept" -> "Join this trainer's workspace with your current verified account."
                "resend" -> "The old link will stop working immediately. A new link will be returned for local testing."
                else -> "This invitation will stop working immediately."
            }, confirmLabel = "Confirm", onConfirm = { onEvent(InvitationEvent.Confirm) }, onDismiss = { onEvent(InvitationEvent.Cancel) },
        )
    }
}

private fun expiry(millis: Long): String = kotlin.time.Instant.fromEpochMilliseconds(millis).toString().replace('T', ' ').replace("Z", " UTC")

internal fun invitationStatus(summary: InvitationSummary, nowMillis: Long): String = when (summary.status) {
    "accepted" -> "Accepted · recheck active clients"
    "revoked" -> "Revoked invitation"
    else -> if (summary.expiresAtMillis <= nowMillis) "Expired invitation" else "Pending acceptance"
}
