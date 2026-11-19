package net.pekmen.one.core.cloud.circles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SubjectSharedTotal
import net.pekmen.one.core.model.UuidV7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class PrivateCirclesRlsAndPrivacyTest {

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
    fun `non-member eve is denied read and write access`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount(server, "user-alice")
        val eve = buildAccount(server, "user-eve")

        val circle = alice.repo.createCircle("Study Squad", "Alice").getOrThrow()
        assertTrue(circle.inviteCode.length >= 16)
        assertNotEquals(circle.inviteCode, CircleInviteCodeGenerator.generate())

        val aliceMemberships = alice.repo.observeMemberships(circle.id).first()
        assertEquals(1, aliceMemberships.size)
        assertFalse(aliceMemberships.first().sharingConfig.shareEnabled)
        assertFalse(aliceMemberships.first().sharingConfig.shareLiveFocusStatus)
        assertTrue(aliceMemberships.first().sharingConfig.allowedSubjectTitles.isEmpty())

        val detailsDenied = server.fetchCircleDetails("user-eve", circle.id)
        assertTrue(detailsDenied.exceptionOrNull() is CircleAccessDeniedException)

        val todayStr = LocalDate.of(2026, 11, 19).toString()
        val leaderboardDenied = server.fetchLeaderboard("user-eve", circle.id, CirclePeriodType.DAILY, todayStr)
        assertTrue(leaderboardDenied.exceptionOrNull() is CircleAccessDeniedException)

        val configDenied = server.updateSharingConfig(
            actingUserId = "user-eve",
            circleId = circle.id,
            config = CircleSharingConfig(shareEnabled = true),
            nowMs = fakeTime.wallTimeMs()
        )
        assertTrue(configDenied.exceptionOrNull() is CircleAccessDeniedException)

        val spoofSummary = RemoteMemberSummaryDto(
            id = UuidV7.generate(fakeTime.wallTimeMs()),
            circleId = circle.id,
            userId = "user-eve",
            displayName = "Eve",
            periodType = CirclePeriodType.DAILY,
            periodStartDate = todayStr,
            liveTimerFocusSec = 9_999L,
            manualEntryFocusSec = 0L,
            subjectTotals = emptyList(),
            isCurrentlyFocusing = true,
            updatedAtMs = fakeTime.wallTimeMs()
        )
        assertTrue(server.publishSummary("user-eve", spoofSummary).exceptionOrNull() is CircleAccessDeniedException)
        assertTrue(
            server.leaveCircle("user-eve", circle.id, fakeTime.wallTimeMs())
                .exceptionOrNull() is CircleAccessDeniedException
        )

        assertTrue(
            eve.repo.joinCircle("BAD0-CODE-0000-0000", "Eve")
                .exceptionOrNull() is CircleNotFoundException
        )
        assertTrue(eve.repo.refreshCircle(circle.id).exceptionOrNull() is CircleAccessDeniedException)
        assertTrue(eve.repo.joinedCircles.first().isEmpty())
        assertTrue(eve.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, todayStr).first().isEmpty())
    }

    @Test
    fun `leaving circle revokes access and purges summary`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount(server, "user-alice")
        val bob = buildAccount(server, "user-bob")
        val charlie = buildAccount(server, "user-charlie")

        val circle = alice.repo.createCircle("Deep Work", "Alice").getOrThrow()
        bob.repo.joinCircle(circle.inviteCode, "Bob").getOrThrow()
        charlie.repo.joinCircle(circle.inviteCode, "Charlie").getOrThrow()

        CirclesTestSupport.insertSession(
            db = charlie.db,
            ownerUserId = charlie.userId,
            focusSec = 1_500L,
            isManualEntry = false,
            effectiveDate = LocalDate.of(2026, 11, 19),
            now = fakeTime.wallTimeMs()
        )
        charlie.repo.updateSharingConfig(circle.id, CircleSharingConfig(shareEnabled = true)).getOrThrow()
        charlie.repo.publishLocalSummarySnapshot(circle.id, isCurrentlyFocusing = false).getOrThrow()

        val todayStr = LocalDate.of(2026, 11, 19).toString()
        val weekStartStr = CircleSummaryProjector.periodWindow(
            CirclePeriodType.WEEKLY,
            LocalDate.of(2026, 11, 19)
        ).periodStartDate

        alice.repo.refreshCircle(circle.id).getOrThrow()
        bob.repo.refreshCircle(circle.id).getOrThrow()
        assertEquals(1, alice.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, todayStr).first().size)
        assertEquals(1, bob.repo.observeLeaderboard(circle.id, CirclePeriodType.WEEKLY, weekStartStr).first().size)

        charlie.repo.leaveCircle(circle.id).getOrThrow()

        assertTrue(charlie.repo.joinedCircles.first().isEmpty())
        assertTrue(charlie.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, todayStr).first().isEmpty())
        assertTrue(charlie.repo.observeMemberships(circle.id).first().isEmpty())
        assertTrue(charlie.repo.refreshCircle(circle.id).exceptionOrNull() is CircleAccessDeniedException)
        assertTrue(
            charlie.repo.updateSharingConfig(circle.id, CircleSharingConfig(shareEnabled = true))
                .exceptionOrNull() is CircleAccessDeniedException
        )
        assertTrue(
            charlie.repo.publishLocalSummarySnapshot(circle.id, isCurrentlyFocusing = false)
                .exceptionOrNull() is CircleAccessDeniedException
        )
        assertEquals(0, server.storedSummaryCount(circle.id))

        alice.repo.refreshCircle(circle.id).getOrThrow()
        bob.repo.refreshCircle(circle.id).getOrThrow()
        assertTrue(alice.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, todayStr).first().isEmpty())
        assertTrue(alice.repo.observeLeaderboard(circle.id, CirclePeriodType.WEEKLY, weekStartStr).first().isEmpty())
        assertTrue(bob.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, todayStr).first().isEmpty())
        assertTrue(bob.repo.observeLeaderboard(circle.id, CirclePeriodType.WEEKLY, weekStartStr).first().isEmpty())
    }

    @Test
    fun `selective sharing hides private data and vanishes off`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount(server, "user-alice")
        val bob = buildAccount(server, "user-bob")

        val circle = alice.repo.createCircle("Exam Prep", "Alice").getOrThrow()
        bob.repo.joinCircle(circle.inviteCode, "Bob").getOrThrow()

        val today = LocalDate.of(2026, 11, 19)
        seedBobPrivateAndSharedActivity(bob, today)

        bob.repo.publishLocalSummarySnapshot(circle.id, isCurrentlyFocusing = true).getOrThrow()
        assertEquals(0, server.storedSummaryCount(circle.id))

        bob.repo.updateSharingConfig(
            circleId = circle.id,
            config = CircleSharingConfig(
                shareEnabled = true,
                shareLiveFocusStatus = true,
                allowedSubjectTitles = setOf("Almanca B2")
            )
        ).getOrThrow()
        bob.repo.publishLocalSummarySnapshot(circle.id, isCurrentlyFocusing = true).getOrThrow()

        alice.repo.refreshCircle(circle.id).getOrThrow()
        val bobStanding = alice.repo.observeLeaderboard(
            circleId = circle.id,
            periodType = CirclePeriodType.DAILY,
            periodStartDate = today.toString()
        ).first().single()
        assertEquals("user-bob", bobStanding.userId)
        assertEquals(2_700L, bobStanding.liveTimerFocusSec)
        assertEquals(900L, bobStanding.manualEntryFocusSec)
        assertEquals(3_600L, bobStanding.totalSharedFocusSec)
        assertTrue(bobStanding.isCurrentlyFocusing)
        assertEquals(listOf(SubjectSharedTotal("Almanca B2", 2_400L)), bobStanding.subjectTotals)

        assertRawServerPayloadRedacted(server.dumpCircleSummariesJson(circle.id))

        bob.repo.updateSharingConfig(
            circleId = circle.id,
            config = CircleSharingConfig(
                shareEnabled = true,
                shareLiveFocusStatus = false,
                allowedSubjectTitles = emptySet()
            )
        ).getOrThrow()
        bob.repo.publishLocalSummarySnapshot(circle.id, isCurrentlyFocusing = true).getOrThrow()
        alice.repo.refreshCircle(circle.id).getOrThrow()
        val updatedStanding = alice.repo.observeLeaderboard(
            circle.id,
            CirclePeriodType.DAILY,
            today.toString()
        ).first().single()
        assertFalse(updatedStanding.isCurrentlyFocusing)
        assertTrue(updatedStanding.subjectTotals.isEmpty())
        assertEquals(3_600L, updatedStanding.totalSharedFocusSec)

        bob.repo.updateSharingConfig(
            circleId = circle.id,
            config = CircleSharingConfig(shareEnabled = false)
        ).getOrThrow()
        assertEquals(0, server.storedSummaryCount(circle.id))
        assertTrue(bob.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, today.toString()).first().isEmpty())

        alice.repo.refreshCircle(circle.id).getOrThrow()
        assertTrue(alice.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, today.toString()).first().isEmpty())
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

    private suspend fun seedBobPrivateAndSharedActivity(bob: CircleAccountHarness, today: LocalDate) {
        val now = fakeTime.wallTimeMs()
        val almancaId = CirclesTestSupport.insertSubject(bob.db, bob.userId, "Almanca B2", now)
        val kisiselId = CirclesTestSupport.insertSubject(bob.db, bob.userId, "Kisisel", now + 1L)
        val bookId = CirclesTestSupport.insertBook(
            bob.db,
            bob.userId,
            almancaId,
            "Erkundungen B2 Secret Book",
            now + 2L
        )
        val taskId = CirclesTestSupport.insertTask(bob.db, bob.userId, almancaId, "Private Dentist Task", now + 3L)

        CirclesTestSupport.insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            focusSec = 1_500L,
            isManualEntry = false,
            effectiveDate = today,
            now = now + 4L,
            subjectId = almancaId,
            bookId = bookId,
            taskId = taskId,
            rating = 5,
            note = "SECRET_REFLECTION_NOTE"
        )
        CirclesTestSupport.insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            focusSec = 900L,
            isManualEntry = true,
            effectiveDate = today,
            now = now + 5L,
            subjectId = almancaId,
            rating = 4,
            note = "MANUAL_SECRET_NOTE"
        )
        CirclesTestSupport.insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            focusSec = 1_200L,
            isManualEntry = false,
            effectiveDate = today,
            now = now + 6L,
            subjectId = kisiselId,
            rating = 2,
            note = "KISISEL_PRIVATE_NOTE"
        )
        CirclesTestSupport.insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            focusSec = 9_999L,
            isManualEntry = false,
            effectiveDate = today,
            now = now + 7L,
            subjectId = almancaId,
            status = FocusSessionStatus.ABANDONED
        )
    }

    private fun assertRawServerPayloadRedacted(rawServerJson: String) {
        assertTrue(rawServerJson.contains("Almanca B2"))
        assertFalse(rawServerJson.contains("Kisisel"))
        assertFalse(rawServerJson.contains("SECRET_REFLECTION_NOTE"))
        assertFalse(rawServerJson.contains("MANUAL_SECRET_NOTE"))
        assertFalse(rawServerJson.contains("KISISEL_PRIVATE_NOTE"))
        assertFalse(rawServerJson.contains("Erkundungen B2 Secret Book"))
        assertFalse(rawServerJson.contains("Private Dentist Task"))
        assertFalse(rawServerJson.contains("reflectionNote"))
        assertFalse(rawServerJson.contains("focusRating"))
    }
}
