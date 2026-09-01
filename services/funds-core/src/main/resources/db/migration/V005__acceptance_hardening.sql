-- V004 transferred the schema to the non-login migration owner. Every later
-- migration must execute as that role so ownership and its default privileges
-- remain fail-closed even when Flyway connects through a bootstrap superuser.
SET ROLE funds_migrator;

-- Preserve the meaning of hashes across the V004 -> V005 canonical-format
-- boundary. Existing facts keep their bytes and are tagged with the exact
-- verifier that wrote them; new commands/facts use the current typed schemes.
ALTER TABLE funds.idempotency_command
    ADD COLUMN request_hash_scheme text NOT NULL DEFAULT 'V004_OPAQUE'
        CHECK (request_hash_scheme IN ('V004_OPAQUE', 'TYPED_V2'));
ALTER TABLE funds.journal
    ADD COLUMN canonical_hash_scheme text NOT NULL DEFAULT 'V004_V1'
        CHECK (canonical_hash_scheme IN ('V004_V1', 'V2'));

-- A completed idempotency result is a pointer to its own immutable journal,
-- never merely to any journal that happens to exist. Validate V004 facts before
-- adding the composite identity and keep result_json as a checked cache of that
-- journal rather than an independent source of truth.
DO $validate_completed_command_results$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM funds.idempotency_command command
        LEFT JOIN funds.journal journal
          ON journal.journal_id = command.journal_id
         AND journal.command_id = command.command_id
        WHERE command.state = 'COMPLETED'
          AND (journal.journal_id IS NULL
               OR command.result_json ->> 'journalId'
                    IS DISTINCT FROM journal.journal_id::text
               OR command.result_json ->> 'journalSequence'
                    IS DISTINCT FROM journal.journal_sequence::text
               OR command.result_json ->> 'canonicalHash'
                    IS DISTINCT FROM journal.canonical_hash::text)
    ) THEN
        RAISE EXCEPTION 'V005 cannot accept an incoherent completed command result';
    END IF;
END
$validate_completed_command_results$;

ALTER TABLE funds.journal
    ADD CONSTRAINT journal_command_link_identity_key
        UNIQUE (journal_id, command_id);
