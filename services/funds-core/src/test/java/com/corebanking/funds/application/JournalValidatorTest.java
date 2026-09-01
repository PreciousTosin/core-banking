package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalValidatorTest {
    private static final UUID JOURNAL_ID = uuid(1);
    private static final UUID COMMAND_ID = uuid(2);
    private static final UUID CORRELATION_ID = uuid(3);
    private static final UUID BUSINESS_TRANSACTION_ID = uuid(4);
    private static final UUID LEGAL_ENTITY_ID = uuid(5);
    private static final UUID BOOK_ID = uuid(6);
    private static final UUID CHART_VERSION_ID = uuid(7);
    private static final UUID PERIOD_ID = uuid(70);
    private static final UUID ASSET_ACCOUNT = uuid(8);
    private static final UUID CUSTOMER_LIABILITY = uuid(9);
    private static final UUID USD_POSITION = uuid(10);
    private static final UUID NGN_POSITION = uuid(11);
    private static final UUID POSTING_A = uuid(12);
    private static final UUID POSTING_B = uuid(13);
    private static final Instant BOOKING_TIME = Instant.parse("2026-08-30T14:15:16.123456Z");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 30);

    private final JournalValidator validator = new JournalValidator();
    private final CanonicalJournalHasher hasher = new CanonicalJournalHasher();

    @Test
    void acceptsBalancedSingleCurrencyJournal() {
        var draft = fixtureJournal(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_000),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100_000));

        assertDoesNotThrow(() -> validator.validate(draft));
    }

    @Test
    void acceptsMicrosecondBookingTimeAndRejectsSubMicrosecondPrecision() {
        var postings = List.of(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100));
        var microsecond = journal(
            JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "microsecond",
            Instant.parse("2026-08-30T14:15:16.123456Z"), VALUE_DATE, null, 41, postings);
        var subMicrosecond = journal(
            JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "nanosecond",
            Instant.parse("2026-08-30T14:15:16.123456001Z"), VALUE_DATE, null, 41, postings);

        assertDoesNotThrow(() -> validator.validate(microsecond));
        assertThrows(InvalidJournalException.class, () -> validator.validate(subMicrosecond));
    }

    @Test
    void rejectsPerCurrencyImbalance() {
        var draft = fixtureJournal(
            line(POSTING_A, USD_POSITION, "USD", 1_000),
            line(POSTING_B, NGN_POSITION, "NGN", -1_000));

        assertThrows(InvalidJournalException.class, () -> validator.validate(draft));
    }

    @Test
    void hashIsIndependentOfInputLineOrder() {
        var a = line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_000);
        var b = line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100_000);

        assertEquals(hasher.sha256(fixtureJournal(a, b)), hasher.sha256(fixtureJournal(b, a)));
    }

    @Test
    void rejectsEmptyJournal() {
        assertThrows(IllegalArgumentException.class, () -> fixtureJournal(List.of()));
    }

    @Test
    void rejectsZeroPosting() {
        assertThrows(IllegalArgumentException.class,
            () -> line(POSTING_A, ASSET_ACCOUNT, "NGN", 0));
    }

    @Test
    void rejectsAmountsThatCannotBeExactlyReversed() {
        assertThrows(IllegalArgumentException.class,
            () -> line(POSTING_A, ASSET_ACCOUNT, "NGN", Long.MIN_VALUE));
    }

    @Test
    void rejectsJournalAndDimensionInputsBeyondTheReversalEnvelope() {
        var tooManyPostings = new ArrayList<PostingLine>();
        for (int index = 0; index <= JournalValidator.MAX_POSTINGS_PER_JOURNAL; index++) {
            tooManyPostings.add(line(uuid(1_000 + index), ASSET_ACCOUNT, "NGN", index % 2 == 0 ? 1 : -1));
        }
        assertThrows(InvalidJournalException.class,
            () -> validator.validate(fixtureJournal(tooManyPostings)));

        var tooManyDimensions = new LinkedHashMap<String, String>();
        for (int index = 0; index <= JournalValidator.MAX_DIMENSIONS_PER_POSTING; index++) {
            tooManyDimensions.put("key-" + index, "value-" + index);
        }
        assertThrows(InvalidJournalException.class, () -> validator.validate(fixtureJournal(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100, tooManyDimensions),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100))));

        var oversizedDimensions = Map.of("memo", "x".repeat(JournalValidator.MAX_DIMENSION_JSON_BYTES));
        assertThrows(InvalidJournalException.class, () -> validator.validate(fixtureJournal(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100, oversizedDimensions),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100))));
    }

    @Test
    void rejectsDuplicatePostingIdentity() {
        var draft = fixtureJournal(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_000),
            line(POSTING_A, CUSTOMER_LIABILITY, "NGN", -100_000));

        assertThrows(InvalidJournalException.class, () -> validator.validate(draft));
    }

    @Test
    void rejectsArithmeticOverflowWhileSummingCurrency() {
        var draft = fixtureJournal(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", Long.MAX_VALUE),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", 1));

        assertThrows(MonetaryOverflowException.class, () -> validator.validate(draft));
    }

    @Test
    void rejectsMissingPostingAndAccountIdentities() {
        var missingPostingId = fixtureJournal(
            line(null, ASSET_ACCOUNT, "NGN", 100),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100));
        var missingAccountId = fixtureJournal(
            line(POSTING_A, null, "NGN", 100),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100));

        assertThrows(InvalidJournalException.class, () -> validator.validate(missingPostingId));
        assertThrows(InvalidJournalException.class, () -> validator.validate(missingAccountId));
    }

    @Test
    void hashChangesForEveryFinanciallyMeaningfulJournalField() {
        var postings = List.of(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_000, Map.of("channel", "nip", "region", "ng")),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100_000));
        var baseline = journal(
            JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID, LEGAL_ENTITY_ID,
            BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings);

        var hashes = new ArrayList<String>();
        hashes.add(hasher.sha256(baseline));
        hashes.add(hasher.sha256(journal(uuid(101), COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, uuid(102), CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, uuid(103), BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, uuid(104),
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            uuid(105), BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, uuid(106), PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(new JournalDraft(
            JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID, LEGAL_ENTITY_ID,
            BOOK_ID, uuid(1_006), PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, uuid(107), "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_DEBIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Different narration", BOOKING_TIME,
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME.plusNanos(1),
            VALUE_DATE, null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE.plusDays(1), null, 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, uuid(108), 41, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 42, postings)));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, List.of(
                line(uuid(109), ASSET_ACCOUNT, "NGN", 100_000, Map.of("channel", "nip", "region", "ng")),
                postings.get(1)))));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, List.of(
                line(POSTING_A, uuid(110), "NGN", 100_000, Map.of("channel", "nip", "region", "ng")),
                postings.get(1)))));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, List.of(
                line(POSTING_A, ASSET_ACCOUNT, "USD", 100_000, Map.of("channel", "nip", "region", "ng")),
                postings.get(1)))));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, List.of(
                line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_001, Map.of("channel", "nip", "region", "ng")),
                postings.get(1)))));
        hashes.add(hasher.sha256(journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Crédit received", BOOKING_TIME,
            VALUE_DATE, null, 41, List.of(
                line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_000, Map.of("channel", "branch", "region", "ng")),
                postings.get(1)))));

        assertEquals(hashes.size(), new HashSet<>(hashes).size(), "every financial mutation must change the hash");
        assertTrue(hashes.stream().allMatch(hash -> hash.matches("[0-9a-f]{64}")));
    }

    @Test
    void hashExcludesDatabaseAssignedAccountSequence() {
        var first = new PostingLine(POSTING_A, ASSET_ACCOUNT, CurrencyCode.of("NGN"), 100_000, 0, Map.of());
        var second = new PostingLine(POSTING_A, ASSET_ACCOUNT, CurrencyCode.of("NGN"), 100_000, 99, Map.of());

        assertEquals(
            hasher.sha256(fixtureJournal(first, line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100_000))),
            hasher.sha256(fixtureJournal(second, line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100_000))));
    }

    @Test
    void hashSortsOpposingDimensionInsertionOrdersAndConstructorsCopyMutableCollections() {
        var dimensionsA = new LinkedHashMap<String, String>();
        dimensionsA.put("region", "ng");
        dimensionsA.put("channel", "nip");
        var dimensionsB = new LinkedHashMap<String, String>();
        dimensionsB.put("channel", "nip");
        dimensionsB.put("region", "ng");
        var postings = new ArrayList<PostingLine>();
        var a = line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_000, dimensionsA);
        postings.add(a);
        postings.add(line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100_000));
        var draft = fixtureJournal(postings);
        var sameContent = fixtureJournal(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100_000, dimensionsB),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100_000));

        dimensionsA.put("mutable", "must-not-leak");
        postings.clear();

        assertEquals(hasher.sha256(sameContent), hasher.sha256(draft));
        assertThrows(UnsupportedOperationException.class, () -> a.dimensions().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> draft.postings().clear());
    }

    @Test
    void hashUsesPostingIdentityAsTheTieBreakForOneAccount() {
        var first = line(uuid(301), ASSET_ACCOUNT, "NGN", 40);
        var second = line(uuid(302), ASSET_ACCOUNT, "NGN", 60);
        var credit = line(uuid(303), CUSTOMER_LIABILITY, "NGN", -100);

        assertEquals(
            hasher.sha256(fixtureJournal(first, second, credit)),
            hasher.sha256(fixtureJournal(second, credit, first)));
        assertNotEquals(
            hasher.sha256(fixtureJournal(first, second, credit)),
            hasher.sha256(fixtureJournal(
                line(uuid(304), ASSET_ACCOUNT, "NGN", 40), second, credit)));
    }

    @Test
    void reversalPresenceAndValueAreBothCanonical() {
        var postings = List.of(
            line(POSTING_A, ASSET_ACCOUNT, "NGN", 100),
            line(POSTING_B, CUSTOMER_LIABILITY, "NGN", -100));
        var absent = journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "REVERSAL", "reason", BOOKING_TIME,
            VALUE_DATE, null, 41, postings);
        var first = journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "REVERSAL", "reason", BOOKING_TIME,
            VALUE_DATE, uuid(201), 41, postings);
        var second = journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "REVERSAL", "reason", BOOKING_TIME,
            VALUE_DATE, uuid(202), 41, postings);

        assertNotEquals(hasher.sha256(absent), hasher.sha256(first));
        assertNotEquals(hasher.sha256(first), hasher.sha256(second));
    }

    private static JournalDraft fixtureJournal(PostingLine... postings) {
        return fixtureJournal(List.of(postings));
    }

    private static JournalDraft fixtureJournal(List<PostingLine> postings) {
        return journal(JOURNAL_ID, COMMAND_ID, CORRELATION_ID, BUSINESS_TRANSACTION_ID,
            LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID, "CUSTOMER_CREDIT", "Customer deposit",
            BOOKING_TIME, VALUE_DATE, null, 41, postings);
    }

    private static JournalDraft journal(
        UUID journalId,
        UUID commandId,
        UUID correlationId,
        UUID businessTransactionId,
        UUID legalEntityId,
        UUID bookId,
        UUID periodId,
        String transactionType,
        String narration,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOfJournalId,
        int policyVersion,
        List<PostingLine> postings) {
        return new JournalDraft(
            journalId, commandId, correlationId, businessTransactionId, legalEntityId, bookId,
            CHART_VERSION_ID, periodId, transactionType, narration, bookingTime, valueDate, reversalOfJournalId,
            policyVersion, postings);
    }

    private static PostingLine line(UUID postingId, UUID accountId, String currency, long amount) {
        return line(postingId, accountId, currency, amount, Map.of());
    }

    private static PostingLine line(
        UUID postingId,
        UUID accountId,
        String currency,
        long amount,
        Map<String, String> dimensions) {
        return new PostingLine(postingId, accountId, CurrencyCode.of(currency), amount, 0, dimensions);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
