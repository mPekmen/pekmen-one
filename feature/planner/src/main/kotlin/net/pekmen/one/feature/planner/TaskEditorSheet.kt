package net.pekmen.one.feature.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.data.planner.SavePlannerTaskRequest
import net.pekmen.one.core.designsystem.R as DesignR
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskRecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val DUE_NONE = 0
private const val DUE_TODAY = 1
private const val DUE_TOMORROW = 2
private const val DUE_NEXT_WEEK = 3
private const val DUE_KEEP_EXISTING = 4

private const val REMINDER_OFF = 0
private const val REMINDER_AT_DUE = 1
private const val REMINDER_15M_BEFORE = 2
private const val REMINDER_1H_BEFORE = 3
private const val REMINDER_IN_1H = 4
private const val REMINDER_KEEP_EXISTING = 5

private val TIME_HOUR_PRESETS = listOf(9, 12, 15, 18, 20)
private const val ONE_HOUR_MS = 60L * 60_000L
private const val FIFTEEN_MIN_MS = 15L * 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    existingTask: PlannerTask?,
    subjects: List<LearningSubject>,
    nowWallMs: Long,
    zoneId: ZoneId,
    errorMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (SavePlannerTaskRequest) -> Unit,
    onCancelTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    val todayDate = Instant.ofEpochMilli(nowWallMs).atZone(zoneId).toLocalDate()
    val initialDueZoned = existingTask?.dueAtWallMs?.let { Instant.ofEpochMilli(it).atZone(zoneId) }

    var titleText by remember(existingTask?.id) { mutableStateOf(existingTask?.title.orEmpty()) }
    var notesText by remember(existingTask?.id) { mutableStateOf(existingTask?.notes.orEmpty()) }
    var selectedSubjectId by remember(existingTask?.id) { mutableStateOf(existingTask?.subjectId) }
    var priority by remember(existingTask?.id) { mutableIntStateOf(existingTask?.priority ?: 1) }
    var recurrenceRule by remember(existingTask?.id) {
        mutableStateOf(existingTask?.recurrenceRule ?: TaskRecurrenceRule.NONE)
    }
    var duePreset by remember(existingTask?.id) {
        mutableIntStateOf(initialDuePreset(initialDueZoned?.toLocalDate(), todayDate))
    }
    var selectedHour by remember(existingTask?.id) {
        mutableIntStateOf(initialDueZoned?.hour ?: 18)
    }
    var selectedMinute by remember(existingTask?.id) {
        mutableIntStateOf(initialDueZoned?.minute ?: 0)
    }
    var reminderPreset by remember(existingTask?.id) {
        mutableIntStateOf(initialReminderPreset(existingTask))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EditorHeader(isEditing = existingTask != null, errorMessageRes = errorMessageRes)

            OutlinedTextField(
                value = titleText,
                onValueChange = { if (it.length <= 120) titleText = it },
                label = { Text(text = stringResource(R.string.planner_editor_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = notesText,
                onValueChange = { if (it.length <= 500) notesText = it },
                label = { Text(text = stringResource(R.string.planner_editor_notes_label)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            if (subjects.isNotEmpty()) {
                SubjectSelectorRow(
                    subjects = subjects,
                    selectedSubjectId = selectedSubjectId,
                    onSelectSubject = { selectedSubjectId = it }
                )
            }

            PrioritySelectorRow(
                selectedPriority = priority,
                onSelectPriority = { priority = it }
            )

            DueDateSelectorRow(
                selectedPreset = duePreset,
                onSelectPreset = { next ->
                    duePreset = next
                    if (next == DUE_NONE && reminderPreset != REMINDER_IN_1H) {
                        reminderPreset = REMINDER_OFF
                    } else if (next != DUE_NONE && reminderPreset == REMINDER_IN_1H) {
                        reminderPreset = REMINDER_AT_DUE
                    }
                }
            )

            if (duePreset != DUE_NONE) {
                TimePresetRow(
                    selectedHour = selectedHour,
                    zoneId = zoneId,
                    onSelectHour = {
                        if (duePreset == DUE_KEEP_EXISTING && initialDueZoned == null) {
                            duePreset = DUE_TODAY
                        }
                        selectedHour = it
                        selectedMinute = 0
                    }
                )
            }

            ReminderSelectorRow(
                hasDueDate = duePreset != DUE_NONE,
                selectedPreset = reminderPreset,
                onSelectPreset = { reminderPreset = it }
            )

            RecurrenceSelectorRow(
                selectedRule = recurrenceRule,
                onSelectRule = { recurrenceRule = it }
            )

            if (existingTask != null) {
                ExistingTaskSecondaryActions(
                    onCancelTask = { onCancelTask(existingTask.id) },
                    onDeleteTask = { onDeleteTask(existingTask.id) }
                )
            }

            EditorFooterActions(
                onDismiss = onDismiss,
                onConfirm = {
                    val computedDue = computeDueWallMs(
                        preset = duePreset,
                        existingDueDate = initialDueZoned?.toLocalDate(),
                        todayDate = todayDate,
                        hour = selectedHour,
                        minute = selectedMinute,
                        zoneId = zoneId
                    )
                    val computedReminder = computeReminderWallMs(
                        preset = reminderPreset,
                        existingReminderWallMs = existingTask?.reminderAtWallMs,
                        dueWallMs = computedDue,
                        nowWallMs = nowWallMs
                    )
                    onSave(
                        SavePlannerTaskRequest(
                            existingId = existingTask?.id,
                            title = titleText,
                            notes = notesText,
                            subjectId = selectedSubjectId,
                            dueAtWallMs = computedDue,
                            reminderAtWallMs = computedReminder,
                            recurrenceRule = recurrenceRule,
                            priority = priority
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun EditorHeader(isEditing: Boolean, errorMessageRes: Int?) {
    Text(
        text = if (isEditing) {
            stringResource(R.string.planner_editor_edit_title)
        } else {
            stringResource(R.string.planner_editor_create_title)
        },
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    errorMessageRes?.let { errRes ->
        Text(
            text = stringResource(errRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SubjectSelectorRow(
    subjects: List<LearningSubject>,
    selectedSubjectId: String?,
    onSelectSubject: (String?) -> Unit
) {
    Text(
        text = stringResource(R.string.planner_editor_subject_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "subject_none") {
            FilterChip(
                selected = selectedSubjectId == null,
                onClick = { onSelectSubject(null) },
                label = { Text(text = stringResource(R.string.planner_editor_subject_none)) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
        items(subjects, key = { it.id }) { subject ->
            FilterChip(
                selected = selectedSubjectId == subject.id,
                onClick = { onSelectSubject(subject.id) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(subject.colorHex), CircleShape)
                    )
                },
                label = { Text(text = subject.title) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun PrioritySelectorRow(
    selectedPriority: Int,
    onSelectPriority: (Int) -> Unit
) {
    Text(
        text = stringResource(R.string.planner_editor_priority_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val priorities = listOf(
        0 to R.string.planner_priority_low,
        1 to R.string.planner_priority_normal,
        2 to R.string.planner_priority_high
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        priorities.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = selectedPriority == value,
                onClick = { onSelectPriority(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = priorities.size)
            ) {
                Text(text = stringResource(labelRes), maxLines = 1)
            }
        }
    }
}

@Composable
private fun DueDateSelectorRow(
    selectedPreset: Int,
    onSelectPreset: (Int) -> Unit
) {
    Text(
        text = stringResource(R.string.planner_editor_due_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val options = listOf(
        DUE_NONE to R.string.planner_due_none,
        DUE_TODAY to R.string.planner_due_today,
        DUE_TOMORROW to R.string.planner_due_tomorrow,
        DUE_NEXT_WEEK to R.string.planner_due_next_week
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (preset, labelRes) ->
            FilterChip(
                selected = selectedPreset == preset,
                onClick = { onSelectPreset(preset) },
                label = { Text(text = stringResource(labelRes)) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun TimePresetRow(
    selectedHour: Int,
    zoneId: ZoneId,
    onSelectHour: (Int) -> Unit
) {
    Text(
        text = stringResource(R.string.planner_editor_time_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val baseDate = LocalDate.of(2026, 1, 1)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TIME_HOUR_PRESETS, key = { it }) { hour ->
            val sampleMs = baseDate.atTime(LocalTime.of(hour, 0)).atZone(zoneId).toInstant().toEpochMilli()
            FilterChip(
                selected = selectedHour == hour,
                onClick = { onSelectHour(hour) },
                label = { Text(text = PekmenFormatters.formatShortTime(sampleMs, zoneId)) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun ReminderSelectorRow(
    hasDueDate: Boolean,
    selectedPreset: Int,
    onSelectPreset: (Int) -> Unit
) {
    Text(
        text = stringResource(R.string.planner_editor_reminder_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val options = if (hasDueDate) {
        listOf(
            REMINDER_OFF to R.string.planner_reminder_off,
            REMINDER_AT_DUE to R.string.planner_reminder_at_due,
            REMINDER_15M_BEFORE to R.string.planner_reminder_15m_before,
            REMINDER_1H_BEFORE to R.string.planner_reminder_1h_before
        )
    } else {
        listOf(
            REMINDER_OFF to R.string.planner_reminder_off,
            REMINDER_IN_1H to R.string.planner_reminder_in_1h
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (preset, labelRes) ->
            FilterChip(
                selected = selectedPreset == preset,
                onClick = { onSelectPreset(preset) },
                label = { Text(text = stringResource(labelRes)) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun RecurrenceSelectorRow(
    selectedRule: TaskRecurrenceRule,
    onSelectRule: (TaskRecurrenceRule) -> Unit
) {
    Text(
        text = stringResource(R.string.planner_editor_recurrence_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val rules = TaskRecurrenceRule.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            val labelRes = when (rule) {
                TaskRecurrenceRule.NONE -> R.string.planner_recurrence_none
                TaskRecurrenceRule.DAILY -> R.string.planner_recurrence_daily
                TaskRecurrenceRule.WEEKLY -> R.string.planner_recurrence_weekly
            }
            SegmentedButton(
                selected = selectedRule == rule,
                onClick = { onSelectRule(rule) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = rules.size)
            ) {
                Text(text = stringResource(labelRes), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ExistingTaskSecondaryActions(
    onCancelTask: () -> Unit,
    onDeleteTask: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(
            onClick = onCancelTask,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.planner_editor_cancel_task))
        }
        TextButton(
            onClick = onDeleteTask,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(painter = painterResource(PekmenIcons.Delete), contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(DesignR.string.action_delete),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun EditorFooterActions(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(DesignR.string.action_cancel))
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(DesignR.string.action_save))
        }
    }
}

private fun initialDuePreset(dueDate: LocalDate?, todayDate: LocalDate): Int {
    if (dueDate == null) return DUE_NONE
    return when (dueDate) {
        todayDate -> DUE_TODAY
        todayDate.plusDays(1L) -> DUE_TOMORROW
        todayDate.plusDays(7L) -> DUE_NEXT_WEEK
        else -> DUE_KEEP_EXISTING
    }
}

private fun initialReminderPreset(task: PlannerTask?): Int {
    val reminderMs = task?.reminderAtWallMs ?: return REMINDER_OFF
    val dueMs = task.dueAtWallMs ?: return REMINDER_KEEP_EXISTING
    return when (dueMs - reminderMs) {
        0L -> REMINDER_AT_DUE
        FIFTEEN_MIN_MS -> REMINDER_15M_BEFORE
        ONE_HOUR_MS -> REMINDER_1H_BEFORE
        else -> REMINDER_KEEP_EXISTING
    }
}

private fun computeDueWallMs(
    preset: Int,
    existingDueDate: LocalDate?,
    todayDate: LocalDate,
    hour: Int,
    minute: Int,
    zoneId: ZoneId
): Long? {
    val targetDate = when (preset) {
        DUE_NONE -> return null
        DUE_TODAY -> todayDate
        DUE_TOMORROW -> todayDate.plusDays(1L)
        DUE_NEXT_WEEK -> todayDate.plusDays(7L)
        DUE_KEEP_EXISTING -> existingDueDate ?: return null
        else -> return null
    }
    return targetDate.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}

private fun computeReminderWallMs(
    preset: Int,
    existingReminderWallMs: Long?,
    dueWallMs: Long?,
    nowWallMs: Long
): Long? {
    return when (preset) {
        REMINDER_OFF -> null
        REMINDER_AT_DUE -> dueWallMs
        REMINDER_15M_BEFORE -> dueWallMs?.minus(FIFTEEN_MIN_MS)
        REMINDER_1H_BEFORE -> dueWallMs?.minus(ONE_HOUR_MS)
        REMINDER_IN_1H -> nowWallMs + ONE_HOUR_MS
        REMINDER_KEEP_EXISTING -> existingReminderWallMs
        else -> null
    }
}
