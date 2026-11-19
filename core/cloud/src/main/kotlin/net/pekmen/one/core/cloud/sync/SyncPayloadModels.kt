package net.pekmen.one.core.cloud.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SyncEntityTable(val tableName: String) {
    FOCUS_PROFILES("focus_profiles"),
    LEARNING_SUBJECTS("learning_subjects"),
    LEARNING_BOOKS("learning_books"),
    LEARNING_TOPICS("learning_topics"),
    FOCUS_SESSIONS("focus_sessions"),
    PLANNER_TASKS("planner_tasks");

    companion object {
        fun fromTableName(name: String): SyncEntityTable? =
            entries.firstOrNull { it.tableName == name }
    }
}

@Serializable
enum class SyncOpType {
    UPSERT,
    DELETE
}

@Serializable
data class SyncMetadataPayload(
    val ownerUserId: String,
    val createdAtMs: Long,
    val clientUpdatedAtMs: Long,
    val serverUpdatedAtMs: Long? = null,
    val deletedAtMs: Long? = null,
    val version: Long = 1L
)

@Serializable
sealed interface SyncEntityPayload {
    val id: String
    val sync: SyncMetadataPayload
    val table: SyncEntityTable
}

@Serializable
@SerialName("focus_profile")
data class FocusProfilePayload(
    override val id: String,
    val builtInSlug: String,
    val customName: String? = null,
    val colorHex: Long,
    val iconKey: String,
    val defaultMode: String,
    val workMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val cyclesBeforeLongBreak: Int,
    val dndEnabled: Boolean,
    val sortOrder: Int,
    val isArchived: Boolean,
    override val sync: SyncMetadataPayload
) : SyncEntityPayload {
    override val table: SyncEntityTable get() = SyncEntityTable.FOCUS_PROFILES
}

@Serializable
@SerialName("learning_subject")
data class LearningSubjectPayload(
    override val id: String,
    val title: String,
    val colorHex: Long,
    val weeklyGoalMinutes: Int,
    val sortOrder: Int,
    val isArchived: Boolean,
    override val sync: SyncMetadataPayload
) : SyncEntityPayload {
    override val table: SyncEntityTable get() = SyncEntityTable.LEARNING_SUBJECTS
}

@Serializable
@SerialName("learning_book")
data class LearningBookPayload(
    override val id: String,
    val subjectId: String,
    val title: String,
    val author: String? = null,
    val totalPages: Int,
    val currentPage: Int,
    val totalChapters: Int,
    val completedChapters: Int,
    val isCompleted: Boolean,
    override val sync: SyncMetadataPayload
) : SyncEntityPayload {
    override val table: SyncEntityTable get() = SyncEntityTable.LEARNING_BOOKS
}

@Serializable
@SerialName("learning_topic")
data class LearningTopicPayload(
    override val id: String,
    val subjectId: String,
    val bookId: String? = null,
    val title: String,
    val status: String,
    val sortOrder: Int,
    override val sync: SyncMetadataPayload
) : SyncEntityPayload {
    override val table: SyncEntityTable get() = SyncEntityTable.LEARNING_TOPICS
}

@Serializable
data class FocusSessionSegmentPayload(
    val id: String,
    val sessionId: String,
    val segmentIndex: Int,
    val phase: String,
    val startedAtWallMs: Long,
    val startedAtElapsedMs: Long,
    val bootCount: Int,
    val lastCheckpointedDurationMs: Long,
    val plannedDurationMs: Long? = null,
    val endedAtWallMs: Long? = null,
    val durationMs: Long? = null
)

@Serializable
@SerialName("focus_session")
data class FocusSessionPayload(
    override val id: String,
    val profileId: String,
    val subjectId: String? = null,
    val bookId: String? = null,
    val topicId: String? = null,
    val taskId: String? = null,
    val bookStartPage: Int? = null,
    val bookEndPage: Int? = null,
    val sessionMode: String,
    val status: String,
    val currentPhase: String,
    val awaitingNextPhase: String? = null,
    val completedFocusCycles: Int,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long? = null,
    val effectiveDayDate: String,
    val plannedFocusSec: Long? = null,
    val actualFocusSec: Long,
    val actualBreakSec: Long,
    val pauseCount: Int,
    val isManualEntry: Boolean,
    val wasManuallyAdjusted: Boolean,
    val interruptedByReboot: Boolean,
    val focusRating: Int? = null,
    val reflectionNote: String? = null,
    val segments: List<FocusSessionSegmentPayload> = emptyList(),
    override val sync: SyncMetadataPayload
) : SyncEntityPayload {
    override val table: SyncEntityTable get() = SyncEntityTable.FOCUS_SESSIONS
}

@Serializable
@SerialName("planner_task")
data class PlannerTaskPayload(
    override val id: String,
    val title: String,
    val notes: String? = null,
    val subjectId: String? = null,
    val dueAtWallMs: Long? = null,
    val reminderAtWallMs: Long? = null,
    val recurrenceRule: String,
    val status: String,
    val completedAtWallMs: Long? = null,
    val priority: Int,
    override val sync: SyncMetadataPayload
) : SyncEntityPayload {
    override val table: SyncEntityTable get() = SyncEntityTable.PLANNER_TASKS
}

@Serializable
data class SyncMutationItem(
    val mutationId: String,
    val entityTable: String,
    val entityId: String,
    val opType: SyncOpType,
    val baseVersion: Long,
    val clientUpdatedAtMs: Long,
    val payload: SyncEntityPayload
)

@Serializable
data class SyncPushRequest(
    val mutations: List<SyncMutationItem>
)

@Serializable
data class SyncMutationAck(
    val mutationId: String,
    val entityTable: String,
    val entityId: String,
    val accepted: Boolean,
    val deduplicated: Boolean,
    val resolvedVersion: Long,
    val serverUpdatedAtMs: Long,
    val winningPayload: SyncEntityPayload? = null,
    val error: String? = null
)

@Serializable
data class SyncPushResponse(
    val results: List<SyncMutationAck>
)

@Serializable
data class SyncPullRequest(
    val entityTable: String,
    val afterServerUpdatedAtMs: Long,
    val afterEntityId: String,
    val limit: Int = 100
)

@Serializable
data class SyncDeltaRecord(
    val entityTable: String,
    val entityId: String,
    val serverUpdatedAtMs: Long,
    val mutationId: String,
    val payload: SyncEntityPayload
)

@Serializable
data class SyncPullResponse(
    val records: List<SyncDeltaRecord>,
    val nextServerUpdatedAtMs: Long,
    val nextEntityId: String,
    val hasMore: Boolean
)

object SyncPayloadJson {
    val codec: Json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodePayload(payload: SyncEntityPayload): String =
        codec.encodeToString(SyncEntityPayload.serializer(), payload)

    fun decodePayload(rawJson: String): SyncEntityPayload =
        codec.decodeFromString(SyncEntityPayload.serializer(), rawJson)
}
