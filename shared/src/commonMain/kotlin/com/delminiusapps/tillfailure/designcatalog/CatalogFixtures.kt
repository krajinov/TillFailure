package com.delminiusapps.tillfailure.designcatalog

import androidx.compose.runtime.Immutable
import com.delminiusapps.tillfailure.core.designsystem.LoggedSetStatus

enum class FixtureContentState { Ready, Loading, Empty, Error }

@Immutable
data class AuthFixtureUiModel(
    val firstName: String,
    val programName: String,
    val email: String,
    val password: String,
    val emailSupportingText: String? = null,
    val emailHasError: Boolean = false,
    val isSubmitting: Boolean = false,
    val passwordVisible: Boolean = false,
)

@Immutable
data class CountProgressFixtureUiModel(
    val completed: Int,
    val total: Int,
)

internal data class NormalizedCountProgress(
    val completed: Int,
    val total: Int,
    val fraction: Float,
)

internal fun CountProgressFixtureUiModel.normalized(): NormalizedCountProgress {
    val normalizedTotal = total.coerceAtLeast(0)
    val normalizedCompleted = completed.coerceIn(0, normalizedTotal)
    return NormalizedCountProgress(
        completed = normalizedCompleted,
        total = normalizedTotal,
        fraction = if (normalizedTotal == 0) 0f else normalizedCompleted.toFloat() / normalizedTotal,
    )
}

enum class ClientNavigationDestination { Home, Workouts, Schedule, Progress, Messages }

@Immutable
data class AppointmentDateFixtureUiModel(
    val fullDate: String,
    val weekday: String,
    val dayNumber: String,
)

@Immutable
data class ClientHomeFixtureUiModel(
    val firstName: String,
    val weekProgress: CountProgressFixtureUiModel,
    val selectedNavigationDestination: ClientNavigationDestination,
    val nextWorkoutSchedule: String,
    val workoutTitle: String,
    val workoutSummary: String,
    val appointmentDate: AppointmentDateFixtureUiModel,
    val trainerName: String,
    val appointmentSummary: String,
    val trainerNoteTime: String,
    val trainerNote: String,
    val bodyWeight: String,
    val bodyWeightTrend: String,
    val consistency: String,
    val consistencyPeriod: String,
    val contentState: FixtureContentState = FixtureContentState.Ready,
)

@Immutable
data class LoggedSetFixtureUiModel(
    val number: Int,
    val weight: String,
    val reps: String,
    val status: LoggedSetStatus,
)

@Immutable
data class ActiveWorkoutFixtureUiModel(
    val title: String,
    val exerciseProgress: CountProgressFixtureUiModel,
    val elapsedDuration: String,
    val exerciseName: String,
    val exerciseTargetReps: String,
    val restDuration: String,
    val previousWeight: String,
    val targetWeight: String,
    val timer: String,
    val sets: List<LoggedSetFixtureUiModel>,
)

@Immutable
data class AppointmentFixtureUiModel(
    val time: String,
    val client: String,
    val summary: String,
    val status: String,
)

@Immutable
data class ActivityFixtureUiModel(
    val title: String,
    val detail: String,
    val glyph: String? = null,
)

enum class TrainerNavigationDestination { Dashboard, Clients, Programs, Schedule, Messages }

enum class TrainerDashboardSection { Active, Messages, Appointments }

@Immutable
data class TrainerDashboardFixtureUiModel(
    val trainerName: String,
    val date: String,
    val activeClients: Int,
    val messages: Int,
    val selectedNavigationDestination: TrainerNavigationDestination,
    val selectedSection: TrainerDashboardSection,
    val appointments: List<AppointmentFixtureUiModel>,
    val activities: List<ActivityFixtureUiModel>,
    val contentState: FixtureContentState = FixtureContentState.Ready,
)

enum class TrainerClientDetailsTab { Overview, Program, History }

val TrainerClientDetailsTab.label: String
    get() = when (this) {
        TrainerClientDetailsTab.Overview -> "Overview"
        TrainerClientDetailsTab.Program -> "Program"
        TrainerClientDetailsTab.History -> "History"
    }

@Immutable
data class ClientRestrictionFixtureUiModel(
    val title: String,
    val message: String,
)

@Immutable
data class RecentActivityFixtureUiModel(
    val glyph: String,
    val title: String,
    val summary: String,
)

@Immutable
data class TrainerClientDetailsFixtureUiModel(
    val name: String,
    val since: String,
    val selectedTab: TrainerClientDetailsTab,
    val adherence: String,
    val lastWorkout: String,
    val nextSession: String,
    val programName: String,
    val programWeek: CountProgressFixtureUiModel,
    val weeklyWorkoutTarget: Int,
    val programProgress: CountProgressFixtureUiModel,
    val restriction: ClientRestrictionFixtureUiModel,
    val trainerNote: String,
    val recentActivity: RecentActivityFixtureUiModel,
)

