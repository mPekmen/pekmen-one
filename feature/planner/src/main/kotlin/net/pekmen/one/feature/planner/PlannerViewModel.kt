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
import java.time.Instant
import java.time.ZoneId

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
    val zoneId: ZoneId = ZoneId.systemDefault()
)

class PlannerViewModel(
    private val plannerRepository: PlannerRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository,
    private val calendarInsertGateway: CalendarInsertGateway = PlatformCalendarInsertGateway()
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)
    private val bannerDismissed = MutableStateFlow(false)
    private val editorState = MutableStateFlow(EditorModalState())

    val uiState: StateFlow<PlannerUiState> = combine(
        plannerRepository.observeTasks(),
        plannerRepository.observeAvailableSubjects(),
        refreshTick,
        bannerDismissed,
        editorState
    ) { tasks, subjects, _, dismissed, editor ->
        val nowMs = diagnosticsRepository.currentWallTimeMs()
        val zone = diagnosticsRepository.currentZoneId()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val beginMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(CALENDAR_WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
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
            zoneId = zone
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlannerUiState()
    )

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

    private companion object {
        const val CALENDAR_WINDOW_DAYS = 7L
    }
}
