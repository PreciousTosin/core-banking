package com.corebanking.funds.infrastructure.postgres;

import com.corebanking.funds.application.CanonicalJournalHasher;
import com.corebanking.funds.application.CanonicalCommandHasher;
import com.corebanking.funds.application.JournalValidator;
import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingDimensions;
import com.corebanking.funds.application.PostingResult;
import com.corebanking.funds.application.PostingTransactionObserver;
import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.AccountingPeriodClosedException;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerCapacityException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * SQL of one posting transaction; PostingService owns the connection, isolation, deadlines and
 * commit. {@link #post} runs, in order: insertIdempotencyCommand (claim the command row) ->
 * lockIdempotencyCommand (scheme and request-hash check under FOR UPDATE) -> completed replay
 * -> validateBookAndPeriod (chart row, then book row, then period, all FOR SHARE, through
 * funds.lock_book_chart_for_posting and funds.lock_period_for_posting) ->
 * lockAccountsAndBalances (accounts FOR UPDATE with their mappings FOR SHARE, then materialised
 * balances FOR UPDATE, each pass in canonical UUID-string order) -> assignAccountSequences ->
 * validate and hash (canonical scheme V2) -> insertJournal -> insertPostings ->
 * updateMaterialisedBalances -> updateControlProjection (control keys in code-then-currency
 * order) -> insertOutbox -> completeIdempotencyCommand.
 *
 * <p>One global lock order, shared with chart governance (chart before book, V006) and with
 * every concurrent journal (UUID-string account order), is what keeps postings from
 * deadlocking each other or rotate_chart_version; PostgresRetryPolicy still retries 40P01 as a
 * backstop. All money and coordinate arithmetic is checked: addMoneyExact raises
 * MonetaryOverflowException and addCapacityExact raises LedgerCapacityException naming the
 * exhausted coordinate. PostgreSQL re-checks the same governance in its own triggers (V005
 * journal_governance, posting_chart_mapping), so the Java checks exist to fail early with a
 * domain exception, not as the only guard.
 */
@ApplicationScoped
public class JdbcLedgerRepository implements LedgerRepository {
    // Driver-side cancel for the replay reads (findCompleted and the V004 loaders), which run
    // before any row lock is taken. Independent of the transaction-local statement_timeout that
    // PostingService applies, so a caller using the repository directly is still bounded.
    private static final int LEGACY_QUERY_TIMEOUT_SECONDS = 5;
    // The same total order CanonicalJournalHasher sorts postings by. Every journal locks its
    // accounts in this order, so overlapping account sets can never be taken in opposite
    // directions by two transactions.
    private static final Comparator<UUID> CANONICAL_ACCOUNT_ORDER = Comparator.comparing(UUID::toString);
    // Control projections are locked in this order for the same reason (updateControlProjection).
    private static final Comparator<ControlKey> CONTROL_ORDER = Comparator
        .comparing(ControlKey::controlAccountCode)
        .thenComparing(key -> key.currency().value());

    private final JournalValidator validator;
    private final CanonicalJournalHasher hasher;
    private final CanonicalCommandHasher commandHasher;
    private final PostingTransactionObserver observer;

    public JdbcLedgerRepository() {
        this(new JournalValidator(), new CanonicalJournalHasher(), PostingTransactionObserver.noop());
    }

    public JdbcLedgerRepository(JournalValidator validator, CanonicalJournalHasher hasher) {
        this(validator, hasher, PostingTransactionObserver.noop());
    }

    @Inject
    public JdbcLedgerRepository(PostingTransactionObserver observer) {
        this(new JournalValidator(), new CanonicalJournalHasher(), observer);
    }

    public JdbcLedgerRepository(
        JournalValidator validator,
        CanonicalJournalHasher hasher,
        PostingTransactionObserver observer
    ) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.commandHasher = new CanonicalCommandHasher();
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Executes the posting sequence described on the class. A command found COMPLETED under the
     * row lock is replayed here as well, so the method stays correct without the findCompleted
     * pre-flight. SQL failures are classified by SqlState; the caller rolls back on any throw.
     */
    @Override
    public PostingResult post(Connection connection, PostingCommand command) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(command, "command");
        try {
            insertIdempotencyCommand(connection, command);
            LockedCommand locked = lockIdempotencyCommand(connection, command.commandId());
            // A non-TYPED_V2 row can never be claimed by this path: a V004_OPAQUE hash is not
            // comparable with a typed hash, and V005 forbids creating new legacy rows.
            if (!"TYPED_V2".equals(locked.requestHashScheme())
                || !locked.requestHash().equals(command.requestHash())) {
                throw new IdempotencyConflictException(command.commandId());
            }
            observer.afterIdempotencyAcquired(command.commandId());
            if (locked.completed()) {
                return loadCompletedResult(connection, command.commandId());
            }

            validateBookAndPeriod(connection, command.journal());
            List<UUID> accountIds = canonicalAccountIds(command.journal());
            Map<UUID, AccountState> accounts = lockAccountsAndBalances(
                connection,
                command.journal(),
                accountIds);
            observer.afterAccountLocks(command.commandId());
            JournalDraft assignedJournal = assignAccountSequences(command.journal(), accounts);
            validator.validate(assignedJournal);
            // Scheme V2 pins chartVersionId; V004_V1 did not (see loadLegacyJournal).
            String canonicalHash = hasher.v2Sha256(assignedJournal);

            long journalSequence = insertJournal(connection, assignedJournal, canonicalHash);
            insertPostings(connection, assignedJournal);
            updateMaterialisedBalances(connection, assignedJournal, accounts);
            updateControlProjection(connection, assignedJournal, accounts, journalSequence);
            observer.afterFinancialRowsBeforeOutbox(command.commandId());
            insertOutbox(connection, assignedJournal.journalId(), journalSequence, canonicalHash);

            var result = new PostingResult(assignedJournal.journalId(), journalSequence, canonicalHash);
            completeIdempotencyCommand(connection, command.commandId(), result);
            return result;
        } catch (SQLException failure) {
            throw SqlState.persistenceFailure(failure);
        }
    }

    /**
     * Rebuilds a pre-V005 journal from its stored rows and proves it against its own V004_V1
     * canonical hash before anything is derived from it. The stored fact is re-hashed rather than
     * trusted because the V004 command hash is opaque: the only way to decide whether a replay
     * carries the same content is to recompute the typed V2 command hash from facts that have
     * themselves been authenticated. Row reads are capped at the POC limits plus one so an
     * oversized or inconsistent legacy journal fails fast instead of being read without bound.
     */
    private JournalDraft loadLegacyJournal(
        Connection connection,
        UUID commandId,
        UUID journalId,
        String canonicalHash
    ) throws SQLException {
        LegacyHeader header = loadLegacyHeader(connection, commandId, journalId);
        Map<UUID, LegacyPostingBuilder> builders = loadLegacyPostingSummaries(
            connection, journalId);
        loadLegacyDimensions(connection, journalId, builders);
        var postings = new ArrayList<PostingLine>(builders.size());
        for (LegacyPostingBuilder builder : builders.values()) {
            postings.add(builder.build());
        }
        var persisted = new JournalDraft(
            header.journalId(),
            header.commandId(),
            header.correlationId(),
            header.businessTransactionId(),
            header.legalEntityId(),
            header.bookId(),
            header.chartVersionId(),
            header.periodId(),
            header.transactionType(),
            header.narration(),
            header.bookingTime(),
            header.valueDate(),
            header.reversalOfJournalId(),
            header.policyVersion(),
            postings);
        validator.validate(persisted);
        if (!canonicalHash.equals(hasher.v004Sha256(persisted))) {
            throw new InvalidJournalException(
                "migrated V004 journal hash does not match persisted facts: " + journalId);
        }
        return persisted;
    }

    /** Matches journal_id and command_id together (the V005 composite link), not either alone. */
    private static LegacyHeader loadLegacyHeader(
        Connection connection,
        UUID commandId,
        UUID journalId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT journal_id, command_id, correlation_id, business_transaction_id,
                   legal_entity_id, book_id, chart_version_id, period_id,
                   transaction_type, narration, booking_time, value_date,
                   reversal_of_journal_id, policy_version
            FROM funds.journal
            WHERE journal_id = ? AND command_id = ?
            """)) {
            statement.setQueryTimeout(LEGACY_QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, journalId);
            statement.setObject(2, commandId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException(
                        "migrated completed command has no coherent journal: " + commandId);
                }
                return new LegacyHeader(
                    rows.getObject("journal_id", UUID.class),
                    rows.getObject("command_id", UUID.class),
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
                    rows.getInt("policy_version"));
            }
        }
    }

    private static Map<UUID, LegacyPostingBuilder> loadLegacyPostingSummaries(
        Connection connection,
        UUID journalId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT posting_id, account_id, currency, signed_minor_units,
                   account_sequence, octet_length(dimensions::text) AS dimension_json_bytes
            FROM funds.posting
            WHERE journal_id = ?
            ORDER BY posting_id
            LIMIT ?
            """)) {
            statement.setQueryTimeout(LEGACY_QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, journalId);
            statement.setInt(2, JournalValidator.MAX_POSTINGS_PER_JOURNAL + 1);
            try (var rows = statement.executeQuery()) {
                Map<UUID, LegacyPostingBuilder> builders = new LinkedHashMap<>();
                while (rows.next()) {
                    if (builders.size() == JournalValidator.MAX_POSTINGS_PER_JOURNAL) {
                        throw new InvalidJournalException(
                            "migrated journal exceeds POC posting limit");
                    }
                    if (rows.getInt("dimension_json_bytes")
                        > JournalValidator.MAX_DIMENSION_JSON_BYTES) {
                        throw new InvalidJournalException(
                            "migrated journal dimension JSON exceeds POC byte limit");
                    }
                    UUID postingId = rows.getObject("posting_id", UUID.class);
                    builders.put(postingId, new LegacyPostingBuilder(
                        postingId,
                        rows.getObject("account_id", UUID.class),
                        CurrencyCode.of(rows.getString("currency")),
                        rows.getLong("signed_minor_units"),
                        rows.getLong("account_sequence"),
                        new LinkedHashMap<>()));
                }
                if (builders.isEmpty()) {
                    throw new InvalidJournalException(
                        "migrated journal has no postings: " + journalId);
                }
                return builders;
            }
        }
    }

    /**
     * Flattens every dimension so the per-posting count limit is checked row by row. The hasher
     * re-sorts postings and keys, so the ORDER BY only makes the LIMIT deterministic.
     */
    private static void loadLegacyDimensions(
        Connection connection,
        UUID journalId,
        Map<UUID, LegacyPostingBuilder> builders
    ) throws SQLException {
        int maximumRows = JournalValidator.MAX_POSTINGS_PER_JOURNAL
            * JournalValidator.MAX_DIMENSIONS_PER_POSTING;
        try (var statement = connection.prepareStatement("""
            SELECT posting.posting_id, dimension.key, dimension.value
            FROM funds.posting posting
            CROSS JOIN LATERAL jsonb_each_text(posting.dimensions) dimension
            WHERE posting.journal_id = ?
            ORDER BY posting.posting_id, dimension.key
            LIMIT ?
            """)) {
            statement.setQueryTimeout(LEGACY_QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, journalId);
            statement.setInt(2, maximumRows + 1);
            int rowsRead = 0;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rowsRead++ == maximumRows) {
                        throw new InvalidJournalException(
                            "migrated journal dimension rows exceed POC limit");
                    }
                    UUID postingId = rows.getObject("posting_id", UUID.class);
                    LegacyPostingBuilder builder = builders.get(postingId);
                    if (builder == null) {
                        throw new InvalidJournalException(
                            "migrated journal posting set is inconsistent: " + postingId);
                    }
                    if (builder.dimensions().size()
                        == JournalValidator.MAX_DIMENSIONS_PER_POSTING) {
                        throw new InvalidJournalException(
                            "migrated posting exceeds POC dimension limit");
                    }
                    builder.dimensions().put(rows.getString("key"), rows.getString("value"));
                }
            }
        }
    }

    /**
     * Pre-flight replay check, run by PostingService before validation and before any lock. The
     * result_json cache is never returned on its own: coherentCompletedResult requires the
     * command's journal_id, the joined journal row and the cached fields to agree, mirroring the
     * identity V005 enforces at write time. TYPED_V2 rows compare request hashes directly, and an
     * IN_PROGRESS one yields empty, leaving the decision to post() and its row lock. V004_OPAQUE
     * rows cannot be compared by hash, so a completed one is re-verified from stored facts
     * (loadLegacyJournal) and an incomplete one is a conflict outright.
     */
    @Override
    public Optional<PostingResult> findCompleted(
        Connection connection,
        PostingCommand command
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(command, "command");
        UUID commandId = command.commandId();
        try (var statement = connection.prepareStatement("""
            SELECT command.request_hash, command.request_hash_scheme, command.state,
                   command.journal_id AS command_journal_id,
                   command.result_json ->> 'journalId' AS stored_journal_id,
                   command.result_json ->> 'journalSequence' AS stored_journal_sequence,
                   command.result_json ->> 'canonicalHash' AS stored_canonical_hash,
                   journal.journal_id, journal.journal_sequence, journal.canonical_hash,
                   journal.canonical_hash_scheme
            FROM funds.idempotency_command command
            LEFT JOIN funds.journal journal
              ON journal.journal_id = command.journal_id
             AND journal.command_id = command.command_id
            WHERE command.command_id = ?
            """)) {
            statement.setQueryTimeout(LEGACY_QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, commandId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String commandScheme = rows.getString("request_hash_scheme");
                boolean completed = "COMPLETED".equals(rows.getString("state"));
                if ("TYPED_V2".equals(commandScheme)) {
                    if (!command.requestHash().equals(rows.getString("request_hash"))) {
                        throw new IdempotencyConflictException(commandId);
                    }
                    if (!completed) {
                        return Optional.empty();
                    }
                } else if ("V004_OPAQUE".equals(commandScheme)) {
                    if (!completed) {
                        throw new IdempotencyConflictException(commandId);
                    }
                } else {
                    throw new InvalidJournalException(
                        "unsupported command hash scheme for command: " + commandId);
                }
                if (!completed) {
                    return Optional.empty();
                }
                PostingResult result = coherentCompletedResult(rows, commandId);
                if ("V004_OPAQUE".equals(commandScheme)) {
                    if (!"V004_V1".equals(rows.getString("canonical_hash_scheme"))) {
                        throw new InvalidJournalException(
                            "migrated command does not reference a V004 journal: " + commandId);
                    }
                    JournalDraft persisted = loadLegacyJournal(
                        connection, commandId, result.journalId(), result.canonicalHash());
                    if (!command.requestHash().equals(commandHasher.postingV2(persisted))) {
                        throw new IdempotencyConflictException(commandId);
                    }
                }
                return Optional.of(result);
            }
        } catch (SQLException failure) {
            throw SqlState.persistenceFailure(failure);
        }
    }

    /** Disagreement between pointer, journal row and cached result is corruption, not a replay. */
    private static PostingResult coherentCompletedResult(
        java.sql.ResultSet rows,
        UUID commandId
    ) throws SQLException {
        UUID commandJournalId = rows.getObject("command_journal_id", UUID.class);
        UUID joinedJournalId = rows.getObject("journal_id", UUID.class);
        UUID storedJournalId = parseUuid(rows.getString("stored_journal_id"));
        long storedSequence = parseLong(rows.getString("stored_journal_sequence"));
        String storedHash = rows.getString("stored_canonical_hash");
        if (commandJournalId == null
            || !commandJournalId.equals(joinedJournalId)
            || !commandJournalId.equals(storedJournalId)
            || storedSequence != rows.getLong("journal_sequence")
            || storedHash == null
            || !storedHash.equals(rows.getString("canonical_hash"))) {
            throw new InvalidJournalException(
                "completed command has inconsistent stored result: " + commandId);
        }
        return new PostingResult(storedJournalId, storedSequence, storedHash);
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
            throw new InvalidJournalException(
                "completed command contains malformed journal sequence");
        }
    }

    /**
     * Claims the command row before any financial work. ON CONFLICT DO NOTHING plus the FOR
     * UPDATE that follows turn the row into the per-command mutex, and V003.2 refuses postings
     * whose command row is missing or already COMPLETED.
     */
    private static void insertIdempotencyCommand(Connection connection, PostingCommand command)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.idempotency_command
                (command_id, request_hash, request_hash_scheme, state, created_at)
            VALUES (?, ?, 'TYPED_V2', 'IN_PROGRESS', CURRENT_TIMESTAMP)
            ON CONFLICT (command_id) DO NOTHING
            """)) {
            statement.setObject(1, command.commandId());
            statement.setString(2, command.requestHash());
            statement.executeUpdate();
        }
    }

    /**
     * A row lock, not just a read: concurrent same-key requests queue here and the loser observes
     * the winner's final state.
     */
    private static LockedCommand lockIdempotencyCommand(Connection connection, UUID commandId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT request_hash, request_hash_scheme, state
            FROM funds.idempotency_command
            WHERE command_id = ?
            FOR UPDATE
            """)) {
            statement.setObject(1, commandId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("idempotency row disappeared for command " + commandId);
                }
                return new LockedCommand(
                    rows.getString("request_hash"),
                    rows.getString("request_hash_scheme"),
                    "COMPLETED".equals(rows.getString("state")));
            }
        }
    }

    /**
     * Replay from inside post(), after the row lock. The full coherence checks live in
     * findCompleted, which is the normal replay path.
     */
    private static PostingResult loadCompletedResult(Connection connection, UUID commandId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT journal_id, journal_sequence, canonical_hash
            FROM funds.journal
            WHERE command_id = ?
            """)) {
            statement.setObject(1, commandId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("completed command has no journal: " + commandId);
                }
                return new PostingResult(
                    rows.getObject("journal_id", UUID.class),
                    rows.getLong("journal_sequence"),
                    rows.getString("canonical_hash"));
            }
        }
    }

    /**
     * Locks chart then book (FOR SHARE) through funds.lock_book_chart_for_posting, the order
     * rotate_chart_version and the mapping triggers use (V006), then the period. Share locks let
     * concurrent postings proceed while stopping a rotation or period close from slipping in
     * between validation and commit. The booking date is derived in the book's timezone because
     * periods are book-local calendar ranges.
     */
    private static void validateBookAndPeriod(Connection connection, JournalDraft journal)
        throws SQLException {
        LocalDate localBookingDate;
        try (var statement = connection.prepareStatement("""
            SELECT legal_entity_id, accounting_policy_version, timezone,
                   chart_status, chart_activated_at
            FROM funds.lock_book_chart_for_posting(?, ?)
            """)) {
            statement.setObject(1, journal.bookId());
            statement.setObject(2, journal.chartVersionId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException(
                        "book/chart governance does not exist: "
                            + journal.bookId() + "/" + journal.chartVersionId());
                }
                if (!journal.legalEntityId().equals(rows.getObject("legal_entity_id", UUID.class))) {
                    throw new InvalidJournalException("journal legal entity does not match its book");
                }
                if (journal.policyVersion() != rows.getInt("accounting_policy_version")) {
                    throw new InvalidJournalException("journal policy version does not match its book");
                }
                if (!"ACTIVE".equals(rows.getString("chart_status"))) {
                    throw new InvalidJournalException("journal chart is not active");
                }
                Instant chartActivatedAt = rows.getObject(
                    "chart_activated_at", OffsetDateTime.class).toInstant();
                if (chartActivatedAt.isAfter(journal.bookingTime())) {
                    throw new InvalidJournalException(
                        "journal chart is not effective for its booking time");
                }
                ZoneId timezone;
                try {
                    timezone = ZoneId.of(rows.getString("timezone"));
                } catch (RuntimeException invalidTimezone) {
                    throw new InvalidJournalException("book has an invalid timezone");
                }
                localBookingDate = journal.bookingTime().atZone(timezone).toLocalDate();
            }
        }
        // Close the chart-governance result before opening the period query.
        // This keeps one cancellable JDBC statement live at a time on the
        // bounded posting connection while both row locks remain held.
        validatePeriod(connection, journal, localBookingDate);
    }

    private static void validatePeriod(
        Connection connection,
        JournalDraft journal,
        LocalDate localBookingDate
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT book_id, business_date_from, business_date_to, status
            FROM funds.lock_period_for_posting(?)
            """)) {
            statement.setObject(1, journal.periodId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException("accounting period does not exist: " + journal.periodId());
                }
                if (!"OPEN".equals(rows.getString("status"))) {
                    throw new AccountingPeriodClosedException(journal.periodId());
                }
                UUID periodBookId = rows.getObject("book_id", UUID.class);
                LocalDate from = rows.getObject("business_date_from", LocalDate.class);
                LocalDate to = rows.getObject("business_date_to", LocalDate.class);
                if (!journal.bookId().equals(periodBookId)
                    || localBookingDate.isBefore(from)
                    || localBookingDate.isAfter(to)
                    || journal.valueDate().isBefore(from)
                    || journal.valueDate().isAfter(to)) {
                    throw new InvalidJournalException("explicit accounting period does not cover the journal");
                }
            }
        }
    }

    /**
     * Distinct accounts in the canonical lock order. The null check lives here because locking
     * precedes JournalValidator in post().
     */
    private static List<UUID> canonicalAccountIds(JournalDraft journal) {
        var ids = new ArrayList<UUID>();
        for (var posting : journal.postings()) {
            if (posting.accountId() == null) {
                throw new InvalidJournalException("accountId must not be null");
            }
            if (!ids.contains(posting.accountId())) {
                ids.add(posting.accountId());
            }
        }
        ids.sort(CANONICAL_ACCOUNT_ORDER);
        return List.copyOf(ids);
    }

    /**
     * Two passes in the same canonical order: first every ledger_account row (FOR UPDATE) with its
     * chart mapping (FOR SHARE), then every materialised_balance row (FOR UPDATE), created on
     * first use. Reading the balance under its own lock is what makes the later UPDATE an exact
     * read-modify-write rather than a lost update.
     */
    private static Map<UUID, AccountState> lockAccountsAndBalances(
        Connection connection,
        JournalDraft journal,
        List<UUID> accountIds
    ) throws SQLException {
        Map<UUID, AccountMetadata> metadata = new LinkedHashMap<>();
        for (UUID accountId : accountIds) {
            metadata.put(accountId, lockAccount(connection, journal, accountId));
        }

        Map<UUID, AccountState> states = new LinkedHashMap<>();
        for (UUID accountId : accountIds) {
            ensureMaterialisedBalance(connection, accountId);
            var materialised = lockMaterialisedBalance(connection, accountId);
            var account = metadata.get(accountId);
            states.put(accountId, new AccountState(
                accountId,
                account.controlAccountCode(),
                account.currency(),
                materialised.signedPostingTotal(),
                materialised.latestAccountSequence(),
                materialised.version()));
        }
        return Map.copyOf(states);
    }

    /**
     * Resolves the account through the journal's pinned chart version, so a mapping that exists
     * only on another chart reads as "does not exist". Currency and permitted direction are
     * checked per line against the locked mapping; V005 posting_chart_mapping repeats both.
     */
    private static AccountMetadata lockAccount(
        Connection connection,
        JournalDraft journal,
        UUID accountId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT book_id, currency, control_account_code, status, permitted_direction
            FROM funds.lock_account_mapping_for_posting(?, ?)
            """)) {
            statement.setObject(1, accountId);
            statement.setObject(2, journal.chartVersionId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException("ledger account does not exist: " + accountId);
                }
                if (!journal.bookId().equals(rows.getObject("book_id", UUID.class))) {
                    throw new InvalidJournalException("posting account belongs to another book: " + accountId);
                }
                if (!"OPEN".equals(rows.getString("status"))) {
                    throw new InvalidJournalException("posting account is not open: " + accountId);
                }
                var currency = CurrencyCode.of(rows.getString("currency"));
                for (var posting : journal.postings()) {
                    if (accountId.equals(posting.accountId()) && !currency.equals(posting.currency())) {
                        throw new InvalidJournalException("posting currency does not match account: " + accountId);
                    }
                    if (accountId.equals(posting.accountId())) {
                        String permitted = rows.getString("permitted_direction");
                        if ((posting.signedMinorUnits() > 0 && "CREDIT".equals(permitted))
                            || (posting.signedMinorUnits() < 0 && "DEBIT".equals(permitted))) {
                            throw new InvalidJournalException(
                                "posting direction is not permitted by chart mapping: " + accountId);
                        }
                    }
                }
                return new AccountMetadata(
                    rows.getString("control_account_code"),
                    currency);
            }
        }
    }

    /**
     * Lazy creation keeps account onboarding free of balance rows; DO NOTHING is safe because the
     * row is locked and re-read immediately afterwards.
     */
    private static void ensureMaterialisedBalance(Connection connection, UUID accountId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.materialised_balance
                (account_id, signed_posting_total, latest_account_sequence, version)
            VALUES (?, 0, 0, 0)
            ON CONFLICT (account_id) DO NOTHING
            """)) {
            statement.setObject(1, accountId);
            statement.executeUpdate();
        }
    }

    private static MaterialisedBalance lockMaterialisedBalance(Connection connection, UUID accountId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT signed_posting_total, latest_account_sequence, version
            FROM funds.materialised_balance
            WHERE account_id = ?
            FOR UPDATE
            """)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("materialised balance row disappeared for account " + accountId);
                }
                return new MaterialisedBalance(
                    rows.getLong("signed_posting_total"),
                    rows.getLong("latest_account_sequence"),
                    rows.getLong("version"));
            }
        }
    }

    /**
     * Continues each account's monotonic sequence from the locked latest_account_sequence, in
     * journal line order. Exhaustion is a LedgerCapacityException for the "account sequence"
     * coordinate, never a wrap. The sequence is a storage coordinate and is not part of the
     * canonical hash.
     */
    private static JournalDraft assignAccountSequences(
        JournalDraft journal,
        Map<UUID, AccountState> accounts
    ) {
        Map<UUID, Long> latestSequences = new HashMap<>();
        accounts.forEach((accountId, account) -> latestSequences.put(accountId, account.latestAccountSequence()));
        var assignedPostings = new ArrayList<PostingLine>(journal.postings().size());
        for (var posting : journal.postings()) {
            long sequence = addCapacityExact(
                latestSequences.get(posting.accountId()), 1, "account sequence");
            latestSequences.put(posting.accountId(), sequence);
            assignedPostings.add(new PostingLine(
                posting.postingId(),
                posting.accountId(),
                posting.currency(),
                posting.signedMinorUnits(),
                sequence,
                posting.dimensions()));
        }
        return copyWithPostings(journal, assignedPostings);
    }

    private static JournalDraft copyWithPostings(JournalDraft journal, List<PostingLine> postings) {
        return new JournalDraft(
            journal.journalId(),
            journal.commandId(),
            journal.correlationId(),
            journal.businessTransactionId(),
            journal.legalEntityId(),
            journal.bookId(),
            journal.chartVersionId(),
            journal.periodId(),
            journal.transactionType(),
            journal.narration(),
            journal.bookingTime(),
            journal.valueDate(),
            journal.reversalOfJournalId(),
            journal.policyVersion(),
            postings);
    }

    /**
     * journal_sequence comes from the bigserial and is the global ordering the proofs' cutoff and
     * the outbox aggregate_version use. New rows are always scheme V2; V005 rejects anything else.
     */
    private static long insertJournal(Connection connection, JournalDraft journal, String canonicalHash)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.journal
                (journal_id, command_id, correlation_id, business_transaction_id, legal_entity_id,
                 book_id, period_id, transaction_type, narration, booking_time, value_date,
                 chart_version_id, reversal_of_journal_id, policy_version, canonical_hash,
                 canonical_hash_scheme)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'V2')
            RETURNING journal_sequence
            """)) {
            statement.setObject(1, journal.journalId());
            statement.setObject(2, journal.commandId());
            statement.setObject(3, journal.correlationId());
            statement.setObject(4, journal.businessTransactionId());
            statement.setObject(5, journal.legalEntityId());
            statement.setObject(6, journal.bookId());
            statement.setObject(7, journal.periodId());
            statement.setString(8, journal.transactionType());
            statement.setString(9, journal.narration());
            statement.setObject(10, OffsetDateTime.ofInstant(journal.bookingTime(), ZoneOffset.UTC));
            statement.setObject(11, journal.valueDate());
            statement.setObject(12, journal.chartVersionId());
            if (journal.reversalOfJournalId() == null) {
                statement.setNull(13, Types.OTHER);
            } else {
                statement.setObject(13, journal.reversalOfJournalId());
            }
            statement.setInt(14, journal.policyVersion());
            statement.setString(15, canonicalHash);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * Dimensions are persisted as compact JSON (PostingDimensions); the V005 CHECK bounds the
     * stored text at 8192 bytes and 32 keys, mirroring JournalValidator's limits.
     */
    private static void insertPostings(Connection connection, JournalDraft journal) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.posting
                (posting_id, journal_id, account_id, currency, signed_minor_units,
                 account_sequence, dimensions)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """)) {
            for (var posting : journal.postings()) {
                statement.setObject(1, posting.postingId());
                statement.setObject(2, journal.journalId());
                statement.setObject(3, posting.accountId());
                statement.setString(4, posting.currency().value());
                statement.setLong(5, posting.signedMinorUnits());
                statement.setLong(6, posting.accountSequence());
                statement.setString(7, PostingDimensions.compactJson(posting.dimensions()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Read-modify-write against the totals read under lock in lockAccountsAndBalances. The delta,
     * the new total and the version (advanced by the account's posting count) are computed with
     * exact arithmetic in Java; a bigint overflow reported by PostgreSQL (22003) maps to the same
     * MonetaryOverflowException so both layers fail identically.
     */
    private static void updateMaterialisedBalances(
        Connection connection,
        JournalDraft journal,
        Map<UUID, AccountState> accounts
    ) throws SQLException {
        Map<UUID, Long> deltas = new HashMap<>();
        Map<UUID, Integer> postingCounts = new HashMap<>();
        Map<UUID, Long> latestSequences = new HashMap<>();
        for (var posting : journal.postings()) {
            deltas.merge(
                posting.accountId(), posting.signedMinorUnits(),
                JdbcLedgerRepository::addMoneyExact);
            postingCounts.merge(posting.accountId(), 1, Math::addExact);
            latestSequences.put(posting.accountId(), posting.accountSequence());
        }

        try (var statement = connection.prepareStatement("""
            UPDATE funds.materialised_balance
            SET signed_posting_total = ?, latest_account_sequence = ?, version = ?
            WHERE account_id = ?
            """)) {
            for (UUID accountId : canonicalAccountIds(journal)) {
                AccountState account = accounts.get(accountId);
                long newTotal = addMoneyExact(account.signedPostingTotal(), deltas.get(accountId));
                long newVersion = addCapacityExact(
                    account.version(), postingCounts.get(accountId), "materialised version");
                statement.setLong(1, newTotal);
                statement.setLong(2, latestSequences.get(accountId));
                statement.setLong(3, newVersion);
                statement.setObject(4, accountId);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException failure) {
            if (SqlState.occursIn(failure, SqlState.NUMERIC_VALUE_OUT_OF_RANGE)) {
                throw monetaryOverflow("materialised balance exceeds bigint range", failure);
            }
            throw failure;
        }
    }

    /**
     * Book-level control totals per (control code, currency), locked in CONTROL_ORDER so concurrent
     * journals in one book take these rows in one direction. latest_journal_sequence records this
     * journal's sequence, which the control-account proof compares against the newest mapped
     * source sequence at its cutoff.
     */
    private static void updateControlProjection(
        Connection connection,
        JournalDraft journal,
        Map<UUID, AccountState> accounts,
        long journalSequence
    ) throws SQLException {
        Map<ControlKey, Long> deltas = new TreeMap<>(CONTROL_ORDER);
        for (var posting : journal.postings()) {
            AccountState account = accounts.get(posting.accountId());
            var key = new ControlKey(account.controlAccountCode(), account.currency());
            deltas.merge(key, posting.signedMinorUnits(), JdbcLedgerRepository::addMoneyExact);
        }

        try {
            for (var entry : deltas.entrySet()) {
                ensureControlProjection(connection, journal.bookId(), entry.getKey());
                long currentTotal = lockControlProjection(connection, journal.bookId(), entry.getKey());
                long newTotal = addMoneyExact(currentTotal, entry.getValue());
                updateControlProjection(
                    connection,
                    journal.bookId(),
                    entry.getKey(),
                    newTotal,
                    journalSequence);
            }
        } catch (SQLException failure) {
            if (SqlState.occursIn(failure, SqlState.NUMERIC_VALUE_OUT_OF_RANGE)) {
                throw monetaryOverflow("control-account projection exceeds bigint range", failure);
            }
            throw failure;
        }
    }

    private static void ensureControlProjection(Connection connection, UUID bookId, ControlKey key)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.control_account_projection
                (book_id, control_account_code, currency, signed_posting_total, latest_journal_sequence)
            VALUES (?, ?, ?, 0, 0)
            ON CONFLICT (book_id, control_account_code, currency) DO NOTHING
            """)) {
            statement.setObject(1, bookId);
            statement.setString(2, key.controlAccountCode());
            statement.setString(3, key.currency().value());
            statement.executeUpdate();
        }
    }

    private static long lockControlProjection(Connection connection, UUID bookId, ControlKey key)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT signed_posting_total
            FROM funds.control_account_projection
            WHERE book_id = ? AND control_account_code = ? AND currency = ?
            FOR UPDATE
            """)) {
            statement.setObject(1, bookId);
            statement.setString(2, key.controlAccountCode());
            statement.setString(3, key.currency().value());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("control projection row disappeared");
                }
                return rows.getLong(1);
            }
        }
    }

    private static void updateControlProjection(
        Connection connection,
        UUID bookId,
        ControlKey key,
        long newTotal,
        long journalSequence
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE funds.control_account_projection
            SET signed_posting_total = ?, latest_journal_sequence = ?
            WHERE book_id = ? AND control_account_code = ? AND currency = ?
            """)) {
            statement.setLong(1, newTotal);
            statement.setLong(2, journalSequence);
            statement.setObject(3, bookId);
            statement.setString(4, key.controlAccountCode());
            statement.setString(5, key.currency().value());
            statement.executeUpdate();
        }
    }

    /**
     * Transactional outbox row in the same commit as the journal. The event ID is a name-based
     * UUID of the journal ID and aggregate_version is the journal sequence, so both the primary
     * key and the (aggregate, version, type) unique constraint refuse a second event for one
     * journal. The payload is the same triple the completed command stores.
     */
    private static void insertOutbox(
        Connection connection,
        UUID journalId,
        long journalSequence,
        String canonicalHash
    ) throws SQLException {
        UUID eventId = UUID.nameUUIDFromBytes(
            ("JournalPosted:" + journalId).getBytes(StandardCharsets.UTF_8));
        String payload = "{\"journalId\":" + jsonString(journalId.toString())
            + ",\"journalSequence\":" + journalSequence
            + ",\"canonicalHash\":" + jsonString(canonicalHash) + "}";
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.outbox_event
                (event_id, aggregate_id, aggregate_version, event_type, schema_version, payload, created_at)
            VALUES (?, ?, ?, 'JournalPosted', 1, ?::jsonb, CURRENT_TIMESTAMP)
            """)) {
            statement.setObject(1, eventId);
            statement.setObject(2, journalId);
            statement.setLong(3, journalSequence);
            statement.setString(4, payload);
            statement.executeUpdate();
        }
    }

    /**
     * Final step. The state predicate turns a double completion into a failed update rather than
     * a silent overwrite, V004 makes the completed row immutable, and V005 checks result_json
     * against the journal it points at before the row is accepted.
     */
    private static void completeIdempotencyCommand(
        Connection connection,
        UUID commandId,
        PostingResult result
    ) throws SQLException {
        String resultJson = "{\"journalId\":" + jsonString(result.journalId().toString())
            + ",\"journalSequence\":" + result.journalSequence()
            + ",\"canonicalHash\":" + jsonString(result.canonicalHash()) + "}";
        try (var statement = connection.prepareStatement("""
            UPDATE funds.idempotency_command
            SET state = 'COMPLETED', journal_id = ?, result_json = ?::jsonb,
                completed_at = CURRENT_TIMESTAMP
            WHERE command_id = ? AND state = 'IN_PROGRESS'
            """)) {
            statement.setObject(1, result.journalId());
            statement.setString(2, resultJson);
            statement.setObject(3, commandId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("idempotency command could not be completed: " + commandId);
            }
        }
    }

    // Both helpers wrap Math.addExact, but an overflowing amount and an exhausted coordinate are
    // distinct failures with distinct exceptions (README "Reading the accounting model").
    private static long addMoneyExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new MonetaryOverflowException(overflow);
        }
    }

    private static long addCapacityExact(long left, long right, String coordinate) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new LedgerCapacityException(coordinate, overflow);
        }
    }

    private static MonetaryOverflowException monetaryOverflow(String message, Throwable cause) {
        var overflow = new ArithmeticException(message);
        overflow.initCause(cause);
        return new MonetaryOverflowException(overflow);
    }

    /**
     * RFC 8259 string escaping so the hand-built payloads stay valid for the ::jsonb cast without
     * a JSON library. The values are UUIDs and hex digests, so this is a guard, not a serializer.
     */
    private static String jsonString(String value) {
        var json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u");
                        String hex = Integer.toHexString(character);
                        json.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    /** Idempotency row as read under FOR UPDATE. */
    private record LockedCommand(String requestHash, String requestHashScheme, boolean completed) {}

    /** funds.journal columns needed to rebuild a V004 journal for re-hashing. */
    private record LegacyHeader(
        UUID journalId,
        UUID commandId,
        UUID correlationId,
        UUID businessTransactionId,
        UUID legalEntityId,
        UUID bookId,
        UUID chartVersionId,
        UUID periodId,
        String transactionType,
        String narration,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOfJournalId,
        int policyVersion) {}

    /** Accumulator for a legacy posting: facts from one query, dimensions attached by a second. */
    private record LegacyPostingBuilder(
        UUID postingId,
        UUID accountId,
        CurrencyCode currency,
        long signedMinorUnits,
        long accountSequence,
        Map<String, String> dimensions) {

        private PostingLine build() {
            return new PostingLine(
                postingId, accountId, currency, signedMinorUnits, accountSequence, dimensions);
        }
    }

    /** Chart-mapping facts read together with the account lock. */
    private record AccountMetadata(String controlAccountCode, CurrencyCode currency) {}

    /** materialised_balance row as read under FOR UPDATE. */
    private record MaterialisedBalance(
        long signedPostingTotal,
        long latestAccountSequence,
        long version
    ) {}

    /** Account metadata plus its locked balance, the basis of every later read-modify-write. */
    private record AccountState(
        UUID accountId,
        String controlAccountCode,
        CurrencyCode currency,
        long signedPostingTotal,
        long latestAccountSequence,
        long version
    ) {}

    /** Identity of a control_account_projection row within one book. */
    private record ControlKey(String controlAccountCode, CurrencyCode currency) {}
}
