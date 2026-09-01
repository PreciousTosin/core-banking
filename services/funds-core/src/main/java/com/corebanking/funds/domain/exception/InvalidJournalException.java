package com.corebanking.funds.domain.exception;

/**
 * The journal is rejected on its own merits: unbalanced, over the 256/32/8192 limits, aimed
 * at a closed account, wrong book, inactive chart or stale policy, or using reversal metadata
 * on the wrong path. Deterministic for the same content; the transaction has rolled back.
 */
public class InvalidJournalException extends RuntimeException {
    public InvalidJournalException(String message) {
        super(message);
    }
}
