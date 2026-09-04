package com.delminiusapps.tillfailure.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.delminiusapps.tillfailure.foundation.details.FoundationDetailsRoute
import com.delminiusapps.tillfailure.foundation.home.FoundationHomeRoute
import com.delminiusapps.tillfailure.designcatalog.ActiveWorkoutFixtureScreen
import com.delminiusapps.tillfailure.designcatalog.AuthFixtureScreen
import com.delminiusapps.tillfailure.designcatalog.AuthFixtureUiModel
import com.delminiusapps.tillfailure.designcatalog.CatalogFixtures
import com.delminiusapps.tillfailure.designcatalog.CatalogPreviewDestination
import com.delminiusapps.tillfailure.designcatalog.ClientHomeFixtureScreen
import com.delminiusapps.tillfailure.designcatalog.DesignCatalogScreen
import com.delminiusapps.tillfailure.designcatalog.TrainerClientDetailsFixtureScreen
import com.delminiusapps.tillfailure.designcatalog.TrainerClientDetailsTab
import com.delminiusapps.tillfailure.designcatalog.TrainerDashboardFixtureScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
sealed interface FoundationDestination : NavKey

@Serializable
data object FoundationHome : FoundationDestination

@Serializable
data object FoundationDetails : FoundationDestination

@Serializable
data object DesignCatalog : FoundationDestination

@Serializable
data object CatalogAuthentication : FoundationDestination

@Serializable
data object CatalogClientHome : FoundationDestination

@Serializable
data object CatalogActiveWorkout : FoundationDestination

@Serializable
data object CatalogTrainerDashboard : FoundationDestination

@Serializable
data object CatalogTrainerClientDetails : FoundationDestination

internal enum class FoundationSnackbarHostOwner { Root, Destination }

internal fun foundationSnackbarHostOwner(
    destination: FoundationDestination?,
): FoundationSnackbarHostOwner = when (destination) {
    DesignCatalog,
    CatalogClientHome,
    CatalogActiveWorkout,
    CatalogTrainerDashboard,
    CatalogTrainerClientDetails,
    -> FoundationSnackbarHostOwner.Destination

    FoundationHome,
    FoundationDetails,
    CatalogAuthentication,
    null,
    -> FoundationSnackbarHostOwner.Root
}

internal fun destinationOwnsFoundationSnackbarHost(
    activeDestination: FoundationDestination?,
    candidateDestination: FoundationDestination,
): Boolean =
    activeDestination == candidateDestination &&
        foundationSnackbarHostOwner(candidateDestination) == FoundationSnackbarHostOwner.Destination

/** Local draft for one Authentication catalog entry; a newly opened entry starts from the fixture. */
internal data class AuthFixtureDraft(
    val email: String,
    val password: String,
    val passwordVisible: Boolean,
) {
    fun withEmail(value: String) = copy(email = value)
    fun withPassword(value: String) = copy(password = value)
    fun togglePasswordVisibility() = copy(passwordVisible = !passwordVisible)
    fun toUiModel(fixture: AuthFixtureUiModel) = fixture.copy(
        email = email,
        password = password,
        passwordVisible = passwordVisible,
    )

    companion object {
        fun from(fixture: AuthFixtureUiModel) = AuthFixtureDraft(
            email = fixture.email,
            password = fixture.password,
            passwordVisible = fixture.passwordVisible,
        )
    }
}

private const val CatalogProfileUnavailableMessage =
    "Profile navigation is outside this Milestone 2 fixture."
private const val CatalogDashboardSectionMessage =
    "Dashboard filtering is outside this Milestone 2 fixture."

private val foundationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(FoundationHome::class, FoundationHome.serializer())
            subclass(FoundationDetails::class, FoundationDetails.serializer())
            subclass(DesignCatalog::class, DesignCatalog.serializer())
            subclass(CatalogAuthentication::class, CatalogAuthentication.serializer())
            subclass(CatalogClientHome::class, CatalogClientHome.serializer())
            subclass(CatalogActiveWorkout::class, CatalogActiveWorkout.serializer())
            subclass(CatalogTrainerDashboard::class, CatalogTrainerDashboard.serializer())
            subclass(CatalogTrainerClientDetails::class, CatalogTrainerClientDetails.serializer())
        }
    }
}

