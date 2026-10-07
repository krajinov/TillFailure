package com.delminiusapps.tillfailure.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.coroutines.flow.Flow
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme

@Serializable
private sealed interface IdentityDestination : NavKey

@Serializable
private data object IdentityGateDestination : IdentityDestination

@Serializable
private data class IdentityStateDestination(val status: GateStatus) : IdentityDestination

private val identitySavedState = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(IdentityGateDestination::class, IdentityGateDestination.serializer())
            subclass(IdentityStateDestination::class, IdentityStateDestination.serializer())
        }
    }
}

@Composable
fun IdentityRoot(client: ProductIdentityClient?, onOpenDevelopmentCatalog: (() -> Unit)? = null) {
    if (client == null) {
        IdentityScreen(IdentityState(status = GateStatus.Unconfigured), {}, onOpenDevelopmentCatalog)
        return
    }
    val backStack = rememberNavBackStack(identitySavedState, IdentityGateDestination)
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<IdentityGateDestination> {
                val viewModel: IdentityViewModel = koinViewModel(parameters = { parametersOf(client) })
                val state by viewModel.state.collectAsStateWithLifecycle()
                IdentityStateNavigation(state, viewModel.effect, viewModel::onEvent, onOpenDevelopmentCatalog)
            }
        },
    )
}

/** Replaces the entire gate destination when authority changes; the owning ViewModel stays scoped
 * to the stable parent entry, while every child screen remains stateless. */
@Composable
private fun IdentityStateNavigation(
    state: IdentityState,
    effect: Flow<IdentityEffect>,
    onEvent: (IdentityEvent) -> Unit,
    onOpenDevelopmentCatalog: (() -> Unit)?,
) {
    val backStack = rememberNavBackStack(identitySavedState, IdentityStateDestination(GateStatus.Loading))
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(effect, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effect.collect { emitted ->
                when (emitted) {
                    is IdentityEffect.ReplaceRoot -> {
                        val destination = IdentityStateDestination(emitted.status)
                        if (backStack.lastOrNull() != destination) {
                            backStack.clear()
                            backStack.add(destination)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(state.status) {
        val destination = IdentityStateDestination(state.status)
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }
    NavDisplay(
        backStack = backStack,
        entryProvider = entryProvider {
            entry<IdentityStateDestination> { IdentityScreen(state, onEvent, onOpenDevelopmentCatalog) }
        },
    )
}

@Composable
fun IdentityScreen(
    state: IdentityState,
    onEvent: (IdentityEvent) -> Unit,
    onOpenDevelopmentCatalog: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(TillFailureTheme.colors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(24.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("TillFailure", style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText)
        Text(
            when (state.status) {
                GateStatus.Loading -> "Verifying your account online…"
                GateStatus.SignedOut -> "Sign in to continue."
                GateStatus.InvalidCredentials -> "Email or password is incorrect."
                GateStatus.IdentityUnsupported -> if (state.canSignOut)
                    "This account ID cannot access workspaces. You can safely sign out."
                    else "This account ID cannot access workspaces. Safe sign-out is unavailable; contact support."
                GateStatus.AnonymousSession -> if (state.canSignOut)
                    "This anonymous session cannot access workspaces. You can safely sign out."
                    else "This anonymous session cannot access workspaces. Safe sign-out is unavailable; contact support."
                GateStatus.NoAccount -> "Your sign-in is valid, but your account is not ready."
                GateStatus.AccountDisabled -> "This account is disabled. Contact support."
                GateStatus.WorkspaceGate -> "No active workspace membership was found. Ask your trainer or operator for access."
                GateStatus.MembershipVerifiedFeaturePending -> "Membership verified. This feature is coming soon."
                GateStatus.UnsupportedSchema -> "Your account data needs an update. Contact support."
                GateStatus.AccessLost -> "Access could not be verified. Contact support or retry."
                GateStatus.ConnectToVerify -> "Connect to verify your account."
                GateStatus.Retry -> "Verification timed out. Retry while connected."
                GateStatus.MultipleMemberships -> "Multiple memberships found. Workspace selection is coming soon."
                GateStatus.Unconfigured -> "Sign-in is not configured in this build."
                GateStatus.SessionExpired -> "Your session expired. Account recovery is required before another sign-in."
                GateStatus.CleanupRequired -> "This installation needs account cleanup before another sign-in. Contact support."
                GateStatus.SwitchingOut -> "Safely signing out…"
                GateStatus.DepartureBlocked -> "Sign-out is blocked by pending work or an unavailable cleanup check. Retry while connected."
            },
            color = TillFailureTheme.colors.primaryText,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.status == GateStatus.Loading) CircularProgressIndicator()
        if (state.status == GateStatus.SignedOut || state.status == GateStatus.InvalidCredentials) {
            OutlinedTextField(
                value = state.email,
                onValueChange = { onEvent(IdentityEvent.EmailChanged(it)) },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = { onEvent(IdentityEvent.PasswordChanged(it)) },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = { onEvent(IdentityEvent.SignIn) }, enabled = !state.busy) { Text("Sign in") }
            Text("Development emulator sign-in only", color = TillFailureTheme.colors.secondaryText)
        } else if (state.status != GateStatus.Loading && state.status != GateStatus.IdentityUnsupported &&
            state.status != GateStatus.AnonymousSession &&
            state.status != GateStatus.MembershipVerifiedFeaturePending && state.status != GateStatus.ConnectToVerify &&
            state.status != GateStatus.Unconfigured && state.status != GateStatus.SessionExpired &&
            state.status != GateStatus.CleanupRequired
        ) {
            Button(onClick = { onEvent(IdentityEvent.Retry) }, enabled = !state.busy) { Text("Recheck access") }
        } else if (state.status == GateStatus.ConnectToVerify) {
            Button(onClick = { onEvent(IdentityEvent.Retry) }) { Text("Retry") }
        }
        if (state.status == GateStatus.CleanupRequired) {
            Button(onClick = { onEvent(IdentityEvent.RetryCleanup) }, enabled = !state.busy) { Text("Retry cleanup") }
        }
        if (state.canSignOut) {
            Button(onClick = { onEvent(IdentityEvent.SignOut) }, enabled = !state.busy) { Text("Sign out") }
        }
        if (onOpenDevelopmentCatalog != null) {
            Button(onClick = onOpenDevelopmentCatalog) { Text("Open development UI catalog") }
            Text("Debug visual fixtures only; this does not verify an account or role.",
                color = TillFailureTheme.colors.secondaryText)
        }
    }
}
