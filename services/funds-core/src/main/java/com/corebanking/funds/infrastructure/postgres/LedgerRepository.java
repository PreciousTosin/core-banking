package com.corebanking.funds.infrastructure.postgres;

import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingResult;
import java.sql.Connection;
import java.util.Optional;

/**
 * SQL boundary of a posting transaction. The caller (PostingService) owns the connection:
 * autocommit is off, isolation is SERIALIZABLE, the transaction-local lock/statement/idle
 * deadlines are already applied, and the caller commits or rolls back. Neither method commits,
 * and PostgresRetryPolicy may re-run the whole transaction from a fresh connection, so every
 * effect must be safe to repeat; the idempotency_command row is what provides that.
 */
public interface LedgerRepository {
    /**
     * Posts the command's journal inside the caller's transaction and returns the result the
     * caller will commit; a command already COMPLETED under the row lock returns its stored
     * result instead. Throws IdempotencyConflictException for a same-key, different-content
     * request; InvalidJournalException, AccountingPeriodClosedException,
     * MonetaryOverflowException or LedgerCapacityException when the journal cannot be admitted;
     * and a LedgerPersistenceException (classified by SqlState) for any SQL failure.
     */
    PostingResult post(Connection connection, PostingCommand command);

    /**
     * Pre-flight replay check, run before validation and before any row lock. Returns the
     * stored result only when the command is COMPLETED and that result coherently identifies
     * its own journal (V004 rows are additionally re-verified from stored facts); returns
     * empty when the command is unknown or still IN_PROGRESS under the current scheme. Never a
     * partial answer: a hash mismatch is IdempotencyConflictException and an incoherent stored
     * fact is InvalidJournalException.
     */
    Optional<PostingResult> findCompleted(Connection connection, PostingCommand command);
}
