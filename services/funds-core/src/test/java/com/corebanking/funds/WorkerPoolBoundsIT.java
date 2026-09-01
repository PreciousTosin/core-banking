package com.corebanking.funds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.ExecutorRecorder;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves the ACC-25 worker-pool boundary: the Quarkus global executor is bounded to 2–8 threads
 * plus a 32-task queue and rejects the first task beyond that capacity with a
 * {@link RejectedExecutionException} instead of growing, blocking or dropping it silently. Catches
 * a configuration drift that would let request load allocate unbounded threads or queue memory
 * inside the 640 MiB container budget described in the README memory boundary.
 */
@QuarkusTest
class WorkerPoolBoundsIT {
    // Mirror quarkus.thread-pool.core-threads/max-threads/queue-size in application.properties.
    private static final int CORE_THREADS = 2;
    private static final int MAX_THREADS = 8;
    private static final int QUEUE_CAPACITY = 32;

    @Test
    void globalWorkerPoolHasAFiniteQueueAndRejectsBeyondItsDeclaredCapacity() throws Exception {
        var executor = ExecutorRecorder.getCurrent();

        // Eight blockers occupy every worker and hold it; the next 32 fill the queue; task 41
        // has nowhere to go and must be rejected deterministically.
        int capacity = MAX_THREADS + QUEUE_CAPACITY;
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(MAX_THREADS);
        var accepted = new ArrayList<CompletableFuture<Void>>(capacity);
        Runnable blocker = () -> {
            started.countDown();
            try {
                assertTrue(release.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            for (int task = 0; task < capacity; task++) {
                accepted.add(CompletableFuture.runAsync(blocker, executor));
            }

            assertTrue(started.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS),
                "the declared maximum workers must start while the queue remains blocked");
            assertThrows(RejectedExecutionException.class,
                () -> CompletableFuture.runAsync(blocker, executor));
        } finally {
            release.countDown();
        }
        CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new))
            .get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(capacity, accepted.size());
    }
}
