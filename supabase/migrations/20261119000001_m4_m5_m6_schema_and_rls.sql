-- Pekmen One M4 + M5 + M6 PostgreSQL Schema, Strict RLS & Idempotency Migration
-- Zero permissive policies: every table enables and forces Row-Level Security.

BEGIN;

-- ============================================================================
-- 1. Shared Trigger Functions (Owner Assignment, Immutability, Server Clock)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.pekmen_current_epoch_ms()
RETURNS bigint
LANGUAGE sql
STABLE
AS $$
    SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint;
$$;

CREATE OR REPLACE FUNCTION public.pekmen_enforce_owner_and_server_ts()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_now_ms bigint := public.pekmen_current_epoch_ms();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required: auth.uid() is null'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.owner_user_id := v_uid;
        NEW.server_updated_at_ms := v_now_ms;
        RETURN NEW;
    END IF;

    IF NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id THEN
        RAISE EXCEPTION 'owner_user_id is immutable'
            USING ERRCODE = '42501';
    END IF;

    IF OLD.deleted_at_ms IS NOT NULL AND NEW.deleted_at_ms IS NULL THEN
        RAISE EXCEPTION 'Delete tombstones cannot be resurrected'
            USING ERRCODE = '22000';
    END IF;

    NEW.server_updated_at_ms := GREATEST(v_now_ms, COALESCE(OLD.server_updated_at_ms, 0) + 1);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.pekmen_enforce_ledger_owner()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required: auth.uid() is null'
            USING ERRCODE = '42501';
    END IF;

    NEW.owner_user_id := v_uid;
    IF NEW.server_updated_at_ms IS NULL OR NEW.server_updated_at_ms <= 0 THEN
        NEW.server_updated_at_ms := public.pekmen_current_epoch_ms();
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.pekmen_enforce_segment_owner()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required: auth.uid() is null'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.owner_user_id := v_uid;
        RETURN NEW;
    END IF;

    IF NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id THEN
        RAISE EXCEPTION 'owner_user_id is immutable'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

-- ============================================================================
-- 2. M4/M5 Personal Sync Tables (Zero cross-tenant visibility)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.focus_profiles (
    id uuid NOT NULL,
    built_in_slug text NOT NULL,
    custom_name text,
    color_hex bigint NOT NULL,
    icon_key text NOT NULL,
    default_mode text NOT NULL,
    work_minutes integer NOT NULL CHECK (work_minutes > 0 AND work_minutes <= 180),
    short_break_minutes integer NOT NULL CHECK (short_break_minutes > 0 AND short_break_minutes <= 60),
    long_break_minutes integer NOT NULL CHECK (long_break_minutes > 0 AND long_break_minutes <= 90),
    cycles_before_long_break integer NOT NULL CHECK (cycles_before_long_break > 0 AND cycles_before_long_break <= 10),
    dnd_enabled boolean NOT NULL DEFAULT FALSE,
    sort_order integer NOT NULL DEFAULT 0,
    is_archived boolean NOT NULL DEFAULT FALSE,
    owner_user_id uuid NOT NULL,
    created_at_ms bigint NOT NULL,
    client_updated_at_ms bigint NOT NULL,
    server_updated_at_ms bigint NOT NULL,
    deleted_at_ms bigint,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    -- Composite PK prevents built-in profile UUID collisions across tenants.
    PRIMARY KEY (owner_user_id, id)
);

CREATE INDEX IF NOT EXISTS idx_focus_profiles_owner_cursor
    ON public.focus_profiles (owner_user_id, server_updated_at_ms, id);

CREATE TABLE IF NOT EXISTS public.learning_subjects (
    id uuid PRIMARY KEY,
    title text NOT NULL,
    color_hex bigint NOT NULL,
    weekly_goal_minutes integer NOT NULL CHECK (weekly_goal_minutes >= 0),
    sort_order integer NOT NULL DEFAULT 0,
    is_archived boolean NOT NULL DEFAULT FALSE,
    owner_user_id uuid NOT NULL,
    created_at_ms bigint NOT NULL,
    client_updated_at_ms bigint NOT NULL,
    server_updated_at_ms bigint NOT NULL,
    deleted_at_ms bigint,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (owner_user_id, id)
);

