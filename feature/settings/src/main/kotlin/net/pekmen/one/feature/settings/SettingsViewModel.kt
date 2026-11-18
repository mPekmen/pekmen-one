package net.pekmen.one.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.SaveProfileRequest
import net.pekmen.one.core.data.SystemDiagnostics
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val profiles: List<FocusProfile> = emptyList(),
    val diagnostics: SystemDiagnostics = SystemDiagnostics(
        hasNotificationPermission = true,
        canScheduleExactAlarms = true
    ),
    val editingProfile: FocusProfile? = null,
    val isCreatingProfile: Boolean = false,
    val profileErrorRes: Int? = null
)

class SettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val profileRepository: FocusProfileRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository
) : ViewModel() {

    private val diagnosticsFlow = MutableStateFlow(diagnosticsRepository.refreshDiagnostics())
    private val editingProfile = MutableStateFlow<FocusProfile?>(null)
    private val isCreatingProfile = MutableStateFlow(false)
    private val profileErrorRes = MutableStateFlow<Int?>(null)

    private val editorState = combine(
        editingProfile,
        isCreatingProfile,
        profileErrorRes
    ) { editing, creating, err ->
        Triple(editing, creating, err)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferences,
        profileRepository.profiles,
        diagnosticsFlow,
        editorState
    ) { prefs, profiles, diag, (editing, creating, err) ->
        SettingsUiState(
            preferences = prefs,
            profiles = profiles,
            diagnostics = diag,
            editingProfile = editing,
            isCreatingProfile = creating,
            profileErrorRes = err
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState()
    )

    fun refreshDiagnostics() {
        diagnosticsFlow.value = diagnosticsRepository.refreshDiagnostics()
    }

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch { preferencesRepository.setThemePreference(preference) }
    }

    fun setDayRolloverHour(hour: Int) {
        viewModelScope.launch { preferencesRepository.setDayRolloverHour(hour) }
    }

    fun setAutoStartBreaks(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoStartBreaks(enabled) }
    }

    fun setAutoStartNextFocus(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoStartNextFocus(enabled) }
    }

    fun openEditProfile(profile: FocusProfile) {
        profileErrorRes.value = null
        isCreatingProfile.value = false
        editingProfile.value = profile
    }

    fun openCreateProfile() {
        profileErrorRes.value = null
        editingProfile.value = null
        isCreatingProfile.value = true
    }

    fun dismissProfileEditor() {
        editingProfile.value = null
        isCreatingProfile.value = false
        profileErrorRes.value = null
    }

    fun saveProfile(request: SaveProfileRequest) {
        viewModelScope.launch {
            val result = profileRepository.saveProfile(request)
            if (result.isSuccess) {
                dismissProfileEditor()
            } else {
                profileErrorRes.value = R.string.settings_profile_error_name
            }
        }
    }
}
