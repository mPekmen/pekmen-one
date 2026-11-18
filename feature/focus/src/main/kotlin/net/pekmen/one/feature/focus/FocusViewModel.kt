package net.pekmen.one.feature.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.FocusSessionRepository
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
    val errorMessageRes: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModel(
    private val sessionRepository: FocusSessionRepository,
    private val profileRepository: FocusProfileRepository,
    private val historyRepository: FocusHistoryRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository
) : ViewModel() {

    private val selectedModeOverride = MutableStateFlow<FocusSessionMode?>(null)
    private val wrapUpSnapshot = MutableStateFlow<ActiveSessionSnapshot?>(null)
    private val notifBannerDismissed = MutableStateFlow(false)
    private val errorRes = MutableStateFlow<Int?>(null)
    private val permissionGranted = MutableStateFlow(
        diagnosticsRepository.refreshDiagnostics().hasNotificationPermission
    )

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

    private val uiExtras = combine(
        selectedModeOverride,
        wrapUpSnapshot,
        permissionGranted,
        notifBannerDismissed,
        errorRes
    ) { modeOverride, wrapUp, hasNotif, dismissed, err ->
        UiFocusExtras(modeOverride, wrapUp, hasNotif, dismissed, err)
    }

    val uiState: StateFlow<FocusUiState> = combine(coreInputs, uiExtras) { bundle, extras ->
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

        FocusUiState(
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
            errorMessageRes = extras.err
        )
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

    fun startFocus() {
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        viewModelScope.launch {
            val customArg = if (state.selectedMode == FocusSessionMode.CUSTOM) {
                state.customMinutes
            } else {
                null
            }
            val result = sessionRepository.startSession(
                profileId = profile.id,
                mode = state.selectedMode,
                customDurationMinutes = customArg
            )
            if (result.isFailure) {
                errorRes.value = R.string.focus_error_start_failed
            } else {
                errorRes.value = null
            }
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
        viewModelScope.launch {
            val frozen = sessionRepository.freezeActiveSessionForWrapUp()
            wrapUpSnapshot.value = frozen
        }
    }

    fun dismissWrapUp() {
        wrapUpSnapshot.value = null
    }

    fun saveWrapUp(trimmedFocusSeconds: Long?, rating: Int?, note: String?) {
        viewModelScope.launch {
            sessionRepository.completeActiveSession(trimmedFocusSeconds, rating, note)
            wrapUpSnapshot.value = null
        }
    }

    fun discardShortSession() {
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

    private fun kotlinx.coroutines.flow.Flow<ActiveSessionSnapshot?>.mapStatusRunning():
        kotlinx.coroutines.flow.Flow<Boolean> {
        return kotlinx.coroutines.flow.flow {
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

    private data class UiFocusExtras(
        val modeOverride: FocusSessionMode?,
        val wrapUp: ActiveSessionSnapshot?,
        val hasNotif: Boolean,
        val dismissed: Boolean,
        val err: Int?
    )

    private companion object {
        const val TICK_INTERVAL_MS = 250L
        const val CHECKPOINT_TICK_INTERVAL = 60 // 15 seconds
    }
}
