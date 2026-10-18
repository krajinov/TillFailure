package com.delminiusapps.tillfailure.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import com.delminiusapps.tillfailure.invitations.InvitationEntry
import com.delminiusapps.tillfailure.invitations.TrainerInvitationPresentation
import com.delminiusapps.tillfailure.clients.ClientRepository
import com.delminiusapps.tillfailure.clients.ClientsRoute
import com.delminiusapps.tillfailure.clients.ClientScope
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.delminiusapps.tillfailure.core.designsystem.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.*

@Serializable internal data class ShellOwner(val uid: String?, val workspace: String?, val trainer: String?, val role: String?, val epoch: Long)
@Serializable internal data class ShellDestination(val page: ShellPage, val clientId: String? = null, val owner: ShellOwner? = null,
    val invitationEntry: InvitationEntry? = null, val refreshVersion: Long = 0) : NavKey
@Serializable internal enum class ShellPage { Home, Profile, Invitations, Clients, ClientDetails }
private val shellSavedState = SavedStateConfiguration {
    serializersModule = SerializersModule { polymorphic(NavKey::class) { subclass(ShellDestination::class, ShellDestination.serializer()) } }
}

/** No catalog fixtures or future tabs. The caller supplies a current, server-verified root. */
@Composable
internal fun RoleShell(state: IdentityState, onEvent: (IdentityEvent) -> Unit,
    invitations: (@Composable (TrainerInvitationPresentation?, (() -> Unit)?) -> Unit)?, onboardingRecovery: (@Composable () -> Unit)?, clients: ClientRepository?) {
    val owner = ShellOwner(state.accountUid, state.verifiedWorkspaceId, state.accountUid.takeIf { state.verifiedRole == "trainer" }, state.verifiedRole, state.authorityEpoch)
    val stack = rememberNavBackStack(shellSavedState, ShellDestination(ShellPage.Home, owner = owner))
    LaunchedEffect(owner, stack.lastOrNull()) {
        if ((stack.lastOrNull() as? ShellDestination)?.owner != owner) {
            stack.clear(); stack.add(ShellDestination(ShellPage.Home, owner = owner))
        }
    }
    val selectedDestination = stack.lastOrNull() as? ShellDestination
    val selected = selectedDestination?.page ?: ShellPage.Home
    fun open(page: ShellPage, source: ShellDestination? = selectedDestination) {
        openShellPage(stack, owner, page, source)
    }
    Column(Modifier.fillMaxSize().background(TillFailureTheme.colors.background).safeDrawingPadding().imePadding()) {
        NavDisplay(backStack = stack, onBack = {
            if (stack.lastOrNull() == selectedDestination) {
                if (selectedDestination?.page == ShellPage.Invitations) backFromInvitations(stack, selectedDestination)
                else if (stack.size > 1) stack.removeLastOrNull()
            }
        }, modifier = Modifier.weight(1f), entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()), entryProvider = entryProvider {
            entry<ShellDestination> { destination ->
                if (destination.owner != owner) return@entry
                if (destination.page in setOf(ShellPage.Clients, ShellPage.ClientDetails)) {
                    if (state.verifiedRole == "trainer" && clients != null && state.accountUid != null && state.verifiedWorkspaceId != null) {
                        ClientsRoute(clients,
                            ClientScope(state.accountUid, state.verifiedWorkspaceId, state.accountUid, state.authorityEpoch),
                            destination.clientId, { if (stack.lastOrNull() == destination) stack.add(ShellDestination(ShellPage.ClientDetails, it, owner, refreshVersion = destination.refreshVersion)) },
                            { if (stack.lastOrNull() == destination && stack.size > 1) stack.removeLastOrNull() }, { onEvent(IdentityEvent.Retry) },
                            { compose -> openClientInvitations(stack, owner, if (compose) InvitationEntry.Invite else InvitationEntry.Manage, destination) })
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TillFailureSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.lg)) {
                    Text(if (state.verifiedRole == "trainer") "Trainer workspace" else "Your workspace",
                        style = MaterialTheme.typography.labelLarge, color = TillFailureTheme.colors.secondaryText)
                    when (destination.page) {
                        ShellPage.Home -> {
                            Text(if (state.verifiedRole == "trainer") "Trainer home" else "You're ready${state.displayName.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}.",
                                style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText)
                            TillFailureCard {
                                Text(if (state.verifiedRole == "trainer") "View assigned clients and manage invitations. Programming and scheduling are planned for a later update."
                                    else "Onboarding complete. Workouts, scheduling, progress and messages are planned for later updates.",
                                    Modifier.padding(TillFailureSpacing.lg), color = TillFailureTheme.colors.secondaryText)
                            }
                            if (state.verifiedRole == "trainer") TillFailurePrimaryButton("Clients", { open(ShellPage.Clients, destination) })
                            if (state.verifiedRole == "trainer") TillFailurePrimaryButton("Client invitations", { open(ShellPage.Invitations, destination) })
                            TillFailureSecondaryButton("Profile and access", { open(ShellPage.Profile, destination) })
                            onboardingRecovery?.invoke()
                        }
                        ShellPage.Profile -> {
                            Text("Profile and access", style = MaterialTheme.typography.headlineMedium, color = TillFailureTheme.colors.primaryText)
                            Text("Workspace: ${state.verifiedWorkspaceId}\nRole: ${state.verifiedRole}", color = TillFailureTheme.colors.secondaryText)
                            if (state.verifiedRole == "client") Text("${state.displayName}\n${state.locale}\n${state.timeZoneId}", color = TillFailureTheme.colors.primaryText)
                            TillFailureSecondaryButton("Recheck access", { onEvent(IdentityEvent.Retry) }, enabled = !state.busy)
                            TillFailureSecondaryButton("Choose workspace", { onEvent(IdentityEvent.ChangeWorkspace) }, enabled = !state.busy)
                            if (state.canSignOut) TillFailureSecondaryButton("Sign out", { onEvent(IdentityEvent.SignOut) }, enabled = !state.busy)
                            TillFailureSecondaryButton("Invitations and recovery", { open(ShellPage.Invitations, destination) })
                            onboardingRecovery?.invoke()
                        }
                        ShellPage.Clients, ShellPage.ClientDetails -> Unit
                        ShellPage.Invitations -> {
                            invitations?.invoke(destination.invitationEntry?.let { TrainerInvitationPresentation(it, destination.refreshVersion) }) { backFromInvitations(stack, destination) }
                            onboardingRecovery?.invoke()
                        }
                    }
                }
            }
        })
        val pages = if (state.verifiedRole == "trainer") listOf(ShellPage.Home, ShellPage.Clients, ShellPage.Profile) else listOf(ShellPage.Home, ShellPage.Profile)
        TillFailureBottomNavigation(pages.map { page -> TillFailureNavigationItem(page.name, if (page == ShellPage.Home) "⌂" else "○", selected == page || page == ShellPage.Clients && selected in setOf(ShellPage.ClientDetails, ShellPage.Invitations)) }, { open(pages[it]) })
    }
}

