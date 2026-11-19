package net.pekmen.one.feature.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.designsystem.R as DesignR
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.ActiveSessionSnapshot
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.TopicStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionWrapUpSheet(
    snapshot: ActiveSessionSnapshot,
    onDismiss: () -> Unit,
    onSave: (trimmedFocusSeconds: Long?, rating: Int?, note: String?) -> Unit,
    onDiscardShort: () -> Unit,
    linkedSubject: LearningSubject? = null,
    linkedBook: LearningBook? = null,
    linkedTopic: LearningTopic? = null,
    isSaving: Boolean = false,
    onSaveWithLearning: ((
        trimmedFocusSeconds: Long?,
        rating: Int?,
        note: String?,
        bookEndPage: Int?,
        markTopicMastered: Boolean
    ) -> Unit)? = null
) {
    val context = LocalContext.current
    val recordedFocusSec = snapshot.session.actualFocusSec
    val recordedBreakSec = snapshot.session.actualBreakSec
    val initialMinutes = ((recordedFocusSec + 30L) / 60L).toInt().coerceAtLeast(0)

    var adjustedMinutesText by remember(snapshot.session.id) {
        mutableStateOf(initialMinutes.toString())
    }
    var selectedRating by remember(snapshot.session.id) {
        mutableIntStateOf(snapshot.session.focusRating ?: 4)
    }
    var noteText by remember(snapshot.session.id) {
        mutableStateOf(snapshot.session.reflectionNote.orEmpty())
    }
    var bookEndPageText by remember(snapshot.session.id, linkedBook?.id) {
        mutableStateOf((snapshot.session.bookEndPage ?: linkedBook?.currentPage)?.toString().orEmpty())
    }
    var markTopicMastered by remember(snapshot.session.id, linkedTopic?.id) {
        mutableStateOf(false)
    }

    val adjustedMinutes = adjustedMinutesText.toIntOrNull()?.coerceIn(0, 960) ?: initialMinutes
    val isModified = adjustedMinutes != initialMinutes
    val focusSummaryText = PekmenFormatters.formatHumanDuration(context, recordedFocusSec)
    val breakSummaryText = PekmenFormatters.formatHumanDuration(context, recordedBreakSec)
    val isUnderShortThreshold = recordedFocusSec < FocusTimeMath.MIN_VALID_SESSION_SECONDS

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.focus_wrapup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    R.string.focus_wrapup_recorded_summary,
                    focusSummaryText,
                    breakSummaryText
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            linkedSubject?.let { subject ->
                AssistChip(
                    onClick = {},
                    label = { Text(text = subject.title) }
                )
            }

            if (linkedBook != null) {
                OutlinedTextField(
                    value = bookEndPageText,
                    onValueChange = { input ->
                        if (input.length <= 5 && input.all { it.isDigit() }) {
                            bookEndPageText = input
                        }
                    },
                    label = {
                        Text(
                            text = stringResource(
                                R.string.focus_wrapup_book_end_page_label,
                                PekmenFormatters.formatInteger(linkedBook.currentPage),
                                PekmenFormatters.formatInteger(linkedBook.totalPages)
                            )
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (linkedTopic != null && linkedTopic.status != TopicStatus.MASTERED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { markTopicMastered = !markTopicMastered },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = markTopicMastered,
                        onCheckedChange = { markTopicMastered = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.focus_wrapup_mark_topic_mastered,
                            linkedTopic.title
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Text(
                text = stringResource(R.string.focus_wrapup_adjust_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.focus_wrapup_adjust_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val minusCd = stringResource(R.string.focus_cd_decrease_minutes)
                val plusCd = stringResource(R.string.focus_cd_increase_minutes)
                FilledTonalButton(
                    onClick = {
                        val next = (adjustedMinutes - 5).coerceAtLeast(0)
                        adjustedMinutesText = next.toString()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = minusCd }
                ) {
                    Text(text = stringResource(R.string.focus_custom_minus_5))
                }
                OutlinedTextField(
                    value = adjustedMinutesText,
                    onValueChange = { input ->
                        if (input.length <= 3 && input.all { it.isDigit() }) {
                            adjustedMinutesText = input
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        val next = (adjustedMinutes + 5).coerceAtMost(960)
                        adjustedMinutesText = next.toString()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = plusCd }
                ) {
                    Text(text = stringResource(R.string.focus_custom_plus_5))
                }
            }

            Text(
                text = stringResource(R.string.focus_wrapup_rating_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (value in 1..5) {
                    val label = PekmenFormatters.formatInteger(value)
                    val ratingCd = stringResource(R.string.focus_wrapup_rating_cd, label)
                    FilterChip(
                        selected = selectedRating == value,
                        onClick = { selectedRating = value },
                        label = { Text(text = label) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = ratingCd }
                    )
                }
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { if (it.length <= 500) noteText = it },
                label = { Text(text = stringResource(R.string.focus_wrapup_note_label)) },
                placeholder = { Text(text = stringResource(R.string.focus_wrapup_note_placeholder)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            if (isUnderShortThreshold) {
                TextButton(
                    onClick = onDiscardShort,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        painter = painterResource(PekmenIcons.Delete),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.focus_wrapup_discard_short))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(DesignR.string.action_cancel))
                }
                Button(
                    onClick = {
                        val trimmedSec = if (isModified) adjustedMinutes * 60L else null
                        val parsedPage = if (linkedBook != null) bookEndPageText.toIntOrNull() else null
                        if (onSaveWithLearning != null) {
                            onSaveWithLearning(
                                trimmedSec,
                                selectedRating,
                                noteText,
                                parsedPage,
                                markTopicMastered
                            )
                        } else {
                            onSave(trimmedSec, selectedRating, noteText)
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        painter = painterResource(PekmenIcons.Check),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.focus_wrapup_save))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
