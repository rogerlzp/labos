package com.labos.kernel.workflow.runtime.worker;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local ownership of active Worker executions for the single Runtime Host.
 *
 * <p>This registry is deliberately in-memory: it is not a lease service and
 * grants no authority across processes. Durable execution evidence belongs
 * in WorkflowRuntimeRepository. A JVM restart naturally discards these handles.
 */
public final class WorkerExecutionRegistry {
    public record Snapshot(UUID attemptId, String workerId, boolean cancellationRequested,
                           long registeredAtNanos) {}

    private static final class Entry {
        final String workerId;
        final Thread thread;
        final Runnable cancellation;
        final long registeredAtNanos = System.nanoTime();
        final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private boolean finished;

        Entry(String workerId, Thread thread, Runnable cancellation) {
            this.workerId = workerId;
            this.thread = thread;
            this.cancellation = cancellation;
        }

        synchronized boolean cancel() {
            if (finished) return false;
            if (cancellationRequested.compareAndSet(false, true)) {
                try { cancellation.run(); }
                finally { thread.interrupt(); }
                return true;
            }
            return false;
        }

        synchronized void finish() { finished = true; }
    }

    private final Map<UUID, Entry> active = new ConcurrentHashMap<>();

    /** Register exactly one local execution handle for an Attempt. */
    public void register(UUID attemptId, String workerId, Thread thread, Runnable cancellation) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId is required");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(cancellation, "cancellation");
        Entry entry = new Entry(workerId, thread, cancellation);
        if (active.putIfAbsent(attemptId, entry) != null)
            throw new IllegalStateException("Attempt already has a local Worker execution");
    }

    /** Remove only the local handle; durable Attempt state is managed separately. */
    public boolean unregister(UUID attemptId, Thread expectedThread) {
        Entry current = active.get(attemptId);
        if (current == null || current.thread != expectedThread || !active.remove(attemptId, current))
            return false;
        current.finish();
        return true;
    }

    /** Best-effort local cancellation. This is not proof that a device action stopped. */
    public boolean requestCancellation(UUID attemptId) {
        Entry entry = active.get(attemptId);
        if (entry == null) return false;
        return entry.cancel();
    }

    public boolean isActive(UUID attemptId) { return active.containsKey(attemptId); }

    public Snapshot snapshot(UUID attemptId) {
        Entry e = active.get(attemptId);
        return e == null ? null : new Snapshot(attemptId, e.workerId,
                e.cancellationRequested.get(), e.registeredAtNanos);
    }

    public int activeCount() { return active.size(); }
}
