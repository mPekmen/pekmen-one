package net.pekmen.one.core.model

import java.time.LocalDate

enum class ThemePreference {
    SYSTEM,
    DARK,
    LIGHT
}

enum class BuiltInProfileSlug {
    STUDY,
    WORK,
    SLEEP,
    PERSONAL,
    CUSTOM
}

enum class FocusSessionMode {
    POMODORO,
    STOPWATCH,
    CUSTOM
}

enum class FocusPhase {
    FOCUS,
    SHORT_BREAK,
    LONG_BREAK
}

enum class FocusSessionStatus {
    RUNNING,
    PAUSED,
    AWAITING_NEXT_PHASE,
    COMPLETED,
    ABANDONED
}

enum class SyncStatus {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    SYNCED,
    SYNC_ERROR
}

data class UserPreferences(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val dayRolloverHour: Int = EffectiveDayCalculator.DEFAULT_ROLLOVER_HOUR,
    val selectedProfileId: String? = null,
    val autoStartBreaks: Boolean = false,
    val autoStartNextFocus: Boolean = false,
    val defaultCustomMinutes: Int = 45,
    val activeOwnerUserId: String? = null,
    val zenIntegrationEnabled: Boolean = false,
    val routineKeywordEnabled: Boolean = false
)

data class FocusProfile(
    val id: String,
    val builtInSlug: BuiltInProfileSlug,
    val customName: String?,
    val colorHex: Long,
    val iconKey: String,
    val defaultMode: FocusSessionMode,
    val workMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val cyclesBeforeLongBreak: Int,
    val dndEnabled: Boolean,
    val sortOrder: Int,
    val isArchived: Boolean = false
)

data class FocusSessionSegment(
    val id: String,
    val sessionId: String,
    val segmentIndex: Int,
    val phase: FocusPhase,
    val startedAtWallMs: Long,
    val startedAtElapsedMs: Long,
    val bootCount: Int,
    val lastCheckpointedDurationMs: Long,
    val plannedDurationMs: Long?,
    val endedAtWallMs: Long?,
    val durationMs: Long?
)

data class FocusSession(
    val id: String,
    val profileId: String,
    val subjectId: String? = null,
    val bookId: String? = null,
    val topicId: String? = null,
    val taskId: String? = null,
    val bookStartPage: Int? = null,
    val bookEndPage: Int? = null,
    val sessionMode: FocusSessionMode,
    val status: FocusSessionStatus,
    val currentPhase: FocusPhase,
    val awaitingNextPhase: FocusPhase? = null,
    val completedFocusCycles: Int,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long? = null,
    val effectiveDayDate: LocalDate,
    val plannedFocusSec: Long? = null,
    val actualFocusSec: Long,
    val actualBreakSec: Long,
    val pauseCount: Int,
    val isManualEntry: Boolean = false,
    val wasManuallyAdjusted: Boolean = false,
    val interruptedByReboot: Boolean = false,
    val focusRating: Int? = null,
    val reflectionNote: String? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)

data class ActiveSessionSnapshot(
    val session: FocusSession,
    val profile: FocusProfile,
    val segments: List<FocusSessionSegment>,
    val openSegment: FocusSessionSegment?
)

data class ProfileFocusBreakdown(
    val profile: FocusProfile,
    val focusSeconds: Long,
    val breakSeconds: Long,
    val sessionCount: Int
)

data class DailyFocusSummary(
    val date: LocalDate,
    val netFocusSeconds: Long,
    val breakSeconds: Long,
    val completedSessionCount: Int,
    val averageFocusRating: Double?
)

data class WeeklyFocusSummary(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalFocusSeconds: Long,
    val totalBreakSeconds: Long,
    val completedSessionCount: Int,
    val dailyBreakdown: List<DailyFocusSummary>,
    val profileBreakdown: List<ProfileFocusBreakdown>
)

/**
 * Deterministic built-in profile definitions seeded locally on first launch.
 */
object BuiltInProfiles {
    const val STUDY_ID = "01900000-0000-7000-8000-000000000001"
    const val WORK_ID = "01900000-0000-7000-8000-000000000002"
    const val SLEEP_ID = "01900000-0000-7000-8000-000000000003"
    const val PERSONAL_ID = "01900000-0000-7000-8000-000000000004"

    fun defaults(): List<FocusProfile> = listOf(
        FocusProfile(
            id = STUDY_ID,
            builtInSlug = BuiltInProfileSlug.STUDY,
            customName = null,
            colorHex = 0xFF8B7CF6,
            iconKey = "study",
            defaultMode = FocusSessionMode.POMODORO,
            workMinutes = 25,
            shortBreakMinutes = 5,
            longBreakMinutes = 15,
            cyclesBeforeLongBreak = 4,
            dndEnabled = true,
            sortOrder = 0
        ),
        FocusProfile(
            id = WORK_ID,
            builtInSlug = BuiltInProfileSlug.WORK,
            customName = null,
            colorHex = 0xFF60A5FA,
            iconKey = "work",
            defaultMode = FocusSessionMode.POMODORO,
            workMinutes = 50,
            shortBreakMinutes = 10,
            longBreakMinutes = 20,
            cyclesBeforeLongBreak = 3,
            dndEnabled = true,
            sortOrder = 1
        ),
        FocusProfile(
            id = SLEEP_ID,
            builtInSlug = BuiltInProfileSlug.SLEEP,
            customName = null,
            colorHex = 0xFF6EE7B7,
            iconKey = "sleep",
            defaultMode = FocusSessionMode.CUSTOM,
            workMinutes = 30,
            shortBreakMinutes = 5,
            longBreakMinutes = 10,
            cyclesBeforeLongBreak = 2,
            dndEnabled = true,
            sortOrder = 2
        ),
        FocusProfile(
            id = PERSONAL_ID,
            builtInSlug = BuiltInProfileSlug.PERSONAL,
            customName = null,
            colorHex = 0xFFF472B6,
            iconKey = "personal",
            defaultMode = FocusSessionMode.STOPWATCH,
            workMinutes = 30,
            shortBreakMinutes = 5,
            longBreakMinutes = 15,
            cyclesBeforeLongBreak = 4,
            dndEnabled = false,
            sortOrder = 3
        )
    )
}
