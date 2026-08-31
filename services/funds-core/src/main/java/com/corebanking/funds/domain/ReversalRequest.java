package com.corebanking.funds.domain;

import java.nio.charset.StandardCharsets;
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
        if (!requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "requestHash must be a lowercase 64-character SHA-256 hexadecimal digest");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (reason.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("reason must not exceed 512 UTF-8 bytes");
        }
    }
}
