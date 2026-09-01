-- V003.1: reference identity immutability. Freezes the book's legal entity and
-- the ledger account's book, chart, currency and control mapping after
-- creation. V001 froze scope and product binding; this closes the remaining
-- identity columns that journals, projections and proofs depend on.

-- Journals denormalise the book's legal entity (V002 trigger). Moving a book
-- between entities would silently invalidate every journal already written.
CREATE FUNCTION funds.enforce_book_identity_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id THEN
        RAISE EXCEPTION 'book legal entity is immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$function$;

-- Other book columns (policy version, governance revision) remain updatable.
CREATE TRIGGER book_identity_immutable
BEFORE UPDATE ON funds.book
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_book_identity_immutability();

-- Book, chart, currency and control code decide where an account's postings
-- roll up; changing any of them would reclassify history. V005 narrows this to
-- book and currency once classification moves to ledger_account_chart_mapping.
CREATE FUNCTION funds.enforce_ledger_account_identity_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.book_id IS DISTINCT FROM OLD.book_id
       OR NEW.chart_version_id IS DISTINCT FROM OLD.chart_version_id
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.control_account_code IS DISTINCT FROM OLD.control_account_code THEN
        RAISE EXCEPTION 'ledger account identity and control mapping are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$function$;

-- Complements V001's ledger_account_reference_immutable; dropped and
-- re-created with the narrower rule by V005.
CREATE TRIGGER ledger_account_identity_immutable
BEFORE UPDATE ON funds.ledger_account
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_ledger_account_identity_immutability();
