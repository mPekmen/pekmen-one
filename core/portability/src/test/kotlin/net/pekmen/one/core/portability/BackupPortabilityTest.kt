package net.pekmen.one.core.portability

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BackupImportResult
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.model.UuidV7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class BackupPortabilityTest {

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
    fun `legacy v1 archive restores profiles sessions segments and preferences into Room v2`() = runTest {
        val db = newDb()
        val store = newDataStore(this, "v1_prefs")
        val service = BackupPortabilityServiceImpl(db, store, FixedTimeProvider(BASE_WALL_MS))

        val profileId = BuiltInProfiles.STUDY_ID
        val sessionId = UuidV7.generate(BASE_WALL_MS + 10L)
        val segmentId = UuidV7.generate(BASE_WALL_MS + 20L)
        val v1Json = buildLegacyV1Json(profileId, sessionId, segmentId)

        val result = service.importJsonArchive(v1Json)
        assertTrue(result is BackupImportResult.Success)
        val report = (result as BackupImportResult.Success).report
        assertEquals(1, report.sourceSchemaVersion)
        assertEquals(1, report.profilesRestored)
        assertEquals(0, report.subjectsRestored)
        assertEquals(0, report.booksRestored)
        assertEquals(0, report.topicsRestored)
        assertEquals(1, report.sessionsRestored)
        assertEquals(1, report.segmentsRestored)
        assertEquals(0, report.tasksRestored)

        val sessions = db.portabilityDao().getSessionsForOwnerOnce(null)
        assertEquals(1, sessions.size)
        assertEquals(sessionId, sessions.first().id)
        assertNull(sessions.first().bookStartPage)
        assertEquals(1, db.portabilityDao().getSegmentsForOwnerOnce(null).size)

        val prefs = store.preferences.first()
        assertEquals(ThemePreference.DARK, prefs.themePreference)
        assertEquals(6, prefs.dayRolloverHour)
        assertEquals(profileId, prefs.selectedProfileId)
        assertTrue(prefs.autoStartBreaks)
        assertFalse(prefs.zenIntegrationEnabled)
    }

    @Test
    fun `full v2 round-trip preserves M1 M2 M3 entities SyncMetadata UUIDv7 and preferences`() = runTest {
        val sourceDb = newDb()
        val sourceStore = newDataStore(this, "src_prefs")
        val sourceService = BackupPortabilityServiceImpl(sourceDb, sourceStore, FixedTimeProvider(BASE_WALL_MS))
        val fixture = seedFullV2Fixture(sourceDb, sourceStore, ownerUserId = "owner-uuid-1")

        val exportedJson = sourceService.exportJsonArchive().getOrThrow()
        assertTrue(exportedJson.contains("\"schemaVersion\": 2"))
        assertFalse(exportedJson.contains("activeOwnerUserId"))

        val targetDb = newDb()
        val targetStore = newDataStore(this, "dst_prefs")
        val targetService = BackupPortabilityServiceImpl(targetDb, targetStore, FixedTimeProvider(BASE_WALL_MS + 5000L))

        val result = targetService.importJsonArchive(exportedJson)
        assertTrue(result is BackupImportResult.Success)
        val report = (result as BackupImportResult.Success).report
        assertEquals(2, report.sourceSchemaVersion)
        assertEquals(2, report.profilesRestored)
        assertEquals(1, report.subjectsRestored)
        assertEquals(1, report.booksRestored)
        assertEquals(1, report.topicsRestored)
        assertEquals(1, report.sessionsRestored)
        assertEquals(2, report.segmentsRestored)
        assertEquals(1, report.tasksRestored)

        assertFullFixturePreserved(targetDb, targetStore, fixture, ownerUserId = "owner-uuid-1")
    }

    @Test
    fun `double importing same v2 archive is 100 percent idempotent without duplicates`() = runTest {
        val sourceDb = newDb()
        val sourceStore = newDataStore(this, "idem_src")
        val sourceService = BackupPortabilityServiceImpl(sourceDb, sourceStore, FixedTimeProvider(BASE_WALL_MS))
        seedFullV2Fixture(sourceDb, sourceStore, ownerUserId = null)
        val archiveJson = sourceService.exportJsonArchive().getOrThrow()

        val targetDb = newDb()
        val targetStore = newDataStore(this, "idem_dst")
        val targetService = BackupPortabilityServiceImpl(targetDb, targetStore, FixedTimeProvider(BASE_WALL_MS))

        val firstImport = targetService.importJsonArchive(archiveJson)
        val secondImport = targetService.importJsonArchive(archiveJson)

        assertTrue(firstImport is BackupImportResult.Success)
        assertTrue(secondImport is BackupImportResult.Success)
        assertEquals(2, targetDb.portabilityDao().getProfilesForOwnerOnce(null).size)
        assertEquals(1, targetDb.portabilityDao().getSubjectsForOwnerOnce(null).size)
        assertEquals(1, targetDb.portabilityDao().getBooksForOwnerOnce(null).size)
        assertEquals(1, targetDb.portabilityDao().getTopicsForOwnerOnce(null).size)
        assertEquals(1, targetDb.portabilityDao().getSessionsForOwnerOnce(null).size)
        assertEquals(2, targetDb.portabilityDao().getSegmentsForOwnerOnce(null).size)
        assertEquals(1, targetDb.portabilityDao().getTasksForOwnerOnce(null).size)
    }

    @Test
    fun `malformed JSON returns MALFORMED_JSON without touching existing DB or preferences`() = runTest {
        val db = newDb()
        val store = newDataStore(this, "malformed_prefs")
        val service = BackupPortabilityServiceImpl(db, store, FixedTimeProvider(BASE_WALL_MS))
        val baseline = seedPreExistingBaseline(db, store)

        val cases = listOf(
            "",
            "{not valid json",
            "{}",
            """{"schemaVersion":"2","exportedAtWallMs":100}""",
            """{"schemaVersion":2,"exportedAtWallMs":100,"unknownExtraKey":true}"""
        )

        for (raw in cases) {
            val res = service.importJsonArchive(raw)
            assertTrue("Expected Failure for $raw", res is BackupImportResult.Failure)
            assertEquals(BackupValidationError.MALFORMED_JSON, (res as BackupImportResult.Failure).reason)
        }
        assertPreExistingBaselineIntact(db, store, baseline)
    }

    @Test
    fun `unsupported schemaVersion 99 returns UNSUPPORTED_SCHEMA_VERSION without touching DB`() = runTest {
        val db = newDb()
        val store = newDataStore(this, "version_prefs")
        val service = BackupPortabilityServiceImpl(db, store, FixedTimeProvider(BASE_WALL_MS))
        val baseline = seedPreExistingBaseline(db, store)

        val futureJson = """{"schemaVersion":99,"exportedAtWallMs":1760000000000,"futureArray":[1,2]}"""
        val zeroJson = """{"schemaVersion":0,"exportedAtWallMs":1760000000000}"""

        val futureResult = service.importJsonArchive(futureJson) as BackupImportResult.Failure
        val zeroResult = service.importJsonArchive(zeroJson) as BackupImportResult.Failure

        assertEquals(BackupValidationError.UNSUPPORTED_SCHEMA_VERSION, futureResult.reason)
        assertEquals(BackupValidationError.UNSUPPORTED_SCHEMA_VERSION, zeroResult.reason)
        assertPreExistingBaselineIntact(db, store, baseline)
    }

    @Test
    fun `phase 1 rejects bad UUID bounds and dangling FKs leaving existing DB unchanged`() = runTest {
        val db = newDb()
        val store = newDataStore(this, "fk_prefs")
        val service = BackupPortabilityServiceImpl(db, store, FixedTimeProvider(BASE_WALL_MS))
        val baseline = seedPreExistingBaseline(db, store)

        val validTemplate = buildValidArchiveFixture(ownerUserId = null)
        val badUuidArchive = validTemplate.copy(
            subjects = validTemplate.subjects.map { it.copy(id = "not-a-uuid") }
        )
        val badBoundsArchive = validTemplate.copy(
            sessions = validTemplate.sessions.map { it.copy(actualFocusSec = -5L) },
            books = validTemplate.books.map { it.copy(totalPages = -1) }
        )
        val danglingSessionProfile = validTemplate.copy(
            sessions = validTemplate.sessions.map {
                it.copy(profileId = UuidV7.generate(BASE_WALL_MS + 999L))
            }
        )
        val danglingSegmentSession = validTemplate.copy(
            segments = validTemplate.segments.map {
                it.copy(sessionId = UuidV7.generate(BASE_WALL_MS + 998L))
            }
        )
        val danglingBookSubject = validTemplate.copy(
            books = validTemplate.books.map {
                it.copy(subjectId = UuidV7.generate(BASE_WALL_MS + 997L))
            }
        )
        val danglingTopicSubject = validTemplate.copy(
            topics = validTemplate.topics.map {
                it.copy(subjectId = UuidV7.generate(BASE_WALL_MS + 996L))
            }
        )
        val danglingSessionBook = validTemplate.copy(
            sessions = validTemplate.sessions.map {
                it.copy(bookId = UuidV7.generate(BASE_WALL_MS + 995L))
            }
        )
        val danglingSessionTask = validTemplate.copy(
            sessions = validTemplate.sessions.map {
                it.copy(taskId = UuidV7.generate(BASE_WALL_MS + 994L))
            }
        )

        assertFailure(service, badUuidArchive, BackupValidationError.INVALID_IDENTIFIER)
        assertFailure(service, badBoundsArchive, BackupValidationError.INVALID_FIELD_BOUNDS)
        assertFailure(service, danglingSessionProfile, BackupValidationError.INVALID_FOREIGN_KEY)
        assertFailure(service, danglingSegmentSession, BackupValidationError.INVALID_FOREIGN_KEY)
        assertFailure(service, danglingBookSubject, BackupValidationError.INVALID_FOREIGN_KEY)
        assertFailure(service, danglingTopicSubject, BackupValidationError.INVALID_FOREIGN_KEY)
        assertFailure(service, danglingSessionBook, BackupValidationError.INVALID_FOREIGN_KEY)
        assertFailure(service, danglingSessionTask, BackupValidationError.INVALID_FOREIGN_KEY)
        assertPreExistingBaselineIntact(db, store, baseline)
    }

    @Test
    fun `phase 2 rolls back Room transaction completely when DataStore write fails`() = runTest {
        val db = newDb()
        val file = File(tempFolder.newFolder(), "rollback.preferences_pb")
        val realRawStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val failingToggleStore = FailingOnWriteDataStore(realRawStore)
        val prefsStore = UserPreferencesDataStore(failingToggleStore)

        val baseline = seedPreExistingBaseline(db, prefsStore)
        val incomingArchive = buildValidArchiveFixture(ownerUserId = null)
        val incomingJson = BackupValidator.json.encodeToString(PekmenBackupArchive.serializer(), incomingArchive)

        // Fail inside DataStore AFTER all 7 Room DAO upserts execute inside withTransaction
        failingToggleStore.failWrites = true
        val svc = BackupPortabilityServiceImpl(db, prefsStore, FixedTimeProvider(BASE_WALL_MS))
        val res = svc.importJsonArchive(incomingJson)

        assertTrue(res is BackupImportResult.Failure)
        assertEquals(BackupValidationError.IO_FAILURE, (res as BackupImportResult.Failure).reason)
        assertPreExistingBaselineIntact(db, prefsStore, baseline)
    }

    @Test
    fun `phase 2 rolls back Room transaction completely when SQLite throws mid-upsert`() = runTest {
        val db = newDb()
        val store = newDataStore(this, "sql_rollback_prefs")
        val service = BackupPortabilityServiceImpl(db, store, FixedTimeProvider(BASE_WALL_MS))
        val baseline = seedPreExistingBaseline(db, store)

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_task_insert BEFORE INSERT ON planner_tasks " +
                "BEGIN SELECT RAISE(ABORT, 'Simulated mid-transaction failure'); END"
        )

        val incomingArchive = buildValidArchiveFixture(ownerUserId = null)
        val incomingJson = BackupValidator.json.encodeToString(PekmenBackupArchive.serializer(), incomingArchive)
        val res = service.importJsonArchive(incomingJson)

        assertTrue(res is BackupImportResult.Failure)
        assertPreExistingBaselineIntact(db, store, baseline)
    }

    @Test
    fun `CSV export escapes RFC 4180 delimiters and neutralizes formula injection prefixes`() = runTest {
        val db = newDb()
        val store = newDataStore(this, "csv_prefs")
        val service = BackupPortabilityServiceImpl(db, store, FixedTimeProvider(BASE_WALL_MS))
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

    private suspend fun seedCsvInjectionFixture(db: PekmenDatabase) {
        val profile = builtInStudyProfile(BASE_WALL_MS)
        val subjectId = UuidV7.generate(BASE_WALL_MS + 1L)
        val bookId = UuidV7.generate(BASE_WALL_MS + 2L)
        val topicId = UuidV7.generate(BASE_WALL_MS + 3L)
        val sessionId = UuidV7.generate(BASE_WALL_MS + 4L)
        val taskId = UuidV7.generate(BASE_WALL_MS + 5L)
        val sync = SyncMetadataEntity(createdAtMs = BASE_WALL_MS, clientUpdatedAtMs = BASE_WALL_MS)

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
                    startedAtWallMs = BASE_WALL_MS,
                    endedAtWallMs = BASE_WALL_MS + 1500_000L,
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

    @Test
    fun `JSON and CSV exports isolate data to activeOwnerUserId`() = runTest {
        val db = newDb()
        val store = newDataStore(this, "owner_prefs")
        val service = BackupPortabilityServiceImpl(db, store, FixedTimeProvider(BASE_WALL_MS))

        val study = builtInStudyProfile(BASE_WALL_MS)
        db.portabilityDao().upsertProfiles(listOf(study))

        val aliceSync = SyncMetadataEntity(
            ownerUserId = "alice",
            createdAtMs = BASE_WALL_MS,
            clientUpdatedAtMs = BASE_WALL_MS
        )
        val bobSync = SyncMetadataEntity(
            ownerUserId = "bob",
            createdAtMs = BASE_WALL_MS,
            clientUpdatedAtMs = BASE_WALL_MS
        )
        db.portabilityDao().upsertSubjects(
            listOf(
                LearningSubjectEntity(
                    UuidV7.generate(BASE_WALL_MS + 1L),
                    "Alice Secret Subject",
                    1L,
                    60,
                    sync = aliceSync
                ),
                LearningSubjectEntity(
                    UuidV7.generate(BASE_WALL_MS + 2L),
                    "Bob Visible Subject",
                    2L,
                    90,
                    sync = bobSync
                )
            )
        )
        db.portabilityDao().upsertTasks(
            listOf(
                PlannerTaskEntity(UuidV7.generate(BASE_WALL_MS + 3L), "Alice Secret Task", sync = aliceSync),
                PlannerTaskEntity(UuidV7.generate(BASE_WALL_MS + 4L), "Bob Visible Task", sync = bobSync)
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

    private fun newDb(): PekmenDatabase {
        return PekmenDatabase.createInMemory(context).also { databases.add(it) }
    }

    private fun newDataStore(scope: TestScope, name: String): UserPreferencesDataStore {
        val file = File(tempFolder.newFolder(), "$name.preferences_pb")
        val raw = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file }
        return UserPreferencesDataStore(raw)
    }

    private suspend fun assertFailure(
        service: BackupPortabilityServiceImpl,
        archive: PekmenBackupArchive,
        expected: BackupValidationError
    ) {
        val raw = BackupValidator.json.encodeToString(PekmenBackupArchive.serializer(), archive)
        val result = service.importJsonArchive(raw)
        assertTrue("Expected failure for $expected", result is BackupImportResult.Failure)
        assertEquals(expected, (result as BackupImportResult.Failure).reason)
    }

    private suspend fun seedPreExistingBaseline(
        db: PekmenDatabase,
        store: UserPreferencesDataStore
    ): BaselineIds {
        val profile = builtInStudyProfile(BASE_WALL_MS)
        val subjectId = UuidV7.generate(BASE_WALL_MS + 100L)
        val taskId = UuidV7.generate(BASE_WALL_MS + 101L)
        val sync = SyncMetadataEntity(createdAtMs = BASE_WALL_MS, clientUpdatedAtMs = BASE_WALL_MS)

        db.portabilityDao().upsertProfiles(listOf(profile))
        db.portabilityDao().upsertSubjects(
            listOf(LearningSubjectEntity(subjectId, "Baseline Subject", 0xFF8B7CF6, 100, sync = sync))
        )
        db.portabilityDao().upsertTasks(
            listOf(PlannerTaskEntity(taskId, "Baseline Task", subjectId = subjectId, sync = sync))
        )
        store.setThemePreference(ThemePreference.LIGHT)
        store.setDayRolloverHour(3)
        return BaselineIds(profile.id, subjectId, taskId)
    }

    private suspend fun assertPreExistingBaselineIntact(
        db: PekmenDatabase,
        store: UserPreferencesDataStore,
        baseline: BaselineIds
    ) {
        assertEquals(listOf(baseline.profileId), db.portabilityDao().getProfilesForOwnerOnce(null).map { it.id })
        assertEquals(listOf(baseline.subjectId), db.portabilityDao().getSubjectsForOwnerOnce(null).map { it.id })
        assertEquals(listOf(baseline.taskId), db.portabilityDao().getTasksForOwnerOnce(null).map { it.id })
        assertEquals(0, db.portabilityDao().getSessionsForOwnerOnce(null).size)
        val prefs = store.preferences.first()
        assertEquals(ThemePreference.LIGHT, prefs.themePreference)
        assertEquals(3, prefs.dayRolloverHour)
    }

    private suspend fun seedFullV2Fixture(
        db: PekmenDatabase,
        store: UserPreferencesDataStore,
        ownerUserId: String?
    ): BaselineIds {
        val ids = BaselineIds(
            profileId = UuidV7.generate(BASE_WALL_MS + 1L),
            subjectId = UuidV7.generate(BASE_WALL_MS + 2L),
            taskId = UuidV7.generate(BASE_WALL_MS + 5L),
            bookId = UuidV7.generate(BASE_WALL_MS + 3L),
            topicId = UuidV7.generate(BASE_WALL_MS + 4L),
            sessionId = UuidV7.generate(BASE_WALL_MS + 6L)
        )
        val ownerSync = SyncMetadataEntity(
            ownerUserId = ownerUserId,
            createdAtMs = BASE_WALL_MS,
            clientUpdatedAtMs = BASE_WALL_MS + 50L,
            serverUpdatedAtMs = BASE_WALL_MS + 60L,
            deletedAtMs = null,
            version = 3L,
            syncStatus = SyncStatus.SYNCED.name
        )
        seedLearningAndTaskEntities(db, ids, ownerSync)
        seedSessionAndSegments(db, ids, ownerSync)
        seedV2Preferences(store, ownerUserId, ids.profileId)
        return ids
    }

    private suspend fun seedLearningAndTaskEntities(
        db: PekmenDatabase,
        ids: BaselineIds,
        ownerSync: SyncMetadataEntity
    ) {
        val study = builtInStudyProfile(BASE_WALL_MS)
        val customProfile = FocusProfileEntity(
            id = ids.profileId,
            builtInSlug = BuiltInProfileSlug.CUSTOM.name,
            customName = "Deep Lab",
            colorHex = 0xFF60A5FA,
            iconKey = "custom",
            defaultMode = FocusSessionMode.CUSTOM.name,
            workMinutes = 45,
            shortBreakMinutes = 10,
            longBreakMinutes = 20,
            cyclesBeforeLongBreak = 2,
            dndEnabled = true,
            sortOrder = 4,
            isArchived = false,
            sync = ownerSync
        )
        db.portabilityDao().upsertProfiles(listOf(study, customProfile))
        db.portabilityDao().upsertSubjects(
            listOf(LearningSubjectEntity(ids.subjectId, "Almanca B2", 0xFF8B7CF6, 150, sync = ownerSync))
        )
        db.portabilityDao().upsertBooks(
            listOf(
                LearningBookEntity(
                    id = ids.bookId,
                    subjectId = ids.subjectId,
                    title = "Erkundungen B2",
                    author = "Schubert",
                    totalPages = 240,
                    currentPage = 52,
                    totalChapters = 10,
                    completedChapters = 3,
                    isCompleted = false,
                    sync = ownerSync
                )
            )
        )
        db.portabilityDao().upsertTopics(
            listOf(
                LearningTopicEntity(
                    id = ids.topicId,
                    subjectId = ids.subjectId,
                    bookId = ids.bookId,
                    title = "Schreiben",
                    status = TopicStatus.MASTERED.name,
                    sortOrder = 1,
                    sync = ownerSync
                )
            )
        )
        db.portabilityDao().upsertTasks(
            listOf(
                PlannerTaskEntity(
                    id = ids.taskId,
                    title = "Write essay",
                    notes = "Focus on connectors",
                    subjectId = ids.subjectId,
                    dueAtWallMs = BASE_WALL_MS + 86_400_000L,
                    reminderAtWallMs = BASE_WALL_MS + 43_200_000L,
                    recurrenceRule = TaskRecurrenceRule.DAILY.name,
                    status = TaskStatus.OPEN.name,
                    priority = 2,
                    sync = ownerSync
                )
            )
        )
    }

    private suspend fun seedSessionAndSegments(
        db: PekmenDatabase,
        ids: BaselineIds,
        ownerSync: SyncMetadataEntity
    ) {
        val seg1Id = UuidV7.generate(BASE_WALL_MS + 7L)
        val seg2Id = UuidV7.generate(BASE_WALL_MS + 8L)
        db.portabilityDao().upsertSessions(
            listOf(
                FocusSessionEntity(
                    id = ids.sessionId,
                    profileId = ids.profileId,
                    subjectId = ids.subjectId,
                    bookId = ids.bookId,
                    topicId = ids.topicId,
                    taskId = ids.taskId,
                    bookStartPage = 40,
                    bookEndPage = 52,
                    sessionMode = FocusSessionMode.POMODORO.name,
                    status = FocusSessionStatus.COMPLETED.name,
                    currentPhase = FocusPhase.FOCUS.name,
                    completedFocusCycles = 1,
                    startedAtWallMs = BASE_WALL_MS,
                    endedAtWallMs = BASE_WALL_MS + 1_800_000L,
                    effectiveDayDate = "2026-11-19",
                    plannedFocusSec = 1500L,
                    actualFocusSec = 1500L,
                    actualBreakSec = 300L,
                    pauseCount = 1,
                    focusRating = 5,
                    reflectionNote = "Solid chapter",
                    sync = ownerSync
                )
            )
        )
        db.portabilityDao().upsertSegments(
            listOf(
                FocusSessionSegmentEntity(
                    id = seg1Id,
                    sessionId = ids.sessionId,
                    segmentIndex = 0,
                    phase = FocusPhase.FOCUS.name,
                    startedAtWallMs = BASE_WALL_MS,
                    startedAtElapsedMs = 10_000L,
                    bootCount = 1,
                    lastCheckpointedDurationMs = 1_500_000L,
                    plannedDurationMs = 1_500_000L,
                    endedAtWallMs = BASE_WALL_MS + 1_500_000L,
                    durationMs = 1_500_000L
                ),
                FocusSessionSegmentEntity(
                    id = seg2Id,
                    sessionId = ids.sessionId,
                    segmentIndex = 1,
                    phase = FocusPhase.SHORT_BREAK.name,
                    startedAtWallMs = BASE_WALL_MS + 1_500_000L,
                    startedAtElapsedMs = 1_510_000L,
                    bootCount = 1,
                    lastCheckpointedDurationMs = 300_000L,
                    plannedDurationMs = 300_000L,
                    endedAtWallMs = BASE_WALL_MS + 1_800_000L,
                    durationMs = 300_000L
                )
            )
        )
    }

    private suspend fun seedV2Preferences(
        store: UserPreferencesDataStore,
        ownerUserId: String?,
        selectedProfileId: String
    ) {
        store.setActiveOwnerUserId(ownerUserId)
        store.setThemePreference(ThemePreference.DARK)
        store.setDayRolloverHour(5)
        store.setSelectedProfileId(selectedProfileId)
        store.setAutoStartBreaks(true)
        store.setAutoStartNextFocus(true)
        store.setDefaultCustomMinutes(55)
        store.setZenIntegrationEnabled(true)
        store.setRoutineKeywordEnabled(true)
    }

    private suspend fun assertFullFixturePreserved(
        db: PekmenDatabase,
        store: UserPreferencesDataStore,
        fixture: BaselineIds,
        ownerUserId: String?
    ) {
        val books = db.portabilityDao().getBooksForOwnerOnce(ownerUserId)
        assertEquals(1, books.size)
        assertEquals(52, books.first().currentPage)
        assertEquals(240, books.first().totalPages)

        val session = db.portabilityDao().getSessionsForOwnerOnce(ownerUserId).single()
        assertEquals(fixture.sessionId, session.id)
        assertEquals(fixture.profileId, session.profileId)
        assertEquals(40, session.bookStartPage)
        assertEquals(52, session.bookEndPage)
        assertEquals(3L, session.sync.version)
        assertEquals(SyncStatus.SYNCED.name, session.sync.syncStatus)
        assertEquals(BASE_WALL_MS + 60L, session.sync.serverUpdatedAtMs)
        assertEquals(ownerUserId, session.sync.ownerUserId)

        val prefs = store.preferences.first()
        assertEquals(ThemePreference.DARK, prefs.themePreference)
        assertEquals(5, prefs.dayRolloverHour)
        assertEquals(fixture.profileId, prefs.selectedProfileId)
        assertTrue(prefs.autoStartBreaks)
        assertTrue(prefs.autoStartNextFocus)
        assertEquals(55, prefs.defaultCustomMinutes)
        assertTrue(prefs.zenIntegrationEnabled)
        assertTrue(prefs.routineKeywordEnabled)
        assertNull(prefs.activeOwnerUserId)
    }

    private fun buildValidArchiveFixture(ownerUserId: String?): PekmenBackupArchive {
        val profileId = UuidV7.generate(BASE_WALL_MS + 200L)
        val subjectId = UuidV7.generate(BASE_WALL_MS + 201L)
        val bookId = UuidV7.generate(BASE_WALL_MS + 202L)
        val topicId = UuidV7.generate(BASE_WALL_MS + 203L)
        val taskId = UuidV7.generate(BASE_WALL_MS + 204L)
        val sessionId = UuidV7.generate(BASE_WALL_MS + 205L)
        val segId = UuidV7.generate(BASE_WALL_MS + 206L)
        val sync = BackupSyncMetadata(
            ownerUserId = ownerUserId,
            createdAtMs = BASE_WALL_MS,
            clientUpdatedAtMs = BASE_WALL_MS
        )

        return PekmenBackupArchive(
            schemaVersion = 2,
            exportedAtWallMs = BASE_WALL_MS,
            preferences = BackupPreferences(selectedProfileId = profileId),
            profiles = listOf(
                BackupProfile(
                    id = profileId,
                    builtInSlug = BuiltInProfileSlug.CUSTOM.name,
                    customName = "Archive Profile",
                    colorHex = 0xFF8B7CF6,
                    iconKey = "custom",
                    defaultMode = FocusSessionMode.POMODORO.name,
                    workMinutes = 25,
                    shortBreakMinutes = 5,
                    longBreakMinutes = 15,
                    cyclesBeforeLongBreak = 4,
                    dndEnabled = false,
                    sortOrder = 0,
                    sync = sync
                )
            ),
            subjects = listOf(BackupSubject(subjectId, "Subject", 0xFF60A5FA, 120, sync = sync)),
            books = listOf(BackupBook(bookId, subjectId, "Book", totalPages = 100, currentPage = 10, sync = sync)),
            topics = listOf(BackupTopic(topicId, subjectId, bookId, "Topic", sync = sync)),
            tasks = listOf(BackupTask(taskId, "Task", subjectId = subjectId, sync = sync)),
            sessions = listOf(
                BackupSession(
                    id = sessionId,
                    profileId = profileId,
                    subjectId = subjectId,
                    bookId = bookId,
                    topicId = topicId,
                    taskId = taskId,
                    sessionMode = FocusSessionMode.POMODORO.name,
                    status = FocusSessionStatus.COMPLETED.name,
                    currentPhase = FocusPhase.FOCUS.name,
                    completedFocusCycles = 1,
                    startedAtWallMs = BASE_WALL_MS,
                    endedAtWallMs = BASE_WALL_MS + 600_000L,
                    effectiveDayDate = "2026-11-19",
                    actualFocusSec = 600L,
                    actualBreakSec = 0L,
                    pauseCount = 0,
                    sync = sync
                )
            ),
            segments = listOf(
                BackupSegment(
                    id = segId,
                    sessionId = sessionId,
                    segmentIndex = 0,
                    phase = FocusPhase.FOCUS.name,
                    startedAtWallMs = BASE_WALL_MS,
                    startedAtElapsedMs = 1_000L,
                    bootCount = 1,
                    lastCheckpointedDurationMs = 600_000L,
                    durationMs = 600_000L
                )
            )
        )
    }

    private fun buildLegacyV1Json(profileId: String, sessionId: String, segmentId: String): String {
        return """
            {
              "schemaVersion": 1,
              "exportedAtWallMs": 1760000000000,
              "appVersion": "0.1.0-m1",
              "preferences": {
                "themePreference": "DARK",
                "dayRolloverHour": 6,
                "selectedProfileId": "$profileId",
                "autoStartBreaks": true,
                "autoStartNextFocus": false,
                "defaultCustomMinutes": 40
              },
              "profiles": [
                {
                  "id": "$profileId",
                  "builtInSlug": "STUDY",
                  "customName": null,
                  "colorHex": 4287331574,
                  "iconKey": "study",
                  "defaultMode": "POMODORO",
                  "workMinutes": 25,
                  "shortBreakMinutes": 5,
                  "longBreakMinutes": 15,
                  "cyclesBeforeLongBreak": 4,
                  "dndEnabled": true,
                  "sortOrder": 0,
                  "isArchived": false,
                  "sync": {
                    "createdAtMs": 1760000000000,
                    "clientUpdatedAtMs": 1760000000000,
                    "version": 1,
                    "syncStatus": "LOCAL_ONLY"
                  }
                }
              ],
              "sessions": [
                {
                  "id": "$sessionId",
                  "profileId": "$profileId",
                  "sessionMode": "POMODORO",
                  "status": "COMPLETED",
                  "currentPhase": "FOCUS",
                  "completedFocusCycles": 1,
                  "startedAtWallMs": 1760000000000,
                  "endedAtWallMs": 1760001500000,
                  "effectiveDayDate": "2026-11-19",
                  "plannedFocusSec": 1500,
                  "actualFocusSec": 1500,
                  "actualBreakSec": 0,
                  "pauseCount": 0,
                  "sync": {
                    "createdAtMs": 1760000000000,
                    "clientUpdatedAtMs": 1760001500000,
                    "version": 1,
                    "syncStatus": "LOCAL_ONLY"
                  }
                }
              ],
              "segments": [
                {
                  "id": "$segmentId",
                  "sessionId": "$sessionId",
                  "segmentIndex": 0,
                  "phase": "FOCUS",
                  "startedAtWallMs": 1760000000000,
                  "startedAtElapsedMs": 5000,
                  "bootCount": 1,
                  "lastCheckpointedDurationMs": 1500000,
                  "plannedDurationMs": 1500000,
                  "endedAtWallMs": 1760001500000,
                  "durationMs": 1500000
                }
              ]
            }
        """.trimIndent()
    }

    private fun builtInStudyProfile(nowMs: Long): FocusProfileEntity {
        val study = BuiltInProfiles.defaults().first()
        return FocusProfileEntity(
            id = study.id,
            builtInSlug = study.builtInSlug.name,
            customName = study.customName,
            colorHex = study.colorHex,
            iconKey = study.iconKey,
            defaultMode = study.defaultMode.name,
            workMinutes = study.workMinutes,
            shortBreakMinutes = study.shortBreakMinutes,
            longBreakMinutes = study.longBreakMinutes,
            cyclesBeforeLongBreak = study.cyclesBeforeLongBreak,
            dndEnabled = study.dndEnabled,
            sortOrder = study.sortOrder,
            isArchived = false,
            sync = SyncMetadataEntity(createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
        )
    }

    private data class BaselineIds(
        val profileId: String,
        val subjectId: String,
        val taskId: String,
        val bookId: String = "",
        val topicId: String = "",
        val sessionId: String = ""
    )

    private class FixedTimeProvider(private val wallMs: Long) : TimeProvider {
        override fun wallTimeMs(): Long = wallMs
        override fun elapsedRealtimeMs(): Long = 10_000L
        override fun bootCount(): Int = 1
        override fun zoneId(): ZoneId = ZoneId.of("Europe/Istanbul")
    }

    private class FailingOnWriteDataStore(
        private val delegate: DataStore<Preferences>
    ) : DataStore<Preferences> {
        var failWrites: Boolean = false
        override val data: Flow<Preferences> = delegate.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("Simulated DataStore write failure")
            return delegate.updateData(transform)
        }
    }

    private companion object {
        const val BASE_WALL_MS = 1_760_000_000_000L
    }
}
