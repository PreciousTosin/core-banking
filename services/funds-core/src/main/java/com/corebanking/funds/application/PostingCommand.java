package com.corebanking.funds.application;

import com.corebanking.funds.domain.JournalDraft;
import java.util.Objects;
import java.util.UUID;

/**
 * Idempotent posting request: the command identity, the caller's TYPED_V2 request hash and the
 * journal it claims to describe. The hash is only format-checked here; PostingService proves it
 * equals postingV2(journal) before opening a transaction.
 */
public record PostingCommand(UUID commandId, String requestHash, JournalDraft journal) {
    public PostingCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
        journal = Objects.requireNonNull(journal, "journal");
        // Same shape as the char(64) request_hash column, lowercase so string equality with
        // the stored value is exact.
        if (!requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "requestHash must be a lowercase 64-character SHA-256 hexadecimal digest");
        }
        if (!commandId.equals(journal.commandId())) {
            throw new IllegalArgumentException("commandId must match journal.commandId");
        }
    }
}
