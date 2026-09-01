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

    public PostingResult post(PostingCommand command) {
        return post(command, false);
    }

    PostingResult postTrustedReversal(PostingCommand command) {
        return post(command, true);
    }

    private PostingResult post(PostingCommand command, boolean trustedReversal) {
        Objects.requireNonNull(command, "command");
        boolean linkedReversal = command.journal().reversalOfJournalId() != null;
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

        return retryPolicy.execute(command.commandId(), () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                PostingResult result;
                try {
                    transactionTimeouts.apply(connection);
                    var completed = repository.findCompleted(connection, command);
                    if (completed.isPresent()) {
                        connection.rollback();
                        return completed.orElseThrow();
                    }
                    validator.validate(command.journal());
                    result = repository.post(connection, command);
                    observer.beforeCommit(command.commandId());
                    connection.commit();
                } catch (SQLException failure) {
                    rollback(connection, failure);
                    throw SqlState.persistenceFailure(failure);
                } catch (RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
                observer.afterCommitBeforeReturn(command.commandId());
                return result;
            } catch (SQLException connectionFailure) {
                throw SqlState.persistenceFailure(connectionFailure);
            }
        });
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
