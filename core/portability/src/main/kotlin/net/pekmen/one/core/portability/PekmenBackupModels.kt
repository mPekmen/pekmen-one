package net.pekmen.one.core.portability

import kotlinx.serialization.Serializable
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.TopicStatus

@Serializable
data class BackupSyncMetadata(
    val ownerUserId: String? = null,
    val createdAtMs: Long,
    val clientUpdatedAtMs: Long,
    val serverUpdatedAtMs: Long? = null,
    val deletedAtMs: Long? = null,
    val version: Long = 1L,
    val syncStatus: String = SyncStatus.LOCAL_ONLY.name
)

@Serializable
data class BackupPreferences(
    val themePreference: String = ThemePreference.SYSTEM.name,
    val dayRolloverHour: Int = EffectiveDayCalculator.DEFAULT_ROLLOVER_HOUR,
    val selectedProfileId: String? = null,
    val autoStartBreaks: Boolean = false,
    val autoStartNextFocus: Boolean = false,
    val defaultCustomMinutes: Int = 45,
    val zenIntegrationEnabled: Boolean = false,
    val routineKeywordEnabled: Boolean = false
)

@Serializable
data class BackupProfile(
    val id: String,
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
    val isArchived: Boolean = false,
    val sync: BackupSyncMetadata
)

@Serializable
data class BackupSubject(
    val id: String,
    val title: String,
    val colorHex: Long,
    val weeklyGoalMinutes: Int,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val sync: BackupSyncMetadata,
    val dailyGoalMinutes: Int = 0
)

@Serializable
data class BackupBook(
    val id: String,
    val subjectId: String,
    val title: String,
    val author: String? = null,
    val totalPages: Int,
    val currentPage: Int = 0,
    val totalChapters: Int = 0,
    val completedChapters: Int = 0,
    val isCompleted: Boolean = false,
    val sync: BackupSyncMetadata
)

@Serializable
data class BackupTopic(
    val id: String,
    val subjectId: String,
    val bookId: String? = null,
    val title: String,
    val status: String = TopicStatus.TODO.name,
    val sortOrder: Int = 0,
    val sync: BackupSyncMetadata,
    val parentTopicId: String? = null
)

@Serializable
data class BackupSession(
    val id: String,
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
    val isManualEntry: Boolean = false,
    val wasManuallyAdjusted: Boolean = false,
    val interruptedByReboot: Boolean = false,
    val focusRating: Int? = null,
    val reflectionNote: String? = null,
    val sync: BackupSyncMetadata
)

@Serializable
data class BackupSegment(
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
data class BackupTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    val subjectId: String? = null,
    val dueAtWallMs: Long? = null,
    val reminderAtWallMs: Long? = null,
    val recurrenceRule: String = TaskRecurrenceRule.NONE.name,
    val status: String = TaskStatus.OPEN.name,
    val completedAtWallMs: Long? = null,
    val priority: Int = 1,
    val sync: BackupSyncMetadata
)

@Serializable
data class PekmenBackupArchive(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtWallMs: Long,
    val appVersion: String = CURRENT_APP_VERSION,
    val preferences: BackupPreferences? = null,
    val profiles: List<BackupProfile> = emptyList(),
    val subjects: List<BackupSubject> = emptyList(),
    val books: List<BackupBook> = emptyList(),
    val topics: List<BackupTopic> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
    val segments: List<BackupSegment> = emptyList(),
    val tasks: List<BackupTask> = emptyList()
) {
    companion object {
        const val MIN_SUPPORTED_SCHEMA_VERSION = 1
        const val CURRENT_SCHEMA_VERSION = 3
        const val CURRENT_APP_VERSION = "0.2.0"
    }
}
