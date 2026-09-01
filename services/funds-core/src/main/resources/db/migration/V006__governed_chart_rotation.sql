-- V004 transferred the schema to the non-login migration owner. Every later
-- migration executes as that role so new routines inherit fail-closed ACLs.
SET ROLE funds_migrator;

-- Posting and direct-journal guards participate in the same lock protocol as
-- chart governance: chart row first, then the stable book row. A join with
-- FOR SHARE on both relations does not promise executor row-lock order.
CREATE OR REPLACE FUNCTION funds.lock_book_chart_for_posting(
    p_book_id uuid,
    p_chart_version_id uuid
)
RETURNS TABLE (
    legal_entity_id uuid,
    accounting_policy_version integer,
    timezone text,
    chart_status text,
    chart_activated_at timestamptz
)
LANGUAGE plpgsql
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
DECLARE
    locked_chart_status text;
    locked_chart_activated_at timestamptz;
    locked_legal_entity_id uuid;
    locked_policy_version integer;
    locked_timezone text;
BEGIN
    SELECT chart.status, chart.activated_at
    INTO locked_chart_status, locked_chart_activated_at
    FROM funds.chart_version chart
    WHERE chart.chart_version_id = p_chart_version_id
      AND chart.book_id = p_book_id
    FOR SHARE OF chart;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT book.legal_entity_id, book.accounting_policy_version, book.timezone
    INTO locked_legal_entity_id, locked_policy_version, locked_timezone
    FROM funds.book book
    WHERE book.book_id = p_book_id
    FOR SHARE OF book;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    RETURN QUERY SELECT locked_legal_entity_id, locked_policy_version,
                        locked_timezone, locked_chart_status,
                        locked_chart_activated_at;
END
$function$;

CREATE OR REPLACE FUNCTION funds.enforce_journal_governance()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
DECLARE
    governed_legal_entity uuid;
    governed_policy integer;
    governed_timezone text;
    governed_chart_status text;
    governed_chart_activated_at timestamptz;
    period_book_id uuid;
    period_from date;
    period_to date;
    period_status text;
    local_booking_date date;
BEGIN
    SELECT governance.legal_entity_id, governance.accounting_policy_version,
           governance.timezone, governance.chart_status,
           governance.chart_activated_at
    INTO governed_legal_entity, governed_policy, governed_timezone,
         governed_chart_status, governed_chart_activated_at
    FROM funds.lock_book_chart_for_posting(NEW.book_id, NEW.chart_version_id) governance;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'journal chart does not belong to its book'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_chart_governance';
    END IF;
    IF governed_legal_entity IS DISTINCT FROM NEW.legal_entity_id THEN
        RAISE EXCEPTION 'journal legal entity does not match its book'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_book_legal_entity_consistency';
    END IF;
    IF governed_policy IS DISTINCT FROM NEW.policy_version THEN
        RAISE EXCEPTION 'journal policy version is not current for its book'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_current_policy';
    END IF;
    IF governed_chart_status <> 'ACTIVE'
       OR governed_chart_activated_at > NEW.booking_time THEN
        RAISE EXCEPTION 'journal chart is not effective for booking time'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_effective_chart';
    END IF;

    SELECT period.book_id, period.business_date_from, period.business_date_to, period.status
    INTO period_book_id, period_from, period_to, period_status
    FROM funds.accounting_period period
    WHERE period.period_id = NEW.period_id
    FOR SHARE OF period;

    IF NOT FOUND OR period_book_id IS DISTINCT FROM NEW.book_id THEN
        RAISE EXCEPTION 'journal period does not belong to its book'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_period_book';
    END IF;
    IF period_status <> 'OPEN' THEN
        RAISE EXCEPTION 'journal period is not open'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_open_period';
    END IF;
    local_booking_date := (NEW.booking_time AT TIME ZONE governed_timezone)::date;
    IF local_booking_date < period_from OR local_booking_date > period_to THEN
        RAISE EXCEPTION 'book-local booking date is outside journal period'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_booking_date_period';
    END IF;
    IF NEW.value_date < period_from OR NEW.value_date > period_to THEN
        RAISE EXCEPTION 'value date is outside journal period'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_value_date_period';
    END IF;
    RETURN NEW;
END
$function$;

