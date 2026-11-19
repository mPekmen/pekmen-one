package net.pekmen.one

import android.content.Context
import net.pekmen.one.core.cloud.CirclesRepository
import net.pekmen.one.core.cloud.CloudAccountRepository
import net.pekmen.one.core.cloud.CloudRlsGateProvider
import net.pekmen.one.core.cloud.DefaultCloudRlsGateProvider
import net.pekmen.one.core.cloud.auth.AuthBackendGateway
import net.pekmen.one.core.cloud.auth.AuthSessionRecord
import net.pekmen.one.core.cloud.auth.CloudAccountRepositoryImpl
import net.pekmen.one.core.cloud.auth.CloudRlsGatedException
import net.pekmen.one.core.cloud.auth.KeystoreTokenVault
import net.pekmen.one.core.cloud.auth.TokenVault
import net.pekmen.one.core.cloud.circles.CirclesGatedException
import net.pekmen.one.core.cloud.circles.CirclesRemoteGateway
import net.pekmen.one.core.cloud.circles.CirclesRepositoryImpl
import net.pekmen.one.core.cloud.circles.RemoteCircleSnapshot
import net.pekmen.one.core.cloud.circles.RemoteMemberSummaryDto
import net.pekmen.one.core.cloud.sync.JobSchedulerSyncScheduler
import net.pekmen.one.core.cloud.sync.NoOpSyncWorkScheduler
import net.pekmen.one.core.cloud.sync.OutboxSyncEngine
import net.pekmen.one.core.cloud.sync.SyncPullRequest
import net.pekmen.one.core.cloud.sync.SyncPullResponse
import net.pekmen.one.core.cloud.sync.SyncPushRequest
import net.pekmen.one.core.cloud.sync.SyncPushResponse
import net.pekmen.one.core.cloud.sync.SyncRemoteGateway
import net.pekmen.one.core.cloud.sync.SyncWorkScheduler
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.TimeProvider

internal class CloudContainerSlice(
    private val appContext: Context,
    private val database: PekmenDatabase,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val timeProvider: TimeProvider
) {
    val cloudRlsGateProvider: CloudRlsGateProvider by lazy {
        DefaultCloudRlsGateProvider(
            compiledWithCloudFlag = BuildConfig.CLOUD_ENABLED,
            backendEndpointConfigured = BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
            rlsVerified = false
        )
    }

    private val tokenVault: TokenVault by lazy { KeystoreTokenVault(appContext) }

    private val authGateway: AuthBackendGateway by lazy { GatedAuthBackendGateway(cloudRlsGateProvider) }

    private val syncRemoteGateway: SyncRemoteGateway by lazy { GatedSyncRemoteGateway(cloudRlsGateProvider) }

    private val circlesRemoteGateway: CirclesRemoteGateway by lazy { GatedCirclesRemoteGateway(cloudRlsGateProvider) }

    private val syncWorkScheduler: SyncWorkScheduler by lazy {
        if (cloudRlsGateProvider.currentGateStatus().isLiveCloudEnabled) {
            JobSchedulerSyncScheduler(appContext)
        } else {
            NoOpSyncWorkScheduler()
        }
    }

    private val outboxSyncEngine: OutboxSyncEngine by lazy {
        OutboxSyncEngine(
            database = database,
            remoteGateway = syncRemoteGateway,
            rlsGateProvider = cloudRlsGateProvider,
            timeProvider = timeProvider
        )
    }

    val cloudAccountRepository: CloudAccountRepository by lazy {
        CloudAccountRepositoryImpl(
            database = database,
            preferencesDataStore = userPreferencesDataStore,
            tokenVault = tokenVault,
            authGateway = authGateway,
            syncEngine = outboxSyncEngine,
            syncWorkScheduler = syncWorkScheduler,
            rlsGateProvider = cloudRlsGateProvider,
            timeProvider = timeProvider
        )
    }

    val circlesRepository: CirclesRepository by lazy {
        CirclesRepositoryImpl(
            database = database,
            preferencesDataStore = userPreferencesDataStore,
            remoteGateway = circlesRemoteGateway,
            timeProvider = timeProvider,
            gateStatusProvider = { cloudRlsGateProvider.currentGateStatus() }
        )
    }
}

private class GatedAuthBackendGateway(
    private val gateProvider: CloudRlsGateProvider
) : AuthBackendGateway {
    override suspend fun requestOtp(email: String): Result<Unit> =
        Result.failure(CloudRlsGatedException(gateProvider.currentGateStatus()))

    override suspend fun verifyOtp(email: String, code: String): Result<AuthSessionRecord> =
        Result.failure(CloudRlsGatedException(gateProvider.currentGateStatus()))

    override suspend fun refreshSession(refreshToken: String): Result<AuthSessionRecord> =
        Result.failure(CloudRlsGatedException(gateProvider.currentGateStatus()))

    override suspend fun revokeSession(accessToken: String): Result<Unit> =
        Result.failure(CloudRlsGatedException(gateProvider.currentGateStatus()))
}

private class GatedSyncRemoteGateway(
    private val gateProvider: CloudRlsGateProvider
) : SyncRemoteGateway {
    override suspend fun pushMutations(accessToken: String, request: SyncPushRequest): Result<SyncPushResponse> =
        Result.failure(CloudRlsGatedException(gateProvider.currentGateStatus()))

    override suspend fun pullDeltas(accessToken: String, request: SyncPullRequest): Result<SyncPullResponse> =
        Result.failure(CloudRlsGatedException(gateProvider.currentGateStatus()))
}

private class GatedCirclesRemoteGateway(
    private val gateProvider: CloudRlsGateProvider
) : CirclesRemoteGateway {
    private fun gatedError(): Result<Nothing> =
        Result.failure(CirclesGatedException(gateProvider.currentGateStatus()))

    override suspend fun createCircle(
        actingUserId: String,
        name: String,
        creatorDisplayName: String,
        inviteCode: String,
        nowMs: Long
    ): Result<RemoteCircleSnapshot> = gatedError()

    override suspend fun joinCircle(
        actingUserId: String,
        inviteCode: String,
        memberDisplayName: String,
        nowMs: Long
    ): Result<RemoteCircleSnapshot> = gatedError()

    override suspend fun leaveCircle(actingUserId: String, circleId: String, nowMs: Long): Result<Unit> =
        gatedError()

    override suspend fun updateSharingConfig(
        actingUserId: String,
        circleId: String,
        config: CircleSharingConfig,
        nowMs: Long
    ): Result<CircleMembership> = gatedError()

    override suspend fun publishSummary(actingUserId: String, summary: RemoteMemberSummaryDto): Result<Unit> =
        gatedError()

    override suspend fun deleteMySummary(actingUserId: String, circleId: String): Result<Unit> = gatedError()

    override suspend fun fetchCircleDetails(actingUserId: String, circleId: String): Result<RemoteCircleSnapshot> =
        gatedError()

    override suspend fun fetchJoinedCircles(actingUserId: String): Result<List<RemoteCircleSnapshot>> =
        gatedError()

    override suspend fun fetchLeaderboard(
        actingUserId: String,
        circleId: String,
        periodType: CirclePeriodType,
        periodStartDate: String
    ): Result<List<RemoteMemberSummaryDto>> = gatedError()
}