@Composable
internal fun FoundationNavigation(showDevelopmentCatalog: Boolean) {
    val backStack = rememberNavBackStack(
        foundationSavedStateConfiguration,
        FoundationHome,
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val activeDestination = backStack.lastOrNull() as? FoundationDestination
    val rootOwnsSnackbarHost =
        foundationSnackbarHostOwner(activeDestination) == FoundationSnackbarHostOwner.Root

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = if (rootOwnsSnackbarHost) {
            WindowInsets.safeDrawing
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        snackbarHost = {
            if (rootOwnsSnackbarHost) {
                SnackbarHost(snackbarHostState)
            }
        },
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = backStack,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<FoundationHome> {
                    FoundationHomeRoute(
                        onNavigateToDetails = {
                            if (backStack.lastOrNull() != FoundationDetails) {
                                backStack.add(FoundationDetails)
                            }
                        },
                        showDevelopmentCatalog = showDevelopmentCatalog,
                        onNavigateToDesignCatalog = { backStack.add(DesignCatalog) },
                        onShowMessage = snackbarHostState::showSnackbar,
                    )
                }
                entry<FoundationDetails> {
                    FoundationDetailsRoute(
                        onNavigateBack = {
                            if (backStack.size > 1) {
                                backStack.removeLastOrNull()
                            }
                        },
                    )
                }
                entry<DesignCatalog> {
                    DesignCatalogScreen(
                        onBackClick = { backStack.removeLastOrNull() },
                        onOpenPreview = { destination ->
                            backStack.add(
                                when (destination) {
                                    CatalogPreviewDestination.Authentication -> CatalogAuthentication
                                    CatalogPreviewDestination.ClientHome -> CatalogClientHome
                                    CatalogPreviewDestination.ActiveWorkout -> CatalogActiveWorkout
                                    CatalogPreviewDestination.TrainerDashboard -> CatalogTrainerDashboard
                                    CatalogPreviewDestination.TrainerClientDetails -> CatalogTrainerClientDetails
                                },
                            )
                        },
                        snackbarHostState = snackbarHostState.takeIf {
                            destinationOwnsFoundationSnackbarHost(activeDestination, DesignCatalog)
                        },
                    )
                }
                entry<CatalogAuthentication> {
                    var draft by remember { mutableStateOf(AuthFixtureDraft.from(CatalogFixtures.auth)) }
                    AuthFixtureScreen(
                        model = draft.toUiModel(CatalogFixtures.auth),
                        onBackClick = { backStack.removeLastOrNull() },
                        onEmailChanged = { draft = draft.withEmail(it) },
                        onPasswordChanged = { draft = draft.withPassword(it) },
                        onPasswordVisibilityToggle = { draft = draft.togglePasswordVisibility() },
                        onForgotPasswordClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Password recovery is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onSignInClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Authentication is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                    )
                }
                entry<CatalogClientHome> {
                    ClientHomeFixtureScreen(
                        model = CatalogFixtures.clientHome,
                        onBackClick = { backStack.removeLastOrNull() },
                        onStartWorkoutClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Workout execution is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onAppointmentClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Appointment details are outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onNavigationItemClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Navigation destinations are outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onRetryClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Retry is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onProfileClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = CatalogProfileUnavailableMessage,
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        snackbarHostState = snackbarHostState.takeIf {
                            destinationOwnsFoundationSnackbarHost(activeDestination, CatalogClientHome)
                        },
                    )
                }
                entry<CatalogActiveWorkout> {
                    ActiveWorkoutFixtureScreen(
                        model = CatalogFixtures.activeWorkout,
                        onBackClick = { backStack.removeLastOrNull() },
                        onMoreClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Workout options are outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onReplaceExerciseClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Exercise replacement is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onAddSetClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Adding sets is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onSetClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Set editing is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onNoteClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Workout note editing is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onCompleteSetClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Completing sets is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onFinishClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Finishing workouts is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        snackbarHostState = snackbarHostState.takeIf {
                            destinationOwnsFoundationSnackbarHost(activeDestination, CatalogActiveWorkout)
                        },
                    )
                }
                entry<CatalogTrainerDashboard> {
                    TrainerDashboardFixtureScreen(
                        model = CatalogFixtures.trainerDashboard,
                        onBackClick = { backStack.removeLastOrNull() },
                        onNavigationItemClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Navigation destinations are outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onAppointmentClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Client appointment details are outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onActivityClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Activity review is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onSectionClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = CatalogDashboardSectionMessage,
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onViewScheduleClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Schedule navigation is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onReviewActivitiesClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Review queue navigation is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onRetryClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Retry is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onProfileClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = CatalogProfileUnavailableMessage,
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        snackbarHostState = snackbarHostState.takeIf {
                            destinationOwnsFoundationSnackbarHost(activeDestination, CatalogTrainerDashboard)
                        },
                    )
                }
                entry<CatalogTrainerClientDetails> {
                    var selectedTab by remember { mutableStateOf(CatalogFixtures.trainerClientDetails.selectedTab) }
                    TrainerClientDetailsFixtureScreen(
                        model = CatalogFixtures.trainerClientDetails.copy(selectedTab = selectedTab),
                        onBackClick = { backStack.removeLastOrNull() },
                        onMoreClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Client options are outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onMessageClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Trainer messaging is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onTabClick = { tab ->
                            selectedTab = tab
                        },
                        onEditClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Client profile editing is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        onAssignProgramClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Program assignment is outside this Milestone 2 fixture.",
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        },
                        snackbarHostState = snackbarHostState.takeIf {
                            destinationOwnsFoundationSnackbarHost(activeDestination, CatalogTrainerClientDetails)
                        },
                    )
                }
            },
        )
    }
}
