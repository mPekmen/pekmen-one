package net.pekmen.one.feature.focus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.SaveBookRequest
import net.pekmen.one.core.data.SaveSubjectRequest
import net.pekmen.one.core.data.SaveTopicRequest
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.TopicStatus
import java.time.LocalDate

internal class FakeLearningRepo(
    private val subjects: List<LearningSubject>,
    private val books: List<LearningBook>,
    private val topics: List<LearningTopic>
) : LearningRepository {
    override fun observeSubjects(): Flow<List<LearningSubject>> = flowOf(subjects)
    override fun observeBooks(subjectId: String?): Flow<List<LearningBook>> = flowOf(books)
    override fun observeTopics(subjectId: String?): Flow<List<LearningTopic>> = flowOf(topics)
    override fun observeWeeklySubjectProgress(anchorDate: LocalDate): Flow<List<SubjectWeeklyProgress>> =
        flowOf(emptyList())

    override suspend fun saveSubject(request: SaveSubjectRequest) =
        Result.failure<LearningSubject>(NotImplementedError())

    override suspend fun softDeleteSubject(subjectId: String) = Result.success(Unit)
    override suspend fun saveBook(request: SaveBookRequest) =
        Result.failure<LearningBook>(NotImplementedError())

    override suspend fun updateBookProgress(bookId: String, currentPage: Int, completedChapters: Int?) =
        Result.failure<LearningBook>(NotImplementedError())

    override suspend fun softDeleteBook(bookId: String) = Result.success(Unit)
    override suspend fun saveTopic(request: SaveTopicRequest) =
        Result.failure<LearningTopic>(NotImplementedError())

    override suspend fun updateTopicStatus(topicId: String, status: TopicStatus) =
        Result.failure<LearningTopic>(NotImplementedError())

    override suspend fun softDeleteTopic(topicId: String) = Result.success(Unit)
}
