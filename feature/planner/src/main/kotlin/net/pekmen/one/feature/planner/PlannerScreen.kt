package net.pekmen.one.feature.planner

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.pekmen.one.core.data.planner.SavePlannerTaskRequest
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.ExternalCalendarItem
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import java.time.Instant
import java.time.ZoneId

@Composable
fun PlannerScreenRoute(
    viewModel: PlannerViewModel,
    onFocusOnTask: (taskId: String, subjectId: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val exportUnavailableMsg = stringResource(R.string.planner_export_unavailable)

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.onCalendarPermissionChanged()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onCalendarPermissionChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PlannerScreen(
            state = state,
            onAddTask = viewModel::openCreateTask,
            onEditTask = viewModel::openEditTask,
            onDismissEditor = viewModel::dismissEditor,
            onSaveTask = viewModel::saveTask,
            onCompleteTask = viewModel::completeTask,
            onReopenTask = viewModel::reopenTask,
            onCancelTask = viewModel::cancelTask,
            onDeleteTask = viewModel::deleteTask,
            onFocusOnTask = onFocusOnTask,
            onExportToCalendar = { task ->
                val intent = viewModel.buildCalendarInsertIntent(task)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(exportUnavailableMsg)
                    }
                }
            },
            onRequestCalendarPermission = {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            },
            onDismissCalendarBanner = viewModel::dismissCalendarBanner,
            onSelectDisplay = viewModel::selectDisplay,
            onMoveWeek = viewModel::moveWeek
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
fun PlannerScreen(
    state: PlannerUiState,
    onAddTask: () -> Unit,
    onEditTask: (PlannerTask) -> Unit,
    onDismissEditor: () -> Unit,
    onSaveTask: (SavePlannerTaskRequest) -> Unit,
    onCompleteTask: (String) -> Unit,
    onReopenTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onFocusOnTask: (taskId: String, subjectId: String?) -> Unit,
    onExportToCalendar: (PlannerTask) -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onDismissCalendarBanner: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectDisplay: (PlannerDisplay) -> Unit = {},
    onMoveWeek: (Int) -> Unit = {}
) {
    val subjectById = remember(state.subjects) { state.subjects.associateBy { it.id } }
    var historyStatus by rememberSaveable { mutableStateOf<TaskStatus?>(null) }
    var historyDays by rememberSaveable { mutableStateOf(0) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                PlannerHeader(onAddTask, state.display, onSelectDisplay)
            }

            if (state.display == PlannerDisplay.WEEK) {
                item(key = "week_navigation") { PlannerWeekNavigation(state.week, onMoveWeek) }
                items(state.week, key = { it.date.toString() }) { day ->
                    PlannerWeekDayCard(day, onEditTask)
                }
                return@LazyColumn
            }
            if (state.display == PlannerDisplay.HISTORY) {
                item(key = "history_filters") {
                    PlannerHistoryFilters(historyStatus, historyDays, { historyStatus = it }, { historyDays = it })
                }
                val visible = filterPlannerHistory(
                    state.pastTasks, historyStatus, historyDays, state.nowWallMs, state.zoneId
                )
                if (visible.isEmpty()) {
                    item(key = "history_empty") { Text(stringResource(R.string.planner_history_empty)) }
                }
                items(visible, key = { it.id }) { task -> PlannerHistoryRow(task, onEditTask, state.zoneId) }
                return@LazyColumn
            }

            when (val cal = state.calendarResult) {
                CalendarReadResult.PermissionDenied -> {
                    if (!state.calendarBannerDismissed) {
                        item(key = "calendar_banner") {
                            CalendarPermissionBanner(
                                onAllow = onRequestCalendarPermission,
                                onDismiss = onDismissCalendarBanner
                            )
                        }
                    }
                }
                is CalendarReadResult.Available -> {
                    item(key = "calendar_header") {
                        SectionTitle(text = stringResource(R.string.planner_section_calendar))
                    }
                    if (cal.events.isEmpty()) {
                        item(key = "calendar_empty") {
                            CalendarEmptyCard()
                        }
                    } else {
                        itemsIndexed(
                            items = cal.events,
                            key = { index, event -> "cal_${index}_${event.eventId}_${event.beginWallMs}" }
                        ) { _, event ->
                            ExternalCalendarCard(event = event, zoneId = state.zoneId)
                        }
                    }
                }
            }

            if (state.overdueTasks.isNotEmpty()) {
                item(key = "overdue_header") {
                    SectionTitle(
                        text = stringResource(R.string.planner_section_overdue),
                        isAlert = true
                    )
                }
                items(state.overdueTasks, key = { "overdue_${it.id}" }) { task ->
                    PlannerTaskCard(
                        task = task,
                        subject = task.subjectId?.let { subjectById[it] },
                        zoneId = state.zoneId,
                        isOverdue = true,
                        onEdit = { onEditTask(task) },
                        onComplete = { onCompleteTask(task.id) },
                        onFocus = { onFocusOnTask(task.id, task.subjectId) },
                        onExportCalendar = { onExportToCalendar(task) }
                    )
                }
            }

            item(key = "open_header") {
                SectionTitle(text = stringResource(R.string.planner_section_open))
            }

            if (state.openTasks.isEmpty() && state.overdueTasks.isEmpty()) {
                item(key = "tasks_empty") {
                    PlannerEmptyCard()
                }
            } else {
                items(state.openTasks, key = { "open_${it.id}" }) { task ->
                    PlannerTaskCard(
                        task = task,
                        subject = task.subjectId?.let { subjectById[it] },
                        zoneId = state.zoneId,
                        isOverdue = false,
                        onEdit = { onEditTask(task) },
                        onComplete = { onCompleteTask(task.id) },
                        onFocus = { onFocusOnTask(task.id, task.subjectId) },
                        onExportCalendar = { onExportToCalendar(task) }
                    )
                }
            }

            if (state.completedTasks.isNotEmpty()) {
                item(key = "completed_header") {
                    SectionTitle(text = stringResource(R.string.planner_section_completed))
                }
                items(state.completedTasks, key = { "done_${it.id}" }) { task ->
                    CompletedTaskCard(
                        task = task,
                        onReopen = { onReopenTask(task.id) },
                        onDelete = { onDeleteTask(task.id) }
                    )
                }
            }
        }
    }

    if (state.isEditorOpen) {
        TaskEditorSheet(
            existingTask = state.editingTask,
            subjects = state.subjects,
            nowWallMs = state.nowWallMs,
            zoneId = state.zoneId,
            errorMessageRes = state.editorErrorRes,
            onDismiss = onDismissEditor,
            onSave = onSaveTask,
            onCancelTask = onCancelTask,
            onDeleteTask = onDeleteTask
        )
    }
}

