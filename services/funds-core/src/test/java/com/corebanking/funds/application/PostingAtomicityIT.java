package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PostingAtomicityIT {
    private static final UUID COMMAND_ID = TestPostingStack.uuid(20);
    private static final Set<UUID> FIXTURE_ACCOUNTS = Set.of(
        TestPostingStack.PROVIDER_ASSET,
        TestPostingStack.CUSTOMER_LIABILITY);

    @Inject
    DataSource dataSource;

    private RowProbe rows;

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
        rows = new RowProbe(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void failureAfterFinancialRowsButBeforeOutboxRollsBackEverything() throws SQLException {
        PostingCommand command = CrashPostingWorker.command(COMMAND_ID);
        LedgerSnapshot before = rows.snapshot(COMMAND_ID, FIXTURE_ACCOUNTS);
        var failing = TestPostingStack.create(dataSource, new PostingTransactionObserver() {
            @Override
            public void afterFinancialRowsBeforeOutbox(UUID commandId) {
                throw new InjectedPostingFailure(commandId);
            }
        });

        InjectedPostingFailure failure = assertThrows(
            InjectedPostingFailure.class,
            () -> failing.postingService().post(command));

        assertAll(
            () -> assertEquals(COMMAND_ID, failure.commandId()),
            () -> assertEquals(before, rows.snapshot(COMMAND_ID, FIXTURE_ACCOUNTS)));

        PostingResult recovered = TestPostingStack
            .create(dataSource, PostingTransactionObserver.noop())
            .postingService()
            .post(command);
        LedgerSnapshot after = rows.snapshot(COMMAND_ID, FIXTURE_ACCOUNTS);

        assertAll(
            () -> assertEquals(CrashPostingWorker.JOURNAL_ID, recovered.journalId()),
            () -> assertEquals(command.requestHash(), recovered.canonicalHash()),
            () -> assertEquals(1, after.journals().size()),
            () -> assertEquals(
                new JournalFact(
                    CrashPostingWorker.JOURNAL_ID,
                    recovered.journalSequence(),
                    COMMAND_ID,
                    command.requestHash()),
                after.journals().getFirst()),
            () -> assertEquals(
                List.of(
                    new PostingFact(
                        CrashPostingWorker.PROVIDER_POSTING_ID,
                        CrashPostingWorker.JOURNAL_ID,
                        TestPostingStack.PROVIDER_ASSET,
                        "NGN",
                        CrashPostingWorker.POSTING_AMOUNT,
                        TestPostingStack.PROVIDER_INITIAL_SEQUENCE + 1,
                        "{\"rail\": \"provider\"}"),
                    new PostingFact(
                        CrashPostingWorker.CUSTOMER_POSTING_ID,
                        CrashPostingWorker.JOURNAL_ID,
                        TestPostingStack.CUSTOMER_LIABILITY,
                        "NGN",
                        -CrashPostingWorker.POSTING_AMOUNT,
                        TestPostingStack.CUSTOMER_INITIAL_SEQUENCE + 1,
                        "{\"customer\": \"crash-recovery\"}")),
                after.postings()),
            () -> assertEquals(
                Map.of(
                    TestPostingStack.PROVIDER_ASSET, CrashPostingWorker.POSTING_AMOUNT,
                    TestPostingStack.CUSTOMER_LIABILITY, -CrashPostingWorker.POSTING_AMOUNT),
                balanceDeltas(before, after)),
            () -> assertEquals(
                new BalanceFact(
                    TestPostingStack.PROVIDER_INITIAL_TOTAL + CrashPostingWorker.POSTING_AMOUNT,
                    TestPostingStack.PROVIDER_INITIAL_SEQUENCE + 1,
                    TestPostingStack.PROVIDER_INITIAL_VERSION + 1),
                after.balances().get(TestPostingStack.PROVIDER_ASSET)),
            () -> assertEquals(
                new BalanceFact(
                    TestPostingStack.CUSTOMER_INITIAL_TOTAL - CrashPostingWorker.POSTING_AMOUNT,
                    TestPostingStack.CUSTOMER_INITIAL_SEQUENCE + 1,
                    TestPostingStack.CUSTOMER_INITIAL_VERSION + 1),
                after.balances().get(TestPostingStack.CUSTOMER_LIABILITY)),
            () -> assertEquals(
                Map.of(
                    TestPostingStack.PROVIDER_CONTROL, CrashPostingWorker.POSTING_AMOUNT,
                    TestPostingStack.CUSTOMER_CONTROL, -CrashPostingWorker.POSTING_AMOUNT,
                    TestPostingStack.INDEPENDENT_CONTROL, 0L),
                controlDeltas(before, after)),
            () -> assertEquals(1, after.controls().size() - before.controls().size()),
            () -> assertEquals(
                new ControlFact(
                    TestPostingStack.PROVIDER_CONTROL_INITIAL_TOTAL + CrashPostingWorker.POSTING_AMOUNT,
                    recovered.journalSequence()),
                after.controls().get(TestPostingStack.PROVIDER_CONTROL)),
            () -> assertEquals(
                new ControlFact(-CrashPostingWorker.POSTING_AMOUNT, recovered.journalSequence()),
                after.controls().get(TestPostingStack.CUSTOMER_CONTROL)),
            () -> assertEquals(
                new ControlFact(TestPostingStack.INDEPENDENT_CONTROL_TOTAL, 0),
                after.controls().get(TestPostingStack.INDEPENDENT_CONTROL)),
            () -> assertEquals(1, completedIdempotencyCount(after)),
            () -> assertEquals(CrashPostingWorker.JOURNAL_ID, after.idempotency().getFirst().journalId()),
            () -> assertNotNull(after.idempotency().getFirst().resultJson()),
            () -> assertTrue(after.idempotency().getFirst().resultJson().contains(command.requestHash())),
            () -> assertEquals(1, after.outbox().size()),
            () -> assertEquals(CrashPostingWorker.JOURNAL_ID, after.outbox().getFirst().aggregateId()),
            () -> assertEquals(recovered.journalSequence(), after.outbox().getFirst().aggregateVersion()),
            () -> assertEquals("JournalPosted", after.outbox().getFirst().eventType()));
    }

    private static Map<UUID, Long> balanceDeltas(LedgerSnapshot before, LedgerSnapshot after) {
        var deltas = new LinkedHashMap<UUID, Long>();
        for (UUID accountId : FIXTURE_ACCOUNTS) {
            deltas.put(
                accountId,
                after.balances().get(accountId).signedPostingTotal()
                    - before.balances().get(accountId).signedPostingTotal());
        }
        return Map.copyOf(deltas);
    }

    private static Map<String, Long> controlDeltas(LedgerSnapshot before, LedgerSnapshot after) {
        var deltas = new LinkedHashMap<String, Long>();
        for (String controlCode : List.of(
            TestPostingStack.PROVIDER_CONTROL,
            TestPostingStack.CUSTOMER_CONTROL,
            TestPostingStack.INDEPENDENT_CONTROL)) {
            ControlFact previous = before.controls().get(controlCode);
            ControlFact current = after.controls().get(controlCode);
            deltas.put(
                controlCode,
                current.signedPostingTotal() - (previous == null ? 0 : previous.signedPostingTotal()));
        }
        return Map.copyOf(deltas);
    }

    private static long completedIdempotencyCount(LedgerSnapshot snapshot) {
        return snapshot.idempotency().stream().filter(fact -> "COMPLETED".equals(fact.state())).count();
    }

    private static final class InjectedPostingFailure extends RuntimeException {
        private final UUID commandId;

        private InjectedPostingFailure(UUID commandId) {
            super("injected failure for command " + commandId);
            this.commandId = commandId;
        }

        private UUID commandId() {
            return commandId;
        }
    }

    private record LedgerSnapshot(
        Map<UUID, BalanceFact> balances,
        Map<String, ControlFact> controls,
        List<IdempotencyFact> idempotency,
        List<JournalFact> journals,
        List<PostingFact> postings,
        List<OutboxFact> outbox
    ) {}

    private record BalanceFact(long signedPostingTotal, long latestAccountSequence, long version) {}

    private record ControlFact(long signedPostingTotal, long latestJournalSequence) {}

    private record IdempotencyFact(
        UUID commandId,
        String requestHash,
        String state,
        UUID journalId,
        String resultJson,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
    ) {}

    private record JournalFact(UUID journalId, long journalSequence, UUID commandId, String canonicalHash) {}

    private record PostingFact(
        UUID postingId,
        UUID journalId,
        UUID accountId,
        String currency,
        long signedMinorUnits,
        long accountSequence,
        String dimensions
    ) {}

    private record OutboxFact(
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

    private static final class RowProbe {
        private final DataSource dataSource;

        private RowProbe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        private LedgerSnapshot snapshot(UUID commandId, Set<UUID> accountIds) throws SQLException {
            try (var connection = dataSource.getConnection()) {
                return new LedgerSnapshot(
                    balances(connection, accountIds),
                    controls(connection),
                    idempotency(connection, commandId),
                    journals(connection, commandId),
                    postings(connection, commandId, accountIds),
                    outbox(connection, commandId));
            }
        }

        private static Map<UUID, BalanceFact> balances(Connection connection, Set<UUID> accountIds)
            throws SQLException {
            var facts = new LinkedHashMap<UUID, BalanceFact>();
            try (var statement = connection.prepareStatement("""
                SELECT account_id, signed_posting_total, latest_account_sequence, version
                FROM funds.materialised_balance
                WHERE account_id IN (?, ?)
                ORDER BY account_id
                """)) {
                bindAccounts(statement, accountIds, 1);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        facts.put(
                            result.getObject("account_id", UUID.class),
                            new BalanceFact(
                                result.getLong("signed_posting_total"),
                                result.getLong("latest_account_sequence"),
                                result.getLong("version")));
                    }
                }
            }
            return Map.copyOf(facts);
        }

        private static Map<String, ControlFact> controls(Connection connection) throws SQLException {
            var facts = new LinkedHashMap<String, ControlFact>();
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
                        facts.put(
                            result.getString("control_account_code"),
                            new ControlFact(
                                result.getLong("signed_posting_total"),
                                result.getLong("latest_journal_sequence")));
                    }
                }
            }
            return Map.copyOf(facts);
        }

        private static List<IdempotencyFact> idempotency(Connection connection, UUID commandId)
            throws SQLException {
            var facts = new ArrayList<IdempotencyFact>();
            try (var statement = connection.prepareStatement("""
                SELECT command_id, request_hash, state, journal_id, result_json::text,
                       created_at, completed_at
                FROM funds.idempotency_command
                WHERE command_id = ?
                ORDER BY command_id
                """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        facts.add(new IdempotencyFact(
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
            return List.copyOf(facts);
        }

        private static List<JournalFact> journals(Connection connection, UUID commandId) throws SQLException {
            var facts = new ArrayList<JournalFact>();
            try (var statement = connection.prepareStatement("""
                SELECT journal_id, journal_sequence, command_id, canonical_hash
                FROM funds.journal
                WHERE command_id = ?
                ORDER BY journal_sequence
                """)) {
                statement.setObject(1, commandId);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        facts.add(new JournalFact(
                            result.getObject("journal_id", UUID.class),
                            result.getLong("journal_sequence"),
                            result.getObject("command_id", UUID.class),
                            result.getString("canonical_hash")));
                    }
                }
            }
            return List.copyOf(facts);
        }

        private static List<PostingFact> postings(
            Connection connection,
            UUID commandId,
            Set<UUID> accountIds
        ) throws SQLException {
            var facts = new ArrayList<PostingFact>();
            try (var statement = connection.prepareStatement("""
                SELECT posting.posting_id, posting.journal_id, posting.account_id,
                       posting.currency, posting.signed_minor_units,
                       posting.account_sequence, posting.dimensions::text
                FROM funds.posting posting
                JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                WHERE journal.command_id = ? AND posting.account_id IN (?, ?)
                ORDER BY posting.posting_id
                """)) {
                statement.setObject(1, commandId);
                bindAccounts(statement, accountIds, 2);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        facts.add(new PostingFact(
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
            return List.copyOf(facts);
        }

        private static List<OutboxFact> outbox(Connection connection, UUID commandId) throws SQLException {
            var facts = new ArrayList<OutboxFact>();
            try (var statement = connection.prepareStatement("""
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
                        facts.add(new OutboxFact(
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
            return List.copyOf(facts);
        }

        private static void bindAccounts(
            java.sql.PreparedStatement statement,
            Set<UUID> accountIds,
            int startIndex
        ) throws SQLException {
            List<UUID> ordered = accountIds.stream().sorted().toList();
            assertEquals(2, ordered.size());
            statement.setObject(startIndex, ordered.get(0));
            statement.setObject(startIndex + 1, ordered.get(1));
        }
    }
}
