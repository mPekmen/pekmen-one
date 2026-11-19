package net.pekmen.one.feature.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.R as DesignR
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.TopicStatus

private val SUBJECT_PALETTE_COLORS = listOf(
    0xFF8B7CF6,
    0xFF60A5FA,
    0xFF6EE7B7,
    0xFFF472B6,
    0xFFFBBF24,
    0xFFFB7185
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectEditorSheet(
    existing: LearningSubject?,
    errorMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (existingId: String?, title: String, colorHex: Long, weeklyGoalMinutes: Int, dailyGoalMinutes: Int) -> Unit,
    onDelete: (String) -> Unit
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var goalText by remember(existing?.id) {
        mutableStateOf((existing?.weeklyGoalMinutes ?: 150).toString())
    }
    var dailyGoal by remember(existing?.id) { mutableStateOf((existing?.dailyGoalMinutes ?: 0).toString()) }
    var selectedColor by remember(existing?.id) {
        mutableLongStateOf(existing?.colorHex ?: SUBJECT_PALETTE_COLORS.first())
    }
    var confirmDelete by remember(existing?.id) { mutableStateOf(false) }

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
            Text(
                text = stringResource(
                    if (existing == null) R.string.learning_subject_create_title
                    else R.string.learning_subject_edit_title
                ),
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

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 80) title = it },
                label = { Text(text = stringResource(R.string.learning_subject_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = goalText,
                onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) goalText = it },
                label = { Text(text = stringResource(R.string.learning_subject_goal_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            ColorPaletteSelector(
                selectedColor = selectedColor,
                onSelect = { selectedColor = it }
            )
            LearningNumberField(dailyGoal, { dailyGoal = it }, R.string.learning_subject_daily_goal)

            if (existing != null) {
                DeleteConfirmButton(
                    confirmDelete = confirmDelete,
                    onClick = {
                        if (confirmDelete) onDelete(existing.id) else confirmDelete = true
                    }
                )
            }

            SheetActionButtons(
                onDismiss = onDismiss,
                onSave = {
                    onSave(
                        existing?.id,
                        title,
                        selectedColor,
                        goalText.toIntOrNull() ?: 0,
                        dailyGoal.toIntOrNull() ?: 0
                    )
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookEditorSheet(
    subjectId: String,
    existing: LearningBook?,
    errorMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (
        existingId: String?,
        subjectId: String,
        title: String,
        author: String?,
        totalPages: Int,
        currentPage: Int,
        totalChapters: Int,
        completedChapters: Int
    ) -> Unit,
    onDelete: (String) -> Unit
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var author by remember(existing?.id) { mutableStateOf(existing?.author.orEmpty()) }
    var totalPagesText by remember(existing?.id) {
        mutableStateOf((existing?.totalPages ?: 200).toString())
    }
    var currentPageText by remember(existing?.id) {
        mutableStateOf((existing?.currentPage ?: 0).toString())
    }
    var confirmDelete by remember(existing?.id) { mutableStateOf(false) }
    var totalChapters by remember(existing?.id) { mutableStateOf((existing?.totalChapters ?: 0).toString()) }
    var completedChapters by remember(existing?.id) { mutableStateOf((existing?.completedChapters ?: 0).toString()) }

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
            Text(
                text = stringResource(
                    if (existing == null) R.string.learning_book_create_title
                    else R.string.learning_book_edit_title
                ),
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

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 80) title = it },
                label = { Text(text = stringResource(R.string.learning_book_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = author,
                onValueChange = { if (it.length <= 80) author = it },
                label = { Text(text = stringResource(R.string.learning_book_author_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LearningNumberField(
                    totalPagesText, { totalPagesText = it }, R.string.learning_book_total_pages_label,
                    Modifier.weight(1f)
                )
                LearningNumberField(
                    currentPageText, { currentPageText = it }, R.string.learning_book_current_page_label,
                    Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LearningNumberField(
                    totalChapters, { totalChapters = it }, R.string.learning_chapters_total, Modifier.weight(1f)
                )
                LearningNumberField(
                    completedChapters, { completedChapters = it }, R.string.learning_chapters_done, Modifier.weight(1f)
                )
            }
            if (existing != null) {
                DeleteConfirmButton(
                    confirmDelete = confirmDelete,
                    onClick = {
                        if (confirmDelete) onDelete(existing.id) else confirmDelete = true
                    }
                )
            }

            SheetActionButtons(
                onDismiss = onDismiss,
                onSave = {
                    onSave(
                        existing?.id,
                        subjectId,
                        title,
                        author.ifBlank { null },
                        totalPagesText.toIntOrNull() ?: 0,
                        currentPageText.toIntOrNull() ?: 0,
                        totalChapters.toIntOrNull() ?: 0,
                        completedChapters.toIntOrNull() ?: 0
                    )
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicEditorSheet(
    subjectId: String,
    availableBooks: List<LearningBook>,
    availableTopics: List<LearningTopic>,
    existing: LearningTopic?,
    errorMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (
        existingId: String?,
        subjectId: String,
        bookId: String?,
        title: String,
        status: TopicStatus,
        parentTopicId: String?
    ) -> Unit,
    onDelete: (String) -> Unit,
    onViewHistory: (LearningTopic) -> Unit
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var selectedBookId by remember(existing?.id) { mutableStateOf(existing?.bookId) }
    var selectedParent by remember(existing?.id) { mutableStateOf(existing?.parentTopicId) }
    val canMove = existing == null || availableTopics.none { it.parentTopicId == existing.id }
    val validParents = if (canMove) availableTopics.filter { candidate ->
        candidate.id != existing?.id && candidate.parentTopicId == null &&
            (candidate.bookId == null || candidate.bookId == selectedBookId)
    } else emptyList()
    val resolvedParent = selectedParent?.takeIf { id -> validParents.any { it.id == id } }
    var selectedStatus by remember(existing?.id) { mutableStateOf(existing?.status ?: TopicStatus.TODO) }
    var confirmDelete by remember(existing?.id) { mutableStateOf(false) }

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
            Text(
                text = stringResource(
                    if (existing == null) R.string.learning_topic_create_title
                    else R.string.learning_topic_edit_title
                ),
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

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 80) title = it },
                label = { Text(text = stringResource(R.string.learning_topic_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (availableBooks.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.learning_topic_book_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedBookId == null,
                            enabled = canMove,
                            onClick = { selectedBookId = null },
                            label = { Text(text = stringResource(R.string.learning_topic_book_none)) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    items(availableBooks, key = { it.id }) { book ->
                        FilterChip(
                            selected = selectedBookId == book.id,
                            enabled = canMove,
                            onClick = { selectedBookId = book.id },
                            label = { Text(text = book.title) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }

            if (validParents.isNotEmpty()) {
                TopicParentSelector(validParents, resolvedParent) { selectedParent = it }
            }
            Text(
                text = stringResource(R.string.learning_topic_status_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TopicStatus.entries) { status ->
                    FilterChip(
                        selected = selectedStatus == status,
                        onClick = { selectedStatus = status },
                        label = { Text(text = topicStatusLabel(status)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            if (existing != null) {
                TextButton(onClick = { onViewHistory(existing) }) {
                    Text(stringResource(R.string.learning_topic_history))
                }
                DeleteConfirmButton(
                    confirmDelete = confirmDelete,
                    onClick = {
                        if (confirmDelete) onDelete(existing.id) else confirmDelete = true
                    }
                )
            }

            SheetActionButtons(
                onDismiss = onDismiss,
                onSave = {
                    onSave(existing?.id, subjectId, selectedBookId, title, selectedStatus, resolvedParent)
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun LearningNumberField(value: String, change: (String) -> Unit, label: Int, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) change(it) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun TopicParentSelector(topics: List<LearningTopic>, selectedId: String?, select: (String?) -> Unit) {
    Text(stringResource(R.string.learning_topic_parent), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(selectedId == null, { select(null) }, { Text(stringResource(R.string.learning_topic_root)) })
        }
        items(topics, key = { it.id }) { topic ->
            FilterChip(selectedId == topic.id, { select(topic.id) }, { Text(topic.title) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopicHistorySheet(topic: LearningTopic, entries: List<CompletedSessionItem>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(topic.title, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.learning_topic_history), style = MaterialTheme.typography.titleMedium)
            }
            if (entries.isEmpty()) item { Text(stringResource(R.string.learning_topic_history_empty)) }
            items(entries, key = { it.session.id }) { entry ->
                val session = entry.session
                val date = PekmenFormatters.formatMediumDate(session.effectiveDayDate, locale)
                val duration = PekmenFormatters.formatHumanDuration(context, session.actualFocusSec, locale)
                Text(stringResource(R.string.learning_topic_history_entry, date, duration))
                session.reflectionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ColorPaletteSelector(
    selectedColor: Long,
    onSelect: (Long) -> Unit
) {
    val colorCd = stringResource(R.string.learning_cd_select_color)
    Text(
        text = stringResource(R.string.learning_subject_color_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(SUBJECT_PALETTE_COLORS) { hex ->
            val selected = hex == selectedColor
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(hex), CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
                    .clickable { onSelect(hex) }
                    .semantics { contentDescription = colorCd }
            )
        }
    }
}

@Composable
private fun DeleteConfirmButton(
    confirmDelete: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Icon(painter = painterResource(PekmenIcons.Delete), contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (confirmDelete) R.string.learning_delete_confirm
                else DesignR.string.action_delete
            )
        )
    }
}

@Composable
private fun SheetActionButtons(
    onDismiss: () -> Unit,
    onSave: () -> Unit
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
            onClick = onSave,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Icon(painter = painterResource(PekmenIcons.Check), contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(DesignR.string.action_save))
        }
    }
}

@Composable
internal fun topicStatusLabel(status: TopicStatus): String {
    return when (status) {
        TopicStatus.TODO -> stringResource(R.string.learning_topic_status_todo)
        TopicStatus.IN_PROGRESS -> stringResource(R.string.learning_topic_status_in_progress)
        TopicStatus.MASTERED -> stringResource(R.string.learning_topic_status_mastered)
    }
}
