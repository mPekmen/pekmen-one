# Portability Integration Guide (M3)

Wire `BackupPortabilityServiceImpl` and `PortabilitySection` into `:app` and `:feature:settings` with zero storage
permissions (SAF `CreateDocument` / `OpenDocument` only).

## 1. Gradle Dependencies

In `feature/settings/build.gradle.kts` (`dependencies { ... }`; note `libs.androidx.activity.compose` is already
present on `integration` @ `ff5a494`):

```kotlin
implementation(project(":core:portability"))
```

In `app/build.gradle.kts` (`dependencies { ... }`):

```kotlin
implementation(project(":core:portability"))
```

## 2. `AppContainer.kt` (`app/src/main/kotlin/net/pekmen/one/AppContainer.kt`)

```kotlin
val backupPortabilityService: BackupPortabilityService by lazy {
    BackupPortabilityServiceImpl(database, userPreferencesDataStore, timeProvider)
}
```

Pass `backupPortabilityService` into `PekmenAppShell(...)` and `SettingsViewModel(...)` inside `PekmenNavHost.kt`.

## 3. `SettingsViewModel.kt` (`feature/settings/.../SettingsViewModel.kt`)

1. Add `private val backupPortabilityService: BackupPortabilityService` to `SettingsViewModel`.
2. Add `val portability: PortabilityUiState = PortabilityUiState()` to `SettingsUiState` and combine
   `portabilityFlow` (`MutableStateFlow(PortabilityUiState())`) into `uiState`.
3. Add 3 short methods (running SAF `ContentResolver` stream reads/writes on `Dispatchers.IO`):

```kotlin
fun exportBackupJson(uri: Uri, resolver: ContentResolver) =
    runPortabilityWrite(ExportFormat.JSON, uri, resolver) { backupPortabilityService.exportJsonArchive() }

fun exportCsv(target: PortabilityCsvTarget, uri: Uri, resolver: ContentResolver) =
    runPortabilityWrite(ExportFormat.CSV, uri, resolver) {
        when (target) {
            PortabilityCsvTarget.SESSIONS -> backupPortabilityService.exportSessionsCsv()
            PortabilityCsvTarget.LEARNING -> backupPortabilityService.exportLearningCsv()
            PortabilityCsvTarget.TASKS -> backupPortabilityService.exportTasksCsv()
        }
    }

fun restoreBackupJson(uri: Uri, resolver: ContentResolver) {
    viewModelScope.launch {
        portabilityFlow.value = PortabilityUiState(isBusy = true, status = PortabilityStatus.InProgress)
        val raw = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }
        }.getOrNull()
        val status = if (raw == null) {
            PortabilityStatus.Error(BackupValidationError.IO_FAILURE, "Unable to open input document")
        } else {
            when (val res = backupPortabilityService.importJsonArchive(raw)) {
                is BackupImportResult.Success -> PortabilityStatus.RestoreSuccess(res.report)
                is BackupImportResult.Failure -> PortabilityStatus.Error(res.reason, res.details)
            }
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
        val result = producer().mapCatching { payload ->
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(payload) }
                    ?: error("Unable to open output document")
            }
        }
        val status = result.fold(
            onSuccess = { PortabilityStatus.ExportSuccess(format) },
            onFailure = { PortabilityStatus.Error(BackupValidationError.IO_FAILURE, it.message.orEmpty()) }
        )
        portabilityFlow.value = PortabilityUiState(isBusy = false, status = status)
    }
}
```

## 4. `SettingsScreen.kt` (`feature/settings/.../SettingsScreen.kt`)

In `SettingsRoute`: obtain `val resolver = LocalContext.current.contentResolver` and forward
`onExportBackupJson = { viewModel.exportBackupJson(it, resolver) }`,
`onRestoreBackupJson = { viewModel.restoreBackupJson(it, resolver) }`, and
`onExportCsv = { target, uri -> viewModel.exportCsv(target, uri, resolver) }`.

In `SettingsScreen` `LazyColumn`:

```kotlin
item {
    PortabilitySection(
        state = state.portability,
        onExportBackupJson = onExportBackupJson,
        onRestoreBackupJson = onRestoreBackupJson,
        onExportCsv = onExportCsv
    )
}
```
