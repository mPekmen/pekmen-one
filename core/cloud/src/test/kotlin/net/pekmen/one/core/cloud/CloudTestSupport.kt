package net.pekmen.one.core.cloud

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import net.pekmen.one.core.cloud.auth.CloudAccountRepositoryImpl
import net.pekmen.one.core.cloud.auth.KeystoreTokenVault
import net.pekmen.one.core.cloud.sync.InMemoryAesTokenCipher
import net.pekmen.one.core.cloud.sync.InMemoryAuthBackendGateway
import net.pekmen.one.core.cloud.sync.InMemoryRlsServerTestDouble
import net.pekmen.one.core.cloud.sync.MutableTestTimeProvider
import net.pekmen.one.core.cloud.sync.NoOpSyncWorkScheduler
import net.pekmen.one.core.cloud.sync.OutboxSyncEngine
import net.pekmen.one.core.cloud.sync.SyncEntityPayload
import net.pekmen.one.core.cloud.sync.SyncMutationItem
import net.pekmen.one.core.cloud.sync.SyncOpType
import net.pekmen.one.core.cloud.sync.SyncPushRequest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BuiltInProfiles
import org.junit.rules.TemporaryFolder
import java.io.File

internal data class ClientHarness(
    val db: PekmenDatabase,
    val prefs: UserPreferencesDataStore,
    val vault: KeystoreTokenVault,
    val repo: CloudAccountRepositoryImpl
)

internal class CloudTestHarness(
    private val context: Context,
    private val tempFolder: TemporaryFolder,
    private val timeProvider: MutableTestTimeProvider,
    private val authGateway: InMemoryAuthBackendGateway,
    private val server: InMemoryRlsServerTestDouble,
    private val openDatabases: MutableList<PekmenDatabase>
) {

    suspend fun createClient(
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

    suspend fun signInClient(client: ClientHarness, email: String, otp: String) {
        client.repo.requestOtpSignIn(email).getOrThrow()
        client.repo.verifyOtpSignIn(email, otp).getOrThrow()
    }

    suspend fun seedBuiltInProfiles(db: PekmenDatabase, nowMs: Long) {
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

    suspend fun insertGuestFixtures(db: PekmenDatabase, nowMs: Long) =
        CloudFixtureSeeder.insertGuestFixtures(db, nowMs)

    suspend fun seedFiftyMixedMutations(db: PekmenDatabase, ownerId: String, baseMs: Long) =
        CloudFixtureSeeder.seedFiftyMixedMutations(db, ownerId, baseMs)

    suspend fun insertOwnedSubject(db: PekmenDatabase, id: String, ownerId: String, title: String, nowMs: Long) =
        CloudFixtureSeeder.insertOwnedSubject(db, id, ownerId, title, nowMs)

    suspend fun updateOwnedSubjectTitle(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        updatedAtMs: Long,
        version: Long
    ) = CloudFixtureSeeder.updateOwnedSubjectTitle(db, id, ownerId, title, updatedAtMs, version)

    suspend fun softDeleteOwnedSubject(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        deletedAtMs: Long,
        version: Long
    ) = CloudFixtureSeeder.softDeleteOwnedSubject(db, id, ownerId, deletedAtMs, version)

    suspend fun insertOwnedSession(db: PekmenDatabase, id: String, ownerId: String, subjectId: String, nowMs: Long) =
        CloudFixtureSeeder.insertOwnedSession(db, id, ownerId, subjectId, nowMs)

    suspend fun insertOwnedTask(db: PekmenDatabase, id: String, ownerId: String, title: String, nowMs: Long) =
        CloudFixtureSeeder.insertOwnedTask(db, id, ownerId, title, nowMs)

    suspend fun updateOwnedTaskTitle(
        db: PekmenDatabase,
        id: String,
        ownerId: String,
        title: String,
        updatedAtMs: Long,
        version: Long
    ) = CloudFixtureSeeder.updateOwnedTaskTitle(db, id, ownerId, title, updatedAtMs, version)

    suspend fun pushSingleAs(
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

    companion object {
        const val ALICE_ID = "01900000-0000-7000-8000-aaaaaaaaaaa1"
        const val ALICE_EMAIL = "alice@example.invalid"
        const val ALICE_OTP = "246810"
        const val BOB_ID = "01900000-0000-7000-8000-bbbbbbbbbbb2"
        const val BOB_EMAIL = "bob@example.invalid"
        const val BOB_OTP = "135790"
    }
}
