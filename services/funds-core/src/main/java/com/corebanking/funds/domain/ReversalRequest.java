package com.corebanking.funds.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record ReversalRequest(
    UUID commandId,
    String requestHash,
    UUID originalJournalId,
    UUID correlationId,
    UUID businessTransactionId,
    UUID currentPeriodId,
    Instant bookingTime,
    LocalDate valueDate,
    String reason) {

    public ReversalRequest {
        commandId = Objects.requireNonNull(commandId, "commandId");
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
        originalJournalId = Objects.requireNonNull(originalJournalId, "originalJournalId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        businessTransactionId = Objects.requireNonNull(
            businessTransactionId,
            "businessTransactionId");
        currentPeriodId = Objects.requireNonNull(currentPeriodId, "currentPeriodId");
        bookingTime = Objects.requireNonNull(bookingTime, "bookingTime");
        valueDate = Objects.requireNonNull(valueDate, "valueDate");
        reason = Objects.requireNonNull(reason, "reason");
        if (requestHash.length() != 64) {
            throw new IllegalArgumentException("requestHash must contain exactly 64 characters");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
