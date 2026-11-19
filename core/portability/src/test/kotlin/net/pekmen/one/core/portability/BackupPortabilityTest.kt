package net.pekmen.one.core.portability

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BackupImportResult
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.ThemePreference
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
        val store = BackupTestFixtures.newDataStore(tempFolder, this, "v1_prefs")
        val service = BackupPortabilityServiceImpl(db, store, BackupTestFixtures.FixedTimeProvider())

        val profileId = BuiltInProfiles.STUDY_ID
        val sessionId = UuidV7.generate(BackupTestFixtures.BASE_WALL_MS + 10L)
        val segmentId = UuidV7.generate(BackupTestFixtures.BASE_WALL_MS + 20L)
        val v1Json = BackupTestFixtures.buildLegacyV1Json(profileId, sessionId, segmentId)

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
    fun `full current round-trip preserves M1 M2 M3 entities SyncMetadata UUIDv7 and preferences`() = runTest {
        val sourceDb = newDb()
        val sourceStore = BackupTestFixtures.newDataStore(tempFolder, this, "src_prefs")
        val sourceService = BackupPortabilityServiceImpl(
            sourceDb,
            sourceStore,
            BackupTestFixtures.FixedTimeProvider()
        )
        val fixture = BackupTestFixtures.seedFullV2Fixture(sourceDb, sourceStore, ownerUserId = "owner-uuid-1")

        val exportedJson = sourceService.exportJsonArchive().getOrThrow()
        assertTrue(exportedJson.contains("\"schemaVersion\": 3"))
        assertFalse(exportedJson.contains("activeOwnerUserId"))

        val targetDb = newDb()
        val targetStore = BackupTestFixtures.newDataStore(tempFolder, this, "dst_prefs")
        val targetService = BackupPortabilityServiceImpl(
            targetDb,
            targetStore,
            BackupTestFixtures.FixedTimeProvider(BackupTestFixtures.BASE_WALL_MS + 5000L)
        )

        val result = targetService.importJsonArchive(exportedJson)
        assertTrue(result is BackupImportResult.Success)
        val report = (result as BackupImportResult.Success).report
        assertEquals(3, report.sourceSchemaVersion)
        assertEquals(2, report.profilesRestored)
        assertEquals(1, report.subjectsRestored)
        assertEquals(1, report.booksRestored)
        assertEquals(1, report.topicsRestored)
        assertEquals(1, report.sessionsRestored)
        assertEquals(2, report.segmentsRestored)
        assertEquals(1, report.tasksRestored)

        BackupTestFixtures.assertFullFixturePreserved(targetDb, targetStore, fixture, ownerUserId = "owner-uuid-1")
    }

    @Test
    fun `double importing same v2 archive is 100 percent idempotent without duplicates`() = runTest {
        val sourceDb = newDb()
        val sourceStore = BackupTestFixtures.newDataStore(tempFolder, this, "idem_src")
        val sourceService = BackupPortabilityServiceImpl(
            sourceDb,
            sourceStore,
            BackupTestFixtures.FixedTimeProvider()
        )
        BackupTestFixtures.seedFullV2Fixture(sourceDb, sourceStore, ownerUserId = null)
        val archiveJson = sourceService.exportJsonArchive().getOrThrow()

        val targetDb = newDb()
        val targetStore = BackupTestFixtures.newDataStore(tempFolder, this, "idem_dst")
        val targetService = BackupPortabilityServiceImpl(
            targetDb,
            targetStore,
            BackupTestFixtures.FixedTimeProvider()
        )

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
        val store = BackupTestFixtures.newDataStore(tempFolder, this, "malformed_prefs")
        val service = BackupPortabilityServiceImpl(db, store, BackupTestFixtures.FixedTimeProvider())
        val baseline = BackupTestFixtures.seedPreExistingBaseline(db, store)

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
        BackupTestFixtures.assertPreExistingBaselineIntact(db, store, baseline)
    }

    @Test
    fun `unsupported schemaVersion 99 returns UNSUPPORTED_SCHEMA_VERSION without touching DB`() = runTest {
        val db = newDb()
        val store = BackupTestFixtures.newDataStore(tempFolder, this, "version_prefs")
        val service = BackupPortabilityServiceImpl(db, store, BackupTestFixtures.FixedTimeProvider())
        val baseline = BackupTestFixtures.seedPreExistingBaseline(db, store)

        val futureJson = """{"schemaVersion":99,"exportedAtWallMs":1760000000000,"futureArray":[1,2]}"""
        val zeroJson = """{"schemaVersion":0,"exportedAtWallMs":1760000000000}"""

        val futureResult = service.importJsonArchive(futureJson) as BackupImportResult.Failure
        val zeroResult = service.importJsonArchive(zeroJson) as BackupImportResult.Failure

        assertEquals(BackupValidationError.UNSUPPORTED_SCHEMA_VERSION, futureResult.reason)
        assertEquals(BackupValidationError.UNSUPPORTED_SCHEMA_VERSION, zeroResult.reason)
        BackupTestFixtures.assertPreExistingBaselineIntact(db, store, baseline)
    }

    @Test
    fun `phase 1 rejects bad UUID bounds and dangling FKs leaving existing DB unchanged`() = runTest {
        val db = newDb()
        val store = BackupTestFixtures.newDataStore(tempFolder, this, "fk_prefs")
        val service = BackupPortabilityServiceImpl(db, store, BackupTestFixtures.FixedTimeProvider())
        val baseline = BackupTestFixtures.seedPreExistingBaseline(db, store)

        val validTemplate = BackupTestFixtures.buildValidArchiveFixture(ownerUserId = null)
        val baseMs = BackupTestFixtures.BASE_WALL_MS
        val badUuid = validTemplate.copy(subjects = validTemplate.subjects.map { it.copy(id = "not-a-uuid") })
        val badBounds = validTemplate.copy(
            sessions = validTemplate.sessions.map { it.copy(actualFocusSec = -5L) },
            books = validTemplate.books.map { it.copy(totalPages = -1) }
        )
        val danglingProfile = validTemplate.copy(
            sessions = validTemplate.sessions.map { it.copy(profileId = UuidV7.generate(baseMs + 999L)) }
        )
        val danglingSegment = validTemplate.copy(
            segments = validTemplate.segments.map { it.copy(sessionId = UuidV7.generate(baseMs + 998L)) }
        )
        val danglingBook = validTemplate.copy(
            books = validTemplate.books.map { it.copy(subjectId = UuidV7.generate(baseMs + 997L)) }
        )
        val danglingTopic = validTemplate.copy(
            topics = validTemplate.topics.map { it.copy(subjectId = UuidV7.generate(baseMs + 996L)) }
        )
        val danglingSessionBook = validTemplate.copy(
            sessions = validTemplate.sessions.map { it.copy(bookId = UuidV7.generate(baseMs + 995L)) }
        )
        val danglingSessionTask = validTemplate.copy(
            sessions = validTemplate.sessions.map { it.copy(taskId = UuidV7.generate(baseMs + 994L)) }
        )

        BackupTestFixtures.assertFailure(service, badUuid, BackupValidationError.INVALID_IDENTIFIER)
        BackupTestFixtures.assertFailure(service, badBounds, BackupValidationError.INVALID_FIELD_BOUNDS)
        BackupTestFixtures.assertFailure(service, danglingProfile, BackupValidationError.INVALID_FOREIGN_KEY)
        BackupTestFixtures.assertFailure(service, danglingSegment, BackupValidationError.INVALID_FOREIGN_KEY)
        BackupTestFixtures.assertFailure(service, danglingBook, BackupValidationError.INVALID_FOREIGN_KEY)
        BackupTestFixtures.assertFailure(service, danglingTopic, BackupValidationError.INVALID_FOREIGN_KEY)
        BackupTestFixtures.assertFailure(service, danglingSessionBook, BackupValidationError.INVALID_FOREIGN_KEY)
        BackupTestFixtures.assertFailure(service, danglingSessionTask, BackupValidationError.INVALID_FOREIGN_KEY)
        BackupTestFixtures.assertPreExistingBaselineIntact(db, store, baseline)
    }

    @Test
    fun `phase 2 rolls back Room transaction completely when DataStore or SQLite fails`() = runTest {
        val db = newDb()
        val file = File(tempFolder.newFolder(), "rollback.preferences_pb")
        val realRawStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val failingToggleStore = BackupTestFixtures.FailingOnWriteDataStore(realRawStore)
        val prefsStore = UserPreferencesDataStore(failingToggleStore)

        val baseline = BackupTestFixtures.seedPreExistingBaseline(db, prefsStore)
        val incomingArchive = BackupTestFixtures.buildValidArchiveFixture(ownerUserId = null)
        val incomingJson = BackupValidator.json.encodeToString(PekmenBackupArchive.serializer(), incomingArchive)

        // Fail inside DataStore AFTER all 7 Room DAO upserts execute inside withTransaction
        failingToggleStore.failWrites = true
        val svc = BackupPortabilityServiceImpl(db, prefsStore, BackupTestFixtures.FixedTimeProvider())
        val dsFailResult = svc.importJsonArchive(incomingJson)

        assertTrue(dsFailResult is BackupImportResult.Failure)
        assertEquals(BackupValidationError.IO_FAILURE, (dsFailResult as BackupImportResult.Failure).reason)
        BackupTestFixtures.assertPreExistingBaselineIntact(db, prefsStore, baseline)

        // Now allow DataStore writes, but fail SQLite mid-transaction on the final table upsert
        failingToggleStore.failWrites = false
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_task_insert BEFORE INSERT ON planner_tasks " +
                "BEGIN SELECT RAISE(ABORT, 'Simulated mid-transaction failure'); END"
        )
        val sqlFailResult = svc.importJsonArchive(incomingJson)
        assertTrue(sqlFailResult is BackupImportResult.Failure)
        BackupTestFixtures.assertPreExistingBaselineIntact(db, prefsStore, baseline)
    }

    private fun newDb(): PekmenDatabase {
        return PekmenDatabase.createInMemory(context).also { databases.add(it) }
    }
}
