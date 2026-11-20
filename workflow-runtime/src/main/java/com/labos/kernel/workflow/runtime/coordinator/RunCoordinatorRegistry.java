package com.labos.kernel.workflow.runtime.coordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Process-local registry that gives each tenant-scoped Run exactly one serial mailbox.
 * The supplied executor is shared by all Run mailboxes; no thread is allocated
 * per Run. Durable Run/Graph state remains in WorkflowRuntimeRepository.
 *
 * <p>The {@code runIsActive} admission check is always executed outside the registry
 * lifecycle lock so database latency for one Run cannot block other Runs.
 */
public final class RunCoordinatorRegistry<T> implements AutoCloseable {
    public record RunKey(UUID tenantId, UUID runId) {
        public RunKey {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    private final Executor mailboxExecutor;
    private final int mailboxCapacity;
    private final Predicate<RunKey> runIsActive;
    private final Function<RunKey, Consumer<T>> handlerFactory;
    private final ConcurrentMap<RunKey, SerialMailbox<T>> mailboxes = new ConcurrentHashMap<>();
    private final Set<RunKey> evictWhenIdle = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;

    public RunCoordinatorRegistry(Executor mailboxExecutor, int mailboxCapacity,
                                  Predicate<RunKey> runIsActive,
                                  Function<RunKey, Consumer<T>> handlerFactory) {
        this.mailboxExecutor = Objects.requireNonNull(mailboxExecutor, "mailboxExecutor");
        if (mailboxCapacity < 1) throw new IllegalArgumentException("mailboxCapacity must be >= 1");
        this.mailboxCapacity = mailboxCapacity;
        this.runIsActive = Objects.requireNonNull(runIsActive, "runIsActive");
        this.handlerFactory = Objects.requireNonNull(handlerFactory, "handlerFactory");
    }

    public SerialMailbox<T> mailboxFor(UUID tenantId, UUID runId) {
        return mailboxFor(new RunKey(tenantId, runId));
    }

    public SerialMailbox<T> mailboxFor(RunKey key) {
        Objects.requireNonNull(key, "key");
        if (closed) throw new IllegalStateException("RunCoordinatorRegistry is closed");
        SerialMailbox<T> existing = mailboxes.get(key);
        if (existing != null && !existing.isClosed()) return existing;

        // Admission check runs outside lifecycleLock so DB I/O never stalls other Runs.
        if (!runIsActive.test(key))
            throw new IllegalStateException("Run is not active; refusing to create a new coordinator mailbox");

        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("RunCoordinatorRegistry is closed");
            SerialMailbox<T> current = mailboxes.get(key);
            if (current != null && !current.isClosed()) return current;

            Consumer<T> handler = Objects.requireNonNull(handlerFactory.apply(key), "Run event handler");
            AtomicReference<SerialMailbox<T>> createdRef = new AtomicReference<>();
            SerialMailbox<T> created = new SerialMailbox<>(mailboxCapacity, mailboxExecutor, handler, () -> {
                SerialMailbox<T> target = createdRef.get();
                if (target != null && evictWhenIdle.contains(key)) {
                    removeIfIdle(key, target);
                }
            });
            createdRef.set(created);
            evictWhenIdle.remove(key);
            mailboxes.put(key, created);
            return created;
        }
    }

    public CompletableFuture<Void> submit(UUID tenantId, UUID runId, T event) {
        return submit(new RunKey(tenantId, runId), event);
    }

    public CompletableFuture<Void> submit(RunKey key, T event) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(event, "event");
        for (int attempt = 0; attempt < 4; attempt++) {
            SerialMailbox<T> mailbox = mailboxFor(key);
            try {
                return mailbox.submit(event);
            } catch (IllegalStateException mailboxClosed) {
                if (closed || !mailbox.isClosed()) throw mailboxClosed;
                // The mailbox was concurrently evicted while idle; re-check run admission and retry.
            }
        }
        throw new IllegalStateException("Run mailbox remained concurrently evicted during submit");
    }

    /**
     * Mark this Run's mailbox to be closed and removed as soon as it becomes idle.
     * Safe to invoke from inside the mailbox's own handler thread.
     */
    public boolean requestEvictWhenIdle(UUID tenantId, UUID runId) {
        return requestEvictWhenIdle(new RunKey(tenantId, runId));
    }

    public boolean requestEvictWhenIdle(RunKey key) {
        Objects.requireNonNull(key, "key");
        evictWhenIdle.add(key);
        return remove(key);
    }

    public boolean remove(UUID tenantId, UUID runId) {
        return remove(new RunKey(tenantId, runId));
    }

    public boolean remove(RunKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (lifecycleLock) {
            SerialMailbox<T> mailbox = mailboxes.get(key);
            if (mailbox == null) {
                evictWhenIdle.remove(key);
                return false;
            }
            if (!mailbox.closeIfIdle()) return false;
            evictWhenIdle.remove(key);
            return mailboxes.remove(key, mailbox);
        }
    }

    private boolean removeIfIdle(RunKey key, SerialMailbox<T> expected) {
        synchronized (lifecycleLock) {
            SerialMailbox<T> current = mailboxes.get(key);
            if (current != expected || !evictWhenIdle.contains(key)) return false;
            if (!current.closeIfIdle()) return false;
            evictWhenIdle.remove(key);
            return mailboxes.remove(key, current);
        }
    }

    public int runCount() { return mailboxes.size(); }

    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        List<SerialMailbox<T>> snapshot;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<>(mailboxes.values());
            mailboxes.clear();
            evictWhenIdle.clear();
        }
        for (SerialMailbox<T> mailbox : snapshot) mailbox.close();
    }
}
