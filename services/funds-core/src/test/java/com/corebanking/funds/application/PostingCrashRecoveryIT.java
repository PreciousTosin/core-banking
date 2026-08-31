package com.corebanking.funds.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.security.SimplePassword;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PostingCrashRecoveryIT {
    private static final UUID BEFORE_COMMIT_COMMAND_ID = TestPostingStack.uuid(40);
    private static final UUID AFTER_COMMIT_COMMAND_ID = TestPostingStack.uuid(50);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    DataSource dataSource;

    private CrashProbe rows;
    private DatabaseCredentials credentials;
    private DataSource boundedDataSource;

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
        boundedDataSource = new TimeoutDataSource(dataSource);
        rows = new CrashProbe(boundedDataSource);
        credentials = databaseCredentials(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void unchangedMvccSnapshotDoesNotClaimRollbackWhileAccountLocksRemainHeld() throws Exception {
        CrashSnapshot before = rows.snapshot(BEFORE_COMMIT_COMMAND_ID);
        try (var blocker = runSqlBounded(
            "lock-holder connection acquisition",
            boundedDataSource::getConnection)) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("""
                SELECT account_id
                FROM funds.ledger_account
                WHERE account_id IN (?, ?)
                ORDER BY account_id
                FOR UPDATE
                """)) {
                statement.setQueryTimeout(1);
                statement.setObject(1, TestPostingStack.PROVIDER_ASSET);
                statement.setObject(2, TestPostingStack.CUSTOMER_LIABILITY);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertTrue(result.next());
                }
            }

            assertEquals(before, rows.snapshot(BEFORE_COMMIT_COMMAND_ID));
            SQLException lockStillHeld = assertThrows(SQLException.class, rows::probeRollbackReleaseOnce);
            assertEquals("55P03", lockStillHeld.getSQLState());

            blocker.rollback();
            rows.awaitRollbackComplete();
        }
    }

    @Test
    void timedBoundedCallCancelsAndJoinsItsBlockingTask() {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var neverReleased = new CountDownLatch(1);

        AssertionError failure = assertThrows(AssertionError.class, () -> runBounded(
            Duration.ofMillis(100),
            "blocking bounded-call proof",
            () -> {
                started.countDown();
                try {
                    neverReleased.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw expected;
                }
                return null;
            }));

        assertAll(
            () -> assertEquals(0, started.getCount()),
            () -> assertEquals(0, interrupted.getCount()),
            () -> assertTrue(failure.getMessage().contains("blocking bounded-call proof")));
    }

    @Test
    void workerCleanupSharesOneDeadlineAcrossProcessAndOutputReader() throws Exception {
        var input = new StubbornInputStream();
        var process = new SlowTerminationProcess(input, Duration.ofMillis(120));
        var worker = new WorkerHandle(
            process,
            CrashPostingWorker.CrashPoint.BEFORE_COMMIT,
            Duration.ofMillis(160));
        long startedAt = System.nanoTime();
        try {
            AssertionError failure = assertThrows(AssertionError.class, worker::close);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertAll(
                () -> assertTrue(process.destroyed.get()),
                () -> assertTrue(failure.getMessage().contains("survived forced destruction")),
                () -> assertTrue(elapsedMillis < 240, () -> "cleanup used fresh deadlines: " + elapsedMillis + "ms"));
        } finally {
            input.release();
            if (!worker.outputReader.join(Duration.ofSeconds(1))) {
                throw new AssertionError("cleanup-test output reader did not terminate");
            }
        }
    }

    @Test
    void processDeathBeforeCommitRollsBackAndIdenticalRetryPostsOnce() throws Exception {
        PostingCommand command = CrashPostingWorker.command(BEFORE_COMMIT_COMMAND_ID);
        CrashSnapshot before = rows.snapshot(BEFORE_COMMIT_COMMAND_ID);

        try (WorkerHandle worker = startWorker(
            CrashPostingWorker.CrashPoint.BEFORE_COMMIT,
            BEFORE_COMMIT_COMMAND_ID)) {
            assertHaltedAt(worker.awaitExit(), CrashPostingWorker.CrashPoint.BEFORE_COMMIT);
            rows.awaitRollbackComplete();
            assertEquals(before, rows.snapshot(BEFORE_COMMIT_COMMAND_ID));

            PostingResult recovered = postBounded(command, "pre-commit recovery retry");
            CrashSnapshot after = rows.snapshot(BEFORE_COMMIT_COMMAND_ID);

            assertOneCompletedEffect(command, recovered, before, after);
        }
    }

    @Test
    void processDeathAfterCommitReturnsStoredResultWithoutReposting() throws Exception {
        PostingCommand command = CrashPostingWorker.command(AFTER_COMMIT_COMMAND_ID);
        CrashSnapshot before = rows.snapshot(AFTER_COMMIT_COMMAND_ID);

        try (WorkerHandle worker = startWorker(
            CrashPostingWorker.CrashPoint.AFTER_COMMIT_BEFORE_RETURN,
            AFTER_COMMIT_COMMAND_ID)) {
            UUID committedJournal = rows.awaitCommittedJournal(AFTER_COMMIT_COMMAND_ID);
            assertEquals(CrashPostingWorker.JOURNAL_ID, committedJournal);
            assertHaltedAt(worker.awaitExit(), CrashPostingWorker.CrashPoint.AFTER_COMMIT_BEFORE_RETURN);
            CrashSnapshot committed = rows.snapshot(AFTER_COMMIT_COMMAND_ID);

            PostingResult replayed = postBounded(command, "post-commit idempotent replay");
            CrashSnapshot afterReplay = rows.snapshot(AFTER_COMMIT_COMMAND_ID);

            assertAll(
                () -> assertEquals(committedJournal, replayed.journalId()),
                () -> assertEquals(committed.journals().getFirst().journalSequence(), replayed.journalSequence()),
                () -> assertEquals(committed, afterReplay));
            assertOneCompletedEffect(command, replayed, before, afterReplay);
        }
    }

    private PostingResult postBounded(PostingCommand command, String description) throws Exception {
        return runBounded(PROCESS_TIMEOUT, description, () -> TestPostingStack
            .create(boundedDataSource, PostingTransactionObserver.noop())
            .postingService()
            .post(command));
    }

    private WorkerHandle startWorker(CrashPostingWorker.CrashPoint point, UUID commandId) throws IOException {
        var builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("surefire.test.class.path"),
            CrashPostingWorker.class.getName(),
            commandId.toString(),
            point.name())
            .redirectErrorStream(true);
        builder.environment().put("CB_TEST_JDBC_URL", credentials.jdbcUrl());
        builder.environment().put("CB_TEST_DB_USER", credentials.username());
        builder.environment().put("CB_TEST_DB_PASSWORD", credentials.password());
        return new WorkerHandle(builder.start(), point);
    }

    private static DatabaseCredentials databaseCredentials(DataSource dataSource) throws SQLException {
        AgroalDataSource agroal = dataSource.unwrap(AgroalDataSource.class);
        var factory = agroal
            .getConfiguration()
            .connectionPoolConfiguration()
            .connectionFactoryConfiguration();
        String username = factory.principal().getName();
        String password = factory.credentials().stream()
            .filter(SimplePassword.class::isInstance)
            .map(SimplePassword.class::cast)
            .map(SimplePassword::getWord)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("datasource password is unavailable"));
        return new DatabaseCredentials(factory.jdbcUrl(), username, password);
    }

    private static void assertHaltedAt(WorkerExit exit, CrashPostingWorker.CrashPoint point) {
        assertAll(
            () -> assertEquals(91, exit.exitCode()),
            () -> assertEquals(
                List.of("REACHED:" + point),
                exit.output().lines().filter(line -> !line.isBlank()).toList()));
    }

    private static void assertOneCompletedEffect(
        PostingCommand command,
        PostingResult result,
        CrashSnapshot before,
        CrashSnapshot after
    ) {
        assertAll(
            () -> assertEquals(CrashPostingWorker.JOURNAL_ID, result.journalId()),
            () -> assertEquals(command.requestHash(), result.canonicalHash()),
            () -> assertEquals(1, after.idempotency().size()),
            () -> assertEquals(command.commandId(), after.idempotency().getFirst().commandId()),
            () -> assertEquals("COMPLETED", after.idempotency().getFirst().state()),
            () -> assertEquals(command.requestHash(), after.idempotency().getFirst().requestHash()),
            () -> assertEquals(result.journalId(), after.idempotency().getFirst().journalId()),
            () -> assertNotNull(after.idempotency().getFirst().resultJson()),
            () -> assertTrue(after.idempotency().getFirst().resultJson().contains(result.journalId().toString())),
            () -> assertTrue(after.idempotency().getFirst().resultJson().contains(command.requestHash())),
            () -> assertNotNull(after.idempotency().getFirst().createdAt()),
            () -> assertNotNull(after.idempotency().getFirst().completedAt()),
            () -> assertEquals(1, after.journals().size()),
            () -> assertEquals(result.journalId(), after.journals().getFirst().journalId()),
            () -> assertEquals(result.journalSequence(), after.journals().getFirst().journalSequence()),
            () -> assertEquals(command.commandId(), after.journals().getFirst().commandId()),
            () -> assertEquals(command.journal().correlationId(), after.journals().getFirst().correlationId()),
            () -> assertEquals(
                command.journal().businessTransactionId(),
                after.journals().getFirst().businessTransactionId()),
            () -> assertEquals(command.journal().legalEntityId(), after.journals().getFirst().legalEntityId()),
            () -> assertEquals(command.journal().bookId(), after.journals().getFirst().bookId()),
            () -> assertEquals(command.journal().periodId(), after.journals().getFirst().periodId()),
            () -> assertEquals(command.journal().transactionType(), after.journals().getFirst().transactionType()),
            () -> assertEquals(command.journal().narration(), after.journals().getFirst().narration()),
            () -> assertEquals(
                OffsetDateTime.ofInstant(command.journal().bookingTime(), ZoneOffset.UTC),
                after.journals().getFirst().bookingTime()),
            () -> assertEquals(command.journal().valueDate(), after.journals().getFirst().valueDate()),
            () -> assertNull(after.journals().getFirst().reversalOfJournalId()),
            () -> assertEquals(command.journal().policyVersion(), after.journals().getFirst().policyVersion()),
            () -> assertEquals(command.requestHash(), after.journals().getFirst().canonicalHash()),
            () -> assertEquals(
                List.of(
                    new PostingState(
                        CrashPostingWorker.PROVIDER_POSTING_ID,
                        CrashPostingWorker.JOURNAL_ID,
                        TestPostingStack.PROVIDER_ASSET,
                        "NGN",
                        CrashPostingWorker.POSTING_AMOUNT,
                        TestPostingStack.PROVIDER_INITIAL_SEQUENCE + 1,
                        "{\"rail\": \"provider\"}"),
                    new PostingState(
                        CrashPostingWorker.CUSTOMER_POSTING_ID,
                        CrashPostingWorker.JOURNAL_ID,
                        TestPostingStack.CUSTOMER_LIABILITY,
                        "NGN",
                        -CrashPostingWorker.POSTING_AMOUNT,
                        TestPostingStack.CUSTOMER_INITIAL_SEQUENCE + 1,
                        "{\"customer\": \"crash-recovery\"}")),
                after.postings()),
            () -> assertEquals(
                CrashPostingWorker.POSTING_AMOUNT,
                balanceDelta(before, after, TestPostingStack.PROVIDER_ASSET)),
            () -> assertEquals(
                -CrashPostingWorker.POSTING_AMOUNT,
                balanceDelta(before, after, TestPostingStack.CUSTOMER_LIABILITY)),
            () -> assertEquals(
                new BalanceState(
                    TestPostingStack.PROVIDER_INITIAL_TOTAL + CrashPostingWorker.POSTING_AMOUNT,
                    TestPostingStack.PROVIDER_INITIAL_SEQUENCE + 1,
                    TestPostingStack.PROVIDER_INITIAL_VERSION + 1),
                after.balances().get(TestPostingStack.PROVIDER_ASSET)),
            () -> assertEquals(
                new BalanceState(
                    TestPostingStack.CUSTOMER_INITIAL_TOTAL - CrashPostingWorker.POSTING_AMOUNT,
                    TestPostingStack.CUSTOMER_INITIAL_SEQUENCE + 1,
                    TestPostingStack.CUSTOMER_INITIAL_VERSION + 1),
                after.balances().get(TestPostingStack.CUSTOMER_LIABILITY)),
            () -> assertEquals(1, after.controls().size() - before.controls().size()),
            () -> assertEquals(
                CrashPostingWorker.POSTING_AMOUNT,
                controlDelta(before, after, TestPostingStack.PROVIDER_CONTROL)),
            () -> assertEquals(
                -CrashPostingWorker.POSTING_AMOUNT,
                controlDelta(before, after, TestPostingStack.CUSTOMER_CONTROL)),
            () -> assertEquals(0, controlDelta(before, after, TestPostingStack.INDEPENDENT_CONTROL)),
            () -> assertEquals(
                new ControlState(
                    TestPostingStack.PROVIDER_CONTROL_INITIAL_TOTAL + CrashPostingWorker.POSTING_AMOUNT,
                    result.journalSequence()),
                after.controls().get(TestPostingStack.PROVIDER_CONTROL)),
            () -> assertEquals(
                new ControlState(-CrashPostingWorker.POSTING_AMOUNT, result.journalSequence()),
                after.controls().get(TestPostingStack.CUSTOMER_CONTROL)),
            () -> assertEquals(
                new ControlState(TestPostingStack.INDEPENDENT_CONTROL_TOTAL, 0),
                after.controls().get(TestPostingStack.INDEPENDENT_CONTROL)),
            () -> assertEquals(1, after.outbox().size()),
            () -> assertEquals(
                UUID.nameUUIDFromBytes(("JournalPosted:" + result.journalId()).getBytes(UTF_8)),
                after.outbox().getFirst().eventId()),
            () -> assertEquals(result.journalId(), after.outbox().getFirst().aggregateId()),
            () -> assertEquals(result.journalSequence(), after.outbox().getFirst().aggregateVersion()),
            () -> assertEquals("JournalPosted", after.outbox().getFirst().eventType()),
            () -> assertEquals(1, after.outbox().getFirst().schemaVersion()),
            () -> assertTrue(after.outbox().getFirst().payload().contains(result.journalId().toString())),
            () -> assertTrue(after.outbox().getFirst().payload().contains(command.requestHash())),
            () -> assertNotNull(after.outbox().getFirst().createdAt()),
            () -> assertNull(after.outbox().getFirst().publishedAt()),
            () -> assertEquals(0, after.outbox().getFirst().publishAttempts()));
    }

    private static long balanceDelta(
        CrashSnapshot before,
        CrashSnapshot after,
        UUID accountId
    ) {
        return after.balances().get(accountId).signedPostingTotal()
            - before.balances().get(accountId).signedPostingTotal();
    }

    private static long controlDelta(
        CrashSnapshot before,
        CrashSnapshot after,
        String controlCode
    ) {
        ControlState previous = before.controls().get(controlCode);
        ControlState current = after.controls().get(controlCode);
        return current.signedPostingTotal() - (previous == null ? 0 : previous.signedPostingTotal());
    }

    private static <T> T runBounded(
        Duration timeout,
        String description,
        Callable<T> operation
    ) throws Exception {
        Deadline deadline = Deadline.after(timeout);
        long cleanupReserve = Math.min(SECONDS.toNanos(1), Math.max(1, timeout.toNanos() / 4));
        ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("bounded-" + description.replace(' ', '-')).factory());
        Future<T> future = executor.submit(operation);
        Throwable primaryFailure = null;
        try {
            long operationBudget = Math.max(1, deadline.remainingNanos() - cleanupReserve);
            try {
                return future.get(operationBudget, NANOSECONDS);
            } catch (TimeoutException timeoutFailure) {
                throw new AssertionError(description + " exceeded its overall bound", timeoutFailure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(description + " wait was interrupted", interrupted);
            } catch (ExecutionException executionFailure) {
                Throwable cause = executionFailure.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new AssertionError(description + " failed", cause);
            }
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            shutdownBounded(executor, future, description, deadline, primaryFailure);
        }
    }

    private static void shutdownBounded(
        ExecutorService executor,
        Future<?> future,
        String description,
        Deadline deadline,
        Throwable primaryFailure
    ) {
        AssertionError cleanupFailure = null;
        try {
            if (!future.isDone()) {
                future.cancel(true);
            }
        } catch (RuntimeException failure) {
            cleanupFailure = append(
                cleanupFailure,
                new AssertionError(description + " future cancellation failed", failure));
        }
        try {
            executor.shutdownNow();
        } catch (RuntimeException failure) {
            cleanupFailure = append(
                cleanupFailure,
                new AssertionError(description + " executor shutdown failed", failure));
        }
        try {
            long remaining = deadline.remainingNanos();
            if (!executor.isTerminated()
                && (remaining == 0 || !executor.awaitTermination(remaining, NANOSECONDS))) {
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError(description + " executor did not terminate within the overall bound"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cleanupFailure = append(
                cleanupFailure,
                new AssertionError(description + " executor cleanup was interrupted", interrupted));
        }
        if (cleanupFailure != null) {
            if (primaryFailure == null) {
                throw cleanupFailure;
            }
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static <T> T runSqlBounded(String description, Callable<T> operation) throws SQLException {
        try {
            return runBounded(PROCESS_TIMEOUT, description, operation);
        } catch (SQLException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AssertionError(description + " failed with an unexpected checked exception", failure);
        }
    }

    private static Connection configureTimeouts(Connection connection) throws SQLException {
        boolean configured = false;
        try {
            connection.setNetworkTimeout(Runnable::run, 1_000);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(1);
                statement.execute("SET statement_timeout = '1000ms'");
                statement.execute("SET lock_timeout = '200ms'");
            }
            configured = true;
            return connection;
        } finally {
            if (!configured) {
                connection.close();
            }
        }
    }

    private static java.sql.PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(1);
        return statement;
    }

    private record DatabaseCredentials(String jdbcUrl, String username, String password) {}

    private record WorkerExit(int exitCode, String output) {}

    private record CrashSnapshot(
        List<IdempotencyState> idempotency,
        List<JournalState> journals,
        List<PostingState> postings,
        Map<UUID, BalanceState> balances,
        Map<String, ControlState> controls,
        List<OutboxState> outbox
    ) {}

    private record IdempotencyState(
        UUID commandId,
        String requestHash,
        String state,
        UUID journalId,
        String resultJson,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
    ) {}

    private record JournalState(
        UUID journalId,
        long journalSequence,
        UUID commandId,
        UUID correlationId,
        UUID businessTransactionId,
        UUID legalEntityId,
        UUID bookId,
        UUID periodId,
        String transactionType,
        String narration,
        OffsetDateTime bookingTime,
        LocalDate valueDate,
        UUID reversalOfJournalId,
        int policyVersion,
        String canonicalHash
    ) {}

    private record PostingState(
        UUID postingId,
        UUID journalId,
        UUID accountId,
        String currency,
        long signedMinorUnits,
        long accountSequence,
        String dimensions
    ) {}

    private record BalanceState(long signedPostingTotal, long latestAccountSequence, long version) {}

    private record ControlState(long signedPostingTotal, long latestJournalSequence) {}

    private record OutboxState(
        UUID eventId,
        UUID aggregateId,
        long aggregateVersion,
        String eventType,
        int schemaVersion,
        String payload,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        int publishAttempts
    ) {}

    private static final class CrashProbe {
        private final DataSource dataSource;

        private CrashProbe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        private UUID awaitCommittedJournal(UUID commandId) throws SQLException {
            return runSqlBounded("committed-journal visibility", () -> {
                Deadline deadline = Deadline.after(Duration.ofSeconds(8));
                do {
                    Optional<UUID> journalId = journalId(commandId);
                    if (journalId.isPresent()) {
                        return journalId.get();
                    }
                    Thread.onSpinWait();
                } while (deadline.hasRemaining());
                throw new AssertionError("committed journal was not visible within the overall bound");
            });
        }

        private Optional<UUID> journalId(UUID commandId) throws SQLException {
            try (var connection = dataSource.getConnection();
                 var statement = prepare(connection, """
                     SELECT journal_id FROM funds.journal WHERE command_id = ?
                     """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    return result.next()
                        ? Optional.of(result.getObject("journal_id", UUID.class))
                        : Optional.empty();
                }
            }
        }

        private CrashSnapshot snapshot(UUID commandId) throws SQLException {
            return runSqlBounded("scoped ledger snapshot", () -> snapshotWithinBound(commandId));
        }

        private CrashSnapshot snapshotWithinBound(UUID commandId) throws SQLException {
            try (var connection = dataSource.getConnection()) {
                return new CrashSnapshot(
                    idempotency(connection, commandId),
                    journals(connection, commandId),
                    postings(connection, commandId),
                    balances(connection),
                    controls(connection),
                    outbox(connection, commandId));
            }
        }

        private void awaitRollbackComplete() throws SQLException {
            runSqlBounded("pre-commit rollback synchronization", () -> {
                Deadline deadline = Deadline.after(Duration.ofSeconds(8));
                SQLException lastLockFailure = null;
                do {
                    try {
                        probeRollbackReleaseOnceWithinBound();
                        return null;
                    } catch (SQLException failure) {
                        if (!"55P03".equals(failure.getSQLState())) {
                            throw failure;
                        }
                        lastLockFailure = failure;
                        Thread.onSpinWait();
                    }
                } while (deadline.hasRemaining());
                throw new AssertionError(
                    "fixture account locks were not released within the overall bound",
                    lastLockFailure);
            });
        }

        private void probeRollbackReleaseOnce() throws SQLException {
            runSqlBounded("single rollback-release probe", () -> {
                probeRollbackReleaseOnceWithinBound();
                return null;
            });
        }

        private void probeRollbackReleaseOnceWithinBound() throws SQLException {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                Throwable primaryFailure = null;
                try (var statement = prepare(connection, """
                    SELECT account_id
                    FROM funds.ledger_account
                    WHERE account_id IN (?, ?)
                    ORDER BY account_id
                    FOR UPDATE
                    """)) {
                    statement.setObject(1, TestPostingStack.PROVIDER_ASSET);
                    statement.setObject(2, TestPostingStack.CUSTOMER_LIABILITY);
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || !result.next() || result.next()) {
                            throw new AssertionError("rollback-release probe did not lock exactly two fixture accounts");
                        }
                    }
                } catch (SQLException | RuntimeException | Error failure) {
                    primaryFailure = failure;
                    throw failure;
                } finally {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        if (primaryFailure == null) {
                            throw rollbackFailure;
                        }
                        primaryFailure.addSuppressed(rollbackFailure);
                    }
                }
            }
        }

        private static List<IdempotencyState> idempotency(Connection connection, UUID commandId)
            throws SQLException {
            var states = new ArrayList<IdempotencyState>();
            try (var statement = prepare(connection, """
                SELECT command_id, request_hash, state, journal_id, result_json::text,
                       created_at, completed_at
                FROM funds.idempotency_command
                WHERE command_id = ?
                ORDER BY command_id
                """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.add(new IdempotencyState(
                            result.getObject("command_id", UUID.class),
                            result.getString("request_hash"),
                            result.getString("state"),
                            result.getObject("journal_id", UUID.class),
                            result.getString("result_json"),
                            result.getObject("created_at", OffsetDateTime.class),
                            result.getObject("completed_at", OffsetDateTime.class)));
                    }
                }
            }
            return List.copyOf(states);
        }

        private static List<JournalState> journals(Connection connection, UUID commandId) throws SQLException {
            var states = new ArrayList<JournalState>();
            try (var statement = prepare(connection, """
                SELECT journal_id, journal_sequence, command_id, correlation_id,
                       business_transaction_id, legal_entity_id, book_id, period_id,
                       transaction_type, narration, booking_time, value_date,
                       reversal_of_journal_id, policy_version, canonical_hash
                FROM funds.journal
                WHERE command_id = ?
                ORDER BY journal_sequence
                """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.add(new JournalState(
                            result.getObject("journal_id", UUID.class),
                            result.getLong("journal_sequence"),
                            result.getObject("command_id", UUID.class),
                            result.getObject("correlation_id", UUID.class),
                            result.getObject("business_transaction_id", UUID.class),
                            result.getObject("legal_entity_id", UUID.class),
                            result.getObject("book_id", UUID.class),
                            result.getObject("period_id", UUID.class),
                            result.getString("transaction_type"),
                            result.getString("narration"),
                            result.getObject("booking_time", OffsetDateTime.class),
                            result.getObject("value_date", LocalDate.class),
                            result.getObject("reversal_of_journal_id", UUID.class),
                            result.getInt("policy_version"),
                            result.getString("canonical_hash")));
                    }
                }
            }
            return List.copyOf(states);
        }

        private static List<PostingState> postings(Connection connection, UUID commandId) throws SQLException {
            var states = new ArrayList<PostingState>();
            try (var statement = prepare(connection, """
                SELECT posting.posting_id, posting.journal_id, posting.account_id,
                       posting.currency, posting.signed_minor_units,
                       posting.account_sequence, posting.dimensions::text
                FROM funds.posting posting
                JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                WHERE journal.command_id = ?
                  AND posting.account_id IN (?, ?)
                ORDER BY posting.posting_id
                """)) {
                statement.setObject(1, commandId);
                statement.setObject(2, TestPostingStack.PROVIDER_ASSET);
                statement.setObject(3, TestPostingStack.CUSTOMER_LIABILITY);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.add(new PostingState(
                            result.getObject("posting_id", UUID.class),
                            result.getObject("journal_id", UUID.class),
                            result.getObject("account_id", UUID.class),
                            result.getString("currency"),
                            result.getLong("signed_minor_units"),
                            result.getLong("account_sequence"),
                            result.getString("dimensions")));
                    }
                }
            }
            return List.copyOf(states);
        }

        private static Map<UUID, BalanceState> balances(Connection connection) throws SQLException {
            var states = new LinkedHashMap<UUID, BalanceState>();
            try (var statement = prepare(connection, """
                SELECT account_id, signed_posting_total, latest_account_sequence, version
                FROM funds.materialised_balance
                WHERE account_id IN (?, ?)
                ORDER BY account_id
                """)) {
                statement.setObject(1, TestPostingStack.PROVIDER_ASSET);
                statement.setObject(2, TestPostingStack.CUSTOMER_LIABILITY);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.put(
                            result.getObject("account_id", UUID.class),
                            new BalanceState(
                                result.getLong("signed_posting_total"),
                                result.getLong("latest_account_sequence"),
                                result.getLong("version")));
                    }
                }
            }
            return Map.copyOf(states);
        }

        private static Map<String, ControlState> controls(Connection connection) throws SQLException {
            var states = new LinkedHashMap<String, ControlState>();
            try (var statement = prepare(connection, """
                SELECT control_account_code, signed_posting_total, latest_journal_sequence
                FROM funds.control_account_projection
                WHERE book_id = ? AND currency = 'NGN'
                  AND control_account_code IN (?, ?, ?)
                ORDER BY control_account_code
                """)) {
                statement.setObject(1, TestPostingStack.BOOK_ID);
                statement.setString(2, TestPostingStack.PROVIDER_CONTROL);
                statement.setString(3, TestPostingStack.CUSTOMER_CONTROL);
                statement.setString(4, TestPostingStack.INDEPENDENT_CONTROL);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.put(
                            result.getString("control_account_code"),
                            new ControlState(
                                result.getLong("signed_posting_total"),
                                result.getLong("latest_journal_sequence")));
                    }
                }
            }
            return Map.copyOf(states);
        }

        private static List<OutboxState> outbox(Connection connection, UUID commandId) throws SQLException {
            var states = new ArrayList<OutboxState>();
            try (var statement = prepare(connection, """
                SELECT event.event_id, event.aggregate_id, event.aggregate_version,
                       event.event_type, event.schema_version, event.payload::text,
                       event.created_at, event.published_at, event.publish_attempts
                FROM funds.outbox_event event
                JOIN funds.journal journal ON journal.journal_id = event.aggregate_id
                WHERE journal.command_id = ?
                ORDER BY event.event_id
                """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.add(new OutboxState(
                            result.getObject("event_id", UUID.class),
                            result.getObject("aggregate_id", UUID.class),
                            result.getLong("aggregate_version"),
                            result.getString("event_type"),
                            result.getInt("schema_version"),
                            result.getString("payload"),
                            result.getObject("created_at", OffsetDateTime.class),
                            result.getObject("published_at", OffsetDateTime.class),
                            result.getInt("publish_attempts")));
                    }
                }
            }
            return List.copyOf(states);
        }
    }

    private static final class WorkerHandle implements AutoCloseable {
        private final Process process;
        private final CrashPostingWorker.CrashPoint point;
        private final FutureTask<String> outputRead;
        private final Thread outputReader;
        private final Duration timeout;

        private WorkerHandle(Process process, CrashPostingWorker.CrashPoint point) {
            this(process, point, PROCESS_TIMEOUT);
        }

        private WorkerHandle(
            Process process,
            CrashPostingWorker.CrashPoint point,
            Duration timeout
        ) {
            this.process = process;
            this.point = point;
            this.timeout = timeout;
            this.outputRead = new FutureTask<>(() -> new String(process.getInputStream().readAllBytes(), UTF_8));
            this.outputReader = Thread.ofVirtual()
                .name("crash-posting-worker-output-" + point)
                .start(outputRead);
        }

        private WorkerExit awaitExit() {
            Deadline deadline = Deadline.after(timeout);
            try {
                if (!process.waitFor(deadline.remainingNanos(), NANOSECONDS)) {
                    throw new AssertionError("crash worker did not exit within 10 seconds at " + point);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("crash worker wait was interrupted at " + point, interrupted);
            }

            try {
                return new WorkerExit(
                    process.exitValue(),
                    outputRead.get(Math.max(1, deadline.remainingNanos()), NANOSECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("crash worker output read was interrupted at " + point, interrupted);
            } catch (ExecutionException failure) {
                throw new AssertionError("crash worker output read failed at " + point, failure.getCause());
            } catch (TimeoutException failure) {
                throw new AssertionError("crash worker output was not drained within 10 seconds at " + point, failure);
            }
        }

        @Override
        public void close() {
            Deadline deadline = Deadline.after(timeout);
            AssertionError cleanupFailure = null;
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    long remaining = deadline.remainingNanos();
                    if (remaining == 0 || !process.waitFor(remaining, NANOSECONDS)) {
                        cleanupFailure = append(
                            cleanupFailure,
                            new AssertionError("crash worker survived forced destruction at " + point));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cleanupFailure = append(
                        cleanupFailure,
                        new AssertionError("forced crash-worker wait was interrupted at " + point, interrupted));
                }
            }

            try {
                process.getInputStream().close();
            } catch (IOException failure) {
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError("crash-worker output close failed at " + point, failure));
            }
            if (outputReader.isAlive()) {
                outputRead.cancel(true);
                outputReader.interrupt();
                try {
                    long remaining = deadline.remainingNanos();
                    if (remaining == 0 || !outputReader.join(Duration.ofNanos(remaining))) {
                        cleanupFailure = append(
                            cleanupFailure,
                            new AssertionError("crash-worker output reader survived 10-second cleanup at " + point));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cleanupFailure = append(
                        cleanupFailure,
                        new AssertionError("crash-worker output cleanup was interrupted at " + point, interrupted));
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private static AssertionError append(AssertionError existing, AssertionError next) {
            return PostingCrashRecoveryIT.append(existing, next);
        }
    }

    private static AssertionError append(AssertionError existing, AssertionError next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    private static final class Deadline {
        private final long expiresAt;

        private Deadline(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        private static Deadline after(Duration timeout) {
            long now = System.nanoTime();
            long duration = Math.max(0, timeout.toNanos());
            long expiresAt = Long.MAX_VALUE - now < duration ? Long.MAX_VALUE : now + duration;
            return new Deadline(expiresAt);
        }

        private long remainingNanos() {
            return Math.max(0, expiresAt - System.nanoTime());
        }

        private boolean hasRemaining() {
            return remainingNanos() > 0;
        }
    }

    private static final class TimeoutDataSource implements DataSource {
        private final DataSource delegate;

        private TimeoutDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return configureTimeouts(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return configureTimeouts(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter output) throws SQLException {
            delegate.setLogWriter(output);
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
        public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            return delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return type.isInstance(this) || delegate.isWrapperFor(type);
        }
    }

    private static final class StubbornInputStream extends InputStream {
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int read() {
            while (released.getCount() != 0) {
                try {
                    released.await();
                } catch (InterruptedException ignored) {
                    // Deliberately resist interruption to pressure-test the combined cleanup deadline.
                }
            }
            return -1;
        }

        @Override
        public void close() {}

        private void release() {
            released.countDown();
        }
    }

    private static final class SlowTerminationProcess extends Process {
        private final InputStream input;
        private final Duration waitDuration;
        private final AtomicBoolean destroyed = new AtomicBoolean();

        private SlowTerminationProcess(InputStream input, Duration waitDuration) {
            this.input = input;
            this.waitDuration = waitDuration;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            long waitMillis = Math.min(waitDuration.toMillis(), unit.toMillis(timeout));
            new CountDownLatch(1).await(waitMillis, MILLISECONDS);
            return false;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("process is still alive");
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyed.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            return true;
        }
    }
}
