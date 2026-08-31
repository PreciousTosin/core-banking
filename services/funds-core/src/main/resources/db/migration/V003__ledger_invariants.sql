CREATE FUNCTION funds.enforce_journal_balance()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    checked_journal_id uuid;
    has_imbalance boolean;
BEGIN
    IF TG_TABLE_NAME = 'journal' THEN
        IF TG_OP = 'DELETE' THEN
            checked_journal_id := OLD.journal_id;
        ELSE
            checked_journal_id := NEW.journal_id;
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        checked_journal_id := OLD.journal_id;
    ELSE
        checked_journal_id := NEW.journal_id;
    END IF;

    SELECT EXISTS (
        SELECT currency
        FROM funds.posting
        WHERE journal_id = checked_journal_id
        GROUP BY currency
        HAVING sum(signed_minor_units::numeric) <> 0
    )
    INTO has_imbalance;

    IF has_imbalance THEN
        RAISE EXCEPTION 'journal % is not balanced independently per currency', checked_journal_id
            USING ERRCODE = '23514',
                  CONSTRAINT = 'journal_balanced_per_currency';
    END IF;

    RETURN NULL;
END
$function$;

CREATE CONSTRAINT TRIGGER journal_balance_deferred
AFTER INSERT OR UPDATE OR DELETE ON funds.journal
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_balance();

CREATE CONSTRAINT TRIGGER posting_balance_deferred
AFTER INSERT OR UPDATE OR DELETE ON funds.posting
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_balance();

CREATE FUNCTION funds.reject_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER journal_immutable
BEFORE UPDATE OR DELETE ON funds.journal
FOR EACH ROW
EXECUTE FUNCTION funds.reject_ledger_mutation();

CREATE TRIGGER posting_immutable
BEFORE UPDATE OR DELETE ON funds.posting
FOR EACH ROW
EXECUTE FUNCTION funds.reject_ledger_mutation();
