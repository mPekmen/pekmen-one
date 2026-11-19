package net.pekmen.one.feature.focus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.pekmen.one.core.data.FocusSessionRepository
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.ActiveSessionSnapshot
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SessionLearningLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FocusLearningLinkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Config(qualifiers = "en")
    fun `focus screen displays subject book and topic chips before starting`() {
        val study = BuiltInProfiles.defaults().first()
        val subject = LearningSubject("sub-1", title = "Almanca B2", colorHex = 0xFF8B7CF6, weeklyGoalMinutes = 150)
        val book = LearningBook(
            id = "book-1",
            subjectId = "sub-1",
            title = "Erkundungen B2",
            totalPages = 240,
            currentPage = 40
        )
        val topic = LearningTopic("topic-1", subjectId = "sub-1", bookId = "book-1", title = "Schreiben")
        var selectedSubject: String? = null

        composeRule.setContent {
            PekmenOneTheme {
                FocusScreen(
                    state = FocusUiState(
                        profiles = listOf(study),
                        selectedProfile = study,
                        subjects = listOf(subject),
                        books = listOf(book),
                        topics = listOf(topic),
                        selectedSubjectId = subject.id,
                        selectedBookId = book.id,
                        selectedTopicId = topic.id
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
                    onDismissNotificationBanner = {},
                    onSelectSubject = { selectedSubject = it }
                )
            }
        }

        composeRule.onNodeWithText("Almanca B2").performScrollTo().performClick()
        assertEquals("sub-1", selectedSubject)
        composeRule.onNodeWithText("Erkundungen B2 (p. 40/240)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Schreiben").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `wrap up sheet updates book end page and marks topic mastered`() {
        val study = BuiltInProfiles.defaults().first()
        val subject = LearningSubject("sub-1", title = "Almanca B2", colorHex = 0xFF8B7CF6, weeklyGoalMinutes = 150)
        val book = LearningBook(
            id = "book-1",
            subjectId = "sub-1",
            title = "Erkundungen B2",
            totalPages = 240,
            currentPage = 40
        )
        val topic = LearningTopic("topic-1", subjectId = "sub-1", bookId = "book-1", title = "Schreiben")
        var savedPage: Int? = null
        var savedMastered: Boolean? = null
        val session = FocusSession(
            id = "sess-1",
            profileId = study.id,
            subjectId = subject.id,
            bookId = book.id,
            topicId = topic.id,
            bookStartPage = 40,
            sessionMode = FocusSessionMode.POMODORO,
            status = FocusSessionStatus.PAUSED,
            currentPhase = FocusPhase.FOCUS,
            completedFocusCycles = 1,
            startedAtWallMs = 1_760_000_000_000L,
            effectiveDayDate = LocalDate.of(2026, 11, 19),
            actualFocusSec = 1500L,
            actualBreakSec = 0L,
            pauseCount = 1
        )

        composeRule.setContent {
            PekmenOneTheme {
                SessionWrapUpSheet(
                    snapshot = ActiveSessionSnapshot(session, study, emptyList(), null),
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onDiscardShort = {},
                    linkedSubject = subject,
                    linkedBook = book,
                    linkedTopic = topic,
                    onSaveWithLearning = { _, _, _, endPage, mastered ->
                        savedPage = endPage
                        savedMastered = mastered
                    }
                )
            }
        }

        composeRule.onNodeWithText("40").performScrollTo().performTextReplacement("52")
        composeRule.onNodeWithText("Mark topic Schreiben as mastered").performScrollTo().performClick()
        composeRule.onNodeWithText("Save session").performScrollTo().performClick()

        assertEquals(52, savedPage)
        assertEquals(true, savedMastered)
    }

    @Test
    fun `view model prevents duplicate saveWrapUp submission and passes link on start`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val fakeSessionRepo = FakeFocusSessionRepository(gate)
        val subject = LearningSubject("sub-1", title = "Almanca B2", colorHex = 0xFF8B7CF6, weeklyGoalMinutes = 150)
        val book = LearningBook(
            id = "book-1",
            subjectId = "sub-1",
            title = "Erkundungen B2",
            totalPages = 240,
            currentPage = 40
        )
        val topic = LearningTopic("topic-1", subjectId = "sub-1", bookId = "book-1", title = "Schreiben")
        val vm = FocusViewModel(
            sessionRepository = fakeSessionRepo,
            profileRepository = FakeProfileRepo(),
            historyRepository = FakeHistoryRepo(),
            preferencesRepository = FakePrefsRepo(),
            diagnosticsRepository = FakeDiagnosticsRepo(),
            learningRepository = FakeLearningRepo(listOf(subject), listOf(book), listOf(topic))
        )

        val ready = vm.uiState.first { it.profiles.isNotEmpty() && it.subjects.isNotEmpty() }
        assertTrue(ready.subjects.isNotEmpty())
        vm.selectSubject("sub-1")
        vm.selectBook("book-1")
        vm.selectTopic("topic-1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startFocus()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("sub-1", fakeSessionRepo.lastStartLink?.subjectId)
        assertEquals("book-1", fakeSessionRepo.lastStartLink?.bookId)
        assertEquals("topic-1", fakeSessionRepo.lastStartLink?.topicId)
        assertEquals(40, fakeSessionRepo.lastStartLink?.bookStartPage)

        // Rapid double click on saveWrapUp while completion is in flight
        vm.saveWrapUp(null, 5, "Great", bookEndPage = 52, markTopicMastered = true)
        vm.saveWrapUp(null, 4, "Duplicate", bookEndPage = 99, markTopicMastered = false)
        testDispatcher.scheduler.runCurrent()
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeSessionRepo.completeCalls)
        assertEquals(52, fakeSessionRepo.lastBookEndPage)
        assertEquals(true, fakeSessionRepo.lastMarkMastered)
    }

    private class FakeFocusSessionRepository(
        private val completeGate: CompletableDeferred<Unit>
    ) : FocusSessionRepository {
        override val activeSession: Flow<ActiveSessionSnapshot?> = MutableStateFlow(null)
        var lastStartLink: SessionLearningLink? = null
        var completeCalls = 0
        var lastBookEndPage: Int? = null
        var lastMarkMastered = false

        override suspend fun startSession(
            profileId: String,
            mode: FocusSessionMode,
            customDurationMinutes: Int?,
            link: SessionLearningLink?
        ): Result<FocusSession> {
            lastStartLink = link
            return Result.success(
                FocusSession(
                    id = "s-1",
                    profileId = profileId,
                    subjectId = link?.subjectId,
                    bookId = link?.bookId,
                    topicId = link?.topicId,
                    bookStartPage = link?.bookStartPage,
                    sessionMode = mode,
                    status = FocusSessionStatus.RUNNING,
                    currentPhase = FocusPhase.FOCUS,
                    completedFocusCycles = 0,
                    startedAtWallMs = 1_760_000_000_000L,
                    effectiveDayDate = LocalDate.of(2026, 11, 19),
                    actualFocusSec = 0L,
                    actualBreakSec = 0L,
                    pauseCount = 0
                )
            )
        }

        override suspend fun pauseActiveSession() = Unit
        override suspend fun resumeActiveSession() = Unit
        override suspend fun startAwaitingNextPhase() = Unit
        override suspend fun skipBreakAndStartFocus() = Unit
        override suspend fun freezeActiveSessionForWrapUp(): ActiveSessionSnapshot? = null
        override suspend fun completeActiveSession(
            trimmedFocusSeconds: Long?,
            rating: Int?,
            note: String?,
            bookEndPage: Int?,
            markTopicMastered: Boolean
        ): FocusSession? {
            completeCalls += 1
            lastBookEndPage = bookEndPage
            lastMarkMastered = markTopicMastered
            completeGate.await()
            return null
        }

        override suspend fun discardActiveSession() = Unit
        override suspend fun checkpointActiveSession() = Unit
        override suspend fun reconcileActiveSessionAfterBootOrWake() = Unit
        override suspend fun handlePhaseTimerExpired(sessionId: String, segmentId: String) = Unit
    }
}
