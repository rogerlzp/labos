package com.labos.kernel.workflow.runtime.worker;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkerExecutionRegistryTest {
    @Test
    void registrationAndCancellationAreProcessLocalAndIdempotent() throws Exception {
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        UUID attemptId = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        }, "runtime-worker-test");
        registry.register(attemptId, "worker-1", worker, cancellations::incrementAndGet);
        worker.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(1, registry.activeCount());
        assertTrue(registry.requestCancellation(attemptId));
        assertFalse(registry.requestCancellation(attemptId));
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertEquals(1, cancellations.get());
        assertTrue(registry.snapshot(attemptId).cancellationRequested());
        assertTrue(registry.unregister(attemptId, worker));
        assertFalse(registry.unregister(attemptId, worker));
        assertEquals(0, registry.activeCount());
    }

    @Test
    void duplicateAttemptRegistrationIsRejected() {
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        UUID attemptId = UUID.randomUUID();
        Thread thread = new Thread();
        registry.register(attemptId, "worker-1", thread, () -> {});
        assertThrows(IllegalStateException.class,
                () -> registry.register(attemptId, "worker-2", new Thread(), () -> {}));
        assertTrue(registry.unregister(attemptId, thread));
    }
}
