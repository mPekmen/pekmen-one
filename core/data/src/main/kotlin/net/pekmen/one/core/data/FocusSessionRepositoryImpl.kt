package net.pekmen.one.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.database.relation.FocusSessionWithDetails
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.ActiveSessionSnapshot
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.core.model.UuidV7
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.FocusNotificationGateway
import net.pekmen.one.core.platform.ProfileNameProvider

class FocusSessionRepositoryImpl(
    private val database: PekmenDatabase,
    private val profileRepository: FocusProfileRepository,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val timeProvider: TimeProvider,
    private val notificationGateway: FocusNotificationGateway,
    private val alarmGateway: FocusAlarmGateway,
    private val profileNameProvider: ProfileNameProvider
) : FocusSessionRepository {

    private val sessionDao = database.focusSessionDao()
    private val profileDao = database.focusProfileDao()
    private val stateMutex = Mutex()

    override val activeSession: Flow<ActiveSessionSnapshot?> = sessionDao.observeActiveSession()
        .map { it?.toActiveSnapshot() }

    override suspend fun startSession(
        profileId: String,
        mode: FocusSessionMode,
        customDurationMinutes: Int?
    ): Result<FocusSession> = stateMutex.withLock {
        runCatching {
            profileRepository.ensureSeeded()
            val prefs = preferencesDataStore.preferences.first()
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            val effectiveDay = EffectiveDayCalculator.effectiveDateFor(
                epochMs = nowWall,
                zoneId = timeProvider.zoneId(),
                rolloverHour = prefs.dayRolloverHour
            )

            var created: Triple<FocusSessionEntity, FocusProfileEntity, FocusSessionSegmentEntity>? = null
            database.withTransaction {
                check(sessionDao.countActiveSessions() == 0) { "An active focus session already exists" }
                val profile = requireNotNull(profileDao.getById(profileId)) { "Unknown profile: $profileId" }
                val plannedSec = when (mode) {
                    FocusSessionMode.POMODORO -> profile.workMinutes * 60L
                    FocusSessionMode.CUSTOM -> {
                        val customMin = customDurationMinutes ?: prefs.defaultCustomMinutes
                        customMin.coerceIn(1, 480) * 60L
                    }
                    FocusSessionMode.STOPWATCH -> null
                }
                val sessionId = UuidV7.generate(nowWall)
                val sessionEntity = FocusSessionEntity(
                    id = sessionId,
                    profileId = profile.id,
                    sessionMode = mode.name,
                    status = FocusSessionStatus.RUNNING.name,
                    currentPhase = FocusPhase.FOCUS.name,
                    completedFocusCycles = 0,
                    startedAtWallMs = nowWall,
                    effectiveDayDate = effectiveDay.toString(),
                    plannedFocusSec = plannedSec,
                    actualFocusSec = 0L,
                    actualBreakSec = 0L,
                    pauseCount = 0,
                    sync = SyncMetadataEntity(createdAtMs = nowWall, clientUpdatedAtMs = nowWall)
                )
                val segmentEntity = FocusSessionSegmentEntity(
                    id = UuidV7.generate(nowWall + 1L),
                    sessionId = sessionId,
                    segmentIndex = 0,
                    phase = FocusPhase.FOCUS.name,
                    startedAtWallMs = nowWall,
                    startedAtElapsedMs = nowElapsed,
                    bootCount = bootCount,
                    lastCheckpointedDurationMs = 0L,
                    plannedDurationMs = plannedSec?.times(1000L)
                )
                sessionDao.insertSession(sessionEntity)
                sessionDao.insertSegment(segmentEntity)
                created = Triple(sessionEntity, profile, segmentEntity)
            }

            val (session, profile, segment) = requireNotNull(created)
            FocusSessionMathHelper.syncRunningPlatformState(
                running = TransitionEffect.Running(
                    session = session,
                    profile = profile,
                    openSegment = segment,
                    totalFocusMs = 0L
                ),
                nowWall = nowWall,
                nowElapsed = nowElapsed,
                gateways = PlatformGateways(notificationGateway, alarmGateway, profileNameProvider)
            )
            session.toDomain()
        }
    }

    override suspend fun pauseActiveSession() {
        stateMutex.withLock {
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            var pausedEffect: TransitionEffect? = null

            database.withTransaction {
                val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
                if (active.session.status != FocusSessionStatus.RUNNING.name) return@withTransaction
                val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null }
                val updatedSegments = closeOpenSegmentIfPresent(active, openSeg, nowWall, nowElapsed, bootCount)
                val totals = FocusSessionMathHelper.computeSegmentTotalsMs(updatedSegments)
                val updatedSession = active.session.copy(
                    status = FocusSessionStatus.PAUSED.name,
                    actualFocusSec = totals.first / 1000L,
                    actualBreakSec = totals.second / 1000L,
                    pauseCount = active.session.pauseCount + 1,
                    sync = active.session.sync.copy(
                        clientUpdatedAtMs = nowWall,
                        version = active.session.sync.version + 1L
                    )
                )
                sessionDao.updateSession(updatedSession)
                pausedEffect = TransitionEffect.Paused(updatedSession, active.profile, interruptedByReboot = false)
            }

            dispatchEffect(pausedEffect, nowWall, nowElapsed)
        }
    }

    override suspend fun resumeActiveSession() {
        stateMutex.withLock {
            val prefs = preferencesDataStore.preferences.first()
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            var outcome: TransitionEffect? = null

            database.withTransaction {
                val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
                if (active.session.status != FocusSessionStatus.PAUSED.name) return@withTransaction
                val mode = FocusSessionMode.valueOf(active.session.sessionMode)
                val currentPhase = FocusPhase.valueOf(active.session.currentPhase)
                val remainingMs = if (mode == FocusSessionMode.STOPWATCH) {
                    null
                } else {
                    FocusSessionMathHelper.computeRemainingCountdownMs(active, currentPhase)
                }

                if (remainingMs != null && remainingMs < 1000L) {
                    outcome = advancePhaseLocked(active, prefs, nowWall, nowElapsed, bootCount)
                    return@withTransaction
                }

                val newSegment = FocusSessionSegmentEntity(
                    id = UuidV7.generate(nowWall),
                    sessionId = active.session.id,
                    segmentIndex = active.segments.size,
                    phase = currentPhase.name,
                    startedAtWallMs = nowWall,
                    startedAtElapsedMs = nowElapsed,
                    bootCount = bootCount,
                    lastCheckpointedDurationMs = 0L,
                    plannedDurationMs = remainingMs
                )
                sessionDao.insertSegment(newSegment)
                val updatedSession = active.session.copy(
                    status = FocusSessionStatus.RUNNING.name,
                    interruptedByReboot = false,
                    sync = active.session.sync.copy(
                        clientUpdatedAtMs = nowWall,
                        version = active.session.sync.version + 1L
                    )
                )
                sessionDao.updateSession(updatedSession)
                val totals = FocusSessionMathHelper.computeSegmentTotalsMs(active.segments)
                outcome = TransitionEffect.Running(updatedSession, active.profile, newSegment, totals.first)
            }

            dispatchEffect(outcome, nowWall, nowElapsed)
        }
    }

    override suspend fun startAwaitingNextPhase() {
        stateMutex.withLock {
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            var outcome: TransitionEffect? = null

            database.withTransaction {
                val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
                if (active.session.status != FocusSessionStatus.AWAITING_NEXT_PHASE.name) return@withTransaction
                val nextPhase = active.session.awaitingNextPhase?.let(FocusPhase::valueOf) ?: return@withTransaction
                outcome = openNewPhaseLocked(active, nextPhase, nowWall, nowElapsed, bootCount)
            }

            dispatchEffect(outcome, nowWall, nowElapsed)
        }
    }

    override suspend fun skipBreakAndStartFocus() {
        stateMutex.withLock {
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            var outcome: TransitionEffect? = null

            database.withTransaction {
                val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
                val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null }
                val updatedSegments = closeOpenSegmentIfPresent(active, openSeg, nowWall, nowElapsed, bootCount)
                val totals = FocusSessionMathHelper.computeSegmentTotalsMs(updatedSegments)
                val updatedActive = active.copy(
                    session = active.session.copy(
                        actualFocusSec = totals.first / 1000L,
                        actualBreakSec = totals.second / 1000L
                    ),
                    segments = updatedSegments
                )
                outcome = openNewPhaseLocked(updatedActive, FocusPhase.FOCUS, nowWall, nowElapsed, bootCount)
            }

            dispatchEffect(outcome, nowWall, nowElapsed)
        }
    }

    override suspend fun freezeActiveSessionForWrapUp(): ActiveSessionSnapshot? {
        pauseActiveSession()
        return sessionDao.getActiveSessionOnce()?.toActiveSnapshot()
    }

    override suspend fun completeActiveSession(
        trimmedFocusSeconds: Long?,
        rating: Int?,
        note: String?
    ): FocusSession? = stateMutex.withLock {
        val nowWall = timeProvider.wallTimeMs()
        val nowElapsed = timeProvider.elapsedRealtimeMs()
        val bootCount = timeProvider.bootCount()
        var completed: FocusSessionEntity? = null

        database.withTransaction {
            val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
            val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null }
            val updatedSegments = closeOpenSegmentIfPresent(active, openSeg, nowWall, nowElapsed, bootCount)
            val totals = FocusSessionMathHelper.computeSegmentTotalsMs(updatedSegments)
            val computedFocusSec = totals.first / 1000L
            val computedBreakSec = totals.second / 1000L
            val finalFocusSec = trimmedFocusSeconds?.coerceIn(0L, MAX_SESSION_SECONDS) ?: computedFocusSec
            val adjusted = active.session.wasManuallyAdjusted ||
                (trimmedFocusSeconds != null && finalFocusSec != computedFocusSec)

            val updatedSession = active.session.copy(
                status = FocusSessionStatus.COMPLETED.name,
                awaitingNextPhase = null,
                endedAtWallMs = nowWall,
                actualFocusSec = finalFocusSec,
                actualBreakSec = computedBreakSec,
                wasManuallyAdjusted = adjusted,
                interruptedByReboot = false,
                focusRating = rating?.coerceIn(1, 5) ?: active.session.focusRating,
                reflectionNote = note?.trim()?.take(MAX_NOTE_LENGTH)?.ifEmpty { null }
                    ?: active.session.reflectionNote,
                sync = active.session.sync.copy(
                    clientUpdatedAtMs = nowWall,
                    version = active.session.sync.version + 1L
                )
            )
            sessionDao.updateSession(updatedSession)
            completed = updatedSession
        }

        if (completed != null) {
            alarmGateway.cancelPhaseEnd()
            notificationGateway.cancelAllSessionNotifications()
        }
        completed?.toDomain()
    }

    override suspend fun discardActiveSession() {
        stateMutex.withLock {
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            database.withTransaction {
                val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
                val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null }
                val updatedSegments = closeOpenSegmentIfPresent(active, openSeg, nowWall, nowElapsed, bootCount)
                val totals = FocusSessionMathHelper.computeSegmentTotalsMs(updatedSegments)
                val abandoned = active.session.copy(
                    status = FocusSessionStatus.ABANDONED.name,
                    awaitingNextPhase = null,
                    endedAtWallMs = nowWall,
                    actualFocusSec = totals.first / 1000L,
                    actualBreakSec = totals.second / 1000L,
                    sync = active.session.sync.copy(
                        clientUpdatedAtMs = nowWall,
                        version = active.session.sync.version + 1L
                    )
                )
                sessionDao.updateSession(abandoned)
            }
            alarmGateway.cancelPhaseEnd()
            notificationGateway.cancelAllSessionNotifications()
        }
    }

    override suspend fun checkpointActiveSession() {
        stateMutex.withLock {
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            val active = sessionDao.getActiveSessionOnce() ?: return@withLock
            if (active.session.status != FocusSessionStatus.RUNNING.name) return@withLock
            val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null } ?: return@withLock
            if (openSeg.bootCount == bootCount && nowElapsed >= openSeg.startedAtElapsedMs) {
                val elapsed = FocusTimeMath.openSegmentElapsedMs(openSeg.toDomain(), nowElapsed, bootCount)
                sessionDao.updateOpenSegmentCheckpoint(openSeg.id, elapsed)
            }
        }
    }

    override suspend fun reconcileActiveSessionAfterBootOrWake() {
        stateMutex.withLock {
            val prefs = preferencesDataStore.preferences.first()
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            var effect: TransitionEffect? = null
            database.withTransaction {
                val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
                effect = reconcileActiveLocked(active, prefs, nowWall, nowElapsed, bootCount)
            }
            dispatchEffect(effect, nowWall, nowElapsed)
        }
    }

    override suspend fun handlePhaseTimerExpired(sessionId: String, segmentId: String) {
        stateMutex.withLock {
            val prefs = preferencesDataStore.preferences.first()
            val nowWall = timeProvider.wallTimeMs()
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val bootCount = timeProvider.bootCount()
            var effect: TransitionEffect? = null

            database.withTransaction {
                val active = sessionDao.getActiveSessionOnce() ?: return@withTransaction
                val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null } ?: return@withTransaction
                val matchesTarget = active.session.id == sessionId &&
                    active.session.status == FocusSessionStatus.RUNNING.name &&
                    openSeg.id == segmentId
                if (!matchesTarget) return@withTransaction

                val exactDuration = openSeg.plannedDurationMs
                    ?: FocusTimeMath.openSegmentElapsedMs(openSeg.toDomain(), nowElapsed, bootCount)
                val closed = openSeg.copy(
                    endedAtWallMs = nowWall,
                    durationMs = exactDuration,
                    lastCheckpointedDurationMs = exactDuration
                )
                sessionDao.updateSegment(closed)
                val updatedSegments = active.segments.map { if (it.id == closed.id) closed else it }
                effect = advancePhaseLocked(
                    active = active.copy(segments = updatedSegments),
                    prefs = prefs,
                    nowWall = nowWall,
                    nowElapsed = nowElapsed,
                    bootCount = bootCount
                )
            }

            dispatchEffect(effect, nowWall, nowElapsed)
        }
    }

    private suspend fun reconcileActiveLocked(
        active: FocusSessionWithDetails,
        prefs: UserPreferences,
        nowWall: Long,
        nowElapsed: Long,
        bootCount: Int
    ): TransitionEffect? {
        return when (FocusSessionStatus.valueOf(active.session.status)) {
            FocusSessionStatus.PAUSED ->
                TransitionEffect.Paused(active.session, active.profile, active.session.interruptedByReboot)
            FocusSessionStatus.AWAITING_NEXT_PHASE ->
                TransitionEffect.Awaiting(
                    active.session,
                    active.profile,
                    active.session.awaitingNextPhase?.let(FocusPhase::valueOf),
                    showAlert = false
                )
            FocusSessionStatus.RUNNING -> reconcileRunningLocked(active, prefs, nowWall, nowElapsed, bootCount)
            FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> null
        }
    }

    private suspend fun reconcileRunningLocked(
        active: FocusSessionWithDetails,
        prefs: UserPreferences,
        nowWall: Long,
        nowElapsed: Long,
        bootCount: Int
    ): TransitionEffect? {
        val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null } ?: return null
        val rebooted = openSeg.bootCount != bootCount || nowElapsed < openSeg.startedAtElapsedMs
        if (rebooted) {
            val capped = openSeg.plannedDurationMs
                ?.let { openSeg.lastCheckpointedDurationMs.coerceAtMost(it) }
                ?: openSeg.lastCheckpointedDurationMs
            val closed = openSeg.copy(
                endedAtWallMs = nowWall,
                durationMs = capped.coerceAtLeast(0L),
                lastCheckpointedDurationMs = capped.coerceAtLeast(0L)
            )
            sessionDao.updateSegment(closed)
            val totals = FocusSessionMathHelper.computeSegmentTotalsMs(
                active.segments.map { if (it.id == closed.id) closed else it }
            )
            val paused = active.session.copy(
                status = FocusSessionStatus.PAUSED.name,
                actualFocusSec = totals.first / 1000L,
                actualBreakSec = totals.second / 1000L,
                interruptedByReboot = true,
                sync = active.session.sync.copy(
                    clientUpdatedAtMs = nowWall,
                    version = active.session.sync.version + 1L
                )
            )
            sessionDao.updateSession(paused)
            return TransitionEffect.Paused(paused, active.profile, interruptedByReboot = true)
        }

        val elapsedSinceStart = (nowElapsed - openSeg.startedAtElapsedMs).coerceAtLeast(0L)
        val plannedMs = openSeg.plannedDurationMs
        if (plannedMs != null && elapsedSinceStart >= plannedMs) {
            val closed = openSeg.copy(
                endedAtWallMs = nowWall,
                durationMs = plannedMs,
                lastCheckpointedDurationMs = plannedMs
            )
            sessionDao.updateSegment(closed)
            val updated = active.segments.map { if (it.id == closed.id) closed else it }
            return advancePhaseLocked(active.copy(segments = updated), prefs, nowWall, nowElapsed, bootCount)
        }
        val totals = FocusSessionMathHelper.computeSegmentTotalsMs(active.segments)
        return TransitionEffect.Running(active.session, active.profile, openSeg, totals.first)
    }

    private suspend fun closeOpenSegmentIfPresent(
        active: FocusSessionWithDetails,
        openSeg: FocusSessionSegmentEntity?,
        nowWall: Long,
        nowElapsed: Long,
        bootCount: Int
    ): List<FocusSessionSegmentEntity> {
        if (openSeg == null) return active.segments
        val segDurMs = FocusTimeMath.openSegmentElapsedMs(openSeg.toDomain(), nowElapsed, bootCount)
        val closed = openSeg.copy(
            endedAtWallMs = nowWall,
            durationMs = segDurMs,
            lastCheckpointedDurationMs = segDurMs
        )
        sessionDao.updateSegment(closed)
        return active.segments.map { if (it.id == closed.id) closed else it }
    }

    private suspend fun advancePhaseLocked(
        active: FocusSessionWithDetails,
        prefs: UserPreferences,
        nowWall: Long,
        nowElapsed: Long,
        bootCount: Int
    ): TransitionEffect {
        val currentPhase = FocusPhase.valueOf(active.session.currentPhase)
        val mode = FocusSessionMode.valueOf(active.session.sessionMode)
        val totals = FocusSessionMathHelper.computeSegmentTotalsMs(active.segments)
        val newCycles = if (currentPhase == FocusPhase.FOCUS) {
            active.session.completedFocusCycles + 1
        } else {
            active.session.completedFocusCycles
        }
        val nextPhase = FocusSessionMathHelper.determineNextPhase(
            mode,
            currentPhase,
            newCycles,
            active.profile.cyclesBeforeLongBreak
        )
        val baseUpdated = active.session.copy(
            completedFocusCycles = newCycles,
            actualFocusSec = totals.first / 1000L,
            actualBreakSec = totals.second / 1000L,
            sync = active.session.sync.copy(
                clientUpdatedAtMs = nowWall,
                version = active.session.sync.version + 1L
            )
        )
        val shouldAutoStart = when (nextPhase) {
            FocusPhase.SHORT_BREAK, FocusPhase.LONG_BREAK -> prefs.autoStartBreaks
            FocusPhase.FOCUS -> prefs.autoStartNextFocus
            null -> false
        }
        if (nextPhase != null && shouldAutoStart) {
            return openNewPhaseLocked(
                active.copy(session = baseUpdated),
                nextPhase,
                nowWall,
                nowElapsed,
                bootCount,
                alertPhase = currentPhase
            )
        }
        val awaiting = baseUpdated.copy(
            status = FocusSessionStatus.AWAITING_NEXT_PHASE.name,
            awaitingNextPhase = nextPhase?.name
        )
        sessionDao.updateSession(awaiting)
        return TransitionEffect.Awaiting(awaiting, active.profile, nextPhase, showAlert = true)
    }

    private suspend fun openNewPhaseLocked(
        active: FocusSessionWithDetails,
        nextPhase: FocusPhase,
        nowWall: Long,
        nowElapsed: Long,
        bootCount: Int,
        alertPhase: FocusPhase? = null
    ): TransitionEffect {
        val plannedMs = FocusTimeMath.phasePlannedDurationMs(
            profile = active.profile.toDomain(),
            phase = nextPhase,
            customFocusSeconds = active.session.plannedFocusSec
                .takeIf { active.session.sessionMode == FocusSessionMode.CUSTOM.name }
        )
        val seg = FocusSessionSegmentEntity(
            id = UuidV7.generate(nowWall),
            sessionId = active.session.id,
            segmentIndex = active.segments.size,
            phase = nextPhase.name,
            startedAtWallMs = nowWall,
            startedAtElapsedMs = nowElapsed,
            bootCount = bootCount,
            lastCheckpointedDurationMs = 0L,
            plannedDurationMs = plannedMs
        )
        sessionDao.insertSegment(seg)
        val updated = active.session.copy(
            status = FocusSessionStatus.RUNNING.name,
            currentPhase = nextPhase.name,
            awaitingNextPhase = null,
            interruptedByReboot = false,
            sync = active.session.sync.copy(
                clientUpdatedAtMs = nowWall,
                version = active.session.sync.version + 1L
            )
        )
        sessionDao.updateSession(updated)
        val totals = FocusSessionMathHelper.computeSegmentTotalsMs(active.segments)
        return TransitionEffect.Running(updated, active.profile, seg, totals.first, alertPhase)
    }

    private fun dispatchEffect(effect: TransitionEffect?, nowWall: Long, nowElapsed: Long) {
        FocusSessionMathHelper.applyTransitionEffect(
            effect = effect,
            nowWall = nowWall,
            nowElapsed = nowElapsed,
            gateways = PlatformGateways(notificationGateway, alarmGateway, profileNameProvider)
        )
    }

    private companion object {
        const val MAX_SESSION_SECONDS = 57_600L
        const val MAX_NOTE_LENGTH = 500
    }
}
