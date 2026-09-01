package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
    void newFactsCannotClaimLegacyHashSchemes() throws SQLException {
        UUID commandId = TestPostingStack.uuid(995);
        UUID journalId = TestPostingStack.uuid(996);
        try (var connection = dataSource.getConnection()) {
            assertConstraint("new_command_hash_scheme", () -> executeInRollback(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, request_hash_scheme, state, created_at)
                VALUES (?, ?, 'V004_OPAQUE', 'IN_PROGRESS', CURRENT_TIMESTAMP)
                """, commandId, "a".repeat(64)));
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                """, commandId, "a".repeat(64));
            assertConstraint("new_journal_hash_scheme", () -> executeInRollback(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, chart_version_id, period_id, transaction_type,
                     narration, booking_time, value_date, policy_version, canonical_hash,
                     canonical_hash_scheme)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DIRECT', 'legacy scheme injection',
                        TIMESTAMPTZ '2026-01-15 10:00:00+00', DATE '2026-01-15', 1, ?,
                        'V004_V1')
                """, journalId, commandId, TestPostingStack.uuid(997),
                TestPostingStack.uuid(998), TestPostingStack.LEGAL_ENTITY_ID,
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, "b".repeat(64)));
        }
    }

    @Test
    void completedCommandsMustIdentifyTheirOwnExactJournalResult() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertConstraint("completed_command_result_consistency",
                () -> attemptIncoherentCompletion(
                    connection, TestPostingStack.uuid(980), TestPostingStack.uuid(981),
                    TestPostingStack.uuid(982), false));
            assertConstraint("completed_command_result_consistency",
                () -> attemptIncoherentCompletion(
                    connection, TestPostingStack.uuid(983), TestPostingStack.uuid(984),
                    TestPostingStack.uuid(983), true));
        }
        assertEquals(0, count("funds.journal"));
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
                    (account_id, book_id, chart_version_id, account_code, account_currency,
                     account_class,
                     normal_balance, control_account_code, account_role, currency_policy,
                     permitted_direction)
                SELECT account_id, book_id, ?, account_code, account_currency, account_class,
                       normal_balance,
                       control_account_code, account_role, currency_policy, permitted_direction
                FROM funds.ledger_account_chart_mapping
                WHERE chart_version_id = ?
                """, nextChart, TestPostingStack.CHART_VERSION_ID);
            assertConstraint("one_active_chart_per_book_idx", () -> executeInRollback(connection, """
                UPDATE funds.chart_version
                SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-16 00:00:00+00'
                WHERE chart_version_id = ?
                """, nextChart));
            rotateChart(connection, TestPostingStack.BOOK_ID,
                TestPostingStack.CHART_VERSION_ID, nextChart,
                Instant.parse("2026-01-16T00:00:00Z"));
            assertConstraint("ledger_account_chart_mapping_frozen",
                () -> executeInRollback(connection, """
                    UPDATE funds.ledger_account_chart_mapping SET account_class = 'EQUITY'
                    WHERE account_id = ? AND chart_version_id = ?
                    """, TestPostingStack.CUSTOMER_LIABILITY, nextChart));
            assertConstraint("ledger_account_chart_mapping_frozen",
                () -> executeInRollback(connection, """
                    DELETE FROM funds.ledger_account_chart_mapping
                    WHERE account_id = ? AND chart_version_id = ?
                    """, TestPostingStack.CUSTOMER_LIABILITY, nextChart));
            assertConstraint("ledger_account_chart_mapping_frozen",
                () -> executeInRollback(connection, """
                    INSERT INTO funds.ledger_account_chart_mapping
                        (account_id, book_id, chart_version_id, account_code,
                         account_currency, account_class, normal_balance,
                         control_account_code, account_role)
                    SELECT account_id, book_id, ?, account_code, account_currency,
                           account_class, normal_balance, control_account_code, account_role
                    FROM funds.ledger_account_chart_mapping
                    WHERE account_id = ? AND chart_version_id = ?
                    """, nextChart, TestPostingStack.PROVIDER_ASSET,
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
             AND mapping.account_currency = posting.currency
            WHERE journal.journal_id = ? AND posting.account_id = ?
            """, historical.journalId(), TestPostingStack.CUSTOMER_LIABILITY));
    }

    @Test
    void chartActivationRequiresCompleteMappingsAndDraftCreation() throws SQLException {
        UUID partialChart = TestPostingStack.uuid(1_150);
        try (var connection = dataSource.getConnection()) {
            assertConstraint("active_chart_account_onboarding_deferred",
                () -> executeInRollback(connection, """
                    INSERT INTO funds.ledger_account
                        (account_id, book_id, account_scope, product_version_id,
                         currency, status, created_at)
                    VALUES (?, ?, 'INTERNAL', NULL, 'NGN', 'OPEN', CURRENT_TIMESTAMP)
                    """, TestPostingStack.uuid(1_148), TestPostingStack.BOOK_ID));
            assertConstraint("chart_version_must_start_draft", () -> executeInRollback(
                connection, """
                    INSERT INTO funds.chart_version
                        (chart_version_id, book_id, version, status, activated_at,
                         approval_reference)
                    VALUES (?, ?, 9, 'ACTIVE', TIMESTAMPTZ '2026-01-10 00:00:00+00',
                            'APP-DIRECT-ACTIVE')
                    """, TestPostingStack.uuid(1_149), TestPostingStack.BOOK_ID));

            createChartWithProviderMappingOnly(connection, partialChart);
            assertConstraint("chart_mapping_incomplete", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                partialChart, Instant.parse("2026-01-10T00:00:00Z")));
            assertEquals("ACTIVE", queryString(connection,
                "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
                TestPostingStack.CHART_VERSION_ID));
            assertEquals("DRAFT", queryString(connection,
                "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
                partialChart));
            execute(connection, """
                INSERT INTO funds.ledger_account_chart_mapping
                    (account_id, book_id, chart_version_id, account_code, account_currency,
                     account_class, normal_balance, control_account_code, account_role,
                     currency_policy, permitted_direction)
                SELECT account_id, book_id, ?, account_code, account_currency, account_class,
                       normal_balance, control_account_code, account_role, currency_policy,
                       permitted_direction
                FROM funds.ledger_account_chart_mapping
                WHERE chart_version_id = ? AND account_id = ?
                """, partialChart, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.CUSTOMER_LIABILITY);
            rotateChart(connection, TestPostingStack.BOOK_ID,
                TestPostingStack.CHART_VERSION_ID, partialChart,
                Instant.parse("2026-01-10T00:00:00Z"));
        }

        PostingResult governed = postingService.post(command(
            TestPostingStack.uuid(1_151), TestPostingStack.uuid(1_152), partialChart,
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null, 100));
        assertEquals(partialChart, queryUuid(
            "SELECT chart_version_id FROM funds.journal WHERE journal_id = ?",
            governed.journalId()));
    }

    @Test
    void governedChartRotationRejectsHistoricalAndInvalidEffectiveBoundsAtomically()
        throws SQLException {
        postingService.post(command(
            TestPostingStack.uuid(1_153), TestPostingStack.uuid(1_154),
            TestPostingStack.CHART_VERSION_ID,
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null,
            100));
        UUID candidateChart = TestPostingStack.uuid(1_155);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);

            assertConstraint("chart_rotation_historical_cutoff", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                candidateChart, Instant.parse("2026-01-15T10:00:00Z")));
            assertConstraint("chart_rotation_effective_bounds", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                candidateChart, Instant.parse("2025-12-31T23:59:59Z")));
            assertConstraint("chart_rotation_effective_bounds", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                candidateChart, Instant.parse("2099-01-01T00:00:00Z")));
        }

        assertEquals("ACTIVE", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            TestPostingStack.CHART_VERSION_ID));
        assertEquals("DRAFT", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            candidateChart));
    }

    @Test
    void governedChartRotationRequiresDistinctExistingLifecycleRowsForOneBook()
        throws SQLException {
        UUID candidateChart = TestPostingStack.uuid(1_156);
        UUID otherBook = TestPostingStack.uuid(1_157);
        UUID otherChart = TestPostingStack.uuid(1_158);
        UUID olderChart = TestPostingStack.uuid(1_160);
        UUID futureChart = TestPostingStack.uuid(1_161);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);
            execute(connection, """
                INSERT INTO funds.book
                    (book_id, legal_entity_id, functional_currency, timezone, calendar_code,
                     accounting_policy_version)
                VALUES (?, ?, 'NGN', 'Africa/Lagos', 'NG', 1)
                """, otherBook, TestPostingStack.uuid(1_159));
            execute(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, approval_reference)
                VALUES (?, ?, 2, 'DRAFT', 'APP-OTHER-CANDIDATE')
                """, otherChart, otherBook);
            execute(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, approval_reference)
                VALUES (?, ?, 1, 'DRAFT', 'APP-OLDER-CANDIDATE')
                """, olderChart, otherBook);

            assertConstraint("chart_rotation_distinct_versions", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.CHART_VERSION_ID,
                Instant.parse("2026-01-10T00:00:00Z")));
            assertConstraint("chart_rotation_versions_exist", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.uuid(9_999), Instant.parse("2026-01-10T00:00:00Z")));
            assertConstraint("chart_rotation_book_exists", () -> rotateChart(
                connection, TestPostingStack.uuid(9_998),
                TestPostingStack.CHART_VERSION_ID, candidateChart,
                Instant.parse("2026-01-10T00:00:00Z")));
            assertConstraint("chart_rotation_identifiers_required", () -> rotateChart(
                connection, null, TestPostingStack.CHART_VERSION_ID, candidateChart,
                Instant.parse("2026-01-10T00:00:00Z")));
            assertConstraint("chart_rotation_effective_time_required", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                candidateChart, null));
            assertConstraint("chart_rotation_book_consistency", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                otherChart, Instant.parse("2026-01-10T00:00:00Z")));
            assertConstraint("chart_rotation_current_active", () -> rotateChart(
                connection, TestPostingStack.BOOK_ID, candidateChart,
                TestPostingStack.CHART_VERSION_ID,
                Instant.parse("2026-01-10T00:00:00Z")));

            execute(connection, """
                UPDATE funds.chart_version
                SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
                WHERE chart_version_id = ?
                """, otherChart);
            assertConstraint("chart_rotation_version_order", () -> rotateChart(
                connection, otherBook, otherChart, olderChart,
                Instant.parse("2026-01-10T00:00:00Z")));
            execute(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, approval_reference)
                VALUES (?, ?, 3, 'DRAFT', 'APP-FUTURE-CANDIDATE')
                """, futureChart, otherBook);
            rotateChart(connection, otherBook, otherChart, futureChart,
                Instant.parse("2026-01-10T00:00:00Z"));
            assertConstraint("chart_rotation_candidate_draft", () -> rotateChart(
                connection, otherBook, futureChart, otherChart,
                Instant.parse("2026-01-11T00:00:00Z")));
        }

        assertEquals("ACTIVE", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            TestPostingStack.CHART_VERSION_ID));
        assertEquals("DRAFT", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            candidateChart));
    }

    @RepeatedTest(5)
    void governedChartRotationDoesNotDeadlockWithCandidateMappingInsert() throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_159);
        UUID closedAccount = TestPostingStack.uuid(1_158);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);
            execute(connection, """
                INSERT INTO funds.ledger_account
                    (account_id, book_id, account_scope, product_version_id,
                     currency, status, created_at)
                VALUES (?, ?, 'INTERNAL', NULL, 'NGN', 'CLOSED', CURRENT_TIMESTAMP)
                """, closedAccount, TestPostingStack.BOOK_ID);
        }

        SQLException rotationFailure = raceGovernedRotationWithCandidateMutation(
            candidateChart, connection -> execute(connection, """
                INSERT INTO funds.ledger_account_chart_mapping
                    (account_id, book_id, chart_version_id, account_code, account_currency,
                     account_class, normal_balance, control_account_code, account_role)
                VALUES (?, ?, ?, 'CLOSED-INTERNAL', 'NGN', 'ASSET', 'DEBIT',
                        'CLOSED-INTERNAL', 'INTERNAL')
                """, closedAccount, TestPostingStack.BOOK_ID, candidateChart));

        assertNull(rotationFailure);
        assertSuccessfulRotation(candidateChart);
        assertEquals(1, queryLong("""
            SELECT count(*) FROM funds.ledger_account_chart_mapping
            WHERE account_id = ? AND chart_version_id = ?
            """, closedAccount, candidateChart));
    }

    @RepeatedTest(5)
    void governedChartRotationDoesNotDeadlockWithCandidateMappingUpdate() throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_160);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);
        }

        SQLException rotationFailure = raceGovernedRotationWithCandidateMutation(
            candidateChart, connection -> execute(connection, """
                UPDATE funds.ledger_account_chart_mapping
                SET account_class = 'EQUITY'
                WHERE account_id = ? AND chart_version_id = ?
                """, TestPostingStack.CUSTOMER_LIABILITY, candidateChart));

        assertNull(rotationFailure);
        assertSuccessfulRotation(candidateChart);
        assertEquals("EQUITY", queryString("""
            SELECT account_class FROM funds.ledger_account_chart_mapping
            WHERE account_id = ? AND chart_version_id = ?
            """, TestPostingStack.CUSTOMER_LIABILITY, candidateChart));
    }

    @RepeatedTest(5)
    void governedChartRotationDoesNotDeadlockWithCandidateMappingDelete() throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_161);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);
        }

        SQLException rotationFailure = raceGovernedRotationWithCandidateMutation(
            candidateChart, connection -> execute(connection, """
                DELETE FROM funds.ledger_account_chart_mapping
                WHERE account_id = ? AND chart_version_id = ?
                """, TestPostingStack.CUSTOMER_LIABILITY, candidateChart));

        assertTrue(rotationFailure instanceof PSQLException,
            "expected governed completeness rejection but received " + rotationFailure);
        PSQLException postgresFailure = (PSQLException) rotationFailure;
        assertEquals("23514", postgresFailure.getSQLState());
        assertEquals("chart_mapping_incomplete",
            postgresFailure.getServerErrorMessage().getConstraint());
        assertEquals("ACTIVE", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            TestPostingStack.CHART_VERSION_ID));
        assertEquals("DRAFT", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            candidateChart));
        assertEquals(1, queryLong("""
            SELECT count(*) FROM funds.ledger_account_chart_mapping
            WHERE chart_version_id = ?
            """, candidateChart));
    }

    @Test
    void directJournalGovernanceWaitsForChartBeforeBookDuringRotation() throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_162);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<SQLException> rotation = null;
        Future<SQLException> journalInsert = null;
        try (var candidateBlocker = dataSource.getConnection();
             var rotationConnection = dataSource.getConnection();
             var journalConnection = dataSource.getConnection()) {
            candidateBlocker.setAutoCommit(false);
            queryLong(candidateBlocker, """
                SELECT governance_revision
                FROM funds.chart_version WHERE chart_version_id = ? FOR UPDATE
                """, candidateChart);

            rotationConnection.setAutoCommit(false);
            int rotationPid = (int) queryLong(
                rotationConnection, "SELECT pg_backend_pid()");
            rotation = executor.submit(() -> {
                try {
                    rotateChart(rotationConnection, TestPostingStack.BOOK_ID,
                        TestPostingStack.CHART_VERSION_ID, candidateChart,
                        Instant.parse("2026-01-10T00:00:00Z"));
                    rotationConnection.commit();
                    return null;
                } catch (SQLException failure) {
                    rotationConnection.rollback();
                    return failure;
                }
            });
            awaitBackendLock(rotationPid, rotation);

            int journalPid = (int) queryLong(journalConnection, "SELECT pg_backend_pid()");
            journalInsert = executor.submit(() -> {
                try {
                    insertDirectJournal(
                        journalConnection, TestPostingStack.uuid(1_163),
                        TestPostingStack.uuid(1_164), TestPostingStack.BOOK_ID,
                        TestPostingStack.CHART_VERSION_ID, TestPostingStack.PERIOD_ID,
                        Instant.parse("2026-01-15T10:00:00Z"),
                        LocalDate.of(2026, 1, 15), null, "DIRECT");
                    return null;
                } catch (SQLException failure) {
                    return failure;
                }
            });
            awaitBackendLock(journalPid, journalInsert);

            candidateBlocker.commit();
            SQLException rotationFailure = rotation.get(5, TimeUnit.SECONDS);
            SQLException journalFailure = journalInsert.get(5, TimeUnit.SECONDS);
            assertTrue(rotationFailure == null || !"40P01".equals(rotationFailure.getSQLState()),
                "rotation deadlocked against direct journal governance: " + rotationFailure);
            assertTrue(journalFailure == null || !"40P01".equals(journalFailure.getSQLState()),
                "direct journal governance deadlocked against rotation: " + journalFailure);
            assertNull(rotationFailure);
            assertTrue(journalFailure instanceof PSQLException,
                "expected retired-chart rejection but received " + journalFailure);
            PSQLException postgresFailure = (PSQLException) journalFailure;
            assertEquals("23514", postgresFailure.getSQLState());
            assertEquals("journal_effective_chart",
                postgresFailure.getServerErrorMessage().getConstraint());
        } finally {
            if (rotation != null && !rotation.isDone()) {
                rotation.cancel(true);
            }
            if (journalInsert != null && !journalInsert.isDone()) {
                journalInsert.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private SQLException raceGovernedRotationWithCandidateMutation(
        UUID candidateChart,
        ConnectionSqlAction candidateMutation
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<SQLException> mapping = null;
        Future<SQLException> rotation = null;
        try (var bookBlocker = dataSource.getConnection();
             var rotationConnection = dataSource.getConnection();
             var mappingConnection = dataSource.getConnection()) {
            bookBlocker.setAutoCommit(false);
            queryLong(bookBlocker, """
                SELECT chart_governance_revision
                FROM funds.book
                WHERE book_id = ?
                FOR UPDATE
                """, TestPostingStack.BOOK_ID);

            mappingConnection.setAutoCommit(false);
            int mappingBackendPid = (int) queryLong(
                mappingConnection, "SELECT pg_backend_pid()");
            mapping = executor.submit(() -> {
                try {
                    candidateMutation.run(mappingConnection);
                    mappingConnection.commit();
                    return null;
                } catch (SQLException failure) {
                    mappingConnection.rollback();
                    return failure;
                }
            });
            awaitBackendLock(mappingBackendPid, mapping);

            rotationConnection.setAutoCommit(false);
            int rotationBackendPid = (int) queryLong(
                rotationConnection, "SELECT pg_backend_pid()");
            rotation = executor.submit(() -> {
                try {
                    rotateChart(rotationConnection, TestPostingStack.BOOK_ID,
                        TestPostingStack.CHART_VERSION_ID, candidateChart,
                        Instant.parse("2026-01-10T00:00:00Z"));
                    rotationConnection.commit();
                    return null;
                } catch (SQLException failure) {
                    rotationConnection.rollback();
                    return failure;
                }
            });

            awaitBackendLock(rotationBackendPid, rotation);

            assertEquals(1, queryLong(bookBlocker, """
                SELECT count(*)
                FROM unnest(pg_blocking_pids(?)) AS blocker(pid)
                WHERE blocker.pid = ?
                """, rotationBackendPid, mappingBackendPid),
                "rotation must wait on the candidate-chart lock acquired by mapping DML");

            bookBlocker.commit();
            SQLException mutationFailure = mapping.get(5, TimeUnit.SECONDS);
            SQLException rotationFailure = rotation.get(5, TimeUnit.SECONDS);

            assertNull(mutationFailure,
                "candidate mapping mutation must have a defined successful outcome");
            assertTrue(rotationFailure == null || !"40P01".equals(rotationFailure.getSQLState()),
                "governed rotation deadlocked: " + rotationFailure);
            return rotationFailure;
        } finally {
            if (mapping != null && !mapping.isDone()) {
                mapping.cancel(true);
            }
            if (rotation != null && !rotation.isDone()) {
                rotation.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void repeatableReadGovernedRotationSerializesAgainstAConcurrentMappingDeletion()
        throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_160);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);
        }

        SQLException activationFailure = raceGovernedRotation(
            candidateChart, Connection.TRANSACTION_REPEATABLE_READ,
            connection -> execute(connection, """
                DELETE FROM funds.ledger_account_chart_mapping
                WHERE account_id = ? AND chart_version_id = ?
                """, TestPostingStack.CUSTOMER_LIABILITY, candidateChart));
        assertTrue(activationFailure != null,
            "snapshot-isolated activation committed from a stale complete mapping view");
        assertEquals("40001", activationFailure.getSQLState());

        assertEquals("DRAFT", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            candidateChart));
        try (var connection = dataSource.getConnection()) {
            assertEquals(1, queryLong(connection, """
                SELECT count(*) FROM funds.ledger_account_chart_mapping
                WHERE chart_version_id = ?
                """, candidateChart));
        }
    }

    @Test
    void repeatableReadActivationSerializesAgainstConcurrentOpenAccountCreation()
        throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_170);
        UUID newAccount = TestPostingStack.uuid(1_171);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidateAndRetireCurrent(connection, candidateChart);
        }

        SQLException activationFailure = raceRepeatableReadActivation(
            candidateChart, connection -> execute(connection, """
                INSERT INTO funds.ledger_account
                    (account_id, book_id, account_scope, product_version_id,
                     currency, status, created_at)
                VALUES (?, ?, 'INTERNAL', NULL, 'NGN', 'OPEN', CURRENT_TIMESTAMP)
                """, newAccount, TestPostingStack.BOOK_ID));
        assertTrue(activationFailure != null,
            "snapshot-isolated activation ignored the concurrently created open account");
        assertEquals("40001", activationFailure.getSQLState());
        assertEquals("DRAFT", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            candidateChart));
        try (var connection = dataSource.getConnection()) {
            assertEquals(1, queryLong(connection, """
                SELECT count(*) FROM funds.ledger_account WHERE account_id = ?
                """, newAccount));
            assertEquals(0, queryLong(connection, """
                SELECT count(*) FROM funds.ledger_account_chart_mapping
                WHERE chart_version_id = ? AND account_id = ?
                """, candidateChart, newAccount));
        }
    }

    @Test
    void readCommittedGovernedRotationRevalidatesAfterConcurrentMappingDeletion()
        throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_180);
        try (var connection = dataSource.getConnection()) {
            createCompleteCandidate(connection, candidateChart);
        }

        SQLException activationFailure = raceGovernedRotation(
            candidateChart, Connection.TRANSACTION_READ_COMMITTED,
            connection -> execute(connection, """
                DELETE FROM funds.ledger_account_chart_mapping
                WHERE account_id = ? AND chart_version_id = ?
                """, TestPostingStack.CUSTOMER_LIABILITY, candidateChart));
        assertTrue(activationFailure instanceof PSQLException,
            () -> "expected PostgreSQL activation rejection but received "
                + activationFailure);
        PSQLException postgresFailure = (PSQLException) activationFailure;
        assertEquals("23514", postgresFailure.getSQLState());
        assertEquals("chart_mapping_incomplete",
            postgresFailure.getServerErrorMessage().getConstraint());
        assertEquals("DRAFT", queryString(
            "SELECT status FROM funds.chart_version WHERE chart_version_id = ?",
            candidateChart));
    }

    @RepeatedTest(5)
    void repeatableReadChartCreationSerializesAgainstAnEarlierOpenAccountInsert()
        throws Exception {
        UUID candidateChart = TestPostingStack.uuid(1_190);
        UUID newAccount = TestPostingStack.uuid(1_191);
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                UPDATE funds.chart_version
                SET status = 'RETIRED', retired_at = TIMESTAMPTZ '2026-01-10 00:00:00+00'
                WHERE chart_version_id = ?
                """, TestPostingStack.CHART_VERSION_ID);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<SQLException> chartLifecycle = null;
        try (var accountConnection = dataSource.getConnection();
             var chartConnection = dataSource.getConnection()) {
            accountConnection.setAutoCommit(false);
            execute(accountConnection, """
                INSERT INTO funds.ledger_account
                    (account_id, book_id, account_scope, product_version_id,
                     currency, status, created_at)
                VALUES (?, ?, 'INTERNAL', NULL, 'NGN', 'OPEN', CURRENT_TIMESTAMP)
                """, newAccount, TestPostingStack.BOOK_ID);

            chartConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            chartConnection.setAutoCommit(false);
            int chartBackendPid = (int) queryLong(chartConnection, "SELECT pg_backend_pid()");
            chartLifecycle = executor.submit(() -> {
                try {
                    execute(chartConnection, """
                        INSERT INTO funds.chart_version
                            (chart_version_id, book_id, version, status, approval_reference)
                        VALUES (?, ?, 2, 'DRAFT', 'APP-CONCURRENT-CHART')
                        """, candidateChart, TestPostingStack.BOOK_ID);
                    execute(chartConnection, """
                        INSERT INTO funds.ledger_account_chart_mapping
                            (account_id, book_id, chart_version_id, account_code,
                             account_currency, account_class, normal_balance,
                             control_account_code, account_role, currency_policy,
                             permitted_direction)
                        SELECT account_id, book_id, ?, account_code, account_currency,
                               account_class, normal_balance, control_account_code,
                               account_role, currency_policy, permitted_direction
                        FROM funds.ledger_account_chart_mapping
                        WHERE chart_version_id = ?
                        """, candidateChart, TestPostingStack.CHART_VERSION_ID);
                    execute(chartConnection, """
                        UPDATE funds.chart_version
                        SET status = 'ACTIVE',
                            activated_at = TIMESTAMPTZ '2026-01-10 00:00:00+00'
                        WHERE chart_version_id = ?
                        """, candidateChart);
                    chartConnection.commit();
                    return null;
                } catch (SQLException failure) {
                    chartConnection.rollback();
                    return failure;
                }
            });

            awaitBackendLockOrCompletion(chartBackendPid, chartLifecycle);
            accountConnection.commit();
            SQLException lifecycleFailure = chartLifecycle.get(5, TimeUnit.SECONDS);
            assertTrue(lifecycleFailure != null,
                "chart created from a snapshot that omitted the concurrent open account");
            assertEquals("40001", lifecycleFailure.getSQLState());
        } finally {
            if (chartLifecycle != null && !chartLifecycle.isDone()) {
                chartLifecycle.cancel(true);
            }
            executor.shutdownNow();
        }

        try (var connection = dataSource.getConnection()) {
            assertEquals(0, queryLong(connection, """
                SELECT count(*) FROM funds.chart_version WHERE chart_version_id = ?
                """, candidateChart));
            assertEquals(1, queryLong(connection, """
                SELECT count(*) FROM funds.ledger_account WHERE account_id = ?
                """, newAccount));
        }
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
            assertConstraint("posting_dimensions_string_values_check",
                () -> insertDirectPostingFixture(connection, TestPostingStack.uuid(1_226),
                    TestPostingStack.uuid(1_227), 1, "{\"numeric\":1}"));
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
        return new PostingCommand(commandId, new CanonicalCommandHasher().postingV2(draft), draft);
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
                        'journalId', ?::text,
                        'journalSequence', (SELECT journal_sequence FROM funds.journal
                                            WHERE journal_id = ?),
                        'canonicalHash', ?::text)
                WHERE command_id = ?
                """, journalId, journalId, journalId, "b".repeat(64), commandId);
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

    private static void attemptIncoherentCompletion(
        Connection connection,
        UUID journalOwnerCommandId,
        UUID journalId,
        UUID completedCommandId,
        boolean corruptResult
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertDirectHeader(
                connection, journalOwnerCommandId, journalId, null, "DIRECT");
            if (!completedCommandId.equals(journalOwnerCommandId)) {
                execute(connection, """
                    INSERT INTO funds.idempotency_command
                        (command_id, request_hash, state, created_at)
                    VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                    """, completedCommandId, "e".repeat(64));
            }
            execute(connection, """
                UPDATE funds.idempotency_command
                SET state = 'COMPLETED', journal_id = ?, completed_at = CURRENT_TIMESTAMP,
                    result_json = jsonb_build_object(
                        'journalId', ?::text,
                        'journalSequence', (SELECT journal_sequence FROM funds.journal
                                            WHERE journal_id = ?),
                        'canonicalHash', ?::text)
                WHERE command_id = ?
                """, journalId, journalId, journalId,
                corruptResult ? "e".repeat(64) : "d".repeat(64), completedCommandId);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
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
                    'journalId', ?::text,
                    'journalSequence', (SELECT journal_sequence FROM funds.journal
                                        WHERE journal_id = ?),
                    'canonicalHash', ?::text)
            WHERE command_id = ?
            """, journalId, journalId, journalId, "d".repeat(64), commandId);
    }

    private static void createChartWithProviderMappingOnly(
        Connection connection,
        UUID chartVersionId
    ) throws SQLException {
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES (?, ?, 2, 'DRAFT', 'APP-PARTIAL-CHART')
            """, chartVersionId, TestPostingStack.BOOK_ID);
        execute(connection, """
            INSERT INTO funds.ledger_account_chart_mapping
                (account_id, book_id, chart_version_id, account_code, account_currency,
                 account_class, normal_balance, control_account_code, account_role,
                 currency_policy, permitted_direction)
            SELECT account_id, book_id, ?, account_code, account_currency, account_class,
                   normal_balance, control_account_code, account_role, currency_policy,
                   permitted_direction
            FROM funds.ledger_account_chart_mapping
            WHERE chart_version_id = ? AND account_id = ?
            """, chartVersionId, TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.PROVIDER_ASSET);
    }

    private static void createCompleteCandidateAndRetireCurrent(
        Connection connection,
        UUID chartVersionId
    ) throws SQLException {
        createCompleteCandidate(connection, chartVersionId);
        execute(connection, """
            UPDATE funds.chart_version
            SET status = 'RETIRED', retired_at = TIMESTAMPTZ '2026-01-10 00:00:00+00'
            WHERE chart_version_id = ?
            """, TestPostingStack.CHART_VERSION_ID);
    }

    private static void createCompleteCandidate(
        Connection connection,
        UUID chartVersionId
    ) throws SQLException {
        createChartWithProviderMappingOnly(connection, chartVersionId);
        execute(connection, """
            INSERT INTO funds.ledger_account_chart_mapping
                (account_id, book_id, chart_version_id, account_code, account_currency,
                 account_class, normal_balance, control_account_code, account_role,
                 currency_policy, permitted_direction)
            SELECT account_id, book_id, ?, account_code, account_currency, account_class,
                   normal_balance, control_account_code, account_role, currency_policy,
                   permitted_direction
            FROM funds.ledger_account_chart_mapping
            WHERE chart_version_id = ? AND account_id = ?
            """, chartVersionId, TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.CUSTOMER_LIABILITY);
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
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES (?, ?, 1, 'DRAFT', 'APP-OTHER')
            """, chartVersionId, bookId);
        execute(connection, """
            INSERT INTO funds.accounting_period
                (period_id, book_id, business_date_from, business_date_to, status)
            VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
            """, periodId, bookId);
        execute(connection, """
            UPDATE funds.chart_version
            SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
            WHERE chart_version_id = ?
            """, chartVersionId);
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

    private long queryLong(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return queryLong(connection, sql, values);
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

    private void awaitBackendLock(int backendPid, Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (operation.isDone()) {
                throw new AssertionError(
                    "governed database operation completed before the expected lock wait");
            }
            try (var connection = dataSource.getConnection()) {
                if (queryLong(connection, """
                    SELECT count(*) FROM pg_stat_activity
                    WHERE pid = ? AND wait_event_type = 'Lock'
                    """, backendPid) == 1) {
                    return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("governed database operation did not reach its expected lock");
    }

    private void awaitBackendLockOrCompletion(int backendPid, Future<?> operation)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (operation.isDone()) {
                return;
            }
            try (var connection = dataSource.getConnection()) {
                if (queryLong(connection, """
                    SELECT count(*) FROM pg_stat_activity
                    WHERE pid = ? AND wait_event_type = 'Lock'
                    """, backendPid) == 1) {
                    return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("chart lifecycle neither completed nor reached its book lock");
    }

    private SQLException raceRepeatableReadActivation(
        UUID chartVersionId,
        ConnectionSqlAction concurrentMutation
    ) throws Exception {
        return raceActivation(
            chartVersionId, Connection.TRANSACTION_REPEATABLE_READ, concurrentMutation);
    }

    private SQLException raceGovernedRotation(
        UUID chartVersionId,
        int transactionIsolation,
        ConnectionSqlAction concurrentMutation
    ) throws Exception {
        return raceChartLifecycle(
            chartVersionId, transactionIsolation, concurrentMutation, true);
    }

    private SQLException raceActivation(
        UUID chartVersionId,
        int transactionIsolation,
        ConnectionSqlAction concurrentMutation
    ) throws Exception {
        return raceChartLifecycle(
            chartVersionId, transactionIsolation, concurrentMutation, false);
    }

    private SQLException raceChartLifecycle(
        UUID chartVersionId,
        int transactionIsolation,
        ConnectionSqlAction concurrentMutation,
        boolean governedRotation
    ) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<SQLException> activation = null;
        try (var mutationConnection = dataSource.getConnection();
             var activationConnection = dataSource.getConnection()) {
            mutationConnection.setAutoCommit(false);
            concurrentMutation.run(mutationConnection);

            activationConnection.setTransactionIsolation(transactionIsolation);
            activationConnection.setAutoCommit(false);
            int activationBackendPid = (int) queryLong(
                activationConnection, "SELECT pg_backend_pid()");
            activation = executor.submit(() -> {
                try {
                    if (governedRotation) {
                        rotateChart(activationConnection, TestPostingStack.BOOK_ID,
                            TestPostingStack.CHART_VERSION_ID, chartVersionId,
                            Instant.parse("2026-01-10T00:00:00Z"));
                    } else {
                        execute(activationConnection, """
                            UPDATE funds.chart_version
                            SET status = 'ACTIVE',
                                activated_at = TIMESTAMPTZ '2026-01-10 00:00:00+00'
                            WHERE chart_version_id = ?
                            """, chartVersionId);
                    }
                    activationConnection.commit();
                    return null;
                } catch (SQLException failure) {
                    activationConnection.rollback();
                    return failure;
                }
            });

            awaitBackendLock(activationBackendPid, activation);
            mutationConnection.commit();
            return activation.get(5, TimeUnit.SECONDS);
        } finally {
            if (activation != null && !activation.isDone()) {
                activation.cancel(true);
            }
            executor.shutdownNow();
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

    private void assertSuccessfulRotation(UUID candidateChart) throws SQLException {
        assertEquals(2, queryLong("""
            SELECT count(*)
            FROM funds.chart_version
            WHERE (chart_version_id = ? AND status = 'RETIRED'
                   AND retired_at = TIMESTAMPTZ '2026-01-10 00:00:00+00')
               OR (chart_version_id = ? AND status = 'ACTIVE'
                   AND activated_at = TIMESTAMPTZ '2026-01-10 00:00:00+00')
            """, TestPostingStack.CHART_VERSION_ID, candidateChart));
        assertEquals(1, queryLong("""
            SELECT count(*) FROM funds.chart_version
            WHERE book_id = ? AND status = 'ACTIVE'
            """, TestPostingStack.BOOK_ID));
    }

    private static void rotateChart(
        Connection connection,
        UUID bookId,
        UUID currentChart,
        UUID candidateChart,
        Instant effectiveAt
    ) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT funds.rotate_chart_version(?, ?, ?, ?)")) {
            statement.setObject(1, bookId);
            statement.setObject(2, currentChart);
            statement.setObject(3, candidateChart);
            statement.setObject(4,
                effectiveAt == null ? null : effectiveAt.atOffset(ZoneOffset.UTC));
            try (var rows = statement.executeQuery()) {
                rows.next();
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionSqlAction {
        void run(Connection connection) throws SQLException;
    }
}
