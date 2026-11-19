# Private Circles Integration Notes (`w6-social` -> Wave 2C)

To wire M6 Private Circles into `:app` without merge conflicts in Wave 2C:

## 1. Gradle Dependencies (`app/build.gradle.kts`)
Ensure `:app` includes:
```kotlin
implementation(project(":core:cloud"))
implementation(project(":feature:circles"))
```

## 2. `AppContainer.kt`
Wire `CirclesRepositoryImpl` using `PekmenDatabase`, `UserPreferencesDataStore`, `TimeProvider`, `CloudRlsGateProvider` (from `w5-cloud`), and the active `CirclesRemoteGateway` (or `InMemoryCirclesServerDouble()` / fail-closed stub when offline):
```kotlin
val circlesRepository: CirclesRepository by lazy {
    CirclesRepositoryImpl(
        database = database,
        preferencesDataStore = userPreferencesDataStore,
        remoteGateway = circlesRemoteGateway,
        timeProvider = timeProvider,
        gateStatusProvider = { cloudRlsGateProvider.currentStatus() }
    )
}
```
Note: In offline-only APK builds where `pekmen.cloudEnabled == false`, `cloudRlsGateProvider.currentStatus().isLiveCloudEnabled` is `false`, so all live remote circle operations fail closed with `CirclesGatedException` and zero network requests are made.

## 3. `PekmenNavHost.kt` / Navigation Entry Hook
`feature:circles` already exposes `@Serializable data object CirclesRoute` and `@Composable fun CirclesTabRoute(viewModel: CirclesViewModel, modifier: Modifier = Modifier)`.
`core:designsystem` already has `R.string.nav_circles` and `PekmenIcons.Group`.

Add a destination (either as a navigation tab or an entry button from Settings / Insights):
```kotlin
composable<CirclesRoute> {
    val vm: CirclesViewModel = viewModel {
        CirclesViewModel(
            circlesRepository = circlesRepository,
            preferencesRepository = userPreferencesRepository,
            diagnosticsRepository = diagnosticsRepository,
            gateStatusProvider = { cloudRlsGateProvider.currentStatus() },
            availableSubjectsFlow = learningRepository.subjects.map { list -> list.map { it.title } },
            isCurrentlyFocusingFlow = sessionRepository.activeSession.map { snapshot ->
                snapshot?.session?.status == FocusSessionStatus.RUNNING
            }
        )
    }
    CirclesTabRoute(viewModel = vm)
}
```
