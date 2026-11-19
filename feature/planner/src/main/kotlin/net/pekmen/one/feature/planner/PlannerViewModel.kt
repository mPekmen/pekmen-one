package net.pekmen.one.feature.planner

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.planner.CalendarInsertGateway
import net.pekmen.one.core.data.planner.PlatformCalendarInsertGateway
import net.pekmen.one.core.data.planner.PlannerRepository
import net.pekmen.one.core.data.planner.SavePlannerTaskRequest
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class PlannerUiState(
    val overdueTasks: List<PlannerTask> = emptyList(),
    val openTasks: List<PlannerTask> = emptyList(),
    val completedTasks: List<PlannerTask> = emptyList(),
    val calendarResult: CalendarReadResult = CalendarReadResult.PermissionDenied,
    val subjects: List<LearningSubject> = emptyList(),
    val calendarBannerDismissed: Boolean = false,
    val isEditorOpen: Boolean = false,
    val editingTask: PlannerTask? = null,
    val editorErrorRes: Int? = null,
    val nowWallMs: Long = 0L,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val display: PlannerDisplay = PlannerDisplay.AGENDA,
    val week: List<PlannerWeekDay> = emptyList(),
    val pastTasks: List<PlannerTask> = emptyList()
)

class PlannerViewModel(
    private val plannerRepository: PlannerRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository,
    private val calendarInsertGateway: CalendarInsertGateway = PlatformCalendarInsertGateway()
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)
    private val bannerDismissed = MutableStateFlow(false)
    private val editorState = MutableStateFlow(EditorModalState())
    private val displayState = MutableStateFlow(DisplayState())

    val uiState: StateFlow<PlannerUiState> = combine(
        plannerRepository.observeTasks(),
        plannerRepository.observeAvailableSubjects(),
        combine(refreshTick, displayState) { _, display -> display },
        bannerDismissed,
        editorState
    ) { tasks, subjects, display, dismissed, editor ->
        val nowMs = diagnosticsRepository.currentWallTimeMs()
        val zone = diagnosticsRepository.currentZoneId()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val beginDate = if (display.mode == PlannerDisplay.WEEK) {
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(display.weekOffset.toLong())
        } else {
            today
        }
        val beginMs = beginDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = beginDate.plusDays(CALENDAR_WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        val calendarResult = plannerRepository.readCalendarEvents(beginMs, endMs)

        val openAll = tasks.filter { it.status == TaskStatus.OPEN }
        val overdue = openAll.filter { task ->
            val due = task.dueAtWallMs
            due != null && due < nowMs
        }
        val upcoming = openAll.filter { task ->
            val due = task.dueAtWallMs
            due == null || due >= nowMs
        }
        val completed = tasks
            .filter { it.status == TaskStatus.COMPLETED }
            .sortedByDescending { it.completedAtWallMs ?: 0L }

        PlannerUiState(
            overdueTasks = overdue,
            openTasks = upcoming,
            completedTasks = completed,
            calendarResult = calendarResult,
            subjects = subjects,
            calendarBannerDismissed = dismissed,
            isEditorOpen = editor.isOpen,
            editingTask = editor.editingTask,
            editorErrorRes = editor.errorRes,
            nowWallMs = nowMs,
            zoneId = zone,
            display = display.mode,
            week = if (display.mode == PlannerDisplay.WEEK) {
                weekFor(beginDate, zone, tasks, calendarResult)
            } else {
                emptyList()
            },
            pastTasks = tasks.filter { it.status != TaskStatus.OPEN }.sortedByDescending { it.lastUpdatedWallMs }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlannerUiState()
    )

    fun selectDisplay(display: PlannerDisplay) {
        displayState.value = displayState.value.copy(mode = display)
    }

    fun moveWeek(by: Int) {
        val current = displayState.value
        displayState.value = current.copy(weekOffset = (current.weekOffset + by).coerceIn(-52, 52))
    }

    private fun weekFor(
        start: LocalDate,
        zone: ZoneId,
        tasks: List<PlannerTask>,
        calendar: CalendarReadResult
    ): List<PlannerWeekDay> {
        val tasksByDay = tasks.filter { it.status != TaskStatus.CANCELLED }.groupBy { task ->
            task.dueAtWallMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        }
        val events = (calendar as? CalendarReadResult.Available)?.events.orEmpty()
        return (0L until CALENDAR_WINDOW_DAYS).map { offset ->
            val day = start.plusDays(offset)
            val begin = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            PlannerWeekDay(
                date = day,
                tasks = tasksByDay[day].orEmpty().sortedWith(
                    compareByDescending<PlannerTask> { it.priority }.thenBy { it.dueAtWallMs }
                ),
                events = events.filter { it.beginWallMs < end && it.endWallMs >= begin }
            )
        }
    }

    fun openCreateTask() {
        editorState.value = EditorModalState(isOpen = true, editingTask = null, errorRes = null)
    }

    fun openEditTask(task: PlannerTask) {
        editorState.value = EditorModalState(isOpen = true, editingTask = task, errorRes = null)
    }

    fun dismissEditor() {
        editorState.value = EditorModalState()
    }

    fun saveTask(request: SavePlannerTaskRequest) {
        viewModelScope.launch {
            val result = plannerRepository.saveTask(request)
            if (result.isSuccess) {
                dismissEditor()
            } else {
                editorState.value = editorState.value.copy(errorRes = R.string.planner_error_blank_title)
            }
        }
    }

    fun completeTask(taskId: String) {
        viewModelScope.launch {
            plannerRepository.completeTask(taskId)
        }
    }

    fun reopenTask(taskId: String) {
        viewModelScope.launch {
            plannerRepository.reopenTask(taskId)
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            plannerRepository.cancelTask(taskId)
            dismissEditor()
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            plannerRepository.softDeleteTask(taskId)
            if (editorState.value.editingTask?.id == taskId) {
                dismissEditor()
            }
        }
    }

    fun onCalendarPermissionChanged() {
        refreshTick.value += 1
    }

    fun dismissCalendarBanner() {
        bannerDismissed.value = true
    }

    fun buildCalendarInsertIntent(task: PlannerTask): Intent {
        return calendarInsertGateway.buildInsertEventIntent(task)
    }

    private data class EditorModalState(
        val isOpen: Boolean = false,
        val editingTask: PlannerTask? = null,
        val errorRes: Int? = null
    )

    private data class DisplayState(val mode: PlannerDisplay = PlannerDisplay.AGENDA, val weekOffset: Int = 0)

    private companion object {
        const val CALENDAR_WINDOW_DAYS = 7L
    }
}
