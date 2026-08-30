package com.corebanking.funds.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.*;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;

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

    @Test void rendersDebitAndCreditNormalBalances() {
        assertEquals(10_000, NormalBalance.DEBIT.toNatural(10_000));
        assertEquals(10_000, NormalBalance.CREDIT.toNatural(-10_000));
        assertEquals(-2_000, NormalBalance.CREDIT.toNatural(2_000));
        assertThrows(MonetaryOverflowException.class, () -> NormalBalance.CREDIT.toNatural(Long.MIN_VALUE));
    }

    @Test void bookRejectsMissingRequiredReferenceData() {
        var id = UUID.randomUUID();
        var legalEntityId = UUID.randomUUID();
        var timezone = ZoneId.of("Africa/Lagos");

        assertDoesNotThrow(() -> new Book(id, legalEntityId, NGN, timezone, "NG", "2026.1"));
        assertThrows(NullPointerException.class, () -> new Book(id, null, NGN, timezone, "NG", "2026.1"));
        assertThrows(NullPointerException.class, () -> new Book(id, legalEntityId, null, timezone, "NG", "2026.1"));
        assertThrows(NullPointerException.class, () -> new Book(id, legalEntityId, NGN, null, "NG", "2026.1"));
        assertThrows(IllegalArgumentException.class, () -> new Book(id, legalEntityId, NGN, timezone, "", "2026.1"));
        assertThrows(IllegalArgumentException.class, () -> new Book(id, legalEntityId, NGN, timezone, "NG", ""));
    }

    @Test void ledgerAccountRejectsMissingRequiredReferenceData() {
        var book = new Book(UUID.randomUUID(), UUID.randomUUID(), NGN, ZoneId.of("Africa/Lagos"), "NG", "2026.1");

        assertDoesNotThrow(() -> new LedgerAccount(
            UUID.randomUUID(), book, NGN, AccountClass.LIABILITY, NormalBalance.CREDIT, "CUSTOMER_DEPOSITS", AccountStatus.OPEN));
        assertThrows(NullPointerException.class, () -> new LedgerAccount(
            UUID.randomUUID(), null, NGN, AccountClass.LIABILITY, NormalBalance.CREDIT, "CUSTOMER_DEPOSITS", AccountStatus.OPEN));
        assertThrows(NullPointerException.class, () -> new LedgerAccount(
            UUID.randomUUID(), book, null, AccountClass.LIABILITY, NormalBalance.CREDIT, "CUSTOMER_DEPOSITS", AccountStatus.OPEN));
        assertThrows(NullPointerException.class, () -> new LedgerAccount(
            UUID.randomUUID(), book, NGN, null, NormalBalance.CREDIT, "CUSTOMER_DEPOSITS", AccountStatus.OPEN));
        assertThrows(NullPointerException.class, () -> new LedgerAccount(
            UUID.randomUUID(), book, NGN, AccountClass.LIABILITY, null, "CUSTOMER_DEPOSITS", AccountStatus.OPEN));
        assertThrows(IllegalArgumentException.class, () -> new LedgerAccount(
            UUID.randomUUID(), book, NGN, AccountClass.LIABILITY, NormalBalance.CREDIT, "", AccountStatus.OPEN));
        assertThrows(NullPointerException.class, () -> new LedgerAccount(
            UUID.randomUUID(), book, NGN, AccountClass.LIABILITY, NormalBalance.CREDIT, "CUSTOMER_DEPOSITS", null));
    }

    @Test void ledgerAccountUsesItsNormalDirectionForBookedBalance() {
        var book = new Book(UUID.randomUUID(), UUID.randomUUID(), NGN, ZoneId.of("Africa/Lagos"), "NG", "2026.1");
        var account = new LedgerAccount(
            UUID.randomUUID(), book, NGN, AccountClass.LIABILITY, NormalBalance.CREDIT, "CUSTOMER_DEPOSITS", AccountStatus.OPEN);

        assertEquals(2_500, account.bookedNaturalBalance(-2_500));
    }
}
