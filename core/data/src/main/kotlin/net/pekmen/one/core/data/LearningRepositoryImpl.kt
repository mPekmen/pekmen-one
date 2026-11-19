package net.pekmen.one.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.TopicStatus
import net.pekmen.one.core.model.UuidV7
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LearningRepositoryImpl(
    private val database: PekmenDatabase,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val timeProvider: TimeProvider
) : LearningRepository {

    private val learningDao = database.learningDao()
    private val topicDao = database.learningTopicDao()
    private val accountSessionDao = database.accountSessionDao()
    private val outboxDao = database.syncOutboxDao()

    private val ownerUserIdFlow: Flow<String?> = preferencesDataStore.preferences
        .map { LearningOutboxEncoder.normalizeOwner(it.activeOwnerUserId) }
        .distinctUntilChanged()

    override fun observeSubjects(): Flow<List<LearningSubject>> {
        return ownerUserIdFlow.flatMapLatest { ownerUserId ->
            learningDao.observeActiveSubjectsByOwner(ownerUserId).map { list ->
                list.map { it.toDomain() }
            }
        }
    }

    override fun observeBooks(subjectId: String?): Flow<List<LearningBook>> {
        return ownerUserIdFlow.flatMapLatest { ownerUserId ->
            learningDao.observeActiveBooksByOwner(ownerUserId).map { list ->
                list.asSequence()
                    .filter { subjectId == null || it.subjectId == subjectId }
                    .map { it.toDomain() }
                    .toList()
            }
        }
    }

    override fun observeTopics(subjectId: String?): Flow<List<LearningTopic>> {
        return ownerUserIdFlow.flatMapLatest { ownerUserId ->
            topicDao.observeActiveTopicsByOwner(ownerUserId).map { list ->
                list.asSequence()
                    .filter { subjectId == null || it.subjectId == subjectId }
                    .map { it.toDomain() }
                    .toList()
            }
        }
    }

    override fun observeWeeklySubjectProgress(anchorDate: LocalDate): Flow<List<SubjectWeeklyProgress>> {
        val windowDates = EffectiveDayCalculator.sevenDayWindowEndingOn(anchorDate)
        val startIso = windowDates.first().toString()
        val endIso = windowDates.last().toString()

        return ownerUserIdFlow.flatMapLatest { ownerUserId ->
            combine(
                learningDao.observeActiveSubjectsByOwner(ownerUserId),
                learningDao.observeActiveBooksByOwner(ownerUserId),
                topicDao.observeActiveTopicsByOwner(ownerUserId),
                accountSessionDao.observeCompletedSessionsByOwner(startIso, endIso, ownerUserId)
            ) { subjects, books, topics, sessions ->
                val sessionItems = sessions.map {
                    CompletedSessionItem(it.session.toDomain(), it.profile.toDomain())
                }
                FocusSummaryAggregator.summarizeSubjectsForWeek(
                    anchorDate = anchorDate,
                    subjects = subjects.map { it.toDomain() },
                    books = books.map { it.toDomain() },
                    topics = topics.map { it.toDomain() },
                    items = sessionItems
                )
            }
        }
    }

    override suspend fun saveSubject(request: SaveSubjectRequest): Result<LearningSubject> = runCatching {
        val cleanedTitle = request.title.trim().take(MAX_TITLE_LENGTH)
        require(cleanedTitle.isNotEmpty()) { "Subject title must not be blank" }
        require(request.weeklyGoalMinutes in 0..MAX_WEEKLY_GOAL_MINUTES) {
            "Weekly goal minutes out of bounds: ${request.weeklyGoalMinutes}"
        }

        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        var saved: LearningSubjectEntity? = null

        database.withTransaction {
            val existing = request.existingId?.let { learningDao.getSubjectById(it) }
            saved = if (existing != null) {
                updateExistingSubject(existing, cleanedTitle, request, ownerUserId, now)
            } else {
                createNewSubject(cleanedTitle, request, ownerUserId, now)
            }
        }
        requireNotNull(saved).toDomain()
    }

    override suspend fun softDeleteSubject(subjectId: String): Result<Unit> = runCatching {
        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        database.withTransaction {
            val existing = requireNotNull(learningDao.getSubjectById(subjectId)) {
                "Subject not found: $subjectId"
            }
            require(existing.sync.ownerUserId == ownerUserId) {
                "Subject belongs to a different account"
            }
            softDeleteChildLearningRows(subjectId, ownerUserId, now)
            learningDao.softDeleteSubject(subjectId, deletedAtMs = now, updatedAtMs = now)
            val deletedSubject = existing.copy(
                sync = existing.sync.copy(
                    clientUpdatedAtMs = now,
                    deletedAtMs = now,
                    version = existing.sync.version + 1L
                )
            )
            outboxDao.enqueue(
                LearningOutboxEncoder.buildSubjectMutation(
                    entity = deletedSubject,
                    opType = OP_DELETE,
                    baseVersion = existing.sync.version,
                    nowMs = now
                )
            )
        }
    }

    override suspend fun saveBook(request: SaveBookRequest): Result<LearningBook> = runCatching {
        val cleanedTitle = request.title.trim().take(MAX_TITLE_LENGTH)
        require(cleanedTitle.isNotEmpty()) { "Book title must not be blank" }
        require(request.totalPages >= 0) { "Total pages cannot be negative" }
        require(request.currentPage >= 0) { "Current page cannot be negative" }
        require(request.totalChapters >= 0) { "Total chapters cannot be negative" }
        require(request.completedChapters >= 0) { "Completed chapters cannot be negative" }

        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        var saved: LearningBookEntity? = null

        database.withTransaction {
            val subject = requireNotNull(learningDao.getSubjectById(request.subjectId)) {
                "Subject not found: ${request.subjectId}"
            }
            require(subject.sync.ownerUserId == ownerUserId) {
                "Subject belongs to a different account"
            }
            val existing = request.existingId?.let { learningDao.getBookById(it) }
            saved = if (existing != null) {
                updateExistingBook(existing, cleanedTitle, request, ownerUserId, now)
            } else {
                createNewBook(cleanedTitle, request, ownerUserId, now)
            }
        }
        requireNotNull(saved).toDomain()
    }

    override suspend fun updateBookProgress(
        bookId: String,
        currentPage: Int,
        completedChapters: Int?
    ): Result<LearningBook> = runCatching {
        require(currentPage >= 0) { "Current page cannot be negative" }
        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        var updatedResult: LearningBookEntity? = null

        database.withTransaction {
            val existing = requireNotNull(learningDao.getBookById(bookId)) {
                "Book not found: $bookId"
            }
            require(existing.sync.ownerUserId == ownerUserId) {
                "Book belongs to a different account"
            }
            val resolvedPage = clampPage(currentPage, existing.totalPages)
            val resolvedChapters = clampPage(
                completedChapters ?: existing.completedChapters,
                existing.totalChapters
            )
            val completed = computeBookCompleted(
                totalPages = existing.totalPages,
                currentPage = resolvedPage,
                totalChapters = existing.totalChapters,
                completedChapters = resolvedChapters
            )
            val updated = existing.copy(
                currentPage = resolvedPage,
                completedChapters = resolvedChapters,
                isCompleted = completed,
                sync = existing.sync.copy(
                    clientUpdatedAtMs = now,
                    version = existing.sync.version + 1L,
                    syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
                )
            )
            learningDao.updateBook(updated)
            outboxDao.enqueue(
                LearningOutboxEncoder.buildBookMutation(
                    entity = updated,
                    opType = OP_UPSERT,
                    baseVersion = existing.sync.version,
                    nowMs = now
                )
            )
            updatedResult = updated
        }
        requireNotNull(updatedResult).toDomain()
    }

    override suspend fun softDeleteBook(bookId: String): Result<Unit> = runCatching {
        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        database.withTransaction {
            val existing = requireNotNull(learningDao.getBookById(bookId)) {
                "Book not found: $bookId"
            }
            require(existing.sync.ownerUserId == ownerUserId) {
                "Book belongs to a different account"
            }
            unlinkTopicsFromDeletedBook(bookId, ownerUserId, now)
            learningDao.softDeleteBook(bookId, deletedAtMs = now, updatedAtMs = now)
            val deletedBook = existing.copy(
                sync = existing.sync.copy(
                    clientUpdatedAtMs = now,
                    deletedAtMs = now,
                    version = existing.sync.version + 1L
                )
            )
            outboxDao.enqueue(
                LearningOutboxEncoder.buildBookMutation(
                    entity = deletedBook,
                    opType = OP_DELETE,
                    baseVersion = existing.sync.version,
                    nowMs = now
                )
            )
        }
    }

    override suspend fun saveTopic(request: SaveTopicRequest): Result<LearningTopic> = runCatching {
        val cleanedTitle = request.title.trim().take(MAX_TITLE_LENGTH)
        require(cleanedTitle.isNotEmpty()) { "Topic title must not be blank" }

        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        var saved: LearningTopicEntity? = null

        database.withTransaction {
            val subject = requireNotNull(learningDao.getSubjectById(request.subjectId)) {
                "Subject not found: ${request.subjectId}"
            }
            require(subject.sync.ownerUserId == ownerUserId) {
                "Subject belongs to a different account"
            }
            if (request.bookId != null) {
                val book = requireNotNull(learningDao.getBookById(request.bookId)) {
                    "Book not found: ${request.bookId}"
                }
                require(book.sync.ownerUserId == ownerUserId && book.subjectId == request.subjectId) {
                    "Book does not belong to subject or account"
                }
            }
            val existing = request.existingId?.let { topicDao.getTopicById(it) }
            saved = if (existing != null) {
                updateExistingTopic(existing, cleanedTitle, request, ownerUserId, now)
            } else {
                createNewTopic(cleanedTitle, request, ownerUserId, now)
            }
        }
        requireNotNull(saved).toDomain()
    }

    override suspend fun updateTopicStatus(topicId: String, status: TopicStatus): Result<LearningTopic> = runCatching {
        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        var updatedResult: LearningTopicEntity? = null

        database.withTransaction {
            val existing = requireNotNull(topicDao.getTopicById(topicId)) {
                "Topic not found: $topicId"
            }
            require(existing.sync.ownerUserId == ownerUserId) {
                "Topic belongs to a different account"
            }
            val updated = existing.copy(
                status = status.name,
                sync = existing.sync.copy(
                    clientUpdatedAtMs = now,
                    version = existing.sync.version + 1L,
                    syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
                )
            )
            topicDao.upsertTopic(updated)
            outboxDao.enqueue(
                LearningOutboxEncoder.buildTopicMutation(
                    entity = updated,
                    opType = OP_UPSERT,
                    baseVersion = existing.sync.version,
                    nowMs = now
                )
            )
            updatedResult = updated
        }
        requireNotNull(updatedResult).toDomain()
    }

    override suspend fun softDeleteTopic(topicId: String): Result<Unit> = runCatching {
        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        database.withTransaction {
            val existing = requireNotNull(topicDao.getTopicById(topicId)) {
                "Topic not found: $topicId"
            }
            require(existing.sync.ownerUserId == ownerUserId) {
                "Topic belongs to a different account"
            }
            topicDao.softDeleteTopic(topicId, deletedAtMs = now, updatedAtMs = now)
            val deleted = existing.copy(
                sync = existing.sync.copy(
                    clientUpdatedAtMs = now,
                    deletedAtMs = now,
                    version = existing.sync.version + 1L
                )
            )
            outboxDao.enqueue(
                LearningOutboxEncoder.buildTopicMutation(
                    entity = deleted,
                    opType = OP_DELETE,
                    baseVersion = existing.sync.version,
                    nowMs = now
                )
            )
        }
    }

    private suspend fun updateExistingSubject(
        existing: LearningSubjectEntity,
        cleanedTitle: String,
        request: SaveSubjectRequest,
        ownerUserId: String?,
        now: Long
    ): LearningSubjectEntity {
        require(existing.sync.ownerUserId == ownerUserId) {
            "Subject belongs to a different account"
        }
        val updated = existing.copy(
            title = cleanedTitle,
            colorHex = request.colorHex,
            weeklyGoalMinutes = request.weeklyGoalMinutes,
            sortOrder = request.sortOrder ?: existing.sortOrder,
            sync = existing.sync.copy(
                clientUpdatedAtMs = now,
                version = existing.sync.version + 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.updateSubject(updated)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildSubjectMutation(
                entity = updated,
                opType = OP_UPSERT,
                baseVersion = existing.sync.version,
                nowMs = now
            )
        )
        return updated
    }

    private suspend fun createNewSubject(
        cleanedTitle: String,
        request: SaveSubjectRequest,
        ownerUserId: String?,
        now: Long
    ): LearningSubjectEntity {
        val activeCount = learningDao.observeActiveSubjectsByOwner(ownerUserId).first().size
        val created = LearningSubjectEntity(
            id = UuidV7.generate(now),
            title = cleanedTitle,
            colorHex = request.colorHex,
            weeklyGoalMinutes = request.weeklyGoalMinutes,
            sortOrder = request.sortOrder ?: activeCount,
            isArchived = false,
            sync = SyncMetadataEntity(
                ownerUserId = ownerUserId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                version = 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.upsertSubject(created)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildSubjectMutation(
                entity = created,
                opType = OP_UPSERT,
                baseVersion = 0L,
                nowMs = now
            )
        )
        return created
    }

    private suspend fun softDeleteChildLearningRows(
        subjectId: String,
        ownerUserId: String?,
        now: Long
    ) {
        val childBooks = learningDao.observeActiveBooksByOwner(ownerUserId).first()
            .filter { it.subjectId == subjectId }
        val childTopics = topicDao.observeActiveTopicsByOwner(ownerUserId).first()
            .filter { it.subjectId == subjectId }

        childTopics.forEach { topic ->
            topicDao.softDeleteTopic(topic.id, deletedAtMs = now, updatedAtMs = now)
            val deletedTopic = topic.copy(
                sync = topic.sync.copy(
                    clientUpdatedAtMs = now,
                    deletedAtMs = now,
                    version = topic.sync.version + 1L
                )
            )
            outboxDao.enqueue(
                LearningOutboxEncoder.buildTopicMutation(
                    entity = deletedTopic,
                    opType = OP_DELETE,
                    baseVersion = topic.sync.version,
                    nowMs = now
                )
            )
        }

        childBooks.forEach { book ->
            learningDao.softDeleteBook(book.id, deletedAtMs = now, updatedAtMs = now)
            val deletedBook = book.copy(
                sync = book.sync.copy(
                    clientUpdatedAtMs = now,
                    deletedAtMs = now,
                    version = book.sync.version + 1L
                )
            )
            outboxDao.enqueue(
                LearningOutboxEncoder.buildBookMutation(
                    entity = deletedBook,
                    opType = OP_DELETE,
                    baseVersion = book.sync.version,
                    nowMs = now
                )
            )
        }
    }

    private suspend fun updateExistingBook(
        existing: LearningBookEntity,
        cleanedTitle: String,
        request: SaveBookRequest,
        ownerUserId: String?,
        now: Long
    ): LearningBookEntity {
        require(existing.sync.ownerUserId == ownerUserId) {
            "Book belongs to a different account"
        }
        val resolvedPage = clampPage(request.currentPage, request.totalPages)
        val resolvedChapters = clampPage(request.completedChapters, request.totalChapters)
        val updated = existing.copy(
            subjectId = request.subjectId,
            title = cleanedTitle,
            author = request.author?.trim()?.take(MAX_TITLE_LENGTH)?.ifEmpty { null },
            totalPages = request.totalPages,
            currentPage = resolvedPage,
            totalChapters = request.totalChapters,
            completedChapters = resolvedChapters,
            isCompleted = computeBookCompleted(
                request.totalPages,
                resolvedPage,
                request.totalChapters,
                resolvedChapters
            ),
            sync = existing.sync.copy(
                clientUpdatedAtMs = now,
                version = existing.sync.version + 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.updateBook(updated)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildBookMutation(
                entity = updated,
                opType = OP_UPSERT,
                baseVersion = existing.sync.version,
                nowMs = now
            )
        )
        return updated
    }

    private suspend fun createNewBook(
        cleanedTitle: String,
        request: SaveBookRequest,
        ownerUserId: String?,
        now: Long
    ): LearningBookEntity {
        val resolvedPage = clampPage(request.currentPage, request.totalPages)
        val resolvedChapters = clampPage(request.completedChapters, request.totalChapters)
        val created = LearningBookEntity(
            id = UuidV7.generate(now),
            subjectId = request.subjectId,
            title = cleanedTitle,
            author = request.author?.trim()?.take(MAX_TITLE_LENGTH)?.ifEmpty { null },
            totalPages = request.totalPages,
            currentPage = resolvedPage,
            totalChapters = request.totalChapters,
            completedChapters = resolvedChapters,
            isCompleted = computeBookCompleted(
                request.totalPages,
                resolvedPage,
                request.totalChapters,
                resolvedChapters
            ),
            sync = SyncMetadataEntity(
                ownerUserId = ownerUserId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                version = 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.upsertBook(created)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildBookMutation(
                entity = created,
                opType = OP_UPSERT,
                baseVersion = 0L,
                nowMs = now
            )
        )
        return created
    }

    private suspend fun unlinkTopicsFromDeletedBook(
        bookId: String,
        ownerUserId: String?,
        now: Long
    ) {
        val linkedTopics = topicDao.observeActiveTopicsByOwner(ownerUserId).first()
            .filter { it.bookId == bookId }
        linkedTopics.forEach { topic ->
            val unlinked = topic.copy(
                bookId = null,
                sync = topic.sync.copy(
                    clientUpdatedAtMs = now,
                    version = topic.sync.version + 1L,
                    syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
                )
            )
            topicDao.upsertTopic(unlinked)
            outboxDao.enqueue(
                LearningOutboxEncoder.buildTopicMutation(
                    entity = unlinked,
                    opType = OP_UPSERT,
                    baseVersion = topic.sync.version,
                    nowMs = now
                )
            )
        }
    }

    private suspend fun updateExistingTopic(
        existing: LearningTopicEntity,
        cleanedTitle: String,
        request: SaveTopicRequest,
        ownerUserId: String?,
        now: Long
    ): LearningTopicEntity {
        require(existing.sync.ownerUserId == ownerUserId) {
            "Topic belongs to a different account"
        }
        val updated = existing.copy(
            subjectId = request.subjectId,
            bookId = request.bookId,
            title = cleanedTitle,
            status = request.status.name,
            sortOrder = request.sortOrder ?: existing.sortOrder,
            sync = existing.sync.copy(
                clientUpdatedAtMs = now,
                version = existing.sync.version + 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        topicDao.upsertTopic(updated)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildTopicMutation(
                entity = updated,
                opType = OP_UPSERT,
                baseVersion = existing.sync.version,
                nowMs = now
            )
        )
        return updated
    }

    private suspend fun createNewTopic(
        cleanedTitle: String,
        request: SaveTopicRequest,
        ownerUserId: String?,
        now: Long
    ): LearningTopicEntity {
        val topicCount = topicDao.observeActiveTopicsByOwner(ownerUserId).first()
            .count { it.subjectId == request.subjectId }
        val created = LearningTopicEntity(
            id = UuidV7.generate(now),
            subjectId = request.subjectId,
            bookId = request.bookId,
            title = cleanedTitle,
            status = request.status.name,
            sortOrder = request.sortOrder ?: topicCount,
            sync = SyncMetadataEntity(
                ownerUserId = ownerUserId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                version = 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        topicDao.upsertTopic(created)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildTopicMutation(
                entity = created,
                opType = OP_UPSERT,
                baseVersion = 0L,
                nowMs = now
            )
        )
        return created
    }

    private suspend fun currentOwnerUserId(): String? {
        return LearningOutboxEncoder.normalizeOwner(preferencesDataStore.preferences.first().activeOwnerUserId)
    }

    private fun clampPage(value: Int, total: Int): Int {
        return if (total > 0) value.coerceIn(0, total) else value.coerceAtLeast(0)
    }

    private fun computeBookCompleted(
        totalPages: Int,
        currentPage: Int,
        totalChapters: Int,
        completedChapters: Int
    ): Boolean {
        return when {
            totalPages > 0 -> currentPage >= totalPages
            totalChapters > 0 -> completedChapters >= totalChapters
            else -> false
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
        const val MAX_WEEKLY_GOAL_MINUTES = 10_080
        const val OP_UPSERT = "UPSERT"
        const val OP_DELETE = "DELETE"
    }
}

internal object LearningOutboxEncoder {
    private const val TABLE_SUBJECTS = "learning_subjects"
    private const val TABLE_BOOKS = "learning_books"
    private const val TABLE_TOPICS = "learning_topics"
    private const val TABLE_SESSIONS = "focus_sessions"

    fun normalizeOwner(rawOwnerId: String?): String? {
        return rawOwnerId?.trim()?.ifEmpty { null }
    }

    fun syncStatusFor(ownerUserId: String?): String {
        return if (ownerUserId == null) SyncStatus.LOCAL_ONLY.name else SyncStatus.PENDING_UPLOAD.name
    }

    fun buildSubjectMutation(
        entity: LearningSubjectEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("title", entity.title)
            put("color_hex", entity.colorHex)
            put("weekly_goal_minutes", entity.weeklyGoalMinutes)
            put("sort_order", entity.sortOrder)
            put("is_archived", entity.isArchived)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_SUBJECTS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }

    fun buildBookMutation(
        entity: LearningBookEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("subject_id", entity.subjectId)
            put("title", entity.title)
            put("author", entity.author)
            put("total_pages", entity.totalPages)
            put("current_page", entity.currentPage)
            put("total_chapters", entity.totalChapters)
            put("completed_chapters", entity.completedChapters)
            put("is_completed", entity.isCompleted)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_BOOKS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }

    fun buildTopicMutation(
        entity: LearningTopicEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("subject_id", entity.subjectId)
            put("book_id", entity.bookId)
            put("title", entity.title)
            put("status", entity.status)
            put("sort_order", entity.sortOrder)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_TOPICS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }

    fun buildSessionMutation(
        entity: FocusSessionEntity,
        opType: String,
        baseVersion: Long,
        nowMs: Long
    ): SyncOutboxEntity {
        val payload = buildJsonObject {
            put("id", entity.id)
            put("profile_id", entity.profileId)
            put("subject_id", entity.subjectId)
            put("book_id", entity.bookId)
            put("topic_id", entity.topicId)
            put("task_id", entity.taskId)
            put("book_start_page", entity.bookStartPage)
            put("book_end_page", entity.bookEndPage)
            put("session_mode", entity.sessionMode)
            put("status", entity.status)
            put("started_at_wall_ms", entity.startedAtWallMs)
            put("ended_at_wall_ms", entity.endedAtWallMs)
            put("effective_day_date", entity.effectiveDayDate)
            put("actual_focus_sec", entity.actualFocusSec)
            put("actual_break_sec", entity.actualBreakSec)
            put("is_manual_entry", entity.isManualEntry)
            put("focus_rating", entity.focusRating)
            put("reflection_note", entity.reflectionNote)
            put("owner_user_id", entity.sync.ownerUserId)
            put("client_updated_at_ms", entity.sync.clientUpdatedAtMs)
            put("deleted_at_ms", entity.sync.deletedAtMs)
            put("version", entity.sync.version)
        }.toString()
        return SyncOutboxEntity(
            mutationId = UuidV7.generate(nowMs),
            entityTable = TABLE_SESSIONS,
            entityId = entity.id,
            opType = opType,
            payloadJson = payload,
            baseVersion = baseVersion,
            attemptCount = 0,
            createdAtMs = nowMs,
            ownerUserId = entity.sync.ownerUserId
        )
    }
}
