package net.pekmen.one.core.cloud

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.cloud.auth.AuthSessionRecord
import net.pekmen.one.core.cloud.auth.CloudAccountRepositoryImpl
import net.pekmen.one.core.cloud.auth.CloudRlsGatedException
import net.pekmen.one.core.cloud.auth.KeystoreTokenVault
import net.pekmen.one.core.cloud.sync.ConflictWinner
import net.pekmen.one.core.cloud.sync.FocusProfilePayload
import net.pekmen.one.core.cloud.sync.FocusSessionPayload
import net.pekmen.one.core.cloud.sync.FocusSessionSegmentPayload
import net.pekmen.one.core.cloud.sync.InMemoryAesTokenCipher
import net.pekmen.one.core.cloud.sync.InMemoryAuthBackendGateway
import net.pekmen.one.core.cloud.sync.InMemoryRlsServerTestDouble
import net.pekmen.one.core.cloud.sync.LearningBookPayload
import net.pekmen.one.core.cloud.sync.LearningSubjectPayload
import net.pekmen.one.core.cloud.sync.LearningTopicPayload
import net.pekmen.one.core.cloud.sync.MutableTestTimeProvider
import net.pekmen.one.core.cloud.sync.NoOpSyncWorkScheduler
import net.pekmen.one.core.cloud.sync.OutboxSyncEngine
import net.pekmen.one.core.cloud.sync.PlannerTaskPayload
import net.pekmen.one.core.cloud.sync.RlsAuthorizationException
import net.pekmen.one.core.cloud.sync.SyncConflictCandidate
import net.pekmen.one.core.cloud.sync.SyncConflictResolver
import net.pekmen.one.core.cloud.sync.SyncEntityPayload
import net.pekmen.one.core.cloud.sync.SyncEntityTable
import net.pekmen.one.core.cloud.sync.SyncMetadataPayload
import net.pekmen.one.core.cloud.sync.SyncMutationItem
import net.pekmen.one.core.cloud.sync.SyncOpType
import net.pekmen.one.core.cloud.sync.SyncPayloadJson
import net.pekmen.one.core.cloud.sync.SyncPullRequest
import net.pekmen.one.core.cloud.sync.SyncPushRequest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.AccountState
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.CloudSyncPhase
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.LogoutCheckResult
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.model.UuidV7
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
class CloudAuthRlsAndSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var timeProvider: MutableTestTimeProvider
    private lateinit var authGateway: InMemoryAuthBackendGateway
    private lateinit var server: InMemoryRlsServerTestDouble
    private val openDatabases = mutableListOf<PekmenDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val baseWallMs = LocalDateTime.of(2026, 11, 19, 10, 0, 0)
            .atZone(ZoneId.of("Europe/Istanbul"))
            .toInstant()
            .toEpochMilli()
        timeProvider = MutableTestTimeProvider(wallMs = baseWallMs)
        authGateway = InMemoryAuthBackendGateway(timeProvider)
        server = InMemoryRlsServerTestDouble(authGateway::resolveUserIdForToken)
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    @Test
    fun `default gate provider fails closed and blocks live auth and sync`() = runTest {
        val defaultGate = DefaultCloudRlsGateProvider()
        assertFalse(defaultGate.currentGateStatus().isLiveCloudEnabled)

        val client = createClient(this, rlsGateProvider = defaultGate)
        authGateway.registerAccount(ALICE_EMAIL, ALICE_ID, "Alice", ALICE_OTP)

        assertEquals(AccountState.Guest, client.repo.accountState.first())
        assertEquals(CloudSyncPhase.OFFLINE_GUEST, client.repo.syncState.first().phase)

        val otpReq = client.repo.requestOtpSignIn(ALICE_EMAIL)
        assertTrue(otpReq.exceptionOrNull() is CloudRlsGatedException)

        val otpVerify = client.repo.verifyOtpSignIn(ALICE_EMAIL, ALICE_OTP)
        assertTrue(otpVerify.exceptionOrNull() is CloudRlsGatedException)

        val syncResult = client.repo.triggerSyncNow()
        assertTrue(syncResult.exceptionOrNull() is CloudRlsGatedException)

        var gateOpen = true
        val toggleGate = CloudRlsGateProvider {
            DefaultCloudRlsGateProvider(
                compiledWithCloudFlag = true,
                backendEndpointConfigured = true,
                rlsVerified = gateOpen
            ).currentGateStatus()
        }
        val gatedClient = createClient(this, rlsGateProvider = toggleGate)
        gatedClient.repo.requestOtpSignIn(ALICE_EMAIL).getOrThrow()
        gatedClient.repo.verifyOtpSignIn(ALICE_EMAIL, ALICE_OTP).getOrThrow()
        gateOpen = false

        assertEquals(CloudSyncPhase.GATED_CLOUD_UNVERIFIED, gatedClient.repo.syncState.first().phase)
        assertTrue(gatedClient.repo.triggerSyncNow().exceptionOrNull() is CloudRlsGatedException)
    }

    @Test
    fun `keystore token vault encrypts credentials and keeps Room and DataStore token free`() = runTest {
        val vaultFile = File(tempFolder.newFolder(), "vault.enc")
        val cipher = InMemoryAesTokenCipher()
        val vault = KeystoreTokenVault(vaultFile, cipher)

        val record = AuthSessionRecord(
            userId = ALICE_ID,
            email = ALICE_EMAIL,
            displayName = "Alice",
            accessToken = "pekmen-test-session-alice-plain-check",
            refreshToken = "pekmen-test-refresh-alice-plain-check",
            expiresAtWallMs = timeProvider.wallTimeMs() + 3_600_000L
        )
        vault.saveSession(record)

        val rawText = vaultFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(rawText.contains("pekmen-test-session-alice-plain-check"))
        assertFalse(rawText.contains("pekmen-test-refresh-alice-plain-check"))
        assertFalse(rawText.contains(ALICE_EMAIL))
        assertFalse(record.toString().contains("pekmen-test-session-alice-plain-check"))
        assertEquals(record, vault.readSession())

        vaultFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertNull(vault.readSession())
        assertFalse(vaultFile.exists())
    }

    @Test
    fun `guest data requires explicit claim then tags owner and pushes via outbox`() = runTest {
        authGateway.registerAccount(ALICE_EMAIL, ALICE_ID, "Alice", ALICE_OTP)
        val client = createClient(this)
        val now = timeProvider.wallTimeMs()

        insertGuestFixtures(client.db, now)
        assertEquals(6, client.repo.countUnclaimedGuestRecords())

        client.repo.requestOtpSignIn(ALICE_EMAIL).getOrThrow()
        val signedIn = client.repo.verifyOtpSignIn(ALICE_EMAIL, ALICE_OTP).getOrThrow()
        assertEquals(ALICE_ID, signedIn.userId)
        assertEquals(ALICE_ID, client.prefs.preferences.first().activeOwnerUserId)

        // Before explicit claim, Alice sees zero guest rows.
        assertEquals(0, client.db.learningDao().observeActiveSubjectsByOwner(ALICE_ID).first().size)
        assertEquals(0, client.db.plannerTaskDao().observeTasksByOwner(ALICE_ID).first().size)

        val claimed = client.repo.claimGuestDataToSignedInUser().getOrThrow()
        assertEquals(6, claimed)
        assertEquals(0, client.repo.countUnclaimedGuestRecords())
        assertEquals(6, client.db.syncOutboxDao().countPendingForOwner(ALICE_ID))
        assertEquals(1, client.db.learningDao().observeActiveSubjectsByOwner(ALICE_ID).first().size)
        assertEquals(1, client.db.plannerTaskDao().observeTasksByOwner(ALICE_ID).first().size)

        val synced = client.repo.triggerSyncNow().getOrThrow()
        assertEquals(6, synced)
        assertEquals(0, client.db.syncOutboxDao().countPendingForOwner(ALICE_ID))
        assertEquals(6, server.statsForUser(ALICE_ID).totalRows)
    }

    @Test
    fun `user A vs user B strict RLS rejection and local Room isolation across switches`() = runTest {
        authGateway.registerAccount(ALICE_EMAIL, ALICE_ID, "Alice", ALICE_OTP)
        authGateway.registerAccount(BOB_EMAIL, BOB_ID, "Bob", BOB_OTP)
        val client = createClient(this)
        val now = timeProvider.wallTimeMs()

        // Alice signs in, creates & syncs a subject, session, and task
        signInClient(client, ALICE_EMAIL, ALICE_OTP)
        val aliceSubjectId = UuidV7.generate(now)
        val aliceSessionId = UuidV7.generate(now + 1L)
        val aliceTaskId = UuidV7.generate(now + 2L)
        insertOwnedSubject(client.db, aliceSubjectId, ALICE_ID, "Alice German B2", now)
        insertOwnedSession(client.db, aliceSessionId, ALICE_ID, aliceSubjectId, now)
        insertOwnedTask(client.db, aliceTaskId, ALICE_ID, "Alice Task", now)
        client.repo.triggerSyncNow().getOrThrow()

        val aliceToken = client.vault.readSession()!!.accessToken
        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout())

        // Guest mode must NOT see Alice's rows
        assertEquals(0, client.db.learningDao().observeActiveSubjectsByOwner(null).first().size)
        assertEquals(0, client.db.plannerTaskDao().observeTasksByOwner(null).first().size)
        assertEquals(
            0,
            client.db.accountSessionDao().observeRecentCompletedSessionsByOwner(20, null).first().size
        )

        // Bob signs in on the SAME local Room DB
        signInClient(client, BOB_EMAIL, BOB_OTP)
        val bobToken = client.vault.readSession()!!.accessToken
        assertEquals(0, client.db.learningDao().observeActiveSubjectsByOwner(BOB_ID).first().size)
        assertEquals(0, client.db.plannerTaskDao().observeTasksByOwner(BOB_ID).first().size)

        // Cross-tenant RLS rejections on server (READ, WRITE, DELETE denied)
        assertCrossTenantRlsDenied(bobToken, aliceSubjectId, now)

        // Alice's row on server remains intact
        val aliceServerSubject = server.readRowAs(aliceToken, SyncEntityTable.LEARNING_SUBJECTS, aliceSubjectId)
            .getOrThrow() as LearningSubjectPayload
        assertEquals("Alice German B2", aliceServerSubject.title)
        assertNull(aliceServerSubject.sync.deletedAtMs)

        // Bob creates his own data; Alice's remains isolated
        val bobSubjectId = UuidV7.generate(now + 30L)
        insertOwnedSubject(client.db, bobSubjectId, BOB_ID, "Bob Physics", now + 30L)
        client.repo.triggerSyncNow().getOrThrow()
        assertEquals(1, client.db.learningDao().observeActiveSubjectsByOwner(BOB_ID).first().size)
        assertEquals("Bob Physics", client.db.learningDao().observeActiveSubjectsByOwner(BOB_ID).first().single().title)

        // Switch back to Alice -> sees only Alice's data
        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout())
        signInClient(client, ALICE_EMAIL, ALICE_OTP)
        val aliceSubjects = client.db.learningDao().observeActiveSubjectsByOwner(ALICE_ID).first()
        assertEquals(listOf("Alice German B2"), aliceSubjects.map { it.title })
    }

    private suspend fun assertCrossTenantRlsDenied(
        bobToken: String,
        aliceSubjectId: String,
        nowMs: Long
    ) {
        assertTrue(server.readRowAs(bobToken, SyncEntityTable.LEARNING_SUBJECTS, aliceSubjectId).isFailure)
        val bobPull = server.pullDeltas(
            bobToken,
            SyncPullRequest(SyncEntityTable.LEARNING_SUBJECTS.tableName, 0L, "")
        ).getOrThrow()
        assertTrue(bobPull.records.isEmpty())

        val spoofedWithAliceOwner = LearningSubjectPayload(
            id = aliceSubjectId,
            title = "Tampered",
            colorHex = 0xFF0000L,
            weeklyGoalMinutes = 10,
            sortOrder = 0,
            isArchived = false,
            sync = SyncMetadataPayload(ALICE_ID, nowMs, nowMs + 100L, version = 2L)
        )
        assertTrue(
            pushSingleAs(bobToken, spoofedWithAliceOwner, UuidV7.generate(nowMs + 10L))
                .exceptionOrNull() is RlsAuthorizationException
        )

        val spoofedWithBobOwnerSameId = spoofedWithAliceOwner.copy(
            sync = spoofedWithAliceOwner.sync.copy(ownerUserId = BOB_ID)
        )
        assertTrue(
            pushSingleAs(bobToken, spoofedWithBobOwnerSameId, UuidV7.generate(nowMs + 11L))
                .exceptionOrNull() is RlsAuthorizationException
        )

        assertTrue(
            server.deleteRowAs(
                bobToken,
                SyncEntityTable.LEARNING_SUBJECTS,
                aliceSubjectId,
                nowMs + 20L,
                UuidV7.generate(nowMs + 20L)
            ).exceptionOrNull() is RlsAuthorizationException
        )
    }

    @Test
    fun `50 mixed writes handle mid batch network failure and retry idempotently without duplicates`() = runTest {
        authGateway.registerAccount(ALICE_EMAIL, ALICE_ID, "Alice", ALICE_OTP)
        val client1 = createClient(this, pushBatchSize = 20)
        signInClient(client1, ALICE_EMAIL, ALICE_OTP)
        val baseMs = timeProvider.wallTimeMs()

        seedFiftyMixedMutations(client1.db, ALICE_ID, baseMs)
        assertEquals(50, client1.db.syncOutboxDao().countPendingForOwner(ALICE_ID))

        // Batch 1 (20 items) succeeds; Batch 2 commits first 7 items on server then drops network!
        server.failOnPushCallNumber = 2
        server.failMidBatchAfterAcceptedCount = 7

        val firstSync = client1.repo.triggerSyncNow()
        assertTrue(firstSync.isFailure)
        assertEquals(CloudSyncPhase.ERROR, client1.repo.syncState.first().phase)
        assertEquals(30, client1.db.syncOutboxDao().countPendingForOwner(ALICE_ID))
        assertEquals(27, server.statsForUser(ALICE_ID).totalRows)

        // Clear fault and retry: the 7 already-committed items deduplicate via mutation_id ledger!
        server.failOnPushCallNumber = null
        server.failMidBatchAfterAcceptedCount = null
        client1.repo.triggerSyncNow().getOrThrow()

        assertEquals(0, client1.db.syncOutboxDao().countPendingForOwner(ALICE_ID))
        assertEquals(CloudSyncPhase.IDLE, client1.repo.syncState.first().phase)
        val stats = server.statsForUser(ALICE_ID)
        assertEquals(50, stats.totalRows)
        assertEquals(42, stats.activeRows)
        assertEquals(50, stats.ledgerSize)

        // Fresh Client 2 pulls all 50 records via cursor delta sync
        val client2 = createClient(this, pushBatchSize = 20)
        signInClient(client2, ALICE_EMAIL, ALICE_OTP)
        val pulled = client2.repo.triggerSyncNow().getOrThrow()
        assertEquals(50, pulled)
        assertEquals(8, client2.db.learningDao().observeActiveSubjectsByOwner(ALICE_ID).first().size)
        assertEquals(8, client2.db.learningDao().observeActiveBooksByOwner(ALICE_ID).first().size)
        assertEquals(8, client2.db.learningTopicDao().observeActiveTopicsByOwner(ALICE_ID).first().size)
        assertEquals(7, client2.db.plannerTaskDao().observeTasksByOwner(ALICE_ID).first().size)
        assertEquals(
            7,
            client2.db.accountSessionDao().observeRecentCompletedSessionsByOwner(20, ALICE_ID).first().size
        )

        // Second sync is a clean idempotent no-op
        assertEquals(0, client2.repo.triggerSyncNow().getOrThrow())
    }

    @Test
    fun `two client conflicts converge and delete tombstones are never resurrected by older updates`() = runTest {
        // Direct unit check on SyncConflictResolver tie-break rules
        assertEquals(
            ConflictWinner.REMOTE,
            SyncConflictResolver.resolve(
                SyncConflictCandidate(deletedAtMs = null, clientUpdatedAtMs = 500L, version = 1L, mutationId = "a"),
                SyncConflictCandidate(deletedAtMs = null, clientUpdatedAtMs = 500L, version = 2L, mutationId = "a")
            )
        )
        assertEquals(
            ConflictWinner.REMOTE,
            SyncConflictResolver.resolve(
                SyncConflictCandidate(deletedAtMs = null, clientUpdatedAtMs = 500L, version = 2L, mutationId = "m1"),
                SyncConflictCandidate(deletedAtMs = null, clientUpdatedAtMs = 500L, version = 2L, mutationId = "m2")
            )
        )

        authGateway.registerAccount(ALICE_EMAIL, ALICE_ID, "Alice", ALICE_OTP)
        val client1 = createClient(this)
        val client2 = createClient(this)
        signInClient(client1, ALICE_EMAIL, ALICE_OTP)
        signInClient(client2, ALICE_EMAIL, ALICE_OTP)

        val t0 = timeProvider.wallTimeMs()
        val taskId = UuidV7.generate(t0)
        val subjectId = UuidV7.generate(t0 + 1L)
        insertOwnedSubject(client1.db, subjectId, ALICE_ID, "Initial Subject", t0)
        insertOwnedTask(client1.db, taskId, ALICE_ID, "Initial Task", t0)
        client1.repo.triggerSyncNow().getOrThrow()
        client2.repo.triggerSyncNow().getOrThrow()

        // Client 2 edits task at t0 + 5_000; Client 1 edits task at t0 + 1_000 (older)
        updateOwnedTaskTitle(client2.db, taskId, ALICE_ID, "Client 2 Newer Title", t0 + 5_000L, version = 2L)
        updateOwnedTaskTitle(client1.db, taskId, ALICE_ID, "Client 1 Older Title", t0 + 1_000L, version = 2L)

        client2.repo.triggerSyncNow().getOrThrow()
        client1.repo.triggerSyncNow().getOrThrow()
        client2.repo.triggerSyncNow().getOrThrow()

        assertEquals(
            "Client 2 Newer Title",
            client1.db.plannerTaskDao().getTaskById(taskId)?.title
        )
        assertEquals(
            "Client 2 Newer Title",
            client2.db.plannerTaskDao().getTaskById(taskId)?.title
        )

        // Delete tombstone vs newer non-delete update: tombstone must NEVER be resurrected!
        updateOwnedSubjectTitle(client2.db, subjectId, ALICE_ID, "Resurrection Update", t0 + 9_000L, version = 2L)
        softDeleteOwnedSubject(client1.db, subjectId, ALICE_ID, deletedAtMs = t0 + 4_000L, version = 2L)

        // Even when Client 2 pushes its newer update FIRST, Client 1's tombstone wins & propagates back
        client2.repo.triggerSyncNow().getOrThrow()
        client1.repo.triggerSyncNow().getOrThrow()
        client2.repo.triggerSyncNow().getOrThrow()

        assertNull(client1.db.learningDao().getSubjectById(subjectId))
        assertNull(client2.db.learningDao().getSubjectById(subjectId))
        val aliceToken = client1.vault.readSession()!!.accessToken
        val serverSubject = server.readRowAs(aliceToken, SyncEntityTable.LEARNING_SUBJECTS, subjectId).getOrThrow()
        assertNotNull(serverSubject.sync.deletedAtMs)
    }

    @Test
    fun `logout blocks when outbox has pending mutations and preserves them until synced or discarded`() = runTest {
        authGateway.registerAccount(ALICE_EMAIL, ALICE_ID, "Alice", ALICE_OTP)
        val client = createClient(this)
        signInClient(client, ALICE_EMAIL, ALICE_OTP)
        val now = timeProvider.wallTimeMs()

        val subjectId = UuidV7.generate(now)
        insertOwnedSubject(client.db, subjectId, ALICE_ID, "Unsynced Subject", now)
        assertEquals(1, client.db.syncOutboxDao().countPendingForOwner(ALICE_ID))

        val blocked = client.repo.attemptLogout(forceDiscardPending = false)
        assertEquals(LogoutCheckResult.BlockedByPendingMutations(1), blocked)
        assertTrue(client.repo.accountState.first() is AccountState.SignedIn)
        assertNotNull(client.vault.readSession())
        assertEquals(1, client.db.syncOutboxDao().countPendingForOwner(ALICE_ID))

        // Sync drains outbox -> normal logout succeeds
        client.repo.triggerSyncNow().getOrThrow()
        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout(forceDiscardPending = false))
        assertEquals(AccountState.Guest, client.repo.accountState.first())
        assertNull(client.vault.readSession())

        // Force discard path deletes pending outbox for that user and signs out cleanly
        signInClient(client, ALICE_EMAIL, ALICE_OTP)
        insertOwnedTask(client.db, UuidV7.generate(now + 50L), ALICE_ID, "Discardable Task", now + 50L)
        assertEquals(1, client.db.syncOutboxDao().countPendingForOwner(ALICE_ID))
        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout(forceDiscardPending = true))
        assertEquals(0, client.db.syncOutboxDao().countPendingForOwner(ALICE_ID))
    }

    private data class ClientHarness(
        val db: PekmenDatabase,
        val prefs: UserPreferencesDataStore,
        val vault: KeystoreTokenVault,
        val repo: CloudAccountRepositoryImpl
    )

    private suspend fun createClient(
        scope: TestScope,
        rlsGateProvider: CloudRlsGateProvider = DefaultCloudRlsGateProvider(
            compiledWithCloudFlag = true,
            backendEndpointConfigured = true,
            rlsVerified = true
        ),
        pushBatchSize: Int = OutboxSyncEngine.DEFAULT_PUSH_BATCH_SIZE
    ): ClientHarness {
        val db = PekmenDatabase.createInMemory(context)
        openDatabases += db
        seedBuiltInProfiles(db, timeProvider.wallTimeMs())
        val dir = tempFolder.newFolder()
        val rawStore = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) {
            File(dir, "prefs.preferences_pb")
        }
        val prefs = UserPreferencesDataStore(rawStore)
        val vault = KeystoreTokenVault(File(dir, "auth_vault.enc"), InMemoryAesTokenCipher())
        val syncEngine = OutboxSyncEngine(
            database = db,
            remoteGateway = server,
            rlsGateProvider = rlsGateProvider,
            timeProvider = timeProvider,
            pushBatchSize = pushBatchSize,
            pullPageSize = 20
        )
        val repo = CloudAccountRepositoryImpl(
            database = db,
            preferencesDataStore = prefs,
            tokenVault = vault,
            authGateway = authGateway,
            syncEngine = syncEngine,
            syncWorkScheduler = NoOpSyncWorkScheduler(),
            rlsGateProvider = rlsGateProvider,
            timeProvider = timeProvider
        )
        return ClientHarness(db, prefs, vault, repo)
    }

    private suspend fun signInClient(client: ClientHarness, email: String, otp: String) {
        client.repo.requestOtpSignIn(email).getOrThrow()
        client.repo.verifyOtpSignIn(email, otp).getOrThrow()
    }

    private suspend fun seedBuiltInProfiles(db: PekmenDatabase, nowMs: Long) {
        val seeds = BuiltInProfiles.defaults().map { profile ->
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
                isArchived = false,
                sync = SyncMetadataEntity(createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        }
        db.focusProfileDao().insertAllIgnore(seeds)
    }

    private suspend fun insertGuestFixtures(db: PekmenDatabase, nowMs: Long) {
        val profileId = UuidV7.generate(nowMs)
        val subjectId = UuidV7.generate(nowMs + 1L)
        val bookId = UuidV7.generate(nowMs + 2L)
        val topicId = UuidV7.generate(nowMs + 3L)
        val sessionId = UuidV7.generate(nowMs + 4L)
        val taskId = UuidV7.generate(nowMs + 5L)
        db.withTransaction {
            insertGuestProfileAndLearning(db, profileId, subjectId, bookId, topicId, nowMs)
            insertGuestSessionAndTask(db, subjectId, bookId, topicId, sessionId, taskId, nowMs)
        }
    }

    private suspend fun insertGuestProfileAndLearning(
        db: PekmenDatabase,
        profileId: String,
        subjectId: String,
        bookId: String,
        topicId: String,
        nowMs: Long
    ) {
        db.focusProfileDao().insert(
            FocusProfileEntity(
                id = profileId,
                builtInSlug = BuiltInProfileSlug.CUSTOM.name,
                customName = "Deep Reading",
                colorHex = 0xFF8B7CF6,
                iconKey = "custom",
                defaultMode = FocusSessionMode.CUSTOM.name,
                workMinutes = 40,
                shortBreakMinutes = 5,
                longBreakMinutes = 15,
                cyclesBeforeLongBreak = 3,
                dndEnabled = false,
                sortOrder = 4,
                isArchived = false,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.learningDao().upsertSubject(
            LearningSubjectEntity(
                id = subjectId,
                title = "Guest Subject",
                colorHex = 0xFF60A5FA,
                weeklyGoalMinutes = 120,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.learningDao().upsertBook(
            LearningBookEntity(
                id = bookId,
                subjectId = subjectId,
                title = "Guest Book",
                totalPages = 150,
                currentPage = 20,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.learningTopicDao().upsertTopic(
            LearningTopicEntity(
                id = topicId,
                subjectId = subjectId,
                bookId = bookId,
                title = "Guest Topic",
                status = TopicStatus.IN_PROGRESS.name,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
    }

    private suspend fun insertGuestSessionAndTask(
        db: PekmenDatabase,
        subjectId: String,
        bookId: String,
        topicId: String,
        sessionId: String,
        taskId: String,
        nowMs: Long
    ) {
        db.focusSessionDao().insertSession(
            FocusSessionEntity(
                id = sessionId,
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                bookId = bookId,
                topicId = topicId,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = FocusSessionStatus.COMPLETED.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = nowMs - 1_500_000L,
                endedAtWallMs = nowMs,
                effectiveDayDate = LocalDate.of(2026, 11, 19).toString(),
                plannedFocusSec = 1_500L,
                actualFocusSec = 1_500L,
                actualBreakSec = 0L,
                pauseCount = 0,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
        db.plannerTaskDao().upsertTask(
            PlannerTaskEntity(
                id = taskId,
                title = "Guest Task",
                subjectId = subjectId,
                recurrenceRule = TaskRecurrenceRule.NONE.name,
                status = TaskStatus.OPEN.name,
                sync = SyncMetadataEntity(ownerUserId = null, createdAtMs = nowMs, clientUpdatedAtMs = nowMs)
            )
        )
    }

    private suspend fun seedFiftyMixedMutations(db: PekmenDatabase, ownerId: String, baseMs: Long) {
        var seq = 0L
        fun nextTs(): Long {
            seq += 1L
            return baseMs + seq
        }

        val subjectIds = (0 until 10).map { UuidV7.generate(nextTs()) }
        db.withTransaction {
            seedMixedProfiles(db, ownerId, ::nextTs)
            seedMixedSubjects(db, ownerId, subjectIds, ::nextTs)
            seedMixedBooks(db, ownerId, subjectIds, ::nextTs)
            seedMixedTopics(db, ownerId, subjectIds, ::nextTs)
            seedMixedSessions(db, ownerId, subjectIds, ::nextTs)
            seedMixedTasks(db, ownerId, subjectIds, ::nextTs)
        }
    }

    private suspend fun seedMixedProfiles(db: PekmenDatabase, ownerId: String, nextTs: () -> Long) {
        repeat(4) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx == 3) ts + 5L else null
            val entity = FocusProfileEntity(
                id = UuidV7.generate(ts),
                builtInSlug = BuiltInProfileSlug.CUSTOM.name,
                customName = "Custom $idx",
                colorHex = 0xFF8B7CF6,
                iconKey = "custom",
                defaultMode = FocusSessionMode.POMODORO.name,
                workMinutes = 25 + idx,
                shortBreakMinutes = 5,
                longBreakMinutes = 15,
                cyclesBeforeLongBreak = 4,
                dndEnabled = false,
                sortOrder = 10 + idx,
                isArchived = false,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.focusProfileDao().insert(entity)
            val payload = FocusProfilePayload(
                id = entity.id,
                builtInSlug = entity.builtInSlug,
                customName = entity.customName,
                colorHex = entity.colorHex,
                iconKey = entity.iconKey,
                defaultMode = entity.defaultMode,
                workMinutes = entity.workMinutes,
                shortBreakMinutes = entity.shortBreakMinutes,
                longBreakMinutes = entity.longBreakMinutes,
                cyclesBeforeLongBreak = entity.cyclesBeforeLongBreak,
                dndEnabled = entity.dndEnabled,
                sortOrder = entity.sortOrder,
                isArchived = entity.isArchived,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedSubjects(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        subjectIds.forEachIndexed { idx, id ->
            val ts = nextTs()
            val deletedAt = if (idx >= 8) ts + 5L else null
            val entity = LearningSubjectEntity(
                id = id,
                title = "Subject $idx",
                colorHex = 0xFF60A5FA,
                weeklyGoalMinutes = 120 + idx * 10,
                sortOrder = idx,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.learningDao().upsertSubject(entity)
            val payload = LearningSubjectPayload(
                id = id,
                title = entity.title,
                colorHex = entity.colorHex,
                weeklyGoalMinutes = entity.weeklyGoalMinutes,
                sortOrder = entity.sortOrder,
                isArchived = entity.isArchived,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedBooks(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(10) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx >= 8) ts + 5L else null
            val subjectId = subjectIds[idx % 8]
            val entity = LearningBookEntity(
                id = UuidV7.generate(ts),
                subjectId = subjectId,
                title = "Book $idx",
                totalPages = 200,
                currentPage = idx * 10,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.learningDao().upsertBook(entity)
            val payload = LearningBookPayload(
                id = entity.id,
                subjectId = subjectId,
                title = entity.title,
                author = null,
                totalPages = entity.totalPages,
                currentPage = entity.currentPage,
                totalChapters = 0,
                completedChapters = 0,
                isCompleted = false,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedTopics(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(10) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx >= 8) ts + 5L else null
            val subjectId = subjectIds[idx % 8]
            val entity = LearningTopicEntity(
                id = UuidV7.generate(ts),
                subjectId = subjectId,
                title = "Topic $idx",
                status = TopicStatus.TODO.name,
                sortOrder = idx,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.learningTopicDao().upsertTopic(entity)
            val payload = LearningTopicPayload(
                id = entity.id,
                subjectId = subjectId,
                bookId = null,
                title = entity.title,
                status = entity.status,
                sortOrder = entity.sortOrder,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private suspend fun seedMixedSessions(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(8) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx == 7) ts + 5L else null
            val subjectId = subjectIds[idx]
            val sessionId = UuidV7.generate(ts)
            val segId = UuidV7.generate(ts + 100L)
            val session = FocusSessionEntity(
                id = sessionId,
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = FocusSessionStatus.COMPLETED.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = ts - 1_500_000L,
                endedAtWallMs = ts,
                effectiveDayDate = LocalDate.of(2026, 11, 19).toString(),
                plannedFocusSec = 1_500L,
                actualFocusSec = 1_500L,
                actualBreakSec = 0L,
                pauseCount = 0,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            val seg = FocusSessionSegmentEntity(
                id = segId,
                sessionId = sessionId,
                segmentIndex = 0,
                phase = FocusPhase.FOCUS.name,
                startedAtWallMs = ts - 1_500_000L,
                startedAtElapsedMs = 10_000L,
                bootCount = 1,
                lastCheckpointedDurationMs = 1_500_000L,
                plannedDurationMs = 1_500_000L,
                endedAtWallMs = ts,
                durationMs = 1_500_000L
            )
            db.focusSessionDao().insertSession(session)
            db.focusSessionDao().insertSegment(seg)
            enqueuePayload(db, session.toPayload(seg, ownerId, ts, deletedAt), ownerId, ts)
        }
    }

    private suspend fun seedMixedTasks(
        db: PekmenDatabase,
        ownerId: String,
        subjectIds: List<String>,
        nextTs: () -> Long
    ) {
        repeat(8) { idx ->
            val ts = nextTs()
            val deletedAt = if (idx == 7) ts + 5L else null
            val entity = PlannerTaskEntity(
                id = UuidV7.generate(ts),
                title = "Task $idx",
                subjectId = subjectIds[idx],
                recurrenceRule = TaskRecurrenceRule.NONE.name,
                status = TaskStatus.OPEN.name,
                sync = SyncMetadataEntity(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            db.plannerTaskDao().upsertTask(entity)
            val payload = PlannerTaskPayload(
                id = entity.id,
                title = entity.title,
                subjectId = entity.subjectId,
                recurrenceRule = entity.recurrenceRule,
                status = entity.status,
                priority = entity.priority,
                sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
            )
            enqueuePayload(db, payload, ownerId, ts)
        }
    }

    private fun FocusSessionEntity.toPayload(
        seg: FocusSessionSegmentEntity,
        ownerId: String,
        ts: Long,
        deletedAt: Long?
    ): FocusSessionPayload = FocusSessionPayload(
        id = id,
        profileId = profileId,
        subjectId = subjectId,
        sessionMode = sessionMode,
        status = status,
        currentPhase = currentPhase,
        completedFocusCycles = completedFocusCycles,
        startedAtWallMs = startedAtWallMs,
        endedAtWallMs = endedAtWallMs,
        effectiveDayDate = effectiveDayDate,
        plannedFocusSec = plannedFocusSec,
        actualFocusSec = actualFocusSec,
        actualBreakSec = actualBreakSec,
        pauseCount = pauseCount,
        isManualEntry = false,
        wasManuallyAdjusted = false,
        interruptedByReboot = false,
        segments = listOf(
            FocusSessionSegmentPayload(
                id = seg.id,
                sessionId = seg.sessionId,
                segmentIndex = seg.segmentIndex,
                phase = seg.phase,
                startedAtWallMs = seg.startedAtWallMs,
                startedAtElapsedMs = seg.startedAtElapsedMs,
                bootCount = seg.bootCount,
                lastCheckpointedDurationMs = seg.lastCheckpointedDurationMs,
                plannedDurationMs = seg.plannedDurationMs,
                endedAtWallMs = seg.endedAtWallMs,
                durationMs = seg.durationMs
            )
        ),
        sync = SyncMetadataPayload(ownerId, ts, ts, deletedAtMs = deletedAt)
    )

    private suspend fun insertOwnedSubject(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        nowMs: Long
    ) {
        val entity = LearningSubjectEntity(
            id = id,
            title = title,
            colorHex = 0xFF8B7CF6,
            weeklyGoalMinutes = 150,
            sync = SyncMetadataEntity(ownerId, nowMs, nowMs, syncStatus = SyncStatus.PENDING_UPLOAD.name)
        )
        db.learningDao().upsertSubject(entity)
        enqueuePayload(
            db,
            LearningSubjectPayload(
                id = id,
                title = title,
                colorHex = entity.colorHex,
                weeklyGoalMinutes = entity.weeklyGoalMinutes,
                sortOrder = 0,
                isArchived = false,
                sync = SyncMetadataPayload(ownerId, nowMs, nowMs)
            ),
            ownerId,
            nowMs
        )
    }

    private suspend fun updateOwnedSubjectTitle(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        updatedAtMs: Long,
        version: Long
    ) {
        val existing = requireNotNull(db.learningDao().getSubjectById(id))
        val updated = existing.copy(
            title = title,
            sync = existing.sync.copy(
                clientUpdatedAtMs = updatedAtMs,
                version = version,
                syncStatus = SyncStatus.PENDING_UPLOAD.name
            )
        )
        db.learningDao().updateSubject(updated)
        enqueuePayload(
            db,
            LearningSubjectPayload(
                id = id,
                title = title,
                colorHex = updated.colorHex,
                weeklyGoalMinutes = updated.weeklyGoalMinutes,
                sortOrder = updated.sortOrder,
                isArchived = updated.isArchived,
                sync = SyncMetadataPayload(ownerId, updated.sync.createdAtMs, updatedAtMs, version = version)
            ),
            ownerId,
            updatedAtMs
        )
    }

    private suspend fun softDeleteOwnedSubject(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        deletedAtMs: Long,
        version: Long
    ) {
        val existing = requireNotNull(db.learningDao().getSubjectById(id))
        db.learningDao().softDeleteSubject(id, deletedAtMs = deletedAtMs, updatedAtMs = deletedAtMs)
        enqueuePayload(
            db,
            LearningSubjectPayload(
                id = id,
                title = existing.title,
                colorHex = existing.colorHex,
                weeklyGoalMinutes = existing.weeklyGoalMinutes,
                sortOrder = existing.sortOrder,
                isArchived = existing.isArchived,
                sync = SyncMetadataPayload(
                    ownerUserId = ownerId,
                    createdAtMs = existing.sync.createdAtMs,
                    clientUpdatedAtMs = deletedAtMs,
                    deletedAtMs = deletedAtMs,
                    version = version
                )
            ),
            ownerId,
            deletedAtMs
        )
    }

    private suspend fun insertOwnedSession(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        subjectId: String,
        nowMs: Long
    ) {
        val entity = FocusSessionEntity(
            id = id,
            profileId = BuiltInProfiles.STUDY_ID,
            subjectId = subjectId,
            sessionMode = FocusSessionMode.POMODORO.name,
            status = FocusSessionStatus.COMPLETED.name,
            currentPhase = FocusPhase.FOCUS.name,
            completedFocusCycles = 1,
            startedAtWallMs = nowMs - 1_500_000L,
            endedAtWallMs = nowMs,
            effectiveDayDate = LocalDate.of(2026, 11, 19).toString(),
            plannedFocusSec = 1_500L,
            actualFocusSec = 1_500L,
            actualBreakSec = 0L,
            pauseCount = 0,
            sync = SyncMetadataEntity(ownerId, nowMs, nowMs, syncStatus = SyncStatus.PENDING_UPLOAD.name)
        )
        db.focusSessionDao().insertSession(entity)
        enqueuePayload(
            db,
            FocusSessionPayload(
                id = id,
                profileId = entity.profileId,
                subjectId = subjectId,
                sessionMode = entity.sessionMode,
                status = entity.status,
                currentPhase = entity.currentPhase,
                completedFocusCycles = entity.completedFocusCycles,
                startedAtWallMs = entity.startedAtWallMs,
                endedAtWallMs = entity.endedAtWallMs,
                effectiveDayDate = entity.effectiveDayDate,
                plannedFocusSec = entity.plannedFocusSec,
                actualFocusSec = entity.actualFocusSec,
                actualBreakSec = entity.actualBreakSec,
                pauseCount = entity.pauseCount,
                isManualEntry = false,
                wasManuallyAdjusted = false,
                interruptedByReboot = false,
                sync = SyncMetadataPayload(ownerId, nowMs, nowMs)
            ),
            ownerId,
            nowMs
        )
    }

    private suspend fun insertOwnedTask(db: PekmenDatabase, id: String, ownerId: String, title: String, nowMs: Long) {
        val entity = PlannerTaskEntity(
            id = id,
            title = title,
            recurrenceRule = TaskRecurrenceRule.NONE.name,
            status = TaskStatus.OPEN.name,
            sync = SyncMetadataEntity(ownerId, nowMs, nowMs, syncStatus = SyncStatus.PENDING_UPLOAD.name)
        )
        db.plannerTaskDao().upsertTask(entity)
        enqueuePayload(
            db,
            PlannerTaskPayload(
                id = id,
                title = title,
                recurrenceRule = entity.recurrenceRule,
                status = entity.status,
                priority = entity.priority,
                sync = SyncMetadataPayload(ownerId, nowMs, nowMs)
            ),
            ownerId,
            nowMs
        )
    }

    private suspend fun updateOwnedTaskTitle(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        updatedAtMs: Long,
        version: Long
    ) {
        val existing = requireNotNull(db.plannerTaskDao().getTaskById(id))
        val updated = existing.copy(
            title = title,
            sync = existing.sync.copy(
                clientUpdatedAtMs = updatedAtMs,
                version = version,
                syncStatus = SyncStatus.PENDING_UPLOAD.name
            )
        )
        db.plannerTaskDao().updateTask(updated)
        enqueuePayload(
            db,
            PlannerTaskPayload(
                id = id,
                title = title,
                recurrenceRule = updated.recurrenceRule,
                status = updated.status,
                priority = updated.priority,
                sync = SyncMetadataPayload(ownerId, updated.sync.createdAtMs, updatedAtMs, version = version)
            ),
            ownerId,
            updatedAtMs
        )
    }

    private suspend fun pushSingleAs(
        token: String,
        payload: SyncEntityPayload,
        mutationId: String
    ) = server.pushMutations(
        token,
        SyncPushRequest(
            listOf(
                SyncMutationItem(
                    mutationId = mutationId,
                    entityTable = payload.table.tableName,
                    entityId = payload.id,
                    opType = if (payload.sync.deletedAtMs != null) SyncOpType.DELETE else SyncOpType.UPSERT,
                    baseVersion = payload.sync.version,
                    clientUpdatedAtMs = payload.sync.clientUpdatedAtMs,
                    payload = payload
                )
            )
        )
    )

    private suspend fun enqueuePayload(
        db: PekmenDatabase,
        payload: SyncEntityPayload,
        ownerId: String,
        nowMs: Long
    ) {
        val opType = if (payload.sync.deletedAtMs != null) SyncOpType.DELETE else SyncOpType.UPSERT
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                mutationId = UuidV7.generate(nowMs),
                entityTable = payload.table.tableName,
                entityId = payload.id,
                opType = opType.name,
                payloadJson = SyncPayloadJson.encodePayload(payload),
                baseVersion = payload.sync.version,
                attemptCount = 0,
                createdAtMs = nowMs,
                ownerUserId = ownerId
            )
        )
    }

    private companion object {
        const val ALICE_ID = "01900000-0000-7000-8000-aaaaaaaaaaa1"
        const val ALICE_EMAIL = "alice@example.invalid"
        const val ALICE_OTP = "246810"
        const val BOB_ID = "01900000-0000-7000-8000-bbbbbbbbbbb2"
        const val BOB_EMAIL = "bob@example.invalid"
        const val BOB_OTP = "135790"
    }
}
