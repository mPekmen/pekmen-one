package net.pekmen.one.core.cloud.sync

class RlsAuthorizationException(message: String) : SecurityException(message)

/**
 * Remote synchronization gateway supporting idempotent batch mutation pushes and
 * cursor-based delta pulls ordered by (serverUpdatedAtMs, entityId).
 */
interface SyncRemoteGateway {
    suspend fun pushMutations(
        accessToken: String,
        request: SyncPushRequest
    ): Result<SyncPushResponse>

    suspend fun pullDeltas(
        accessToken: String,
        request: SyncPullRequest
    ): Result<SyncPullResponse>
}
