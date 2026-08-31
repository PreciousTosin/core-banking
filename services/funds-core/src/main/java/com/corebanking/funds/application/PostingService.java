package com.corebanking.funds.application;

import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.infrastructure.postgres.LedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
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

    public PostingService(
        DataSource dataSource,
        LedgerRepository repository,
        PostgresRetryPolicy retryPolicy
    ) {
        this(dataSource, repository, retryPolicy, PostingTransactionObserver.noop());
    }

    @Inject
    public PostingService(
        DataSource dataSource,
        LedgerRepository repository,
        PostgresRetryPolicy retryPolicy,
        PostingTransactionObserver observer
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.validator = new JournalValidator();
    }

    public PostingResult post(PostingCommand command) {
        Objects.requireNonNull(command, "command");
        validator.validate(command.journal());
        return retryPolicy.execute(command.commandId(), () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                PostingResult result;
                try {
                    result = repository.post(connection, command);
                    observer.beforeCommit(command.commandId());
                    connection.commit();
                } catch (SQLException failure) {
                    rollback(connection, failure);
                    throw new LedgerPersistenceException(failure);
                } catch (RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
                observer.afterCommitBeforeReturn(command.commandId());
                return result;
            } catch (SQLException connectionFailure) {
                throw new LedgerPersistenceException(connectionFailure);
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
