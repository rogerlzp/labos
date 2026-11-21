package com.labos.kernel.workflow.runtime.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionService;
import com.labos.kernel.workflow.runtime.worker.WorkerTaskHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-host application service bridging durable {@code READY} task Tokens to
 * {@link WorkerExecutionService} via {@code reserveCapacity -> dispatchAttempt -> WORKER_DISPATCH Outbox}.
 *
 * <p>Deliberately single-instance (no distributed broker or lease coordinator):
 * <ul>
 *   <li>Capacity exhaustion leaves Tokens {@code READY} without false failure.</li>
 *   <li>WorkerPool backpressure or a closed {@code SchedulingGate} safely requeues the
 *       {@code WORKER_DISPATCH} outbox row back to {@code PENDING} while Attempt remains {@code DISPATCHED}.</li>
 *   <li>Every {@link WorkerTaskHandler.TaskRequest} is hydrated from persisted DB rows
 *       ({@code node_attempt} + {@code node_activation}) rather than trusting raw outbox JSON alone.</li>
 * </ul>
 */
public final class LocalTaskDispatchService {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record TaskDispatchSpec(
            String capacityKey,
            int capacityLimit,
            String sideEffectClass,
            String workerQueue,
            Duration reservationTtl,
            String payloadJson) {
        public TaskDispatchSpec {
            if (capacityKey == null || capacityKey.isBlank())
                throw new IllegalArgumentException("capacityKey is required");
            if (capacityLimit < 1)
                throw new IllegalArgumentException("capacityLimit must be >= 1");
            if (sideEffectClass == null || sideEffectClass.isBlank())
                throw new IllegalArgumentException("sideEffectClass is required");
            if (workerQueue == null || workerQueue.isBlank())
                throw new IllegalArgumentException("workerQueue is required");
            Objects.requireNonNull(reservationTtl, "reservationTtl");
            if (reservationTtl.isZero() || reservationTtl.isNegative())
                throw new IllegalArgumentException("reservationTtl must be positive");
            payloadJson = (payloadJson == null || payloadJson.isBlank()) ? "{}" : payloadJson;
        }
    }

    @FunctionalInterface
    public interface TaskDispatchSpecResolver {
        TaskDispatchSpec resolve(GraphPlan.Node node);
    }

    @FunctionalInterface
    public interface TaskHandlerResolver {
        Optional<WorkerTaskHandler> resolve(GraphPlan.Node node);
    }

    public record ReserveDispatchSummary(
            List<UUID> dispatchedAttemptIds,
            int capacityDeferred,
            boolean gateClosed) {
        public ReserveDispatchSummary {
            dispatchedAttemptIds = List.copyOf(Objects.requireNonNull(dispatchedAttemptIds, "dispatchedAttemptIds"));
        }
    }

    public record OutboxDeliverySummary(
            Map<UUID, CompletableFuture<WorkerExecutionService.ExecutionOutcome>> submitted,
            int requeuedOnBackpressure,
            int deadLetteredStale,
            int failedMissingHandler) {
        public OutboxDeliverySummary {
            submitted = Map.copyOf(Objects.requireNonNull(submitted, "submitted"));
        }
    }

    private final WorkflowRuntimeRepository repository;
    private final WorkerExecutionService workerExecutionService;
    private final TaskDispatchSpecResolver specResolver;
    private final TaskHandlerResolver handlerResolver;
    private final AtomicLong workerSeq = new AtomicLong();

