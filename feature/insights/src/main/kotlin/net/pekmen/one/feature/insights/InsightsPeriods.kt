package net.pekmen.one.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.model.BuiltInProfileSlug
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

enum class InsightsPeriod { DAY, WEEK, MONTH }

data class InsightsPeriodSummary(
    val period: InsightsPeriod,
    val start: LocalDate,
    val end: LocalDate,
    val focusSeconds: Long,
    val breakSeconds: Long,
    val completed: Int,
    val categorySeconds: Map<BuiltInProfileSlug, Long>,
    val subjectSeconds: Map<String, Long>,
    val goalMinutes: Int
)

internal fun InsightsPeriod.startOn(today: LocalDate): LocalDate = when (this) {
    InsightsPeriod.DAY -> today
    InsightsPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    InsightsPeriod.MONTH -> today.withDayOfMonth(1)
}

internal fun periodSummary(
    period: InsightsPeriod,
    start: LocalDate,
    end: LocalDate,
    rows: List<CompletedSessionItem>,
    goalMinutes: Int
): InsightsPeriodSummary {
    val sessions = rows.filter { it.session.effectiveDayDate in start..end }
    return InsightsPeriodSummary(
        period = period,
        start = start,
        end = end,
        focusSeconds = sessions.sumOf { it.session.actualFocusSec },
        breakSeconds = sessions.sumOf { it.session.actualBreakSec },
        completed = sessions.size,
        categorySeconds = sessions.groupBy { it.profile.builtInSlug }
            .mapValues { (_, items) -> items.sumOf { it.session.actualFocusSec } },
        subjectSeconds = sessions.mapNotNull { row -> row.session.subjectId?.let { it to row.session.actualFocusSec } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, durations) -> durations.sum() },
        goalMinutes = goalMinutes
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InsightsPeriodCard(
    summary: InsightsPeriodSummary,
    subjectNames: Map<String, String>,
    change: (InsightsPeriod) -> Unit
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.insights_period_title), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InsightsPeriod.entries.forEach { option ->
                    val title = when (option) {
                        InsightsPeriod.DAY -> R.string.insights_period_day
                        InsightsPeriod.WEEK -> R.string.insights_period_week
                        InsightsPeriod.MONTH -> R.string.insights_period_month
                    }
                    FilterChip(
                        selected = option == summary.period,
                        onClick = { change(option) },
                        label = { Text(stringResource(title)) }
                    )
                }
            }
            val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            Text(
                stringResource(R.string.insights_period_dates, summary.start.format(format), summary.end.format(format))
            )
            Text(
                stringResource(
                    R.string.insights_period_totals,
                    PekmenFormatters.formatHumanDuration(context, summary.focusSeconds),
                    PekmenFormatters.formatHumanDuration(context, summary.breakSeconds),
                    summary.completed
                )
            )
            if (summary.goalMinutes > 0) {
                Text(stringResource(R.string.insights_period_goal, summary.focusSeconds / 60L, summary.goalMinutes))
            }
            summary.categorySeconds.entries.sortedByDescending { it.value }.forEach { (category, seconds) ->
                val name = when (category) {
                    BuiltInProfileSlug.STUDY -> R.string.insights_category_study
                    BuiltInProfileSlug.WORK -> R.string.insights_category_work
                    BuiltInProfileSlug.SLEEP -> R.string.insights_category_sleep
                    BuiltInProfileSlug.PERSONAL -> R.string.insights_category_personal
                    BuiltInProfileSlug.CUSTOM -> R.string.insights_category_custom
                }
                PeriodValue(stringResource(name), seconds)
            }
            summary.subjectSeconds.entries.sortedByDescending { it.value }.forEach { (subjectId, seconds) ->
                subjectNames[subjectId]?.let { name -> PeriodValue(name, seconds) }
            }
        }
    }
}

@Composable
private fun PeriodValue(label: String, seconds: Long) {
    val duration = PekmenFormatters.formatHumanDuration(LocalContext.current, seconds)
    Text(stringResource(R.string.insights_period_row, label, duration))
}
