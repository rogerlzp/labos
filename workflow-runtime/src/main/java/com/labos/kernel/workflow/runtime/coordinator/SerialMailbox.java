package com.labos.kernel.workflow.runtime.coordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded, process-local serial mailbox for one RunCoordinator.
 *
 * <p>Many mailboxes may share one Host executor. The draining flag guarantees
 * that at most one event from this mailbox is processed at a time; it does not
 * allocate one thread per Run.
 */
public final class SerialMailbox<T> implements AutoCloseable {
    private record Envelope<T>(T message, CompletableFuture<Void> completion) {}

    private final Object lock = new Object();
    private final ArrayBlockingQueue<Envelope<T>> queue;
    private final Executor executor;
    private final Consumer<T> handler;
    private final AtomicBoolean draining = new AtomicBoolean();
    private boolean closed;

    public SerialMailbox(int capacity, Executor executor, Consumer<T> handler) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /** Enqueue one message or reject immediately when the bounded mailbox is full. */
    public CompletableFuture<Void> submit(T message) {
        Objects.requireNonNull(message, "message");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) throw new IllegalStateException("SerialMailbox is closed");
            if (!queue.offer(new Envelope<>(message, completion)))
                throw new RejectedExecutionException("Run mailbox capacity exhausted");
        }
        scheduleDrain();
        return completion;
    }

    public int queuedCount() { return queue.size(); }

    public boolean isClosed() {
        synchronized (lock) { return closed; }
    }

    public boolean isIdle() {
        synchronized (lock) { return queue.isEmpty() && !draining.get(); }
    }

    /** Atomically close only when no message is queued or currently executing. */
    boolean closeIfIdle() {
        synchronized (lock) {
            if (!queue.isEmpty() || draining.get()) return false;
            closed = true;
            return true;
        }
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException rejected) {
            draining.set(false);
            failPending(rejected);
        }
    }

    private void drain() {
        while (true) {
            Envelope<T> next;
            synchronized (lock) {
                if (closed) {
                    draining.set(false);
                    return;
                }
                next = queue.poll();
                if (next == null) {
                    draining.set(false);
                    // submit() may have enqueued immediately before draining was cleared.
                    if (!queue.isEmpty()) scheduleDrain();
                    return;
                }
            }
            try {
                handler.accept(next.message());
                next.completion().complete(null);
            } catch (RuntimeException failure) {
                next.completion().completeExceptionally(failure);
            }
        }
    }

    private void failPending(RuntimeException failure) {
        List<Envelope<T>> pending = new ArrayList<>();
        synchronized (lock) {
            closed = true;
            queue.drainTo(pending);
        }
        for (Envelope<T> envelope : pending) envelope.completion().completeExceptionally(failure);
    }

    @Override
    public void close() {
        List<Envelope<T>> pending = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            queue.drainTo(pending);
        }
        CancellationException cancelled = new CancellationException("Run mailbox closed before processing");
        for (Envelope<T> envelope : pending) envelope.completion().completeExceptionally(cancelled);
    }
}
