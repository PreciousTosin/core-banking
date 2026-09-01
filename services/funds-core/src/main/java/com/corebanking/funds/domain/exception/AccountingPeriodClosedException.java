package com.corebanking.funds.domain.exception;

import java.util.UUID;

/**
 * The period the journal explicitly named is not OPEN (CLOSING or CLOSED), checked under a
 * shared period lock at commit. Deterministic: resubmitting the same journal will not help;
 * the caller must target an open period, as a reversal does through currentPeriodId.
 */
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
