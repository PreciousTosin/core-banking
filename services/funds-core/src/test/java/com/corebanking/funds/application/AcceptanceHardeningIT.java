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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            assertThrows(SQLException.class, () -> executeInRollback(connection, """
                UPDATE funds.product_version SET finance_principle = 'NON_INTEREST'
                WHERE product_version_id = ?
                """, TestPostingStack.PRODUCT_VERSION_ID));
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
            assertThrows(SQLException.class, () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_010), TestPostingStack.uuid(1_011),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-31T23:30:00Z"),
                LocalDate.of(2026, 1, 31), null, "DIRECT"));
        }
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
            assertThrows(SQLException.class, () -> executeInRollback(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, activated_at, approval_reference)
                VALUES (?, ?, 2, 'ACTIVE', TIMESTAMPTZ '2026-01-16 00:00:00+00', 'APP-CHART-2')
                """, nextChart, TestPostingStack.BOOK_ID));

            connection.setAutoCommit(false);
            execute(connection, """
                UPDATE funds.chart_version
                SET status = 'RETIRED', retired_at = TIMESTAMPTZ '2026-01-16 00:00:00+00'
                WHERE chart_version_id = ?
                """, TestPostingStack.CHART_VERSION_ID);
            execute(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, activated_at, approval_reference)
                VALUES (?, ?, 2, 'ACTIVE', TIMESTAMPTZ '2026-01-16 00:00:00+00', 'APP-CHART-2')
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
            connection.commit();
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
    }

    @Test
    void databaseRejectsDisguisedAndInexactReversalFacts() throws SQLException {
        PostingResult original = postingService.post(command(
            TestPostingStack.uuid(1_200), TestPostingStack.uuid(1_201),
            TestPostingStack.CHART_VERSION_ID,
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null,
            100));
        try (var connection = dataSource.getConnection()) {
            assertThrows(SQLException.class, () -> insertDirectJournal(
                connection, TestPostingStack.uuid(1_202), TestPostingStack.uuid(1_203),
                TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                TestPostingStack.PERIOD_ID, Instant.parse("2026-01-15T11:00:00Z"),
                LocalDate.of(2026, 1, 15), original.journalId(), "ALTERNATE_TYPE"));
            assertThrows(SQLException.class,
                () -> insertInexactReversal(connection, original.journalId()));
        }
        assertEquals(1, count("funds.journal"));
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'direct', ?, ?, ?, 1, ?)
                """, journalId, commandId, TestPostingStack.uuid(9_001),
                TestPostingStack.uuid(9_002), TestPostingStack.LEGAL_ENTITY_ID, bookId,
                chartVersionId, periodId, type, bookingTime, valueDate, reversalOf,
                "e".repeat(64));
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
}
