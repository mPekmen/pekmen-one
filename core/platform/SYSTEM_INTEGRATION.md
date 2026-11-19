# M3 System Bridges Integration Guide (`w3-system`)

This document specifies exact, zero-conflict integration hooks for wiring:
- Profile-scoped opt-in `AutomaticZenRule` (`FocusZenRuleManager`)
- Samsung Modes & Routines bridges (`AppShortcutsPublisher`, `FocusQuickSettingsTileService`, `SamsungRoutineBridgeHelper`)
- Multi-config Compose previews (`PekmenPreviewAnnotations.kt`)

---

## 1. AndroidManifest & Security Script Updates

### 1.1 `core/platform/src/main/AndroidManifest.xml`
Add the Quick Settings tile `<service>` inside `<application>` (strictly guarded with `BIND_QUICK_SETTINGS_TILE`):

```xml
        <service
            android:name="net.pekmen.one.core.platform.samsung.FocusQuickSettingsTileService"
            android:exported="true"
            android:icon="@drawable/ic_stat_focus"
            android:label="@string/tile_focus_label"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
```

### 1.2 `app/src/main/AndroidManifest.xml` (optional static XML anchor)
Inside `<activity android:name=".MainActivity" ...>`, optionally link the XML anchor (dynamic shortcuts are still published at runtime via `ShortcutManagerCompat` so `.debug` and release package IDs both work):

```xml
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/pekmen_shortcuts" />
```

### 1.3 `scripts/verify-security.ps1` alignment
`scripts/verify-security.ps1` lines 101, 112, and 116 currently check `net.pekmen.one.core.platform.FocusQuickSettingsTileService`.
Update those three string literals to match the `.samsung` subpackage:
- `net.pekmen.one.core.platform.samsung.FocusQuickSettingsTileService`

---

## 2. `AppContainer` & `PekmenApplication` Wiring

### 2.1 `app/src/main/kotlin/net/pekmen/one/AppContainer.kt`
Add `zenRuleManager`, `appShortcutsPublisher`, and `focusTileStateBridge`:

```kotlin
    val zenRuleManager: FocusZenRuleManager by lazy {
        FocusZenRuleManager(
            context = appContext,
            configurationActivity = ComponentName(appContext, MainActivity::class.java)
        )
    }

    val appShortcutsPublisher: AppShortcutsPublisher by lazy {
        AppShortcutsPublisher(
            context = appContext,
            launcherActivityClass = MainActivity::class.java
        )
    }

    val focusTileStateBridge: FocusTileStateBridge by lazy {
        object : FocusTileStateBridge {
            override suspend fun currentTileStatus(): FocusTileStatus {
                val active = sessionRepository.activeSession.first() ?: return FocusTileStatus(status = null)
                return FocusTileStatus(
                    status = active.session.status,
                    profileName = profileNameProvider.resolve(active.profile),
                    phase = active.session.currentPhase
                )
            }

            override suspend fun toggleFromTile(): FocusTileStatus {
                val active = sessionRepository.activeSession.first() ?: return FocusTileStatus(status = null)
                when (active.session.status) {
                    FocusSessionStatus.RUNNING -> sessionRepository.pauseActiveSession()
                    FocusSessionStatus.PAUSED -> sessionRepository.resumeActiveSession()
                    FocusSessionStatus.AWAITING_NEXT_PHASE -> sessionRepository.startAwaitingNextPhase()
                    FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> Unit
                }
                return currentTileStatus()
            }
        }
    }
```

Also pass a tile/zen sync callback (or gateway) into `FocusSessionRepositoryImpl` / `SystemDiagnosticsRepositoryImpl` (see Section 3 & 5).

### 2.2 `app/src/main/kotlin/net/pekmen/one/PekmenApplication.kt`
Implement `FocusTileBridgeProvider` and publish dynamic shortcuts on startup:

