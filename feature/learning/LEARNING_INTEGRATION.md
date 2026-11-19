# Learning Module Integration Notes (Wave 2A)

1. `app/build.gradle.kts`:
   Add `implementation(project(":feature:learning"))` to `dependencies`.

2. `app/src/main/kotlin/net/pekmen/one/AppContainer.kt`:
   ```kotlin
   val learningRepository: LearningRepository by lazy {
       LearningRepositoryImpl(
           database = database,
           preferencesDataStore = userPreferencesDataStore,
           timeProvider = timeProvider
       )
   }
   ```

3. `app/src/main/kotlin/net/pekmen/one/MainActivity.kt`:
   Pass `learningRepository = appContainer.learningRepository` to `PekmenAppShell(...)`.

4. `app/src/main/kotlin/net/pekmen/one/navigation/PekmenNavHost.kt`:
   - Add `learningRepository: LearningRepository` parameter to `PekmenAppShell`.
   - Add `PekmenNavDestination(id = NAV_ID_LEARNING, labelRes = R.string.nav_learning, iconRes = PekmenIcons.Book)`.
   - Wire `composable<LearningRoute>` with `LearningViewModel(learningRepository, userPreferencesRepository, diagnosticsRepository)` and `LearningRoute(viewModel = vm, onFocusOnSubject = { subjectId, bookId, topicId -> ... })`.
   - Pass `learningRepository = learningRepository` to `FocusViewModel(...)` and `InsightsViewModel(...)`.
