package net.pekmen.one.core.cloud.sync

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.pekmen.one.core.cloud.CloudRlsGateProvider
import net.pekmen.one.core.cloud.auth.AuthSessionRecord
import net.pekmen.one.core.cloud.auth.CloudRlsGatedException
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncCursorEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity
import net.pekmen.one.core.model.AccountState
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.CloudSyncPhase
import net.pekmen.one.core.model.SyncStateSummary
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7

private data class SyncRuntimeSnapshot(
    val isSyncing: Boolean = false,
    val lastSyncedWallMs: Long? = null,
    val lastError: String? = null
)

/**
 * Offline-first eventual sync coordinator keeping Room as single source of truth.
 * Executes idempotent batch outbox push followed by cursor-based delta pull.
 */
class OutboxSyncEngine(
    private val database: PekmenDatabase,
    private val remoteGateway: SyncRemoteGateway,
    private val rlsGateProvider: CloudRlsGateProvider,
    private val timeProvider: TimeProvider,
    private val pushBatchSize: Int = DEFAULT_PUSH_BATCH_SIZE,
    private val pullPageSize: Int = DEFAULT_PULL_PAGE_SIZE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val syncOutboxDao = database.syncOutboxDao()
    private val recordStore = RoomSyncRecordStore(database)
    private val syncMutex = Mutex()
    private val runtimeStatus = MutableStateFlow(SyncRuntimeSnapshot())

    fun observeSyncState(accountState: Flow<AccountState>): Flow<SyncStateSummary> {
        return combine(
            accountState,
            syncOutboxDao.observeOutbox(),
            runtimeStatus
        ) { account, outboxRows, runtime ->
            val gateStatus = rlsGateProvider.currentGateStatus()
            val ownerId = (account as? AccountState.SignedIn)?.userId
            val pendingCount = outboxRows.count { it.ownerUserId == ownerId }
            val phase = resolveSyncPhase(account, gateStatus.isLiveCloudEnabled, runtime)
            SyncStateSummary(
                phase = phase,
                pendingMutationCount = pendingCount,
                lastSyncedWallMs = runtime.lastSyncedWallMs,
                lastError = runtime.lastError
            )
        }
    }

    fun resetRuntimeStatus() {
        runtimeStatus.value = SyncRuntimeSnapshot()
    }

    suspend fun countUnclaimedGuestRecords(): Int = withContext(ioDispatcher) {
        recordStore.countUnclaimedGuestRecords()
    }

    suspend fun readUnclaimedGuestPayloads(
        ownerUserId: String,
        updatedAtMs: Long
    ): List<SyncEntityPayload> = recordStore.readUnclaimedGuestPayloads(ownerUserId, updatedAtMs)

    suspend fun enqueueMutation(
        payload: SyncEntityPayload,
        ownerUserId: String,
        nowMs: Long
    ) {
        val opType = if (payload.sync.deletedAtMs != null) SyncOpType.DELETE else SyncOpType.UPSERT
        syncOutboxDao.enqueue(
            SyncOutboxEntity(
                mutationId = UuidV7.generate(nowMs),
                entityTable = payload.table.tableName,
                entityId = payload.id,
                opType = opType.name,
                payloadJson = SyncPayloadJson.encodePayload(payload),
                baseVersion = payload.sync.version,
                attemptCount = 0,
                createdAtMs = nowMs,
                ownerUserId = ownerUserId,
                lastError = null
            )
        )
    }

    suspend fun sync(session: AuthSessionRecord): Result<Int> = withContext(ioDispatcher) {
        val gate = rlsGateProvider.currentGateStatus()
        if (!gate.isLiveCloudEnabled) {
            return@withContext Result.failure(CloudRlsGatedException(gate))
        }
        syncMutex.withLock {
            runtimeStatus.value = runtimeStatus.value.copy(isSyncing = true, lastError = null)
            val pushResult = pushAllPending(session)
            val pushedCount = pushResult.getOrElse { err ->
                runtimeStatus.value = runtimeStatus.value.copy(isSyncing = false, lastError = err.message)
                return@withLock Result.failure(err)
            }
            val pullResult = pullAllTables(session)
            val pulledCount = pullResult.getOrElse { err ->
                runtimeStatus.value = runtimeStatus.value.copy(isSyncing = false, lastError = err.message)
                return@withLock Result.failure(err)
            }
            runtimeStatus.value = SyncRuntimeSnapshot(
                isSyncing = false,
                lastSyncedWallMs = timeProvider.wallTimeMs(),
                lastError = null
            )
            Result.success(pushedCount + pulledCount)
        }
    }

    private fun resolveSyncPhase(
        account: AccountState,
        isLiveCloudEnabled: Boolean,
        runtime: SyncRuntimeSnapshot
    ): CloudSyncPhase = when {
        account is AccountState.Guest -> CloudSyncPhase.OFFLINE_GUEST
        !isLiveCloudEnabled -> CloudSyncPhase.GATED_CLOUD_UNVERIFIED
        runtime.isSyncing -> CloudSyncPhase.SYNCING
        runtime.lastError != null -> CloudSyncPhase.ERROR
        else -> CloudSyncPhase.IDLE
    }

    private suspend fun pushAllPending(session: AuthSessionRecord): Result<Int> {
        var totalAccepted = 0
        while (true) {
            val allPending = syncOutboxDao.getPendingBatchForOwner(session.userId, PREFETCH_OUTBOX_LIMIT)
            if (allPending.isEmpty()) break
            val sortedBatch = allPending
                .sortedWith(compareBy<SyncOutboxEntity>({ tablePriority(it.entityTable) }, { it.createdAtMs }))
                .take(pushBatchSize)
            val items = sortedBatch.map { buildMutationItem(it, session.userId) }
            val response = remoteGateway.pushMutations(session.accessToken, SyncPushRequest(items))
                .getOrElse { error ->
                    sortedBatch.forEach { syncOutboxDao.markFailedAttempt(it.mutationId, error.message) }
                    return Result.failure(error)
                }
            val outcome = applyPushResponse(session.userId, items, response)
            totalAccepted += outcome.acceptedCount
            if (outcome.rejectionError != null) {
                return Result.failure(IllegalStateException(outcome.rejectionError))
            }
        }
        return Result.success(totalAccepted)
    }

    private suspend fun applyPushResponse(
        ownerUserId: String,
        items: List<SyncMutationItem>,
        response: SyncPushResponse
    ): BatchPushOutcome {
        val itemsByMutationId = items.associateBy { it.mutationId }
        val acceptedIds = mutableListOf<String>()
        var firstError: String? = null
        database.withTransaction {
            for (ack in response.results) {
                if (ack.accepted) {
                    acceptedIds += ack.mutationId
                    val item = itemsByMutationId[ack.mutationId]
                    val winner = ack.winningPayload
                    if (winner != null) {
                        if (winner.sync.ownerUserId != ownerUserId) {
                            throw RlsAuthorizationException(
                                "Cross-tenant winningPayload rejected for ${ack.entityTable}:${ack.entityId}"
                            )
                        }
                        recordStore.insertOrUpdateRemotePayload(winner, ack.serverUpdatedAtMs)
                    } else if (item != null) {
                        recordStore.markSyncedIfUnchanged(
                            tableName = ack.entityTable,
                            entityId = ack.entityId,
                            expectedClientUpdatedAtMs = item.clientUpdatedAtMs,
                            serverUpdatedAtMs = ack.serverUpdatedAtMs,
                            resolvedVersion = ack.resolvedVersion
                        )
                    }
                } else {
                    syncOutboxDao.markFailedAttempt(ack.mutationId, ack.error)
                    if (firstError == null) {
                        firstError = ack.error ?: "Remote mutation rejected for ${ack.entityTable}:${ack.entityId}"
                    }
                }
            }
            if (acceptedIds.isNotEmpty()) {
                syncOutboxDao.deleteByMutationIds(acceptedIds)
            }
            if (firstError == null && acceptedIds.size < items.size) {
                firstError = "Partial batch acknowledgement (${acceptedIds.size}/${items.size})"
            }
        }
        return BatchPushOutcome(acceptedIds.size, firstError)
    }

    private suspend fun pullAllTables(session: AuthSessionRecord): Result<Int> {
        var totalPulled = 0
        for (table in SyncEntityTable.entries) {
            val tableCount = pullSingleTable(session, table).getOrElse { error ->
                return Result.failure(error)
            }
            totalPulled += tableCount
        }
        return Result.success(totalPulled)
    }

    private suspend fun pullSingleTable(
        session: AuthSessionRecord,
        table: SyncEntityTable
    ): Result<Int> {
        val initialCursor = syncOutboxDao.getCursor(table.tableName)
        var afterTs = initialCursor?.lastServerUpdatedAtMs ?: 0L
        var afterId = initialCursor?.lastEntityId ?: ""
        var pulledForTable = 0
        while (true) {
            val request = SyncPullRequest(
                entityTable = table.tableName,
                afterServerUpdatedAtMs = afterTs,
                afterEntityId = afterId,
                limit = pullPageSize
            )
            val response = remoteGateway.pullDeltas(session.accessToken, request).getOrElse { error ->
                return Result.failure(error)
            }
            if (response.records.isNotEmpty()) {
                applyPullPage(session.userId, table, response)
                pulledForTable += response.records.size
                afterTs = response.nextServerUpdatedAtMs
                afterId = response.nextEntityId
            }
            if (!response.hasMore || response.records.isEmpty()) break
        }
        return Result.success(pulledForTable)
    }

    private suspend fun applyPullPage(
        ownerUserId: String,
        table: SyncEntityTable,
        response: SyncPullResponse
    ) {
        val now = timeProvider.wallTimeMs()
        database.withTransaction {
            val supersededOutboxIds = mutableListOf<String>()
            for (delta in response.records) {
                if (delta.payload.sync.ownerUserId != ownerUserId) {
                    throw RlsAuthorizationException("Cross-tenant delta rejected for table ${table.tableName}")
                }
                val localState = recordStore.readLocalConflictState(table, delta.entityId, ownerUserId)
                if (localState == null) {
                    recordStore.insertOrUpdateRemotePayload(delta.payload, delta.serverUpdatedAtMs)
                } else {
                    val remoteCandidate = SyncConflictCandidate(
                        deletedAtMs = delta.payload.sync.deletedAtMs,
                        clientUpdatedAtMs = delta.payload.sync.clientUpdatedAtMs,
                        version = delta.payload.sync.version,
                        mutationId = delta.mutationId
                    )
                    val winner = SyncConflictResolver.resolve(localState.candidate, remoteCandidate)
                    if (winner == ConflictWinner.REMOTE) {
                        recordStore.insertOrUpdateRemotePayload(delta.payload, delta.serverUpdatedAtMs)
                        localState.pendingMutationId?.let { supersededOutboxIds += it }
                    }
                }
            }
            if (supersededOutboxIds.isNotEmpty()) {
                syncOutboxDao.deleteByMutationIds(supersededOutboxIds)
            }
            syncOutboxDao.upsertCursor(
                SyncCursorEntity(
                    entityTable = table.tableName,
                    lastServerUpdatedAtMs = response.nextServerUpdatedAtMs,
                    lastEntityId = response.nextEntityId,
                    updatedAtMs = now
                )
            )
        }
    }

    private fun buildMutationItem(outbox: SyncOutboxEntity, userId: String): SyncMutationItem {
        val table = SyncEntityTable.fromTableName(outbox.entityTable)
        val payload = table?.let { recordStore.readPayloadById(it, outbox.entityId, userId) }
            ?: SyncPayloadJson.decodePayload(outbox.payloadJson)
        val opType = if (payload.sync.deletedAtMs != null || outbox.opType == SyncOpType.DELETE.name) {
            SyncOpType.DELETE
        } else {
            SyncOpType.UPSERT
        }
        return SyncMutationItem(
            mutationId = outbox.mutationId,
            entityTable = outbox.entityTable,
            entityId = outbox.entityId,
            opType = opType,
            baseVersion = outbox.baseVersion,
            clientUpdatedAtMs = payload.sync.clientUpdatedAtMs,
            payload = payload
        )
    }

    private fun tablePriority(tableName: String): Int = when (tableName) {
        SyncEntityTable.FOCUS_PROFILES.tableName -> PRIORITY_PROFILES
        SyncEntityTable.LEARNING_SUBJECTS.tableName -> PRIORITY_SUBJECTS
        SyncEntityTable.LEARNING_BOOKS.tableName -> PRIORITY_BOOKS
        SyncEntityTable.LEARNING_TOPICS.tableName -> PRIORITY_TOPICS
        SyncEntityTable.PLANNER_TASKS.tableName -> PRIORITY_TASKS
        else -> PRIORITY_SESSIONS
    }

    private data class BatchPushOutcome(
        val acceptedCount: Int,
        val rejectionError: String?
    )

    companion object {
        const val DEFAULT_PUSH_BATCH_SIZE = 20
        const val DEFAULT_PULL_PAGE_SIZE = 100
        private const val PREFETCH_OUTBOX_LIMIT = 500
        private const val PRIORITY_PROFILES = 0
        private const val PRIORITY_SUBJECTS = 1
        private const val PRIORITY_BOOKS = 2
        private const val PRIORITY_TOPICS = 3
        private const val PRIORITY_TASKS = 4
        private const val PRIORITY_SESSIONS = 5
    }
}

