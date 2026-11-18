package net.pekmen.one.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.designsystem.R as DesignR
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.format.localizedName
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.FocusProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualSessionSheet(
    profiles: List<FocusProfile>,
    errorMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (ManualSessionInput) -> Unit
) {
    var selectedProfileId by remember(profiles) {
        mutableStateOf(profiles.firstOrNull()?.id.orEmpty())
    }
    var daysAgo by remember { mutableIntStateOf(0) }
    var hourText by remember { mutableStateOf("09") }
    var minuteText by remember { mutableStateOf("00") }
    var focusMinutesText by remember { mutableStateOf("25") }
    var breakMinutesText by remember { mutableStateOf("5") }
    var rating by remember { mutableIntStateOf(4) }
    var note by remember { mutableStateOf("") }

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
                text = stringResource(R.string.insights_manual_title),
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

            ProfileChipsSelector(
                profiles = profiles,
                selectedId = selectedProfileId,
                onSelect = { selectedProfileId = it }
            )

            Text(
                text = stringResource(R.string.insights_field_day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dayOptions = listOf(
                    0 to R.string.insights_day_today,
                    1 to R.string.insights_day_yesterday,
                    2 to R.string.insights_day_two_ago,
                    3 to R.string.insights_day_three_ago
                )
                items(dayOptions) { (offset, labelRes) ->
                    FilterChip(
                        selected = daysAgo == offset,
                        onClick = { daysAgo = offset },
                        label = { Text(text = stringResource(labelRes)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.insights_field_start_time),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) hourText = it },
                    label = { Text(text = stringResource(R.string.insights_field_hour)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minuteText,
                    onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) minuteText = it },
                    label = { Text(text = stringResource(R.string.insights_field_minute)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = focusMinutesText,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) focusMinutesText = it },
                    label = { Text(text = stringResource(R.string.insights_field_focus_min)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = breakMinutesText,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) breakMinutesText = it },
                    label = { Text(text = stringResource(R.string.insights_field_break_min)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            RatingSelectorRow(selectedRating = rating, onSelect = { rating = it })

            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 500) note = it },
                label = { Text(text = stringResource(R.string.insights_field_note)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

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
                    onClick = {
                        onSave(
                            ManualSessionInput(
                                profileId = selectedProfileId,
                                daysAgo = daysAgo,
                                hour = hourText.toIntOrNull() ?: 9,
                                minute = minuteText.toIntOrNull() ?: 0,
                                focusMinutes = focusMinutesText.toIntOrNull() ?: 0,
                                breakMinutes = breakMinutesText.toIntOrNull() ?: 0,
                                rating = rating,
                                note = note
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(DesignR.string.action_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSessionSheet(
    item: CompletedSessionItem,
    profiles: List<FocusProfile>,
    errorMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (
        sessionId: String,
        focusMinutes: Int,
        breakMinutes: Int,
        profileId: String,
        rating: Int?,
        note: String?
    ) -> Unit,
    onDelete: (String) -> Unit
) {
    var selectedProfileId by remember(item.session.id) { mutableStateOf(item.profile.id) }
    var focusMinText by remember(item.session.id) {
        mutableStateOf(((item.session.actualFocusSec + 30L) / 60L).toString())
    }
    var breakMinText by remember(item.session.id) {
        mutableStateOf(((item.session.actualBreakSec + 30L) / 60L).toString())
    }
    var rating by remember(item.session.id) { mutableIntStateOf(item.session.focusRating ?: 4) }
    var note by remember(item.session.id) { mutableStateOf(item.session.reflectionNote.orEmpty()) }
    var confirmDelete by remember(item.session.id) { mutableStateOf(false) }

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
                text = stringResource(R.string.insights_edit_title),
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

            ProfileChipsSelector(
                profiles = profiles,
                selectedId = selectedProfileId,
                onSelect = { selectedProfileId = it }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = focusMinText,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) focusMinText = it },
                    label = { Text(text = stringResource(R.string.insights_field_focus_min)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = breakMinText,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) breakMinText = it },
                    label = { Text(text = stringResource(R.string.insights_field_break_min)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            RatingSelectorRow(selectedRating = rating, onSelect = { rating = it })

            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 500) note = it },
                label = { Text(text = stringResource(R.string.insights_field_note)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            TextButton(
                onClick = {
                    if (confirmDelete) {
                        onDelete(item.session.id)
                    } else {
                        confirmDelete = true
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(painter = painterResource(PekmenIcons.Delete), contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (confirmDelete) {
                        stringResource(R.string.insights_delete_confirm)
                    } else {
                        stringResource(DesignR.string.action_delete)
                    }
                )
            }

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
                    onClick = {
                        onSave(
                            item.session.id,
                            focusMinText.toIntOrNull() ?: 0,
                            breakMinText.toIntOrNull() ?: 0,
                            selectedProfileId,
                            rating,
                            note
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(DesignR.string.action_save))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ProfileChipsSelector(
    profiles: List<FocusProfile>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.insights_field_profile),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(profiles, key = { it.id }) { profile ->
            FilterChip(
                selected = profile.id == selectedId,
                onClick = { onSelect(profile.id) },
                label = { Text(text = profile.localizedName()) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun RatingSelectorRow(
    selectedRating: Int,
    onSelect: (Int) -> Unit
) {
    Text(
        text = stringResource(R.string.insights_field_rating),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (value in 1..5) {
            FilterChip(
                selected = selectedRating == value,
                onClick = { onSelect(value) },
                label = { Text(text = PekmenFormatters.formatInteger(value)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            )
        }
    }
}
