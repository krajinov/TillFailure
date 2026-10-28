package com.delminiusapps.tillfailure.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delminiusapps.tillfailure.profile.*
import com.delminiusapps.tillfailure.invitations.InvitationEntry
import com.delminiusapps.tillfailure.invitations.TrainerInvitationPresentation
import com.delminiusapps.tillfailure.clients.*
import com.delminiusapps.tillfailure.exercises.*
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
    val invitationEntry: InvitationEntry? = null, val refreshVersion: Long = 0, val exerciseId: String? = null) : NavKey
@Serializable internal enum class ShellPage { Home, Profile, Invitations, Clients, ClientDetails, EditProfile, TrainerNote, Exercises, ExerciseDetails, CustomExercises, CustomExerciseDetails, CustomExerciseEditor }
private val shellSavedState = SavedStateConfiguration {
    serializersModule = SerializersModule { polymorphic(NavKey::class) { subclass(ShellDestination::class, ShellDestination.serializer()) } }
}

/** No catalog fixtures or future tabs. The caller supplies a current, server-verified root. */
@Composable
internal fun RoleShell(state: IdentityState, onEvent: (IdentityEvent) -> Unit,
    invitations: (@Composable (TrainerInvitationPresentation?, (() -> Unit)?) -> Unit)?, onboardingRecovery: (@Composable () -> Unit)?, clients: ClientRepository?, profile: ProfileViewModel? = null, exercises: SystemExerciseRepository? = null, customExercises: CustomExerciseRepository? = null) {
    val owner = ShellOwner(state.accountUid, state.verifiedWorkspaceId, state.accountUid.takeIf { state.verifiedRole == "trainer" }, state.verifiedRole, state.authorityEpoch)
    val stack = rememberNavBackStack(shellSavedState, ShellDestination(ShellPage.Home, owner = owner))
    LaunchedEffect(owner, stack.lastOrNull()) {
        if ((stack.lastOrNull() as? ShellDestination)?.owner != owner) {
            stack.clear(); stack.add(ShellDestination(ShellPage.Home, owner = owner))
        }
    }
    var customLeave by remember(owner) { mutableStateOf<Pair<ShellDestination,(String?)->Unit>?>(null) }
    var noteLeave by remember(owner) { mutableStateOf<Pair<ShellDestination,(String?)->Unit>?>(null) }
    val profileState = profile?.state?.collectAsStateWithLifecycle()?.value
    val currentOwner by rememberUpdatedState(owner)
    LaunchedEffect(profile, owner) {
        profile?.effect?.collect { result ->
            val top = stack.lastOrNull() as? ShellDestination
            if (acceptProfileNavigation(profile, result, currentOwner, top)) {
                if (result.exit == ProfileExit.Back) stack.removeLastOrNull()
                else openShellPage(stack, currentOwner, if (result.exit == ProfileExit.Home) ShellPage.Home else ShellPage.Profile, top)
                result.snapshot?.let { onEvent(IdentityEvent.ProfileRefreshed(result.scope, it)) }
            }
        }
    }
    var revocationNotice by remember(owner) { mutableStateOf(false) }
    val selectedDestination = stack.lastOrNull() as? ShellDestination
    val selected = selectedDestination?.page ?: ShellPage.Home
    LaunchedEffect(owner,selectedDestination) { if (selectedDestination?.page != ShellPage.Clients) revocationNotice=false }
    fun leaveProfile(exit: ProfileExit) {
        val p = profileState ?: return
        if (p.scope == ProfileScope(owner.uid, owner.workspace, owner.epoch) && p.visit == selectedDestination?.refreshVersion)
            profile?.onEvent(p.scope!!, p.visit, p.promptId, ProfileEvent.Leave(exit))
    }
    fun open(page: ShellPage, source: ShellDestination? = selectedDestination) {
        if(source?.page in customExercisePages && stack.lastOrNull()==source) {
            customLeave?.takeIf { it.first==source }?.second?.invoke(page.name);return
        }
        if(source?.page == ShellPage.TrainerNote && stack.lastOrNull()==source) {
            noteLeave?.takeIf { it.first==source }?.second?.invoke(page.name);return
        }
        if (source?.page == ShellPage.EditProfile && stack.lastOrNull() == source)
            leaveProfile(if (page == ShellPage.Home) ProfileExit.Home else ProfileExit.Profile)
        else openShellPage(stack, owner, page, source)
    }
    @Composable fun recovery() {
        val p = profileState ?: return
        if (p.scope == ProfileScope(owner.uid, owner.workspace, owner.epoch))
            ProfileScreen(p, { profile?.onEvent(p.scope!!, p.visit, p.promptId, it) }, { onEvent(IdentityEvent.Retry) }, recoveryOnly = true)
    }
    Column(Modifier.fillMaxSize().background(TillFailureTheme.colors.background).safeDrawingPadding().imePadding()) {
        NavDisplay(backStack = stack, onBack = {
            if (stack.lastOrNull() == selectedDestination) {
                if (selectedDestination?.page in customExercisePages) customLeave?.takeIf { it.first==selectedDestination }?.second?.invoke(null)
                else if (selectedDestination?.page == ShellPage.TrainerNote) noteLeave?.takeIf { it.first==selectedDestination }?.second?.invoke(null)
                else if (selectedDestination?.page == ShellPage.EditProfile) leaveProfile(ProfileExit.Back)
                else if (selectedDestination?.page in setOf(ShellPage.Exercises, ShellPage.ExerciseDetails)) backFromExercises(stack, selectedDestination!!)
                else if (selectedDestination?.page == ShellPage.Invitations) backFromInvitations(stack, selectedDestination)
                else if (stack.size > 1) stack.removeLastOrNull()
            }
        }, modifier = Modifier.weight(1f), entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberProductViewModelStoreDecorator()), entryProvider = entryProvider {
            entry<ShellDestination> { destination ->
                if (destination.owner != owner) return@entry
                if (destination.page in customExercisePages) {
                    if(owner.role=="trainer" && customExercises!=null && owner.uid!=null && owner.workspace!=null) {
                        val mode=when(destination.page){ShellPage.CustomExercises->CustomExerciseMode.List;ShellPage.CustomExerciseDetails->CustomExerciseMode.Detail;else->CustomExerciseMode.Editor}
                        CustomExercisesRoute(customExercises,CustomExerciseVisit(ExerciseScope(owner.uid,owner.workspace,owner.epoch),destination.refreshVersion,mode,destination.exerciseId),
                            {ownsClientDestination(stack,owner,currentOwner,destination)},
                            {callback,install->if(install && stack.lastOrNull()==destination)customLeave=destination to callback else if(customLeave?.second===callback)customLeave=null},
                            {n->if(ownsClientDestination(stack,owner,currentOwner,destination)) {
                                if(n.kind=="back") {if(n.exit!=null)openShellPage(stack,owner,ShellPage.valueOf(n.exit),destination) else backFromCustomExercises(stack,destination)}
                                else {
                                    val page=if(n.kind in setOf("create","edit"))ShellPage.CustomExerciseEditor else ShellPage.CustomExerciseDetails
                                    val visit=stack.filterIsInstance<ShellDestination>().maxOf{it.refreshVersion}+1
                                    if(n.kind=="saved")stack.removeLastOrNull()
                                    stack.add(ShellDestination(page,owner=owner,refreshVersion=visit,exerciseId=n.id))
                                }
                            }},
                            {if(ownsClientDestination(stack,owner,currentOwner,destination))openClientInvitations(stack,owner,InvitationEntry.Manage,destination)})
                    }
                } else if (destination.page in setOf(ShellPage.Exercises, ShellPage.ExerciseDetails)) {
                    if (owner.role == "trainer" && exercises != null && owner.uid != null && owner.workspace != null) {
                        ExercisesRoute(exercises, ExerciseVisit(ExerciseScope(owner.uid, owner.workspace, owner.epoch), destination.refreshVersion, destination.exerciseId),
                            { ownsClientDestination(stack, owner, currentOwner, destination) },
                            { id -> if (ownsClientDestination(stack, owner, currentOwner, destination)) stack.add(ShellDestination(ShellPage.ExerciseDetails, owner = owner, refreshVersion = destination.refreshVersion + 1, exerciseId = id)) },
                            { if (ownsClientDestination(stack, owner, currentOwner, destination)) backFromExercises(stack, destination) },
                            if(destination.page==ShellPage.Exercises && customExercises!=null) ({if(ownsClientDestination(stack,owner,currentOwner,destination))stack.add(ShellDestination(ShellPage.CustomExercises,owner=owner,refreshVersion=destination.refreshVersion+1))}) else null)
                    }
                } else if(destination.page == ShellPage.TrainerNote) {
                    val commands=clients?.commands
                    if(state.verifiedRole=="trainer" && commands!=null && owner.uid!=null && owner.workspace!=null && destination.clientId!=null)
                        TrainerNoteRoute(commands,NoteOwner(ClientScope(owner.uid,owner.workspace,owner.uid,owner.epoch),destination.clientId,destination.refreshVersion),
                            { ownsClientDestination(stack,owner,currentOwner,destination) },
                            { callback,install -> if(install && stack.lastOrNull()==destination) noteLeave=destination to callback else if(noteLeave?.second===callback) noteLeave=null },
                            { page -> if(ownsClientDestination(stack,owner,currentOwner,destination)) { if(page==null && stack.size>1) stack.removeLastOrNull() else page?.let { openShellPage(stack,owner,ShellPage.valueOf(it),destination) } } },
                            { if(ownsClientDestination(stack,owner,currentOwner,destination)) openClientInvitations(stack,owner,InvitationEntry.Manage,destination) })
                } else if (destination.page in setOf(ShellPage.Clients, ShellPage.ClientDetails)) {
                    if (state.verifiedRole == "trainer" && clients != null && state.accountUid != null && state.verifiedWorkspaceId != null) {
                        ClientsRoute(clients,
                            ClientScope(state.accountUid, state.verifiedWorkspaceId, state.accountUid, state.authorityEpoch),
                            destination.clientId, { if (stack.lastOrNull() == destination) stack.add(ShellDestination(ShellPage.ClientDetails, it, owner, refreshVersion = destination.refreshVersion)) },
                            { if (stack.lastOrNull() == destination && stack.size > 1) stack.removeLastOrNull() }, { if (ownsClientDestination(stack,owner,currentOwner,destination)) { onEvent(IdentityEvent.Retry) } },
                            { compose -> openClientInvitations(stack, owner, if (compose) InvitationEntry.Invite else InvitationEntry.Manage, destination) }, onRevoked = {
                                if (ownsClientDestination(stack,owner,currentOwner,destination) && stack.size > 1) {
                                    revocationNotice=true
                                    stack.removeLastOrNull()
                                    val roster = stack.lastOrNull() as? ShellDestination
                                    if (roster?.page == ShellPage.Clients) stack[stack.lastIndex] = roster.copy(refreshVersion = roster.refreshVersion + 1)
                                }
                            }, workspaceName = state.verifiedWorkspaceName ?: state.verifiedWorkspaceId, completedRevocation = revocationNotice,onNotes={ target ->
                                if(ownsClientDestination(stack,owner,currentOwner,destination)) {
                                    val visit=stack.filterIsInstance<ShellDestination>().maxOf { it.refreshVersion }+1
                                    stack.add(ShellDestination(ShellPage.TrainerNote,target,owner,refreshVersion=visit))
                                }
                            })
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
                                Text(if (state.verifiedRole == "trainer") "View assigned clients, manage invitations and browse system exercises. Programming and scheduling are planned for a later update."
                                    else "Onboarding complete. Workouts, scheduling, progress and messages are planned for later updates.",
                                    Modifier.padding(TillFailureSpacing.lg), color = TillFailureTheme.colors.secondaryText)
                            }
                            if (state.verifiedRole == "trainer") TillFailurePrimaryButton("Clients", { open(ShellPage.Clients, destination) })
                            if (state.verifiedRole == "trainer" && exercises != null) TillFailurePrimaryButton("Exercises", { open(ShellPage.Exercises, destination) })
                            if (state.verifiedRole == "trainer") TillFailurePrimaryButton("Client invitations", { open(ShellPage.Invitations, destination) })
                            TillFailureSecondaryButton("Profile and access", { open(ShellPage.Profile, destination) })
                            onboardingRecovery?.invoke(); recovery()
                        }
                        ShellPage.Profile -> {
                            Text("Profile and access", style = MaterialTheme.typography.headlineMedium, color = TillFailureTheme.colors.primaryText)
                            Text("Workspace: ${state.verifiedWorkspaceId}\nRole: ${state.verifiedRole}", color = TillFailureTheme.colors.secondaryText)
                            if (state.verifiedRole == "client") Text("${state.displayName}\n${state.locale}\n${state.timeZoneId}", color = TillFailureTheme.colors.primaryText)
                            if (state.verifiedRole == "client" && profile != null)
                                TillFailurePrimaryButton("Edit basic profile", { openBasicProfile(stack, owner, destination) })
                            TillFailureSecondaryButton("Recheck access", { onEvent(IdentityEvent.Retry) }, enabled = !state.busy)
                            TillFailureSecondaryButton("Choose workspace", { onEvent(IdentityEvent.ChangeWorkspace) }, enabled = !state.busy)
                            if (state.canSignOut) TillFailureSecondaryButton("Sign out", { onEvent(IdentityEvent.SignOut) }, enabled = !state.busy)
                            TillFailureSecondaryButton("Invitations and recovery", { open(ShellPage.Invitations, destination) })
                            onboardingRecovery?.invoke(); recovery()
                        }
                        ShellPage.EditProfile -> {
                            val expected = ProfileScope(owner.uid, owner.workspace, owner.epoch)
                            LaunchedEffect(profile, destination, profileState?.scope) {
                                if (profileState?.scope == expected) profile?.attach(destination.refreshVersion)
                            }
                            DisposableEffect(profile, destination) { onDispose { profile?.detach(expected, destination.refreshVersion) } }
                            if (profileState?.scope == expected && profileState.visit == destination.refreshVersion)
                                ProfileScreen(profileState, { profile?.onEvent(expected, destination.refreshVersion, profileState.promptId, it) }, { onEvent(IdentityEvent.Retry) })
                        }
                        ShellPage.Clients, ShellPage.ClientDetails, ShellPage.TrainerNote, ShellPage.Exercises, ShellPage.ExerciseDetails, ShellPage.CustomExercises, ShellPage.CustomExerciseDetails, ShellPage.CustomExerciseEditor -> Unit
                        ShellPage.Invitations -> {
                            invitations?.invoke(destination.invitationEntry?.let { TrainerInvitationPresentation(it, destination.refreshVersion) }) { backFromInvitations(stack, destination) }
                            onboardingRecovery?.invoke()
                        }
                    }
                }
            }
        })
        val pages = if (state.verifiedRole == "trainer") listOfNotNull(ShellPage.Home, ShellPage.Clients, ShellPage.Exercises.takeIf { exercises != null }, ShellPage.Profile) else listOf(ShellPage.Home, ShellPage.Profile)
        TillFailureBottomNavigation(pages.map { page -> TillFailureNavigationItem(page.name, if (page == ShellPage.Home) "⌂" else "○", selected == page || page == ShellPage.Exercises && selected in setOf(ShellPage.ExerciseDetails)+customExercisePages || page == ShellPage.Profile && selected == ShellPage.EditProfile || page == ShellPage.Clients && selected in setOf(ShellPage.ClientDetails, ShellPage.Invitations, ShellPage.TrainerNote)) }, { open(pages[it]) })
    }
}

