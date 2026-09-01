package com.corebanking.funds.application;

import com.corebanking.funds.infrastructure.postgres.JdbcLedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Shared PostgreSQL fixture and wiring for the posting integration tests: a real
 * JdbcLedgerRepository and PostingService over a caller-supplied DataSource and observer, plus one
 * deterministic reference graph (book, active chart, open period, product, a debit-normal provider
 * asset and a credit-normal customer liability) with pre-seeded projections. The crash-recovery
 * child JVM (CrashPostingWorker) uses the same constants, so every identity here is fixed rather
 * than random and both processes agree on it without exchanging state.
 */
final class TestPostingStack {
    static final UUID BOOK_ID = uuid(1);
    static final UUID CHART_VERSION_ID = uuid(2);
    static final UUID PRODUCT_ID = uuid(3);
    static final UUID PRODUCT_VERSION_ID = uuid(4);
    static final UUID PROVIDER_ASSET = uuid(5);
    static final UUID CUSTOMER_LIABILITY = uuid(6);
    static final UUID PERIOD_ID = uuid(7);
    static final UUID LEGAL_ENTITY_ID = uuid(8);

    static final String PROVIDER_CONTROL = "PROVIDER-CASH";
    static final String CUSTOMER_CONTROL = "CUSTOMER-DEPOSITS";
    static final String INDEPENDENT_CONTROL = "INDEPENDENT-CONTROL";

    // Projections start non-zero so tests assert deltas (total, sequence, version) against a known
    // baseline instead of a fresh row. The provider control is pre-seeded to match its account; the
    // customer control is deliberately absent so posting exercises the insert path; the independent
    // control has no mapped account and must never move.
    static final long PROVIDER_INITIAL_TOTAL = 11_000;
    static final long PROVIDER_INITIAL_SEQUENCE = 3;
    static final long PROVIDER_INITIAL_VERSION = 3;
    static final long CUSTOMER_INITIAL_TOTAL = -17_000;
    static final long CUSTOMER_INITIAL_SEQUENCE = 5;
    static final long CUSTOMER_INITIAL_VERSION = 5;
    static final long PROVIDER_CONTROL_INITIAL_TOTAL = 11_000;
    static final long INDEPENDENT_CONTROL_TOTAL = 777;

    private final PostingService postingService;

    private TestPostingStack(PostingService postingService) {
        this.postingService = postingService;
    }

