package com.labos.kernel.workflow.runtime.recovery;

import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded recovery pass for expired Worker execution rights.
 *
 * <p>This is an invocable service, not a background scheduler. The host
 * runtime must decide cadence, leadership, shutdown and observability.
 * Candidate discovery is advisory; every recovery decision is revalidated
 * under the Run and execution-object locks by the repository.
 */
public final class WorkerLeaseRecoveryService {
    private final WorkflowRuntimeRepository repository;

    public WorkerLeaseRecoveryService(WorkflowRuntimeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public record RecoveryItem(
            WorkflowRuntimeRepository.ExpiredWorkerAttempt candidate,
            WorkflowRuntimeRepository.WorkerRecoveryResult result) {}

    public record BatchResult(int scanned, int safeToRetry, int manualRequired,
                              int skipped, List<RecoveryItem> items) {
        public BatchResult {
            items = List.copyOf(items);
        }
    }

    /**
     * Recover at most limit expired leases. Repeated/concurrent scans are safe:
     * a candidate that another recovery pass already handled is reported as skipped.
     */
    public BatchResult recoverExpiredBatch(int limit, String recoveryActor) {
        if (recoveryActor == null || recoveryActor.isBlank())
            throw new IllegalArgumentException("recoveryActor is required");
        List<WorkflowRuntimeRepository.ExpiredWorkerAttempt> candidates =
                repository.findExpiredWorkerAttempts(limit);
        List<RecoveryItem> items = new ArrayList<>(candidates.size());
        int safe = 0, manual = 0, skipped = 0;
        for (var candidate : candidates) {
            var result = repository.recoverExpiredWorkerAttempt(
                    candidate.tenantId(), candidate.runId(), candidate.attemptId(), recoveryActor);
            items.add(new RecoveryItem(candidate, result));
            switch (result.outcome()) {
                case "SAFE_TO_RETRY" -> safe++;
                case "MANUAL_REQUIRED" -> manual++;
                default -> skipped++;
            }
        }
        return new BatchResult(candidates.size(), safe, manual, skipped, items);
    }
}
