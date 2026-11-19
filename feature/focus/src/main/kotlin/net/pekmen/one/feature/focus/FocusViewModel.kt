package net.pekmen.one.feature.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.FocusSessionRepository
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.ActiveSessionSnapshot
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.FocusDisplayClock
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SessionLearningLink
import net.pekmen.one.core.model.UserPreferences

data class FocusUiState(
    val profiles: List<FocusProfile> = emptyList(),
    val selectedProfile: FocusProfile? = null,
    val selectedMode: FocusSessionMode = FocusSessionMode.POMODORO,
    val customMinutes: Int = 45,
    val activeSnapshot: ActiveSessionSnapshot? = null,
    val clock: FocusDisplayClock? = null,
    val todaySummary: DailyFocusSummary? = null,
    val wrapUpSnapshot: ActiveSessionSnapshot? = null,
    val hasNotificationPermission: Boolean = true,
    val notificationBannerDismissed: Boolean = false,
    val errorMessageRes: Int? = null,
    val subjects: List<LearningSubject> = emptyList(),
    val books: List<LearningBook> = emptyList(),
    val topics: List<LearningTopic> = emptyList(),
    val selectedSubjectId: String? = null,
    val selectedBookId: String? = null,
    val selectedTopicId: String? = null,
    val activeLinkedSubject: LearningSubject? = null,
    val activeLinkedBook: LearningBook? = null,
    val activeLinkedTopic: LearningTopic? = null,
    val isSavingWrapUp: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModel(
    private val sessionRepository: FocusSessionRepository,
    private val profileRepository: FocusProfileRepository,
    private val historyRepository: FocusHistoryRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository,
    private val learningRepository: LearningRepository? = null
) : ViewModel() {

    private val selectedModeOverride = MutableStateFlow<FocusSessionMode?>(null)
    private val wrapUpSnapshot = MutableStateFlow<ActiveSessionSnapshot?>(null)
    private val savingWrapUp = MutableStateFlow(false)
    private val notifBannerDismissed = MutableStateFlow(false)
    private val errorRes = MutableStateFlow<Int?>(null)
    private val permissionGranted = MutableStateFlow(
        diagnosticsRepository.refreshDiagnostics().hasNotificationPermission
    )

    private val selectedSubjectId = MutableStateFlow<String?>(null)
    private val selectedBookId = MutableStateFlow<String?>(null)
    private val selectedTopicId = MutableStateFlow<String?>(null)

    private val isRunningFlow = sessionRepository.activeSession
        .mapStatusRunning()
        .distinctUntilChanged()

    private val foregroundTicker = isRunningFlow.flatMapLatest { running ->
        flow {
            var tickCount = 0
            emit(diagnosticsRepository.currentElapsedRealtimeMs())
            while (running) {
                delay(TICK_INTERVAL_MS)
                val nowElapsed = diagnosticsRepository.currentElapsedRealtimeMs()
                emit(nowElapsed)
                tickCount += 1
                if (tickCount % CHECKPOINT_TICK_INTERVAL == 0) {
                    sessionRepository.checkpointActiveSession()
                }
            }
        }
    }

    private val todaySummaryFlow = preferencesRepository.preferences.flatMapLatest { prefs ->
        val effectiveToday = EffectiveDayCalculator.effectiveDateFor(
            epochMs = diagnosticsRepository.currentWallTimeMs(),
            zoneId = diagnosticsRepository.currentZoneId(),
            rolloverHour = prefs.dayRolloverHour
        )
        historyRepository.observeDailySummary(effectiveToday)
    }

    private val coreInputs = combine(
        profileRepository.profiles,
        preferencesRepository.preferences,
        sessionRepository.activeSession,
        foregroundTicker,
        todaySummaryFlow
    ) { profiles, prefs, active, elapsedMs, todaySummary ->
        CoreFocusBundle(profiles, prefs, active, elapsedMs, todaySummary)
    }

    private val learningSelectionFlow = combine(
        selectedSubjectId,
        selectedBookId,
        selectedTopicId
    ) { subjectId, bookId, topicId ->
        Triple(subjectId, bookId, topicId)
    }

    private val learningBundleFlow = combine(
        learningRepository?.observeSubjects() ?: flowOf(emptyList()),
        learningRepository?.observeBooks() ?: flowOf(emptyList()),
        learningRepository?.observeTopics() ?: flowOf(emptyList()),
        learningSelectionFlow
    ) { subjects, books, topics, selection ->
        LearningSelectionBundle(
            subjects = subjects,
            books = books,
            topics = topics,
            selectedSubjectId = selection.first,
            selectedBookId = selection.second,
            selectedTopicId = selection.third
        )
    }

    private val bannersFlow = combine(
        permissionGranted,
        notifBannerDismissed,
        errorRes
    ) { hasNotif, dismissed, err ->
        Triple(hasNotif, dismissed, err)
    }

    private val uiExtras = combine(
        selectedModeOverride,
        wrapUpSnapshot,
        savingWrapUp,
        bannersFlow
    ) { modeOverride, wrapUp, isSaving, banners ->
        UiFocusExtras(
            modeOverride = modeOverride,
            wrapUp = wrapUp,
            isSaving = isSaving,
            hasNotif = banners.first,
            dismissed = banners.second,
            err = banners.third
        )
    }

    val uiState: StateFlow<FocusUiState> = combine(
        coreInputs,
        learningBundleFlow,
        uiExtras
    ) { bundle, learning, extras ->
        buildUiState(bundle, learning, extras)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = FocusUiState()
    )

    init {
        viewModelScope.launch {
            profileRepository.ensureSeeded()
        }
        combine(sessionRepository.activeSession, foregroundTicker) { snapshot, elapsedMs ->
            snapshot to elapsedMs
        }.onEach { (snapshot, elapsedMs) ->
            if (snapshot != null) {
                checkForegroundExpiry(snapshot, elapsedMs)
            }
        }.launchIn(viewModelScope)
    }

    fun selectProfile(profile: FocusProfile) {
        viewModelScope.launch {
            preferencesRepository.setSelectedProfileId(profile.id)
            selectedModeOverride.value = profile.defaultMode
        }
    }

    fun selectMode(mode: FocusSessionMode) {
        selectedModeOverride.value = mode
    }

    fun setCustomMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setDefaultCustomMinutes(minutes)
        }
    }

    fun selectSubject(subjectId: String?) {
        selectedSubjectId.value = subjectId
        val books = uiState.value.books
        val topics = uiState.value.topics
        val currentBook = books.firstOrNull { it.id == selectedBookId.value }
        if (currentBook != null && currentBook.subjectId != subjectId) {
            selectedBookId.value = null
        }
        val currentTopic = topics.firstOrNull { it.id == selectedTopicId.value }
        if (currentTopic != null && currentTopic.subjectId != subjectId) {
            selectedTopicId.value = null
        }
    }

    fun selectBook(bookId: String?) {
        selectedBookId.value = bookId
        val book = uiState.value.books.firstOrNull { it.id == bookId }
        if (book != null) {
            selectedSubjectId.value = book.subjectId
        }
        val topicBookId = uiState.value.topics.firstOrNull { it.id == selectedTopicId.value }?.bookId
        if (bookId != null && topicBookId != null && topicBookId != bookId) {
            selectedTopicId.value = null
        }
    }

    fun selectTopic(topicId: String?) {
        selectedTopicId.value = topicId
        val topic = uiState.value.topics.firstOrNull { it.id == topicId } ?: return
        selectedSubjectId.value = topic.subjectId
        if (topic.bookId != null) {
            selectedBookId.value = topic.bookId
        }
    }

    fun prepareLearningLink(subjectId: String?, bookId: String?, topicId: String?) {
        selectedSubjectId.value = subjectId
        selectedBookId.value = bookId
        selectedTopicId.value = topicId
    }

    fun startFocus() {
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        viewModelScope.launch {
            val customArg = if (state.selectedMode == FocusSessionMode.CUSTOM) {
                state.customMinutes
            } else {
                null
            }
            val selectedBook = state.books.firstOrNull { it.id == state.selectedBookId }
            val link = if (
                state.selectedSubjectId != null ||
                state.selectedBookId != null ||
                state.selectedTopicId != null
            ) {
                SessionLearningLink(
                    subjectId = state.selectedSubjectId ?: selectedBook?.subjectId,
                    bookId = state.selectedBookId,
                    topicId = state.selectedTopicId,
                    bookStartPage = selectedBook?.currentPage
                )
            } else {
                null
            }
            val result = sessionRepository.startSession(
                profileId = profile.id,
                mode = state.selectedMode,
                customDurationMinutes = customArg,
                link = link
            )
            errorRes.value = if (result.isFailure) R.string.focus_error_start_failed else null
        }
    }

    fun pauseFocus() {
        viewModelScope.launch { sessionRepository.pauseActiveSession() }
    }

    fun resumeFocus() {
        viewModelScope.launch { sessionRepository.resumeActiveSession() }
    }

    fun startNextPhase() {
        viewModelScope.launch { sessionRepository.startAwaitingNextPhase() }
    }

    fun skipBreakToFocus() {
        viewModelScope.launch { sessionRepository.skipBreakAndStartFocus() }
    }

    fun openWrapUp() {
        if (savingWrapUp.value) return
        viewModelScope.launch {
            val frozen = sessionRepository.freezeActiveSessionForWrapUp()
            wrapUpSnapshot.value = frozen
        }
    }

    fun dismissWrapUp() {
        if (savingWrapUp.value) return
        wrapUpSnapshot.value = null
    }

    fun saveWrapUp(
        trimmedFocusSeconds: Long?,
        rating: Int?,
        note: String?,
        bookEndPage: Int? = null,
        markTopicMastered: Boolean = false
    ) {
        if (savingWrapUp.value) return
        savingWrapUp.value = true
        viewModelScope.launch {
            try {
                sessionRepository.completeActiveSession(
                    trimmedFocusSeconds = trimmedFocusSeconds,
                    rating = rating,
                    note = note,
                    bookEndPage = bookEndPage,
                    markTopicMastered = markTopicMastered
                )
                wrapUpSnapshot.value = null
            } finally {
                savingWrapUp.value = false
            }
        }
    }

    fun discardShortSession() {
        if (savingWrapUp.value) return
        viewModelScope.launch {
            sessionRepository.discardActiveSession()
            wrapUpSnapshot.value = null
        }
    }

    fun onPermissionResultChanged() {
        permissionGranted.value = diagnosticsRepository.refreshDiagnostics().hasNotificationPermission
    }

    fun dismissNotificationBanner() {
        notifBannerDismissed.value = true
    }

    fun clearError() {
        errorRes.value = null
    }

    private fun buildUiState(
        bundle: CoreFocusBundle,
        learning: LearningSelectionBundle,
        extras: UiFocusExtras
    ): FocusUiState {
        val selectedProfile = bundle.profiles.firstOrNull { it.id == bundle.prefs.selectedProfileId }
            ?: bundle.profiles.firstOrNull { it.id == BuiltInProfiles.STUDY_ID }
            ?: bundle.profiles.firstOrNull()

        val active = bundle.active
        val mode = active?.session?.sessionMode
            ?: extras.modeOverride
            ?: selectedProfile?.defaultMode
            ?: FocusSessionMode.POMODORO

        val clock = active?.let { snapshot ->
            FocusTimeMath.computeDisplayClock(
                snapshot = snapshot,
                nowElapsedMs = bundle.elapsedMs,
                currentBootCount = diagnosticsRepository.currentBootCount()
            )
        }

        val validSubjectId = learning.selectedSubjectId?.takeIf { id ->
            learning.subjects.any { it.id == id }
        }
        val validBookId = learning.selectedBookId?.takeIf { id ->
            learning.books.any { it.id == id && (validSubjectId == null || it.subjectId == validSubjectId) }
        }
        val validTopicId = learning.selectedTopicId?.takeIf { id ->
            learning.topics.any { it.id == id && (validSubjectId == null || it.subjectId == validSubjectId) }
        }

        val refSession = extras.wrapUp?.session ?: active?.session
        val linkedSubject = refSession?.subjectId?.let { id ->
            learning.subjects.firstOrNull { it.id == id }
        }
        val linkedBook = refSession?.bookId?.let { id ->
            learning.books.firstOrNull { it.id == id }
        }
        val linkedTopic = refSession?.topicId?.let { id ->
            learning.topics.firstOrNull { it.id == id }
        }

        return FocusUiState(
            profiles = bundle.profiles,
            selectedProfile = active?.profile ?: selectedProfile,
            selectedMode = mode,
            customMinutes = bundle.prefs.defaultCustomMinutes,
            activeSnapshot = active,
            clock = clock,
            todaySummary = bundle.todaySummary,
            wrapUpSnapshot = extras.wrapUp,
            hasNotificationPermission = extras.hasNotif,
            notificationBannerDismissed = extras.dismissed,
            errorMessageRes = extras.err,
            subjects = learning.subjects,
            books = learning.books,
            topics = learning.topics,
            selectedSubjectId = validSubjectId,
            selectedBookId = validBookId,
            selectedTopicId = validTopicId,
            activeLinkedSubject = linkedSubject,
            activeLinkedBook = linkedBook,
            activeLinkedTopic = linkedTopic,
            isSavingWrapUp = extras.isSaving
        )
    }

    private var lastExpiredSegmentId: String? = null

    private fun checkForegroundExpiry(snapshot: ActiveSessionSnapshot, nowElapsedMs: Long) {
        val openSeg = snapshot.openSegment ?: return
        val plannedMs = openSeg.plannedDurationMs ?: return
        if (snapshot.session.status != FocusSessionStatus.RUNNING) return
        if (openSeg.bootCount != diagnosticsRepository.currentBootCount()) return
        val elapsed = nowElapsedMs - openSeg.startedAtElapsedMs
        if (elapsed >= plannedMs && lastExpiredSegmentId != openSeg.id) {
            lastExpiredSegmentId = openSeg.id
            viewModelScope.launch {
                sessionRepository.handlePhaseTimerExpired(snapshot.session.id, openSeg.id)
            }
        }
    }

    private fun Flow<ActiveSessionSnapshot?>.mapStatusRunning(): Flow<Boolean> {
        return flow {
            collect { snapshot ->
                emit(snapshot?.session?.status == FocusSessionStatus.RUNNING)
            }
        }
    }

    private data class CoreFocusBundle(
        val profiles: List<FocusProfile>,
        val prefs: UserPreferences,
        val active: ActiveSessionSnapshot?,
        val elapsedMs: Long,
        val todaySummary: DailyFocusSummary
    )

    private data class LearningSelectionBundle(
        val subjects: List<LearningSubject>,
        val books: List<LearningBook>,
        val topics: List<LearningTopic>,
        val selectedSubjectId: String?,
        val selectedBookId: String?,
        val selectedTopicId: String?
    )

    private data class UiFocusExtras(
        val modeOverride: FocusSessionMode?,
        val wrapUp: ActiveSessionSnapshot?,
        val isSaving: Boolean,
        val hasNotif: Boolean,
        val dismissed: Boolean,
        val err: Int?
    )

    private companion object {
        const val TICK_INTERVAL_MS = 250L
        const val CHECKPOINT_TICK_INTERVAL = 60 // 15 seconds
    }
}
