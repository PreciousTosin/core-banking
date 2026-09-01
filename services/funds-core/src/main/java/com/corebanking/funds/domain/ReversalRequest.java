package com.corebanking.funds.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Command to post the exact negation of an existing journal. ReversalService rebuilds every
 * original posting with its sign flipped, links the new journal through reversalOfJournalId,
 * and posts it into currentPeriodId (which may differ from the original's period) at this
 * request's own bookingTime and valueDate. reason becomes the reversal journal's narration.
 *
 * <p>requestHash must equal CanonicalCommandHasher.reversalV2 of this record, which pins every
 * other field: commandId, originalJournalId, correlationId, businessTransactionId,
 * currentPeriodId, bookingTime, valueDate and reason. A mismatch is reported as
 * IdempotencyConflictException. commandId also seeds the deterministic journal and posting IDs
 * of the reversal, so a replay reproduces the same journal.
 */
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
        // reason is persisted as journal.narration, whose CHECK is octet_length <= 512, so the
        // limit is measured in UTF-8 bytes rather than characters.
        if (reason.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("reason must not exceed 512 UTF-8 bytes");
        }
    }
}
