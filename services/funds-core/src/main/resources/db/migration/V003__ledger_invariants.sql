-- V003: ledger invariants. Adds the deferred per-currency balance check on
-- journal and posting rows and makes both tables immutable. V002 defined the
-- shape of the facts; this migration is what makes them a ledger.

-- Every journal must sum to zero independently per currency (ACC-01). Deferred
-- to commit because lines arrive one row at a time; summed in numeric so a
-- bigint overflow cannot mask an imbalance. Mirrors JournalValidator's
-- per-currency total, but runs even for direct DML.
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

-- Fired from the header as well as the lines. An empty journal passes here (no
-- currency group is unbalanced); V005's journal_reversibility_deferred rejects it.
CREATE CONSTRAINT TRIGGER journal_balance_deferred
AFTER INSERT OR UPDATE OR DELETE ON funds.journal
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_balance();

-- Each line re-queues the check; the deferred run sees the complete journal.
CREATE CONSTRAINT TRIGGER posting_balance_deferred
AFTER INSERT OR UPDATE OR DELETE ON funds.posting
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION funds.enforce_journal_balance();

-- Journals and postings are append-only facts. A correction is a new linked
-- reversal journal (V003.2, ReversalService), never an in-place edit.
CREATE FUNCTION funds.reject_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END
$function$;

-- Dropped and re-created by V005 around its one-time chart backfill.
CREATE TRIGGER journal_immutable
BEFORE UPDATE OR DELETE ON funds.journal
FOR EACH ROW
EXECUTE FUNCTION funds.reject_ledger_mutation();

-- Lines are guarded independently of the header so neither can be edited alone.
CREATE TRIGGER posting_immutable
BEFORE UPDATE OR DELETE ON funds.posting
FOR EACH ROW
EXECUTE FUNCTION funds.reject_ledger_mutation();
