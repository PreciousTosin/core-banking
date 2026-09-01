package com.corebanking.funds.application.proof;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.application.CanonicalCommandHasher;
import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingResult;
import com.corebanking.funds.application.PostingService;
import com.corebanking.funds.application.ReversalService;
import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.ReversalRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves ACC-19: per-book, per-currency trial balances and independently sourced
 * control-account proofs at an exact journal-sequence cutoff, including detection of a
 * projection that no longer agrees with the immutable postings. Runs on the Quarkus
 * dev-services PostgreSQL container (Testcontainers) over a book this class seeds itself;
 * every account is mapped ASSET/DEBIT because the proofs only sum signed postings. Catches a
 * proof that trusts the projection instead of the postings, leaks across book, currency or
 * control-code coordinates, or overflows once aggregates leave the signed 64-bit range.
 */
@QuarkusTest
class AccountingProofServiceIT {
    private static final UUID BOOK = uuid(1);
    private static final UUID CHART = uuid(2);
    private static final UUID PERIOD = uuid(3);
    private static final UUID LEGAL_ENTITY = uuid(4);
    private static final UUID PROVIDER = uuid(10);
    private static final UUID CUSTOMER_A = uuid(11);
    private static final UUID CUSTOMER_B = uuid(12);
    private static final UUID OTHER = uuid(13);
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Inject DataSource dataSource;
    @Inject PostingService postingService;
    @Inject ReversalService reversalService;
    @Inject AccountingProofService proofService;

