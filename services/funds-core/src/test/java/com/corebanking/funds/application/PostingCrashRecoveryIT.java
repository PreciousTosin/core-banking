package com.corebanking.funds.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
import org.postgresql.PGStatement;
import org.postgresql.ds.PGSimpleDataSource;

@QuarkusTest
class PostingCrashRecoveryIT {
    private static final UUID BEFORE_COMMIT_COMMAND_ID = TestPostingStack.uuid(40);
    private static final UUID AFTER_COMMIT_COMMAND_ID = TestPostingStack.uuid(50);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    DataSource dataSource;

    @TempDir
    Path tempDirectory;

    private CrashProbe rows;
    private DatabaseCredentials credentials;
    private PGSimpleDataSource boundedDataSource;

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
        credentials = databaseCredentials(dataSource);
        boundedDataSource = timeoutDataSource(credentials);
        rows = new CrashProbe(boundedDataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void unchangedMvccSnapshotDoesNotClaimRollbackWhileAccountLocksRemainHeld() throws Exception {
        CrashSnapshot before = rows.snapshot(BEFORE_COMMIT_COMMAND_ID);
        runJdbcBounded(
            PROCESS_TIMEOUT,
            "lock-holder proof",
            boundedDataSource,
            resources -> resources.withConnection(blocker -> {
                blocker.setAutoCommit(false);
                try (var tracked = resources.prepare(blocker, """
                    SELECT account_id
                    FROM funds.ledger_account
                    WHERE account_id IN (?, ?)
                    ORDER BY account_id
                    FOR UPDATE
                    """)) {
                    var statement = tracked.statement();
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
                return null;
            }));
    }

    @Test
    void boundedCrashProbesDoNotContaminateQuarkusPoolSessionTimeouts() throws Exception {
        SessionTimeouts before = pooledSessionTimeouts();

        rows.snapshot(BEFORE_COMMIT_COMMAND_ID);

        assertAll(
            () -> assertEquals(new SessionTimeouts("0", "0"), before),
            () -> assertEquals(before, pooledSessionTimeouts()));
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
    void callerInterruptionCompletesBoundedCleanupBeforeRestoringInterrupt() throws Exception {
        var operationStarted = new CountDownLatch(1);
        var operationInterrupted = new CountDownLatch(1);
        var allowOperationCleanup = new CountDownLatch(1);
        var operationCleaned = new AtomicBoolean();
        var callerFinished = new CountDownLatch(1);
        var callerInterruptRestored = new AtomicBoolean();

        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                assertThrows(AssertionError.class, () -> runBounded(
                    Duration.ofSeconds(2),
                    "interrupted bounded-call proof",
                    () -> {
                        operationStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException expected) {
                            operationInterrupted.countDown();
                            boolean cleanupAllowed = false;
                            while (!cleanupAllowed) {
                                try {
                                    allowOperationCleanup.await();
                                    cleanupAllowed = true;
                                } catch (InterruptedException repeatedCancellation) {
                                    // Future.cancel and shutdownNow may both signal cancellation.
                                }
                            }
                            operationCleaned.set(true);
                            throw expected;
                        }
                        return null;
                    }));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                callerFinished.countDown();
            }
        });

        try {
            assertTrue(operationStarted.await(1, SECONDS));
            caller.interrupt();
            assertTrue(operationInterrupted.await(1, SECONDS));
            assertEquals(1, callerFinished.getCount(), "caller returned before bounded cleanup finished");
        } finally {
            allowOperationCleanup.countDown();
            assertTrue(caller.join(Duration.ofSeconds(1)));
        }

