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
    private final UUID scopedTenantId;

    public WorkerExecutionRecoveryService(WorkflowRuntimeRepository repository,
                                          WorkerExecutionRegistry registry) {
        this(repository, registry, null);
    }

    public WorkerExecutionRecoveryService(WorkflowRuntimeRepository repository,
                                          WorkerExecutionRegistry registry,
                                          UUID scopedTenantId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scopedTenantId = scopedTenantId;
    }

    public record RecoveryItem(WorkflowRuntimeRepository.InterruptedAttempt candidate,
                                WorkflowRuntimeRepository.WorkerRecoveryResult result) {}

    public record StartupResult(WorkflowRuntimeRepository.OutboxRestartResult outbox,
                                int scanned, int safeToRetry, int manualRequired,
                                int skipped, List<RecoveryItem> items) {
        public StartupResult { items = List.copyOf(items); }
    }

    /**
     * Reconcile all bounded pages of in-flight Attempts before opening dispatch. Outbox recovery runs only after all pages are safe.
     */
    public StartupResult recoverAtStartup(int limit, String recoveryActor) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Recovery batch limit must be 1..1000");
        if (recoveryActor == null || recoveryActor.isBlank())
            throw new IllegalArgumentException("recoveryActor is required");
        if (registry.activeCount() != 0)
            throw new IllegalStateException("Startup recovery must run before local Workers are started");

        List<RecoveryItem> items = new ArrayList<>();
        int scanned = 0, safe = 0, manual = 0, skipped = 0;
        while (true) {
            List<WorkflowRuntimeRepository.InterruptedAttempt> candidates = scopedTenantId != null
                    ? repository.findInterruptedAttemptsForTenant(scopedTenantId, limit)
                    : repository.findInterruptedAttempts(limit);
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
            // An in-flight row that cannot be reconciled must block startup;
            // otherwise the same first page could be returned forever or unsafe work replayed.
            if (skipped > 0)
                throw new IllegalStateException("Startup recovery found unreconciled Attempts; Host must remain closed");
        }

        // Requeue Outbox only after every in-flight Attempt has been reconciled.
        var outbox = scopedTenantId != null
                ? repository.recoverOutboxAfterRestartForTenant(scopedTenantId)
                : repository.recoverOutboxAfterRestart();
        return new StartupResult(outbox, scanned, safe, manual, skipped, items);
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