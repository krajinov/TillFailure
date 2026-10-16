package com.delminiusapps.tillfailure.invitations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.delminiusapps.tillfailure.core.designsystem.*
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState

@Composable
fun InvitationScreen(state: InvitationState, gate: IdentityState, onEvent: (InvitationEvent) -> Unit) {
    if (gate.status in setOf(GateStatus.Unconfigured, GateStatus.Loading, GateStatus.CleanupRequired, GateStatus.SwitchingOut,
            GateStatus.IdentityUnsupported, GateStatus.AnonymousSession, GateStatus.AccountDisabled, GateStatus.AccessLost)) {
        if (state.pending != null && gate.status !in setOf(GateStatus.CleanupRequired, GateStatus.SwitchingOut)) {
            Text("An invitation command remains protected. Recheck access or retry its original identity.", color = TillFailureTheme.colors.warning)
            TillFailureSecondaryButton("Retry invitation recovery", { onEvent(InvitationEvent.RetryCommand) }, enabled = !state.busy)
        }
        return
    }
    val trainer = gate.status == GateStatus.TrainerHome && gate.verifiedRole == "trainer"
    Column(verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.md)) {
        Text(if (trainer) "Client invitations" else "Your invitation", style = MaterialTheme.typography.titleLarge,
            color = TillFailureTheme.colors.primaryText)
        if (state.pending != null) {
            Text("A ${state.pending.action} command needs confirmation. Sign-out is blocked until it is resolved.",
                color = TillFailureTheme.colors.warning)
            TillFailurePrimaryButton("Retry same command", { onEvent(InvitationEvent.RetryCommand) }, enabled = !state.busy)
        }
        if (trainer) {
            Text("Send a personal invitation.", style = MaterialTheme.typography.headlineSmall, color = TillFailureTheme.colors.primaryText)
            TillFailureTextField(state.email, { onEvent(InvitationEvent.Email(it)) }, "Client email",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                enabled = !state.busy && state.pending == null)
            TillFailurePrimaryButton("Create invitation", { onEvent(InvitationEvent.Create) }, enabled = !state.busy && state.pending == null)
            TillFailureSecondaryButton("Refresh invitations", { onEvent(InvitationEvent.ListInvitations) }, enabled = !state.busy && state.pending == null)
            for (summary in state.summaries) {
                TillFailureCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(TillFailureSpacing.lg), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm)) {
                        Text(summary.emailMasked, color = TillFailureTheme.colors.primaryText)
                        Text("${summary.status} · version ${summary.tokenVersion}", color = TillFailureTheme.colors.secondaryText)
                        Text("Expires ${expiry(summary.expiresAtMillis)}", color = TillFailureTheme.colors.secondaryText)
                        if (summary.status == "pending") {
                            TillFailureSecondaryButton("Resend", { onEvent(InvitationEvent.Resend(summary)) }, enabled = !state.busy && state.pending == null)
                            TillFailureTextButton("Revoke", { onEvent(InvitationEvent.Revoke(summary)) }, enabled = !state.busy && state.pending == null)
                        }
                    }
                }
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
            TillFailureSecondaryButton("Open local test invitation", { onEvent(InvitationEvent.UseDelivery) })
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
