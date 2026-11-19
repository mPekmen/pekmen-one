package net.pekmen.one.core.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.UuidV7

internal object LearningOutboxEncoder {
    private const val TABLE_SUBJECTS = "learning_subjects"
    private const val TABLE_BOOKS = "learning_books"
    private const val TABLE_TOPICS = "learning_topics"
    private const val TABLE_SESSIONS = "focus_sessions"

    fun normalizeOwner(rawOwnerId: String?): String? {
        return rawOwnerId?.trim()?.ifEmpty { null }
    }

    fun syncStatusFor(ownerUserId: String?): String {
        return if (ownerUserId == null) SyncStatus.LOCAL_ONLY.name else SyncStatus.PENDING_UPLOAD.name
    }

    fun buildSubjectMutation(
        entity: LearningSubjectEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("title", entity.title)
            put("color_hex", entity.colorHex)
            put("weekly_goal_minutes", entity.weeklyGoalMinutes)
            put("sort_order", entity.sortOrder)
            put("is_archived", entity.isArchived)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_SUBJECTS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }

    fun buildBookMutation(
        entity: LearningBookEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("subject_id", entity.subjectId)
            put("title", entity.title)
            put("author", entity.author)
            put("total_pages", entity.totalPages)
            put("current_page", entity.currentPage)
            put("total_chapters", entity.totalChapters)
            put("completed_chapters", entity.completedChapters)
            put("is_completed", entity.isCompleted)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_BOOKS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }

    fun buildTopicMutation(
        entity: LearningTopicEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("subject_id", entity.subjectId)
            put("book_id", entity.bookId)
            put("title", entity.title)
            put("status", entity.status)
            put("sort_order", entity.sortOrder)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_TOPICS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }

    fun buildSessionMutation(
        entity: FocusSessionEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("profile_id", entity.profileId)
            put("subject_id", entity.subjectId)
            put("book_id", entity.bookId)
            put("topic_id", entity.topicId)
            put("task_id", entity.taskId)
            put("book_start_page", entity.bookStartPage)
            put("book_end_page", entity.bookEndPage)
            put("session_mode", entity.sessionMode)
            put("status", entity.status)
            put("started_at_wall_ms", entity.startedAtWallMs)
            put("ended_at_wall_ms", entity.endedAtWallMs)
            put("effective_day_date", entity.effectiveDayDate)
            put("actual_focus_sec", entity.actualFocusSec)
            put("actual_break_sec", entity.actualBreakSec)
            put("is_manual_entry", entity.isManualEntry)
            put("focus_rating", entity.focusRating)
            put("reflection_note", entity.reflectionNote)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_SESSIONS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }
}