    @BeforeEach
    void setUp() throws SQLException {
        reset();
        try (var connection = dataSource.getConnection()) {
            seedBook(connection, BOOK, CHART, PERIOD, LEGAL_ENTITY, "NGN");
            seedAccount(connection, PROVIDER, BOOK, CHART, "PROVIDER", "NGN", "PROVIDER-CASH");
            seedAccount(connection, CUSTOMER_A, BOOK, CHART, "CUSTOMER-A", "NGN", "CUSTOMER-DEPOSITS");
            seedAccount(connection, CUSTOMER_B, BOOK, CHART, "CUSTOMER-B", "NGN", "CUSTOMER-DEPOSITS");
            seedAccount(connection, OTHER, BOOK, CHART, "OTHER", "NGN", "OTHER-CONTROL");
            seedAccount(connection, uuid(206), BOOK, CHART, "USD-DEBIT", "USD", "USD-ASSET");
            seedAccount(connection, uuid(207), BOOK, CHART,
                "USD-CUSTOMER", "USD", "CUSTOMER-DEPOSITS");
            seedAccount(connection, uuid(300), BOOK, CHART,
                "MINIMUM", "NGN", "MINIMUM-CONTROL");
            seedAccount(connection, uuid(301), BOOK, CHART,
                "MAXIMUM", "NGN", "MAXIMUM-CONTROL");
            seedAccount(connection, uuid(302), BOOK, CHART,
                "UNIT", "NGN", "UNIT-CONTROL");
            activateChart(connection, CHART);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        reset();
    }

    @Test
    void provesInflowTransferAndReversalAtEachCurrentCutoff() {
        PostingResult inflow = post(20, "INFLOW", null,
            line(21, PROVIDER, NGN, 100_000), line(22, CUSTOMER_A, NGN, -100_000));
        assertProof(inflow.journalSequence(), 100_000, 100_000, -100_000);

        PostingResult transfer = post(30, "TRANSFER", null,
            line(31, CUSTOMER_A, NGN, 25_000), line(32, CUSTOMER_B, NGN, -25_000));
        assertProof(transfer.journalSequence(), 125_000, 125_000, -100_000);

        PostingResult reversal = reversalService.reverse(canonical(new ReversalRequest(
            uuid(40), "b".repeat(64), inflow.journalId(), uuid(2_040), uuid(3_040), PERIOD,
            Instant.parse("2026-01-16T10:00:00Z"), LocalDate.of(2026, 1, 16), "Proof reversal")));
        assertProof(reversal.journalSequence(), 225_000, 225_000, 0);
    }

    // Corrupts the projection by direct SQL, deliberately as the owning role funds_migrator
    // (the ownership check makes that explicit); nothing on the service path updates a
    // projection outside a posting. +37 on a -80_000 total must show up as difference -37 while
    // the trial balance, sourced from postings alone, is unchanged and still balanced.
    @Test
    void projectionCorruptionReportsExactDifferenceWithoutChangingImmutableTrialProof() throws SQLException {
        PostingResult result = post(50, "INFLOW", null,
            line(51, PROVIDER, NGN, 80_000), line(52, CUSTOMER_A, NGN, -80_000));
        TrialBalanceProof before = proofService.trialBalance(BOOK, NGN, result.journalSequence());

        try (var connection = dataSource.getConnection()) {
            execute(connection, "SET ROLE funds_migrator");
            try {
                assertTrue(queryBoolean(connection, """
                    SELECT current_user = tableowner
                    FROM pg_tables
                    WHERE schemaname = 'funds' AND tablename = 'control_account_projection'
                    """));
                execute(connection, """
                    UPDATE funds.control_account_projection
                    SET signed_posting_total = signed_posting_total + 37
                    WHERE book_id = ? AND control_account_code = 'CUSTOMER-DEPOSITS' AND currency = 'NGN'
                    """, BOOK);
            } finally {
                execute(connection, "RESET ROLE");
            }
        }

        ControlAccountProof control = proofService.controlAccount(
            BOOK, "CUSTOMER-DEPOSITS", NGN, result.journalSequence());
        TrialBalanceProof after = proofService.trialBalance(BOOK, NGN, result.journalSequence());

        assertAll(
            () -> assertEquals(before, after),
            () -> assertTrue(after.balanced()),
            () -> assertEquals(BigInteger.valueOf(-80_000), control.sourceTotal()),
            () -> assertEquals(BigInteger.valueOf(-79_963), control.projectionTotal()),
            () -> assertEquals(BigInteger.valueOf(-37), control.difference()));
    }

    // The projection row is removed by direct SQL, bypassing the service. A control code with
    // real postings but no projection must fail closed; a code with no postings at all has
    // nothing to reconcile and proves as zero.
    @Test
    void missingProjectionForMappedSourceFailsClosedWhileEmptySourceUsesZero() throws SQLException {
        PostingResult result = post(55, "INFLOW", null,
            line(56, PROVIDER, NGN, 90), line(57, CUSTOMER_A, NGN, -90));
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                DELETE FROM funds.control_account_projection
                WHERE book_id = ? AND control_account_code = 'CUSTOMER-DEPOSITS' AND currency = 'NGN'
                """, BOOK);
        }

        assertAll(
            () -> assertThrows(IllegalStateException.class,
                () -> proofService.controlAccount(
                    BOOK, "CUSTOMER-DEPOSITS", NGN, result.journalSequence())),
            () -> assertEquals(BigInteger.ZERO,
                proofService.controlAccount(BOOK, "NEVER-POSTED", NGN, result.journalSequence()).difference()));
    }

    // The projection's latest_journal_sequence must equal the latest journal that actually
    // touched this control code (first), even when the cutoff is a later unrelated journal.
    // Sequence: prove at the unrelated cutoff (valid); rewrite the projection sequence to the
    // unrelated journal (rejected: it never posted to CUSTOMER-DEPOSITS); rewrite it past the
    // cutoff (rejected again). The trial balance is unaffected by projection tampering.
    @Test
    void exactSourceSequenceAcceptsUnrelatedLaterJournalAndRejectsRewrittenProjectionSequence()
        throws SQLException {
        PostingResult first = post(60, "INFLOW", null,
            line(61, PROVIDER, NGN, 50), line(62, CUSTOMER_A, NGN, -50));
        PostingResult unrelated = post(70, "UNRELATED", null,
            line(71, PROVIDER, NGN, 20), line(72, OTHER, NGN, -20));

        ControlAccountProof valid = proofService.controlAccount(
            BOOK, "CUSTOMER-DEPOSITS", NGN, unrelated.journalSequence());
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                UPDATE funds.control_account_projection SET latest_journal_sequence = ?
                WHERE book_id = ? AND control_account_code = 'CUSTOMER-DEPOSITS' AND currency = 'NGN'
                """, unrelated.journalSequence(), BOOK);
        }

