package net.pekmen.one.core.data.planner

import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.model.AgendaOverview
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskRecurrenceRule

interface PlannerRepository {
    fun observeTasks(): Flow<List<PlannerTask>>
    fun observeAvailableSubjects(): Flow<List<LearningSubject>>
    fun observeAgenda(beginWallMs: Long, endWallMs: Long): Flow<AgendaOverview>
    suspend fun getTaskById(taskId: String): PlannerTask?
    suspend fun saveTask(request: SavePlannerTaskRequest): Result<PlannerTask>
    suspend fun completeTask(taskId: String): Result<CompleteTaskOutcome>
    suspend fun reopenTask(taskId: String): Result<PlannerTask>
    suspend fun cancelTask(taskId: String): Result<PlannerTask>
    suspend fun softDeleteTask(taskId: String): Result<Unit>
    suspend fun rescheduleAllPendingReminders(): Int
    suspend fun handleReminderFired(taskId: String)
    fun readCalendarEvents(beginWallMs: Long, endWallMs: Long): CalendarReadResult
}

data class SavePlannerTaskRequest(
    val existingId: String? = null,
    val title: String,
    val notes: String? = null,
    val subjectId: String? = null,
    val dueAtWallMs: Long? = null,
    val reminderAtWallMs: Long? = null,
    val recurrenceRule: TaskRecurrenceRule = TaskRecurrenceRule.NONE,
    val priority: Int = 1
)

data class CompleteTaskOutcome(
    val completedTask: PlannerTask,
    val nextOccurrence: PlannerTask? = null
)
