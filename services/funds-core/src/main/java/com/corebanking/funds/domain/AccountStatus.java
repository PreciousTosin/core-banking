package com.corebanking.funds.domain;

/**
 * Ledger-account lifecycle. The posting path only accepts OPEN accounts; the blocked states
 * are stored and constrained but one-sided blocking is not yet enforced in this slice.
 */
public enum AccountStatus {
    OPEN, DEBIT_BLOCKED, CREDIT_BLOCKED, CLOSED
}
