package com.corebanking.funds.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.security.SimplePassword;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
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

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
        rows = new CrashProbe(dataSource);
        credentials = databaseCredentials(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void processDeathBeforeCommitRollsBackAndIdenticalRetryPostsOnce() throws Exception {
        PostingCommand command = CrashPostingWorker.command(BEFORE_COMMIT_COMMAND_ID);
        CrashSnapshot before = rows.snapshot(BEFORE_COMMIT_COMMAND_ID);

        try (WorkerHandle worker = startWorker(
            CrashPostingWorker.CrashPoint.BEFORE_COMMIT,
            BEFORE_COMMIT_COMMAND_ID)) {
            assertHaltedAt(worker.awaitExit(), CrashPostingWorker.CrashPoint.BEFORE_COMMIT);
            assertEquals(before, rows.awaitSnapshot(BEFORE_COMMIT_COMMAND_ID, before));

            PostingResult recovered = TestPostingStack
                .create(dataSource, PostingTransactionObserver.noop())
                .postingService()
                .post(command);
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

            PostingResult replayed = TestPostingStack
                .create(dataSource, PostingTransactionObserver.noop())
                .postingService()
                .post(command);
            CrashSnapshot afterReplay = rows.snapshot(AFTER_COMMIT_COMMAND_ID);

            assertAll(
                () -> assertEquals(committedJournal, replayed.journalId()),
                () -> assertEquals(committed.journals().getFirst().journalSequence(), replayed.journalSequence()),
                () -> assertEquals(committed, afterReplay));
            assertOneCompletedEffect(command, replayed, before, afterReplay);
        }
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
            () -> assertEquals("COMPLETED", after.idempotency().getFirst().state()),
            () -> assertEquals(command.requestHash(), after.idempotency().getFirst().requestHash()),
            () -> assertEquals(result.journalId(), after.idempotency().getFirst().journalId()),
            () -> assertNotNull(after.idempotency().getFirst().resultJson()),
            () -> assertEquals(1, after.journals().size()),
            () -> assertEquals(result.journalId(), after.journals().getFirst().journalId()),
            () -> assertEquals(result.journalSequence(), after.journals().getFirst().journalSequence()),
            () -> assertEquals(command.requestHash(), after.journals().getFirst().canonicalHash()),
            () -> assertEquals(
                List.of(
                    new PostingState(
                        CrashPostingWorker.PROVIDER_POSTING_ID,
                        TestPostingStack.PROVIDER_ASSET,
                        CrashPostingWorker.POSTING_AMOUNT,
                        TestPostingStack.PROVIDER_INITIAL_SEQUENCE + 1),
                    new PostingState(
                        CrashPostingWorker.CUSTOMER_POSTING_ID,
                        TestPostingStack.CUSTOMER_LIABILITY,
                        -CrashPostingWorker.POSTING_AMOUNT,
                        TestPostingStack.CUSTOMER_INITIAL_SEQUENCE + 1)),
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
            () -> assertEquals(result.journalId(), after.outbox().getFirst().aggregateId()),
            () -> assertEquals(result.journalSequence(), after.outbox().getFirst().aggregateVersion()),
            () -> assertEquals("JournalPosted", after.outbox().getFirst().eventType()));
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
        String requestHash,
        String state,
        UUID journalId,
        String resultJson
    ) {}

    private record JournalState(UUID journalId, long journalSequence, String canonicalHash) {}

    private record PostingState(
        UUID postingId,
        UUID accountId,
        long signedMinorUnits,
        long accountSequence
    ) {}

    private record BalanceState(long signedPostingTotal, long latestAccountSequence, long version) {}

    private record ControlState(long signedPostingTotal, long latestJournalSequence) {}

    private record OutboxState(UUID aggregateId, long aggregateVersion, String eventType, String payload) {}

    private static final class CrashProbe {
        private final DataSource dataSource;

        private CrashProbe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        private CrashSnapshot awaitSnapshot(UUID commandId, CrashSnapshot expected) throws SQLException {
            long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            CrashSnapshot actual;
            do {
                actual = snapshot(commandId);
                if (expected.equals(actual)) {
                    return actual;
                }
                Thread.onSpinWait();
            } while (System.nanoTime() < deadline);
            return actual;
        }

        private UUID awaitCommittedJournal(UUID commandId) throws SQLException {
            long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            do {
                Optional<UUID> journalId = journalId(commandId);
                if (journalId.isPresent()) {
                    return journalId.get();
                }
                Thread.onSpinWait();
            } while (System.nanoTime() < deadline);
            throw new AssertionError("committed journal was not visible within 10 seconds");
        }

        private Optional<UUID> journalId(UUID commandId) throws SQLException {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("""
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

        private static List<IdempotencyState> idempotency(Connection connection, UUID commandId)
            throws SQLException {
            var states = new ArrayList<IdempotencyState>();
            try (var statement = connection.prepareStatement("""
                SELECT request_hash, state, journal_id, result_json::text
                FROM funds.idempotency_command
                WHERE command_id = ?
                ORDER BY command_id
                """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.add(new IdempotencyState(
                            result.getString("request_hash"),
                            result.getString("state"),
                            result.getObject("journal_id", UUID.class),
                            result.getString("result_json")));
                    }
                }
            }
            return List.copyOf(states);
        }

        private static List<JournalState> journals(Connection connection, UUID commandId) throws SQLException {
            var states = new ArrayList<JournalState>();
            try (var statement = connection.prepareStatement("""
                SELECT journal_id, journal_sequence, canonical_hash
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
                            result.getString("canonical_hash")));
                    }
                }
            }
            return List.copyOf(states);
        }

        private static List<PostingState> postings(Connection connection, UUID commandId) throws SQLException {
            var states = new ArrayList<PostingState>();
            try (var statement = connection.prepareStatement("""
                SELECT posting.posting_id, posting.account_id, posting.signed_minor_units,
                       posting.account_sequence
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
                            result.getObject("account_id", UUID.class),
                            result.getLong("signed_minor_units"),
                            result.getLong("account_sequence")));
                    }
                }
            }
            return List.copyOf(states);
        }

        private static Map<UUID, BalanceState> balances(Connection connection) throws SQLException {
            var states = new LinkedHashMap<UUID, BalanceState>();
            try (var statement = connection.prepareStatement("""
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
            try (var statement = connection.prepareStatement("""
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
            try (var statement = connection.prepareStatement("""
                SELECT event.aggregate_id, event.aggregate_version, event.event_type,
                       event.payload::text
                FROM funds.outbox_event event
                JOIN funds.journal journal ON journal.journal_id = event.aggregate_id
                WHERE journal.command_id = ?
                ORDER BY event.event_id
                """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        states.add(new OutboxState(
                            result.getObject("aggregate_id", UUID.class),
                            result.getLong("aggregate_version"),
                            result.getString("event_type"),
                            result.getString("payload")));
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

        private WorkerHandle(Process process, CrashPostingWorker.CrashPoint point) {
            this.process = process;
            this.point = point;
            this.outputRead = new FutureTask<>(() -> new String(process.getInputStream().readAllBytes(), UTF_8));
            this.outputReader = Thread.ofVirtual()
                .name("crash-posting-worker-output-" + point)
                .start(outputRead);
        }

        private WorkerExit awaitExit() {
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), SECONDS)) {
                    throw new AssertionError("crash worker did not exit within 10 seconds at " + point);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("crash worker wait was interrupted at " + point, interrupted);
            }

            try {
                return new WorkerExit(
                    process.exitValue(),
                    outputRead.get(PROCESS_TIMEOUT.toSeconds(), SECONDS));
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
            AssertionError cleanupFailure = null;
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), SECONDS)) {
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
                    if (!outputReader.join(PROCESS_TIMEOUT)) {
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
            if (existing == null) {
                return next;
            }
            existing.addSuppressed(next);
            return existing;
        }
    }
}
