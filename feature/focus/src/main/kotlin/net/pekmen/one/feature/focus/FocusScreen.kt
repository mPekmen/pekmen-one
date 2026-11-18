package net.pekmen.one.feature.focus

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.format.localizedName
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusTimeMath

@Composable
fun FocusRoute(
    viewModel: FocusViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.onPermissionResultChanged()
    }

    FocusScreen(
        state = state,
        onSelectProfile = viewModel::selectProfile,
        onSelectMode = viewModel::selectMode,
        onSetCustomMinutes = viewModel::setCustomMinutes,
        onStart = viewModel::startFocus,
        onPause = viewModel::pauseFocus,
        onResume = viewModel::resumeFocus,
        onStartNextPhase = viewModel::startNextPhase,
        onSkipBreakToFocus = viewModel::skipBreakToFocus,
        onOpenWrapUp = viewModel::openWrapUp,
        onDismissWrapUp = viewModel::dismissWrapUp,
        onSaveWrapUp = viewModel::saveWrapUp,
        onDiscardShort = viewModel::discardShortSession,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onDismissNotificationBanner = viewModel::dismissNotificationBanner,
        modifier = modifier
    )
}

@Composable
fun FocusScreen(
    state: FocusUiState,
    onSelectProfile: (FocusProfile) -> Unit,
    onSelectMode: (FocusSessionMode) -> Unit,
    onSetCustomMinutes: (Int) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartNextPhase: () -> Unit,
    onSkipBreakToFocus: () -> Unit,
    onOpenWrapUp: () -> Unit,
    onDismissWrapUp: () -> Unit,
    onSaveWrapUp: (Long?, Int?, String?) -> Unit,
    onDiscardShort: () -> Unit,
    onRequestNotifications: () -> Unit,
    onDismissNotificationBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = state.activeSnapshot
    val profileAccent = state.selectedProfile?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary
    val clock = state.clock

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProfileSelectorRow(
                profiles = state.profiles,
                selectedProfile = state.selectedProfile,
                enabled = active == null,
                onSelectProfile = onSelectProfile
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !state.hasNotificationPermission &&
                !state.notificationBannerDismissed
            ) {
                NotificationPermissionBanner(
                    onAllow = onRequestNotifications,
                    onDismiss = onDismissNotificationBanner
                )
            }

            if (active?.session?.interruptedByReboot == true) {
                RebootRecoveryBanner()
            }

            state.errorMessageRes?.let { errRes ->
                Text(
                    text = stringResource(errRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (active == null) {
                IdleConfigurationSection(
                    selectedMode = state.selectedMode,
                    customMinutes = state.customMinutes,
                    onSelectMode = onSelectMode,
                    onSetCustomMinutes = onSetCustomMinutes
                )
            }

            FocusRingDisplaySection(
                state = state,
                profileAccent = profileAccent
            )

            FocusControlsBar(
                activeStatus = active?.session?.status,
                currentPhase = active?.session?.currentPhase,
                awaitingNextPhase = active?.session?.awaitingNextPhase,
                netFocusSeconds = clock?.totalFocusSeconds ?: 0L,
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onStartNextPhase = onStartNextPhase,
                onSkipBreakToFocus = onSkipBreakToFocus,
                onFinish = onOpenWrapUp,
                onDiscardShort = onDiscardShort
            )

            TodaySummaryMiniCard(
                netFocusSeconds = state.todaySummary?.netFocusSeconds ?: 0L,
                breakSeconds = state.todaySummary?.breakSeconds ?: 0L,
                sessionCount = state.todaySummary?.completedSessionCount ?: 0
            )
        }
    }

    state.wrapUpSnapshot?.let { wrapUp ->
        SessionWrapUpSheet(
            snapshot = wrapUp,
            onDismiss = onDismissWrapUp,
            onSave = onSaveWrapUp,
            onDiscardShort = onDiscardShort
        )
    }
}

@Composable
private fun IdleConfigurationSection(
    selectedMode: FocusSessionMode,
    customMinutes: Int,
    onSelectMode: (FocusSessionMode) -> Unit,
    onSetCustomMinutes: (Int) -> Unit
) {
    ModeSelectorRow(
        selectedMode = selectedMode,
        onSelectMode = onSelectMode
    )
    if (selectedMode == FocusSessionMode.CUSTOM) {
        CustomDurationPicker(
            minutes = customMinutes,
            onSetMinutes = onSetCustomMinutes
        )
    }
}

@Composable
private fun FocusRingDisplaySection(
    state: FocusUiState,
    profileAccent: Color
) {
    val context = LocalContext.current
    val active = state.activeSnapshot
    val clock = state.clock
    val idleSeconds = when (state.selectedMode) {
        FocusSessionMode.POMODORO -> (state.selectedProfile?.workMinutes ?: 25) * 60L
        FocusSessionMode.CUSTOM -> state.customMinutes * 60L
        FocusSessionMode.STOPWATCH -> 0L
    }
    val displayPhase = active?.session?.awaitingNextPhase
        ?: active?.session?.currentPhase
        ?: FocusPhase.FOCUS
    val isTargetReached = active?.session?.status == FocusSessionStatus.AWAITING_NEXT_PHASE &&
        active.session.awaitingNextPhase == null

    PekmenFocusRing(
        displaySeconds = clock?.primarySeconds ?: idleSeconds,
        progressFraction = clock?.progressFraction ?: 0f,
        phase = displayPhase,
        isTargetReached = isTargetReached,
        isPomodoro = (active?.session?.sessionMode ?: state.selectedMode) == FocusSessionMode.POMODORO,
        completedCycles = active?.session?.completedFocusCycles ?: 0,
        totalCycles = state.selectedProfile?.cyclesBeforeLongBreak ?: 4,
        accentColor = profileAccent,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    if (active != null && clock != null) {
        ActiveCountersRow(
            netFocusText = PekmenFormatters.formatHumanDuration(context, clock.totalFocusSeconds),
            breakText = PekmenFormatters.formatHumanDuration(context, clock.totalBreakSeconds)
        )
    }
}

@Composable
private fun ProfileSelectorRow(
    profiles: List<FocusProfile>,
    selectedProfile: FocusProfile?,
    enabled: Boolean,
    onSelectProfile: (FocusProfile) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(profiles, key = { it.id }) { profile ->
            val selected = profile.id == selectedProfile?.id
            FilterChip(
                selected = selected,
                enabled = enabled || selected,
                onClick = { if (enabled) onSelectProfile(profile) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(profile.colorHex), CircleShape)
                    )
                },
                label = { Text(text = profile.localizedName()) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun ModeSelectorRow(
    selectedMode: FocusSessionMode,
    onSelectMode: (FocusSessionMode) -> Unit
) {
    val modes = FocusSessionMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            val label = when (mode) {
                FocusSessionMode.POMODORO -> stringResource(R.string.focus_mode_pomodoro)
                FocusSessionMode.STOPWATCH -> stringResource(R.string.focus_mode_stopwatch)
                FocusSessionMode.CUSTOM -> stringResource(R.string.focus_mode_custom)
            }
            SegmentedButton(
                selected = mode == selectedMode,
                onClick = { onSelectMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
            ) {
                Text(text = label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CustomDurationPicker(
    minutes: Int,
    onSetMinutes: (Int) -> Unit
) {
    val presets = listOf(15, 30, 45, 60, 90)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.focus_custom_duration_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val minusCd = stringResource(R.string.focus_cd_decrease_minutes)
                val plusCd = stringResource(R.string.focus_cd_increase_minutes)
                FilledTonalButton(
                    onClick = { onSetMinutes((minutes - 5).coerceAtLeast(1)) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = minusCd }
                ) {
                    Text(text = stringResource(R.string.focus_custom_minus_5))
                }
                Text(
                    text = stringResource(
                        R.string.focus_minutes_chip,
                        PekmenFormatters.formatInteger(minutes)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                FilledTonalButton(
                    onClick = { onSetMinutes((minutes + 5).coerceAtMost(480)) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = plusCd }
                ) {
                    Text(text = stringResource(R.string.focus_custom_plus_5))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { preset ->
                FilterChip(
                    selected = minutes == preset,
                    onClick = { onSetMinutes(preset) },
                    label = {
                        Text(
                            text = stringResource(
                                R.string.focus_minutes_chip,
                                PekmenFormatters.formatInteger(preset)
                            )
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

@Composable
private fun ActiveCountersRow(
    netFocusText: String,
    breakText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.focus_stat_net_focus),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = netFocusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.focus_stat_break),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = breakText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun FocusControlsBar(
    activeStatus: FocusSessionStatus?,
    currentPhase: FocusPhase?,
    awaitingNextPhase: FocusPhase?,
    netFocusSeconds: Long,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartNextPhase: () -> Unit,
    onSkipBreakToFocus: () -> Unit,
    onFinish: () -> Unit,
    onDiscardShort: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (activeStatus) {
            null -> {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Icon(painter = painterResource(PekmenIcons.Play), contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.focus_action_start))
                }
            }
            FocusSessionStatus.RUNNING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPause,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Pause), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.focus_action_pause))
                    }
                    Button(
                        onClick = onFinish,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Stop), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.focus_action_finish))
                    }
                }
                if (currentPhase != null && currentPhase != FocusPhase.FOCUS) {
                    OutlinedButton(
                        onClick = onSkipBreakToFocus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(text = stringResource(R.string.focus_action_skip_to_focus))
                    }
                }
            }
            FocusSessionStatus.PAUSED -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onResume,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Play), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.focus_action_resume))
                    }
                    FilledTonalButton(
                        onClick = onFinish,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Stop), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.focus_action_finish))
                    }
                }
                if (netFocusSeconds < FocusTimeMath.MIN_VALID_SESSION_SECONDS) {
                    TextButton(
                        onClick = onDiscardShort,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(text = stringResource(R.string.focus_action_discard_short))
                    }
                }
            }
            FocusSessionStatus.AWAITING_NEXT_PHASE -> {
                if (awaitingNextPhase == null) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(painter = painterResource(PekmenIcons.Check), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.focus_action_review_save))
                    }
                } else {
                    val nextLabel = if (awaitingNextPhase == FocusPhase.FOCUS) {
                        stringResource(R.string.focus_action_start_next_focus)
                    } else {
                        stringResource(R.string.focus_action_start_break)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onStartNextPhase,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                        ) {
                            Icon(painter = painterResource(PekmenIcons.Coffee), contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = nextLabel)
                        }
                        OutlinedButton(
                            onClick = onFinish,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                        ) {
                            Text(text = stringResource(R.string.focus_action_finish))
                        }
                    }
                }
            }
            FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> Unit
        }
    }
}

@Composable
private fun NotificationPermissionBanner(
    onAllow: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.focus_notif_banner_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(R.string.focus_notif_banner_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(R.string.focus_notif_banner_allow))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(R.string.focus_notif_banner_dismiss))
                }
            }
        }
    }
}

@Composable
private fun RebootRecoveryBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(PekmenIcons.Warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.focus_reboot_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(R.string.focus_reboot_banner_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun TodaySummaryMiniCard(
    netFocusSeconds: Long,
    breakSeconds: Long,
    sessionCount: Int
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.focus_today_summary_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = PekmenFormatters.formatHumanDuration(context, netFocusSeconds),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.focus_today_sessions_count,
                        PekmenFormatters.formatInteger(sessionCount)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(
                        R.string.focus_today_break_summary,
                        PekmenFormatters.formatHumanDuration(context, breakSeconds)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
