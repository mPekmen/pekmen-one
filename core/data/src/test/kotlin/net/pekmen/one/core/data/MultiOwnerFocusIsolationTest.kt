package net.pekmen.one.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.FocusNotificationGateway
import net.pekmen.one.core.platform.ProfileNameProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class MultiOwnerFocusIsolationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fakeTime: FakeClock
    private val dbName = "multi_owner_focus_isolation.db"
    private var openDb: PekmenDatabase? = null
    private var dsScope: CoroutineScope? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        deleteDbFiles(dbFile)
        val wall = LocalDateTime.of(2026, 11, 19, 10, 0, 0)
            .atZone(ZoneId.of("Europe/Istanbul"))
            .toInstant()
            .toEpochMilli()
        fakeTime = FakeClock(wall, 50_000L, 1, ZoneId.of("Europe/Istanbul"))
    }

    @After
    fun tearDown() {
        dsScope?.cancel()
        dsScope = null
        openDb?.close()
        openDb = null
        deleteDbFiles(context.getDatabasePath(dbName))
    }

    @Test
    fun `active session and custom profile isolate per owner`() = runTest {
        val db = PekmenDatabase.createFileBackedForTest(context, dbName)
        openDb = db
        val scope = CoroutineScope(SupervisorJob())
        dsScope = scope
        val rawStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.newFolder(), "owner_prefs.preferences_pb")
        }
        val prefs = UserPreferencesDataStore(rawStore)
        val profiles = FocusProfileRepositoryImpl(
            profileDao = db.focusProfileDao(),
            timeProvider = fakeTime,
            accountSessionDao = db.accountSessionDao(),
            preferencesDataStore = prefs,
            syncOutboxDao = db.syncOutboxDao()
        )
        val sessions = FocusSessionRepositoryImpl(
            database = db,
            profileRepository = profiles,
            preferencesDataStore = prefs,
            timeProvider = fakeTime,
            notificationGateway = NoOpNotifs,
            alarmGateway = NoOpAlarms,
            profileNameProvider = ProfileNameProvider { it.customName ?: it.builtInSlug.name }
        )

        prefs.setActiveOwnerUserId("user-a")
        profiles.ensureSeeded()
        val customA = profiles.saveProfile(
            SaveProfileRequest(
                customName = "Alice Flow",
                colorHex = 0xFF8B7CF6,
                defaultMode = FocusSessionMode.CUSTOM,
                workMinutes = 40,
                shortBreakMinutes = 5,
                longBreakMinutes = 15,
                cyclesBeforeLongBreak = 3
            )
        ).getOrThrow()
        assertEquals(1, db.syncOutboxDao().countPendingForOwner("user-a"))

        val aSession = sessions.startSession(customA.id, FocusSessionMode.CUSTOM).getOrThrow()
        assertEquals("user-a", db.focusSessionDao().getSessionById(aSession.id)?.sync?.ownerUserId)
        fakeTime.advance(120_000L)
        assertNotNull(sessions.activeSession.first())

        prefs.setActiveOwnerUserId("user-b")
        assertNull(sessions.activeSession.first())
        sessions.pauseActiveSession()
        sessions.discardActiveSession()
        assertEquals("RUNNING", db.focusSessionDao().getSessionById(aSession.id)?.status)
        assertTrue(sessions.startSession(customA.id, FocusSessionMode.CUSTOM).isFailure)

        val bSession = sessions.startSession(BuiltInProfiles.STUDY_ID, FocusSessionMode.POMODORO).getOrThrow()
        assertEquals("user-b", db.focusSessionDao().getSessionById(bSession.id)?.sync?.ownerUserId)
        assertEquals(bSession.id, sessions.activeSession.first()?.session?.id)

        prefs.setActiveOwnerUserId("user-a")
        val restoredA = sessions.activeSession.first()
        assertEquals(aSession.id, restoredA?.session?.id)
        assertEquals("user-a", db.focusSessionDao().getSessionById(aSession.id)?.sync?.ownerUserId)
    }

    private fun deleteDbFiles(dbFile: File) {
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()
    }

    private class FakeClock(
        private var wallMs: Long,
        private var elapsedMs: Long,
        private val boot: Int,
        private val zone: ZoneId
    ) : TimeProvider {
        override fun wallTimeMs(): Long = wallMs
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun bootCount(): Int = boot
        override fun zoneId(): ZoneId = zone
        fun advance(deltaMs: Long) {
            wallMs += deltaMs
            elapsedMs += deltaMs
        }
    }

    private object NoOpNotifs : FocusNotificationGateway {
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

    private object NoOpAlarms : FocusAlarmGateway {
        override fun schedulePhaseEnd(sessionId: String, segmentId: String, triggerAtElapsedMs: Long) = Unit
        override fun cancelPhaseEnd() = Unit
        override fun canScheduleExactAlarms(): Boolean = true
    }
}
