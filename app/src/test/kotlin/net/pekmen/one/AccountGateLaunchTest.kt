package net.pekmen.one

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class AccountGateLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun coldStartWithoutCredentialsKeepsPersonalNavigationClosed() {
        assumeTrue(BuildConfig.SUPABASE_URL.isBlank())
        val configurationMessage = compose.activity.getString(R.string.auth_configuration)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(configurationMessage).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(configurationMessage).assertIsDisplayed()
        compose.onNodeWithText("Start focus").assertDoesNotExist()
    }
}
