package net.pekmen.one.feature.circles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.CircleMemberStanding
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.SocialCircle

data class CirclesScreenActions(
    val onSelectCircle: (String) -> Unit = {},
    val onSelectPeriod: (CirclePeriodType) -> Unit = {},
    val onOpenCreateDialog: () -> Unit = {},
    val onDismissCreateDialog: () -> Unit = {},
    val onCreateCircle: (String, String) -> Unit = { _, _ -> },
    val onOpenJoinDialog: () -> Unit = {},
    val onDismissJoinDialog: () -> Unit = {},
    val onJoinCircle: (String, String) -> Unit = { _, _ -> },
    val onOpenLeaveConfirm: () -> Unit = {},
    val onDismissLeaveConfirm: () -> Unit = {},
    val onLeaveCircle: () -> Unit = {},
    val onSetShareEnabled: (Boolean) -> Unit = {},
    val onSetShareLiveStatus: (Boolean) -> Unit = {},
    val onToggleSubject: (String) -> Unit = {},
    val onAddCustomSubject: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissError: () -> Unit = {}
)

@Composable
fun CirclesTabRoute(
    viewModel: CirclesViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CirclesScreen(
        state = state,
        actions = CirclesScreenActions(
            onSelectCircle = viewModel::selectCircle,
            onSelectPeriod = viewModel::selectPeriod,
            onOpenCreateDialog = viewModel::openCreateDialog,
            onDismissCreateDialog = viewModel::dismissCreateDialog,
            onCreateCircle = viewModel::createCircle,
            onOpenJoinDialog = viewModel::openJoinDialog,
            onDismissJoinDialog = viewModel::dismissJoinDialog,
            onJoinCircle = viewModel::joinCircle,
            onOpenLeaveConfirm = viewModel::openLeaveConfirm,
            onDismissLeaveConfirm = viewModel::dismissLeaveConfirm,
            onLeaveCircle = viewModel::leaveSelectedCircle,
            onSetShareEnabled = viewModel::setShareEnabled,
            onSetShareLiveStatus = viewModel::setShareLiveStatus,
            onToggleSubject = viewModel::toggleSubjectTitle,
            onAddCustomSubject = viewModel::addCustomSubjectTitle,
            onRefresh = viewModel::refreshSelectedCircle,
            onDismissError = viewModel::dismissError
        ),
        modifier = modifier
    )
}

@Composable
fun CirclesScreen(
    state: CirclesUiState,
    actions: CirclesScreenActions,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("circles_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CirclesHeaderBar(
                    onOpenCreate = actions.onOpenCreateDialog,
                    onOpenJoin = actions.onOpenJoinDialog
                )
            }

            if (!state.gateStatus.isLiveCloudEnabled) {
                item {
                    RlsGateBannerCard()
                }
            } else if (!state.isSignedIn) {
                item {
                    SignInRequiredBannerCard()
                }
            }

            state.errorMessageRes?.let { errorRes ->
                item {
                    ErrorFeedbackCard(
                        message = stringResource(errorRes),
                        onDismiss = actions.onDismissError
                    )
                }
            }

            if (state.circles.isEmpty()) {
                item {
                    EmptyCirclesCard()
                }
            } else {
                item {
                    CircleSelectorRow(
                        circles = state.circles,
                        selectedCircleId = state.selectedCircleId,
                        onSelectCircle = actions.onSelectCircle
                    )
                }

                state.selectedCircle?.let { circle ->
                    item {
                        SelectedCircleSummaryCard(
                            circle = circle,
                            activeMemberCount = state.activeMemberships.size,
                            onRefresh = actions.onRefresh,
                            onLeaveClick = actions.onOpenLeaveConfirm
                        )
                    }

                    item {
                        PrivacySharingCard(
                            config = state.myMembership?.sharingConfig ?: CircleSharingConfig(),
                            availableSubjects = state.availableSubjectTitles,
                            onSetShareEnabled = actions.onSetShareEnabled,
                            onSetShareLiveStatus = actions.onSetShareLiveStatus,
                            onToggleSubject = actions.onToggleSubject,
                            onAddCustomSubject = actions.onAddCustomSubject
                        )
                    }

                    item {
                        LeaderboardHeaderCard(
                            selectedPeriod = state.selectedPeriod,
                            onSelectPeriod = actions.onSelectPeriod
                        )
                    }

                    if (state.leaderboard.isEmpty()) {
                        item {
                            EmptyLeaderboardCard()
                        }
                    } else {
                        items(state.leaderboard, key = { "${it.userId}_${it.periodType}" }) { standing ->
                            LeaderboardStandingCard(standing = standing)
                        }
                    }
                }
            }
        }
    }

    if (state.showCreateDialog) {
        CreateCircleDialog(
            onDismiss = actions.onDismissCreateDialog,
            onConfirm = actions.onCreateCircle
        )
    }

    if (state.showJoinDialog) {
        JoinCircleDialog(
            onDismiss = actions.onDismissJoinDialog,
            onConfirm = actions.onJoinCircle
        )
    }

    val selectedCircle = state.selectedCircle
    if (state.showLeaveConfirmDialog && selectedCircle != null) {
        LeaveCircleConfirmDialog(
            circleName = selectedCircle.name,
            onDismiss = actions.onDismissLeaveConfirm,
            onConfirm = actions.onLeaveCircle
        )
    }
}

