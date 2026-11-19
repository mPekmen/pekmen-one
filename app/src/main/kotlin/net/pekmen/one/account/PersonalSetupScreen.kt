package net.pekmen.one.account

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import net.pekmen.one.R
import net.pekmen.one.core.designsystem.format.localizedName
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.PersonalSetup

@Composable
@Suppress("CyclomaticComplexMethod") // Step validation, navigation and persistence share one wizard draft.
internal fun PersonalSetupScreen(accounts: PekmenAccounts, active: AccountAccess.Personal) {
    val account = active.account
    val action = rememberAccountAction()
    var draft by remember(account) {
        mutableStateOf(active.setup.copy(name = active.setup.name.ifBlank { account.name.orEmpty() }))
    }
    var legacyCount by remember(account) { mutableIntStateOf(0) }
    var legacyDialog by remember { mutableStateOf(false) }
    var signOutDialog by remember { mutableStateOf(false) }
    val profiles by account.container.profileRepository.profiles.collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(account) {
        try {
            legacyCount = accounts.legacyCount(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            action.run { legacyCount = accounts.legacyCount(account) }
        }
    }

    val moveBack = {
        if (draft.step == 0) {
            signOutDialog = true
        } else {
            action.run {
                val previous = draft.copy(step = draft.step - 1)
                accounts.saveDraft(account, previous)
                draft = previous
            }
        }
    }
    BackHandler { if (!action.busy) moveBack() }

    AccountPage {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_progress, draft.step + 1, 3))
        when (draft.step) {
            0 -> {
                ProfileFields(draft.name, draft.username, { draft = draft.copy(name = it) }, {
                    draft = draft.copy(username = it)
                })
                if (legacyCount > 0) {
                    OutlinedButton(onClick = { legacyDialog = true }, enabled = !action.busy) {
                        Text(stringResource(R.string.setup_legacy, legacyCount))
                    }
                }
            }
            1 -> SetupFocusForm(draft, profiles) { draft = it }
            2 -> {
                SetupTextField(
                    draft.firstLesson, { draft = draft.copy(firstLesson = it.take(80)) }, R.string.setup_lesson
                )
                SetupPermissions()
            }
        }
        val ready = when (draft.step) {
            0 -> draft.hasValidIdentity()
            1 -> draft.purpose.isNotBlank() && draft.categories.isNotEmpty() && draft.hasValidGoals() &&
                profiles.any { it.id == draft.profileId }
            else -> true
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = moveBack, enabled = !action.busy) {
                Text(stringResource(if (draft.step == 0) R.string.auth_sign_out else R.string.account_back))
            }
            Button(enabled = ready && !action.busy, onClick = {
                action.run {
                    if (draft.step == 2) {
                        accounts.finishSetup(account, draft)
                    } else {
                        val next = draft.copy(step = draft.step + 1)
                        accounts.saveDraft(account, next)
                        draft = next
                    }
                }
            }) { Text(stringResource(if (draft.step == 2) R.string.setup_finish else R.string.setup_next)) }
        }
        AccountActionFeedback(action)
    }

    if (legacyDialog) {
        ConfirmAction(R.string.setup_legacy_title, R.string.setup_legacy_description, R.string.setup_legacy_confirm, {
            legacyDialog = false
        }) {
            legacyDialog = false
            action.run {
                accounts.claimLegacy(account)
                legacyCount = 0
            }
        }
    }
    if (signOutDialog) {
        SignOutConfirmation(onDismiss = { signOutDialog = false }) {
            signOutDialog = false
            action.run { accounts.signOut() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupFocusForm(draft: PersonalSetup, profiles: List<FocusProfile>, update: (PersonalSetup) -> Unit) {
    SetupTextField(draft.purpose, { update(draft.copy(purpose = it.take(120))) }, R.string.setup_purpose)
    Message(R.string.setup_categories)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.filter { it.builtInSlug != BuiltInProfileSlug.CUSTOM }.forEach { profile ->
            val selected = profile.id in draft.categories
            FilterChip(
                selected = selected,
                onClick = {
                    val ids = if (selected) draft.categories - profile.id else draft.categories + profile.id
                    update(draft.copy(categories = ids))
                },
                label = { Text(profile.localizedName()) }
            )
        }
    }
    SetupGoalField(draft.dailyGoalMinutes, { update(draft.copy(dailyGoalMinutes = it)) }, R.string.setup_daily)
    SetupGoalField(draft.weeklyGoalMinutes, { update(draft.copy(weeklyGoalMinutes = it)) }, R.string.setup_weekly)
    Message(R.string.setup_profile)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            FilterChip(
                selected = profile.id == draft.profileId,
                onClick = { update(draft.copy(profileId = profile.id)) },
                label = { Text(profile.localizedName()) }
            )
        }
    }
}

