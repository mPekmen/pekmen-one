package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus

@Entity(
    tableName = "planner_tasks",
    indices = [
        Index(value = ["due_at_wall_ms"]),
        Index(value = ["status"]),
        Index(value = ["deleted_at_ms"]),
        Index(value = ["owner_user_id"])
    ]
)
data class PlannerTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "subject_id")
    val subjectId: String? = null,
    @ColumnInfo(name = "due_at_wall_ms")
    val dueAtWallMs: Long? = null,
    @ColumnInfo(name = "reminder_at_wall_ms")
    val reminderAtWallMs: Long? = null,
    @ColumnInfo(name = "recurrence_rule")
    val recurrenceRule: String = TaskRecurrenceRule.NONE.name,
    @ColumnInfo(name = "status")
    val status: String = TaskStatus.OPEN.name,
    @ColumnInfo(name = "completed_at_wall_ms")
    val completedAtWallMs: Long? = null,
    @ColumnInfo(name = "priority")
    val priority: Int = 1,
    @Embedded
    val sync: SyncMetadataEntity
) {
    fun toDomain(): PlannerTask = PlannerTask(
        id = id,
        ownerUserId = sync.ownerUserId,
        title = title,
        notes = notes,
        subjectId = subjectId,
        dueAtWallMs = dueAtWallMs,
        reminderAtWallMs = reminderAtWallMs,
        recurrenceRule = TaskRecurrenceRule.entries.firstOrNull { it.name == recurrenceRule }
            ?: TaskRecurrenceRule.NONE,
        status = TaskStatus.entries.firstOrNull { it.name == status } ?: TaskStatus.OPEN,
        completedAtWallMs = completedAtWallMs,
        priority = priority,
        syncStatus = SyncStatus.entries.firstOrNull { it.name == sync.syncStatus } ?: SyncStatus.LOCAL_ONLY
    )
}
