package net.pekmen.one.core.model

enum class TaskRecurrenceRule {
    NONE,
    DAILY,
    WEEKLY
}

enum class TaskStatus {
    OPEN,
    COMPLETED,
    CANCELLED
}

data class PlannerTask(
    val id: String,
    val ownerUserId: String? = null,
    val title: String,
    val notes: String? = null,
    val subjectId: String? = null,
    val dueAtWallMs: Long? = null,
    val reminderAtWallMs: Long? = null,
    val recurrenceRule: TaskRecurrenceRule = TaskRecurrenceRule.NONE,
    val status: TaskStatus = TaskStatus.OPEN,
    val completedAtWallMs: Long? = null,
    val priority: Int = 1,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)

data class ExternalCalendarItem(
    val eventId: Long,
    val title: String,
    val beginWallMs: Long,
    val endWallMs: Long,
    val allDay: Boolean,
    val calendarDisplayName: String? = null
)

sealed interface CalendarReadResult {
    data class Available(val events: List<ExternalCalendarItem>) : CalendarReadResult
    data object PermissionDenied : CalendarReadResult
}

data class AgendaOverview(
    val openTasks: List<PlannerTask>,
    val completedTasks: List<PlannerTask>,
    val calendarResult: CalendarReadResult
)
