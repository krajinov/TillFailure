package com.delminiusapps.tillfailure.designcatalog

import com.delminiusapps.tillfailure.core.designsystem.LoggedSetStatus
import com.delminiusapps.tillfailure.core.designsystem.TillFailureBottomNavigationLayout
import com.delminiusapps.tillfailure.core.designsystem.tillFailureBottomNavigationLayout
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Milestone2ReviewRegressionTest {
    @Test
    fun clientBottomNavigationUsesRenderedWidthAtNormalModerateAndLargeText() {
        val labels = clientNavigationItems(ClientNavigationDestination.Home).map { it.label }
        assertEquals(listOf("Home", "Workouts", "Schedule", "Progress", "Messages"), labels)
        val renderedWidthsAtOneX = listOf(40, 56, 54, 53, 62)

        assertEquals(TillFailureBottomNavigationLayout.Weighted, navigationLayout(393, 1f, renderedWidthsAtOneX))
        assertEquals(TillFailureBottomNavigationLayout.HorizontallyScrollable, navigationLayout(320, 1.25f, renderedWidthsAtOneX))
        assertEquals(TillFailureBottomNavigationLayout.HorizontallyScrollable, navigationLayout(393, 2f, renderedWidthsAtOneX))
    }

    @Test
    fun trainerBottomNavigationUsesRenderedWidthAtNormalModerateAndLargeText() {
        val labels = trainerNavigationItems(TrainerNavigationDestination.Dashboard).map { it.label }
        assertEquals(listOf("Dashboard", "Clients", "Programs", "Schedule", "Messages"), labels)
        val renderedWidthsAtOneX = listOf(68, 38, 58, 54, 62)

        assertEquals(TillFailureBottomNavigationLayout.Weighted, navigationLayout(393, 1f, renderedWidthsAtOneX))
        assertEquals(TillFailureBottomNavigationLayout.HorizontallyScrollable, navigationLayout(320, 1.25f, renderedWidthsAtOneX))
        assertEquals(TillFailureBottomNavigationLayout.HorizontallyScrollable, navigationLayout(393, 2f, renderedWidthsAtOneX))
        assertEquals(TillFailureBottomNavigationLayout.HorizontallyScrollable, navigationLayout(320, 1f, renderedWidthsAtOneX))
        assertEquals(TillFailureBottomNavigationLayout.Weighted, navigationLayout(520, 1.25f, renderedWidthsAtOneX))
    }

    @Test
    fun compactNavigationRequiresEveryTargetToFitAfterOuterPadding() {
        assertEquals(TillFailureBottomNavigationLayout.Weighted, navigationLayout(316, 1f, List(5) { 60 }))
        assertEquals(
            TillFailureBottomNavigationLayout.HorizontallyScrollable,
            navigationLayout(316, 1f, listOf(60, 60, 61, 60, 60)),
        )
    }

    @Test
    fun appointmentDatePresentationUsesAlternateModelValues() {
        val alternateModel = CatalogFixtures.clientHome.copy(
            appointmentDate = AppointmentDateFixtureUiModel(
                fullDate = "Friday, 27 September",
                weekday = "FRI",
                dayNumber = "27",
            ),
        )

        assertEquals(
            AppointmentDateFixtureUiModel(
                fullDate = "Friday, 27 September",
                weekday = "FRI",
                dayNumber = "27",
            ),
            clientHomeAppointmentDate(alternateModel),
        )
    }

    @Test
    fun activityClickForwardsExactTitleOnce() {
        val activity = ActivityFixtureUiModel(
            title = "Priya Shah",
            detail = "Program review is due",
        )
        var invocationCount = 0
        var forwardedTitle: String? = null

        dispatchActivityClick(activity) { title ->
            invocationCount += 1
            forwardedTitle = title
        }

        assertEquals(1, invocationCount)
        assertEquals("Priya Shah", forwardedTitle)
    }

    @Test
    fun catalogTopActionForwardsSuppliedProfileCallbackExactlyOnce() {
        var invocationCount = 0
        val action = catalogTopAction(
            glyph = "A",
            description = "Open profile",
            onClick = { invocationCount += 1 },
        )

        assertNotNull(action)
        action.onClick()

        assertEquals(1, invocationCount)
    }

    @Test
    fun catalogTopActionWithoutCallbackDoesNotExposeAControl() {
        assertNull(
            catalogTopAction(
                glyph = "A",
                description = "Open profile",
                onClick = null,
            ),
        )
    }

    @Test
    fun dashboardSessionCountSupportsZeroOneAndMultipleEntries() {
        assertEquals("0 sessions", trainerDashboardSessionCount(0))
        assertEquals("1 session", trainerDashboardSessionCount(1))
        assertEquals("2 sessions", trainerDashboardSessionCount(2))
    }

    @Test
    fun dashboardUpdateCountSupportsZeroOneAndMultipleEntries() {
        assertEquals("0 updates", trainerDashboardUpdateCount(0))
        assertEquals("1 update", trainerDashboardUpdateCount(1))
        assertEquals("2 updates", trainerDashboardUpdateCount(2))
    }

    @Test
    fun completeSetActionUsesCurrentSetOne() {
        val action = activeWorkoutCompleteSetAction(setsWithCurrent(1, 2, 3, 4, current = setOf(1)))

        assertEquals("Complete set 1", action.label)
        assertEquals(CurrentSetResolution.Ready, action.resolution)
        assertTrue(action.enabled)
    }

    @Test
    fun defaultStructuredModelsPreserveApprovedProgressOutputs() {
        val completeSet = activeWorkoutCompleteSetAction(CatalogFixtures.activeWorkout.sets)
        val workoutProgress = activeWorkoutProgress(CatalogFixtures.activeWorkout)
        val weeklyProgress = clientWeeklyProgress(CatalogFixtures.clientHome)
        val programProgress = trainerProgramProgress(CatalogFixtures.trainerClientDetails)

        assertEquals("Complete set 3", completeSet.label)
        assertEquals("3 / 7", workoutProgress.label)
        assertEquals(3f / 7f, workoutProgress.fraction)
        assertEquals("3 of 4 workouts", weeklyProgress.label)
        assertEquals("3", weeklyProgress.completedIndicator)
        assertEquals("WEEK 4 OF 8", programProgress.weekLabel)
        assertEquals("3 workouts weekly · 12 of 32 completed", programProgress.detailLabel)
        assertEquals(0.375f, programProgress.fraction)
    }

    @Test
    fun completeSetActionUsesCurrentSetFour() {
        val action = activeWorkoutCompleteSetAction(setsWithCurrent(1, 2, 3, 4, current = setOf(4)))

        assertEquals("Complete set 4", action.label)
        assertEquals(CurrentSetResolution.Ready, action.resolution)
        assertTrue(action.enabled)
    }

    @Test
    fun completeSetActionDoesNotDependOnSetThreeExisting() {
        val action = activeWorkoutCompleteSetAction(setsWithCurrent(1, 2, 4, current = setOf(2)))

        assertEquals("Complete set 2", action.label)
        assertTrue(action.enabled)
    }

    @Test
    fun completeSetActionDisablesWhenNoSetIsCurrent() {
        val action = activeWorkoutCompleteSetAction(setsWithCurrent(1, 2, 4, current = emptySet()))

        assertEquals("No current set", action.label)
        assertEquals(CurrentSetResolution.Missing, action.resolution)
        assertFalse(action.enabled)
    }

    @Test
    fun completeSetActionDisablesMalformedMultipleCurrentSets() {
        val action = activeWorkoutCompleteSetAction(setsWithCurrent(1, 2, 4, current = setOf(1, 4)))

        assertEquals("Resolve current set", action.label)
        assertEquals(CurrentSetResolution.Ambiguous, action.resolution)
        assertFalse(action.enabled)
    }

    @Test
    fun weeklyProgressUsesOneNormalizedSourceForTextIndicatorAndSemantics() {
        listOf(0, 1, 3, 4).forEach { completed ->
            val presentation = clientWeeklyProgress(
                CatalogFixtures.clientHome.copy(
                    weekProgress = CountProgressFixtureUiModel(completed = completed, total = 4),
                ),
            )

            assertEquals("$completed of 4 workouts", presentation.label)
            assertEquals(completed.toString(), presentation.completedIndicator)
            assertEquals("$completed of 4 workouts completed", presentation.accessibilityDescription)
        }
    }

    @Test
    fun weeklyProgressNormalizesInvalidAndZeroTotalCounts() {
        val negative = clientProgress(completed = -2, total = 4)
        val overflow = clientProgress(completed = 8, total = 4)
        val zeroTotal = clientProgress(completed = 2, total = 0)

        assertEquals("0 of 4 workouts", negative.label)
        assertEquals("4 of 4 workouts", overflow.label)
        assertEquals("0 of 0 workouts", zeroTotal.label)
        assertEquals("0", zeroTotal.completedIndicator)
    }

    @Test
    fun trainerProgramProgressCoversZeroPartialAndComplete() {
        val zero = trainerProgress(completed = 0, total = 32)
        val partial = trainerProgress(completed = 12, total = 32)
        val complete = trainerProgress(completed = 32, total = 32)

        assertEquals(0f, zero.fraction)
        assertEquals("3 workouts weekly · 0 of 32 completed", zero.detailLabel)
        assertEquals(0.375f, partial.fraction)
        assertEquals("3 workouts weekly · 12 of 32 completed", partial.detailLabel)
        assertEquals("12 of 32 workouts completed", partial.accessibilityDescription)
        assertEquals(1f, complete.fraction)
    }

    @Test
    fun trainerProgramProgressNormalizesZeroTotalAndInvalidCounts() {
        val zeroTotal = trainerProgress(completed = 5, total = 0)
        val negative = trainerProgress(completed = -1, total = 32)
        val overflow = trainerProgress(completed = 40, total = 32)

        assertEquals(0f, zeroTotal.fraction)
        assertEquals("3 workouts weekly · 0 of 0 completed", zeroTotal.detailLabel)
        assertEquals(0f, negative.fraction)
        assertEquals(1f, overflow.fraction)
        assertEquals("3 workouts weekly · 32 of 32 completed", overflow.detailLabel)
    }

    @Test
    fun auditDerivesAuthenticationAndClientBusinessCopyFromModels() {
        val auth = CatalogFixtures.auth.copy(firstName = "Priya", programName = "Mobility Reset")
        val client = CatalogFixtures.clientHome.copy(
            nextWorkoutSchedule = "FRIDAY, 09:30",
            trainerName = "Noor",
            trainerNoteTime = "2 min ago",
        )

        assertEquals("Welcome back, Priya.", authWelcomeTitle(auth))
        assertEquals("Sign in to continue your Mobility Reset program.", authProgramMessage(auth))
        assertEquals("NEXT WORKOUT · FRIDAY, 09:30", clientNextWorkoutLabel(client))
        assertEquals("Personal training with Noor", clientAppointmentTitle(client))
        assertEquals("NOOR · 2 MIN AGO", clientTrainerNoteLabel(client))
    }

    @Test
    fun auditDerivesWorkoutProgressSummaryAndElapsedDataFromModel() {
        val model = CatalogFixtures.activeWorkout.copy(
            exerciseProgress = CountProgressFixtureUiModel(completed = 1, total = 4),
            elapsedDuration = "07:45",
            exerciseTargetReps = "10 reps",
            restDuration = "90 sec",
            sets = CatalogFixtures.activeWorkout.sets.take(2),
        )
        val progress = activeWorkoutProgress(model)

        assertEquals("1 / 4", progress.label)
        assertEquals(0.25f, progress.fraction)
        assertEquals("1 of 4 exercises completed", progress.accessibilityDescription)
        assertEquals("2 sets · 10 reps · Rest 90 sec", activeWorkoutExerciseSummary(model))
        assertEquals("07:45", model.elapsedDuration)
    }

    @Test
    fun auditDerivesInitialsActivityGlyphsAndSelectedStates() {
        assertEquals("PS", initialsFromName("Priya Shah"))
        assertEquals("PS", activityGlyph(ActivityFixtureUiModel("Priya Shah", "Review due")))
        assertEquals(
            "Messages",
            clientNavigationItems(ClientNavigationDestination.Messages).single { it.selected }.label,
        )
        assertEquals(
            "Programs",
            trainerNavigationItems(TrainerNavigationDestination.Programs).single { it.selected }.label,
        )
        assertEquals("Program", TrainerClientDetailsTab.Program.label)
        assertEquals("Appointments", TrainerDashboardSection.Appointments.label)
    }

    @Test
    fun auditNormalizesTrainerDashboardCountsAndDescriptions() {
        assertEquals("A steady day · 0 clients need attention", trainerDashboardClientSummary(-1))
        assertEquals("A steady day · 1 client needs attention", trainerDashboardClientSummary(1))
        assertEquals("0 unread messages", trainerDashboardMessageDescription(-1))
        assertEquals("1 unread message", trainerDashboardMessageDescription(1))
    }

    @Test
    fun passwordVisibilityHelpersSelectVisualTransformationLabelsAndDescriptions() {
        val hiddenTransformation = authPasswordVisualTransformation(passwordVisible = false)
        assertTrue(hiddenTransformation is androidx.compose.ui.text.input.PasswordVisualTransformation)
        assertEquals("Show", authPasswordToggleLabel(passwordVisible = false))
        assertEquals("Show password", authPasswordToggleDescription(passwordVisible = false))

        val visibleTransformation = authPasswordVisualTransformation(passwordVisible = true)
        assertEquals(androidx.compose.ui.text.input.VisualTransformation.None, visibleTransformation)
        assertEquals("Hide", authPasswordToggleLabel(passwordVisible = true))
        assertEquals("Hide password", authPasswordToggleDescription(passwordVisible = true))
    }

    @Test
    fun passwordVisibilityTogglePreservesPasswordValue() {
        val initialModel = CatalogFixtures.auth.copy(
            password = "SecretPassword123!",
            passwordVisible = false,
        )
        var toggledModel = initialModel
        var toggleCount = 0

        val onToggle: () -> Unit = {
            toggleCount += 1
            toggledModel = toggledModel.copy(passwordVisible = !toggledModel.passwordVisible)
        }

        onToggle()
        assertEquals(1, toggleCount)
        assertTrue(toggledModel.passwordVisible)
        assertEquals("SecretPassword123!", toggledModel.password)

        onToggle()
        assertEquals(2, toggleCount)
        assertFalse(toggledModel.passwordVisible)
        assertEquals("SecretPassword123!", toggledModel.password)
    }

    @Test
    fun trainerDashboardSectionActionCallbacksDispatchOnce() {
        var viewScheduleCount = 0
        var reviewActivitiesCount = 0

        val onViewSchedule: () -> Unit = { viewScheduleCount += 1 }
        val onReviewActivities: () -> Unit = { reviewActivitiesCount += 1 }

        onViewSchedule()
        onReviewActivities()

        assertEquals(1, viewScheduleCount)
        assertEquals(1, reviewActivitiesCount)
    }

    @Test
    fun activeWorkoutNoteCallbackDispatchesOnce() {
        var noteClickCount = 0
        val onNoteClick: () -> Unit = { noteClickCount += 1 }

        onNoteClick()

        assertEquals(1, noteClickCount)
    }

    @Test
    fun trainerClientDetailsTabSelectionDispatchesExactTab() {
        val selectedTabs = mutableListOf<TrainerClientDetailsTab>()
        val onTabClick: (TrainerClientDetailsTab) -> Unit = { tab -> selectedTabs.add(tab) }

        TrainerClientDetailsTab.entries.forEach { tab ->
            onTabClick(tab)
        }

        assertEquals(
            listOf(
                TrainerClientDetailsTab.Overview,
                TrainerClientDetailsTab.Program,
                TrainerClientDetailsTab.History,
            ),
            selectedTabs,
        )
    }

    @Test
    fun rowClickCallbackDispatchesExactlyOnceWhenSupplied() {
        var loggedSetClickCount = 0
        val onLoggedSetClick: () -> Unit = { loggedSetClickCount += 1 }

        onLoggedSetClick()
        assertEquals(1, loggedSetClickCount)
    }

    private fun clientProgress(completed: Int, total: Int): ClientWeeklyProgressPresentation =
        clientWeeklyProgress(
            CatalogFixtures.clientHome.copy(
                weekProgress = CountProgressFixtureUiModel(completed = completed, total = total),
            ),
        )

    private fun trainerProgress(completed: Int, total: Int): TrainerProgramProgressPresentation =
        trainerProgramProgress(
            CatalogFixtures.trainerClientDetails.copy(
                programProgress = CountProgressFixtureUiModel(completed = completed, total = total),
            ),
        )

    private fun setsWithCurrent(
        vararg numbers: Int,
        current: Set<Int>,
    ): List<LoggedSetFixtureUiModel> = numbers.map { number ->
        LoggedSetFixtureUiModel(
            number = number,
            weight = "72.5 kg",
            reps = "8 reps",
            status = if (number in current) LoggedSetStatus.Current else LoggedSetStatus.Completed,
        )
    }
}

private fun navigationLayout(
    availableWidthDp: Int,
    fontScale: Float,
    renderedItemWidthsAtOneX: List<Int>,
): TillFailureBottomNavigationLayout =
    tillFailureBottomNavigationLayout(
        availableWidthPx = availableWidthDp,
        requiredItemWidthsPx = renderedItemWidthsAtOneX.map { maxOf(48, ceil(it * fontScale).toInt()) },
        outerHorizontalPaddingPx = 8,
    )
