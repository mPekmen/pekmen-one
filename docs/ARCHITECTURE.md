# Pekmen One — Architecture Overview (M0–M1)

## 1. Module Dependency Graph

```text
:app
 ├── :feature:focus
 ├── :feature:insights
 ├── :feature:settings
 ├── :core:data
 ├── :core:platform
 ├── :core:database
 ├── :core:datastore
 ├── :core:designsystem
 └── :core:model

:feature:*  ──▶  :core:data (interfaces), :core:designsystem, :core:model
:core:data  ──▶  :core:database, :core:datastore, :core:platform (interfaces), :core:model
:core:database / :core:datastore / :core:platform  ──▶  :core:model
:core:model ──▶  Pure Kotlin JVM (zero Android SDK dependencies)
```

### Boundary Rules
- `:feature:*` modules **never** depend on `:core:database`, `:core:datastore`, `:core:platform`, or `AppContainer`.
- `AppContainer` lives in `:app`, manages singleton lifecycles, and passes narrow slice interfaces (`FocusDependencies`, `InsightsDependencies`, `SettingsDependencies`) rather than acting as a global service locator.
- UI never fetches from remote APIs directly; Room is the durable Single Source of Truth (SSOT).

## 2. Reboot-Safe & Process-Death-Safe Focus Time Accounting

Unlike naive `delay(1000)` counters that drift or inflate across device sleep/reboots, Pekmen One models every session as an ordered ledger of `focus_session_segments`:

1. **Same-Boot Process Death Recovery:**
   Each running segment stores `started_at_elapsed_ms` (`SystemClock.elapsedRealtime()`) and `boot_count` (`Settings.Global.BOOT_COUNT`). If Android kills the app process and the user returns during the same boot, elapsed duration is computed deterministically via `nowElapsed - started_at_elapsed_ms` with zero lost time.
2. **Device Reboot / Power-Off Protection:**
   During foreground ticks (`15s`), `onStop`, and `ACTION_SHUTDOWN`, `last_checkpointed_duration_ms` is persisted. When `reconcileActiveSessionAfterBootOrWake()` detects `currentBootCount != segment.boot_count` (or `currentElapsed < started_at_elapsed_ms`), it closes the segment at `last_checkpointed_duration_ms`, transitions the session to `PAUSED` with `interrupted_by_reboot = true`, and notifies the user—guaranteeing powered-off hours are **never** falsely recorded as focus time.
3. **Unattended Expiry Cap:**
   Countdown segments (`POMODORO`, `CUSTOM`) cap elapsed duration at `planned_duration_ms` so leaving a finished timer unattended overnight never inflates focus statistics.
4. **Custom Day Rollover (`dayRolloverHour`):**
   `EffectiveDayCalculator` assigns late-night sessions (e.g. `02:15` when rollover is `04:00`) to the logical study day.

## 3. Localization & Design System

- **Bilingual Support:** Full English (`values/strings.xml`) and Turkish (`values-tr/strings.xml`) string parity, enforced by Android Lint (`MissingTranslation`, `ExtraTranslation`, `HardcodedText` configured as `error`).
- **Midnight Violet / Daylight Violet:** Material 3 color tokens with verified WCAG AA contrast (`#10111A` `onPrimary` against `#8B7CF6` in dark mode) and `fontFeatureSettings = "tnum"` tabular numerals on live timers.

## 4. M2–M6 Extension Readiness

- `focus_sessions` already carries nullable foreign-reference placeholders (`subject_id`, `book_id`, `topic_id`, `task_id`) and `SyncMetadata` (`id` UUIDv7, `owner_user_id`, `created_at_ms`, `client_updated_at_ms`, `server_updated_at_ms`, `deleted_at_ms`, `version`, `sync_status`), plus local `sync_outbox`.
- M2 (Learning), M3 (Planner/Calendar/Samsung bridges/Export), M4 (Supabase Auth/RLS), M5 (Eventual Sync), and M6 (Private Circles) are **not** yet implemented or exposed in the UI.
