package com.corebanking.funds.domain.exception;

import java.sql.SQLException;

/** A finite database lock or statement deadline expired. */
public class LedgerTimeoutException extends LedgerPersistenceException {
    public LedgerTimeoutException(SQLException cause) {
        super(cause);
    }
}
