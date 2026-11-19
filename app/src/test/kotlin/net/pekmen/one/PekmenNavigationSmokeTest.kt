package net.pekmen.one

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.navigation.PekmenAppShell
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PekmenNavigationSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `navigates across M1 screens in English`() {
        val app = ApplicationProvider.getApplicationContext<PekmenApplication>()
        composeRule.setContent {
            PekmenOneTheme {
                PekmenAppShell(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    sessionRepository = app.container.sessionRepository,
                    profileRepository = app.container.profileRepository,
                    historyRepository = app.container.historyRepository,
                    learningRepository = app.container.learningRepository,
                    userPreferencesRepository = app.container.userPreferencesRepository,
                    diagnosticsRepository = app.container.diagnosticsRepository
                )
            }
        }

        composeRule.onNodeWithText("Start focus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Learning").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Organize what you study").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Organize what you study").assertIsDisplayed()
        composeRule.onNodeWithText("Summary").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Last 7 days").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Last 7 days").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Dark (Midnight Violet)").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "tr")
    fun `navigates across M1 screens in Turkish`() {
        val app = ApplicationProvider.getApplicationContext<PekmenApplication>()
        composeRule.setContent {
            PekmenOneTheme {
                PekmenAppShell(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    sessionRepository = app.container.sessionRepository,
                    profileRepository = app.container.profileRepository,
                    historyRepository = app.container.historyRepository,
                    learningRepository = app.container.learningRepository,
                    userPreferencesRepository = app.container.userPreferencesRepository,
                    diagnosticsRepository = app.container.diagnosticsRepository
                )
            }
        }

        composeRule.onNodeWithText("Odağı başlat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Öğrenme").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Çalıştığınız konuları düzenleyin").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Çalıştığınız konuları düzenleyin").assertIsDisplayed()
        composeRule.onNodeWithText("Özet").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Son 7 gün").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Son 7 gün").assertIsDisplayed()
        composeRule.onNodeWithText("Ayarlar").performClick()
        composeRule.onNodeWithText("Koyu (Midnight Violet)").assertIsDisplayed()
    }
}
