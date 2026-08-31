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

CREATE TRIGGER book_identity_immutable
BEFORE UPDATE ON funds.book
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_book_identity_immutability();

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

CREATE TRIGGER ledger_account_identity_immutable
BEFORE UPDATE ON funds.ledger_account
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_ledger_account_identity_immutability();
