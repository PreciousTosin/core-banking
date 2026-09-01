CREATE FUNCTION funds.reject_posting_to_completed_journal()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    command_state text;
BEGIN
    SELECT command.state
    INTO command_state
    FROM funds.journal journal
    JOIN funds.idempotency_command command ON command.command_id = journal.command_id
    WHERE journal.journal_id = NEW.journal_id
    FOR UPDATE OF command;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'posting journal is not visible to finality guard %', NEW.journal_id
            USING ERRCODE = '55000',
                  CONSTRAINT = 'posting_requires_in_progress_command';
    END IF;

    IF command_state = 'COMPLETED' THEN
        RAISE EXCEPTION 'cannot append posting to completed journal %', NEW.journal_id
            USING ERRCODE = '55000',
                  CONSTRAINT = 'posting_requires_in_progress_command';
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER posting_requires_in_progress_command
BEFORE INSERT ON funds.posting
FOR EACH ROW
EXECUTE FUNCTION funds.reject_posting_to_completed_journal();

CREATE UNIQUE INDEX one_reversal_per_original_idx
    ON funds.journal (reversal_of_journal_id)
    WHERE transaction_type = 'REVERSAL' AND reversal_of_journal_id IS NOT NULL;
