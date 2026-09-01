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

public final class CrashPostingWorker {
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

    enum CrashPoint {
        BEFORE_COMMIT,
        AFTER_COMMIT_BEFORE_RETURN
    }

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
                System.out.println("REACHED:" + reached);
                System.out.flush();
                Runtime.getRuntime().halt(91);
            }
        }
    }
}
