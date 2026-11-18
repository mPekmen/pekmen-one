package net.pekmen.one

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.navigation.PekmenAppShell

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as PekmenApplication).container

        setContent {
            val prefs by appContainer.userPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = UserPreferences())
            val windowSizeClass = calculateWindowSizeClass(this)

            PekmenOneTheme(themePreference = prefs.themePreference) {
                PekmenAppShell(
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    userPreferencesRepository = appContainer.userPreferencesRepository
                )
            }
        }
    }
}
