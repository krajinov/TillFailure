package com.delminiusapps.tillfailure.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.delminiusapps.tillfailure.core.designsystem.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.*

@Serializable private data class ShellDestination(val page: ShellPage) : NavKey
@Serializable private enum class ShellPage { Home, Profile, Invitations }
private val shellSavedState = SavedStateConfiguration {
    serializersModule = SerializersModule { polymorphic(NavKey::class) { subclass(ShellDestination::class, ShellDestination.serializer()) } }
}

/** No catalog fixtures or future tabs. The caller supplies a current, server-verified root. */
@Composable
internal fun RoleShell(state: IdentityState, onEvent: (IdentityEvent) -> Unit,
    invitations: (@Composable () -> Unit)?, onboardingRecovery: (@Composable () -> Unit)?) {
    val stack = rememberNavBackStack(shellSavedState, ShellDestination(ShellPage.Home))
    val selected = (stack.lastOrNull() as? ShellDestination)?.page ?: ShellPage.Home
    fun open(page: ShellPage) {
        stack.clear(); stack.add(ShellDestination(ShellPage.Home))
        if (page != ShellPage.Home) stack.add(ShellDestination(page))
    }
    Column(Modifier.fillMaxSize().background(TillFailureTheme.colors.background).safeDrawingPadding().imePadding()) {
        NavDisplay(backStack = stack, modifier = Modifier.weight(1f), entryProvider = entryProvider {
            entry<ShellDestination> { destination ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TillFailureSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.lg)) {
                    Text(if (state.verifiedRole == "trainer") "Trainer workspace" else "Your workspace",
                        style = MaterialTheme.typography.labelLarge, color = TillFailureTheme.colors.secondaryText)
                    when (destination.page) {
                        ShellPage.Home -> {
                            Text(if (state.verifiedRole == "trainer") "Trainer home" else "You're ready${state.displayName.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}.",
                                style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText)
                            TillFailureCard {
                                Text(if (state.verifiedRole == "trainer") "Manage your client invitations. Programming and scheduling are planned for a later update."
                                    else "Onboarding complete. Workouts, scheduling, progress and messages are planned for later updates.",
                                    Modifier.padding(TillFailureSpacing.lg), color = TillFailureTheme.colors.secondaryText)
                            }
                            if (state.verifiedRole == "trainer") TillFailurePrimaryButton("Client invitations", { open(ShellPage.Invitations) })
                            TillFailureSecondaryButton("Profile and access", { open(ShellPage.Profile) })
                            onboardingRecovery?.invoke()
                        }
                        ShellPage.Profile -> {
                            Text("Profile and access", style = MaterialTheme.typography.headlineMedium, color = TillFailureTheme.colors.primaryText)
                            Text("Workspace: ${state.verifiedWorkspaceId}\nRole: ${state.verifiedRole}", color = TillFailureTheme.colors.secondaryText)
                            if (state.verifiedRole == "client") Text("${state.displayName}\n${state.locale}\n${state.timeZoneId}", color = TillFailureTheme.colors.primaryText)
                            TillFailureSecondaryButton("Recheck access", { onEvent(IdentityEvent.Retry) }, enabled = !state.busy)
                            TillFailureSecondaryButton("Choose workspace", { onEvent(IdentityEvent.ChangeWorkspace) }, enabled = !state.busy)
                            if (state.canSignOut) TillFailureSecondaryButton("Sign out", { onEvent(IdentityEvent.SignOut) }, enabled = !state.busy)
                            TillFailureSecondaryButton("Invitations and recovery", { open(ShellPage.Invitations) })
                            onboardingRecovery?.invoke()
                        }
                        ShellPage.Invitations -> { invitations?.invoke(); onboardingRecovery?.invoke() }
                    }
                }
            }
        })
        TillFailureBottomNavigation(listOf(TillFailureNavigationItem("Home", "⌂", selected == ShellPage.Home),
            TillFailureNavigationItem("Profile", "○", selected == ShellPage.Profile)),
            { open(if (it == 0) ShellPage.Home else ShellPage.Profile) })
    }
}
