package net.pekmen.one.feature.focus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.RetroactiveSessionRequest
import net.pekmen.one.core.data.SaveProfileRequest
import net.pekmen.one.core.data.SystemDiagnostics
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.WeeklyFocusSummary
import java.time.LocalDate
import java.time.ZoneId

internal class FakeProfileRepo : FocusProfileRepository {
    override val profiles: Flow<List<FocusProfile>> = flowOf(BuiltInProfiles.defaults())
    override suspend fun ensureSeeded() = Unit
    override suspend fun saveProfile(request: SaveProfileRequest): Result<FocusProfile> =
        Result.success(BuiltInProfiles.defaults().first())
}

internal class FakeHistoryRepo : FocusHistoryRepository {
    override fun observeDailySummary(date: LocalDate): Flow<DailyFocusSummary> =
        flowOf(DailyFocusSummary(date, 0L, 0L, 0, null))

    override fun observeWeeklySummary(anchorDate: LocalDate): Flow<WeeklyFocusSummary> =
        flowOf(WeeklyFocusSummary(anchorDate.minusDays(6), anchorDate, 0L, 0L, 0, emptyList(), emptyList()))

    override fun observeRecentCompletedSessions(limit: Int): Flow<List<CompletedSessionItem>> =
        flowOf(emptyList())

    override suspend fun updateCompletedSession(
        sessionId: String,
        newFocusSeconds: Long,
        newBreakSeconds: Long,
        profileId: String,
        rating: Int?,
        note: String?
    ): Result<Unit> = Result.success(Unit)

    override suspend fun logRetroactiveSession(request: RetroactiveSessionRequest): Result<FocusSession> =
        Result.failure(UnsupportedOperationException())

    override suspend fun softDeleteSession(sessionId: String) = Unit
}

internal class FakeDiagnosticsRepo : SystemDiagnosticsRepository {
    override fun refreshDiagnostics(): SystemDiagnostics = SystemDiagnostics(true, true)
    override fun currentWallTimeMs(): Long = 1_760_000_000_000L
    override fun currentElapsedRealtimeMs(): Long = 10_000L
    override fun currentBootCount(): Int = 1
    override fun currentZoneId(): ZoneId = ZoneId.of("Europe/Istanbul")
}
