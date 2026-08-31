package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.AccountingPeriodClosedException;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import com.corebanking.funds.infrastructure.postgres.JdbcLedgerRepository;
import com.corebanking.funds.infrastructure.postgres.LedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import com.corebanking.funds.infrastructure.postgres.SqlState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
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
        removeScopedControlOverflowTrigger();
        truncateAllTables();
        try (var connection = dataSource.getConnection()) {
            insertReferenceGraph(connection);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try {
            removeScopedControlOverflowTrigger();
        } finally {
            truncateAllTables();
        }
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
                () -> assertEquals(
                    new PostingRow(PROVIDER_ASSET, "NGN", 100_000, 1),
                    posting(connection, PROVIDER_POSTING_ID)),
                () -> assertEquals(
                    new PostingRow(CUSTOMER_LIABILITY, "NGN", -100_000, 1),
                    posting(connection, CUSTOMER_POSTING_ID)),
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
        var recordingDataSource = new RecordingDataSource(dataSource, false);
        var observedService = postingService(recordingDataSource, new JdbcLedgerRepository(), (id, attempt) -> {});

        assertThrows(IdempotencyConflictException.class, () -> observedService.post(conflict));

        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertSingleRolledBackAttempt(recordingDataSource),
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
        var recordingDataSource = new RecordingDataSource(dataSource, false);
        var observedService = postingService(recordingDataSource, new JdbcLedgerRepository(), (id, attempt) -> {});

        assertThrows(InvalidJournalException.class, () -> observedService.post(command));

        assertSingleRolledBackAttempt(recordingDataSource);
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
        var recordingDataSource = new RecordingDataSource(dataSource, false);
        var observedService = postingService(recordingDataSource, new JdbcLedgerRepository(), (id, attempt) -> {});

        assertThrows(MonetaryOverflowException.class, () -> observedService.post(command(draft)));

        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertSingleRolledBackAttempt(recordingDataSource),
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
    void controlProjectionOverflowRollsBackEarlierMaterialisedAndControlChanges() throws SQLException {
        seedProjectionState(
            new BalanceState(50, 4, 4),
            new BalanceState(-50, 7, 7),
            new ControlState(Long.MAX_VALUE, 11),
            new ControlState(-50, 12));
        var recordingDataSource = new RecordingDataSource(dataSource, false);
        var service = postingService(recordingDataSource, new JdbcLedgerRepository(), (commandId, attempt) -> {});
        var draft = journal(
            COMMAND_ID,
            JOURNAL_ID,
            new PostingLine(PROVIDER_POSTING_ID, PROVIDER_ASSET, NGN, 1, 0, Map.of()),
            new PostingLine(CUSTOMER_POSTING_ID, CUSTOMER_LIABILITY, NGN, -1, 0, Map.of()));

        assertThrows(MonetaryOverflowException.class, () -> service.post(command(draft)));

        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertSingleRolledBackAttempt(recordingDataSource),
                () -> assertEquals(new BalanceState(50, 4, 4), balanceState(connection, PROVIDER_ASSET)),
                () -> assertEquals(new BalanceState(-50, 7, 7), balanceState(connection, CUSTOMER_LIABILITY)),
                () -> assertEquals(
                    new ControlState(Long.MAX_VALUE, 11),
                    controlState(connection, "PROVIDER-CASH")),
                () -> assertEquals(
                    new ControlState(-50, 12),
                    controlState(connection, "CUSTOMER-DEPOSITS")),
                () -> assertNoNewPostingRows(connection));
        }
    }

    @Test
    void postgresNumericOverflowIsMappedAndRollsBackEveryEarlierWrite() throws SQLException {
        seedProjectionState(
            new BalanceState(50, 4, 4),
            new BalanceState(-50, 7, 7),
            new ControlState(50, 11),
            new ControlState(-50, 12));
        try {
            installScopedControlOverflowTrigger();
            var recordingDataSource = new RecordingDataSource(dataSource, false);
            var service = postingService(recordingDataSource, new JdbcLedgerRepository(), (commandId, attempt) -> {});

            MonetaryOverflowException failure = assertThrows(
                MonetaryOverflowException.class,
                () -> service.post(exampleACommand(COMMAND_ID, JOURNAL_ID)));

            try (var connection = dataSource.getConnection()) {
                assertAll(
                    () -> assertTrue(SqlState.occursIn(failure, SqlState.NUMERIC_VALUE_OUT_OF_RANGE)),
                    () -> assertSingleRolledBackAttempt(recordingDataSource),
                    () -> assertEquals(new BalanceState(50, 4, 4), balanceState(connection, PROVIDER_ASSET)),
                    () -> assertEquals(new BalanceState(-50, 7, 7), balanceState(connection, CUSTOMER_LIABILITY)),
                    () -> assertEquals(
                        new ControlState(50, 11),
                        controlState(connection, "PROVIDER-CASH")),
                    () -> assertEquals(
                        new ControlState(-50, 12),
                        controlState(connection, "CUSTOMER-DEPOSITS")),
                    () -> assertNoNewPostingRows(connection));
            }
        } finally {
            removeScopedControlOverflowTrigger();
        }
    }

    @Test
    void postingServiceRetriesWithFreshSerializableTransactionsAndUnchangedCommand() throws SQLException {
        var delayedAttempts = new ArrayList<Integer>();
        var recordingDataSource = new RecordingDataSource(dataSource, false);
        var repository = new ScriptedLedgerRepository(
            new JdbcLedgerRepository(),
            2,
            () -> new IllegalStateException(
                new LedgerPersistenceException(new SQLException("serialization", "40001"))));
        var service = postingService(
            recordingDataSource,
            repository,
            (commandId, attempt) -> delayedAttempts.add(attempt));
        var command = exampleACommand(COMMAND_ID, JOURNAL_ID);

        PostingResult result = service.post(command);

        assertAll(
            () -> assertEquals(JOURNAL_ID, result.journalId()),
            () -> assertEquals(3, recordingDataSource.connections().size()),
            () -> assertNotSame(
                recordingDataSource.connections().get(0).delegate(),
                recordingDataSource.connections().get(1).delegate()),
            () -> assertNotSame(
                recordingDataSource.connections().get(1).delegate(),
                recordingDataSource.connections().get(2).delegate()),
            () -> assertNotSame(
                recordingDataSource.connections().get(0).delegate(),
                recordingDataSource.connections().get(2).delegate()),
            () -> assertEquals(List.of(1, 2), delayedAttempts),
            () -> assertEquals(3, repository.commands().size()),
            () -> repository.commands().forEach(attempted -> assertSame(command, attempted)),
            () -> repository.commands().forEach(attempted -> assertEquals(COMMAND_ID, attempted.commandId())),
            () -> repository.commands().forEach(attempted -> assertEquals(command.requestHash(), attempted.requestHash())),
            () -> assertEquals(
                List.of("autoCommit:false", "isolation:" + Connection.TRANSACTION_SERIALIZABLE, "rollback", "close"),
                recordingDataSource.connections().get(0).events()),
            () -> assertEquals(
                List.of("autoCommit:false", "isolation:" + Connection.TRANSACTION_SERIALIZABLE, "rollback", "close"),
                recordingDataSource.connections().get(1).events()),
            () -> assertEquals(
                List.of("autoCommit:false", "isolation:" + Connection.TRANSACTION_SERIALIZABLE, "commit", "close"),
                recordingDataSource.connections().get(2).events()),
            () -> assertEquals(1, recordingDataSource.commitCount()),
            () -> assertEquals(2, recordingDataSource.rollbackCount()));
    }

    @Test
    void postingServiceStopsAfterFiveFreshRolledBackTransactions() {
        var delayedAttempts = new ArrayList<Integer>();
        var recordingDataSource = new RecordingDataSource(dataSource, false);
        var repository = new ScriptedLedgerRepository(
            new JdbcLedgerRepository(),
            Integer.MAX_VALUE,
            () -> new LedgerPersistenceException(new SQLException("deadlock", "40P01")));
        var service = postingService(
            recordingDataSource,
            repository,
            (commandId, attempt) -> delayedAttempts.add(attempt));

        assertThrows(
            LedgerPersistenceException.class,
            () -> service.post(exampleACommand(COMMAND_ID, JOURNAL_ID)));

        assertAll(
            () -> assertEquals(5, recordingDataSource.connections().size()),
            () -> assertEquals(5, repository.commands().size()),
            () -> assertEquals(0, recordingDataSource.commitCount()),
            () -> assertEquals(5, recordingDataSource.rollbackCount()),
            () -> assertEquals(List.of(1, 2, 3, 4), delayedAttempts),
            () -> recordingDataSource.connections().forEach(connection -> assertEquals(
                List.of("autoCommit:false", "isolation:" + Connection.TRANSACTION_SERIALIZABLE, "rollback", "close"),
                connection.events())));
    }

    @Test
    void postingServicePreservesSuppressedRollbackFailure() {
        var recordingDataSource = new RecordingDataSource(dataSource, true);
        var original = new InvalidJournalException("injected validation failure");
        var repository = new ScriptedLedgerRepository(
            new JdbcLedgerRepository(),
            1,
            () -> original);
        var service = postingService(recordingDataSource, repository, (commandId, attempt) -> {});

        InvalidJournalException thrown = assertThrows(
            InvalidJournalException.class,
            () -> service.post(exampleACommand(COMMAND_ID, JOURNAL_ID)));

        assertAll(
            () -> assertSame(original, thrown),
            () -> assertEquals(1, thrown.getSuppressed().length),
            () -> assertEquals("rollback failed", thrown.getSuppressed()[0].getMessage()),
            () -> assertSingleRolledBackAttempt(recordingDataSource));
    }

    @Test
    void ordinaryPostgreSqlConstraintFailureIsNotRetried() {
        var recordingDataSource = new RecordingDataSource(dataSource, false);
        var service = postingService(recordingDataSource, new JdbcLedgerRepository(), (commandId, attempt) -> {});
        var ordinaryConstraint = command(journalWithNarration("x".repeat(513)));

        LedgerPersistenceException failure = assertThrows(
            LedgerPersistenceException.class,
            () -> service.post(ordinaryConstraint));

        assertAll(
            () -> assertTrue(SqlState.occursIn(failure, "23514")),
            () -> assertSingleRolledBackAttempt(recordingDataSource));
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

    private JournalDraft journalWithNarration(String narration) {
        JournalDraft original = exampleACommand(COMMAND_ID, JOURNAL_ID).journal();
        return new JournalDraft(
            original.journalId(),
            original.commandId(),
            original.correlationId(),
            original.businessTransactionId(),
            original.legalEntityId(),
            original.bookId(),
            original.periodId(),
            original.transactionType(),
            narration,
            original.bookingTime(),
            original.valueDate(),
            original.reversalOfJournalId(),
            original.policyVersion(),
            original.postings());
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

    private static PostingRow posting(Connection connection, UUID postingId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT account_id, currency, signed_minor_units, account_sequence
            FROM funds.posting
            WHERE posting_id = ?
            """)) {
            statement.setObject(1, postingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new PostingRow(
                    rows.getObject("account_id", UUID.class),
                    rows.getString("currency"),
                    rows.getLong("signed_minor_units"),
                    rows.getLong("account_sequence"));
            }
        }
    }

    private static BalanceState balanceState(Connection connection, UUID accountId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT signed_posting_total, latest_account_sequence, version
            FROM funds.materialised_balance
            WHERE account_id = ?
            """)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new BalanceState(rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        }
    }

    private static ControlState controlState(Connection connection, String controlCode) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT signed_posting_total, latest_journal_sequence
            FROM funds.control_account_projection
            WHERE book_id = ? AND control_account_code = ? AND currency = 'NGN'
            """)) {
            statement.setObject(1, BOOK_ID);
            statement.setString(2, controlCode);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new ControlState(rows.getLong(1), rows.getLong(2));
            }
        }
    }

    private void seedProjectionState(
        BalanceState providerBalance,
        BalanceState customerBalance,
        ControlState providerControl,
        ControlState customerControl
    ) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                INSERT INTO funds.materialised_balance
                    (account_id, signed_posting_total, latest_account_sequence, version)
                VALUES (?, ?, ?, ?), (?, ?, ?, ?)
                """,
                PROVIDER_ASSET,
                providerBalance.signedPostingTotal(),
                providerBalance.latestAccountSequence(),
                providerBalance.version(),
                CUSTOMER_LIABILITY,
                customerBalance.signedPostingTotal(),
                customerBalance.latestAccountSequence(),
                customerBalance.version());
            execute(connection, """
                INSERT INTO funds.control_account_projection
                    (book_id, control_account_code, currency, signed_posting_total, latest_journal_sequence)
                VALUES (?, 'PROVIDER-CASH', 'NGN', ?, ?),
                       (?, 'CUSTOMER-DEPOSITS', 'NGN', ?, ?)
                """,
                BOOK_ID,
                providerControl.signedPostingTotal(),
                providerControl.latestJournalSequence(),
                BOOK_ID,
                customerControl.signedPostingTotal(),
                customerControl.latestJournalSequence());
        }
    }

    private void installScopedControlOverflowTrigger() throws SQLException {
        removeScopedControlOverflowTrigger();
        execute("""
            CREATE FUNCTION funds.task6_raise_control_22003()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $function$
            BEGIN
                IF NEW.control_account_code = 'PROVIDER-CASH' THEN
                    RAISE EXCEPTION 'Task 6 scoped numeric overflow'
                        USING ERRCODE = '22003';
                END IF;
                RETURN NEW;
            END
            $function$
            """);
        execute("""
            CREATE TRIGGER task6_control_22003
            BEFORE UPDATE ON funds.control_account_projection
            FOR EACH ROW
            EXECUTE FUNCTION funds.task6_raise_control_22003()
            """);
    }

    private void removeScopedControlOverflowTrigger() throws SQLException {
        execute("DROP TRIGGER IF EXISTS task6_control_22003 ON funds.control_account_projection");
        execute("DROP FUNCTION IF EXISTS funds.task6_raise_control_22003()");
    }

    private static void assertNoNewPostingRows(Connection connection) throws SQLException {
        assertAll(
            () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.idempotency_command")),
            () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.journal")),
            () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.posting")),
            () -> assertEquals(0, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")));
    }

    private static PostingService postingService(
        DataSource dataSource,
        LedgerRepository repository,
        PostgresRetryPolicy.RetryJitter jitter
    ) {
        return new PostingService(dataSource, repository, new PostgresRetryPolicy(jitter));
    }

    private static void assertSingleRolledBackAttempt(RecordingDataSource dataSource) {
        assertAll(
            () -> assertEquals(1, dataSource.connections().size()),
            () -> assertEquals(0, dataSource.commitCount()),
            () -> assertEquals(1, dataSource.rollbackCount()),
            () -> assertEquals(
                List.of("autoCommit:false", "isolation:" + Connection.TRANSACTION_SERIALIZABLE, "rollback", "close"),
                dataSource.connections().getFirst().events()));
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

    private record PostingRow(
        UUID accountId,
        String currency,
        long signedMinorUnits,
        long accountSequence
    ) {}

    private record BalanceState(
        long signedPostingTotal,
        long latestAccountSequence,
        long version
    ) {}

    private record ControlState(long signedPostingTotal, long latestJournalSequence) {}

    private static final class ScriptedLedgerRepository implements LedgerRepository {
        private final LedgerRepository delegate;
        private final int failuresBeforeSuccess;
        private final Supplier<? extends RuntimeException> failure;
        private final List<PostingCommand> commands = new ArrayList<>();

        private ScriptedLedgerRepository(
            LedgerRepository delegate,
            int failuresBeforeSuccess,
            Supplier<? extends RuntimeException> failure
        ) {
            this.delegate = delegate;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.failure = failure;
        }

        @Override
        public PostingResult post(Connection connection, PostingCommand command) {
            commands.add(command);
            if (commands.size() <= failuresBeforeSuccess) {
                throw failure.get();
            }
            return delegate.post(connection, command);
        }

        @Override
        public Optional<PostingResult> findCompleted(
            Connection connection,
            UUID commandId,
            String requestHash
        ) {
            return delegate.findCompleted(connection, commandId, requestHash);
        }

        private List<PostingCommand> commands() {
            return List.copyOf(commands);
        }
    }

    private static final class RecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final boolean failRollback;
        private final List<ConnectionTrace> connections = new ArrayList<>();

        private RecordingDataSource(DataSource delegate, boolean failRollback) {
            this.delegate = delegate;
            this.failRollback = failRollback;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return record(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return record(delegate.getConnection(username, password));
        }

        private Connection record(Connection connection) {
            var trace = new ConnectionTrace(connection);
            connections.add(trace);
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    String event = switch (method.getName()) {
                        case "setAutoCommit" -> "autoCommit:" + arguments[0];
                        case "setTransactionIsolation" -> "isolation:" + arguments[0];
                        case "commit" -> "commit";
                        case "rollback" -> arguments == null || arguments.length == 0 ? "rollback" : null;
                        case "close" -> "close";
                        default -> null;
                    };
                    try {
                        Object result = method.invoke(connection, arguments);
                        if (event != null) {
                            trace.events.add(event);
                        }
                        if ("rollback".equals(event) && failRollback) {
                            throw new SQLException("rollback failed", "08006");
                        }
                        return result;
                    } catch (InvocationTargetException invocationFailure) {
                        throw invocationFailure.getCause();
                    }
                });
        }

        private List<ConnectionTrace> connections() {
            return List.copyOf(connections);
        }

        private long commitCount() {
            return connections.stream().flatMap(trace -> trace.events().stream())
                .filter("commit"::equals)
                .count();
        }

        private long rollbackCount() {
            return connections.stream().flatMap(trace -> trace.events().stream())
                .filter("rollback"::equals)
                .count();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    private static final class ConnectionTrace {
        private final Connection delegate;
        private final List<String> events = new ArrayList<>();

        private ConnectionTrace(Connection delegate) {
            this.delegate = delegate;
        }

        private Connection delegate() {
            return delegate;
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }
}
