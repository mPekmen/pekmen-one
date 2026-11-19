package net.pekmen.one.core.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.cloud.auth.AuthSessionRecord
import net.pekmen.one.core.cloud.auth.CloudRlsGatedException
import net.pekmen.one.core.cloud.auth.KeystoreTokenVault
import net.pekmen.one.core.cloud.sync.InMemoryAesTokenCipher
import net.pekmen.one.core.cloud.sync.InMemoryAuthBackendGateway
import net.pekmen.one.core.cloud.sync.InMemoryRlsServerTestDouble
import net.pekmen.one.core.cloud.sync.LearningSubjectPayload
import net.pekmen.one.core.cloud.sync.MutableTestTimeProvider
import net.pekmen.one.core.cloud.sync.RlsAuthorizationException
import net.pekmen.one.core.cloud.sync.SyncEntityTable
import net.pekmen.one.core.cloud.sync.SyncMetadataPayload
import net.pekmen.one.core.cloud.sync.SyncPullRequest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.model.AccountState
import net.pekmen.one.core.model.CloudSyncPhase
import net.pekmen.one.core.model.LogoutCheckResult
import net.pekmen.one.core.model.UuidV7
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
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CloudAuthAndRlsTest {

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
    fun `default gate fails closed and blocks live auth and sync`() = runTest {
        val defaultGate = DefaultCloudRlsGateProvider()
        assertFalse(defaultGate.currentGateStatus().isLiveCloudEnabled)

        val client = harness.createClient(this, rlsGateProvider = defaultGate)
        authGateway.registerAccount(
            CloudTestHarness.ALICE_EMAIL,
            CloudTestHarness.ALICE_ID,
            "Alice",
            CloudTestHarness.ALICE_OTP
        )

        assertEquals(AccountState.Guest, client.repo.accountState.first())
        assertEquals(CloudSyncPhase.OFFLINE_GUEST, client.repo.syncState.first().phase)

        val otpReq = client.repo.requestOtpSignIn(CloudTestHarness.ALICE_EMAIL)
        assertTrue(otpReq.exceptionOrNull() is CloudRlsGatedException)

        val otpVerify = client.repo.verifyOtpSignIn(CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
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
        val gatedClient = harness.createClient(this, rlsGateProvider = toggleGate)
        gatedClient.repo.requestOtpSignIn(CloudTestHarness.ALICE_EMAIL).getOrThrow()
        gatedClient.repo.verifyOtpSignIn(CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP).getOrThrow()
        gateOpen = false

        assertEquals(CloudSyncPhase.GATED_CLOUD_UNVERIFIED, gatedClient.repo.syncState.first().phase)
        assertTrue(gatedClient.repo.triggerSyncNow().exceptionOrNull() is CloudRlsGatedException)
    }

    @Test
    fun `keystore vault encrypts tokens and keeps db clean`() = runTest {
        val vaultFile = File(tempFolder.newFolder(), "vault.enc")
        val cipher = InMemoryAesTokenCipher()
        val vault = KeystoreTokenVault(vaultFile, cipher)

        val record = AuthSessionRecord(
            userId = CloudTestHarness.ALICE_ID,
            email = CloudTestHarness.ALICE_EMAIL,
            displayName = "Alice",
            accessToken = "pekmen-test-session-alice-plain-check",
            refreshToken = "pekmen-test-refresh-alice-plain-check",
            expiresAtWallMs = timeProvider.wallTimeMs() + 3_600_000L
        )
        vault.saveSession(record)

        val rawText = vaultFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(rawText.contains("pekmen-test-session-alice-plain-check"))
        assertFalse(rawText.contains("pekmen-test-refresh-alice-plain-check"))
        assertFalse(rawText.contains(CloudTestHarness.ALICE_EMAIL))
        assertFalse(record.toString().contains("pekmen-test-session-alice-plain-check"))
        assertEquals(record, vault.readSession())

        vaultFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertNull(vault.readSession())
        assertFalse(vaultFile.exists())
    }

    @Test
    fun `guest data claims explicitly and pushes via outbox`() = runTest {
        authGateway.registerAccount(
            CloudTestHarness.ALICE_EMAIL,
            CloudTestHarness.ALICE_ID,
            "Alice",
            CloudTestHarness.ALICE_OTP
        )
        val client = harness.createClient(this)
        val now = timeProvider.wallTimeMs()

        harness.insertGuestFixtures(client.db, now)
        assertEquals(6, client.repo.countUnclaimedGuestRecords())

        client.repo.requestOtpSignIn(CloudTestHarness.ALICE_EMAIL).getOrThrow()
        val signedIn = client.repo.verifyOtpSignIn(
            CloudTestHarness.ALICE_EMAIL,
            CloudTestHarness.ALICE_OTP
        ).getOrThrow()
        assertEquals(CloudTestHarness.ALICE_ID, signedIn.userId)
        assertEquals(CloudTestHarness.ALICE_ID, client.prefs.preferences.first().activeOwnerUserId)

        assertEquals(0, client.db.learningDao().observeActiveSubjectsByOwner(CloudTestHarness.ALICE_ID).first().size)
        assertEquals(0, client.db.plannerTaskDao().observeTasksByOwner(CloudTestHarness.ALICE_ID).first().size)

        val claimed = client.repo.claimGuestDataToSignedInUser().getOrThrow()
        assertEquals(6, claimed)
        assertEquals(0, client.repo.countUnclaimedGuestRecords())
        assertEquals(6, client.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))
        assertEquals(1, client.db.learningDao().observeActiveSubjectsByOwner(CloudTestHarness.ALICE_ID).first().size)
        assertEquals(1, client.db.plannerTaskDao().observeTasksByOwner(CloudTestHarness.ALICE_ID).first().size)

        val synced = client.repo.triggerSyncNow().getOrThrow()
        assertEquals(12, synced)
        assertEquals(0, client.db.syncOutboxDao().countPendingForOwner(CloudTestHarness.ALICE_ID))
        assertEquals(6, server.statsForUser(CloudTestHarness.ALICE_ID).totalRows)
        assertEquals(0, client.repo.triggerSyncNow().getOrThrow())
    }

    @Test
    fun `cross tenant RLS rejects reads writes and isolates Room`() = runTest {
        authGateway.registerAccount(
            CloudTestHarness.ALICE_EMAIL,
            CloudTestHarness.ALICE_ID,
            "Alice",
            CloudTestHarness.ALICE_OTP
        )
        authGateway.registerAccount(
            CloudTestHarness.BOB_EMAIL,
            CloudTestHarness.BOB_ID,
            "Bob",
            CloudTestHarness.BOB_OTP
        )
        val client = harness.createClient(this)
        val now = timeProvider.wallTimeMs()

        harness.signInClient(client, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
        val aliceSubjectId = UuidV7.generate(now)
        val aliceSessionId = UuidV7.generate(now + 1L)
        val aliceTaskId = UuidV7.generate(now + 2L)
        harness.insertOwnedSubject(client.db, aliceSubjectId, CloudTestHarness.ALICE_ID, "Alice German B2", now)
        harness.insertOwnedSession(client.db, aliceSessionId, CloudTestHarness.ALICE_ID, aliceSubjectId, now)
        harness.insertOwnedTask(client.db, aliceTaskId, CloudTestHarness.ALICE_ID, "Alice Task", now)
        client.repo.triggerSyncNow().getOrThrow()

        val aliceToken = client.vault.readSession()!!.accessToken
        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout())

        assertEquals(0, client.db.learningDao().observeActiveSubjectsByOwner(null).first().size)
        assertEquals(0, client.db.plannerTaskDao().observeTasksByOwner(null).first().size)
        assertEquals(
            0,
            client.db.accountSessionDao().observeRecentCompletedSessionsByOwner(20, null).first().size
        )

        harness.signInClient(client, CloudTestHarness.BOB_EMAIL, CloudTestHarness.BOB_OTP)
        val bobToken = client.vault.readSession()!!.accessToken
        assertEquals(0, client.db.learningDao().observeActiveSubjectsByOwner(CloudTestHarness.BOB_ID).first().size)
        assertEquals(0, client.db.plannerTaskDao().observeTasksByOwner(CloudTestHarness.BOB_ID).first().size)

        assertCrossTenantRlsDenied(bobToken, aliceSubjectId, now)

        assertTrue(
            server.readRowAs(aliceToken, SyncEntityTable.LEARNING_SUBJECTS, aliceSubjectId)
                .exceptionOrNull() is RlsAuthorizationException
        )

        val bobSubjectId = UuidV7.generate(now + 30L)
        harness.insertOwnedSubject(client.db, bobSubjectId, CloudTestHarness.BOB_ID, "Bob Physics", now + 30L)
        client.repo.triggerSyncNow().getOrThrow()
        assertEquals(1, client.db.learningDao().observeActiveSubjectsByOwner(CloudTestHarness.BOB_ID).first().size)
        assertEquals(
            "Bob Physics",
            client.db.learningDao().observeActiveSubjectsByOwner(CloudTestHarness.BOB_ID).first().single().title
        )

        assertEquals(LogoutCheckResult.SignedOut, client.repo.attemptLogout())
        harness.signInClient(client, CloudTestHarness.ALICE_EMAIL, CloudTestHarness.ALICE_OTP)
        val freshAliceToken = client.vault.readSession()!!.accessToken
        val aliceServerSubject = server.readRowAs(freshAliceToken, SyncEntityTable.LEARNING_SUBJECTS, aliceSubjectId)
            .getOrThrow() as LearningSubjectPayload
        assertEquals("Alice German B2", aliceServerSubject.title)
        assertNull(aliceServerSubject.sync.deletedAtMs)
        val aliceSubjects = client.db.learningDao().observeActiveSubjectsByOwner(CloudTestHarness.ALICE_ID).first()
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
            sync = SyncMetadataPayload(CloudTestHarness.ALICE_ID, nowMs, nowMs + 100L, version = 2L)
        )
        assertTrue(
            harness.pushSingleAs(bobToken, spoofedWithAliceOwner, UuidV7.generate(nowMs + 10L))
                .exceptionOrNull() is RlsAuthorizationException
        )

        val spoofedWithBobOwnerSameId = spoofedWithAliceOwner.copy(
            sync = spoofedWithAliceOwner.sync.copy(ownerUserId = CloudTestHarness.BOB_ID)
        )
        assertTrue(
            harness.pushSingleAs(bobToken, spoofedWithBobOwnerSameId, UuidV7.generate(nowMs + 11L))
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
}
