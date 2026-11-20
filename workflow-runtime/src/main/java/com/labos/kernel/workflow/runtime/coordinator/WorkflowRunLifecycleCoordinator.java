package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Application-layer bridge between durable Run/Attempt transitions and the
 * process-local per-Run GraphCoordinator mailbox.
 *
 * <p>The database is authoritative. Signals only wake the coordinator; they
 * are never treated as durable work records. A lost/rejected signal does not
 * undo a committed transition. The caller can invoke {@link #wake(UUID, UUID)}
 * again because GraphCoordinator re-reads durable Tokens and Activations.
 *
 * <p>This class does not dispatch Workers, start RuntimeHost, or invoke devices.
 * A Worker adapter must call {@link #completeAttempt} after its result is ready
 * to be committed.
 */
public final class WorkflowRunLifecycleCoordinator implements AutoCloseable {
    public enum SignalType { RUN_STARTED, ATTEMPT_SUCCEEDED, REPLAY_WAKEUP }

    public record Signal(UUID tenantId, UUID runId, SignalType type, UUID attemptId) {
        public Signal {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(type, "type");
        }
    }

    private final WorkflowRuntimeRepository repository;
    private final RunCoordinatorRegistry<Signal> mailboxes;

    public WorkflowRunLifecycleCoordinator(
            WorkflowRuntimeRepository repository,
            GraphCoordinator graphCoordinator,
            Executor sharedMailboxExecutor,
            int mailboxCapacity) {
        this.repository = Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(graphCoordinator, "graphCoordinator");
        this.mailboxes = new RunCoordinatorRegistry<>(
                Objects.requireNonNull(sharedMailboxExecutor, "sharedMailboxExecutor"),
                mailboxCapacity,
                repository::isOpenRun,
                runId -> handlerFor(runId, graphCoordinator));
    }

    /**
     * Start a newly-created Run, then enqueue its first graph advancement.
     * Repeated calls while RUNNING are safe and act as a wakeup. The DB
     * transition commits before the signal is submitted.
     */
    public CompletableFuture<Void> startRun(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        repository.startRunIfCreated(tenantId, runId);
        return wake(tenantId, runId);
    }

    /**
     * Commit the successful Attempt first; notify the Run mailbox only after
     * that transaction returns. Identical completion replays are idempotent.
     */
    public CompletableFuture<Void> completeAttempt(
            UUID tenantId, UUID runId, UUID attemptId,
            String outputKey, String outputJson) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(attemptId, "attemptId");
        repository.completeAttempt(tenantId, runId, attemptId, outputKey, outputJson);
        return mailboxes.submit(runId,
                new Signal(tenantId, runId, SignalType.ATTEMPT_SUCCEEDED, attemptId));
    }

    /**
     * Explicitly re-notify after mailbox rejection or handler failure. The
     * durable graph state, not this signal, determines the actual transition.
     */
    public CompletableFuture<Void> wake(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        return mailboxes.submit(runId,
                new Signal(tenantId, runId, SignalType.REPLAY_WAKEUP, null));
    }

    public int activeMailboxCount() {
        return mailboxes.runCount();
    }

    private Consumer<Signal> handlerFor(UUID mailboxRunId, GraphCoordinator graphCoordinator) {
        return signal -> {
            if (!mailboxRunId.equals(signal.runId()))
                throw new IllegalArgumentException("Mailbox signal Run identity mismatch");
            graphCoordinator.advance(signal.tenantId(), mailboxRunId);
        };
    }

    @Override
    public void close() {
        mailboxes.close();
    }
}