@Composable
private fun PlannerHeader(onAddTask: () -> Unit, display: PlannerDisplay, select: (PlannerDisplay) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.planner_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        PlannerDisplayPicker(display, select)
        FilledTonalButton(
            onClick = onAddTask,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(painter = painterResource(PekmenIcons.Add), contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.planner_add_task))
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    isAlert: Boolean = false
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun CalendarPermissionBanner(
    onAllow: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.planner_calendar_banner_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(R.string.planner_calendar_banner_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(R.string.planner_calendar_banner_allow))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(R.string.planner_calendar_banner_dismiss))
                }
            }
        }
    }
}

@Composable
private fun CalendarEmptyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.planner_calendar_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun ExternalCalendarCard(
    event: ExternalCalendarItem,
    zoneId: ZoneId
) {
    val dateLabel = PekmenFormatters.formatMediumDate(
        Instant.ofEpochMilli(event.beginWallMs).atZone(zoneId).toLocalDate()
    )
    val timeText = if (event.allDay) {
        stringResource(R.string.planner_calendar_all_day)
    } else {
        "${PekmenFormatters.formatShortTime(event.beginWallMs, zoneId)} – " +
            PekmenFormatters.formatShortTime(event.endWallMs, zoneId)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(PekmenIcons.Calendar),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$dateLabel · $timeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                event.calendarDisplayName?.let { calName ->
                    Text(
                        text = calName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannerTaskCard(
    task: PlannerTask,
    subject: LearningSubject?,
    zoneId: ZoneId,
    isOverdue: Boolean,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    onFocus: () -> Unit,
    onExportCalendar: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onComplete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(PekmenIcons.Check),
                        contentDescription = stringResource(R.string.planner_cd_complete_task),
                        tint = if (isOverdue) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    task.notes?.let { notes ->
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            TaskMetadataChips(
                task = task,
                subject = subject,
                zoneId = zoneId,
                isOverdue = isOverdue,
                onEdit = onEdit
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onFocus,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(painter = painterResource(PekmenIcons.Play), contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.planner_action_start_focus))
                }
                OutlinedButton(
                    onClick = onExportCalendar,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(painter = painterResource(PekmenIcons.Calendar), contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.planner_action_export_calendar))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskMetadataChips(
    task: PlannerTask,
    subject: LearningSubject?,
    zoneId: ZoneId,
    isOverdue: Boolean,
    onEdit: () -> Unit
) {
    val badges = buildList {
        task.dueAtWallMs?.let { dueMs ->
            val date = PekmenFormatters.formatMediumDate(
                Instant.ofEpochMilli(dueMs).atZone(zoneId).toLocalDate()
            )
            val time = PekmenFormatters.formatShortTime(dueMs, zoneId)
            add(stringResource(R.string.planner_due_label, "$date $time"))
        }
        task.reminderAtWallMs?.let { remMs ->
            val time = PekmenFormatters.formatShortTime(remMs, zoneId)
            add(stringResource(R.string.planner_reminder_label, time))
        }
        when (task.recurrenceRule) {
            TaskRecurrenceRule.DAILY -> add(stringResource(R.string.planner_recurrence_daily))
            TaskRecurrenceRule.WEEKLY -> add(stringResource(R.string.planner_recurrence_weekly))
            TaskRecurrenceRule.NONE -> Unit
        }
    }

    if (subject == null && badges.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        subject?.let { linked ->
            AssistChip(
                onClick = onEdit,
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(linked.colorHex), CircleShape)
                    )
                },
                label = { Text(text = linked.title) }
            )
        }
        badges.forEachIndexed { index, badge ->
            AssistChip(
                onClick = onEdit,
                label = {
                    Text(
                        text = badge,
                        color = if (isOverdue && index == 0 && task.dueAtWallMs != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun CompletedTaskCard(
    task: PlannerTask,
    onReopen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onReopen,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(PekmenIcons.Check),
                    contentDescription = stringResource(R.string.planner_cd_reopen_task),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = TextDecoration.LineThrough
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(PekmenIcons.Delete),
                    contentDescription = stringResource(R.string.planner_cd_delete_task),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlannerEmptyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.planner_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.planner_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
