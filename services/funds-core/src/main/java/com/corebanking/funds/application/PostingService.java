package com.corebanking.funds.application;

import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.infrastructure.postgres.LedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import com.corebanking.funds.infrastructure.postgres.SqlState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Transactional entry point for posting a journal. Owns the SERIALIZABLE transaction, the
 * transaction-local deadlines, idempotency replay, retry and rollback; JdbcLedgerRepository
 * owns the SQL. Two entry points share one choreography: the public generic path and the
 * package-private trusted-reversal path reserved for ReversalService.
 */
@ApplicationScoped
public class PostingService {
    private final DataSource dataSource;
    private final LedgerRepository repository;
    private final PostgresRetryPolicy retryPolicy;
    private final PostingTransactionObserver observer;
    private final JournalValidator validator;
    private final CanonicalCommandHasher commandHasher;
    private final PostingTransactionTimeouts transactionTimeouts;

    public PostingService(
        DataSource dataSource,
        LedgerRepository repository,
        PostgresRetryPolicy retryPolicy
    ) {
        this(
            dataSource,
            repository,
            retryPolicy,
            PostingTransactionObserver.noop(),
            PostingTransactionTimeouts.defaults());
    }

    public PostingService(
        DataSource dataSource,
        LedgerRepository repository,
        PostgresRetryPolicy retryPolicy,
        PostingTransactionObserver observer
    ) {
        this(dataSource, repository, retryPolicy, observer, PostingTransactionTimeouts.defaults());
    }

    @Inject
    public PostingService(
        DataSource dataSource,
        LedgerRepository repository,
        PostgresRetryPolicy retryPolicy,
        PostingTransactionObserver observer,
        PostingTransactionTimeouts transactionTimeouts
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.validator = new JournalValidator();
        this.commandHasher = new CanonicalCommandHasher();
        this.transactionTimeouts = Objects.requireNonNull(transactionTimeouts, "transactionTimeouts");
    }

    /**
     * Posts a generic journal. Proves the TYPED_V2 request hash is postingV2 of the journal and
     * rejects reversal metadata; linked reversals enter only through the trusted path.
     */
    public PostingResult post(PostingCommand command) {
        return post(command, false);
    }

    /**
     * Posts a reversal assembled by ReversalService. Package-private so no caller outside this
     * package can present reversal metadata; the request hash was already verified as
     * reversalV2 there.
     */
    PostingResult postTrustedReversal(PostingCommand command) {
        return post(command, true);
    }

    private PostingResult post(PostingCommand command, boolean trustedReversal) {
        Objects.requireNonNull(command, "command");
        boolean linkedReversal = command.journal().reversalOfJournalId() != null;
        // The two paths differ only in admission. A trusted reversal's request hash is the
        // reversalV2 digest of the ReversalRequest, which ReversalService has already checked,
        // so recomputing postingV2 over the reversal journal here could never match. A generic
        // command must hash to postingV2 of its own journal and may carry no reversal marker.
        if (trustedReversal) {
            if (!linkedReversal || !"REVERSAL".equals(command.journal().transactionType())) {
                throw new InvalidJournalException("trusted reversal path requires linked REVERSAL journal");
            }
        } else {
            if (!command.requestHash().equals(commandHasher.postingV2(command.journal()))) {
                throw new IdempotencyConflictException(command.commandId());
            }
            if (linkedReversal || "REVERSAL".equals(command.journal().transactionType())) {
                throw new InvalidJournalException(
                    "generic posting path does not accept reversal metadata");
            }
        }

        // PostgresRetryPolicy reruns the whole closure, on a fresh connection, for serialization
        // failures and deadlocks only. Timeouts and business rejections are never retried.
        return retryPolicy.execute(command.commandId(), () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                PostingResult result;
                try {
                    // Deadlines go first so every later statement, the replay lookup included,
                    // is bounded before any financial row is read or locked.
                    transactionTimeouts.apply(connection);
                    // Same-content replay short-circuits with the stored result; the transaction
                    // has only read and is rolled back rather than committed. A same-key,
                    // different-content command fails inside findCompleted as a conflict.
                    var completed = repository.findCompleted(connection, command);
                    if (completed.isPresent()) {
                        connection.rollback();
                        return completed.orElseThrow();
                    }
                    // Cheap application-level admission before the repository takes any lock;
                    // the repository validates again once account sequences are assigned.
                    validator.validate(command.journal());
                    result = repository.post(connection, command);
                    // Observer hooks are the crash-injection seam used by PostingCrashRecoveryIT:
                    // a halt at either hook must leave the command recoverable by replay.
                    observer.beforeCommit(command.commandId());
                    connection.commit();
                } catch (SQLException failure) {
                    // 55P03 / 57014 become LedgerTimeoutException, everything else
                    // LedgerPersistenceException; the retry policy inspects the cause chain.
                    rollback(connection, failure);
                    throw SqlState.persistenceFailure(failure);
                } catch (RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
                observer.afterCommitBeforeReturn(command.commandId());
                return result;
            } catch (SQLException connectionFailure) {
                // Acquiring, configuring or closing the connection failed outside the
                // transaction body.
                throw SqlState.persistenceFailure(connectionFailure);
            }
        });
    }

    // A suppressed rollback failure must not hide the original cause.
    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
