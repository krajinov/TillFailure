package com.delminiusapps.tillfailure.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.delminiusapps.tillfailure.core.designsystem.*
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.InvitationClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun OnboardingRoute(gate: IdentityState, client: InvitationClient, onRecheck: () -> Unit) {
    val model: OnboardingViewModel = koinViewModel(parameters = { parametersOf(client) })
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(gate) { model.updateIdentity(gate) }
    LaunchedEffect(model, gate.accountUid) { model.effect.collect { if (it == gate.accountUid) onRecheck() } }
    // Nav restoration/composition can precede updateIdentity; never flash another scope's draft.
    if (state.uid != gate.accountUid || state.workspaceId != gate.verifiedWorkspaceId || state.authorityEpoch != gate.authorityEpoch) return
    OnboardingScreen(state, gate, model::onEvent)
}

@Composable
fun OnboardingScreen(state: OnboardingState, gate: IdentityState, onEvent: (OnboardingEvent) -> Unit) {
    if (gate.status in setOf(GateStatus.CleanupRequired, GateStatus.SwitchingOut)) return
    if (state.pending != null) {
        Text("Locked command recovery: your completion is awaiting confirmation. The original command is protected; sign-out stays blocked. If retries cannot resolve it, an approved reconciliation policy is required. Discard and automatic repair are unavailable.", color = TillFailureTheme.colors.warning)
        TillFailurePrimaryButton("Retry original completion", { onEvent(OnboardingEvent.Retry) }, enabled = !state.busy, loading = state.busy)
    } else if (state.completed) {
        Text("You’re ready. Verifying your completed profile…", color = TillFailureTheme.colors.success)
    } else if (gate.status == GateStatus.Onboarding) {
        Text("First, the basics.", style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText)
        Text("Set your display name and regional preferences to finish onboarding.", color = TillFailureTheme.colors.secondaryText)
        TillFailureTextField(state.name, { onEvent(OnboardingEvent.Name(it)) }, "Display name", enabled = !state.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        TillFailureTextField(state.locale, { onEvent(OnboardingEvent.Locale(it)) }, "Locale", supportingText = "For example: en or bs-BA", enabled = !state.busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        TillFailureTextField(state.zone, { onEvent(OnboardingEvent.Zone(it)) }, "Time zone", supportingText = "For example: Europe/Sarajevo or UTC", enabled = !state.busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done))
        Row(Modifier.fillMaxWidth()) {
            Checkbox(state.acknowledged, { onEvent(OnboardingEvent.Acknowledge(it)) }, enabled = !state.busy,
                modifier = Modifier.semantics { contentDescription = "Confirm onboarding details" })
            Text("I confirm these details and want to complete onboarding.", Modifier.weight(1f), color = TillFailureTheme.colors.primaryText)
        }
        TillFailurePrimaryButton("Complete onboarding", { onEvent(OnboardingEvent.Complete) }, enabled = !state.busy, loading = state.busy)
        Text("Development emulator fixture only", color = TillFailureTheme.colors.secondaryText)
    }
    if (state.message.isNotEmpty()) Text(state.message, color = TillFailureTheme.colors.primaryText)
}
