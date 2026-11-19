package net.pekmen.one.core.portability

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.ZoneId

internal object BackupTestFixtures {

    const val BASE_WALL_MS = 1_760_000_000_000L

    data class BaselineIds(
        val profileId: String,
        val subjectId: String,
        val taskId: String,
        val bookId: String = "",
        val topicId: String = "",
        val sessionId: String = ""
    )

    class FixedTimeProvider(private val wallMs: Long = BASE_WALL_MS) : TimeProvider {
        override fun wallTimeMs(): Long = wallMs
        override fun elapsedRealtimeMs(): Long = 10_000L
        override fun bootCount(): Int = 1
        override fun zoneId(): ZoneId = ZoneId.of("Europe/Istanbul")
    }

    class FailingOnWriteDataStore(
        private val delegate: DataStore<Preferences>
    ) : DataStore<Preferences> {
        var failWrites: Boolean = false
        override val data: Flow<Preferences> = delegate.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("Simulated DataStore write failure")
            return delegate.updateData(transform)
        }
    }

    fun newDataStore(tempFolder: TemporaryFolder, scope: TestScope, name: String): UserPreferencesDataStore {
        val file = File(tempFolder.newFolder(), "$name.preferences_pb")
        val raw = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file }
        return UserPreferencesDataStore(raw)
    }

    suspend fun assertFailure(
        service: BackupPortabilityServiceImpl,
        archive: PekmenBackupArchive,
        expected: BackupValidationError
    ) {
        val raw = BackupValidator.json.encodeToString(PekmenBackupArchive.serializer(), archive)
        val result = service.importJsonArchive(raw)
        assertTrue("Expected failure for $expected", result is BackupImportResult.Failure)
        assertEquals(expected, (result as BackupImportResult.Failure).reason)
    }

    suspend fun seedPreExistingBaseline(
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

    suspend fun assertPreExistingBaselineIntact(
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

    suspend fun seedFullV2Fixture(
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

    suspend fun assertFullFixturePreserved(
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

    fun buildValidArchiveFixture(ownerUserId: String?): PekmenBackupArchive {
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
            profiles = listOf(buildArchiveProfile(profileId, sync)),
            subjects = listOf(BackupSubject(subjectId, "Subject", 0xFF60A5FA, 120, sync = sync)),
            books = listOf(BackupBook(bookId, subjectId, "Book", totalPages = 100, currentPage = 10, sync = sync)),
            topics = listOf(BackupTopic(topicId, subjectId, bookId, "Topic", sync = sync)),
            tasks = listOf(BackupTask(taskId, "Task", subjectId = subjectId, sync = sync)),
            sessions = listOf(buildArchiveSession(sessionId, profileId, subjectId, bookId, topicId, taskId, sync)),
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

    fun buildLegacyV1Json(profileId: String, sessionId: String, segmentId: String): String {
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

    fun builtInStudyProfile(nowMs: Long): FocusProfileEntity {
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

    private fun buildArchiveProfile(profileId: String, sync: BackupSyncMetadata): BackupProfile = BackupProfile(
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

    private fun buildArchiveSession(
        sessionId: String,
        profileId: String,
        subjectId: String,
        bookId: String,
        topicId: String,
        taskId: String,
        sync: BackupSyncMetadata
    ): BackupSession = BackupSession(
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
}
