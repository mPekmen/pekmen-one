package net.pekmen.one.core.portability

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BackupImportResult
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class BackupOwnershipSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private val openDatabases = mutableListOf<PekmenDatabase>()
    private val fixedTime = object : TimeProvider {
        override fun wallTimeMs(): Long = 1_763_500_000_000L
        override fun elapsedRealtimeMs(): Long = 10_000L
        override fun bootCount(): Int = 1
        override fun zoneId(): ZoneId = ZoneId.of("Europe/Istanbul")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    @Test
    fun `rejects cross-owner archive active session and pk overwrite`() = runTest {
        val ownerADb = newDb()
        val ownerAStore = newPrefs("a")
        ownerAStore.setActiveOwnerUserId("owner-a")
        seedBuiltInStudy(ownerADb)
        val sharedSubjectId = UuidV7.generate(fixedTime.wallTimeMs())
        ownerADb.learningDao().upsertSubject(
            LearningSubjectEntity(
                id = sharedSubjectId,
                title = "Alice Private",
                colorHex = 0xFF8B7CF6,
                weeklyGoalMinutes = 120,
                sync = SyncMetadataEntity(
                    ownerUserId = "owner-a",
                    createdAtMs = 1_000L,
                    clientUpdatedAtMs = 1_000L
                )
            )
        )
        val aliceSessionId = UuidV7.generate(fixedTime.wallTimeMs() + 1L)
        ownerADb.focusSessionDao().insertSession(
            FocusSessionEntity(
                id = aliceSessionId,
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = sharedSubjectId,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = FocusSessionStatus.COMPLETED.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = 1_000L,
                endedAtWallMs = 1_501_000L,
                effectiveDayDate = "2026-11-19",
                plannedFocusSec = 1_500L,
                actualFocusSec = 1_500L,
                actualBreakSec = 0L,
                pauseCount = 0,
                sync = SyncMetadataEntity(
                    ownerUserId = "owner-a",
                    createdAtMs = 1_000L,
                    clientUpdatedAtMs = 1_000L
                )
            )
        )
        val serviceA = BackupPortabilityServiceImpl(ownerADb, ownerAStore, fixedTime)
        val ownerAArchive = serviceA.exportJsonArchive().getOrThrow()

        val ownerBStore = newPrefs("b")
        ownerBStore.setActiveOwnerUserId("owner-b")
        val targetDb = newDb()
        seedBuiltInStudy(targetDb)
        targetDb.learningDao().upsertSubject(
            LearningSubjectEntity(
                id = sharedSubjectId,
                title = "Bob Existing",
                colorHex = 0xFF60A5FA,
                weeklyGoalMinutes = 90,
                sync = SyncMetadataEntity(
                    ownerUserId = "owner-b",
                    createdAtMs = 1_000L,
                    clientUpdatedAtMs = 1_000L
                )
            )
        )
        val serviceB = BackupPortabilityServiceImpl(targetDb, ownerBStore, fixedTime)
        val crossOwnerResult = serviceB.importJsonArchive(ownerAArchive)
        assertTrue(crossOwnerResult is BackupImportResult.Failure)
        assertEquals(
            BackupValidationError.INVALID_IDENTIFIER,
            (crossOwnerResult as BackupImportResult.Failure).reason
        )
        assertEquals("Bob Existing", targetDb.learningDao().getSubjectById(sharedSubjectId)?.title)

        val tamperedRunning = ownerAArchive.replace("\"status\": \"COMPLETED\"", "\"status\": \"RUNNING\"")
        val activeSessionReject = serviceA.importJsonArchive(tamperedRunning)
        assertTrue(activeSessionReject is BackupImportResult.Failure)
    }

    @Test
    fun `guest restore while signed in claims owner and reschedules`() = runTest {
        val guestDb = newDb()
        val guestStore = newPrefs("guest")
        seedBuiltInStudy(guestDb)
        val sessionId = UuidV7.generate(fixedTime.wallTimeMs() + 5L)
        guestDb.focusSessionDao().insertSession(
            FocusSessionEntity(
                id = sessionId,
                profileId = BuiltInProfiles.STUDY_ID,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = FocusSessionStatus.COMPLETED.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = 1_000L,
                endedAtWallMs = 1_501_000L,
                effectiveDayDate = "2026-11-19",
                plannedFocusSec = 1_500L,
                actualFocusSec = 1_500L,
                actualBreakSec = 0L,
                pauseCount = 0,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = 1_000L, clientUpdatedAtMs = 1_000L)
            )
        )
        guestDb.focusSessionDao().insertSegment(
            FocusSessionSegmentEntity(
                id = UuidV7.generate(fixedTime.wallTimeMs() + 6L),
                sessionId = sessionId,
                segmentIndex = 0,
                phase = FocusPhase.FOCUS.name,
                startedAtWallMs = 1_000L,
                startedAtElapsedMs = 100L,
                bootCount = 1,
                lastCheckpointedDurationMs = 1_500_000L,
                plannedDurationMs = 1_500_000L,
                endedAtWallMs = 1_501_000L,
                durationMs = 1_500_000L
            )
        )
        val guestJson = BackupPortabilityServiceImpl(guestDb, guestStore, fixedTime).exportJsonArchive().getOrThrow()

        val signedInDb = newDb()
        val signedInStore = newPrefs("signed")
        signedInStore.setActiveOwnerUserId("owner-claimed")
        var rescheduled = false
        val service = BackupPortabilityServiceImpl(signedInDb, signedInStore, fixedTime) {
            rescheduled = true
        }
        val outcome = service.importJsonArchive(guestJson)
        assertTrue(outcome is BackupImportResult.Success)
        assertTrue(rescheduled)
        assertEquals("owner-claimed", signedInDb.focusSessionDao().getSessionById(sessionId)?.sync?.ownerUserId)
        assertTrue(signedInDb.syncOutboxDao().countPendingForOwner("owner-claimed") > 0)
    }

    private fun newDb(): PekmenDatabase {
        val db = PekmenDatabase.createInMemory(context)
        openDatabases += db
        return db
    }

    private fun kotlinx.coroutines.test.TestScope.newPrefs(name: String): UserPreferencesDataStore {
        val file = File(tempFolder.newFolder(), "$name.preferences_pb")
        return UserPreferencesDataStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        )
    }

    private suspend fun seedBuiltInStudy(db: PekmenDatabase) {
        val p = BuiltInProfiles.defaults().first()
        db.focusProfileDao().insert(
            FocusProfileEntity(
                id = p.id,
                builtInSlug = p.builtInSlug.name,
                customName = null,
                colorHex = p.colorHex,
                iconKey = p.iconKey,
                defaultMode = p.defaultMode.name,
                workMinutes = p.workMinutes,
                shortBreakMinutes = p.shortBreakMinutes,
                longBreakMinutes = p.longBreakMinutes,
                cyclesBeforeLongBreak = p.cyclesBeforeLongBreak,
                dndEnabled = p.dndEnabled,
                sortOrder = p.sortOrder,
                isArchived = false,
                sync = SyncMetadataEntity(createdAtMs = 1_000L, clientUpdatedAtMs = 1_000L)
            )
        )
    }
}
