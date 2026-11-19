package net.pekmen.one.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.ActiveSessionSnapshot
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.model.SessionLearningLink
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.FocusNotificationGateway
import net.pekmen.one.core.platform.ProfileNameProvider

@OptIn(ExperimentalCoroutinesApi::class)
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
    private val accountSessionDao = database.accountSessionDao()
    private val outboxDao = database.syncOutboxDao()
    private val stateMutex = Mutex()
    private val transitions = FocusSessionTransitionsHelper(
        sessionDao = sessionDao,
        profileDao = database.focusProfileDao(),
        learningDao = database.learningDao(),
        learningTopicDao = database.learningTopicDao(),
        outboxDao = outboxDao
    )

    private val ownerUserIdFlow = preferencesDataStore.preferences
        .map { LearningOutboxEncoder.normalizeOwner(it.activeOwnerUserId) }
        .distinctUntilChanged()

    override val activeSession: Flow<ActiveSessionSnapshot?> = ownerUserIdFlow.flatMapLatest { ownerUserId ->
        accountSessionDao.observeActiveSessionByOwner(ownerUserId)
            .map { it?.toActiveSnapshot() }
    }

    override suspend fun startSession(
        profileId: String,
        mode: FocusSessionMode,
        customDurationMinutes: Int?,
        link: SessionLearningLink?
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
                created = transitions.insertInitialSession(
                    NewSessionArgs(
                        profileId = profileId,
                        mode = mode,
                        customDurationMinutes = customDurationMinutes,
                        link = link,
                        prefs = prefs,
                        effectiveDay = effectiveDay,
                        nowWall = nowWall,
                        nowElapsed = nowElapsed,
                        bootCount = bootCount
                    )
                )
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
                val updatedSegments = transitions.closeOpenSegmentIfPresent(
                    active, openSeg, nowWall, nowElapsed, bootCount
                )
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
                    outcome = transitions.advancePhaseLocked(active, prefs, nowWall, nowElapsed, bootCount)
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
                outcome = transitions.openNewPhaseLocked(active, nextPhase, nowWall, nowElapsed, bootCount)
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
                val updatedSegments = transitions.closeOpenSegmentIfPresent(
                    active, openSeg, nowWall, nowElapsed, bootCount
                )
                val totals = FocusSessionMathHelper.computeSegmentTotalsMs(updatedSegments)
                val updatedActive = active.copy(
                    session = active.session.copy(
                        actualFocusSec = totals.first / 1000L,
                        actualBreakSec = totals.second / 1000L
                    ),
                    segments = updatedSegments
                )
                outcome = transitions.openNewPhaseLocked(
                    active = updatedActive,
                    nextPhase = FocusPhase.FOCUS,
                    nowWall = nowWall,
                    nowElapsed = nowElapsed,
                    bootCount = bootCount
                )
            }

            dispatchEffect(outcome, nowWall, nowElapsed)
        }
    }

    override suspend fun freezeActiveSessionForWrapUp(): ActiveSessionSnapshot? {
        pauseActiveSession()
        val ownerUserId = LearningOutboxEncoder.normalizeOwner(
            preferencesDataStore.preferences.first().activeOwnerUserId
        )
        return sessionDao.getActiveSessionOnce()
            ?.takeIf { it.session.sync.ownerUserId == ownerUserId }
            ?.toActiveSnapshot()
    }

    override suspend fun completeActiveSession(
        trimmedFocusSeconds: Long?,
        rating: Int?,
        note: String?,
        bookEndPage: Int?,
        markTopicMastered: Boolean
    ): FocusSession? = stateMutex.withLock {
        val nowWall = timeProvider.wallTimeMs()
        val nowElapsed = timeProvider.elapsedRealtimeMs()
        val bootCount = timeProvider.bootCount()
        var completed: FocusSessionEntity? = null

        database.withTransaction {
            val active = sessionDao.getActiveSessionOnce()
            if (active != null && transitions.isCompletableStatus(active.session.status)) {
                val openSeg = active.segments.lastOrNull { it.endedAtWallMs == null }
                val updatedSegments = transitions.closeOpenSegmentIfPresent(
                    active, openSeg, nowWall, nowElapsed, bootCount
                )
                val totals = FocusSessionMathHelper.computeSegmentTotalsMs(updatedSegments)
                val computedFocusSec = totals.first / 1000L
                val computedBreakSec = totals.second / 1000L
                val finalFocusSec = trimmedFocusSeconds?.coerceIn(0L, MAX_SESSION_SECONDS) ?: computedFocusSec
                val adjusted = active.session.wasManuallyAdjusted ||
                    (trimmedFocusSeconds != null && finalFocusSec != computedFocusSec)

                val resolvedEndPage = transitions.updateLinkedBookOnComplete(active.session, bookEndPage, nowWall)
                transitions.updateLinkedTopicOnComplete(active.session, markTopicMastered, nowWall)

                val ownerId = active.session.sync.ownerUserId
                val updatedSession = active.session.copy(
                    status = FocusSessionStatus.COMPLETED.name,
                    awaitingNextPhase = null,
                    endedAtWallMs = nowWall,
                    bookEndPage = resolvedEndPage ?: active.session.bookEndPage,
                    actualFocusSec = finalFocusSec,
                    actualBreakSec = computedBreakSec,
                    wasManuallyAdjusted = adjusted,
                    interruptedByReboot = false,
                    focusRating = rating?.coerceIn(1, 5) ?: active.session.focusRating,
                    reflectionNote = note?.trim()?.take(MAX_NOTE_LENGTH)?.ifEmpty { null }
                        ?: active.session.reflectionNote,
                    sync = active.session.sync.copy(
                        clientUpdatedAtMs = nowWall,
                        version = active.session.sync.version + 1L,
                        syncStatus = LearningOutboxEncoder.syncStatusFor(ownerId)
                    )
                )
                sessionDao.updateSession(updatedSession)
                outboxDao.enqueue(
                    LearningOutboxEncoder.buildSessionMutation(
                        entity = updatedSession,
                        opType = OP_UPSERT,
                        baseVersion = active.session.sync.version,
                        nowMs = nowWall
                    )
                )
                completed = updatedSession
            }
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
                val updatedSegments = transitions.closeOpenSegmentIfPresent(
                    active, openSeg, nowWall, nowElapsed, bootCount
                )
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
                effect = transitions.reconcileActiveLocked(active, prefs, nowWall, nowElapsed, bootCount)
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
                effect = transitions.advancePhaseLocked(
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
        const val OP_UPSERT = "UPSERT"
    }
}
