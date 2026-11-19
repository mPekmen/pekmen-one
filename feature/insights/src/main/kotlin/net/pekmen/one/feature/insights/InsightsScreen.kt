package net.pekmen.one.feature.insights

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.format.localizedName
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.ProfileFocusBreakdown
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.WeeklyFocusSummary
import java.text.NumberFormat

@Composable
fun InsightsRoute(
    viewModel: InsightsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    InsightsScreen(
        state = state,
        onOpenManual = viewModel::openManualSheet,
        onDismissManual = viewModel::dismissManualSheet,
        onSaveManual = viewModel::saveManualSession,
        onOpenEdit = viewModel::openEditSheet,
        onDismissEdit = viewModel::dismissEditSheet,
        onSaveEdit = viewModel::saveEditedSession,
        onDeleteSession = viewModel::deleteSession,
        modifier = modifier
    )
}

@Composable
fun InsightsScreen(
    state: InsightsUiState,
    onOpenManual: () -> Unit,
    onDismissManual: () -> Unit,
    onSaveManual: (ManualSessionInput) -> Unit,
    onOpenEdit: (CompletedSessionItem) -> Unit,
    onDismissEdit: () -> Unit,
    onSaveEdit: (String, Int, Int, String, Int?, String?) -> Unit,
    onDeleteSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val subjectsById = state.subjectWeeklyProgress.associate { it.subject.id to it.subject }
    val booksById = state.subjectWeeklyProgress
        .flatMap { it.books }
        .associateBy { it.id }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.insights_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    FilledTonalButton(
                        onClick = onOpenManual,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Add), contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.insights_log_past_button))
                    }
                }
            }

            state.todaySummary?.let { today ->
                item {
                    DailySummaryCard(summary = today)
                }
            }

            state.weeklySummary?.let { week ->
                item {
                    WeeklySummaryCard(summary = week)
                }
                if (week.profileBreakdown.isNotEmpty()) {
                    item {
                        ProfileBreakdownCard(
                            totalFocusSec = week.totalFocusSeconds.coerceAtLeast(1L),
                            items = week.profileBreakdown
                        )
                    }
                }
            }

            if (state.subjectWeeklyProgress.isNotEmpty()) {
                item {
                    WeeklySubjectGoalsCard(items = state.subjectWeeklyProgress)
                }
            }

            item {
                Text(
                    text = stringResource(R.string.insights_recent_sessions_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (state.recentSessions.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.insights_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.insights_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(state.recentSessions, key = { it.session.id }) { item ->
                    SessionHistoryCard(
                        item = item,
                        subject = item.session.subjectId?.let { subjectsById[it] },
                        book = item.session.bookId?.let { booksById[it] },
                        onClick = { onOpenEdit(item) }
                    )
                }
            }
        }
    }

    if (state.isManualSheetOpen) {
        ManualSessionSheet(
            profiles = state.profiles,
            errorMessageRes = state.errorMessageRes,
            onDismiss = onDismissManual,
            onSave = onSaveManual
        )
    }

    state.editingSession?.let { item ->
        EditSessionSheet(
            item = item,
            profiles = state.profiles,
            errorMessageRes = state.errorMessageRes,
            onDismiss = onDismissEdit,
            onSave = onSaveEdit,
            onDelete = onDeleteSession
        )
    }
}

@Composable
private fun DailySummaryCard(summary: DailyFocusSummary) {
    val context = LocalContext.current
    val ratingFormatted = summary.averageFocusRating?.let {
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it)
    } ?: "—"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.insights_today_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCell(
                    label = stringResource(R.string.insights_metric_focus),
                    value = PekmenFormatters.formatHumanDuration(context, summary.netFocusSeconds),
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = stringResource(R.string.insights_metric_break),
                    value = PekmenFormatters.formatHumanDuration(context, summary.breakSeconds),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCell(
                    label = stringResource(R.string.insights_metric_sessions),
                    value = PekmenFormatters.formatInteger(summary.completedSessionCount),
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = stringResource(R.string.insights_metric_avg_rating),
                    value = ratingFormatted,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WeeklySummaryCard(summary: WeeklyFocusSummary) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.insights_week_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = PekmenFormatters.formatHumanDuration(context, summary.totalFocusSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            WeeklyBarChart(days = summary.dailyBreakdown)
        }
    }
}

