package net.pekmen.one

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `navigates across top-level destinations in English`() {
        val app = ApplicationProvider.getApplicationContext<PekmenApplication>()
        composeRule.setContent {
            PekmenOneTheme {
                PekmenAppShell(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    userPreferencesRepository = app.container.userPreferencesRepository
                )
            }
        }

        composeRule.onNodeWithText("Focus foundation initialized. Timer engine arrives in M1.").assertIsDisplayed()
        composeRule.onNodeWithText("Summary").performClick()
        composeRule.onNodeWithText("Real daily and weekly Room summaries arrive in M1.").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Dark (Midnight Violet)").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "tr")
    fun `navigates across top-level destinations in Turkish`() {
        val app = ApplicationProvider.getApplicationContext<PekmenApplication>()
        composeRule.setContent {
            PekmenOneTheme {
                PekmenAppShell(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    userPreferencesRepository = app.container.userPreferencesRepository
                )
            }
        }

        composeRule.onNodeWithText("Odak temeli hazırlandı. Zamanlayıcı motoru M1 ile eklenecek.").assertIsDisplayed()
        composeRule.onNodeWithText("Özet").performClick()
        composeRule.onNodeWithText("Gerçek Room günlük ve haftalık özetleri M1 ile eklenecek.").assertIsDisplayed()
        composeRule.onNodeWithText("Ayarlar").performClick()
        composeRule.onNodeWithText("Koyu (Midnight Violet)").assertIsDisplayed()
    }
}
