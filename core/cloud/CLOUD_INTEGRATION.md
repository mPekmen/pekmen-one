# M4 & M5 Cloud Integration Guide (`core:cloud`)

Pekmen One ships as an **offline-first APK** by default (`pekmen.cloudEnabled = false`, zero `android.permission.INTERNET` permission in the merged offline `AndroidManifest.xml`).

## 1. Gradle Feature Flag (`pekmen.cloudEnabled`)

In `app/build.gradle.kts`, wire a Gradle property flag defaulting to `false`:

```kotlin
val cloudEnabled = providers.gradleProperty("pekmen.cloudEnabled")
    .map { it.toBooleanStrictOrNull() ?: false }
    .getOrElse(false)

android {
    defaultConfig {
        buildConfigField("boolean", "CLOUD_ENABLED", cloudEnabled.toString())
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${providers.gradleProperty("pekmen.supabaseUrl").getOrElse("")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${providers.gradleProperty("pekmen.supabaseAnonKey").getOrElse("")}\""
        )
    }
}
```

- **Default offline build** (`./gradlew assembleDebug`): `BuildConfig.CLOUD_ENABLED == false`, zero `INTERNET` permission, `scripts/verify-security.ps1` passes without `-AllowCloudPermissions`.
- **Optional cloud build**: add a separate `src/cloud/AndroidManifest.xml` (or productFlavor) declaring `<uses-permission android:name="android.permission.INTERNET" />` ONLY when `cloudEnabled == true`, and validate with `./scripts/verify-security.ps1 -AllowCloudPermissions`.
- **Never** commit `pekmen.supabaseUrl` or keys in `gradle.properties` or source; read only from local user `~/.gradle/gradle.properties` or CI secrets. Never bundle `service_role`.

## 2. Manifest Service Entry (Cloud Builds Only)

When cloud builds are enabled, register `SyncJobService` inside `<application>` (`exported="false"` + `BIND_JOB_SERVICE` so Android Lint `ExportedService` remains clean):

```xml
<service
    android:name="net.pekmen.one.core.cloud.sync.SyncJobService"
    android:permission="android.permission.BIND_JOB_SERVICE"
    android:exported="false" />
```

And have `PekmenApplication` implement `SyncTriggerProvider`:

```kotlin
override fun syncRunner(): suspend () -> Result<Int> = {
    container.cloudAccountRepository.triggerSyncNow()
}
```

## 3. AppContainer Wiring

```kotlin
val cloudRlsGateProvider: CloudRlsGateProvider by lazy {
    DefaultCloudRlsGateProvider(
        compiledWithCloudFlag = BuildConfig.CLOUD_ENABLED,
        backendEndpointConfigured = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
        rlsVerified = false // Flip true only after verified live Supabase RLS test run
    )
}

val syncWorkScheduler: SyncWorkScheduler by lazy {
    if (cloudRlsGateProvider.currentGateStatus().isLiveCloudEnabled) {
        JobSchedulerSyncScheduler(appContext)
    } else {
        NoOpSyncWorkScheduler()
    }
}

val tokenVault: TokenVault by lazy { KeystoreTokenVault(appContext) }
```

Pass `database`, `userPreferencesDataStore`, `tokenVault`, `authGateway`, `OutboxSyncEngine(...)`, `syncWorkScheduler`, `cloudRlsGateProvider`, and `timeProvider` into `CloudAccountRepositoryImpl`.

## 4. Settings / `AccountSyncSection` Wiring

Expose `CloudAccountRepository.accountState`, `syncState`, and `gateStatus` to `SettingsViewModel` / `AccountSyncSection`:

- When `!gateStatus.isLiveCloudEnabled`: display a clear offline/gated banner (`Cloud sync is disabled in this offline-first build`) with sign-in/sync controls disabled.
- When `AccountState.Guest` and gated ON: show Email + OTP sign-in form (`requestOtpSignIn(email)` -> `verifyOtpSignIn(email, code)`).
- After sign-in, if `countUnclaimedGuestRecords() > 0`: prompt user explicitly to call `claimGuestDataToSignedInUser()` (never silently merge across accounts).
- On sign-out: call `attemptLogout(forceDiscardPending = false)`. If `LogoutCheckResult.BlockedByPendingMutations(count)` is returned, prompt the user to **Sync first** (`triggerSyncNow()`) or **Confirm discard pending (`forceDiscardPending = true`)**.

## 5. Localization Checklist for Settings UI Wiring

Add all Account & Sync UI strings to both `feature/settings/src/main/res/values/strings_cloud.xml` and `values-tr/strings_cloud.xml` (no hardcoded Compose strings, escape apostrophes with `\'`).
