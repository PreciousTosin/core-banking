package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.testsupport.PropertyCases;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalProperties {
    private static final long SEED = 0xCB20260830L;
    private static final int RANDOM_CASES = 2_000;
    private static final UUID ASSET_ACCOUNT = uuid(1);
    private static final UUID LIABILITY_ACCOUNT = uuid(2);
    private final JournalValidator validator = new JournalValidator();

    @Test
    void addingEqualDebitAndCreditAlwaysBalances() {
        PropertyCases.positiveMinorUnits(SEED, RANDOM_CASES).forEach(amount -> {
            var draft = fixtureJournal(
                line(uuid(101), ASSET_ACCOUNT, amount),
                line(uuid(102), LIABILITY_ACCOUNT, -amount));

            assertDoesNotThrow(
                () -> validator.validate(draft),
                () -> caseLabel(amount));
        });
    }

    @Test
    void changingOneSideByOneMinorUnitAlwaysFails() {
        PropertyCases.positiveMinorUnits(SEED, RANDOM_CASES).forEach(amount -> {
            if (amount == 1) {
                assertThrows(
                    IllegalArgumentException.class,
                    () -> validator.validate(fixtureJournal(
                        line(uuid(101), ASSET_ACCOUNT, amount),
                        line(uuid(102), LIABILITY_ACCOUNT, -amount + 1))),
                    () -> caseLabel(amount));
                return;
            }

            var draft = fixtureJournal(
                line(uuid(101), ASSET_ACCOUNT, amount),
                line(uuid(102), LIABILITY_ACCOUNT, -amount + 1));
            assertThrows(
                InvalidJournalException.class,
                () -> validator.validate(draft),
                () -> caseLabel(amount));
        });
    }

    @Test
    void positiveMinorUnitsContainsExactBoundariesThenReproducibleRandomCases() {
        var first = PropertyCases.positiveMinorUnits(SEED, RANDOM_CASES).toArray();
        var second = PropertyCases.positiveMinorUnits(SEED, RANDOM_CASES).toArray();

        assertEquals(2_007, first.length);
        assertArrayEquals(new long[] {
            1,
            2,
            99,
            100,
            1_000_000_000,
            Long.MAX_VALUE / 2,
            Long.MAX_VALUE - 1
        }, Arrays.copyOf(first, 7));
        assertArrayEquals(first, second);
        assertTrue(Arrays.stream(first, 7, first.length)
            .allMatch(value -> value >= 1 && value < 1_000_000_001L));
    }

    private static String caseLabel(long amount) {
        return "seed=0xCB20260830L, amount=" + amount;
    }

    private static JournalDraft fixtureJournal(PostingLine... postings) {
        return new JournalDraft(
            uuid(10),
            uuid(11),
            uuid(12),
            uuid(13),
            uuid(14),
            uuid(15),
            uuid(16),
            uuid(17),
            "PROPERTY_TEST",
            "Seeded journal property",
            Instant.parse("2026-08-30T00:00:00Z"),
            LocalDate.of(2026, 8, 30),
            null,
            1,
            List.of(postings));
    }

    private static PostingLine line(UUID postingId, UUID accountId, long amount) {
        return new PostingLine(
            postingId, accountId, CurrencyCode.of("NGN"), amount, 0, Map.of("source", "property"));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
