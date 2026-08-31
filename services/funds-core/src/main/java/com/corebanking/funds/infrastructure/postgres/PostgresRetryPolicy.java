package com.corebanking.funds.infrastructure.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

@ApplicationScoped
public class PostgresRetryPolicy {
    private static final int MAX_ATTEMPTS = 5;
    private final RetryJitter jitter;

    public PostgresRetryPolicy() {
        this((commandId, attempt) -> {
            long upperBoundMillis = 1L << Math.min(attempt, 5);
            long delayMillis = ThreadLocalRandom.current().nextLong(1, upperBoundMillis + 1);
            LockSupport.parkNanos(delayMillis * 1_000_000);
        });
    }

    public PostgresRetryPolicy(RetryJitter jitter) {
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    public <T> T execute(UUID commandId, Supplier<T> operation) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(operation, "operation");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException failure) {
                if (!SqlState.isRetryable(failure) || attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
                jitter.pause(commandId, attempt);
            }
        }
        throw new IllegalStateException("retry loop exhausted without a result");
    }

    @FunctionalInterface
    public interface RetryJitter {
        void pause(UUID commandId, int failedAttempt);
    }
}
