package com.corebanking.funds.application;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.ReversalRequest;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
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

@ApplicationScoped
public class ReversalService {
    // POC guardrails keep a single correction comfortably bounded on an 8 GiB VM.
    static final int MAX_POSTINGS_PER_JOURNAL = 256;
    static final int MAX_DIMENSIONS_PER_POSTING = 32;
    static final int MAX_DIMENSION_JSON_BYTES = 8_192;
    static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int MAX_DIMENSION_ROWS =
        MAX_POSTINGS_PER_JOURNAL * MAX_DIMENSIONS_PER_POSTING;
    private static final String SINGLE_REVERSAL_CONSTRAINT = "one_reversal_per_original_idx";

    private final DataSource dataSource;
    private final PostingService postingService;
    private final JournalValidator validator;
    private final CanonicalJournalHasher hasher;

    @Inject
    public ReversalService(DataSource dataSource, PostingService postingService) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.validator = new JournalValidator();
        this.hasher = new CanonicalJournalHasher();
    }

    public PostingResult reverse(ReversalRequest request) {
        Objects.requireNonNull(request, "request");
        LoadOutcome loaded = loadCoherentFact(request);
        if (loaded.completedResult().isPresent()) {
            return loaded.completedResult().orElseThrow();
        }
        OriginalJournal original = loaded.original();
        if (original.reversalOfJournalId() != null) {
            throw new InvalidJournalException(
                "reversal of a reversal requires an explicit correction template policy");
        }

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

        var reversal = new JournalDraft(
            deterministicId(request.commandId(), "journal:" + original.journalId()),
            request.commandId(),
            request.correlationId(),
            request.businessTransactionId(),
            original.legalEntityId(),
            original.bookId(),
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
            return postingService.post(new PostingCommand(
                request.commandId(),
                request.requestHash(),
                reversal));
        } catch (LedgerPersistenceException failure) {
            if (hasConstraint(failure, SINGLE_REVERSAL_CONSTRAINT)) {
                throw new InvalidJournalException(
                    "original journal already has an exact reversal: " + original.journalId());
            }
            throw failure;
        }
    }

    private LoadOutcome loadCoherentFact(ReversalRequest request) {
        try (Connection connection = dataSource.getConnection()) {
            ConnectionSettings settings = settings(connection);
            Throwable primary = null;
            try {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);

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
                var mapped = new LedgerPersistenceException(failure);
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
            throw new LedgerPersistenceException(failure);
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

    private static Optional<PostingResult> preflightCompleted(
        Connection connection,
        ReversalRequest request
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT command.request_hash, command.state, command.journal_id,
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
                if (!request.requestHash().equals(rows.getString("request_hash"))) {
                    throw new IdempotencyConflictException(request.commandId());
                }
                if (!"COMPLETED".equals(rows.getString("state"))) {
                    return Optional.empty();
                }
                UUID commandJournalId = rows.getObject("journal_id", UUID.class);
                UUID joinedJournalId = rows.getObject("joined_journal_id", UUID.class);
                UUID storedJournalId = parseUuid(rows.getString("stored_journal_id"));
                long storedSequence = parseLong(rows.getString("stored_journal_sequence"));
                String storedHash = rows.getString("stored_canonical_hash");
                if (commandJournalId == null
                    || !commandJournalId.equals(joinedJournalId)
                    || !commandJournalId.equals(storedJournalId)
                    || storedSequence != rows.getLong("joined_journal_sequence")
                    || storedHash == null
                    || !storedHash.equals(rows.getString("joined_canonical_hash"))) {
                    throw new InvalidJournalException(
                        "completed command has inconsistent stored result: " + request.commandId());
                }
                return Optional.of(new PostingResult(storedJournalId, storedSequence, storedHash));
            }
        }
    }

    private static OriginalJournalHeader loadVerifiedHeader(Connection connection, UUID journalId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT journal.journal_id, journal.command_id, journal.journal_sequence,
                   journal.correlation_id, journal.business_transaction_id,
                   journal.legal_entity_id, journal.book_id, journal.period_id,
                   journal.transaction_type, journal.narration, journal.booking_time,
                   journal.value_date, journal.reversal_of_journal_id,
                   journal.policy_version, journal.canonical_hash,
                   command.state AS command_state,
                   command.journal_id AS completed_journal_id,
                   book.accounting_policy_version AS current_policy_version
            FROM funds.journal journal
            JOIN funds.idempotency_command command
              ON command.command_id = journal.command_id
            JOIN funds.book book ON book.book_id = journal.book_id
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
                    rows.getObject("period_id", UUID.class),
                    rows.getString("transaction_type"),
                    rows.getString("narration"),
                    rows.getObject("booking_time", OffsetDateTime.class).toInstant(),
                    rows.getObject("value_date", LocalDate.class),
                    rows.getObject("reversal_of_journal_id", UUID.class),
                    rows.getInt("policy_version"),
                    rows.getString("canonical_hash"),
                    rows.getInt("current_policy_version"));
            }
        }
    }

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
            statement.setInt(2, MAX_POSTINGS_PER_JOURNAL + 1);
            try (var rows = statement.executeQuery()) {
                Map<UUID, PostingBuilder> builders = new LinkedHashMap<>();
                while (rows.next()) {
                    if (builders.size() == MAX_POSTINGS_PER_JOURNAL) {
                        throw new InvalidJournalException(
                            "original journal exceeds POC posting limit of "
                                + MAX_POSTINGS_PER_JOURNAL);
                    }
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
            header.periodId(),
            header.transactionType(),
            header.narration(),
            header.bookingTime(),
            header.valueDate(),
            header.reversalOfJournalId(),
            header.historicalPolicyVersion(),
            postings);
        validator.validate(persisted);
        if (!header.canonicalHash().equals(hasher.sha256(persisted))) {
            throw new InvalidJournalException(
                "original journal canonical hash does not match its persisted postings: "
                    + header.journalId());
        }
        return new OriginalJournal(
            header.journalId(),
            header.legalEntityId(),
            header.bookId(),
            header.reversalOfJournalId(),
            header.currentPolicyVersion(),
            postings);
    }

    private static ConnectionSettings settings(Connection connection) throws SQLException {
        return new ConnectionSettings(
            connection.getAutoCommit(),
            connection.isReadOnly(),
            connection.getTransactionIsolation());
    }

    private static void rollback(Connection connection, Throwable primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }

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
                throw new LedgerPersistenceException(restorationFailure);
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

    private static UUID deterministicId(UUID commandId, String component) {
        return UUID.nameUUIDFromBytes(
            ("funds-reversal:" + commandId + ":" + component).getBytes(StandardCharsets.UTF_8));
    }

    private record LoadOutcome(Optional<PostingResult> completedResult, OriginalJournal original) {}

    private record ConnectionSettings(boolean autoCommit, boolean readOnly, int isolation) {}

    private record OriginalJournalHeader(
        UUID journalId,
        UUID commandId,
        long journalSequence,
        UUID correlationId,
        UUID businessTransactionId,
        UUID legalEntityId,
        UUID bookId,
        UUID periodId,
        String transactionType,
        String narration,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOfJournalId,
        int historicalPolicyVersion,
        String canonicalHash,
        int currentPolicyVersion) {}

    private record OriginalJournal(
        UUID journalId,
        UUID legalEntityId,
        UUID bookId,
        UUID reversalOfJournalId,
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
