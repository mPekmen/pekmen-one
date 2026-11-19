package net.pekmen.one.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.BackupImportReport
import net.pekmen.one.core.model.BackupValidationError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PortabilitySectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `english restore success renders localized title and exact entity counts`() {
        val report = BackupImportReport(
            sourceSchemaVersion = 2,
            profilesRestored = 4,
            subjectsRestored = 2,
            booksRestored = 3,
            topicsRestored = 5,
            sessionsRestored = 7,
            segmentsRestored = 14,
            tasksRestored = 6
        )

        composeRule.setContent {
            PekmenOneTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PortabilitySection(
                        state = PortabilityUiState(
                            isBusy = false,
                            status = PortabilityStatus.RestoreSuccess(report)
                        ),
                        onExportBackupJson = {},
                        onRestoreBackupJson = {},
                        onExportCsv = { _, _ -> }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Backup & Export").assertIsDisplayed()
        composeRule.onNodeWithText("Export JSON backup").assertIsDisplayed()
        composeRule.onNodeWithText("Restore JSON backup").assertIsDisplayed()
        composeRule.onNodeWithText("Restore complete (schema v2)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Profiles: 4 · Sessions: 7 · Segments: 14")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Subjects: 2 · Books: 3 · Topics: 5 · Tasks: 6")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "tr")
    fun `turkish foreign key error and busy state render localized error and disable buttons`() {
        composeRule.setContent {
            PekmenOneTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PortabilitySection(
                        state = PortabilityUiState(
                            isBusy = true,
                            status = PortabilityStatus.Error(
                                error = BackupValidationError.INVALID_FOREIGN_KEY,
                                details = "session missing profileId"
                            )
                        ),
                        onExportBackupJson = {},
                        onRestoreBackupJson = {},
                        onExportCsv = { _, _ -> }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Yedekleme ve Dışa Aktarma").assertIsDisplayed()
        composeRule.onNodeWithText("JSON yedeği dışa aktar").assertIsNotEnabled()
        composeRule.onNodeWithText("İşlem başarısız").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Yedek reddedildi: kayıtlar arasında kopuk referans bulundu. Mevcut veriler değiştirilmedi."
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ayrıntı: session missing profileId").performScrollTo().assertIsDisplayed()
    }
}
