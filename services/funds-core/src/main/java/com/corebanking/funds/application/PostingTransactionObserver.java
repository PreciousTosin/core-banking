package com.corebanking.funds.application;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * Test seam for crash and failure injection inside the posting transaction. Production wires
 * the no-op bean below. PostingCrashRecoveryIT halts the JVM from beforeCommit and
 * afterCommitBeforeReturn to prove replay recovers a command on either side of the commit;
 * PostingAtomicityIT throws from afterFinancialRowsBeforeOutbox to prove the whole transaction
 * rolls back. Hooks fire in declaration order, the first three from JdbcLedgerRepository and
 * the last two from PostingService.
 */
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

/** Production binding: no hook does anything. */
@ApplicationScoped
final class NoOpPostingTransactionObserver implements PostingTransactionObserver {}
