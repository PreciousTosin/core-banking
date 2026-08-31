package com.corebanking.funds.application;

import java.util.Objects;
import java.util.UUID;

public record PostingResult(UUID journalId, long journalSequence, String canonicalHash) {
    public PostingResult {
        journalId = Objects.requireNonNull(journalId, "journalId");
        canonicalHash = Objects.requireNonNull(canonicalHash, "canonicalHash");
        if (journalSequence < 1) {
            throw new IllegalArgumentException("journalSequence must be positive");
        }
    }
}
