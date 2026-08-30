package com.corebanking.funds.domain;

public record Money(CurrencyCode currency, long minorUnits) {
    public static Money of(CurrencyCode currency, long minorUnits) {
        return new Money(currency, minorUnits);
    }

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
