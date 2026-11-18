package net.pekmen.one.feature.focus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.ActiveSessionSnapshot
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.FocusDisplayClock
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class FocusScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `idle state renders profiles modes and triggers start callback`() {
        var started = false
        val profiles = BuiltInProfiles.defaults()

        composeRule.setContent {
            PekmenOneTheme {
                FocusScreen(
                    state = FocusUiState(
                        profiles = profiles,
                        selectedProfile = profiles.first(),
                        selectedMode = FocusSessionMode.POMODORO,
                        todaySummary = DailyFocusSummary(
                            date = LocalDate.of(2026, 11, 19),
                            netFocusSeconds = 1800L,
                            breakSeconds = 300L,
                            completedSessionCount = 2,
                            averageFocusRating = 4.5
                        )
                    ),
                    onSelectProfile = {},
                    onSelectMode = {},
                    onSetCustomMinutes = {},
                    onStart = { started = true },
                    onPause = {},
                    onResume = {},
                    onStartNextPhase = {},
                    onSkipBreakToFocus = {},
                    onOpenWrapUp = {},
                    onDismissWrapUp = {},
                    onSaveWrapUp = { _, _, _ -> },
                    onDiscardShort = {},
                    onRequestNotifications = {},
                    onDismissNotificationBanner = {}
                )
            }
        }

        composeRule.onNodeWithText("Study").assertIsDisplayed()
        composeRule.onNodeWithText("25:00").assertIsDisplayed()
        composeRule.onNodeWithText("Start focus").performClick()
        assertTrue(started)
    }

    @Test
    @Config(qualifiers = "tr")
    fun `reboot paused state displays Turkish recovery banner and controls`() {
        val profiles = BuiltInProfiles.defaults()
        val study = profiles.first()
        val session = FocusSession(
            id = "01900000-0000-7000-8000-000000000101",
            profileId = study.id,
            sessionMode = FocusSessionMode.STOPWATCH,
            status = FocusSessionStatus.PAUSED,
            currentPhase = FocusPhase.FOCUS,
            completedFocusCycles = 0,
            startedAtWallMs = 1_760_000_000_000L,
            effectiveDayDate = LocalDate.of(2026, 11, 19),
            actualFocusSec = 720L,
            actualBreakSec = 0L,
            pauseCount = 0,
            interruptedByReboot = true
        )

        composeRule.setContent {
            PekmenOneTheme {
                FocusScreen(
                    state = FocusUiState(
                        profiles = profiles,
                        selectedProfile = study,
                        selectedMode = FocusSessionMode.STOPWATCH,
                        activeSnapshot = ActiveSessionSnapshot(
                            session = session,
                            profile = study,
                            segments = emptyList(),
                            openSegment = null
                        ),
                        clock = FocusDisplayClock(
                            primarySeconds = 720L,
                            phaseElapsedSeconds = 720L,
                            phasePlannedSeconds = null,
                            totalFocusSeconds = 720L,
                            totalBreakSeconds = 0L,
                            isCountdown = false,
                            progressFraction = 0f
                        )
                    ),
                    onSelectProfile = {},
                    onSelectMode = {},
                    onSetCustomMinutes = {},
                    onStart = {},
                    onPause = {},
                    onResume = {},
                    onStartNextPhase = {},
                    onSkipBreakToFocus = {},
                    onOpenWrapUp = {},
                    onDismissWrapUp = {},
                    onSaveWrapUp = { _, _, _ -> },
                    onDiscardShort = {},
                    onRequestNotifications = {},
                    onDismissNotificationBanner = {}
                )
            }
        }

        composeRule.onNodeWithText("Yeniden başlatma sonrası duraklatıldı").assertIsDisplayed()
        composeRule.onNodeWithText("Devam et").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Bitir").performScrollTo().assertIsDisplayed()
    }
}
