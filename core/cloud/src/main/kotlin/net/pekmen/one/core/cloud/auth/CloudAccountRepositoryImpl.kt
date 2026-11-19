package net.pekmen.one.core.cloud.auth

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.pekmen.one.core.cloud.CloudAccountRepository
import net.pekmen.one.core.cloud.CloudRlsGateProvider
import net.pekmen.one.core.cloud.sync.OutboxSyncEngine
import net.pekmen.one.core.cloud.sync.SyncWorkScheduler
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.AccountState
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.LogoutCheckResult
import net.pekmen.one.core.model.SyncStateSummary
import net.pekmen.one.core.model.TimeProvider

/**
 * Coordinates passwordless Email + OTP authentication, AndroidKeyStore token vaulting,
 * explicit guest-data claim, strict multi-account isolation, and outbox-safe logout.
 */
class CloudAccountRepositoryImpl(
    private val database: PekmenDatabase,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val tokenVault: TokenVault,
    private val authGateway: AuthBackendGateway,
    private val syncEngine: OutboxSyncEngine,
    private val syncWorkScheduler: SyncWorkScheduler,
    private val rlsGateProvider: CloudRlsGateProvider,
    private val timeProvider: TimeProvider
) : CloudAccountRepository {

    private val syncOutboxDao = database.syncOutboxDao()
    private val accountClaimDao = database.accountClaimDao()
    private val socialCircleDao = database.socialCircleDao()
    private val authMutex = Mutex()
    private val vaultHydrated = MutableStateFlow(false)
    private val sessionUpdates = MutableStateFlow<AuthSessionRecord?>(null)

    private val sessionStream: Flow<AuthSessionRecord?> = flow {
        ensureVaultLoaded()
        emitAll(sessionUpdates)
    }

    override val gateStatus: CloudRlsGateStatus
        get() = rlsGateProvider.currentGateStatus()

    override val accountState: Flow<AccountState> = combine(
        preferencesDataStore.preferences,
        sessionStream
    ) { prefs, session ->
        if (session != null && prefs.activeOwnerUserId == session.userId) {
            AccountState.SignedIn(
                userId = session.userId,
                email = session.email,
                displayName = session.displayName
            )
        } else {
            AccountState.Guest
        }
    }

    override val syncState: Flow<SyncStateSummary> = syncEngine.observeSyncState(accountState)

    override suspend fun requestOtpSignIn(email: String): Result<Unit> {
        val currentGate = gateStatus
        if (!currentGate.isLiveCloudEnabled) {
            return Result.failure(CloudRlsGatedException(currentGate))
        }
        val normalized = email.trim().lowercase()
        if (!normalized.contains('@') || !normalized.contains('.')) {
            return Result.failure(IllegalArgumentException("Enter a valid email address"))
        }
        return authGateway.requestOtp(normalized)
    }

    override suspend fun verifyOtpSignIn(
        email: String,
        code: String
    ): Result<AccountState.SignedIn> = authMutex.withLock {
        val currentGate = gateStatus
        val normalized = email.trim().lowercase()
        val trimmedCode = code.trim()
        val failure: Throwable? = when {
            !currentGate.isLiveCloudEnabled -> CloudRlsGatedException(currentGate)
            normalized.isEmpty() || trimmedCode.isEmpty() ->
                IllegalArgumentException("Email and verification code are required")
            else -> null
        }
        if (failure != null) {
            Result.failure(failure)
        } else {
            performOtpSignInLocked(normalized, trimmedCode)
        }
    }

    private suspend fun performOtpSignInLocked(
        email: String,
        code: String
    ): Result<AccountState.SignedIn> {
        val currentSession = resolveActiveSessionLocked()
        val currentOwner = currentSession?.userId
            ?: preferencesDataStore.preferences.first().activeOwnerUserId
        val session = authGateway.verifyOtp(email, code).getOrElse { error ->
            return Result.failure(error)
        }
        if (currentOwner != null && currentOwner != session.userId) {
            val pendingPrevious = syncOutboxDao.countPendingForOwner(currentOwner)
            if (pendingPrevious > 0) {
                return Result.failure(
                    IllegalStateException("Sync or sign out of current account before switching users")
                )
            }
        }
        if (currentOwner != session.userId) {
            if (currentSession != null && gateStatus.isLiveCloudEnabled) {
                runCatching { authGateway.revokeSession(currentSession.accessToken) }
            }
            database.withTransaction {
                clearCachedCircleTables()
                syncOutboxDao.clearCursors()
            }
            syncEngine.resetRuntimeStatus()
        }
        tokenVault.saveSession(session)
        preferencesDataStore.setActiveOwnerUserId(session.userId)
        sessionUpdates.value = session
        vaultHydrated.value = true
        syncWorkScheduler.schedulePeriodicSync(session.userId)
        return Result.success(
            AccountState.SignedIn(
                userId = session.userId,
                email = session.email,
                displayName = session.displayName
            )
        )
    }

    override suspend fun countUnclaimedGuestRecords(): Int = syncEngine.countUnclaimedGuestRecords()

    override suspend fun claimGuestDataToSignedInUser(): Result<Int> = authMutex.withLock {
        val session = resolveActiveSessionLocked()
            ?: return@withLock Result.failure(IllegalStateException("Must be signed in to claim guest data"))
        val now = timeProvider.wallTimeMs()
        preferencesDataStore.setActiveOwnerUserId(session.userId)

        var claimedCount = 0
        database.withTransaction {
            val payloads = syncEngine.readUnclaimedGuestPayloads(session.userId, now)
            accountClaimDao.claimGuestCustomProfiles(session.userId, now)
            accountClaimDao.claimGuestSubjects(session.userId, now)
            accountClaimDao.claimGuestBooks(session.userId, now)
            accountClaimDao.claimGuestTopics(session.userId, now)
            accountClaimDao.claimGuestSessions(session.userId, now)
            accountClaimDao.claimGuestTasks(session.userId, now)
            payloads.forEach { payload ->
                syncEngine.enqueueMutation(payload, session.userId, now)
            }
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM sync_outbox WHERE owner_user_id IS NULL"
            )
            claimedCount = payloads.size
        }

        if (claimedCount > 0 && gateStatus.isLiveCloudEnabled) {
            syncWorkScheduler.scheduleImmediateSync(session.userId)
        }
        Result.success(claimedCount)
    }

    override suspend fun attemptLogout(forceDiscardPending: Boolean): LogoutCheckResult = authMutex.withLock {
        val activeSession = resolveActiveSessionLocked()
        val activeOwner = activeSession?.userId ?: preferencesDataStore.preferences.first().activeOwnerUserId
        if (activeOwner == null) {
            clearLocalAuthState()
            return@withLock LogoutCheckResult.SignedOut
        }

        val pendingCount = syncOutboxDao.countPendingForOwner(activeOwner)
        if (pendingCount > 0 && !forceDiscardPending) {
            return@withLock LogoutCheckResult.BlockedByPendingMutations(pendingCount)
        }

        if (activeSession != null && gateStatus.isLiveCloudEnabled) {
            runCatching { authGateway.revokeSession(activeSession.accessToken) }
        }

        database.withTransaction {
            if (forceDiscardPending && pendingCount > 0) {
                discardOutboxForOwner(activeOwner)
            }
            clearCachedCircleTables()
            syncOutboxDao.clearCursors()
        }

        clearLocalAuthState()
        LogoutCheckResult.SignedOut
    }

    override suspend fun triggerSyncNow(): Result<Int> {
        val currentGate = gateStatus
        if (!currentGate.isLiveCloudEnabled) {
            return Result.failure(CloudRlsGatedException(currentGate))
        }
        val sessionResult = resolveOrRefreshSession()
        val activeSession = sessionResult.getOrElse { return Result.failure(it) }
        return syncEngine.sync(activeSession)
    }

    private suspend fun resolveOrRefreshSession(): Result<AuthSessionRecord> = authMutex.withLock {
        val existing = resolveActiveSessionLocked()
            ?: return@withLock Result.failure(IllegalStateException("Sign in required before syncing"))
        if (existing.expiresAtWallMs > timeProvider.wallTimeMs()) {
            Result.success(existing)
        } else {
            authGateway.refreshSession(existing.refreshToken).onSuccess { refreshed ->
                tokenVault.saveSession(refreshed)
                sessionUpdates.value = refreshed
            }
        }
    }

    private suspend fun ensureVaultLoaded() {
        if (vaultHydrated.value) return
        authMutex.withLock {
            if (!vaultHydrated.value) {
                sessionUpdates.value = tokenVault.readSession()
                vaultHydrated.value = true
            }
        }
    }

    private suspend fun resolveActiveSessionLocked(): AuthSessionRecord? {
        if (!vaultHydrated.value) {
            sessionUpdates.value = tokenVault.readSession()
            vaultHydrated.value = true
        }
        return sessionUpdates.value
    }

    private suspend fun clearCachedCircleTables() {
        socialCircleDao.clearAllSummaries()
        socialCircleDao.clearAllMemberships()
        socialCircleDao.clearAllCircles()
    }

    private suspend fun discardOutboxForOwner(ownerUserId: String) {
        while (true) {
            val batch = syncOutboxDao.getPendingBatchForOwner(ownerUserId, DISCARD_BATCH_SIZE)
            if (batch.isEmpty()) break
            syncOutboxDao.deleteByMutationIds(batch.map { it.mutationId })
        }
    }

    private suspend fun clearLocalAuthState() {
        tokenVault.clearSession()
        preferencesDataStore.setActiveOwnerUserId(null)
        syncWorkScheduler.cancelSync()
        sessionUpdates.value = null
        vaultHydrated.value = true
        syncEngine.resetRuntimeStatus()
    }

    private companion object {
        const val DISCARD_BATCH_SIZE = 200
    }
}
