package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.testsupport.GeneratedLedgerOperation;
import com.corebanking.funds.testsupport.PropertyCases;
import com.corebanking.funds.testsupport.ReferenceLedgerModel;
import com.corebanking.funds.testsupport.ReferenceLedgerModel.ExpectedOutcome;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AccountingStateMachineIT {
    private static final long BASE_SEED = 0xCB20260830L;
    private static final int SEED_COUNT = 32;
    private static final int OPERATIONS_PER_SEED = 128;
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 1, 15);

    @Inject
    DataSource dataSource;

    @BeforeEach
    void resetDatabase() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void generatedHistoriesPreserveEveryAccountingInvariant() throws SQLException {
        int executed = 0;
        var totals = new RunTotals();
        for (int seedIndex = 0; seedIndex < SEED_COUNT; seedIndex++) {
            long seed = BASE_SEED + seedIndex;
            Fixture fixture = seedFixture(seed);
            seedFixture(fixture, seedIndex);
            executed += runSeed(seed, fixture, totals);
        }
        assertEquals(SEED_COUNT * OPERATIONS_PER_SEED, executed);
        assertDatabaseCounts(totals, "complete deterministic run");
    }

    @Test
    void reversalComparisonRejectsDimensionMismatch() throws SQLException {
        long seed = BASE_SEED;
        Fixture fixture = seedFixture(seed);
        seedFixture(fixture, 0);
        var model = new ReferenceLedgerModel(fixture.accountControls());
        var service = TestPostingStack.create(dataSource, PostingTransactionObserver.noop()).postingService();

        PostingCommand original = commandFor(
            seed,
            0,
            new GeneratedLedgerOperation.Post(deterministicUuid(seed, 0, 200), 100),
            fixture,
            model);
        model.apply(original, service.post(original));

        PostingCommand validReversal = commandFor(
            seed,
            1,
            new GeneratedLedgerOperation.Reverse(
                deterministicUuid(seed, 1, 200),
                original.journal().journalId()),
            fixture,
            model);
        var alteredLines = new ArrayList<>(validReversal.journal().postings());
        PostingLine first = alteredLines.getFirst();
        alteredLines.set(0, new PostingLine(
            first.postingId(),
            first.accountId(),
            first.currency(),
            first.signedMinorUnits(),
            first.accountSequence(),
            Map.of("operation", "dimension-mismatch", "seed", Long.toUnsignedString(seed))));
        PostingCommand alteredReversal = command(copyWithPostings(validReversal.journal(), alteredLines));
        model.apply(alteredReversal, service.post(alteredReversal));

        AssertionError mismatch = assertThrows(AssertionError.class, () -> {
            try (Connection connection = dataSource.getConnection()) {
                assertReversalsAndHashes(connection, model);
            }
        });
        assertTrue(mismatch.getMessage().contains("dimensions"), mismatch::getMessage);
    }

    @Test
    void reversalCandidatesExcludeCorrectionsAndAlreadyReversedOriginals() {
        long seed = BASE_SEED;
        Fixture fixture = seedFixture(seed);
        var model = new ReferenceLedgerModel(fixture.accountControls());
        PostingCommand firstOriginal = commandFor(
            seed, 0, new GeneratedLedgerOperation.Post(deterministicUuid(seed, 0, 200), 100),
            fixture, model);
        PostingCommand secondOriginal = commandFor(
            seed, 1, new GeneratedLedgerOperation.Post(deterministicUuid(seed, 1, 200), 99),
            fixture, model);
        model.apply(firstOriginal, modelResult(firstOriginal, 1));
        model.apply(secondOriginal, modelResult(secondOriginal, 2));
        PostingCommand reversal = commandFor(
            seed,
            2,
            new GeneratedLedgerOperation.Reverse(
                deterministicUuid(seed, 2, 200), firstOriginal.journal().journalId()),
            fixture,
            model);
        model.apply(reversal, modelResult(reversal, 3));

        assertEquals(List.of(secondOriginal.journal().journalId()), model.reversibleJournalIds());
    }

    private static PostingResult modelResult(PostingCommand command, long sequence) {
        return new PostingResult(
            command.journal().journalId(),
            sequence,
            new CanonicalJournalHasher().sha256(command.journal()));
    }

    private static JournalDraft copyWithPostings(JournalDraft journal, List<PostingLine> postings) {
        return new JournalDraft(
            journal.journalId(),
            journal.commandId(),
            journal.correlationId(),
            journal.businessTransactionId(),
            journal.legalEntityId(),
            journal.bookId(),
            journal.periodId(),
            journal.transactionType(),
            journal.narration(),
            journal.bookingTime(),
            journal.valueDate(),
            journal.reversalOfJournalId(),
            journal.policyVersion(),
            postings);
    }

    private int runSeed(long seed, Fixture fixture, RunTotals totals) throws SQLException {
        var random = new SplittableRandom(seed);
        var model = new ReferenceLedgerModel(fixture.accountControls());
        var service = TestPostingStack.create(dataSource, PostingTransactionObserver.noop()).postingService();
        var prefix = new ArrayList<GeneratedLedgerOperation>(OPERATIONS_PER_SEED);
        for (int operationIndex = 0; operationIndex < OPERATIONS_PER_SEED; operationIndex++) {
            GeneratedLedgerOperation operation = generate(random, model, operationIndex);
            prefix.add(operation);
            try {
                executeAndAssert(seed, operationIndex, operation, fixture, model, service, totals);
            } catch (AssertionError | RuntimeException | SQLException failure) {
                replayOnce(seed, prefix, failure);
                throw contextualFailure(seed, operationIndex, prefix, failure);
            }
        }
        return prefix.size();
    }

    private static GeneratedLedgerOperation generate(
        SplittableRandom random,
        ReferenceLedgerModel model,
        int operationIndex
    ) {
        int choice = random.nextInt(100);
        if (choice < 45) {
            return new GeneratedLedgerOperation.Post(
                randomUuid(random),
                PropertyCases.stateMachineMinorUnits(random, operationIndex));
        }
        if (choice < 65 && !model.successfulCommandIds().isEmpty()) {
            return new GeneratedLedgerOperation.RetrySame(select(random, model.successfulCommandIds()));
        }
        if (choice < 75 && !model.successfulCommandIds().isEmpty()) {
            return new GeneratedLedgerOperation.RetryDifferentHash(
                select(random, model.successfulCommandIds()));
        }
        if (choice < 90 && !model.reversibleJournalIds().isEmpty()) {
            return new GeneratedLedgerOperation.Reverse(
                randomUuid(random),
                select(random, model.reversibleJournalIds()));
        }
        if (choice >= 90) {
            long debit = PropertyCases.stateMachineMinorUnits(random, operationIndex);
            long credit = debit == 1 ? 2 : debit - 1;
            return new GeneratedLedgerOperation.SubmitUnbalanced(randomUuid(random), debit, credit);
        }
        return new GeneratedLedgerOperation.Post(
            randomUuid(random),
            PropertyCases.stateMachineMinorUnits(random, operationIndex));
    }

    private static <T> T select(SplittableRandom random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    private void executeAndAssert(
        long seed,
        int operationIndex,
        GeneratedLedgerOperation operation,
        Fixture fixture,
        ReferenceLedgerModel model,
        PostingService service,
        RunTotals totals
    ) throws SQLException {
        PostingCommand command = commandFor(seed, operationIndex, operation, fixture, model);
        ExpectedOutcome expected = model.predict(command);
        DatabaseCounts before = databaseCounts();

        if (expected == ExpectedOutcome.NEW_SUCCESS || expected == ExpectedOutcome.SUCCESSFUL_RETRY) {
            PostingResult result = service.post(command);
            model.apply(command, result);
            if (expected == ExpectedOutcome.NEW_SUCCESS) {
                totals.recordNewJournal(command.journal().postings().size());
            }
        } else {
            Class<? extends RuntimeException> exceptionType = ReferenceLedgerModel.exceptionType(expected);
            assertThrows(exceptionType, () -> service.post(command));
        }

        DatabaseCounts after = databaseCounts();
        assertOperationCardinality(expected, command, model, before, after);
        assertAllInvariants(fixture, model);
    }

    private static PostingCommand commandFor(
        long seed,
        int operationIndex,
        GeneratedLedgerOperation operation,
        Fixture fixture,
        ReferenceLedgerModel model
    ) {
        if (operation instanceof GeneratedLedgerOperation.RetrySame retry) {
            return model.successfulCommand(retry.commandId()).command();
        }
        if (operation instanceof GeneratedLedgerOperation.RetryDifferentHash conflict) {
            return command(balancedDraft(
                seed,
                operationIndex,
                conflict.commandId(),
                1,
                fixture,
                "CONFLICT"));
        }
        if (operation instanceof GeneratedLedgerOperation.Post post) {
            return command(balancedDraft(
                seed,
                operationIndex,
                post.commandId(),
                post.amount(),
                fixture,
                "POST"));
        }
        if (operation instanceof GeneratedLedgerOperation.SubmitUnbalanced unbalanced) {
            JournalDraft draft = draft(
                seed,
                operationIndex,
                unbalanced.commandId(),
                null,
                fixture,
                "UNBALANCED",
                List.of(
                    line(seed, operationIndex, 0, fixture.debitAccountId(), unbalanced.debit()),
                    line(seed, operationIndex, 1, fixture.creditAccountId(), -unbalanced.credit())));
            return command(draft);
        }
        var reversal = (GeneratedLedgerOperation.Reverse) operation;
        var original = model.journal(reversal.originalJournalId());
        var reversed = new ArrayList<PostingLine>(original.lines().size());
        for (int lineIndex = 0; lineIndex < original.lines().size(); lineIndex++) {
            var originalLine = original.lines().get(lineIndex);
            reversed.add(new PostingLine(
                deterministicUuid(seed, operationIndex, 100 + lineIndex),
                originalLine.accountId(),
                originalLine.currency(),
                Math.negateExact(originalLine.signedMinorUnits()),
                0,
                originalLine.dimensions()));
        }
        return command(draft(
            seed,
            operationIndex,
            reversal.commandId(),
            reversal.originalJournalId(),
            fixture,
            "REVERSAL",
            reversed));
    }

    private static JournalDraft balancedDraft(
        long seed,
        int operationIndex,
        UUID commandId,
        long amount,
        Fixture fixture,
        String transactionType
    ) {
        return draft(
            seed,
            operationIndex,
            commandId,
            null,
            fixture,
            transactionType,
            List.of(
                line(seed, operationIndex, 0, fixture.debitAccountId(), amount),
                line(seed, operationIndex, 1, fixture.creditAccountId(), -amount)));
    }

    private static JournalDraft draft(
        long seed,
        int operationIndex,
        UUID commandId,
        UUID reversalOf,
        Fixture fixture,
        String transactionType,
        List<PostingLine> lines
    ) {
        return new JournalDraft(
            deterministicUuid(seed, operationIndex, 1),
            commandId,
            deterministicUuid(seed, operationIndex, 2),
            deterministicUuid(seed, operationIndex, 3),
            fixture.legalEntityId(),
            fixture.bookId(),
            fixture.periodId(),
            transactionType,
            "seed=" + seed + ", operation=" + operationIndex,
            Instant.ofEpochSecond(1_768_473_600L + Math.floorMod(seed, 86_400)),
            VALUE_DATE,
            reversalOf,
            1,
            lines);
    }

    private static PostingLine line(
        long seed,
        int operationIndex,
        int lineIndex,
        UUID accountId,
        long amount
    ) {
        return new PostingLine(
            deterministicUuid(seed, operationIndex, 10 + lineIndex),
            accountId,
            NGN,
            amount,
            0,
            Map.of("seed", Long.toUnsignedString(seed), "operation", Integer.toString(operationIndex)));
    }

    private static PostingCommand command(JournalDraft draft) {
        return new PostingCommand(draft.commandId(), new CanonicalJournalHasher().sha256(draft), draft);
    }

    private static UUID randomUuid(SplittableRandom random) {
        return new UUID(random.nextLong(), random.nextLong());
    }

    private static UUID deterministicUuid(long seed, int operationIndex, int salt) {
        long streamSeed = seed * 0x9E3779B97F4A7C15L
            + (long) operationIndex * 0xD1B54A32D192ED03L
            + salt;
        var random = new SplittableRandom(streamSeed);
        return randomUuid(random);
    }

    private static Fixture seedFixture(long seed) {
        UUID debitAccountId = deterministicUuid(seed, -1, 7);
        UUID creditAccountId = deterministicUuid(seed, -1, 8);
        return new Fixture(
            deterministicUuid(seed, -1, 1),
            deterministicUuid(seed, -1, 2),
            deterministicUuid(seed, -1, 3),
            deterministicUuid(seed, -1, 4),
            deterministicUuid(seed, -1, 5),
            deterministicUuid(seed, -1, 6),
            debitAccountId,
            creditAccountId,
            Map.of(debitAccountId, "ASSET-CONTROL", creditAccountId, "LIABILITY-CONTROL"));
    }

    private void seedFixture(Fixture fixture, int seedIndex) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, """
                INSERT INTO funds.book
                    (book_id, legal_entity_id, functional_currency, timezone, calendar_code,
                     accounting_policy_version)
                VALUES (?, ?, 'NGN', 'Africa/Lagos', 'NG', 1)
                """, fixture.bookId(), fixture.legalEntityId());
            execute(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, activated_at)
                VALUES (?, ?, 1, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, fixture.chartVersionId(), fixture.bookId());
            execute(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
                """, fixture.periodId(), fixture.bookId());
            execute(connection, """
                INSERT INTO funds.product_definition
                    (product_id, product_code, product_kind, finance_principle)
                VALUES (?, ?, 'SAVINGS', 'CONVENTIONAL')
                """, fixture.productId(), "STATE-MACHINE-" + seedIndex);
            execute(connection, """
                INSERT INTO funds.product_version
                    (product_version_id, product_id, version, effective_from, approval_reference,
                     policy_hash, policy_json)
                VALUES (?, ?, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', ?, ?, '{}'::jsonb)
                """,
                fixture.productVersionId(),
                fixture.productId(),
                "STATE-MACHINE-" + seedIndex,
                "9".repeat(64));
            insertAccount(connection, fixture, fixture.debitAccountId(), "DEBIT", "INTERNAL", null,
                "ASSET", "DEBIT", "ASSET-CONTROL");
            insertAccount(connection, fixture, fixture.creditAccountId(), "CREDIT", "CUSTOMER",
                fixture.productVersionId(), "LIABILITY", "CREDIT", "LIABILITY-CONTROL");
        }
    }

    private static void insertAccount(
        Connection connection,
        Fixture fixture,
        UUID accountId,
        String accountCode,
        String accountScope,
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
            fixture.bookId(),
            fixture.chartVersionId(),
            accountCode,
            accountScope,
            productVersionId,
            accountClass,
            normalBalance,
            controlCode);
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private DatabaseCounts databaseCounts() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return new DatabaseCounts(
                scalarLong(connection, "SELECT count(*) FROM funds.journal"),
                scalarLong(connection, "SELECT count(*) FROM funds.posting"),
                scalarLong(connection, "SELECT count(*) FROM funds.idempotency_command"),
                scalarLong(connection, "SELECT count(*) FROM funds.outbox_event"));
        }
    }

    private static long scalarLong(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("scalar query returned no row: " + sql);
                }
                return rows.getLong(1);
            }
        }
    }

    private static void assertOperationCardinality(
        ExpectedOutcome outcome,
        PostingCommand command,
        ReferenceLedgerModel model,
        DatabaseCounts before,
        DatabaseCounts after
    ) {
        long successfulDelta = outcome == ExpectedOutcome.NEW_SUCCESS ? 1 : 0;
        long postingDelta = outcome == ExpectedOutcome.NEW_SUCCESS ? command.journal().postings().size() : 0;
        assertEquals(before.journals() + successfulDelta, after.journals(), "journal cardinality");
        assertEquals(before.postings() + postingDelta, after.postings(), "posting cardinality");
        assertEquals(before.idempotency() + successfulDelta, after.idempotency(), "idempotency cardinality");
        assertEquals(before.outbox() + successfulDelta, after.outbox(), "outbox cardinality");
        if (outcome == ExpectedOutcome.SUCCESSFUL_RETRY) {
            assertEquals(
                model.successfulCommand(command.commandId()).result().journalId(),
                command.journal().journalId(),
                "same-hash retry must retain the original journal ID");
        }
    }

    private void assertDatabaseCounts(RunTotals totals, String context) throws SQLException {
        DatabaseCounts actual = databaseCounts();
        assertEquals(totals.journals, actual.journals(), context + " journal count");
        assertEquals(totals.postings, actual.postings(), context + " posting count");
        assertEquals(totals.journals, actual.idempotency(), context + " idempotency count");
        assertEquals(totals.journals, actual.outbox(), context + " outbox count");
    }

    private void assertAllInvariants(Fixture fixture, ReferenceLedgerModel model) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, scalarLong(connection, """
                SELECT count(*)
                FROM (
                    SELECT posting.journal_id, posting.currency
                    FROM funds.posting posting
                    JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                    WHERE journal.book_id = ?
                    GROUP BY posting.journal_id, posting.currency
                    HAVING sum(posting.signed_minor_units::numeric) <> 0
                ) unbalanced
                """, fixture.bookId()), "every journal must balance independently by currency");

            assertEquals(
                model.accountTotals(),
                replayAndMaterialisedTotals(connection, fixture),
                "materialised balances must equal immutable-posting replay at the current cutoff");
            assertEquals(
                model.controlTotals(),
                independentAndProjectedControlTotals(connection, fixture),
                "control projections must equal an independent posting/account aggregation");

            assertEquals(0, scalarLong(connection, """
                SELECT count(*)
                FROM funds.journal journal
                WHERE journal.book_id = ?
                  AND ((SELECT count(*) FROM funds.outbox_event event
                        WHERE event.aggregate_id = journal.journal_id
                          AND event.event_type = 'JournalPosted') <> 1
                    OR (SELECT count(*) FROM funds.idempotency_command command
                        WHERE command.command_id = journal.command_id
                          AND command.state = 'COMPLETED'
                          AND command.journal_id = journal.journal_id) <> 1)
                """, fixture.bookId()), "every successful journal needs one outbox event and completed command");
            assertEquals(model.journals().size(), scalarLong(connection,
                "SELECT count(*) FROM funds.journal WHERE book_id = ?", fixture.bookId()));
            assertEquals(model.successfulCommandCount(), scalarLong(connection, """
                SELECT count(*) FROM funds.idempotency_command command
                JOIN funds.journal journal ON journal.journal_id = command.journal_id
                WHERE journal.book_id = ? AND command.state = 'COMPLETED'
                """, fixture.bookId()));
            assertEquals(model.expectedOutboxIds(), outboxIds(connection, fixture),
                "outbox IDs must be deterministic and one-to-one with journals");
            assertReversalsAndHashes(connection, model);
        }
    }

    private static Map<UUID, BigInteger> replayAndMaterialisedTotals(
        Connection connection,
        Fixture fixture
    ) throws SQLException {
        var totals = new LinkedHashMap<UUID, BigInteger>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT account.account_id,
                   coalesce(sum(posting.signed_minor_units::numeric)
                       FILTER (WHERE journal.journal_sequence <= cutoff.maximum), 0) AS replay_total,
                   coalesce(balance.signed_posting_total::numeric, 0) AS materialised_total
            FROM funds.ledger_account account
            CROSS JOIN LATERAL (
                SELECT coalesce(max(journal_sequence), 0) AS maximum
                FROM funds.journal WHERE book_id = ?
            ) cutoff
            LEFT JOIN funds.posting posting ON posting.account_id = account.account_id
            LEFT JOIN funds.journal journal ON journal.journal_id = posting.journal_id
            LEFT JOIN funds.materialised_balance balance ON balance.account_id = account.account_id
            WHERE account.book_id = ?
            GROUP BY account.account_id, balance.signed_posting_total, cutoff.maximum
            ORDER BY account.account_id
            """)) {
            statement.setObject(1, fixture.bookId());
            statement.setObject(2, fixture.bookId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    BigInteger replay = rows.getObject("replay_total", BigDecimal.class).toBigIntegerExact();
                    BigInteger materialised = rows.getObject("materialised_total", BigDecimal.class).toBigIntegerExact();
                    assertEquals(replay, materialised, "materialised total differs from replay");
                    totals.put(rows.getObject("account_id", UUID.class), replay);
                }
            }
        }
        return Map.copyOf(totals);
    }

    private static Map<ReferenceLedgerModel.ControlKey, BigInteger> independentAndProjectedControlTotals(
        Connection connection,
        Fixture fixture
    ) throws SQLException {
        Map<ReferenceLedgerModel.ControlKey, BigInteger> independent = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT account.control_account_code, posting.currency,
                   sum(posting.signed_minor_units::numeric) AS independent_total
            FROM funds.posting posting
            JOIN funds.journal journal ON journal.journal_id = posting.journal_id
            JOIN funds.ledger_account account ON account.account_id = posting.account_id
            WHERE journal.book_id = ?
            GROUP BY account.control_account_code, posting.currency
            ORDER BY account.control_account_code, posting.currency
            """)) {
            statement.setObject(1, fixture.bookId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    independent.put(
                        new ReferenceLedgerModel.ControlKey(
                            rows.getString("control_account_code"),
                            CurrencyCode.of(rows.getString("currency"))),
                        rows.getObject("independent_total", BigDecimal.class).toBigIntegerExact());
                }
            }
        }
        Map<ReferenceLedgerModel.ControlKey, BigInteger> projected = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT control_account_code, currency, signed_posting_total::numeric
            FROM funds.control_account_projection
            WHERE book_id = ?
            ORDER BY control_account_code, currency
            """)) {
            statement.setObject(1, fixture.bookId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    projected.put(
                        new ReferenceLedgerModel.ControlKey(
                            rows.getString("control_account_code"),
                            CurrencyCode.of(rows.getString("currency"))),
                        rows.getObject("signed_posting_total", BigDecimal.class).toBigIntegerExact());
                }
            }
        }
        assertEquals(independent, projected, "persisted control projection differs from independent SQL");
        return Map.copyOf(independent);
    }

    private static java.util.Set<UUID> outboxIds(Connection connection, Fixture fixture)
        throws SQLException {
        var ids = new LinkedHashSet<UUID>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT event.event_id
            FROM funds.outbox_event event
            JOIN funds.journal journal ON journal.journal_id = event.aggregate_id
            WHERE journal.book_id = ?
            ORDER BY event.event_id
            """)) {
            statement.setObject(1, fixture.bookId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return java.util.Set.copyOf(ids);
    }

    private static void assertReversalsAndHashes(Connection connection, ReferenceLedgerModel model)
        throws SQLException {
        for (var journal : model.journals().values()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reversal_of_journal_id, canonical_hash
                FROM funds.journal WHERE journal_id = ?
                """)) {
                statement.setObject(1, journal.journalId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing journal " + journal.journalId());
                    }
                    assertEquals(journal.reversalOfJournalId(), rows.getObject(1, UUID.class));
                    assertEquals(journal.canonicalHash(), rows.getString(2),
                        "original journal hash must remain unchanged");
                }
            }
            if (journal.reversalOfJournalId() != null) {
                var expected = lineMultiset(model.journal(journal.reversalOfJournalId()).lines(), true);
                var actual = databaseLineMultiset(connection, journal.journalId());
                assertEquals(expected, actual,
                    "reversal lines must be exact negations with identical dimensions");
            }
        }
    }

    private static Map<LineValue, Integer> lineMultiset(
        List<ReferenceLedgerModel.ModelLine> lines,
        boolean negate
    ) {
        var values = new HashMap<LineValue, Integer>();
        for (var line : lines) {
            long amount = negate ? Math.negateExact(line.signedMinorUnits()) : line.signedMinorUnits();
            values.merge(new LineValue(
                line.accountId(),
                line.currency(),
                amount,
                stringDimensions(line.dimensions())), 1, Integer::sum);
        }
        return Map.copyOf(values);
    }

    private static Map<String, DimensionValue> stringDimensions(Map<String, String> dimensions) {
        var typed = new LinkedHashMap<String, DimensionValue>();
        dimensions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> typed.put(entry.getKey(), new DimensionValue("string", entry.getValue())));
        return Map.copyOf(typed);
    }

    private static Map<LineValue, Integer> databaseLineMultiset(Connection connection, UUID journalId)
        throws SQLException {
        var postings = new LinkedHashMap<UUID, DatabaseLine>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT posting.posting_id, posting.account_id, posting.currency,
                   posting.signed_minor_units, dimension.key AS dimension_key,
                   jsonb_typeof(dimension.value) AS dimension_type,
                   dimension.value #>> '{}' AS dimension_value
            FROM funds.posting posting
            LEFT JOIN LATERAL jsonb_each(posting.dimensions) dimension ON true
            WHERE posting.journal_id = ?
            ORDER BY posting.posting_id, dimension.key
            """)) {
            statement.setObject(1, journalId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID postingId = rows.getObject("posting_id", UUID.class);
                    DatabaseLine line = postings.get(postingId);
                    if (line == null) {
                        line = new DatabaseLine(
                            rows.getObject("account_id", UUID.class),
                            CurrencyCode.of(rows.getString("currency")),
                            rows.getLong("signed_minor_units"));
                        postings.put(postingId, line);
                    }
                    String dimensionKey = rows.getString("dimension_key");
                    if (dimensionKey != null) {
                        line.dimensions().put(dimensionKey, new DimensionValue(
                            rows.getString("dimension_type"),
                            rows.getString("dimension_value")));
                    }
                }
            }
        }
        var values = new HashMap<LineValue, Integer>();
        for (DatabaseLine line : postings.values()) {
            values.merge(new LineValue(
                line.accountId(),
                line.currency(),
                line.signedMinorUnits(),
                Map.copyOf(line.dimensions())), 1, Integer::sum);
        }
        return Map.copyOf(values);
    }

    private void replayOnce(
        long seed,
        List<GeneratedLedgerOperation> prefix,
        Throwable originalFailure
    ) {
        try {
            TestPostingStack.reset(dataSource);
            Fixture fixture = seedFixture(seed);
            seedFixture(fixture, 0);
            var model = new ReferenceLedgerModel(fixture.accountControls());
            var service = TestPostingStack.create(dataSource, PostingTransactionObserver.noop()).postingService();
            var totals = new RunTotals();
            for (int index = 0; index < prefix.size(); index++) {
                executeAndAssert(seed, index, prefix.get(index), fixture, model, service, totals);
            }
            originalFailure.addSuppressed(new AssertionError(
                "exact prefix replay unexpectedly passed; the original failure may be infrastructure-related"));
        } catch (Throwable replayFailure) {
            originalFailure.addSuppressed(new AssertionError(
                "exact prefix replay reproduced a failure", replayFailure));
        }
    }

    private static AssertionError contextualFailure(
        long seed,
        int operationIndex,
        List<GeneratedLedgerOperation> prefix,
        Throwable failure
    ) {
        var message = new StringBuilder(256)
            .append("state-machine failure; seed=")
            .append(seed)
            .append(", operationIndex=")
            .append(operationIndex)
            .append(", completePrefix=[\n");
        for (int index = 0; index < prefix.size(); index++) {
            message.append("  ").append(index).append(": ").append(prefix.get(index)).append('\n');
        }
        message.append(']');
        return new AssertionError(message.toString(), failure);
    }

    private record Fixture(
        UUID bookId,
        UUID legalEntityId,
        UUID chartVersionId,
        UUID periodId,
        UUID productId,
        UUID productVersionId,
        UUID debitAccountId,
        UUID creditAccountId,
        Map<UUID, String> accountControls
    ) {}

    private record DatabaseCounts(long journals, long postings, long idempotency, long outbox) {}

    private record LineValue(
        UUID accountId,
        CurrencyCode currency,
        long signedMinorUnits,
        Map<String, DimensionValue> dimensions
    ) {}

    private record DimensionValue(String jsonType, String textValue) {}

    private record DatabaseLine(
        UUID accountId,
        CurrencyCode currency,
        long signedMinorUnits,
        Map<String, DimensionValue> dimensions
    ) {
        private DatabaseLine(UUID accountId, CurrencyCode currency, long signedMinorUnits) {
            this(accountId, currency, signedMinorUnits, new LinkedHashMap<>());
        }
    }

    private static final class RunTotals {
        private long journals;
        private long postings;

        private void recordNewJournal(int postingCount) {
            journals++;
            postings += postingCount;
        }
    }
}
