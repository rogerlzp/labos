package com.labos.kernel.workflow.runtime.coordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Process-local registry that gives each Run exactly one serial mailbox.
 * The supplied executor is shared by all Run mailboxes; no thread is allocated
 * per Run. Durable Run/Graph state remains in WorkflowRuntimeRepository.
 */
public final class RunCoordinatorRegistry<T> implements AutoCloseable {
    private final Executor mailboxExecutor;
    private final int mailboxCapacity;
    private final Predicate<UUID> runIsActive;
    private final java.util.function.Function<UUID, Consumer<T>> handlerFactory;
    private final ConcurrentMap<UUID, SerialMailbox<T>> mailboxes = new ConcurrentHashMap<>();
    private boolean closed;

    public RunCoordinatorRegistry(Executor mailboxExecutor, int mailboxCapacity,
                                  Predicate<UUID> runIsActive,
                                  java.util.function.Function<UUID, Consumer<T>> handlerFactory) {
        this.mailboxExecutor = Objects.requireNonNull(mailboxExecutor, "mailboxExecutor");
        if (mailboxCapacity < 1) throw new IllegalArgumentException("mailboxCapacity must be >= 1");
        this.mailboxCapacity = mailboxCapacity;
        this.runIsActive = Objects.requireNonNull(runIsActive, "runIsActive");
        this.handlerFactory = Objects.requireNonNull(handlerFactory, "handlerFactory");
    }

    public synchronized SerialMailbox<T> mailboxFor(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        if (closed) throw new IllegalStateException("RunCoordinatorRegistry is closed");
        SerialMailbox<T> existing = mailboxes.get(runId);
        if (existing != null) return existing;
        if (!runIsActive.test(runId))
            throw new IllegalStateException("Run is not active; refusing to create a new coordinator mailbox");
        return mailboxes.computeIfAbsent(runId, id ->
                new SerialMailbox<>(mailboxCapacity, mailboxExecutor,
                        Objects.requireNonNull(handlerFactory.apply(id), "Run event handler")));
    }

    public CompletableFuture<Void> submit(UUID runId, T event) {
        return mailboxFor(runId).submit(event);
    }

    public synchronized boolean remove(UUID runId) {
        SerialMailbox<T> mailbox = mailboxes.get(runId);
        if (mailbox == null || !mailbox.closeIfIdle()) return false;
        return mailboxes.remove(runId, mailbox);
    }

    public int runCount() { return mailboxes.size(); }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<SerialMailbox<T>> snapshot = new ArrayList<>(mailboxes.values());
        mailboxes.clear();
        for (SerialMailbox<T> mailbox : snapshot) mailbox.close();
    }
}
