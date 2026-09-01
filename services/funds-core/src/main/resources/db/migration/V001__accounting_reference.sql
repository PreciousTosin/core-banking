-- V001: accounting reference data. Creates the funds schema and the reference
-- foundations every later migration builds on: book, chart version, accounting
-- period, product definition/version, ledger account and account identifier,
-- plus the immutability triggers that protect product terms and identifiers.
-- Baseline migration; V002 adds the journal, posting and outbox facts on top.

-- btree_gist lets the accounting_period EXCLUDE constraint combine uuid
-- equality with daterange overlap in one index.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SCHEMA funds;

-- NUBAN check digit: weights 3,7,3 repeated across the six institution digits
-- and nine serial digits; the tenth digit must equal the result. Used by the
-- account_identifier CHECK so an unverifiable NUBAN can never be stored.
CREATE FUNCTION funds.is_valid_nuban(p_institution_code text, p_nuban text)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $function$
    SELECT p_institution_code ~ '^[0-9]{6}$'
       AND p_nuban ~ '^[0-9]{10}$'
       AND (
            10 - (
                substring(p_institution_code FROM 1 FOR 1)::integer * 3
              + substring(p_institution_code FROM 2 FOR 1)::integer * 7
              + substring(p_institution_code FROM 3 FOR 1)::integer * 3
              + substring(p_institution_code FROM 4 FOR 1)::integer * 3
              + substring(p_institution_code FROM 5 FOR 1)::integer * 7
              + substring(p_institution_code FROM 6 FOR 1)::integer * 3
              + substring(p_nuban FROM 1 FOR 1)::integer * 3
              + substring(p_nuban FROM 2 FOR 1)::integer * 7
              + substring(p_nuban FROM 3 FOR 1)::integer * 3
              + substring(p_nuban FROM 4 FOR 1)::integer * 3
              + substring(p_nuban FROM 5 FOR 1)::integer * 7
              + substring(p_nuban FROM 6 FOR 1)::integer * 3
              + substring(p_nuban FROM 7 FOR 1)::integer * 3
              + substring(p_nuban FROM 8 FOR 1)::integer * 7
              + substring(p_nuban FROM 9 FOR 1)::integer * 3
            ) % 10
       ) % 10 = substring(p_nuban FROM 10 FOR 1)::integer
$function$;

-- A book is one legal entity's accounting boundary (functional currency,
-- calendar, policy version).
CREATE TABLE funds.book (
    book_id uuid PRIMARY KEY,
    legal_entity_id uuid NOT NULL,
    functional_currency char(3) NOT NULL CHECK (functional_currency ~ '^[A-Z]{3}$'),
    timezone text NOT NULL,
    calendar_code text NOT NULL,
    accounting_policy_version integer NOT NULL CHECK (accounting_policy_version > 0),
    UNIQUE (legal_entity_id, book_id)
);

-- Versioned chart of accounts, one lifecycle per book. UNIQUE (book_id,
-- chart_version_id) lets dependants reference a chart only through its own
-- book, which is what prevents cross-book classification later.
CREATE TABLE funds.chart_version (
    chart_version_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    activated_at timestamptz,
    UNIQUE (book_id, version),
    UNIQUE (book_id, chart_version_id)
);

CREATE TABLE funds.accounting_period (
    period_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    business_date_from date NOT NULL,
    business_date_to date NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN','CLOSING','CLOSED')),
    CHECK (business_date_to >= business_date_from),
    -- Periods of one book may not overlap: two inclusive date ranges that
    -- intersect would let one booking date resolve to two periods.
    EXCLUDE USING gist (book_id WITH =, daterange(business_date_from, business_date_to, '[]') WITH &&)
);

-- Stable commercial family. The classification columns are moved onto the
-- immutable product_version by V005 so a later version cannot reclassify.
CREATE TABLE funds.product_definition (
    product_id uuid PRIMARY KEY,
    product_code text NOT NULL UNIQUE,
    product_kind text NOT NULL CHECK (product_kind IN ('SAVINGS','CURRENT','FIXED_DEPOSIT','DOMICILIARY')),
    finance_principle text NOT NULL CHECK (finance_principle IN ('CONVENTIONAL','NON_INTEREST'))
);

