CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SCHEMA funds;

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

CREATE TABLE funds.book (
    book_id uuid PRIMARY KEY,
    legal_entity_id uuid NOT NULL,
    functional_currency char(3) NOT NULL CHECK (functional_currency ~ '^[A-Z]{3}$'),
    timezone text NOT NULL,
    calendar_code text NOT NULL,
    accounting_policy_version integer NOT NULL CHECK (accounting_policy_version > 0),
    UNIQUE (legal_entity_id, book_id)
);

CREATE TABLE funds.chart_version (
    chart_version_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    activated_at timestamptz,
    UNIQUE (book_id, version)
);

CREATE TABLE funds.accounting_period (
    period_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    business_date_from date NOT NULL,
    business_date_to date NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN','CLOSING','CLOSED')),
    CHECK (business_date_to >= business_date_from),
    EXCLUDE USING gist (book_id WITH =, daterange(business_date_from, business_date_to, '[]') WITH &&)
);

CREATE TABLE funds.product_definition (
    product_id uuid PRIMARY KEY,
    product_code text NOT NULL UNIQUE,
    product_kind text NOT NULL CHECK (product_kind IN ('SAVINGS','CURRENT','FIXED_DEPOSIT','DOMICILIARY')),
    finance_principle text NOT NULL CHECK (finance_principle IN ('CONVENTIONAL','NON_INTEREST'))
);

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
    CHECK ((account_scope = 'CUSTOMER' AND product_version_id IS NOT NULL)
        OR (account_scope <> 'CUSTOMER' AND product_version_id IS NULL))
);

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
    CHECK ((scheme = 'NUBAN' AND institution_code IS NOT NULL AND provider_id IS NULL
            AND normalised_value ~ '^[0-9]{10}$')
        OR (scheme = 'PROVIDER_VIRTUAL_ACCOUNT' AND provider_id IS NOT NULL)
        OR (scheme = 'IBAN' AND false)),
    CHECK (scheme <> 'NUBAN' OR funds.is_valid_nuban(institution_code::text, normalised_value))
);

CREATE INDEX account_period_lookup_idx
    ON funds.accounting_period (book_id, business_date_from, business_date_to);

CREATE INDEX ledger_account_book_status_idx
    ON funds.ledger_account (book_id, status);

CREATE INDEX account_identifier_resolution_idx
    ON funds.account_identifier (scheme, normalised_value, lifecycle_status, routing_scope);

CREATE UNIQUE INDEX account_identifier_active_scope_uidx
    ON funds.account_identifier (
        scheme,
        coalesce(institution_code, ''),
        coalesce(provider_id::text, ''),
        normalised_value
    )
    WHERE lifecycle_status = 'ACTIVE';

CREATE UNIQUE INDEX account_identifier_active_primary_nuban_uidx
    ON funds.account_identifier (account_id)
    WHERE scheme = 'NUBAN' AND lifecycle_status = 'ACTIVE' AND is_primary;

CREATE FUNCTION funds.enforce_external_identifier_customer_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.routing_scope = 'EXTERNAL'
       AND NOT EXISTS (
           SELECT 1
           FROM funds.ledger_account account
           WHERE account.account_id = NEW.account_id
             AND account.account_scope = 'CUSTOMER'
       ) THEN
        RAISE EXCEPTION 'external identifiers require a CUSTOMER ledger account'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER account_identifier_external_customer_only
BEFORE INSERT ON funds.account_identifier
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_external_identifier_customer_scope();

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
