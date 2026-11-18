package net.pekmen.one.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pekmen.one.core.data.SaveProfileRequest
import net.pekmen.one.core.data.SystemDiagnostics
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.format.localizedName
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.ThemePreference
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScreen(
        state = state,
        onSelectTheme = viewModel::setThemePreference,
        onChangeRolloverHour = viewModel::setDayRolloverHour,
        onToggleAutoBreaks = viewModel::setAutoStartBreaks,
        onToggleAutoNextFocus = viewModel::setAutoStartNextFocus,
        onEditProfile = viewModel::openEditProfile,
        onCreateProfile = viewModel::openCreateProfile,
        onDismissProfileEditor = viewModel::dismissProfileEditor,
        onSaveProfile = viewModel::saveProfile,
        modifier = modifier
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSelectTheme: (ThemePreference) -> Unit,
    onChangeRolloverHour: (Int) -> Unit,
    onToggleAutoBreaks: (Boolean) -> Unit,
    onToggleAutoNextFocus: (Boolean) -> Unit,
    onEditProfile: (FocusProfile) -> Unit,
    onCreateProfile: () -> Unit,
    onDismissProfileEditor: () -> Unit,
    onSaveProfile: (SaveProfileRequest) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

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
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                AppearanceCard(
                    selectedTheme = state.preferences.themePreference,
                    onSelectTheme = onSelectTheme
                )
            }

            item {
                FocusRhythmCard(
                    rolloverHour = state.preferences.dayRolloverHour,
                    autoStartBreaks = state.preferences.autoStartBreaks,
                    autoStartNextFocus = state.preferences.autoStartNextFocus,
                    onChangeRolloverHour = onChangeRolloverHour,
                    onToggleAutoBreaks = onToggleAutoBreaks,
                    onToggleAutoNextFocus = onToggleAutoNextFocus
                )
            }

            item {
                FocusProfilesCard(
                    profiles = state.profiles,
                    onEditProfile = onEditProfile,
                    onCreateProfile = onCreateProfile
                )
            }

            item {
                SystemDiagnosticsCard(
                    diagnostics = state.diagnostics,
                    onOpenNotificationsSettings = { openNotificationSettings(context) },
                    onOpenExactAlarmSettings = { openExactAlarmSettings(context) }
                )
            }

            item {
                PrivacyCard()
            }
        }
    }

    if (state.isCreatingProfile || state.editingProfile != null) {
        ProfileEditorSheet(
            existingProfile = state.editingProfile,
            errorMessageRes = state.profileErrorRes,
            onDismiss = onDismissProfileEditor,
            onSave = onSaveProfile
        )
    }
}

@Composable
private fun AppearanceCard(
    selectedTheme: ThemePreference,
    onSelectTheme: (ThemePreference) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_theme_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            ThemePreference.entries.forEach { option ->
                val label = when (option) {
                    ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
                    ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
                    ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selectedTheme == option,
                            onClick = { onSelectTheme(option) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedTheme == option, onClick = null)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusRhythmCard(
    rolloverHour: Int,
    autoStartBreaks: Boolean,
    autoStartNextFocus: Boolean,
    onChangeRolloverHour: (Int) -> Unit,
    onToggleAutoBreaks: (Boolean) -> Unit,
    onToggleAutoNextFocus: (Boolean) -> Unit
) {
    val timeFormatted = LocalTime.of(rolloverHour, 0)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_rhythm_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_rollover_label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_rollover_hint, timeFormatted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val minusCd = stringResource(R.string.settings_cd_decrease_rollover)
                    val plusCd = stringResource(R.string.settings_cd_increase_rollover)
                    FilledTonalButton(
                        onClick = {
                            onChangeRolloverHour(
                                (rolloverHour - 1).coerceAtLeast(EffectiveDayCalculator.MIN_ROLLOVER_HOUR)
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = minusCd }
                    ) {
                        Text(text = stringResource(R.string.settings_symbol_minus))
                    }
                    Text(
                        text = timeFormatted,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FilledTonalButton(
                        onClick = {
                            onChangeRolloverHour(
                                (rolloverHour + 1).coerceAtMost(EffectiveDayCalculator.MAX_ROLLOVER_HOUR)
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = plusCd }
                    ) {
                        Text(text = stringResource(R.string.settings_symbol_plus))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SwitchSettingRow(
                label = stringResource(R.string.settings_auto_breaks),
                checked = autoStartBreaks,
                onCheckedChange = onToggleAutoBreaks
            )

            SwitchSettingRow(
                label = stringResource(R.string.settings_auto_next_focus),
                checked = autoStartNextFocus,
                onCheckedChange = onToggleAutoNextFocus
            )
        }
    }
}

@Composable
private fun FocusProfilesCard(
    profiles: List<FocusProfile>,
    onEditProfile: (FocusProfile) -> Unit,
    onCreateProfile: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_profiles_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = onCreateProfile,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(painter = painterResource(PekmenIcons.Add), contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.settings_profiles_add))
                }
            }

            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onEditProfile(profile) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(profile.colorHex), CircleShape)
                        )
                        Column {
                            Text(
                                text = profile.localizedName(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_profile_summary,
                                    PekmenFormatters.formatInteger(profile.workMinutes),
                                    PekmenFormatters.formatInteger(profile.shortBreakMinutes)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(PekmenIcons.Edit),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemDiagnosticsCard(
    diagnostics: SystemDiagnostics,
    onOpenNotificationsSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_diagnostics_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            DiagnosticRow(
                title = stringResource(R.string.settings_notif_status_title),
                status = if (diagnostics.hasNotificationPermission) {
                    stringResource(R.string.settings_status_granted)
                } else {
                    stringResource(R.string.settings_status_missing)
                },
                actionLabel = stringResource(R.string.settings_action_open_system),
                onAction = onOpenNotificationsSettings
            )

            DiagnosticRow(
                title = stringResource(R.string.settings_exact_alarm_title),
                status = if (diagnostics.canScheduleExactAlarms) {
                    stringResource(R.string.settings_status_granted)
                } else {
                    stringResource(R.string.settings_status_fallback)
                },
                actionLabel = stringResource(R.string.settings_action_open_system),
                onAction = onOpenExactAlarmSettings
            )
        }
    }
}

@Composable
private fun PrivacyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(PekmenIcons.Shield),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_privacy_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_privacy_offline_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DiagnosticRow(
    title: String,
    status: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(
            onClick = onAction,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = actionLabel)
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }
}

private fun openExactAlarmSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
    runCatching { context.startActivity(intent) }
}
