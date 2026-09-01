package com.corebanking.funds.infrastructure.postgres;

import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.LedgerTimeoutException;
import java.sql.SQLException;
import java.util.Set;

/**
 * SQLSTATE to domain-exception mapping for the posting path. Only the codes named here steer
 * control flow: 40001 and 40P01 are retried by PostgresRetryPolicy, 22003 becomes
 * MonetaryOverflowException in JdbcLedgerRepository, and 55P03 (lock_timeout) and 57014
 * (statement_timeout, which reports the same code as an explicit cancel) become
 * LedgerTimeoutException. Everything else, including the backend termination that an
 * idle_in_transaction_session_timeout causes (25P03), is a plain LedgerPersistenceException.
 */
public final class SqlState {
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    public static final String QUERY_CANCELED = "57014";

    private SqlState() {}

    /**
     * Walks the cause chain because the SQLException usually arrives wrapped (for example in a
     * LedgerPersistenceException thrown inside the retry closure). getNextException is not
     * consulted.
     */
    public static boolean occursIn(Throwable failure, String... expectedStates) {
        var expected = Set.of(expectedStates);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlFailure && expected.contains(sqlFailure.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRetryable(Throwable failure) {
        return occursIn(failure, SERIALIZATION_FAILURE, DEADLOCK_DETECTED);
    }

    /**
     * Separates an expired finite deadline from an unknown persistence fault so callers can
     * tell bounded backpressure apart from corruption or outage.
     */
    public static LedgerPersistenceException persistenceFailure(SQLException failure) {
        if (occursIn(failure, LOCK_NOT_AVAILABLE, QUERY_CANCELED)) {
            return new LedgerTimeoutException(failure);
        }
        return new LedgerPersistenceException(failure);
    }
}
