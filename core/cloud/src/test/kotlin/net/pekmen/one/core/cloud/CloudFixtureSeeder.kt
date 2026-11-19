package net.pekmen.one.core.cloud

import androidx.room.withTransaction
import net.pekmen.one.core.cloud.sync.FocusProfilePayload
import net.pekmen.one.core.cloud.sync.FocusSessionPayload
import net.pekmen.one.core.cloud.sync.FocusSessionSegmentPayload
import net.pekmen.one.core.cloud.sync.LearningBookPayload
import net.pekmen.one.core.cloud.sync.LearningSubjectPayload
import net.pekmen.one.core.cloud.sync.LearningTopicPayload
import net.pekmen.one.core.cloud.sync.PlannerTaskPayload
import net.pekmen.one.core.cloud.sync.SyncEntityPayload
import net.pekmen.one.core.cloud.sync.SyncMetadataPayload
import net.pekmen.one.core.cloud.sync.SyncOpType
import net.pekmen.one.core.cloud.sync.SyncPayloadJson
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.model.UuidV7
import java.time.LocalDate

internal object CloudFixtureSeeder {

    suspend fun insertGuestFixtures(db: PekmenDatabase, nowMs: Long) {
        val profileId = UuidV7.generate(nowMs)
        val subjectId = UuidV7.generate(nowMs + 1L)
        val bookId = UuidV7.generate(nowMs + 2L)
        val topicId = UuidV7.generate(nowMs + 3L)
        val sessionId = UuidV7.generate(nowMs + 4L)
        val taskId = UuidV7.generate(nowMs + 5L)
        db.withTransaction {
            insertGuestProfileAndLearning(db, profileId, subjectId, bookId, topicId, nowMs)
            insertGuestSessionAndTask(db, subjectId, bookId, topicId, sessionId, taskId, nowMs)
        }
    }

