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

@QuarkusTest
class WorkerPoolBoundsIT {
    private static final int CORE_THREADS = 2;
    private static final int MAX_THREADS = 8;
    private static final int QUEUE_CAPACITY = 32;

    @Test
    void globalWorkerPoolHasAFiniteQueueAndRejectsBeyondItsDeclaredCapacity() throws Exception {
        var executor = ExecutorRecorder.getCurrent();

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
