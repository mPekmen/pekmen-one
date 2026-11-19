package net.pekmen.one.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.model.TimeProvider
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
class FocusEngineRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: PekmenDatabase
    private lateinit var fakeTime: FakeTimeProvider
    private lateinit var fakeNotifications: FakeNotificationGateway
    private lateinit var fakeAlarms: FakeAlarmGateway
    private lateinit var dataStore: UserPreferencesDataStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PekmenDatabase.createInMemory(context)
        val baseWall = LocalDateTime.of(2026, 11, 19, 10, 0, 0)
            .atZone(ZoneId.of("Europe/Istanbul"))
            .toInstant()
            .toEpochMilli()
        fakeTime = FakeTimeProvider(
            wallMs = baseWall,
            elapsedMs = 50_000L,
            boot = 10,
            zone = ZoneId.of("Europe/Istanbul")
        )
        fakeNotifications = FakeNotificationGateway()
        fakeAlarms = FakeAlarmGateway()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun buildRepos(scope: TestScope): Triple<
        FocusProfileRepositoryImpl,
        FocusSessionRepositoryImpl,
        FocusHistoryRepositoryImpl
        > {
        if (!::dataStore.isInitialized) {
            val file = File(tempFolder.newFolder(), "prefs.preferences_pb")
            val rawStore = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file }
            dataStore = UserPreferencesDataStore(rawStore)
        }
        val profiles = FocusProfileRepositoryImpl(database.focusProfileDao(), fakeTime)
        val sessions = FocusSessionRepositoryImpl(
            database = database,
            profileRepository = profiles,
            preferencesDataStore = dataStore,
            timeProvider = fakeTime,
            notificationGateway = fakeNotifications,
            alarmGateway = fakeAlarms,
            profileNameProvider = ProfileNameProvider { it.customName ?: it.builtInSlug.name }
        )
        val history = FocusHistoryRepositoryImpl(database, profiles, dataStore, fakeTime)
        return Triple(profiles, sessions, history)
    }

    @Test
    fun `stopwatch excludes paused gaps from net focus seconds`() = runTest {
        val (_, sessions, _) = buildRepos(this)

        sessions.startSession(BuiltInProfiles.PERSONAL_ID, FocusSessionMode.STOPWATCH).getOrThrow()
        fakeTime.advanceBy(120_000L) // 2 min active
        sessions.pauseActiveSession()

        fakeTime.advanceBy(600_000L) // 10 min paused (must NOT be counted)
        sessions.resumeActiveSession()

        fakeTime.advanceBy(180_000L) // 3 min active
        val completed = sessions.completeActiveSession(rating = 5, note = "Clean deep work")

        assertNotNull(completed)
        assertEquals(FocusSessionStatus.COMPLETED, completed?.status)
        assertEquals(300L, completed?.actualFocusSec)
        assertEquals(0L, completed?.actualBreakSec)
        assertEquals(1, completed?.pauseCount)
        assertEquals(5, completed?.focusRating)
    }

    @Test
    fun `pomodoro 4 cycle separates net focus and break and reaches long break`() = runTest {
        val (profiles, sessions, _) = buildRepos(this)
        profiles.ensureSeeded()
        val study = profiles.profiles.first().first { it.id == BuiltInProfiles.STUDY_ID }
        assertEquals(4, study.cyclesBeforeLongBreak)

        sessions.startSession(BuiltInProfiles.STUDY_ID, FocusSessionMode.POMODORO).getOrThrow()

        repeat(3) { cycle ->
            val activeFocus = sessions.activeSession.first()!!
            val focusSeg = activeFocus.openSegment!!
            fakeTime.advanceBy(25 * 60_000L)
            sessions.handlePhaseTimerExpired(activeFocus.session.id, focusSeg.id)

            val awaitingBreak = sessions.activeSession.first()!!
            assertEquals(FocusSessionStatus.AWAITING_NEXT_PHASE, awaitingBreak.session.status)
            assertEquals(FocusPhase.SHORT_BREAK, awaitingBreak.session.awaitingNextPhase)
            assertEquals(cycle + 1, awaitingBreak.session.completedFocusCycles)

            sessions.startAwaitingNextPhase()
            val activeBreak = sessions.activeSession.first()!!
            val breakSeg = activeBreak.openSegment!!
            fakeTime.advanceBy(5 * 60_000L)
            sessions.handlePhaseTimerExpired(activeBreak.session.id, breakSeg.id)

            val awaitingFocus = sessions.activeSession.first()!!
            assertEquals(FocusPhase.FOCUS, awaitingFocus.session.awaitingNextPhase)
            sessions.startAwaitingNextPhase()
        }

        // 4th focus cycle -> triggers LONG_BREAK
        val fourthFocus = sessions.activeSession.first()!!
        fakeTime.advanceBy(25 * 60_000L)
        sessions.handlePhaseTimerExpired(fourthFocus.session.id, fourthFocus.openSegment!!.id)

        val awaitingLongBreak = sessions.activeSession.first()!!
        assertEquals(FocusPhase.LONG_BREAK, awaitingLongBreak.session.awaitingNextPhase)

        sessions.startAwaitingNextPhase()
        fakeTime.advanceBy(15 * 60_000L)
        val activeLongBreak = sessions.activeSession.first()!!
        sessions.handlePhaseTimerExpired(activeLongBreak.session.id, activeLongBreak.openSegment!!.id)

        val completed = sessions.completeActiveSession()!!
        assertEquals(4 * 25 * 60L, completed.actualFocusSec) // 6000s
        assertEquals((3 * 5 + 15) * 60L, completed.actualBreakSec) // 1800s
    }

    @Test
    fun `unattended countdown expiry caps elapsed time at planned duration`() = runTest {
        val (_, sessions, _) = buildRepos(this)

        sessions.startSession(
            profileId = BuiltInProfiles.SLEEP_ID,
            mode = FocusSessionMode.CUSTOM,
            customDurationMinutes = 20
        ).getOrThrow()

        // User ignores device for 2 hours after starting a 20m timer
        fakeTime.advanceBy(120 * 60_000L)
        sessions.reconcileActiveSessionAfterBootOrWake()

        val active = sessions.activeSession.first()!!
        assertEquals(FocusSessionStatus.AWAITING_NEXT_PHASE, active.session.status)
        assertNull(active.session.awaitingNextPhase)
        assertEquals(20 * 60L, active.session.actualFocusSec)
    }

    @Test
    fun `custom countdown offers a single optional break and separates its duration`() = runTest {
        val (_, sessions, _) = buildRepos(this)
        dataStore.setCustomBreakEnabled(true)
        sessions.startSession(BuiltInProfiles.STUDY_ID, FocusSessionMode.CUSTOM, 10).getOrThrow()

        fakeTime.advanceBy(10 * 60_000L)
        sessions.reconcileActiveSessionAfterBootOrWake()
        assertEquals(FocusPhase.SHORT_BREAK, sessions.activeSession.first()?.session?.awaitingNextPhase)
        sessions.startAwaitingNextPhase()

        fakeTime.advanceBy(5 * 60_000L)
        sessions.reconcileActiveSessionAfterBootOrWake()
        assertNull(sessions.activeSession.first()?.session?.awaitingNextPhase)
        val completed = requireNotNull(sessions.completeActiveSession())
        assertEquals(600L, completed.actualFocusSec)
        assertEquals(300L, completed.actualBreakSec)
    }

    @Test
    fun `discarding short session marks abandoned and excludes from summaries`() = runTest {
        val (_, sessions, history) = buildRepos(this)
        val today = LocalDate.of(2026, 11, 19)

        sessions.startSession(BuiltInProfiles.PERSONAL_ID, FocusSessionMode.STOPWATCH).getOrThrow()
        fakeTime.advanceBy(25_000L)
        sessions.discardActiveSession()

        assertNull(sessions.activeSession.first())
        val summary = history.observeDailySummary(today).first()
        assertEquals(0L, summary.netFocusSeconds)
        assertEquals(0, summary.completedSessionCount)
    }

    @Test
    fun `process death on same boot continues running session seamlessly without lost time`() = runTest {
        val (_, firstInstance, _) = buildRepos(this)
        firstInstance.startSession(BuiltInProfiles.STUDY_ID, FocusSessionMode.POMODORO).getOrThrow()

        // App process killed for 18 minutes on same boot
        fakeTime.advanceBy(18 * 60_000L)

        // Fresh repository instance created after OS recreates process
        val (_, recreatedInstance, _) = buildRepos(this)
        recreatedInstance.reconcileActiveSessionAfterBootOrWake()

        val snapshot = recreatedInstance.activeSession.first()!!
        assertEquals(FocusSessionStatus.RUNNING, snapshot.session.status)
        assertFalse(snapshot.session.interruptedByReboot)

        val clock = FocusTimeMath.computeDisplayClock(
            snapshot = snapshot,
            nowElapsedMs = fakeTime.elapsedRealtimeMs(),
            currentBootCount = fakeTime.bootCount()
        )
        assertEquals(18 * 60L, clock.totalFocusSeconds)
        assertEquals(7 * 60L, clock.primarySeconds) // 25m - 18m = 7m remaining
    }

    @Test
    fun `device reboot stops counting powered off time and auto pauses at last checkpoint`() = runTest {
        val (_, firstInstance, _) = buildRepos(this)
        firstInstance.startSession(BuiltInProfiles.PERSONAL_ID, FocusSessionMode.STOPWATCH).getOrThrow()

        // User focuses for 12 minutes and foreground heartbeat checkpoints it
        fakeTime.advanceBy(12 * 60_000L)
        firstInstance.checkpointActiveSession()

        // Device powers off for 6 hours, then boots again (bootCount increments, elapsedRealtime resets)
        fakeTime.simulateReboot(poweredOffWallMs = 6 * 3_600_000L, newElapsedMs = 35_000L)

        val (_, afterBootInstance, _) = buildRepos(this)
        afterBootInstance.reconcileActiveSessionAfterBootOrWake()

        val snapshot = afterBootInstance.activeSession.first()!!
        assertEquals(FocusSessionStatus.PAUSED, snapshot.session.status)
        assertTrue(snapshot.session.interruptedByReboot)
        assertEquals(12 * 60L, snapshot.session.actualFocusSec) // NEVER 6h 12m!
        assertTrue(fakeNotifications.lastPausedWasReboot)
    }

    @Test
    fun `manual trim retroactive entry and rollover hour aggregate accurately in daily and weekly summaries`() =
        runTest {
            val (_, sessions, history) = buildRepos(this)
            val anchorDay = LocalDate.of(2026, 11, 19)

            // 1) Live session trimmed from 30m down to 20m during wrap-up
            sessions.startSession(BuiltInProfiles.PERSONAL_ID, FocusSessionMode.STOPWATCH).getOrThrow()
            fakeTime.advanceBy(30 * 60_000L)
            val trimmed = sessions.completeActiveSession(trimmedFocusSeconds = 20 * 60L, rating = 4)!!
            assertTrue(trimmed.wasManuallyAdjusted)
            assertEquals(20 * 60L, trimmed.actualFocusSec)

            // 2) Retroactive session at 02:30 on Nov 19 -> attributes to Nov 18 (rolloverHour = 4)
            val earlyMorningWall = LocalDateTime.of(2026, 11, 19, 2, 30, 0)
                .atZone(ZoneId.of("Europe/Istanbul"))
                .toInstant()
                .toEpochMilli()
            val retro = history.logRetroactiveSession(
                RetroactiveSessionRequest(
                    profileId = BuiltInProfiles.WORK_ID,
                    startedAtWallMs = earlyMorningWall,
                    focusMinutes = 40,
                    breakMinutes = 5,
                    rating = 5,
                    note = "Late night paper review"
                )
            ).getOrThrow()
            assertTrue(retro.isManualEntry)
            assertEquals(LocalDate.of(2026, 11, 18), retro.effectiveDayDate)

            // 3) Reject future retroactive entry
            val futureResult = history.logRetroactiveSession(
                RetroactiveSessionRequest(
                    profileId = BuiltInProfiles.STUDY_ID,
                    startedAtWallMs = fakeTime.wallTimeMs() + 60_000L,
                    focusMinutes = 25,
                    breakMinutes = 0,
                    rating = null,
                    note = null
                )
            )
            assertTrue(futureResult.isFailure)

            val todaySummary = history.observeDailySummary(anchorDay).first()
            assertEquals(20 * 60L, todaySummary.netFocusSeconds)
            assertEquals(1, todaySummary.completedSessionCount)

            val weekly = history.observeWeeklySummary(anchorDay).first()
            assertEquals((20 + 40) * 60L, weekly.totalFocusSeconds)
            assertEquals(5 * 60L, weekly.totalBreakSeconds)
            assertEquals(2, weekly.completedSessionCount)
            assertEquals(2, weekly.profileBreakdown.size)

            // 4) Soft delete removes from summaries
            history.softDeleteSession(retro.id)
            val weeklyAfterDelete = history.observeWeeklySummary(anchorDay).first()
            assertEquals(20 * 60L, weeklyAfterDelete.totalFocusSeconds)
            assertEquals(1, weeklyAfterDelete.completedSessionCount)
        }

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

        fun simulateReboot(poweredOffWallMs: Long, newElapsedMs: Long) {
            wallMs += poweredOffWallMs
            elapsedMs = newElapsedMs
            boot += 1
        }
    }

    private class FakeNotificationGateway : FocusNotificationGateway {
        var lastPausedWasReboot: Boolean = false

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
        ) {
            lastPausedWasReboot = interruptedByReboot
        }

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

    private class FakeAlarmGateway : FocusAlarmGateway {
        var scheduledTriggerElapsedMs: Long? = null

        override fun schedulePhaseEnd(sessionId: String, segmentId: String, triggerAtElapsedMs: Long) {
            scheduledTriggerElapsedMs = triggerAtElapsedMs
        }

        override fun cancelPhaseEnd() {
            scheduledTriggerElapsedMs = null
        }

        override fun canScheduleExactAlarms(): Boolean = true
    }
}
