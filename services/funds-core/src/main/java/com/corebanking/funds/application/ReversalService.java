package com.corebanking.funds.application;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.ReversalRequest;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class ReversalService {
    private final DataSource dataSource;
    private final PostingService postingService;
    private final JournalValidator validator;

    @Inject
    public ReversalService(DataSource dataSource, PostingService postingService) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.validator = new JournalValidator();
    }

    public PostingResult reverse(ReversalRequest request) {
        Objects.requireNonNull(request, "request");
        OriginalJournal original = loadOriginal(request.originalJournalId());
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
            original.policyVersion(),
            reversalPostings);
        validator.validate(reversal);
        return postingService.post(new PostingCommand(
            request.commandId(),
            request.requestHash(),
            reversal));
    }

    private OriginalJournal loadOriginal(UUID journalId) {
        try (Connection connection = dataSource.getConnection()) {
            OriginalJournalHeader header = loadHeader(connection, journalId);
            List<PostingLine> postings = loadPostings(connection, journalId);
            if (postings.isEmpty()) {
                throw new InvalidJournalException("original journal has no postings: " + journalId);
            }
            return new OriginalJournal(
                header.journalId(),
                header.commandId(),
                header.journalSequence(),
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
                header.policyVersion(),
                header.canonicalHash(),
                postings);
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }

    private static OriginalJournalHeader loadHeader(Connection connection, UUID journalId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT journal_id, command_id, journal_sequence, correlation_id,
                   business_transaction_id, legal_entity_id, book_id, period_id,
                   transaction_type, narration, booking_time, value_date,
                   reversal_of_journal_id, policy_version, canonical_hash
            FROM funds.journal
            WHERE journal_id = ?
            """)) {
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new InvalidJournalException("original journal does not exist: " + journalId);
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
                    rows.getObject("value_date", java.time.LocalDate.class),
                    rows.getObject("reversal_of_journal_id", UUID.class),
                    rows.getInt("policy_version"),
                    rows.getString("canonical_hash"));
            }
        }
    }

    private static List<PostingLine> loadPostings(Connection connection, UUID journalId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT posting.posting_id, posting.account_id, posting.currency,
                   posting.signed_minor_units, posting.account_sequence,
                   dimension.key AS dimension_key, dimension.value AS dimension_value
            FROM funds.posting posting
            LEFT JOIN LATERAL jsonb_each_text(posting.dimensions) dimension ON true
            WHERE posting.journal_id = ?
            ORDER BY posting.account_sequence, posting.posting_id, dimension.key
            """)) {
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                Map<UUID, PostingBuilder> builders = new LinkedHashMap<>();
                while (rows.next()) {
                    UUID postingId = rows.getObject("posting_id", UUID.class);
                    PostingBuilder builder = builders.get(postingId);
                    if (builder == null) {
                        builder = new PostingBuilder(
                            postingId,
                            rows.getObject("account_id", UUID.class),
                            CurrencyCode.of(rows.getString("currency")),
                            rows.getLong("signed_minor_units"),
                            rows.getLong("account_sequence"));
                        builders.put(postingId, builder);
                    }
                    String key = rows.getString("dimension_key");
                    if (key != null) {
                        builder.dimensions().put(key, rows.getString("dimension_value"));
                    }
                }
                var postings = new ArrayList<PostingLine>(builders.size());
                for (PostingBuilder builder : builders.values()) {
                    postings.add(builder.build());
                }
                return List.copyOf(postings);
            }
        }
    }

    private static long negateExact(long value) {
        try {
            return Math.negateExact(value);
        } catch (ArithmeticException overflow) {
            throw new MonetaryOverflowException(overflow);
        }
    }

    private static UUID deterministicId(UUID commandId, String component) {
        return UUID.nameUUIDFromBytes(
            ("funds-reversal:" + commandId + ":" + component).getBytes(StandardCharsets.UTF_8));
    }

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
        java.time.Instant bookingTime,
        java.time.LocalDate valueDate,
        UUID reversalOfJournalId,
        int policyVersion,
        String canonicalHash) {}

    private record OriginalJournal(
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
        java.time.Instant bookingTime,
        java.time.LocalDate valueDate,
        UUID reversalOfJournalId,
        int policyVersion,
        String canonicalHash,
        List<PostingLine> postings) {}

    private record PostingBuilder(
        UUID postingId,
        UUID accountId,
        CurrencyCode currency,
        long signedMinorUnits,
        long accountSequence,
        Map<String, String> dimensions) {

        private PostingBuilder(
            UUID postingId,
            UUID accountId,
            CurrencyCode currency,
            long signedMinorUnits,
            long accountSequence
        ) {
            this(postingId, accountId, currency, signedMinorUnits, accountSequence, new LinkedHashMap<>());
        }

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
