package com.corebanking.funds.infrastructure.postgres;

import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.LedgerTimeoutException;
import java.sql.SQLException;
import java.util.Set;

public final class SqlState {
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    public static final String QUERY_CANCELED = "57014";

    private SqlState() {}

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

    public static LedgerPersistenceException persistenceFailure(SQLException failure) {
        if (occursIn(failure, LOCK_NOT_AVAILABLE, QUERY_CANCELED)) {
            return new LedgerTimeoutException(failure);
        }
        return new LedgerPersistenceException(failure);
    }
}
