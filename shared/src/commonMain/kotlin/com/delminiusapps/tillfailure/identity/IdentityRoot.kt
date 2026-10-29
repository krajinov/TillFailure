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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.LifecycleEventObserver
import com.delminiusapps.tillfailure.clients.ClientRepository
import com.delminiusapps.tillfailure.onboarding.OnboardingRoute
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import com.delminiusapps.tillfailure.invitations.InvitationViewModel
import com.delminiusapps.tillfailure.invitations.InvitationScreen
import com.delminiusapps.tillfailure.invitations.InvitationEffect
import com.delminiusapps.tillfailure.invitations.TrainerInvitationPresentation
import com.delminiusapps.tillfailure.invitations.InvitationState
import com.delminiusapps.tillfailure.profile.ProfileViewModel
import com.delminiusapps.tillfailure.profile.ProfileScope
import com.delminiusapps.tillfailure.profile.ProfileScreen

@Serializable
private sealed interface IdentityDestination : NavKey

@Serializable
private data object IdentityGateDestination : IdentityDestination

@Serializable
private data class IdentityStateDestination(val status: GateStatus, val epoch: Long = 0) : IdentityDestination

private val identitySavedState = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(IdentityGateDestination::class, IdentityGateDestination.serializer())
            subclass(IdentityStateDestination::class, IdentityStateDestination.serializer())
        }
    }
}

internal fun canOpenDevelopmentCatalog(state: IdentityState): Boolean =
    !state.busy && state.status !in setOf(GateStatus.Loading, GateStatus.SwitchingOut, GateStatus.CleanupRequired)

