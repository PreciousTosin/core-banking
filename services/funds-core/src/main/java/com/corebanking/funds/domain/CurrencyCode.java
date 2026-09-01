package com.corebanking.funds.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Three-letter ISO 4217 currency code, upper-cased on construction. The database stores the
 * same shape (char(3) with an ^[A-Z]{3}$ CHECK) on book, ledger account and posting rows.
 */
public record CurrencyCode(String value) {
    public CurrencyCode {
        value = Objects.requireNonNull(value, "currency").toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be three letters");
        }
    }

    public static CurrencyCode of(String value) {
        return new CurrencyCode(value);
    }
}
