-- V002: journal, posting and transactional outbox. Adds the idempotency command
-- record, the journal header and signed posting lines, the materialised and
-- control-account projections, and the outbox row written in the same commit
-- as its journal. Builds on the V001 reference tables; V003 adds the balance
-- and immutability triggers that turn these facts into a ledger.

-- One row per commandId: claimed IN_PROGRESS before financial work, COMPLETED
-- with its result in the same commit as the journal. Replays read this row.
CREATE TABLE funds.idempotency_command (
    command_id uuid PRIMARY KEY,
    request_hash char(64) NOT NULL,
    state text NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    journal_id uuid,
    result_json jsonb,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    -- State and result move together: a half-written completion cannot exist.
    CHECK ((state = 'IN_PROGRESS' AND journal_id IS NULL AND result_json IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND journal_id IS NOT NULL AND result_json IS NOT NULL AND completed_at IS NOT NULL))
);

-- Journal header. journal_sequence is a monotonic identifier, not a gapless
-- business number; command_id UNIQUE gives one journal per command; the
-- reversal link ties an exact reversal to its original (limited in V003.2).
CREATE TABLE funds.journal (
    journal_id uuid PRIMARY KEY,
    journal_sequence bigserial UNIQUE NOT NULL,
    command_id uuid UNIQUE NOT NULL REFERENCES funds.idempotency_command(command_id),
    correlation_id uuid NOT NULL,
    business_transaction_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    period_id uuid NOT NULL REFERENCES funds.accounting_period(period_id),
    transaction_type text NOT NULL,
    narration text NOT NULL CHECK (octet_length(narration) <= 512),
    booking_time timestamptz NOT NULL,
    value_date date NOT NULL,
    reversal_of_journal_id uuid REFERENCES funds.journal(journal_id),
    policy_version integer NOT NULL CHECK (policy_version > 0),
    canonical_hash char(64) NOT NULL
);

-- Signed lines: positive minor units are debits, negative are credits (README
-- sign convention). (account_id, account_sequence) is the per-account
-- monotonic order that balance updates and reversal reconstruction rely on.
CREATE TABLE funds.posting (
    posting_id uuid PRIMARY KEY,
    journal_id uuid NOT NULL REFERENCES funds.journal(journal_id),
    account_id uuid NOT NULL REFERENCES funds.ledger_account(account_id),
    currency char(3) NOT NULL,
    -- Zero lines carry no accounting meaning; PostingLine rejects them too.
    signed_minor_units bigint NOT NULL CHECK (signed_minor_units <> 0),
    account_sequence bigint NOT NULL CHECK (account_sequence > 0),
    dimensions jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (account_id, account_sequence)
);

-- Per-account running total, updated in the posting transaction under the
-- account lock. A projection, not a source of truth: proofs recompute from
-- posting and compare against it.
CREATE TABLE funds.materialised_balance (
    account_id uuid PRIMARY KEY REFERENCES funds.ledger_account(account_id),
    signed_posting_total bigint NOT NULL DEFAULT 0,
    latest_account_sequence bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0
);

-- Per book / control code / currency total together with the latest journal
-- sequence it includes, so the control proof can recompute postings up to the
-- same cutoff and detect divergence.
CREATE TABLE funds.control_account_projection (
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    control_account_code text NOT NULL,
    currency char(3) NOT NULL,
    signed_posting_total bigint NOT NULL DEFAULT 0,
    latest_journal_sequence bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (book_id, control_account_code, currency)
);

-- Transactional outbox: the event row commits with the journal or not at all.
-- Relay is outside this module; the unique key makes a re-emitted aggregate
-- version idempotent at insert.
CREATE TABLE funds.outbox_event (
    event_id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type text NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0,
    UNIQUE (aggregate_id, aggregate_version, event_type)
);

-- Command and journal reference each other; deferring this side lets either
-- row be written first inside one transaction while both must exist at commit.
ALTER TABLE funds.idempotency_command
    ADD CONSTRAINT fk_idempotency_completed_journal
    FOREIGN KEY (journal_id) REFERENCES funds.journal(journal_id)
    DEFERRABLE INITIALLY DEFERRED;

-- Every journal of one business transaction.
CREATE INDEX journal_business_transaction_idx
    ON funds.journal (business_transaction_id);

-- Lines of one journal: balance trigger, reversal reconstruction, proofs.
CREATE INDEX posting_journal_idx
    ON funds.posting (journal_id);

-- Relay scan of unpublished events in creation order; partial so it stays
-- small however large the published history grows.
CREATE INDEX outbox_unpublished_created_at_idx
    ON funds.outbox_event (created_at)
    WHERE published_at IS NULL;

-- legal_entity_id is denormalised onto the journal and must agree with the
-- book's. FOR SHARE holds the book row while it is compared. Replaced by
-- enforce_journal_governance in V005.
CREATE FUNCTION funds.enforce_journal_reference_consistency()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    referenced_legal_entity_id uuid;
BEGIN
    SELECT book.legal_entity_id
    INTO referenced_legal_entity_id
    FROM funds.book book
    WHERE book.book_id = NEW.book_id
    FOR SHARE;

    IF FOUND AND referenced_legal_entity_id IS DISTINCT FROM NEW.legal_entity_id THEN
        RAISE EXCEPTION 'journal legal entity must match its book legal entity'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'journal_book_legal_entity_consistency';
    END IF;

    RETURN NEW;
END
$function$;

-- Dropped by V005 together with its function.
CREATE TRIGGER journal_reference_consistency
BEFORE INSERT OR UPDATE ON funds.journal
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_reference_consistency();

-- Currency is pinned per account and every line must belong to the journal's
-- book. NOT FOUND returns NEW on purpose: a missing account or journal then
-- fails on its foreign key with the standard error instead of here.
CREATE FUNCTION funds.enforce_posting_reference_consistency()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    referenced_account_book_id uuid;
    referenced_account_currency char(3);
    referenced_journal_book_id uuid;
BEGIN
    SELECT account.book_id, account.currency, journal.book_id
    INTO referenced_account_book_id, referenced_account_currency, referenced_journal_book_id
    FROM funds.ledger_account account
    CROSS JOIN funds.journal journal
    WHERE account.account_id = NEW.account_id
      AND journal.journal_id = NEW.journal_id
    FOR SHARE OF account, journal;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF referenced_account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'posting currency must match its account currency'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'posting_account_currency_consistency';
    END IF;

    IF referenced_account_book_id IS DISTINCT FROM referenced_journal_book_id THEN
        RAISE EXCEPTION 'posting account must belong to the journal book'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'posting_account_book_consistency';
    END IF;

    RETURN NEW;
END
$function$;

-- Row-level, so a single mismatched line rejects the whole transaction.
CREATE TRIGGER posting_reference_consistency
BEFORE INSERT OR UPDATE ON funds.posting
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_posting_reference_consistency();
