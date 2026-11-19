package net.pekmen.one.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.model.UuidV7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PekmenDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "pekmen_migration_v1_to_v2.db"
    private var migratedDb: PekmenDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDatabaseFiles(context.getDatabasePath(dbName))
    }

    @After
    fun tearDown() {
        migratedDb?.close()
        migratedDb = null
        deleteDatabaseFiles(context.getDatabasePath(dbName))
    }

    @Test
    fun `migrates v1 sqlite to v2 with zero data loss`() = runTest {
        val customProfileId = UuidV7.generate(1_760_000_000_000L)
        val completedSessionId = UuidV7.generate(1_760_000_100_000L)
        val completedFocusSegId = UuidV7.generate(1_760_000_100_010L)
        val completedBreakSegId = UuidV7.generate(1_760_000_200_010L)
        val runningSessionId = UuidV7.generate(1_760_000_300_000L)
        val runningClosedSegId = UuidV7.generate(1_760_000_300_010L)
        val runningOpenSegId = UuidV7.generate(1_760_000_350_010L)
        val outboxMutationId = UuidV7.generate(1_760_000_400_000L)

        createGenuineV1Database(
            V1SeedIds(
                customProfileId = customProfileId,
                completedSessionId = completedSessionId,
                completedFocusSegId = completedFocusSegId,
                completedBreakSegId = completedBreakSegId,
                runningSessionId = runningSessionId,
                runningClosedSegId = runningClosedSegId,
                runningOpenSegId = runningOpenSegId,
                outboxMutationId = outboxMutationId
            )
        )

        val db = PekmenDatabase.createFileBackedForTest(context, dbName)
        migratedDb = db

        val sqlite = db.openHelper.writableDatabase
        assertEquals(2, sqlite.version)
        sqlite.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }

        val profiles = db.focusProfileDao().observeActiveProfiles().first()
        assertEquals(2, profiles.size)
        val customProfile = profiles.first { it.id == customProfileId }
        assertEquals("Thesis Sprint", customProfile.customName)
        assertEquals(45, customProfile.workMinutes)

        val completedDetails = db.focusSessionDao().getSessionDetailsById(completedSessionId)
        assertNotNull(completedDetails)
        assertEquals(completedSessionId, completedDetails?.session?.id)
        assertEquals(BuiltInProfiles.STUDY_ID, completedDetails?.session?.profileId)
        assertEquals(FocusSessionStatus.COMPLETED.name, completedDetails?.session?.status)
        assertEquals(1500L, completedDetails?.session?.actualFocusSec)
        assertEquals(300L, completedDetails?.session?.actualBreakSec)
        assertEquals(5, completedDetails?.session?.focusRating)
        assertEquals("Legacy M1 session", completedDetails?.session?.reflectionNote)
        assertNull(completedDetails?.session?.subjectId)
        assertNull(completedDetails?.session?.bookStartPage)
        assertNull(completedDetails?.session?.bookEndPage)

        val completedSegs = completedDetails?.segments?.sortedBy { it.segmentIndex }.orEmpty()
        assertEquals(2, completedSegs.size)
        assertEquals(completedFocusSegId, completedSegs[0].id)
        assertEquals(1_500_000L, completedSegs[0].durationMs)
        assertEquals(completedBreakSegId, completedSegs[1].id)
        assertEquals(300_000L, completedSegs[1].durationMs)

        val activeRunning = db.focusSessionDao().getActiveSessionOnce()
        assertNotNull(activeRunning)
        assertEquals(runningSessionId, activeRunning?.session?.id)
        assertEquals(customProfileId, activeRunning?.session?.profileId)
        assertEquals(FocusSessionStatus.RUNNING.name, activeRunning?.session?.status)
        assertEquals(600L, activeRunning?.session?.actualFocusSec)
        val runningSegs = activeRunning?.segments?.sortedBy { it.segmentIndex }.orEmpty()
        assertEquals(2, runningSegs.size)
        assertEquals(runningClosedSegId, runningSegs[0].id)
        assertEquals(600_000L, runningSegs[0].durationMs)
        assertEquals(runningOpenSegId, runningSegs[1].id)
        assertNull(runningSegs[1].endedAtWallMs)
        assertEquals(120_000L, runningSegs[1].lastCheckpointedDurationMs)

        val outboxRows = db.syncOutboxDao().observeOutbox().first()
        assertEquals(1, outboxRows.size)
        assertEquals(outboxMutationId, outboxRows.first().mutationId)
        assertNull(outboxRows.first().ownerUserId)
        assertNull(outboxRows.first().lastError)

        verifyV2TablesWritable(db)
    }

    private fun createGenuineV1Database(ids: V1SeedIds) {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            rawDb.execSQL("PRAGMA foreign_keys = ON")
            executeV1Ddl(rawDb)
            insertV1Profiles(rawDb, ids.customProfileId)
            insertV1CompletedSession(
                rawDb = rawDb,
                sessionId = ids.completedSessionId,
                focusSegId = ids.completedFocusSegId,
                breakSegId = ids.completedBreakSegId
            )
            insertV1RunningSession(
                rawDb = rawDb,
                customProfileId = ids.customProfileId,
                sessionId = ids.runningSessionId,
                closedSegId = ids.runningClosedSegId,
                openSegId = ids.runningOpenSegId
            )
            rawDb.execSQL(
                """
                INSERT INTO `sync_outbox` (
                    `mutation_id`, `entity_table`, `entity_id`, `op_type`,
                    `payload_json`, `base_version`, `attempt_count`, `created_at_ms`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    ids.outboxMutationId,
                    "focus_sessions",
                    ids.completedSessionId,
                    "UPSERT",
                    """{"id":"${ids.completedSessionId}"}""",
                    1L,
                    0,
                    1_760_000_400_000L
                )
            )
            rawDb.execSQL("PRAGMA user_version = 1")
        } finally {
            rawDb.close()
        }
    }

    private fun executeV1Ddl(rawDb: SQLiteDatabase) {
        rawDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `focus_profiles` (" +
                "`id` TEXT NOT NULL, `built_in_slug` TEXT NOT NULL, `custom_name` TEXT, " +
                "`color_hex` INTEGER NOT NULL, `icon_key` TEXT NOT NULL, `default_mode` TEXT NOT NULL, " +
                "`work_minutes` INTEGER NOT NULL, `short_break_minutes` INTEGER NOT NULL, " +
                "`long_break_minutes` INTEGER NOT NULL, `cycles_before_long_break` INTEGER NOT NULL, " +
                "`dnd_enabled` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, " +
                "`is_archived` INTEGER NOT NULL, `owner_user_id` TEXT, " +
                "`created_at_ms` INTEGER NOT NULL, `client_updated_at_ms` INTEGER NOT NULL, " +
                "`server_updated_at_ms` INTEGER, `deleted_at_ms` INTEGER, " +
                "`version` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        rawDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_profiles_sort_order` ON `focus_profiles` (`sort_order`)"
        )
        rawDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_profiles_deleted_at_ms` ON `focus_profiles` (`deleted_at_ms`)"
        )

        rawDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `focus_sessions` (" +
                "`id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `subject_id` TEXT, `book_id` TEXT, " +
                "`topic_id` TEXT, `task_id` TEXT, `session_mode` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`current_phase` TEXT NOT NULL, `awaiting_next_phase` TEXT, " +
                "`completed_focus_cycles` INTEGER NOT NULL, `started_at_wall_ms` INTEGER NOT NULL, " +
                "`ended_at_wall_ms` INTEGER, `effective_day_date` TEXT NOT NULL, " +
                "`planned_focus_sec` INTEGER, `actual_focus_sec` INTEGER NOT NULL, " +
                "`actual_break_sec` INTEGER NOT NULL, `pause_count` INTEGER NOT NULL, " +
                "`is_manual_entry` INTEGER NOT NULL, `was_manually_adjusted` INTEGER NOT NULL, " +
                "`interrupted_by_reboot` INTEGER NOT NULL, `focus_rating` INTEGER, " +
                "`reflection_note` TEXT, `owner_user_id` TEXT, `created_at_ms` INTEGER NOT NULL, " +
                "`client_updated_at_ms` INTEGER NOT NULL, `server_updated_at_ms` INTEGER, " +
                "`deleted_at_ms` INTEGER, `version` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`profile_id`) REFERENCES `focus_profiles`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
        )
        rawDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_sessions_profile_id` ON `focus_sessions` (`profile_id`)"
        )
        rawDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_sessions_effective_day_date` " +
                "ON `focus_sessions` (`effective_day_date`)"
        )
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_status` ON `focus_sessions` (`status`)")
        rawDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_sessions_deleted_at_ms` ON `focus_sessions` (`deleted_at_ms`)"
        )

        rawDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `focus_session_segments` (" +
                "`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `segment_index` INTEGER NOT NULL, " +
                "`phase` TEXT NOT NULL, `started_at_wall_ms` INTEGER NOT NULL, " +
                "`started_at_elapsed_ms` INTEGER NOT NULL, `boot_count` INTEGER NOT NULL, " +
                "`last_checkpointed_duration_ms` INTEGER NOT NULL, `planned_duration_ms` INTEGER, " +
                "`ended_at_wall_ms` INTEGER, `duration_ms` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`session_id`) REFERENCES `focus_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        rawDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_session_segments_session_id` " +
                "ON `focus_session_segments` (`session_id`)"
        )
        rawDb.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_focus_session_segments_session_id_segment_index` " +
                "ON `focus_session_segments` (`session_id`, `segment_index`)"
        )

        rawDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                "`mutation_id` TEXT NOT NULL, `entity_table` TEXT NOT NULL, `entity_id` TEXT NOT NULL, " +
                "`op_type` TEXT NOT NULL, `payload_json` TEXT NOT NULL, `base_version` INTEGER NOT NULL, " +
                "`attempt_count` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`mutation_id`))"
        )
        rawDb.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_entity_table_entity_id` " +
                "ON `sync_outbox` (`entity_table`, `entity_id`)"
        )

        rawDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        rawDb.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                "VALUES(42, '607059930e38d2d83112c10296a5c0b0')"
        )
    }

    private fun insertV1Profiles(rawDb: SQLiteDatabase, customProfileId: String) {
        val sql = """
            INSERT INTO `focus_profiles` (
                `id`, `built_in_slug`, `custom_name`, `color_hex`, `icon_key`, `default_mode`,
                `work_minutes`, `short_break_minutes`, `long_break_minutes`, `cycles_before_long_break`,
                `dnd_enabled`, `sort_order`, `is_archived`, `owner_user_id`, `created_at_ms`,
                `client_updated_at_ms`, `server_updated_at_ms`, `deleted_at_ms`, `version`, `sync_status`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        rawDb.execSQL(
            sql,
            arrayOf(
                BuiltInProfiles.STUDY_ID,
                "STUDY",
                null,
                0xFF8B7CF6,
                "study",
                FocusSessionMode.POMODORO.name,
                25,
                5,
                15,
                4,
                1,
                0,
                0,
                null,
                1_760_000_000_000L,
                1_760_000_000_000L,
                null,
                null,
                1L,
                SyncStatus.LOCAL_ONLY.name
            )
        )
        rawDb.execSQL(
            sql,
            arrayOf(
                customProfileId,
                "CUSTOM",
                "Thesis Sprint",
                0xFF60A5FA,
                "custom",
                FocusSessionMode.CUSTOM.name,
                45,
                10,
                20,
                3,
                0,
                1,
                0,
                null,
                1_760_000_000_010L,
                1_760_000_000_010L,
                null,
                null,
                1L,
                SyncStatus.LOCAL_ONLY.name
            )
        )
    }

    private fun insertV1CompletedSession(
        rawDb: SQLiteDatabase,
        sessionId: String,
        focusSegId: String,
        breakSegId: String
    ) {
        rawDb.execSQL(
            """
            INSERT INTO `focus_sessions` (
                `id`, `profile_id`, `subject_id`, `book_id`, `topic_id`, `task_id`,
                `session_mode`, `status`, `current_phase`, `awaiting_next_phase`, `completed_focus_cycles`,
                `started_at_wall_ms`, `ended_at_wall_ms`, `effective_day_date`, `planned_focus_sec`,
                `actual_focus_sec`, `actual_break_sec`, `pause_count`, `is_manual_entry`,
                `was_manually_adjusted`, `interrupted_by_reboot`, `focus_rating`, `reflection_note`,
                `owner_user_id`, `created_at_ms`, `client_updated_at_ms`, `server_updated_at_ms`,
                `deleted_at_ms`, `version`, `sync_status`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                sessionId,
                BuiltInProfiles.STUDY_ID,
                null,
                null,
                null,
                null,
                FocusSessionMode.POMODORO.name,
                FocusSessionStatus.COMPLETED.name,
                FocusPhase.SHORT_BREAK.name,
                null,
                1,
                1_760_000_100_000L,
                1_760_001_900_000L,
                "2026-11-18",
                1500L,
                1500L,
                300L,
                0,
                0,
                0,
                0,
                5,
                "Legacy M1 session",
                null,
                1_760_000_100_000L,
                1_760_001_900_000L,
                null,
                null,
                2L,
                SyncStatus.LOCAL_ONLY.name
            )
        )

        insertV1Segment(
            rawDb = rawDb,
            row = V1SegmentRow(
                segmentId = focusSegId,
                sessionId = sessionId,
                index = 0,
                phase = FocusPhase.FOCUS.name,
                startedWall = 1_760_000_100_000L,
                startedElapsed = 10_000L,
                checkpointed = 1_500_000L,
                planned = 1_500_000L,
                endedWall = 1_760_001_600_000L,
                durationMs = 1_500_000L
            )
        )
        insertV1Segment(
            rawDb = rawDb,
            row = V1SegmentRow(
                segmentId = breakSegId,
                sessionId = sessionId,
                index = 1,
                phase = FocusPhase.SHORT_BREAK.name,
                startedWall = 1_760_001_600_000L,
                startedElapsed = 1_510_000L,
                checkpointed = 300_000L,
                planned = 300_000L,
                endedWall = 1_760_001_900_000L,
                durationMs = 300_000L
            )
        )
    }

    private fun insertV1RunningSession(
        rawDb: SQLiteDatabase,
        customProfileId: String,
        sessionId: String,
        closedSegId: String,
        openSegId: String
    ) {
        rawDb.execSQL(
            """
            INSERT INTO `focus_sessions` (
                `id`, `profile_id`, `subject_id`, `book_id`, `topic_id`, `task_id`,
                `session_mode`, `status`, `current_phase`, `awaiting_next_phase`, `completed_focus_cycles`,
                `started_at_wall_ms`, `ended_at_wall_ms`, `effective_day_date`, `planned_focus_sec`,
                `actual_focus_sec`, `actual_break_sec`, `pause_count`, `is_manual_entry`,
                `was_manually_adjusted`, `interrupted_by_reboot`, `focus_rating`, `reflection_note`,
                `owner_user_id`, `created_at_ms`, `client_updated_at_ms`, `server_updated_at_ms`,
                `deleted_at_ms`, `version`, `sync_status`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                sessionId,
                customProfileId,
                null,
                null,
                null,
                null,
                FocusSessionMode.CUSTOM.name,
                FocusSessionStatus.RUNNING.name,
                FocusPhase.FOCUS.name,
                null,
                0,
                1_760_000_300_000L,
                null,
                "2026-11-19",
                2700L,
                600L,
                0L,
                1,
                0,
                0,
                0,
                null,
                null,
                null,
                1_760_000_300_000L,
                1_760_000_350_010L,
                null,
                null,
                2L,
                SyncStatus.LOCAL_ONLY.name
            )
        )

        insertV1Segment(
            rawDb = rawDb,
            row = V1SegmentRow(
                segmentId = closedSegId,
                sessionId = sessionId,
                index = 0,
                phase = FocusPhase.FOCUS.name,
                startedWall = 1_760_000_300_000L,
                startedElapsed = 20_000L,
                checkpointed = 600_000L,
                planned = 2_700_000L,
                endedWall = 1_760_000_900_000L,
                durationMs = 600_000L
            )
        )
        insertV1Segment(
            rawDb = rawDb,
            row = V1SegmentRow(
                segmentId = openSegId,
                sessionId = sessionId,
                index = 1,
                phase = FocusPhase.FOCUS.name,
                startedWall = 1_760_000_350_010L,
                startedElapsed = 70_000L,
                checkpointed = 120_000L,
                planned = 2_100_000L,
                endedWall = null,
                durationMs = null
            )
        )
    }

    private fun insertV1Segment(rawDb: SQLiteDatabase, row: V1SegmentRow) {
        rawDb.execSQL(
            """
            INSERT INTO `focus_session_segments` (
                `id`, `session_id`, `segment_index`, `phase`, `started_at_wall_ms`,
                `started_at_elapsed_ms`, `boot_count`, `last_checkpointed_duration_ms`,
                `planned_duration_ms`, `ended_at_wall_ms`, `duration_ms`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                row.segmentId,
                row.sessionId,
                row.index,
                row.phase,
                row.startedWall,
                row.startedElapsed,
                1,
                row.checkpointed,
                row.planned,
                row.endedWall,
                row.durationMs
            )
        )
    }

    private suspend fun verifyV2TablesWritable(db: PekmenDatabase) {
        val subjectId = UuidV7.generate(1_760_000_500_000L)
        val bookId = UuidV7.generate(1_760_000_500_010L)
        val topicId = UuidV7.generate(1_760_000_500_020L)
        val sync = SyncMetadataEntity(createdAtMs = 1_760_000_500_000L, clientUpdatedAtMs = 1_760_000_500_000L)

        db.learningDao().upsertSubject(
            LearningSubjectEntity(
                id = subjectId,
                title = "Almanca B2",
                colorHex = 0xFF8B7CF6,
                weeklyGoalMinutes = 150,
                sync = sync
            )
        )
        db.learningDao().upsertBook(
            LearningBookEntity(
                id = bookId,
                subjectId = subjectId,
                title = "Erkundungen B2",
                totalPages = 240,
                currentPage = 40,
                sync = sync
            )
        )
        db.learningTopicDao().upsertTopic(
            LearningTopicEntity(
                id = topicId,
                subjectId = subjectId,
                bookId = bookId,
                title = "Schreiben",
                status = TopicStatus.TODO.name,
                sync = sync
            )
        )

        val subjects = db.learningDao().observeActiveSubjects().first()
        val books = db.learningDao().observeAllActiveBooks().first()
        val topics = db.learningTopicDao().observeAllActiveTopics().first()
        assertEquals(1, subjects.size)
        assertEquals(1, books.size)
        assertEquals(1, topics.size)
        assertFalse(books.first().isCompleted)
        assertTrue(UuidV7.isValidUuidV7(subjects.first().id))
    }

    private fun deleteDatabaseFiles(dbFile: File) {
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()
    }

    private data class V1SeedIds(
        val customProfileId: String,
        val completedSessionId: String,
        val completedFocusSegId: String,
        val completedBreakSegId: String,
        val runningSessionId: String,
        val runningClosedSegId: String,
        val runningOpenSegId: String,
        val outboxMutationId: String
    )

    private data class V1SegmentRow(
        val segmentId: String,
        val sessionId: String,
        val index: Int,
        val phase: String,
        val startedWall: Long,
        val startedElapsed: Long,
        val checkpointed: Long,
        val planned: Long? = null,
        val endedWall: Long? = null,
        val durationMs: Long? = null
    )
}