```kotlin
class PekmenApplication : Application(), FocusTriggerProvider, FocusTileBridgeProvider {
    // ...
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appShortcutsPublisher.publishDynamicShortcuts()
        applicationScope.launch {
            container.profileRepository.ensureSeeded()
            container.sessionRepository.reconcileActiveSessionAfterBootOrWake()
        }
    }

    override fun focusTriggerHandler(): FocusTriggerHandler = container.focusTriggerHandler
    override fun focusTileStateBridge(): FocusTileStateBridge = container.focusTileStateBridge
    override fun launcherActivityClass(): Class<out Activity> = MainActivity::class.java
}
```

---

## 3. `FocusSessionRepositoryImpl` & Notification Hooks

Never call `NotificationManager.setInterruptionFilter(...)` anywhere. Always invoke system side effects **outside** `database.withTransaction`:

1. **On session start (`startSession`), resume (`resumeActiveSession`), next phase (`startAwaitingNextPhase`), skip break (`skipBreakAndStartFocus`), timer expiry (`handlePhaseTimerExpired`), and boot reconciliation (`reconcileActiveSessionAfterBootOrWake`):**
   ```kotlin
   zenRuleManager.syncForSessionState(
       zenIntegrationEnabled = prefs.zenIntegrationEnabled,
       profile = profile.toDomain(),
       status = FocusSessionStatus.valueOf(session.status),
       phase = FocusPhase.valueOf(session.currentPhase)
   )
   FocusQuickSettingsTileService.requestTileUpdate(appContext)
   ```
   *(When `phase == SHORT_BREAK` or `LONG_BREAK` or `status == PAUSED` or `AWAITING_NEXT_PHASE`, `syncForSessionState` automatically deactivates ONLY Pekmen's owned `ruleId` via `Condition.STATE_FALSE`.)*

2. **On pause (`pauseActiveSession`), complete (`completeActiveSession`), and discard (`discardActiveSession`):**
   ```kotlin
   zenRuleManager.deactivateOwnedRule()
   FocusQuickSettingsTileService.requestTileUpdate(appContext)
   ```

3. **In `FocusNotificationManager` (`showRunningNotification`, `showPausedNotification`, `showAwaitingNextPhaseNotification`):**
   Pass optional `routineSubtext: String?` computed via:
   ```kotlin
   val routineSubtext = SamsungRoutineBridgeHelper.appendKeywordSubtext(
       baseSubtext = null,
       routineKeywordEnabled = prefs.routineKeywordEnabled,
       slug = profile.builtInSlug,
       status = sessionStatus,
       phase = phase
   )
   builder.setSubText(routineSubtext)
   ```

---

## 4. `MainActivity` Shortcut Intent Handling

Handle both `onCreate` and `onNewIntent` (activity already has `android:launchMode="singleTop"`):

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShortcutIntent(intent)
        // ...
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        val command = AppShortcutsPublisher.parseShortcutIntent(intent) ?: return
        lifecycleScope.launch {
            appContainer.profileRepository.ensureSeeded()
            val active = appContainer.sessionRepository.activeSession.first()
            when (command) {
                is FocusShortcutCommand.StartProfile -> {
                    appContainer.userPreferencesRepository.setSelectedProfileId(command.profileId)
                    if (active == null) {
                        appContainer.sessionRepository.startSession(
                            profileId = command.profileId,
                            mode = command.mode
                        )
                    }
                }
                FocusShortcutCommand.ToggleActiveFocus -> {
                    when (active?.session?.status) {
                        FocusSessionStatus.RUNNING -> appContainer.sessionRepository.pauseActiveSession()
                        FocusSessionStatus.PAUSED -> appContainer.sessionRepository.resumeActiveSession()
                        FocusSessionStatus.AWAITING_NEXT_PHASE ->
                            appContainer.sessionRepository.startAwaitingNextPhase()
                        null, FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> {
                            val prefs = appContainer.userPreferencesRepository.preferences.first()
                            val profileId = prefs.selectedProfileId ?: BuiltInProfiles.STUDY_ID
                            appContainer.sessionRepository.startSession(profileId, FocusSessionMode.POMODORO)
                        }
                    }
                }
            }
        }
    }
