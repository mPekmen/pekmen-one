package net.pekmen.one.feature.insights

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.SubjectWeeklyProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class InsightsSubjectProgressTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `insights displays weekly subject goals and linked book page progress in English`() {
        val state = sampleInsightsState()
        composeRule.setContent {
            PekmenOneTheme {
                InsightsScreen(
                    state = state,
                    onOpenManual = {},
                    onDismissManual = {},
                    onSaveManual = {},
                    onOpenEdit = {},
                    onDismissEdit = {},
                    onSaveEdit = { _, _, _, _, _, _ -> },
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Weekly subject goals"))
        composeRule.onNodeWithText("Weekly subject goals").assertIsDisplayed()
        composeRule.onNodeWithText("Almanca B2").assertIsDisplayed()
        composeRule.onNodeWithText("25 / 150 min · 1 sessions").assertIsDisplayed()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Almanca B2 · Erkundungen B2 (p. 40 → 52)"))
        composeRule.onNodeWithText("Almanca B2 · Erkundungen B2 (p. 40 → 52)").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "tr")
    fun `insights displays weekly subject goals in Turkish`() {
        val state = sampleInsightsState()
        composeRule.setContent {
            PekmenOneTheme {
                InsightsScreen(
                    state = state,
                    onOpenManual = {},
                    onDismissManual = {},
                    onSaveManual = {},
                    onOpenEdit = {},
                    onDismissEdit = {},
                    onSaveEdit = { _, _, _, _, _, _ -> },
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Haftalık ders hedefleri"))
        composeRule.onNodeWithText("Haftalık ders hedefleri").assertIsDisplayed()
        composeRule.onNodeWithText("25 / 150 dk · 1 oturum").assertIsDisplayed()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Almanca B2 · Erkundungen B2 (s. 40 → 52)"))
        composeRule.onNodeWithText("Almanca B2 · Erkundungen B2 (s. 40 → 52)").assertIsDisplayed()
    }

    private fun sampleInsightsState(): InsightsUiState {
        val study = BuiltInProfiles.defaults().first()
        val subject = LearningSubject("sub-1", title = "Almanca B2", colorHex = 0xFF8B7CF6, weeklyGoalMinutes = 150)
        val book = LearningBook(
            id = "book-1",
            subjectId = "sub-1",
            title = "Erkundungen B2",
            totalPages = 240,
            currentPage = 52
        )
        val progress = SubjectWeeklyProgress(
            subject = subject,
            netFocusSecondsThisWeek = 1500L,
            completedSessionCount = 1,
            books = listOf(book),
            topics = emptyList()
        )
        val linkedSession = CompletedSessionItem(
            session = FocusSession(
                id = "s-1",
                profileId = study.id,
                subjectId = subject.id,
                bookId = book.id,
                bookStartPage = 40,
                bookEndPage = 52,
                sessionMode = FocusSessionMode.POMODORO,
                status = FocusSessionStatus.COMPLETED,
                currentPhase = FocusPhase.FOCUS,
                completedFocusCycles = 1,
                startedAtWallMs = 1_760_000_000_000L,
                effectiveDayDate = LocalDate.of(2026, 11, 19),
                actualFocusSec = 1500L,
                actualBreakSec = 0L,
                pauseCount = 0
            ),
            profile = study
        )
        return InsightsUiState(
            profiles = listOf(study),
            todaySummary = DailyFocusSummary(LocalDate.of(2026, 11, 19), 1500L, 0L, 1, null),
            subjectWeeklyProgress = listOf(progress),
            recentSessions = listOf(linkedSession)
        )
    }
}
