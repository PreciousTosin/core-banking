package com.corebanking.funds.domain.exception;

import java.util.UUID;

/**
 * The command ID is being reused with different content. Raised when the supplied requestHash
 * is not the typed V2 hash of the command itself, or when an idempotency_command row already
 * holds a different hash (or a V004_OPAQUE fact whose reconstructed V2 hash differs). The
 * caller should infer a payload mismatch, not a transient failure; an unchanged retry repeats
 * it.
 */
public class IdempotencyConflictException extends RuntimeException {
    private final UUID commandId;

    public IdempotencyConflictException(UUID commandId) {
        super("command ID is already associated with a different request hash: " + commandId);
        this.commandId = commandId;
    }

    public UUID commandId() {
        return commandId;
    }
}
