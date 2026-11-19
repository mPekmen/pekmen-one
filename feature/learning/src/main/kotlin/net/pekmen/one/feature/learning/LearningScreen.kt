package net.pekmen.one.feature.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.TopicStatus
import java.text.NumberFormat

@Composable
fun LearningRoute(
    viewModel: LearningViewModel,
    onFocusOnSubject: (subjectId: String, bookId: String?, topicId: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.topicHistory.collectAsStateWithLifecycle()

    LearningScreen(
        state = state,
        onAddSubject = viewModel::openCreateSubject,
        onEditSubject = viewModel::openEditSubject,
        onAddBook = viewModel::openCreateBook,
        onEditBook = viewModel::openEditBook,
        onStepBookPage = viewModel::stepBookPage,
        onAddTopic = viewModel::openCreateTopic,
        onEditTopic = viewModel::openEditTopic,
        onCycleTopicStatus = viewModel::cycleTopicStatus,
        onFocusOnSubject = onFocusOnSubject,
        onDismissEditor = viewModel::dismissEditor,
        onSaveSubject = viewModel::saveSubject,
        onDeleteSubject = viewModel::deleteSubject,
        onSaveBook = viewModel::saveBook,
        onDeleteBook = viewModel::deleteBook,
        onSaveTopic = viewModel::saveTopic,
        onDeleteTopic = viewModel::deleteTopic,
        onViewTopicHistory = viewModel::openTopicHistory,
        topicHistory = history,
        modifier = modifier
    )
}

@Composable
fun LearningScreen(
    state: LearningUiState,
    onAddSubject: () -> Unit,
    onEditSubject: (LearningSubject) -> Unit,
    onAddBook: (String) -> Unit,
    onEditBook: (LearningBook) -> Unit,
    onStepBookPage: (LearningBook, Int) -> Unit,
    onAddTopic: (String) -> Unit,
    onEditTopic: (LearningTopic) -> Unit,
    onCycleTopicStatus: (LearningTopic) -> Unit,
    onFocusOnSubject: (subjectId: String, bookId: String?, topicId: String?) -> Unit = { _, _, _ -> },
    onDismissEditor: () -> Unit,
    onSaveSubject: (String?, String, Long, Int, Int) -> Unit,
    onDeleteSubject: (String) -> Unit,
    onSaveBook: (String?, String, String, String?, Int, Int, Int, Int) -> Unit,
    onDeleteBook: (String) -> Unit,
    onSaveTopic: (String?, String, String?, String, TopicStatus, String?) -> Unit,
    onDeleteTopic: (String) -> Unit,
    onViewTopicHistory: (LearningTopic) -> Unit = {},
    topicHistory: List<CompletedSessionItem> = emptyList(),
    modifier: Modifier = Modifier
) {
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
                        text = stringResource(R.string.learning_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    FilledTonalButton(
                        onClick = onAddSubject,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Add), contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.learning_add_subject))
                    }
                }
            }

            if (state.subjectProgress.isEmpty()) {
                item {
                    EmptyLearningCard(onAddSubject = onAddSubject)
                }
            } else {
                items(state.subjectProgress, key = { it.subject.id }) { progress ->
                    SubjectProgressCard(
                        progress = progress,
                        onEditSubject = { onEditSubject(progress.subject) },
                        onFocus = {
                            val topic = progress.topics.firstOrNull()
                            onFocusOnSubject(
                                progress.subject.id,
                                if (topic == null) progress.books.firstOrNull()?.id else topic.bookId,
                                topic?.id
                            )
                        },
                        onAddBook = { onAddBook(progress.subject.id) },
                        onEditBook = onEditBook,
                        onStepBookPage = onStepBookPage,
                        onAddTopic = { onAddTopic(progress.subject.id) },
                        onEditTopic = onEditTopic,
                        onCycleTopicStatus = onCycleTopicStatus
                    )
                }
            }
        }
    }

    when (val modal = state.editorModal) {
        is LearningEditorModal.SubjectEditor -> SubjectEditorSheet(
            existing = modal.existing,
            errorMessageRes = state.errorMessageRes,
            onDismiss = onDismissEditor,
            onSave = onSaveSubject,
            onDelete = onDeleteSubject
        )
        is LearningEditorModal.BookEditor -> BookEditorSheet(
            subjectId = modal.subjectId,
            existing = modal.existing,
            errorMessageRes = state.errorMessageRes,
            onDismiss = onDismissEditor,
            onSave = onSaveBook,
            onDelete = onDeleteBook
        )
        is LearningEditorModal.TopicEditor -> TopicEditorSheet(
            subjectId = modal.subjectId,
            availableBooks = modal.availableBooks,
            availableTopics = state.subjectProgress.firstOrNull { it.subject.id == modal.subjectId }?.topics.orEmpty(),
            existing = modal.existing,
            errorMessageRes = state.errorMessageRes,
            onDismiss = onDismissEditor,
            onSave = onSaveTopic,
            onDelete = onDeleteTopic,
            onViewHistory = onViewTopicHistory
        )
        is LearningEditorModal.TopicHistory -> TopicHistorySheet(
            topic = modal.topic,
            entries = topicHistory.filter { it.session.topicId == modal.topic.id },
            onDismiss = onDismissEditor
        )
        null -> Unit
    }
}

