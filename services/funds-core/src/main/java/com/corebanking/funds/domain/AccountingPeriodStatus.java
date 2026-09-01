package com.corebanking.funds.domain;

/**
 * Lifecycle of an accounting period. Only OPEN admits postings; CLOSING and CLOSED both fail
 * posting with AccountingPeriodClosedException. Transitions are not implemented in this slice.
 */
public enum AccountingPeriodStatus {
    OPEN, CLOSING, CLOSED
}
