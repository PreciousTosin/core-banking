package com.corebanking.funds.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
