package com.corebanking.funds.domain.exception;

/** A monotonic ledger coordinate has exhausted its signed 64-bit storage domain. */
public final class LedgerCapacityException extends RuntimeException {
    public LedgerCapacityException(String coordinate, ArithmeticException cause) {
        super(coordinate + " exhausted its bigint capacity", cause);
    }
}
