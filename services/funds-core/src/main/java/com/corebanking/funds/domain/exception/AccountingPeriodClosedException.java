package com.corebanking.funds.domain.exception;

import java.util.UUID;

public class AccountingPeriodClosedException extends RuntimeException {
    private final UUID periodId;

    public AccountingPeriodClosedException(UUID periodId) {
        super("accounting period is not open: " + periodId);
        this.periodId = periodId;
    }

    public UUID periodId() {
        return periodId;
    }
}
