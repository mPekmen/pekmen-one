package net.pekmen.one.core.cloud

import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.model.AccountState
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.LogoutCheckResult
import net.pekmen.one.core.model.SyncStateSummary

interface CloudAccountRepository {
    val accountState: Flow<AccountState>
    val syncState: Flow<SyncStateSummary>
    val gateStatus: CloudRlsGateStatus

    suspend fun requestOtpSignIn(email: String): Result<Unit>
    suspend fun verifyOtpSignIn(email: String, code: String): Result<AccountState.SignedIn>
    suspend fun countUnclaimedGuestRecords(): Int
    suspend fun claimGuestDataToSignedInUser(): Result<Int>
    suspend fun attemptLogout(forceDiscardPending: Boolean = false): LogoutCheckResult
    suspend fun triggerSyncNow(): Result<Int>
}
