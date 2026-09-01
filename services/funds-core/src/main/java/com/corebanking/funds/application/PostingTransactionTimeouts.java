package com.corebanking.funds.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Transaction-local PostgreSQL deadlines applied before posting reads or locks. Defaults are
 * 1s lock, 3s statement and 5s idle-in-transaction (funds.posting.* properties). The
 * constructor enforces lock < statement < idle: a lock wait happens inside a statement, so a
 * longer lock_timeout could never fire and a blocked lock would surface as 57014 (query
 * cancelled) instead of 55P03 (lock not available); the idle deadline catches a client that
 * stops issuing statements mid-transaction.
 */
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

    /**
     * Sets the three deadlines for the current transaction only. set_config(..., true) is
     * transaction-local, so the values vanish at commit or rollback and can never leak to the
     * next borrower of the pooled connection.
     */
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

    // Values are sent as whole milliseconds; a sub-millisecond duration would truncate to "0ms",
    // which PostgreSQL treats as "disabled".
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
