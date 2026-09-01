package com.corebanking.funds.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A journal as submitted for posting, before the repository assigns sequences. Every field
 * except the postings' accountSequence is pinned into the canonical hash
 * (CanonicalJournalHasher); chartVersionId is the one field the V2 scheme adds over V004_V1,
 * which is the hash-scheme boundary the README describes. Postings are hashed in canonical
 * account/posting UUID order, so their order in this list is not significant.
 *
 * <p>This constructor guards presence and shape only. Balance per currency, the 256/32/8192
 * limits, duplicate posting IDs and booking-time precision are JournalValidator's job; period,
 * chart, policy and account governance are checked under lock at commit.
 *
 * <p>transactionType "REVERSAL" and a non-null reversalOfJournalId are reserved for the trusted
 * path from ReversalService; the generic posting path rejects both. policyVersion must equal
 * the book's current accounting policy at commit, and periodId names the period explicitly
 * rather than being derived from the dates.
 */
public record JournalDraft(
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
    int policyVersion,
    List<PostingLine> postings) {

    public JournalDraft {
        journalId = Objects.requireNonNull(journalId, "journalId");
        commandId = Objects.requireNonNull(commandId, "commandId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        businessTransactionId = Objects.requireNonNull(businessTransactionId, "businessTransactionId");
        legalEntityId = Objects.requireNonNull(legalEntityId, "legalEntityId");
        bookId = Objects.requireNonNull(bookId, "bookId");
        chartVersionId = Objects.requireNonNull(chartVersionId, "chartVersionId");
        periodId = Objects.requireNonNull(periodId, "periodId");
        transactionType = Objects.requireNonNull(transactionType, "transactionType");
        narration = Objects.requireNonNull(narration, "narration");
        bookingTime = Objects.requireNonNull(bookingTime, "bookingTime");
        valueDate = Objects.requireNonNull(valueDate, "valueDate");
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        if (transactionType.isBlank()) {
            throw new IllegalArgumentException("transactionType must not be blank");
        }
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
        if (postings.isEmpty()) {
            throw new IllegalArgumentException("postings must not be empty");
        }
    }
}
