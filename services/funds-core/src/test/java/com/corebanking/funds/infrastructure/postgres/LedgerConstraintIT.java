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
            UPDATE funds.ledger_account
            SET control_account_code = 'REWRITTEN-CONTROL'
            WHERE account_id = '%s'
            """.formatted(CUSTOMER_ACCOUNT_A)));
    }

    @Test
    void rejectsAccountChartVersionChangeAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> {
            insertAlternateChartForMainBook(connection);

            assertSqlState(connection, "55000", """
                UPDATE funds.ledger_account SET chart_version_id = '%s' WHERE account_id = '%s'
                """.formatted(uuid(24), CUSTOMER_ACCOUNT_A));
        });
    }

    @Test
    void rejectsCoherentAccountBookAndChartMoveAfterJournalCommit() throws Exception {
        withCommittedJournal(connection -> {
            insertSecondBookAccount(connection);

            assertSqlState(connection, "55000", """
                UPDATE funds.ledger_account
                SET book_id = '%s', chart_version_id = '%s'
                WHERE account_id = '%s'
                """.formatted(SECOND_BOOK_ID, SECOND_CHART_VERSION_ID, CUSTOMER_ACCOUNT_A));
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
                        result_json = '{"journalId":"%s"}'::jsonb,
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(JOURNAL_ID, JOURNAL_ID, COMMAND_ID));

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
                setup.commit();
            }
            try (var assembly = dataSource.getConnection()) {
                assembly.setAutoCommit(false);
                insertPosting(assembly, POSTING_A_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_A, "NGN", 100, 1);
                insertPosting(assembly, POSTING_B_ID, JOURNAL_ID, CUSTOMER_ACCOUNT_B, "NGN", -100, 1);

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
                assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.posting"));
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
                        result_json = '{"status":"BOOKED"}'::jsonb,
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(JOURNAL_ID, COMMAND_ID));
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
                assertEquals("BOOKED", queryString(connection, """
                    SELECT result_json ->> 'status' FROM funds.idempotency_command WHERE command_id = '%s'
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
                    SET state = 'COMPLETED', journal_id = '%s', result_json = '{}'::jsonb,
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s' AND state = 'IN_PROGRESS'
                    """.formatted(JOURNAL_ID, COMMAND_ID));
                execute(connection, "SET CONSTRAINTS ALL IMMEDIATE");

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

                execute(connection, "RESET ROLE");
                execute(connection, "SET SESSION AUTHORIZATION funds_app");
                assertSqlState(connection, "42501", "SET ROLE funds_migrator");
                execute(connection, "RESET SESSION AUTHORIZATION");

                execute(connection, "SET SESSION AUTHORIZATION funds_proof_reader");
                assertEquals(0, queryLong(connection, """
                    SELECT coalesce(sum(posting.signed_minor_units::numeric), 0)
                    FROM funds.posting posting
                    JOIN funds.ledger_account account ON account.account_id = posting.account_id
                    """));
                assertEquals(2, queryLong(connection, "SELECT count(*) FROM funds.materialised_balance"));
                assertEquals(1, queryLong(connection, "SELECT count(*) FROM funds.control_account_projection"));
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
                        result_json = '{"journalId":"%s"}'::jsonb,
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(JOURNAL_ID, JOURNAL_ID, COMMAND_ID));
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
                        result_json = '{"journalId":"%s"}'::jsonb,
                        completed_at = TIMESTAMPTZ '2026-01-15 10:00:01+00'
                    WHERE command_id = '%s'
                    """.formatted(JOURNAL_ID, JOURNAL_ID, COMMAND_ID));
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
                (chart_version_id, book_id, version, status, activated_at)
            VALUES
                ('00000000-0000-0000-0000-000000000002',
                 '00000000-0000-0000-0000-000000000001', 1, 'ACTIVE',
                 TIMESTAMPTZ '2026-01-01 00:00:00+00')
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
                (product_id, product_code, product_kind, finance_principle)
            VALUES
                ('00000000-0000-0000-0000-000000000003',
                 'SAVINGS-STANDARD', 'SAVINGS', 'CONVENTIONAL')
            """);
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json)
            VALUES
                ('00000000-0000-0000-0000-000000000004',
                 '00000000-0000-0000-0000-000000000003', 1,
                 TIMESTAMPTZ '2026-01-01 00:00:00+00', 'APP-2026-001', '%s', '{}'::jsonb)
            """.formatted(REQUEST_HASH));
        execute(connection, ledgerAccountInsert(
            CUSTOMER_ACCOUNT_A, BOOK_ID, CHART_VERSION_ID, "CUSTOMER-A", "CUSTOMER",
            PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN", "CUSTOMER-DEPOSITS"));
        execute(connection, ledgerAccountInsert(
            CUSTOMER_ACCOUNT_B, BOOK_ID, CHART_VERSION_ID, "CUSTOMER-B", "CUSTOMER",
            PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN", "CUSTOMER-DEPOSITS"));
        execute(connection, ledgerAccountInsert(
            USD_ACCOUNT, BOOK_ID, CHART_VERSION_ID, "USD-INTERNAL", "INTERNAL",
            null, "ASSET", "DEBIT", "USD", "FX-CONTROL"));
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
                (chart_version_id, book_id, version, status, activated_at)
            VALUES ('%s', '%s', 1, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """.formatted(SECOND_CHART_VERSION_ID, SECOND_BOOK_ID));
        execute(connection, ledgerAccountInsert(
            SECOND_ACCOUNT_ID, SECOND_BOOK_ID, SECOND_CHART_VERSION_ID, "SECOND-BOOK", "INTERNAL",
            null, "ASSET", "DEBIT", "NGN", "SECOND-CONTROL"));
    }

    private static void insertAlternateChartForMainBook(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, activated_at)
            VALUES ('%s', '%s', 2, 'ACTIVE', TIMESTAMPTZ '2026-02-01 00:00:00+00')
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
                 book_id, period_id, transaction_type, narration, booking_time, value_date,
                 policy_version, canonical_hash)
            VALUES
                ('%s', '%s', '00000000-0000-0000-0000-000000000035',
                 '00000000-0000-0000-0000-000000000036', '%s', '%s', '%s',
                 'TRANSFER', 'Task 5 integration fixture',
                 TIMESTAMPTZ '2026-01-15 10:00:00+00', DATE '2026-01-15', 1, '%s')
            """.formatted(journalId, commandId, legalEntityId, bookId, periodId, REQUEST_HASH);
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

    private static String ledgerAccountInsert(
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
    ) {
        var productValue = productVersionId == null ? "NULL" : "'" + productVersionId + "'";
        return """
            INSERT INTO funds.ledger_account
                (account_id, book_id, chart_version_id, account_code, account_scope,
                 product_version_id, account_class, normal_balance, currency,
                 control_account_code, status, created_at)
            VALUES
                ('%s', '%s', '%s', '%s', '%s', %s, '%s', '%s', '%s', '%s',
                 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """.formatted(
                accountId, bookId, chartVersionId, accountCode, accountScope, productValue,
                accountClass, normalBalance, currency, controlAccountCode);
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
