package com.corebanking.funds.domain.exception;

import java.sql.SQLException;

/**
 * A database failure the kernel could not classify further. Serialization failures and
 * deadlocks are retried by PostgresRetryPolicy before this surfaces, and deadline expiries
 * arrive as the LedgerTimeoutException subclass (see SqlState.persistenceFailure). Reversal
 * maps the one-reversal-per-original constraint violation onto InvalidJournalException before
 * rethrowing anything else.
 */
public class LedgerPersistenceException extends RuntimeException {
    public LedgerPersistenceException(SQLException cause) {
        super(cause);
    }
}
