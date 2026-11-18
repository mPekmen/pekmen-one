package net.pekmen.one.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.RetroactiveSessionRequest
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.WeeklyFocusSummary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class ManualSessionInput(
    val profileId: String,
    val daysAgo: Int,
    val hour: Int,
    val minute: Int,
    val focusMinutes: Int,
    val breakMinutes: Int,
    val rating: Int?,
    val note: String?
)

data class InsightsUiState(
    val profiles: List<FocusProfile> = emptyList(),
    val todaySummary: DailyFocusSummary? = null,
    val weeklySummary: WeeklyFocusSummary? = null,
    val recentSessions: List<CompletedSessionItem> = emptyList(),
    val editingSession: CompletedSessionItem? = null,
    val isManualSheetOpen: Boolean = false,
    val errorMessageRes: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(
    private val historyRepository: FocusHistoryRepository,
    profileRepository: FocusProfileRepository,
    preferencesRepository: UserPreferencesRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository
) : ViewModel() {

    private val editingSession = MutableStateFlow<CompletedSessionItem?>(null)
    private val manualSheetOpen = MutableStateFlow(false)
    private val errorRes = MutableStateFlow<Int?>(null)

    private val effectiveTodayFlow = preferencesRepository.preferences.flatMapLatest { prefs ->
        val effectiveToday = EffectiveDayCalculator.effectiveDateFor(
            epochMs = diagnosticsRepository.currentWallTimeMs(),
            zoneId = diagnosticsRepository.currentZoneId(),
            rolloverHour = prefs.dayRolloverHour
        )
        combine(
            historyRepository.observeDailySummary(effectiveToday),
            historyRepository.observeWeeklySummary(effectiveToday)
        ) { daily, weekly -> daily to weekly }
    }

    private val dataFlow = combine(
        profileRepository.profiles,
        effectiveTodayFlow,
        historyRepository.observeRecentCompletedSessions(60)
    ) { profiles, (daily, weekly), recent ->
        Triple(profiles, daily to weekly, recent)
    }

    val uiState: StateFlow<InsightsUiState> = combine(
        dataFlow,
        editingSession,
        manualSheetOpen,
        errorRes
    ) { (profiles, summaries, recent), editing, manualOpen, err ->
        InsightsUiState(
            profiles = profiles,
            todaySummary = summaries.first,
            weeklySummary = summaries.second,
            recentSessions = recent,
            editingSession = editing,
            isManualSheetOpen = manualOpen,
            errorMessageRes = err
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = InsightsUiState()
    )

    fun openManualSheet() {
        errorRes.value = null
        manualSheetOpen.value = true
    }

    fun dismissManualSheet() {
        manualSheetOpen.value = false
    }

    fun openEditSheet(item: CompletedSessionItem) {
        errorRes.value = null
        editingSession.value = item
    }

    fun dismissEditSheet() {
        editingSession.value = null
    }

    fun saveManualSession(input: ManualSessionInput) {
        viewModelScope.launch {
            val zone = diagnosticsRepository.currentZoneId()
            val nowWall = diagnosticsRepository.currentWallTimeMs()
            val baseDate = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate()
            val targetDate = baseDate.minusDays(input.daysAgo.toLong().coerceIn(0L, 30L))
            val startTime = LocalTime.of(input.hour.coerceIn(0, 23), input.minute.coerceIn(0, 59))
            val startedAtWallMs = targetDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()

            val result = historyRepository.logRetroactiveSession(
                RetroactiveSessionRequest(
                    profileId = input.profileId,
                    startedAtWallMs = startedAtWallMs,
                    focusMinutes = input.focusMinutes,
                    breakMinutes = input.breakMinutes,
                    rating = input.rating,
                    note = input.note
                )
            )
            if (result.isSuccess) {
                manualSheetOpen.value = false
                errorRes.value = null
            } else {
                errorRes.value = R.string.insights_error_invalid_entry
            }
        }
    }

    fun saveEditedSession(
        sessionId: String,
        focusMinutes: Int,
        breakMinutes: Int,
        profileId: String,
        rating: Int?,
        note: String?
    ) {
        viewModelScope.launch {
            val result = historyRepository.updateCompletedSession(
                sessionId = sessionId,
                newFocusSeconds = focusMinutes.coerceAtLeast(0) * 60L,
                newBreakSeconds = breakMinutes.coerceAtLeast(0) * 60L,
                profileId = profileId,
                rating = rating,
                note = note
            )
            if (result.isSuccess) {
                editingSession.value = null
                errorRes.value = null
            } else {
                errorRes.value = R.string.insights_error_invalid_entry
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            historyRepository.softDeleteSession(sessionId)
            editingSession.value = null
        }
    }

    fun clearError() {
        errorRes.value = null
    }

    fun currentDayStartDate(): LocalDate {
        return Instant.ofEpochMilli(diagnosticsRepository.currentWallTimeMs())
            .atZone(diagnosticsRepository.currentZoneId())
            .toLocalDate()
    }
}
