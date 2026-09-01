package com.corebanking.funds.domain.exception;

import java.util.UUID;

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
