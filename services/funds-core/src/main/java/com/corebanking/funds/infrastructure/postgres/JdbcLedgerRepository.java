package com.corebanking.funds.infrastructure.postgres;

import com.corebanking.funds.application.CanonicalJournalHasher;
import com.corebanking.funds.application.JournalValidator;
import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingResult;
import com.corebanking.funds.application.PostingTransactionObserver;
import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.AccountingPeriodClosedException;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

@ApplicationScoped
public class JdbcLedgerRepository implements LedgerRepository {
    private static final Comparator<UUID> CANONICAL_ACCOUNT_ORDER = Comparator.comparing(UUID::toString);
    private static final Comparator<ControlKey> CONTROL_ORDER = Comparator
        .comparing(ControlKey::controlAccountCode)
        .thenComparing(key -> key.currency().value());

    private final JournalValidator validator;
    private final CanonicalJournalHasher hasher;
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
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public PostingResult post(Connection connection, PostingCommand command) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(command, "command");
        try {
            insertIdempotencyCommand(connection, command);
            LockedCommand locked = lockIdempotencyCommand(connection, command.commandId());
            if (!locked.requestHash().equals(command.requestHash())) {
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
            String canonicalHash = hasher.sha256(assignedJournal);

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
            if (SqlState.occursIn(failure, SqlState.NUMERIC_VALUE_OUT_OF_RANGE)) {
                throw monetaryOverflow("PostgreSQL rejected a monetary projection outside bigint range", failure);
            }
            throw new LedgerPersistenceException(failure);
        }
    }

