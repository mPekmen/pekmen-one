package net.pekmen.one.feature.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pekmen.one.core.cloud.CloudAccountRepository
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.SaveProfileRequest
import net.pekmen.one.core.data.SystemDiagnostics
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.BackupImportResult
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.ExportFormat
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.core.portability.BackupPortabilityService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val profiles: List<FocusProfile> = emptyList(),
    val diagnostics: SystemDiagnostics = SystemDiagnostics(
        hasNotificationPermission = true,
        canScheduleExactAlarms = true
    ),
    val editingProfile: FocusProfile? = null,
    val isCreatingProfile: Boolean = false,
    val profileErrorRes: Int? = null,
    val portability: PortabilityUiState = PortabilityUiState(),
    val cloud: AccountSyncUiState = AccountSyncUiState()
)

class SettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val profileRepository: FocusProfileRepository,
    private val diagnosticsRepository: SystemDiagnosticsRepository,
    private val backupPortabilityService: BackupPortabilityService? = null,
    cloudAccountRepository: CloudAccountRepository? = null
) : ViewModel() {

    private val diagnosticsFlow = MutableStateFlow(diagnosticsRepository.refreshDiagnostics())
    private val editingProfile = MutableStateFlow<FocusProfile?>(null)
    private val isCreatingProfile = MutableStateFlow(false)
    private val profileErrorRes = MutableStateFlow<Int?>(null)
    private val portabilityFlow = MutableStateFlow(PortabilityUiState())
    private val cloudCoordinator = AccountSyncCoordinator(cloudAccountRepository, viewModelScope)

    private val editorState = combine(
        editingProfile,
        isCreatingProfile,
        profileErrorRes
    ) { editing, creating, err ->
        Triple(editing, creating, err)
    }

    private val sectionExtras = combine(
        portabilityFlow,
        cloudCoordinator.state
    ) { portability, cloud ->
        portability to cloud
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferences,
        profileRepository.profiles,
        diagnosticsFlow,
        editorState,
        sectionExtras
    ) { prefs, profiles, diag, (editing, creating, err), (portability, cloud) ->
        SettingsUiState(
            preferences = prefs,
            profiles = profiles,
            diagnostics = diag,
            editingProfile = editing,
            isCreatingProfile = creating,
            profileErrorRes = err,
            portability = portability,
            cloud = cloud
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
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

    fun setZenIntegrationEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setZenIntegrationEnabled(enabled) }
    }

    fun setRoutineKeywordEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setRoutineKeywordEnabled(enabled) }
    }

    fun onCloudAction(action: CloudAccountAction) {
        cloudCoordinator.dispatch(action)
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

    fun exportBackupJson(uri: Uri, resolver: ContentResolver) {
        val service = backupPortabilityService ?: return
        runPortabilityWrite(ExportFormat.JSON, uri, resolver) { service.exportJsonArchive() }
    }

    fun exportCsv(target: PortabilityCsvTarget, uri: Uri, resolver: ContentResolver) {
        val service = backupPortabilityService ?: return
        runPortabilityWrite(ExportFormat.CSV, uri, resolver) {
            when (target) {
                PortabilityCsvTarget.SESSIONS -> service.exportSessionsCsv()
                PortabilityCsvTarget.LEARNING -> service.exportLearningCsv()
                PortabilityCsvTarget.TASKS -> service.exportTasksCsv()
            }
        }
    }

    fun restoreBackupJson(uri: Uri, resolver: ContentResolver) {
        val service = backupPortabilityService ?: return
        viewModelScope.launch {
            portabilityFlow.value = PortabilityUiState(isBusy = true, status = PortabilityStatus.InProgress)
            val status = try {
                val raw = withContext(Dispatchers.IO) {
                    val stream = resolver.openInputStream(uri)
                        ?: throw IOException("Input document stream unavailable")
                    stream.use { input -> readBoundedUtf8(input, MAX_BACKUP_BYTES) }
                }
                when (val res = service.importJsonArchive(raw)) {
                    is BackupImportResult.Success -> PortabilityStatus.RestoreSuccess(res.report)
                    is BackupImportResult.Failure -> PortabilityStatus.Error(res.reason, res.details)
                }
            } catch (io: IOException) {
                PortabilityStatus.Error(BackupValidationError.IO_FAILURE, io.message.orEmpty())
            } catch (sec: SecurityException) {
                PortabilityStatus.Error(BackupValidationError.IO_FAILURE, sec.message.orEmpty())
            } catch (illegal: IllegalStateException) {
                PortabilityStatus.Error(BackupValidationError.IO_FAILURE, illegal.message.orEmpty())
            }
            portabilityFlow.value = PortabilityUiState(isBusy = false, status = status)
        }
    }

    private fun runPortabilityWrite(
        format: ExportFormat,
        uri: Uri,
        resolver: ContentResolver,
        producer: suspend () -> Result<String>
    ) {
        viewModelScope.launch {
            portabilityFlow.value = PortabilityUiState(isBusy = true, status = PortabilityStatus.InProgress)
            val status = try {
                val payload = producer().getOrThrow()
                withContext(Dispatchers.IO) {
                    val stream = resolver.openOutputStream(uri)
                        ?: throw IOException("Output document stream unavailable")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                }
                PortabilityStatus.ExportSuccess(format)
            } catch (io: IOException) {
                PortabilityStatus.Error(BackupValidationError.IO_FAILURE, io.message.orEmpty())
            } catch (sec: SecurityException) {
                PortabilityStatus.Error(BackupValidationError.IO_FAILURE, sec.message.orEmpty())
            } catch (illegal: IllegalStateException) {
                PortabilityStatus.Error(BackupValidationError.IO_FAILURE, illegal.message.orEmpty())
            }
            portabilityFlow.value = PortabilityUiState(isBusy = false, status = status)
        }
    }

    private fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("Backup file exceeds 16 MiB limit")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        const val STREAM_BUFFER_BYTES = 8 * 1024
    }
}