        assertAll(
            () -> assertTrue(operationCleaned.get()),
            () -> assertTrue(callerInterruptRestored.get()));
    }

    @Test
    void cleanupFailureRestoresInterruptAndRetainsPrimaryAndSuppressedDiagnostics() {
        var executor = new InterruptingCleanupExecutor();
        Thread.interrupted();
        try {
            AssertionError failure = assertThrows(AssertionError.class, () -> runBounded(
                Duration.ofSeconds(1),
                "interrupting cleanup failure",
                () -> null,
                () -> null,
                () -> new AssertionError("release diagnostic"),
                executor));

            assertAll(
                () -> assertTrue(executor.isTerminated()),
                () -> assertTrue(Thread.currentThread().isInterrupted()),
                () -> assertTrue(throwableTreeContains(failure, "executor shutdown failed")),
                () -> assertTrue(throwableTreeContains(failure, "release diagnostic")));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void lateJdbcConnectionIsClosedWhenTimeoutWinsAcquisitionRace() {
        var lateDataSource = new InterruptReturningDataSource();

        AssertionError failure = assertThrows(AssertionError.class, () -> runJdbcBounded(
            Duration.ofSeconds(1),
            "late JDBC acquisition proof",
            lateDataSource,
            resources -> resources.withConnection(connection -> null)));

        assertAll(
            () -> assertTrue(failure.getMessage().contains("late JDBC acquisition proof")),
            () -> assertEquals(0, lateDataSource.acquisitionStarted.getCount()),
            () -> assertEquals(0, lateDataSource.acquisitionFinished.getCount()),
            () -> assertTrue(lateDataSource.aborted.get()),
            () -> assertTrue(lateDataSource.closed.get()));
    }

    @Test
    void trackedJdbcProxiesUseIdentitySemanticsAndPreserveWrapperContracts() throws Exception {
        runJdbcBounded(
            PROCESS_TIMEOUT,
            "tracked JDBC proxy contract",
            boundedDataSource,
            resources -> resources.withConnection(connection -> {
                PGConnection connectionDelegate = connection.unwrap(PGConnection.class);
                assertAll(
                    () -> assertTrue(connection.equals(connection)),
                    () -> assertFalse(connection.equals(connectionDelegate)),
                    () -> assertFalse(connectionDelegate.equals(connection)),
                    () -> assertEquals(System.identityHashCode(connection), connection.hashCode()),
                    () -> assertTrue(connection.toString().startsWith("tracked Connection@")),
                    () -> assertTrue(connection.isWrapperFor(Connection.class)),
                    () -> assertSame(connection, connection.unwrap(Connection.class)),
                    () -> assertTrue(connection.isWrapperFor(PGConnection.class)),
                    () -> assertSame(connectionDelegate, connection.unwrap(PGConnection.class)));

                try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                    PGStatement statementDelegate = statement.unwrap(PGStatement.class);
                    assertAll(
                        () -> assertTrue(statement.equals(statement)),
                        () -> assertFalse(statement.equals(statementDelegate)),
                        () -> assertFalse(statementDelegate.equals(statement)),
                        () -> assertEquals(System.identityHashCode(statement), statement.hashCode()),
                        () -> assertTrue(statement.toString().startsWith("tracked PreparedStatement@")),
                        () -> assertTrue(statement.isWrapperFor(Statement.class)),
                        () -> assertSame(statement, statement.unwrap(Statement.class)),
                        () -> assertTrue(statement.isWrapperFor(PreparedStatement.class)),
                        () -> assertSame(statement, statement.unwrap(PreparedStatement.class)),
                        () -> assertTrue(statement.isWrapperFor(PGStatement.class)),
                        () -> assertSame(statementDelegate, statement.unwrap(PGStatement.class)));
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(1, result.getInt(1));
                    }
                }
                return null;
            }));
    }

    @Test
    void blockingResourceCancellationRunsInOwnedBoundedTaskBeforeMainWorkJoins() {
        Thread caller = Thread.currentThread();
        var operationStarted = new CountDownLatch(1);
        var operationInterrupted = new CountDownLatch(1);
        var cancellationStarted = new CountDownLatch(1);
        var cancellationInterrupted = new CountDownLatch(1);
        var cancellationRanOnCaller = new AtomicBoolean();
        var releaseVerified = new AtomicBoolean();
        var sequence = new AtomicInteger();

        AssertionError failure = assertThrows(AssertionError.class, () -> runBounded(
            Duration.ofMillis(400),
            "blocking resource-cancellation proof",
            () -> {
                operationStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    operationInterrupted.countDown();
                    sequence.compareAndSet(2, 3);
                    throw expected;
                }
                return null;
            },
            () -> {
                if (Thread.currentThread() == caller) {
                    cancellationRanOnCaller.set(true);
                    return new AssertionError("resource cancellation ran on the caller");
                }
                cancellationStarted.countDown();
                sequence.compareAndSet(0, 1);
                try {
                    new CountDownLatch(1).await();
                    return null;
                } catch (InterruptedException expected) {
                    cancellationInterrupted.countDown();
                    sequence.compareAndSet(1, 2);
                    return new AssertionError("blocked cancellation interrupted", expected);
                }
            },
            () -> {
                releaseVerified.set(true);
                return new AssertionError("live resource diagnostic");
            }));

        assertAll(
            () -> assertEquals(0, operationStarted.getCount()),
            () -> assertFalse(cancellationRanOnCaller.get()),
            () -> assertEquals(0, cancellationStarted.getCount()),
            () -> assertEquals(0, cancellationInterrupted.getCount()),
            () -> assertEquals(0, operationInterrupted.getCount()),
            () -> assertEquals(3, sequence.get()),
            () -> assertTrue(releaseVerified.get()),
            () -> assertTrue(throwableTreeContains(failure, "resource cancellation task")),
            () -> assertTrue(throwableTreeContains(failure, "live resource diagnostic")));
    }

    @Test
    void realRepositoryRetryClosesAndClearsTheFailedAttemptBeforeOpeningTheNextConnection() throws Exception {
        UUID commandId = TestPostingStack.uuid(60);
        PostingCommand command = CrashPostingWorker.command(commandId);
        CrashSnapshot before = rows.snapshot(commandId);
        var retryDataSource = new SerializationFailureOnceDataSource(boundedDataSource);

        PostingResult result = runJdbcBounded(
            PROCESS_TIMEOUT,
            "tracked real-repository retry",
            retryDataSource,
            resources -> TestPostingStack
                .create(resources.trackingDataSource(), PostingTransactionObserver.noop())
                .postingService()
                .post(command));

        CrashSnapshot after = rows.snapshot(commandId);
        assertAll(
            () -> assertTrue(retryDataSource.serializationFailureInjected.get()),
            () -> assertEquals(2, retryDataSource.acquiredConnections.get()),
            () -> assertEquals(2, retryDataSource.physicalConnections.size()),
            () -> assertTrue(retryDataSource.physicalConnections.stream().allMatch(connection -> {
                try {
                    return connection.isClosed();
                } catch (SQLException failure) {
                    throw new AssertionError("failed to inspect retry connection", failure);
                }
            })));
        assertOneCompletedEffect(command, result, before, after);
    }

    @Test
    void boundedCancellationCancelsTheBlockingRealRepositoryStatementAndAbortsItsConnection() throws Exception {
        UUID commandId = TestPostingStack.uuid(61);
        PostingCommand command = CrashPostingWorker.command(commandId);
        CrashSnapshot before = rows.snapshot(commandId);
        PGSimpleDataSource slowDataSource = timeoutDataSource(credentials);
        slowDataSource.setSocketTimeout(5);
        slowDataSource.setQueryTimeout(5);
        slowDataSource.setOptions("-c statement_timeout=5000 -c lock_timeout=5000");
        var observedDataSource = new BlockingStatementObservationDataSource(slowDataSource);

        runJdbcBounded(
            PROCESS_TIMEOUT,
            "blocking repository fixture",
            boundedDataSource,
            holderResources -> holderResources.withConnection(holder -> {
                holder.setAutoCommit(false);
                try (var tracked = holderResources.prepare(holder, """
                    SELECT account_id
                    FROM funds.ledger_account
                    WHERE account_id IN (?, ?)
                    ORDER BY account_id
                    FOR UPDATE
                    """)) {
                    var statement = tracked.statement();
                    statement.setObject(1, TestPostingStack.PROVIDER_ASSET);
                    statement.setObject(2, TestPostingStack.CUSTOMER_LIABILITY);
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.next());
                    }
                }

                AssertionError failure = assertThrows(AssertionError.class, () -> runJdbcBounded(
                    Duration.ofSeconds(2),
                    "blocking real-repository cancellation",
                    observedDataSource,
                    resources -> TestPostingStack
                        .create(resources.trackingDataSource(), PostingTransactionObserver.noop())
                        .postingService()
                        .post(command)));

                assertAll(
                    () -> assertTrue(failure.getMessage().contains("blocking real-repository cancellation")),
                    () -> assertEquals(0, observedDataSource.blockingStatementStarted.getCount()),
                    () -> assertTrue(observedDataSource.statementCancelled.get()),
                    () -> assertTrue(observedDataSource.connectionAborted.get()),
                    () -> assertTrue(observedDataSource.connectionClosed.get()),
                    () -> assertEquals(before, rows.snapshot(commandId)));
                holder.rollback();
                return null;
            }));
    }

    @Test
    void workerOperationAndCleanupBudgetsStartFreshAfterLongVisibilityPhase() {
        var clock = new MutableNanoClock();
        var process = new DeterministicExitProcess();
        var waits = new RecordingTimedWaits(clock);
        var files = new RecordingWorkerFiles(process);
        Path outputFile = tempDirectory.resolve("deterministic-worker-output.log");
        var worker = new WorkerHandle(
            process,
            CrashPostingWorker.CrashPoint.BEFORE_COMMIT,
            outputFile,
            Duration.ofNanos(100),
            clock::nanoTime,
            waits,
            files);

        clock.advance(1_000);
        WorkerExit exit = worker.awaitExit();
        clock.advance(1_000);
        worker.close();

        assertAll(
            () -> assertEquals(91, exit.exitCode()),
            () -> assertEquals("REACHED:BEFORE_COMMIT\n", exit.output()),
            () -> assertTrue(files.readAfterExit.get()),
            () -> assertTrue(files.deleted.get()),
            () -> assertEquals(outputFile, files.readPath.get()),
            () -> assertEquals(outputFile, files.deletedPath.get()),
            () -> assertEquals(List.of(100L, 100L, 100L), waits.observedBudgets));
    }

    @Test
    void workerCleanupReservesDeletionWithinOneDiminishingDeadline() {
        var clock = new MutableNanoClock();
        var process = new DeterministicExitProcess();
        var waits = new RecordingTimedWaits(clock);
        var files = new RecordingWorkerFiles(process);
        Path outputFile = tempDirectory.resolve("deterministic-cleanup-output.log");
        var worker = new WorkerHandle(
            process,
            CrashPostingWorker.CrashPoint.BEFORE_COMMIT,
            outputFile,
            Duration.ofNanos(100),
            clock::nanoTime,
            waits,
            files);

        worker.close();

        assertAll(
            () -> assertTrue(files.deleted.get()),
            () -> assertEquals(outputFile, files.deletedPath.get()),
            () -> assertEquals(List.of(90L, 70L), waits.observedBudgets));
    }

    @Test
    void workerCleanupPrestartsOneDeletionAndJoinsItAfterNonDeletionBudgetIsExhausted() {
        var clock = new MutableNanoClock();
        var process = new DeterministicExitProcess();
        var deletionPrepared = new CountDownLatch(1);
        var deletionReleased = new CountDownLatch(1);
        var deletionJoined = new CountDownLatch(1);
        var deletionAttempts = new AtomicInteger();
        Path outputFile = tempDirectory.resolve("exhausted-cleanup-output.log");
        TimedWaits waits = new TimedWaits() {
            @Override
            public boolean waitFor(Process ignored, long remainingNanos) {
                assertEquals(0, deletionPrepared.getCount(), "process cleanup began before deletion was pre-started");
                assertEquals(90, remainingNanos);
                clock.advance(90);
                process.exited.set(true);
                return true;
            }

            @Override
            public <T> T call(Callable<T> operation, Deadline deadline, String description) throws Exception {
                throw new AssertionError("cleanup must join the pre-started deletion, not submit a late call");
            }

            @Override
            public <T> DeferredCall<T> defer(
                Callable<T> operation,
                Deadline startDeadline,
                Deadline cleanupDeadline,
                String description
            ) {
                assertEquals(90, startDeadline.remainingNanos());
                assertEquals(100, cleanupDeadline.remainingNanos());
                deletionPrepared.countDown();
                return completionDeadline -> {
                    deletionReleased.countDown();
                    try {
                        assertEquals(10, completionDeadline.remainingNanos());
                        return operation.call();
                    } finally {
                        deletionJoined.countDown();
                    }
                };
            }
        };
        WorkerFiles files = new WorkerFiles() {
            @Override
            public String read(Path path) {
                throw new AssertionError("cleanup must not read worker output");
            }

            @Override
            public void delete(Path path) {
                assertEquals(outputFile, path);
                deletionAttempts.incrementAndGet();
            }
        };
        var worker = new WorkerHandle(
            process,
            CrashPostingWorker.CrashPoint.BEFORE_COMMIT,
            outputFile,
            Duration.ofNanos(100),
            clock::nanoTime,
            waits,
            files);

        worker.close();

        assertAll(
            () -> assertEquals(0, deletionPrepared.getCount()),
            () -> assertEquals(0, deletionReleased.getCount()),
            () -> assertEquals(0, deletionJoined.getCount()),
            () -> assertEquals(1, deletionAttempts.get()));
    }

    @Test
    void workerStartFailureStillAttemptsBoundedDeletionAndSuppressesItsFailure() {
        var clock = new MutableNanoClock();
        var waits = new RecordingTimedWaits(clock);
        var deletionAttempted = new AtomicBoolean();
        var deletionFailure = new IOException("deterministic deletion diagnostic");
        Path outputFile = tempDirectory.resolve("failed-worker-start-output.log");
        WorkerFiles files = new WorkerFiles() {
            @Override
            public String read(Path path) {
                throw new AssertionError("start-failure output must not be read");
            }

            @Override
            public void delete(Path path) throws IOException {
                assertEquals(outputFile, path);
                deletionAttempted.set(true);
                throw deletionFailure;
            }
        };

        IOException startFailure = assertThrows(IOException.class, () -> startWorker(
            () -> {
                clock.advance(1_000);
                throw new IOException("deterministic process-start diagnostic");
            },
            CrashPostingWorker.CrashPoint.BEFORE_COMMIT,
            outputFile,
            Duration.ofNanos(100),
            clock::nanoTime,
            waits,
            files));

        assertAll(
            () -> assertEquals("deterministic process-start diagnostic", startFailure.getMessage()),
            () -> assertTrue(deletionAttempted.get()),
            () -> assertEquals(List.of(100L), waits.observedBudgets),
            () -> assertEquals(1, startFailure.getSuppressed().length),
            () -> assertSame(deletionFailure, startFailure.getSuppressed()[0]));
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
        return runJdbcBounded(
            PROCESS_TIMEOUT,
            description,
            boundedDataSource,
            resources -> TestPostingStack
                .create(resources.trackingDataSource(), PostingTransactionObserver.noop())
                .postingService()
                .post(command));
    }

    private SessionTimeouts pooledSessionTimeouts() throws SQLException {
        return runJdbcBounded(
            PROCESS_TIMEOUT,
            "pooled session-timeout inspection",
            dataSource,
            resources -> resources.withConnection(connection -> {
                try (var tracked = resources.prepare(connection, """
                    SELECT current_setting('statement_timeout'), current_setting('lock_timeout')
                    """)) {
                    try (var result = tracked.statement().executeQuery()) {
                        assertTrue(result.next());
                        return new SessionTimeouts(result.getString(1), result.getString(2));
                    }
                }
            }));
    }

    private WorkerHandle startWorker(CrashPostingWorker.CrashPoint point, UUID commandId) throws IOException {
        Path outputFile = Files.createTempFile(tempDirectory, "crash-posting-" + point + '-', ".log");
        var builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("surefire.test.class.path"),
            CrashPostingWorker.class.getName(),
            commandId.toString(),
            point.name())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile());
        builder.environment().put("CB_TEST_JDBC_URL", credentials.jdbcUrl());
        builder.environment().put("CB_TEST_DB_USER", credentials.username());
        builder.environment().put("CB_TEST_DB_PASSWORD", credentials.password());
        return startWorker(
            builder::start,
            point,
            outputFile,
            PROCESS_TIMEOUT,
            System::nanoTime,
            SystemTimedWaits.INSTANCE,
            SystemWorkerFiles.INSTANCE);
    }

    private static WorkerHandle startWorker(
        ProcessStarter processStarter,
        CrashPostingWorker.CrashPoint point,
        Path outputFile,
        Duration timeout,
        LongSupplier nanoTime,
        TimedWaits timedWaits,
        WorkerFiles files
    ) throws IOException {
        try {
            return new WorkerHandle(
                processStarter.start(),
                point,
                outputFile,
                timeout,
                nanoTime,
                timedWaits,
                files);
        } catch (IOException startFailure) {
            boolean restoreInterrupt = Thread.interrupted();
            Deadline cleanupDeadline = Deadline.after(timeout, nanoTime);
            try {
                timedWaits.call(
                    () -> {
                        files.delete(outputFile);
                        return null;
                    },
                    cleanupDeadline,
                    "failed crash-worker output-file deletion at " + point);
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
                startFailure.addSuppressed(interrupted);
            } catch (Exception | Error deleteFailure) {
                startFailure.addSuppressed(deleteFailure);
            }
            restoreInterrupt |= Thread.interrupted();
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            throw startFailure;
        }
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

    private static PGSimpleDataSource timeoutDataSource(DatabaseCredentials credentials) {
        var isolated = new PGSimpleDataSource();
        isolated.setURL(credentials.jdbcUrl());
        isolated.setUser(credentials.username());
        isolated.setPassword(credentials.password());
        isolated.setConnectTimeout(1);
        isolated.setLoginTimeout(1);
        isolated.setSocketTimeout(1);
        isolated.setCancelSignalTimeout(1);
        isolated.setQueryTimeout(1);
        isolated.setOptions("-c statement_timeout=1000 -c lock_timeout=200");
        return isolated;
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
            () -> assertEquals(
                new CanonicalJournalHasher().sha256(command.journal()),
                result.canonicalHash()),
            () -> assertEquals(1, after.idempotency().size()),
            () -> assertEquals(command.commandId(), after.idempotency().getFirst().commandId()),
            () -> assertEquals("COMPLETED", after.idempotency().getFirst().state()),
            () -> assertEquals(command.requestHash(), after.idempotency().getFirst().requestHash()),
            () -> assertEquals(result.journalId(), after.idempotency().getFirst().journalId()),
            () -> assertNotNull(after.idempotency().getFirst().resultJson()),
            () -> assertTrue(after.idempotency().getFirst().resultJson().contains(result.journalId().toString())),
            () -> assertTrue(after.idempotency().getFirst().resultJson().contains(
                result.canonicalHash())),
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
            () -> assertEquals(result.canonicalHash(), after.journals().getFirst().canonicalHash()),
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
            () -> assertTrue(after.outbox().getFirst().payload().contains(result.canonicalHash())),
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

    private static boolean throwableTreeContains(Throwable failure, String expectedText) {
        if (failure.getMessage() != null && failure.getMessage().contains(expectedText)) {
            return true;
        }
        if (failure.getCause() != null && throwableTreeContains(failure.getCause(), expectedText)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (throwableTreeContains(suppressed, expectedText)) {
                return true;
            }
        }
        return false;
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
        return runBounded(timeout, description, operation, () -> null, () -> null);
    }

    private static <T> T runBounded(
        Duration timeout,
        String description,
        Callable<T> operation,
        Supplier<AssertionError> cancellation,
        Supplier<AssertionError> releaseVerification
    ) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("bounded-" + description.replace(' ', '-')).factory());
        return runBounded(
            timeout,
            description,
            operation,
            cancellation,
            releaseVerification,
            executor);
    }

    private static <T> T runBounded(
        Duration timeout,
        String description,
        Callable<T> operation,
        Supplier<AssertionError> cancellation,
        Supplier<AssertionError> releaseVerification,
        ExecutorService executor
    ) throws Exception {
        Deadline deadline = Deadline.after(timeout, System::nanoTime);
        return runBounded(
            deadline,
            description,
            operation,
            cancellation,
            releaseVerification,
            executor);
    }

    private static <T> T runBounded(
        Deadline deadline,
        String description,
        Callable<T> operation
    ) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("bounded-" + description.replace(' ', '-')).factory());
        return runBounded(deadline, description, operation, () -> null, () -> null, executor);
    }

    private static <T> T runBounded(
        Deadline deadline,
        String description,
        Callable<T> operation,
        Supplier<AssertionError> cancellation,
        Supplier<AssertionError> releaseVerification,
        ExecutorService executor
    ) throws Exception {
        long cleanupReserve = Math.min(
            SECONDS.toNanos(3),
            Math.max(1, deadline.remainingNanos() / 2));
        Future<T> future = executor.submit(operation);
        Throwable primaryFailure = null;
        boolean restoreInterrupt = false;
        try {
            long operationBudget = Math.max(1, deadline.remainingNanos() - cleanupReserve);
            try {
                return future.get(operationBudget, NANOSECONDS);
            } catch (TimeoutException timeoutFailure) {
                throw new AssertionError(description + " exceeded its overall bound", timeoutFailure);
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
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
            CleanupOutcome cleanup = shutdownBounded(
                executor,
                future,
                description,
                deadline,
                cancellation,
                releaseVerification);
            restoreInterrupt |= cleanup.restoreInterrupt();
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            if (cleanup.failure() != null) {
                if (primaryFailure == null) {
                    throw cleanup.failure();
                }
                if (cleanup.failure() != primaryFailure) {
                    primaryFailure.addSuppressed(cleanup.failure());
                }
            }
        }
    }

    private static CleanupOutcome shutdownBounded(
        ExecutorService executor,
        Future<?> future,
        String description,
        Deadline deadline,
        Supplier<AssertionError> cancellation,
        Supplier<AssertionError> releaseVerification
    ) {
        AssertionError cleanupFailure = null;
        boolean restoreInterrupt = Thread.interrupted();
        if (!future.isDone()) {
            ExecutorService cancellationExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("cancel-" + description.replace(' ', '-')).factory());
            Future<AssertionError> cancellationTask = null;
            try {
                cancellationTask = cancellationExecutor.submit(cancellation::get);
            } catch (RuntimeException | Error failure) {
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError(description + " resource cancellation task submission failed", failure));
            }
            if (cancellationTask != null) {
                boolean cancellationComplete = false;
                while (!cancellationComplete) {
                    long remaining = deadline.remainingNanos();
                    if (remaining == 0) {
                        cleanupFailure = append(
                            cleanupFailure,
                            new AssertionError(
                                description + " resource cancellation task did not complete within the overall bound"));
                        break;
                    }
                    long terminationReserve = Math.min(
                        SECONDS.toNanos(1),
                        Math.max(1, remaining / 4));
                    long cancellationBudget = Math.max(1, remaining - terminationReserve);
                    try {
                        cleanupFailure = append(
                            cleanupFailure,
                            cancellationTask.get(cancellationBudget, NANOSECONDS));
                        cancellationComplete = true;
                    } catch (TimeoutException timeoutFailure) {
                        cleanupFailure = append(
                            cleanupFailure,
                            new AssertionError(
                                description + " resource cancellation task did not complete within the overall bound",
                                timeoutFailure));
                        break;
                    } catch (InterruptedException interrupted) {
                        restoreInterrupt = true;
                    } catch (ExecutionException executionFailure) {
                        cleanupFailure = append(
                            cleanupFailure,
                            new AssertionError(
                                description + " resource cancellation task failed",
                                executionFailure.getCause()));
                        cancellationComplete = true;
                    }
                }
                if (!cancellationComplete) {
                    try {
                        cancellationTask.cancel(true);
                    } catch (RuntimeException failure) {
                        cleanupFailure = append(
                            cleanupFailure,
                            new AssertionError(
                                description + " resource cancellation future cancellation failed",
                                failure));
                    }
                }
            }
            try {
                cancellationExecutor.shutdownNow();
            } catch (RuntimeException failure) {
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError(description + " resource cancellation executor shutdown failed", failure));
            }
            CleanupOutcome cancellationShutdown = awaitTerminationBounded(
                cancellationExecutor,
                description + " resource cancellation executor",
                deadline,
                restoreInterrupt,
                cleanupFailure);
            restoreInterrupt = cancellationShutdown.restoreInterrupt();
            cleanupFailure = cancellationShutdown.failure();
        }
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
        CleanupOutcome executorShutdown = awaitTerminationBounded(
            executor,
            description + " executor",
            deadline,
            restoreInterrupt,
            cleanupFailure);
        restoreInterrupt = executorShutdown.restoreInterrupt();
        cleanupFailure = executorShutdown.failure();
        try {
            cleanupFailure = append(cleanupFailure, releaseVerification.get());
        } catch (RuntimeException | Error failure) {
            cleanupFailure = append(
                cleanupFailure,
                new AssertionError(description + " resource-release verification failed", failure));
        }
        restoreInterrupt |= Thread.interrupted();
        return new CleanupOutcome(restoreInterrupt, cleanupFailure);
    }

    private static CleanupOutcome awaitTerminationBounded(
        ExecutorService executor,
        String description,
        Deadline deadline,
        boolean restoreInterrupt,
        AssertionError cleanupFailure
    ) {
        while (!executor.isTerminated()) {
            long remaining = deadline.remainingNanos();
            if (remaining == 0) {
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError(description + " did not terminate within the overall bound"));
                break;
            }
            try {
                if (!executor.awaitTermination(remaining, NANOSECONDS)) {
                    cleanupFailure = append(
                        cleanupFailure,
                        new AssertionError(description + " did not terminate within the overall bound"));
                    break;
                }
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
            }
        }
        return new CleanupOutcome(restoreInterrupt, cleanupFailure);
    }

    private static <T> T runJdbcBounded(
        Duration timeout,
        String description,
        DataSource dataSource,
        JdbcWork<T> operation
    ) throws SQLException {
        var resources = new JdbcResources(dataSource, description);
        try {
            return runBounded(
                timeout,
                description,
                () -> resources.execute(operation),
                resources::cancelActive,
                resources::releaseFailure);
        } catch (SQLException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AssertionError(description + " failed with an unexpected checked exception", failure);
        }
    }

    @FunctionalInterface
    private interface JdbcWork<T> {
        T execute(JdbcResources resources) throws Exception;
    }

    @FunctionalInterface
    private interface ProcessStarter {
        Process start() throws IOException;
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws Exception;
    }

    private record DatabaseCredentials(String jdbcUrl, String username, String password) {}

    private record SessionTimeouts(String statementTimeout, String lockTimeout) {}

    private record CleanupOutcome(boolean restoreInterrupt, AssertionError failure) {}

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

    private static final class JdbcResources {
        private final DataSource dataSource;
        private final String description;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Connection> activeConnection = new AtomicReference<>();
        private final AtomicReference<Statement> activeStatement = new AtomicReference<>();
        private final AtomicReference<AssertionError> lifecycleFailure = new AtomicReference<>();

        private JdbcResources(DataSource dataSource, String description) {
            this.dataSource = dataSource;
            this.description = description;
        }

        private <T> T execute(JdbcWork<T> operation) throws Exception {
            Throwable primaryFailure = null;
            try {
                return operation.execute(this);
            } catch (Exception | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                closeRemaining(primaryFailure);
            }
        }

        private DataSource trackingDataSource() {
            return new TrackingDataSource(dataSource, this);
        }

        private <T> T withConnection(SqlFunction<Connection, T> operation) throws Exception {
            Connection connection = acquireConnection();
            try {
                return operation.apply(connection);
            } finally {
                closeConnection(connection);
            }
        }

        private Connection acquireConnection() throws SQLException {
            Connection delegate = dataSource.getConnection();
            if (cancelled.get()) {
                abortAndClose(delegate);
                throw new SQLTimeoutException(description + " acquired a connection after cancellation");
            }
            Connection connection = trackedConnection(delegate);
            if (!activeConnection.compareAndSet(null, connection)) {
                abortAndClose(delegate);
                throw new IllegalStateException(description + " opened concurrent JDBC connections");
            }
            if (cancelled.get()) {
                cancelConnection(connection);
                throw new SQLTimeoutException(description + " acquisition lost the cancellation race");
            }
            return connection;
        }

        private TrackedStatement prepare(Connection connection, String sql) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(1);
            return new TrackedStatement(this, statement);
        }

        private Connection trackedConnection(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return identityObjectMethod(proxy, method, arguments, "Connection");
                    }
                    if ("unwrap".equals(method.getName())
                        && arguments != null
                        && arguments[0] instanceof Class<?> type
                        && type.isInstance(proxy)) {
                        return type.cast(proxy);
                    }
                    if ("isWrapperFor".equals(method.getName())
                        && arguments != null
                        && arguments[0] instanceof Class<?> type
                        && type.isInstance(proxy)) {
                        return true;
                    }
                    if ("close".equals(method.getName()) || "abort".equals(method.getName())) {
                        try {
                            return invoke(delegate, method, arguments);
                        } finally {
                            activeConnection.compareAndSet((Connection) proxy, null);
                        }
                    }
                    Object result = invoke(delegate, method, arguments);
                    if (("prepareStatement".equals(method.getName()) || "createStatement".equals(method.getName()))
                        && result instanceof Statement statement) {
                        return trackedStatement(statement);
                    }
                    return result;
                });
        }

        private Statement trackedStatement(Statement delegate) throws SQLException {
            if (cancelled.get()) {
                delegate.close();
                throw new SQLTimeoutException(description + " prepared a statement after cancellation");
            }
            Class<?> statementType = delegate instanceof PreparedStatement
                ? PreparedStatement.class
                : Statement.class;
            Statement statement = (Statement) Proxy.newProxyInstance(
                statementType.getClassLoader(),
                new Class<?>[]{statementType},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return identityObjectMethod(proxy, method, arguments, statementType.getSimpleName());
                    }
                    if ("unwrap".equals(method.getName())
                        && arguments != null
                        && arguments[0] instanceof Class<?> type
                        && type.isInstance(proxy)) {
                        return type.cast(proxy);
                    }
                    if ("isWrapperFor".equals(method.getName())
                        && arguments != null
                        && arguments[0] instanceof Class<?> type
                        && type.isInstance(proxy)) {
                        return true;
                    }
                    if ("close".equals(method.getName())) {
                        try {
                            return invoke(delegate, method, arguments);
                        } finally {
                            activeStatement.compareAndSet((Statement) proxy, null);
                        }
                    }
                    return invoke(delegate, method, arguments);
                });
            if (!activeStatement.compareAndSet(null, statement)) {
                delegate.close();
                throw new IllegalStateException(description + " opened concurrent JDBC statements");
            }
            if (cancelled.get()) {
                cancelStatement(statement);
                closeStatement(statement);
                throw new SQLTimeoutException(description + " statement preparation lost the cancellation race");
            }
            return statement;
        }

        private AssertionError cancelActive() {
            cancelled.set(true);
            Statement statement = activeStatement.get();
            if (statement != null) {
                cancelStatement(statement);
            }
            Connection connection = activeConnection.get();
            if (connection != null) {
                cancelConnection(connection);
            }
            return null;
        }

        private void cancelStatement(Statement statement) {
            try {
                statement.cancel();
            } catch (SQLException | RuntimeException failure) {
                recordFailure(new AssertionError(description + " active statement cancellation failed", failure));
            }
        }

        private void cancelConnection(Connection connection) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException failure) {
                recordFailure(new AssertionError(description + " active connection abort failed", failure));
            } finally {
                closeConnection(connection);
            }
        }

        private void abortAndClose(Connection connection) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException failure) {
                recordFailure(new AssertionError(description + " late connection abort failed", failure));
            } finally {
                try {
                    connection.close();
                } catch (SQLException | RuntimeException failure) {
                    recordFailure(new AssertionError(description + " late connection close failed", failure));
                }
            }
        }

        private void closeStatement(Statement statement) {
            try {
                statement.close();
            } catch (SQLException | RuntimeException failure) {
                recordFailure(new AssertionError(description + " statement close failed", failure));
            } finally {
                if (isClosed(statement)) {
                    activeStatement.compareAndSet(statement, null);
                }
            }
        }

        private void closeConnection(Connection connection) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException failure) {
                recordFailure(new AssertionError(description + " connection close failed", failure));
            } finally {
                if (isClosed(connection)) {
                    activeConnection.compareAndSet(connection, null);
                }
            }
        }

        private void closeRemaining(Throwable primaryFailure) {
            Statement statement = activeStatement.get();
            if (statement != null) {
                closeStatement(statement);
            }
            Connection connection = activeConnection.get();
            if (connection != null) {
                closeConnection(connection);
            }
            if (!cancelled.get()) {
                AssertionError closeFailure = lifecycleFailure.getAndSet(null);
                if (closeFailure != null) {
                    if (primaryFailure == null) {
                        throw closeFailure;
                    }
                    primaryFailure.addSuppressed(closeFailure);
                }
            }
        }

        private AssertionError releaseFailure() {
            AssertionError failure = lifecycleFailure.getAndSet(null);
            Statement statement = activeStatement.get();
            if (statement != null) {
                if (isClosed(statement)) {
                    activeStatement.compareAndSet(statement, null);
                } else {
                    failure = append(
                        failure,
                        new AssertionError(description + " completed with a live JDBC statement"));
                }
            }
            Connection connection = activeConnection.get();
            if (connection != null) {
                if (isClosed(connection)) {
                    activeConnection.compareAndSet(connection, null);
                } else {
                    failure = append(
                        failure,
                        new AssertionError(description + " completed with a live JDBC connection"));
                }
            }
            return failure;
        }

        private void recordFailure(AssertionError failure) {
            AssertionError existing = lifecycleFailure.get();
            if (existing == null && lifecycleFailure.compareAndSet(null, failure)) {
                return;
            }
            lifecycleFailure.get().addSuppressed(failure);
        }

        private static boolean isClosed(Statement statement) {
            try {
                return statement.isClosed();
            } catch (SQLException | RuntimeException failure) {
                return false;
            }
        }

        private static boolean isClosed(Connection connection) {
            try {
                return connection.isClosed();
            } catch (SQLException | RuntimeException failure) {
                return false;
            }
        }
    }

    private record TrackedStatement(JdbcResources owner, PreparedStatement statement) implements AutoCloseable {
        @Override
        public void close() {
            owner.closeStatement(statement);
        }
    }

    private static final class TrackingDataSource implements DataSource {
        private final DataSource delegate;
        private final JdbcResources resources;

        private TrackingDataSource(DataSource delegate, JdbcResources resources) {
            this.delegate = delegate;
            this.resources = resources;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return resources.acquireConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("scoped credentials are fixed");
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

    private static final class CrashProbe {
        private final PGSimpleDataSource dataSource;

        private CrashProbe(PGSimpleDataSource dataSource) {
            this.dataSource = dataSource;
        }

        private UUID awaitCommittedJournal(UUID commandId) throws SQLException {
            return runJdbcBounded(PROCESS_TIMEOUT, "committed-journal visibility", dataSource, resources -> {
                Deadline deadline = Deadline.after(Duration.ofSeconds(8), System::nanoTime);
                do {
                    Optional<UUID> journalId = journalId(resources, commandId);
                    if (journalId.isPresent()) {
                        return journalId.get();
                    }
                    Thread.onSpinWait();
                } while (deadline.hasRemaining());
                throw new AssertionError("committed journal was not visible within the overall bound");
            });
        }

        private Optional<UUID> journalId(JdbcResources resources, UUID commandId) throws Exception {
            return resources.withConnection(connection -> {
                try (var tracked = resources.prepare(connection, """
                     SELECT journal_id FROM funds.journal WHERE command_id = ?
                     """)) {
                    var statement = tracked.statement();
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    return result.next()
                        ? Optional.of(result.getObject("journal_id", UUID.class))
                        : Optional.empty();
                }
                }
            });
        }

        private CrashSnapshot snapshot(UUID commandId) throws SQLException {
            return runJdbcBounded(
                PROCESS_TIMEOUT,
                "scoped ledger snapshot",
                dataSource,
                resources -> snapshotWithinBound(resources, commandId));
        }

        private CrashSnapshot snapshotWithinBound(JdbcResources resources, UUID commandId) throws Exception {
            return resources.withConnection(connection -> new CrashSnapshot(
                idempotency(resources, connection, commandId),
                journals(resources, connection, commandId),
                postings(resources, connection, commandId),
                balances(resources, connection),
                controls(resources, connection),
                outbox(resources, connection, commandId)));
        }

        private void awaitRollbackComplete() throws SQLException {
            runJdbcBounded(PROCESS_TIMEOUT, "pre-commit rollback synchronization", dataSource, resources -> {
                Deadline deadline = Deadline.after(Duration.ofSeconds(8), System::nanoTime);
                SQLException lastLockFailure = null;
                do {
                    try {
                        probeRollbackReleaseOnceWithinBound(resources);
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
            runJdbcBounded(PROCESS_TIMEOUT, "single rollback-release probe", dataSource, resources -> {
                probeRollbackReleaseOnceWithinBound(resources);
                return null;
            });
        }

        private void probeRollbackReleaseOnceWithinBound(JdbcResources resources) throws Exception {
            resources.withConnection(connection -> {
                connection.setAutoCommit(false);
                Throwable primaryFailure = null;
                try (var tracked = resources.prepare(connection, """
                    SELECT account_id
                    FROM funds.ledger_account
                    WHERE account_id IN (?, ?)
                    ORDER BY account_id
                    FOR UPDATE
                    """)) {
                    var statement = tracked.statement();
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
                return null;
            });
        }

        private static List<IdempotencyState> idempotency(
            JdbcResources resources,
            Connection connection,
            UUID commandId
        ) throws SQLException {
            var states = new ArrayList<IdempotencyState>();
            try (var tracked = resources.prepare(connection, """
                SELECT command_id, request_hash, state, journal_id, result_json::text,
                       created_at, completed_at
                FROM funds.idempotency_command
                WHERE command_id = ?
                ORDER BY command_id
                """)) {
                var statement = tracked.statement();
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

        private static List<JournalState> journals(
            JdbcResources resources,
            Connection connection,
            UUID commandId
        ) throws SQLException {
            var states = new ArrayList<JournalState>();
            try (var tracked = resources.prepare(connection, """
                SELECT journal_id, journal_sequence, command_id, correlation_id,
                       business_transaction_id, legal_entity_id, book_id, period_id,
                       transaction_type, narration, booking_time, value_date,
                       reversal_of_journal_id, policy_version, canonical_hash
                FROM funds.journal
                WHERE command_id = ?
                ORDER BY journal_sequence
                """)) {
                var statement = tracked.statement();
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

        private static List<PostingState> postings(
            JdbcResources resources,
            Connection connection,
            UUID commandId
        ) throws SQLException {
            var states = new ArrayList<PostingState>();
            try (var tracked = resources.prepare(connection, """
                SELECT posting.posting_id, posting.journal_id, posting.account_id,
                       posting.currency, posting.signed_minor_units,
                       posting.account_sequence, posting.dimensions::text
                FROM funds.posting posting
                JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                WHERE journal.command_id = ?
                  AND posting.account_id IN (?, ?)
                ORDER BY posting.posting_id
                """)) {
                var statement = tracked.statement();
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

        private static Map<UUID, BalanceState> balances(JdbcResources resources, Connection connection)
            throws SQLException {
            var states = new LinkedHashMap<UUID, BalanceState>();
            try (var tracked = resources.prepare(connection, """
                SELECT account_id, signed_posting_total, latest_account_sequence, version
                FROM funds.materialised_balance
                WHERE account_id IN (?, ?)
                ORDER BY account_id
                """)) {
                var statement = tracked.statement();
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

        private static Map<String, ControlState> controls(JdbcResources resources, Connection connection)
            throws SQLException {
            var states = new LinkedHashMap<String, ControlState>();
            try (var tracked = resources.prepare(connection, """
                SELECT control_account_code, signed_posting_total, latest_journal_sequence
                FROM funds.control_account_projection
                WHERE book_id = ? AND currency = 'NGN'
                  AND control_account_code IN (?, ?, ?)
                ORDER BY control_account_code
                """)) {
                var statement = tracked.statement();
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

        private static List<OutboxState> outbox(
            JdbcResources resources,
            Connection connection,
            UUID commandId
        ) throws SQLException {
            var states = new ArrayList<OutboxState>();
            try (var tracked = resources.prepare(connection, """
                SELECT event.event_id, event.aggregate_id, event.aggregate_version,
                       event.event_type, event.schema_version, event.payload::text,
                       event.created_at, event.published_at, event.publish_attempts
                FROM funds.outbox_event event
                JOIN funds.journal journal ON journal.journal_id = event.aggregate_id
                WHERE journal.command_id = ?
                ORDER BY event.event_id
                """)) {
                var statement = tracked.statement();
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

    private interface TimedWaits {
        boolean waitFor(Process process, long remainingNanos) throws InterruptedException;

        <T> T call(Callable<T> operation, Deadline deadline, String description) throws Exception;

        default <T> DeferredCall<T> defer(
            Callable<T> operation,
            Deadline startDeadline,
            Deadline cleanupDeadline,
            String description
        ) throws Exception {
            throw new UnsupportedOperationException("deferred bounded call is not implemented");
        }
    }

    @FunctionalInterface
    private interface DeferredCall<T> {
        T releaseAndJoin(Deadline deadline) throws Exception;
    }

    private enum SystemTimedWaits implements TimedWaits {
        INSTANCE;

        @Override
        public boolean waitFor(Process process, long remainingNanos) throws InterruptedException {
            return process.waitFor(remainingNanos, NANOSECONDS);
        }

        @Override
        public <T> T call(Callable<T> operation, Deadline deadline, String description) throws Exception {
            return runBounded(deadline, description, operation);
        }

        @Override
        public <T> DeferredCall<T> defer(
            Callable<T> operation,
            Deadline startDeadline,
            Deadline cleanupDeadline,
            String description
        ) {
            return SystemDeferredCall.start(operation, startDeadline, cleanupDeadline, description);
        }
    }

    private interface WorkerFiles {
        String read(Path path) throws IOException;

        void delete(Path path) throws IOException;
    }

    private enum SystemWorkerFiles implements WorkerFiles {
        INSTANCE;

        @Override
        public String read(Path path) throws IOException {
            return Files.readString(path, UTF_8);
        }

        @Override
        public void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
            if (Files.exists(path)) {
                throw new IOException("worker output file still exists after deletion: " + path.getFileName());
            }
        }
    }

    private static final class WorkerHandle implements AutoCloseable {
        private final Process process;
        private final CrashPostingWorker.CrashPoint point;
        private final Path outputFile;
        private final TimedWaits timedWaits;
        private final WorkerFiles files;
        private final Duration timeout;
        private final LongSupplier nanoTime;
        private final long deletionReserve;

        private WorkerHandle(Process process, CrashPostingWorker.CrashPoint point, Path outputFile) {
            this(
                process,
                point,
                outputFile,
                PROCESS_TIMEOUT,
                System::nanoTime,
                SystemTimedWaits.INSTANCE,
                SystemWorkerFiles.INSTANCE);
        }

        private WorkerHandle(
            Process process,
            CrashPostingWorker.CrashPoint point,
            Path outputFile,
            Duration timeout,
            LongSupplier nanoTime,
            TimedWaits timedWaits,
            WorkerFiles files
        ) {
            this.process = process;
            this.point = point;
            this.outputFile = outputFile;
            this.timedWaits = timedWaits;
            this.files = files;
            this.timeout = timeout;
            this.nanoTime = nanoTime;
            this.deletionReserve = Math.min(
                SECONDS.toNanos(1),
                Math.max(1, timeout.toNanos() / 10));
        }

        private WorkerExit awaitExit() {
            Deadline exitDeadline = Deadline.after(timeout, nanoTime);
            try {
                if (!timedWaits.waitFor(process, Math.max(1, exitDeadline.remainingNanos()))) {
                    throw new AssertionError("crash worker did not exit within 10 seconds at " + point);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("crash worker wait was interrupted at " + point, interrupted);
            }

            try {
                Deadline outputDeadline = Deadline.after(timeout, nanoTime);
                return new WorkerExit(
                    process.exitValue(),
                    timedWaits.call(
                        () -> files.read(outputFile),
                        outputDeadline,
                        "crash-worker output-file read at " + point));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("crash worker output-file read was interrupted at " + point, interrupted);
            } catch (Exception failure) {
                throw new AssertionError("crash worker output-file read failed at " + point, failure);
            }
        }

        @Override
        public void close() {
            Deadline cleanupDeadline = Deadline.after(timeout, nanoTime);
            Deadline nonDeletionDeadline = cleanupDeadline.reservingTail(deletionReserve);
            AssertionError cleanupFailure = null;
            boolean restoreInterrupt = Thread.interrupted();
            DeferredCall<Void> deletion = null;
            try {
                deletion = timedWaits.defer(
                    () -> {
                        files.delete(outputFile);
                        return null;
                    },
                    nonDeletionDeadline,
                    cleanupDeadline,
                    "crash-worker output-file deletion at " + point);
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError("crash-worker output-file deletion did not start at " + point, interrupted));
            } catch (Exception | Error failure) {
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError("crash-worker output-file deletion did not start at " + point, failure));
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                boolean waitComplete = false;
                while (!waitComplete) {
                    long remaining = nonDeletionDeadline.remainingNanos();
                    if (remaining == 0) {
                        cleanupFailure = append(
                            cleanupFailure,
                            new AssertionError("crash worker survived forced destruction at " + point));
                        break;
                    }
                    try {
                        if (!timedWaits.waitFor(process, remaining)) {
                            cleanupFailure = append(
                                cleanupFailure,
                                new AssertionError("crash worker survived forced destruction at " + point));
                        }
                        waitComplete = true;
                    } catch (InterruptedException interrupted) {
                        restoreInterrupt = true;
                    }
                }
            }

            boolean deleteComplete = false;
            if (deletion != null) {
                try {
                    deletion.releaseAndJoin(cleanupDeadline);
                    deleteComplete = true;
                } catch (InterruptedException interrupted) {
                    restoreInterrupt = true;
                    cleanupFailure = append(
                        cleanupFailure,
                        new AssertionError("crash-worker output-file deletion was interrupted at " + point, interrupted));
                } catch (Exception | Error failure) {
                    cleanupFailure = append(
                        cleanupFailure,
                        new AssertionError("crash-worker output-file deletion failed at " + point, failure));
                }
            }
            if (!deleteComplete) {
                cleanupFailure = append(
                    cleanupFailure,
                    new AssertionError("crash-worker output file survived cleanup at " + point));
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private static AssertionError append(AssertionError existing, AssertionError next) {
            return PostingCrashRecoveryIT.append(existing, next);
        }
    }

    private static final class SystemDeferredCall<T> implements DeferredCall<T> {
        private final ExecutorService executor;
        private final Future<T> future;
        private final CountDownLatch release;
        private final CountDownLatch operationStarted;
        private final String description;

        private SystemDeferredCall(
            ExecutorService executor,
            Future<T> future,
            CountDownLatch release,
            CountDownLatch operationStarted,
            String description
        ) {
            this.executor = executor;
            this.future = future;
            this.release = release;
            this.operationStarted = operationStarted;
            this.description = description;
        }

        private static <T> SystemDeferredCall<T> start(
            Callable<T> operation,
            Deadline startDeadline,
            Deadline cleanupDeadline,
            String description
        ) {
            ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("deferred-" + description.replace(' ', '-')).factory());
            var ready = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var operationStarted = new CountDownLatch(1);
            Future<T> future = executor.submit(() -> {
                ready.countDown();
                release.await();
                operationStarted.countDown();
                return operation.call();
            });
            boolean restoreInterrupt = false;
            boolean started = false;
            while (!started) {
                long remaining = startDeadline.remainingNanos();
                if (remaining == 0) {
                    break;
                }
                try {
                    started = ready.await(remaining, NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    restoreInterrupt = true;
                }
            }
            if (!started) {
                future.cancel(true);
                executor.shutdownNow();
                CleanupOutcome cleanup = awaitTerminationBounded(
                    executor,
                    description + " deferred executor",
                    cleanupDeadline,
                    restoreInterrupt,
                    new AssertionError(description + " did not start within its reserved allocation"));
                if (cleanup.restoreInterrupt()) {
                    Thread.currentThread().interrupt();
                }
                throw cleanup.failure();
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return new SystemDeferredCall<>(executor, future, release, operationStarted, description);
        }

        @Override
        public T releaseAndJoin(Deadline deadline) throws Exception {
            release.countDown();
            Throwable primaryFailure = null;
            boolean restoreInterrupt = Thread.interrupted();
            long joinReserve = Math.min(
                SECONDS.toNanos(1),
                Math.max(1, deadline.remainingNanos() / 2));
            Deadline operationDeadline = deadline.reservingTail(joinReserve);
            try {
                boolean entered = false;
                while (!entered) {
                    long remaining = operationDeadline.remainingNanos();
                    if (remaining == 0) {
                        throw new AssertionError(description + " did not enter within its reserved cleanup allocation");
                    }
                    try {
                        entered = operationStarted.await(remaining, NANOSECONDS);
                    } catch (InterruptedException interrupted) {
                        restoreInterrupt = true;
                    }
                }
                while (true) {
                    long remaining = operationDeadline.remainingNanos();
                    if (remaining == 0) {
                        throw new AssertionError(description + " exceeded its reserved cleanup allocation");
                    }
                    try {
                        return future.get(remaining, NANOSECONDS);
                    } catch (InterruptedException interrupted) {
                        restoreInterrupt = true;
                    } catch (TimeoutException timeoutFailure) {
                        throw new AssertionError(
                            description + " exceeded its reserved cleanup allocation",
                            timeoutFailure);
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
                }
            } catch (Exception | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                CleanupOutcome cleanup = shutdownBounded(
                    executor,
                    future,
                    description + " deferred task",
                    deadline,
                    () -> null,
                    () -> null);
                restoreInterrupt |= cleanup.restoreInterrupt();
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
                if (cleanup.failure() != null) {
                    if (primaryFailure == null) {
                        throw cleanup.failure();
                    }
                    if (cleanup.failure() != primaryFailure) {
                        primaryFailure.addSuppressed(cleanup.failure());
                    }
                }
            }
        }

    }

    private static AssertionError append(AssertionError existing, AssertionError next) {
        if (next == null) {
            return existing;
        }
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    private static final class Deadline {
        private final long expiresAt;
        private final LongSupplier nanoTime;

        private Deadline(long expiresAt, LongSupplier nanoTime) {
            this.expiresAt = expiresAt;
            this.nanoTime = nanoTime;
        }

        private static Deadline after(Duration timeout, LongSupplier nanoTime) {
            long now = nanoTime.getAsLong();
            long duration = Math.max(0, timeout.toNanos());
            long expiresAt = Long.MAX_VALUE - now < duration ? Long.MAX_VALUE : now + duration;
            return new Deadline(expiresAt, nanoTime);
        }

        private long remainingNanos() {
            return Math.max(0, expiresAt - nanoTime.getAsLong());
        }

        private boolean hasRemaining() {
            return remainingNanos() > 0;
        }

        private Deadline reservingTail(long reservedNanos) {
            long now = nanoTime.getAsLong();
            long usable = Math.max(0, remainingNanos() - Math.max(0, reservedNanos));
            long usableExpiresAt = Long.MAX_VALUE - now < usable ? Long.MAX_VALUE : now + usable;
            return new Deadline(usableExpiresAt, nanoTime);
        }
    }

    private static final class DeterministicExitProcess extends Process {
        private final AtomicBoolean exited = new AtomicBoolean();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
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
            return false;
        }

        @Override
        public int exitValue() {
            if (!exited.get()) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return 91;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            exited.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exited.get();
        }
    }

    private static final class MutableNanoClock {
        private long now;

        private long nanoTime() {
            return now;
        }

        private void advance(long nanoseconds) {
            now += nanoseconds;
        }
    }

    private static final class RecordingTimedWaits implements TimedWaits {
        private final MutableNanoClock clock;
        private final List<Long> observedBudgets = new ArrayList<>();

        private RecordingTimedWaits(MutableNanoClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean waitFor(Process process, long remainingNanos) {
            observedBudgets.add(remainingNanos);
            clock.advance(30);
            ((DeterministicExitProcess) process).exited.set(true);
            return true;
        }

        @Override
        public <T> T call(Callable<T> operation, Deadline deadline, String description) throws Exception {
            observedBudgets.add(deadline.remainingNanos());
            if (description.contains("read")) {
                clock.advance(20);
            }
            return operation.call();
        }

        @Override
        public <T> DeferredCall<T> defer(
            Callable<T> operation,
            Deadline startDeadline,
            Deadline cleanupDeadline,
            String description
        ) {
            return completionDeadline -> {
                observedBudgets.add(completionDeadline.remainingNanos());
                return operation.call();
            };
        }
    }

    private static final class RecordingWorkerFiles implements WorkerFiles {
        private final Process process;
        private final AtomicBoolean readAfterExit = new AtomicBoolean();
        private final AtomicBoolean deleted = new AtomicBoolean();
        private final AtomicReference<Path> readPath = new AtomicReference<>();
        private final AtomicReference<Path> deletedPath = new AtomicReference<>();

        private RecordingWorkerFiles(Process process) {
            this.process = process;
        }

        @Override
        public String read(Path path) {
            readPath.set(path);
            readAfterExit.set(!process.isAlive());
            return "REACHED:BEFORE_COMMIT\n";
        }

        @Override
        public void delete(Path path) {
            deletedPath.set(path);
            deleted.set(true);
        }
    }

    private static final class InterruptReturningDataSource extends PGSimpleDataSource {
        private final CountDownLatch acquisitionStarted = new CountDownLatch(1);
        private final CountDownLatch acquisitionFinished = new CountDownLatch(1);
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public Connection getConnection() {
            acquisitionStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException timeoutCancellation) {
                // Simulate a driver returning a connection after caller-side cancellation won the race.
            }
            acquisitionFinished.countDown();
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "abort" -> {
                        aborted.set(true);
                        closed.set(true);
                        yield null;
                    }
                    case "close" -> {
                        closed.set(true);
                        yield null;
                    }
                    case "isClosed" -> closed.get();
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    case "toString" -> "interrupt-returning-connection";
                    default -> defaultValue(method.getReturnType());
                });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            return 0;
        }
    }

    private static final class InterruptingCleanupExecutor extends AbstractExecutorService {
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            throw new IllegalStateException("shutdown diagnostic");
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return terminated.get();
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
            terminated.set(true);
            Thread.currentThread().interrupt();
            Thread.interrupted();
            throw new InterruptedException("deterministic cleanup interruption");
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class SerializationFailureOnceDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicBoolean serializationFailureInjected = new AtomicBoolean();
        private final AtomicInteger acquiredConnections = new AtomicInteger();
        private final List<Connection> physicalConnections = new ArrayList<>();

        private SerializationFailureOnceDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection physical = delegate.getConnection();
            physicalConnections.add(physical);
            acquiredConnections.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(physical, method, arguments);
                    if ("prepareStatement".equals(method.getName())
                        && result instanceof PreparedStatement statement) {
                        return serializationFailureOnce(statement);
                    }
                    return result;
                });
        }

        private PreparedStatement serializationFailureOnce(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().startsWith("execute")
                        && serializationFailureInjected.compareAndSet(false, true)) {
                        throw new SQLException("forced nested serialization failure", "40001");
                    }
                    return invoke(statement, method, arguments);
                });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("test datasource credentials are fixed");
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
            return delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return delegate.isWrapperFor(type);
        }
    }

    private static final class BlockingStatementObservationDataSource implements DataSource {
        private final DataSource delegate;
        private final CountDownLatch blockingStatementStarted = new CountDownLatch(1);
        private final AtomicBoolean statementCancelled = new AtomicBoolean();
        private final AtomicBoolean connectionAborted = new AtomicBoolean();
        private final AtomicBoolean connectionClosed = new AtomicBoolean();

        private BlockingStatementObservationDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection physical = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if ("abort".equals(method.getName())) {
                        connectionAborted.set(true);
                    } else if ("close".equals(method.getName())) {
                        connectionClosed.set(true);
                    }
                    Object result = invoke(physical, method, arguments);
                    if ("prepareStatement".equals(method.getName())
                        && arguments != null
                        && arguments.length > 0
                        && arguments[0] instanceof String sql
                        && result instanceof PreparedStatement statement) {
                        return observedStatement(statement, sql);
                    }
                    return result;
                });
        }

        private PreparedStatement observedStatement(PreparedStatement statement, String sql) {
            boolean blocksOnFixture = sql.contains("FROM funds.lock_account_mapping_for_posting(");
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if (blocksOnFixture && method.getName().startsWith("execute")) {
                        blockingStatementStarted.countDown();
                    } else if (blocksOnFixture && "cancel".equals(method.getName())) {
                        statementCancelled.set(true);
                    }
                    return invoke(statement, method, arguments);
                });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("test datasource credentials are fixed");
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
            return delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return delegate.isWrapperFor(type);
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object identityObjectMethod(
        Object proxy,
        Method method,
        Object[] arguments,
        String interfaceName
    ) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "tracked " + interfaceName + '@'
                + Integer.toHexString(System.identityHashCode(proxy));
            default -> throw new AssertionError("unexpected Object method: " + method);
        };
    }
}
