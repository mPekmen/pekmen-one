# Pekmen ONE V2 staging

Use a dedicated test Supabase project, synthetic accounts and an Android test device. The app uses the Supabase Auth HTTPS API. Google sign-in uses browser OAuth with PKCE and a verified HTTPS Android App Link. The app must be configured to receive the link used by email confirmation and password recovery.

## Build inputs

Set the following properties in your user Gradle configuration or inject them through staging CI. Use only a public Supabase client key.

```properties
pekmen.supabaseUrl=https://<staging-project>.supabase.co
pekmen.supabasePublishableKey=sb_publishable_<staging-value>
pekmen.authRedirectHost=<staging-link-host>
```

The public values are embedded in the APK. Keep server keys and the Google OAuth client secret in their respective service configurations. A build without a Supabase URL and client key displays the configuration state at the account gate. Run `./gradlew :app:assembleDebug` from a full JDK 21 with Android SDK API 35. On Windows use `gradlew.bat`. If another Windows process holds a build output open, wait for the other build to finish and retry with `--no-parallel --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"`.

## Staging verification

1. Enable email confirmation and Google in the staging Auth configuration. Register the staging Supabase Auth callback with Google. Add the exact link `https://<staging-link-host>/auth/pekmen-one` to the Supabase redirect allowlist.
2. Serve Android Digital Asset Links at `https://<staging-link-host>/.well-known/assetlinks.json` for the installed APK application ID and signing certificate. The standard debug variant uses `net.pekmen.one.debug`. The release variant uses `net.pekmen.one`.
3. Check that Android reports the link as verified. Open a clean install and complete signup and email confirmation, password sign-in and recovery, then Google sign-in and cancellation.
4. Create different learning and planner records for test accounts A and B. Check failed authentication, interrupted setup, app restart, offline continuation, account switch and an A notification delivered after B signs in.
5. Check the app with an existing unauthenticated install before accepting the legacy import prompt. Compare imported records, decline the prompt in a separate run and confirm the original local database remains available.

Finishing the first setup needs a connection to save the profile to the verified Supabase account. The development build uses a seven-day offline window from the last verified session. Expiration and account switches lock access to the prior account's records. This duration is a provisional product decision. The developer build does not provision cloud sync or account deletion.
