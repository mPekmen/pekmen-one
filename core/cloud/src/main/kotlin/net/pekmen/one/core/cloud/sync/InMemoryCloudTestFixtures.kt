package net.pekmen.one.core.cloud.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.pekmen.one.core.cloud.auth.AuthBackendGateway
import net.pekmen.one.core.cloud.auth.AuthRejectedException
import net.pekmen.one.core.cloud.auth.AuthSessionRecord
import net.pekmen.one.core.cloud.auth.TokenCipher
import net.pekmen.one.core.model.TimeProvider
import java.io.IOException
import java.security.SecureRandom
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * In-memory AES/GCM/NoPadding cipher for deterministic JVM and Robolectric tests
 * using an ephemeral runtime-generated 256-bit AES key (zero hardcoded key literals).
 */
class InMemoryAesTokenCipher(
    private val secretKey: SecretKey = generateEphemeralKey()
) : TokenCipher {

    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(HEADER_BYTES + iv.size + encrypted.size).apply {
            this[0] = FORMAT_VERSION
            this[1] = iv.size.toByte()
            iv.copyInto(this, destinationOffset = HEADER_BYTES)
            encrypted.copyInto(this, destinationOffset = HEADER_BYTES + iv.size)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > HEADER_BYTES + GCM_IV_BYTES) { "Encrypted payload too short" }
        require(ciphertext[0] == FORMAT_VERSION) { "Unsupported cipher payload version: ${ciphertext[0]}" }
        val ivLength = ciphertext[1].toInt() and UNSIGNED_BYTE_MASK
        val offset = HEADER_BYTES + ivLength
        val iv = ciphertext.copyOfRange(HEADER_BYTES, offset)
        val encrypted = ciphertext.copyOfRange(offset, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 0x01
        private const val HEADER_BYTES = 2
        private const val UNSIGNED_BYTE_MASK = 0xFF

        private fun generateEphemeralKey(): SecretKey {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(AES_KEY_BITS)
            return generator.generateKey()
        }
    }
}

class MutableTestTimeProvider(
    private var wallMs: Long,
    private var elapsedMs: Long = 10_000L,
    private var boot: Int = 1,
    private var zone: ZoneId = ZoneId.of("Europe/Istanbul")
) : TimeProvider {
    override fun wallTimeMs(): Long = wallMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun bootCount(): Int = boot
    override fun zoneId(): ZoneId = zone

    fun advanceBy(deltaMs: Long) {
        wallMs += deltaMs
        elapsedMs += deltaMs
    }
}