@Composable
private fun CirclesHeaderBar(
    onOpenCreate: () -> Unit,
    onOpenJoin: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.circles_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onOpenJoin,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(text = stringResource(R.string.circles_action_join))
            }
            FilledTonalButton(
                onClick = onOpenCreate,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(
                    painter = painterResource(PekmenIcons.Add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(R.string.circles_action_create))
            }
        }
    }
}

@Composable
private fun RlsGateBannerCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(PekmenIcons.Warning),
                contentDescription = stringResource(R.string.circles_cd_gate),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.circles_gate_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.circles_gate_banner_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun SignInRequiredBannerCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.circles_signin_banner_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.circles_signin_banner_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorFeedbackCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(text = stringResource(R.string.circles_action_dismiss))
            }
        }
    }
}

@Composable
private fun EmptyCirclesCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(PekmenIcons.Group),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.circles_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.circles_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CircleSelectorRow(
    circles: List<SocialCircle>,
    selectedCircleId: String?,
    onSelectCircle: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(circles, key = { it.id }) { circle ->
            FilterChip(
                selected = circle.id == selectedCircleId,
                onClick = { onSelectCircle(circle.id) },
                label = { Text(text = circle.name) }
            )
        }
    }
}

@Composable
private fun SelectedCircleSummaryCard(
    circle: SocialCircle,
    activeMemberCount: Int,
    onRefresh: () -> Unit,
    onLeaveClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = circle.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.circles_invite_code_label, circle.inviteCode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(
                    R.string.circles_member_count,
                    PekmenFormatters.formatInteger(activeMemberCount)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(R.string.circles_action_refresh))
                }
                TextButton(
                    onClick = onLeaveClick,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.circles_action_leave),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacySharingCard(
    config: CircleSharingConfig,
    availableSubjects: List<String>,
    onSetShareEnabled: (Boolean) -> Unit,
    onSetShareLiveStatus: (Boolean) -> Unit,
    onToggleSubject: (String) -> Unit,
    onAddCustomSubject: (String) -> Unit
) {
    var newSubjectInput by rememberSaveable { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(PekmenIcons.Shield),
                    contentDescription = stringResource(R.string.circles_cd_shield),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.circles_privacy_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = stringResource(R.string.circles_privacy_boundary_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PrivacySwitchRow(
                title = stringResource(R.string.circles_switch_share_progress),
                subtitle = stringResource(R.string.circles_switch_share_progress_hint),
                checked = config.shareEnabled,
                enabled = true,
                onCheckedChange = onSetShareEnabled
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            PrivacySwitchRow(
                title = stringResource(R.string.circles_switch_share_live),
                subtitle = stringResource(R.string.circles_switch_share_live_hint),
                checked = config.shareEnabled && config.shareLiveFocusStatus,
                enabled = config.shareEnabled,
                onCheckedChange = onSetShareLiveStatus
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = stringResource(R.string.circles_subjects_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.circles_subjects_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newSubjectInput,
                    onValueChange = { newSubjectInput = it },
                    label = { Text(text = stringResource(R.string.circles_subjects_add_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        onAddCustomSubject(newSubjectInput)
                        newSubjectInput = ""
                    },
                    enabled = newSubjectInput.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(R.string.circles_action_add))
                }
            }

            if (availableSubjects.isEmpty()) {
                Text(
                    text = stringResource(R.string.circles_subjects_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableSubjects.forEach { subject ->
                        val selected = subject in config.allowedSubjectTitles
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleSubject(subject) },
                            label = { Text(text = subject) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

@Composable
private fun LeaderboardHeaderCard(
    selectedPeriod: CirclePeriodType,
    onSelectPeriod: (CirclePeriodType) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.circles_leaderboard_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CirclePeriodType.entries.forEachIndexed { index, period ->
                val labelRes = when (period) {
                    CirclePeriodType.DAILY -> R.string.circles_period_daily
                    CirclePeriodType.WEEKLY -> R.string.circles_period_weekly
                }
                SegmentedButton(
                    selected = selectedPeriod == period,
                    onClick = { onSelectPeriod(period) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = CirclePeriodType.entries.size
                    ),
                    label = { Text(text = stringResource(labelRes)) }
                )
            }
        }
    }
}

@Composable
private fun EmptyLeaderboardCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.circles_leaderboard_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaderboardStandingCard(standing: CircleMemberStanding) {
    val context = LocalContext.current
    val totalFormatted = PekmenFormatters.formatHumanDuration(context, standing.totalSharedFocusSec)
    val liveFormatted = PekmenFormatters.formatHumanDuration(context, standing.liveTimerFocusSec)
    val manualFormatted = PekmenFormatters.formatHumanDuration(context, standing.manualEntryFocusSec)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(
                            R.string.circles_rank_prefix,
                            PekmenFormatters.formatInteger(standing.rank)
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = standing.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(R.string.circles_stat_total, totalFormatted),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (standing.isCurrentlyFocusing) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = stringResource(R.string.circles_live_focusing_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.circles_stat_live_timer, liveFormatted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.circles_stat_manual_logged, manualFormatted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (standing.subjectTotals.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    standing.subjectTotals.forEach { subject ->
                        val dur = PekmenFormatters.formatHumanDuration(context, subject.focusSeconds)
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.circles_subject_chip,
                                        subject.subjectTitle,
                                        dur
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateCircleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var circleName by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.circles_dialog_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = circleName,
                    onValueChange = { circleName = it },
                    label = { Text(text = stringResource(R.string.circles_dialog_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(text = stringResource(R.string.circles_dialog_display_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(circleName, displayName) },
                enabled = circleName.isNotBlank() && displayName.isNotBlank()
            ) {
                Text(text = stringResource(R.string.circles_dialog_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.circles_action_cancel))
            }
        }
    )
}

@Composable
private fun JoinCircleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.circles_dialog_join_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it },
                    label = { Text(text = stringResource(R.string.circles_dialog_invite_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(text = stringResource(R.string.circles_dialog_display_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(inviteCode, displayName) },
                enabled = inviteCode.isNotBlank() && displayName.isNotBlank()
            ) {
                Text(text = stringResource(R.string.circles_dialog_join_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.circles_action_cancel))
            }
        }
    )
}

@Composable
private fun LeaveCircleConfirmDialog(
    circleName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.circles_dialog_leave_title, circleName)) },
        text = { Text(text = stringResource(R.string.circles_dialog_leave_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.circles_dialog_leave_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.circles_action_cancel))
            }
        }
    )
}
