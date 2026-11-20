package com.labos.kernel.workflow.runtime.worker;

import com.labos.kernel.workflow.runtime.host.RuntimeHost;
import com.labos.kernel.workflow.runtime.host.SchedulingGate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One bounded WorkerPool per single Runtime Host.
 *
 * <p>Per-Run permits count both running and queued tasks, preventing one Run
 * from filling the global queue. Worker handles are registered only after a
 * task starts executing on a real pool thread.
 */
public final class WorkerPool implements RuntimeHost.LifecycleComponent, AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final WorkerExecutionRegistry registry;
    private final SchedulingGate schedulingGate;
    private final int perRunLimit;
    private final ConcurrentMap<UUID, AtomicInteger> inFlightByRun = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, FutureTask<?>> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorkerPool(int workerThreads, int queueCapacity, int perRunLimit,
                      WorkerExecutionRegistry registry, SchedulingGate schedulingGate) {
        if (workerThreads < 1) throw new IllegalArgumentException("workerThreads must be >= 1");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        if (perRunLimit < 1) throw new IllegalArgumentException("perRunLimit must be >= 1");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.schedulingGate = Objects.requireNonNull(schedulingGate, "schedulingGate");
        this.perRunLimit = perRunLimit;
        this.executor = new ThreadPoolExecutor(workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public void start() {
        if (closing.get() || closed.get()) throw new IllegalStateException("WorkerPool is closing or closed");
        if (!started.compareAndSet(false, true))
            throw new IllegalStateException("WorkerPool already started");
    }

    public <T> Future<T> submit(UUID runId, UUID attemptId, String workerId, Callable<T> work) {
        return submit(runId, attemptId, workerId, work, () -> {});
    }

    /**
     * Submit an Attempt to the pool and run {@code afterRelease} on the worker thread strictly
     * AFTER {@link WorkerExecutionRegistry#unregister}, task map removal, and per-Run permit release
     * have all completed. This guarantees callers unblocked by {@code afterRelease} can safely
     * observe zero in-flight permits or resubmit the same Attempt/Run immediately without racing.
     */
    public <T> Future<T> submit(UUID runId, UUID attemptId, String workerId,
                                Callable<T> work, Runnable afterRelease) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(afterRelease, "afterRelease");
        if (workerId == null || workerId.isBlank())
            throw new IllegalArgumentException("workerId is required");
        if (!started.get() || closing.get() || closed.get())
            throw new IllegalStateException("WorkerPool is not running");
        schedulingGate.requireOpen();
        reserveRunPermit(runId);

        AtomicInteger phase = new AtomicInteger(0); // 0=queued, 1=running, 2=finished
        AtomicBoolean released = new AtomicBoolean();
        AtomicReference<FutureTask<?>> taskRef = new AtomicReference<>();
        Runnable release = () -> {
            if (!released.compareAndSet(false, true)) return;
            FutureTask<?> task = taskRef.get();
            if (task != null) tasks.remove(attemptId, task);
            releaseRunPermit(runId);
        };

        FutureTask<T> task = new FutureTask<>(() -> {
            if (!phase.compareAndSet(0, 1)) throw new CancellationException("Attempt was cancelled before execution");
            Thread thread = Thread.currentThread();
            boolean registered = false;
            try {
                registry.register(attemptId, workerId, thread, () -> {});
                registered = true;
                return work.call();
            } finally {
                try {
                    if (registered) registry.unregister(attemptId, thread);
                } finally {
                    phase.set(2);
                    release.run();
                    afterRelease.run();
                }
            }
        }) {
            @Override protected void done() {
                if (phase.compareAndSet(0, 2)) release.run();
            }
        };
        taskRef.set(task);
        if (tasks.putIfAbsent(attemptId, task) != null) {
            phase.set(2);
            release.run();
            throw new IllegalStateException("Attempt already submitted to WorkerPool");
        }
        try {
            executor.execute(task);
            return task;
        } catch (RejectedExecutionException rejected) {
            task.cancel(false);
            throw rejected;
        }
    }

    /** Best-effort cancellation; it does not prove that an external side effect stopped. */
    public boolean cancel(UUID attemptId) {
        FutureTask<?> task = tasks.get(attemptId);
        if (task == null) return false;
        boolean local = registry.requestCancellation(attemptId);
        boolean future = task.cancel(true);
        return local || future;
    }

    public int inFlightForRun(UUID runId) {
        AtomicInteger count = inFlightByRun.get(runId);
        return count == null ? 0 : count.get();
    }

    public int activeThreadCount() { return executor.getActiveCount(); }

    public int queuedTaskCount() { return executor.getQueue().size(); }

    public boolean isRunning() { return started.get() && !closing.get() && !closed.get(); }

    private void reserveRunPermit(UUID runId) {
        AtomicInteger count = inFlightByRun.compute(runId, (key, current) -> {
            if (current == null) return new AtomicInteger(1);
            current.incrementAndGet();
            return current;
        });
        if (count.get() > perRunLimit) {
            releaseRunPermit(runId);
            throw new RejectedExecutionException("Per-Run Worker limit reached: " + runId);
        }
    }

    private void releaseRunPermit(UUID runId) {
        inFlightByRun.computeIfPresent(runId, (key, count) ->
                count.decrementAndGet() == 0 ? null : count);
    }

    @Override
    public void stop() { close(); }

    @Override
    public void close() {
        if (closed.get()) return;
        if (closing.compareAndSet(false, true)) {
            started.set(false);
            for (UUID attemptId : List.copyOf(tasks.keySet())) cancel(attemptId);
            for (Runnable queued : executor.shutdownNow()) {
                if (queued instanceof Future<?> future) future.cancel(false);
            }
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("WorkerPool shutdown timed out; active workers may still be running");
            closed.set(true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for WorkerPool shutdown", interrupted);
        }
    }
}
