package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.infrastructure.postgres.LedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostingServiceTest {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Test
    void subMicrosecondBookingTimeFailsAfterIdempotencyPreflightButBeforeRetryOrPost() {
        var dependencies = new RecordingDependencies();
        PostingService service = dependencies.service();

        assertThrows(InvalidJournalException.class, () -> service.post(command(
            Instant.parse("2026-01-15T10:00:00.123456001Z"), 100, -100)));

        assertEquals(1, dependencies.retryPolicy.executions);
        assertEquals(1, dependencies.dataSource.connections);
        assertEquals(1, dependencies.repository.preflightCalls);
        assertEquals(0, dependencies.repository.calls);
    }

    @Test
    void otherInvalidJournalFailsAfterIdempotencyPreflightButBeforeRetryOrPost() {
        var dependencies = new RecordingDependencies();
        PostingService service = dependencies.service();

        assertThrows(InvalidJournalException.class, () -> service.post(command(
            Instant.parse("2026-01-15T10:00:00.123456Z"), 100, -99)));

        assertEquals(1, dependencies.retryPolicy.executions);
        assertEquals(1, dependencies.dataSource.connections);
        assertEquals(1, dependencies.repository.preflightCalls);
        assertEquals(0, dependencies.repository.calls);
    }

    @Test
    void exactMicrosecondBookingTimeProceedsThroughOneNormalAttempt() {
        var dependencies = new RecordingDependencies();
        PostingCommand command = command(
            Instant.parse("2026-01-15T10:00:00.123456Z"), 100, -100);

        PostingResult result = dependencies.service().post(command);

        assertEquals(command.journal().journalId(), result.journalId());
        assertEquals(1, dependencies.retryPolicy.executions);
        assertEquals(1, dependencies.dataSource.connections);
        assertEquals(1, dependencies.repository.preflightCalls);
        assertEquals(1, dependencies.repository.calls);
    }

    @Test
    void completedSameContentReplayResolvesBeforeLaterJournalValidation() {
        var dependencies = new RecordingDependencies();
        PostingCommand command = command(
            Instant.parse("2026-01-15T10:00:00.123456001Z"), 100, -100);
        var completed = new PostingResult(command.journal().journalId(), 91, "a".repeat(64));
        dependencies.repository.completed = Optional.of(completed);

        assertEquals(completed, dependencies.service().post(command));
        assertEquals(1, dependencies.dataSource.connections);
        assertEquals(1, dependencies.repository.preflightCalls);
        assertEquals(0, dependencies.repository.calls);
        assertEquals(1, dependencies.retryPolicy.executions);
    }

    @Test
    void staleCallerHashCannotAuthorizeChangedFinancialContent() {
        var dependencies = new RecordingDependencies();
        PostingCommand original = command(
            Instant.parse("2026-01-15T10:00:00.123456Z"), 100, -100);
        JournalDraft changed = new JournalDraft(
            original.journal().journalId(), original.commandId(), original.journal().correlationId(),
            original.journal().businessTransactionId(), original.journal().legalEntityId(),
            original.journal().bookId(), original.journal().chartVersionId(),
            original.journal().periodId(), original.journal().transactionType(), "changed narration",
            original.journal().bookingTime(), original.journal().valueDate(), null,
            original.journal().policyVersion(), original.journal().postings());

        assertThrows(IdempotencyConflictException.class,
            () -> dependencies.service().post(new PostingCommand(
                original.commandId(), original.requestHash(), changed)));
        assertEquals(0, dependencies.dataSource.connections);
        assertEquals(0, dependencies.repository.calls);
    }

    @Test
    void staleCallerHashConflictsForEveryFinancialCommandField() {
        var dependencies = new RecordingDependencies();
        PostingCommand original = command(
            Instant.parse("2026-01-15T10:00:00.123456Z"), 100, -100);
        List<NamedMutation> mutations = List.of(
            mutation("journalId", values -> values.journalId = uuid(101)),
            mutation("commandId", values -> values.commandId = uuid(102)),
            mutation("correlationId", values -> values.correlationId = uuid(103)),
            mutation("businessTransactionId", values -> values.businessTransactionId = uuid(104)),
            mutation("legalEntityId", values -> values.legalEntityId = uuid(105)),
            mutation("bookId", values -> values.bookId = uuid(106)),
            mutation("chartVersionId", values -> values.chartVersionId = uuid(107)),
            mutation("periodId", values -> values.periodId = uuid(108)),
            mutation("transactionType", values -> values.transactionType = "CHANGED_TYPE"),
            mutation("narration", values -> values.narration = "changed narration"),
            mutation("bookingTime", values -> values.bookingTime = values.bookingTime.plusSeconds(1)),
            mutation("valueDate", values -> values.valueDate = values.valueDate.plusDays(1)),
            mutation("reversalOfJournalId", values -> values.reversalOfJournalId = uuid(109)),
            mutation("policyVersion", values -> values.policyVersion++),
            mutation("postingId", values -> values.replaceFirstPosting(new PostingLine(
                uuid(110), values.postings.getFirst().accountId(), NGN, 100, 0, Map.of()))),
            mutation("postingAccount", values -> values.replaceFirstPosting(new PostingLine(
                values.postings.getFirst().postingId(), uuid(111), NGN, 100, 0, Map.of()))),
            mutation("postingCurrency", values -> values.replaceFirstPosting(new PostingLine(
                values.postings.getFirst().postingId(), values.postings.getFirst().accountId(),
                CurrencyCode.of("USD"), 100, 0, Map.of()))),
            mutation("postingAmount", values -> values.replaceFirstPosting(new PostingLine(
                values.postings.getFirst().postingId(), values.postings.getFirst().accountId(),
                NGN, 101, 0, Map.of()))),
            mutation("postingDimensions", values -> values.replaceFirstPosting(new PostingLine(
                values.postings.getFirst().postingId(), values.postings.getFirst().accountId(),
                NGN, 100, 0, Map.of("rail", "nibss")))));

        for (NamedMutation mutation : mutations) {
            DraftValues values = new DraftValues(original.journal());
            mutation.change().accept(values);
            JournalDraft changed = values.toDraft();
            assertThrows(
                IdempotencyConflictException.class,
                () -> dependencies.service().post(new PostingCommand(
                    changed.commandId(), original.requestHash(), changed)),
                mutation.name());
        }
        assertEquals(0, dependencies.dataSource.connections);
        assertEquals(0, dependencies.repository.preflightCalls);
        assertEquals(0, dependencies.repository.calls);
    }

    @Test
    void genericPostingPathRejectsCallerSuppliedReversalMetadata() {
        var dependencies = new RecordingDependencies();
        PostingCommand original = command(
            Instant.parse("2026-01-15T10:00:00.123456Z"), 100, -100);
        JournalDraft disguised = new JournalDraft(
            original.journal().journalId(), original.commandId(), original.journal().correlationId(),
            original.journal().businessTransactionId(), original.journal().legalEntityId(),
            original.journal().bookId(), original.journal().chartVersionId(),
            original.journal().periodId(), "NOT_A_REVERSAL", original.journal().narration(),
            original.journal().bookingTime(), original.journal().valueDate(), uuid(99),
            original.journal().policyVersion(), original.journal().postings());
        String hash = new CanonicalCommandHasher().postingV2(disguised);

        assertThrows(InvalidJournalException.class,
            () -> dependencies.service().post(new PostingCommand(original.commandId(), hash, disguised)));
        assertEquals(0, dependencies.repository.calls);
    }

    @Test
    void staleHashOnCallerSuppliedReversalMetadataConflictsBeforeMetadataValidation() {
        var dependencies = new RecordingDependencies();
        PostingCommand original = command(
            Instant.parse("2026-01-15T10:00:00.123456Z"), 100, -100);
        JournalDraft disguised = new JournalDraft(
            original.journal().journalId(), original.commandId(), original.journal().correlationId(),
            original.journal().businessTransactionId(), original.journal().legalEntityId(),
            original.journal().bookId(), original.journal().chartVersionId(),
            original.journal().periodId(), "REVERSAL", original.journal().narration(),
            original.journal().bookingTime(), original.journal().valueDate(), uuid(99),
            original.journal().policyVersion(), original.journal().postings());

        assertThrows(IdempotencyConflictException.class,
            () -> dependencies.service().post(new PostingCommand(
                original.commandId(), original.requestHash(), disguised)));
        assertEquals(0, dependencies.dataSource.connections);
        assertEquals(0, dependencies.repository.calls);
    }

    private static PostingCommand command(Instant bookingTime, long debit, long credit) {
        UUID commandId = uuid(1);
        var journal = new JournalDraft(
            uuid(2), commandId, uuid(3), uuid(4), uuid(5), uuid(6), uuid(12), uuid(7),
            "BOUNDARY_TEST", "Posting service validation boundary", bookingTime,
            LocalDate.of(2026, 1, 15), null, 1,
            List.of(
                new PostingLine(uuid(8), uuid(10), NGN, debit, 0, Map.of()),
                new PostingLine(uuid(9), uuid(11), NGN, credit, 0, Map.of())));
        return new PostingCommand(commandId, new CanonicalCommandHasher().postingV2(journal), journal);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static NamedMutation mutation(String name, Consumer<DraftValues> change) {
        return new NamedMutation(name, change);
    }

    private record NamedMutation(String name, Consumer<DraftValues> change) {}

    private static final class DraftValues {
        private UUID journalId;
        private UUID commandId;
        private UUID correlationId;
        private UUID businessTransactionId;
        private UUID legalEntityId;
        private UUID bookId;
        private UUID chartVersionId;
        private UUID periodId;
        private String transactionType;
        private String narration;
        private Instant bookingTime;
        private LocalDate valueDate;
        private UUID reversalOfJournalId;
        private int policyVersion;
        private final List<PostingLine> postings;

        private DraftValues(JournalDraft source) {
            journalId = source.journalId();
            commandId = source.commandId();
            correlationId = source.correlationId();
            businessTransactionId = source.businessTransactionId();
            legalEntityId = source.legalEntityId();
            bookId = source.bookId();
            chartVersionId = source.chartVersionId();
            periodId = source.periodId();
            transactionType = source.transactionType();
            narration = source.narration();
            bookingTime = source.bookingTime();
            valueDate = source.valueDate();
            reversalOfJournalId = source.reversalOfJournalId();
            policyVersion = source.policyVersion();
            postings = new ArrayList<>(source.postings());
        }

        private void replaceFirstPosting(PostingLine replacement) {
            postings.set(0, replacement);
        }

        private JournalDraft toDraft() {
            return new JournalDraft(
                journalId, commandId, correlationId, businessTransactionId, legalEntityId,
                bookId, chartVersionId, periodId, transactionType, narration, bookingTime,
                valueDate, reversalOfJournalId, policyVersion, postings);
        }
    }

    private static final class RecordingDependencies {
        private final RecordingDataSource dataSource = new RecordingDataSource();
        private final RecordingRepository repository = new RecordingRepository();
        private final RecordingRetryPolicy retryPolicy = new RecordingRetryPolicy();

        private PostingService service() {
            return new PostingService(dataSource, repository, retryPolicy);
        }
    }

    private static final class RecordingRetryPolicy extends PostgresRetryPolicy {
        private int executions;

        private RecordingRetryPolicy() {
            super((commandId, attempt) -> {});
        }

        @Override
        public <T> T execute(UUID commandId, Supplier<T> operation) {
            executions++;
            return super.execute(commandId, operation);
        }
    }

    private static final class RecordingRepository implements LedgerRepository {
        private int calls;
        private int preflightCalls;
        private Optional<PostingResult> completed = Optional.empty();

        @Override
        public PostingResult post(Connection connection, PostingCommand command) {
            calls++;
            return new PostingResult(command.journal().journalId(), 1, command.requestHash());
        }

        @Override
        public Optional<PostingResult> findCompleted(
            Connection connection,
            PostingCommand command
        ) {
            preflightCalls++;
            return completed;
        }
    }

    private static final class RecordingDataSource implements DataSource {
        private int connections;

        @Override
        public Connection getConnection() {
            connections++;
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement();
                    case "getAutoCommit" -> false;
                    case "isClosed" -> false;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    default -> null;
                });
        }

        private static PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "isClosed" -> false;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    default -> null;
                });
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
