package com.corebanking.funds.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MoneyTest {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Test void addsOnlySameCurrency() {
        assertEquals(Money.of(NGN, 150), Money.of(NGN, 100).add(Money.of(NGN, 50)));
        assertThrows(IllegalArgumentException.class,
            () -> Money.of(NGN, 100).add(Money.of(CurrencyCode.of("USD"), 50)));
    }

    @Test void rejectsOverflowInsteadOfWrapping() {
        assertThrows(MonetaryOverflowException.class,
            () -> Money.of(NGN, Long.MAX_VALUE).add(Money.of(NGN, 1)));
        assertThrows(MonetaryOverflowException.class,
            () -> Money.of(NGN, Long.MIN_VALUE).negate());
    }

    @Test void currencyCodeIsCanonical() {
        assertEquals("NGN", CurrencyCode.of("ngn").value());
        assertThrows(IllegalArgumentException.class, () -> CurrencyCode.of("NAIRA"));
    }
}
