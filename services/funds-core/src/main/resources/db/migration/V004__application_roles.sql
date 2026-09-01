-- V004: application roles. Bootstraps the three capability roles (migrator as
-- schema owner, app as the service role, proof_reader for external proof
-- jobs), transfers ownership of every funds object to the migrator, pins
-- SECURITY DEFINER/INVOKER and search_path on every routine, revokes PUBLIC
-- and grants funds_app only the column-level DML the kernel needs. V003.x
-- finished the ledger invariants; from here the database, not the service,
-- decides who may mutate ledger facts (ACC-24). See MIGRATION-ROLES.md.
--
-- Guarded by MigrationIT.roleBootstrapIsFailClosedAndNeverAltersExistingClusterRoles,
-- which reads this file as text: exactly three role-creation lines must exist,
-- and the idempotent-create, role-alter, membership-catalogue and dynamic
-- revoke-from idioms are forbidden anywhere in the file, comments included.

-- Fail closed: plain creation errors if any of the three roles already exists,
-- so a pre-existing cluster role is never adopted or changed. NOINHERIT keeps a
-- login granted one capability from silently acquiring another's privileges.
CREATE ROLE funds_migrator WITH
    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE funds_app WITH
    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE funds_proof_reader WITH
    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

-- A COMPLETED result is the replay answer for its commandId forever; only
-- IN_PROGRESS rows may still change (completion, or owner-abandonment cleanup).
CREATE FUNCTION funds.reject_completed_idempotency_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.state = 'COMPLETED' THEN
        RAISE EXCEPTION 'completed idempotency results are immutable'
            USING ERRCODE = '55000',
                  CONSTRAINT = 'completed_idempotency_immutable';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END
$function$;

-- Applies to every role, including the owner: finality is not a grant.
CREATE TRIGGER completed_idempotency_immutable
BEFORE UPDATE OR DELETE ON funds.idempotency_command
FOR EACH ROW
EXECUTE FUNCTION funds.reject_completed_idempotency_mutation();

-- Row locks (FOR SHARE / FOR UPDATE) need UPDATE privilege, which funds_app
-- must never hold on reference tables. These definer routines take the lock
-- on the caller's behalf and return only the governed columns. Replaced by
-- lock_book_chart_for_posting in V005 once journals pin a chart.
CREATE FUNCTION funds.lock_book_for_posting(p_book_id uuid)
RETURNS TABLE (legal_entity_id uuid, accounting_policy_version integer)
LANGUAGE sql
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
    SELECT book.legal_entity_id, book.accounting_policy_version
    FROM funds.book book
    WHERE book.book_id = p_book_id
    FOR SHARE OF book
$function$;

-- Shared period lock: a period close cannot commit between the OPEN check and
-- the journal insert (ACC-20 closed-period rejection). Still used after V005.
CREATE FUNCTION funds.lock_period_for_posting(p_period_id uuid)
RETURNS TABLE (
    book_id uuid,
    business_date_from date,
    business_date_to date,
    status text
)
LANGUAGE sql
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
    SELECT period.book_id, period.business_date_from, period.business_date_to, period.status
    FROM funds.accounting_period period
    WHERE period.period_id = p_period_id
    FOR SHARE OF period
$function$;

-- Exclusive account lock serialises balance updates; the shared chart lock pins
-- the classification read in the same statement. Callers lock accounts in
-- canonical UUID-string order. Replaced by lock_account_mapping_for_posting
-- in V005.
CREATE FUNCTION funds.lock_account_for_posting(p_account_id uuid)
RETURNS TABLE (
    book_id uuid,
    currency character(3),
    control_account_code text,
    status text,
    chart_status text
)
LANGUAGE sql
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, funds
AS $function$
    SELECT account.book_id, account.currency, account.control_account_code,
           account.status, chart.status AS chart_status
    FROM funds.ledger_account account
    JOIN funds.chart_version chart ON chart.chart_version_id = account.chart_version_id
    WHERE account.account_id = p_account_id
    FOR UPDATE OF account
    FOR SHARE OF chart
$function$;

-- Every funds routine has a deterministic safe path. Only the routines that
-- must read/lock tables unavailable to their caller execute with owner rights.
ALTER FUNCTION funds.is_valid_nuban(text, text)
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_product_version_mutation()
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_ledger_account_reference_immutability()
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_external_identifier_customer_scope()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_account_identifier_mutation()
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_journal_reference_consistency()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_posting_reference_consistency()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_journal_balance()
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_ledger_mutation()
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_book_identity_immutability()
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_ledger_account_identity_immutability()
    SECURITY INVOKER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_posting_to_completed_journal()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_completed_idempotency_mutation()
    SECURITY INVOKER SET search_path = pg_catalog, funds;

-- Nothing in funds is reachable by default; every capability below is an
-- explicit grant to a named role.
REVOKE ALL ON SCHEMA funds FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA funds FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA funds FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA funds FROM PUBLIC;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA funds FROM funds_app, funds_proof_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA funds FROM funds_app, funds_proof_reader;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA funds FROM funds_app, funds_proof_reader;

-- Ownership moves to the non-login migrator so every later migration runs
-- under SET ROLE and new objects inherit its hardened default privileges.
ALTER SCHEMA funds OWNER TO funds_migrator;

-- Transfer every table (and standalone sequence) created by V001-V003.x under
-- the bootstrap login. Owned serial sequences follow their table automatically.
DO $relations$
DECLARE
    relation record;
