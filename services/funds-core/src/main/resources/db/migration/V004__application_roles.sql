DO $roles$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'funds_migrator') THEN
        CREATE ROLE funds_migrator NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'funds_app') THEN
        CREATE ROLE funds_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'funds_proof_reader') THEN
        CREATE ROLE funds_proof_reader NOLOGIN;
    END IF;
END
$roles$;

ALTER ROLE funds_migrator WITH
    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE funds_app WITH
    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE funds_proof_reader WITH
    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

-- None of the capability roles may inherit another role. Deployment login roles
-- may be made members of exactly funds_app or funds_proof_reader outside Flyway.
DO $memberships$
DECLARE
    membership record;
BEGIN
    FOR membership IN
        SELECT granted.rolname AS granted_role, member.rolname AS member_role
        FROM pg_auth_members auth
        JOIN pg_roles granted ON granted.oid = auth.roleid
        JOIN pg_roles member ON member.oid = auth.member
        WHERE member.rolname IN ('funds_migrator', 'funds_app', 'funds_proof_reader')
    LOOP
        EXECUTE format('REVOKE %I FROM %I', membership.granted_role, membership.member_role);
    END LOOP;
END
$memberships$;

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

    RETURN NEW;
END
$function$;

CREATE TRIGGER completed_idempotency_immutable
BEFORE UPDATE ON funds.idempotency_command
FOR EACH ROW
EXECUTE FUNCTION funds.reject_completed_idempotency_mutation();

-- Trigger guards must be able to take their internal SHARE/UPDATE locks without
-- granting the application role broad UPDATE rights on reference or journal
-- tables. They execute as the tightly owned migrator role with a fixed path.
ALTER FUNCTION funds.reject_product_version_mutation()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_ledger_account_reference_immutability()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_external_identifier_customer_scope()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_account_identifier_mutation()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_journal_reference_consistency()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_posting_reference_consistency()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_journal_balance()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_ledger_mutation()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_book_identity_immutability()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.enforce_ledger_account_identity_immutability()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_posting_to_completed_journal()
    SECURITY DEFINER SET search_path = pg_catalog, funds;
ALTER FUNCTION funds.reject_completed_idempotency_mutation()
    SECURITY DEFINER SET search_path = pg_catalog, funds;

REVOKE ALL ON SCHEMA funds FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA funds FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA funds FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA funds FROM PUBLIC;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA funds FROM funds_app, funds_proof_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA funds FROM funds_app, funds_proof_reader;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA funds FROM funds_app, funds_proof_reader;

ALTER SCHEMA funds OWNER TO funds_migrator;

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
    payload, created_at, published_at, publish_attempts
) ON funds.outbox_event TO funds_app;

GRANT USAGE ON SEQUENCE funds.journal_journal_sequence_seq TO funds_app;

GRANT SELECT ON ALL TABLES IN SCHEMA funds TO funds_proof_reader;