-- A mapping moved between charts locks every participating chart in one global
-- UUID order, followed by every participating book in UUID order. This matches
-- rotation even for malformed cross-book DML that will later fail a constraint.
CREATE OR REPLACE FUNCTION funds.reject_chart_mapping_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
DECLARE
    source_book_id uuid;
    source_chart_version_id uuid;
    target_book_id uuid;
    target_chart_version_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        source_book_id := OLD.book_id;
        source_chart_version_id := OLD.chart_version_id;
        target_book_id := OLD.book_id;
        target_chart_version_id := OLD.chart_version_id;
    ELSIF TG_OP = 'INSERT' THEN
        source_book_id := NEW.book_id;
        source_chart_version_id := NEW.chart_version_id;
        target_book_id := NEW.book_id;
        target_chart_version_id := NEW.chart_version_id;
    ELSE
        source_book_id := OLD.book_id;
        source_chart_version_id := OLD.chart_version_id;
        target_book_id := NEW.book_id;
        target_chart_version_id := NEW.chart_version_id;
    END IF;

    PERFORM 1
    FROM funds.chart_version chart
    WHERE (chart.chart_version_id = source_chart_version_id
           AND chart.book_id = source_book_id)
       OR (chart.chart_version_id = target_chart_version_id
           AND chart.book_id = target_book_id)
    ORDER BY chart.chart_version_id
    FOR UPDATE OF chart;

    IF EXISTS (
        SELECT 1
        FROM funds.chart_version chart
        WHERE ((chart.chart_version_id = source_chart_version_id
                AND chart.book_id = source_book_id)
               OR (chart.chart_version_id = target_chart_version_id
                   AND chart.book_id = target_book_id))
          AND chart.status <> 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'chart mappings are frozen once the chart leaves DRAFT'
            USING ERRCODE = '55000',
                  CONSTRAINT = 'ledger_account_chart_mapping_frozen';
    END IF;

    PERFORM 1
    FROM funds.book book
    WHERE book.book_id = source_book_id OR book.book_id = target_book_id
    ORDER BY book.book_id
    FOR UPDATE OF book;

    UPDATE funds.book book
    SET chart_governance_revision = book.chart_governance_revision + 1
    WHERE book.book_id = source_book_id OR book.book_id = target_book_id;

    UPDATE funds.chart_version chart
    SET governance_revision = chart.governance_revision + 1
    WHERE ((chart.chart_version_id = source_chart_version_id
            AND chart.book_id = source_book_id)
           OR (chart.chart_version_id = target_chart_version_id
               AND chart.book_id = target_book_id))
      AND chart.status = 'DRAFT';

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$function$;

-- Operational rotation is one statement. It locks both lifecycle rows in
-- canonical UUID order before the stable book row, revalidates all facts under
-- those locks, and changes both statuses atomically.
CREATE FUNCTION funds.rotate_chart_version(
    p_book_id uuid,
    p_current_chart_version_id uuid,
    p_candidate_chart_version_id uuid,
    p_effective_at timestamptz
)
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
DECLARE
    locked_chart_count integer;
    current_book_id uuid;
    current_version integer;
    current_status text;
    current_activated_at timestamptz;
    current_retired_at timestamptz;
    candidate_book_id uuid;
    candidate_version integer;
    candidate_status text;
    candidate_activated_at timestamptz;
    candidate_retired_at timestamptz;
BEGIN
    IF p_book_id IS NULL
       OR p_current_chart_version_id IS NULL
       OR p_candidate_chart_version_id IS NULL THEN
        RAISE EXCEPTION 'chart rotation identifiers are required'
            USING ERRCODE = '23502',
                  CONSTRAINT = 'chart_rotation_identifiers_required';
    END IF;
    IF p_current_chart_version_id = p_candidate_chart_version_id THEN
        RAISE EXCEPTION 'current and candidate chart versions must differ'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_rotation_distinct_versions';
    END IF;
    IF p_effective_at IS NULL THEN
        RAISE EXCEPTION 'chart rotation effective time is required'
            USING ERRCODE = '23502',
                  CONSTRAINT = 'chart_rotation_effective_time_required';
    END IF;

    PERFORM 1
    FROM funds.chart_version chart
    WHERE chart.chart_version_id IN (
        p_current_chart_version_id,
        p_candidate_chart_version_id)
    ORDER BY chart.chart_version_id
    FOR UPDATE OF chart;
    GET DIAGNOSTICS locked_chart_count = ROW_COUNT;

    IF locked_chart_count <> 2 THEN
        RAISE EXCEPTION 'both chart versions must exist'
            USING ERRCODE = '23503',
                  CONSTRAINT = 'chart_rotation_versions_exist';
    END IF;

    -- The book is the stable serialization row for mapping/account-universe
    -- changes. It is always locked after every participating chart row.
    PERFORM 1
    FROM funds.book book
    WHERE book.book_id = p_book_id
    FOR UPDATE OF book;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'chart rotation book does not exist'
            USING ERRCODE = '23503',
                  CONSTRAINT = 'chart_rotation_book_exists';
    END IF;

    SELECT chart.book_id, chart.version, chart.status,
           chart.activated_at, chart.retired_at
    INTO current_book_id, current_version, current_status,
         current_activated_at, current_retired_at
    FROM funds.chart_version chart
    WHERE chart.chart_version_id = p_current_chart_version_id;

    SELECT chart.book_id, chart.version, chart.status,
           chart.activated_at, chart.retired_at
    INTO candidate_book_id, candidate_version, candidate_status,
         candidate_activated_at, candidate_retired_at
    FROM funds.chart_version chart
    WHERE chart.chart_version_id = p_candidate_chart_version_id;

    IF current_book_id IS DISTINCT FROM p_book_id
       OR candidate_book_id IS DISTINCT FROM p_book_id THEN
        RAISE EXCEPTION 'both chart versions must belong to the governed book'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_rotation_book_consistency';
    END IF;
    IF current_status <> 'ACTIVE'
       OR current_activated_at IS NULL
       OR current_retired_at IS NOT NULL THEN
        RAISE EXCEPTION 'current chart version must be ACTIVE'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_rotation_current_active';
    END IF;
    IF candidate_status <> 'DRAFT'
       OR candidate_activated_at IS NOT NULL
       OR candidate_retired_at IS NOT NULL THEN
        RAISE EXCEPTION 'candidate chart version must be DRAFT'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_rotation_candidate_draft';
    END IF;
    IF candidate_version <= current_version THEN
        RAISE EXCEPTION 'candidate chart version must advance the book version'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_rotation_version_order';
    END IF;
    IF p_effective_at < current_activated_at
       OR p_effective_at > statement_timestamp() THEN
        RAISE EXCEPTION 'rotation effective time is outside the permitted bounds'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_rotation_effective_bounds';
    END IF;

    -- Chart validity is half-open [activated_at, retired_at). A retroactive
    -- boundary must not make an already accepted journal historically invalid.
    IF EXISTS (
        SELECT 1
        FROM funds.journal journal
        WHERE journal.book_id = p_book_id
          AND journal.chart_version_id = p_current_chart_version_id
          AND journal.booking_time >= p_effective_at
    ) THEN
        RAISE EXCEPTION 'rotation boundary overlaps an existing current-chart journal'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_rotation_historical_cutoff';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM funds.ledger_account account
        WHERE account.book_id = p_book_id
          AND account.status = 'OPEN'
          AND NOT EXISTS (
              SELECT 1
              FROM funds.ledger_account_chart_mapping mapping
              WHERE mapping.account_id = account.account_id
                AND mapping.book_id = account.book_id
                AND mapping.chart_version_id = p_candidate_chart_version_id
                AND mapping.account_currency = account.currency
          )
    ) THEN
        RAISE EXCEPTION 'chart activation requires one mapping for every open account'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_mapping_incomplete';
    END IF;

    UPDATE funds.chart_version
    SET status = 'RETIRED', retired_at = p_effective_at
    WHERE chart_version_id = p_current_chart_version_id;

    UPDATE funds.chart_version
    SET status = 'ACTIVE', activated_at = p_effective_at
    WHERE chart_version_id = p_candidate_chart_version_id;
END
$function$;

-- The runtime role cannot update chart lifecycle rows or execute the owner-only
-- operation. A controlled operator must assume funds_migrator; ordinary service
-- callers cannot split rotation into lifecycle DML.
REVOKE ALL PRIVILEGES ON FUNCTION funds.rotate_chart_version(uuid, uuid, uuid, timestamptz)
    FROM PUBLIC, funds_app, funds_proof_reader;

RESET ROLE;
