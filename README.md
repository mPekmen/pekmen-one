# Pekmen One

Open-source (**AGPL-3.0**) offline-first personal focus OS and learning companion for Android, built natively with **Kotlin, Jetpack Compose, Material 3, Room, and DataStore**.

> **Current development:** V2 builds on the existing modular application. Personal screens require a Supabase account and completed setup. See [V2 staging setup](docs/V2_STAGING.md) for testing account flows.

## Key Features

- **Midnight Violet & Daylight Violet Theme:** Follows Android system dark/light theme automatically (with manual override), WCAG-checked contrast, tabular numerals (`tnum`), and adaptive `NavigationBar` / `NavigationRail` layouts.
- **Bilingual Native Localization:** Full **English** and **Turkish** (`values` + `values-tr`) resources with localized date, time, and number formatting.
- **Offline-First Focus Engine:**
  - **Pomodoro**, **Stopwatch**, and **Custom** countdown modes across built-in (`Study`, `Work`, `Sleep`, `Personal`) and custom profiles.
  - Strict separation of **net focus time**, **break time**, and **paused gaps**.
  - **Process-death continuity** via `SystemClock.elapsedRealtime()` and **device reboot protection** via `BOOT_COUNT` checkpoints (powered-off time is never counted as focus).
  - **Session wrap-up & manual corrections:** Trim/adjust duration, rate focus quality (1–5), add reflection notes, or log past sessions retroactively.
  - **Real Daily & Weekly Summaries:** Custom `dayRolloverHour` (default `04:00`), Canvas 7-day charts, and profile breakdowns driven directly by Room SQLite.
- **Personal storage:** Each verified account uses its own Room database and preferences. Authentication credentials use Android Keystore encryption, and Android system backups are disabled.
- **Account and setup:** Supabase email and Google sign-in, personal goals and focus preferences, and an opt-in import for eligible records from earlier installs.

## Tech Stack

- **Language & UI:** Kotlin 2.1, Jetpack Compose, Material 3, Type-safe Navigation Compose
- **Persistence:** Room (SQLite + exported schema JSONs), Preferences DataStore
- **Quality & Security:** Detekt, Android Lint (`HardcodedText` & `MissingTranslation` as errors), CashApp Licensee, Gitleaks, Robolectric + JUnit4 + Turbine tests

## Local Build & Verification

1. Configure a full **JDK 21** with `jlink` and **Android SDK** (API 35). Set `ANDROID_HOME` or use an untracked `local.properties` with `sdk.dir=...`.
2. Run static analysis, license audit, unit/data tests, and debug APK build:
   ```bash
   ./gradlew detekt lintDebug licenseeDebug :core:model:test testDebugUnitTest assembleDebug
   ```
3. Run the local security & merged-manifest audit (PowerShell):
   ```powershell
   ./scripts/verify-security.ps1 -AllowCloudPermissions
   ```

## License

Licensed under **GNU Affero General Public License v3.0 (AGPL-3.0)** — see [LICENSE](LICENSE).
Security policy and vulnerability reporting: [SECURITY.md](SECURITY.md).
