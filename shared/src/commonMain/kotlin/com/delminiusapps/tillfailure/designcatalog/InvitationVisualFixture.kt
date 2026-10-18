package com.delminiusapps.tillfailure.designcatalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityScreen
import com.delminiusapps.tillfailure.identity.IdentityState
import com.delminiusapps.tillfailure.invitations.InvitationRequest
import com.delminiusapps.tillfailure.invitations.InvitationScreen
import com.delminiusapps.tillfailure.invitations.InvitationState
import com.delminiusapps.tillfailure.invitations.InvitationSummary

/** Stateless screenshot fixtures; no identity client, token, Auth, or repository is injected. */
@Composable
fun InvitationVisualFixture(stage: String, fontScale: Float = 1f) {
    val trainer = stage.startsWith("trainer")
    val gate = IdentityState(status = if (trainer) GateStatus.TrainerHome else GateStatus.WorkspaceGate,
        verifiedRole = if (trainer) "trainer" else null)
    val fixture = InvitationState(
        email = if (trainer) "client@example.invalid" else "", hasLink = !trainer,
        workspaceName = if (!trainer) "Forge Personal Training" else null,
        expiresAtMillis = 1792062000000,
        summaries = if (trainer) listOf(InvitationSummary("visual", "c***@example.invalid", "client", "pending",
            1792062000000, 1791889200000, 1791889200000, 2)) else emptyList(),
        confirmation = if (stage.endsWith("confirm")) InvitationRequest(if (trainer) "resend" else "accept") else null,
        message = "Visual fixture only; no account access or invitations are created.",
    )
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.coerceIn(1f, 2f))) {
        TillFailureTheme { IdentityScreen(gate, {}, invitations = { _, _ -> InvitationScreen(fixture, gate, {}) }) }
    }
}