@Composable
private fun SetupGoalField(minutes: Int, setMinutes: (Int) -> Unit, label: Int) {
    OutlinedTextField(
        value = if (minutes == 0) "" else minutes.toString(),
        onValueChange = { raw -> setMinutes(raw.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) },
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SetupPermissions() {
    var calendarResult by remember { mutableStateOf<Boolean?>(null) }
    var notificationsResult by remember { mutableStateOf<Boolean?>(null) }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        calendarResult = it
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsResult = it
    }
    Message(R.string.setup_permissions)
    OutlinedButton(onClick = { calendar.launch(Manifest.permission.READ_CALENDAR) }) {
        Text(stringResource(R.string.setup_calendar))
    }
    calendarResult?.let { Message(if (it) R.string.setup_permission_granted else R.string.setup_permission_denied) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OutlinedButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text(stringResource(R.string.setup_notifications))
        }
        notificationsResult?.let {
            Message(if (it) R.string.setup_permission_granted else R.string.setup_permission_denied)
        }
    }
}

@Composable
internal fun AccountProfileScreen(
    accounts: PekmenAccounts,
    active: AccountAccess.Personal,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(active.setup.name) }
    var username by remember { mutableStateOf(active.setup.username) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val action = rememberAccountAction()
    BackHandler(!action.busy, onBack)
    AccountPage {
        Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineSmall)
        Text(active.account.email)
        ProfileFields(name, username, { name = it; saved = false }, { username = it; saved = false })
        Button(
            onClick = {
                action.run {
                    accounts.updateProfile(active.account, name, username)
                    saved = true
                }
            },
            enabled = active.setup.copy(name = name, username = username).hasValidIdentity() && !action.busy
        ) { Text(stringResource(R.string.account_save)) }
        if (saved) Message(R.string.account_saved)
        OutlinedButton(onClick = { confirmSignOut = true }, enabled = !action.busy) {
            Text(stringResource(R.string.auth_sign_out))
        }
        TextButton(onClick = onBack, enabled = !action.busy) { Text(stringResource(R.string.account_back)) }
        AccountActionFeedback(action)
    }
    if (confirmSignOut) {
        SignOutConfirmation(onDismiss = { confirmSignOut = false }) {
            confirmSignOut = false
            action.run { accounts.signOut() }
        }
    }
}

@Composable
private fun SignOutConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmAction(R.string.auth_sign_out, R.string.account_sign_out_hint, R.string.auth_sign_out, onDismiss, onConfirm)
}

@Composable
private fun ConfirmAction(title: Int, summary: Int, label: Int, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(summary)) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(label)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.account_cancel)) } }
    )
}

@Composable
private fun ProfileFields(name: String, username: String, setName: (String) -> Unit, setUsername: (String) -> Unit) {
    SetupTextField(name, { setName(it.take(80)) }, R.string.setup_name)
    SetupTextField(username, { setUsername(it.take(24)) }, R.string.setup_username)
    Message(R.string.setup_username_hint)
}

@Composable
private fun SetupTextField(value: String, onChange: (String) -> Unit, label: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}