    @Override
    public Optional<PostingResult> findCompleted(
        Connection connection,
        UUID commandId,
        String requestHash
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(requestHash, "requestHash");
        try (var statement = connection.prepareStatement("""
            SELECT journal.journal_id, journal.journal_sequence, journal.canonical_hash
            FROM funds.idempotency_command command
            JOIN funds.journal journal ON journal.journal_id = command.journal_id
            WHERE command.command_id = ? AND command.request_hash = ? AND command.state = 'COMPLETED'
            """)) {
            statement.setObject(1, commandId);
            statement.setString(2, requestHash);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PostingResult(
                    rows.getObject("journal_id", UUID.class),
                    rows.getLong("journal_sequence"),
                    rows.getString("canonical_hash")));
            }
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }

    private static void insertIdempotencyCommand(Connection connection, PostingCommand command)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.idempotency_command
                (command_id, request_hash, state, created_at)
            VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
            ON CONFLICT (command_id) DO NOTHING
            """)) {
            statement.setObject(1, command.commandId());
            statement.setString(2, command.requestHash());
            statement.executeUpdate();
        }
    }

    private static LockedCommand lockIdempotencyCommand(Connection connection, UUID commandId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT request_hash, state
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
                    "COMPLETED".equals(rows.getString("state")));
            }
        }
    }

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

    private static void validateBookAndPeriod(Connection connection, JournalDraft journal)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT legal_entity_id, accounting_policy_version
            FROM funds.book
            WHERE book_id = ?
            FOR SHARE
            """)) {
            statement.setObject(1, journal.bookId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException("book does not exist: " + journal.bookId());
                }
                if (!journal.legalEntityId().equals(rows.getObject("legal_entity_id", UUID.class))) {
                    throw new InvalidJournalException("journal legal entity does not match its book");
                }
                if (journal.policyVersion() != rows.getInt("accounting_policy_version")) {
                    throw new InvalidJournalException("journal policy version does not match its book");
                }
            }
        }

        try (var statement = connection.prepareStatement("""
            SELECT book_id, business_date_from, business_date_to, status
            FROM funds.accounting_period
            WHERE period_id = ?
            FOR SHARE
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
                    || journal.valueDate().isBefore(from)
                    || journal.valueDate().isAfter(to)) {
                    throw new InvalidJournalException("explicit accounting period does not cover the journal");
                }
            }
        }
    }

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

    private static AccountMetadata lockAccount(
        Connection connection,
        JournalDraft journal,
        UUID accountId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT account.book_id, account.currency, account.control_account_code,
                   account.status, chart.status AS chart_status
            FROM funds.ledger_account account
            JOIN funds.chart_version chart ON chart.chart_version_id = account.chart_version_id
            WHERE account.account_id = ?
            FOR UPDATE OF account
            FOR SHARE OF chart
            """)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException("ledger account does not exist: " + accountId);
                }
                if (!journal.bookId().equals(rows.getObject("book_id", UUID.class))) {
                    throw new InvalidJournalException("posting account belongs to another book: " + accountId);
                }
                if (!"ACTIVE".equals(rows.getString("chart_status"))) {
                    throw new InvalidJournalException("posting account chart is not active: " + accountId);
                }
                if (!"OPEN".equals(rows.getString("status"))) {
                    throw new InvalidJournalException("posting account is not open: " + accountId);
                }
                var currency = CurrencyCode.of(rows.getString("currency"));
                for (var posting : journal.postings()) {
                    if (accountId.equals(posting.accountId()) && !currency.equals(posting.currency())) {
                        throw new InvalidJournalException("posting currency does not match account: " + accountId);
                    }
                }
                return new AccountMetadata(
                    rows.getString("control_account_code"),
                    currency);
            }
        }
    }

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

    private static JournalDraft assignAccountSequences(
        JournalDraft journal,
        Map<UUID, AccountState> accounts
    ) {
        Map<UUID, Long> latestSequences = new HashMap<>();
        accounts.forEach((accountId, account) -> latestSequences.put(accountId, account.latestAccountSequence()));
        var assignedPostings = new ArrayList<PostingLine>(journal.postings().size());
        for (var posting : journal.postings()) {
            long sequence = addExact(latestSequences.get(posting.accountId()), 1);
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
            journal.periodId(),
            journal.transactionType(),
            journal.narration(),
            journal.bookingTime(),
            journal.valueDate(),
            journal.reversalOfJournalId(),
            journal.policyVersion(),
            postings);
    }

    private static long insertJournal(Connection connection, JournalDraft journal, String canonicalHash)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO funds.journal
                (journal_id, command_id, correlation_id, business_transaction_id, legal_entity_id,
                 book_id, period_id, transaction_type, narration, booking_time, value_date,
                 reversal_of_journal_id, policy_version, canonical_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            if (journal.reversalOfJournalId() == null) {
                statement.setNull(12, Types.OTHER);
            } else {
                statement.setObject(12, journal.reversalOfJournalId());
            }
            statement.setInt(13, journal.policyVersion());
            statement.setString(14, canonicalHash);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

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
                statement.setString(7, jsonObject(posting.dimensions()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void updateMaterialisedBalances(
        Connection connection,
        JournalDraft journal,
        Map<UUID, AccountState> accounts
    ) throws SQLException {
        Map<UUID, Long> deltas = new HashMap<>();
        Map<UUID, Integer> postingCounts = new HashMap<>();
        Map<UUID, Long> latestSequences = new HashMap<>();
        for (var posting : journal.postings()) {
            deltas.merge(posting.accountId(), posting.signedMinorUnits(), JdbcLedgerRepository::addExact);
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
                long newTotal = addExact(account.signedPostingTotal(), deltas.get(accountId));
                long newVersion = addExact(account.version(), postingCounts.get(accountId));
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
            deltas.merge(key, posting.signedMinorUnits(), JdbcLedgerRepository::addExact);
        }

        try {
            for (var entry : deltas.entrySet()) {
                ensureControlProjection(connection, journal.bookId(), entry.getKey());
                long currentTotal = lockControlProjection(connection, journal.bookId(), entry.getKey());
                long newTotal = addExact(currentTotal, entry.getValue());
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

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new MonetaryOverflowException(overflow);
        }
    }

    private static MonetaryOverflowException monetaryOverflow(String message, Throwable cause) {
        var overflow = new ArithmeticException(message);
        overflow.initCause(cause);
        return new MonetaryOverflowException(overflow);
    }

    private static String jsonObject(Map<String, String> values) {
        var json = new StringBuilder("{");
        boolean first = true;
        for (var entry : new TreeMap<>(values).entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(jsonString(entry.getKey())).append(':').append(jsonString(entry.getValue()));
        }
        return json.append('}').toString();
    }

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

    private record LockedCommand(String requestHash, boolean completed) {}

    private record AccountMetadata(String controlAccountCode, CurrencyCode currency) {}

    private record MaterialisedBalance(
        long signedPostingTotal,
        long latestAccountSequence,
        long version
    ) {}

    private record AccountState(
        UUID accountId,
        String controlAccountCode,
        CurrencyCode currency,
        long signedPostingTotal,
        long latestAccountSequence,
        long version
    ) {}

    private record ControlKey(String controlAccountCode, CurrencyCode currency) {}
}
