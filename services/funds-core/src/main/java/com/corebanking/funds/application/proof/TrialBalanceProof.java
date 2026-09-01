package com.corebanking.funds.application.proof;

import com.corebanking.funds.domain.CurrencyCode;
import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of one trial-balance proof. totalDebits and totalCredits are the absolute sums of the
 * positive and negative signed postings (README sign convention) for a book and currency up to
 * a journal_sequence cutoff, held as BigInteger because the SQL aggregates in numeric. A false
 * balanced flag means persisted postings no longer sum to zero for that currency.
 */
public record TrialBalanceProof(
    UUID bookId,
    CurrencyCode currency,
    long cutoff,
    BigInteger totalDebits,
    BigInteger totalCredits,
    boolean balanced
) {
    public TrialBalanceProof {
        bookId = Objects.requireNonNull(bookId, "bookId");
        currency = Objects.requireNonNull(currency, "currency");
        totalDebits = Objects.requireNonNull(totalDebits, "totalDebits");
        totalCredits = Objects.requireNonNull(totalCredits, "totalCredits");
        requireNonNegative(cutoff, "cutoff");
        requireNonNegative(totalDebits, "totalDebits");
        requireNonNegative(totalCredits, "totalCredits");
        if (balanced != totalDebits.equals(totalCredits)) {
            throw new IllegalArgumentException("balanced must equal totalDebits == totalCredits");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireNonNegative(BigInteger value, String name) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
