package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.infrastructure.postgres.LedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostingServiceTest {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Test
    void subMicrosecondBookingTimeFailsBeforeRetryJdbcOrRepository() {
        var dependencies = new RecordingDependencies();
        PostingService service = dependencies.service();

        assertThrows(InvalidJournalException.class, () -> service.post(command(
            Instant.parse("2026-01-15T10:00:00.123456001Z"), 100, -100)));

        assertEquals(0, dependencies.retryPolicy.executions);
        assertEquals(0, dependencies.dataSource.connections);
        assertEquals(0, dependencies.repository.calls);
    }

    @Test
    void otherInvalidJournalFailsBeforeRetryJdbcOrRepository() {
        var dependencies = new RecordingDependencies();
        PostingService service = dependencies.service();

        assertThrows(InvalidJournalException.class, () -> service.post(command(
            Instant.parse("2026-01-15T10:00:00.123456Z"), 100, -99)));

        assertEquals(0, dependencies.retryPolicy.executions);
        assertEquals(0, dependencies.dataSource.connections);
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
        assertEquals(1, dependencies.repository.calls);
    }

    private static PostingCommand command(Instant bookingTime, long debit, long credit) {
        UUID commandId = uuid(1);
        var journal = new JournalDraft(
            uuid(2), commandId, uuid(3), uuid(4), uuid(5), uuid(6), uuid(7),
            "BOUNDARY_TEST", "Posting service validation boundary", bookingTime,
            LocalDate.of(2026, 1, 15), null, 1,
            List.of(
                new PostingLine(uuid(8), uuid(10), NGN, debit, 0, Map.of()),
                new PostingLine(uuid(9), uuid(11), NGN, credit, 0, Map.of())));
        return new PostingCommand(commandId, new CanonicalJournalHasher().sha256(journal), journal);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
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

        @Override
        public PostingResult post(Connection connection, PostingCommand command) {
            calls++;
            return new PostingResult(command.journal().journalId(), 1, command.requestHash());
        }

        @Override
        public Optional<PostingResult> findCompleted(
            Connection connection,
            UUID commandId,
            String requestHash
        ) {
            return Optional.empty();
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
                    case "getAutoCommit" -> false;
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