    private suspend fun insertGuestProfileAndLearning(
        db: PekmenDatabase,
        profileId: String,
        subjectId: String,
        bookId: String,
        topicId: String,
        nowMs: Long
    ) {
        db.focusProfileDao().insert(
            FocusProfileEntity(
                id = profileId,
                builtInSlug = BuiltInProfileSlug.CUSTOM.name,
                customName = "Deep Reading",
                colorHex = 0xFF8B7CF6,
                iconKey = "custom",
                defaultMode = FocusSessionMode.CUSTOM.name,
                workMinutes = 40,
                shortBreakMinutes = 5,
                longBreakMinutes = 15,
                cyclesBeforeLongBreak = 3,
                dndEnabled = false,
                sortOrder = 4,
                isArchived = false,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.learningDao().upsertSubject(
            LearningSubjectEntity(
                id = subjectId,
                title = "Guest Subject",
                colorHex = 0xFF60A5FA,
                weeklyGoalMinutes = 120,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.learningDao().upsertBook(
            LearningBookEntity(
                id = bookId,
                subjectId = subjectId,
                title = "Guest Book",
                totalPages = 150,
                currentPage = 20,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.learningTopicDao().upsertTopic(
            LearningTopicEntity(
                id = topicId,
                subjectId = subjectId,
                bookId = bookId,
                title = "Guest Topic",
                status = TopicStatus.IN_PROGRESS.name,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
    }

    private suspend fun insertGuestSessionAndTask(
        db: PekmenDatabase,
        subjectId: String,
        bookId: String,
        topicId: String,
        sessionId: String,
        taskId: String,
        nowMs: Long
    ) {
        db.focusSessionDao().insertSession(
            FocusSessionEntity(
                id = sessionId,
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                bookId = bookId,
                topicId = topicId,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = FocusSessionStatus.COMPLETED.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = nowMs - 1_500_000L,
                endedAtWallMs = nowMs,
                effectiveDayDate = LocalDate.of(2026, 11, 19).toString(),
                plannedFocusSec = 1_500L,
                actualFocusSec = 1_500L,
                actualBreakSec = 0L,
                pauseCount = 0,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.plannerTaskDao().upsertTask(
            PlannerTaskEntity(
                id = taskId,
                title = "Guest Task",
                subjectId = subjectId,
                recurrenceRule = TaskRecurrenceRule.NONE.name,
                status = TaskStatus.OPEN.name,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
    }

    suspend fun seedFiftyMixedMutations(db: PekmenDatabase, ownerId: String, baseMs: Long) {
        var seq = 0L
        fun nextTs(): Long {
            seq += 1L
            return baseMs + seq
        }

        val subjectIds = (0 until 10).map { UuidV7.generate(nextTs()) }
        db.withTransaction {
            seedMixedProfiles(db, ownerId, ::nextTs)
            seedMixedSubjects(db, ownerId, subjectIds, ::nextTs)
            seedMixedBooks(db, ownerId, subjectIds, ::nextTs)
            seedMixedTopics(db, ownerId, subjectIds, ::nextTs)
            seedMixedSessions(db, ownerId, subjectIds, ::nextTs)
            seedMixedTasks(db, ownerId, subjectIds, ::nextTs)
        }
    }

    private suspend fun seedMixedProfiles(db: PekmenDatabase, ownerId: String, nextTs: () -> Long) {
        repeat(4) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx == 3) ts + 5L else null
            val entity = FocusProfileEntity(
                id = UuidV7.generate(ts),
                builtInSlug = BuiltInProfileSlug.CUSTOM.name,
                customName = "Custom $idx",
                colorHex = 0xFF8B7CF6,
                iconKey = "custom",
                defaultMode = FocusSessionMode.POMODORO.name,
                workMinutes = 25 + idx,
                shortBreakMinutes = 5,
                longBreakMinutes = 15,
                cyclesBeforeLongBreak = 4,
                dndEnabled = false,
                sortOrder = 10 + idx,
                isArchived = false,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.focusProfileDao().insert(entity)
            val payload = FocusProfilePayload(
                id = entity.id,
                builtInSlug = entity.builtInSlug,
                customName = entity.customName,
                colorHex = entity.colorHex,
                iconKey = entity.iconKey,
                defaultMode = entity.defaultMode,
                workMinutes = entity.workMinutes,
                shortBreakMinutes = entity.shortBreakMinutes,
                longBreakMinutes = entity.longBreakMinutes,
                cyclesBeforeLongBreak = entity.cyclesBeforeLongBreak,
                dndEnabled = entity.dndEnabled,
                sortOrder = entity.sortOrder,
                isArchived = entity.isArchived,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedSubjects(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        subjectIds.forEachIndexed { idx, id ->
            val ts = nextTs()
            val deletedAt = if (idx >= 8) ts + 5L else null
            val entity = LearningSubjectEntity(
                id = id,
                title = "Subject $idx",
                colorHex = 0xFF60A5FA,
                weeklyGoalMinutes = 120 + idx * 10,
                sortOrder = idx,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.learningDao().upsertSubject(entity)
            val payload = LearningSubjectPayload(
                id = id,
                title = entity.title,
                colorHex = entity.colorHex,
                weeklyGoalMinutes = entity.weeklyGoalMinutes,
                sortOrder = entity.sortOrder,
                isArchived = entity.isArchived,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedBooks(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(10) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx >= 8) ts + 5L else null
            val subjectId = subjectIds[idx % 8]
            val entity = LearningBookEntity(
                id = UuidV7.generate(ts),
                subjectId = subjectId,
                title = "Book $idx",
                totalPages = 200,
                currentPage = idx * 10,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.learningDao().upsertBook(entity)
            val payload = LearningBookPayload(
                id = entity.id,
                subjectId = subjectId,
                title = entity.title,
                author = null,
                totalPages = entity.totalPages,
                currentPage = entity.currentPage,
                totalChapters = 0,
                completedChapters = 0,
                isCompleted = false,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedTopics(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(10) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx >= 8) ts + 5L else null
            val subjectId = subjectIds[idx % 8]
            val entity = LearningTopicEntity(
                id = UuidV7.generate(ts),
                subjectId = subjectId,
                title = "Topic $idx",
                status = TopicStatus.TODO.name,
                sortOrder = idx,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.learningTopicDao().upsertTopic(entity)
            val payload = LearningTopicPayload(
                id = entity.id,
                subjectId = subjectId,
                bookId = null,
                title = entity.title,
                status = entity.status,
                sortOrder = entity.sortOrder,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedSessions(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(8) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx == 7) ts + 5L else null
            val subjectId = subjectIds[idx]
            val sessionId = UuidV7.generate(ts)
            val segId = UuidV7.generate(ts + 100L)
            val session = FocusSessionEntity(
                id = sessionId,
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = FocusSessionStatus.COMPLETED.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = ts - 1_500_000L,
                endedAtWallMs = ts,
                effectiveDayDate = LocalDate.of(2026, 11, 19).toString(),
                plannedFocusSec = 1_500L,
                actualFocusSec = 1_500L,
                actualBreakSec = 0L,
                pauseCount = 0,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            val seg = FocusSessionSegmentEntity(
                id = segId,
                sessionId = sessionId,
                segmentIndex = 0,
                phase = FocusPhase.FOCUS.name,
                startedAtWallMs = ts - 1_500_000L,
                startedAtElapsedMs = 10_000L,
                bootCount = 1,
                lastCheckpointedDurationMs = 1_500_000L,
                plannedDurationMs = 1_500_000L,
                endedAtWallMs = ts,
                durationMs = 1_500_000L
            )
            db.focusSessionDao().insertSession(session)
            db.focusSessionDao().insertSegment(seg)
            enqueuePayload(db, session.toPayload(seg, ownerId, ts, deletedAt), ownerId, ts)
        }
    }

    private suspend fun seedMixedTasks(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(8) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx == 7) ts + 5L else null
            val entity = PlannerTaskEntity(
                id = UuidV7.generate(ts),
                title = "Task $idx",
                subjectId = subjectIds[idx],
                recurrenceRule = TaskRecurrenceRule.NONE.name,
                status = TaskStatus.OPEN.name,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.plannerTaskDao().upsertTask(entity)
            val payload = PlannerTaskPayload(
                id = entity.id,
                title = entity.title,
                subjectId = entity.subjectId,
                recurrenceRule = entity.recurrenceRule,
                status = entity.status,
                priority = entity.priority,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    suspend fun insertOwnedSubject(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        nowMs: Long
    ) {
        val entity = LearningSubjectEntity(
            id = id,
            title = title,
            colorHex = 0xFF8B7CF6,
            weeklyGoalMinutes = 150,
            sync = SyncMetadataEntity(ownerId, nowMs, nowMs, syncStatus = SyncStatus.PENDING_UPLOAD.name)
        )
        db.learningDao().upsertSubject(entity)
        enqueuePayload(
            db,
            LearningSubjectPayload(
                id = id,
                title = title,
                colorHex = entity.colorHex,
                weeklyGoalMinutes = entity.weeklyGoalMinutes,
                sortOrder = 0,
                isArchived = false,
                sync = SyncMetadataPayload(ownerId, nowMs, nowMs)
            ),
            ownerId,
            nowMs
        )
    }

    suspend fun updateOwnedSubjectTitle(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        updatedAtMs: Long,
        version: Long
    ) {
        val existing = requireNotNull(db.learningDao().getSubjectById(id))
        val updated = existing.copy(
            title = title,
            sync = existing.sync.copy(
                clientUpdatedAtMs = updatedAtMs,
                version = version,
                syncStatus = SyncStatus.PENDING_UPLOAD.name
            )
        )
        db.learningDao().updateSubject(updated)
        enqueuePayload(
            db,
            LearningSubjectPayload(
                id = id,
                title = title,
                colorHex = updated.colorHex,
                weeklyGoalMinutes = updated.weeklyGoalMinutes,
                sortOrder = updated.sortOrder,
                isArchived = updated.isArchived,
                sync = SyncMetadataPayload(ownerId, updated.sync.createdAtMs, updatedAtMs, version = version)
            ),
            ownerId,
            updatedAtMs
        )
    }

    suspend fun softDeleteOwnedSubject(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        deletedAtMs: Long,
        version: Long
    ) {
        val existing = requireNotNull(db.learningDao().getSubjectById(id))
        db.learningDao().softDeleteSubject(id, deletedAtMs = deletedAtMs, updatedAtMs = deletedAtMs)
        enqueuePayload(
            db,
            LearningSubjectPayload(
                id = id,
                title = existing.title,
                colorHex = existing.colorHex,
                weeklyGoalMinutes = existing.weeklyGoalMinutes,
                sortOrder = existing.sortOrder,
                isArchived = existing.isArchived,
                sync = SyncMetadataPayload(
                    ownerUserId = ownerId,
                    createdAtMs = existing.sync.createdAtMs,
                    clientUpdatedAtMs = deletedAtMs,
                    deletedAtMs = deletedAtMs,
                    version = version
                )
            ),
            ownerId,
            deletedAtMs
        )
    }

    suspend fun insertOwnedSession(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        subjectId: String,
        nowMs: Long
    ) {
        val entity = FocusSessionEntity(
            id = id,
            profileId = BuiltInProfiles.STUDY_ID,
            subjectId = subjectId,
            sessionMode = FocusSessionMode.POMODORO.name,
            status = FocusSessionStatus.COMPLETED.name,
            currentPhase = FocusPhase.FOCUS.name,
            completedFocusCycles = 1,
            startedAtWallMs = nowMs - 1_500_000L,
            endedAtWallMs = nowMs,
            effectiveDayDate = LocalDate.of(2026, 11, 19).toString(),
            plannedFocusSec = 1_500L,
            actualFocusSec = 1_500L,
            actualBreakSec = 0L,
            pauseCount = 0,
            sync = SyncMetadataEntity(ownerId, nowMs, nowMs, syncStatus = SyncStatus.PENDING_UPLOAD.name)
        )
        db.focusSessionDao().insertSession(entity)
        enqueuePayload(
            db,
            FocusSessionPayload(
                id = id,
                profileId = entity.profileId,
                subjectId = subjectId,
                sessionMode = entity.sessionMode,
                status = entity.status,
                currentPhase = entity.currentPhase,
                completedFocusCycles = entity.completedFocusCycles,
                startedAtWallMs = entity.startedAtWallMs,
                endedAtWallMs = entity.endedAtWallMs,
                effectiveDayDate = entity.effectiveDayDate,
                plannedFocusSec = entity.plannedFocusSec,
                actualFocusSec = entity.actualFocusSec,
                actualBreakSec = entity.actualBreakSec,
                pauseCount = entity.pauseCount,
                isManualEntry = false,
                wasManuallyAdjusted = false,
                interruptedByReboot = false,
                sync = SyncMetadataPayload(ownerId, nowMs, nowMs)
            ),
            ownerId,
            nowMs
        )
    }

    suspend fun insertOwnedTask(db: PekmenDatabase, id: String, ownerId: String, title: String, nowMs: Long) {
        val entity = PlannerTaskEntity(
            id = id,
            title = title,
            recurrenceRule = TaskRecurrenceRule.NONE.name,
            status = TaskStatus.OPEN.name,
            sync = SyncMetadataEntity(ownerId, nowMs, nowMs, syncStatus = SyncStatus.PENDING_UPLOAD.name)
        )
        db.plannerTaskDao().upsertTask(entity)
        enqueuePayload(
            db,
            PlannerTaskPayload(
                id = id,
                title = title,
                recurrenceRule = entity.recurrenceRule,
                status = entity.status,
                priority = entity.priority,
                sync = SyncMetadataPayload(ownerId, nowMs, nowMs)
            ),
            ownerId,
            nowMs
        )
    }

    suspend fun updateOwnedTaskTitle(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        updatedAtMs: Long,
        version: Long
    ) {
        val existing = requireNotNull(db.plannerTaskDao().getTaskById(id))
        val updated = existing.copy(
            title = title,
            sync = existing.sync.copy(
                clientUpdatedAtMs = updatedAtMs,
                version = version,
                syncStatus = SyncStatus.PENDING_UPLOAD.name
            )
        )
        db.plannerTaskDao().updateTask(updated)
        enqueuePayload(
            db,
            PlannerTaskPayload(
                id = id,
                title = title,
                recurrenceRule = updated.recurrenceRule,
                status = updated.status,
                priority = updated.priority,
                sync = SyncMetadataPayload(ownerId, updated.sync.createdAtMs, updatedAtMs, version = version)
            ),
            ownerId,
            updatedAtMs
        )
    }

    private suspend fun enqueuePayload(
        db: PekmenDatabase,
        payload: SyncEntityPayload,
        ownerId: String,
        nowMs: Long
    ) {
        val opType = if (payload.sync.deletedAtMs != null) SyncOpType.DELETE else SyncOpType.UPSERT
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                mutationId = UuidV7.generate(nowMs),
                entityTable = payload.table.tableName,
                entityId = payload.id,
                opType = opType.name,
                payloadJson = SyncPayloadJson.encodePayload(payload),
                baseVersion = payload.sync.version,
                attemptCount = 0,
                createdAtMs = nowMs,
                ownerUserId = ownerId
            )
        )
    }

    private fun FocusSessionEntity.toPayload(
        seg: FocusSessionSegmentEntity,
        ownerId: String,
        ts: Long,
        deletedAt: Long?
    ): FocusSessionPayload = FocusSessionPayload(
        id = id,
        profileId = profileId,
        subjectId = subjectId,
        sessionMode = sessionMode,
        status = status,
        currentPhase = currentPhase,
        completedFocusCycles = completedFocusCycles,
        startedAtWallMs = startedAtWallMs,
        endedAtWallMs = endedAtWallMs,
        effectiveDayDate = effectiveDayDate,
        plannedFocusSec = plannedFocusSec,
        actualFocusSec = actualFocusSec,
        actualBreakSec = actualBreakSec,
        pauseCount = pauseCount,
        isManualEntry = false,
        wasManuallyAdjusted = false,
        interruptedByReboot = false,
        segments = listOf(
            FocusSessionSegmentPayload(
                id = seg.id,
                sessionId = seg.sessionId,
                segmentIndex = seg.segmentIndex,
                phase = seg.phase,
                startedAtWallMs = seg.startedAtWallMs,
                startedAtElapsedMs = seg.startedAtElapsedMs,
                bootCount = seg.bootCount,
                lastCheckpointedDurationMs = seg.lastCheckpointedDurationMs,
                plannedDurationMs = seg.plannedDurationMs,
                endedAtWallMs = seg.endedAtWallMs,
                durationMs = seg.durationMs
            )
        ),
        sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
    )
}
