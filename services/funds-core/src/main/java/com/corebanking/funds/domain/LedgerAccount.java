package com.corebanking.funds.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The balance-bearing financial identity. Postings, locks and materialised balances key on
 * this UUID; addresses such as NUBANs only resolve to it. Currency is fixed for life and
 * every posting must match it; controlAccountCode groups the account into the per-book,
 * per-currency control projection used by the independent proofs.
 */
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

    /** Signed posting total as the balance this account's normal side would report. */
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
