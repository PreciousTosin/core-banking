package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.AccountingPeriodClosedException;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PostingServiceIT {
    private static final UUID BOOK_ID = uuid(1);
    private static final UUID CHART_VERSION_ID = uuid(2);
    private static final UUID PRODUCT_ID = uuid(3);
    private static final UUID PRODUCT_VERSION_ID = uuid(4);
    private static final UUID PROVIDER_ASSET = uuid(5);
    private static final UUID CUSTOMER_LIABILITY = uuid(6);
    private static final UUID PERIOD_ID = uuid(7);
    private static final UUID LEGAL_ENTITY_ID = uuid(8);
    private static final UUID COMMAND_ID = uuid(20);
    private static final UUID JOURNAL_ID = uuid(21);
    private static final UUID PROVIDER_POSTING_ID = uuid(22);
    private static final UUID CUSTOMER_POSTING_ID = uuid(23);
    private static final CurrencyCode NGN = new CurrencyCode("NGN");
    private static final CurrencyCode USD = new CurrencyCode("USD");
    private static final String DIFFERENT_HASH = "f".repeat(64);

    @Inject
    DataSource dataSource;

    @Inject
    PostingService postingService;

    @BeforeEach
    void setUp() throws SQLException {
        truncateAllTables();
        try (var connection = dataSource.getConnection()) {
            insertReferenceGraph(connection);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        truncateAllTables();
    }

    @Test
    void postsExampleAInflowAsOneAtomicLedgerEffect() throws SQLException {
        var command = exampleACommand(COMMAND_ID, JOURNAL_ID);

        PostingResult result = postingService.post(command);

        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertEquals(JOURNAL_ID, result.journalId()),
                () -> assertEquals(command.requestHash(), result.canonicalHash()),
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(100_000, balance(connection, PROVIDER_ASSET)),
                () -> assertEquals(-100_000, balance(connection, CUSTOMER_LIABILITY)),
                () -> assertEquals(1, accountSequence(connection, PROVIDER_ASSET)),
                () -> assertEquals(1, accountSequence(connection, CUSTOMER_LIABILITY)),
                () -> assertEquals(100_000, controlTotal(connection, "PROVIDER-CASH")),
                () -> assertEquals(-100_000, controlTotal(connection, "CUSTOMER-DEPOSITS")),
                () -> assertEquals(1, queryLong(connection, """
                    SELECT count(*) FROM funds.idempotency_command
                    WHERE command_id = '%s' AND request_hash = '%s' AND state = 'COMPLETED'
                      AND journal_id = '%s' AND result_json ->> 'canonicalHash' = '%s'
                    """.formatted(COMMAND_ID, command.requestHash(), JOURNAL_ID, command.requestHash()))),
                () -> assertEquals(1, queryLong(connection, """
                    SELECT count(*) FROM funds.outbox_event
                    WHERE aggregate_id = '%s' AND aggregate_version = %d
                      AND event_type = 'JournalPosted' AND published_at IS NULL
                    """.formatted(JOURNAL_ID, result.journalSequence()))));
        }
    }

    @Test
    void completedCommandWithSameHashReturnsStoredResultWithoutReposting() throws SQLException {
        var command = exampleACommand(COMMAND_ID, JOURNAL_ID);
        PostingResult first = postingService.post(command);

        PostingResult replay = postingService.post(command);

        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertEquals(first, replay),
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")),
                () -> assertEquals(100_000, balance(connection, PROVIDER_ASSET)),
                () -> assertEquals(-100_000, balance(connection, CUSTOMER_LIABILITY)));
        }
    }

    @Test
    void completedCommandWithDifferentHashIsAnIdempotencyConflict() throws SQLException {
        var command = exampleACommand(COMMAND_ID, JOURNAL_ID);
        PostingResult first = postingService.post(command);
        var conflict = new PostingCommand(COMMAND_ID, DIFFERENT_HASH, command.journal());

        assertThrows(IdempotencyConflictException.class, () -> postingService.post(conflict));

        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")),
                () -> assertEquals(first.canonicalHash(), queryString(connection, """
                    SELECT request_hash FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(COMMAND_ID))));
        }
    }

    @Test
    void closedExplicitPeriodRejectsCommandWithoutCommittingAnyPostingRows() throws SQLException {
        execute("UPDATE funds.accounting_period SET status = 'CLOSED' WHERE period_id = '" + PERIOD_ID + "'");

        assertThrows(
            AccountingPeriodClosedException.class,
            () -> postingService.post(exampleACommand(COMMAND_ID, JOURNAL_ID)));

        assertNoPostingRows();
    }

    @Test
    void accountCurrencyMismatchRollsBackEveryPostingRow() throws SQLException {
        var draft = journal(
            COMMAND_ID,
            JOURNAL_ID,
            new PostingLine(PROVIDER_POSTING_ID, PROVIDER_ASSET, USD, 100_000, 0, Map.of()),
            new PostingLine(CUSTOMER_POSTING_ID, CUSTOMER_LIABILITY, USD, -100_000, 0, Map.of()));
        var command = command(draft);

        assertThrows(RuntimeException.class, () -> postingService.post(command));

        assertNoPostingRows();
    }

    @Test
    void materialisedBalanceOverflowRollsBackEveryChange() throws SQLException {
        execute("""
            INSERT INTO funds.materialised_balance
                (account_id, signed_posting_total, latest_account_sequence, version)
            VALUES ('%s', %d, 9, 9)
            """.formatted(PROVIDER_ASSET, Long.MAX_VALUE));
        var draft = journal(
            COMMAND_ID,
            JOURNAL_ID,
            new PostingLine(PROVIDER_POSTING_ID, PROVIDER_ASSET, NGN, 1, 0, Map.of()),
            new PostingLine(CUSTOMER_POSTING_ID, CUSTOMER_LIABILITY, NGN, -1, 0, Map.of()));

        assertThrows(MonetaryOverflowException.class, () -> postingService.post(command(draft)));

        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertEquals(Long.MAX_VALUE, balance(connection, PROVIDER_ASSET)),
                () -> assertEquals(9, accountSequence(connection, PROVIDER_ASSET)),
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.materialised_balance")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.idempotency_command")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.control_account_projection")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")));
        }
    }

    @Test
    void retryPolicyWalksCauseChainAndRetriesOnlySerializationFailures() {
        var attempts = new AtomicInteger();
        var delayedAttempts = new ArrayList<Integer>();
        var policy = new PostgresRetryPolicy((commandId, attempt) -> delayedAttempts.add(attempt));

        String result = policy.execute(COMMAND_ID, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException(
                    new LedgerPersistenceException(new SQLException("serialization", "40001")));
            }
            return "posted";
        });

        assertAll(
            () -> assertEquals("posted", result),
            () -> assertEquals(3, attempts.get()),
            () -> assertEquals(List.of(1, 2), delayedAttempts));
    }

    @Test
    void retryPolicyStopsAfterFiveAttempts() {
        var attempts = new AtomicInteger();
        var delayedAttempts = new ArrayList<Integer>();
        var policy = new PostgresRetryPolicy((commandId, attempt) -> delayedAttempts.add(attempt));

        assertThrows(LedgerPersistenceException.class, () -> policy.execute(COMMAND_ID, () -> {
            attempts.incrementAndGet();
            throw new LedgerPersistenceException(new SQLException("deadlock", "40P01"));
        }));

        assertAll(
            () -> assertEquals(5, attempts.get()),
            () -> assertEquals(List.of(1, 2, 3, 4), delayedAttempts));
    }

    @Test
    void retryPolicyDoesNotRetryOrdinaryConstraintFailures() {
        var attempts = new AtomicInteger();
        var delayedAttempts = new ArrayList<Integer>();
        var policy = new PostgresRetryPolicy((commandId, attempt) -> delayedAttempts.add(attempt));

        assertThrows(LedgerPersistenceException.class, () -> policy.execute(COMMAND_ID, () -> {
            attempts.incrementAndGet();
            throw new LedgerPersistenceException(new SQLException("constraint", "23514"));
        }));

        assertAll(
            () -> assertEquals(1, attempts.get()),
            () -> assertEquals(List.of(), delayedAttempts));
    }

    private PostingCommand exampleACommand(UUID commandId, UUID journalId) {
        return command(journal(
            commandId,
            journalId,
            new PostingLine(PROVIDER_POSTING_ID, PROVIDER_ASSET, NGN, 100_000, 0, Map.of("rail", "provider")),
            new PostingLine(
                CUSTOMER_POSTING_ID,
                CUSTOMER_LIABILITY,
                NGN,
                -100_000,
                0,
                Map.of("customer", "example-a"))));
    }

    private static PostingCommand command(JournalDraft draft) {
        return new PostingCommand(draft.commandId(), new CanonicalJournalHasher().sha256(draft), draft);
    }

    private static JournalDraft journal(
        UUID commandId,
        UUID journalId,
        PostingLine first,
        PostingLine second
    ) {
        return new JournalDraft(
            journalId,
            commandId,
            uuid(30),
            uuid(31),
            LEGAL_ENTITY_ID,
            BOOK_ID,
            PERIOD_ID,
            "PROVIDER_INFLOW",
            "Example A provider inflow",
            Instant.parse("2026-01-15T10:00:00Z"),
            LocalDate.of(2026, 1, 15),
            null,
            1,
            List.of(first, second));
    }

    private void assertNoPostingRows() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.idempotency_command")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.materialised_balance")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.control_account_projection")),
                () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")));
        }
    }

    private static long balance(Connection connection, UUID accountId) throws SQLException {
        return queryLong(connection, """
            SELECT signed_posting_total FROM funds.materialised_balance WHERE account_id = '%s'
            """.formatted(accountId));
    }

    private static long accountSequence(Connection connection, UUID accountId) throws SQLException {
        return queryLong(connection, """
            SELECT latest_account_sequence FROM funds.materialised_balance WHERE account_id = '%s'
            """.formatted(accountId));
    }

    private static long controlTotal(Connection connection, String code) throws SQLException {
        return queryLong(connection, """
            SELECT signed_posting_total FROM funds.control_account_projection
            WHERE book_id = '%s' AND control_account_code = '%s' AND currency = 'NGN'
            """.formatted(BOOK_ID, code));
    }

    private void insertReferenceGraph(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
            VALUES (?, ?, 'NGN', 'Africa/Lagos', 'NG', 1)
            """, BOOK_ID, LEGAL_ENTITY_ID);
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, activated_at)
            VALUES (?, ?, 1, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """, CHART_VERSION_ID, BOOK_ID);
        execute(connection, """
            INSERT INTO funds.accounting_period
                (period_id, book_id, business_date_from, business_date_to, status)
            VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
            """, PERIOD_ID, BOOK_ID);
        execute(connection, """
            INSERT INTO funds.product_definition
                (product_id, product_code, product_kind, finance_principle)
            VALUES (?, 'SAVINGS-STANDARD', 'SAVINGS', 'CONVENTIONAL')
            """, PRODUCT_ID);
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json)
            VALUES (?, ?, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00',
                    'APP-2026-001', ?, '{}'::jsonb)
            """, PRODUCT_VERSION_ID, PRODUCT_ID, "a".repeat(64));
        insertAccount(
            connection,
            PROVIDER_ASSET,
            "PROVIDER-ASSET",
            "INTERNAL",
            null,
            "ASSET",
            "DEBIT",
            "PROVIDER-CASH");
        insertAccount(
            connection,
            CUSTOMER_LIABILITY,
            "CUSTOMER-LIABILITY",
            "CUSTOMER",
            PRODUCT_VERSION_ID,
            "LIABILITY",
            "CREDIT",
            "CUSTOMER-DEPOSITS");
    }

    private void insertAccount(
        Connection connection,
        UUID accountId,
        String code,
        String scope,
        UUID productVersionId,
        String accountClass,
        String normalBalance,
        String controlCode
    ) throws SQLException {
        execute(connection, """
            INSERT INTO funds.ledger_account
                (account_id, book_id, chart_version_id, account_code, account_scope,
                 product_version_id, account_class, normal_balance, currency,
                 control_account_code, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NGN', ?, 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """,
            accountId,
            BOOK_ID,
            CHART_VERSION_ID,
            code,
            scope,
            productVersionId,
            accountClass,
            normalBalance,
            controlCode);
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            execute(connection, sql);
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private void truncateAllTables() throws SQLException {
        execute("""
            TRUNCATE
                funds.outbox_event,
                funds.control_account_projection,
                funds.materialised_balance,
                funds.posting,
                funds.journal,
                funds.idempotency_command,
                funds.account_identifier,
                funds.ledger_account,
                funds.accounting_period,
                funds.chart_version,
                funds.book,
                funds.product_version,
                funds.product_definition
            CASCADE
            """);
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
