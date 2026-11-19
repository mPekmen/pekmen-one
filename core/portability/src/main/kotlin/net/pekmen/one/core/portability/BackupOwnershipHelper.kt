package net.pekmen.one.core.portability

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.SyncOutboxEntity
import net.pekmen.one.core.model.BackupImportResult
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.UuidV7

internal object BackupOwnershipHelper {

    private val builtInProfileIds = setOf(
        BuiltInProfiles.STUDY_ID,
        BuiltInProfiles.WORK_ID,
        BuiltInProfiles.SLEEP_ID,
        BuiltInProfiles.PERSONAL_ID
    )

    fun normalizeOwner(rawOwnerId: String?): String? = rawOwnerId?.trim()?.ifEmpty { null }

    fun validateArchiveOwnership(
        archive: PekmenBackupArchive,
        activeOwner: String?
    ): BackupImportResult.Failure? {
        for (profile in archive.profiles) {
            val isCustom = profile.builtInSlug == BuiltInProfileSlug.CUSTOM.name
            if (!isCustom && profile.id !in builtInProfileIds) {
                return BackupImportResult.Failure(
                    BackupValidationError.INVALID_IDENTIFIER,
                    "Unknown built-in profile id ${profile.id}"
                )
            }
        }

        val distinctOwners = collectDistinctOwners(archive)
        if (distinctOwners.size > 1) {
            return BackupImportResult.Failure(
                BackupValidationError.INVALID_IDENTIFIER,
                "Backup archive mixes records from multiple accounts"
            )
        }
        if (activeOwner != null && distinctOwners.any { it != activeOwner }) {
            return BackupImportResult.Failure(
                BackupValidationError.INVALID_IDENTIFIER,
                "Backup archive belongs to a different account"
            )
        }
        return null
    }

    suspend fun assertNoCrossOwnerCollisions(
        database: PekmenDatabase,
        archive: PekmenBackupArchive,
        activeOwner: String?
    ) {
        val profileDao = database.focusProfileDao()
        val learningDao = database.learningDao()
        val topicDao = database.learningTopicDao()
        val sessionDao = database.focusSessionDao()
        val taskDao = database.plannerTaskDao()

        for (p in archive.profiles) {
            val isCustom = p.builtInSlug == BuiltInProfileSlug.CUSTOM.name
            val resolved = if (isCustom) resolveOwner(p.sync.ownerUserId, activeOwner) else null
            profileDao.getById(p.id)?.let { existing ->
                val existingCustom = existing.builtInSlug == BuiltInProfileSlug.CUSTOM.name
                require(existingCustom == isCustom && existing.sync.ownerUserId == resolved) {
                    "Cross-owner profile collision on ${p.id}"
                }
            }
        }
        for (s in archive.subjects) {
            val resolved = resolveOwner(s.sync.ownerUserId, activeOwner)
            learningDao.getSubjectById(s.id)?.let { existing ->
                require(existing.sync.ownerUserId == resolved) { "Cross-owner subject collision on ${s.id}" }
            }
        }
        for (b in archive.books) {
            val resolved = resolveOwner(b.sync.ownerUserId, activeOwner)
            learningDao.getBookById(b.id)?.let { existing ->
                require(existing.sync.ownerUserId == resolved) { "Cross-owner book collision on ${b.id}" }
            }
        }
        for (t in archive.topics) {
            val resolved = resolveOwner(t.sync.ownerUserId, activeOwner)
            topicDao.getTopicById(t.id)?.let { existing ->
                require(existing.sync.ownerUserId == resolved) { "Cross-owner topic collision on ${t.id}" }
            }
        }
        for (ses in archive.sessions) {
            val resolved = resolveOwner(ses.sync.ownerUserId, activeOwner)
            sessionDao.getSessionById(ses.id)?.let { existing ->
                require(existing.sync.ownerUserId == resolved) { "Cross-owner session collision on ${ses.id}" }
            }
        }
        for (task in archive.tasks) {
            val resolved = resolveOwner(task.sync.ownerUserId, activeOwner)
            taskDao.getTaskById(task.id)?.let { existing ->
                require(existing.sync.ownerUserId == resolved) { "Cross-owner task collision on ${task.id}" }
            }
        }
    }

