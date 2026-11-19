package net.pekmen.one.core.model

sealed interface AccountState {
    data object Guest : AccountState
    data class SignedIn(
        val userId: String,
        val email: String,
        val displayName: String? = null
    ) : AccountState
}

enum class CloudSyncPhase {
    OFFLINE_GUEST,
    GATED_CLOUD_UNVERIFIED,
    IDLE,
    SYNCING,
    ERROR
}

data class SyncStateSummary(
    val phase: CloudSyncPhase = CloudSyncPhase.OFFLINE_GUEST,
    val pendingMutationCount: Int = 0,
    val lastSyncedWallMs: Long? = null,
    val lastError: String? = null
)

data class CloudRlsGateStatus(
    val compiledWithCloudFlag: Boolean,
    val backendEndpointConfigured: Boolean,
    val rlsVerified: Boolean
) {
    val isLiveCloudEnabled: Boolean
        get() = compiledWithCloudFlag && backendEndpointConfigured && rlsVerified
}

sealed interface LogoutCheckResult {
    data object SignedOut : LogoutCheckResult
    data class BlockedByPendingMutations(val pendingCount: Int) : LogoutCheckResult
}
