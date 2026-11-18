# Security & Privacy Policy — Pekmen One

Pekmen One is an open-source (AGPL-3.0) personal OS and offline-first Android application developed under a **security-first, privacy-by-default, assume-breach** engineering model.

## 1. Current Threat Model & Privacy Boundaries (M0–M1)

- **Zero Network Exposure:** M0–M1 intentionally declare **no `android.permission.INTERNET`** in the merged `AndroidManifest.xml`. All focus sessions, profiles, and user preferences stay strictly on-device inside app-private storage (Room SQLite + Jetpack DataStore).
- **Backup & Extraction Hardening:** `android:allowBackup="false"`, `data_extraction_rules.xml`, and `backup_rules.xml` prevent unencrypted cloud backups or automated device-to-device extraction of local study/focus records.
- **Minimal Permissions:** Only `POST_NOTIFICATIONS` (optional user-facing timer status), `SCHEDULE_EXACT_ALARM` (optional exact Pomodoro phase end alerts with non-exact fallback), `RECEIVE_BOOT_COMPLETED` (safe timer reconciliation on reboot), and `VIBRATE` are declared.
- **Component & IPC Hardening:**
  - `MainActivity` is the sole exported Activity (`MAIN`/`LAUNCHER`), with zero custom deep-link URI schemes in M0–M1.
  - `FocusActionReceiver` and `FocusAlarmReceiver` are strictly `android:exported="false"`.
  - All `PendingIntent` instances use explicit component targets and `PendingIntent.FLAG_IMMUTABLE`.
  - `BootCompletedReceiver` (`exported="true"` required by Android OS) enforces an explicit action allowlist (`ACTION_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`, `ACTION_SHUTDOWN`) and ignores untrusted `Intent` extras.
- **Lock-Screen Privacy:** Ongoing focus notifications use `NotificationCompat.VISIBILITY_PRIVATE` with a sanitized `publicVersion` so user reflection notes or sensitive titles are never exposed on locked screens.

## 2. Public Repository & Secrets Discipline

- Never commit `local.properties`, `.env*`, keystores (`*.jks`, `*.keystore`), private keys, signing passwords, or Supabase `service_role` keys.
- Future cloud milestones (M4+) may only embed public client identifiers (such as a Supabase `anon`/publishable key protected by PostgreSQL Row-Level Security), configured via local environment/Gradle properties rather than hardcoded literals.
- Automated enforcement runs via:
  - `.gitleaks.toml` + `gitleaks` CI scanning (`.github/workflows/security.yml`)
  - Android Lint strict rules (`config/lint/lint.xml`)
  - CashApp Licensee dependency license verification (`licenseeDebug`)
  - `./scripts/verify-security.ps1` merged manifest & secret check

## 3. Credential Revocation & Incident Response Playbook

If a credential, token, or signing key is ever suspected of leaking into a commit or log:
1. **Revoke & Rotate Immediately:** Invalidate the exposed key/token at the upstream provider (Supabase, GitHub, signing authority) **before** cleaning Git history. Removing text from Git alone is never sufficient for a public repository.
2. **Audit Exposure:** Inspect provider audit logs for unauthorized access or RLS bypass attempts.
3. **Purge & Force-Rotate History:** Scrub sensitive artifacts with `git filter-repo` if necessary and document the rotation.
4. **Prevent Recurrence:** Add a targeted detection rule in `.gitleaks.toml` and verify in CI.

## 4. Reporting a Vulnerability

Please do not open public GitHub Issues with exploit details for unpatched security vulnerabilities. Contact the maintainer privately via GitHub Security Advisories (`https://github.com/mPekmen/pekmen-one/security/advisories`) or via the contact channel listed on `https://pekmen.net`.
