package com.labos.kernel.workflow.runtime.recovery;

import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    public record InboxRecoverySummary(int applied, int rejected) {}

    public record StartupResult(InboxRecoverySummary inbox,
                                WorkflowRuntimeRepository.OutboxRestartResult outbox,
                                int scanned, int safeToRetry, int manualRequired,
                                int skipped, List<RecoveryItem> items) {
        public StartupResult { items = List.copyOf(items); }

        public StartupResult(WorkflowRuntimeRepository.OutboxRestartResult outbox,
                             int scanned, int safeToRetry, int manualRequired,
                             int skipped, List<RecoveryItem> items) {
            this(new InboxRecoverySummary(0, 0), outbox, scanned, safeToRetry, manualRequired, skipped, items);
        }
    }

    /**
     * Reconcile durable command Inbox receipts first, then all bounded pages of in-flight Attempts,
     * and finally Outbox recovery after all Attempts are safe.
     */
    public StartupResult recoverAtStartup(int limit, String recoveryActor) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Recovery batch limit must be 1..1000");
        if (recoveryActor == null || recoveryActor.isBlank())
            throw new IllegalArgumentException("recoveryActor is required");
        if (registry.activeCount() != 0)
            throw new IllegalStateException("Startup recovery must run before local Workers are started");

        // Step 0: finish or reject durable RECEIVED/PROCESSING command inbox rows before inspecting Attempts
        // so committed device outcomes (e.g. SUCCEEDED/FAILED) are never overlooked on crash recovery.
        InboxRecoverySummary inboxSummary = recoverPendingCommandInbox(limit, recoveryActor);

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
        return new StartupResult(inboxSummary, outbox, scanned, safe, manual, skipped, items);
    }

    private InboxRecoverySummary recoverPendingCommandInbox(int limit, String recoveryActor) {
        int applied = 0;
        int rejected = 0;
        while (true) {
            List<WorkflowRuntimeRepository.PendingCommandInboxReceipt> pending = scopedTenantId != null
                    ? repository.findPendingCommandInboxReceiptsForTenant(scopedTenantId, limit)
                    : repository.findPendingCommandInboxReceipts(limit);
            if (pending.isEmpty()) break;
            int progressed = 0;
            for (var receipt : pending) {
                switch (reconcilePendingCommandInboxReceipt(receipt, recoveryActor)) {
                    case APPLIED -> {
                        applied++;
                        progressed++;
                    }
                    case REJECTED -> {
                        rejected++;
                        progressed++;
                    }
                    case ALREADY_CONSUMED -> progressed++;
                }
            }
            if (progressed == 0) {
                throw new IllegalStateException(
                        "Startup command inbox recovery made zero progress; Host must remain closed");
            }
        }
        return new InboxRecoverySummary(applied, rejected);
    }

    private enum InboxReconcileOutcome { APPLIED, REJECTED, ALREADY_CONSUMED }

    private InboxReconcileOutcome reconcilePendingCommandInboxReceipt(
            WorkflowRuntimeRepository.PendingCommandInboxReceipt receipt,
            String recoveryActor) {
        try {
            if (receipt.commandIdText() == null || receipt.commandIdText().isBlank()
                    || receipt.targetState() == null || receipt.targetState().isBlank()) {
                throw new IllegalArgumentException("Missing commandId or state in command inbox payload");
            }
            UUID commandId = UUID.fromString(receipt.commandIdText());
            repository.applyCommandEvent(
                    receipt.tenantId(),
                    receipt.runId(),
                    receipt.inboxId(),
                    commandId,
                    receipt.targetState(),
                    receipt.deviceSequence(),
                    receipt.outcomeJson());
            return InboxReconcileOutcome.APPLIED;
        } catch (WorkflowRuntimeRepository.StaleRuntimeWriteException stale) {
            Optional<String> state = repository.findInboxReceiptState(receipt.tenantId(), receipt.inboxId());
            if (state.isPresent() && ("PROCESSED".equals(state.get()) || "REJECTED".equals(state.get()))) {
                return InboxReconcileOutcome.ALREADY_CONSUMED;
            }
            throw new IllegalStateException(
                    "Startup command inbox replay hit unresolved stale state for inbox " + receipt.inboxId(), stale);
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                | IllegalArgumentException
                | IllegalStateException permanentFailure) {
            String reason = safeMessage(permanentFailure);
            boolean marked = repository.markInboxRejected(
                    receipt.tenantId(),
                    receipt.runId(),
                    receipt.inboxId(),
                    "{\"rejectionReason\":\"" + jsonEscape(reason) + "\"}",
                    recoveryActor);
            if (!marked) {
                Optional<String> state = repository.findInboxReceiptState(receipt.tenantId(), receipt.inboxId());
                if (state.isPresent() && ("PROCESSED".equals(state.get()) || "REJECTED".equals(state.get()))) {
                    return InboxReconcileOutcome.ALREADY_CONSUMED;
                }
                throw new IllegalStateException(
                        "Failed to reject invalid command inbox receipt " + receipt.inboxId(), permanentFailure);
            }
            return InboxReconcileOutcome.REJECTED;
        }
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
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