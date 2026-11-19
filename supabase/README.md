# Pekmen One — Supabase Backend & Strict RLS Setup

Pekmen One ships **offline-first by default** (`pekmen.cloudEnabled=false`, zero `android.permission.INTERNET` in the offline APK). Optional cloud sync (M4–M5) and Private Circles (M6) stay **fail-closed** behind `CloudRlsGateProvider` until all three gate conditions hold:

1. `compiledWithCloudFlag == true` (cloud flavor/property enabled in Gradle)
2. `backendEndpointConfigured == true` (`pekmen_supabase_url` + publishable `pekmen_anon_key` supplied locally; never commit keys or `.env` files)
3. `rlsVerified == true` (verified live migration & cross-tenant RLS test suite pass)

## Migration Overview

`supabase/migrations/20261119000001_m4_m5_m6_schema_and_rls.sql` provisions:

- **Personal M4/M5 tables**: `focus_profiles`, `learning_subjects`, `learning_books`, `learning_topics`, `focus_sessions`, `focus_session_segments`, `planner_tasks`
- **Idempotency ledger**: `sync_mutation_ledger` + `public.claim_sync_mutation(...)` deduplicating retries on `mutation_id`
- **Conflict comparator**: `public.should_incoming_mutation_win(...)` enforcing tombstone-first, then `client_updated_at_ms`, then `version`
- **Private Circles (M6)**: `social_circles`, `social_circle_memberships`, `social_member_summaries` + `public.join_circle_by_invite_code(...)`

## Security & RLS Invariants

- Every table executes `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY`.
- Zero permissive policies exist; every row access requires `auth.uid() IS NOT NULL`.
- `BEFORE INSERT OR UPDATE` triggers overwrite `owner_user_id := auth.uid()` and reject any attempt to mutate `owner_user_id` or resurrect a soft-delete tombstone (`deleted_at_ms`).
- `social_member_summaries` has **zero columns** for raw sessions, reflection notes, ratings, books, topics, or planner tasks, and its `SELECT` policy allows reads **only** by active circle members when the target member has `share_enabled = TRUE` and `left_at_ms IS NULL`.
- Membership triggers automatically purge a user's `social_member_summaries` whenever `share_enabled` flips to `FALSE` or `left_at_ms` is set.

## Applying & Verifying Locally

```bash
supabase start
supabase db push
```

Verify RLS with two separate authenticated test JWTs (`User A` and `User B`):
1. Confirm `User B` cannot `SELECT`, `INSERT`, `UPDATE`, or `DELETE` any row owned by `User A`.
2. Confirm `claim_sync_mutation` returns `is_new_mutation = TRUE` once and `FALSE` on duplicate `mutation_id`.
3. Confirm non-members and opted-out members (`share_enabled = FALSE`) are invisible in `social_member_summaries`.
4. Never embed any Supabase service-role secret in any Android build or mobile code; mobile clients use only the public `anon` key paired with user-scoped JWTs.
