package com.corebanking.funds.application;

import com.corebanking.funds.domain.JournalDraft;
import java.util.Objects;
import java.util.UUID;

public record PostingCommand(UUID commandId, String requestHash, JournalDraft journal) {
    public PostingCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
        journal = Objects.requireNonNull(journal, "journal");
        if (requestHash.length() != 64) {
            throw new IllegalArgumentException("requestHash must contain exactly 64 characters");
        }
        if (!commandId.equals(journal.commandId())) {
            throw new IllegalArgumentException("commandId must match journal.commandId");
        }
    }
}
