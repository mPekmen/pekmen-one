package net.pekmen.one

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.feature.circles.CirclesScreen
import net.pekmen.one.feature.circles.CirclesScreenActions
import net.pekmen.one.feature.circles.CirclesUiState
import net.pekmen.one.feature.focus.FocusScreen
import net.pekmen.one.feature.focus.FocusUiState
import net.pekmen.one.feature.insights.InsightsScreen
import net.pekmen.one.feature.insights.InsightsUiState
import net.pekmen.one.feature.learning.LearningScreen
import net.pekmen.one.feature.learning.LearningUiState
import net.pekmen.one.feature.planner.PlannerScreen
import net.pekmen.one.feature.planner.PlannerUiState
import net.pekmen.one.feature.settings.SettingsScreen
import net.pekmen.one.feature.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PekmenPreviewSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `renders focus screen in light theme`() {
        val defaultProfiles = BuiltInProfiles.defaults()
        composeRule.setContent {
            PekmenOneTheme(themePreference = ThemePreference.LIGHT) {
                FocusScreen(
                    state = FocusUiState(profiles = defaultProfiles, selectedProfile = defaultProfiles.first()),
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
        composeRule.onNodeWithText("Start focus").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "tr")
    fun `renders turkish learning with scaled font 1_3x`() {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.3f)
            ) {
                PekmenOneTheme(themePreference = ThemePreference.DARK) {
                    LearningScreen(
                        state = LearningUiState(),
                        onAddSubject = {},
                        onEditSubject = {},
                        onAddBook = {},
                        onEditBook = {},
                        onStepBookPage = { _, _ -> },
                        onAddTopic = {},
                        onEditTopic = {},
                        onCycleTopicStatus = {},
                        onDismissEditor = {},
                        onSaveSubject = { _, _, _, _ -> },
                        onDeleteSubject = {},
                        onSaveBook = { _, _, _, _, _, _ -> },
                        onDeleteBook = {},
                        onSaveTopic = { _, _, _, _, _ -> },
                        onDeleteTopic = {}
                    )
                }
            }
        }
        composeRule.onNodeWithText("Öğrenme").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `renders planner insights settings and circles dark`() {
        var pageIndex by mutableIntStateOf(0)
        composeRule.setContent {
            PekmenOneTheme(themePreference = ThemePreference.DARK) {
                when (pageIndex) {
                    0 -> PlannerScreen(
                        state = PlannerUiState(),
                        onAddTask = {},
                        onEditTask = {},
                        onDismissEditor = {},
                        onSaveTask = {},
                        onCompleteTask = {},
                        onReopenTask = {},
                        onCancelTask = {},
                        onDeleteTask = {},
                        onFocusOnTask = { _, _ -> },
                        onExportToCalendar = {},
                        onRequestCalendarPermission = {},
                        onDismissCalendarBanner = {}
                    )
                    1 -> InsightsScreen(
                        state = InsightsUiState(),
                        onOpenManual = {},
                        onDismissManual = {},
                        onSaveManual = {},
                        onOpenEdit = {},
                        onDismissEdit = {},
                        onSaveEdit = { _, _, _, _, _, _ -> },
                        onDeleteSession = {},
                        onOpenCircles = {}
                    )
                    2 -> SettingsScreen(
                        state = SettingsUiState(),
                        onSelectTheme = {},
                        onChangeRolloverHour = {},
                        onToggleAutoBreaks = {},
                        onToggleAutoNextFocus = {},
                        onEditProfile = {},
                        onCreateProfile = {},
                        onDismissProfileEditor = {},
                        onSaveProfile = {}
                    )
                    else -> CirclesScreen(
                        state = CirclesUiState(gateStatus = CloudRlsGateStatus(false, false, false)),
                        actions = CirclesScreenActions()
                    )
                }
            }
        }
        composeRule.onNodeWithText("Add task").assertIsDisplayed()
        composeRule.runOnIdle { pageIndex = 1 }
        composeRule.onNodeWithText("Summary").assertIsDisplayed()
        composeRule.runOnIdle { pageIndex = 2 }
        composeRule.onNodeWithText("Dark (Midnight Violet)").assertIsDisplayed()
        composeRule.runOnIdle { pageIndex = 3 }
        composeRule.onNodeWithText("Private Circles").assertIsDisplayed()
    }
}
