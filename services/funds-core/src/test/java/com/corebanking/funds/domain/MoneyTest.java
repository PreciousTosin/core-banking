package com.corebanking.funds.domain;

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

    @Test void rejectsMissingCurrencyAtConstructionBoundary() {
        assertThrows(NullPointerException.class, () -> Money.of(null, 100));
        assertThrows(NullPointerException.class, () -> new Money(null, 100));
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

        assertDoesNotThrow(() -> new Book(id, legalEntityId, NGN, timezone, "NG", 1));
        assertThrows(NullPointerException.class, () -> new Book(id, null, NGN, timezone, "NG", 1));
        assertThrows(NullPointerException.class, () -> new Book(id, legalEntityId, null, timezone, "NG", 1));
        assertThrows(NullPointerException.class, () -> new Book(id, legalEntityId, NGN, null, "NG", 1));
        assertThrows(IllegalArgumentException.class, () -> new Book(id, legalEntityId, NGN, timezone, "", 1));
    }

    @Test void bookRequiresPositivePolicyVersion() {
        var id = UUID.randomUUID();
        var legalEntityId = UUID.randomUUID();
        var timezone = ZoneId.of("Africa/Lagos");

        assertDoesNotThrow(() -> new Book(id, legalEntityId, NGN, timezone, "NG", 1));
        assertThrows(IllegalArgumentException.class, () -> new Book(id, legalEntityId, NGN, timezone, "NG", 0));
        assertThrows(IllegalArgumentException.class, () -> new Book(id, legalEntityId, NGN, timezone, "NG", -1));
    }

    @Test void ledgerAccountRejectsMissingRequiredReferenceData() {
        var book = new Book(UUID.randomUUID(), UUID.randomUUID(), NGN, ZoneId.of("Africa/Lagos"), "NG", 1);

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
        var book = new Book(UUID.randomUUID(), UUID.randomUUID(), NGN, ZoneId.of("Africa/Lagos"), "NG", 1);
        var account = new LedgerAccount(
            UUID.randomUUID(), book, NGN, AccountClass.LIABILITY, NormalBalance.CREDIT, "CUSTOMER_DEPOSITS", AccountStatus.OPEN);

        assertEquals(2_500, account.bookedNaturalBalance(-2_500));
    }

    @Test void productDefinitionRequiresIdentifierAndCode() {
        assertDoesNotThrow(() -> new ProductDefinition(UUID.randomUUID(), "SAVINGS_STANDARD"));
        assertThrows(NullPointerException.class, () -> new ProductDefinition(null, "SAVINGS_STANDARD"));
        assertThrows(IllegalArgumentException.class, () -> new ProductDefinition(UUID.randomUUID(), ""));
    }

    @Test void productVersionRequiresReferenceTermsAndValidInterval() {
        var product = new ProductDefinition(UUID.randomUUID(), "SAVINGS_STANDARD");
        var start = java.time.Instant.parse("2026-01-01T00:00:00Z");
        var end = java.time.Instant.parse("2026-12-31T23:59:59Z");
        var hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        assertDoesNotThrow(() -> new ProductVersion(
            UUID.randomUUID(), product, 1, DepositProductKind.SAVINGS, FinancePrinciple.CONVENTIONAL,
            start, end, "APP-2026-001", hash));
        assertThrows(NullPointerException.class, () -> new ProductVersion(
            UUID.randomUUID(), product, 1, null, FinancePrinciple.CONVENTIONAL, start, end, "APP-2026-001", hash));
        assertThrows(NullPointerException.class, () -> new ProductVersion(
            UUID.randomUUID(), product, 1, DepositProductKind.SAVINGS, null, start, end, "APP-2026-001", hash));
        assertThrows(IllegalArgumentException.class, () -> new ProductVersion(
            UUID.randomUUID(), product, 0, DepositProductKind.SAVINGS, FinancePrinciple.CONVENTIONAL,
            start, end, "APP-2026-001", hash));
        assertThrows(IllegalArgumentException.class, () -> new ProductVersion(
            UUID.randomUUID(), product, 1, DepositProductKind.SAVINGS, FinancePrinciple.CONVENTIONAL,
            start, start, "APP-2026-001", hash));
        assertThrows(IllegalArgumentException.class, () -> new ProductVersion(
            UUID.randomUUID(), product, 1, DepositProductKind.SAVINGS, FinancePrinciple.CONVENTIONAL,
            start, end, "", hash));
        assertThrows(IllegalArgumentException.class, () -> new ProductVersion(
            UUID.randomUUID(), product, 1, DepositProductKind.SAVINGS, FinancePrinciple.CONVENTIONAL,
            start, end, "APP-2026-001", "not-a-64-character-hash"));
    }
}
