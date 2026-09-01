package com.corebanking.funds.domain.exception;

import java.sql.SQLException;

/**
 * A finite database lock or statement deadline expired. Mapped from SQLSTATE 55P03
 * (lock_not_available) and 57014 (query_canceled), which the transaction-local lock and
 * statement timeouts (1s/3s by default) raise. The caller should infer contention or a slow
 * statement rather than a rejected journal; the transaction has rolled back and the command
 * was not applied.
 */
public class LedgerTimeoutException extends LedgerPersistenceException {
    public LedgerTimeoutException(SQLException cause) {
        super(cause);
    }
}
