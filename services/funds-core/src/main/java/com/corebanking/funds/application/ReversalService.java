package com.corebanking.funds.application;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.ReversalRequest;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import com.corebanking.funds.infrastructure.postgres.SqlState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;

/**
 * Builds and posts the exact reversal of a completed journal. Pipeline: verify the reversalV2
 * request hash; run a read-only pre-flight (loadCoherentFact) that either finds a completed
 * replay or loads and re-hashes the original; negate every posting into a linked REVERSAL
 * draft whose IDs derive from the commandId; hand it to PostingService.postTrustedReversal.
 * The ledger mirrors the rules applied here (exact negation, one reversal per original, no
 * reversal of a reversal) in V005's enforce_journal_reversibility and
 * one_reversal_per_original_idx, so a bypass of this class still fails at commit.
 */
@ApplicationScoped
public class ReversalService {
    static final int MAX_POSTINGS_PER_JOURNAL = JournalValidator.MAX_POSTINGS_PER_JOURNAL;
    static final int MAX_DIMENSIONS_PER_POSTING = JournalValidator.MAX_DIMENSIONS_PER_POSTING;
    static final int MAX_DIMENSION_JSON_BYTES = JournalValidator.MAX_DIMENSION_JSON_BYTES;
    // Client-side cancel backstop for the pre-flight reads; the server-side deadlines from
    // PostingTransactionTimeouts are applied to the same connection as well.
    static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int MAX_DIMENSION_ROWS =
        MAX_POSTINGS_PER_JOURNAL * MAX_DIMENSIONS_PER_POSTING;
    // Partial unique index on journal.reversal_of_journal_id (V005). It is the authoritative
    // guard when two reversals of one original both pass the pre-flight existence check.
    private static final String SINGLE_REVERSAL_CONSTRAINT = "one_reversal_per_original_idx";

    private final DataSource dataSource;
    private final PostingService postingService;
    private final JournalValidator validator;
    private final CanonicalJournalHasher hasher;
    private final CanonicalCommandHasher commandHasher;
    private final PostingTransactionTimeouts transactionTimeouts;

    public ReversalService(DataSource dataSource, PostingService postingService) {
        this(dataSource, postingService, PostingTransactionTimeouts.defaults());
    }

    @Inject
    public ReversalService(
        DataSource dataSource,
        PostingService postingService,
        PostingTransactionTimeouts transactionTimeouts
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.validator = new JournalValidator();
        this.hasher = new CanonicalJournalHasher();
        this.commandHasher = new CanonicalCommandHasher();
        this.transactionTimeouts = Objects.requireNonNull(transactionTimeouts, "transactionTimeouts");
    }

    /**
     * Reverses a completed original exactly once. Returns the stored result on a same-content
     * replay; throws IdempotencyConflictException on a request-hash mismatch and
     * InvalidJournalException when the original is missing, incoherent, already reversed or
     * itself a reversal.
     */
    public PostingResult reverse(ReversalRequest request) {
        Objects.requireNonNull(request, "request");
        // The request hash is checked here, against the ReversalRequest, because the trusted
        // posting path receives a journal the caller never hashed.
        if (!request.requestHash().equals(commandHasher.reversalV2(request))) {
            throw new IdempotencyConflictException(request.commandId());
        }
        LoadOutcome loaded = loadCoherentFact(request);
        if (loaded.completedResult().isPresent()) {
            return loaded.completedResult().orElseThrow();
        }
        OriginalJournal original = loaded.original();
        // Reversing a reversal would re-apply the original under REVERSAL semantics; the
        // database refuses it as well (reversal_of_reversal_forbidden).
        if (original.reversalOfJournalId() != null) {
            throw new InvalidJournalException(
                "reversal of a reversal requires an explicit correction template policy");
        }

        // IDs derive from the commandId so a retry rebuilds identical postings and collides on
        // identity instead of duplicating. negateExact cannot overflow because Long.MIN_VALUE
        // is rejected at posting admission; the guard stays for exactness.
        var reversalPostings = new ArrayList<PostingLine>(original.postings().size());
        for (PostingLine posting : original.postings()) {
            reversalPostings.add(new PostingLine(
                deterministicId(request.commandId(), "posting:" + posting.postingId()),
                posting.accountId(),
                posting.currency(),
                negateExact(posting.signedMinorUnits()),
                0,
                posting.dimensions()));
        }

        // The reversal posts under the book's current ACTIVE chart and policy version and the
        // caller's open period, not the original's historical coordinates: the repository
        // would reject a journal whose governance is no longer current.
        var reversal = new JournalDraft(
            deterministicId(request.commandId(), "journal:" + original.journalId()),
            request.commandId(),
            request.correlationId(),
            request.businessTransactionId(),
            original.legalEntityId(),
            original.bookId(),
            original.currentChartVersionId(),
            request.currentPeriodId(),
            "REVERSAL",
            request.reason(),
            request.bookingTime(),
            request.valueDate(),
            original.journalId(),
            original.currentPolicyVersion(),
            reversalPostings);
        validator.validate(reversal);
        try {
            return postingService.postTrustedReversal(new PostingCommand(
                request.commandId(),
                request.requestHash(),
                reversal));
        } catch (LedgerPersistenceException failure) {
            // Concurrent reversals of one original can both pass rejectExistingReversal; the
            // loser fails on the unique index and is reported as the same business error.
            if (hasConstraint(failure, SINGLE_REVERSAL_CONSTRAINT)) {
                throw new InvalidJournalException(
                    "original journal already has an exact reversal: " + original.journalId());
            }
            throw failure;
        }
    }

