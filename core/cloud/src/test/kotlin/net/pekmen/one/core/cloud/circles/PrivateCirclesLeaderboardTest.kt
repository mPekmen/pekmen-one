package net.pekmen.one.core.cloud.circles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.SubjectSharedTotal
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PrivateCirclesLeaderboardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fakeTime: CircleFakeTimeProvider
    private val openDatabases = mutableListOf<PekmenDatabase>()

    private val enabledGate = CloudRlsGateStatus(
        compiledWithCloudFlag = true,
        backendEndpointConfigured = true,
        rlsVerified = true
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val baseWallMs = LocalDateTime.of(2026, 11, 19, 14, 0, 0)
            .atZone(ZoneId.of("Europe/Istanbul"))
            .toInstant()
            .toEpochMilli()
        fakeTime = CircleFakeTimeProvider(
            wallMs = baseWallMs,
            elapsedMs = 100_000L,
            boot = 1,
            zone = ZoneId.of("Europe/Istanbul")
        )
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    @Test
    fun `leaderboard ranks members deterministically with ties`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount(server, "user-alice")
        val bob = buildAccount(server, "user-bob")
        val charlie = buildAccount(server, "user-charlie")

        val circle = alice.repo.createCircle("Study Rank", "Alice").getOrThrow()
        bob.repo.joinCircle(circle.inviteCode, "Bob").getOrThrow()
        charlie.repo.joinCircle(circle.inviteCode, "Charlie").getOrThrow()

        val today = LocalDate.of(2026, 11, 19)
        val now = fakeTime.wallTimeMs()
        CirclesTestSupport.insertSession(
            alice.db,
            alice.userId,
            focusSec = 3_000L,
            isManualEntry = false,
            effectiveDate = today,
            now = now
        )
        CirclesTestSupport.insertSession(
            alice.db,
            alice.userId,
            focusSec = 600L,
            isManualEntry = true,
            effectiveDate = today,
            now = now + 1L
        )
        CirclesTestSupport.insertSession(
            bob.db,
            bob.userId,
            focusSec = 2_400L,
            isManualEntry = false,
            effectiveDate = today,
            now = now + 2L
        )
        CirclesTestSupport.insertSession(
            charlie.db,
            charlie.userId,
            focusSec = 1_000L,
            isManualEntry = false,
            effectiveDate = today,
            now = now + 3L
        )
        CirclesTestSupport.insertSession(
            charlie.db,
            charlie.userId,
            focusSec = 1_400L,
            isManualEntry = true,
            effectiveDate = today,
            now = now + 4L
        )

        listOf(alice, bob, charlie).forEach { account ->
            account.repo.updateSharingConfig(circle.id, CircleSharingConfig(shareEnabled = true)).getOrThrow()
            account.repo.publishLocalSummarySnapshot(circle.id, isCurrentlyFocusing = false).getOrThrow()
        }

        alice.repo.refreshCircle(circle.id).getOrThrow()
        val ranked = alice.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, today.toString()).first()
        assertEquals(3, ranked.size)
        assertEquals(listOf("user-alice", "user-bob", "user-charlie"), ranked.map { it.userId })
        assertEquals(listOf(1, 2, 3), ranked.map { it.rank })
        assertEquals(3_600L, ranked[0].totalSharedFocusSec)
        assertEquals(2_400L, ranked[1].totalSharedFocusSec)
        assertEquals(2_400L, ranked[2].totalSharedFocusSec)
        assertEquals(1_000L, ranked[2].liveTimerFocusSec)
        assertEquals(1_400L, ranked[2].manualEntryFocusSec)
    }

    @Test
    fun `rls gate fails closed with zero gateway calls`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val offlineGate = CloudRlsGateStatus(
            compiledWithCloudFlag = false,
            backendEndpointConfigured = false,
            rlsVerified = false
        )
        val unverifiedRlsGate = CloudRlsGateStatus(
            compiledWithCloudFlag = true,
            backendEndpointConfigured = true,
            rlsVerified = false
        )

        val aliceOffline = buildAccount(server, "user-alice", offlineGate)
        assertTrue(aliceOffline.repo.createCircle("Offline", "Alice").exceptionOrNull() is CirclesGatedException)
        assertTrue(
            aliceOffline.repo.joinCircle("ABCD-1234-EFGH-5678", "Alice")
                .exceptionOrNull() is CirclesGatedException
        )
        assertTrue(aliceOffline.repo.leaveCircle("circle-1").exceptionOrNull() is CirclesGatedException)
        assertTrue(
            aliceOffline.repo.updateSharingConfig("circle-1", CircleSharingConfig(shareEnabled = true))
                .exceptionOrNull() is CirclesGatedException
        )
        assertTrue(
            aliceOffline.repo.publishLocalSummarySnapshot("circle-1", true)
                .exceptionOrNull() is CirclesGatedException
        )
        assertTrue(aliceOffline.repo.refreshCircle("circle-1").exceptionOrNull() is CirclesGatedException)
        assertTrue(aliceOffline.repo.refreshJoinedCircles().exceptionOrNull() is CirclesGatedException)

        val aliceUnverified = buildAccount(server, "user-alice-2", unverifiedRlsGate)
        assertTrue(
            aliceUnverified.repo.createCircle("Unverified", "Alice")
                .exceptionOrNull() is CirclesGatedException
        )
        assertEquals(0, server.totalGatewayCalls())
    }

    @Test
    fun `projector aggregates weekly window with opt-in`() {
        val anchorDate = LocalDate.of(2026, 11, 19)
        val weeklyWindow = CircleSummaryProjector.periodWindow(CirclePeriodType.WEEKLY, anchorDate)
        assertEquals(LocalDate.of(2026, 11, 16), weeklyWindow.startDate)
        assertEquals(LocalDate.of(2026, 11, 22), weeklyWindow.endDate)

        val subjectId = "subj-almanca"
        val subjects = mapOf(
            subjectId to LearningSubject(
                id = subjectId,
                title = "Almanca B2",
                colorHex = 0xFF8B7CF6,
                weeklyGoalMinutes = 150
            )
        )
        val sessions = listOf(
            FocusSession(
                id = "s-in-week",
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                sessionMode = FocusSessionMode.POMODORO,
                status = FocusSessionStatus.COMPLETED,
                currentPhase = FocusPhase.FOCUS,
                completedFocusCycles = 1,
                startedAtWallMs = 1_000L,
                effectiveDayDate = LocalDate.of(2026, 11, 16),
                actualFocusSec = 1_500L,
                actualBreakSec = 0L,
                pauseCount = 0,
                isManualEntry = false,
                reflectionNote = "Should never appear",
                focusRating = 5
            ),
            FocusSession(
                id = "s-out-of-week",
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                sessionMode = FocusSessionMode.POMODORO,
                status = FocusSessionStatus.COMPLETED,
                currentPhase = FocusPhase.FOCUS,
                completedFocusCycles = 1,
                startedAtWallMs = 1_000L,
                effectiveDayDate = LocalDate.of(2026, 11, 15),
                actualFocusSec = 3_600L,
                actualBreakSec = 0L,
                pauseCount = 0,
                isManualEntry = false
            )
        )

        val disabled = CircleSummaryProjector.project(
            circleId = "c-1",
            membership = CirclesTestSupport.testMembership(CircleSharingConfig(shareEnabled = false)),
            periodWindow = weeklyWindow,
            completedSessions = sessions,
            subjectsById = subjects,
            activeFocusRunning = true,
            nowMs = 5_000L
        )
        assertNull(disabled)

        val enabled = CircleSummaryProjector.project(
            circleId = "c-1",
            membership = CirclesTestSupport.testMembership(
                CircleSharingConfig(
                    shareEnabled = true,
                    shareLiveFocusStatus = false,
                    allowedSubjectTitles = setOf("Almanca B2")
                )
            ),
            periodWindow = weeklyWindow,
            completedSessions = sessions,
            subjectsById = subjects,
            activeFocusRunning = true,
            nowMs = 5_000L
        )!!
        assertEquals(1_500L, enabled.liveTimerFocusSec)
        assertEquals(0L, enabled.manualEntryFocusSec)
        assertFalse(enabled.isCurrentlyFocusing)
        assertEquals(listOf(SubjectSharedTotal("Almanca B2", 1_500L)), enabled.subjectTotals)
    }

    private suspend fun TestScope.buildAccount(
        server: InMemoryCirclesServerDouble,
        userId: String,
        gate: CloudRlsGateStatus = enabledGate
    ): CircleAccountHarness = CirclesTestSupport.buildAccount(
        env = CirclesTestEnv(this, context, tempFolder, openDatabases, fakeTime, server),
        userId = userId,
        gateProvider = { gate }
    )
}