internal data class LocalConflictState(
    val candidate: SyncConflictCandidate,
    val pendingMutationId: String?
)

/**
 * Direct Room/SQLite helper allowing OutboxSyncEngine to inspect soft-deleted tombstones
 * and apply in-place updates without triggering SQLite REPLACE cascade-deletes.
 */
internal class RoomSyncRecordStore(
    private val database: PekmenDatabase
) {
    private val profileDao = database.focusProfileDao()
    private val learningDao = database.learningDao()
    private val topicDao = database.learningTopicDao()
    private val sessionDao = database.focusSessionDao()
    private val taskDao = database.plannerTaskDao()
    private val portabilityDao = database.portabilityDao()

    fun countUnclaimedGuestRecords(): Int {
        val tables = listOf(
            "focus_profiles" to "deleted_at_ms IS NULL AND owner_user_id IS NULL AND built_in_slug = 'CUSTOM'",
            "learning_subjects" to "deleted_at_ms IS NULL AND owner_user_id IS NULL",
            "learning_books" to "deleted_at_ms IS NULL AND owner_user_id IS NULL",
            "learning_topics" to "deleted_at_ms IS NULL AND owner_user_id IS NULL",
            "focus_sessions" to "deleted_at_ms IS NULL AND owner_user_id IS NULL",
            "planner_tasks" to "deleted_at_ms IS NULL AND owner_user_id IS NULL"
        )
        return tables.sumOf { (table, predicate) ->
            database.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table WHERE $predicate")).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    fun readUnclaimedGuestPayloads(ownerUserId: String, updatedAtMs: Long): List<SyncEntityPayload> {
        val result = mutableListOf<SyncEntityPayload>()
        queryPayloads(
            sql = "SELECT * FROM focus_profiles WHERE owner_user_id IS NULL AND built_in_slug = 'CUSTOM'",
            args = emptyArray()
        ) { cursor ->
            result += cursor.toFocusProfilePayload(ownerOverride = ownerUserId, claimedAtMs = updatedAtMs)
        }
        queryPayloads("SELECT * FROM learning_subjects WHERE owner_user_id IS NULL", emptyArray()) { cursor ->
            result += cursor.toLearningSubjectPayload(ownerOverride = ownerUserId, claimedAtMs = updatedAtMs)
        }
        queryPayloads("SELECT * FROM learning_books WHERE owner_user_id IS NULL", emptyArray()) { cursor ->
            result += cursor.toLearningBookPayload(ownerOverride = ownerUserId, claimedAtMs = updatedAtMs)
        }
        queryPayloads("SELECT * FROM learning_topics WHERE owner_user_id IS NULL", emptyArray()) { cursor ->
            result += cursor.toLearningTopicPayload(ownerOverride = ownerUserId, claimedAtMs = updatedAtMs)
        }
        queryPayloads("SELECT * FROM planner_tasks WHERE owner_user_id IS NULL", emptyArray()) { cursor ->
            result += cursor.toPlannerTaskPayload(ownerOverride = ownerUserId, claimedAtMs = updatedAtMs)
        }
        queryPayloads(
            "SELECT * FROM focus_sessions WHERE owner_user_id IS NULL AND status IN ('COMPLETED', 'ABANDONED')",
            emptyArray()
        ) { cursor ->
            val sessionId = cursor.str("id")
            val segments = readSegmentsForSession(sessionId)
            result += cursor.toFocusSessionPayload(segments, ownerOverride = ownerUserId, claimedAtMs = updatedAtMs)
        }
        return result
    }

    fun readPayloadById(
        table: SyncEntityTable,
        entityId: String,
        fallbackOwnerUserId: String
    ): SyncEntityPayload? {
        val sql = "SELECT * FROM ${table.tableName} WHERE id = ? LIMIT 1"
        var payload: SyncEntityPayload? = null
        queryPayloads(sql, arrayOf<Any>(entityId)) { cursor ->
            val owner = cursor.strOrNull("owner_user_id") ?: fallbackOwnerUserId
            payload = when (table) {
                SyncEntityTable.FOCUS_PROFILES -> cursor.toFocusProfilePayload(ownerOverride = owner)
                SyncEntityTable.LEARNING_SUBJECTS -> cursor.toLearningSubjectPayload(ownerOverride = owner)
                SyncEntityTable.LEARNING_BOOKS -> cursor.toLearningBookPayload(ownerOverride = owner)
                SyncEntityTable.LEARNING_TOPICS -> cursor.toLearningTopicPayload(ownerOverride = owner)
                SyncEntityTable.PLANNER_TASKS -> cursor.toPlannerTaskPayload(ownerOverride = owner)
                SyncEntityTable.FOCUS_SESSIONS -> {
                    val segments = readSegmentsForSession(entityId)
                    cursor.toFocusSessionPayload(segments, ownerOverride = owner)
                }
            }
        }
        return payload
    }

    fun readLocalConflictState(
        table: SyncEntityTable,
        entityId: String,
        ownerUserId: String
    ): LocalConflictState? {
        val sql = "SELECT deleted_at_ms, client_updated_at_ms, version, owner_user_id " +
            "FROM ${table.tableName} WHERE id = ? LIMIT 1"
        val row = database.query(SimpleSQLiteQuery(sql, arrayOf<Any>(entityId))).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val rowOwner = cursor.strOrNull("owner_user_id")
            if (rowOwner != null && rowOwner != ownerUserId) {
                throw RlsAuthorizationException("Local ID collision across tenants for ${table.tableName}:$entityId")
            }
            Triple(
                cursor.lngOrNull("deleted_at_ms"),
                cursor.lng("client_updated_at_ms"),
                cursor.lng("version")
            )
        }
        val outboxSql = "SELECT mutation_id FROM sync_outbox WHERE entity_table = ? AND entity_id = ? LIMIT 1"
        val pendingMutationId = database.query(
            SimpleSQLiteQuery(outboxSql, arrayOf<Any>(table.tableName, entityId))
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.str("mutation_id") else null
        }
        return LocalConflictState(
            candidate = SyncConflictCandidate(
                deletedAtMs = row.first,
                clientUpdatedAtMs = row.second,
                version = row.third,
                mutationId = pendingMutationId ?: ""
            ),
            pendingMutationId = pendingMutationId
        )
    }

    fun markSyncedIfUnchanged(
        tableName: String,
        entityId: String,
        expectedClientUpdatedAtMs: Long,
        serverUpdatedAtMs: Long,
        resolvedVersion: Long
    ) {
        val table = SyncEntityTable.fromTableName(tableName) ?: return
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ${table.tableName} " +
                "SET sync_status = ?, server_updated_at_ms = ?, version = ? " +
                "WHERE id = ? AND client_updated_at_ms = ?",
            arrayOf<Any>(
                SyncStatus.SYNCED.name,
                serverUpdatedAtMs,
                resolvedVersion,
                entityId,
                expectedClientUpdatedAtMs
            )
        )
    }

    suspend fun insertOrUpdateRemotePayload(payload: SyncEntityPayload, serverUpdatedAtMs: Long) {
        val exists = verifyRemotePayloadWriteSafety(payload)
        val syncMeta = payload.sync.toEntity(serverUpdatedAtMs = serverUpdatedAtMs)
        when (payload) {
            is FocusProfilePayload -> {
                val entity = payload.toEntity(syncMeta)
                if (exists) profileDao.update(entity) else profileDao.insert(entity)
            }
            is LearningSubjectPayload -> {
                val entity = payload.toEntity(syncMeta)
                if (exists) learningDao.updateSubject(entity) else learningDao.upsertSubject(entity)
            }
            is LearningBookPayload -> {
                val entity = payload.toEntity(syncMeta)
                if (exists) learningDao.updateBook(entity) else learningDao.upsertBook(entity)
            }
            is LearningTopicPayload -> topicDao.upsertTopic(payload.toEntity(syncMeta))
            is PlannerTaskPayload -> {
                val entity = payload.toEntity(syncMeta)
                if (exists) taskDao.updateTask(entity) else taskDao.upsertTask(entity)
            }
            is FocusSessionPayload -> {
                val entity = payload.toEntity(syncMeta)
                if (exists) sessionDao.updateSession(entity) else sessionDao.insertSession(entity)
                if (payload.segments.isNotEmpty()) {
                    portabilityDao.upsertSegments(payload.segments.map { it.toEntity() })
                }
            }
        }
    }

    private fun verifyRemotePayloadWriteSafety(payload: SyncEntityPayload): Boolean {
        if (payload is FocusProfilePayload) {
            val isCustom = payload.builtInSlug == BuiltInProfileSlug.CUSTOM.name &&
                payload.id !in BUILT_IN_PROFILE_IDS
            rlsRequire(isCustom) { "Built-in profiles cannot be overwritten remotely: ${payload.id}" }
        }
        if (payload is FocusSessionPayload) {
            rlsRequire(payload.segments.all { it.sessionId == payload.id }) {
                "Segment session_id mismatch on ${payload.id}"
            }
        }
        val sql = "SELECT owner_user_id FROM ${payload.table.tableName} WHERE id = ? LIMIT 1"
        return database.query(SimpleSQLiteQuery(sql, arrayOf<Any>(payload.id))).use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val localOwner = cursor.strOrNull("owner_user_id")
            rlsRequire(localOwner == payload.sync.ownerUserId) {
                "Local row owner mismatch for ${payload.table.tableName}:${payload.id}"
            }
            true
        }
    }

    private inline fun rlsRequire(condition: Boolean, message: () -> String) {
        if (!condition) throw RlsAuthorizationException(message())
    }

    private companion object {
        val BUILT_IN_PROFILE_IDS = setOf(
            BuiltInProfiles.STUDY_ID,
            BuiltInProfiles.WORK_ID,
            BuiltInProfiles.SLEEP_ID,
            BuiltInProfiles.PERSONAL_ID
        )
    }

    private fun readSegmentsForSession(sessionId: String): List<FocusSessionSegmentPayload> {
        val list = mutableListOf<FocusSessionSegmentPayload>()
        queryPayloads(
            "SELECT * FROM focus_session_segments WHERE session_id = ? ORDER BY segment_index ASC",
            arrayOf<Any>(sessionId)
        ) { cursor ->
            list += FocusSessionSegmentPayload(
                id = cursor.str("id"),
                sessionId = cursor.str("session_id"),
                segmentIndex = cursor.intVal("segment_index"),
                phase = cursor.str("phase"),
                startedAtWallMs = cursor.lng("started_at_wall_ms"),
                startedAtElapsedMs = cursor.lng("started_at_elapsed_ms"),
                bootCount = cursor.intVal("boot_count"),
                lastCheckpointedDurationMs = cursor.lng("last_checkpointed_duration_ms"),
                plannedDurationMs = cursor.lngOrNull("planned_duration_ms"),
                endedAtWallMs = cursor.lngOrNull("ended_at_wall_ms"),
                durationMs = cursor.lngOrNull("duration_ms")
            )
        }
        return list
    }

    private inline fun queryPayloads(
        sql: String,
        args: Array<Any>,
        rowConsumer: (Cursor) -> Unit
    ) {
        database.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            while (cursor.moveToNext()) {
                rowConsumer(cursor)
            }
        }
    }
}

