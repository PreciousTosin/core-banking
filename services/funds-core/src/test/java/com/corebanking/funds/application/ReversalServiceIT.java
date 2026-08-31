package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.ReversalRequest;
import com.corebanking.funds.domain.exception.AccountingPeriodClosedException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReversalServiceIT {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");
    private static final UUID ORIGINAL_COMMAND_ID = TestPostingStack.uuid(200);
    private static final UUID ORIGINAL_JOURNAL_ID = TestPostingStack.uuid(201);
    private static final UUID REVERSAL_COMMAND_ID = TestPostingStack.uuid(210);
    private static final UUID NEXT_PERIOD_ID = TestPostingStack.uuid(211);
    private static final Instant REVERSAL_BOOKING_TIME = Instant.parse("2026-02-15T09:30:00Z");
    private static final LocalDate REVERSAL_VALUE_DATE = LocalDate.of(2026, 2, 15);

    @Inject
    DataSource dataSource;

    @Inject
    PostingService postingService;

    @Inject
    ReversalService reversalService;

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void reversesClosedPeriodJournalInCurrentPeriodWithoutChangingOriginal() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        JournalSnapshot originalBefore = journalSnapshot(ORIGINAL_JOURNAL_ID);
        closeOriginalAndOpenNextPeriod();

        PostingResult first = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            "b".repeat(64)));
        PostingResult replay = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            "b".repeat(64)));

        JournalSnapshot reversal = journalSnapshot(first.journalId());
        JournalSnapshot originalAfter = journalSnapshot(ORIGINAL_JOURNAL_ID);
        assertAll(
            () -> assertEquals(first, replay),
            () -> assertNotEquals(ORIGINAL_JOURNAL_ID, first.journalId()),
            () -> assertEquals(originalBefore, originalAfter),
            () -> assertEquals(NEXT_PERIOD_ID, reversal.periodId()),
            () -> assertEquals(REVERSAL_BOOKING_TIME, reversal.bookingTime()),
            () -> assertEquals(REVERSAL_VALUE_DATE, reversal.valueDate()),
            () -> assertEquals(ORIGINAL_JOURNAL_ID, reversal.reversalOfJournalId()),
            () -> assertEquals("REVERSAL", reversal.transactionType()),
            () -> assertEquals("Customer-requested correction", reversal.narration()),
            () -> assertEquals(TestPostingStack.uuid(212), reversal.correlationId()),
            () -> assertEquals(TestPostingStack.uuid(213), reversal.businessTransactionId()),
            () -> assertExactNegations(originalBefore.postings(), reversal.postings()),
            () -> assertEquals(2, count("funds.journal")),
            () -> assertEquals(4, count("funds.posting")),
            () -> assertEquals(2, count("funds.idempotency_command")),
            () -> assertEquals(2, count("funds.outbox_event")));
    }

    @Test
    void explicitReversalValueDateMustBelongToCurrentPeriod() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        ReversalRequest outsideCurrentPeriod = new ReversalRequest(
            REVERSAL_COMMAND_ID,
            "c".repeat(64),
            ORIGINAL_JOURNAL_ID,
            TestPostingStack.uuid(212),
            TestPostingStack.uuid(213),
            NEXT_PERIOD_ID,
            REVERSAL_BOOKING_TIME,
            LocalDate.of(2026, 1, 31),
            "Explicit date outside current period");

        assertThrows(InvalidJournalException.class, () -> reversalService.reverse(outsideCurrentPeriod));

        assertAll(
            () -> assertEquals(1, count("funds.journal")),
            () -> assertEquals(2, count("funds.posting")),
            () -> assertEquals(1, count("funds.outbox_event")));
    }

    @Test
    void ordinaryPostingToClosedPeriodRemainsRejected() throws SQLException {
        closeOriginalAndOpenNextPeriod();

        assertThrows(
            AccountingPeriodClosedException.class,
            () -> postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID)));

        assertAll(
            () -> assertEquals(0, count("funds.journal")),
            () -> assertEquals(0, count("funds.posting")),
            () -> assertEquals(0, count("funds.outbox_event")));
    }

    @Test
    void rejectsReversalOfReversalWithoutWritingAnything() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        PostingResult firstReversal = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            "d".repeat(64)));
        DatabaseCounts before = databaseCounts();

        assertThrows(
            InvalidJournalException.class,
            () -> reversalService.reverse(new ReversalRequest(
                TestPostingStack.uuid(220),
                "e".repeat(64),
                firstReversal.journalId(),
                TestPostingStack.uuid(221),
                TestPostingStack.uuid(222),
                NEXT_PERIOD_ID,
                REVERSAL_BOOKING_TIME.plusSeconds(60),
                REVERSAL_VALUE_DATE,
                "Attempted reversal chain")));

        assertEquals(before, databaseCounts());
    }

    @Test
    void longMinimumNegationIsRejectedAtomically() throws SQLException {
        UUID thirdAccount = TestPostingStack.uuid(209);
        seedThirdAccountAndZeroProjections(thirdAccount);
        PostingCommand extreme = command(new JournalDraft(
            ORIGINAL_JOURNAL_ID,
            ORIGINAL_COMMAND_ID,
            TestPostingStack.uuid(202),
            TestPostingStack.uuid(203),
            TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID,
            TestPostingStack.PERIOD_ID,
            "EXTREME_BALANCED",
            "Balanced journal containing Long.MIN_VALUE",
            Instant.parse("2026-01-20T10:00:00Z"),
            LocalDate.of(2026, 1, 20),
            null,
            1,
            List.of(
                new PostingLine(TestPostingStack.uuid(204), TestPostingStack.PROVIDER_ASSET,
                    NGN, Long.MIN_VALUE, 0, Map.of("case", "minimum")),
                new PostingLine(TestPostingStack.uuid(205), TestPostingStack.CUSTOMER_LIABILITY,
                    NGN, Long.MAX_VALUE, 0, Map.of("case", "maximum")),
                new PostingLine(TestPostingStack.uuid(206), thirdAccount,
                    NGN, 1, 0, Map.of("case", "unit")))));
        postingService.post(extreme);
        closeOriginalAndOpenNextPeriod();
        JournalSnapshot originalBefore = journalSnapshot(ORIGINAL_JOURNAL_ID);
        DatabaseCounts countsBefore = databaseCounts();

        assertThrows(
            MonetaryOverflowException.class,
            () -> reversalService.reverse(reversalRequest(
                REVERSAL_COMMAND_ID,
                ORIGINAL_JOURNAL_ID,
                "f".repeat(64))));

        assertAll(
            () -> assertEquals(originalBefore, journalSnapshot(ORIGINAL_JOURNAL_ID)),
            () -> assertEquals(countsBefore, databaseCounts()),
            () -> assertEquals(0, queryLong("""
                SELECT count(*) FROM funds.idempotency_command WHERE command_id = ?
                """, REVERSAL_COMMAND_ID)));
    }

    private PostingCommand exampleA(UUID commandId, UUID journalId) {
        return command(new JournalDraft(
            journalId,
            commandId,
            TestPostingStack.uuid(202),
            TestPostingStack.uuid(203),
            TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID,
            TestPostingStack.PERIOD_ID,
            "PROVIDER_INFLOW",
            "Example A provider inflow",
            Instant.parse("2026-01-15T10:00:00Z"),
            LocalDate.of(2026, 1, 15),
            null,
            1,
            List.of(
                new PostingLine(TestPostingStack.uuid(204), TestPostingStack.PROVIDER_ASSET,
                    NGN, 100_000, 0, Map.of("rail", "provider", "route", "nibss")),
                new PostingLine(TestPostingStack.uuid(205), TestPostingStack.CUSTOMER_LIABILITY,
                    NGN, -100_000, 0, Map.of("customer", "example-a")))));
    }

    private static PostingCommand command(JournalDraft journal) {
        return new PostingCommand(
            journal.commandId(),
            new CanonicalJournalHasher().sha256(journal),
            journal);
    }

    private static ReversalRequest reversalRequest(UUID commandId, UUID originalJournalId, String requestHash) {
        return new ReversalRequest(
            commandId,
            requestHash,
            originalJournalId,
            TestPostingStack.uuid(212),
            TestPostingStack.uuid(213),
            NEXT_PERIOD_ID,
            REVERSAL_BOOKING_TIME,
            REVERSAL_VALUE_DATE,
            "Customer-requested correction");
    }

    private void closeOriginalAndOpenNextPeriod() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            TestPostingStack.execute(connection, """
                UPDATE funds.accounting_period SET status = 'CLOSED' WHERE period_id = ?
                """, TestPostingStack.PERIOD_ID);
            TestPostingStack.execute(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES (?, ?, DATE '2026-02-01', DATE '2026-02-28', 'OPEN')
                """, NEXT_PERIOD_ID, TestPostingStack.BOOK_ID);
        }
    }

    private void seedThirdAccountAndZeroProjections(UUID accountId) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            TestPostingStack.execute(connection, """
                INSERT INTO funds.ledger_account
                    (account_id, book_id, chart_version_id, account_code, account_scope,
                     account_class, normal_balance, currency, control_account_code, status, created_at)
                VALUES (?, ?, ?, 'ROUNDING-ASSET', 'INTERNAL', 'ASSET', 'DEBIT', 'NGN',
                        ?, 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, accountId, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PROVIDER_CONTROL);
            TestPostingStack.execute(connection, """
                UPDATE funds.materialised_balance
                SET signed_posting_total = 0, latest_account_sequence = 0, version = 0
                """);
            TestPostingStack.execute(connection, "DELETE FROM funds.control_account_projection");
        }
    }

    private static void assertExactNegations(List<PostingSnapshot> original, List<PostingSnapshot> reversal) {
        assertEquals(original.size(), reversal.size());
        for (int index = 0; index < original.size(); index++) {
            PostingSnapshot source = original.get(index);
            PostingSnapshot correction = reversal.get(index);
            assertAll(
                () -> assertNotEquals(source.postingId(), correction.postingId()),
                () -> assertEquals(source.accountId(), correction.accountId()),
                () -> assertEquals(source.currency(), correction.currency()),
                () -> assertEquals(Math.negateExact(source.signedMinorUnits()), correction.signedMinorUnits()),
                () -> assertEquals(source.dimensionsJson(), correction.dimensionsJson()),
                () -> assertEquals(source.accountSequence() + 1, correction.accountSequence()));
        }
    }

    private JournalSnapshot journalSnapshot(UUID journalId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                 SELECT command_id, correlation_id, business_transaction_id, legal_entity_id,
                        book_id, period_id, transaction_type, narration, booking_time, value_date,
                        reversal_of_journal_id, policy_version, canonical_hash
                 FROM funds.journal
                 WHERE journal_id = ?
                 """)) {
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new JournalSnapshot(
                    journalId,
                    rows.getObject("command_id", UUID.class),
                    rows.getObject("correlation_id", UUID.class),
                    rows.getObject("business_transaction_id", UUID.class),
                    rows.getObject("legal_entity_id", UUID.class),
                    rows.getObject("book_id", UUID.class),
                    rows.getObject("period_id", UUID.class),
                    rows.getString("transaction_type"),
                    rows.getString("narration"),
                    rows.getObject("booking_time", OffsetDateTime.class).toInstant(),
                    rows.getObject("value_date", LocalDate.class),
                    rows.getObject("reversal_of_journal_id", UUID.class),
                    rows.getInt("policy_version"),
                    rows.getString("canonical_hash"),
                    postingSnapshots(connection, journalId));
            }
        }
    }

    private static List<PostingSnapshot> postingSnapshots(Connection connection, UUID journalId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT posting_id, account_id, currency, signed_minor_units, account_sequence,
                   dimensions::text AS dimensions_json
            FROM funds.posting
            WHERE journal_id = ?
            ORDER BY account_sequence, posting_id
            """)) {
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                var postings = new ArrayList<PostingSnapshot>();
                while (rows.next()) {
                    postings.add(new PostingSnapshot(
                        rows.getObject("posting_id", UUID.class),
                        rows.getObject("account_id", UUID.class),
                        rows.getString("currency"),
                        rows.getLong("signed_minor_units"),
                        rows.getLong("account_sequence"),
                        rows.getString("dimensions_json")));
                }
                return List.copyOf(postings);
            }
        }
    }

    private DatabaseCounts databaseCounts() throws SQLException {
        return new DatabaseCounts(
            count("funds.idempotency_command"),
            count("funds.journal"),
            count("funds.posting"),
            count("funds.outbox_event"));
    }

    private long count(String table) throws SQLException {
        return queryLong("SELECT count(*) FROM " + table);
    }

    private long queryLong(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private record JournalSnapshot(
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
        String canonicalHash,
        List<PostingSnapshot> postings) {}

    private record PostingSnapshot(
        UUID postingId,
        UUID accountId,
        String currency,
        long signedMinorUnits,
        long accountSequence,
        String dimensionsJson) {}

    private record DatabaseCounts(long commands, long journals, long postings, long outboxEvents) {}
}