ALTER TABLE funds.idempotency_command
    ADD CONSTRAINT idempotency_journal_command_link_fkey
        FOREIGN KEY (journal_id, command_id)
        REFERENCES funds.journal(journal_id, command_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION funds.enforce_completed_command_result()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF NEW.state = 'COMPLETED' AND NOT EXISTS (
        SELECT 1
        FROM funds.journal journal
        WHERE journal.journal_id = NEW.journal_id
          AND journal.command_id = NEW.command_id
          AND NEW.result_json ->> 'journalId' = journal.journal_id::text
          AND NEW.result_json ->> 'journalSequence' = journal.journal_sequence::text
          AND NEW.result_json ->> 'canonicalHash' = journal.canonical_hash::text
    ) THEN
        RAISE EXCEPTION 'completed command result must exactly identify its own journal'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'completed_command_result_consistency';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER idempotency_completed_result_consistency
BEFORE INSERT OR UPDATE ON funds.idempotency_command
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_completed_command_result();

-- Product definitions are stable commercial families. Classification belongs
-- to the immutable terms version to which a customer account is bound.
DROP TRIGGER product_version_immutable ON funds.product_version;

ALTER TABLE funds.product_version
    ADD COLUMN product_kind text,
    ADD COLUMN finance_principle text;

UPDATE funds.product_version version
SET product_kind = definition.product_kind,
    finance_principle = definition.finance_principle
FROM funds.product_definition definition
WHERE definition.product_id = version.product_id;

ALTER TABLE funds.product_version
    ALTER COLUMN product_kind SET NOT NULL,
    ALTER COLUMN finance_principle SET NOT NULL,
    ADD CONSTRAINT product_version_kind_check
        CHECK (product_kind IN ('SAVINGS','CURRENT','FIXED_DEPOSIT','DOMICILIARY')),
    ADD CONSTRAINT product_version_finance_principle_check
        CHECK (finance_principle IN ('CONVENTIONAL','NON_INTEREST'));

ALTER TABLE funds.product_definition
    DROP COLUMN product_kind,
    DROP COLUMN finance_principle;

CREATE OR REPLACE FUNCTION funds.reject_product_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    RAISE EXCEPTION 'product versions are immutable; create a new version instead'
        USING ERRCODE = '55000',
              CONSTRAINT = 'product_version_immutable';
END
$function$;

CREATE TRIGGER product_version_immutable
BEFORE UPDATE OR DELETE ON funds.product_version
FOR EACH ROW
EXECUTE FUNCTION funds.reject_product_version_mutation();

CREATE FUNCTION funds.reject_product_definition_identity_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    RAISE EXCEPTION 'product definitions are stable; create a new family instead'
        USING ERRCODE = '55000',
              CONSTRAINT = 'product_definition_identity_immutable';
END
$function$;

CREATE TRIGGER product_definition_identity_immutable
BEFORE UPDATE OR DELETE ON funds.product_definition
FOR EACH ROW
EXECUTE FUNCTION funds.reject_product_definition_identity_mutation();

-- Govern chart activation and retain an approved, bounded validity history.
ALTER TABLE funds.chart_version
    ADD COLUMN approval_reference text,
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN governance_revision bigint NOT NULL DEFAULT 0;

UPDATE funds.chart_version
SET approval_reference = 'MIGRATED-V005-' || version::text;

ALTER TABLE funds.chart_version
    ALTER COLUMN approval_reference SET NOT NULL,
    ADD CONSTRAINT chart_governance_revision_check CHECK (governance_revision >= 0),
    ADD CONSTRAINT chart_activation_fields_check CHECK (
        (status = 'DRAFT' AND activated_at IS NULL AND retired_at IS NULL)
        OR (status = 'ACTIVE' AND activated_at IS NOT NULL AND retired_at IS NULL)
        OR (status = 'RETIRED' AND activated_at IS NOT NULL AND retired_at IS NOT NULL
            AND retired_at >= activated_at)
    );

CREATE UNIQUE INDEX one_active_chart_per_book_idx
    ON funds.chart_version (book_id)
    WHERE status = 'ACTIVE';

CREATE FUNCTION funds.enforce_chart_version_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'approved chart versions cannot be deleted'
            USING ERRCODE = '55000',
                  CONSTRAINT = 'chart_version_identity_immutable';
    END IF;

    IF NEW.chart_version_id IS DISTINCT FROM OLD.chart_version_id
       OR NEW.book_id IS DISTINCT FROM OLD.book_id
       OR NEW.version IS DISTINCT FROM OLD.version
       OR NEW.approval_reference IS DISTINCT FROM OLD.approval_reference
       OR (NEW.governance_revision IS DISTINCT FROM OLD.governance_revision
           AND NOT (OLD.status = 'DRAFT' AND NEW.status = 'DRAFT'
                    AND NEW.governance_revision = OLD.governance_revision + 1))
       OR (NEW.activated_at IS DISTINCT FROM OLD.activated_at
           AND NOT (OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE'
                    AND OLD.activated_at IS NULL AND NEW.activated_at IS NOT NULL))
       OR (NEW.retired_at IS DISTINCT FROM OLD.retired_at
           AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED'
                    AND OLD.retired_at IS NULL AND NEW.retired_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'approved chart identity and activation facts are immutable'
            USING ERRCODE = '55000',
                  CONSTRAINT = 'chart_version_identity_immutable';
    END IF;

    IF NOT (OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE'
            OR OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED'
            OR OLD.status = NEW.status) THEN
        RAISE EXCEPTION 'invalid chart transition % -> %', OLD.status, NEW.status
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_version_forward_transition';
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE' THEN
        IF EXISTS (
            SELECT 1
            FROM funds.ledger_account account
            WHERE account.book_id = NEW.book_id
              AND account.status = 'OPEN'
              AND NOT EXISTS (
                  SELECT 1
                  FROM funds.ledger_account_chart_mapping mapping
                  WHERE mapping.account_id = account.account_id
                    AND mapping.book_id = account.book_id
                    AND mapping.chart_version_id = NEW.chart_version_id
                    AND mapping.account_currency = account.currency
              )
        ) THEN
            RAISE EXCEPTION 'chart activation requires one mapping for every open account'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'chart_mapping_incomplete';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER chart_version_forward_transition
BEFORE UPDATE OR DELETE ON funds.chart_version
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_chart_version_transition();

CREATE FUNCTION funds.require_new_chart_version_draft()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF NEW.status <> 'DRAFT'
       OR NEW.activated_at IS NOT NULL
       OR NEW.retired_at IS NOT NULL THEN
        RAISE EXCEPTION 'new chart versions must start in DRAFT'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chart_version_must_start_draft';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER chart_version_must_start_draft
BEFORE INSERT ON funds.chart_version
FOR EACH ROW
EXECUTE FUNCTION funds.require_new_chart_version_draft();

-- Stable ledger identity is separated from its versioned chart classification.
ALTER TABLE funds.ledger_account
    ADD CONSTRAINT ledger_account_book_identity_key UNIQUE (account_id, book_id),
    ADD CONSTRAINT ledger_account_book_currency_identity_key
        UNIQUE (account_id, book_id, currency);

CREATE TABLE funds.ledger_account_chart_mapping (
    account_id uuid NOT NULL,
    book_id uuid NOT NULL,
    chart_version_id uuid NOT NULL,
    account_code text NOT NULL,
    account_currency character(3) NOT NULL,
    account_class text NOT NULL
        CHECK (account_class IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance text NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    control_account_code text NOT NULL,
    account_role text NOT NULL CHECK (account_role IN ('CUSTOMER','CONTROL','INTERNAL')),
    currency_policy text NOT NULL DEFAULT 'ACCOUNT_CURRENCY'
        CHECK (currency_policy = 'ACCOUNT_CURRENCY'),
    permitted_direction text NOT NULL DEFAULT 'BOTH'
        CHECK (permitted_direction IN ('BOTH','DEBIT','CREDIT')),
    PRIMARY KEY (account_id, chart_version_id),
    UNIQUE (book_id, chart_version_id, account_code, account_currency),
    FOREIGN KEY (account_id, book_id, account_currency)
        REFERENCES funds.ledger_account(account_id, book_id, currency),
    FOREIGN KEY (book_id, chart_version_id)
        REFERENCES funds.chart_version(book_id, chart_version_id)
);

INSERT INTO funds.ledger_account_chart_mapping (
    account_id, book_id, chart_version_id, account_code, account_currency, account_class,
    normal_balance, control_account_code, account_role
)
SELECT account_id, book_id, chart_version_id, account_code, currency, account_class,
       normal_balance, control_account_code, account_scope
FROM funds.ledger_account;

CREATE FUNCTION funds.reject_chart_mapping_mutation()
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

    -- A mapping mutation writes the governed chart row, not merely a shared
    -- lock. PostgreSQL then rejects a waiting REPEATABLE READ activation whose
    -- snapshot predates this mutation instead of letting it validate stale
    -- completeness. Lock both sides of a moved mapping in canonical order.
    PERFORM 1
    FROM funds.chart_version chart
    WHERE (chart.chart_version_id = source_chart_version_id
           AND chart.book_id = source_book_id)
       OR (chart.chart_version_id = target_chart_version_id
           AND chart.book_id = target_book_id)
    ORDER BY chart.book_id, chart.chart_version_id
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

CREATE TRIGGER ledger_account_chart_mapping_frozen
BEFORE INSERT OR UPDATE OR DELETE ON funds.ledger_account_chart_mapping
FOR EACH ROW
EXECUTE FUNCTION funds.reject_chart_mapping_mutation();

-- This PoC intentionally defers account onboarding after the first chart is
-- active. Without the future governed atomic onboarding operation, accepting
-- a new OPEN account would make the frozen active chart incomplete.
CREATE FUNCTION funds.reject_ungoverned_active_chart_account_onboarding()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF NEW.status = 'OPEN'
       AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'OPEN') THEN
        IF EXISTS (
            SELECT 1 FROM funds.chart_version chart
            WHERE chart.book_id = NEW.book_id AND chart.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'active-chart account onboarding requires a governed atomic operation'
                USING ERRCODE = '55000',
                      CONSTRAINT = 'active_chart_account_onboarding_deferred';
        END IF;

        -- Advance every candidate chart row before the account becomes visible.
        -- A waiting snapshot-isolated activation then serializes; under READ
        -- COMMITTED the post-lock checks observe whichever transaction won.
        PERFORM 1
        FROM funds.chart_version chart
        WHERE chart.book_id = NEW.book_id AND chart.status = 'DRAFT'
        ORDER BY chart.chart_version_id
        FOR UPDATE OF chart;

        UPDATE funds.chart_version chart
        SET governance_revision = chart.governance_revision + 1
        WHERE chart.book_id = NEW.book_id AND chart.status = 'DRAFT';

        IF EXISTS (
            SELECT 1 FROM funds.chart_version chart
            WHERE chart.book_id = NEW.book_id AND chart.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'active-chart account onboarding requires a governed atomic operation'
                USING ERRCODE = '55000',
                      CONSTRAINT = 'active_chart_account_onboarding_deferred';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER active_chart_account_onboarding_deferred
BEFORE INSERT OR UPDATE OF status ON funds.ledger_account
FOR EACH ROW
EXECUTE FUNCTION funds.reject_ungoverned_active_chart_account_onboarding();

DROP TRIGGER ledger_account_identity_immutable ON funds.ledger_account;
CREATE OR REPLACE FUNCTION funds.enforce_ledger_account_identity_immutability()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF NEW.book_id IS DISTINCT FROM OLD.book_id
       OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'ledger account book and currency identity are immutable'
            USING ERRCODE = '55000',
                  CONSTRAINT = 'ledger_account_identity_immutable';
    END IF;
    RETURN NEW;
END
$function$;
CREATE TRIGGER ledger_account_identity_immutable
BEFORE UPDATE ON funds.ledger_account
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_ledger_account_identity_immutability();

ALTER TABLE funds.ledger_account
    DROP COLUMN chart_version_id,
    DROP COLUMN account_code,
    DROP COLUMN account_class,
    DROP COLUMN normal_balance,
    DROP COLUMN control_account_code;

-- Every journal pins the one governed chart used to resolve all of its lines.
DROP TRIGGER journal_immutable ON funds.journal;
ALTER TABLE funds.journal ADD COLUMN chart_version_id uuid;

DO $backfill_journal_chart$
BEGIN
    IF EXISTS (
        SELECT journal.journal_id
        FROM funds.journal journal
        JOIN funds.posting posting ON posting.journal_id = journal.journal_id
        JOIN funds.ledger_account_chart_mapping mapping
          ON mapping.account_id = posting.account_id
         AND mapping.account_currency = posting.currency
        GROUP BY journal.journal_id
        HAVING count(DISTINCT mapping.chart_version_id) <> 1
    ) THEN
        RAISE EXCEPTION 'V005 cannot backfill a journal that mixes chart versions';
    END IF;

    UPDATE funds.journal journal
    SET chart_version_id = coalesce(
        (SELECT mapping.chart_version_id
         FROM funds.posting posting
         JOIN funds.ledger_account_chart_mapping mapping
           ON mapping.account_id = posting.account_id
          AND mapping.account_currency = posting.currency
         WHERE posting.journal_id = journal.journal_id
         ORDER BY mapping.chart_version_id
         LIMIT 1),
        (SELECT chart.chart_version_id
         FROM funds.chart_version chart
         WHERE chart.book_id = journal.book_id AND chart.status = 'ACTIVE'));
END
$backfill_journal_chart$;

-- The backfill schedules V003's deferred journal-balance trigger. Drain it
-- while the row shape is still the V004 shape before the following ALTER.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE funds.journal
    ALTER COLUMN chart_version_id SET NOT NULL,
    ADD CONSTRAINT journal_chart_book_fk
        FOREIGN KEY (book_id, chart_version_id)
        REFERENCES funds.chart_version(book_id, chart_version_id),
    ADD CONSTRAINT journal_reversal_linkage_check CHECK (
        (reversal_of_journal_id IS NULL AND transaction_type <> 'REVERSAL')
        OR (reversal_of_journal_id IS NOT NULL AND transaction_type = 'REVERSAL'
            AND reversal_of_journal_id <> journal_id)
    );

CREATE TRIGGER journal_immutable
BEFORE UPDATE OR DELETE ON funds.journal
FOR EACH ROW
EXECUTE FUNCTION funds.reject_ledger_mutation();

DROP INDEX funds.one_reversal_per_original_idx;
CREATE UNIQUE INDEX one_reversal_per_original_idx
    ON funds.journal (reversal_of_journal_id)
    WHERE reversal_of_journal_id IS NOT NULL;

CREATE FUNCTION funds.enforce_journal_governance()
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
    SELECT book.legal_entity_id, book.accounting_policy_version, book.timezone,
           chart.status, chart.activated_at
    INTO governed_legal_entity, governed_policy, governed_timezone,
         governed_chart_status, governed_chart_activated_at
    FROM funds.book book
    JOIN funds.chart_version chart
      ON chart.book_id = book.book_id
     AND chart.chart_version_id = NEW.chart_version_id
    WHERE book.book_id = NEW.book_id
    FOR SHARE OF book, chart;

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

DROP TRIGGER journal_reference_consistency ON funds.journal;
DROP FUNCTION funds.enforce_journal_reference_consistency();
CREATE TRIGGER journal_governance
BEFORE INSERT ON funds.journal
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_governance();

-- A journal's chart pin is authoritative for every line, including direct
-- funds_app DML. This independently closes the mixed-version bypass that the
-- service-side mapping locks already reject.
CREATE FUNCTION funds.enforce_posting_chart_mapping()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
DECLARE
    governed_direction text;
BEGIN
    -- An absent/invisible journal belongs to the earlier finality/FK guards.
    -- Returning here preserves their stable failure contract while a visible
    -- journal continues through this chart-specific guard.
    PERFORM 1
    FROM funds.journal journal
    WHERE journal.journal_id = NEW.journal_id
    FOR SHARE OF journal;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    SELECT mapping.permitted_direction
    INTO governed_direction
    FROM funds.journal journal
    JOIN funds.ledger_account account
      ON account.account_id = NEW.account_id
     AND account.book_id = journal.book_id
     AND account.currency = NEW.currency
    JOIN funds.ledger_account_chart_mapping mapping
      ON mapping.account_id = account.account_id
     AND mapping.book_id = journal.book_id
     AND mapping.chart_version_id = journal.chart_version_id
     AND mapping.account_currency = account.currency
    WHERE journal.journal_id = NEW.journal_id
    FOR SHARE OF journal, account, mapping;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'posting account does not resolve through the journal chart and currency'
            USING ERRCODE = '23514', CONSTRAINT = 'posting_chart_mapping';
    END IF;
    IF (NEW.signed_minor_units > 0 AND governed_direction = 'CREDIT')
       OR (NEW.signed_minor_units < 0 AND governed_direction = 'DEBIT') THEN
        RAISE EXCEPTION 'posting direction is not permitted by the journal chart mapping'
            USING ERRCODE = '23514', CONSTRAINT = 'posting_permitted_direction';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER posting_chart_mapping
BEFORE INSERT ON funds.posting
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_posting_chart_mapping();

CREATE FUNCTION funds.jsonb_object_size(value jsonb)
RETURNS integer
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, funds
AS $function$
    SELECT count(*)::integer FROM pg_catalog.jsonb_object_keys(value)
$function$;

CREATE FUNCTION funds.jsonb_object_values_are_strings(value jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF pg_catalog.jsonb_typeof(value) <> 'object' THEN
        RETURN false;
    END IF;
    RETURN NOT EXISTS (
        SELECT 1
        FROM pg_catalog.jsonb_each(value) AS member
        WHERE pg_catalog.jsonb_typeof(member.value) <> 'string'
    );
END
$function$;

ALTER TABLE funds.posting
    ADD CONSTRAINT posting_reversible_amount_check
        CHECK (signed_minor_units <> '-9223372036854775808'::bigint),
    ADD CONSTRAINT posting_dimensions_object_check
        CHECK (jsonb_typeof(dimensions) = 'object'),
    ADD CONSTRAINT posting_dimensions_string_values_check
        CHECK (funds.jsonb_object_values_are_strings(dimensions)),
    ADD CONSTRAINT posting_dimensions_count_check
        CHECK (funds.jsonb_object_size(dimensions) <= 32),
    ADD CONSTRAINT posting_dimensions_bytes_check
        CHECK (octet_length(dimensions::text) <= 8192);

-- CHECK constraints above validate existing lines automatically. Deferred
-- constraint triggers do not run retroactively, so fail the additive upgrade
-- if a V004 database already contains a journal outside the new universal
-- reversible envelope or an inexact linked correction.
DO $validate_historical_reversibility$
BEGIN
    IF EXISTS (
        SELECT journal.journal_id
        FROM funds.journal journal
        LEFT JOIN funds.posting posting ON posting.journal_id = journal.journal_id
        GROUP BY journal.journal_id
        HAVING count(posting.posting_id) < 2 OR count(posting.posting_id) > 256
    ) THEN
        RAISE EXCEPTION 'V005 cannot accept a historical journal outside the 2..256 posting envelope';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM funds.journal correction
        JOIN funds.journal original
          ON original.journal_id = correction.reversal_of_journal_id
        WHERE original.reversal_of_journal_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'V005 cannot accept a historical reversal of a reversal';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM funds.journal correction
        WHERE correction.reversal_of_journal_id IS NOT NULL
          AND EXISTS (
              SELECT 1
              FROM (
                  (SELECT posting.account_id, posting.currency,
                          posting.signed_minor_units::numeric AS amount, posting.dimensions
                   FROM funds.posting posting
                   WHERE posting.journal_id = correction.journal_id
                   EXCEPT ALL
                   SELECT posting.account_id, posting.currency,
                          -(posting.signed_minor_units::numeric), posting.dimensions
                   FROM funds.posting posting
                   WHERE posting.journal_id = correction.reversal_of_journal_id)
                  UNION ALL
                  (SELECT posting.account_id, posting.currency,
                          -(posting.signed_minor_units::numeric), posting.dimensions
                   FROM funds.posting posting
                   WHERE posting.journal_id = correction.reversal_of_journal_id
                   EXCEPT ALL
                   SELECT posting.account_id, posting.currency,
                          posting.signed_minor_units::numeric, posting.dimensions
                   FROM funds.posting posting
                   WHERE posting.journal_id = correction.journal_id)
              ) mismatch
          )
    ) THEN
        RAISE EXCEPTION 'V005 cannot accept a historical inexact reversal';
    END IF;
END
$validate_historical_reversibility$;

CREATE FUNCTION funds.enforce_journal_reversibility()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
DECLARE
    checked_journal_id uuid;
    checked_reversal_of uuid;
    checked_type text;
    line_count integer;
BEGIN
    checked_journal_id := CASE WHEN TG_TABLE_NAME = 'journal'
        THEN CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_id ELSE NEW.journal_id END
        ELSE CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_id ELSE NEW.journal_id END
    END;

    SELECT journal.reversal_of_journal_id, journal.transaction_type
    INTO checked_reversal_of, checked_type
    FROM funds.journal journal
    WHERE journal.journal_id = checked_journal_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO line_count
    FROM funds.posting posting
    WHERE posting.journal_id = checked_journal_id;
    IF line_count < 2 OR line_count > 256 THEN
        RAISE EXCEPTION 'journal must contain between 2 and 256 postings'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_reversible_posting_count';
    END IF;

    IF checked_reversal_of IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM funds.journal original
            WHERE original.journal_id = checked_reversal_of
              AND original.reversal_of_journal_id IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'reversal of a reversal requires a distinct correction policy'
                USING ERRCODE = '23514', CONSTRAINT = 'reversal_of_reversal_forbidden';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM (
                (SELECT posting.account_id, posting.currency,
                        posting.signed_minor_units::numeric AS amount, posting.dimensions
                 FROM funds.posting posting
                 WHERE posting.journal_id = checked_journal_id
                 EXCEPT ALL
                 SELECT posting.account_id, posting.currency,
                        -(posting.signed_minor_units::numeric), posting.dimensions
                 FROM funds.posting posting
                 WHERE posting.journal_id = checked_reversal_of)
                UNION ALL
                (SELECT posting.account_id, posting.currency,
                        -(posting.signed_minor_units::numeric), posting.dimensions
                 FROM funds.posting posting
                 WHERE posting.journal_id = checked_reversal_of
                 EXCEPT ALL
                 SELECT posting.account_id, posting.currency,
                        posting.signed_minor_units::numeric, posting.dimensions
                 FROM funds.posting posting
                 WHERE posting.journal_id = checked_journal_id)
            ) mismatch
        ) THEN
            RAISE EXCEPTION 'reversal postings are not exact negations of the original'
                USING ERRCODE = '23514', CONSTRAINT = 'reversal_exact_negation';
        END IF;
    ELSIF checked_type = 'REVERSAL' THEN
        RAISE EXCEPTION 'REVERSAL journal requires an original link'
            USING ERRCODE = '23514', CONSTRAINT = 'journal_reversal_linkage_check';
    END IF;
    RETURN NULL;
END
$function$;

CREATE CONSTRAINT TRIGGER journal_reversibility_deferred
AFTER INSERT OR UPDATE OR DELETE ON funds.journal
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_reversibility();

CREATE CONSTRAINT TRIGGER posting_reversibility_deferred
AFTER INSERT OR UPDATE OR DELETE ON funds.posting
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_reversibility();

-- Narrow security-definer locks expose only governed posting metadata.
CREATE FUNCTION funds.lock_book_chart_for_posting(p_book_id uuid, p_chart_version_id uuid)
RETURNS TABLE (
    legal_entity_id uuid,
    accounting_policy_version integer,
    timezone text,
    chart_status text,
    chart_activated_at timestamptz
)
LANGUAGE sql
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
    SELECT book.legal_entity_id, book.accounting_policy_version, book.timezone,
           chart.status, chart.activated_at
    FROM funds.book book
    JOIN funds.chart_version chart
      ON chart.book_id = book.book_id
     AND chart.chart_version_id = p_chart_version_id
    WHERE book.book_id = p_book_id
    FOR SHARE OF book, chart
$function$;

CREATE FUNCTION funds.lock_account_mapping_for_posting(
    p_account_id uuid,
    p_chart_version_id uuid
)
RETURNS TABLE (
    book_id uuid,
    currency character(3),
    control_account_code text,
    status text,
    permitted_direction text
)
LANGUAGE sql
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
    SELECT account.book_id, account.currency, mapping.control_account_code,
           account.status, mapping.permitted_direction
    FROM funds.ledger_account account
    JOIN funds.ledger_account_chart_mapping mapping
      ON mapping.account_id = account.account_id
     AND mapping.book_id = account.book_id
     AND mapping.chart_version_id = p_chart_version_id
     AND mapping.account_currency = account.currency
    WHERE account.account_id = p_account_id
    FOR UPDATE OF account
    FOR SHARE OF mapping
$function$;

-- V004 granted proof access to the then-current whole schema. Replace it with
-- the exact immutable facts and mapping columns needed by the external proof job.
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA funds FROM funds_proof_reader;
GRANT SELECT (journal_id, journal_sequence, book_id, chart_version_id)
    ON funds.journal TO funds_proof_reader;
GRANT SELECT (journal_id, account_id, currency, signed_minor_units)
    ON funds.posting TO funds_proof_reader;
GRANT SELECT (account_id, book_id, chart_version_id, account_currency, control_account_code)
    ON funds.ledger_account_chart_mapping TO funds_proof_reader;
GRANT SELECT (book_id, control_account_code, currency, signed_posting_total,
              latest_journal_sequence)
    ON funds.control_account_projection TO funds_proof_reader;

GRANT SELECT ON funds.chart_version, funds.ledger_account_chart_mapping TO funds_app;
GRANT INSERT (request_hash_scheme) ON funds.idempotency_command TO funds_app;
GRANT INSERT (chart_version_id, canonical_hash_scheme) ON funds.journal TO funds_app;
GRANT EXECUTE ON FUNCTION funds.lock_book_chart_for_posting(uuid, uuid) TO funds_app;
GRANT EXECUTE ON FUNCTION funds.lock_account_mapping_for_posting(uuid, uuid) TO funds_app;
GRANT EXECUTE ON FUNCTION funds.jsonb_object_size(jsonb) TO funds_app;
GRANT EXECUTE ON FUNCTION funds.jsonb_object_values_are_strings(jsonb) TO funds_app;
DROP FUNCTION funds.lock_book_for_posting(uuid);
DROP FUNCTION funds.lock_account_for_posting(uuid);

ALTER TABLE funds.idempotency_command
    ALTER COLUMN request_hash_scheme SET DEFAULT 'TYPED_V2';
ALTER TABLE funds.journal
    ALTER COLUMN canonical_hash_scheme SET DEFAULT 'V2';

-- Legacy schemes identify rows that were already authenticated by V004.  They
-- are migration-only compatibility markers, not caller-selectable algorithms.
-- Install these guards only after the V004 rows have been preserved and the
-- defaults have advanced, so an ordinary application INSERT can create only a
-- typed/current fact even though the repositories name the columns explicitly.
CREATE FUNCTION funds.require_current_command_hash_scheme()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF NEW.request_hash_scheme <> 'TYPED_V2' THEN
        RAISE EXCEPTION 'new commands must use the TYPED_V2 hash scheme'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'new_command_hash_scheme';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER idempotency_command_current_hash_scheme
BEFORE INSERT ON funds.idempotency_command
FOR EACH ROW
EXECUTE FUNCTION funds.require_current_command_hash_scheme();

CREATE FUNCTION funds.require_current_journal_hash_scheme()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, funds
AS $function$
BEGIN
    IF NEW.canonical_hash_scheme <> 'V2' THEN
        RAISE EXCEPTION 'new journals must use the V2 canonical hash scheme'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'new_journal_hash_scheme';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER journal_current_hash_scheme
BEFORE INSERT ON funds.journal
FOR EACH ROW
EXECUTE FUNCTION funds.require_current_journal_hash_scheme();

RESET ROLE;
