package net.pekmen.one

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.navigation.PekmenAppShell

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer
        get() = (application as PekmenApplication).container

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    userPreferencesRepository = appContainer.userPreferencesRepository,
                    diagnosticsRepository = appContainer.diagnosticsRepository
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            appContainer.sessionRepository.checkpointActiveSession()
        }
    }
}
