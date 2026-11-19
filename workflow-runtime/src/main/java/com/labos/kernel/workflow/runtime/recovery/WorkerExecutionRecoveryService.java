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
 * <p>It does not acquire ownership from another process. On startup it
 * reconciles durable SENDING outbox rows and interrupted Attempts. Local
 * cancellation is best-effort and never treated as proof of physical stop.
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

    /** Run once before the Host opens the scheduling gate. */
    public StartupResult recoverAtStartup(int limit, String recoveryActor) {
        if (recoveryActor == null || recoveryActor.isBlank())
            throw new IllegalArgumentException("recoveryActor is required");
        var outbox = repository.recoverOutboxAfterRestart();
        List<RecoveryItem> items = new ArrayList<>();
        int scanned = 0, safe = 0, manual = 0, skipped = 0;
        while (true) {
            List<WorkflowRuntimeRepository.InterruptedAttempt> candidates =
                    repository.findInterruptedAttempts(limit);
            if (candidates.isEmpty()) break;
            scanned += candidates.size();
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
            // Recovery changes every STARTED candidate to a non-STARTED state.
            // Re-read from the beginning until no candidate remains; a single
            // bounded page must never be mistaken for a complete startup scan.
        }
        return new StartupResult(outbox,scanned,safe,manual,skipped,items);
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