@Composable
private fun ProfileBreakdownCard(
    totalFocusSec: Long,
    items: List<ProfileFocusBreakdown>
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.insights_profile_breakdown_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            items.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = entry.profile.localizedName(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = PekmenFormatters.formatHumanDuration(context, entry.focusSeconds),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (entry.focusSeconds.toFloat() / totalFocusSec.toFloat()).coerceIn(0f, 1f) },
                        color = Color(entry.profile.colorHex),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklySubjectGoalsCard(items: List<SubjectWeeklyProgress>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.insights_subject_goals_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            items.forEach { entry ->
                val focusedMinutes = entry.netFocusSecondsThisWeek / 60L
                val accent = Color(entry.subject.colorHex)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(accent, CircleShape)
                            )
                            Text(
                                text = entry.subject.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.insights_subject_goal_progress,
                                PekmenFormatters.formatInteger(focusedMinutes),
                                PekmenFormatters.formatInteger(entry.subject.weeklyGoalMinutes),
                                PekmenFormatters.formatInteger(entry.completedSessionCount)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { entry.goalProgressFraction },
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionHistoryCard(
    item: CompletedSessionItem,
    subject: LearningSubject?,
    book: LearningBook?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val session = item.session
    val dateLabel = PekmenFormatters.formatMediumDate(session.effectiveDayDate)
    val startTimeLabel = PekmenFormatters.formatShortTime(session.startedAtWallMs)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(item.profile.colorHex), CircleShape)
                    )
                    Text(
                        text = item.profile.localizedName(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(R.string.insights_session_timestamp, dateLabel, startTimeLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(
                    R.string.insights_session_line,
                    PekmenFormatters.formatHumanDuration(context, session.actualFocusSec),
                    PekmenFormatters.formatHumanDuration(context, session.actualBreakSec)
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            LinkedLearningMetadataLine(session = session, subject = subject, book = book)

            session.reflectionNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SessionBadgesRow(session = session, onClick = onClick)
        }
    }
}

@Composable
private fun LinkedLearningMetadataLine(
    session: FocusSession,
    subject: LearningSubject?,
    book: LearningBook?
) {
    if (subject == null && book == null) return
    val startPage = session.bookStartPage
    val endPage = session.bookEndPage
    val bookPart = if (book != null && startPage != null && endPage != null) {
        stringResource(
            R.string.insights_session_book_pages,
            book.title,
            PekmenFormatters.formatInteger(startPage),
            PekmenFormatters.formatInteger(endPage)
        )
    } else {
        book?.title
    }
    val combined = listOfNotNull(subject?.title, bookPart).joinToString(" · ")
    if (combined.isEmpty()) return
    Text(
        text = combined,
        style = MaterialTheme.typography.labelLarge,
        color = subject?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionBadgesRow(
    session: FocusSession,
    onClick: () -> Unit
) {
    val badges = buildList {
        session.focusRating?.let {
            add(stringResource(R.string.insights_rating_short, PekmenFormatters.formatInteger(it)))
        }
        if (session.isManualEntry) add(stringResource(R.string.insights_badge_manual))
        if (session.wasManuallyAdjusted) add(stringResource(R.string.insights_badge_adjusted))
        if (session.interruptedByReboot) add(stringResource(R.string.insights_badge_reboot))
    }
    if (badges.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        badges.forEach { badgeText ->
            AssistChip(
                onClick = onClick,
                label = { Text(text = badgeText) }
            )
        }
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
