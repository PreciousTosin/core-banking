package com.corebanking.funds.domain.exception;

import java.sql.SQLException;

public class LedgerPersistenceException extends RuntimeException {
    public LedgerPersistenceException(SQLException cause) {
        super(cause);
    }
}