    /**
     * Wires the production posting path (validator, hasher, JDBC repository, PostingService) with
     * the given observer injected into both repository and service. The retry policy keeps its
     * real attempt loop but a no-op pause, so serialization retries do not sleep under test.
     */
    static TestPostingStack create(DataSource dataSource, PostingTransactionObserver observer) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(observer, "observer");
        var validator = new JournalValidator();
        var hasher = new CanonicalJournalHasher();
        var repository = new JdbcLedgerRepository(validator, hasher, observer);
        var retryPolicy = new PostgresRetryPolicy((commandId, attempt) -> {});
        return new TestPostingStack(new PostingService(dataSource, repository, retryPolicy, observer));
    }

    PostingService postingService() {
        return postingService;
    }

    /** Truncates every ledger table and reinstalls the reference graph and projection baselines. */
    static void resetAndSeed(DataSource dataSource) throws SQLException {
        reset(dataSource);
        try (var connection = dataSource.getConnection()) {
            insertReferenceGraph(connection);
            insertProjectionFixtures(connection);
        }
    }

    /**
     * Empties every funds table in one statement. TRUNCATE needs a privilege V004 never grants to
     * funds_app, so this is test-only teardown and can never be reached through the service role.
     */
    static void reset(DataSource dataSource) throws SQLException {
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
                RESTART IDENTITY CASCADE
                """);
        }
    }

    // The chart is inserted DRAFT, both accounts are mapped, and only then is it activated: V005
    // rejects charts created directly ACTIVE and activation requires every open account mapped.
    private static void insertReferenceGraph(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.book
                (book_id, legal_entity_id, functional_currency, timezone, calendar_code,
                 accounting_policy_version)
            VALUES (?, ?, 'NGN', 'Africa/Lagos', 'NG', 1)
            """, BOOK_ID, LEGAL_ENTITY_ID);
        execute(connection, """
            INSERT INTO funds.chart_version
                (chart_version_id, book_id, version, status, approval_reference)
            VALUES (?, ?, 1, 'DRAFT', 'APP-CHART-001')
            """, CHART_VERSION_ID, BOOK_ID);
        execute(connection, """
            INSERT INTO funds.accounting_period
                (period_id, book_id, business_date_from, business_date_to, status)
            VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'OPEN')
            """, PERIOD_ID, BOOK_ID);
        execute(connection, """
            INSERT INTO funds.product_definition
                (product_id, product_code)
            VALUES (?, 'CRASH-RECOVERY-SAVINGS')
            """, PRODUCT_ID);
        execute(connection, """
            INSERT INTO funds.product_version
                (product_version_id, product_id, version, effective_from, approval_reference,
                 policy_hash, policy_json, product_kind, finance_principle)
            VALUES (?, ?, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00',
                    'APP-CRASH-RECOVERY-001', ?, '{}'::jsonb, 'SAVINGS', 'CONVENTIONAL')
            """, PRODUCT_VERSION_ID, PRODUCT_ID, "a".repeat(64));
        insertAccount(
            connection,
            PROVIDER_ASSET,
            "PROVIDER-ASSET",
            "INTERNAL",
            null,
            "ASSET",
            "DEBIT",
            PROVIDER_CONTROL);
        insertAccount(
            connection,
            CUSTOMER_LIABILITY,
            "CUSTOMER-LIABILITY",
            "CUSTOMER",
            PRODUCT_VERSION_ID,
            "LIABILITY",
            "CREDIT",
            CUSTOMER_CONTROL);
        execute(connection, """
            UPDATE funds.chart_version
            SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
            WHERE chart_version_id = ?
            """, CHART_VERSION_ID);
    }

    private static void insertAccount(
        Connection connection,
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
                (account_id, book_id, account_scope, product_version_id, currency, status, created_at)
            VALUES (?, ?, ?, ?, 'NGN', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00')
            """,
            accountId,
            BOOK_ID,
            accountScope,
            productVersionId);
        execute(connection, """
            INSERT INTO funds.ledger_account_chart_mapping
                (account_id, book_id, chart_version_id, account_code, account_currency, account_class,
                 normal_balance, control_account_code, account_role)
            VALUES (?, ?, ?, ?, 'NGN', ?, ?, ?, ?)
            """,
            accountId,
            BOOK_ID,
            CHART_VERSION_ID,
            accountCode,
            accountClass,
            normalBalance,
            controlCode,
            accountScope);
    }

    private static void insertProjectionFixtures(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO funds.materialised_balance
                (account_id, signed_posting_total, latest_account_sequence, version)
            VALUES (?, ?, ?, ?), (?, ?, ?, ?)
            """,
            PROVIDER_ASSET,
            PROVIDER_INITIAL_TOTAL,
            PROVIDER_INITIAL_SEQUENCE,
            PROVIDER_INITIAL_VERSION,
            CUSTOMER_LIABILITY,
            CUSTOMER_INITIAL_TOTAL,
            CUSTOMER_INITIAL_SEQUENCE,
            CUSTOMER_INITIAL_VERSION);
        execute(connection, """
            INSERT INTO funds.control_account_projection
                (book_id, control_account_code, currency, signed_posting_total,
                 latest_journal_sequence)
            VALUES (?, ?, 'NGN', ?, 0), (?, ?, 'NGN', ?, 0)
            """,
            BOOK_ID,
            PROVIDER_CONTROL,
            PROVIDER_CONTROL_INITIAL_TOTAL,
            BOOK_ID,
            INDEPENDENT_CONTROL,
            INDEPENDENT_CONTROL_TOTAL);
    }

    /** Runs one parameterised update; UUIDs bind via setObject so PostgreSQL sees uuid values. */
    static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    /** Deterministic UUID from a small integer; the number is the whole identity of a fixture. */
    static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