@Composable
fun IdentityRoot(client: ProductIdentityClient?, onOpenDevelopmentCatalog: (() -> Unit)? = null) {
    if (client == null) {
        IdentityScreen(IdentityState(status = GateStatus.Unconfigured), {}, onOpenDevelopmentCatalog)
        return
    }
    val backStack = rememberNavBackStack(identitySavedState, IdentityGateDestination)
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator(), rememberProductViewModelStoreDecorator()),
        entryProvider = entryProvider {
            entry<IdentityGateDestination> {
                val viewModel: IdentityViewModel = koinViewModel(parameters = { parametersOf(client) })
                val state by viewModel.state.collectAsStateWithLifecycle()
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(viewModel, lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) viewModel.onEvent(IdentityEvent.Suspend)
                        if (event == Lifecycle.Event.ON_START) viewModel.onEvent(IdentityEvent.Foreground)
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val invitationClient = viewModel.activeClient().invitations
                if (invitationClient == null) {
                    IdentityStateNavigation(state, viewModel.effect, viewModel::onEvent, onOpenDevelopmentCatalog, drafts = viewModel.activeClient().drafts, customExercises = viewModel.activeClient().customExercises, exercises = viewModel.activeClient().exercises, clients = viewModel.activeClient().clients)
                } else {
                    val invitations: InvitationViewModel = koinViewModel(parameters = { parametersOf(invitationClient) })
                    val profile: ProfileViewModel = koinViewModel(parameters = { parametersOf(invitationClient) })
                    LaunchedEffect(profile, invitationClient, state) { profile.update(invitationClient, state) }
                    DisposableEffect(profile, lifecycle) {
                        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) profile.suspendPresentation() }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer); profile.suspendPresentation() }
                    }
                    val invitationState by invitations.state.collectAsStateWithLifecycle()
                    // Identity effects run after composition. Never render an old UID's state in
                    // that intervening frame, including its transient local-delivery token.
                    val visibleInvitationState = if (invitationState.accountUid == state.accountUid && invitationState.workspaceId == state.verifiedWorkspaceId &&
                        invitationState.authorityEpoch == state.authorityEpoch)
                        invitationState else InvitationState(hasLink = invitationClient.memory.linkFor(state.accountUid) != null)
                    LaunchedEffect(state, invitationClient, invitationClient.memory.revision) {
                        invitations.updateClient(invitationClient)
                        invitations.updateIdentity(state)
                    }
                    val owner = LocalLifecycleOwner.current
                    LaunchedEffect(invitations, owner) {
                        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            invitations.effect.collect { effect ->
                                if (effect is InvitationEffect.RecheckMembership && viewModel.currentState().accountUid == effect.uid)
                                    viewModel.onEvent(IdentityEvent.Retry)
                            }
                        }
                    }
                    IdentityStateNavigation(state.copy(busy = state.busy || visibleInvitationState.busy),
                        viewModel.effect, viewModel::onEvent, onOpenDevelopmentCatalog, onboardingClient = invitationClient, drafts = viewModel.activeClient().drafts, customExercises = viewModel.activeClient().customExercises, exercises = viewModel.activeClient().exercises, clients = viewModel.activeClient().clients, profile = profile) { presentation, back ->
                        DisposableEffect(presentation, state.accountUid, state.verifiedWorkspaceId, state.authorityEpoch, state.status, invitationClient) {
                            if (presentation != null && state.status == GateStatus.TrainerHome) {
                                invitations.updateClient(invitationClient); invitations.updateIdentity(state)
                                invitations.attachTrainer(presentation)
                            }
                            onDispose { if (presentation != null) invitations.detachTrainer(state.accountUid, state.verifiedWorkspaceId, state.authorityEpoch, presentation.visit) }
                        }
                        val presentationState = if (presentation != null && visibleInvitationState.trainerVisit != presentation.visit)
                            InvitationState() else visibleInvitationState
                        InvitationScreen(presentationState, state, { event ->
                            invitations.onPresentationEvent(state.accountUid, state.verifiedWorkspaceId, state.authorityEpoch,
                                presentationState.confirmation?.idempotencyKey, event, presentation?.visit)
                        }, onBack = back)
                    }
                }
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
    onboardingClient: com.delminiusapps.tillfailure.invitations.InvitationClient? = null,
    drafts: com.delminiusapps.tillfailure.programs.DraftRepository? = null,
    customExercises: com.delminiusapps.tillfailure.exercises.CustomExerciseRepository? = null,
    exercises: com.delminiusapps.tillfailure.exercises.SystemExerciseRepository? = null,
    clients: ClientRepository? = null,
    profile: ProfileViewModel? = null,
    invitations: (@Composable (TrainerInvitationPresentation?, (() -> Unit)?) -> Unit)? = null,
) {
    val backStack = rememberNavBackStack(identitySavedState, IdentityStateDestination(GateStatus.Loading))
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(effect, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effect.collect { emitted ->
                when (emitted) {
                    is IdentityEffect.ReplaceRoot -> {
                        val destination = IdentityStateDestination(currentState.status, currentState.authorityEpoch)
                        if (backStack.lastOrNull() != destination) {
                            backStack.clear()
                            backStack.add(destination)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(state.status, state.authorityEpoch) {
        val destination = IdentityStateDestination(state.status, state.authorityEpoch)
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator(), rememberProductViewModelStoreDecorator()),
        entryProvider = entryProvider {
            entry<IdentityStateDestination> {
                // Current server gate always wins over restored keys and buffered effects.
                IdentityScreen(state, onEvent, onOpenDevelopmentCatalog, invitations, drafts = drafts, customExercises = customExercises, exercises = exercises, clients = clients,
                    profile = profile, onboarding = onboardingClient?.let { service -> { OnboardingRoute(state, service) { onEvent(IdentityEvent.Retry) } } })
            }
        },
    )
}

@Composable
fun IdentityScreen(
    state: IdentityState,
    onEvent: (IdentityEvent) -> Unit,
    onOpenDevelopmentCatalog: (() -> Unit)? = null,
    invitations: (@Composable (TrainerInvitationPresentation?, (() -> Unit)?) -> Unit)? = null,
    onboarding: (@Composable () -> Unit)? = null,
    drafts: com.delminiusapps.tillfailure.programs.DraftRepository? = null,
    customExercises: com.delminiusapps.tillfailure.exercises.CustomExerciseRepository? = null,
    exercises: com.delminiusapps.tillfailure.exercises.SystemExerciseRepository? = null,
    clients: ClientRepository? = null,
    profile: ProfileViewModel? = null,
) {
    if (state.status in setOf(GateStatus.ClientHome, GateStatus.TrainerHome)) {
        key(state.accountUid, state.verifiedWorkspaceId, state.verifiedRole, state.authorityEpoch) {
            RoleShell(state, onEvent, invitations, onboarding, clients, profile, exercises, customExercises, drafts)
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(TillFailureTheme.colors.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(24.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("TillFailure", style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText)
        Text(
            when (state.status) {
                GateStatus.Onboarding -> "About you"
                GateStatus.ClientHome -> "Client home"
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
                GateStatus.UnverifiedEmail -> "Verify the email on this account, then recheck. Access stays locked until Auth reports it verified."
                GateStatus.AccountDisabled -> "This account is disabled. Contact support."
                GateStatus.WorkspaceGate -> "Workspace access pending. Ask your trainer or operator for access, then recheck."
                GateStatus.TrainerHome -> "Trainer workspace verified. Manage client invitations below."
                GateStatus.MembershipVerifiedFeaturePending -> "Membership verified. This feature is coming soon."
                GateStatus.UnsupportedSchema -> "Your account data needs an update. Contact support."
                GateStatus.AccessLost -> "Access could not be verified. Contact support or retry."
                GateStatus.ConnectToVerify -> "Connect to verify your account."
                GateStatus.Retry -> "Verification timed out. Retry while connected."
                GateStatus.MultipleMemberships -> "Choose your workspace. Each option was verified online."
                GateStatus.Unconfigured -> "Sign-in is not configured in this build."
                GateStatus.SessionExpired -> "Your session expired. Account recovery is required before another sign-in."
                GateStatus.CleanupRequired -> "This installation needs account cleanup before another sign-in. Contact support."
                GateStatus.SwitchingOut -> "Safely signing out…"
                GateStatus.ReconciliationRequired -> "Account verification needs reconciliation. Access remains locked; contact support."
                GateStatus.LockedRecovery -> when (state.recovery) {
                    com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.StorageLocked -> "Locked recovery: protected storage cannot be read safely. Nothing was deleted. Retry or contact support; another account cannot sign in."
                    com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.ReconciliationRequired -> "Locked recovery: retained work needs an approved reconciliation policy. No discard or automatic repair is available."
                    else -> when (state.recoveryCause) {
                        GateStatus.AccessLost, GateStatus.AccountDisabled, GateStatus.SessionExpired -> "Locked recovery: access is no longer verified. The original command remains protected. Retry under this account; a receipt cannot restore workspace access."
                        GateStatus.ReconciliationRequired -> "Locked recovery: reconciliation needs review. The original command remains protected."
                        else -> "Locked recovery: the original command remains protected. Retry needs current business access. Confirm Resolve to determine its outcome. Identity, binding or storage limits may still block sign-out."
                    }
                }
                GateStatus.DepartureBlocked -> "Sign-out is blocked by pending work or an unavailable cleanup check. Retry while connected."
            },
            color = TillFailureTheme.colors.primaryText,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.status == GateStatus.MultipleMemberships) {
            state.workspaces.sortedBy { it.workspaceId != state.preferredWorkspaceId }.forEach { workspace ->
                com.delminiusapps.tillfailure.core.designsystem.TillFailureSecondaryButton(
                    "${workspace.name} · ${workspace.role}", { onEvent(IdentityEvent.ChooseWorkspace(workspace.workspaceId)) }, enabled = !state.busy)
            }
        }
        onboarding?.invoke()
        profile?.let { model ->
            val profileState by model.state.collectAsStateWithLifecycle()
            if (profileState.scope == ProfileScope(state.accountUid, state.verifiedWorkspaceId, state.authorityEpoch))
                ProfileScreen(profileState, { model.onEvent(profileState.scope!!, null, profileState.promptId, it) }, { onEvent(IdentityEvent.Retry) }, recoveryOnly = true)
        }
        invitations?.invoke(null, null)
        if (state.status == GateStatus.Loading) CircularProgressIndicator()
        if (state.status == GateStatus.SignedOut || state.status == GateStatus.InvalidCredentials) {
            OutlinedTextField(
                value = state.email,
                onValueChange = { onEvent(IdentityEvent.EmailChanged(it)) },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            )
            Button(onClick = { onEvent(IdentityEvent.SignIn) }, enabled = !state.busy) { Text("Sign in") }
            Text("Development emulator sign-in only", color = TillFailureTheme.colors.secondaryText)
        } else if (state.status != GateStatus.Loading && state.status != GateStatus.IdentityUnsupported &&
            state.status != GateStatus.AnonymousSession &&
            state.status != GateStatus.TrainerHome && state.status != GateStatus.MembershipVerifiedFeaturePending && state.status != GateStatus.ConnectToVerify &&
            state.status != GateStatus.Unconfigured && state.status != GateStatus.SessionExpired &&
            state.status != GateStatus.CleanupRequired
        ) {
            Button(onClick = { onEvent(IdentityEvent.Retry) }, enabled = !state.busy) { Text("Recheck access") }
        } else if (state.status == GateStatus.ConnectToVerify) {
            Button(onClick = { onEvent(IdentityEvent.Retry) }) { Text("Retry") }
        }
        if (state.status in setOf(GateStatus.AccessLost, GateStatus.LockedRecovery) && state.accountUid != null &&
            state.recovery !in setOf(com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.StorageLocked,
                com.delminiusapps.tillfailure.invitations.ProtectedRecoveryState.ReconciliationRequired)) {
            Button(onClick = { onEvent(IdentityEvent.ChangeWorkspace) }, enabled = !state.busy) { Text("Choose another workspace") }
        }
        if (state.status == GateStatus.CleanupRequired) {
            Button(onClick = { onEvent(IdentityEvent.RetryCleanup) }, enabled = !state.busy) { Text("Retry cleanup") }
        }
        if (state.canSignOut) {
            Button(onClick = { onEvent(IdentityEvent.SignOut) }, enabled = !state.busy) { Text("Sign out") }
        }
        if (onOpenDevelopmentCatalog != null && canOpenDevelopmentCatalog(state)) {
            Button(onClick = onOpenDevelopmentCatalog) { Text("Open development UI catalog") }
            Text("Debug visual fixtures only; this does not verify an account or role.",
                color = TillFailureTheme.colors.secondaryText)
        }
    }
}