private fun Cursor.readSyncMeta(
    ownerOverride: String? = null,
    claimedAtMs: Long? = null
): SyncMetadataPayload = SyncMetadataPayload(
    ownerUserId = ownerOverride ?: requireNotNull(strOrNull("owner_user_id")),
    createdAtMs = lng("created_at_ms"),
    clientUpdatedAtMs = claimedAtMs ?: lng("client_updated_at_ms"),
    serverUpdatedAtMs = lngOrNull("server_updated_at_ms"),
    deletedAtMs = lngOrNull("deleted_at_ms"),
    version = if (claimedAtMs != null) lng("version") + 1L else lng("version")
)

private fun Cursor.toFocusProfilePayload(
    ownerOverride: String? = null,
    claimedAtMs: Long? = null
) = FocusProfilePayload(
    id = str("id"),
    builtInSlug = str("built_in_slug"),
    customName = strOrNull("custom_name"),
    colorHex = lng("color_hex"),
    iconKey = str("icon_key"),
    defaultMode = str("default_mode"),
    workMinutes = intVal("work_minutes"),
    shortBreakMinutes = intVal("short_break_minutes"),
    longBreakMinutes = intVal("long_break_minutes"),
    cyclesBeforeLongBreak = intVal("cycles_before_long_break"),
    dndEnabled = bool("dnd_enabled"),
    sortOrder = intVal("sort_order"),
    isArchived = bool("is_archived"),
    sync = readSyncMeta(ownerOverride, claimedAtMs)
)

