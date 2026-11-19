package com.labos.kernel.workflow.runtime.recovery;

import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Recovery coordinator for the single Runtime Host process.
 *
 * <p>Run after acquiring the local host lock and before starting WorkerPool
 * or Outbox dispatch. It does not elect a leader or acquire ownership from
 * another process. Local cancellation is best-effort and never proves that
 * a physical device action has stopped.
 */
public final class WorkerExecutionRecoveryService {
    private final WorkflowRuntimeRepository repository;
    private final WorkerExecutionRegistry registry;

    public WorkerExecutionRecoveryService(WorkflowRuntimeRepository repository,
                                          WorkerExecutionRegistry registry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public record RecoveryItem(WorkflowRuntimeRepository.InterruptedAttempt candidate,
                                WorkflowRuntimeRepository.WorkerRecoveryResult result) {}

    public record StartupResult(WorkflowRuntimeRepository.OutboxRestartResult outbox,
                                int scanned, int safeToRetry, int manualRequired,
                                int skipped, List<RecoveryItem> items) {
        public StartupResult { items = List.copyOf(items); }
    }

    /**
     * Reconcile one bounded batch of in-flight Attempts before opening dispatch.
     * If scanned == limit, call again before starting the WorkerPool/Dispatcher.
     */
    public StartupResult recoverAtStartup(int limit, String recoveryActor) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Recovery batch limit must be 1..1000");
        if (recoveryActor == null || recoveryActor.isBlank())
            throw new IllegalArgumentException("recoveryActor is required");
        if (registry.activeCount() != 0)
            throw new IllegalStateException("Startup recovery must run before local Workers are started");

        List<WorkflowRuntimeRepository.InterruptedAttempt> candidates =
                repository.findInterruptedAttempts(limit);
        List<RecoveryItem> items = new ArrayList<>(candidates.size());
        int safe = 0, manual = 0, skipped = 0;
        for (var candidate : candidates) {
            var result = repository.recoverInterruptedAttempt(
                    candidate.tenantId(), candidate.runId(), candidate.attemptId(), recoveryActor);
            items.add(new RecoveryItem(candidate, result));
            switch (result.outcome()) {
                case "SAFE_TO_RETRY" -> safe++;
                case "MANUAL_REQUIRED" -> manual++;
                default -> skipped++;
            }
        }

        // Attempt reconciliation must precede Outbox replay. In particular,
        // a Worker_DISPATCH for an ACCEPTED/STARTED Attempt must not be replayed.
        var outbox = repository.recoverOutboxAfterRestart();
        return new StartupResult(outbox, candidates.size(), safe, manual, skipped, items);
    }

    /** Cancel a live local Worker first, then reconcile from durable facts. */
    public WorkflowRuntimeRepository.WorkerRecoveryResult handleLocalTimeout(
            UUID tenantId, UUID runId, UUID attemptId, String recoveryActor) {
        registry.requestCancellation(attemptId);
        return repository.recoverInterruptedAttempt(tenantId,runId,attemptId,recoveryActor);
    }

    public boolean requestLocalCancellation(UUID attemptId) {
        return registry.requestCancellation(attemptId);
    }

    public int activeLocalExecutions() { return registry.activeCount(); }
}