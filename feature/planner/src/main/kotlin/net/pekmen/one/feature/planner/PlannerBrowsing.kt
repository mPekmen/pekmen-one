package net.pekmen.one.feature.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.ExternalCalendarItem
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskStatus
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class PlannerDisplay { AGENDA, WEEK, HISTORY }

data class PlannerWeekDay(
    val date: LocalDate,
    val tasks: List<PlannerTask>,
    val events: List<ExternalCalendarItem>
)

@Composable
internal fun PlannerDisplayPicker(current: PlannerDisplay, change: (PlannerDisplay) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(PekmenIcons.Calendar),
                contentDescription = stringResource(R.string.planner_display_menu, stringResource(current.label()))
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlannerDisplay.entries.forEach { display ->
                DropdownMenuItem(
                    text = { Text(stringResource(display.label())) },
                    onClick = {
                        expanded = false
                        change(display)
                    }
                )
            }
        }
    }
}

private fun PlannerDisplay.label() = when (this) {
    PlannerDisplay.AGENDA -> R.string.planner_display_agenda
    PlannerDisplay.WEEK -> R.string.planner_display_week
    PlannerDisplay.HISTORY -> R.string.planner_display_history
}

@Composable
internal fun PlannerWeekNavigation(days: List<PlannerWeekDay>, move: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { move(-1) }) { Text(stringResource(R.string.planner_previous_week)) }
        TextButton(onClick = { move(1) }) { Text(stringResource(R.string.planner_next_week)) }
    }
    if (days.isNotEmpty()) {
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        Text(
            stringResource(
                R.string.planner_week_range,
                days.first().date.format(format),
                days.last().date.format(format)
            ),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
internal fun PlannerWeekDayCard(day: PlannerWeekDay, onEdit: (PlannerTask) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                day.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                style = MaterialTheme.typography.titleMedium
            )
            if (day.tasks.isEmpty() && day.events.isEmpty()) {
                Text(stringResource(R.string.planner_week_day_empty))
            }
            day.tasks.forEach { PlannerHistoryRow(it, onEdit) }
            day.events.forEach { Text(it.title, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
internal fun PlannerHistoryRow(task: PlannerTask, onEdit: (PlannerTask) -> Unit, historyZone: ZoneId? = null) {
    val state = when (task.status) {
        TaskStatus.OPEN -> R.string.planner_status_open
        TaskStatus.COMPLETED -> R.string.planner_status_completed
        TaskStatus.CANCELLED -> R.string.planner_status_cancelled
    }
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onEdit(task) }.padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(task.title, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(state), style = MaterialTheme.typography.labelLarge)
        if (historyZone != null) {
            val changed = task.completedAtWallMs ?: task.lastUpdatedWallMs
            val date = Instant.ofEpochMilli(changed).atZone(historyZone).toLocalDate()
            Text(PekmenFormatters.formatMediumDate(date), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun filterPlannerHistory(
    tasks: List<PlannerTask>,
    status: TaskStatus?,
    days: Int,
    now: Long,
    zone: ZoneId
): List<PlannerTask> {
    val minDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days.toLong() - 1L)
    val lower = if (days > 0) minDate.atStartOfDay(zone).toInstant().toEpochMilli() else Long.MIN_VALUE
    return tasks.filter { task ->
        (status == null || task.status == status) && (task.completedAtWallMs ?: task.lastUpdatedWallMs) >= lower
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlannerHistoryFilters(
    status: TaskStatus?,
    days: Int,
    selectStatus: (TaskStatus?) -> Unit,
    selectDays: (Int) -> Unit
) {
    val statusOptions = listOf(
        null to R.string.planner_history_all_statuses,
        TaskStatus.COMPLETED to R.string.planner_status_completed,
        TaskStatus.CANCELLED to R.string.planner_status_cancelled
    )
    val dateOptions = listOf(0 to R.string.planner_history_all_dates, 7 to R.string.planner_history_7_days,
        30 to R.string.planner_history_30_days)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        statusOptions.forEach { (value, label) ->
            FilterChip(status == value, { selectStatus(value) }, { Text(stringResource(label)) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        dateOptions.forEach { (value, label) ->
            FilterChip(days == value, { selectDays(value) }, { Text(stringResource(label)) })
        }
    }
}
