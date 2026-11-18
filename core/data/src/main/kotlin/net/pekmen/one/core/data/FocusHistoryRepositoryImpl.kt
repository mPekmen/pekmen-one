package net.pekmen.one.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7
import net.pekmen.one.core.model.WeeklyFocusSummary
import java.time.LocalDate

class FocusHistoryRepositoryImpl(
    private val database: PekmenDatabase,
    private val profileRepository: FocusProfileRepository,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val timeProvider: TimeProvider
) : FocusHistoryRepository {

    private val sessionDao = database.focusSessionDao()
    private val profileDao = database.focusProfileDao()

    override fun observeDailySummary(date: LocalDate): Flow<DailyFocusSummary> {
        val dateIso = date.toString()
        return sessionDao.observeCompletedSessionsInDateRange(dateIso, dateIso)
            .map { rows ->
                val items = rows.map { CompletedSessionItem(it.session.toDomain(), it.profile.toDomain()) }
                FocusSummaryAggregator.summarizeDay(date, items)
            }
    }

    override fun observeWeeklySummary(anchorDate: LocalDate): Flow<WeeklyFocusSummary> {
        val startDate = anchorDate.minusDays(6L)
        return sessionDao.observeCompletedSessionsInDateRange(startDate.toString(), anchorDate.toString())
            .map { rows ->
                val items = rows.map { CompletedSessionItem(it.session.toDomain(), it.profile.toDomain()) }
                FocusSummaryAggregator.summarizeWeek(anchorDate, items)
            }
    }

    override fun observeRecentCompletedSessions(limit: Int): Flow<List<CompletedSessionItem>> {
        return sessionDao.observeRecentCompletedSessions(limit).map { rows ->
            rows.map { CompletedSessionItem(it.session.toDomain(), it.profile.toDomain()) }
        }
    }

    override suspend fun updateCompletedSession(
        sessionId: String,
        newFocusSeconds: Long,
        newBreakSeconds: Long,
        profileId: String,
        rating: Int?,
        note: String?
    ): Result<Unit> = runCatching {
        require(newFocusSeconds in 0L..MAX_SESSION_SECONDS) {
            "Focus duration out of bounds: $newFocusSeconds"
        }
        require(newBreakSeconds in 0L..MAX_BREAK_SECONDS) {
            "Break duration out of bounds: $newBreakSeconds"
        }
        require(newFocusSeconds > 0L || newBreakSeconds > 0L) {
            "Session duration cannot be zero"
        }
        val validatedRating = rating?.also {
            require(it in 1..5) { "Focus rating must be 1..5" }
        }
        profileRepository.ensureSeeded()
        val profile = requireNotNull(profileDao.getById(profileId)) { "Unknown profile: $profileId" }
        val now = timeProvider.wallTimeMs()

        database.withTransaction {
            val existing = requireNotNull(sessionDao.getSessionById(sessionId)) {
                "Session not found: $sessionId"
            }
            require(existing.status == FocusSessionStatus.COMPLETED.name) {
                "Only completed sessions can be edited here"
            }
            val durationChanged = existing.actualFocusSec != newFocusSeconds ||
                existing.actualBreakSec != newBreakSeconds
            val updated = existing.copy(
                profileId = profile.id,
                actualFocusSec = newFocusSeconds,
                actualBreakSec = newBreakSeconds,
                wasManuallyAdjusted = existing.wasManuallyAdjusted || durationChanged,
                focusRating = validatedRating,
                reflectionNote = note?.trim()?.take(MAX_NOTE_LENGTH)?.ifEmpty { null },
                sync = existing.sync.copy(
                    clientUpdatedAtMs = now,
                    version = existing.sync.version + 1L
                )
            )
            sessionDao.updateSession(updated)
        }
    }

    override suspend fun logRetroactiveSession(request: RetroactiveSessionRequest): Result<FocusSession> =
        runCatching {
            val now = timeProvider.wallTimeMs()
            require(request.startedAtWallMs in 1L..now) {
                "Cannot log retroactive sessions in the future"
            }
            require(request.focusMinutes in 1..MAX_RETROACTIVE_FOCUS_MINUTES) {
                "Focus minutes must be between 1 and $MAX_RETROACTIVE_FOCUS_MINUTES"
            }
            require(request.breakMinutes in 0..MAX_RETROACTIVE_BREAK_MINUTES) {
                "Break minutes must be between 0 and $MAX_RETROACTIVE_BREAK_MINUTES"
            }
            val validatedRating = request.rating?.also {
                require(it in 1..5) { "Focus rating must be 1..5" }
            }

            profileRepository.ensureSeeded()
            val profile = requireNotNull(profileDao.getById(request.profileId)) {
                "Unknown profile: ${request.profileId}"
            }
            val prefs = preferencesDataStore.preferences.first()
            val effectiveDay = EffectiveDayCalculator.effectiveDateFor(
                epochMs = request.startedAtWallMs,
                zoneId = timeProvider.zoneId(),
                rolloverHour = prefs.dayRolloverHour
            )
            val totalMillis = (request.focusMinutes + request.breakMinutes) * 60_000L
            val endWall = (request.startedAtWallMs + totalMillis).coerceAtMost(now)

            val entity = FocusSessionEntity(
                id = UuidV7.generate(now),
                profileId = profile.id,
                sessionMode = FocusSessionMode.CUSTOM.name,
                status = FocusSessionStatus.COMPLETED.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = request.startedAtWallMs,
                endedAtWallMs = endWall,
                effectiveDayDate = effectiveDay.toString(),
                plannedFocusSec = request.focusMinutes * 60L,
                actualFocusSec = request.focusMinutes * 60L,
                actualBreakSec = request.breakMinutes * 60L,
                pauseCount = 0,
                isManualEntry = true,
                wasManuallyAdjusted = false,
                interruptedByReboot = false,
                focusRating = validatedRating,
                reflectionNote = request.note?.trim()?.take(MAX_NOTE_LENGTH)?.ifEmpty { null },
                sync = SyncMetadataEntity(createdAtMs = now, clientUpdatedAtMs = now)
            )
            sessionDao.insertSession(entity)
            entity.toDomain()
        }

    override suspend fun softDeleteSession(sessionId: String) {
        val now = timeProvider.wallTimeMs()
        sessionDao.softDeleteSession(sessionId = sessionId, deletedAtMs = now, updatedAtMs = now)
    }

    private companion object {
        const val MAX_SESSION_SECONDS = 57_600L // 16 hours
        const val MAX_BREAK_SECONDS = 28_800L // 8 hours
        const val MAX_RETROACTIVE_FOCUS_MINUTES = 960
        const val MAX_RETROACTIVE_BREAK_MINUTES = 480
        const val MAX_NOTE_LENGTH = 500
    }
}