    /**
     * Read-only REPEATABLE READ pre-flight: either finds a completed same-content replay or
     * returns a verified snapshot of the original. Nothing here writes; the posting itself runs
     * later, SERIALIZABLE, inside PostingService. The pooled connection's autocommit,
     * read-only flag and isolation are saved first and restored on every exit path.
     */
    private LoadOutcome loadCoherentFact(ReversalRequest request) {
        try (Connection connection = dataSource.getConnection()) {
            ConnectionSettings settings = settings(connection);
            Throwable primary = null;
            try {
                connection.setAutoCommit(false);
                // One snapshot for header, postings and dimensions, so the re-hash below
                // cannot mix two versions of the ledger.
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                transactionTimeouts.apply(connection);

                Optional<PostingResult> completed = preflightCompleted(connection, request);
                if (completed.isPresent()) {
                    connection.commit();
                    return new LoadOutcome(completed, null);
                }

                OriginalJournalHeader header = loadVerifiedHeader(
                    connection,
                    request.originalJournalId());
                rejectExistingReversal(connection, request.originalJournalId());
                List<PostingLine> postings = loadBoundedPostings(
                    connection,
                    request.originalJournalId());
                OriginalJournal original = assembleAndVerifyOriginal(header, postings);
                connection.commit();
                return new LoadOutcome(Optional.empty(), original);
            } catch (SQLException failure) {
                var mapped = SqlState.persistenceFailure(failure);
                primary = mapped;
                rollback(connection, mapped);
                throw mapped;
            } catch (RuntimeException | Error failure) {
                primary = failure;
                rollback(connection, failure);
                throw failure;
            } finally {
                restore(connection, settings, primary);
            }
        } catch (SQLException failure) {
            throw SqlState.persistenceFailure(failure);
        }
    }

