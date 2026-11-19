package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SyncStatus
import java.time.LocalDate

@Entity(
    tableName = "focus_sessions",
    foreignKeys = [
        ForeignKey(
            entity = FocusProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["profile_id"]),
        Index(value = ["effective_day_date"]),
        Index(value = ["status"]),
        Index(value = ["deleted_at_ms"]),
        Index(value = ["subject_id"]),
        Index(value = ["owner_user_id"])
    ]
)
data class FocusSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String? = null,
    @ColumnInfo(name = "book_id")
    val bookId: String? = null,
    @ColumnInfo(name = "topic_id")
    val topicId: String? = null,
    @ColumnInfo(name = "task_id")
    val taskId: String? = null,
    @ColumnInfo(name = "book_start_page")
    val bookStartPage: Int? = null,
    @ColumnInfo(name = "book_end_page")
    val bookEndPage: Int? = null,
    @ColumnInfo(name = "session_mode")
    val sessionMode: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "current_phase")
    val currentPhase: String,
    @ColumnInfo(name = "awaiting_next_phase")
    val awaitingNextPhase: String? = null,
    @ColumnInfo(name = "completed_focus_cycles")
    val completedFocusCycles: Int,
    @ColumnInfo(name = "started_at_wall_ms")
    val startedAtWallMs: Long,
    @ColumnInfo(name = "ended_at_wall_ms")
    val endedAtWallMs: Long? = null,
    @ColumnInfo(name = "effective_day_date")
    val effectiveDayDate: String,
    @ColumnInfo(name = "planned_focus_sec")
    val plannedFocusSec: Long? = null,
    @ColumnInfo(name = "actual_focus_sec")
    val actualFocusSec: Long,
    @ColumnInfo(name = "actual_break_sec")
    val actualBreakSec: Long,
    @ColumnInfo(name = "pause_count")
    val pauseCount: Int,
    @ColumnInfo(name = "is_manual_entry")
    val isManualEntry: Boolean = false,
    @ColumnInfo(name = "was_manually_adjusted")
    val wasManuallyAdjusted: Boolean = false,
    @ColumnInfo(name = "interrupted_by_reboot")
    val interruptedByReboot: Boolean = false,
    @ColumnInfo(name = "focus_rating")
    val focusRating: Int? = null,
    @ColumnInfo(name = "reflection_note")
    val reflectionNote: String? = null,
    @Embedded
    val sync: SyncMetadataEntity
) {
    fun toDomain(): FocusSession = FocusSession(
        id = id,
        profileId = profileId,
        subjectId = subjectId,
        bookId = bookId,
        topicId = topicId,
        taskId = taskId,
        bookStartPage = bookStartPage,
        bookEndPage = bookEndPage,
        sessionMode = FocusSessionMode.valueOf(sessionMode),
        status = FocusSessionStatus.valueOf(status),
        currentPhase = FocusPhase.valueOf(currentPhase),
        awaitingNextPhase = awaitingNextPhase?.let(FocusPhase::valueOf),
        completedFocusCycles = completedFocusCycles,
        startedAtWallMs = startedAtWallMs,
        endedAtWallMs = endedAtWallMs,
        effectiveDayDate = LocalDate.parse(effectiveDayDate),
        plannedFocusSec = plannedFocusSec,
        actualFocusSec = actualFocusSec,
        actualBreakSec = actualBreakSec,
        pauseCount = pauseCount,
        isManualEntry = isManualEntry,
        wasManuallyAdjusted = wasManuallyAdjusted,
        interruptedByReboot = interruptedByReboot,
        focusRating = focusRating,
        reflectionNote = reflectionNote,
        syncStatus = SyncStatus.entries.firstOrNull { it.name == sync.syncStatus }
            ?: SyncStatus.LOCAL_ONLY
    )
}
