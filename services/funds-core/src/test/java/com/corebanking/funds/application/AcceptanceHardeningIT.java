package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

@QuarkusTest
class AcceptanceHardeningIT {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Inject
    DataSource dataSource;

    @Inject
    PostingService postingService;

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void productClassificationLivesOnTheImmutableVersion() throws SQLException {
        UUID nextVersion = TestPostingStack.uuid(1_000);
        try (var connection = dataSource.getConnection()) {
            assertEquals(0, queryLong(connection, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'funds' AND table_name = 'product_definition'
                  AND column_name IN ('product_kind', 'finance_principle')
                """));
            assertEquals(2, queryLong(connection, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'funds' AND table_name = 'product_version'
                  AND column_name IN ('product_kind', 'finance_principle')
                """));
            assertEquals("SAVINGS/CONVENTIONAL", queryString(connection, """
                SELECT product_kind || '/' || finance_principle
                FROM funds.product_version WHERE product_version_id = ?
                """, TestPostingStack.PRODUCT_VERSION_ID));
            execute(connection, """
                INSERT INTO funds.product_version
                    (product_version_id, product_id, version, effective_from,
                     approval_reference, policy_hash, policy_json,
                     product_kind, finance_principle)
                VALUES (?, ?, 2, TIMESTAMPTZ '2026-02-01 00:00:00+00',
                        'APP-PRODUCT-002', ?, '{}'::jsonb, 'CURRENT', 'NON_INTEREST')
                """, nextVersion, TestPostingStack.PRODUCT_ID, "b".repeat(64));
            assertEquals("CURRENT/NON_INTEREST", queryString(connection, """
                SELECT product_kind || '/' || finance_principle
                FROM funds.product_version WHERE product_version_id = ?
                """, nextVersion));
            assertEquals("SAVINGS/CONVENTIONAL", queryString(connection, """
                SELECT version.product_kind || '/' || version.finance_principle
                FROM funds.ledger_account account
                JOIN funds.product_version version
                  ON version.product_version_id = account.product_version_id
                WHERE account.account_id = ?
                """, TestPostingStack.CUSTOMER_LIABILITY));
            assertConstraint("product_version_immutable", () -> executeInRollback(connection, """
                UPDATE funds.product_version SET finance_principle = 'NON_INTEREST'
                WHERE product_version_id = ?
                """, TestPostingStack.PRODUCT_VERSION_ID));
            assertConstraint("product_definition_identity_immutable",
                () -> executeInRollback(connection, """
                    UPDATE funds.product_definition SET product_code = 'RECLASSIFIED'
                    WHERE product_id = ?
                    """, TestPostingStack.PRODUCT_ID));
        }
    }