    public LocalTaskDispatchService(
            WorkflowRuntimeRepository repository,
            WorkerExecutionService workerExecutionService,
            TaskDispatchSpecResolver specResolver,
            TaskHandlerResolver handlerResolver) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workerExecutionService = Objects.requireNonNull(workerExecutionService, "workerExecutionService");
        this.specResolver = Objects.requireNonNull(specResolver, "specResolver");
        this.handlerResolver = Objects.requireNonNull(handlerResolver, "handlerResolver");
    }

    /**
     * Reserve capacity and persist {@code DISPATCHED} + {@code WORKER_DISPATCH} Outbox rows
     * for up to {@code maxTasks} {@code READY} task Tokens of one Run.
     */
    public ReserveDispatchSummary dispatchReadyTasks(UUID tenantId, UUID runId, int maxTasks) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        if (maxTasks < 1) throw new IllegalArgumentException("maxTasks must be >= 1");

        WorkflowRuntimeRepository.RunDefinitionSnapshot snapshot =
                repository.loadRunDefinitionSnapshot(tenantId, runId);
        if (!"RUNNING".equals(snapshot.runStatus()) || !"OPEN".equals(snapshot.controlGate())) {
            return new ReserveDispatchSummary(List.of(), 0, true);
        }
        GraphPlan plan;
        try {
            plan = GraphPlanCodec.fromJson(snapshot.graphPlanJson(), snapshot.diagnosticContext());
        } catch (IllegalArgumentException invalidPlan) {
            // Deterministic corrupt GraphPlan fault: terminalize the Run so RuntimeDispatchLoop
            // cannot replay this poison pill on every poll cycle and starve LIMIT batches.
            try {
                repository.failGraphRun(tenantId, runId, null,
                        invalidPlan.getClass().getSimpleName(), invalidPlan.getMessage());
            } catch (RuntimeException ignored) {
                // Run already transitioned concurrently.
            }
            throw invalidPlan;
        }
        Map<String, GraphPlan.Node> taskNodes = new LinkedHashMap<>();
        for (GraphPlan.Node node : plan.nodes()) {
            if (node.kind() == GraphPlan.Kind.TASK || node.kind() == GraphPlan.Kind.BUILTIN) {
                taskNodes.put(node.key(), node);
            }
        }
        if (taskNodes.isEmpty()) {
            return new ReserveDispatchSummary(List.of(), 0, false);
        }

        List<WorkflowRuntimeRepository.GraphToken> readyTokens = repository.findReadyGraphTokensForNodes(
                tenantId, runId, List.copyOf(taskNodes.keySet()), maxTasks);
        List<UUID> dispatched = new ArrayList<>();
        int deferred = 0;
        for (WorkflowRuntimeRepository.GraphToken token : readyTokens) {
            GraphPlan.Node node = taskNodes.get(token.nodeKey());
            if (node == null) continue;
            TaskDispatchSpec spec = Objects.requireNonNull(specResolver.resolve(node), "TaskDispatchSpec");
            WorkflowRuntimeRepository.CapacityReservation reservation;
            try {
                reservation = repository.reserveCapacity(
                        tenantId, runId,
                        spec.capacityKey(), spec.capacityLimit(),
                        token.nodeKey(), spec.sideEffectClass(),
                        Instant.now().plus(spec.reservationTtl()));
            } catch (WorkflowRuntimeRepository.CapacityExhaustedException exhausted) {
                deferred++;
                continue;
            } catch (WorkflowRuntimeRepository.InvalidTransitionException gateClosed) {
                return new ReserveDispatchSummary(dispatched, deferred, true);
            }
            try {
                repository.dispatchAttempt(
                        tenantId, runId, reservation.attemptId(),
                        spec.workerQueue(), spec.payloadJson());
                dispatched.add(reservation.attemptId());
            } catch (WorkflowRuntimeRepository.InvalidTransitionException gateClosed) {
                // Run closed its gate between reserveCapacity (CREATED) and dispatchAttempt:
                // converge CREATED pre-start Attempt cleanly so Drain never stalls on an orphan.
                try {
                    repository.abortPreStartAttemptOnGateClosed(
                            tenantId, runId, reservation.attemptId(), "gate-closed-before-dispatch");
                } catch (RuntimeException ignored) {
                    // Run already terminal; leave untouched.
                }
                return new ReserveDispatchSummary(dispatched, deferred, true);
            }
        }
        return new ReserveDispatchSummary(dispatched, deferred, false);
    }

    /**
     * Claim up to {@code batchSize} {@code PENDING} {@code WORKER_DISPATCH} Outbox messages for
     * {@code tenantId} and submit each valid {@code DISPATCHED} Attempt to {@link WorkerExecutionService}.
     */
    public OutboxDeliverySummary deliverPendingWorkerDispatches(
            UUID tenantId, int batchSize, String workerIdPrefix) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (workerIdPrefix == null || workerIdPrefix.isBlank())
            throw new IllegalArgumentException("workerIdPrefix is required");

        List<WorkflowRuntimeRepository.OutboxMessage> claimed =
                repository.beginOutboxSendForKind(tenantId, "WORKER_DISPATCH", batchSize);
        Map<UUID, CompletableFuture<WorkerExecutionService.ExecutionOutcome>> submitted = new LinkedHashMap<>();
        int requeued = 0;
        int deadLettered = 0;
        int missingHandler = 0;

        for (WorkflowRuntimeRepository.OutboxMessage msg : claimed) {
            UUID attemptId = extractUuidField(msg.payloadJson(), "attemptId");
            if (attemptId == null) {
                repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                deadLettered++;
                continue;
            }
            Optional<WorkflowRuntimeRepository.DispatchedAttemptRecord> loaded =
                    repository.loadDispatchedAttempt(tenantId, attemptId);
            if (loaded.isEmpty() || !"DISPATCHED".equals(loaded.get().attemptState())) {
                repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                deadLettered++;
                continue;
            }
            WorkflowRuntimeRepository.DispatchedAttemptRecord attempt = loaded.get();
            GraphPlan.Node node = findNode(tenantId, attempt.runId(), attempt.nodeKey());
            Optional<WorkerTaskHandler> handlerOpt = node == null ? Optional.empty() : handlerResolver.resolve(node);
            if (handlerOpt.isEmpty()) {
                repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                try {
                    repository.failAttempt(tenantId, attempt.runId(), attempt.attemptId(),
                            "{\"errorCode\":\"MISSING_TASK_HANDLER\",\"nodeKey\":\""
                                    + attempt.nodeKey().replace("\"", "\\\"") + "\"}",
                            false);
                } catch (RuntimeException ignored) {
                    // Run/Attempt transitioned concurrently.
                }
                missingHandler++;
                continue;
            }

            String workerId = workerIdPrefix + "-" + workerSeq.incrementAndGet();
            WorkerTaskHandler.TaskRequest request = new WorkerTaskHandler.TaskRequest(
                    tenantId, attempt.runId(), attempt.attemptId(), attempt.dispatchId(),
                    workerId, attempt.nodeKey(), attempt.sideEffectClass(), msg.payloadJson());

            // Mark WORKER_DISPATCH outbox DELIVERED before handing off to WorkerPool so a fast task
            // reaching END never races with maybeCompleteGraphRun's pending/sending outbox guard.
            // On synchronous pool backpressure rejection, immediately revert DELIVERED -> PENDING
            // (guarded by attempt_state == 'DISPATCHED'); on crash between mark and submit,
            // WorkerExecutionRecoveryService scans DISPATCHED Attempts directly.
            repository.markOutboxDelivered(tenantId, msg.outboxId());
            try {
                CompletableFuture<WorkerExecutionService.ExecutionOutcome> future =
                        workerExecutionService.submit(request, handlerOpt.get());
                submitted.put(attempt.attemptId(), future);
            } catch (RejectedExecutionException | IllegalStateException backpressure) {
                repository.requeueWorkerDispatchOnBackpressure(tenantId, msg.outboxId(), attempt.attemptId());
                requeued++;
            }
        }
        return new OutboxDeliverySummary(submitted, requeued, deadLettered, missingHandler);
    }

    private GraphPlan.Node findNode(UUID tenantId, UUID runId, String nodeKey) {
        WorkflowRuntimeRepository.RunDefinitionSnapshot snapshot =
                repository.loadRunDefinitionSnapshot(tenantId, runId);
        GraphPlan plan = GraphPlanCodec.fromJson(
                snapshot.graphPlanJson(), snapshot.diagnosticContext());
        for (GraphPlan.Node node : plan.nodes()) {
            if (node.key().equals(nodeKey)) return node;
        }
        return null;
    }

    private static UUID extractUuidField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode tree = JSON.readTree(json);
            JsonNode node = tree.get(field);
            if (node == null || !node.isTextual() || node.asText().isBlank()) return null;
            return UUID.fromString(node.asText());
        } catch (Exception invalid) {
            return null;
        }
    }
}
