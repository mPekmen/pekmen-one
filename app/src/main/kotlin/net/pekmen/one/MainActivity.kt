package net.pekmen.one

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.pekmen.one.account.AccountAccess
import net.pekmen.one.account.AccountPage
import net.pekmen.one.account.AccountProfileScreen
import net.pekmen.one.account.ActiveAccount
import net.pekmen.one.account.AuthScreen
import net.pekmen.one.account.PasswordRecoveryScreen
import net.pekmen.one.account.PersonalSetupScreen
import net.pekmen.one.core.cloud.auth.IdentityError
import net.pekmen.one.core.cloud.auth.IdentityFailure
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.core.platform.planner.TaskReminderReceiver
import net.pekmen.one.core.platform.samsung.AppShortcutsPublisher
import net.pekmen.one.core.platform.samsung.FocusShortcutCommand
import net.pekmen.one.navigation.PekmenAppShell
import java.net.URI

class MainActivity : ComponentActivity() {
    private val accounts get() = (application as PekmenApplication).accounts
    private var linkError by mutableStateOf<IdentityError?>(null)
    private var pendingTask by mutableStateOf<Pair<ActiveAccount, String>?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            val access by accounts.access.collectAsStateWithLifecycle()
            val personal = access as? AccountAccess.Personal
            val preferences = personal?.account?.container?.userPreferencesRepository?.preferences
            val initial = UserPreferences()
            val prefs = if (preferences == null) initial else key(personal?.account) {
                val current by preferences.collectAsStateWithLifecycle(initial)
                current
            }
            PekmenOneTheme(themePreference = prefs.themePreference) {
                when (val current = access) {
                    AccountAccess.Restoring -> AccountPage {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                        CircularProgressIndicator()
                    }
                    AccountAccess.SignedOut -> AuthScreen(accounts, linkError)
                    AccountAccess.Recovery -> PasswordRecoveryScreen(accounts)
                    is AccountAccess.Personal -> key(current.account) {
                        if (!current.setup.completed) {
                            PersonalSetupScreen(accounts, current)
                        } else {
                            val local = current.account.container
                            PekmenAppShell(
                                widthSizeClass = calculateWindowSizeClass(this).widthSizeClass,
                                sessionRepository = local.sessionRepository,
                                profileRepository = local.profileRepository,
                                historyRepository = local.historyRepository,
                                learningRepository = local.learningRepository,
                                plannerRepository = local.plannerRepository,
                                calendarInsertGateway = local.calendarInsertGateway,
                                userPreferencesRepository = local.userPreferencesRepository,
                                diagnosticsRepository = local.diagnosticsRepository,
                                backupPortabilityService = local.backupPortabilityService,
                                accountContent = { back -> AccountProfileScreen(accounts, current, back) },
                                taskToOpen = pendingTask?.takeIf { it.first === current.account }?.second,
                                onTaskOpened = { pendingTask = null }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            accounts.restore()
            accounts.refreshIfNeeded()
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            accounts.withReadyAccount { it.container.sessionRepository.checkpointActiveSession() }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        lifecycleScope.launch {
            accounts.restore()
            when {
                intent.action == Intent.ACTION_VIEW && intent.data?.path == "/auth/pekmen-one" -> {
                    try {
                        linkError = null
                        accounts.receiveLink(URI(requireNotNull(intent.data).toString()))
                    } catch (failure: IdentityFailure) {
                        linkError = failure.reason
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        linkError = IdentityError.LINK
                    }
                }
                intent.hasExtra(TaskReminderReceiver.EXTRA_TASK_ID) -> {
                    val taskId = intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID) ?: return@launch
                    val owner = intent.getStringExtra(TaskReminderReceiver.EXTRA_OWNER_ID)
                    accounts.withReadyAccount { account ->
                        val ownsTask = account.id.value == owner &&
                            account.container.plannerRepository.getTaskById(taskId) != null
                        if (ownsTask) {
                            pendingTask = account to taskId
                        }
                    }
                }
                else -> {
                    val command = AppShortcutsPublisher.parseShortcutIntent(intent) ?: return@launch
                    accounts.withReadyAccount { executeShortcut(it.container, command) }
                }
            }
        }
    }

    private suspend fun executeShortcut(local: AppContainer, command: FocusShortcutCommand) {
        val active = local.sessionRepository.activeSession.first()
        when (command) {
            is FocusShortcutCommand.StartProfile -> {
                val profiles = local.profileRepository.profiles.first()
                if (profiles.any { it.id == command.profileId }) {
                    local.userPreferencesRepository.setSelectedProfileId(command.profileId)
                    if (active == null) local.sessionRepository.startSession(command.profileId, command.mode)
                }
            }
            FocusShortcutCommand.ToggleActiveFocus -> {
                when (active?.session?.status) {
                    FocusSessionStatus.RUNNING -> local.sessionRepository.pauseActiveSession()
                    FocusSessionStatus.PAUSED -> local.sessionRepository.resumeActiveSession()
                    FocusSessionStatus.AWAITING_NEXT_PHASE -> local.sessionRepository.startAwaitingNextPhase()
                    null, FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> {
                        val prefs = local.userPreferencesRepository.preferences.first()
                        val profiles = local.profileRepository.profiles.first()
                        val selected = profiles.firstOrNull { it.id == prefs.selectedProfileId }
                        local.sessionRepository.startSession(
                            selected?.id ?: BuiltInProfiles.STUDY_ID,
                            selected?.defaultMode ?: FocusSessionMode.POMODORO
                        )
                    }
                }
            }
        }
    }
}
