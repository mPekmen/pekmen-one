package net.pekmen.one.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.cloud.CirclesRepository
import net.pekmen.one.core.cloud.circles.CircleAccessDeniedException
import net.pekmen.one.core.cloud.circles.CircleNotFoundException
import net.pekmen.one.core.cloud.circles.CircleSummaryProjector
import net.pekmen.one.core.cloud.circles.CirclesGatedException
import net.pekmen.one.core.cloud.circles.CirclesSyncSupport
import net.pekmen.one.core.cloud.circles.CirclesUnauthenticatedException
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.CircleMemberStanding
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.SocialCircle

data class CirclesUiState(
    val gateStatus: CloudRlsGateStatus = CloudRlsGateStatus(
        compiledWithCloudFlag = false,
        backendEndpointConfigured = false,
        rlsVerified = false
    ),
    val isSignedIn: Boolean = false,
    val circles: List<SocialCircle> = emptyList(),
    val selectedCircleId: String? = null,
    val selectedCircle: SocialCircle? = null,
    val activeMemberships: List<CircleMembership> = emptyList(),
    val myMembership: CircleMembership? = null,
    val selectedPeriod: CirclePeriodType = CirclePeriodType.DAILY,
    val leaderboard: List<CircleMemberStanding> = emptyList(),
    val availableSubjectTitles: List<String> = emptyList(),
    val isBusy: Boolean = false,
    val showCreateDialog: Boolean = false,
    val showJoinDialog: Boolean = false,
    val showLeaveConfirmDialog: Boolean = false,
    val errorMessageRes: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CirclesViewModel(
    private val circlesRepository: CirclesRepository,
    preferencesRepository: UserPreferencesRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository,
    private val gateStatusProvider: () -> CloudRlsGateStatus,
    availableSubjectsFlow: Flow<List<String>> = flowOf(emptyList()),
    private val isCurrentlyFocusingFlow: Flow<Boolean> = flowOf(false)
) : ViewModel() {

    private val explicitSelectedCircleId = MutableStateFlow<String?>(null)
    private val selectedPeriod = MutableStateFlow(CirclePeriodType.DAILY)
    private val isBusy = MutableStateFlow(false)
    private val dialogState = MutableStateFlow(DialogVisibility())
    private val errorMessageRes = MutableStateFlow<Int?>(null)
    private val latestIsFocusing = MutableStateFlow(false)

    private val selectionInputFlow = combine(
        circlesRepository.joinedCircles,
        explicitSelectedCircleId,
        selectedPeriod,
        preferencesRepository.preferences
    ) { circles, explicitId, period, prefs ->
        val selected = circles.firstOrNull { it.id == explicitId } ?: circles.firstOrNull()
        val effectiveToday = EffectiveDayCalculator.effectiveDateFor(
            epochMs = diagnosticsRepository.currentWallTimeMs(),
            zoneId = diagnosticsRepository.currentZoneId(),
            rolloverHour = prefs.dayRolloverHour
        )
        val periodStartDate = CircleSummaryProjector.periodWindow(period, effectiveToday).periodStartDate
        val ownerUserId = prefs.activeOwnerUserId?.trim()?.ifEmpty { null }
        SelectionBundle(
            ownerUserId = ownerUserId,
            circles = circles,
            selectedCircle = selected,
            period = period,
            periodStartDate = periodStartDate
        )
    }.distinctUntilChanged()

    private val circleDetailsFlow = selectionInputFlow.flatMapLatest { bundle ->
        val circle = bundle.selectedCircle
        if (circle == null) {
            flowOf(CircleDataBundle(bundle, emptyList(), emptyList()))
        } else {
            combine(
                circlesRepository.observeMemberships(circle.id),
                circlesRepository.observeLeaderboard(
                    circleId = circle.id,
                    periodType = bundle.period,
                    periodStartDate = bundle.periodStartDate
                )
            ) { memberships, standings ->
                CircleDataBundle(bundle, memberships, standings)
            }
        }
    }

    private val uiControlsFlow = combine(
        isBusy,
        dialogState,
        errorMessageRes
    ) { busy, dialogs, errorRes ->
        UiControlsBundle(busy, dialogs, errorRes)
    }

    init {
        viewModelScope.launch {
            isCurrentlyFocusingFlow.collect { running ->
                latestIsFocusing.value = running
            }
        }
    }

    val uiState: StateFlow<CirclesUiState> = combine(
        circleDetailsFlow,
        availableSubjectsFlow,
        uiControlsFlow
    ) { data, subjects, controls ->
        val selection = data.selection
        val myMembership = data.memberships.firstOrNull { it.userId == selection.ownerUserId }
        val combinedSubjects = (subjects + (myMembership?.sharingConfig?.allowedSubjectTitles ?: emptySet()))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        CirclesUiState(
            gateStatus = gateStatusProvider(),
            isSignedIn = selection.ownerUserId != null,
            circles = selection.circles,
            selectedCircleId = selection.selectedCircle?.id,
            selectedCircle = selection.selectedCircle,
            activeMemberships = data.memberships,
            myMembership = myMembership,
            selectedPeriod = selection.period,
            leaderboard = data.standings,
            availableSubjectTitles = combinedSubjects,
            isBusy = controls.isBusy,
            showCreateDialog = controls.dialogs.create,
            showJoinDialog = controls.dialogs.join,
            showLeaveConfirmDialog = controls.dialogs.leaveConfirm,
            errorMessageRes = controls.errorRes
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CirclesUiState(gateStatus = gateStatusProvider())
    )

    fun selectCircle(circleId: String) {
        explicitSelectedCircleId.value = circleId
        errorMessageRes.value = null
    }

    fun selectPeriod(period: CirclePeriodType) {
        selectedPeriod.value = period
    }

    fun openCreateDialog() {
        errorMessageRes.value = null
        dialogState.value = DialogVisibility(create = true)
    }

    fun dismissCreateDialog() {
        dialogState.value = dialogState.value.copy(create = false)
    }

    fun openJoinDialog() {
        errorMessageRes.value = null
        dialogState.value = DialogVisibility(join = true)
    }

    fun dismissJoinDialog() {
        dialogState.value = dialogState.value.copy(join = false)
    }

    fun openLeaveConfirm() {
        dialogState.value = DialogVisibility(leaveConfirm = true)
    }

    fun dismissLeaveConfirm() {
        dialogState.value = dialogState.value.copy(leaveConfirm = false)
    }

    fun createCircle(name: String, displayName: String) {
        if (name.isBlank() || displayName.isBlank()) {
            errorMessageRes.value = R.string.circles_error_blank_fields
            return
        }
        launchBusyAction {
            val result = circlesRepository.createCircle(name.trim(), displayName.trim())
            result.onSuccess { created ->
                explicitSelectedCircleId.value = created.id
                dialogState.value = DialogVisibility()
            }.onFailure { err ->
                errorMessageRes.value = mapErrorToRes(err)
            }
        }
    }

    fun joinCircle(inviteCode: String, displayName: String) {
        if (inviteCode.isBlank() || displayName.isBlank()) {
            errorMessageRes.value = R.string.circles_error_blank_fields
            return
        }
        launchBusyAction {
            val result = circlesRepository.joinCircle(inviteCode.trim(), displayName.trim())
            result.onSuccess { joined ->
                explicitSelectedCircleId.value = joined.id
                dialogState.value = DialogVisibility()
            }.onFailure { err ->
                errorMessageRes.value = mapErrorToRes(err)
            }
        }
    }

    fun leaveSelectedCircle() {
        val circleId = uiState.value.selectedCircleId ?: return
        launchBusyAction {
            val result = circlesRepository.leaveCircle(circleId)
            result.onSuccess {
                explicitSelectedCircleId.value = null
                dialogState.value = DialogVisibility()
            }.onFailure { err ->
                dialogState.value = DialogVisibility()
                errorMessageRes.value = mapErrorToRes(err)
            }
        }
    }

    fun setShareEnabled(enabled: Boolean) {
        val current = uiState.value.myMembership?.sharingConfig ?: CircleSharingConfig()
        val updated = current.copy(
            shareEnabled = enabled,
            shareLiveFocusStatus = if (enabled) current.shareLiveFocusStatus else false
        )
        applySharingConfig(updated)
    }

    fun setShareLiveStatus(enabled: Boolean) {
        val current = uiState.value.myMembership?.sharingConfig ?: CircleSharingConfig()
        val updated = current.copy(
            shareLiveFocusStatus = current.shareEnabled && enabled
        )
        applySharingConfig(updated)
    }

    fun toggleSubjectTitle(subjectTitle: String) {
        val trimmed = subjectTitle.trim()
        if (trimmed.isEmpty()) return
        val current = uiState.value.myMembership?.sharingConfig ?: CircleSharingConfig()
        val nextSet = if (trimmed in current.allowedSubjectTitles) {
            current.allowedSubjectTitles - trimmed
        } else {
            current.allowedSubjectTitles + trimmed
        }
        applySharingConfig(current.copy(allowedSubjectTitles = nextSet))
    }

    fun addCustomSubjectTitle(rawTitle: String) {
        val trimmed = rawTitle.trim()
        if (trimmed.isEmpty()) return
        val current = uiState.value.myMembership?.sharingConfig ?: CircleSharingConfig()
        applySharingConfig(current.copy(allowedSubjectTitles = current.allowedSubjectTitles + trimmed))
    }

    fun refreshSelectedCircle() {
        val circleId = uiState.value.selectedCircleId ?: return
        val sharingEnabled = uiState.value.myMembership?.sharingConfig?.shareEnabled == true
        launchBusyAction {
            if (sharingEnabled) {
                circlesRepository.publishLocalSummarySnapshot(
                    circleId = circleId,
                    isCurrentlyFocusing = latestIsFocusing.value
                ).onFailure { err ->
                    errorMessageRes.value = mapErrorToRes(err)
                    return@launchBusyAction
                }
            }
            val syncSupport = circlesRepository as? CirclesSyncSupport
            syncSupport?.refreshCircle(circleId)?.onFailure { err ->
                errorMessageRes.value = mapErrorToRes(err)
            }
        }
    }

    fun dismissError() {
        errorMessageRes.value = null
    }

    private fun applySharingConfig(updatedConfig: CircleSharingConfig) {
        val circleId = uiState.value.selectedCircleId ?: return
        launchBusyAction {
            val updateResult = circlesRepository.updateSharingConfig(circleId, updatedConfig)
            if (updateResult.isFailure) {
                errorMessageRes.value = mapErrorToRes(updateResult.exceptionOrNull())
                return@launchBusyAction
            }
            if (updatedConfig.shareEnabled) {
                circlesRepository.publishLocalSummarySnapshot(
                    circleId = circleId,
                    isCurrentlyFocusing = latestIsFocusing.value
                ).onFailure { err ->
                    errorMessageRes.value = mapErrorToRes(err)
                }
            }
        }
    }

    private fun launchBusyAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            isBusy.value = true
            errorMessageRes.value = null
            try {
                block()
            } finally {
                isBusy.value = false
            }
        }
    }

    private fun mapErrorToRes(throwable: Throwable?): Int {
        return when (throwable) {
            is CirclesGatedException -> R.string.circles_error_gated
            is CirclesUnauthenticatedException -> R.string.circles_error_sign_in_required
            is CircleAccessDeniedException -> R.string.circles_error_access_denied
            is CircleNotFoundException -> R.string.circles_error_invalid_invite
            else -> R.string.circles_error_generic
        }
    }

    private data class SelectionBundle(
        val ownerUserId: String?,
        val circles: List<SocialCircle>,
        val selectedCircle: SocialCircle?,
        val period: CirclePeriodType,
        val periodStartDate: String
    )

    private data class CircleDataBundle(
        val selection: SelectionBundle,
        val memberships: List<CircleMembership>,
        val standings: List<CircleMemberStanding>
    )

    private data class DialogVisibility(
        val create: Boolean = false,
        val join: Boolean = false,
        val leaveConfirm: Boolean = false
    )

    private data class UiControlsBundle(
        val isBusy: Boolean,
        val dialogs: DialogVisibility,
        val errorRes: Int?
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