internal fun initialsFromName(name: String): String = name
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)
    .take(2)
    .map { part -> part.first() }
    .joinToString("")
    .uppercase()

object CatalogFixtures {
    val auth = AuthFixtureUiModel(
        firstName = "Alex",
        programName = "Strength Foundation",
        email = "alex.morgan@example.com",
        password = "strongpassword",
    )

    val clientHome = ClientHomeFixtureUiModel(
        firstName = "Alex",
        weekProgress = CountProgressFixtureUiModel(completed = 3, total = 4),
        selectedNavigationDestination = ClientNavigationDestination.Home,
        nextWorkoutSchedule = "TODAY, 14:00",
        workoutTitle = "Upper Body Strength",
        workoutSummary = "7 exercises · 55 min",
        appointmentDate = AppointmentDateFixtureUiModel(
            fullDate = "Tuesday, 18 June",
            weekday = "TUE",
            dayNumber = "18",
        ),
        trainerName = "Maya",
        appointmentSummary = "10:00–11:00 · Forge Studio",
        trainerNoteTime = "10 min ago",
        trainerNote = "Great work on the deadlifts — we’ll add 2.5 kg next session.",
        bodyWeight = "76.8 kg",
        bodyWeightTrend = "−0.6 kg month",
        consistency = "86%",
        consistencyPeriod = "12-week average",
    )

    val activeWorkout = ActiveWorkoutFixtureUiModel(
        title = "Upper Body Strength",
        exerciseProgress = CountProgressFixtureUiModel(completed = 3, total = 7),
        elapsedDuration = "24:18",
        exerciseName = "Barbell Bench Press",
        exerciseTargetReps = "6–8 reps",
        restDuration = "120 sec",
        previousWeight = "70 kg × 8",
        targetWeight = "72.5 kg × 8",
        timer = "01:24 remaining",
        sets = listOf(
            LoggedSetFixtureUiModel(1, "72.5 kg", "8 reps", LoggedSetStatus.Completed),
            LoggedSetFixtureUiModel(2, "72.5 kg", "9 reps", LoggedSetStatus.Edited),
            LoggedSetFixtureUiModel(3, "72.5 kg", "8 reps", LoggedSetStatus.Current),
            LoggedSetFixtureUiModel(4, "72.5 kg", "6–8 reps", LoggedSetStatus.Pending),
        ),
    )

    val trainerDashboard = TrainerDashboardFixtureUiModel(
        trainerName = "Maya",
        date = "Tuesday, 18 June",
        activeClients = 24,
        messages = 3,
        selectedNavigationDestination = TrainerNavigationDestination.Dashboard,
        selectedSection = TrainerDashboardSection.Appointments,
        appointments = listOf(
            AppointmentFixtureUiModel("10:00", "Alex Morgan", "Upper strength", "NEXT"),
            AppointmentFixtureUiModel("12:30", "Priya Shah", "Progress review", "CONFIRMED"),
            AppointmentFixtureUiModel("17:00", "Jon Bell", "Lower body", "CONFIRMED"),
        ),
        activities = listOf(
            ActivityFixtureUiModel("Lena Santos", "Program review is due"),
            ActivityFixtureUiModel("Jon Bell", "Low adherence · 2 days"),
            ActivityFixtureUiModel("Alex completed Upper Body Strength", "12 sets · 1 personal record", glyph = "✓"),
        ),
    )

    val trainerClientDetails = TrainerClientDetailsFixtureUiModel(
        name = "Alex Morgan",
        since = "Active client · Since Jan 2024",
        selectedTab = TrainerClientDetailsTab.Overview,
        adherence = "86%",
        lastWorkout = "Today",
        nextSession = "Tue 10:00",
        programName = "Strength Foundation",
        programWeek = CountProgressFixtureUiModel(completed = 4, total = 8),
        weeklyWorkoutTarget = 3,
        programProgress = CountProgressFixtureUiModel(completed = 12, total = 32),
        restriction = ClientRestrictionFixtureUiModel(
            title = "Right shoulder sensitivity",
            message = "Avoid close-grip pressing; adjust range if needed.",
        ),
        trainerNote = "Confidently increasing load on lower exercises.",
        recentActivity = RecentActivityFixtureUiModel(
            glyph = "✓",
            title = "Upper Body Strength completed",
            summary = "Today, 08:42 · 12 sets · 1 PR",
        ),
    )
}