private fun Cursor.toLearningSubjectPayload(
    ownerOverride: String? = null,
    claimedAtMs: Long? = null
) = LearningSubjectPayload(
    id = str("id"),
    title = str("title"),
    colorHex = lng("color_hex"),
    weeklyGoalMinutes = intVal("weekly_goal_minutes"),
    sortOrder = intVal("sort_order"),
    isArchived = bool("is_archived"),
    sync = readSyncMeta(ownerOverride, claimedAtMs)
)

private fun Cursor.toLearningBookPayload(
    ownerOverride: String? = null,
    claimedAtMs: Long? = null
) = LearningBookPayload(
    id = str("id"),
    subjectId = str("subject_id"),
    title = str("title"),
    author = strOrNull("author"),
    totalPages = intVal("total_pages"),
    currentPage = intVal("current_page"),
    totalChapters = intVal("total_chapters"),
    completedChapters = intVal("completed_chapters"),
    isCompleted = bool("is_completed"),
    sync = readSyncMeta(ownerOverride, claimedAtMs)
)

private fun Cursor.toLearningTopicPayload(
    ownerOverride: String? = null,
    claimedAtMs: Long? = null
) = LearningTopicPayload(
    id = str("id"),
    subjectId = str("subject_id"),
    bookId = strOrNull("book_id"),
    title = str("title"),
    status = str("status"),
    sortOrder = intVal("sort_order"),
    sync = readSyncMeta(ownerOverride, claimedAtMs)
)

