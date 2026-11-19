package net.pekmen.one

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
                    plannerRepository = app.container.plannerRepository,
                    calendarInsertGateway = app.container.calendarInsertGateway,
                    userPreferencesRepository = app.container.userPreferencesRepository,
                    diagnosticsRepository = app.container.diagnosticsRepository,
                    backupPortabilityService = app.container.backupPortabilityService,
                    cloudAccountRepository = app.container.cloudAccountRepository,
                    circlesRepository = app.container.circlesRepository,
                    cloudRlsGateProvider = app.container.cloudRlsGateProvider
                )
            }
        }

        composeRule.onNodeWithText("Start focus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Learning").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Organize what you study").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Organize what you study").assertIsDisplayed()
        composeRule.onNodeWithText("Planner").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Add task").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add task").assertIsDisplayed()
        composeRule.onNodeWithText("Summary").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Last 7 days").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Last 7 days").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Open Private Circles"))
        composeRule.onNodeWithText("Open Private Circles").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Private Circles").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Offline mode active (cloud sync gated)").assertIsDisplayed()
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
                    plannerRepository = app.container.plannerRepository,
                    calendarInsertGateway = app.container.calendarInsertGateway,
                    userPreferencesRepository = app.container.userPreferencesRepository,
                    diagnosticsRepository = app.container.diagnosticsRepository,
                    backupPortabilityService = app.container.backupPortabilityService,
                    cloudAccountRepository = app.container.cloudAccountRepository,
                    circlesRepository = app.container.circlesRepository,
                    cloudRlsGateProvider = app.container.cloudRlsGateProvider
                )
            }
        }

        composeRule.onNodeWithText("Odağı başlat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Öğrenme").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Çalıştığınız konuları düzenleyin").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Çalıştığınız konuları düzenleyin").assertIsDisplayed()
        composeRule.onNodeWithText("Planlayıcı").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Görev ekle").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Görev ekle").assertIsDisplayed()
        composeRule.onNodeWithText("Özet").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Son 7 gün").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Son 7 gün").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Özel Çemberleri Aç"))
        composeRule.onNodeWithText("Özel Çemberleri Aç").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Özel Çemberler").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Çevrimdışı mod etkin (bulut eşitleme kapalı)").assertIsDisplayed()
        composeRule.onNodeWithText("Ayarlar").performClick()
        composeRule.onNodeWithText("Koyu (Midnight Violet)").assertIsDisplayed()
    }
}
