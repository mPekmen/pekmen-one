package net.pekmen.one.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.cloud.CloudAccountRepository
import net.pekmen.one.core.designsystem.R as DesignR
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.AccountState
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.CloudSyncPhase
import net.pekmen.one.core.model.LogoutCheckResult
import net.pekmen.one.core.model.SyncStateSummary

sealed interface CloudAccountAction {
    data class RequestOtp(val email: String) : CloudAccountAction
    data class VerifyOtp(val email: String, val code: String) : CloudAccountAction
    data object ClaimGuestData : CloudAccountAction
    data object SyncNow : CloudAccountAction
    data class Logout(val forceDiscardPending: Boolean = false) : CloudAccountAction
    data object DismissDialogOrError : CloudAccountAction
}

data class AccountSyncUiState(
    val isAvailable: Boolean = false,
    val gateStatus: CloudRlsGateStatus = CloudRlsGateStatus(false, false, false),
    val accountState: AccountState = AccountState.Guest,
    val syncState: SyncStateSummary = SyncStateSummary(),
    val unclaimedGuestCount: Int = 0,
    val isBusy: Boolean = false,
    val otpRequestedEmail: String? = null,
    val pendingLogoutBlockedCount: Int? = null,
    val feedbackMessageRes: Int? = null
)

internal class AccountSyncCoordinator(
    private val repo: CloudAccountRepository?,
    private val scope: CoroutineScope
) {
    private val isBusy = MutableStateFlow(false)
    private val otpEmail = MutableStateFlow<String?>(null)
    private val logoutBlockedCount = MutableStateFlow<Int?>(null)
    private val feedbackRes = MutableStateFlow<Int?>(null)
    private val unclaimedCount = MutableStateFlow(0)

    init {
        refreshUnclaimedCount()
    }

    val state: StateFlow<AccountSyncUiState> = if (repo == null) {
        MutableStateFlow(AccountSyncUiState()).asStateFlow()
    } else {
        combine(
            repo.accountState,
            repo.syncState,
            combine(isBusy, otpEmail, logoutBlockedCount) { busy, email, blocked ->
                Triple(busy, email, blocked)
            },
            combine(feedbackRes, unclaimedCount) { msg, guest -> msg to guest }
        ) { account, sync, (busy, email, blocked), (msg, guest) ->
            AccountSyncUiState(
                isAvailable = true,
                gateStatus = repo.gateStatus,
                accountState = account,
                syncState = sync,
                unclaimedGuestCount = guest,
                isBusy = busy,
                otpRequestedEmail = email,
                pendingLogoutBlockedCount = blocked,
                feedbackMessageRes = msg
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AccountSyncUiState(isAvailable = true, gateStatus = repo.gateStatus)
        )
    }

    fun dispatch(action: CloudAccountAction) {
        val repository = repo ?: return
        when (action) {
            is CloudAccountAction.RequestOtp -> runBusy {
                repository.requestOtpSignIn(action.email).fold(
                    onSuccess = {
                        otpEmail.value = action.email.trim()
                        feedbackRes.value = R.string.cloud_feedback_otp_sent
                    },
                    onFailure = { feedbackRes.value = R.string.cloud_feedback_error }
                )
            }
            is CloudAccountAction.VerifyOtp -> runBusy {
                repository.verifyOtpSignIn(action.email, action.code).fold(
                    onSuccess = {
                        otpEmail.value = null
                        unclaimedCount.value = repository.countUnclaimedGuestRecords()
                        feedbackRes.value = R.string.cloud_feedback_signed_in
                    },
                    onFailure = { feedbackRes.value = R.string.cloud_feedback_error }
                )
            }
            CloudAccountAction.ClaimGuestData -> runBusy {
                repository.claimGuestDataToSignedInUser().fold(
                    onSuccess = {
                        unclaimedCount.value = repository.countUnclaimedGuestRecords()
                        feedbackRes.value = R.string.cloud_feedback_claimed
                    },
                    onFailure = { feedbackRes.value = R.string.cloud_feedback_error }
                )
            }
            CloudAccountAction.SyncNow -> runBusy {
                logoutBlockedCount.value = null
                repository.triggerSyncNow().fold(
                    onSuccess = { feedbackRes.value = R.string.cloud_feedback_synced },
                    onFailure = { feedbackRes.value = R.string.cloud_feedback_error }
                )
            }
            is CloudAccountAction.Logout -> runBusy {
                when (val outcome = repository.attemptLogout(action.forceDiscardPending)) {
                    LogoutCheckResult.SignedOut -> {
                        logoutBlockedCount.value = null
                        otpEmail.value = null
                        unclaimedCount.value = repository.countUnclaimedGuestRecords()
                    }
                    is LogoutCheckResult.BlockedByPendingMutations -> {
                        logoutBlockedCount.value = outcome.pendingCount
                    }
                }
            }
            CloudAccountAction.DismissDialogOrError -> {
                logoutBlockedCount.value = null
                feedbackRes.value = null
            }
        }
    }

    private fun refreshUnclaimedCount() {
        val repository = repo ?: return
        scope.launch {
            unclaimedCount.value = repository.countUnclaimedGuestRecords()
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        scope.launch {
            isBusy.value = true
            feedbackRes.value = null
            try {
                block()
            } finally {
                isBusy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun AccountSyncSection(
    state: AccountSyncUiState,
    onAction: (CloudAccountAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isAvailable) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.cloud_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.cloud_section_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!state.gateStatus.isLiveCloudEnabled) {
                GatedOfflineBanner(compiledWithCloudFlag = state.gateStatus.compiledWithCloudFlag)
            } else {
                when (val account = state.accountState) {
                    AccountState.Guest -> GuestSignInForm(state = state, onAction = onAction)
                    is AccountState.SignedIn -> SignedInCloudPanel(
                        account = account,
                        state = state,
                        onAction = onAction
                    )
                }
            }

            state.feedbackMessageRes?.let { msgRes ->
                Text(
                    text = stringResource(msgRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    state.pendingLogoutBlockedCount?.let { pending ->
        LogoutPendingDialog(
            pendingCount = pending,
            onSyncNow = { onAction(CloudAccountAction.SyncNow) },
            onDiscardAndSignOut = { onAction(CloudAccountAction.Logout(forceDiscardPending = true)) },
            onDismiss = { onAction(CloudAccountAction.DismissDialogOrError) }
        )
    }
}

@Composable
private fun GatedOfflineBanner(compiledWithCloudFlag: Boolean) {
    val bodyRes = if (compiledWithCloudFlag) {
        R.string.cloud_gated_rls_body
    } else {
        R.string.cloud_gated_offline_body
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(PekmenIcons.Shield),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.cloud_guest_status),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GuestSignInForm(
    state: AccountSyncUiState,
    onAction: (CloudAccountAction) -> Unit
) {
    var email by rememberSaveable { mutableStateOf(state.otpRequestedEmail.orEmpty()) }
    var otpCode by rememberSaveable { mutableStateOf("") }

    OutlinedTextField(
        value = email,
        onValueChange = { if (it.length <= 120) email = it },
        label = { Text(text = stringResource(R.string.cloud_email_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedButton(
        onClick = { onAction(CloudAccountAction.RequestOtp(email)) },
        enabled = !state.isBusy && email.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Text(text = stringResource(R.string.cloud_send_code_button))
    }

    if (state.otpRequestedEmail != null) {
        OutlinedTextField(
            value = otpCode,
            onValueChange = { if (it.length <= 12) otpCode = it },
            label = { Text(text = stringResource(R.string.cloud_otp_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onAction(CloudAccountAction.VerifyOtp(email, otpCode)) },
            enabled = !state.isBusy && otpCode.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.cloud_verify_code_button))
        }
    }
}

@Composable
private fun SignedInCloudPanel(
    account: AccountState.SignedIn,
    state: AccountSyncUiState,
    onAction: (CloudAccountAction) -> Unit
) {
    Text(
        text = stringResource(R.string.cloud_signed_in_as, account.email),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = stringResource(syncPhaseLabelRes(state.syncState.phase)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(
            R.string.cloud_pending_mutations,
            PekmenFormatters.formatInteger(state.syncState.pendingMutationCount)
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (state.unclaimedGuestCount > 0) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(
                R.string.cloud_claim_guest_prompt,
                PekmenFormatters.formatInteger(state.unclaimedGuestCount)
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Button(
            onClick = { onAction(CloudAccountAction.ClaimGuestData) },
            enabled = !state.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.cloud_claim_guest_button))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { onAction(CloudAccountAction.SyncNow) },
            enabled = !state.isBusy,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.cloud_sync_now_button))
        }
        OutlinedButton(
            onClick = { onAction(CloudAccountAction.Logout(forceDiscardPending = false)) },
            enabled = !state.isBusy,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.cloud_sign_out_button))
        }
    }
}

@Composable
private fun LogoutPendingDialog(
    pendingCount: Int,
    onSyncNow: () -> Unit,
    onDiscardAndSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.cloud_logout_blocked_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.cloud_logout_blocked_body,
                    PekmenFormatters.formatInteger(pendingCount)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onSyncNow) {
                Text(text = stringResource(R.string.cloud_sync_now_button))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(DesignR.string.action_cancel))
                }
                TextButton(onClick = onDiscardAndSignOut) {
                    Text(
                        text = stringResource(R.string.cloud_logout_discard_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

private fun syncPhaseLabelRes(phase: CloudSyncPhase): Int = when (phase) {
    CloudSyncPhase.OFFLINE_GUEST -> R.string.cloud_sync_phase_offline
    CloudSyncPhase.GATED_CLOUD_UNVERIFIED -> R.string.cloud_sync_phase_gated
    CloudSyncPhase.IDLE -> R.string.cloud_sync_phase_idle
    CloudSyncPhase.SYNCING -> R.string.cloud_sync_phase_syncing
    CloudSyncPhase.ERROR -> R.string.cloud_sync_phase_error
}