CREATE INDEX IF NOT EXISTS idx_learning_subjects_owner_cursor
    ON public.learning_subjects (owner_user_id, server_updated_at_ms, id);

CREATE TABLE IF NOT EXISTS public.learning_books (
    id uuid PRIMARY KEY,
    subject_id uuid NOT NULL,
    title text NOT NULL,
    author text,
    total_pages integer NOT NULL CHECK (total_pages >= 0),
    current_page integer NOT NULL DEFAULT 0 CHECK (current_page >= 0),
    total_chapters integer NOT NULL DEFAULT 0 CHECK (total_chapters >= 0),
    completed_chapters integer NOT NULL DEFAULT 0 CHECK (completed_chapters >= 0),
    is_completed boolean NOT NULL DEFAULT FALSE,
    owner_user_id uuid NOT NULL,
    created_at_ms bigint NOT NULL,
    client_updated_at_ms bigint NOT NULL,
    server_updated_at_ms bigint NOT NULL,
    deleted_at_ms bigint,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (owner_user_id, id),
    FOREIGN KEY (owner_user_id, subject_id)
        REFERENCES public.learning_subjects (owner_user_id, id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_learning_books_owner_cursor
    ON public.learning_books (owner_user_id, server_updated_at_ms, id);

CREATE TABLE IF NOT EXISTS public.learning_topics (
    id uuid PRIMARY KEY,
    subject_id uuid NOT NULL,
    book_id uuid,
    title text NOT NULL,
    status text NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'MASTERED')),
    sort_order integer NOT NULL DEFAULT 0,
    owner_user_id uuid NOT NULL,
    created_at_ms bigint NOT NULL,
    client_updated_at_ms bigint NOT NULL,
    server_updated_at_ms bigint NOT NULL,
    deleted_at_ms bigint,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (owner_user_id, id),
    FOREIGN KEY (owner_user_id, subject_id)
        REFERENCES public.learning_subjects (owner_user_id, id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_learning_topics_owner_cursor
    ON public.learning_topics (owner_user_id, server_updated_at_ms, id);

CREATE TABLE IF NOT EXISTS public.focus_sessions (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL,
    subject_id uuid,
    book_id uuid,
    topic_id uuid,
    task_id uuid,
    book_start_page integer,
    book_end_page integer,
    session_mode text NOT NULL CHECK (session_mode IN ('POMODORO', 'STOPWATCH', 'CUSTOM')),
    status text NOT NULL CHECK (status IN ('RUNNING', 'PAUSED', 'AWAITING_NEXT_PHASE', 'COMPLETED', 'ABANDONED')),
    current_phase text NOT NULL CHECK (current_phase IN ('FOCUS', 'SHORT_BREAK', 'LONG_BREAK')),
    awaiting_next_phase text CHECK (awaiting_next_phase IS NULL OR awaiting_next_phase IN ('FOCUS', 'SHORT_BREAK', 'LONG_BREAK')),
    completed_focus_cycles integer NOT NULL DEFAULT 0 CHECK (completed_focus_cycles >= 0),
    started_at_wall_ms bigint NOT NULL,
    ended_at_wall_ms bigint,
    effective_day_date date NOT NULL,
    planned_focus_sec bigint,
    actual_focus_sec bigint NOT NULL CHECK (actual_focus_sec >= 0),
    actual_break_sec bigint NOT NULL CHECK (actual_break_sec >= 0),
    pause_count integer NOT NULL DEFAULT 0 CHECK (pause_count >= 0),
    is_manual_entry boolean NOT NULL DEFAULT FALSE,
    was_manually_adjusted boolean NOT NULL DEFAULT FALSE,
    interrupted_by_reboot boolean NOT NULL DEFAULT FALSE,
    focus_rating integer CHECK (focus_rating IS NULL OR focus_rating BETWEEN 1 AND 5),
    reflection_note text,
    owner_user_id uuid NOT NULL,
    created_at_ms bigint NOT NULL,
    client_updated_at_ms bigint NOT NULL,
    server_updated_at_ms bigint NOT NULL,
    deleted_at_ms bigint,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (owner_user_id, id)
);

CREATE INDEX IF NOT EXISTS idx_focus_sessions_owner_cursor
    ON public.focus_sessions (owner_user_id, server_updated_at_ms, id);

CREATE TABLE IF NOT EXISTS public.focus_session_segments (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    segment_index integer NOT NULL CHECK (segment_index >= 0),
    phase text NOT NULL CHECK (phase IN ('FOCUS', 'SHORT_BREAK', 'LONG_BREAK')),
    started_at_wall_ms bigint NOT NULL,
    started_at_elapsed_ms bigint NOT NULL,
    boot_count integer NOT NULL,
    last_checkpointed_duration_ms bigint NOT NULL DEFAULT 0 CHECK (last_checkpointed_duration_ms >= 0),
    planned_duration_ms bigint,
    ended_at_wall_ms bigint,
    duration_ms bigint,
    UNIQUE (session_id, segment_index),
    FOREIGN KEY (owner_user_id, session_id)
        REFERENCES public.focus_sessions (owner_user_id, id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_focus_session_segments_owner_session
    ON public.focus_session_segments (owner_user_id, session_id);

CREATE TABLE IF NOT EXISTS public.planner_tasks (
    id uuid PRIMARY KEY,
    title text NOT NULL,
    notes text,
    subject_id uuid,
    due_at_wall_ms bigint,
    reminder_at_wall_ms bigint,
    recurrence_rule text NOT NULL DEFAULT 'NONE' CHECK (recurrence_rule IN ('NONE', 'DAILY', 'WEEKLY')),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    completed_at_wall_ms bigint,
    priority integer NOT NULL DEFAULT 1,
    owner_user_id uuid NOT NULL,
    created_at_ms bigint NOT NULL,
    client_updated_at_ms bigint NOT NULL,
    server_updated_at_ms bigint NOT NULL,
    deleted_at_ms bigint,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (owner_user_id, id)
);

CREATE INDEX IF NOT EXISTS idx_planner_tasks_owner_cursor
    ON public.planner_tasks (owner_user_id, server_updated_at_ms, id);

CREATE TABLE IF NOT EXISTS public.sync_mutation_ledger (
    owner_user_id uuid NOT NULL,
    mutation_id uuid NOT NULL,
    entity_table text NOT NULL CHECK (
        entity_table IN (
            'focus_profiles',
            'learning_subjects',
            'learning_books',
            'learning_topics',
            'focus_sessions',
            'planner_tasks'
        )
    ),
    entity_id uuid NOT NULL,
    op_type text NOT NULL CHECK (op_type IN ('UPSERT', 'DELETE')),
    client_updated_at_ms bigint NOT NULL,
    server_updated_at_ms bigint NOT NULL,
    base_version bigint NOT NULL,
    applied boolean NOT NULL DEFAULT TRUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_user_id, mutation_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_sync_mutation_ledger_mutation_id
    ON public.sync_mutation_ledger (mutation_id);

-- ============================================================================
-- 3. Idempotent Mutation Deduplication & Conflict Resolution Helpers
-- ============================================================================

CREATE OR REPLACE FUNCTION public.claim_sync_mutation(
    p_mutation_id uuid,
    p_entity_table text,
    p_entity_id uuid,
    p_op_type text,
    p_client_updated_at_ms bigint,
    p_base_version bigint
)
RETURNS TABLE (
    is_new_mutation boolean,
    recorded_server_updated_at_ms bigint
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_now_ms bigint := public.pekmen_current_epoch_ms();
    v_inserted_ts bigint;
    v_existing_ts bigint;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required: auth.uid() is null'
            USING ERRCODE = '42501';
    END IF;

    INSERT INTO public.sync_mutation_ledger (
        owner_user_id,
        mutation_id,
        entity_table,
        entity_id,
        op_type,
        client_updated_at_ms,
        server_updated_at_ms,
        base_version,
        applied
    )
    VALUES (
        v_uid,
        p_mutation_id,
        p_entity_table,
        p_entity_id,
        p_op_type,
        p_client_updated_at_ms,
        v_now_ms,
        p_base_version,
        TRUE
    )
    ON CONFLICT (owner_user_id, mutation_id) DO NOTHING
    RETURNING server_updated_at_ms INTO v_inserted_ts;

    IF v_inserted_ts IS NOT NULL THEN
        RETURN QUERY SELECT TRUE, v_inserted_ts;
        RETURN;
    END IF;

    SELECT l.server_updated_at_ms
    INTO v_existing_ts
    FROM public.sync_mutation_ledger l
    WHERE l.owner_user_id = v_uid
      AND l.mutation_id = p_mutation_id
    LIMIT 1;

    IF v_existing_ts IS NULL THEN
        RAISE EXCEPTION 'Mutation ID collision across tenants'
            USING ERRCODE = '42501';
    END IF;

    RETURN QUERY SELECT FALSE, v_existing_ts;
END;
$$;

CREATE OR REPLACE FUNCTION public.should_incoming_mutation_win(
    p_existing_deleted_at_ms bigint,
    p_existing_client_updated_at_ms bigint,
    p_existing_version bigint,
    p_incoming_deleted_at_ms bigint,
    p_incoming_client_updated_at_ms bigint,
    p_incoming_version bigint
)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_existing_deleted_at_ms IS NOT NULL AND p_incoming_deleted_at_ms IS NULL THEN FALSE
        WHEN p_incoming_deleted_at_ms IS NOT NULL AND p_existing_deleted_at_ms IS NULL THEN TRUE
        WHEN p_incoming_client_updated_at_ms > p_existing_client_updated_at_ms THEN TRUE
        WHEN p_incoming_client_updated_at_ms < p_existing_client_updated_at_ms THEN FALSE
        ELSE p_incoming_version > p_existing_version
    END;
$$;

-- ============================================================================
-- 4. M6 Private Social Circles & Privacy-Bounded Aggregate Summaries
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.social_circles (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (char_length(trim(name)) BETWEEN 1 AND 80),
    invite_code text NOT NULL UNIQUE CHECK (char_length(invite_code) >= 8),
    created_by_user_id uuid NOT NULL,
    created_at_ms bigint NOT NULL,
    deleted_at_ms bigint
);

CREATE TABLE IF NOT EXISTS public.social_circle_memberships (
    id uuid PRIMARY KEY,
    circle_id uuid NOT NULL REFERENCES public.social_circles (id) ON DELETE CASCADE,
    user_id uuid NOT NULL,
    display_name text NOT NULL CHECK (char_length(trim(display_name)) BETWEEN 1 AND 60),
    role text NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    share_enabled boolean NOT NULL DEFAULT FALSE,
    share_live_focus_status boolean NOT NULL DEFAULT FALSE,
    allowed_subject_titles text[] NOT NULL DEFAULT '{}',
    joined_at_ms bigint NOT NULL,
    left_at_ms bigint,
    UNIQUE (circle_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_social_circle_memberships_circle_active
    ON public.social_circle_memberships (circle_id, user_id)
    WHERE left_at_ms IS NULL;

-- Structural boundary: contains ONLY aggregate focus durations and whitelisted subject totals.
-- Raw sessions, reflection notes, ratings, books, topics, and planner tasks NEVER exist here.
CREATE TABLE IF NOT EXISTS public.social_member_summaries (
    id uuid PRIMARY KEY,
    circle_id uuid NOT NULL REFERENCES public.social_circles (id) ON DELETE CASCADE,
    user_id uuid NOT NULL,
    display_name text NOT NULL,
    period_type text NOT NULL CHECK (period_type IN ('DAILY', 'WEEKLY')),
    period_start_date date NOT NULL,
    live_timer_focus_sec bigint NOT NULL DEFAULT 0 CHECK (live_timer_focus_sec >= 0),
    manual_entry_focus_sec bigint NOT NULL DEFAULT 0 CHECK (manual_entry_focus_sec >= 0),
    subject_totals_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_currently_focusing boolean NOT NULL DEFAULT FALSE,
    updated_at_ms bigint NOT NULL,
    UNIQUE (circle_id, user_id, period_type, period_start_date)
);

CREATE INDEX IF NOT EXISTS idx_social_member_summaries_circle_period
    ON public.social_member_summaries (circle_id, period_type, period_start_date);

CREATE OR REPLACE FUNCTION public.is_active_circle_member(p_circle_id uuid, p_user_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.social_circle_memberships m
        JOIN public.social_circles c ON c.id = m.circle_id
        WHERE m.circle_id = p_circle_id
          AND m.user_id = p_user_id
          AND m.left_at_ms IS NULL
          AND c.deleted_at_ms IS NULL
    );
$$;

REVOKE ALL ON FUNCTION public.is_active_circle_member(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_active_circle_member(uuid, uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.pekmen_enforce_circle_creator()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required: auth.uid() is null'
            USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' THEN
        NEW.created_by_user_id := v_uid;
        RETURN NEW;
    END IF;
    IF NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id THEN
        RAISE EXCEPTION 'created_by_user_id is immutable'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.pekmen_enforce_membership_and_purge()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF v_uid IS NULL THEN
            RAISE EXCEPTION 'Authentication required: auth.uid() is null'
                USING ERRCODE = '42501';
        END IF;
        NEW.user_id := v_uid;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.circle_id IS DISTINCT FROM OLD.circle_id THEN
            RAISE EXCEPTION 'Membership identity is immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.role IS DISTINCT FROM OLD.role THEN
            RAISE EXCEPTION 'Membership role is immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.left_at_ms IS NOT NULL OR NEW.share_enabled = FALSE THEN
            DELETE FROM public.social_member_summaries s
            WHERE s.circle_id = NEW.circle_id
              AND s.user_id = NEW.user_id;
        END IF;
        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.pekmen_enforce_summary_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required: auth.uid() is null'
            USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' THEN
        NEW.user_id := v_uid;
        RETURN NEW;
    END IF;
    IF NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.circle_id IS DISTINCT FROM OLD.circle_id THEN
        RAISE EXCEPTION 'Summary identity is immutable'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.join_circle_by_invite_code(
    p_invite_code text,
    p_display_name text,
    p_membership_id uuid
)
RETURNS public.social_circles
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_circle public.social_circles;
    v_now_ms bigint := public.pekmen_current_epoch_ms();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '42501';
    END IF;

    SELECT *
    INTO v_circle
    FROM public.social_circles c
    WHERE c.invite_code = trim(p_invite_code)
      AND c.deleted_at_ms IS NULL
    LIMIT 1;

    IF v_circle.id IS NULL THEN
        RAISE EXCEPTION 'Circle not found or invite invalid'
            USING ERRCODE = 'P0002';
    END IF;

    INSERT INTO public.social_circle_memberships (
        id,
        circle_id,
        user_id,
        display_name,
        role,
        share_enabled,
        share_live_focus_status,
        allowed_subject_titles,
        joined_at_ms,
        left_at_ms
    )
    VALUES (
        p_membership_id,
        v_circle.id,
        v_uid,
        trim(p_display_name),
        'MEMBER',
        FALSE,
        FALSE,
        '{}',
        v_now_ms,
        NULL
    )
    ON CONFLICT (circle_id, user_id) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        share_enabled = FALSE,
        share_live_focus_status = FALSE,
        allowed_subject_titles = '{}',
        joined_at_ms = v_now_ms,
        left_at_ms = NULL;

    RETURN v_circle;
END;
$$;

REVOKE ALL ON FUNCTION public.join_circle_by_invite_code(text, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.join_circle_by_invite_code(text, text, uuid) TO authenticated;

-- ============================================================================
-- 5. Attach Triggers to All Tables
-- ============================================================================

DROP TRIGGER IF EXISTS trg_focus_profiles_owner ON public.focus_profiles;
CREATE TRIGGER trg_focus_profiles_owner
    BEFORE INSERT OR UPDATE ON public.focus_profiles
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_owner_and_server_ts();

DROP TRIGGER IF EXISTS trg_learning_subjects_owner ON public.learning_subjects;
CREATE TRIGGER trg_learning_subjects_owner
    BEFORE INSERT OR UPDATE ON public.learning_subjects
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_owner_and_server_ts();

DROP TRIGGER IF EXISTS trg_learning_books_owner ON public.learning_books;
CREATE TRIGGER trg_learning_books_owner
    BEFORE INSERT OR UPDATE ON public.learning_books
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_owner_and_server_ts();

DROP TRIGGER IF EXISTS trg_learning_topics_owner ON public.learning_topics;
CREATE TRIGGER trg_learning_topics_owner
    BEFORE INSERT OR UPDATE ON public.learning_topics
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_owner_and_server_ts();

DROP TRIGGER IF EXISTS trg_focus_sessions_owner ON public.focus_sessions;
CREATE TRIGGER trg_focus_sessions_owner
    BEFORE INSERT OR UPDATE ON public.focus_sessions
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_owner_and_server_ts();

DROP TRIGGER IF EXISTS trg_focus_session_segments_owner ON public.focus_session_segments;
CREATE TRIGGER trg_focus_session_segments_owner
    BEFORE INSERT OR UPDATE ON public.focus_session_segments
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_segment_owner();

DROP TRIGGER IF EXISTS trg_planner_tasks_owner ON public.planner_tasks;
CREATE TRIGGER trg_planner_tasks_owner
    BEFORE INSERT OR UPDATE ON public.planner_tasks
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_owner_and_server_ts();

DROP TRIGGER IF EXISTS trg_sync_mutation_ledger_owner ON public.sync_mutation_ledger;
CREATE TRIGGER trg_sync_mutation_ledger_owner
    BEFORE INSERT ON public.sync_mutation_ledger
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_ledger_owner();

DROP TRIGGER IF EXISTS trg_social_circles_creator ON public.social_circles;
CREATE TRIGGER trg_social_circles_creator
    BEFORE INSERT OR UPDATE ON public.social_circles
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_circle_creator();

DROP TRIGGER IF EXISTS trg_social_circle_memberships_enforce ON public.social_circle_memberships;
CREATE TRIGGER trg_social_circle_memberships_enforce
    BEFORE INSERT OR UPDATE ON public.social_circle_memberships
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_membership_and_purge();

DROP TRIGGER IF EXISTS trg_social_member_summaries_user ON public.social_member_summaries;
CREATE TRIGGER trg_social_member_summaries_user
    BEFORE INSERT OR UPDATE ON public.social_member_summaries
    FOR EACH ROW EXECUTE FUNCTION public.pekmen_enforce_summary_user();

-- ============================================================================
-- 6. Enable & Force Strict Row-Level Security on ALL 11 Tables
-- ============================================================================

ALTER TABLE public.focus_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.focus_profiles FORCE ROW LEVEL SECURITY;

ALTER TABLE public.learning_subjects ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.learning_subjects FORCE ROW LEVEL SECURITY;

ALTER TABLE public.learning_books ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.learning_books FORCE ROW LEVEL SECURITY;

ALTER TABLE public.learning_topics ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.learning_topics FORCE ROW LEVEL SECURITY;

ALTER TABLE public.focus_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.focus_sessions FORCE ROW LEVEL SECURITY;

ALTER TABLE public.focus_session_segments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.focus_session_segments FORCE ROW LEVEL SECURITY;

ALTER TABLE public.planner_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.planner_tasks FORCE ROW LEVEL SECURITY;

ALTER TABLE public.sync_mutation_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_mutation_ledger FORCE ROW LEVEL SECURITY;

ALTER TABLE public.social_circles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.social_circles FORCE ROW LEVEL SECURITY;

ALTER TABLE public.social_circle_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.social_circle_memberships FORCE ROW LEVEL SECURITY;

ALTER TABLE public.social_member_summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.social_member_summaries FORCE ROW LEVEL SECURITY;

-- Personal tables: strictly owner-only (auth.uid() = owner_user_id)

CREATE POLICY focus_profiles_owner_select ON public.focus_profiles
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY focus_profiles_owner_insert ON public.focus_profiles
    FOR INSERT WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY focus_profiles_owner_update ON public.focus_profiles
    FOR UPDATE USING (auth.uid() = owner_user_id) WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY focus_profiles_owner_delete ON public.focus_profiles
    FOR DELETE USING (auth.uid() = owner_user_id);

CREATE POLICY learning_subjects_owner_select ON public.learning_subjects
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY learning_subjects_owner_insert ON public.learning_subjects
    FOR INSERT WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY learning_subjects_owner_update ON public.learning_subjects
    FOR UPDATE USING (auth.uid() = owner_user_id) WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY learning_subjects_owner_delete ON public.learning_subjects
    FOR DELETE USING (auth.uid() = owner_user_id);

CREATE POLICY learning_books_owner_select ON public.learning_books
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY learning_books_owner_insert ON public.learning_books
    FOR INSERT WITH CHECK (
        auth.uid() = owner_user_id
        AND EXISTS (
            SELECT 1 FROM public.learning_subjects s
            WHERE s.id = learning_books.subject_id AND s.owner_user_id = auth.uid()
        )
    );
CREATE POLICY learning_books_owner_update ON public.learning_books
    FOR UPDATE USING (auth.uid() = owner_user_id) WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY learning_books_owner_delete ON public.learning_books
    FOR DELETE USING (auth.uid() = owner_user_id);

CREATE POLICY learning_topics_owner_select ON public.learning_topics
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY learning_topics_owner_insert ON public.learning_topics
    FOR INSERT WITH CHECK (
        auth.uid() = owner_user_id
        AND EXISTS (
            SELECT 1 FROM public.learning_subjects s
            WHERE s.id = learning_topics.subject_id AND s.owner_user_id = auth.uid()
        )
    );
CREATE POLICY learning_topics_owner_update ON public.learning_topics
    FOR UPDATE USING (auth.uid() = owner_user_id) WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY learning_topics_owner_delete ON public.learning_topics
    FOR DELETE USING (auth.uid() = owner_user_id);

CREATE POLICY focus_sessions_owner_select ON public.focus_sessions
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY focus_sessions_owner_insert ON public.focus_sessions
    FOR INSERT WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY focus_sessions_owner_update ON public.focus_sessions
    FOR UPDATE USING (auth.uid() = owner_user_id) WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY focus_sessions_owner_delete ON public.focus_sessions
    FOR DELETE USING (auth.uid() = owner_user_id);

CREATE POLICY focus_session_segments_owner_select ON public.focus_session_segments
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY focus_session_segments_owner_insert ON public.focus_session_segments
    FOR INSERT WITH CHECK (
        auth.uid() = owner_user_id
        AND EXISTS (
            SELECT 1 FROM public.focus_sessions fs
            WHERE fs.id = focus_session_segments.session_id AND fs.owner_user_id = auth.uid()
        )
    );
CREATE POLICY focus_session_segments_owner_update ON public.focus_session_segments
    FOR UPDATE USING (auth.uid() = owner_user_id) WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY focus_session_segments_owner_delete ON public.focus_session_segments
    FOR DELETE USING (auth.uid() = owner_user_id);

CREATE POLICY planner_tasks_owner_select ON public.planner_tasks
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY planner_tasks_owner_insert ON public.planner_tasks
    FOR INSERT WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY planner_tasks_owner_update ON public.planner_tasks
    FOR UPDATE USING (auth.uid() = owner_user_id) WITH CHECK (auth.uid() = owner_user_id);
CREATE POLICY planner_tasks_owner_delete ON public.planner_tasks
    FOR DELETE USING (auth.uid() = owner_user_id);

CREATE POLICY sync_mutation_ledger_owner_select ON public.sync_mutation_ledger
    FOR SELECT USING (auth.uid() = owner_user_id);
CREATE POLICY sync_mutation_ledger_owner_insert ON public.sync_mutation_ledger
    FOR INSERT WITH CHECK (auth.uid() = owner_user_id);

-- Social circles: visible only to creator or active circle members.

CREATE POLICY social_circles_member_select ON public.social_circles
    FOR SELECT USING (
        deleted_at_ms IS NULL
        AND (
            auth.uid() = created_by_user_id
            OR public.is_active_circle_member(id, auth.uid())
        )
    );
CREATE POLICY social_circles_creator_insert ON public.social_circles
    FOR INSERT WITH CHECK (auth.uid() = created_by_user_id);
CREATE POLICY social_circles_creator_update ON public.social_circles
    FOR UPDATE USING (auth.uid() = created_by_user_id) WITH CHECK (auth.uid() = created_by_user_id);
CREATE POLICY social_circles_creator_delete ON public.social_circles
    FOR DELETE USING (auth.uid() = created_by_user_id);

-- Memberships: active members see active co-members; members manage only their own sharing & exit.

CREATE POLICY social_circle_memberships_select ON public.social_circle_memberships
    FOR SELECT USING (
        user_id = auth.uid()
        OR (left_at_ms IS NULL AND public.is_active_circle_member(circle_id, auth.uid()))
    );
CREATE POLICY social_circle_memberships_owner_insert ON public.social_circle_memberships
    FOR INSERT WITH CHECK (
        user_id = auth.uid()
        AND role = 'OWNER'
        AND EXISTS (
            SELECT 1 FROM public.social_circles c
            WHERE c.id = social_circle_memberships.circle_id
              AND c.created_by_user_id = auth.uid()
              AND c.deleted_at_ms IS NULL
        )
    );
CREATE POLICY social_circle_memberships_self_update ON public.social_circle_memberships
    FOR UPDATE USING (user_id = auth.uid() AND left_at_ms IS NULL)
    WITH CHECK (user_id = auth.uid());
CREATE POLICY social_circle_memberships_self_delete ON public.social_circle_memberships
    FOR DELETE USING (user_id = auth.uid());

-- Member summaries: ONLY visible to active circle members AND ONLY when target member has share_enabled = TRUE.
-- Never exposes raw focus_sessions, reflection_note, learning_books, or planner_tasks.

CREATE POLICY social_member_summaries_select ON public.social_member_summaries
    FOR SELECT USING (
        public.is_active_circle_member(circle_id, auth.uid())
        AND EXISTS (
            SELECT 1
            FROM public.social_circle_memberships target_m
            WHERE target_m.circle_id = social_member_summaries.circle_id
              AND target_m.user_id = social_member_summaries.user_id
              AND target_m.left_at_ms IS NULL
              AND target_m.share_enabled = TRUE
        )
    );
CREATE POLICY social_member_summaries_self_insert ON public.social_member_summaries
    FOR INSERT WITH CHECK (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1
            FROM public.social_circle_memberships self_m
            WHERE self_m.circle_id = social_member_summaries.circle_id
              AND self_m.user_id = auth.uid()
              AND self_m.left_at_ms IS NULL
              AND self_m.share_enabled = TRUE
        )
    );
CREATE POLICY social_member_summaries_self_update ON public.social_member_summaries
    FOR UPDATE USING (user_id = auth.uid())
    WITH CHECK (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1
            FROM public.social_circle_memberships self_m
            WHERE self_m.circle_id = social_member_summaries.circle_id
              AND self_m.user_id = auth.uid()
              AND self_m.left_at_ms IS NULL
              AND self_m.share_enabled = TRUE
        )
    );
CREATE POLICY social_member_summaries_self_delete ON public.social_member_summaries
    FOR DELETE USING (user_id = auth.uid());

COMMIT;