-- Approved terms a customer account binds to immutably; the approval reference
-- and policy hash are what make a version auditable.
CREATE TABLE funds.product_version (
    product_version_id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES funds.product_definition(product_id),
    version integer NOT NULL CHECK (version > 0),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    approval_reference text NOT NULL,
    policy_hash char(64) NOT NULL,
    policy_json jsonb NOT NULL,
    UNIQUE (product_id, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Product terms are append-only: a change is a new version, never an UPDATE or
-- DELETE, so an account's historical terms can always be reproduced.
CREATE FUNCTION funds.reject_product_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'product versions are immutable; create a new version instead'
        USING ERRCODE = '55000';
END
$function$;

-- Dropped and re-created by V005 around its one-time classification backfill.
CREATE TRIGGER product_version_immutable
BEFORE UPDATE OR DELETE ON funds.product_version
FOR EACH ROW
EXECUTE FUNCTION funds.reject_product_version_mutation();

-- The balance-bearing financial identity (README: identity and product
-- foundations). Currency is fixed per account; V003.1 makes it immutable and
-- V005 moves the chart classification columns out to a per-chart mapping.
CREATE TABLE funds.ledger_account (
    account_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    chart_version_id uuid NOT NULL REFERENCES funds.chart_version(chart_version_id),
    account_code text NOT NULL,
    account_scope text NOT NULL CHECK (account_scope IN ('CUSTOMER','CONTROL','INTERNAL')),
    product_version_id uuid REFERENCES funds.product_version(product_version_id),
    account_class text NOT NULL CHECK (account_class IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance text NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    control_account_code text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN','DEBIT_BLOCKED','CREDIT_BLOCKED','CLOSED')),
    authorised_floor_minor bigint NOT NULL DEFAULT 0 CHECK (authorised_floor_minor <= 0),
    created_at timestamptz NOT NULL,
    closed_at timestamptz,
    UNIQUE (book_id, account_code, currency),
    -- The chart must belong to the account's own book, not merely exist.
    FOREIGN KEY (book_id, chart_version_id)
        REFERENCES funds.chart_version(book_id, chart_version_id),
    -- Only CUSTOMER accounts carry product terms; CONTROL and INTERNAL never do.
    CHECK ((account_scope = 'CUSTOMER' AND product_version_id IS NOT NULL)
        OR (account_scope <> 'CUSTOMER' AND product_version_id IS NULL))
);

-- Scope and product binding are fixed at creation: reclassifying an account
-- would rewrite the accounting already recorded under its old terms (ACC-40/42).
CREATE FUNCTION funds.enforce_ledger_account_reference_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.account_scope IS DISTINCT FROM OLD.account_scope THEN
        RAISE EXCEPTION 'ledger account scope is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.product_version_id IS DISTINCT FROM OLD.product_version_id THEN
        RAISE EXCEPTION 'ledger account product version binding is immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$function$;

-- V003.1 adds a second UPDATE trigger for the remaining identity columns.
CREATE TRIGGER ledger_account_reference_immutable
BEFORE UPDATE ON funds.ledger_account
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_ledger_account_reference_immutability();

-- Addresses that resolve to an account (NUBAN, provider virtual account). An
-- identifier is never a posting account, command or idempotency key and holds
-- no balance; the balance-bearing identity is ledger_account.account_id.
CREATE TABLE funds.account_identifier (
    account_identifier_id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES funds.ledger_account(account_id),
    scheme text NOT NULL CHECK (scheme IN ('NUBAN','PROVIDER_VIRTUAL_ACCOUNT','IBAN')),
    normalised_value text NOT NULL,
    institution_code char(6),
    provider_id uuid,
    purpose_code text,
    routing_scope text NOT NULL CHECK (routing_scope IN ('SIMULATOR_ONLY','INTERNAL','EXTERNAL')),
    lifecycle_status text NOT NULL CHECK (lifecycle_status IN ('PENDING','ACTIVE','RETIRED','REVOKED')),
    is_primary boolean NOT NULL DEFAULT false,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    issuance_evidence_hash char(64) NOT NULL,
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    -- Each scheme carries exactly its own scoping key: NUBAN an institution
    -- code, a virtual account a provider id. IBAN is reserved for a future
    -- country-specific validator and is always rejected here.
    CHECK ((scheme = 'NUBAN' AND institution_code IS NOT NULL AND provider_id IS NULL
            AND normalised_value ~ '^[0-9]{10}$')
        OR (scheme = 'PROVIDER_VIRTUAL_ACCOUNT' AND provider_id IS NOT NULL
            AND institution_code IS NULL)
        OR (scheme = 'IBAN' AND false)),
    -- A NUBAN that fails its check digit is rejected at the row, whatever the
    -- caller validated.
    CHECK (scheme <> 'NUBAN' OR funds.is_valid_nuban(institution_code::text, normalised_value))
);

-- Period resolution by book and business date.
CREATE INDEX account_period_lookup_idx
    ON funds.accounting_period (book_id, business_date_from, business_date_to);

-- Open-account universe scans per book (chart completeness checks in V005).
CREATE INDEX ledger_account_book_status_idx
    ON funds.ledger_account (book_id, status);

-- Address resolution path: scheme and value, filtered by lifecycle and routing.
CREATE INDEX account_identifier_resolution_idx
    ON funds.account_identifier (scheme, normalised_value, lifecycle_status, routing_scope);

-- One ACTIVE address per scope: the same NUBAN under one institution, or the
-- same virtual account under one provider, cannot resolve to two accounts.
-- coalesce keeps a NULL institution/provider from defeating uniqueness.
CREATE UNIQUE INDEX account_identifier_active_scope_uidx
    ON funds.account_identifier (
        scheme,
        coalesce(institution_code, ''),
        coalesce(provider_id::text, ''),
        normalised_value
    )
    WHERE lifecycle_status = 'ACTIVE';

-- At most one ACTIVE primary NUBAN per account (ACC-38).
CREATE UNIQUE INDEX account_identifier_active_primary_nuban_uidx
    ON funds.account_identifier (account_id)
    WHERE scheme = 'NUBAN' AND lifecycle_status = 'ACTIVE' AND is_primary;

-- Only CUSTOMER accounts may carry an externally routable address; CONTROL and
-- INTERNAL accounts must never be reachable from outside. FOR SHARE holds the
-- referenced account row while the scope is read.
CREATE FUNCTION funds.enforce_external_identifier_customer_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    referenced_account_scope text;
BEGIN
    SELECT account.account_scope
    INTO referenced_account_scope
    FROM funds.ledger_account account
    WHERE account.account_id = NEW.account_id
    FOR SHARE;

    IF NEW.routing_scope = 'EXTERNAL'
       AND referenced_account_scope IS DISTINCT FROM 'CUSTOMER' THEN
        RAISE EXCEPTION 'external identifiers require a CUSTOMER ledger account'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$function$;

-- INSERT only: identifier rows cannot change afterwards (next trigger), so the
-- scope check never needs to run on UPDATE.
CREATE TRIGGER account_identifier_external_customer_only
BEFORE INSERT ON funds.account_identifier
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_external_identifier_customer_scope();

-- Identifier lifecycle is append-only: retire or revoke an address by inserting
-- a successor fact, never by editing the history of who held it.
CREATE FUNCTION funds.reject_account_identifier_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'account identifiers are immutable; append a successor lifecycle fact'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER account_identifier_immutable
BEFORE UPDATE OR DELETE ON funds.account_identifier
FOR EACH ROW
EXECUTE FUNCTION funds.reject_account_identifier_mutation();