    private static void rejectExistingReversal(Connection connection, UUID originalJournalId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT journal_id
            FROM funds.journal
            WHERE reversal_of_journal_id = ? AND transaction_type = 'REVERSAL'
            LIMIT 1
            """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, originalJournalId);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw new InvalidJournalException(
                        "original journal already has an exact reversal: " + originalJournalId);
                }
            }
        }
    }

    /**
     * Idempotency replay for the reversal command. An IN_PROGRESS row must already carry this
     * TYPED_V2 hash; a COMPLETED row must point coherently at its own journal, and its request
     * hash is compared directly (TYPED_V2) or rebuilt from the journal (V004_OPAQUE).
     */
    private Optional<PostingResult> preflightCompleted(
        Connection connection,
        ReversalRequest request
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT command.request_hash, command.request_hash_scheme, command.state,
                   command.journal_id,
                   command.result_json ->> 'journalId' AS stored_journal_id,
                   command.result_json ->> 'journalSequence' AS stored_journal_sequence,
                   command.result_json ->> 'canonicalHash' AS stored_canonical_hash,
                   journal.journal_id AS joined_journal_id,
                   journal.journal_sequence AS joined_journal_sequence,
                   journal.canonical_hash AS joined_canonical_hash
            FROM funds.idempotency_command command
            LEFT JOIN funds.journal journal
              ON journal.journal_id = command.journal_id
             AND journal.command_id = command.command_id
            WHERE command.command_id = ?
            """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, request.commandId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String requestHashScheme = rows.getString("request_hash_scheme");
                boolean completed = "COMPLETED".equals(rows.getString("state"));
                if (!completed) {
                    if (!"TYPED_V2".equals(requestHashScheme)
                        || !request.requestHash().equals(rows.getString("request_hash"))) {
                        throw new IdempotencyConflictException(request.commandId());
                    }
                    return Optional.empty();
                }
                UUID commandJournalId = rows.getObject("journal_id", UUID.class);
                UUID joinedJournalId = rows.getObject("joined_journal_id", UUID.class);
                UUID storedJournalId = parseUuid(rows.getString("stored_journal_id"));
                long storedSequence = parseLong(rows.getString("stored_journal_sequence"));
                String storedHash = rows.getString("stored_canonical_hash");
                // Mirrors completed_command_result_consistency: result_json is a checked cache
                // of the journal row, never an independent source of truth.
                if (commandJournalId == null
                    || !commandJournalId.equals(joinedJournalId)
                    || !commandJournalId.equals(storedJournalId)
                    || storedSequence != rows.getLong("joined_journal_sequence")
                    || storedHash == null
                    || !storedHash.equals(rows.getString("joined_canonical_hash"))) {
                    throw new InvalidJournalException(
                        "completed command has inconsistent stored result: " + request.commandId());
                }
                if ("TYPED_V2".equals(requestHashScheme)) {
                    if (!request.requestHash().equals(rows.getString("request_hash"))) {
                        throw new IdempotencyConflictException(request.commandId());
                    }
                } else if ("V004_OPAQUE".equals(requestHashScheme)) {
                    verifyLegacyCompletedReversal(connection, request, commandJournalId);
                } else {
                    throw new IdempotencyConflictException(request.commandId());
                }
                return Optional.of(new PostingResult(storedJournalId, storedSequence, storedHash));
            }
        }
    }

    /**
     * Replay against a reversal committed under V004. Its stored request hash is opaque, so the
     * legacy journal is re-verified with the V004_V1 hasher and the typed reversalV2 hash is
     * rebuilt from the persisted header. The all-zero placeholder only satisfies the record's
     * format check; reversalV2 never digests the hash field itself.
     */
    private void verifyLegacyCompletedReversal(
        Connection connection,
        ReversalRequest request,
        UUID journalId
    ) throws SQLException {
        OriginalJournalHeader header = loadCompletedHistoricalHeader(connection, journalId);
        if (!"V004_V1".equals(header.canonicalHashScheme())
            || !"REVERSAL".equals(header.transactionType())
            || header.reversalOfJournalId() == null) {
            throw new IdempotencyConflictException(request.commandId());
        }
        List<PostingLine> postings = loadBoundedPostings(connection, journalId);
        assembleAndVerifyOriginal(header, postings);

        ReversalRequest persistedRequest = new ReversalRequest(
            header.commandId(),
            "0".repeat(64),
            header.reversalOfJournalId(),
            header.correlationId(),
            header.businessTransactionId(),
            header.periodId(),
            header.bookingTime(),
            header.valueDate(),
            header.narration());
        if (!request.requestHash().equals(commandHasher.reversalV2(persistedRequest))) {
            throw new IdempotencyConflictException(request.commandId());
        }
    }

    private static OriginalJournalHeader loadCompletedHistoricalHeader(
        Connection connection,
        UUID journalId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT journal.journal_id, journal.command_id, journal.journal_sequence,
                   journal.correlation_id, journal.business_transaction_id,
                   journal.legal_entity_id, journal.book_id, journal.period_id,
                   journal.chart_version_id, journal.transaction_type, journal.narration,
                   journal.booking_time, journal.value_date,
                   journal.reversal_of_journal_id, journal.policy_version,
                   journal.canonical_hash, journal.canonical_hash_scheme,
                   command.state AS command_state,
                   command.journal_id AS completed_journal_id
            FROM funds.journal journal
            JOIN funds.idempotency_command command
              ON command.command_id = journal.command_id
             AND command.journal_id = journal.journal_id
            WHERE journal.journal_id = ?
            """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()
                    || !"COMPLETED".equals(rows.getString("command_state"))
                    || !journalId.equals(rows.getObject("completed_journal_id", UUID.class))) {
                    throw new InvalidJournalException(
                        "legacy completed reversal has no coherent journal: " + journalId);
                }
                UUID historicalChart = rows.getObject("chart_version_id", UUID.class);
                int historicalPolicy = rows.getInt("policy_version");
                // The legacy reversal's own chart and policy fill the "current" slots too:
                // nothing is re-posted from this header, it is only re-hashed.
                return new OriginalJournalHeader(
                    rows.getObject("journal_id", UUID.class),
                    rows.getObject("command_id", UUID.class),
                    rows.getLong("journal_sequence"),
                    rows.getObject("correlation_id", UUID.class),
                    rows.getObject("business_transaction_id", UUID.class),
                    rows.getObject("legal_entity_id", UUID.class),
                    rows.getObject("book_id", UUID.class),
                    historicalChart,
                    rows.getObject("period_id", UUID.class),
                    rows.getString("transaction_type"),
                    rows.getString("narration"),
                    rows.getObject("booking_time", OffsetDateTime.class).toInstant(),
                    rows.getObject("value_date", LocalDate.class),
                    rows.getObject("reversal_of_journal_id", UUID.class),
                    historicalPolicy,
                    rows.getString("canonical_hash"),
                    rows.getString("canonical_hash_scheme"),
                    historicalPolicy,
                    historicalChart);
            }
        }
    }

    /**
     * Loads the original's header together with the book's current policy version and ACTIVE
     * chart. Only a journal that is the completed result of its own command may be reversed.
     */
    private static OriginalJournalHeader loadVerifiedHeader(Connection connection, UUID journalId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT journal.journal_id, journal.command_id, journal.journal_sequence,
                   journal.correlation_id, journal.business_transaction_id,
                   journal.legal_entity_id, journal.book_id, journal.period_id,
                   journal.chart_version_id,
                   journal.transaction_type, journal.narration, journal.booking_time,
                   journal.value_date, journal.reversal_of_journal_id,
                   journal.policy_version, journal.canonical_hash,
                   journal.canonical_hash_scheme,
                   command.state AS command_state,
                   command.journal_id AS completed_journal_id,
                   book.accounting_policy_version AS current_policy_version,
                   current_chart.chart_version_id AS current_chart_version_id
            FROM funds.journal journal
            JOIN funds.idempotency_command command
              ON command.command_id = journal.command_id
            JOIN funds.book book ON book.book_id = journal.book_id
            JOIN funds.chart_version current_chart
              ON current_chart.book_id = book.book_id
             AND current_chart.status = 'ACTIVE'
            WHERE journal.journal_id = ?
            """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException("original journal does not exist: " + journalId);
                }
                UUID completedJournalId = rows.getObject("completed_journal_id", UUID.class);
                if (!"COMPLETED".equals(rows.getString("command_state"))
                    || !journalId.equals(completedJournalId)) {
                    throw new InvalidJournalException(
                        "original journal is not the result of a completed command: " + journalId);
                }
                return new OriginalJournalHeader(
                    rows.getObject("journal_id", UUID.class),
                    rows.getObject("command_id", UUID.class),
                    rows.getLong("journal_sequence"),
                    rows.getObject("correlation_id", UUID.class),
                    rows.getObject("business_transaction_id", UUID.class),
                    rows.getObject("legal_entity_id", UUID.class),
                    rows.getObject("book_id", UUID.class),
                    rows.getObject("chart_version_id", UUID.class),
                    rows.getObject("period_id", UUID.class),
                    rows.getString("transaction_type"),
                    rows.getString("narration"),
                    rows.getObject("booking_time", OffsetDateTime.class).toInstant(),
                    rows.getObject("value_date", LocalDate.class),
                    rows.getObject("reversal_of_journal_id", UUID.class),
                    rows.getInt("policy_version"),
                    rows.getString("canonical_hash"),
                    rows.getString("canonical_hash_scheme"),
                    rows.getInt("current_policy_version"),
                    rows.getObject("current_chart_version_id", UUID.class));
            }
        }
    }

    /**
     * Reassembles the original's postings in account_sequence order, refusing anything past the
     * POC limits instead of reading it. The bounds equal JournalValidator's and the V005 posting
     * envelope, so exceeding one here means corrupted or out-of-envelope data, not a large input.
     */
    private static List<PostingLine> loadBoundedPostings(Connection connection, UUID journalId)
        throws SQLException {
        Map<UUID, PostingBuilder> builders = loadPostingSummaries(connection, journalId);
        loadDimensions(connection, journalId, builders);
        var postings = new ArrayList<PostingLine>(builders.size());
        for (PostingBuilder builder : builders.values()) {
            postings.add(builder.build());
        }
        return List.copyOf(postings);
    }

    private static Map<UUID, PostingBuilder> loadPostingSummaries(
        Connection connection,
        UUID journalId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT posting.posting_id, posting.account_id, posting.currency,
                   posting.signed_minor_units, posting.account_sequence,
                   octet_length(posting.dimensions::text) AS dimension_json_bytes
            FROM funds.posting posting
            WHERE posting.journal_id = ?
            ORDER BY posting.account_sequence, posting.posting_id
            LIMIT ?
            """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, journalId);
            // Fetch one row past the limit so an oversize journal is detected, not truncated.
            statement.setInt(2, MAX_POSTINGS_PER_JOURNAL + 1);
            try (var rows = statement.executeQuery()) {
                Map<UUID, PostingBuilder> builders = new LinkedHashMap<>();
                while (rows.next()) {
                    if (builders.size() == MAX_POSTINGS_PER_JOURNAL) {
                        throw new InvalidJournalException(
                            "original journal exceeds POC posting limit of "
                                + MAX_POSTINGS_PER_JOURNAL);
                    }
                    // Same expression as posting_dimensions_bytes_check, evaluated server-side.
                    int dimensionBytes = rows.getInt("dimension_json_bytes");
                    if (dimensionBytes > MAX_DIMENSION_JSON_BYTES) {
                        throw new InvalidJournalException(
                            "posting dimension JSON exceeds POC byte limit of "
                                + MAX_DIMENSION_JSON_BYTES);
                    }
                    UUID postingId = rows.getObject("posting_id", UUID.class);
                    builders.put(postingId, new PostingBuilder(
                        postingId,
                        rows.getObject("account_id", UUID.class),
                        CurrencyCode.of(rows.getString("currency")),
                        rows.getLong("signed_minor_units"),
                        rows.getLong("account_sequence"),
                        new LinkedHashMap<>()));
                }
                if (builders.isEmpty()) {
                    throw new InvalidJournalException("original journal has no postings: " + journalId);
                }
                return builders;
            }
        }
    }

    private static void loadDimensions(
        Connection connection,
        UUID journalId,
        Map<UUID, PostingBuilder> builders
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT posting.posting_id, dimension.key, dimension.value
            FROM funds.posting posting
            CROSS JOIN LATERAL jsonb_each_text(posting.dimensions) dimension
            WHERE posting.journal_id = ?
            ORDER BY posting.account_sequence, posting.posting_id, dimension.key
            LIMIT ?
            """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, journalId);
            // Flattened key/value rows, again fetched one past the limit to detect overflow.
            statement.setInt(2, MAX_DIMENSION_ROWS + 1);
            int rowsRead = 0;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rowsRead++ == MAX_DIMENSION_ROWS) {
                        throw new InvalidJournalException("original journal dimension rows exceed POC limit");
                    }
                    UUID postingId = rows.getObject("posting_id", UUID.class);
                    PostingBuilder builder = builders.get(postingId);
                    if (builder == null) {
                        throw new InvalidJournalException(
                            "posting set changed within reversal snapshot: " + postingId);
                    }
                    if (builder.dimensions().size() == MAX_DIMENSIONS_PER_POSTING) {
                        throw new InvalidJournalException(
                            "posting exceeds POC dimension limit of "
                                + MAX_DIMENSIONS_PER_POSTING);
                    }
                    builder.dimensions().put(rows.getString("key"), rows.getString("value"));
                }
            }
        }
    }

    /**
     * Rebuilds the persisted journal and recomputes its canonical hash with the verifier named
     * by its scheme tag: V004_V1 for facts written before V005, V2 (which adds chartVersionId)
     * for everything since. A mismatch means the stored postings no longer match the fact that
     * was committed, and nothing derived from them may be posted.
     */
    private OriginalJournal assembleAndVerifyOriginal(
        OriginalJournalHeader header,
        List<PostingLine> postings
    ) {
        var persisted = new JournalDraft(
            header.journalId(),
            header.commandId(),
            header.correlationId(),
            header.businessTransactionId(),
            header.legalEntityId(),
            header.bookId(),
            header.historicalChartVersionId(),
            header.periodId(),
            header.transactionType(),
            header.narration(),
            header.bookingTime(),
            header.valueDate(),
            header.reversalOfJournalId(),
            header.historicalPolicyVersion(),
            postings);
        validator.validate(persisted);
        String verifiedHash = switch (header.canonicalHashScheme()) {
            case "V004_V1" -> hasher.v004Sha256(persisted);
            case "V2" -> hasher.v2Sha256(persisted);
            default -> throw new InvalidJournalException(
                "unsupported journal hash scheme: " + header.canonicalHashScheme());
        };
        if (!header.canonicalHash().equals(verifiedHash)) {
            throw new InvalidJournalException(
                "original journal canonical hash does not match its persisted postings: "
                    + header.journalId());
        }
        return new OriginalJournal(
            header.journalId(),
            header.legalEntityId(),
            header.bookId(),
            header.reversalOfJournalId(),
            header.currentChartVersionId(),
            header.currentPolicyVersion(),
            postings);
    }

    private static ConnectionSettings settings(Connection connection) throws SQLException {
        return new ConnectionSettings(
            connection.getAutoCommit(),
            connection.isReadOnly(),
            connection.getTransactionIsolation());
    }

    // A suppressed rollback failure must not hide the original cause.
    private static void rollback(Connection connection, Throwable primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }

    // Each setting is restored independently so one failure cannot skip the others. Restore
    // failures attach to the primary error when there is one; otherwise they become the error,
    // because returning a mis-configured connection to the pool is not acceptable.
    private static void restore(
        Connection connection,
        ConnectionSettings settings,
        Throwable primary
    ) {
        SQLException restorationFailure = null;
        try {
            connection.setReadOnly(settings.readOnly());
        } catch (SQLException failure) {
            restorationFailure = failure;
        }
        try {
            connection.setTransactionIsolation(settings.isolation());
        } catch (SQLException failure) {
            restorationFailure = append(restorationFailure, failure);
        }
        try {
            connection.setAutoCommit(settings.autoCommit());
        } catch (SQLException failure) {
            restorationFailure = append(restorationFailure, failure);
        }
        if (restorationFailure != null) {
            if (primary != null) {
                primary.addSuppressed(restorationFailure);
            } else {
                throw SqlState.persistenceFailure(restorationFailure);
            }
        }
    }

    private static SQLException append(SQLException first, SQLException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidJournalException("completed command contains malformed journal ID");
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException malformed) {
            throw new InvalidJournalException("completed command contains malformed journal sequence");
        }
    }

    private static long negateExact(long value) {
        try {
            return Math.negateExact(value);
        } catch (ArithmeticException overflow) {
            throw new MonetaryOverflowException(overflow);
        }
    }

    // Walks the cause chain: the PSQLException arrives wrapped in LedgerPersistenceException.
    private static boolean hasConstraint(Throwable failure, String expectedConstraint) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PSQLException postgresFailure
                && postgresFailure.getServerErrorMessage() != null
                && expectedConstraint.equals(
                    postgresFailure.getServerErrorMessage().getConstraint())) {
                return true;
            }
        }
        return false;
    }

    // Name-based UUIDs in a private namespace: the same command always yields the same journal
    // and posting IDs, so a replay after a crash cannot mint a second identity.
    private static UUID deterministicId(UUID commandId, String component) {
        return UUID.nameUUIDFromBytes(
            ("funds-reversal:" + commandId + ":" + component).getBytes(StandardCharsets.UTF_8));
    }

    /** Exactly one side is present: a completed replay result, or a fresh verified original. */
    private record LoadOutcome(Optional<PostingResult> completedResult, OriginalJournal original) {}

    /** Pooled-connection state captured before the pre-flight and restored after it. */
    private record ConnectionSettings(boolean autoCommit, boolean readOnly, int isolation) {}

    /** Persisted journal header plus the book's current governance coordinates. */
    private record OriginalJournalHeader(
        UUID journalId,
        UUID commandId,
        long journalSequence,
        UUID correlationId,
        UUID businessTransactionId,
        UUID legalEntityId,
        UUID bookId,
        UUID historicalChartVersionId,
        UUID periodId,
        String transactionType,
        String narration,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOfJournalId,
        int historicalPolicyVersion,
        String canonicalHash,
        String canonicalHashScheme,
        int currentPolicyVersion,
        UUID currentChartVersionId) {}

    /** Verified original: identity, reversal link, current governance and postings to negate. */
    private record OriginalJournal(
        UUID journalId,
        UUID legalEntityId,
        UUID bookId,
        UUID reversalOfJournalId,
        UUID currentChartVersionId,
        int currentPolicyVersion,
        List<PostingLine> postings) {}

    private record PostingBuilder(
        UUID postingId,
        UUID accountId,
        CurrencyCode currency,
        long signedMinorUnits,
        long accountSequence,
        Map<String, String> dimensions) {

        private PostingLine build() {
            return new PostingLine(
                postingId,
                accountId,
                currency,
                signedMinorUnits,
                accountSequence,
                dimensions);
        }
    }
}