/** One owned route; navigation results contain no invitation reply, email or bearer. */
internal fun openShellPage(stack: MutableList<NavKey>, owner: ShellOwner, page: ShellPage, source: ShellDestination?) {
    if (source == null || stack.lastOrNull() != source || source.owner != owner) return
    if (page == ShellPage.Invitations && owner.role == "trainer") {
        openClientInvitations(stack, owner, InvitationEntry.Manage, source); return
    }
    if (page == ShellPage.Clients && source.page == ShellPage.Invitations) {
        backFromInvitations(stack, source); return
    }
    if (page == source.page) return
    val version = stack.filterIsInstance<ShellDestination>().maxOfOrNull { it.refreshVersion }?.plus(1) ?: 1
    stack.clear(); stack.add(ShellDestination(ShellPage.Home, owner = owner, refreshVersion = version))
    if (page != ShellPage.Home) stack.add(ShellDestination(page, owner = owner, refreshVersion = version))
}

internal fun openClientInvitations(stack: MutableList<NavKey>, owner: ShellOwner, entry: InvitationEntry,
    source: ShellDestination? = stack.lastOrNull() as? ShellDestination) {
    val current = stack.lastOrNull() as? ShellDestination ?: return
    if (current != source || owner.role != "trainer" || current.owner != owner || current.page == ShellPage.Invitations) return
    val version = stack.filterIsInstance<ShellDestination>().maxOfOrNull { it.refreshVersion }?.plus(1) ?: 1
    stack.clear()
    stack.add(ShellDestination(ShellPage.Home, owner = owner, refreshVersion = version))
    stack.add(ShellDestination(ShellPage.Clients, owner = owner, refreshVersion = version))
    stack.add(ShellDestination(ShellPage.Invitations, owner = owner, invitationEntry = entry, refreshVersion = version))
}

internal fun backFromInvitations(stack: MutableList<NavKey>, destination: ShellDestination) {
    if (stack.lastOrNull() != destination || destination.page != ShellPage.Invitations || stack.size <= 1) return
    stack.removeLastOrNull()
    val clients = stack.lastOrNull() as? ShellDestination
    if (clients?.page == ShellPage.Clients && clients.owner == destination.owner) {
        // A new destination store forces a server read even during a rapid interrupted transition.
        stack[stack.lastIndex] = clients.copy(refreshVersion = clients.refreshVersion + 1)
    }
}
