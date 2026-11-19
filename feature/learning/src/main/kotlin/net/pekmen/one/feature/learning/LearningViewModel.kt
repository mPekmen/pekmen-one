package net.pekmen.one.feature.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.SaveBookRequest
import net.pekmen.one.core.data.SaveSubjectRequest
import net.pekmen.one.core.data.SaveTopicRequest
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.TopicStatus

sealed interface LearningEditorModal {
    data class SubjectEditor(val existing: LearningSubject? = null) : LearningEditorModal
    data class BookEditor(val subjectId: String, val existing: LearningBook? = null) : LearningEditorModal
    data class TopicEditor(
        val subjectId: String,
        val availableBooks: List<LearningBook>,
        val existing: LearningTopic? = null
    ) : LearningEditorModal
    data class TopicHistory(val topic: LearningTopic) : LearningEditorModal
}

data class LearningUiState(
    val subjectProgress: List<SubjectWeeklyProgress> = emptyList(),
    val selectedSubjectId: String? = null,
    val editorModal: LearningEditorModal? = null,
    val errorMessageRes: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class LearningViewModel(
    private val learningRepository: LearningRepository,
    preferencesRepository: UserPreferencesRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository
) : ViewModel() {

    private val selectedSubjectId = MutableStateFlow<String?>(null)
    private val editorModal = MutableStateFlow<LearningEditorModal?>(null)
    private val errorRes = MutableStateFlow<Int?>(null)

    val topicHistory = editorModal.flatMapLatest { modal ->
        if (modal is LearningEditorModal.TopicHistory) learningRepository.observeTopicHistory(modal.topic.id)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val weeklyProgressFlow = preferencesRepository.preferences.flatMapLatest { prefs ->
        val effectiveToday = EffectiveDayCalculator.effectiveDateFor(
            epochMs = diagnosticsRepository.currentWallTimeMs(),
            zoneId = diagnosticsRepository.currentZoneId(),
            rolloverHour = prefs.dayRolloverHour
        )
        learningRepository.observeWeeklySubjectProgress(effectiveToday)
    }

    val uiState: StateFlow<LearningUiState> = combine(
        weeklyProgressFlow,
        selectedSubjectId,
        editorModal,
        errorRes
    ) { progressList, selectedId, modal, err ->
        val resolvedSelected = selectedId?.takeIf { id -> progressList.any { it.subject.id == id } }
            ?: progressList.firstOrNull()?.subject?.id
        LearningUiState(
            subjectProgress = progressList,
            selectedSubjectId = resolvedSelected,
            editorModal = modal,
            errorMessageRes = err
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LearningUiState()
    )

    fun selectSubject(subjectId: String?) {
        selectedSubjectId.value = subjectId
    }

    fun openCreateSubject() {
        errorRes.value = null
        editorModal.value = LearningEditorModal.SubjectEditor(existing = null)
    }

    fun openEditSubject(subject: LearningSubject) {
        errorRes.value = null
        editorModal.value = LearningEditorModal.SubjectEditor(existing = subject)
    }

    fun openCreateBook(subjectId: String) {
        errorRes.value = null
        editorModal.value = LearningEditorModal.BookEditor(subjectId = subjectId, existing = null)
    }

    fun openEditBook(book: LearningBook) {
        errorRes.value = null
        editorModal.value = LearningEditorModal.BookEditor(subjectId = book.subjectId, existing = book)
    }

    fun openCreateTopic(subjectId: String) {
        errorRes.value = null
        val books = uiState.value.subjectProgress
            .firstOrNull { it.subject.id == subjectId }
            ?.books
            .orEmpty()
        editorModal.value = LearningEditorModal.TopicEditor(
            subjectId = subjectId,
            availableBooks = books,
            existing = null
        )
    }

    fun openEditTopic(topic: LearningTopic) {
        errorRes.value = null
        val books = uiState.value.subjectProgress
            .firstOrNull { it.subject.id == topic.subjectId }
            ?.books
            .orEmpty()
        editorModal.value = LearningEditorModal.TopicEditor(
            subjectId = topic.subjectId,
            availableBooks = books,
            existing = topic
        )
    }

    fun dismissEditor() {
        editorModal.value = null
        errorRes.value = null
    }

    fun openTopicHistory(topic: LearningTopic) {
        editorModal.value = LearningEditorModal.TopicHistory(topic)
    }

    fun saveSubject(
        existingId: String?,
        title: String,
        colorHex: Long,
        weeklyGoalMinutes: Int,
        dailyGoalMinutes: Int? = null
    ) {
        viewModelScope.launch {
            val result = learningRepository.saveSubject(
                SaveSubjectRequest(
                    existingId = existingId,
                    title = title,
                    colorHex = colorHex,
                    weeklyGoalMinutes = weeklyGoalMinutes,
                    dailyGoalMinutes = dailyGoalMinutes
                )
            )
            if (result.isSuccess) {
                selectedSubjectId.value = result.getOrNull()?.id
                editorModal.value = null
                errorRes.value = null
            } else {
                errorRes.value = R.string.learning_error_invalid_input
            }
        }
    }

    fun deleteSubject(subjectId: String) {
        viewModelScope.launch {
            learningRepository.softDeleteSubject(subjectId)
            editorModal.value = null
        }
    }

    fun saveBook(
        existingId: String?,
        subjectId: String,
        title: String,
        author: String?,
        totalPages: Int,
        currentPage: Int,
        totalChapters: Int = 0,
        completedChapters: Int = 0
    ) {
        viewModelScope.launch {
            val result = learningRepository.saveBook(
                SaveBookRequest(
                    existingId = existingId,
                    subjectId = subjectId,
                    title = title,
                    author = author,
                    totalPages = totalPages,
                    currentPage = currentPage,
                    totalChapters = totalChapters,
                    completedChapters = completedChapters
                )
            )
            if (result.isSuccess) {
                editorModal.value = null
                errorRes.value = null
            } else {
                errorRes.value = R.string.learning_error_invalid_input
            }
        }
    }

    fun stepBookPage(book: LearningBook, delta: Int) {
        viewModelScope.launch {
            if (book.totalPages == 0 && book.totalChapters > 0) {
                val nextChapter = (book.completedChapters + delta).coerceIn(0, book.totalChapters)
                learningRepository.updateBookProgress(book.id, book.currentPage, nextChapter)
            } else {
                val nextPage = (book.currentPage + delta).coerceAtLeast(0)
                learningRepository.updateBookProgress(bookId = book.id, currentPage = nextPage)
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            learningRepository.softDeleteBook(bookId)
            editorModal.value = null
        }
    }

    fun saveTopic(
        existingId: String?,
        subjectId: String,
        bookId: String?,
        title: String,
        status: TopicStatus,
        parentTopicId: String? = null
    ) {
        viewModelScope.launch {
            val result = learningRepository.saveTopic(
                SaveTopicRequest(
                    existingId = existingId,
                    subjectId = subjectId,
                    bookId = bookId,
                    title = title,
                    status = status,
                    parentTopicId = parentTopicId
                )
            )
            if (result.isSuccess) {
                editorModal.value = null
                errorRes.value = null
            } else {
                errorRes.value = R.string.learning_error_invalid_input
            }
        }
    }

    fun cycleTopicStatus(topic: LearningTopic) {
        val next = when (topic.status) {
            TopicStatus.TODO -> TopicStatus.IN_PROGRESS
            TopicStatus.IN_PROGRESS -> TopicStatus.MASTERED
            TopicStatus.MASTERED -> TopicStatus.TODO
        }
        viewModelScope.launch {
            learningRepository.updateTopicStatus(topic.id, next)
        }
    }

    fun deleteTopic(topicId: String) {
        viewModelScope.launch {
            learningRepository.softDeleteTopic(topicId)
            editorModal.value = null
        }
    }

    fun clearError() {
        errorRes.value = null
    }
}