private fun Cursor.toPlannerTaskPayload(
    ownerOverride: String? = null,
    claimedAtMs: Long? = null
) = PlannerTaskPayload(
    id = str("id"),
    title = str("title"),
    notes = strOrNull("notes"),
    subjectId = strOrNull("subject_id"),
    dueAtWallMs = lngOrNull("due_at_wall_ms"),
    reminderAtWallMs = lngOrNull("reminder_at_wall_ms"),
    recurrenceRule = str("recurrence_rule"),
    status = str("status"),
    completedAtWallMs = lngOrNull("completed_at_wall_ms"),
    priority = intVal("priority"),
    sync = readSyncMeta(ownerOverride, claimedAtMs)
)

private fun Cursor.toFocusSessionPayload(
    segments: List<FocusSessionSegmentPayload>,
    ownerOverride: String? = null,
    claimedAtMs: Long? = null
) = FocusSessionPayload(
    id = str("id"),
    profileId = str("profile_id"),
    subjectId = strOrNull("subject_id"),
    bookId = strOrNull("book_id"),
    topicId = strOrNull("topic_id"),
    taskId = strOrNull("task_id"),
    bookStartPage = intOrNull("book_start_page"),
    bookEndPage = intOrNull("book_end_page"),
    sessionMode = str("session_mode"),
    status = str("status"),
    currentPhase = str("current_phase"),
    awaitingNextPhase = strOrNull("awaiting_next_phase"),
    completedFocusCycles = intVal("completed_focus_cycles"),
    startedAtWallMs = lng("started_at_wall_ms"),
    endedAtWallMs = lngOrNull("ended_at_wall_ms"),
    effectiveDayDate = str("effective_day_date"),
    plannedFocusSec = lngOrNull("planned_focus_sec"),
    actualFocusSec = lng("actual_focus_sec"),
    actualBreakSec = lng("actual_break_sec"),
    pauseCount = intVal("pause_count"),
    isManualEntry = bool("is_manual_entry"),
    wasManuallyAdjusted = bool("was_manually_adjusted"),
    interruptedByReboot = bool("interrupted_by_reboot"),
    focusRating = intOrNull("focus_rating"),
    reflectionNote = strOrNull("reflection_note"),
    segments = segments,
    sync = readSyncMeta(ownerOverride, claimedAtMs)
)

