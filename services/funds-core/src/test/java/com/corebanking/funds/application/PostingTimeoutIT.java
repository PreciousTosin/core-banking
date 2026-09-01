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

/**
 * Proves the ACC-25 transaction-local deadlines: the 1s lock, 3s statement and 5s
 * idle-in-transaction timeouts are set on the posting connection before any financial work,
 * and a blocked row lock or a cancelled statement surfaces as a typed
 * {@link LedgerTimeoutException} that {@link PostgresRetryPolicy} never retries. Runs on the
 * Quarkus dev-services PostgreSQL container (Testcontainers) over the TestPostingStack
 * fixture graph. Catches a posting path that would hang on a contended account row, or a
 * retry policy quietly widened to cover timeouts.
 */
@QuarkusTest
class PostingTimeoutIT {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Inject
    DataSource dataSource;

    // Bound from funds.posting.* in the main application.properties; the test profile does not
    // override them, so this is the production 1s/3s/5s configuration.
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

    // Sequence: a holder connection takes PROVIDER_ASSET FOR UPDATE and never commits; a service
    // with a 250ms lock timeout then posts against that account. Asserts SQLSTATE 55P03 in the
    // cause chain, zero retry pauses, an elapsed time bounded by the lock timeout rather than the
    // statement timeout, and no command, journal, posting or outbox row left behind.
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

    // pg_sleep(1) against a 150ms statement_timeout exercises the 57014 (query_canceled) mapping
    // directly, without a posting, so the SqlState classification is tested on its own.
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
        return new PostingCommand(commandId, new CanonicalCommandHasher().postingV2(draft), draft);
    }

    /**
     * Direct SQL that bypasses the service: holds the ledger_account row FOR UPDATE on a
     * connection that never commits, so the service's own account lock must time out.
     */
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
