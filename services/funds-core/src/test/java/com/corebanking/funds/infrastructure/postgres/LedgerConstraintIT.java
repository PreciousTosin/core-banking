package com.corebanking.funds.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.util.PSQLException;
import org.junit.jupiter.api.Test;

/**
 * Proves the ledger's database-level invariants directly with SQL on the migrated Quarkus test
 * datasource, bypassing {@code PostingService} and {@code JdbcLedgerRepository}, so a regressed
 * trigger or CHECK is caught even when the Java path still behaves. Covers ACC-01 (per-currency
 * balance and reference consistency at commit), ACC-20 (journal, posting and completed-command
 * immutability) and ACC-24 (a {@code funds_app} session cannot mutate, disable triggers, redefine
 * functions or escalate; an actual {@code funds_proof_reader} session reads only proof columns).
 * Grouped by mechanism:
 * <ul>
 *   <li>{@code journal_balance_deferred} / {@code posting_balance_deferred} (V003): balance is
 *       checked per currency at COMMIT, mirroring {@code JournalValidator};</li>
 *   <li>{@code posting_reference_consistency}, {@code journal_reference_consistency} (V002):
 *       23514 at row insert for currency, book or legal-entity mismatch;</li>
 *   <li>{@code book_identity_immutable} (V003.1), {@code ledger_account_identity_immutable}
 *       (V005) and {@code ledger_account_chart_mapping_frozen} (V005/V006): 55000 for any
 *       identity or classification change, while operational status changes stay allowed;</li>
 *   <li>{@code journal_immutable} / {@code posting_immutable} ({@code reject_ledger_mutation},
 *       V003) and {@code posting_requires_in_progress_command} (V003.2): no update, delete or
 *       late append once a command is COMPLETED, including across concurrent sessions;</li>
 *   <li>{@code completed_idempotency_immutable} (V004): even the owner role cannot rewrite or
 *       delete a stored result.</li>
 * </ul>
 * Most tests run in a transaction that is rolled back; those needing a committed baseline or a
 * second session truncate every funds table before and after.
 */
@QuarkusTest
class LedgerConstraintIT {
    private static final UUID BOOK_ID = uuid(1);
    private static final UUID CHART_VERSION_ID = uuid(2);
    private static final UUID PRODUCT_VERSION_ID = uuid(4);
    private static final UUID CUSTOMER_ACCOUNT_A = uuid(5);
    private static final UUID CUSTOMER_ACCOUNT_B = uuid(6);
    private static final UUID USD_ACCOUNT = uuid(7);
    private static final UUID PERIOD_ID = uuid(8);
    private static final UUID LEGAL_ENTITY_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SECOND_BOOK_ID = uuid(21);
    private static final UUID SECOND_CHART_VERSION_ID = uuid(22);
    private static final UUID SECOND_ACCOUNT_ID = uuid(23);
    private static final UUID COMMAND_ID = uuid(30);
    private static final UUID JOURNAL_ID = uuid(31);
    private static final UUID POSTING_A_ID = uuid(32);
    private static final UUID POSTING_B_ID = uuid(33);
    private static final UUID EVENT_ID = uuid(34);
    private static final String REQUEST_HASH =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String DIFFERENT_REQUEST_HASH =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Inject
    AgroalDataSource dataSource;

    // +100 NGN and -100 USD net to zero overall but not per currency. Both inserts succeed because
    // the balance trigger is a DEFERRED constraint trigger; only COMMIT raises 23514.
    @Test
    void rejectsCrossCurrencyNetZeroJournalAtCommitAfterBothPostingInsertsSucceed() throws Exception {
        inRollbackTransaction(connection -> {
            insertReferenceGraph(connection);
            insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
            insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
            insertPosting(connection, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);
            insertPosting(connection, POSTING_B_ID, JOURNAL_ID, USD_ACCOUNT, "USD", -100, 1);

            assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting"));
            var error = assertThrows(SQLException.class, connection::commit);
            assertEquals("23514", error.getSQLState());
        });
    }

