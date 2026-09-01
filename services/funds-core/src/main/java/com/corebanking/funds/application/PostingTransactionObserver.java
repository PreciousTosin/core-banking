package com.corebanking.funds.application;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

public interface PostingTransactionObserver {
    default void afterIdempotencyAcquired(UUID commandId) {}

    default void afterAccountLocks(UUID commandId) {}

    default void afterFinancialRowsBeforeOutbox(UUID commandId) {}

    default void beforeCommit(UUID commandId) {}

    default void afterCommitBeforeReturn(UUID commandId) {}

    static PostingTransactionObserver noop() {
        return new PostingTransactionObserver() {};
    }
}

@ApplicationScoped
final class NoOpPostingTransactionObserver implements PostingTransactionObserver {}