class InMemoryAuthBackendGateway(
    private val timeProvider: TimeProvider,
    private val sessionTtlMs: Long = DEFAULT_SESSION_TTL_MS
) : AuthBackendGateway {

    private data class RegisteredAccount(
        val userId: String,
        val email: String,
        val displayName: String,
        val otpCode: String
    )

    private val mutex = Mutex()
    private val accountsByEmail = mutableMapOf<String, RegisteredAccount>()
    private val pendingOtpEmails = mutableSetOf<String>()
    private val accessTokensToUserId = mutableMapOf<String, String>()
    private val refreshTokensToEmail = mutableMapOf<String, String>()
    private var tokenSequence = 0L

    suspend fun registerAccount(
        email: String,
        userId: String,
        displayName: String,
        otpCode: String
    ) {
        mutex.withLock {
            val normalized = email.trim().lowercase()
            accountsByEmail[normalized] = RegisteredAccount(
                userId = userId,
                email = normalized,
                displayName = displayName,
                otpCode = otpCode
            )
        }
    }

    fun resolveUserIdForToken(accessToken: String): String? = accessTokensToUserId[accessToken]

    override suspend fun requestOtp(email: String): Result<Unit> = mutex.withLock {
        val normalized = email.trim().lowercase()
        if (!accountsByEmail.containsKey(normalized)) {
            Result.failure(AuthRejectedException("Unknown test account: $normalized"))
        } else {
            pendingOtpEmails += normalized
            Result.success(Unit)
        }
    }

    override suspend fun verifyOtp(email: String, code: String): Result<AuthSessionRecord> = mutex.withLock {
        val normalized = email.trim().lowercase()
        val account = accountsByEmail[normalized]
            ?: return@withLock Result.failure(AuthRejectedException("Unknown account: $normalized"))
        if (!pendingOtpEmails.contains(normalized) || account.otpCode != code.trim()) {
            Result.failure(AuthRejectedException("Invalid or expired OTP code"))
        } else {
            pendingOtpEmails -= normalized
            Result.success(issueSessionLocked(account))
        }
    }

    override suspend fun refreshSession(refreshToken: String): Result<AuthSessionRecord> = mutex.withLock {
        val email = refreshTokensToEmail[refreshToken]
            ?: return@withLock Result.failure(AuthRejectedException("Invalid refresh token"))
        val account = accountsByEmail[email]
            ?: return@withLock Result.failure(AuthRejectedException("Account missing"))
        Result.success(issueSessionLocked(account))
    }

    override suspend fun revokeSession(accessToken: String): Result<Unit> = mutex.withLock {
        accessTokensToUserId.remove(accessToken)
        Result.success(Unit)
    }

    private fun issueSessionLocked(account: RegisteredAccount): AuthSessionRecord {
        tokenSequence += 1L
        val access = "pekmen-test-session-${account.userId}-$tokenSequence"
        val refresh = "pekmen-test-refresh-${account.userId}-$tokenSequence"
        accessTokensToUserId[access] = account.userId
        refreshTokensToEmail[refresh] = account.email
        return AuthSessionRecord(
            userId = account.userId,
            email = account.email,
            displayName = account.displayName,
            accessToken = access,
            refreshToken = refresh,
            expiresAtWallMs = timeProvider.wallTimeMs() + sessionTtlMs
        )
    }

    companion object {
        private const val DEFAULT_SESSION_TTL_MS = 3_600_000L
    }
}

/**
 * In-memory PostgreSQL/Supabase RLS server double enforcing strict tenant isolation,
 * immutable ownership, mutation_id idempotency ledger, tombstone-wins conflicts,
 * and controllable mid-batch network failure injection.
 */
