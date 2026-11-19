package net.pekmen.one.core.cloud.circles

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
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
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class PrivateCirclesVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fakeTime: FakeTimeProvider
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
        fakeTime = FakeTimeProvider(
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
    fun `non-member eve is denied read and write access to alice circle and summaries`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount("user-alice", server) { enabledGate }
        val eve = buildAccount("user-eve", server) { enabledGate }

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
        assertTrue(server.leaveCircle("user-eve", circle.id, fakeTime.wallTimeMs())
            .exceptionOrNull() is CircleAccessDeniedException)

        assertTrue(eve.repo.joinCircle("BAD0-CODE-0000-0000", "Eve")
            .exceptionOrNull() is CircleNotFoundException)
        assertTrue(eve.repo.refreshCircle(circle.id).exceptionOrNull() is CircleAccessDeniedException)
        assertTrue(eve.repo.joinedCircles.first().isEmpty())
        assertTrue(eve.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, todayStr).first().isEmpty())
    }

    @Test
    fun `member charlie leaving immediately revokes access and purges summary from alice and bob`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount("user-alice", server) { enabledGate }
        val bob = buildAccount("user-bob", server) { enabledGate }
        val charlie = buildAccount("user-charlie", server) { enabledGate }

        val circle = alice.repo.createCircle("Deep Work", "Alice").getOrThrow()
        bob.repo.joinCircle(circle.inviteCode, "Bob").getOrThrow()
        charlie.repo.joinCircle(circle.inviteCode, "Charlie").getOrThrow()

        insertSession(
            db = charlie.db,
            ownerUserId = charlie.userId,
            focusSec = 1_500L,
            isManualEntry = false,
            effectiveDate = LocalDate.of(2026, 11, 19)
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
    fun `bob selective sharing hides excluded subject notes books tasks and vanishes when toggled off`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount("user-alice", server) { enabledGate }
        val bob = buildAccount("user-bob", server) { enabledGate }

        val circle = alice.repo.createCircle("Exam Prep", "Alice").getOrThrow()
        bob.repo.joinCircle(circle.inviteCode, "Bob").getOrThrow()

        val almancaId = insertSubject(bob.db, bob.userId, "Almanca B2")
        val kisiselId = insertSubject(bob.db, bob.userId, "Kisisel")
        val bookId = insertBook(bob.db, bob.userId, almancaId, "Erkundungen B2 Secret Book")
        val taskId = insertTask(bob.db, bob.userId, almancaId, "Private Dentist Task")

        val today = LocalDate.of(2026, 11, 19)
        insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            subjectId = almancaId,
            bookId = bookId,
            taskId = taskId,
            focusSec = 1_500L,
            isManualEntry = false,
            effectiveDate = today,
            rating = 5,
            note = "SECRET_REFLECTION_NOTE"
        )
        insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            subjectId = almancaId,
            focusSec = 900L,
            isManualEntry = true,
            effectiveDate = today,
            rating = 4,
            note = "MANUAL_SECRET_NOTE"
        )
        insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            subjectId = kisiselId,
            focusSec = 1_200L,
            isManualEntry = false,
            effectiveDate = today,
            rating = 2,
            note = "KISISEL_PRIVATE_NOTE"
        )
        insertSession(
            db = bob.db,
            ownerUserId = bob.userId,
            subjectId = almancaId,
            focusSec = 9_999L,
            isManualEntry = false,
            effectiveDate = today,
            status = FocusSessionStatus.ABANDONED
        )

        // Strict default: shareEnabled is OFF, publishing shares nothing
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
        val dailyStandings = alice.repo.observeLeaderboard(
            circleId = circle.id,
            periodType = CirclePeriodType.DAILY,
            periodStartDate = today.toString()
        ).first()

        assertEquals(1, dailyStandings.size)
        val bobStanding = dailyStandings.first()
        assertEquals("user-bob", bobStanding.userId)
        assertEquals(2_700L, bobStanding.liveTimerFocusSec)
        assertEquals(900L, bobStanding.manualEntryFocusSec)
        assertEquals(3_600L, bobStanding.totalSharedFocusSec)
        assertTrue(bobStanding.isCurrentlyFocusing)
        assertEquals(listOf(SubjectSharedTotal("Almanca B2", 2_400L)), bobStanding.subjectTotals)

        val rawServerJson = server.dumpCircleSummariesJson(circle.id)
        assertTrue(rawServerJson.contains("Almanca B2"))
        assertFalse(rawServerJson.contains("Kisisel"))
        assertFalse(rawServerJson.contains("SECRET_REFLECTION_NOTE"))
        assertFalse(rawServerJson.contains("MANUAL_SECRET_NOTE"))
        assertFalse(rawServerJson.contains("KISISEL_PRIVATE_NOTE"))
        assertFalse(rawServerJson.contains("Erkundungen B2 Secret Book"))
        assertFalse(rawServerJson.contains("Private Dentist Task"))
        assertFalse(rawServerJson.contains("reflectionNote"))
        assertFalse(rawServerJson.contains("focusRating"))

        // Turning live status OFF forces isCurrentlyFocusing=false even when active focus runs
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

        // Turning shareEnabled OFF immediately deletes Bob's summary locally & remotely
        bob.repo.updateSharingConfig(
            circleId = circle.id,
            config = CircleSharingConfig(shareEnabled = false)
        ).getOrThrow()
        assertEquals(0, server.storedSummaryCount(circle.id))
        assertTrue(bob.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, today.toString()).first().isEmpty())

        alice.repo.refreshCircle(circle.id).getOrThrow()
        assertTrue(alice.repo.observeLeaderboard(circle.id, CirclePeriodType.DAILY, today.toString()).first().isEmpty())
    }

    @Test
    fun `deterministic leaderboard ranking across multiple members and ties`() = runTest {
        val server = InMemoryCirclesServerDouble()
        val alice = buildAccount("user-alice", server) { enabledGate }
        val bob = buildAccount("user-bob", server) { enabledGate }
        val charlie = buildAccount("user-charlie", server) { enabledGate }

        val circle = alice.repo.createCircle("Study Rank", "Alice").getOrThrow()
        bob.repo.joinCircle(circle.inviteCode, "Bob").getOrThrow()
        charlie.repo.joinCircle(circle.inviteCode, "Charlie").getOrThrow()

        val today = LocalDate.of(2026, 11, 19)
        insertSession(alice.db, alice.userId, focusSec = 3_000L, isManualEntry = false, effectiveDate = today)
        insertSession(alice.db, alice.userId, focusSec = 600L, isManualEntry = true, effectiveDate = today)

        insertSession(bob.db, bob.userId, focusSec = 2_400L, isManualEntry = false, effectiveDate = today)

        insertSession(charlie.db, charlie.userId, focusSec = 1_000L, isManualEntry = false, effectiveDate = today)
        insertSession(charlie.db, charlie.userId, focusSec = 1_400L, isManualEntry = true, effectiveDate = today)

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
    fun `cloud rls gate fails closed with zero remote gateway calls when disabled`() = runTest {
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

        val aliceOffline = buildAccount("user-alice", server) { offlineGate }
        assertTrue(aliceOffline.repo.createCircle("Offline", "Alice").exceptionOrNull() is CirclesGatedException)
        assertTrue(aliceOffline.repo.joinCircle("ABCD-1234-EFGH-5678", "Alice")
            .exceptionOrNull() is CirclesGatedException)
        assertTrue(aliceOffline.repo.leaveCircle("circle-1").exceptionOrNull() is CirclesGatedException)
        assertTrue(aliceOffline.repo.updateSharingConfig("circle-1", CircleSharingConfig(shareEnabled = true))
            .exceptionOrNull() is CirclesGatedException)
        assertTrue(aliceOffline.repo.publishLocalSummarySnapshot("circle-1", true)
            .exceptionOrNull() is CirclesGatedException)
        assertTrue(aliceOffline.repo.refreshCircle("circle-1").exceptionOrNull() is CirclesGatedException)
        assertTrue(aliceOffline.repo.refreshJoinedCircles().exceptionOrNull() is CirclesGatedException)

        val aliceUnverified = buildAccount("user-alice-2", server) { unverifiedRlsGate }
        assertTrue(aliceUnverified.repo.createCircle("Unverified", "Alice")
            .exceptionOrNull() is CirclesGatedException)
        assertEquals(0, server.totalGatewayCalls())
    }

    @Test
    fun `projector handles weekly window and strict opt-in boundaries directly`() {
        val anchorDate = LocalDate.of(2026, 11, 19) // Thursday
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
            membership = testMembership(CircleSharingConfig(shareEnabled = false)),
            periodWindow = weeklyWindow,
            completedSessions = sessions,
            subjectsById = subjects,
            activeFocusRunning = true,
            nowMs = 5_000L
        )
        assertNull(disabled)

        val enabled = CircleSummaryProjector.project(
            circleId = "c-1",
            membership = testMembership(
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
        userId: String,
        server: InMemoryCirclesServerDouble,
        gateProvider: () -> CloudRlsGateStatus
    ): AccountHarness {
        val db = PekmenDatabase.createInMemory(context)
        openDatabases.add(db)
        seedBuiltInProfiles(db)

        val prefsFile = File(tempFolder.newFolder(), "$userId.preferences_pb")
        val rawStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { prefsFile }
        val dataStore = UserPreferencesDataStore(rawStore)
        dataStore.setActiveOwnerUserId(userId)

        val repo = CirclesRepositoryImpl(
            database = db,
            preferencesDataStore = dataStore,
            remoteGateway = server,
            timeProvider = fakeTime,
            gateStatusProvider = gateProvider
        )
        return AccountHarness(userId = userId, db = db, dataStore = dataStore, repo = repo)
    }

    private suspend fun seedBuiltInProfiles(db: PekmenDatabase) {
        val now = fakeTime.wallTimeMs()
        val entities = BuiltInProfiles.defaults().map { profile ->
            FocusProfileEntity(
                id = profile.id,
                builtInSlug = profile.builtInSlug.name,
                customName = profile.customName,
                colorHex = profile.colorHex,
                iconKey = profile.iconKey,
                defaultMode = profile.defaultMode.name,
                workMinutes = profile.workMinutes,
                shortBreakMinutes = profile.shortBreakMinutes,
                longBreakMinutes = profile.longBreakMinutes,
                cyclesBeforeLongBreak = profile.cyclesBeforeLongBreak,
                dndEnabled = profile.dndEnabled,
                sortOrder = profile.sortOrder,
                isArchived = profile.isArchived,
                sync = SyncMetadataEntity(createdAtMs = now, clientUpdatedAtMs = now)
            )
        }
        db.focusProfileDao().insertAllIgnore(entities)
    }

    private suspend fun insertSubject(db: PekmenDatabase, ownerUserId: String, title: String): String {
        val now = fakeTime.wallTimeMs()
        val id = UuidV7.generate(now)
        db.learningDao().upsertSubject(
            LearningSubjectEntity(
                id = id,
                title = title,
                colorHex = 0xFF8B7CF6,
                weeklyGoalMinutes = 150,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
        return id
    }

    private suspend fun insertBook(
        db: PekmenDatabase,
        ownerUserId: String,
        subjectId: String,
        title: String
    ): String {
        val now = fakeTime.wallTimeMs()
        val id = UuidV7.generate(now)
        db.learningDao().upsertBook(
            LearningBookEntity(
                id = id,
                subjectId = subjectId,
                title = title,
                totalPages = 240,
                currentPage = 40,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
        return id
    }

    private suspend fun insertTask(
        db: PekmenDatabase,
        ownerUserId: String,
        subjectId: String,
        title: String
    ): String {
        val now = fakeTime.wallTimeMs()
        val id = UuidV7.generate(now)
        db.plannerTaskDao().upsertTask(
            PlannerTaskEntity(
                id = id,
                title = title,
                subjectId = subjectId,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
        return id
    }

    private suspend fun insertSession(
        db: PekmenDatabase,
        ownerUserId: String,
        focusSec: Long,
        isManualEntry: Boolean,
        effectiveDate: LocalDate,
        subjectId: String? = null,
        bookId: String? = null,
        taskId: String? = null,
        rating: Int? = null,
        note: String? = null,
        status: FocusSessionStatus = FocusSessionStatus.COMPLETED
    ) {
        val now = fakeTime.wallTimeMs()
        db.focusSessionDao().insertSession(
            FocusSessionEntity(
                id = UuidV7.generate(now),
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                bookId = bookId,
                taskId = taskId,
                bookStartPage = if (bookId != null) 40 else null,
                bookEndPage = if (bookId != null) 52 else null,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = status.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = now - focusSec * 1_000L,
                endedAtWallMs = now,
                effectiveDayDate = effectiveDate.toString(),
                plannedFocusSec = focusSec,
                actualFocusSec = focusSec,
                actualBreakSec = 0L,
                pauseCount = 0,
                isManualEntry = isManualEntry,
                focusRating = rating,
                reflectionNote = note,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
    }

    private fun testMembership(config: CircleSharingConfig) = net.pekmen.one.core.model.CircleMembership(
        id = "m-1",
        circleId = "c-1",
        userId = "user-bob",
        displayName = "Bob",
        role = net.pekmen.one.core.model.CircleRole.MEMBER,
        sharingConfig = config,
        joinedAtMs = 1_000L,
        leftAtMs = null
    )

    private data class AccountHarness(
        val userId: String,
        val db: PekmenDatabase,
        val dataStore: UserPreferencesDataStore,
        val repo: CirclesRepositoryImpl
    )

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
    }
}
