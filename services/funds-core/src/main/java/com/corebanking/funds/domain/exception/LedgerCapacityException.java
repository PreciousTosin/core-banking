package com.corebanking.funds.domain.exception;

/**
 * A monotonic ledger coordinate has exhausted its signed 64-bit storage domain. Raised for the
 * per-account posting sequence and the materialised-balance version, never for a money
 * amount (that is MonetaryOverflowException). The transaction rolls back; nothing wraps.
 */
public final class LedgerCapacityException extends RuntimeException {
    public LedgerCapacityException(String coordinate, ArithmeticException cause) {
        super(coordinate + " exhausted its bigint capacity", cause);
    }
}
