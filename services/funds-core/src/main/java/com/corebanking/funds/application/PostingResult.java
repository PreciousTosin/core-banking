package com.corebanking.funds.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable outcome of a completed command: the journal it created, its ledger-wide sequence and
 * canonical hash. Stored in idempotency_command.result_json and returned unchanged on every
 * replay, so it must identify its journal fully on its own
 * (completed_command_result_consistency).
 */
public record PostingResult(UUID journalId, long journalSequence, String canonicalHash) {
    public PostingResult {
        journalId = Objects.requireNonNull(journalId, "journalId");
        canonicalHash = Objects.requireNonNull(canonicalHash, "canonicalHash");
        // journal_sequence is a bigserial; values start at 1.
        if (journalSequence < 1) {
            throw new IllegalArgumentException("journalSequence must be positive");
        }
    }
}
