package net.pekmen.one.core.portability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.model.UuidV7
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupPortabilityCsvTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private val databases = mutableListOf<PekmenDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        databases.clear()
    }

    @Test
    fun `CSV export escapes RFC 4180 delimiters and neutralizes formula injection prefixes`() = runTest {
        val db = newDb()
        val store = BackupTestFixtures.newDataStore(tempFolder, this, "csv_prefs")
        val service = BackupPortabilityServiceImpl(db, store, BackupTestFixtures.FixedTimeProvider())
        seedCsvInjectionFixture(db)

        val sessionsCsv = service.exportSessionsCsv().getOrThrow()
        val learningCsv = service.exportLearningCsv().getOrThrow()
        val tasksCsv = service.exportTasksCsv().getOrThrow()

        assertTrue(sessionsCsv.contains("'=CMD('calc')"))
        assertTrue(learningCsv.contains("\"'+SUM(A1,B1)\""))
        assertTrue(learningCsv.contains("\"Book \"\"One\"\", Vol 2\nPart A\""))
        assertTrue(learningCsv.contains("'@evil"))
        assertTrue(learningCsv.contains("'-10+20"))
        assertTrue(tasksCsv.contains("\"'=HYPERLINK(\"\"http://example.com\"\",\"\"click\"\")\""))
        assertTrue(tasksCsv.contains("\"Line1\n\"\"Quoted\"\", comma\""))
    }

    @Test
    fun `JSON and CSV exports isolate data to activeOwnerUserId`() = runTest {
        val db = newDb()
        val store = BackupTestFixtures.newDataStore(tempFolder, this, "owner_prefs")
        val service = BackupPortabilityServiceImpl(db, store, BackupTestFixtures.FixedTimeProvider())

        val baseMs = BackupTestFixtures.BASE_WALL_MS
        db.portabilityDao().upsertProfiles(listOf(BackupTestFixtures.builtInStudyProfile(baseMs)))

        val aliceSync = SyncMetadataEntity(ownerUserId = "alice", createdAtMs = baseMs, clientUpdatedAtMs = baseMs)
        val bobSync = SyncMetadataEntity(ownerUserId = "bob", createdAtMs = baseMs, clientUpdatedAtMs = baseMs)
        db.portabilityDao().upsertSubjects(
            listOf(
                LearningSubjectEntity(UuidV7.generate(baseMs + 1L), "Alice Secret Subject", 1L, 60, sync = aliceSync),
                LearningSubjectEntity(UuidV7.generate(baseMs + 2L), "Bob Visible Subject", 2L, 90, sync = bobSync)
            )
        )
        db.portabilityDao().upsertTasks(
            listOf(
                PlannerTaskEntity(UuidV7.generate(baseMs + 3L), "Alice Secret Task", sync = aliceSync),
                PlannerTaskEntity(UuidV7.generate(baseMs + 4L), "Bob Visible Task", sync = bobSync)
            )
        )

        store.setActiveOwnerUserId("bob")
        val exportedJson = service.exportJsonArchive().getOrThrow()
        val learningCsv = service.exportLearningCsv().getOrThrow()
        val tasksCsv = service.exportTasksCsv().getOrThrow()

        assertFalse(exportedJson.contains("Alice Secret"))
        assertFalse(learningCsv.contains("Alice Secret"))
        assertFalse(tasksCsv.contains("Alice Secret"))
        assertTrue(exportedJson.contains("Bob Visible Subject"))
        assertTrue(learningCsv.contains("Bob Visible Subject"))
        assertTrue(tasksCsv.contains("Bob Visible Task"))
    }

    private suspend fun seedCsvInjectionFixture(db: PekmenDatabase) {
        val baseMs = BackupTestFixtures.BASE_WALL_MS
        val profile = BackupTestFixtures.builtInStudyProfile(baseMs)
        val subjectId = UuidV7.generate(baseMs + 1L)
        val bookId = UuidV7.generate(baseMs + 2L)
        val topicId = UuidV7.generate(baseMs + 3L)
        val sessionId = UuidV7.generate(baseMs + 4L)
        val taskId = UuidV7.generate(baseMs + 5L)
        val sync = SyncMetadataEntity(createdAtMs = baseMs, clientUpdatedAtMs = baseMs)

        db.portabilityDao().upsertProfiles(listOf(profile))
        db.portabilityDao().upsertSubjects(
            listOf(LearningSubjectEntity(subjectId, "+SUM(A1,B1)", 0xFF8B7CF6, 120, sync = sync))
        )
        db.portabilityDao().upsertBooks(
            listOf(
                LearningBookEntity(
                    id = bookId,
                    subjectId = subjectId,
                    title = "Book \"One\", Vol 2\nPart A",
                    author = "@evil",
                    totalPages = 100,
                    sync = sync
                )
            )
        )
        db.portabilityDao().upsertTopics(
            listOf(LearningTopicEntity(topicId, subjectId, bookId, "-10+20", TopicStatus.TODO.name, sync = sync))
        )
        db.portabilityDao().upsertSessions(
            listOf(
                FocusSessionEntity(
                    id = sessionId,
                    profileId = profile.id,
                    subjectId = subjectId,
                    bookId = bookId,
                    topicId = topicId,
                    taskId = taskId,
                    sessionMode = FocusSessionMode.POMODORO.name,
                    status = FocusSessionStatus.COMPLETED.name,
                    currentPhase = FocusPhase.FOCUS.name,
                    completedFocusCycles = 1,
                    startedAtWallMs = baseMs,
                    endedAtWallMs = baseMs + 1500_000L,
                    effectiveDayDate = "2026-11-19",
                    actualFocusSec = 1500L,
                    actualBreakSec = 0L,
                    pauseCount = 0,
                    reflectionNote = "=CMD('calc')",
                    sync = sync
                )
            )
        )
        db.portabilityDao().upsertTasks(
            listOf(
                PlannerTaskEntity(
                    id = taskId,
                    title = "=HYPERLINK(\"http://example.com\",\"click\")",
                    notes = "Line1\n\"Quoted\", comma",
                    subjectId = subjectId,
                    sync = sync
                )
            )
        )
    }

    private fun newDb(): PekmenDatabase {
        return PekmenDatabase.createInMemory(context).also { databases.add(it) }
    }
}
