# Pekmen One

Open-source (**AGPL-3.0**) offline-first personal focus OS and learning companion for Android, built natively with **Kotlin, Jetpack Compose, Material 3, Room, and DataStore**.

> **Current Status:** M0 (Modular Foundation, Midnight Violet Design System, Localization, CI & Security Baseline) and M1 (Offline-First Focus Engine, Reboot-Safe Recovery, Manual Adjustments & Real Summaries) are implemented on `feat/m0-m1-foundation`. M2–M6 (Learning subjects/books, Planner/Calendar, Samsung Routine bridges, Supabase Auth/Sync, Private Circles, AI) are planned for upcoming milestones.

## Key Implemented Features (M0 + M1)

- **Midnight Violet & Daylight Violet Theme:** Follows Android system dark/light theme automatically (with manual override), WCAG-checked contrast, tabular numerals (`tnum`), and adaptive `NavigationBar` / `NavigationRail` layouts.
- **Bilingual Native Localization:** Full **English** and **Turkish** (`values` + `values-tr`) resources with localized date, time, and number formatting.
- **Offline-First Focus Engine:**
  - **Pomodoro**, **Stopwatch**, and **Custom** countdown modes across built-in (`Study`, `Work`, `Sleep`, `Personal`) and custom profiles.
  - Strict separation of **net focus time**, **break time**, and **paused gaps**.
  - **Process-death continuity** via `SystemClock.elapsedRealtime()` and **device reboot protection** via `BOOT_COUNT` checkpoints (powered-off time is never counted as focus).
  - **Session wrap-up & manual corrections:** Trim/adjust duration, rate focus quality (1–5), add reflection notes, or log past sessions retroactively.
  - **Real Daily & Weekly Summaries:** Custom `dayRolloverHour` (default `04:00`), Canvas 7-day charts, and profile breakdowns driven directly by Room SQLite.
- **Security-First Offline Isolation:** Zero `INTERNET` permission in M0–M1, `android:allowBackup="false"`, private lock-screen notifications, non-exported internal receivers, and automated Gitleaks/Lint/Licensee checks.

## Tech Stack

- **Language & UI:** Kotlin 2.1, Jetpack Compose, Material 3, Type-safe Navigation Compose
- **Persistence:** Room (SQLite + exported schema JSONs), Preferences DataStore
- **Quality & Security:** Detekt, Android Lint (`HardcodedText` & `MissingTranslation` as errors), CashApp Licensee, Gitleaks, Robolectric + JUnit4 + Turbine tests

## Local Build & Verification

1. Ensure **JDK 21** and **Android SDK** (API 35) are configured, and set `sdk.dir=...` in untracked `local.properties`.
2. Run static analysis, license audit, unit/data tests, and debug APK build:
   ```bash
   ./gradlew detekt lintDebug licenseeDebug testDebugUnitTest assembleDebug
   ```
3. Run the local security & merged-manifest audit (PowerShell):
   ```powershell
   ./scripts/verify-security.ps1
   ```

## License

Licensed under **GNU Affero General Public License v3.0 (AGPL-3.0)** — see [LICENSE](LICENSE).
Security policy and vulnerability reporting: [SECURITY.md](SECURITY.md).
