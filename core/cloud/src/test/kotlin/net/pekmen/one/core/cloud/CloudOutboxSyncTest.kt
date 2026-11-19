package net.pekmen.one.core.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.cloud.sync.ConflictWinner
import net.pekmen.one.core.cloud.sync.InMemoryAuthBackendGateway
import net.pekmen.one.core.cloud.sync.InMemoryRlsServerTestDouble
import net.pekmen.one.core.cloud.sync.MutableTestTimeProvider
import net.pekmen.one.core.cloud.sync.SyncConflictCandidate
import net.pekmen.one.core.cloud.sync.SyncConflictResolver
import net.pekmen.one.core.cloud.sync.SyncEntityTable
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.model.AccountState
import net.pekmen.one.core.model.CloudSyncPhase
import net.pekmen.one.core.model.LogoutCheckResult
import net.pekmen.one.core.model.UuidV7
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
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CloudOutboxSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var timeProvider: MutableTestTimeProvider
    private lateinit var authGateway: InMemoryAuthBackendGateway
    private lateinit var server: InMemoryRlsServerTestDouble
    private lateinit var harness: CloudTestHarness
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
        harness = CloudTestHarness(context, tempFolder, timeProvider, authGateway, server, openDatabases)
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    @Test
    fun `50 mixed writes handle mid batch failure idempotently`() = runTest {
        authGateway.registerAccount(
            CloudTestHarness.ALICE_EMAIL,
            CloudTestHarness.ALICE_ID,
            "Alice",
            CloudTestHarness.ALICE_OTP
        )
        val client1 = harness.createClient(this, pushBatchSize = 20)
        harness.signInClient(client1, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
        val baseMs = timeProvider.wallTimeMs()

        harness.seedFiftyMixedMutations(client1.db, CloudTestHarness.ALICE_ID, baseMs)
        assertEquals(50, client1.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))

        server.failOnPushCallNumber = 2
        server.failMidBatchAfterAcceptedCount = 7

        val firstSync = client1.repo.triggerSyncNow()
        assertTrue(firstSync.isFailure)
        assertEquals(CloudSyncPhase.ERROR, client1.repo.syncState.first().phase)
        assertEquals(30, client1.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))
        assertEquals(27, server.statsForUser(CloudTestHarness.ALICE_ID).totalRows)

        server.failOnPushCallNumber = null
        server.failMidBatchAfterAcceptedCount = null
        client1.repo.triggerSyncNow().getOrThrow()

        assertEquals(0, client1.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))
        assertEquals(CloudSyncPhase.IDLE, client1.repo.syncState.first().phase)
        val stats = server.statsForUser(CloudTestHarness.ALICE_ID)
        assertEquals(50, stats.totalRows)
        assertEquals(41, stats.activeRows)
        assertEquals(50, stats.ledgerSize)

        val client2 = harness.createClient(this, pushBatchSize = 20)
        harness.signInClient(client2, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
        val pulled = client2.repo.triggerSyncNow().getOrThrow()
        assertEquals(50, pulled)
        assertEquals(8, client2.db.learningDao().observeActiveSubjectsByOwner(CloudTestHarness.ALICE_ID).first().size)
        assertEquals(8, client2.db.learningDao().observeActiveBooksByOwner(CloudTestHarness.ALICE_ID).first().size)
        assertEquals(
            8,
            client2.db.learningTopicDao().observeActiveTopicsByOwner(CloudTestHarness.ALICE_ID).first().size
        )
        assertEquals(7, client2.db.plannerTaskDao().observeTasksByOwner(CloudTestHarness.ALICE_ID).first().size)
        val syncedSessions = client2.db.accountSessionDao()
            .observeRecentCompletedSessionsByOwner(20, CloudTestHarness.ALICE_ID)
            .first()
        assertEquals(7, syncedSessions.size)

        assertEquals(0, client2.repo.triggerSyncNow().getOrThrow())
    }

    @Test
    fun `conflicts converge and tombstones never resurrect`() = runTest {
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

        authGateway.registerAccount(
            CloudTestHarness.ALICE_EMAIL,
            CloudTestHarness.ALICE_ID,
            "Alice",
            CloudTestHarness.ALICE_OTP
        )
        val client1 = harness.createClient(this)
        val client2 = harness.createClient(this)
        harness.signInClient(client1, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
        harness.signInClient(client2, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)

        val t0 = timeProvider.wallTimeMs()
        val taskId = UuidV7.generate(t0)
        val subjectId = UuidV7.generate(t0 + 1L)
        harness.insertOwnedSubject(client1.db, subjectId, CloudTestHarness.ALICE_ID, "Initial Subject", t0)
        harness.insertOwnedTask(client1.db, taskId, CloudTestHarness.ALICE_ID, "Initial Task", t0)
        client1.repo.triggerSyncNow().getOrThrow()
        client2.repo.triggerSyncNow().getOrThrow()

        harness.updateOwnedTaskTitle(
            client2.db,
            taskId,
            CloudTestHarness.ALICE_ID,
            "Client 2 Newer Title",
            t0 + 5_000L,
            version = 2L
        )
        harness.updateOwnedTaskTitle(
            client1.db,
            taskId,
            CloudTestHarness.ALICE_ID,
            "Client 1 Older Title",
            t0 + 1_000L,
            version = 2L
        )

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

        harness.updateOwnedSubjectTitle(
            client2.db,
            subjectId,
            CloudTestHarness.ALICE_ID,
            "Resurrection Update",
            t0 + 9_000L,
            version = 2L
        )
        harness.softDeleteOwnedSubject(
            client1.db,
            subjectId,
            CloudTestHarness.ALICE_ID,
            deletedAtMs = t0 + 4_000L,
            version = 2L
        )

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
    fun `logout blocks on pending mutations until synced or discarded`() = runTest {
        authGateway.registerAccount(
            CloudTestHarness.ALICE_EMAIL,
            CloudTestHarness.ALICE_ID,
            "Alice",
            CloudTestHarness.ALICE_OTP
        )
        val client = harness.createClient(this)
        harness.signInClient(client, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
        val now = timeProvider.wallTimeMs()

        val subjectId = UuidV7.generate(now)
        harness.insertOwnedSubject(client.db, subjectId, CloudTestHarness.ALICE_ID, "Unsynced Subject", now)
        assertEquals(1, client.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))

        val blocked = client.repo.attemptLogout(forceDiscardPending = false)
        assertEquals(LogoutCheckResult.BlockedByPendingMutations(1), blocked)
        assertTrue(client.repo.accountState.first() is AccountState.SignedIn)
        assertNotNull(client.vault.readSession())
        assertEquals(1, client.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))

        client.repo.triggerSyncNow().getOrThrow()
        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout(forceDiscardPending = false))
        assertEquals(AccountState.Guest, client.repo.accountState.first())
        assertNull(client.vault.readSession())

        harness.signInClient(client, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
        harness.insertOwnedTask(
            client.db,
            UuidV7.generate(now + 50L),
            CloudTestHarness.ALICE_ID,
            "Discardable Task",
            now + 50L
        )
        assertEquals(1, client.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))
        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout(forceDiscardPending = true))
        assertEquals(0, client.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))
    }
}