    fun normalizeArchive(
        archive: PekmenBackupArchive,
        activeOwner: String?
    ): PekmenBackupArchive {
        return archive.copy(
            profiles = archive.profiles.map { p ->
                val isCustom = p.builtInSlug == BuiltInProfileSlug.CUSTOM.name
                val sync = if (isCustom) normalizeSync(p.sync, activeOwner) else normalizeBuiltInSync(p.sync)
                p.copy(sync = sync)
            },
            subjects = archive.subjects.map { it.copy(sync = normalizeSync(it.sync, activeOwner)) },
            books = archive.books.map { it.copy(sync = normalizeSync(it.sync, activeOwner)) },
            topics = archive.topics.map { it.copy(sync = normalizeSync(it.sync, activeOwner)) },
            sessions = archive.sessions.map { it.copy(sync = normalizeSync(it.sync, activeOwner)) },
            tasks = archive.tasks.map { it.copy(sync = normalizeSync(it.sync, activeOwner)) }
        )
    }

    suspend fun enqueueClaimedGuestOutbox(
        database: PekmenDatabase,
        originalArchive: PekmenBackupArchive,
        activeOwner: String?,
        nowMs: Long
    ) {
        if (activeOwner == null) return
        val outboxDao = database.syncOutboxDao()
        originalArchive.profiles
            .filter {
                it.builtInSlug == BuiltInProfileSlug.CUSTOM.name && normalizeOwner(it.sync.ownerUserId) == null
            }
            .forEach { outboxDao.enqueue(buildOutbox("focus_profiles", it.id, it.sync, activeOwner, nowMs)) }
        originalArchive.subjects
            .filter { normalizeOwner(it.sync.ownerUserId) == null }
            .forEach { outboxDao.enqueue(buildOutbox("learning_subjects", it.id, it.sync, activeOwner, nowMs)) }
        originalArchive.books
            .filter { normalizeOwner(it.sync.ownerUserId) == null }
            .forEach { outboxDao.enqueue(buildOutbox("learning_books", it.id, it.sync, activeOwner, nowMs)) }
        originalArchive.topics
            .filter { normalizeOwner(it.sync.ownerUserId) == null }
            .forEach { outboxDao.enqueue(buildOutbox("learning_topics", it.id, it.sync, activeOwner, nowMs)) }
        originalArchive.tasks
            .filter { normalizeOwner(it.sync.ownerUserId) == null }
            .forEach { outboxDao.enqueue(buildOutbox("planner_tasks", it.id, it.sync, activeOwner, nowMs)) }
        originalArchive.sessions
            .filter { normalizeOwner(it.sync.ownerUserId) == null }
            .forEach { outboxDao.enqueue(buildOutbox("focus_sessions", it.id, it.sync, activeOwner, nowMs)) }
    }

    private fun collectDistinctOwners(archive: PekmenBackupArchive): Set<String> = buildSet {
        archive.profiles
            .filter { it.builtInSlug == BuiltInProfileSlug.CUSTOM.name }
            .forEach { normalizeOwner(it.sync.ownerUserId)?.let(::add) }
        archive.subjects.forEach { normalizeOwner(it.sync.ownerUserId)?.let(::add) }
        archive.books.forEach { normalizeOwner(it.sync.ownerUserId)?.let(::add) }
        archive.topics.forEach { normalizeOwner(it.sync.ownerUserId)?.let(::add) }
        archive.sessions.forEach { normalizeOwner(it.sync.ownerUserId)?.let(::add) }
        archive.tasks.forEach { normalizeOwner(it.sync.ownerUserId)?.let(::add) }
    }

    private fun resolveOwner(incomingOwner: String?, activeOwner: String?): String? =
        activeOwner ?: normalizeOwner(incomingOwner)

    private fun normalizeBuiltInSync(sync: BackupSyncMetadata): BackupSyncMetadata =
        sync.copy(ownerUserId = null)

    private fun normalizeSync(sync: BackupSyncMetadata, activeOwner: String?): BackupSyncMetadata {
        val incomingOwner = normalizeOwner(sync.ownerUserId)
        val resolvedOwner = activeOwner ?: incomingOwner
        val shouldQueue = activeOwner != null && incomingOwner == null
        return sync.copy(
            ownerUserId = resolvedOwner,
            syncStatus = if (shouldQueue) SyncStatus.PENDING_UPLOAD.name else sync.syncStatus
        )
    }

    private fun buildOutbox(
        table: String,
        entityId: String,
        sync: BackupSyncMetadata,
        ownerId: String,
        nowMs: Long
    ): SyncOutboxEntity {
        val op = if (sync.deletedAtMs != null) "DELETE" else "UPSERT"
        val json = buildJsonObject {
            put("id", entityId)
            put("owner_user_id", ownerId)
            put("client_updated_at_ms", sync.clientUpdatedAtMs)
            put("deleted_at_ms", sync.deletedAtMs)
            put("version", sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = table,
            entityId = entityId,
            opType = op,
            payloadJson = json,
            baseVersion = sync.version,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = ownerId
        )
    }
}
