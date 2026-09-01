package com.corebanking.funds.domain;

import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import java.util.Objects;

/**
 * Signed integer money in one ISO currency. The sign is the accounting side, not a customer
 * display sign: positive minor units are a debit, negative a credit (README, "Reading the
 * accounting model"). Never floating point, never rounded. Every operation is checked exact
 * arithmetic; a result outside the signed 64-bit range fails with MonetaryOverflowException
 * instead of wrapping or saturating, so the enclosing transaction rolls back.
 */
public record Money(CurrencyCode currency, long minorUnits) {
    public Money {
        currency = Objects.requireNonNull(currency, "currency");
    }

    public static Money of(CurrencyCode currency, long minorUnits) {
        return new Money(currency, minorUnits);
    }

    /** Throws IllegalArgumentException on a currency mismatch; conversion is out of scope. */
    public Money add(Money other) {
        requireSameCurrency(other);
        try {
            return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(e);
        }
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        try {
            return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(e);
        }
    }

    /**
     * Exact negation, the primitive behind reversals. Long.MIN_VALUE has no positive
     * counterpart, so negating it overflows; PostingLine rejects that amount at admission.
     */
    public Money negate() {
        try {
            return new Money(currency, Math.negateExact(minorUnits));
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(e);
        }
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch");
        }
    }
}
