package com.corebanking.funds.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
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

/**
 * Proves the migrated schema on the Quarkus test datasource (a fresh Testcontainers PostgreSQL
 * migrated by Flyway under the test profile): reference-table shape, the V003.2 finality trigger
 * and single-reversal index, the ACC-24 role model, and the ACC-38/ACC-40/ACC-42 reference
 * constraints (NUBAN check digits, identifier cardinality and immutability, product-version
 * binding, period exclusion). Role tests read the catalog ACLs and then switch the session with
 * {@code SET ROLE funds_app} / {@code SET ROLE funds_proof_reader} to observe real 42501 denials.
 * Every test runs inside one rolled-back transaction except the lock test, which needs two
 * connections. Catches a migration that widens a grant, moves ownership or drops a constraint the
 * kernel relies on.
 */
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
                "ledger_account_chart_mapping",
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
    void installsJournalFinalityTriggerAndSingleReversalIndex() throws Exception {
        inTransaction(connection -> {
            assertEquals(1, queryInt(connection, """
                SELECT count(*)
                FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid = trigger.tgrelid
                JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'funds'
                  AND relation.relname = 'posting'
                  AND trigger.tgname = 'posting_requires_in_progress_command'
                  AND NOT trigger.tgisinternal
                """));
            // V005 re-keys the index on the link alone: a second linked journal must not escape
            // uniqueness by carrying a transaction_type other than REVERSAL.
            assertEquals(1, queryInt(connection, """
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = 'funds'
                  AND tablename = 'journal'
                  AND indexname = 'one_reversal_per_original_idx'
                  AND indexdef LIKE '%WHERE%reversal_of_journal_id IS NOT NULL%'
                  AND indexdef NOT LIKE '%transaction_type%'
                """));
        });
    }

    /**
     * Catalog proof of MIGRATION-ROLES.md: three NOLOGIN capability roles with no memberships,
     * every funds object owned by {@code funds_migrator}, nothing granted to PUBLIC,
     * {@code funds_app} limited to exactly five executable functions, column-limited INSERT on
     * journal and outbox and USAGE-only on the journal sequence, {@code funds_proof_reader}
     * limited to column SELECTs. Then, as {@code funds_app}, chart lifecycle UPDATE and
     * {@code rotate_chart_version} are denied, and, as {@code funds_migrator}, freshly created
     * objects inherit the hardened default privileges.
     */
    @Test
    void installsHardenedRoleOwnershipAndExactPrivileges() throws Exception {
        inTransaction(connection -> {
            assertEquals(3, queryInt(connection, """
                SELECT count(*) FROM pg_roles
                WHERE rolname IN ('funds_migrator', 'funds_app', 'funds_proof_reader')
                  AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
                  AND NOT rolinherit AND NOT rolreplication AND NOT rolbypassrls
                """));
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM pg_auth_members membership
                JOIN pg_roles member ON member.oid = membership.member
                WHERE member.rolname IN ('funds_migrator', 'funds_app', 'funds_proof_reader')
                """));
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM (
                    SELECT namespace.nspowner AS owner
                    FROM pg_namespace namespace WHERE namespace.nspname = 'funds'
                    UNION ALL
                    SELECT relation.relowner
                    FROM pg_class relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'funds' AND relation.relkind IN ('r', 'p', 'S')
                    UNION ALL
                    SELECT procedure.proowner
                    FROM pg_proc procedure
                    JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                    WHERE namespace.nspname = 'funds'
                ) owned_object
                WHERE owned_object.owner <> 'funds_migrator'::regrole
                """));
            // SECURITY DEFINER is permitted on exactly the trigger and lock functions; on any
            // other function it would be an escalation path for funds_app.
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM pg_proc procedure
                JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'funds'
                  AND procedure.prosecdef IS DISTINCT FROM (
                      procedure.proname IN (
                          'enforce_external_identifier_customer_scope',
                          'enforce_journal_governance',
                          'enforce_posting_chart_mapping',
                          'enforce_posting_reference_consistency',
                          'reject_posting_to_completed_journal',
                          'reject_ungoverned_active_chart_account_onboarding',
                          'enforce_journal_reversibility',
                          'lock_book_chart_for_posting',
                          'lock_period_for_posting',
                          'lock_account_mapping_for_posting'))
                """));
            // A pinned search_path on every function stops a definer from resolving an
            // attacker-created object ahead of the funds one.
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM pg_proc procedure
                JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'funds'
                  AND procedure.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, funds']
                """));
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM (
                    SELECT privilege.grantee
                    FROM pg_namespace namespace,
                         LATERAL aclexplode(coalesce(
                             namespace.nspacl, acldefault('n', namespace.nspowner))) privilege
                    WHERE namespace.nspname = 'funds'
                    UNION ALL
                    SELECT privilege.grantee
                    FROM pg_class relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace,
                         LATERAL aclexplode(coalesce(
                             relation.relacl,
                             acldefault(
                                 (CASE WHEN relation.relkind = 'S' THEN 'S' ELSE 'r' END)::"char",
                                        relation.relowner))) privilege
                    WHERE namespace.nspname = 'funds' AND relation.relkind IN ('r', 'p', 'S')
                    UNION ALL
                    SELECT privilege.grantee
                    FROM pg_proc procedure
                    JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace,
                         LATERAL aclexplode(coalesce(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                    WHERE namespace.nspname = 'funds'
                ) public_privilege
                WHERE public_privilege.grantee = 0
                """));

            assertTrue(queryBoolean(connection,
                "SELECT has_schema_privilege('funds_app', 'funds', 'USAGE')"));
            assertFalse(queryBoolean(connection,
                "SELECT has_schema_privilege('funds_app', 'funds', 'CREATE')"));
            assertTrue(queryBoolean(connection,
                "SELECT has_table_privilege('funds_app', 'funds.journal', 'SELECT')"));
            assertFalse(queryBoolean(connection,
                "SELECT has_table_privilege('funds_app', 'funds.journal', 'INSERT')"));
            assertTrue(queryBoolean(connection, """
                SELECT has_column_privilege('funds_app', 'funds.journal', 'journal_id', 'INSERT')
                """));
            assertFalse(queryBoolean(connection, """
                SELECT has_column_privilege(
                    'funds_app', 'funds.journal', 'journal_sequence', 'INSERT')
                """));
            assertFalse(queryBoolean(connection,
                "SELECT has_table_privilege('funds_app', 'funds.posting', 'UPDATE, DELETE')"));
            assertFalse(queryBoolean(connection, """
                SELECT has_table_privilege('funds_app', 'funds.accounting_period', 'UPDATE')
                """));
            assertFalse(queryBoolean(connection, """
                SELECT has_table_privilege('funds_app', 'funds.chart_version', 'UPDATE')
                """));
            assertTrue(queryBoolean(connection, """
                SELECT has_sequence_privilege(
                    'funds_app', 'funds.journal_journal_sequence_seq', 'USAGE')
                """));
            assertFalse(queryBoolean(connection, """
                SELECT has_sequence_privilege(
                    'funds_app', 'funds.journal_journal_sequence_seq', 'SELECT')
                """));
            assertFalse(queryBoolean(connection, """
                SELECT has_sequence_privilege(
                    'funds_app', 'funds.journal_journal_sequence_seq', 'UPDATE')
                """));
            assertFalse(queryBoolean(connection, """
                SELECT has_function_privilege(
                    'funds_app', 'funds.reject_ledger_mutation()', 'EXECUTE')
                """));
            assertFalse(queryBoolean(connection, """
                SELECT has_function_privilege(
                    'funds_app',
                    'funds.rotate_chart_version(uuid,uuid,uuid,timestamp with time zone)',
                    'EXECUTE')
                """));
            assertEquals(5, queryInt(connection, """
                SELECT count(*)
                FROM pg_proc procedure
                JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'funds'
                  AND has_function_privilege('funds_app', procedure.oid, 'EXECUTE')
                """));
            assertEquals(
                "jsonb_object_size,jsonb_object_values_are_strings,lock_account_mapping_for_posting,lock_book_chart_for_posting,lock_period_for_posting",
                queryString(connection, """
                    SELECT string_agg(procedure.proname, ',' ORDER BY procedure.proname)
                    FROM pg_proc procedure
                    JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                    WHERE namespace.nspname = 'funds'
                      AND has_function_privilege('funds_app', procedure.oid, 'EXECUTE')
                    """));
            assertEquals(2, queryInt(connection, """
                SELECT count(*)
                FROM pg_proc procedure
                JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                JOIN pg_language language ON language.oid = procedure.prolang
                WHERE namespace.nspname = 'funds'
                  AND procedure.proname IN (
                      'lock_period_for_posting',
                      'lock_account_mapping_for_posting')
                  AND procedure.proisstrict
                  AND procedure.prosecdef
                  AND language.lanname = 'sql'
                  AND procedure.prosrc ~ '^[[:space:]]*SELECT[[:space:]]'
                  AND position(';' IN procedure.prosrc) = 0
                """));
            assertEquals(1, queryInt(connection, """
                SELECT count(*)
                FROM pg_proc procedure
                JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                JOIN pg_language language ON language.oid = procedure.prolang
                WHERE namespace.nspname = 'funds'
                  AND procedure.proname = 'lock_book_chart_for_posting'
                  AND procedure.proisstrict
                  AND procedure.prosecdef
                  AND language.lanname = 'plpgsql'
                """));
            assertEquals(
                "aggregate_id,aggregate_version,created_at,event_id,event_type,payload,schema_version",
                queryString(connection, """
                    SELECT string_agg(privilege.column_name, ',' ORDER BY privilege.column_name)
                    FROM information_schema.column_privileges privilege
                    WHERE privilege.table_schema = 'funds'
                      AND privilege.table_name = 'outbox_event'
                      AND privilege.grantee = 'funds_app'
                      AND privilege.privilege_type = 'INSERT'
                    """));
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM pg_proc procedure
                JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'funds'
                  AND has_function_privilege('funds_proof_reader', procedure.oid, 'EXECUTE')
                """));
            assertFalse(queryBoolean(connection,
                "SELECT has_table_privilege('funds_proof_reader', 'funds.posting', 'SELECT')"));
            assertTrue(queryBoolean(connection, """
                SELECT has_column_privilege(
                    'funds_proof_reader', 'funds.posting', 'signed_minor_units', 'SELECT')
                """));
            assertFalse(queryBoolean(connection, """
                SELECT has_column_privilege(
                    'funds_proof_reader', 'funds.journal', 'narration', 'SELECT')
                """));
            assertFalse(queryBoolean(connection,
                "SELECT has_table_privilege('funds_proof_reader', 'funds.posting', 'INSERT')"));
            assertFalse(queryBoolean(connection, """
                SELECT has_sequence_privilege(
                    'funds_proof_reader', 'funds.journal_journal_sequence_seq', 'USAGE')
                """));

            execute(connection, "SET ROLE funds_app");
            try {
                assertSqlStateRejected(connection, "42501", """
                    UPDATE funds.chart_version SET status = 'RETIRED'
                    WHERE chart_version_id = '00000000-0000-0000-0000-000000000002'
                    """);
                assertSqlStateRejected(connection, "42501", """
                    SELECT funds.rotate_chart_version(
                        '00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000003',
                        TIMESTAMPTZ '2026-01-10 00:00:00+00')
                    """);
            } finally {
                execute(connection, "RESET ROLE");
            }

            // Default privileges must cover objects a later migration creates, not only the
            // ones V004 revoked explicitly.
            execute(connection, "SET ROLE funds_migrator");
            try {
                execute(connection, """
                    CREATE TABLE funds.default_acl_probe (
                        id bigserial PRIMARY KEY
                    )
                    """);
                execute(connection, """
                    CREATE FUNCTION funds.default_acl_probe_function()
                    RETURNS integer LANGUAGE sql AS 'SELECT 1'
                    """);
            } finally {
                execute(connection, "RESET ROLE");
            }
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM pg_class relation,
                     LATERAL aclexplode(coalesce(
                         relation.relacl,
                         acldefault(
                             (CASE WHEN relation.relkind = 'S' THEN 'S' ELSE 'r' END)::"char",
                             relation.relowner))) privilege
                WHERE relation.oid IN (
                    'funds.default_acl_probe'::regclass,
                    'funds.default_acl_probe_id_seq'::regclass)
                  AND privilege.grantee = 0
                """));
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM pg_proc procedure,
                     LATERAL aclexplode(coalesce(
                         procedure.proacl, acldefault('f', procedure.proowner))) privilege
                WHERE procedure.oid = 'funds.default_acl_probe_function()'::regprocedure
                  AND privilege.grantee = 0
                """));
            assertEquals(0, queryInt(connection, """
                SELECT count(*)
                FROM (
                    SELECT relation.relowner AS owner
                    FROM pg_class relation
                    WHERE relation.oid IN (
                        'funds.default_acl_probe'::regclass,
                        'funds.default_acl_probe_id_seq'::regclass)
                    UNION ALL
                    SELECT procedure.proowner
                    FROM pg_proc procedure
                    WHERE procedure.oid = 'funds.default_acl_probe_function()'::regprocedure
                ) future_object
                WHERE future_object.owner <> 'funds_migrator'::regrole
                """));
            execute(connection, "SET ROLE funds_migrator");
            try {
                execute(connection, "DROP FUNCTION funds.default_acl_probe_function()");
                execute(connection, "DROP TABLE funds.default_acl_probe");
            } finally {
                execute(connection, "RESET ROLE");
            }
        });
    }

    /**
     * Reads {@code V004__application_roles.sql} as text. It requires exactly three stripped lines
     * matching {@code CREATE ROLE funds_(migrator|app|proof_reader)...} and forbids the substrings
     * {@code IF NOT EXISTS}, {@code ALTER ROLE funds_}, {@code pg_auth_members} and
     * {@code REVOKE %I FROM %I} anywhere in the file, comments included. Anyone editing V004,
     * even to add a comment quoting one of those phrases, will fail this test by design.
     */
    @Test
    void roleBootstrapIsFailClosedAndNeverAltersExistingClusterRoles() throws Exception {
        try (var input = MigrationIT.class.getResourceAsStream(
            "/db/migration/V004__application_roles.sql")) {
            assertNotNull(input);
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(3, migration.lines()
                .map(String::strip)
                .filter(line -> line.matches(
                    "CREATE ROLE funds_(migrator|app|proof_reader).*"))
                .count());
            assertFalse(migration.contains("IF NOT EXISTS"));
            assertFalse(migration.contains("ALTER ROLE funds_"));
            assertFalse(migration.contains("pg_auth_members"));
            assertFalse(migration.contains("REVOKE %I FROM %I"));
        }
    }

    /**
     * The two queries are the exact per-book trial-balance and control-projection proof shapes;
     * they touch only the columns V005 grants to {@code funds_proof_reader}, so a proof job needs
     * nothing more, and the denials show it can get nothing more.
     */
    @Test
    void proofReaderCanRunExactProofsButCannotReadOperationalOrPolicyPayloads()
        throws Exception {
        inTransaction(connection -> {
            execute(connection, "SET ROLE funds_proof_reader");
            try {
                assertEquals("0/0", queryString(connection, """
                    SELECT
                        coalesce(sum(CASE WHEN posting.signed_minor_units > 0
                            THEN posting.signed_minor_units::numeric ELSE 0::numeric END), 0)::text
                        || '/' ||
                        coalesce(sum(CASE WHEN posting.signed_minor_units < 0
                            THEN -(posting.signed_minor_units::numeric) ELSE 0::numeric END), 0)::text
                    FROM funds.posting posting
                    JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                    WHERE journal.book_id = '00000000-0000-0000-0000-000000000001'
                      AND posting.currency = 'NGN'
                      AND journal.journal_sequence <= 0
                    """));
                assertEquals("0/0", queryString(connection, """
                    WITH mapped AS (
                        SELECT posting.signed_minor_units, journal.journal_sequence
                        FROM funds.posting posting
                        JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                        JOIN funds.ledger_account_chart_mapping mapping
                          ON mapping.account_id = posting.account_id
                         AND mapping.book_id = journal.book_id
                         AND mapping.chart_version_id = journal.chart_version_id
                         AND mapping.account_currency = posting.currency
                        WHERE journal.book_id = '00000000-0000-0000-0000-000000000001'
                          AND mapping.control_account_code = 'CUSTOMER-DEPOSITS'
                          AND posting.currency = 'NGN'
                    ), source AS (
                        SELECT coalesce(sum(signed_minor_units::numeric), 0) AS total
                        FROM mapped
                    ), projection AS (
                        SELECT signed_posting_total::numeric AS total
                        FROM funds.control_account_projection
                        WHERE book_id = '00000000-0000-0000-0000-000000000001'
                          AND control_account_code = 'CUSTOMER-DEPOSITS'
                          AND currency = 'NGN'
                    )
                    SELECT source.total::text || '/' || coalesce(projection.total, 0)::text
                    FROM source LEFT JOIN projection ON true
                    """));

                assertSqlStateRejected(connection, "42501",
                    "SELECT normalised_value FROM funds.account_identifier");
                assertSqlStateRejected(connection, "42501",
                    "SELECT policy_json FROM funds.product_version");
                assertSqlStateRejected(connection, "42501",
                    "SELECT result_json FROM funds.idempotency_command");
                assertSqlStateRejected(connection, "42501",
                    "SELECT payload FROM funds.outbox_event");
            } finally {
                execute(connection, "RESET ROLE");
            }
        });
    }

    @Test
    void rejectsLedgerCurrencyLongerThanThreeCharacters() throws Exception {
        inTransaction(connection -> {
            insertDraftReferenceGraph(connection);
            assertSqlStateRejected(connection, "22001", ledgerInsert(
                uuid(100), BOOK_ID, CHART_VERSION_ID, "INVALID-CURRENCY", "CUSTOMER",
                PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGNN"));
        });
    }

    @Test
    void rejectsUnknownLedgerNormalBalance() throws Exception {
        inTransaction(connection -> {
            insertDraftReferenceGraph(connection);
            assertSqlStateRejected(connection, "23514", ledgerInsert(
                uuid(101), BOOK_ID, CHART_VERSION_ID, "INVALID-NORMAL", "CUSTOMER",
                PRODUCT_VERSION_ID, "LIABILITY", "SIDEWAYS", "NGN"));
        });
    }

    @Test
    void rejectsLedgerAccountWhoseBookDoesNotExist() throws Exception {
        inTransaction(connection -> {
            insertDraftReferenceGraph(connection);
            assertSqlStateRejected(connection, "23503", ledgerInsert(
                uuid(102), uuid(999), CHART_VERSION_ID, "MISSING-BOOK", "CUSTOMER",
                PRODUCT_VERSION_ID, "LIABILITY", "CREDIT", "NGN"));
        });
    }

    @Test
    void rejectsChartVersionFromAnotherBook() throws Exception {
        inTransaction(connection -> {
            insertDraftReferenceGraph(connection);
            insertSecondBookAndChart(connection);

            assertSqlStateRejected(connection, "23503", ledgerInsert(
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
            insertDraftReferenceGraph(connection);
            assertSqlStateRejected(connection, "23514", ledgerInsert(
                uuid(103), BOOK_ID, CHART_VERSION_ID, "NO-PRODUCT", "CUSTOMER",
                null, "LIABILITY", "CREDIT", "NGN"));
        });
    }

    @Test
    void rejectsProductVersionBindingForNonCustomerAccount() throws Exception {
        inTransaction(connection -> {
            insertDraftReferenceGraph(connection);
            assertSqlStateRejected(connection, "23514", ledgerInsert(
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

    // 000011/0000014579 is the published check-digit worked example; 000000/0000000017 is the
    // deterministic SIMULATOR_ONLY fixture named in the README and is not production-routable.
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

    /**
     * Two sessions: the first inserts an external identifier and keeps its transaction open, so
     * the scope trigger's lock on the ledger-account row is still held; the second, under a
     * 250 ms lock_timeout, must fail (55P03) to update that account rather than race the scope
     * check.
     */
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

    // Always rolled back: tests leave no rows behind and share the migrated database safely.
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
        insertDraftReferenceGraph(connection);
        execute(connection, """
            UPDATE funds.chart_version
            SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
            WHERE chart_version_id = '00000000-0000-0000-0000-000000000002'
            """);
    }

    private static void insertDraftReferenceGraph(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
            VALUES
                ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000010',
                 'NGN', 'Africa/Lagos', 'NG', 1)
            """);
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES
                ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
                 1, 'DRAFT', 'APP-CHART-001')
            """);
        execute(connection, """
            INSERT INTO funds.product_definition
                (product_id, product_code)
            VALUES
                ('00000000-0000-0000-0000-000000000003', 'SAVINGS-STANDARD')
            """);
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json, product_kind, finance_principle)
            VALUES
                ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000003',
                 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 'APP-2026-001',
                 '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                 '{"interestRate":"0.01"}'::jsonb, 'SAVINGS', 'CONVENTIONAL')
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
                 policy_hash, policy_json, product_kind, finance_principle)
            VALUES
                ('%s', '00000000-0000-0000-0000-000000000003', 2,
                 TIMESTAMPTZ '2027-01-01 00:00:00+00', 'APP-2027-001',
                 '%s', '{"interestRate":"0.02"}'::jsonb, 'SAVINGS', 'CONVENTIONAL')
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
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES
                ('%s', '%s', 1, 'DRAFT', 'APP-SECOND-CHART')
            """.formatted(SECOND_CHART_VERSION_ID, SECOND_BOOK_ID));
    }

    private void truncateReferenceTables() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            execute(connection, """
                TRUNCATE funds.account_identifier, funds.ledger_account_chart_mapping,
                    funds.ledger_account, funds.accounting_period, funds.chart_version, funds.book,
                    funds.product_version, funds.product_definition CASCADE
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
            WITH inserted_account AS (
                INSERT INTO funds.ledger_account
                    (account_id, book_id, account_scope, product_version_id, currency, status, created_at)
                VALUES ('%s', '%s', '%s', %s, '%s', 'OPEN',
                        TIMESTAMPTZ '2026-01-01 00:00:00+00')
                RETURNING account_id, book_id, currency
            )
            INSERT INTO funds.ledger_account_chart_mapping
                (account_id, book_id, chart_version_id, account_code, account_currency,
                 account_class,
                 normal_balance, control_account_code, account_role)
            SELECT account_id, book_id, '%s', '%s', currency, '%s', '%s',
                   'CUSTOMER-DEPOSITS', '%s'
            FROM inserted_account
            """.formatted(
                accountId, bookId, accountScope, productValue, currency, chartVersionId,
                accountCode, accountClass, normalBalance, accountScope);
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

    // Accepts any constraint-class rejection: string truncation, FK, unique, CHECK, exclusion,
    // trigger-raised 55000 or lock timeout. Use assertSqlStateRejected when the exact code matters.
    private static void assertSqlRejected(Connection connection, String sql) throws SQLException {
        Savepoint beforeViolation = connection.setSavepoint();
        try {
            SQLException failure = assertThrows(SQLException.class, () -> execute(connection, sql));
            assertTrue(Set.of("22001", "23503", "23505", "23514", "23P01", "55000", "55P03")
                .contains(failure.getSQLState()),
                () -> "unexpected SQLSTATE " + failure.getSQLState() + " for: " + sql);
        } finally {
            connection.rollback(beforeViolation);
        }
    }

    private static void assertSqlStateRejected(
        Connection connection,
        String expectedSqlState,
        String sql
    ) throws SQLException {
        Savepoint beforeViolation = connection.setSavepoint();
        try {
            SQLException failure = assertThrows(SQLException.class, () -> execute(connection, sql));
            assertEquals(expectedSqlState, failure.getSQLState());
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
