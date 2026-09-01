package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.LedgerTimeoutException;
import com.corebanking.funds.infrastructure.postgres.JdbcLedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import com.corebanking.funds.infrastructure.postgres.SqlState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PostingTimeoutIT {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Inject
    DataSource dataSource;

    @Inject
    PostingTransactionTimeouts configuredTimeouts;

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void productionDeadlinesAreAppliedLocallyBeforeFinancialWork() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            configuredTimeouts.apply(connection);

            assertAll(
                () -> assertEquals("1s", setting(connection, "lock_timeout")),
                () -> assertEquals("3s", setting(connection, "statement_timeout")),
                () -> assertEquals("5s", setting(connection, "idle_in_transaction_session_timeout")));
            connection.rollback();
        }
    }

    @Test
    void blockedAccountLockHasATypedFiniteOutcomeAndIsNotRetried() throws SQLException {
        var retryPauses = new AtomicInteger();
        var timeouts = new PostingTransactionTimeouts(
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(3));
        var service = new PostingService(
            dataSource,
            new JdbcLedgerRepository(),
            new PostgresRetryPolicy((commandId, attempt) -> retryPauses.incrementAndGet()),
            PostingTransactionObserver.noop(),
            timeouts);

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockAccount(holder, TestPostingStack.PROVIDER_ASSET);

            long started = System.nanoTime();
            LedgerTimeoutException failure = assertThrows(
                LedgerTimeoutException.class,
                () -> service.post(command()));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertAll(
                () -> assertTrue(SqlState.occursIn(failure, SqlState.LOCK_NOT_AVAILABLE)),
                () -> assertEquals(0, retryPauses.get(), "lock timeout must not broaden retry policy"),
                () -> assertTrue(elapsed.compareTo(Duration.ofMillis(150)) >= 0, elapsed::toString),
                () -> assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, elapsed::toString),
                () -> assertEquals(0, count("funds.idempotency_command")),
                () -> assertEquals(0, count("funds.journal")),
                () -> assertEquals(0, count("funds.posting")),
                () -> assertEquals(0, count("funds.outbox_event")));
            holder.rollback();
        }
    }

    @Test
    void statementDeadlineMapsToTheSameTypedNonRetryableOutcome() throws SQLException {
        var timeouts = new PostingTransactionTimeouts(
            Duration.ofMillis(50), Duration.ofMillis(150), Duration.ofSeconds(2));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            timeouts.apply(connection);

            SQLException sqlFailure = assertThrows(SQLException.class, () -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_sleep(1)");
                }
            });

            assertAll(
                () -> assertEquals(SqlState.QUERY_CANCELED, sqlFailure.getSQLState()),
                () -> assertInstanceOf(
                    LedgerTimeoutException.class, SqlState.persistenceFailure(sqlFailure)),
                () -> assertTrue(!SqlState.isRetryable(sqlFailure)));
            connection.rollback();
        }
    }

    private PostingCommand command() {
        UUID commandId = TestPostingStack.uuid(2_000);
        var draft = new JournalDraft(
            TestPostingStack.uuid(2_001), commandId, TestPostingStack.uuid(2_002),
            TestPostingStack.uuid(2_003), TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.PERIOD_ID, "TIMEOUT_TEST", "Blocked account lock",
            Instant.parse("2026-01-15T10:00:00Z"), LocalDate.of(2026, 1, 15), null, 1,
            List.of(
                new PostingLine(TestPostingStack.uuid(2_004), TestPostingStack.PROVIDER_ASSET,
                    NGN, 100, 0, Map.of()),
                new PostingLine(TestPostingStack.uuid(2_005), TestPostingStack.CUSTOMER_LIABILITY,
                    NGN, -100, 0, Map.of())));
        return new PostingCommand(commandId, new CanonicalCommandHasher().postingV1(draft), draft);
    }

    private static void lockAccount(Connection connection, UUID accountId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT account_id FROM funds.ledger_account WHERE account_id = ? FOR UPDATE
            """)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }

    private static String setting(Connection connection, String setting) throws SQLException {
        if (!setting.matches("[a-z_]+")) {
            throw new IllegalArgumentException("unsafe setting name");
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SHOW " + setting)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private long count(String table) throws SQLException {
        if (!table.matches("funds\\.[a-z_]+")) {
            throw new IllegalArgumentException("unsafe table name");
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