@Composable
private fun EmptyLearningCard(onAddSubject: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.learning_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.learning_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onAddSubject,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(painter = painterResource(PekmenIcons.Add), contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.learning_add_subject))
            }
        }
    }
}

@Composable
private fun SubjectProgressCard(
    progress: SubjectWeeklyProgress,
    onEditSubject: () -> Unit,
    onFocus: () -> Unit,
    onAddBook: () -> Unit,
    onEditBook: (LearningBook) -> Unit,
    onStepBookPage: (LearningBook, Int) -> Unit,
    onAddTopic: () -> Unit,
    onEditTopic: (LearningTopic) -> Unit,
    onCycleTopicStatus: (LearningTopic) -> Unit
) {
    val subject = progress.subject
    val accent = Color(subject.colorHex)
    val focusedMinutes = progress.netFocusSecondsThisWeek / 60L
    val editSubjectCd = stringResource(R.string.learning_cd_edit_subject, subject.title)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(accent, CircleShape)
                    )
                    Text(
                        text = subject.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onFocus,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Play), contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.learning_focus_on_subject))
                    }
                    IconButton(
                        onClick = onEditSubject,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(PekmenIcons.Edit),
                            contentDescription = editSubjectCd
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SubjectGoalDetails(progress)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(
                            R.string.learning_weekly_goal_progress,
                            PekmenFormatters.formatInteger(focusedMinutes),
                            PekmenFormatters.formatInteger(subject.weeklyGoalMinutes)
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.learning_weekly_sessions_count,
                            PekmenFormatters.formatInteger(progress.completedSessionCount)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { progress.goalProgressFraction },
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeaderRow(
                title = stringResource(R.string.learning_books_header),
                actionLabel = stringResource(R.string.learning_add_book),
                onAction = onAddBook
            )
            if (progress.books.isEmpty()) {
                Text(
                    text = stringResource(R.string.learning_no_books),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                progress.books.forEach { book ->
                    BookItemRow(
                        book = book,
                        accent = accent,
                        onEdit = { onEditBook(book) },
                        onDecreasePage = { onStepBookPage(book, -1) },
                        onIncreasePage = { onStepBookPage(book, 1) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeaderRow(
                title = stringResource(R.string.learning_topics_header),
                actionLabel = stringResource(R.string.learning_add_topic),
                onAction = onAddTopic
            )
            if (progress.topics.isEmpty()) {
                Text(
                    text = stringResource(R.string.learning_no_topics),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val topicsByParent = progress.topics.groupBy { it.parentTopicId }
                val topicIds = progress.topics.mapTo(HashSet()) { it.id }
                val roots = progress.topics.filter { it.parentTopicId !in topicIds }
                val ordered = roots.flatMap { root ->
                    listOf(root) + topicsByParent[root.id].orEmpty()
                }
                ordered.forEach { topic ->
                    TopicItemRow(
                        topic = topic,
                        isChild = topic.parentTopicId != null,
                        onCycleStatus = { onCycleTopicStatus(topic) },
                        onEdit = { onEditTopic(topic) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(
            onClick = onAction,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(painter = painterResource(PekmenIcons.Add), contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = actionLabel)
        }
    }
}

@Composable
private fun BookItemRow(
    book: LearningBook,
    accent: Color,
    onEdit: () -> Unit,
    onDecreasePage: () -> Unit,
    onIncreasePage: () -> Unit
) {
    val editCd = stringResource(R.string.learning_cd_edit_book, book.title)
    val chapterOnly = book.totalPages == 0 && book.totalChapters > 0
    val prevCd = stringResource(if (chapterOnly) R.string.learning_cd_prev_chapter else R.string.learning_cd_prev_page)
    val nextCd = stringResource(if (chapterOnly) R.string.learning_cd_next_chapter else R.string.learning_cd_next_page)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val resId = if (book.isCompleted) {
                    R.string.learning_book_pages_completed
                } else {
                    R.string.learning_book_pages_progress
                }
                val subtitle = stringResource(
                    resId,
                    PekmenFormatters.formatInteger(book.currentPage),
                    PekmenFormatters.formatInteger(book.totalPages)
                )
                if (!chapterOnly) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (book.isCompleted) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (book.totalChapters > 0) {
                    Text(
                        stringResource(
                            R.string.learning_chapters_progress,
                            PekmenFormatters.formatInteger(book.completedChapters),
                            PekmenFormatters.formatInteger(book.totalChapters)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onDecreasePage,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = prevCd }
                ) {
                    Text(text = stringResource(R.string.learning_symbol_minus))
                }
                FilledTonalButton(
                    onClick = onIncreasePage,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = nextCd }
                ) {
                    Text(text = stringResource(R.string.learning_symbol_plus))
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(painter = painterResource(PekmenIcons.Edit), contentDescription = editCd)
                }
            }
        }
        val progress = if (book.totalPages > 0) book.pageProgressFraction
        else if (book.totalChapters > 0) book.completedChapters.toFloat() / book.totalChapters else 0f
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
    }
}

@Composable
private fun SubjectGoalDetails(progress: SubjectWeeklyProgress) {
    val subject = progress.subject
    val locale = LocalContext.current.resources.configuration.locales[0]
    if (subject.dailyGoalMinutes > 0) {
        Text(
            stringResource(
                R.string.learning_daily_goal_progress,
                PekmenFormatters.formatInteger(progress.netFocusSecondsToday / 60, locale),
                PekmenFormatters.formatInteger(subject.dailyGoalMinutes, locale)
            ),
            style = MaterialTheme.typography.labelLarge
        )
    }
    val target = if (subject.weeklyGoalMinutes == 0) {
        stringResource(R.string.learning_weekly_goal_unset)
    } else {
        val fraction = progress.netFocusSecondsThisWeek / (subject.weeklyGoalMinutes * 60.0)
        val percent = NumberFormat.getPercentInstance(locale).format(fraction)
        stringResource(R.string.learning_weekly_goal_percentage, percent)
    }
    Text(target, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TopicItemRow(
    topic: LearningTopic,
    isChild: Boolean,
    onCycleStatus: () -> Unit,
    onEdit: () -> Unit
) {
    val editCd = stringResource(R.string.learning_cd_edit_topic, topic.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = if (isChild) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = topic.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = onCycleStatus,
                label = { Text(text = topicStatusLabel(topic.status)) }
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(painter = painterResource(PekmenIcons.Edit), contentDescription = editCd)
            }
        }
    }
}