```

---

## 5. `SettingsScreen` + `SettingsViewModel` Zen & Samsung Bridge Card

### 5.1 `SystemDiagnostics` / `SettingsViewModel` additions
Add `val hasNotificationPolicyAccess: Boolean = false` to `SystemDiagnostics` (populated in `SystemDiagnosticsRepositoryImpl` via `NotificationManager.isNotificationPolicyAccessGranted`), and add methods on `SettingsViewModel`:

```kotlin
    fun setZenIntegrationEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setZenIntegrationEnabled(enabled) }
    }

    fun setRoutineKeywordEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setRoutineKeywordEnabled(enabled) }
    }
```

### 5.2 `SettingsScreen` Card Composable (`ZenAndSamsungBridgeCard`)
Add an `item { ZenAndSamsungBridgeCard(...) }` inside `SettingsScreen`'s `LazyColumn`:

```kotlin
@Composable
private fun ZenAndSamsungBridgeCard(
    zenIntegrationEnabled: Boolean,
    routineKeywordEnabled: Boolean,
    hasPolicyAccess: Boolean,
    onToggleZenIntegration: (Boolean) -> Unit,
    onToggleRoutineKeyword: (Boolean) -> Unit,
    onOpenDndPolicySettings: () -> Unit
) {
    val context = LocalContext.current
    val setupSteps = remember(context) { SamsungRoutineBridgeHelper.oneUiSetupSteps(context) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_system_bridges_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SwitchSettingRow(
                label = stringResource(R.string.settings_zen_integration_label),
                checked = zenIntegrationEnabled,
                onCheckedChange = onToggleZenIntegration
            )
            DiagnosticRow(
                title = stringResource(R.string.settings_dnd_policy_access_title),
                status = if (hasPolicyAccess) {
                    stringResource(R.string.settings_status_granted)
                } else {
                    stringResource(R.string.settings_status_missing)
                },
                actionLabel = stringResource(R.string.settings_action_open_system),
                onAction = onOpenDndPolicySettings
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchSettingRow(
                label = stringResource(R.string.settings_routine_keyword_label),
                checked = routineKeywordEnabled,
                onCheckedChange = onToggleRoutineKeyword
            )
            setupSteps.forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

Helper intent launcher for `onOpenDndPolicySettings`:
```kotlin
private fun openDndPolicySettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    runCatching { context.startActivity(intent) }
}
```

Required EN + TR strings for `feature/settings/src/main/res/values/strings.xml` and `values-tr/strings.xml`:
- EN:
  - `settings_system_bridges_section`: `Focus Modes & Samsung Routines`
  - `settings_zen_integration_label`: `Activate Pekmen Focus DND rule automatically`
  - `settings_dnd_policy_access_title`: `Do Not Disturb access`
  - `settings_routine_keyword_label`: `Include [PekmenFocus] keyword tag in notifications`
- TR:
  - `settings_system_bridges_section`: `Odak Modları ve Samsung Rutinleri`
  - `settings_zen_integration_label`: `Pekmen Odak Rahatsız Etmeyin kuralını otomatik etkinleştir`
  - `settings_dnd_policy_access_title`: `Rahatsız Etmeyin erişimi`
  - `settings_routine_keyword_label`: `Bildirimlere [PekmenFocus] anahtar etiketini ekle`

---

## 6. One UI Modes & Routines End-User Setup Notes
1. **Quick Settings Tile**: Open Samsung Quick Settings -> Edit -> drag **Pekmen Focus** (`Pekmen Odak`) into active tiles to monitor Focus state or pause/resume with one tap.
2. **Start Focus from a Routine**: Modes & Routines -> Routines -> `+` -> **Then** -> **Apps** -> **Open app or Do app action** -> pick **Study Pomodoro**, **Work Focus**, **Sleep Custom**, or **Toggle Focus**.
3. **Trigger Device Actions from Pekmen Focus**: Enable **Include [PekmenFocus] keyword tag in notifications** in Pekmen Settings, then in Modes & Routines create a routine with **If -> Notification received -> App: Pekmen One -> Keyword: `[PekmenFocus:STUDY:RUNNING]`** (or `WORK` / `SLEEP`), and pair with **Do Not Disturb**, **Grayscale**, or **App restrictions**.
4. **Strict Offline & Non-Invasive Design**: Zero Knox Device Owner, zero AccessibilityService, zero undocumented Samsung SDKs, and zero calls to `NotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL)`.