        assertAll(
            () -> assertEquals(BigInteger.valueOf(-50), valid.sourceTotal()),
            () -> assertEquals(BigInteger.valueOf(-50), valid.projectionTotal()),
            () -> assertThrows(IllegalStateException.class,
                () -> proofService.controlAccount(
                    BOOK, "CUSTOMER-DEPOSITS", NGN, unrelated.journalSequence())),
            () -> assertTrue(proofService.trialBalance(BOOK, NGN, first.journalSequence()).balanced()));

        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                UPDATE funds.control_account_projection SET latest_journal_sequence = ?
                WHERE book_id = ? AND control_account_code = 'CUSTOMER-DEPOSITS' AND currency = 'NGN'
                """, unrelated.journalSequence() + 1, BOOK);
        }
        assertThrows(IllegalStateException.class,
            () -> proofService.controlAccount(
                BOOK, "CUSTOMER-DEPOSITS", NGN, unrelated.journalSequence()));
    }

    // The transfer moves 20 between two CUSTOMER-DEPOSITS accounts, so the control total is
    // unchanged; only the sequence check can notice that the projection was rewound to before it.
    @Test
    void laterNetZeroMappedActivityCannotBeHiddenByRewindingProjectionSequence() throws SQLException {
        PostingResult first = post(75, "INFLOW", null,
            line(76, PROVIDER, NGN, 50), line(77, CUSTOMER_A, NGN, -50));
        PostingResult transfer = post(78, "TRANSFER", null,
            line(79, CUSTOMER_A, NGN, 20), line(80, CUSTOMER_B, NGN, -20));
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                UPDATE funds.control_account_projection SET latest_journal_sequence = ?
                WHERE book_id = ? AND control_account_code = 'CUSTOMER-DEPOSITS' AND currency = 'NGN'
                """, first.journalSequence(), BOOK);
        }

        assertThrows(IllegalStateException.class,
            () -> proofService.controlAccount(BOOK, "CUSTOMER-DEPOSITS", NGN, transfer.journalSequence()));
    }

    // The projection only holds the current total, so a control proof is defined at the current
    // cutoff for its mapped activity; the trial balance, replayed from postings, stays historical.
    @Test
    void controlProjectionProofRejectsHistoricalCutoffAfterLaterMappedActivity() {
        PostingResult first = post(81, "INFLOW", null,
            line(82, PROVIDER, NGN, 50), line(83, CUSTOMER_A, NGN, -50));
        PostingResult current = post(84, "TRANSFER", null,
            line(85, CUSTOMER_A, NGN, 20), line(86, CUSTOMER_B, NGN, -20));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> proofService.controlAccount(
                BOOK, "CUSTOMER-DEPOSITS", NGN, first.journalSequence()));

        assertAll(
            () -> assertTrue(failure.getMessage().contains("current cutoff")),
            () -> assertTrue(proofService.trialBalance(BOOK, NGN, first.journalSequence()).balanced()),
            () -> assertEquals(BigInteger.valueOf(-50), proofService.controlAccount(
                BOOK, "CUSTOMER-DEPOSITS", NGN, current.journalSequence()).sourceTotal()));
    }

    // Two USD journals are needed: Long.MAX_VALUE is the largest single line the domain admits,
    // so the second journal is what pushes the per-currency aggregate past the long range and
    // forces the proof onto BigInteger. The NGN book and the base book's USD/NGN coordinates
    // must each see only their own activity.
    @Test
    void isolatesBookAndCurrencyAndKeepsAggregatesBeyondLongExact() throws SQLException {
        UUID otherBook = uuid(100);
        UUID otherChart = uuid(101);
        UUID otherPeriod = uuid(102);
        UUID otherEntity = uuid(103);
        UUID usdDebit = uuid(104);
        UUID usdCredit = uuid(105);
        UUID debitTail = uuid(106);
        UUID creditTail = uuid(107);
        CurrencyCode usd = CurrencyCode.of("USD");
        try (var connection = dataSource.getConnection()) {
            seedBook(connection, otherBook, otherChart, otherPeriod, otherEntity, "USD");
            seedAccount(connection, usdDebit, otherBook, otherChart, "USD-DEBIT", "USD", "USD-DEBIT-A");
            seedAccount(connection, debitTail, otherBook, otherChart, "USD-DEBIT-TAIL", "USD", "USD-DEBIT-B");
            seedAccount(connection, usdCredit, otherBook, otherChart, "USD-CREDIT", "USD", "USD-CREDIT-A");
            seedAccount(connection, creditTail, otherBook, otherChart, "USD-CREDIT-TAIL", "USD", "USD-CREDIT-B");
            activateChart(connection, otherChart);
        }
        PostingResult ngn = post(80, "NGN", null,
            line(81, PROVIDER, NGN, 10), line(82, CUSTOMER_A, NGN, -10));
        postFor(otherBook, otherChart, otherPeriod, otherEntity, 110, "USD", null, List.of(
            line(111, usdDebit, usd, Long.MAX_VALUE),
            line(113, usdCredit, usd, -Long.MAX_VALUE)));
        PostingResult huge = postFor(otherBook, otherChart, otherPeriod, otherEntity, 120, "USD", null, List.of(
            line(112, debitTail, usd, 10),
            line(114, creditTail, usd, -10)));

        BigInteger expected = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN);
        TrialBalanceProof usdProof = proofService.trialBalance(otherBook, usd, huge.journalSequence());
        assertAll(
            () -> assertEquals(expected, usdProof.totalDebits()),
            () -> assertEquals(expected, usdProof.totalCredits()),
            () -> assertTrue(usdProof.balanced()),
            () -> assertEquals(BigInteger.TEN,
                proofService.trialBalance(BOOK, NGN, ngn.journalSequence()).totalDebits()),
            () -> assertEquals(BigInteger.ZERO,
                proofService.trialBalance(BOOK, usd, huge.journalSequence()).totalDebits()),
            () -> assertEquals(BigInteger.ZERO,
                proofService.trialBalance(otherBook, NGN, huge.journalSequence()).totalDebits()));
    }

    @Test
    void controlProofCoordinatesIsolateBookCurrencyAndControlCode() throws SQLException {
        UUID otherBook = uuid(200);
        UUID otherChart = uuid(201);
        UUID otherPeriod = uuid(202);
        UUID otherEntity = uuid(203);
        UUID otherDebit = uuid(204);
        UUID otherCustomer = uuid(205);
        UUID usdDebit = uuid(206);
        UUID usdCustomer = uuid(207);
        CurrencyCode usd = CurrencyCode.of("USD");
        try (var connection = dataSource.getConnection()) {
            seedBook(connection, otherBook, otherChart, otherPeriod, otherEntity, "NGN");
            seedAccount(connection, otherDebit, otherBook, otherChart,
                "OTHER-BOOK-DEBIT", "NGN", "ASSET-CONTROL");
            seedAccount(connection, otherCustomer, otherBook, otherChart,
                "OTHER-BOOK-CUSTOMER", "NGN", "CUSTOMER-DEPOSITS");
            activateChart(connection, otherChart);
        }
        post(210, "BASE-NGN", null,
            line(211, PROVIDER, NGN, 11), line(212, CUSTOMER_A, NGN, -11));
        postFor(otherBook, otherChart, otherPeriod, otherEntity, 220, "OTHER-BOOK", null, List.of(
            line(221, otherDebit, NGN, 22), line(222, otherCustomer, NGN, -22)));
        postFor(BOOK, CHART, PERIOD, LEGAL_ENTITY, 230, "BASE-USD", null, List.of(
            line(231, usdDebit, usd, 33), line(232, usdCustomer, usd, -33)));
        PostingResult last = post(240, "OTHER-CONTROL", null,
            line(241, PROVIDER, NGN, 44), line(242, OTHER, NGN, -44));

        assertAll(
            () -> assertControl(BOOK, "CUSTOMER-DEPOSITS", NGN, last.journalSequence(), -11),
            () -> assertControl(otherBook, "CUSTOMER-DEPOSITS", NGN, last.journalSequence(), -22),
            () -> assertControl(BOOK, "CUSTOMER-DEPOSITS", usd, last.journalSequence(), -33),
            () -> assertControl(BOOK, "OTHER-CONTROL", NGN, last.journalSequence(), -44));
    }

    // Long.MIN_VALUE + 1 == -Long.MAX_VALUE is the most negative amount admission allows
    // (MIN_VALUE itself is rejected so reversal negation is always exact); the credit column
    // must report its magnitude without negating a long.
    @Test
    void trialProofHandlesOrderedLongMinimumWithoutNegationOverflow() throws SQLException {
        UUID minimum = uuid(300);
        UUID maximum = uuid(301);
        UUID unit = uuid(302);
        PostingResult result = postFor(BOOK, CHART, PERIOD, LEGAL_ENTITY, 310, "EXTREME", null, List.of(
            line(311, minimum, NGN, Long.MIN_VALUE + 1),
            line(312, maximum, NGN, Long.MAX_VALUE)));

        TrialBalanceProof proof = proofService.trialBalance(BOOK, NGN, result.journalSequence());
        BigInteger magnitude = BigInteger.valueOf(Long.MAX_VALUE);
        assertAll(
            () -> assertEquals(magnitude, proof.totalDebits()),
            () -> assertEquals(magnitude, proof.totalCredits()),
            () -> assertTrue(proof.balanced()));
    }

    @Test
    void validatesEmptyCutoffInputsAndProofConsistency() {
        assertAll(
            () -> assertEquals(new TrialBalanceProof(BOOK, NGN, 0, BigInteger.ZERO, BigInteger.ZERO, true),
                proofService.trialBalance(BOOK, NGN, 0)),
            () -> assertEquals(
                new ControlAccountProof("MISSING", NGN, 0,
                    BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO),
                proofService.controlAccount(BOOK, "MISSING", NGN, 0)),
            () -> assertThrows(NullPointerException.class, () -> proofService.trialBalance(null, NGN, 0)),
            () -> assertThrows(NullPointerException.class, () -> proofService.trialBalance(BOOK, null, 0)),
            () -> assertThrows(IllegalArgumentException.class, () -> proofService.trialBalance(BOOK, NGN, -1)),
            () -> assertThrows(NullPointerException.class,
                () -> proofService.controlAccount(BOOK, null, NGN, 0)),
            () -> assertThrows(NullPointerException.class,
                () -> proofService.controlAccount(BOOK, "X", null, 0)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> proofService.controlAccount(BOOK, "X", NGN, -1)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> proofService.controlAccount(BOOK, " ", NGN, 0)),
            () -> assertThrows(NullPointerException.class,
                () -> new TrialBalanceProof(BOOK, NGN, 1, null, BigInteger.ZERO, false)),
            () -> assertThrows(NullPointerException.class,
                () -> new ControlAccountProof("X", NGN, 1, null, BigInteger.ZERO, BigInteger.ZERO)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new TrialBalanceProof(BOOK, NGN, 1, BigInteger.ONE, BigInteger.ZERO, true)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new ControlAccountProof("X", NGN, 1,
                    BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO)),
            () -> assertFalse(new TrialBalanceProof(
                BOOK, NGN, 1, BigInteger.ONE, BigInteger.ZERO, false).balanced()));
    }

    private void assertProof(long cutoff, long debits, long credits, long customerTotal) {
        TrialBalanceProof trial = proofService.trialBalance(BOOK, NGN, cutoff);
        ControlAccountProof control = proofService.controlAccount(BOOK, "CUSTOMER-DEPOSITS", NGN, cutoff);
        assertAll(
            () -> assertEquals(BigInteger.valueOf(debits), trial.totalDebits()),
            () -> assertEquals(BigInteger.valueOf(credits), trial.totalCredits()),
            () -> assertTrue(trial.balanced()),
            () -> assertEquals(BigInteger.valueOf(customerTotal), control.sourceTotal()),
            () -> assertEquals(BigInteger.valueOf(customerTotal), control.projectionTotal()),
            () -> assertEquals(BigInteger.ZERO, control.difference()));
    }

    private void assertControl(
        UUID book, String controlCode, CurrencyCode currency, long cutoff, long expected
    ) {
        ControlAccountProof proof = proofService.controlAccount(book, controlCode, currency, cutoff);
        BigInteger total = BigInteger.valueOf(expected);
        assertAll(
            () -> assertEquals(total, proof.sourceTotal()),
            () -> assertEquals(total, proof.projectionTotal()),
            () -> assertEquals(BigInteger.ZERO, proof.difference()));
    }

    private PostingResult post(long seed, String type, UUID reversal, PostingLine... lines) {
        return postFor(BOOK, CHART, PERIOD, LEGAL_ENTITY, seed, type, reversal, List.of(lines));
    }

    private PostingResult postFor(
        UUID book, UUID chart, UUID period, UUID entity, long seed, String type, UUID reversal,
        List<PostingLine> lines
    ) {
        UUID commandId = uuid(seed);
        JournalDraft draft = new JournalDraft(
            uuid(seed + 1_000), commandId, uuid(seed + 2_000), uuid(seed + 3_000), entity, book,
            chart, period,
            type, type, Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), reversal, 1, lines);
        return postingService.post(new PostingCommand(
            commandId, new CanonicalCommandHasher().postingV2(draft), draft));
    }

    private static PostingLine line(long seed, UUID account, CurrencyCode currency, long amount) {
        return new PostingLine(uuid(seed), account, currency, amount, 0, Map.of());
    }

    private static ReversalRequest canonical(ReversalRequest request) {
        return new ReversalRequest(
            request.commandId(), new CanonicalCommandHasher().reversalV2(request),
            request.originalJournalId(), request.correlationId(), request.businessTransactionId(),
            request.currentPeriodId(), request.bookingTime(), request.valueDate(), request.reason());
    }

    private void reset() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                TRUNCATE funds.outbox_event, funds.control_account_projection, funds.materialised_balance,
                    funds.posting, funds.journal, funds.idempotency_command, funds.account_identifier,
                    funds.ledger_account_chart_mapping, funds.ledger_account, funds.accounting_period,
                    funds.chart_version, funds.book,
                    funds.product_version, funds.product_definition RESTART IDENTITY CASCADE
                """);
        }
    }

    /** Leaves a book with one DRAFT chart and one OPEN January 2026 period; no accounts yet. */
    private static void seedBook(
        Connection connection, UUID book, UUID chart, UUID period, UUID entity, String currency
    ) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
            VALUES (?, ?, ?, 'Africa/Lagos', 'NG', 1)
            """, book, entity, currency);
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES (?, ?, 1, 'DRAFT', ?)
            """, chart, book, "PROOF-CHART-" + chart);
        execute(connection, """
            INSERT INTO funds.accounting_period (period_id, book_id, business_date_from, business_date_to, status)
            VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
            """, period, book);
    }

    /**
     * Leaves an OPEN internal account mapped on the (still DRAFT) chart under the given control
     * code. Class and normal balance are fixed to ASSET/DEBIT: the proofs sum signed postings,
     * so classification never enters the expected totals.
     */
    private static void seedAccount(
        Connection connection, UUID account, UUID book, UUID chart, String code, String currency, String control
    ) throws SQLException {
        execute(connection, """
            INSERT INTO funds.ledger_account
                (account_id, book_id, account_scope, product_version_id, currency, status, created_at)
            VALUES (?, ?, 'INTERNAL', NULL, ?, 'OPEN',
                    TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """, account, book, currency);
        execute(connection, """
            INSERT INTO funds.ledger_account_chart_mapping
                (account_id, book_id, chart_version_id, account_code, account_currency,
                 account_class,
                 normal_balance, control_account_code, account_role)
            VALUES (?, ?, ?, ?, ?, 'ASSET', 'DEBIT', ?, 'INTERNAL')
            """, account, book, chart, code, currency, control);
    }

    /**
     * Activates the first chart by direct UPDATE rather than funds.rotate_chart_version, which
     * needs an existing ACTIVE chart to retire. Must run after every account is mapped.
     */
    private static void activateChart(Connection connection, UUID chart) throws SQLException {
        execute(connection, """
            UPDATE funds.chart_version
            SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
            WHERE chart_version_id = ?
            """, chart);
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("boolean query returned no row");
            }
            return rows.getBoolean(1);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
