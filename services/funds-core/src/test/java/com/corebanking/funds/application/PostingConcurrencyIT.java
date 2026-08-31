package com.corebanking.funds.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.infrastructure.postgres.JdbcLedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PostingConcurrencyIT {
    private static final UUID BOOK_ID = uuid(1);
    private static final UUID CHART_VERSION_ID = uuid(2);
    private static final UUID PRODUCT_ID = uuid(3);
    private static final UUID PRODUCT_VERSION_ID = uuid(4);
    private static final UUID ACCOUNT_A = uuid(5);
    private static final UUID ACCOUNT_B = uuid(6);
    private static final UUID PERIOD_ID = uuid(7);
    private static final UUID LEGAL_ENTITY_ID = uuid(8);
    private static final CurrencyCode NGN = new CurrencyCode("NGN");
    private static final Comparator<UUID> CANONICAL_UUID_ORDER = Comparator.comparing(UUID::toString);

    @Inject
    DataSource dataSource;

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
    void concurrentSameCommandAndHashWaitsForTheWinnerAndReturnsOneEffect() throws Exception {
        UUID commandId = uuid(100);
        PostingCommand command = command(journal(commandId, 110, 7, false));

        List<Outcome> outcomes = race(command, command);

        PostingResult first = outcomes.get(0).result();
        PostingResult second = outcomes.get(1).result();
        assertAll(
            () -> assertNull(outcomes.get(0).failure()),
            () -> assertNull(outcomes.get(1).failure()),
            () -> assertEquals(first, second));
        try (var connection = dataSource.getConnection()) {
            assertAll(
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")));
        }
    }

    @Test
    void concurrentSameCommandAndDifferentHashesKeepsOnlyTheWinningRequest() throws Exception {
        UUID commandId = uuid(200);
        PostingCommand left = command(journal(commandId, 210, 11, false));
        PostingCommand right = command(journal(commandId, 220, 13, true));
        assertNotEquals(left.requestHash(), right.requestHash());

        List<Outcome> outcomes = race(left, right);

        List<Outcome> successes = outcomes.stream().filter(Outcome::succeeded).toList();
        List<Outcome> failures = outcomes.stream().filter(outcome -> !outcome.succeeded()).toList();
        assertAll(
            () -> assertEquals(1, successes.size()),
            () -> assertEquals(1, failures.size()),
            () -> assertInstanceOf(IdempotencyConflictException.class, failures.getFirst().failure()));
        PostingResult winner = successes.getFirst().result();
        try (var connection = dataSource.getConnection()) {
            PersistedWinner persisted = persistedWinner(connection, commandId);
            assertAll(
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")),
                () -> assertEquals(winner.journalId(), persisted.journalId()),
                () -> assertEquals(winner.canonicalHash(), persisted.requestHash()),
                () -> assertEquals(winner.canonicalHash(), persisted.canonicalHash()),
                () -> assertTrue(
                    Set.of(left.requestHash(), right.requestHash()).contains(persisted.requestHash()),
                    "persisted hash must belong to one of the racing requests"));
        }
    }

    @Test
    void reverseInputOrderLocksCanonicallyAndCommitsOneHundredJournals() throws Exception {
        var observer = new AccountLockObserver();
        var observedDataSource = new ObservedDataSource(dataSource, 0);
        PostingService service = postingService(observedDataSource, observer);
        List<UUID> expectedCommands = new ArrayList<>(100);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int pair = 0; pair < 50; pair++) {
                int firstIndex = pair * 2;
                int secondIndex = firstIndex + 1;
                PostingCommand accountAB = command(journal(
                    uuid(1_000 + firstIndex),
                    2_000 + firstIndex * 10L,
                    firstIndex + 1,
                    false));
                PostingCommand accountBA = command(journal(
                    uuid(1_000 + secondIndex),
                    2_000 + secondIndex * 10L,
                    secondIndex + 1,
                    true));
                expectedCommands.add(accountAB.commandId());
                expectedCommands.add(accountBA.commandId());

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<PostingResult> first = executor.submit(() -> postAfterStart(service, accountAB, ready, start));
                Future<PostingResult> second = executor.submit(() -> postAfterStart(service, accountBA, ready, start));
                assertTrue(ready.await(10, SECONDS), "reverse-order workers did not become ready");
                start.countDown();
                first.get(30, SECONDS);
                second.get(30, SECONDS);
            }
        }

        List<UUID> canonicalAccounts = List.of(ACCOUNT_A, ACCOUNT_B).stream()
            .sorted(CANONICAL_UUID_ORDER)
            .toList();
        List<ConnectionTrace> traces = observedDataSource.traces();
        assertAll(
            () -> assertEquals(Set.copyOf(expectedCommands), observer.lockedCommands()),
            () -> assertEquals(100, traces.stream().filter(ConnectionTrace::committed).count()),
            () -> traces.forEach(trace -> assertCanonicalPrefix(trace.accountLocks(), canonicalAccounts)),
            () -> traces.forEach(trace -> assertCanonicalPrefix(trace.materialisedLocks(), canonicalAccounts)),
            () -> traces.stream().filter(ConnectionTrace::committed).forEach(trace -> assertAll(
                () -> assertEquals(canonicalAccounts, trace.accountLocks()),
                () -> assertEquals(canonicalAccounts, trace.materialisedLocks()))));

        try (var connection = dataSource.getConnection()) {
            long replayedA = postingTotal(connection, ACCOUNT_A);
            long replayedB = postingTotal(connection, ACCOUNT_B);
            assertAll(
                () -> assertEquals(100, queryLong(connection, "SELECT count(*) FROM funds.journal")),
                () -> assertEquals(200, queryLong(connection, "SELECT count(*) FROM funds.posting")),
                () -> assertEquals(100, queryLong(connection, "SELECT count(*) FROM funds.outbox_event")),
                () -> assertEquals(replayedA, materialisedTotal(connection, ACCOUNT_A)),
                () -> assertEquals(replayedB, materialisedTotal(connection, ACCOUNT_B)),
                () -> assertEquals(5_050, replayedA),
                () -> assertEquals(-5_050, replayedB));
        }
    }

    private List<Outcome> race(PostingCommand left, PostingCommand right) throws Exception {
        var observer = new FirstWriterGate(left.commandId());
        var observedDataSource = new ObservedDataSource(dataSource, 2);
        PostingService service = postingService(observedDataSource, observer);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<PostingResult> leftFuture = executor.submit(() -> postAfterStart(service, left, ready, start));
        Future<PostingResult> rightFuture = executor.submit(() -> postAfterStart(service, right, ready, start));
        try {
            assertTrue(ready.await(10, SECONDS), "racing workers did not become ready");
            start.countDown();
            assertTrue(observer.awaitWinner(), "no worker acquired the idempotency row");
            assertTrue(observedDataSource.awaitObservedConnections(), "workers did not acquire independent connections");
            List<Integer> backendPids = observedDataSource.initialBackendPids();
            assertAll(
                () -> assertEquals(2, backendPids.size()),
                () -> assertNotEquals(backendPids.get(0), backendPids.get(1)));
            int waitingPid = awaitLockWait(backendPids);
            assertAll(
                () -> assertTrue(backendPids.contains(waitingPid)),
                () -> assertFalse(leftFuture.isDone(), "winner must remain gated before commit"),
                () -> assertFalse(rightFuture.isDone(), "loser must wait for the winner's transaction"));

            observer.releaseWinner();
            return List.of(outcome(leftFuture), outcome(rightFuture));
        } finally {
            observer.releaseWinner();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, SECONDS), "race executor did not terminate");
        }
    }

    private int awaitLockWait(List<Integer> backendPids) throws SQLException {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT pid
                FROM pg_stat_activity
                WHERE pid IN (?, ?) AND wait_event_type = 'Lock'
                """)) {
                statement.setInt(1, backendPids.get(0));
                statement.setInt(2, backendPids.get(1));
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return rows.getInt(1);
                    }
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("no PostgreSQL lock waiter observed for backends " + backendPids);
    }

    private static PostingResult postAfterStart(
        PostingService service,
        PostingCommand command,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        ready.countDown();
        await(start, "posting start gate");
        return service.post(command);
    }

    private static Outcome outcome(Future<PostingResult> future) throws Exception {
        try {
            return new Outcome(future.get(30, SECONDS), null);
        } catch (ExecutionException failure) {
            return new Outcome(null, failure.getCause());
        }
    }

    private static PostingService postingService(
        DataSource dataSource,
        PostingTransactionObserver observer
    ) {
        return new PostingService(
            dataSource,
            new JdbcLedgerRepository(observer),
            new PostgresRetryPolicy((commandId, attempt) -> {}),
            observer);
    }

    private static PostingCommand command(JournalDraft journal) {
        return new PostingCommand(
            journal.commandId(),
            new CanonicalJournalHasher().sha256(journal),
            journal);
    }

    private static JournalDraft journal(
        UUID commandId,
        long idBase,
        long amount,
        boolean reverseInput
    ) {
        var accountA = new PostingLine(uuid(idBase + 1), ACCOUNT_A, NGN, amount, 0, Map.of());
        var accountB = new PostingLine(uuid(idBase + 2), ACCOUNT_B, NGN, -amount, 0, Map.of());
        List<PostingLine> postings = reverseInput ? List.of(accountB, accountA) : List.of(accountA, accountB);
        return new JournalDraft(
            uuid(idBase),
            commandId,
            uuid(idBase + 3),
            uuid(idBase + 4),
            LEGAL_ENTITY_ID,
            BOOK_ID,
            PERIOD_ID,
            "CONCURRENT_TRANSFER",
            "Concurrent transfer " + idBase,
            Instant.parse("2026-01-15T10:00:00Z"),
            LocalDate.of(2026, 1, 15),
            null,
            1,
            postings);
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
            VALUES (?, 'CONCURRENCY-SAVINGS', 'SAVINGS', 'CONVENTIONAL')
            """, PRODUCT_ID);
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json)
            VALUES (?, ?, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00',
                    'APP-CONCURRENCY-001', ?, '{}'::jsonb)
            """, PRODUCT_VERSION_ID, PRODUCT_ID, "a".repeat(64));
        insertAccount(connection, ACCOUNT_A, "ACCOUNT-A", "INTERNAL", null, "ASSET", "DEBIT", "CONTROL-A");
        insertAccount(
            connection,
            ACCOUNT_B,
            "ACCOUNT-B",
            "CUSTOMER",
            PRODUCT_VERSION_ID,
            "LIABILITY",
            "CREDIT",
            "CONTROL-B");
    }

    private static void insertAccount(
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

    private void truncateAllTables() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
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
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static long postingTotal(Connection connection, UUID accountId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT coalesce(sum(signed_minor_units), 0)
            FROM funds.posting
            WHERE account_id = ?
            """)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long materialisedTotal(Connection connection, UUID accountId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT signed_posting_total
            FROM funds.materialised_balance
            WHERE account_id = ?
            """)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static PersistedWinner persistedWinner(Connection connection, UUID commandId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT command.request_hash, journal.journal_id, journal.canonical_hash
            FROM funds.idempotency_command command
            JOIN funds.journal journal ON journal.command_id = command.command_id
            WHERE command.command_id = ?
            """)) {
            statement.setObject(1, commandId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new PersistedWinner(
                    rows.getString("request_hash"),
                    rows.getObject("journal_id", UUID.class),
                    rows.getString("canonical_hash"));
            }
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void assertCanonicalPrefix(List<UUID> actual, List<UUID> canonical) {
        assertTrue(actual.size() <= canonical.size(), () -> "too many lock operations: " + actual);
        assertEquals(canonical.subList(0, actual.size()), actual);
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(30, SECONDS)) {
                throw new AssertionError(description + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " interrupted", interrupted);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private record Outcome(PostingResult result, Throwable failure) {
        private boolean succeeded() {
            return result != null;
        }
    }

    private record PersistedWinner(String requestHash, UUID journalId, String canonicalHash) {}

    private static final class FirstWriterGate implements PostingTransactionObserver {
        private final UUID commandId;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch winnerAcquired = new CountDownLatch(1);
        private final CountDownLatch winnerRelease = new CountDownLatch(1);

        private FirstWriterGate(UUID commandId) {
            this.commandId = commandId;
        }

        @Override
        public void afterIdempotencyAcquired(UUID observedCommandId) {
            if (commandId.equals(observedCommandId) && claimed.compareAndSet(false, true)) {
                winnerAcquired.countDown();
                await(winnerRelease, "first-writer release gate");
            }
        }

        private boolean awaitWinner() throws InterruptedException {
            return winnerAcquired.await(10, SECONDS);
        }

        private void releaseWinner() {
            winnerRelease.countDown();
        }
    }

    private static final class AccountLockObserver implements PostingTransactionObserver {
        private final Set<UUID> lockedCommands = ConcurrentHashMap.newKeySet();

        @Override
        public void afterAccountLocks(UUID commandId) {
            lockedCommands.add(commandId);
        }

        private Set<UUID> lockedCommands() {
            return Set.copyOf(lockedCommands);
        }
    }

    private static final class ObservedDataSource implements DataSource {
        private final DataSource delegate;
        private final CountDownLatch observedConnections;
        private final List<Integer> backendPids = new CopyOnWriteArrayList<>();
        private final List<ConnectionTrace> traces = new CopyOnWriteArrayList<>();

        private ObservedDataSource(DataSource delegate, int connectionsToObserve) {
            this.delegate = delegate;
            this.observedConnections = new CountDownLatch(connectionsToObserve);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observe(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(delegate.getConnection(username, password));
        }

        private Connection observe(Connection connection) throws SQLException {
            int backendPid;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                backendPid = rows.getInt(1);
            }
            var trace = new ConnectionTrace(backendPid);
            backendPids.add(backendPid);
            traces.add(trace);
            observedConnections.countDown();
            return (Connection) Proxy.newProxyInstance(
                PostingConcurrencyIT.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(connection, method, arguments);
                    if ("prepareStatement".equals(method.getName())
                        && arguments != null
                        && arguments.length > 0
                        && arguments[0] instanceof String sql
                        && result instanceof PreparedStatement preparedStatement) {
                        return observe(preparedStatement, sql, trace);
                    }
                    if ("commit".equals(method.getName())) {
                        trace.committed.set(true);
                    }
                    return result;
                });
        }

        private static PreparedStatement observe(
            PreparedStatement statement,
            String sql,
            ConnectionTrace trace
        ) {
            LockKind kind = LockKind.from(sql);
            if (kind == null) {
                return statement;
            }
            var accountId = new UUID[1];
            return (PreparedStatement) Proxy.newProxyInstance(
                PostingConcurrencyIT.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if ("setObject".equals(method.getName())
                        && arguments != null
                        && arguments.length >= 2
                        && Integer.valueOf(1).equals(arguments[0])
                        && arguments[1] instanceof UUID uuid) {
                        accountId[0] = uuid;
                    }
                    if ("executeQuery".equals(method.getName()) && accountId[0] != null) {
                        kind.record(trace, accountId[0]);
                    }
                    return invoke(statement, method, arguments);
                });
        }

        private boolean awaitObservedConnections() throws InterruptedException {
            return observedConnections.await(10, SECONDS);
        }

        private List<Integer> initialBackendPids() {
            return List.copyOf(backendPids.subList(0, Math.min(2, backendPids.size())));
        }

        private List<ConnectionTrace> traces() {
            return List.copyOf(traces);
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
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }

    private enum LockKind {
        ACCOUNT {
            @Override
            void record(ConnectionTrace trace, UUID accountId) {
                trace.accountLocks.add(accountId);
            }
        },
        MATERIALISED {
            @Override
            void record(ConnectionTrace trace, UUID accountId) {
                trace.materialisedLocks.add(accountId);
            }
        };

        abstract void record(ConnectionTrace trace, UUID accountId);

        private static LockKind from(String sql) {
            if (sql.contains("FROM funds.ledger_account account") && sql.contains("FOR UPDATE OF account")) {
                return ACCOUNT;
            }
            if (sql.contains("FROM funds.materialised_balance") && sql.contains("FOR UPDATE")) {
                return MATERIALISED;
            }
            return null;
        }
    }

    private static final class ConnectionTrace {
        private final int backendPid;
        private final List<UUID> accountLocks = new CopyOnWriteArrayList<>();
        private final List<UUID> materialisedLocks = new CopyOnWriteArrayList<>();
        private final AtomicBoolean committed = new AtomicBoolean();

        private ConnectionTrace(int backendPid) {
            this.backendPid = backendPid;
        }

        private List<UUID> accountLocks() {
            return List.copyOf(accountLocks);
        }

        private List<UUID> materialisedLocks() {
            return List.copyOf(materialisedLocks);
        }

        private boolean committed() {
            return committed.get();
        }

        @Override
        public String toString() {
            return "ConnectionTrace[backendPid=" + backendPid
                + ", accountLocks=" + accountLocks
                + ", materialisedLocks=" + materialisedLocks
                + ", committed=" + committed + ']';
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments)
        throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
