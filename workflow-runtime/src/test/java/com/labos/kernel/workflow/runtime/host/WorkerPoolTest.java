package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import com.labos.kernel.workflow.runtime.worker.WorkerPool;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorkerPoolTest {
    @Test
    void enforcesPerRunLimitAndAllowsDifferentRunsToExecuteInParallel() throws Exception {
        SchedulingGate gate = new SchedulingGate();
        gate.open();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        WorkerPool pool = new WorkerPool(2, 2, 1, registry, gate);
        pool.start();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        UUID runA = UUID.randomUUID(), runB = UUID.randomUUID();

        try {
            Future<String> first = pool.submit(runA, UUID.randomUUID(), "worker-a", () -> {
                started.countDown();
                release.await();
                return "A";
            });
            assertThrows(RejectedExecutionException.class,
                    () -> pool.submit(runA, UUID.randomUUID(), "worker-a2", () -> "A2"));
            Future<String> second = pool.submit(runB, UUID.randomUUID(), "worker-b", () -> {
                started.countDown();
                release.await();
                return "B";
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(1, pool.inFlightForRun(runA));
            assertEquals(1, pool.inFlightForRun(runB));
            release.countDown();
            assertEquals("A", first.get(2, TimeUnit.SECONDS));
            assertEquals("B", second.get(2, TimeUnit.SECONDS));
            assertEquals(0, pool.inFlightForRun(runA));
            assertEquals(0, pool.inFlightForRun(runB));
        } finally {
            release.countDown();
            pool.close();
        }
    }

    @Test
    void rejectsWhenGlobalThreadsAndQueueAreFullAndReleasesRejectedPermit() throws Exception {
        SchedulingGate gate = new SchedulingGate();
        gate.open();
        WorkerPool pool = new WorkerPool(1, 1, 3, new WorkerExecutionRegistry(), gate);
        pool.start();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        UUID run = UUID.randomUUID();

        try {
            Future<Integer> running = pool.submit(run, UUID.randomUUID(), "worker-1", () -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Future<Integer> queued = pool.submit(run, UUID.randomUUID(), "worker-2", () -> 2);
            assertThrows(RejectedExecutionException.class,
                    () -> pool.submit(run, UUID.randomUUID(), "worker-3", () -> 3));
            assertEquals(2, pool.inFlightForRun(run));
            release.countDown();
            assertEquals(1, running.get(2, TimeUnit.SECONDS));
            assertEquals(2, queued.get(2, TimeUnit.SECONDS));
            assertEquals(0, pool.inFlightForRun(run));
        } finally {
            release.countDown();
            pool.close();
        }
    }

    @Test
    void cancellationInterruptsLocalWorkerAndUnregistersItsHandle() throws Exception {
        SchedulingGate gate = new SchedulingGate();
        gate.open();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        WorkerPool pool = new WorkerPool(1, 1, 1, registry, gate);
        pool.start();
        UUID attempt = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        UUID runId = UUID.randomUUID();

        try {
            Future<?> future = pool.submit(runId, attempt, "worker-cancel", () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    return null;
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                    throw expected;
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(registry.isActive(attempt));
            assertTrue(pool.cancel(attempt));
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(interrupted.get());
            assertThrows(CancellationException.class, future::get);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.inFlightForRun(runId) != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, pool.inFlightForRun(runId));
            assertFalse(registry.isActive(attempt));
        } finally {
            pool.close();
        }
    }

    @Test
    void schedulingGateMustBeOpenBeforeSubmission() {
        SchedulingGate gate = new SchedulingGate();
        WorkerPool pool = new WorkerPool(1, 1, 1, new WorkerExecutionRegistry(), gate);
        pool.start();
        try {
            assertThrows(IllegalStateException.class,
                    () -> pool.submit(UUID.randomUUID(), UUID.randomUUID(), "worker", () -> "x"));
        } finally {
            pool.close();
        }
    }
}