    @Test
    void rejectsPostingCurrencyDifferentFromAccountCurrency() throws Exception {
        inRollbackTransaction(connection -> {
            insertReferenceGraph(connection);
            insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
            insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);

            assertSqlState(
                connection,
                "23514",
                postingInsert(POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "USD", 100, 1));
        });
    }

    @Test
    void rejectsPostingAccountFromAnotherBook() throws Exception {
        inRollbackTransaction(connection -> {
            insertReferenceGraph(connection);
            insertSecondBookAccount(connection);
            insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
            insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);

            assertSqlState(
                connection,
                "23514",
                postingInsert(POSTING_A_ID, JOURNAL_ID, SECOND_ACCOUNT_ID, "NGN", 100, 1));
        });
    }

    @Test
    void rejectsJournalLegalEntityDifferentFromBookLegalEntity() throws Exception {
        inRollbackTransaction(connection -> {
            insertReferenceGraph(connection);
            insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);

            assertSqlState(connection, "23514", journalInsert(
                JOURNAL_ID, COMMAND_ID, uuid(999), BOOK_ID, PERIOD_ID));
        });
    }

    @Test
    void rejectsBookLegalEntityChangeAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> assertSqlState(connection, "55000", """
            UPDATE funds.book
            SET legal_entity_id = '00000000-0000-0000-0000-000000000099'
            WHERE book_id = '%s'
            """.formatted(BOOK_ID)));
    }

    @Test
    void rejectsAccountCurrencyChangeAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> assertSqlState(connection, "55000", """
            UPDATE funds.ledger_account SET currency = 'USD' WHERE account_id = '%s'
            """.formatted(CUSTOMER_ACCOUNT_A)));
    }

    @Test
    void rejectsAccountControlMappingChangeAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> assertSqlState(connection, "55000", """
            UPDATE funds.ledger_account_chart_mapping
            SET control_account_code = 'REWRITTEN-CONTROL'
            WHERE account_id = '%s' AND chart_version_id = '%s'
            """.formatted(CUSTOMER_ACCOUNT_A, CHART_VERSION_ID)));
    }

    @Test
    void rejectsAccountChartVersionChangeAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> {
            insertAlternateChartForMainBook(connection);

            assertSqlState(connection, "55000", """
                UPDATE funds.ledger_account_chart_mapping SET chart_version_id = '%s'
                WHERE account_id = '%s' AND chart_version_id = '%s'
                """.formatted(uuid(24), CUSTOMER_ACCOUNT_A, CHART_VERSION_ID));
        });
    }

    @Test
    void rejectsCoherentAccountBookAndChartMoveAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> {
            insertSecondBookAccount(connection);

            assertSqlState(connection, "55000", """
                UPDATE funds.ledger_account
                SET book_id = '%s'
                WHERE account_id = '%s'
                """.formatted(SECOND_BOOK_ID, CUSTOMER_ACCOUNT_A));
        });
    }

    @Test
    void allowsOperationalAccountStateChangeAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> {
            execute(connection, """
                UPDATE funds.ledger_account
                SET status = 'CLOSED', closed_at = TIMESTAMPTZ '2026-01-31 23:59:59+00'
                WHERE account_id = '%s'
                """.formatted(CUSTOMER_ACCOUNT_A));

            assertEquals(1, queryLong(connection, """
                SELECT count(*) FROM funds.ledger_account
                WHERE account_id = '%s'
                  AND status = 'CLOSED'
                  AND closed_at = TIMESTAMPTZ '2026-01-31 23:59:59+00'
                """.formatted(CUSTOMER_ACCOUNT_A)));
        });
    }

    @Test
    void rejectsCommittedJournalUpdateAsImmutable() throws Exception {
        withCommittedJournal(connection -> assertSqlState(connection, "55000", """
            UPDATE funds.journal SET narration = 'changed' WHERE journal_id = '%s'
            """.formatted(JOURNAL_ID)));
    }

    @Test
    void rejectsCommittedJournalDeleteAsImmutable() throws Exception {
        withCommittedJournal(connection -> assertSqlState(connection, "55000", """
            DELETE FROM funds.journal WHERE journal_id = '%s'
            """.formatted(JOURNAL_ID)));
    }

    @Test
    void rejectsCommittedPostingUpdateAsImmutable() throws Exception {
        withCommittedJournal(connection -> assertSqlState(connection, "55000", """
            UPDATE funds.posting SET dimensions = '{"source":"changed"}'::jsonb
            WHERE posting_id = '%s'
            """.formatted(POSTING_A_ID)));
    }

    @Test
    void rejectsCommittedPostingDeleteAsImmutable() throws Exception {
        withCommittedJournal(connection -> assertSqlState(connection, "55000", """
            DELETE FROM funds.posting WHERE posting_id = '%s'
            """.formatted(POSTING_A_ID)));
    }

    @Test
    void allowsPostingAssemblyOnlyWhileCommandIsInProgress() throws Exception {
        inRollbackTransaction(connection -> {
            insertReferenceGraph(connection);
            insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
            insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
            insertPosting(connection, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);
            insertPosting(connection, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -100, 1);

            assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting"));
        });

        withFinalizedJournal(connection -> assertSqlState(connection, "55000", postingInsert(
            uuid(399), JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 1, 2)));
    }

    /**
     * Sequence: the creator session writes a journal and marks its command COMPLETED without
     * committing; a second session tries to append balanced postings to that journal. The
     * finality trigger's lookup cannot see the uncommitted journal, so it must fail with
     * {@code posting_requires_in_progress_command} (either immediately or after waiting on the
     * creator's lock) instead of passing the guard and appending to a journal that is completed
     * by the time the append commits.
     */
    @Test
    void rejectsAppendWhoseCompletedJournalWasUncommittedAtTriggerLookup() throws Exception {
        truncateAllTables();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<SQLException> append = null;
        try {
            try (var referenceConnection = dataSource.getConnection()) {
                insertReferenceGraph(referenceConnection);
            }
            try (var creator = dataSource.getConnection()) {
                creator.setAutoCommit(false);
                insertInProgressCommand(creator, COMMAND_ID, REQUEST_HASH);
                insertJournal(creator, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
                insertPosting(creator, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);
                insertPosting(creator, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -100, 1);
                execute(creator, """
                    UPDATE funds.idempotency_command
                    SET state = 'COMPLETED', journal_id = '%s',
                        result_json = jsonb_build_object(
                            'journalId', '%s'::text,
                            'journalSequence', (SELECT journal_sequence FROM funds.journal
                                                WHERE journal_id = '%s'),
                            'canonicalHash', '%s'::text),
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(
                        JOURNAL_ID, JOURNAL_ID, JOURNAL_ID, REQUEST_HASH, COMMAND_ID));

                var appenderBackendPid = new AtomicInteger();
                append = executor.submit(() -> appendBalancedPostings(appenderBackendPid));
                awaitAppendAtConstraintBoundary(appenderBackendPid, append);
                creator.commit();

                SQLException failure = append.get(5, TimeUnit.SECONDS);
                assertNotNull(failure, "append must be rejected rather than pass after the FK wait");
                assertEquals("55000", failure.getSQLState());
                assertEquals(
                    "posting_requires_in_progress_command",
                    ((PSQLException) failure).getServerErrorMessage().getConstraint());
            }

            try (var connection = dataSource.getConnection()) {
                assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.journal"));
                assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting"));
                assertEquals(0, queryLong(connection, """
                    SELECT sum(signed_minor_units) FROM funds.posting WHERE journal_id = '%s'
                    """.formatted(JOURNAL_ID)));
            }
        } finally {
            if (append != null) {
                append.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            truncateAllTables();
        }
    }

    /**
     * Sequence: a committed IN_PROGRESS journal exists; session A inserts more postings, whose
     * finality trigger takes {@code FOR UPDATE} on the command row and holds it; session B's
     * completion UPDATE must block on that row (observed in pg_stat_activity) until A commits,
     * then succeed. Completion can therefore never overtake in-flight assembly.
     */
    @Test
    void visibleInProgressAssemblySerializesBeforeCompletion() throws Exception {
        truncateAllTables();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<SQLException> completion = null;
        try {
            try (var setup = dataSource.getConnection()) {
                setup.setAutoCommit(false);
                insertReferenceGraph(setup);
                insertInProgressCommand(setup, COMMAND_ID, REQUEST_HASH);
                insertJournal(setup, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
                insertPosting(setup, uuid(395), JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 1, 1);
                insertPosting(setup, uuid(396), JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -1, 1);
                setup.commit();
            }
            try (var assembly = dataSource.getConnection()) {
                assembly.setAutoCommit(false);
                insertPosting(assembly, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 2);
                insertPosting(assembly, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -100, 2);

                var completionBackendPid = new AtomicInteger();
                completion = executor.submit(() -> completeJournal(completionBackendPid));
                awaitBackendLock(completionBackendPid, completion);
                assertTrue(!completion.isDone(), "completion must wait for in-progress assembly");
                assembly.commit();

                assertNull(completion.get(5, TimeUnit.SECONDS));
            }

            try (var connection = dataSource.getConnection()) {
                assertEquals("COMPLETED", queryString(connection, """
                    SELECT state FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(COMMAND_ID)));
                assertEquals(4, queryLong(connection, "SELECT count(*) FROM funds.posting"));
            }
        } finally {
            if (completion != null) {
                completion.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            truncateAllTables();
        }
    }

    @Test
    void rejectsDuplicateCommandIdWithDifferentRequestHash() throws Exception {
        inRollbackTransaction(connection -> {
            insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);

            assertSqlState(connection, "23505", idempotencyInsert(
                COMMAND_ID, DIFFERENT_REQUEST_HASH, "IN_PROGRESS", null, null, null));
        });
    }

    @Test
    void rejectsDuplicateAccountSequence() throws Exception {
        inRollbackTransaction(connection -> {
            insertReferenceGraph(connection);
            insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
            insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
            insertPosting(connection, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);

            assertSqlState(
                connection,
                "23505",
                postingInsert(POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", -100, 1));
        });
    }

    @Test
    void rejectsCompletedIdempotencyWithoutJournalResultAndCompletionTimestamp() throws Exception {
        inRollbackTransaction(connection -> assertSqlState(connection, "23514", idempotencyInsert(
            COMMAND_ID, REQUEST_HASH, "COMPLETED", null, null, null)));
    }

    @Test
    void commitsJournalPostingsBalancesControlProjectionIdempotencyAndOutboxAtomically() throws Exception {
        truncateAllTables();
        try {
            long journalSequence;
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                insertReferenceGraph(connection);
                insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
                insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
                journalSequence = queryLong(connection, """
                    SELECT journal_sequence FROM funds.journal WHERE journal_id = '%s'
                    """.formatted(JOURNAL_ID));
                insertPosting(connection, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 500, 1);
                insertPosting(connection, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -500, 1);
                execute(connection, """
                    INSERT INTO funds.materialised_balance
                        (account_id, signed_posting_total, latest_account_sequence, version)
                    VALUES
                        ('%s', 500, 1, 1),
                        ('%s', -500, 1, 1)
                    """.formatted(CUSTOMER_ACCOUNT_A, CUSTOMER_ACCOUNT_B));
                execute(connection, """
                    INSERT INTO funds.control_account_projection
                        (book_id, control_account_code, currency, signed_posting_total, latest_journal_sequence)
                    VALUES ('%s', 'CUSTOMER-DEPOSITS', 'NGN', 0, %d)
                    """.formatted(BOOK_ID, journalSequence));
                execute(connection, """
                    INSERT INTO funds.outbox_event
                        (event_id, aggregate_id, aggregate_version, event_type, schema_version, payload, created_at)
                    VALUES
                        ('%s', '%s', %d, 'JournalPosted', 1,
                         '{"journalId":"%s"}'::jsonb, TIMESTAMPTZ '2026-01-15 10:00:01+00')
                    """.formatted(EVENT_ID, JOURNAL_ID, journalSequence, JOURNAL_ID));
                execute(connection, """
                    UPDATE funds.idempotency_command
                    SET state = 'COMPLETED', journal_id = '%s',
                        result_json = jsonb_build_object(
                            'journalId', '%s'::text,
                            'journalSequence', (SELECT journal_sequence FROM funds.journal
                                                WHERE journal_id = '%s'),
                            'canonicalHash', '%s'::text),
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(
                        JOURNAL_ID, JOURNAL_ID, JOURNAL_ID, REQUEST_HASH, COMMAND_ID));
                connection.commit();
            }

            try (var connection = dataSource.getConnection()) {
                assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.journal"));
                assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting"));
                assertEquals("0", queryString(connection, """
                    SELECT sum(signed_minor_units::numeric)::text FROM funds.posting
                    WHERE journal_id = '%s' GROUP BY currency
                    """.formatted(JOURNAL_ID)));
                assertEquals(500, queryLong(connection, """
                    SELECT signed_posting_total FROM funds.materialised_balance
                    WHERE account_id = '%s'
                    """.formatted(CUSTOMER_ACCOUNT_A)));
                assertEquals(-500, queryLong(connection, """
                    SELECT signed_posting_total FROM funds.materialised_balance
                    WHERE account_id = '%s'
                    """.formatted(CUSTOMER_ACCOUNT_B)));
                assertEquals(0, queryLong(connection, """
                    SELECT signed_posting_total FROM funds.control_account_projection
                    WHERE book_id = '%s' AND control_account_code = 'CUSTOMER-DEPOSITS' AND currency = 'NGN'
                    """.formatted(BOOK_ID)));
                assertEquals(journalSequence, queryLong(connection, """
                    SELECT latest_journal_sequence FROM funds.control_account_projection
                    WHERE book_id = '%s' AND control_account_code = 'CUSTOMER-DEPOSITS' AND currency = 'NGN'
                    """.formatted(BOOK_ID)));
                assertEquals("COMPLETED", queryString(connection, """
                    SELECT state FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(COMMAND_ID)));
                assertEquals(REQUEST_HASH, queryString(connection, """
                    SELECT result_json ->> 'canonicalHash'
                    FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(COMMAND_ID)));
                assertEquals(JOURNAL_ID.toString(), queryString(connection, """
                    SELECT journal_id::text FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(COMMAND_ID)));
                assertEquals(1, queryLong(connection, """
                    SELECT count(*) FROM funds.outbox_event
                    WHERE aggregate_id = '%s' AND event_type = 'JournalPosted' AND published_at IS NULL
                    """.formatted(JOURNAL_ID)));
            }
        } finally {
            truncateAllTables();
        }
    }

    @Test
    void runsLedgerConstraintsOnPostgreSql18Point6() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var version = queryString(connection, "SHOW server_version");
            System.out.println("LedgerConstraintIT PostgreSQL server_version=" + version);
            assertTrue(version.startsWith("18.6"), () -> "unexpected PostgreSQL version: " + version);
        }
    }

    /**
     * ACC-24 in one session. Sequence: as {@code funds_app}, perform the complete legitimate
     * posting write set (command, journal, postings, balances, projection, outbox, completion) to
     * prove the grants suffice; still as {@code funds_app}, every bypass is 42501 (sequence
     * setval/read, outbox publish columns, journal/posting UPDATE/DELETE, DISABLE TRIGGER, DDL,
     * redefining the guard function, closing a period, SET ROLE to the owner) while rewriting a
     * completed result is 55000 from the trigger; as {@code funds_migrator}, the owner is still
     * blocked from touching completed results but may delete an IN_PROGRESS row; as an actual
     * {@code funds_proof_reader} session, only the proof columns are readable.
     */
    @Test
    void applicationRoleCannotBypassLedgerControls() throws Exception {
        truncateAllTables();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertReferenceGraph(connection);
                execute(connection, "SET ROLE funds_app");

                assertEquals("funds_app", queryString(connection, "SELECT current_user"));
                assertEquals(3, queryLong(connection, "SELECT count(*) FROM funds.ledger_account"));
                assertEquals(0, queryLong(connection,
                    "SELECT count(*) FROM funds.lock_book_chart_for_posting(NULL, NULL)"));
                assertEquals(0, queryLong(connection, """
                    SELECT count(*)
                    FROM funds.lock_account_mapping_for_posting(
                        'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid,
                        'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
                    """));
                execute(connection, """
                    INSERT INTO funds.idempotency_command
                        (command_id, request_hash, state, created_at)
                    VALUES ('%s', '%s', 'IN_PROGRESS', TIMESTAMPTZ '2026-01-15 10:00:00+00')
                    """.formatted(COMMAND_ID, REQUEST_HASH));
                insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
                insertPosting(connection, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);
                insertPosting(connection, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -100, 1);
                execute(connection, """
                    INSERT INTO funds.materialised_balance
                        (account_id, signed_posting_total, latest_account_sequence, version)
                    VALUES ('%s', 0, 0, 0), ('%s', 0, 0, 0)
                    """.formatted(CUSTOMER_ACCOUNT_A, CUSTOMER_ACCOUNT_B));
                execute(connection, """
                    UPDATE funds.materialised_balance
                    SET signed_posting_total = 100, latest_account_sequence = 1, version = 1
                    WHERE account_id = '%s'
                    """.formatted(CUSTOMER_ACCOUNT_A));
                execute(connection, """
                    INSERT INTO funds.control_account_projection
                        (book_id, control_account_code, currency, signed_posting_total,
                         latest_journal_sequence)
                    VALUES ('%s', 'CUSTOMER-DEPOSITS', 'NGN', 0, 0)
                    """.formatted(BOOK_ID));
                execute(connection, """
                    UPDATE funds.control_account_projection
                    SET signed_posting_total = 0, latest_journal_sequence =
                        (SELECT journal_sequence FROM funds.journal WHERE journal_id = '%s')
                    WHERE book_id = '%s' AND control_account_code = 'CUSTOMER-DEPOSITS'
                      AND currency = 'NGN'
                    """.formatted(JOURNAL_ID, BOOK_ID));
                execute(connection, """
                    INSERT INTO funds.outbox_event
                        (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                         payload, created_at)
                    SELECT '%s', journal_id, journal_sequence, 'JournalPosted', 1,
                           '{}'::jsonb, TIMESTAMPTZ '2026-01-15 10:00:00+00'
                    FROM funds.journal WHERE journal_id = '%s'
                    """.formatted(EVENT_ID, JOURNAL_ID));
                execute(connection, """
                    UPDATE funds.idempotency_command
                    SET state = 'COMPLETED', journal_id = '%s',
                        result_json = jsonb_build_object(
                            'journalId', '%s'::text,
                            'journalSequence', (SELECT journal_sequence FROM funds.journal
                                                WHERE journal_id = '%s'),
                            'canonicalHash', '%s'::text),
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s' AND state = 'IN_PROGRESS'
                    """.formatted(
                        JOURNAL_ID, JOURNAL_ID, JOURNAL_ID, REQUEST_HASH, COMMAND_ID));
                // The deferred balance and result-consistency triggers would otherwise run only
                // at a commit this rolled-back test never performs.
                execute(connection, "SET CONSTRAINTS ALL IMMEDIATE");

                long allocatedSequence = queryLong(connection,
                    "SELECT nextval('funds.journal_journal_sequence_seq')");
                assertEquals(allocatedSequence, queryLong(connection,
                    "SELECT currval('funds.journal_journal_sequence_seq')"));
                assertSqlState(connection, "42501", """
                    SELECT setval('funds.journal_journal_sequence_seq', 900, false)
                    """);
                assertSqlState(connection, "42501",
                    "SELECT last_value FROM funds.journal_journal_sequence_seq");

                assertSqlState(connection, "42501", """
                    INSERT INTO funds.outbox_event
                        (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                         payload, created_at, published_at)
                    VALUES ('%s', '%s', 901, 'ForbiddenPublishedAt', 1, '{}'::jsonb,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(uuid(902), JOURNAL_ID));
                assertSqlState(connection, "42501", """
                    INSERT INTO funds.outbox_event
                        (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                         payload, created_at, publish_attempts)
                    VALUES ('%s', '%s', 902, 'ForbiddenPublishAttempts', 1, '{}'::jsonb,
                            CURRENT_TIMESTAMP, 1)
                    """.formatted(uuid(903), JOURNAL_ID));

                assertSqlState(connection, "42501", "UPDATE funds.journal SET narration = 'tampered'");
                assertSqlState(connection, "42501", "DELETE FROM funds.journal");
                assertSqlState(connection, "42501", "UPDATE funds.posting SET signed_minor_units = 1");
                assertSqlState(connection, "42501", "DELETE FROM funds.posting");
                assertSqlState(connection, "42501", "ALTER TABLE funds.posting DISABLE TRIGGER ALL");
                assertSqlState(connection, "42501", "ALTER TABLE funds.posting ADD COLUMN bypass text");
                assertSqlState(connection, "42501", """
                    CREATE OR REPLACE FUNCTION funds.reject_ledger_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'
                    """);
                assertSqlState(connection, "42501", """
                    ALTER FUNCTION funds.reject_ledger_mutation() RENAME TO bypass_ledger_mutation
                    """);
                assertSqlState(connection, "42501", """
                    UPDATE funds.accounting_period SET status = 'CLOSED'
                    WHERE period_id = '%s'
                    """.formatted(PERIOD_ID));

                Savepoint beforeCompletedMutation = connection.setSavepoint();
                try {
                    var completedMutation = assertThrows(SQLException.class, () -> execute(connection, """
                        UPDATE funds.idempotency_command SET result_json = '{"tampered":true}'::jsonb
                        WHERE command_id = '%s'
                        """.formatted(COMMAND_ID)));
                    assertEquals("55000", completedMutation.getSQLState());
                    assertEquals(
                        "completed_idempotency_immutable",
                        ((PSQLException) completedMutation).getServerErrorMessage().getConstraint());
                } finally {
                    connection.rollback(beforeCompletedMutation);
                }

                assertEquals(0, queryLong(connection, """
                    SELECT has_function_privilege(
                        'funds_app', 'funds.reject_ledger_mutation()', 'EXECUTE')::integer
                    """));

                // SET ROLE alone proves nothing here: the test login may assume any role. Only
                // a session actually authorised as funds_app shows the escalation is denied.
                execute(connection, "RESET ROLE");
                execute(connection, "SET SESSION AUTHORIZATION funds_app");
                assertSqlState(connection, "42501", "SET ROLE funds_migrator");
                execute(connection, "RESET SESSION AUTHORIZATION");

                execute(connection, "SET ROLE funds_migrator");
                assertConstraintViolation(connection, "55000", "completed_idempotency_immutable", """
                    UPDATE funds.idempotency_command SET result_json = '{"ownerTamper":true}'::jsonb
                    WHERE command_id = '%s'
                    """.formatted(COMMAND_ID));
                assertConstraintViolation(connection, "55000", "completed_idempotency_immutable", """
                    DELETE FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(COMMAND_ID));
                execute(connection, """
                    INSERT INTO funds.idempotency_command
                        (command_id, request_hash, state, created_at)
                    VALUES ('%s', '%s', 'IN_PROGRESS', CURRENT_TIMESTAMP)
                    """.formatted(uuid(904), REQUEST_HASH));
                execute(connection, """
                    DELETE FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(uuid(904)));
                assertEquals(0, queryLong(connection, """
                    SELECT count(*) FROM funds.idempotency_command WHERE command_id = '%s'
                    """.formatted(uuid(904))));
                execute(connection, "RESET ROLE");

                execute(connection, "SET SESSION AUTHORIZATION funds_proof_reader");
                assertEquals(0, queryLong(connection, """
                    SELECT coalesce(sum(posting.signed_minor_units::numeric), 0)
                    FROM funds.posting posting
                    JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                    JOIN funds.ledger_account_chart_mapping mapping
                      ON mapping.account_id = posting.account_id
                     AND mapping.book_id = journal.book_id
                     AND mapping.chart_version_id = journal.chart_version_id
                     AND mapping.account_currency = posting.currency
                    """));
                assertEquals(1, queryLong(connection, """
                    SELECT count(book_id) FROM funds.control_account_projection
                    """));
                assertSqlState(connection, "42501", "SELECT count(*) FROM funds.materialised_balance");
                assertSqlState(connection, "42501",
                    "SELECT normalised_value FROM funds.account_identifier");
                assertSqlState(connection, "42501", "SELECT policy_json FROM funds.product_version");
                assertSqlState(connection, "42501",
                    "SELECT result_json FROM funds.idempotency_command");
                assertSqlState(connection, "42501", "SELECT payload FROM funds.outbox_event");
                assertSqlState(connection, "42501", idempotencyInsert(
                    uuid(901), REQUEST_HASH, "IN_PROGRESS", null, null, null));
                assertSqlState(connection, "42501", "SET ROLE funds_migrator");
            } finally {
                try {
                    execute(connection, "RESET SESSION AUTHORIZATION");
                } finally {
                    try {
                        execute(connection, "RESET ROLE");
                    } finally {
                        connection.rollback();
                    }
                }
            }
        } finally {
            truncateAllTables();
        }
    }

    private void inRollbackTransaction(SqlConsumer action) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                action.accept(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    // Commits a balanced journal whose command is still IN_PROGRESS, then runs the mutation in a
    // rolled-back transaction so the immutability triggers are exercised against committed rows.
    private void withCommittedJournal(SqlConsumer mutation) throws Exception {
        truncateAllTables();
        try {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                insertReferenceGraph(connection);
                insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
                insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
                insertPosting(connection, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);
                insertPosting(connection, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -100, 1);
                connection.commit();
            }

            inRollbackTransaction(mutation);
        } finally {
            truncateAllTables();
        }
    }

    // Same as withCommittedJournal but the command is COMPLETED, so the finality guard applies.
    private void withFinalizedJournal(SqlConsumer mutation) throws Exception {
        truncateAllTables();
        try {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                insertReferenceGraph(connection);
                insertInProgressCommand(connection, COMMAND_ID, REQUEST_HASH);
                insertJournal(connection, JOURNAL_ID, COMMAND_ID, LEGAL_ENTITY_ID, BOOK_ID, PERIOD_ID);
                insertPosting(connection, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);
                insertPosting(connection, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -100, 1);
                execute(connection, """
                    UPDATE funds.idempotency_command
                    SET state = 'COMPLETED', journal_id = '%s',
                        result_json = jsonb_build_object(
                            'journalId', '%s'::text,
                            'journalSequence', (SELECT journal_sequence FROM funds.journal
                                                WHERE journal_id = '%s'),
                            'canonicalHash', '%s'::text),
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(
                        JOURNAL_ID, JOURNAL_ID, JOURNAL_ID, REQUEST_HASH, COMMAND_ID));
                connection.commit();
            }

            inRollbackTransaction(mutation);
        } finally {
            truncateAllTables();
        }
    }

    private SQLException appendBalancedPostings(AtomicInteger backendPid) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            backendPid.set(Math.toIntExact(queryLong(connection, "SELECT pg_backend_pid()")));
            try {
                execute(connection, """
                    INSERT INTO funds.posting
                        (posting_id, journal_id, account_id, currency, signed_minor_units,
                         account_sequence, dimensions)
                    VALUES
                        ('%s', '%s', '%s', 'NGN', 1, 2, '{}'::jsonb),
                        ('%s', '%s', '%s', 'NGN', -1, 2, '{}'::jsonb)
                    """.formatted(
                        uuid(399), JOURNAL_ID, CUSTOMER_ACCOUNT_A,
                        uuid(400), JOURNAL_ID, CUSTOMER_ACCOUNT_B));
                connection.commit();
                return null;
            } catch (SQLException failure) {
                connection.rollback();
                return failure;
            }
        } catch (SQLException failure) {
            return failure;
        }
    }

    private SQLException completeJournal(AtomicInteger backendPid) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            backendPid.set(Math.toIntExact(queryLong(connection, "SELECT pg_backend_pid()")));
            try {
                execute(connection, """
                    UPDATE funds.idempotency_command
                    SET state = 'COMPLETED', journal_id = '%s',
                        result_json = jsonb_build_object(
                            'journalId', '%s'::text,
                            'journalSequence', (SELECT journal_sequence FROM funds.journal
                                                WHERE journal_id = '%s'),
                            'canonicalHash', '%s'::text),
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(
                        JOURNAL_ID, JOURNAL_ID, JOURNAL_ID, REQUEST_HASH, COMMAND_ID));
                connection.commit();
                return null;
            } catch (SQLException failure) {
                connection.rollback();
                return failure;
            }
        } catch (SQLException failure) {
            return failure;
        }
    }

    // Synchronises on the real database state instead of a sleep: returns once the appender has
    // finished (immediate rejection) or its backend shows a Lock wait in pg_stat_activity.
    private void awaitAppendAtConstraintBoundary(AtomicInteger backendPid, Future<?> append)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (append.isDone()) {
                return;
            }
            int pid = backendPid.get();
            if (pid != 0) {
                try (var connection = dataSource.getConnection()) {
                    if (queryLong(connection, """
                        SELECT count(*) FROM pg_stat_activity
                        WHERE pid = %d AND wait_event_type = 'Lock'
                        """.formatted(pid)) == 1) {
                        return;
                    }
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("append neither rejected nor blocked at its foreign-key boundary");
    }

    // Unlike awaitAppendAtConstraintBoundary, finishing early is a failure: the operation must
    // have blocked on the command row.
    private void awaitBackendLock(AtomicInteger backendPid, Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (operation.isDone()) {
                throw new AssertionError("operation completed without command-row serialization");
            }
            int pid = backendPid.get();
            if (pid != 0) {
                try (var connection = dataSource.getConnection()) {
                    if (queryLong(connection, """
                        SELECT count(*) FROM pg_stat_activity
                        WHERE pid = %d AND wait_event_type = 'Lock'
                        """.formatted(pid)) == 1) {
                        return;
                    }
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("operation did not block on command-row serialization");
    }

    // Chart inserted DRAFT, accounts mapped, then activated: V005 requires a mapping for every
    // open account before activation and rejects ungoverned onboarding onto an ACTIVE chart.
    private static void insertReferenceGraph(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
            VALUES
                ('00000000-0000-0000-0000-000000000001',
                 '00000000-0000-0000-0000-000000000010', 'NGN', 'Africa/Lagos', 'NG', 1)
            """);
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES
                ('00000000-0000-0000-0000-000000000002',
                 '00000000-0000-0000-0000-000000000001', 1, 'DRAFT', 'APP-CHART-001')
            """);
        execute(connection, """
            INSERT INTO funds.accounting_period
                (period_id, book_id, business_date_from, business_date_to, status)
            VALUES
                ('00000000-0000-0000-0000-000000000008',
                 '00000000-0000-0000-0000-000000000001',
                 DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
            """);
        execute(connection, """
            INSERT INTO funds.product_definition
                (product_id, product_code)
            VALUES
                ('00000000-0000-0000-0000-000000000003',
                 'SAVINGS-STANDARD')
            """);
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json, product_kind, finance_principle)
            VALUES
                ('00000000-0000-0000-0000-000000000004',
                 '00000000-0000-0000-0000-000000000003', 1,
                 TIMESTAMPTZ '2026-01-01 00:00:00+00', 'APP-2026-001', '%s', '{}'::jsonb,
                 'SAVINGS', 'CONVENTIONAL')
            """.formatted(REQUEST_HASH));
        insertLedgerAccount(connection,
            CUSTOMER_ACCOUNT_A, BOOK_ID, CHART_VERSION_ID, "CUSTOMER-A", "CUSTOMER",
            PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN", "CUSTOMER-DEPOSITS");
        insertLedgerAccount(connection,
            CUSTOMER_ACCOUNT_B, BOOK_ID, CHART_VERSION_ID, "CUSTOMER-B", "CUSTOMER",
            PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN", "CUSTOMER-DEPOSITS");
        insertLedgerAccount(connection,
            USD_ACCOUNT, BOOK_ID, CHART_VERSION_ID, "USD-INTERNAL", "INTERNAL",
            null, "ASSET", "DEBIT", "USD", "FX-CONTROL");
        execute(connection, """
            UPDATE funds.chart_version
            SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
            WHERE chart_version_id = '%s'
            """.formatted(CHART_VERSION_ID));
    }

    private static void insertSecondBookAccount(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
            VALUES
                ('%s', '00000000-0000-0000-0000-000000000024',
                 'NGN', 'Africa/Lagos', 'NG', 1)
            """.formatted(SECOND_BOOK_ID));
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES ('%s', '%s', 1, 'DRAFT', 'APP-SECOND-CHART')
            """.formatted(SECOND_CHART_VERSION_ID, SECOND_BOOK_ID));
        insertLedgerAccount(connection,
            SECOND_ACCOUNT_ID, SECOND_BOOK_ID, SECOND_CHART_VERSION_ID, "SECOND-BOOK", "INTERNAL",
            null, "ASSET", "DEBIT", "NGN", "SECOND-CONTROL");
        execute(connection, """
            UPDATE funds.chart_version
            SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
            WHERE chart_version_id = '%s'
            """.formatted(SECOND_CHART_VERSION_ID));
    }

    private static void insertAlternateChartForMainBook(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, activated_at, approval_reference)
            VALUES ('%s', '%s', 2, 'DRAFT', NULL, 'APP-ALTERNATE-CHART')
            """.formatted(uuid(24), BOOK_ID));
    }

    private static void insertInProgressCommand(Connection connection, UUID commandId, String requestHash)
        throws SQLException {
        execute(connection, idempotencyInsert(
            commandId, requestHash, "IN_PROGRESS", null, null, null));
    }

    private static void insertJournal(
        Connection connection,
        UUID journalId,
        UUID commandId,
        UUID legalEntityId,
        UUID bookId,
        UUID periodId
    ) throws SQLException {
        execute(connection, journalInsert(journalId, commandId, legalEntityId, bookId, periodId));
    }

    private static void insertPosting(
        Connection connection,
        UUID postingId,
        UUID journalId,
        UUID accountId,
        String currency,
        long signedMinorUnits,
        long accountSequence
    ) throws SQLException {
        execute(connection, postingInsert(
            postingId, journalId, accountId, currency, signedMinorUnits, accountSequence));
    }

    private static String idempotencyInsert(
        UUID commandId,
        String requestHash,
        String state,
        UUID journalId,
        String resultJson,
        String completedAt
    ) {
        var journalValue = journalId == null ? "NULL" : "'" + journalId + "'";
        var resultValue = resultJson == null ? "NULL" : resultJson;
        var completedValue = completedAt == null ? "NULL" : completedAt;
        return """
            INSERT INTO funds.idempotency_command
                (command_id, request_hash, state, journal_id, result_json, created_at, completed_at)
            VALUES
                ('%s', '%s', '%s', %s, %s, TIMESTAMPTZ '2026-01-15 10:00:00+00', %s)
            """.formatted(commandId, requestHash, state, journalValue, resultValue, completedValue);
    }

    // canonical_hash reuses REQUEST_HASH so a COMPLETED result built from the same constant
    // satisfies completed_command_result_consistency (V005); the value itself is arbitrary.
    private static String journalInsert(
        UUID journalId,
        UUID commandId,
        UUID legalEntityId,
        UUID bookId,
        UUID periodId
    ) {
        return """
            INSERT INTO funds.journal
                (journal_id, command_id, correlation_id, business_transaction_id, legal_entity_id,
                 book_id, chart_version_id, period_id, transaction_type, narration, booking_time, value_date,
                 policy_version, canonical_hash)
            VALUES
                ('%s', '%s', '00000000-0000-0000-0000-000000000035',
                 '00000000-0000-0000-0000-000000000036', '%s', '%s', '%s', '%s',
                 'TRANSFER', 'Task 5 integration fixture',
                 TIMESTAMPTZ '2026-01-15 10:00:00+00', DATE '2026-01-15', 1, '%s')
            """.formatted(journalId, commandId, legalEntityId, bookId, CHART_VERSION_ID,
                periodId, REQUEST_HASH);
    }

    private static String postingInsert(
        UUID postingId,
        UUID journalId,
        UUID accountId,
        String currency,
        long signedMinorUnits,
        long accountSequence
    ) {
        return """
            INSERT INTO funds.posting
                (posting_id, journal_id, account_id, currency, signed_minor_units,
                 account_sequence, dimensions)
            VALUES ('%s', '%s', '%s', '%s', %d, %d, '{"source":"task-5-test"}'::jsonb)
            """.formatted(
                postingId, journalId, accountId, currency, signedMinorUnits, accountSequence);
    }

    private static void insertLedgerAccount(
        Connection connection,
        UUID accountId,
        UUID bookId,
        UUID chartVersionId,
        String accountCode,
        String accountScope,
        UUID productVersionId,
        String accountClass,
        String normalBalance,
        String currency,
        String controlAccountCode
    ) throws SQLException {
        var productValue = productVersionId == null ? "NULL" : "'" + productVersionId + "'";
        execute(connection, """
            INSERT INTO funds.ledger_account
                (account_id, book_id, account_scope, product_version_id, currency, status, created_at)
            VALUES
                ('%s', '%s', '%s', %s, '%s',
                 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """.formatted(
                accountId, bookId, accountScope, productValue, currency));
        execute(connection, """
            INSERT INTO funds.ledger_account_chart_mapping
                (account_id, book_id, chart_version_id, account_code, account_currency,
                 account_class,
                 normal_balance, control_account_code, account_role)
            VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s')
            """.formatted(accountId, bookId, chartVersionId, accountCode, currency, accountClass,
                normalBalance, controlAccountCode, accountScope));
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
                    funds.ledger_account_chart_mapping,
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

    // The savepoint keeps one expected failure from aborting the enclosing transaction, so a test
    // can probe several rejections in sequence.
    private static void assertSqlState(Connection connection, String expectedSqlState, String sql)
        throws SQLException {
        Savepoint beforeViolation = connection.setSavepoint();
        try {
            var error = assertThrows(SQLException.class, () -> execute(connection, sql));
            assertEquals(expectedSqlState, error.getSQLState());
        } finally {
            connection.rollback(beforeViolation);
        }
    }

    private static void assertConstraintViolation(
        Connection connection,
        String expectedSqlState,
        String expectedConstraint,
        String sql
    ) throws SQLException {
        Savepoint beforeViolation = connection.setSavepoint();
        try {
            var error = assertThrows(PSQLException.class, () -> execute(connection, sql));
            assertEquals(expectedSqlState, error.getSQLState());
            assertEquals(expectedConstraint, error.getServerErrorMessage().getConstraint());
        } finally {
            connection.rollback(beforeViolation);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    @FunctionalInterface
    private interface SqlConsumer {
        void accept(Connection connection) throws Exception;
    }
}
