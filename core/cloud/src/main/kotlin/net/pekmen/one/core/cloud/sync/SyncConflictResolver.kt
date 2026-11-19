package net.pekmen.one.core.cloud.sync

data class SyncConflictCandidate(
    val deletedAtMs: Long?,
    val clientUpdatedAtMs: Long,
    val version: Long,
    val mutationId: String
)

enum class ConflictWinner {
    LOCAL,
    REMOTE
}

/**
 * Deterministic total-order conflict resolution:
 * 1. Delete tombstone always wins over non-deleted updates (never resurrected).
 * 2. Higher clientUpdatedAtMs wins.
 * 3. Deterministic tie-break on version, then lexicographic mutationId.
 */
object SyncConflictResolver {
    fun resolve(local: SyncConflictCandidate, remote: SyncConflictCandidate): ConflictWinner {
        val localDeleted = local.deletedAtMs != null
        val remoteDeleted = remote.deletedAtMs != null
        return when {
            localDeleted != remoteDeleted ->
                if (remoteDeleted) ConflictWinner.REMOTE else ConflictWinner.LOCAL
            remote.clientUpdatedAtMs != local.clientUpdatedAtMs ->
                if (remote.clientUpdatedAtMs > local.clientUpdatedAtMs) {
                    ConflictWinner.REMOTE
                } else {
                    ConflictWinner.LOCAL
                }
            remote.version != local.version ->
                if (remote.version > local.version) ConflictWinner.REMOTE else ConflictWinner.LOCAL
            remote.mutationId > local.mutationId -> ConflictWinner.REMOTE
            else -> ConflictWinner.LOCAL
        }
    }
}
