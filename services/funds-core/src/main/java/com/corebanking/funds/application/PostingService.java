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

    @Inject
    public PostingService(
        DataSource dataSource,
        LedgerRepository repository,
        PostgresRetryPolicy retryPolicy
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    public PostingResult post(PostingCommand command) {
        Objects.requireNonNull(command, "command");
        return retryPolicy.execute(command.commandId(), () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try {
                    PostingResult result = repository.post(connection, command);
                    connection.commit();
                    return result;
                } catch (SQLException failure) {
                    rollback(connection, failure);
                    throw new LedgerPersistenceException(failure);
                } catch (RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
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
