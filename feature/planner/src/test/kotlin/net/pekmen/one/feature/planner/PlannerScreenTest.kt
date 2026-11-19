package net.pekmen.one.feature.planner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import net.pekmen.one.core.data.planner.SavePlannerTaskRequest
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.ExternalCalendarItem
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PlannerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `english agenda renders overdue open completed tasks calendar banner and callbacks`() {
        val zone = ZoneId.of("Europe/Istanbul")
        val nowMs = LocalDateTime.of(2026, 11, 19, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val subject = LearningSubject(
            id = "sub-1",
            title = "Almanca B2",
            colorHex = 0xFF8B7CF6,
            weeklyGoalMinutes = 150
        )
        val overdue = PlannerTask(
            id = "t-overdue",
            title = "Overdue grammar drill",
            subjectId = subject.id,
            dueAtWallMs = nowMs - 3_600_000L,
            status = TaskStatus.OPEN
        )
        val open = PlannerTask(
            id = "t-open",
            title = "Finish writing sheet",
            subjectId = subject.id,
            dueAtWallMs = nowMs + 3_600_000L,
            recurrenceRule = TaskRecurrenceRule.DAILY,
            status = TaskStatus.OPEN
        )
        val done = PlannerTask(
            id = "t-done",
            title = "Submitted form",
            status = TaskStatus.COMPLETED,
            completedAtWallMs = nowMs - 1_800_000L
        )

        var focusedTask: Pair<String, String?>? = null
        var exportedTask: PlannerTask? = null
        var permissionRequested = false

        composeRule.setContent {
            PekmenOneTheme {
                PlannerScreen(
                    state = PlannerUiState(
                        overdueTasks = listOf(overdue),
                        openTasks = listOf(open),
                        completedTasks = listOf(done),
                        calendarResult = CalendarReadResult.PermissionDenied,
                        subjects = listOf(subject),
                        nowWallMs = nowMs,
                        zoneId = zone
                    ),
                    onAddTask = {},
                    onEditTask = {},
                    onDismissEditor = {},
                    onSaveTask = {},
                    onCompleteTask = {},
                    onReopenTask = {},
                    onCancelTask = {},
                    onDeleteTask = {},
                    onFocusOnTask = { taskId, subjectId -> focusedTask = taskId to subjectId },
                    onExportToCalendar = { exportedTask = it },
                    onRequestCalendarPermission = { permissionRequested = true },
                    onDismissCalendarBanner = {}
                )
            }
        }

        composeRule.onNodeWithText("Show calendar events").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        assertTrue(permissionRequested)

        composeRule.onNodeWithText("Overdue").assertIsDisplayed()
        composeRule.onNodeWithText("Overdue grammar drill").assertIsDisplayed()

        composeRule.onAllNodesWithText("Start Focus").onFirst().performClick()
        assertEquals("t-overdue" to "sub-1", focusedTask)

        composeRule.onAllNodesWithText("Export to Calendar").onFirst().performClick()
        assertEquals(overdue.id, exportedTask?.id)

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Finish writing sheet"))
        composeRule.onNodeWithText("Finish writing sheet").assertIsDisplayed()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Submitted form"))
        composeRule.onNodeWithText("Submitted form").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "tr")
    fun `turkish agenda shows calendar events and editor saves daily recurring task`() {
        val zone = ZoneId.of("Europe/Istanbul")
        val nowMs = LocalDateTime.of(2026, 11, 19, 12, 0).atZone(zone).toInstant().toEpochMilli()
        var savedRequest: SavePlannerTaskRequest? = null
        var editorOpen by mutableStateOf(false)

        composeRule.setContent {
            PekmenOneTheme {
                PlannerScreen(
                    state = PlannerUiState(
                        calendarResult = CalendarReadResult.Available(
                            listOf(
                                ExternalCalendarItem(
                                    eventId = 10L,
                                    title = "Takım Toplantısı",
                                    beginWallMs = nowMs + 3_600_000L,
                                    endWallMs = nowMs + 7_200_000L,
                                    allDay = false,
                                    calendarDisplayName = "İş"
                                )
                            )
                        ),
                        isEditorOpen = editorOpen,
                        nowWallMs = nowMs,
                        zoneId = zone
                    ),
                    onAddTask = { editorOpen = true },
                    onEditTask = {},
                    onDismissEditor = { editorOpen = false },
                    onSaveTask = {
                        savedRequest = it
                        editorOpen = false
                    },
                    onCompleteTask = {},
                    onReopenTask = {},
                    onCancelTask = {},
                    onDeleteTask = {},
                    onFocusOnTask = { _, _ -> },
                    onExportToCalendar = {},
                    onRequestCalendarPermission = {},
                    onDismissCalendarBanner = {}
                )
            }
        }

        composeRule.onNodeWithText("Takım Toplantısı").assertIsDisplayed()
        composeRule.onNodeWithText("Görev ekle").performClick()

        composeRule.onNodeWithText("Görev başlığı").performTextInput("Almanca kelime tekrarı")
        composeRule.onNodeWithText("Günlük").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Kaydet").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertNotNull(savedRequest)
        assertEquals("Almanca kelime tekrarı", savedRequest?.title)
        assertEquals(TaskRecurrenceRule.DAILY, savedRequest?.recurrenceRule)
    }
}