/** One owned route; navigation results contain no invitation reply, email or bearer. */
internal fun openShellPage(stack: MutableList<NavKey>, owner: ShellOwner, page: ShellPage, source: ShellDestination?) {
    if (source == null || stack.lastOrNull() != source || source.owner != owner) return
    if (page in setOf(ShellPage.Exercises, ShellPage.ExerciseDetails)+customExercisePages && owner.role != "trainer") return
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

/** Advance the underlying Profile key too, so reopened editors never reuse an old visit. */
internal fun openBasicProfile(stack: MutableList<NavKey>, owner: ShellOwner, source: ShellDestination) {
    if (owner.role != "client" || source.owner != owner || source.page != ShellPage.Profile || stack.lastOrNull() != source) return
    val visit = stack.filterIsInstance<ShellDestination>().maxOf { it.refreshVersion } + 1
    stack[stack.lastIndex] = source.copy(refreshVersion = visit)
    stack.add(ShellDestination(ShellPage.EditProfile, owner = owner, refreshVersion = visit))
}

internal fun acceptProfileNavigation(model: ProfileViewModel, result: ProfileNavigation, owner: ShellOwner,
    top: ShellDestination?): Boolean =
    result.scope == ProfileScope(owner.uid, owner.workspace, owner.epoch) &&
        top?.page == ShellPage.EditProfile && top.refreshVersion == result.visit && model.consumeNavigation(result)

/** An exiting entry may still receive a failed read; it cannot reset a newer root or destination. */
internal fun ownsClientDestination(stack: List<NavKey>, owner: ShellOwner, currentOwner: ShellOwner, source: ShellDestination): Boolean =
    currentOwner == owner && source.owner == owner && stack.lastOrNull() == source

/** A detail return always creates a fresh list visit/read; stale exiting entries cannot navigate. */
internal fun backFromExercises(stack: MutableList<NavKey>, source: ShellDestination) {
    if (source.page !in setOf(ShellPage.Exercises, ShellPage.ExerciseDetails) || stack.lastOrNull() != source || stack.size < 2) return
    stack.removeLastOrNull()
    val list = stack.lastOrNull() as? ShellDestination
    if (list?.page == ShellPage.Exercises && list.owner == source.owner) stack[stack.lastIndex] = list.copy(refreshVersion = source.refreshVersion + 1)
}


private val customExercisePages=setOf(ShellPage.CustomExercises,ShellPage.CustomExerciseDetails,ShellPage.CustomExerciseEditor)
internal fun backFromCustomExercises(stack:MutableList<NavKey>,source:ShellDestination) {
    if(stack.lastOrNull()!=source || source.page !in customExercisePages || stack.size<2)return
    stack.removeLastOrNull()
    val top=stack.lastOrNull() as? ShellDestination?:return
    if(top.owner==source.owner && top.page in customExercisePages+ShellPage.Exercises)stack[stack.lastIndex]=top.copy(refreshVersion=source.refreshVersion+1)
}
