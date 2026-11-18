package net.pekmen.one.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.data.SaveProfileRequest
import net.pekmen.one.core.designsystem.R as DesignR
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionMode

private val PROFILE_COLOR_SWATCHES = listOf(
    0xFF8B7CF6,
    0xFF60A5FA,
    0xFF6EE7B7,
    0xFFF472B6,
    0xFFFBBF24,
    0xFFFB7185
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorSheet(
    existingProfile: FocusProfile?,
    errorMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (SaveProfileRequest) -> Unit
) {
    val context = LocalContext.current
    val initialName = resolveInitialProfileName(existingProfile, context)

    var nameText by remember(existingProfile?.id) { mutableStateOf(initialName) }
    var colorHex by remember(existingProfile?.id) {
        mutableLongStateOf(existingProfile?.colorHex ?: PROFILE_COLOR_SWATCHES.first())
    }
    var defaultMode by remember(existingProfile?.id) {
        mutableStateOf(existingProfile?.defaultMode ?: FocusSessionMode.POMODORO)
    }
    var workMinText by remember(existingProfile?.id) {
        mutableStateOf((existingProfile?.workMinutes ?: 25).toString())
    }
    var shortBreakText by remember(existingProfile?.id) {
        mutableStateOf((existingProfile?.shortBreakMinutes ?: 5).toString())
    }
    var longBreakText by remember(existingProfile?.id) {
        mutableStateOf((existingProfile?.longBreakMinutes ?: 15).toString())
    }
    var cyclesText by remember(existingProfile?.id) {
        mutableStateOf((existingProfile?.cyclesBeforeLongBreak ?: 4).toString())
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
            ProfileEditorHeader(isEditing = existingProfile != null, errorMessageRes = errorMessageRes)

            OutlinedTextField(
                value = nameText,
                onValueChange = { if (it.length <= 40) nameText = it },
                label = { Text(text = stringResource(R.string.settings_profile_name_label)) },
                singleLine = true,
                enabled = existingProfile == null || existingProfile.builtInSlug == BuiltInProfileSlug.CUSTOM,
                modifier = Modifier.fillMaxWidth()
            )

            ProfileColorPicker(selectedHex = colorHex, onSelectHex = { colorHex = it })
            ProfileModeSelector(selectedMode = defaultMode, onSelectMode = { defaultMode = it })

            ProfileDurationFields(
                workMinText = workMinText,
                shortBreakText = shortBreakText,
                longBreakText = longBreakText,
                cyclesText = cyclesText,
                onWorkChange = { workMinText = it },
                onShortBreakChange = { shortBreakText = it },
                onLongBreakChange = { longBreakText = it },
                onCyclesChange = { cyclesText = it }
            )

            ProfileEditorActions(
                onDismiss = onDismiss,
                onConfirm = {
                    val submittedName = if (existingProfile != null &&
                        existingProfile.builtInSlug != BuiltInProfileSlug.CUSTOM
                    ) {
                        ""
                    } else {
                        nameText
                    }
                    onSave(
                        SaveProfileRequest(
                            existingId = existingProfile?.id,
                            customName = submittedName,
                            colorHex = colorHex,
                            defaultMode = defaultMode,
                            workMinutes = workMinText.toIntOrNull() ?: 25,
                            shortBreakMinutes = shortBreakText.toIntOrNull() ?: 5,
                            longBreakMinutes = longBreakText.toIntOrNull() ?: 15,
                            cyclesBeforeLongBreak = cyclesText.toIntOrNull() ?: 4
                        )
                    )
                }
            )
        }
    }
}

private fun resolveInitialProfileName(profile: FocusProfile?, context: android.content.Context): String {
    if (profile == null) return ""
    return if (profile.builtInSlug == BuiltInProfileSlug.CUSTOM || !profile.customName.isNullOrBlank()) {
        profile.customName.orEmpty()
    } else {
        PekmenFormatters.profileDisplayName(context, profile)
    }
}

@Composable
private fun ProfileEditorHeader(isEditing: Boolean, errorMessageRes: Int?) {
    Text(
        text = if (isEditing) {
            stringResource(R.string.settings_profile_edit_title)
        } else {
            stringResource(R.string.settings_profile_create_title)
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
private fun ProfileColorPicker(selectedHex: Long, onSelectHex: (Long) -> Unit) {
    Text(
        text = stringResource(R.string.settings_profile_color_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val colorCd = stringResource(R.string.settings_profile_color_cd)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PROFILE_COLOR_SWATCHES.forEach { swatch ->
            val selected = swatch == selectedHex
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(swatch))
                    .then(
                        if (selected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelectHex(swatch) }
                    .semantics { contentDescription = colorCd }
            )
        }
    }
}

@Composable
private fun ProfileModeSelector(selectedMode: FocusSessionMode, onSelectMode: (FocusSessionMode) -> Unit) {
    Text(
        text = stringResource(R.string.settings_profile_default_mode),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val modes = FocusSessionMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            val label = when (mode) {
                FocusSessionMode.POMODORO -> stringResource(DesignR.string.mode_pomodoro)
                FocusSessionMode.STOPWATCH -> stringResource(DesignR.string.mode_stopwatch)
                FocusSessionMode.CUSTOM -> stringResource(DesignR.string.mode_custom)
            }
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onSelectMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
            ) {
                Text(text = label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProfileDurationFields(
    workMinText: String,
    shortBreakText: String,
    longBreakText: String,
    cyclesText: String,
    onWorkChange: (String) -> Unit,
    onShortBreakChange: (String) -> Unit,
    onLongBreakChange: (String) -> Unit,
    onCyclesChange: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumericField(
            value = workMinText,
            label = stringResource(R.string.settings_profile_work_min),
            maxLen = 3,
            onValueChange = onWorkChange,
            modifier = Modifier.weight(1f)
        )
        NumericField(
            value = shortBreakText,
            label = stringResource(R.string.settings_profile_short_break),
            maxLen = 2,
            onValueChange = onShortBreakChange,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumericField(
            value = longBreakText,
            label = stringResource(R.string.settings_profile_long_break),
            maxLen = 2,
            onValueChange = onLongBreakChange,
            modifier = Modifier.weight(1f)
        )
        NumericField(
            value = cyclesText,
            label = stringResource(R.string.settings_profile_cycles),
            maxLen = 2,
            onValueChange = onCyclesChange,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NumericField(
    value: String,
    label: String,
    maxLen: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLen && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(text = label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun ProfileEditorActions(
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
