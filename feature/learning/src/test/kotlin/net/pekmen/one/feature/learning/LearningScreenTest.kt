package net.pekmen.one.feature.learning

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.TopicStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LearningScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `renders subject book topic progress in English and dispatches actions`() {
        val subject = LearningSubject(
            id = "sub-1",
            title = "Almanca B2",
            colorHex = 0xFF8B7CF6,
            weeklyGoalMinutes = 150
        )
        val book = LearningBook(
            id = "book-1",
            subjectId = "sub-1",
            title = "Erkundungen B2",
            totalPages = 240,
            currentPage = 40
        )
        val topic = LearningTopic(
            id = "topic-1",
            subjectId = "sub-1",
            bookId = "book-1",
            title = "Schreiben",
            status = TopicStatus.TODO
        )
        val progress = SubjectWeeklyProgress(
            subject = subject,
            netFocusSecondsThisWeek = 1500L,
            completedSessionCount = 1,
            books = listOf(book),
            topics = listOf(topic)
        )
        var focusedSubject: String? = null
        var bumpedPageDelta: Int? = null
        var cycledTopicId: String? = null

        composeRule.setContent {
            PekmenOneTheme {
                LearningScreen(
                    state = LearningUiState(subjectProgress = listOf(progress)),
                    onAddSubject = {},
                    onEditSubject = {},
                    onAddBook = {},
                    onEditBook = {},
                    onStepBookPage = { _, delta -> bumpedPageDelta = delta },
                    onAddTopic = {},
                    onEditTopic = {},
                    onCycleTopicStatus = { cycledTopicId = it.id },
                    onFocusOnSubject = { sId, _, _ -> focusedSubject = sId },
                    onDismissEditor = {},
                    onSaveSubject = { _, _, _, _, _ -> },
                    onDeleteSubject = {},
                    onSaveBook = { _, _, _, _, _, _, _, _ -> },
                    onDeleteBook = {},
                    onSaveTopic = { _, _, _, _, _, _ -> },
                    onDeleteTopic = {}
                )
            }
        }

        composeRule.onNodeWithText("Almanca B2").assertIsDisplayed()
        composeRule.onNodeWithText("25 / 150 min").assertIsDisplayed()
        composeRule.onNodeWithText("Erkundungen B2").assertIsDisplayed()
        composeRule.onNodeWithText("Page 40 / 240").assertIsDisplayed()

        composeRule.onNodeWithText("Focus").performClick()
        assertEquals("sub-1", focusedSubject)

        composeRule.onNodeWithContentDescription("Next page").performClick()
        assertEquals(1, bumpedPageDelta)

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("To do"))
        composeRule.onNodeWithText("Schreiben").assertIsDisplayed()
        composeRule.onNodeWithText("To do").assertIsDisplayed()
        composeRule.onNodeWithText("To do").performClick()
        assertEquals("topic-1", cycledTopicId)
    }

    @Test
    @Config(qualifiers = "tr")
    fun `renders empty learning state and progress in Turkish`() {
        composeRule.setContent {
            PekmenOneTheme {
                LearningScreen(
                    state = LearningUiState(subjectProgress = emptyList()),
                    onAddSubject = {},
                    onEditSubject = {},
                    onAddBook = {},
                    onEditBook = {},
                    onStepBookPage = { _, _ -> },
                    onAddTopic = {},
                    onEditTopic = {},
                    onCycleTopicStatus = {},
                    onDismissEditor = {},
                    onSaveSubject = { _, _, _, _, _ -> },
                    onDeleteSubject = {},
                    onSaveBook = { _, _, _, _, _, _, _, _ -> },
                    onDeleteBook = {},
                    onSaveTopic = { _, _, _, _, _, _ -> },
                    onDeleteTopic = {}
                )
            }
        }

        composeRule.onNodeWithText("Öğrenme").assertIsDisplayed()
        composeRule.onNodeWithText("Çalıştığınız konuları düzenleyin").assertIsDisplayed()
    }
}