private fun SyncMetadataPayload.toEntity(serverUpdatedAtMs: Long) = SyncMetadataEntity(
    ownerUserId = ownerUserId,
    createdAtMs = createdAtMs,
    clientUpdatedAtMs = clientUpdatedAtMs,
    serverUpdatedAtMs = serverUpdatedAtMs,
    deletedAtMs = deletedAtMs,
    version = version,
    syncStatus = SyncStatus.SYNCED.name
)

private fun FocusProfilePayload.toEntity(syncMeta: SyncMetadataEntity) = FocusProfileEntity(
    id = id,
    builtInSlug = builtInSlug,
    customName = customName,
    colorHex = colorHex,
    iconKey = iconKey,
    defaultMode = defaultMode,
    workMinutes = workMinutes,
    shortBreakMinutes = shortBreakMinutes,
    longBreakMinutes = longBreakMinutes,
    cyclesBeforeLongBreak = cyclesBeforeLongBreak,
    dndEnabled = dndEnabled,
    sortOrder = sortOrder,
    isArchived = isArchived,
    sync = syncMeta
)

private fun LearningSubjectPayload.toEntity(syncMeta: SyncMetadataEntity) = LearningSubjectEntity(
    id = id,
    title = title,
    colorHex = colorHex,
    weeklyGoalMinutes = weeklyGoalMinutes,
    sortOrder = sortOrder,
    isArchived = isArchived,
    sync = syncMeta
)

