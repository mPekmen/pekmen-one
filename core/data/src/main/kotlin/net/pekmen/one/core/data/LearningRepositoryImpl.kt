package net.pekmen.one.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
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
    private val catalog = LearningCatalogHelper(learningDao, topicDao, outboxDao)

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

    override fun observeTopicHistory(topicId: String): Flow<List<CompletedSessionItem>> =
        ownerUserIdFlow.flatMapLatest { owner ->
            accountSessionDao.observeTopicHistory(topicId, owner)
                .map { rows -> rows.map { CompletedSessionItem(it.session.toDomain(), it.profile.toDomain()) } }
        }

    override suspend fun saveSubject(request: SaveSubjectRequest): Result<LearningSubject> = runCatching {
        val cleanedTitle = request.title.trim().take(MAX_TITLE_LENGTH)
        require(cleanedTitle.isNotEmpty()) { "Subject title must not be blank" }
        require(request.weeklyGoalMinutes in 0..MAX_WEEKLY_GOAL_MINUTES) {
            "Weekly goal minutes out of bounds: ${request.weeklyGoalMinutes}"
        }
        require(request.dailyGoalMinutes == null || request.dailyGoalMinutes in 0..1_440)

        val ownerUserId = currentOwnerUserId()
        val now = timeProvider.wallTimeMs()
        var saved: LearningSubjectEntity? = null

        database.withTransaction {
            val existing = request.existingId?.let { learningDao.getSubjectById(it) }
            saved = if (existing != null) {
                catalog.updateExistingSubject(existing, cleanedTitle, request, ownerUserId, now)
            } else {
                catalog.createNewSubject(cleanedTitle, request, ownerUserId, now)
            }
        }
        requireNotNull(saved).toDomain()
    }

    suspend fun ensureFirstSubject(id: String, request: SaveSubjectRequest) {
        require(UuidV7.isValidUuidV7(id))
        val title = request.title.trim().take(MAX_TITLE_LENGTH)
        require(title.isNotBlank() && request.weeklyGoalMinutes in 0..MAX_WEEKLY_GOAL_MINUTES)
        val owner = requireNotNull(currentOwnerUserId())
        database.withTransaction {
            if (learningDao.getSubjectById(id) == null) {
                catalog.createNewSubject(title, request, owner, timeProvider.wallTimeMs(), id)
            }
        }
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
            catalog.softDeleteChildLearningRows(subjectId, ownerUserId, now)
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
                catalog.updateExistingBook(existing, cleanedTitle, request, ownerUserId, now)
            } else {
                catalog.createNewBook(cleanedTitle, request, ownerUserId, now)
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
            val resolvedPage = catalog.clampPage(currentPage, existing.totalPages)
            val resolvedChapters = catalog.clampPage(
                completedChapters ?: existing.completedChapters,
                existing.totalChapters
            )
            val completed = catalog.computeBookCompleted(
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
            catalog.unlinkTopicsFromDeletedBook(bookId, ownerUserId, now)
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
            validateTopicParent(request, existing, ownerUserId)
            saved = if (existing != null) {
                catalog.updateExistingTopic(existing, cleanedTitle, request, ownerUserId, now)
            } else {
                catalog.createNewTopic(cleanedTitle, request, ownerUserId, now)
            }
        }
        requireNotNull(saved).toDomain()
    }

    private suspend fun validateTopicParent(
        request: SaveTopicRequest,
        existing: LearningTopicEntity?,
        ownerUserId: String?
    ) {
        request.parentTopicId?.let { parentId ->
            require(parentId != existing?.id)
            val parent = requireNotNull(topicDao.getTopicById(parentId))
            require(parent.sync.ownerUserId == ownerUserId && parent.subjectId == request.subjectId)
            require(parent.parentTopicId == null && (parent.bookId == null || parent.bookId == request.bookId))
        }
        if (existing != null && topicDao.getActiveChildren(existing.id).isNotEmpty()) {
            require(request.parentTopicId == null)
            require(existing.subjectId == request.subjectId && existing.bookId == request.bookId)
        }
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
            topicDao.getActiveChildren(topicId).forEach { child ->
                val newRoot = SaveTopicRequest(
                    existingId = child.id,
                    subjectId = child.subjectId,
                    bookId = child.bookId,
                    title = child.title,
                    status = TopicStatus.valueOf(child.status),
                    sortOrder = child.sortOrder
                )
                catalog.updateExistingTopic(child, child.title, newRoot, ownerUserId, now)
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

    private suspend fun currentOwnerUserId(): String? {
        return LearningOutboxEncoder.normalizeOwner(preferencesDataStore.preferences.first().activeOwnerUserId)
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
        const val MAX_WEEKLY_GOAL_MINUTES = 10_080
        const val OP_UPSERT = "UPSERT"
        const val OP_DELETE = "DELETE"
    }
}
