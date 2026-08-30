package com.corebanking.funds.domain;

import java.util.Objects;
import java.util.UUID;

public record LedgerAccount(
    UUID id,
    Book book,
    CurrencyCode currency,
    AccountClass accountClass,
    NormalBalance normalBalance,
    String controlAccountCode,
    AccountStatus status) {

    public LedgerAccount {
        id = Objects.requireNonNull(id, "id");
        book = Objects.requireNonNull(book, "book");
        currency = Objects.requireNonNull(currency, "currency");
        accountClass = Objects.requireNonNull(accountClass, "accountClass");
        normalBalance = Objects.requireNonNull(normalBalance, "normalBalance");
        controlAccountCode = requireNonBlank(controlAccountCode, "controlAccountCode");
        status = Objects.requireNonNull(status, "status");
    }

    public long bookedNaturalBalance(long signedTotal) {
        return normalBalance.toNatural(signedTotal);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
