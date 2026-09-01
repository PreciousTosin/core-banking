package com.corebanking.funds.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Transaction-local PostgreSQL deadlines applied before posting reads or locks. */
@ApplicationScoped
public class PostingTransactionTimeouts {
    static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(1);
    static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration DEFAULT_IDLE_TRANSACTION_TIMEOUT = Duration.ofSeconds(5);

    private final String lockTimeout;
    private final String statementTimeout;
    private final String idleTransactionTimeout;

    @Inject
    public PostingTransactionTimeouts(
        @ConfigProperty(name = "funds.posting.lock-timeout") Duration lockTimeout,
        @ConfigProperty(name = "funds.posting.statement-timeout") Duration statementTimeout,
        @ConfigProperty(name = "funds.posting.idle-transaction-timeout") Duration idleTransactionTimeout
    ) {
        Duration lock = positiveMillis(lockTimeout, "lockTimeout");
        Duration statement = positiveMillis(statementTimeout, "statementTimeout");
        Duration idle = positiveMillis(idleTransactionTimeout, "idleTransactionTimeout");
        if (lock.compareTo(statement) >= 0) {
            throw new IllegalArgumentException("lockTimeout must be shorter than statementTimeout");
        }
        if (statement.compareTo(idle) >= 0) {
            throw new IllegalArgumentException(
                "statementTimeout must be shorter than idleTransactionTimeout");
        }
        this.lockTimeout = postgresMillis(lock);
        this.statementTimeout = postgresMillis(statement);
        this.idleTransactionTimeout = postgresMillis(idle);
    }

    static PostingTransactionTimeouts defaults() {
        return new PostingTransactionTimeouts(
            DEFAULT_LOCK_TIMEOUT,
            DEFAULT_STATEMENT_TIMEOUT,
            DEFAULT_IDLE_TRANSACTION_TIMEOUT);
    }

    public void apply(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (var statement = connection.prepareStatement("""
            SELECT set_config('lock_timeout', ?, true),
                   set_config('statement_timeout', ?, true),
                   set_config('idle_in_transaction_session_timeout', ?, true)
            """)) {
            statement.setString(1, lockTimeout);
            statement.setString(2, statementTimeout);
            statement.setString(3, idleTransactionTimeout);
            statement.execute();
        }
    }

    private static Duration positiveMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is outside the supported millisecond range", overflow);
        }
        if (value.isNegative() || value.isZero() || millis == 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }

    private static String postgresMillis(Duration value) {
        return value.toMillis() + "ms";
    }
}