BEGIN
    FOR relation IN
        SELECT namespace.nspname AS schema_name, class.relname AS relation_name, class.relkind
        FROM pg_class class
        JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
        WHERE namespace.nspname = 'funds'
          AND class.relkind IN ('r', 'p')
    LOOP
        EXECUTE format(
            'ALTER TABLE %I.%I OWNER TO funds_migrator',
            relation.schema_name,
            relation.relation_name);
    END LOOP;

    -- ALTER TABLE transfers its owned identity/serial sequences. Only standalone
    -- sequences may be altered independently.
    FOR relation IN
        SELECT namespace.nspname AS schema_name, class.relname AS relation_name
        FROM pg_class class
        JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
        WHERE namespace.nspname = 'funds'
          AND class.relkind = 'S'
          AND NOT EXISTS (
              SELECT 1
              FROM pg_depend dependency
              WHERE dependency.classid = 'pg_class'::regclass
                AND dependency.objid = class.oid
                AND dependency.refclassid = 'pg_class'::regclass
                AND dependency.deptype IN ('a', 'i')
          )
    LOOP
        EXECUTE format(
            'ALTER SEQUENCE %I.%I OWNER TO funds_migrator',
            relation.schema_name,
            relation.relation_name);
    END LOOP;
END
$relations$;

-- Same transfer for routines: SECURITY DEFINER bodies must execute as the
-- migrator, never as whichever login happened to run the earlier migrations.
DO $functions$
DECLARE
    function_row record;
BEGIN
    FOR function_row IN
        SELECT namespace.nspname AS schema_name,
               procedure.proname AS function_name,
               pg_get_function_identity_arguments(procedure.oid) AS identity_arguments
        FROM pg_proc procedure
        JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'funds'
    LOOP
        EXECUTE format(
            'ALTER FUNCTION %I.%I(%s) OWNER TO funds_migrator',
            function_row.schema_name,
            function_row.function_name,
            function_row.identity_arguments);
    END LOOP;
END
$functions$;

-- Objects the migrator creates in later migrations start with no PUBLIC access.
ALTER DEFAULT PRIVILEGES FOR ROLE funds_migrator IN SCHEMA funds
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE funds_migrator IN SCHEMA funds
    REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE funds_migrator IN SCHEMA funds
    REVOKE ALL ON FUNCTIONS FROM PUBLIC;
-- PostgreSQL's built-in PUBLIC EXECUTE for functions is a global default;
-- a per-schema REVOKE cannot subtract it, so remove it at the owner level too.
ALTER DEFAULT PRIVILEGES FOR ROLE funds_migrator
    REVOKE ALL ON FUNCTIONS FROM PUBLIC;

GRANT USAGE ON SCHEMA funds TO funds_app, funds_proof_reader;

-- funds_app: read where the kernel reads, and column-scoped INSERT/UPDATE only
-- on the columns it writes. No DELETE anywhere, no UPDATE on journal or
-- posting, so direct ledger mutation is denied by privilege as well as trigger.
GRANT SELECT ON
    funds.book,
    funds.accounting_period,
    funds.ledger_account,
    funds.idempotency_command,
    funds.journal,
    funds.posting,
    funds.materialised_balance,
    funds.control_account_projection,
    funds.outbox_event
TO funds_app;

GRANT INSERT (command_id, request_hash, state, created_at)
    ON funds.idempotency_command TO funds_app;
-- Completion path only: command_id and request_hash can never be rewritten.
GRANT UPDATE (state, journal_id, result_json, completed_at)
    ON funds.idempotency_command TO funds_app;
GRANT INSERT (
    journal_id, command_id, correlation_id, business_transaction_id, legal_entity_id,
    book_id, period_id, transaction_type, narration, booking_time, value_date,
    reversal_of_journal_id, policy_version, canonical_hash
) ON funds.journal TO funds_app;
GRANT INSERT (
    posting_id, journal_id, account_id, currency, signed_minor_units,
    account_sequence, dimensions
) ON funds.posting TO funds_app;
GRANT INSERT (account_id, signed_posting_total, latest_account_sequence, version)
    ON funds.materialised_balance TO funds_app;
GRANT UPDATE (signed_posting_total, latest_account_sequence, version)
    ON funds.materialised_balance TO funds_app;
GRANT INSERT (
    book_id, control_account_code, currency, signed_posting_total, latest_journal_sequence
) ON funds.control_account_projection TO funds_app;
GRANT UPDATE (signed_posting_total, latest_journal_sequence)
    ON funds.control_account_projection TO funds_app;
GRANT INSERT (
    event_id, aggregate_id, aggregate_version, event_type, schema_version,
    payload, created_at
) ON funds.outbox_event TO funds_app;

-- USAGE is the minimum nextval needs; it does not permit setval or sequence
-- UPDATE (MIGRATION-ROLES.md). Gaps after rollback are expected.
GRANT USAGE ON SEQUENCE funds.journal_journal_sequence_seq TO funds_app;
GRANT EXECUTE ON FUNCTION funds.lock_book_for_posting(uuid) TO funds_app;
GRANT EXECUTE ON FUNCTION funds.lock_period_for_posting(uuid) TO funds_app;
GRANT EXECUTE ON FUNCTION funds.lock_account_for_posting(uuid) TO funds_app;

-- Provisional whole-schema read for the external proof job; V005 replaces it
-- with exact column grants.
GRANT SELECT ON ALL TABLES IN SCHEMA funds TO funds_proof_reader;
