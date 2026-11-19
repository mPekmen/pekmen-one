package net.pekmen.one.core.data.planner

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.AgendaOverview
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7
import net.pekmen.one.core.platform.planner.CalendarInstancesReader
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerRepositoryImpl(
    private val database: PekmenDatabase,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val timeProvider: TimeProvider,
    private val reminderGateway: TaskReminderGateway,
    private val calendarReader: CalendarInstancesReader
) : PlannerRepository {

    private val plannerTaskDao = database.plannerTaskDao()
    private val learningDao = database.learningDao()
    private val syncOutboxDao = database.syncOutboxDao()
    private val stateMutex = Mutex()
    private val json = Json { encodeDefaults = true }

    private val activeOwnerFlow: Flow<String?> = preferencesDataStore.preferences
        .map { prefs -> prefs.activeOwnerUserId?.takeIf { it.isNotBlank() } }
        .distinctUntilChanged()

    override fun observeTasks(): Flow<List<PlannerTask>> {
        return activeOwnerFlow.flatMapLatest { ownerId ->
            plannerTaskDao.observeTasksByOwner(ownerId).map { entities ->
                entities.map { it.toDomain() }
            }
        }
    }

    override fun observeAvailableSubjects(): Flow<List<LearningSubject>> {
        return activeOwnerFlow.flatMapLatest { ownerId ->
            learningDao.observeActiveSubjectsByOwner(ownerId).map { entities ->
                entities.map { it.toDomain() }
            }
        }
    }

    override fun observeAgenda(beginWallMs: Long, endWallMs: Long): Flow<AgendaOverview> {
        return observeTasks().map { tasks ->
            AgendaOverview(
                openTasks = tasks.filter { it.status == TaskStatus.OPEN },
                completedTasks = tasks.filter { it.status == TaskStatus.COMPLETED }
                    .sortedByDescending { it.completedAtWallMs ?: 0L },
                calendarResult = calendarReader.readInstances(beginWallMs, endWallMs)
            )
        }
    }

    override suspend fun getTaskById(taskId: String): PlannerTask? {
        val ownerId = currentOwnerUserId()
        val entity = plannerTaskDao.getTaskById(taskId) ?: return null
        return entity.takeIf { it.sync.ownerUserId == ownerId }?.toDomain()
    }

    override suspend fun saveTask(request: SavePlannerTaskRequest): Result<PlannerTask> =
        stateMutex.withLock {
            runCatching {
                val cleanTitle = request.title.trim().take(MAX_TITLE_LENGTH)
                require(cleanTitle.isNotEmpty()) { "Task title cannot be blank" }
                val cleanNotes = request.notes?.trim()?.take(MAX_NOTES_LENGTH)?.ifEmpty { null }
                val clampedPriority = request.priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY)
                val now = timeProvider.wallTimeMs()
                val ownerId = currentOwnerUserId()
                val syncStatus = syncStatusFor(ownerId)

                lateinit var savedEntity: PlannerTaskEntity
                database.withTransaction {
                    request.subjectId?.let { subjectId ->
                        learningDao.getSubjectById(subjectId)?.let { subject ->
                            check(subject.sync.ownerUserId == ownerId && subject.sync.deletedAtMs == null) {
                                "Subject belongs to a different owner or is deleted"
                            }
                        }
                    }
                    val existingId = request.existingId
                    if (existingId != null) {
                        val existing = requireNotNull(plannerTaskDao.getTaskById(existingId)) {
                            "Task not found: $existingId"
                        }
                        check(existing.sync.ownerUserId == ownerId) {
                            "Task belongs to a different owner"
                        }
                        savedEntity = existing.copy(
                            title = cleanTitle,
                            notes = cleanNotes,
                            subjectId = request.subjectId,
                            dueAtWallMs = request.dueAtWallMs,
                            reminderAtWallMs = request.reminderAtWallMs,
                            recurrenceRule = request.recurrenceRule.name,
                            priority = clampedPriority,
                            sync = existing.sync.copy(
                                clientUpdatedAtMs = now,
                                version = existing.sync.version + 1L,
                                syncStatus = syncStatus
                            )
                        )
                        plannerTaskDao.updateTask(savedEntity)
                    } else {
                        savedEntity = PlannerTaskEntity(
                            id = UuidV7.generate(now),
                            title = cleanTitle,
                            notes = cleanNotes,
                            subjectId = request.subjectId,
                            dueAtWallMs = request.dueAtWallMs,
                            reminderAtWallMs = request.reminderAtWallMs,
                            recurrenceRule = request.recurrenceRule.name,
                            status = TaskStatus.OPEN.name,
                            completedAtWallMs = null,
                            priority = clampedPriority,
                            sync = SyncMetadataEntity(
                                ownerUserId = ownerId,
                                createdAtMs = now,
                                clientUpdatedAtMs = now,
                                version = 1L,
                                syncStatus = syncStatus
                            )
                        )
                        plannerTaskDao.upsertTask(savedEntity)
                    }
                    syncOutboxDao.enqueue(buildOutboxEntry(savedEntity, OP_UPSERT, now))
                }

                syncReminderForTask(savedEntity, now)
                savedEntity.toDomain()
            }
        }

    override suspend fun completeTask(taskId: String): Result<CompleteTaskOutcome> =
        stateMutex.withLock {
            runCatching {
                val now = timeProvider.wallTimeMs()
                val zoneId = timeProvider.zoneId()
                val ownerId = currentOwnerUserId()
                val syncStatus = syncStatusFor(ownerId)

                lateinit var outcome: CompleteTaskOutcome
                var shouldCancelCurrent = false
                var nextToSchedule: PlannerTaskEntity? = null

                database.withTransaction {
                    val existing = requireNotNull(plannerTaskDao.getTaskById(taskId)) {
                        "Task not found: $taskId"
                    }
                    check(existing.sync.ownerUserId == ownerId) {
                        "Task belongs to a different owner"
                    }
                    if (existing.status == TaskStatus.COMPLETED.name) {
                        outcome = CompleteTaskOutcome(
                            completedTask = existing.toDomain(),
                            nextOccurrence = null
                        )
                        return@withTransaction
                    }
                    check(existing.status != TaskStatus.CANCELLED.name) {
                        "Cancelled task cannot be completed"
                    }

                    val completedEntity = existing.copy(
                        status = TaskStatus.COMPLETED.name,
                        completedAtWallMs = now,
                        sync = existing.sync.copy(
                            clientUpdatedAtMs = now,
                            version = existing.sync.version + 1L,
                            syncStatus = syncStatus
                        )
                    )
                    plannerTaskDao.updateTask(completedEntity)
                    syncOutboxDao.enqueue(buildOutboxEntry(completedEntity, OP_UPSERT, now))
                    shouldCancelCurrent = true

                    val rule = parseRecurrence(existing.recurrenceRule)
                    val nextEntity = if (rule != TaskRecurrenceRule.NONE) {
                        createNextOccurrence(existing, rule, zoneId, now, ownerId, syncStatus).also {
                            plannerTaskDao.upsertTask(it)
                            syncOutboxDao.enqueue(buildOutboxEntry(it, OP_UPSERT, now))
                            nextToSchedule = it
                        }
                    } else {
                        null
                    }

                    outcome = CompleteTaskOutcome(
                        completedTask = completedEntity.toDomain(),
                        nextOccurrence = nextEntity?.toDomain()
                    )
                }

                if (shouldCancelCurrent) {
                    reminderGateway.cancelReminder(taskId)
                }
                nextToSchedule?.let { syncReminderForTask(it, now) }
                outcome
            }
        }

    override suspend fun reopenTask(taskId: String): Result<PlannerTask> =
        stateMutex.withLock {
            runCatching {
                val now = timeProvider.wallTimeMs()
                val ownerId = currentOwnerUserId()
                val syncStatus = syncStatusFor(ownerId)

                lateinit var reopenedEntity: PlannerTaskEntity
                database.withTransaction {
                    val existing = requireNotNull(plannerTaskDao.getTaskById(taskId)) {
                        "Task not found: $taskId"
                    }
                    check(existing.sync.ownerUserId == ownerId) {
                        "Task belongs to a different owner"
                    }
                    reopenedEntity = existing.copy(
                        status = TaskStatus.OPEN.name,
                        completedAtWallMs = null,
                        sync = existing.sync.copy(
                            clientUpdatedAtMs = now,
                            version = existing.sync.version + 1L,
                            syncStatus = syncStatus
                        )
                    )
                    plannerTaskDao.updateTask(reopenedEntity)
                    syncOutboxDao.enqueue(buildOutboxEntry(reopenedEntity, OP_UPSERT, now))
                }

                syncReminderForTask(reopenedEntity, now)
                reopenedEntity.toDomain()
            }
        }

    override suspend fun cancelTask(taskId: String): Result<PlannerTask> =
        stateMutex.withLock {
            runCatching {
                val now = timeProvider.wallTimeMs()
                val ownerId = currentOwnerUserId()
                val syncStatus = syncStatusFor(ownerId)

                lateinit var cancelledEntity: PlannerTaskEntity
                database.withTransaction {
                    val existing = requireNotNull(plannerTaskDao.getTaskById(taskId)) {
                        "Task not found: $taskId"
                    }
                    check(existing.sync.ownerUserId == ownerId) {
                        "Task belongs to a different owner"
                    }
                    cancelledEntity = existing.copy(
                        status = TaskStatus.CANCELLED.name,
                        sync = existing.sync.copy(
                            clientUpdatedAtMs = now,
                            version = existing.sync.version + 1L,
                            syncStatus = syncStatus
                        )
                    )
                    plannerTaskDao.updateTask(cancelledEntity)
                    syncOutboxDao.enqueue(buildOutboxEntry(cancelledEntity, OP_UPSERT, now))
                }

                reminderGateway.cancelReminder(taskId)
                cancelledEntity.toDomain()
            }
        }

    override suspend fun softDeleteTask(taskId: String): Result<Unit> =
        stateMutex.withLock {
            runCatching {
                val now = timeProvider.wallTimeMs()
                val ownerId = currentOwnerUserId()
                val syncStatus = syncStatusFor(ownerId)
                var cancelledNeeded = false

                database.withTransaction {
                    val existing = plannerTaskDao.getTaskById(taskId) ?: return@withTransaction
                    check(existing.sync.ownerUserId == ownerId) {
                        "Task belongs to a different owner"
                    }
                    val deletedEntity = existing.copy(
                        sync = existing.sync.copy(
                            deletedAtMs = now,
                            clientUpdatedAtMs = now,
                            version = existing.sync.version + 1L,
                            syncStatus = syncStatus
                        )
                    )
                    plannerTaskDao.updateTask(deletedEntity)
                    syncOutboxDao.enqueue(buildOutboxEntry(deletedEntity, OP_DELETE, now))
                    cancelledNeeded = true
                }

                if (cancelledNeeded) {
                    reminderGateway.cancelReminder(taskId)
                }
            }
        }

    override suspend fun rescheduleAllPendingReminders(): Int =
        stateMutex.withLock {
            val now = timeProvider.wallTimeMs()
            val ownerId = currentOwnerUserId()
            val pending = plannerTaskDao.getPendingReminderTasksByOwner(now, ownerId)
            var scheduledCount = 0
            for (entity in pending) {
                val reminderAt = entity.reminderAtWallMs ?: continue
                reminderGateway.scheduleReminder(
                    taskId = entity.id,
                    title = entity.title,
                    triggerAtWallMs = reminderAt
                )
                scheduledCount += 1
            }
            scheduledCount
        }

    override suspend fun handleReminderFired(taskId: String) {
        val ownerId = currentOwnerUserId()
        val task = plannerTaskDao.getTaskById(taskId) ?: return
        if (task.sync.deletedAtMs != null) return
        if (task.sync.ownerUserId != ownerId) return
        if (task.status != TaskStatus.OPEN.name) return
        reminderGateway.showReminderNotification(taskId = task.id, title = task.title)
    }

    override fun readCalendarEvents(beginWallMs: Long, endWallMs: Long): CalendarReadResult {
        return calendarReader.readInstances(beginWallMs, endWallMs)
    }

    private suspend fun currentOwnerUserId(): String? {
        return preferencesDataStore.preferences.first().activeOwnerUserId?.takeIf { it.isNotBlank() }
    }

    private fun syncStatusFor(ownerId: String?): String {
        return if (ownerId == null) SyncStatus.LOCAL_ONLY.name else SyncStatus.PENDING_UPLOAD.name
    }

    private fun syncReminderForTask(entity: PlannerTaskEntity, now: Long) {
        val reminderAt = entity.reminderAtWallMs
        if (entity.status == TaskStatus.OPEN.name && reminderAt != null && reminderAt > now) {
            reminderGateway.scheduleReminder(
                taskId = entity.id,
                title = entity.title,
                triggerAtWallMs = reminderAt
            )
        } else {
            reminderGateway.cancelReminder(entity.id)
        }
    }

    private fun createNextOccurrence(
        completed: PlannerTaskEntity,
        rule: TaskRecurrenceRule,
        zoneId: ZoneId,
        now: Long,
        ownerId: String?,
        syncStatus: String
    ): PlannerTaskEntity {
        val completedDue = completed.dueAtWallMs
        val nextDue = when {
            completedDue != null -> advanceWallTime(completedDue, rule, zoneId)
            completed.reminderAtWallMs == null -> advanceWallTime(now, rule, zoneId)
            else -> null
        }
        val nextReminder = completed.reminderAtWallMs?.let { advanceWallTime(it, rule, zoneId) }

        return PlannerTaskEntity(
            id = UuidV7.generate(now),
            title = completed.title,
            notes = completed.notes,
            subjectId = completed.subjectId,
            dueAtWallMs = nextDue,
            reminderAtWallMs = nextReminder,
            recurrenceRule = completed.recurrenceRule,
            status = TaskStatus.OPEN.name,
            completedAtWallMs = null,
            priority = completed.priority,
            sync = SyncMetadataEntity(
                ownerUserId = ownerId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                version = 1L,
                syncStatus = syncStatus
            )
        )
    }

    private fun advanceWallTime(epochMs: Long, rule: TaskRecurrenceRule, zoneId: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val shifted = when (rule) {
            TaskRecurrenceRule.DAILY -> zoned.plusDays(1L)
            TaskRecurrenceRule.WEEKLY -> zoned.plusWeeks(1L)
            TaskRecurrenceRule.NONE -> zoned
        }
        return shifted.toInstant().toEpochMilli()
    }

    private fun parseRecurrence(raw: String): TaskRecurrenceRule {
        return TaskRecurrenceRule.entries.firstOrNull { it.name == raw } ?: TaskRecurrenceRule.NONE
    }

    private fun buildOutboxEntry(
        entity: PlannerTaskEntity,
        opType: String,
        now: Long
    ): SyncOutboxEntity {
        val payload = PlannerTaskOutboxPayload(
            id = entity.id,
            title = entity.title,
            notes = entity.notes,
            subjectId = entity.subjectId,
            dueAtWallMs = entity.dueAtWallMs,
            reminderAtWallMs = entity.reminderAtWallMs,
            recurrenceRule = entity.recurrenceRule,
            status = entity.status,
            completedAtWallMs = entity.completedAtWallMs,
            priority = entity.priority,
            ownerUserId = entity.sync.ownerUserId,
            createdAtMs = entity.sync.createdAtMs,
            clientUpdatedAtMs = entity.sync.clientUpdatedAtMs,
            deletedAtMs = entity.sync.deletedAtMs,
            version = entity.sync.version
        )
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(now),
            entityTable = TABLE_PLANNER_TASKS,
            entityId = entity.id,
            opType = opType,
            payloadJson = json.encodeToString(PlannerTaskOutboxPayload.serializer(), payload),
            baseVersion = entity.sync.version,
            attemptCount = 0,
            createdAtMs = now,
            ownerUserId = entity.sync.ownerUserId
        )
    }

    @Serializable
    private data class PlannerTaskOutboxPayload(
        val id: String,
        val title: String,
        val notes: String?,
        val subjectId: String?,
        val dueAtWallMs: Long?,
        val reminderAtWallMs: Long?,
        val recurrenceRule: String,
        val status: String,
        val completedAtWallMs: Long?,
        val priority: Int,
        val ownerUserId: String?,
        val createdAtMs: Long,
        val clientUpdatedAtMs: Long,
        val deletedAtMs: Long?,
        val version: Long
    )

    private companion object {
        const val TABLE_PLANNER_TASKS = "planner_tasks"
        const val OP_UPSERT = "UPSERT"
        const val OP_DELETE = "DELETE"
        const val MAX_TITLE_LENGTH = 120
        const val MAX_NOTES_LENGTH = 500
        const val MIN_PRIORITY = 0
        const val MAX_PRIORITY = 3
    }
}