class InMemoryRlsServerTestDouble(
    private val tokenResolver: (String) -> String?
) : SyncRemoteGateway {

    private data class RowKey(
        val ownerUserId: String,
        val entityTable: String,
        val entityId: String
    )

    private data class StoredRow(
        val ownerUserId: String,
        val entityTable: String,
        val entityId: String,
        val serverUpdatedAtMs: Long,
        val lastMutationId: String,
        val payload: SyncEntityPayload
    )

    private val mutex = Mutex()
    private val rows = linkedMapOf<RowKey, StoredRow>()
    private val entityOwners = mutableMapOf<Pair<String, String>, String>()
    private val mutationLedger = mutableMapOf<Pair<String, String>, SyncMutationAck>()
    private var serverClock = 1_000L
    private var pushCallCount = 0

    var failOnPushCallNumber: Int? = null
    var failMidBatchAfterAcceptedCount: Int? = null

    data class UserServerStats(
        val totalRows: Int,
        val activeRows: Int,
        val ledgerSize: Int
    )

    override suspend fun pushMutations(
        accessToken: String,
        request: SyncPushRequest
    ): Result<SyncPushResponse> = mutex.withLock {
        val callerUserId = tokenResolver(accessToken)
            ?: return@withLock Result.failure(RlsAuthorizationException("Unauthorized: invalid token"))

        pushCallCount += 1
        val shouldFailThisCall = failOnPushCallNumber == pushCallCount
        if (shouldFailThisCall && failMidBatchAfterAcceptedCount == null) {
            return@withLock Result.failure(IOException("Simulated network failure on push call $pushCallCount"))
        }

        val acks = mutableListOf<SyncMutationAck>()
        val midBatchLimit = if (shouldFailThisCall) failMidBatchAfterAcceptedCount else null
        for ((index, item) in request.mutations.withIndex()) {
            if (midBatchLimit != null && index >= midBatchLimit) {
                return@withLock Result.failure(
                    IOException("Simulated mid-batch drop after committing $midBatchLimit items")
                )
            }
            val ack = processMutationLocked(callerUserId, item).getOrElse { error ->
                return@withLock Result.failure(error)
            }
            acks += ack
        }
        Result.success(SyncPushResponse(acks))
    }

    override suspend fun pullDeltas(
        accessToken: String,
        request: SyncPullRequest
    ): Result<SyncPullResponse> = mutex.withLock {
        val callerUserId = tokenResolver(accessToken)
            ?: return@withLock Result.failure(RlsAuthorizationException("Unauthorized: invalid token"))

        val matching = rows.values
            .asSequence()
            .filter { it.ownerUserId == callerUserId && it.entityTable == request.entityTable }
            .filter { row ->
                row.serverUpdatedAtMs > request.afterServerUpdatedAtMs ||
                    (row.serverUpdatedAtMs == request.afterServerUpdatedAtMs && row.entityId > request.afterEntityId)
            }
            .sortedWith(compareBy<StoredRow>({ it.serverUpdatedAtMs }, { it.entityId }))
            .toList()

        val page = matching.take(request.limit)
        val hasMore = matching.size > page.size
        val last = page.lastOrNull()
        Result.success(
            SyncPullResponse(
                records = page.map { row ->
                    SyncDeltaRecord(
                        entityTable = row.entityTable,
                        entityId = row.entityId,
                        serverUpdatedAtMs = row.serverUpdatedAtMs,
                        mutationId = row.lastMutationId,
                        payload = row.payload
                    )
                },
                nextServerUpdatedAtMs = last?.serverUpdatedAtMs ?: request.afterServerUpdatedAtMs,
                nextEntityId = last?.entityId ?: request.afterEntityId,
                hasMore = hasMore
            )
        )
    }

    suspend fun readRowAs(
        accessToken: String,
        table: SyncEntityTable,
        entityId: String
    ): Result<SyncEntityPayload> = mutex.withLock {
        val callerUserId = tokenResolver(accessToken)
            ?: return@withLock Result.failure(RlsAuthorizationException("Unauthorized: invalid token"))
        val row = rows[RowKey(callerUserId, table.tableName, entityId)]
            ?: return@withLock Result.failure(
                RlsAuthorizationException("RLS denied read on ${table.tableName}:$entityId")
            )
        Result.success(row.payload)
    }

    suspend fun deleteRowAs(
        accessToken: String,
        table: SyncEntityTable,
        entityId: String,
        deletedAtMs: Long,
        mutationId: String
    ): Result<SyncMutationAck> = mutex.withLock {
        val callerUserId = tokenResolver(accessToken)
            ?: return@withLock Result.failure(RlsAuthorizationException("Unauthorized: invalid token"))
        val existing = rows[RowKey(callerUserId, table.tableName, entityId)]
            ?: return@withLock Result.failure(
                RlsAuthorizationException("RLS denied delete on ${table.tableName}:$entityId")
            )
        val tombstoned = withTombstone(existing.payload, deletedAtMs)
        val item = SyncMutationItem(
            mutationId = mutationId,
            entityTable = table.tableName,
            entityId = entityId,
            opType = SyncOpType.DELETE,
            baseVersion = existing.payload.sync.version,
            clientUpdatedAtMs = deletedAtMs,
            payload = tombstoned
        )
        processMutationLocked(callerUserId, item)
    }

    suspend fun statsForUser(userId: String): UserServerStats = mutex.withLock {
        val userRows = rows.values.filter { it.ownerUserId == userId }
        UserServerStats(
            totalRows = userRows.size,
            activeRows = userRows.count { it.payload.sync.deletedAtMs == null },
            ledgerSize = mutationLedger.keys.count { it.first == userId }
        )
    }

    private fun processMutationLocked(
        callerUserId: String,
        item: SyncMutationItem
    ): Result<SyncMutationAck> {
        if (item.payload.sync.ownerUserId != callerUserId) {
            return Result.failure(
                RlsAuthorizationException(
                    "RLS violation: token uid=$callerUserId != payload owner=${item.payload.sync.ownerUserId}"
                )
            )
        }
        val ledgerKey = callerUserId to item.mutationId
        val cachedAck = mutationLedger[ledgerKey]
        if (cachedAck != null) {
            return Result.success(cachedAck.copy(deduplicated = true))
        }

        if (item.entityTable != SyncEntityTable.FOCUS_PROFILES.tableName) {
            val globalKey = item.entityTable to item.entityId
            val existingOwner = entityOwners[globalKey]
            if (existingOwner != null && existingOwner != callerUserId) {
                return Result.failure(
                    RlsAuthorizationException("RLS violation: cross-tenant write/delete on ${item.entityId}")
                )
            }
            entityOwners[globalKey] = callerUserId
        }

        val rowKey = RowKey(callerUserId, item.entityTable, item.entityId)
        val existing = rows[rowKey]
        val ack = if (existing == null) {
            commitIncomingLocked(rowKey, item)
        } else {
            resolveAndCommitLocked(rowKey, existing, item)
        }
        mutationLedger[ledgerKey] = ack
        return Result.success(ack)
    }

    private fun commitIncomingLocked(
        rowKey: RowKey,
        item: SyncMutationItem
    ): SyncMutationAck {
        serverClock += 1L
        val assignedTs = serverClock
        val normalized = withServerTimestamp(item.payload, assignedTs)
        rows[rowKey] = StoredRow(
            ownerUserId = rowKey.ownerUserId,
            entityTable = rowKey.entityTable,
            entityId = rowKey.entityId,
            serverUpdatedAtMs = assignedTs,
            lastMutationId = item.mutationId,
            payload = normalized
        )
        return SyncMutationAck(
            mutationId = item.mutationId,
            entityTable = item.entityTable,
            entityId = item.entityId,
            accepted = true,
            deduplicated = false,
            resolvedVersion = normalized.sync.version,
            serverUpdatedAtMs = assignedTs
        )
    }

    private fun resolveAndCommitLocked(
        rowKey: RowKey,
        existing: StoredRow,
        item: SyncMutationItem
    ): SyncMutationAck {
        val localCandidate = SyncConflictCandidate(
            deletedAtMs = existing.payload.sync.deletedAtMs,
            clientUpdatedAtMs = existing.payload.sync.clientUpdatedAtMs,
            version = existing.payload.sync.version,
            mutationId = existing.lastMutationId
        )
        val incomingCandidate = SyncConflictCandidate(
            deletedAtMs = item.payload.sync.deletedAtMs,
            clientUpdatedAtMs = item.payload.sync.clientUpdatedAtMs,
            version = item.payload.sync.version,
            mutationId = item.mutationId
        )
        return if (SyncConflictResolver.resolve(localCandidate, incomingCandidate) == ConflictWinner.REMOTE) {
            commitIncomingLocked(rowKey, item)
        } else {
            SyncMutationAck(
                mutationId = item.mutationId,
                entityTable = item.entityTable,
                entityId = item.entityId,
                accepted = true,
                deduplicated = false,
                resolvedVersion = existing.payload.sync.version,
                serverUpdatedAtMs = existing.serverUpdatedAtMs,
                winningPayload = existing.payload
            )
        }
    }

    private fun withServerTimestamp(payload: SyncEntityPayload, serverTs: Long): SyncEntityPayload {
        val updatedSync = payload.sync.copy(serverUpdatedAtMs = serverTs)
        return copyWithSync(payload, updatedSync)
    }

    private fun withTombstone(payload: SyncEntityPayload, deletedAtMs: Long): SyncEntityPayload {
        val updatedSync = payload.sync.copy(
            deletedAtMs = deletedAtMs,
            clientUpdatedAtMs = maxOf(payload.sync.clientUpdatedAtMs, deletedAtMs),
            version = payload.sync.version + 1L
        )
        return copyWithSync(payload, updatedSync)
    }

    private fun copyWithSync(payload: SyncEntityPayload, sync: SyncMetadataPayload): SyncEntityPayload =
        when (payload) {
            is FocusProfilePayload -> payload.copy(sync = sync)
            is LearningSubjectPayload -> payload.copy(sync = sync)
            is LearningBookPayload -> payload.copy(sync = sync)
            is LearningTopicPayload -> payload.copy(sync = sync)
            is FocusSessionPayload -> payload.copy(sync = sync)
            is PlannerTaskPayload -> payload.copy(sync = sync)
        }
}