    @Test
    void serviceAndDatabaseUseTheLagosBookingDateForTheSelectedOpenPeriod() throws SQLException {
        PostingCommand outsideAtLagosMidnight = command(
            TestPostingStack.uuid(1_001), TestPostingStack.uuid(1_002),
            TestPostingStack.CHART_VERSION_ID,
            Instant.parse("2026-01-31T23:30:00Z"), LocalDate.of(2026, 1, 31), null,
            100);

        assertThrows(InvalidJournalException.class,
            () -> postingService.post(outsideAtLagosMidnight));
        assertEquals(0, count("funds.journal"));

        try (var connection = dataSource.getConnection()) {
            assertConstraint("journal_booking_date_period", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_010), TestPostingStack.uuid(1_011),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-31T23:30:00Z"),
                LocalDate.of(2026, 1, 31), null, "DIRECT"));
        }
    }

    @Test
    void serviceAndDatabaseRejectBookingAndValueDatePeriodDivergence() throws SQLException {
        PostingCommand valueOutside = command(
            TestPostingStack.uuid(1_020), TestPostingStack.uuid(1_021),
            TestPostingStack.CHART_VERSION_ID,
            Instant.parse("2026-01-31T10:00:00Z"), LocalDate.of(2026, 2, 1), null, 100);

        assertThrows(InvalidJournalException.class, () -> postingService.post(valueOutside));
        try (var connection = dataSource.getConnection()) {
            assertConstraint("journal_value_date_period", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_022), TestPostingStack.uuid(1_023),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-31T10:00:00Z"),
                LocalDate.of(2026, 2, 1), null, "DIRECT"));
        }
        assertEquals(0, count("funds.journal"));
    }

    @Test
    void databaseRejectsWrongBookAndClosedPeriodsAtTheJournalBoundary() throws SQLException {
        UUID otherPeriod = TestPostingStack.uuid(1_030);
        try (var connection = dataSource.getConnection()) {
            insertOtherBookGovernance(connection, TestPostingStack.uuid(1_031),
                TestPostingStack.uuid(1_032), otherPeriod);
            assertConstraint("journal_period_book", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_033), TestPostingStack.uuid(1_034),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID, otherPeriod,
                Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15),
                null, "DIRECT"));
            execute(connection, """
                UPDATE funds.accounting_period SET status = 'CLOSED' WHERE period_id = ?
                """, TestPostingStack.PERIOD_ID);
            assertConstraint("journal_open_period", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_035), TestPostingStack.uuid(1_036),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-15T10:00:00Z"),
                LocalDate.of(2026, 1, 15), null, "DIRECT"));
        }
        assertEquals(0, count("funds.journal"));
    }

    @Test
    void databaseIndependentlyEnforcesCurrentPolicyAndEffectiveBookChart() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertConstraint("journal_current_policy", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_040), TestPostingStack.uuid(1_041),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-15T10:00:00Z"),
                LocalDate.of(2026, 1, 15), null, "DIRECT", 2));

            UUID otherBook = TestPostingStack.uuid(1_042);
            UUID otherChart = TestPostingStack.uuid(1_043);
            insertOtherBookGovernance(connection, otherBook, otherChart,
                TestPostingStack.uuid(1_044));
            assertConstraint("journal_chart_governance", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_045), TestPostingStack.uuid(1_046),
                TestPostingStack.BOOK_ID, otherChart, TestPostingStack.PERIOD_ID,
                Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15),
                null, "DIRECT"));

            execute(connection, """
                UPDATE funds.chart_version
                SET status = 'RETIRED', retired_at = TIMESTAMPTZ '2026-01-16 00:00:00+00'
                WHERE chart_version_id = ?
                """, TestPostingStack.CHART_VERSION_ID);
            assertConstraint("journal_effective_chart", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_047), TestPostingStack.uuid(1_048),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-17T10:00:00Z"),
                LocalDate.of(2026, 1, 17), null, "DIRECT"));
        }
        assertEquals(0, count("funds.journal"));
    }

    @Test
    void chartRotationPinsHistoricalJournalsAndRequiresOneMappingVersion() throws SQLException {
        PostingResult historical = postingService.post(command(
            TestPostingStack.uuid(1_100), TestPostingStack.uuid(1_101),
            TestPostingStack.CHART_VERSION_ID,
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null,
            100));
        UUID nextChart = TestPostingStack.uuid(1_102);
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, approval_reference)
                VALUES (?, ?, 2, 'DRAFT', 'APP-CHART-2')
                """, nextChart, TestPostingStack.BOOK_ID);
            execute(connection, """
                INSERT INTO funds.ledger_account_chart_mapping
                    (account_id, book_id, chart_version_id, account_code, account_class,
                     normal_balance, control_account_code, account_role, currency_policy,
                     permitted_direction)
                SELECT account_id, book_id, ?, account_code, account_class, normal_balance,
                       control_account_code, account_role, currency_policy, permitted_direction
                FROM funds.ledger_account_chart_mapping
                WHERE chart_version_id = ?
                """, nextChart, TestPostingStack.CHART_VERSION_ID);
            assertConstraint("one_active_chart_per_book_idx", () -> executeInRollback(connection, """
                UPDATE funds.chart_version
                SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-16 00:00:00+00'
                WHERE chart_version_id = ?
                """, nextChart));

            connection.setAutoCommit(false);
            execute(connection, """
                UPDATE funds.chart_version
                SET status = 'RETIRED', retired_at = TIMESTAMPTZ '2026-01-16 00:00:00+00'
                WHERE chart_version_id = ?
                """, TestPostingStack.CHART_VERSION_ID);
            execute(connection, """
                UPDATE funds.chart_version
                SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-16 00:00:00+00'
                WHERE chart_version_id = ?
                """, nextChart);
            connection.commit();
            assertConstraint("ledger_account_chart_mapping_immutable",
                () -> executeInRollback(connection, """
                    UPDATE funds.ledger_account_chart_mapping SET account_class = 'EQUITY'
                    WHERE account_id = ? AND chart_version_id = ?
                    """, TestPostingStack.CUSTOMER_LIABILITY,
                    TestPostingStack.CHART_VERSION_ID));
        }

        PostingResult current = postingService.post(command(
            TestPostingStack.uuid(1_110), TestPostingStack.uuid(1_111), nextChart,
            Instant.parse("2026-01-16T10:00:00Z"), LocalDate.of(2026, 1, 16), null,
            50));
        assertEquals(TestPostingStack.CHART_VERSION_ID,
            queryUuid("SELECT chart_version_id FROM funds.journal WHERE journal_id = ?",
                historical.journalId()));
        assertEquals(nextChart,
            queryUuid("SELECT chart_version_id FROM funds.journal WHERE journal_id = ?",
                current.journalId()));
        assertEquals("LIABILITY", queryString("""
            SELECT mapping.account_class
            FROM funds.journal journal
            JOIN funds.posting posting ON posting.journal_id = journal.journal_id
            JOIN funds.ledger_account_chart_mapping mapping
              ON mapping.account_id = posting.account_id
             AND mapping.chart_version_id = journal.chart_version_id
            WHERE journal.journal_id = ? AND posting.account_id = ?
            """, historical.journalId(), TestPostingStack.CUSTOMER_LIABILITY));
    }

    @Test
    void everyDirectPostingMustResolveThroughTheJournalChartVersion() throws SQLException {
        UUID partialChart = TestPostingStack.uuid(1_150);
        activateChartWithProviderMappingOnly(partialChart);
        PostingCommand mixed = command(
            TestPostingStack.uuid(1_151), TestPostingStack.uuid(1_152), partialChart,
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null, 100);

        assertThrows(InvalidJournalException.class, () -> postingService.post(mixed));
        try (var connection = dataSource.getConnection()) {
            assertConstraint("posting_chart_mapping", () -> insertDirectBalancedJournal(
                connection, TestPostingStack.uuid(1_153), TestPostingStack.uuid(1_154),
                partialChart));
        }
        assertEquals(0, count("funds.journal"));
    }

    @Test
    void databaseRejectsDisguisedAndInexactReversalFacts() throws SQLException {
        PostingResult original = postingService.post(command(
            TestPostingStack.uuid(1_200), TestPostingStack.uuid(1_201),
            TestPostingStack.CHART_VERSION_ID,
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null,
            100));
        try (var connection = dataSource.getConnection()) {
            assertConstraint("journal_reversal_linkage_check", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_202), TestPostingStack.uuid(1_203),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-15T11:00:00Z"),
                LocalDate.of(2026, 1, 15), original.journalId(), "ALTERNATE_TYPE"));
            assertConstraint("reversal_exact_negation",
                () -> insertInexactReversal(connection, original.journalId()));
        }
        assertEquals(1, count("funds.journal"));
    }

    @Test
    void databaseRejectsEveryIrreversibleDirectPostingDomain() throws SQLException {
        String tooManyDimensions = IntStream.rangeClosed(1, 33)
            .mapToObj(index -> "\"k" + index + "\":\"v\"")
            .collect(Collectors.joining(",", "{", "}"));
        String tooManyBytes = "{\"k\":\"" + "x".repeat(8_184) + "\"}";
        try (var connection = dataSource.getConnection()) {
            assertConstraint("posting_reversible_amount_check",
                () -> insertDirectPostingFixture(connection, TestPostingStack.uuid(1_220),
                    TestPostingStack.uuid(1_221), Long.MIN_VALUE, "{}"));
            assertConstraint("posting_dimensions_count_check",
                () -> insertDirectPostingFixture(connection, TestPostingStack.uuid(1_222),
                    TestPostingStack.uuid(1_223), 1, tooManyDimensions));
            assertConstraint("posting_dimensions_bytes_check",
                () -> insertDirectPostingFixture(connection, TestPostingStack.uuid(1_224),
                    TestPostingStack.uuid(1_225), 1, tooManyBytes));
        }
        assertEquals(0, count("funds.journal"));
    }

    @Test
    void databaseRejectsTheTwoHundredFiftySeventhDirectPosting() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertConstraint("journal_reversible_posting_count",
                () -> insertDirectJournalWithTooManyPostings(
                    connection, TestPostingStack.uuid(1_230), TestPostingStack.uuid(1_231)));
        }
        assertEquals(0, count("funds.journal"));
        assertEquals(0, count("funds.posting"));
    }

    @Test
    void databaseAllowsOneExactReversalAndRejectsAnySecondDirectLink() throws SQLException {
        PostingResult original = postingService.post(command(
            TestPostingStack.uuid(1_240), TestPostingStack.uuid(1_241),
            TestPostingStack.CHART_VERSION_ID,
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null, 100));
        try (var connection = dataSource.getConnection()) {
            insertExactDirectReversal(connection, TestPostingStack.uuid(1_242),
                TestPostingStack.uuid(1_243), original.journalId());
            assertConstraint("one_reversal_per_original_idx", () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_244), TestPostingStack.uuid(1_245),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-15T12:00:00Z"),
                LocalDate.of(2026, 1, 15), original.journalId(), "REVERSAL"));
        }
        assertEquals(2, count("funds.journal"));
        assertEquals(4, count("funds.posting"));
    }

    private static PostingCommand command(
        UUID commandId,
        UUID journalId,
        UUID chartVersionId,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOf,
        long amount
    ) {
        JournalDraft draft = new JournalDraft(
            journalId, commandId, TestPostingStack.uuid(commandId.getLeastSignificantBits() + 10),
            TestPostingStack.uuid(commandId.getLeastSignificantBits() + 11),
            TestPostingStack.LEGAL_ENTITY_ID, TestPostingStack.BOOK_ID, chartVersionId,
            TestPostingStack.PERIOD_ID, reversalOf == null ? "TRANSFER" : "REVERSAL", "acceptance",
            bookingTime, valueDate, reversalOf, 1,
            List.of(
                new PostingLine(TestPostingStack.uuid(journalId.getLeastSignificantBits() + 20),
                    TestPostingStack.PROVIDER_ASSET, NGN, amount, 0, Map.of()),
                new PostingLine(TestPostingStack.uuid(journalId.getLeastSignificantBits() + 21),
                    TestPostingStack.CUSTOMER_LIABILITY, NGN, -amount, 0, Map.of())));
        return new PostingCommand(commandId, new CanonicalCommandHasher().postingV1(draft), draft);
    }

    private static void insertDirectJournal(
        Connection connection,
        UUID commandId,
        UUID journalId,
        UUID bookId,
        UUID chartVersionId,
        UUID periodId,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOf,
        String type
    ) throws SQLException {
        insertDirectJournal(connection, commandId, journalId, bookId, chartVersionId,
            periodId, bookingTime, valueDate, reversalOf, type, 1);
    }

    private static void insertDirectJournal(
        Connection connection,
        UUID commandId,
        UUID journalId,
        UUID bookId,
        UUID chartVersionId,
        UUID periodId,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOf,
        String type,
        int policyVersion
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                """, commandId, "d".repeat(64));
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, chart_version_id, period_id, transaction_type,
                     narration, booking_time, value_date, reversal_of_journal_id,
                     policy_version, canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'direct', ?, ?, ?, ?, ?)
                """, journalId, commandId, TestPostingStack.uuid(9_001),
                TestPostingStack.uuid(9_002), TestPostingStack.LEGAL_ENTITY_ID, bookId,
                chartVersionId, periodId, type, bookingTime.atOffset(ZoneOffset.UTC), valueDate, reversalOf,
                policyVersion, "e".repeat(64));
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void insertInexactReversal(Connection connection, UUID originalJournalId)
        throws SQLException {
        UUID commandId = TestPostingStack.uuid(1_210);
        UUID journalId = TestPostingStack.uuid(1_211);
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                """, commandId, "a".repeat(64));
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, chart_version_id, period_id, transaction_type,
                     narration, booking_time, value_date, reversal_of_journal_id,
                     policy_version, canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REVERSAL', 'inexact',
                        TIMESTAMPTZ '2026-01-15 11:00:00+00', DATE '2026-01-15', ?, 1, ?)
                """, journalId, commandId, TestPostingStack.uuid(1_212),
                TestPostingStack.uuid(1_213), TestPostingStack.LEGAL_ENTITY_ID,
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, originalJournalId, "b".repeat(64));
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', -50, 5, '{}'::jsonb),
                       (?, ?, ?, 'NGN', 50, 7, '{}'::jsonb)
                """, TestPostingStack.uuid(1_214), journalId, TestPostingStack.PROVIDER_ASSET,
                TestPostingStack.uuid(1_215), journalId, TestPostingStack.CUSTOMER_LIABILITY);
            execute(connection, """
                UPDATE funds.idempotency_command
                SET state = 'COMPLETED', journal_id = ?, completed_at = CURRENT_TIMESTAMP,
                    result_json = jsonb_build_object(
                        'journalId', ?::text, 'journalSequence', 999, 'canonicalHash', ?::text)
                WHERE command_id = ?
                """, journalId, journalId, "b".repeat(64), commandId);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void insertDirectPostingFixture(
        Connection connection,
        UUID commandId,
        UUID journalId,
        long amount,
        String dimensionsJson
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertDirectHeader(connection, commandId, journalId, null, "DIRECT_LIMIT");
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', ?, 1, ?::jsonb)
                """, TestPostingStack.uuid(journalId.getLeastSignificantBits() + 1), journalId,
                TestPostingStack.PROVIDER_ASSET, amount, dimensionsJson);
            completeDirectCommand(connection, commandId, journalId);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void insertDirectJournalWithTooManyPostings(
        Connection connection,
        UUID commandId,
        UUID journalId
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertDirectHeader(connection, commandId, journalId, null, "DIRECT_LIMIT");
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                SELECT md5(?::text || ':provider:' || number::text)::uuid,
                       ?, ?, 'NGN', 1, number, '{}'::jsonb
                FROM generate_series(1, 129) number
                UNION ALL
                SELECT md5(?::text || ':customer:' || number::text)::uuid,
                       ?, ?, 'NGN', CASE WHEN number = 128 THEN -2 ELSE -1 END,
                       number, '{}'::jsonb
                FROM generate_series(1, 128) number
                """, journalId, journalId, TestPostingStack.PROVIDER_ASSET,
                journalId, journalId, TestPostingStack.CUSTOMER_LIABILITY);
            completeDirectCommand(connection, commandId, journalId);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void insertExactDirectReversal(
        Connection connection,
        UUID commandId,
        UUID journalId,
        UUID originalJournalId
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertDirectHeader(connection, commandId, journalId, originalJournalId, "REVERSAL");
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', -100, 100, '{}'::jsonb),
                       (?, ?, ?, 'NGN', 100, 100, '{}'::jsonb)
                """, TestPostingStack.uuid(1_246), journalId, TestPostingStack.PROVIDER_ASSET,
                TestPostingStack.uuid(1_247), journalId, TestPostingStack.CUSTOMER_LIABILITY);
            completeDirectCommand(connection, commandId, journalId);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void insertDirectHeader(
        Connection connection,
        UUID commandId,
        UUID journalId,
        UUID originalJournalId,
        String transactionType
    ) throws SQLException {
        execute(connection, """
            INSERT INTO funds.idempotency_command
                (command_id, request_hash, state, created_at)
            VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
            """, commandId, "c".repeat(64));
        execute(connection, """
            INSERT INTO funds.journal
                (journal_id, command_id, correlation_id, business_transaction_id,
                 legal_entity_id, book_id, chart_version_id, period_id, transaction_type,
                 narration, booking_time, value_date, reversal_of_journal_id,
                 policy_version, canonical_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'direct acceptance fixture',
                    TIMESTAMPTZ '2026-01-15 11:00:00+00', DATE '2026-01-15', ?, 1, ?)
            """, journalId, commandId,
            TestPostingStack.uuid(commandId.getLeastSignificantBits() + 10),
            TestPostingStack.uuid(commandId.getLeastSignificantBits() + 11),
            TestPostingStack.LEGAL_ENTITY_ID, TestPostingStack.BOOK_ID,
            TestPostingStack.CHART_VERSION_ID, TestPostingStack.PERIOD_ID,
            transactionType, originalJournalId, "d".repeat(64));
    }

    private static void completeDirectCommand(
        Connection connection,
        UUID commandId,
        UUID journalId
    ) throws SQLException {
        execute(connection, """
            UPDATE funds.idempotency_command
            SET state = 'COMPLETED', journal_id = ?, completed_at = CURRENT_TIMESTAMP,
                result_json = jsonb_build_object(
                    'journalId', ?::text, 'journalSequence', 999, 'canonicalHash', ?::text)
            WHERE command_id = ?
            """, journalId, journalId, "d".repeat(64), commandId);
    }

    private void activateChartWithProviderMappingOnly(UUID chartVersionId) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                    INSERT INTO funds.chart_version
                        (chart_version_id, book_id, version, status, approval_reference)
                    VALUES (?, ?, 2, 'DRAFT', 'APP-PARTIAL-CHART')
                    """, chartVersionId, TestPostingStack.BOOK_ID);
                execute(connection, """
                    INSERT INTO funds.ledger_account_chart_mapping
                        (account_id, book_id, chart_version_id, account_code, account_class,
                         normal_balance, control_account_code, account_role,
                         currency_policy, permitted_direction)
                    SELECT account_id, book_id, ?, account_code, account_class, normal_balance,
                           control_account_code, account_role, currency_policy, permitted_direction
                    FROM funds.ledger_account_chart_mapping
                    WHERE chart_version_id = ? AND account_id = ?
                    """, chartVersionId, TestPostingStack.CHART_VERSION_ID,
                    TestPostingStack.PROVIDER_ASSET);
                execute(connection, """
                    UPDATE funds.chart_version
                    SET status = 'RETIRED', retired_at = TIMESTAMPTZ '2026-01-10 00:00:00+00'
                    WHERE chart_version_id = ?
                    """, TestPostingStack.CHART_VERSION_ID);
                execute(connection, """
                    UPDATE funds.chart_version
                    SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-10 00:00:00+00'
                    WHERE chart_version_id = ?
                    """, chartVersionId);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void insertDirectBalancedJournal(
        Connection connection,
        UUID commandId,
        UUID journalId,
        UUID chartVersionId
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                """, commandId, "c".repeat(64));
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, chart_version_id, period_id, transaction_type,
                     narration, booking_time, value_date, policy_version, canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DIRECT', 'mixed chart',
                        TIMESTAMPTZ '2026-01-15 10:00:00+00', DATE '2026-01-15', 1, ?)
                """, journalId, commandId, TestPostingStack.uuid(1_155),
                TestPostingStack.uuid(1_156), TestPostingStack.LEGAL_ENTITY_ID,
                TestPostingStack.BOOK_ID, chartVersionId, TestPostingStack.PERIOD_ID,
                "d".repeat(64));
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', 100, 1, '{}'::jsonb),
                       (?, ?, ?, 'NGN', -100, 1, '{}'::jsonb)
                """, TestPostingStack.uuid(1_157), journalId, TestPostingStack.PROVIDER_ASSET,
                TestPostingStack.uuid(1_158), journalId, TestPostingStack.CUSTOMER_LIABILITY);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void insertOtherBookGovernance(
        Connection connection,
        UUID bookId,
        UUID chartVersionId,
        UUID periodId
    ) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code,
                 accounting_policy_version)
            VALUES (?, ?, 'NGN', 'Africa/Lagos', 'NG', 1)
            """, bookId, TestPostingStack.uuid(bookId.getLeastSignificantBits() + 100));
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, activated_at, approval_reference)
            VALUES (?, ?, 1, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', 'APP-OTHER')
            """, chartVersionId, bookId);
        execute(connection, """
            INSERT INTO funds.accounting_period
                (period_id, book_id, business_date_from, business_date_to, status)
            VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
            """, periodId, bookId);
    }

    private static PSQLException assertConstraint(String expected, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        for (SQLException candidate = failure; candidate != null;
             candidate = candidate.getNextException()) {
            if (candidate instanceof PSQLException postgresFailure
                && postgresFailure.getServerErrorMessage() != null
                && expected.equals(postgresFailure.getServerErrorMessage().getConstraint())) {
                return postgresFailure;
            }
        }
        throw new AssertionError(
            "expected PostgreSQL constraint " + expected + " but received " + failure,
            failure);
    }

    private static void executeInRollback(Connection connection, String sql, Object... values)
        throws SQLException {
        connection.setAutoCommit(false);
        try {
            execute(connection, sql, values);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void execute(Connection connection, String sql, Object... values)
        throws SQLException {
        TestPostingStack.execute(connection, sql, values);
    }

    private long count(String table) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return queryLong(connection, "SELECT count(*) FROM " + table);
        }
    }

    private UUID queryUuid(String sql, Object value) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private String queryString(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return queryString(connection, sql, values);
        }
    }

    private static long queryLong(Connection connection, String sql, Object... values)
        throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String queryString(Connection connection, String sql, Object... values)
        throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
