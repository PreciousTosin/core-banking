package com.corebanking.funds.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MigrationIT {
    private static final UUID BOOK_ID = uuid(1);
    private static final UUID CHART_VERSION_ID = uuid(2);
    private static final UUID PRODUCT_VERSION_ID = uuid(4);
    private static final UUID CUSTOMER_ACCOUNT_A = uuid(5);
    private static final UUID CUSTOMER_ACCOUNT_B = uuid(6);
    private static final UUID CONTROL_ACCOUNT = uuid(7);
    private static final UUID SECOND_PRODUCT_VERSION_ID = uuid(20);
    private static final UUID SECOND_BOOK_ID = uuid(21);
    private static final UUID SECOND_CHART_VERSION_ID = uuid(22);
    private static final String EVIDENCE_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Inject
    AgroalDataSource dataSource;

    @Test
    void createsEveryAccountingReferenceTable() throws Exception {
        inTransaction(connection -> {
            var actual = new HashSet<String>();
            try (var statement = connection.prepareStatement("""
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'funds'
                    """);
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
            }

            assertTrue(actual.containsAll(Set.of(
                "book",
                "chart_version",
                "accounting_period",
                "product_definition",
                "product_version",
                "ledger_account",
                "account_identifier")));
        });
    }

    @Test
    void runsOnPostgreSql18Point6() throws Exception {
        inTransaction(connection -> {
            var version = queryString(connection, "SHOW server_version");
            System.out.println("MigrationIT PostgreSQL server_version=" + version);
            assertTrue(version.startsWith("18.6"), () -> "unexpected PostgreSQL version: " + version);
        });
    }

    @Test
    void rejectsLedgerCurrencyLongerThanThreeCharacters() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, ledgerInsert(
                uuid(100), BOOK_ID, CHART_VERSION_ID, "INVALID-CURRENCY", "CUSTOMER",
                PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGNN"));
        });
    }

    @Test
    void rejectsUnknownLedgerNormalBalance() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, ledgerInsert(
                uuid(101), BOOK_ID, CHART_VERSION_ID, "INVALID-NORMAL", "CUSTOMER",
                PRODUCT_VERSION_ID, "LIABILITY", "SIDEWAYS", "NGN"));
        });
    }

    @Test
    void rejectsLedgerAccountWhoseBookDoesNotExist() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, ledgerInsert(
                uuid(102), uuid(999), CHART_VERSION_ID, "MISSING-BOOK", "CUSTOMER",
                PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN"));
        });
    }

    @Test
    void rejectsChartVersionFromAnotherBook() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            insertSecondBookAndChart(connection);

            assertSqlRejected(connection, ledgerInsert(
                uuid(105), BOOK_ID, SECOND_CHART_VERSION_ID, "WRONG-CHART-BOOK", "CUSTOMER",
                PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN"));
        });
    }

    @Test
    void acceptsCustomerAccountWithProductVersionBinding() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);

            assertEquals(
                PRODUCT_VERSION_ID,
                queryUuid(
                    connection,
                    "SELECT product_version_id FROM funds.ledger_account WHERE account_id = ?",
                    CUSTOMER_ACCOUNT_A)
            );
        });
    }

    @Test
    void rejectsCustomerAccountWithoutProductVersionBinding() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, ledgerInsert(
                uuid(103), BOOK_ID, CHART_VERSION_ID, "NO-PRODUCT", "CUSTOMER",
                null, "LIABILITY", "CREDIT", "NGN"));
        });
    }

    @Test
    void rejectsProductVersionBindingForNonCustomerAccount() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, ledgerInsert(
                uuid(104), BOOK_ID, CHART_VERSION_ID, "CONTROL-WITH-PRODUCT", "CONTROL",
                PRODUCT_VERSION_ID, "ASSET", "DEBIT", "NGN"));
        });
    }

    @Test
    void rejectsProductVersionUpdate() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);

            assertSqlRejected(connection, """
                UPDATE funds.product_version SET approval_reference = 'REPLACED'
                WHERE product_version_id = '%s'
                """.formatted(PRODUCT_VERSION_ID));
            assertEquals("APP-2026-001", queryString(connection, """
                SELECT approval_reference FROM funds.product_version
                WHERE product_version_id = '%s'
                """.formatted(PRODUCT_VERSION_ID)));
        });
    }

    @Test
    void rejectsUnreferencedProductVersionDelete() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            insertSecondProductVersion(connection);

            assertSqlRejected(connection, """
                DELETE FROM funds.product_version WHERE product_version_id = '%s'
                """.formatted(SECOND_PRODUCT_VERSION_ID));
            assertEquals(1, queryInt(connection, """
                SELECT count(*) FROM funds.product_version WHERE product_version_id = '%s'
                """.formatted(SECOND_PRODUCT_VERSION_ID)));
        });
    }

    @Test
    void rejectsCustomerProductVersionBindingReplacement() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            insertSecondProductVersion(connection);

            assertSqlRejected(connection, """
                UPDATE funds.ledger_account SET product_version_id = '%s'
                WHERE account_id = '%s'
                """.formatted(SECOND_PRODUCT_VERSION_ID, CUSTOMER_ACCOUNT_A));
            assertEquals(
                PRODUCT_VERSION_ID,
                queryUuid(
                    connection,
                    "SELECT product_version_id FROM funds.ledger_account WHERE account_id = ?",
                    CUSTOMER_ACCOUNT_A));
        });
    }

    @Test
    void rejectsOverlappingAccountingPeriodsForOneBook() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES
                    ('00000000-0000-0000-0000-000000000120', '00000000-0000-0000-0000-000000000001',
                     DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
                """);

            assertSqlRejected(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES
                    ('00000000-0000-0000-0000-000000000121', '00000000-0000-0000-0000-000000000001',
                     DATE '2026-01-31', DATE '2026-02-28', 'OPEN')
                """);
        });
    }

    @Test
    void acceptsNonOverlappingAccountingPeriodsForOneBook() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES
                    ('00000000-0000-0000-0000-000000000122', '00000000-0000-0000-0000-000000000001',
                     DATE '2026-01-01', DATE '2026-01-31', 'OPEN'),
                    ('00000000-0000-0000-0000-000000000123', '00000000-0000-0000-0000-000000000001',
                     DATE '2026-02-01', DATE '2026-02-28', 'OPEN')
                """);

            assertEquals(2, queryInt(connection, "SELECT count(*) FROM funds.accounting_period"));
        });
    }

    @Test
    void sqlNubanValidatorAcceptsPublishedAndSyntheticFixtures() throws Exception {
        inTransaction(connection -> {
            assertTrue(queryBoolean(connection, "SELECT funds.is_valid_nuban('000011', '0000014579')"));
            assertTrue(queryBoolean(connection, "SELECT funds.is_valid_nuban('000000', '0000000017')"));
        });
    }

    @Test
    void sqlNubanValidatorRejectsMutatedCheckDigit() throws Exception {
        inTransaction(connection ->
            assertFalse(queryBoolean(connection, "SELECT funds.is_valid_nuban('000011', '0000014578')")));
    }

    @Test
    void rejectsBadNubanCheckDigitAtTableBoundary() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, identifierInsert(
                uuid(130), CUSTOMER_ACCOUNT_A, "NUBAN", "0000014578", "000011", null,
                "INTERNAL", "ACTIVE", false));
        });
    }

    @Test
    void rejectsSameActiveIdentifierScopeForTwoAccounts() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(131), CUSTOMER_ACCOUNT_A, "NUBAN", "0000014579", "000011", null,
                "INTERNAL", "ACTIVE", false));

            assertSqlRejected(connection, identifierInsert(
                uuid(132), CUSTOMER_ACCOUNT_B, "NUBAN", "0000014579", "000011", null,
                "INTERNAL", "ACTIVE", false));
        });
    }

    @Test
    void rejectsSecondActivePrimaryNubanForOneAccount() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(133), CUSTOMER_ACCOUNT_A, "NUBAN", "0000014579", "000011", null,
                "INTERNAL", "ACTIVE", true));

            assertSqlRejected(connection, identifierInsert(
                uuid(134), CUSTOMER_ACCOUNT_A, "NUBAN", "0000000017", "000000", null,
                "SIMULATOR_ONLY", "ACTIVE", true));
        });
    }

    @Test
    void acceptsSameProviderAliasFromDifferentProvidersOnOneAccount() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(135), CUSTOMER_ACCOUNT_A, "PROVIDER_VIRTUAL_ACCOUNT", "shared-alias", null, uuid(300),
                "EXTERNAL", "ACTIVE", false));
            execute(connection, identifierInsert(
                uuid(136), CUSTOMER_ACCOUNT_A, "PROVIDER_VIRTUAL_ACCOUNT", "shared-alias", null, uuid(301),
                "EXTERNAL", "ACTIVE", false));

            assertEquals(2, queryInt(connection, """
                SELECT count(*) FROM funds.account_identifier WHERE normalised_value = 'shared-alias'
                """));
        });
    }

    @Test
    void rejectsProviderAliasWithInstitutionCode() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);

            assertSqlRejected(connection, identifierInsert(
                uuid(143), CUSTOMER_ACCOUNT_A, "PROVIDER_VIRTUAL_ACCOUNT", "scoped-alias", "000011", uuid(304),
                "EXTERNAL", "ACTIVE", false));
        });
    }

    @Test
    void rejectsSameActiveProviderAliasWithinOneProvider() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(144), CUSTOMER_ACCOUNT_A, "PROVIDER_VIRTUAL_ACCOUNT", "duplicate-alias", null, uuid(305),
                "EXTERNAL", "ACTIVE", false));

            assertSqlRejected(connection, identifierInsert(
                uuid(145), CUSTOMER_ACCOUNT_B, "PROVIDER_VIRTUAL_ACCOUNT", "duplicate-alias", null, uuid(305),
                "EXTERNAL", "ACTIVE", false));
        });
    }

    @Test
    void retainsSyntheticNubanAsSimulatorOnlyData() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(137), CUSTOMER_ACCOUNT_A, "NUBAN", "0000000017", "000000", null,
                "SIMULATOR_ONLY", "ACTIVE", true));

            assertEquals("SIMULATOR_ONLY", queryString(connection, """
                SELECT routing_scope FROM funds.account_identifier
                WHERE institution_code = '000000' AND normalised_value = '0000000017'
                """));
            assertEquals(0, queryInt(connection, """
                SELECT count(*) FROM funds.account_identifier
                WHERE institution_code = '000000' AND normalised_value = '0000000017'
                  AND routing_scope IN ('INTERNAL', 'EXTERNAL')
                """));
        });
    }

    @Test
    void productionMigrationDoesNotSeedSyntheticNuban() throws Exception {
        inTransaction(connection -> assertEquals(0, queryInt(connection, """
            SELECT count(*) FROM funds.account_identifier
            WHERE institution_code = '000000' AND normalised_value = '0000000017'
            """)));
    }

    @Test
    void rejectsEveryIbanIdentifier() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, identifierInsert(
                uuid(138), CUSTOMER_ACCOUNT_A, "IBAN", "NG00NOTAREALIBAN", null, null,
                "EXTERNAL", "ACTIVE", false));
        });
    }

    @Test
    void rejectsExternalAddressForNonCustomerAccount() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            assertSqlRejected(connection, identifierInsert(
                uuid(139), CONTROL_ACCOUNT, "PROVIDER_VIRTUAL_ACCOUNT", "control-external", null, uuid(302),
                "EXTERNAL", "ACTIVE", false));
        });
    }

    @Test
    void rejectsCustomerScopeChangeAfterExternalIdentifierExists() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(146), CUSTOMER_ACCOUNT_A, "PROVIDER_VIRTUAL_ACCOUNT", "external-alias", null, uuid(306),
                "EXTERNAL", "ACTIVE", false));

            assertSqlRejected(connection, """
                UPDATE funds.ledger_account
                SET account_scope = 'CONTROL', product_version_id = NULL
                WHERE account_id = '%s'
                """.formatted(CUSTOMER_ACCOUNT_A));
            assertEquals("CUSTOMER", queryString(connection, """
                SELECT account_scope FROM funds.ledger_account WHERE account_id = '%s'
                """.formatted(CUSTOMER_ACCOUNT_A)));
        });
    }

    @Test
    void rejectsAccountScopeChangeWithoutIdentifiers() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);

            assertSqlRejected(connection, """
                UPDATE funds.ledger_account SET account_scope = 'INTERNAL'
                WHERE account_id = '%s'
                """.formatted(CONTROL_ACCOUNT));
            assertEquals("CONTROL", queryString(connection, """
                SELECT account_scope FROM funds.ledger_account WHERE account_id = '%s'
                """.formatted(CONTROL_ACCOUNT)));
        });
    }

    @Test
    void externalIdentifierInsertLocksLedgerRowAgainstConcurrentUpdate() throws Exception {
        truncateReferenceTables();
        try {
            try (var setupConnection = dataSource.getConnection()) {
                setupConnection.setAutoCommit(false);
                insertReferenceGraph(setupConnection);
                setupConnection.commit();
            }

            try (var identifierConnection = dataSource.getConnection();
                 var ledgerConnection = dataSource.getConnection()) {
                identifierConnection.setAutoCommit(false);
                ledgerConnection.setAutoCommit(false);
                execute(identifierConnection, identifierInsert(
                    uuid(147), CUSTOMER_ACCOUNT_A, "PROVIDER_VIRTUAL_ACCOUNT", "locking-alias", null, uuid(307),
                    "EXTERNAL", "ACTIVE", false));
                execute(ledgerConnection, "SET LOCAL lock_timeout = '250ms'");

                assertSqlRejected(ledgerConnection, """
                    UPDATE funds.ledger_account SET status = 'DEBIT_BLOCKED'
                    WHERE account_id = '%s'
                    """.formatted(CUSTOMER_ACCOUNT_A));
                identifierConnection.rollback();
                ledgerConnection.rollback();
            }
        } finally {
            truncateReferenceTables();
        }
    }

    @Test
    void acceptsInternalAddressForNonCustomerAccount() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(140), CONTROL_ACCOUNT, "PROVIDER_VIRTUAL_ACCOUNT", "control-internal", null, uuid(303),
                "INTERNAL", "ACTIVE", false));

            assertEquals("INTERNAL", queryString(connection, """
                SELECT routing_scope FROM funds.account_identifier
                WHERE account_identifier_id = '%s'
                """.formatted(uuid(140))));
        });
    }

    @Test
    void rejectsIdentifierUpdate() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(141), CUSTOMER_ACCOUNT_A, "NUBAN", "0000014579", "000011", null,
                "INTERNAL", "ACTIVE", true));

            assertSqlRejected(connection, """
                UPDATE funds.account_identifier SET lifecycle_status = 'RETIRED'
                WHERE account_identifier_id = '%s'
                """.formatted(uuid(141)));
            assertEquals("ACTIVE", queryString(connection, """
                SELECT lifecycle_status FROM funds.account_identifier
                WHERE account_identifier_id = '%s'
                """.formatted(uuid(141))));
        });
    }

    @Test
    void rejectsIdentifierDelete() throws Exception {
        inTransaction(connection -> {
            insertReferenceGraph(connection);
            execute(connection, identifierInsert(
                uuid(142), CUSTOMER_ACCOUNT_A, "NUBAN", "0000014579", "000011", null,
                "INTERNAL", "ACTIVE", true));

            assertSqlRejected(connection, """
                DELETE FROM funds.account_identifier
                WHERE account_identifier_id = '%s'
                """.formatted(uuid(142)));
            assertEquals(1, queryInt(connection, """
                SELECT count(*) FROM funds.account_identifier
                WHERE account_identifier_id = '%s'
                """.formatted(uuid(142))));
        });
    }

    private void inTransaction(SqlConsumer action) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                action.accept(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void insertReferenceGraph(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
            VALUES
                ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000010',
                 'NGN', 'Africa/Lagos', 'NG', 1)
            """);
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, activated_at)
            VALUES
                ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
                 1, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """);
        execute(connection, """
            INSERT INTO funds.product_definition
                (product_id, product_code, product_kind, finance_principle)
            VALUES
                ('00000000-0000-0000-0000-000000000003', 'SAVINGS-STANDARD', 'SAVINGS', 'CONVENTIONAL')
            """);
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json)
            VALUES
                ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000003',
                 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 'APP-2026-001',
                 '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                 '{"interestRate":"0.01"}'::jsonb)
            """);
        execute(connection, ledgerInsert(
            CUSTOMER_ACCOUNT_A, BOOK_ID, CHART_VERSION_ID, "CUSTOMER-A", "CUSTOMER",
            PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN"));
        execute(connection, ledgerInsert(
            CUSTOMER_ACCOUNT_B, BOOK_ID, CHART_VERSION_ID, "CUSTOMER-B", "CUSTOMER",
            PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN"));
        execute(connection, ledgerInsert(
            CONTROL_ACCOUNT, BOOK_ID, CHART_VERSION_ID, "CONTROL-A", "CONTROL",
            null, "ASSET", "DEBIT", "NGN"));
    }

    private static void insertSecondProductVersion(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json)
            VALUES
                ('%s', '00000000-0000-0000-0000-000000000003', 2,
                 TIMESTAMPTZ '2027-01-01 00:00:00+00', 'APP-2027-001',
                 '%s', '{"interestRate":"0.02"}'::jsonb)
            """.formatted(SECOND_PRODUCT_VERSION_ID, EVIDENCE_HASH));
    }

    private static void insertSecondBookAndChart(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
            VALUES
                ('%s', '00000000-0000-0000-0000-000000000023', 'NGN', 'Africa/Lagos', 'NG', 1)
            """.formatted(SECOND_BOOK_ID));
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, activated_at)
            VALUES
                ('%s', '%s', 1, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """.formatted(SECOND_CHART_VERSION_ID, SECOND_BOOK_ID));
    }

    private void truncateReferenceTables() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                TRUNCATE funds.account_identifier, funds.ledger_account, funds.accounting_period,
                    funds.chart_version, funds.book, funds.product_version, funds.product_definition CASCADE
                """);
        }
    }

    private static String ledgerInsert(
        UUID accountId,
        UUID bookId,
        UUID chartVersionId,
        String accountCode,
        String accountScope,
        UUID productVersionId,
        String accountClass,
        String normalBalance,
        String currency
    ) {
        var productValue = productVersionId == null ? "NULL" : "'" + productVersionId + "'";
        return """
            INSERT INTO funds.ledger_account
                (account_id, book_id, chart_version_id, account_code, account_scope, product_version_id,
                 account_class, normal_balance, currency, control_account_code, status, created_at)
            VALUES
                ('%s', '%s', '%s', '%s', '%s', %s, '%s', '%s', '%s', 'CUSTOMER-DEPOSITS',
                 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """.formatted(
                accountId, bookId, chartVersionId, accountCode, accountScope, productValue,
                accountClass, normalBalance, currency);
    }

    private static String identifierInsert(
        UUID identifierId,
        UUID accountId,
        String scheme,
        String normalisedValue,
        String institutionCode,
        UUID providerId,
        String routingScope,
        String lifecycleStatus,
        boolean primary
    ) {
        var institutionValue = institutionCode == null ? "NULL" : "'" + institutionCode + "'";
        var providerValue = providerId == null ? "NULL" : "'" + providerId + "'";
        return """
            INSERT INTO funds.account_identifier
                (account_identifier_id, account_id, scheme, normalised_value, institution_code, provider_id,
                 purpose_code, routing_scope, lifecycle_status, is_primary, valid_from, issuance_evidence_hash)
            VALUES
                ('%s', '%s', '%s', '%s', %s, %s, 'TEST', '%s', '%s', %s,
                 TIMESTAMPTZ '2026-01-01 00:00:00+00', '%s')
            """.formatted(
                identifierId, accountId, scheme, normalisedValue, institutionValue, providerValue,
                routingScope, lifecycleStatus, primary, EVIDENCE_HASH);
    }

    private static void assertSqlRejected(Connection connection, String sql) throws SQLException {
        Savepoint beforeViolation = connection.setSavepoint();
        try {
            assertThrows(SQLException.class, () -> execute(connection, sql));
        } finally {
            connection.rollback(beforeViolation);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getBoolean(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static UUID queryUuid(Connection connection, String sql, UUID parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getObject(1, UUID.class);
            }
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
