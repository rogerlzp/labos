package com.labos.kernel.workflow.runtime.recovery;

import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;

import java.util.Objects;
import java.util.UUID;

/**
 * Single-host recovery coordinator. It reconciles durable Attempts at Host
 * startup and requests best-effort cancellation for local timed-out workers.
 * It never claims execution rights from another process.
 */
public final class WorkerExecutionRecoveryService {
    private final WorkflowRuntimeRepository repository;
    private final WorkerExecutionRegistry registry;

    public WorkerExecutionRecoveryService(WorkflowRuntimeRepository repository,
                                          WorkerExecutionRegistry registry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public boolean requestLocalCancellation(UUID attemptId) {
        return registry.requestCancellation(attemptId);
    }

    /**
     * Startup reconciliation is durable-state driven. A missing process-local
     * handle alone is not evidence that a physical side effect did not occur.
     */
    public WorkflowRuntimeRepository.WorkerRecoveryResult recoverAttempt(
            UUID tenantId, UUID runId, UUID attemptId, String recoveryActor) {
        return repository.recoverInterruptedAttempt(tenantId, runId, attemptId, recoveryActor);
    }

    public int activeLocalExecutions() {
        return registry.activeCount();
    }
}
