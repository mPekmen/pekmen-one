package net.pekmen.one.core.data

import net.pekmen.one.core.database.dao.FocusProfileDao
import net.pekmen.one.core.database.dao.FocusSessionDao
import net.pekmen.one.core.database.dao.LearningDao
import net.pekmen.one.core.database.dao.LearningTopicDao
import net.pekmen.one.core.database.dao.SyncOutboxDao
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.database.relation.FocusSessionWithDetails
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.model.SessionLearningLink
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.core.model.UuidV7
import java.time.LocalDate

internal data class NewSessionArgs(
    val profileId: String,
    val mode: FocusSessionMode,
    val customDurationMinutes: Int?,
    val link: SessionLearningLink?,
    val prefs: UserPreferences,
    val effectiveDay: LocalDate,
    val nowWall: Long,
    val nowElapsed: Long,
    val bootCount: Int
)

internal class FocusSessionTransitionsHelper(
    private val sessionDao: FocusSessionDao,
    private val profileDao: FocusProfileDao,
    private val learningDao: LearningDao,
    private val learningTopicDao: LearningTopicDao,
    private val outboxDao: SyncOutboxDao
) {

    suspend fun insertInitialSession(
        args: NewSessionArgs
    ): Triple<FocusSessionEntity, FocusProfileEntity, FocusSessionSegmentEntity> {
        check(sessionDao.countActiveSessions() == 0) { "An active focus session already exists" }
        val ownerUserId = LearningOutboxEncoder.normalizeOwner(args.prefs.activeOwnerUserId)
        val profile = requireNotNull(profileDao.getById(args.profileId)) {
            "Unknown profile: ${args.profileId}"
        }
        val linkedBook = args.link?.bookId?.let { learningDao.getBookById(it) }
        if (linkedBook != null) {
            require(linkedBook.sync.ownerUserId == ownerUserId) { "Book belongs to a different account" }
        }
        val linkedTopic = args.link?.topicId?.let { learningTopicDao.getTopicById(it) }
        if (linkedTopic != null) {
            require(linkedTopic.sync.ownerUserId == ownerUserId) { "Topic belongs to a different account" }
        }
        val plannedSec = when (args.mode) {
            FocusSessionMode.POMODORO -> profile.workMinutes * 60L
            FocusSessionMode.CUSTOM -> {
                val customMin = args.customDurationMinutes ?: args.prefs.defaultCustomMinutes
                customMin.coerceIn(1, 480) * 60L
            }
            FocusSessionMode.STOPWATCH -> null
        }
        val sessionId = UuidV7.generate(args.nowWall)
        val sessionEntity = FocusSessionEntity(
            id = sessionId,
            profileId = profile.id,
            subjectId = args.link?.subjectId ?: linkedBook?.subjectId ?: linkedTopic?.subjectId,
            bookId = args.link?.bookId,
            topicId = args.link?.topicId,
            taskId = args.link?.taskId,
            bookStartPage = args.link?.bookStartPage ?: linkedBook?.currentPage,
            bookEndPage = args.link?.bookEndPage,
            sessionMode = args.mode.name,
            status = FocusSessionStatus.RUNNING.name,
            currentPhase = FocusPhase.FOCUS.name,
            completedFocusCycles = 0,
            startedAtWallMs = args.nowWall,
            effectiveDayDate = args.effectiveDay.toString(),
            plannedFocusSec = plannedSec,
            actualFocusSec = 0L,
            actualBreakSec = 0L,
            pauseCount = 0,
            sync = SyncMetadataEntity(
                ownerUserId = ownerUserId,
                createdAtMs = args.nowWall,
                clientUpdatedAtMs = args.nowWall,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        val segmentEntity = FocusSessionSegmentEntity(
            id = UuidV7.generate(args.nowWall + 1L),
            sessionId = sessionId,
            segmentIndex = 0,
            phase = FocusPhase.FOCUS.name,
            startedAtWallMs = args.nowWall,
            startedAtElapsedMs = args.nowElapsed,
            bootCount = args.bootCount,
            lastCheckpointedDurationMs = 0L,
            plannedDurationMs = plannedSec?.times(1000L)
        )
        sessionDao.insertSession(sessionEntity)
        sessionDao.insertSegment(segmentEntity)
        return Triple(sessionEntity, profile, segmentEntity)
    }

    suspend fun updateLinkedBookOnComplete(
        session: FocusSessionEntity,
        bookEndPage: Int?,
        nowWall: Long
    ): Int? {
        val bookId = session.bookId
        if (bookId == null || bookEndPage == null) return null
        val book = learningDao.getBookById(bookId)
        var resolvedEndPage: Int? = null
        if (book != null && book.sync.ownerUserId == session.sync.ownerUserId) {
            val targetPage = if (book.totalPages > 0) {
                bookEndPage.coerceIn(0, book.totalPages)
            } else {
                bookEndPage.coerceAtLeast(0)
            }
            val completedBook = if (book.totalPages > 0) targetPage >= book.totalPages else book.isCompleted
            val updatedBook = book.copy(
                currentPage = targetPage,
                isCompleted = completedBook,
                sync = book.sync.copy(
                    clientUpdatedAtMs = nowWall,
                    version = book.sync.version + 1L,
                    syncStatus = LearningOutboxEncoder.syncStatusFor(book.sync.ownerUserId)
                )
            )
            learningDao.updateBook(updatedBook)
            outboxDao.enqueue(
                LearningOutboxEncoder.buildBookMutation(
                    entity = updatedBook,
                    opType = OP_UPSERT,
                    baseVersion = book.sync.version,
                    nowMs = nowWall
                )
            )
            resolvedEndPage = targetPage
        }
        return resolvedEndPage
    }

    suspend fun updateLinkedTopicOnComplete(
        session: FocusSessionEntity,
        markTopicMastered: Boolean,
        nowWall: Long
    ) {
        val topicId = session.topicId
        if (!markTopicMastered || topicId == null) return
        val topic = learningTopicDao.getTopicById(topicId)
        if (topic != null &&
            topic.sync.ownerUserId == session.sync.ownerUserId &&
            topic.status != TopicStatus.MASTERED.name
        ) {
            val updatedTopic = topic.copy(
                status = TopicStatus.MASTERED.name,
                sync = topic.sync.copy(
                    clientUpdatedAtMs = nowWall,
                    version = topic.sync.version + 1L,
                    syncStatus = LearningOutboxEncoder.syncStatusFor(topic.sync.ownerUserId)
                )
            )
            learningTopicDao.upsertTopic(updatedTopic)
            outboxDao.enqueue(
                LearningOutboxEncoder.buildTopicMutation(
                    entity = updatedTopic,
                    opType = OP_UPSERT,
                    baseVersion = topic.sync.version,
                    nowMs = nowWall
                )
            )
        }
    }

    fun isCompletableStatus(statusRaw: String): Boolean {
        return statusRaw == FocusSessionStatus.RUNNING.name ||
            statusRaw == FocusSessionStatus.PAUSED.name ||
            statusRaw == FocusSessionStatus.AWAITING_NEXT_PHASE.name
    }

    suspend fun reconcileActiveLocked(
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

    suspend fun reconcileRunningLocked(
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

    suspend fun closeOpenSegmentIfPresent(
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

    suspend fun advancePhaseLocked(
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

    suspend fun openNewPhaseLocked(
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

    private companion object {
        const val OP_UPSERT = "UPSERT"
    }
}
