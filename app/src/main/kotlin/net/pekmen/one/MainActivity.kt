package net.pekmen.one

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.core.platform.samsung.AppShortcutsPublisher
import net.pekmen.one.core.platform.samsung.FocusShortcutCommand
import net.pekmen.one.navigation.PekmenAppShell

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer
        get() = (application as PekmenApplication).container

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleShortcutIntent(intent)
        }

        setContent {
            val prefs by appContainer.userPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = UserPreferences())
            val windowSizeClass = calculateWindowSizeClass(this)

            PekmenOneTheme(themePreference = prefs.themePreference) {
                PekmenAppShell(
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    sessionRepository = appContainer.sessionRepository,
                    profileRepository = appContainer.profileRepository,
                    historyRepository = appContainer.historyRepository,
                    learningRepository = appContainer.learningRepository,
                    plannerRepository = appContainer.plannerRepository,
                    calendarInsertGateway = appContainer.calendarInsertGateway,
                    userPreferencesRepository = appContainer.userPreferencesRepository,
                    diagnosticsRepository = appContainer.diagnosticsRepository,
                    backupPortabilityService = appContainer.backupPortabilityService,
                    cloudAccountRepository = appContainer.cloudAccountRepository,
                    circlesRepository = appContainer.circlesRepository,
                    cloudRlsGateProvider = appContainer.cloudRlsGateProvider
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            appContainer.sessionRepository.checkpointActiveSession()
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        val command = AppShortcutsPublisher.parseShortcutIntent(intent) ?: return
        lifecycleScope.launch {
            appContainer.profileRepository.ensureSeeded()
            val active = appContainer.sessionRepository.activeSession.first()
            when (command) {
                is FocusShortcutCommand.StartProfile -> {
                    val profiles = appContainer.profileRepository.profiles.first()
                    if (profiles.any { it.id == command.profileId }) {
                        appContainer.userPreferencesRepository.setSelectedProfileId(command.profileId)
                        if (active == null) {
                            appContainer.sessionRepository.startSession(
                                profileId = command.profileId,
                                mode = command.mode
                            )
                        }
                    }
                }
                FocusShortcutCommand.ToggleActiveFocus -> {
                    when (active?.session?.status) {
                        FocusSessionStatus.RUNNING -> appContainer.sessionRepository.pauseActiveSession()
                        FocusSessionStatus.PAUSED -> appContainer.sessionRepository.resumeActiveSession()
                        FocusSessionStatus.AWAITING_NEXT_PHASE ->
                            appContainer.sessionRepository.startAwaitingNextPhase()
                        null, FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> {
                            val prefs = appContainer.userPreferencesRepository.preferences.first()
                            val profiles = appContainer.profileRepository.profiles.first()
                            val selected = profiles.firstOrNull { it.id == prefs.selectedProfileId }
                                ?: profiles.firstOrNull { it.id == BuiltInProfiles.STUDY_ID }
                            val profileId = selected?.id ?: BuiltInProfiles.STUDY_ID
                            val mode = selected?.defaultMode ?: FocusSessionMode.POMODORO
                            appContainer.sessionRepository.startSession(profileId, mode)
                        }
                    }
                }
            }
        }
    }
}
