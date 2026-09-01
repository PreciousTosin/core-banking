package com.corebanking.funds.application;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Child-JVM entry point that PostingCrashRecoveryIT launches to kill a posting at a precise point
 * (ACC-32 owner-termination recovery). Protocol: the parent runs
 * {@code java -cp <surefire.test.class.path> CrashPostingWorker <commandId> <CrashPoint>} with
 * CB_TEST_JDBC_URL, CB_TEST_DB_USER and CB_TEST_DB_PASSWORD in the environment; the worker posts
 * the fixed command through the real TestPostingStack, and when the observer reaches the requested
 * point it writes exactly one line {@code REACHED:<CrashPoint>} to stdout, flushes, and halts with
 * exit code 91. The parent asserts that exit code and that line as the only non-blank output
 * (stderr is merged), so a worker that posts to completion (exit 0) or dies on an exception fails
 * the assertion rather than passing silently.
 */
public final class CrashPostingWorker {
    // Fixed journal and posting identities let the parent assert the exact committed rows. Both
    // crash points reuse them because the IT resets and reseeds the database before each test.
    static final UUID JOURNAL_ID = TestPostingStack.uuid(21);
    static final UUID PROVIDER_POSTING_ID = TestPostingStack.uuid(22);
    static final UUID CUSTOMER_POSTING_ID = TestPostingStack.uuid(23);
    static final long POSTING_AMOUNT = 100_000;

    private CrashPostingWorker() {}

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected commandId and crash point");
        }
        UUID commandId = UUID.fromString(arguments[0]);
        CrashPoint point = CrashPoint.valueOf(arguments[1]);
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(requiredEnvironment("CB_TEST_JDBC_URL"));
        dataSource.setUser(requiredEnvironment("CB_TEST_DB_USER"));
        dataSource.setPassword(requiredEnvironment("CB_TEST_DB_PASSWORD"));

        var observer = new HaltingObserver(point);
        TestPostingStack.create(dataSource, observer).postingService().post(command(commandId));
    }

    /**
     * The one command both processes agree on: the parent rebuilds it from the same commandId to
     * retry or replay after the crash, so its content and TYPED_V2 hash must be a pure function of
     * the commandId.
     */
    static PostingCommand command(UUID commandId) {
        var journal = new JournalDraft(
            JOURNAL_ID,
            commandId,
            TestPostingStack.uuid(30),
            TestPostingStack.uuid(31),
            TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID,
            TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.PERIOD_ID,
            "PROVIDER_INFLOW",
            "Crash-recovery provider inflow",
            Instant.parse("2026-01-15T10:00:00Z"),
            LocalDate.of(2026, 1, 15),
            null,
            1,
            List.of(
                new PostingLine(
                    PROVIDER_POSTING_ID,
                    TestPostingStack.PROVIDER_ASSET,
                    new CurrencyCode("NGN"),
                    POSTING_AMOUNT,
                    0,
                    Map.of("rail", "provider")),
                new PostingLine(
                    CUSTOMER_POSTING_ID,
                    TestPostingStack.CUSTOMER_LIABILITY,
                    new CurrencyCode("NGN"),
                    -POSTING_AMOUNT,
                    0,
                    Map.of("customer", "crash-recovery"))));
        return new PostingCommand(commandId, new CanonicalCommandHasher().postingV2(journal), journal);
    }

    private static String requiredEnvironment(String name) {
        return Objects.requireNonNull(System.getenv(name), name + " is required");
    }

    /**
     * Where the worker dies, named after the PostingTransactionObserver hook it halts in. The
     * first leaves an uncommitted transaction the server must roll back; the second leaves a
     * committed journal whose result the owner never returned.
     */
    enum CrashPoint {
        BEFORE_COMMIT,
        AFTER_COMMIT_BEFORE_RETURN
    }

    /**
     * Halts the JVM at the configured hook. Runtime.halt, not System.exit, so no shutdown hook or
     * driver cleanup runs: the connection is simply gone, which is the failure being simulated.
     */
    private static final class HaltingObserver implements PostingTransactionObserver {
        private final CrashPoint point;

        private HaltingObserver(CrashPoint point) {
            this.point = point;
        }

        @Override
        public void beforeCommit(UUID commandId) {
            haltAt(CrashPoint.BEFORE_COMMIT);
        }

        @Override
        public void afterCommitBeforeReturn(UUID commandId) {
            haltAt(CrashPoint.AFTER_COMMIT_BEFORE_RETURN);
        }

        private void haltAt(CrashPoint reached) {
            if (point == reached) {
                // Flush before halting: halt does not drain stdout; the parent asserts this line.
                System.out.println("REACHED:" + reached);
                System.out.flush();
                Runtime.getRuntime().halt(91);
            }
        }
    }
}
