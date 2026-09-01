package com.corebanking.funds.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class MigrationUpgradeIT {
    private static final UUID BOOK = uuid(1);
    private static final UUID CHART = uuid(3);
    private static final UUID PRODUCT = uuid(5);
    private static final UUID PRODUCT_VERSION = uuid(6);
    private static final UUID CUSTOMER = uuid(8);
    private static final UUID JOURNAL = uuid(10);

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
                    "1000:ASSET,2000:LIABILITY",
                    queryString(connection, """
                        SELECT string_agg(
                            mapping.account_code || ':' || mapping.account_class,
                            ',' ORDER BY mapping.account_code)
                        FROM funds.ledger_account_chart_mapping mapping
                        WHERE mapping.chart_version_id = ?
                        """, CHART));
                assertEquals(CHART.toString(), queryString(connection, """
                    SELECT chart_version_id::text FROM funds.journal WHERE journal_id = ?
                    """, JOURNAL));

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
        }
    }

    private static Flyway flyway(PostgreSQLContainer postgres, MigrationVersion target) {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .target(target)
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
                        'CUSTOMER-DEPOSITS', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, uuid(7), BOOK, CHART, CUSTOMER, BOOK, CHART, PRODUCT_VERSION);
            execute(connection, """
                INSERT INTO funds.idempotency_command
                    (command_id, request_hash, state, created_at)
                VALUES (?, ?, 'IN_PROGRESS', TIMESTAMPTZ '2026-01-15 10:00:00+00')
                """, uuid(9), "b".repeat(64));
            execute(connection, """
                INSERT INTO funds.journal
                    (journal_id, command_id, correlation_id, business_transaction_id,
                     legal_entity_id, book_id, period_id, transaction_type, narration,
                     booking_time, value_date, policy_version, canonical_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRANSFER', 'V004 historical journal',
                        TIMESTAMPTZ '2026-01-15 10:00:00+00', DATE '2026-01-15', 1, ?)
                """, JOURNAL, uuid(9), uuid(11), uuid(12), uuid(2), BOOK, uuid(4),
                "d".repeat(64));
            execute(connection, """
                INSERT INTO funds.posting
                    (posting_id, journal_id, account_id, currency, signed_minor_units,
                     account_sequence, dimensions)
                VALUES (?, ?, ?, 'NGN', 100, 1, '{"legacy":"asset"}'::jsonb),
                       (?, ?, ?, 'NGN', -100, 1, '{"legacy":"customer"}'::jsonb)
                """, uuid(13), JOURNAL, uuid(7), uuid(14), JOURNAL, CUSTOMER);
            execute(connection, """
                UPDATE funds.idempotency_command
                SET state = 'COMPLETED', journal_id = ?, completed_at = now(),
                    result_json = jsonb_build_object(
                        'journalId', ?::text,
                        'journalSequence', (SELECT journal_sequence FROM funds.journal WHERE journal_id = ?),
                        'canonicalHash', ?::text)
                WHERE command_id = ?
                """, JOURNAL, JOURNAL, JOURNAL, "d".repeat(64), uuid(9));
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
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

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