private fun LearningBookPayload.toEntity(syncMeta: SyncMetadataEntity) = LearningBookEntity(
    id = id,
    subjectId = subjectId,
    title = title,
    author = author,
    totalPages = totalPages,
    currentPage = currentPage,
    totalChapters = totalChapters,
    completedChapters = completedChapters,
    isCompleted = isCompleted,
    sync = syncMeta
)

private fun LearningTopicPayload.toEntity(syncMeta: SyncMetadataEntity) = LearningTopicEntity(
    id = id,
    subjectId = subjectId,
    bookId = bookId,
    title = title,
    status = status,
    sortOrder = sortOrder,
    sync = syncMeta
)

private fun PlannerTaskPayload.toEntity(syncMeta: SyncMetadataEntity) = PlannerTaskEntity(
    id = id,
    title = title,
    notes = notes,
    subjectId = subjectId,
    dueAtWallMs = dueAtWallMs,
    reminderAtWallMs = reminderAtWallMs,
    recurrenceRule = recurrenceRule,
    status = status,
    completedAtWallMs = completedAtWallMs,
    priority = priority,
    sync = syncMeta
)

private fun FocusSessionPayload.toEntity(syncMeta: SyncMetadataEntity) = FocusSessionEntity(
    id = id,
    profileId = profileId,
    subjectId = subjectId,
    bookId = bookId,
    topicId = topicId,
    taskId = taskId,
    bookStartPage = bookStartPage,
    bookEndPage = bookEndPage,
    sessionMode = sessionMode,
    status = status,
    currentPhase = currentPhase,
    awaitingNextPhase = awaitingNextPhase,
    completedFocusCycles = completedFocusCycles,
    startedAtWallMs = startedAtWallMs,
    endedAtWallMs = endedAtWallMs,
    effectiveDayDate = effectiveDayDate,
    plannedFocusSec = plannedFocusSec,
    actualFocusSec = actualFocusSec,
    actualBreakSec = actualBreakSec,
    pauseCount = pauseCount,
    isManualEntry = isManualEntry,
    wasManuallyAdjusted = wasManuallyAdjusted,
    interruptedByReboot = interruptedByReboot,
    focusRating = focusRating,
    reflectionNote = reflectionNote,
    sync = syncMeta
)

