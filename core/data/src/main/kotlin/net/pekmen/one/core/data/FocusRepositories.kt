package net.pekmen.one.core.data

import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.model.ActiveSessionSnapshot
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.WeeklyFocusSummary
import java.time.LocalDate

data class CompletedSessionItem(
    val session: FocusSession,
    val profile: FocusProfile
)

data class RetroactiveSessionRequest(
    val profileId: String,
    val startedAtWallMs: Long,
    val focusMinutes: Int,
    val breakMinutes: Int,
    val rating: Int?,
    val note: String?
)

data class SaveProfileRequest(
    val existingId: String? = null,
    val customName: String,
    val colorHex: Long,
    val defaultMode: FocusSessionMode,
    val workMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val cyclesBeforeLongBreak: Int
)

interface FocusProfileRepository {
    val profiles: Flow<List<FocusProfile>>
    suspend fun ensureSeeded()
    suspend fun saveProfile(request: SaveProfileRequest): Result<FocusProfile>
}

interface FocusSessionRepository {
    val activeSession: Flow<ActiveSessionSnapshot?>

    suspend fun startSession(
        profileId: String,
        mode: FocusSessionMode,
        customDurationMinutes: Int? = null
    ): Result<FocusSession>

    suspend fun pauseActiveSession()
    suspend fun resumeActiveSession()
    suspend fun startAwaitingNextPhase()
    suspend fun skipBreakAndStartFocus()
    suspend fun freezeActiveSessionForWrapUp(): ActiveSessionSnapshot?

    suspend fun completeActiveSession(
        trimmedFocusSeconds: Long? = null,
        rating: Int? = null,
        note: String? = null
    ): FocusSession?

    suspend fun discardActiveSession()
    suspend fun checkpointActiveSession()
    suspend fun reconcileActiveSessionAfterBootOrWake()
    suspend fun handlePhaseTimerExpired(sessionId: String, segmentId: String)
}

interface FocusHistoryRepository {
    fun observeDailySummary(date: LocalDate): Flow<DailyFocusSummary>
    fun observeWeeklySummary(anchorDate: LocalDate): Flow<WeeklyFocusSummary>
    fun observeRecentCompletedSessions(limit: Int = 60): Flow<List<CompletedSessionItem>>

    suspend fun updateCompletedSession(
        sessionId: String,
        newFocusSeconds: Long,
        newBreakSeconds: Long,
        profileId: String,
        rating: Int?,
        note: String?
    ): Result<Unit>

    suspend fun logRetroactiveSession(request: RetroactiveSessionRequest): Result<FocusSession>
    suspend fun softDeleteSession(sessionId: String)
}
