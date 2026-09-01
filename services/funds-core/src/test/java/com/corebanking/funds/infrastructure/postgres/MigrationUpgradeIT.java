package com.corebanking.funds.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.application.CanonicalCommandHasher;
import com.corebanking.funds.application.CanonicalJournalHasher;
import com.corebanking.funds.application.JournalValidator;
import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingService;
import com.corebanking.funds.application.PostingTransactionObserver;
import com.corebanking.funds.application.ReversalService;
import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.ReversalRequest;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class MigrationUpgradeIT {
    private static final UUID BOOK = uuid(1);
    private static final UUID CHART = uuid(3);
    private static final UUID PRODUCT = uuid(5);
    private static final UUID PRODUCT_VERSION = uuid(6);
    private static final UUID CUSTOMER = uuid(8);
    private static final UUID PROVIDER_USD = uuid(15);
    private static final UUID CUSTOMER_USD = uuid(16);
    private static final UUID JOURNAL = uuid(10);
    private static final UUID NEXT_PERIOD = uuid(21);
    private static final UUID REVERSAL_COMMAND = uuid(22);
    private static final UUID LEGACY_ORIGINAL_COMMAND = uuid(30);
    private static final UUID LEGACY_ORIGINAL_JOURNAL = uuid(31);
    private static final UUID LEGACY_REVERSAL_COMMAND = uuid(32);
    private static final UUID LEGACY_REVERSAL_JOURNAL = uuid(33);

    @Test
    void v005BackfillsHistoricalProductClassificationChartMappingAndJournalPin()
        throws Exception {
        try (var postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-bookworm"))
            .withDatabaseName("acceptance_upgrade")
            .withUsername("acceptance_upgrade")
            .withPassword("acceptance_upgrade")) {
            postgres.start();
            Flyway throughV004 = flyway(postgres, MigrationVersion.fromVersion("004"));
            throughV004.migrate();
            assertEquals(
                MigrationVersion.fromVersion("004"),
                throughV004.info().current().getVersion());

            try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                seedV004History(connection);
            }

            Flyway throughV005 = flyway(postgres, MigrationVersion.fromVersion("005"));
            throughV005.migrate();
            assertEquals(
                MigrationVersion.fromVersion("005"),
                throughV005.info().current().getVersion());

            try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                assertEquals("SAVINGS/CONVENTIONAL", queryString(connection, """
                    SELECT version.product_kind || '/' || version.finance_principle
                    FROM funds.ledger_account account
                    JOIN funds.product_version version
                      ON version.product_version_id = account.product_version_id
                    WHERE account.account_id = ?
                    """, CUSTOMER));
                assertEquals(0, queryLong(connection, """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'funds' AND table_name = 'product_definition'
                      AND column_name IN ('product_kind', 'finance_principle')
                    """));
                assertEquals(
                    "1000/NGN:ASSET,1000/USD:ASSET,2000/NGN:LIABILITY,2000/USD:LIABILITY",
                    queryString(connection, """
                        SELECT string_agg(
                            mapping.account_code || '/' || mapping.account_currency
                                || ':' || mapping.account_class,
                            ',' ORDER BY mapping.account_code, mapping.account_currency)
                        FROM funds.ledger_account_chart_mapping mapping
                        WHERE mapping.chart_version_id = ?
                        """, CHART));
                assertEquals(CHART.toString(), queryString(connection, """
                    SELECT chart_version_id::text FROM funds.journal WHERE journal_id = ?
                    """, JOURNAL));
                assertEquals("V004_OPAQUE/V004_V1", queryString(connection, """
                    SELECT command.request_hash_scheme || '/' || journal.canonical_hash_scheme
                    FROM funds.idempotency_command command
                    JOIN funds.journal journal ON journal.command_id = command.command_id
                    WHERE command.command_id = ?
                    """, uuid(9)));

                execute(connection, """
                    INSERT INTO funds.product_version
                        (product_version_id, product_id, version, effective_from,
                         approval_reference, policy_hash, policy_json,
                         product_kind, finance_principle)
                    VALUES (?, ?, 2, TIMESTAMPTZ '2026-02-01 00:00:00+00',
                            'APP-UPGRADE-2', ?, '{}'::jsonb, 'CURRENT', 'NON_INTEREST')
                    """, uuid(20), PRODUCT, "c".repeat(64));
                assertEquals("SAVINGS/CONVENTIONAL", queryString(connection, """
                    SELECT version.product_kind || '/' || version.finance_principle
                    FROM funds.ledger_account account
                    JOIN funds.product_version version
                      ON version.product_version_id = account.product_version_id
                    WHERE account.account_id = ?
                    """, CUSTOMER));
            }

            proveMigratedReplayAndReversal(postgres);
        }
    }

    @Test
    void v005RejectsLegacyDimensionsThatCannotRoundTripAsTypedStringFacts()
        throws Exception {
        try (var postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-bookworm"))
            .withDatabaseName("acceptance_upgrade_invalid_dimensions")
            .withUsername("acceptance_upgrade")
            .withPassword("acceptance_upgrade")) {
            postgres.start();
            flyway(postgres, MigrationVersion.fromVersion("004")).migrate();
            try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                seedV004History(connection);
                insertLegacyNonStringDimensionJournal(connection);
            }

            FlywayException failure = assertThrows(FlywayException.class,
                () -> quietFlyway(postgres, MigrationVersion.fromVersion("005")).migrate());
            assertTrue(containsMessage(failure, "posting_dimensions_string_values_check"),
                () -> "unexpected migration failure: " + failure);
        }
    }

    private static void proveMigratedReplayAndReversal(PostgreSQLContainer postgres)
        throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var commandHasher = new CanonicalCommandHasher();
        var journalHasher = new CanonicalJournalHasher();
        var repository = new JdbcLedgerRepository(
            new JournalValidator(), journalHasher, PostingTransactionObserver.noop());
        var postingService = new PostingService(
            dataSource, repository, new PostgresRetryPolicy((commandId, attempt) -> {}));
        var reversalService = new ReversalService(dataSource, postingService);

        try (Connection connection = dataSource.getConnection()) {
            execute(connection, """
                UPDATE funds.accounting_period SET status = 'CLOSED' WHERE period_id = ?
                """, uuid(4));
            execute(connection, """
                UPDATE funds.book SET accounting_policy_version = 2 WHERE book_id = ?
                """, BOOK);
            execute(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES (?, ?, DATE '2026-02-01', DATE '2026-02-28', 'OPEN')
                """, NEXT_PERIOD, BOOK);
        }

        JournalDraft authentic = v004Journal();
        var replay = postingService.post(new PostingCommand(
            authentic.commandId(), commandHasher.postingV2(authentic), authentic));
        assertEquals(JOURNAL, replay.journalId());
        assertEquals(journalHasher.v004Sha256(authentic), replay.canonicalHash());

        JournalDraft mutated = withAmounts(authentic, 101, -101);
        assertThrows(IdempotencyConflictException.class, () -> postingService.post(
            new PostingCommand(
                mutated.commandId(), commandHasher.postingV2(mutated), mutated)));
        JournalDraft changedChart = withChartVersion(authentic, uuid(9_999));
        assertThrows(IdempotencyConflictException.class, () -> postingService.post(
            new PostingCommand(
                changedChart.commandId(), commandHasher.postingV2(changedChart), changedChart)));

        ReversalRequest legacyUnsigned = legacyReversalRequest("0".repeat(64),
            "Legacy V004 exact reversal");
        ReversalRequest legacyReplay = withHash(
            legacyUnsigned, commandHasher.reversalV2(legacyUnsigned));
        var legacyResult = reversalService.reverse(legacyReplay);
        assertEquals(LEGACY_REVERSAL_JOURNAL, legacyResult.journalId());
        assertEquals(
            new CanonicalJournalHasher().v004Sha256(legacyReversalJournal()),
            legacyResult.canonicalHash());

        ReversalRequest mutatedLegacy = legacyReversalRequest(
            "0".repeat(64), "Mutated legacy reversal reason");
        ReversalRequest recomputedMutation = withHash(
            mutatedLegacy, commandHasher.reversalV2(mutatedLegacy));
        assertThrows(IdempotencyConflictException.class,
            () -> reversalService.reverse(recomputedMutation));

        ReversalRequest unsigned = new ReversalRequest(
            REVERSAL_COMMAND,
            "0".repeat(64),
            JOURNAL,
            uuid(23),
            uuid(24),
            NEXT_PERIOD,
            Instant.parse("2026-02-10T10:00:00Z"),
            LocalDate.of(2026, 2, 10),
            "Exact reversal of migrated V004 transfer");
        ReversalRequest request = new ReversalRequest(
            unsigned.commandId(),
            commandHasher.reversalV2(unsigned),
            unsigned.originalJournalId(),
            unsigned.correlationId(),
            unsigned.businessTransactionId(),
            unsigned.currentPeriodId(),
            unsigned.bookingTime(),
            unsigned.valueDate(),
            unsigned.reason());
        var correction = reversalService.reverse(request);

        try (Connection connection = dataSource.getConnection()) {
            execute(connection, """
                UPDATE funds.chart_version
                SET status = 'RETIRED', retired_at = TIMESTAMPTZ '2026-02-11 00:00:00+00'
                WHERE chart_version_id = ?
                """, CHART);
        }
        assertEquals(legacyResult, reversalService.reverse(legacyReplay));

        try (Connection connection = dataSource.getConnection()) {
            assertEquals("TYPED_V2/V2", queryString(connection, """
                SELECT command.request_hash_scheme || '/' || journal.canonical_hash_scheme
                FROM funds.idempotency_command command
                JOIN funds.journal journal ON journal.command_id = command.command_id
                WHERE command.command_id = ?
                """, REVERSAL_COMMAND));
            assertEquals(0, queryLong(connection, """
                SELECT count(*) FROM (
                    (SELECT account_id, currency, signed_minor_units::numeric, dimensions
                     FROM funds.posting WHERE journal_id = ?
                     EXCEPT ALL
                     SELECT account_id, currency, -(signed_minor_units::numeric), dimensions
                     FROM funds.posting WHERE journal_id = ?)
                    UNION ALL
                    (SELECT account_id, currency, -(signed_minor_units::numeric), dimensions
                     FROM funds.posting WHERE journal_id = ?
                     EXCEPT ALL
                     SELECT account_id, currency, signed_minor_units::numeric, dimensions
                     FROM funds.posting WHERE journal_id = ?)
                ) mismatch
                """, JOURNAL, correction.journalId(), JOURNAL, correction.journalId()));
            assertEquals(4, queryLong(connection, "SELECT count(*) FROM funds.journal"));
        }
    }

    private static Flyway flyway(PostgreSQLContainer postgres, MigrationVersion target) {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .target(target)
            .load();
    }

    private static Flyway quietFlyway(
        PostgreSQLContainer postgres,
        MigrationVersion target
    ) {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .target(target)
            // The rollback is the assertion in this negative fixture, not a build error.
            .loggers("org.flywaydb.core.internal.logging.buffered.BufferedLogCreator")
            .load();
    }

    private static void seedV004History(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                INSERT INTO funds.book
                    (book_id, legal_entity_id, functional_currency, timezone,
                     calendar_code, accounting_policy_version)
                VALUES (?, ?, 'NGN', 'Africa/Lagos', 'NG', 1)
                """, BOOK, uuid(2));
            execute(connection, """
                INSERT INTO funds.chart_version
                    (chart_version_id, book_id, version, status, activated_at)
                VALUES (?, ?, 1, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, CHART, BOOK);
            execute(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
                """, uuid(4), BOOK);
            execute(connection, """
                INSERT INTO funds.product_definition
                    (product_id, product_code, product_kind, finance_principle)
                VALUES (?, 'LEGACY-SAVINGS', 'SAVINGS', 'CONVENTIONAL')
                """, PRODUCT);
            execute(connection, """
                INSERT INTO funds.product_version
                    (product_version_id, product_id, version, effective_from,
                     approval_reference, policy_hash, policy_json)
                VALUES (?, ?, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00',
                        'APP-UPGRADE-1', ?, '{}'::jsonb)
                """, PRODUCT_VERSION, PRODUCT, "a".repeat(64));
            execute(connection, """
                INSERT INTO funds.ledger_account
                    (account_id, book_id, chart_version_id, account_code, account_scope,
                     product_version_id, account_class, normal_balance, currency,
                     control_account_code, status, created_at)
                VALUES (?, ?, ?, '1000', 'INTERNAL', NULL, 'ASSET', 'DEBIT', 'NGN',
                        'PROVIDER-FLOAT', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
                       (?, ?, ?, '2000', 'CUSTOMER', ?, 'LIABILITY', 'CREDIT', 'NGN',
                        'CUSTOMER-DEPOSITS', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
                       (?, ?, ?, '1000', 'INTERNAL', NULL, 'ASSET', 'DEBIT', 'USD',
                        'PROVIDER-FLOAT', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
                       (?, ?, ?, '2000', 'CUSTOMER', ?, 'LIABILITY', 'CREDIT', 'USD',
                        'CUSTOMER-DEPOSITS', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, uuid(7), BOOK, CHART, CUSTOMER, BOOK, CHART, PRODUCT_VERSION,
                PROVIDER_USD, BOOK, CHART, CUSTOMER_USD, BOOK, CHART, PRODUCT_VERSION);
            String v004Hash = new CanonicalJournalHasher().v004Sha256(v004Journal());
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', TIMESTAMPTZ '2026-01-15 10:00:00+00')
                """, uuid(9), v004Hash);
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, period_id, transaction_type, narration,
                     booking_time, value_date, policy_version, canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRANSFER', 'V004 historical journal',
                        TIMESTAMPTZ '2026-01-15 10:00:00+00', DATE '2026-01-15', 1, ?)
                """, JOURNAL, uuid(9), uuid(11), uuid(12), uuid(2), BOOK, uuid(4),
                v004Hash);
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', 100, 1, '{"legacy":"asset"}'::jsonb),
                       (?, ?, ?, 'NGN', -100, 1, '{"legacy":"customer"}'::jsonb),
                       (?, ?, ?, 'USD', 200, 1, '{"legacy":"asset"}'::jsonb),
                       (?, ?, ?, 'USD', -200, 1, '{"legacy":"customer"}'::jsonb)
                """, uuid(13), JOURNAL, uuid(7), uuid(14), JOURNAL, CUSTOMER,
                uuid(17), JOURNAL, PROVIDER_USD, uuid(18), JOURNAL, CUSTOMER_USD);
            String legacyOriginalHash = new CanonicalJournalHasher()
                .v004Sha256(legacyOriginalJournal());
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', TIMESTAMPTZ '2026-01-16 10:00:00+00')
                """, LEGACY_ORIGINAL_COMMAND, legacyOriginalHash);
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, period_id, transaction_type, narration,
                     booking_time, value_date, policy_version, canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRANSFER', 'V004 reversal source',
                        TIMESTAMPTZ '2026-01-16 10:00:00+00', DATE '2026-01-16', 1, ?)
                """, LEGACY_ORIGINAL_JOURNAL, LEGACY_ORIGINAL_COMMAND, uuid(34), uuid(35),
                uuid(2), BOOK, uuid(4), legacyOriginalHash);
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', 50, 2,
                            '{"legacy":"reversal-source-asset"}'::jsonb),
                       (?, ?, ?, 'NGN', -50, 2,
                            '{"legacy":"reversal-source-customer"}'::jsonb)
                """, uuid(36), LEGACY_ORIGINAL_JOURNAL, uuid(7),
                uuid(37), LEGACY_ORIGINAL_JOURNAL, CUSTOMER);
            execute(connection, """
                UPDATE funds.idempotency_command
                SET state = 'COMPLETED', journal_id = ?, completed_at = now(),
                    result_json = jsonb_build_object(
                        'journalId', ?::text,
                        'journalSequence', (SELECT journal_sequence FROM funds.journal
                                            WHERE journal_id = ?),
                        'canonicalHash', ?::text)
                WHERE command_id = ?
                """, LEGACY_ORIGINAL_JOURNAL, LEGACY_ORIGINAL_JOURNAL,
                LEGACY_ORIGINAL_JOURNAL, legacyOriginalHash, LEGACY_ORIGINAL_COMMAND);

            String legacyReversalHash = new CanonicalJournalHasher()
                .v004Sha256(legacyReversalJournal());
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', TIMESTAMPTZ '2026-01-17 10:00:00+00')
                """, LEGACY_REVERSAL_COMMAND, "d".repeat(64));
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, period_id, transaction_type, narration,
                     booking_time, value_date, reversal_of_journal_id, policy_version,
                     canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REVERSAL', 'Legacy V004 exact reversal',
                        TIMESTAMPTZ '2026-01-17 10:00:00+00', DATE '2026-01-17', ?, 1, ?)
                """, LEGACY_REVERSAL_JOURNAL, LEGACY_REVERSAL_COMMAND, uuid(38), uuid(39),
                uuid(2), BOOK, uuid(4), LEGACY_ORIGINAL_JOURNAL, legacyReversalHash);
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', -50, 3,
                            '{"legacy":"reversal-source-asset"}'::jsonb),
                       (?, ?, ?, 'NGN', 50, 3,
                            '{"legacy":"reversal-source-customer"}'::jsonb)
                """, uuid(40), LEGACY_REVERSAL_JOURNAL, uuid(7),
                uuid(41), LEGACY_REVERSAL_JOURNAL, CUSTOMER);
            execute(connection, """
                UPDATE funds.idempotency_command
                SET state = 'COMPLETED', journal_id = ?, completed_at = now(),
                    result_json = jsonb_build_object(
                        'journalId', ?::text,
                        'journalSequence', (SELECT journal_sequence FROM funds.journal
                                            WHERE journal_id = ?),
                        'canonicalHash', ?::text)
                WHERE command_id = ?
                """, LEGACY_REVERSAL_JOURNAL, LEGACY_REVERSAL_JOURNAL,
                LEGACY_REVERSAL_JOURNAL, legacyReversalHash, LEGACY_REVERSAL_COMMAND);
            execute(connection, """
                INSERT INTO funds.materialised_balance
                    (account_id, signed_posting_total, latest_account_sequence, version)
                VALUES (?, 100, 3, 3), (?, -100, 3, 3),
                       (?, 200, 1, 1), (?, -200, 1, 1)
                """, uuid(7), CUSTOMER, PROVIDER_USD, CUSTOMER_USD);
            execute(connection, """
                INSERT INTO funds.control_account_projection
                    (book_id, control_account_code, currency, signed_posting_total,
                     latest_journal_sequence)
                VALUES (?, 'PROVIDER-FLOAT', 'NGN', 100,
                        (SELECT journal_sequence FROM funds.journal WHERE journal_id = ?)),
                       (?, 'CUSTOMER-DEPOSITS', 'NGN', -100,
                        (SELECT journal_sequence FROM funds.journal WHERE journal_id = ?)),
                       (?, 'PROVIDER-FLOAT', 'USD', 200,
                        (SELECT journal_sequence FROM funds.journal WHERE journal_id = ?)),
                       (?, 'CUSTOMER-DEPOSITS', 'USD', -200,
                        (SELECT journal_sequence FROM funds.journal WHERE journal_id = ?))
                """, BOOK, LEGACY_REVERSAL_JOURNAL, BOOK, LEGACY_REVERSAL_JOURNAL,
                BOOK, JOURNAL, BOOK, JOURNAL);
            execute(connection, """
                UPDATE funds.idempotency_command
                SET state = 'COMPLETED', journal_id = ?, completed_at = now(),
                    result_json = jsonb_build_object(
                        'journalId', ?::text,
                        'journalSequence', (SELECT journal_sequence FROM funds.journal WHERE journal_id = ?),
                        'canonicalHash', ?::text)
                WHERE command_id = ?
                """, JOURNAL, JOURNAL, JOURNAL, v004Hash, uuid(9));
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static JournalDraft v004Journal() {
        return new JournalDraft(
            JOURNAL,
            uuid(9),
            uuid(11),
            uuid(12),
            uuid(2),
            BOOK,
            CHART,
            uuid(4),
            "TRANSFER",
            "V004 historical journal",
            Instant.parse("2026-01-15T10:00:00Z"),
            LocalDate.of(2026, 1, 15),
            null,
            1,
            List.of(
                new PostingLine(uuid(13), uuid(7), CurrencyCode.of("NGN"), 100, 1,
                    Map.of("legacy", "asset")),
                new PostingLine(uuid(14), CUSTOMER, CurrencyCode.of("NGN"), -100, 1,
                    Map.of("legacy", "customer")),
                new PostingLine(uuid(17), PROVIDER_USD, CurrencyCode.of("USD"), 200, 1,
                    Map.of("legacy", "asset")),
                new PostingLine(uuid(18), CUSTOMER_USD, CurrencyCode.of("USD"), -200, 1,
                    Map.of("legacy", "customer"))));
    }

    private static JournalDraft legacyOriginalJournal() {
        return new JournalDraft(
            LEGACY_ORIGINAL_JOURNAL,
            LEGACY_ORIGINAL_COMMAND,
            uuid(34),
            uuid(35),
            uuid(2),
            BOOK,
            CHART,
            uuid(4),
            "TRANSFER",
            "V004 reversal source",
            Instant.parse("2026-01-16T10:00:00Z"),
            LocalDate.of(2026, 1, 16),
            null,
            1,
            List.of(
                new PostingLine(uuid(36), uuid(7), CurrencyCode.of("NGN"), 50, 2,
                    Map.of("legacy", "reversal-source-asset")),
                new PostingLine(uuid(37), CUSTOMER, CurrencyCode.of("NGN"), -50, 2,
                    Map.of("legacy", "reversal-source-customer"))));
    }

    private static JournalDraft legacyReversalJournal() {
        return new JournalDraft(
            LEGACY_REVERSAL_JOURNAL,
            LEGACY_REVERSAL_COMMAND,
            uuid(38),
            uuid(39),
            uuid(2),
            BOOK,
            CHART,
            uuid(4),
            "REVERSAL",
            "Legacy V004 exact reversal",
            Instant.parse("2026-01-17T10:00:00Z"),
            LocalDate.of(2026, 1, 17),
            LEGACY_ORIGINAL_JOURNAL,
            1,
            List.of(
                new PostingLine(uuid(40), uuid(7), CurrencyCode.of("NGN"), -50, 3,
                    Map.of("legacy", "reversal-source-asset")),
                new PostingLine(uuid(41), CUSTOMER, CurrencyCode.of("NGN"), 50, 3,
                    Map.of("legacy", "reversal-source-customer"))));
    }

    private static ReversalRequest legacyReversalRequest(String hash, String reason) {
        return new ReversalRequest(
            LEGACY_REVERSAL_COMMAND,
            hash,
            LEGACY_ORIGINAL_JOURNAL,
            uuid(38),
            uuid(39),
            uuid(4),
            Instant.parse("2026-01-17T10:00:00Z"),
            LocalDate.of(2026, 1, 17),
            reason);
    }

    private static ReversalRequest withHash(ReversalRequest source, String hash) {
        return new ReversalRequest(
            source.commandId(), hash, source.originalJournalId(), source.correlationId(),
            source.businessTransactionId(), source.currentPeriodId(), source.bookingTime(),
            source.valueDate(), source.reason());
    }

    private static JournalDraft withAmounts(JournalDraft source, long debit, long credit) {
        List<PostingLine> postings = source.postings().stream()
            .map(posting -> {
                if (posting.currency().equals(CurrencyCode.of("NGN"))) {
                    return new PostingLine(
                        posting.postingId(), posting.accountId(), posting.currency(),
                        posting.signedMinorUnits() > 0 ? debit : credit,
                        posting.accountSequence(), posting.dimensions());
                }
                return posting;
            })
            .toList();
        return new JournalDraft(
            source.journalId(), source.commandId(), source.correlationId(),
            source.businessTransactionId(), source.legalEntityId(), source.bookId(),
            source.chartVersionId(), source.periodId(), source.transactionType(),
            source.narration(), source.bookingTime(), source.valueDate(),
            source.reversalOfJournalId(), source.policyVersion(), postings);
    }

    private static JournalDraft withChartVersion(JournalDraft source, UUID chartVersionId) {
        return new JournalDraft(
            source.journalId(), source.commandId(), source.correlationId(),
            source.businessTransactionId(), source.legalEntityId(), source.bookId(),
            chartVersionId, source.periodId(), source.transactionType(), source.narration(),
            source.bookingTime(), source.valueDate(), source.reversalOfJournalId(),
            source.policyVersion(), source.postings());
    }

    private static void insertLegacyNonStringDimensionJournal(Connection connection)
        throws SQLException {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', TIMESTAMPTZ '2026-01-18 10:00:00+00')
                """, uuid(50), "f".repeat(64));
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, period_id, transaction_type, narration,
                     booking_time, value_date, policy_version, canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRANSFER', 'non-string V004 dimensions',
                        TIMESTAMPTZ '2026-01-18 10:00:00+00', DATE '2026-01-18', 1, ?)
                """, uuid(51), uuid(50), uuid(52), uuid(53), uuid(2), BOOK, uuid(4),
                "f".repeat(64));
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', 1, 4, '{"numeric":1}'::jsonb),
                       (?, ?, ?, 'NGN', -1, 4, '{"numeric":1}'::jsonb)
                """, uuid(54), uuid(51), uuid(7), uuid(55), uuid(51), CUSTOMER);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static boolean containsMessage(Throwable failure, String fragment) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void execute(Connection connection, String sql, Object... values)
        throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static String queryString(Connection connection, String sql, Object... values)
        throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long queryLong(Connection connection, String sql, Object... values)
        throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
