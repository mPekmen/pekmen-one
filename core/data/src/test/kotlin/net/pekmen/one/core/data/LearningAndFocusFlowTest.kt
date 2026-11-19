package net.pekmen.one.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SessionLearningLink
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.FocusNotificationGateway
import net.pekmen.one.core.platform.ProfileNameProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class LearningAndFocusFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fakeTime: FakeTimeProvider
    private lateinit var prefsFile: File
    private val dbName = "learning_focus_flow_test.db"
    private var openDatabase: PekmenDatabase? = null
    private var dataStoreScope: kotlinx.coroutines.CoroutineScope? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        deleteDatabaseFiles(dbFile)
        val baseWall = LocalDateTime.of(2026, 11, 19, 14, 0, 0)
            .atZone(ZoneId.of("Europe/Istanbul"))
            .toInstant()
            .toEpochMilli()
        fakeTime = FakeTimeProvider(
            wallMs = baseWall,
            elapsedMs = 100_000L,
            boot = 3,
            zone = ZoneId.of("Europe/Istanbul")
        )
        prefsFile = File(tempFolder.newFolder(), "flow_prefs.preferences_pb")
    }

    @After
    fun tearDown() {
        dataStoreScope?.cancel()
        dataStoreScope = null
        openDatabase?.close()
        openDatabase = null
        deleteDatabaseFiles(context.getDatabasePath(dbName))
    }

    @Test
    fun `almanca b2 flow updates book and persists reopen`() =
        runTest {
            val today = LocalDate.of(2026, 11, 19)
            val bundle = openStack()
            val (subjectId, bookId, topicId) = seedAlmancaSetup(bundle)

            runLinked25MinPomodoro(bundle, subjectId, bookId, topicId)
            verifyIdempotentRetryAndInsights(bundle, today, subjectId)
            verifyLegacySessionExclusion(bundle, today, subjectId)

            dataStoreScope?.cancel()
            dataStoreScope = null
            bundle.database.close()
            openDatabase = null

            val reopened = openStack()
            val reopenedProgress = reopened.learning.observeWeeklySubjectProgress(today).first()
            assertEquals(1, reopenedProgress.size)
            assertEquals("Almanca B2", reopenedProgress.first().subject.title)
            assertEquals(1500L, reopenedProgress.first().netFocusSecondsThisWeek)
            assertEquals(1500L, reopenedProgress.first().netFocusSecondsToday)
            assertEquals(52, reopenedProgress.first().books.first().currentPage)
            assertEquals(TopicStatus.MASTERED, reopenedProgress.first().topics.first().status)
            val topicHistory = reopened.learning.observeTopicHistory(topicId).first()
            assertEquals(1500L, topicHistory.single().session.actualFocusSec)

            val reopenedDaily = reopened.history.observeDailySummary(today).first()
            assertEquals(2700L, reopenedDaily.netFocusSeconds)
            assertEquals(2, reopenedDaily.completedSessionCount)
        }

    @Test
    fun `subtopics are scoped to their lesson and survive deletion of their parent`() = runTest {
        val bundle = openStack()
        val lesson = bundle.learning.saveSubject(
            SaveSubjectRequest(title = "Math", colorHex = 0xFF60A5FA, weeklyGoalMinutes = 150, dailyGoalMinutes = 20)
        ).getOrThrow()
        val parent = bundle.learning.saveTopic(SaveTopicRequest(subjectId = lesson.id, title = "Calculus")).getOrThrow()
        val childRequest = SaveTopicRequest(subjectId = lesson.id, title = "Limits", parentTopicId = parent.id)
        val child = bundle.learning.saveTopic(childRequest).getOrThrow()

        assertEquals(20, bundle.learning.observeSubjects().first().single().dailyGoalMinutes)
        assertEquals(parent.id, child.parentTopicId)
        assertTrue(bundle.learning.saveTopic(childRequest.copy(parentTopicId = child.id)).isFailure)
        bundle.learning.softDeleteTopic(parent.id).getOrThrow()
        val remaining = bundle.learning.observeTopics(lesson.id).first().single()
        assertEquals(child.id, remaining.id)
        assertNull(remaining.parentTopicId)
    }

    @Test
    fun `focus only accepts an existing open task from the active owner`() = runTest {
        val bundle = openStack()
        val dao = bundle.database.plannerTaskDao()
        val task = PlannerTaskEntity(
            id = "test-planner-task",
            title = "Read with focus",
            sync = SyncMetadataEntity(ownerUserId = "account-a", createdAtMs = 1, clientUpdatedAtMs = 1)
        )
        val link = SessionLearningLink(taskId = task.id)
        val profile = BuiltInProfiles.STUDY_ID
        val mode = FocusSessionMode.STOPWATCH
        bundle.dataStore.setActiveOwnerUserId("account-b")
        dao.upsertTask(task)
        assertTrue(bundle.sessions.startSession(profile, mode, link = link).isFailure)

        bundle.dataStore.setActiveOwnerUserId("account-a")
        dao.softDeleteTask(task.id, 2, 2)
        assertTrue(bundle.sessions.startSession(profile, mode, link = link).isFailure)

        dao.upsertTask(task)
        val session = bundle.sessions.startSession(profile, mode, link = link).getOrThrow()
        assertEquals(task.id, session.taskId)
    }

    @Test
    fun `owner isolation edit cascade and tombstones`() =
        runTest {
            val today = LocalDate.of(2026, 11, 19)
            val bundle = openStack()

            bundle.dataStore.setActiveOwnerUserId("user-a")
            val subjectA = bundle.learning.saveSubject(
                SaveSubjectRequest(title = "Physics", colorHex = 0xFF60A5FA, weeklyGoalMinutes = 120)
            ).getOrThrow()
            val bookA = bundle.learning.saveBook(
                SaveBookRequest(subjectId = subjectA.id, title = "Mechanics", totalPages = 100, currentPage = 80)
            ).getOrThrow()
            bundle.learning.saveTopic(
                SaveTopicRequest(subjectId = subjectA.id, bookId = bookA.id, title = "Kinematics")
            ).getOrThrow()

            // Updating subject goal must NOT cascade-delete child book/topic
            bundle.learning.saveSubject(
                SaveSubjectRequest(
                    existingId = subjectA.id,
                    title = "Physics II",
                    colorHex = 0xFF60A5FA,
                    weeklyGoalMinutes = 180
                )
            ).getOrThrow()
            assertEquals(1, bundle.learning.observeBooks(subjectA.id).first().size)
            assertEquals(1, bundle.learning.observeTopics(subjectA.id).first().size)

            bundle.sessions.startSession(
                profileId = BuiltInProfiles.STUDY_ID,
                mode = FocusSessionMode.CUSTOM,
                customDurationMinutes = 30,
                link = SessionLearningLink(subjectId = subjectA.id, bookId = bookA.id)
            ).getOrThrow()
            fakeTime.advanceBy(30 * 60_000L)
            bundle.sessions.completeActiveSession(bookEndPage = 100)
            val finishedBook = bundle.learning.observeBooks(subjectA.id).first().first()
            assertTrue(finishedBook.isCompleted)
            assertEquals(100, finishedBook.currentPage)

            bundle.dataStore.setActiveOwnerUserId("user-b")
            assertTrue(bundle.learning.observeSubjects().first().isEmpty())
            assertEquals(0L, bundle.history.observeDailySummary(today).first().netFocusSeconds)
            assertTrue(bundle.history.observeRecentCompletedSessions().first().isEmpty())

            bundle.dataStore.setActiveOwnerUserId("user-a")
            bundle.learning.softDeleteSubject(subjectA.id).getOrThrow()
            assertTrue(bundle.learning.observeSubjects().first().isEmpty())
            assertTrue(bundle.learning.observeBooks().first().isEmpty())
            assertTrue(bundle.learning.observeTopics().first().isEmpty())

            val deleteOutbox = bundle.database.syncOutboxDao().observeOutbox().first()
                .filter { it.opType == "DELETE" && it.ownerUserId == "user-a" }
            assertEquals(3, deleteOutbox.size)
        }

    private suspend fun seedAlmancaSetup(bundle: StackBundle): Triple<String, String, String> {
        val subject = bundle.learning.saveSubject(
            SaveSubjectRequest(title = "Almanca B2", colorHex = 0xFF8B7CF6, weeklyGoalMinutes = 150)
        ).getOrThrow()
        val book = bundle.learning.saveBook(
            SaveBookRequest(subjectId = subject.id, title = "Erkundungen B2", totalPages = 240, currentPage = 40)
        ).getOrThrow()
        val topic = bundle.learning.saveTopic(
            SaveTopicRequest(subjectId = subject.id, bookId = book.id, title = "Schreiben", status = TopicStatus.TODO)
        ).getOrThrow()
        return Triple(subject.id, book.id, topic.id)
    }

    private suspend fun runLinked25MinPomodoro(
        bundle: StackBundle,
        subjectId: String,
        bookId: String,
        topicId: String
    ) {
        val started = bundle.sessions.startSession(
            profileId = BuiltInProfiles.STUDY_ID,
            mode = FocusSessionMode.POMODORO,
            link = SessionLearningLink(subjectId = subjectId, bookId = bookId, topicId = topicId)
        ).getOrThrow()
        assertEquals(subjectId, started.subjectId)
        assertEquals(bookId, started.bookId)
        assertEquals(topicId, started.topicId)
        assertEquals(40, started.bookStartPage)

        fakeTime.advanceBy(25 * 60_000L)
        val completed = bundle.sessions.completeActiveSession(
            rating = 5,
            note = "Writing practice units 1-2",
            bookEndPage = 52,
            markTopicMastered = true
        )
        assertNotNull(completed)
        assertEquals(FocusSessionStatus.COMPLETED, completed?.status)
        assertEquals(1500L, completed?.actualFocusSec)
        assertEquals(40, completed?.bookStartPage)
        assertEquals(52, completed?.bookEndPage)
    }

    private suspend fun TestScope.verifyIdempotentRetryAndInsights(
        bundle: StackBundle,
        today: LocalDate,
        subjectId: String
    ) {
        val duplicateResults = listOf(
            async { bundle.sessions.completeActiveSession(trimmedFocusSeconds = 600L, bookEndPage = 99) },
            async { bundle.sessions.completeActiveSession(trimmedFocusSeconds = 300L, bookEndPage = 120) }
        ).awaitAll()
        assertTrue(duplicateResults.all { it == null })

        val booksAfter = bundle.learning.observeBooks(subjectId).first()
        assertEquals(1, booksAfter.size)
        assertEquals(52, booksAfter.first().currentPage)
        assertFalse(booksAfter.first().isCompleted)

        val topicsAfter = bundle.learning.observeTopics(subjectId).first()
        assertEquals(TopicStatus.MASTERED, topicsAfter.first().status)

        val outboxTables = bundle.database.syncOutboxDao().observeOutbox().first()
            .map { it.entityTable }
            .toSet()
        assertTrue("learning_subjects" in outboxTables)
        assertTrue("learning_books" in outboxTables)
        assertTrue("learning_topics" in outboxTables)
        assertTrue("focus_sessions" in outboxTables)

        val dailyBeforeLegacy = bundle.history.observeDailySummary(today).first()
        assertEquals(1500L, dailyBeforeLegacy.netFocusSeconds)
        assertEquals(1, dailyBeforeLegacy.completedSessionCount)

        val almancaProgress = bundle.learning.observeWeeklySubjectProgress(today).first()
            .first { it.subject.id == subjectId }
        assertEquals(1500L, almancaProgress.netFocusSecondsThisWeek)
        assertEquals(1, almancaProgress.completedSessionCount)
        assertEquals(1500f / 9000f, almancaProgress.goalProgressFraction, 1e-4f)
    }

    private suspend fun verifyLegacySessionExclusion(
        bundle: StackBundle,
        today: LocalDate,
        subjectId: String
    ) {
        val legacySession = bundle.history.logRetroactiveSession(
            RetroactiveSessionRequest(
                profileId = BuiltInProfiles.WORK_ID,
                startedAtWallMs = fakeTime.wallTimeMs() - 120_000L,
                focusMinutes = 20,
                breakMinutes = 0,
                rating = 4,
                note = "Legacy unlinked session"
            )
        ).getOrThrow()
        assertNull(legacySession.subjectId)

        val dailyWithLegacy = bundle.history.observeDailySummary(today).first()
        assertEquals(2700L, dailyWithLegacy.netFocusSeconds)
        assertEquals(2, dailyWithLegacy.completedSessionCount)

        val subjectAfterLegacy = bundle.learning.observeWeeklySubjectProgress(today).first()
            .first { it.subject.id == subjectId }
        assertEquals(1500L, subjectAfterLegacy.netFocusSecondsThisWeek)
        assertEquals(1, subjectAfterLegacy.completedSessionCount)
    }

    private fun openStack(): StackBundle {
        val db = PekmenDatabase.createFileBackedForTest(context, dbName)
        openDatabase = db
        val dsScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        dataStoreScope = dsScope
        val rawStore = PreferenceDataStoreFactory.create(scope = dsScope) { prefsFile }
        val dataStore = UserPreferencesDataStore(rawStore)
        val profiles = FocusProfileRepositoryImpl(db.focusProfileDao(), fakeTime)
        val learning = LearningRepositoryImpl(db, dataStore, fakeTime)
        val sessions = FocusSessionRepositoryImpl(
            database = db,
            profileRepository = profiles,
            preferencesDataStore = dataStore,
            timeProvider = fakeTime,
            notificationGateway = NoOpNotificationGateway,
            alarmGateway = NoOpAlarmGateway,
            profileNameProvider = ProfileNameProvider { it.customName ?: it.builtInSlug.name }
        )
        val history = FocusHistoryRepositoryImpl(db, profiles, dataStore, fakeTime)
        return StackBundle(db, dataStore, learning, sessions, history)
    }

    private fun deleteDatabaseFiles(dbFile: File) {
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()
    }

    private data class StackBundle(
        val database: PekmenDatabase,
        val dataStore: UserPreferencesDataStore,
        val learning: LearningRepositoryImpl,
        val sessions: FocusSessionRepositoryImpl,
        val history: FocusHistoryRepositoryImpl
    )

    private class FakeTimeProvider(
        private var wallMs: Long,
        private var elapsedMs: Long,
        private var boot: Int,
        private val zone: ZoneId
    ) : TimeProvider {
        override fun wallTimeMs(): Long = wallMs
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun bootCount(): Int = boot
        override fun zoneId(): ZoneId = zone

        fun advanceBy(deltaMs: Long) {
            wallMs += deltaMs
            elapsedMs += deltaMs
        }
    }

    private object NoOpNotificationGateway : FocusNotificationGateway {
        override fun showRunningNotification(
            sessionId: String,
            profileName: String,
            phase: FocusPhase,
            isCountdown: Boolean,
            chronometerBaseWallMs: Long
        ) = Unit

        override fun showPausedNotification(
            sessionId: String,
            profileName: String,
            phase: FocusPhase,
            netFocusSec: Long,
            interruptedByReboot: Boolean
        ) = Unit

        override fun showAwaitingNextPhaseNotification(
            sessionId: String,
            profileName: String,
            completedPhase: FocusPhase,
            nextPhase: FocusPhase?
        ) = Unit

        override fun showPhaseCompletedAlert(
            profileName: String,
            completedPhase: FocusPhase,
            nextPhase: FocusPhase?
        ) = Unit

        override fun cancelAllSessionNotifications() = Unit
    }

    private object NoOpAlarmGateway : FocusAlarmGateway {
        override fun schedulePhaseEnd(sessionId: String, segmentId: String, triggerAtElapsedMs: Long) = Unit
        override fun cancelPhaseEnd() = Unit
        override fun canScheduleExactAlarms(): Boolean = true
    }
}