private fun FocusSessionSegmentPayload.toEntity() = FocusSessionSegmentEntity(
    id = id,
    sessionId = sessionId,
    segmentIndex = segmentIndex,
    phase = phase,
    startedAtWallMs = startedAtWallMs,
    startedAtElapsedMs = startedAtElapsedMs,
    bootCount = bootCount,
    lastCheckpointedDurationMs = lastCheckpointedDurationMs,
    plannedDurationMs = plannedDurationMs,
    endedAtWallMs = endedAtWallMs,
    durationMs = durationMs
)

private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col))

private fun Cursor.strOrNull(col: String): String? {
    val idx = getColumnIndexOrThrow(col)
    return if (isNull(idx)) null else getString(idx)
}

private fun Cursor.lng(col: String): Long = getLong(getColumnIndexOrThrow(col))

private fun Cursor.lngOrNull(col: String): Long? {
    val idx = getColumnIndexOrThrow(col)
    return if (isNull(idx)) null else getLong(idx)
}

private fun Cursor.intVal(col: String): Int = getInt(getColumnIndexOrThrow(col))

private fun Cursor.intOrNull(col: String): Int? {
    val idx = getColumnIndexOrThrow(col)
    return if (isNull(idx)) null else getInt(idx)
}

private fun Cursor.bool(col: String): Boolean = getInt(getColumnIndexOrThrow(col)) != 0
