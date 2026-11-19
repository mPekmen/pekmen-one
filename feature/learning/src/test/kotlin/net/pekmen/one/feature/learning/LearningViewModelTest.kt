package net.pekmen.one.feature.learning

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.model.TopicStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningViewModelTest {

    @Test
    fun `learning view model creates subject book topic and cycles status`() = runTest {
        val repo = FakeLearningRepository()
        val vm = LearningViewModel(
            learningRepository = repo,
            preferencesRepository = FakePrefsRepo(),
            diagnosticsRepository = FakeDiagnosticsRepo()
        )

        vm.saveSubject(null, "Almanca B2", 0xFF8B7CF6, 150)
        val firstState = vm.uiState.first { it.subjectProgress.isNotEmpty() }
        val subjectId = firstState.subjectProgress.first().subject.id

        vm.saveBook(null, subjectId, "Erkundungen B2", null, 240, 40)
        val afterBook = vm.uiState.first { it.subjectProgress.first().books.isNotEmpty() }
        val book = afterBook.subjectProgress.first().books.first()
        assertEquals(40, book.currentPage)

        vm.saveTopic(null, subjectId, book.id, "Schreiben", TopicStatus.TODO)
        val afterTopic = vm.uiState.first { it.subjectProgress.first().topics.isNotEmpty() }
        val topic = afterTopic.subjectProgress.first().topics.first()

        vm.cycleTopicStatus(topic)
        val afterCycle = vm.uiState.first {
            it.subjectProgress.first().topics.first().status == TopicStatus.IN_PROGRESS
        }
        assertEquals(TopicStatus.IN_PROGRESS, afterCycle.subjectProgress.first().topics.first().status)
    }
}
