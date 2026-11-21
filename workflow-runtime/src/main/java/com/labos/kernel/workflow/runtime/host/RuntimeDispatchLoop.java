package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Single-host periodic dispatch loop implementing {@link RuntimeHost.LifecycleComponent}.
 *
 * <p>Registered in {@link RuntimeHost}'s {@code afterRecovery} list and additionally gated by
 * {@link SchedulingGate#isOpen()} on every cycle, so it NEVER dispatches tasks or commands
 * before startup recovery finishes or after shutdown closes the gate.
 */
public final class RuntimeDispatchLoop implements RuntimeHost.LifecycleComponent {

    public record LoopStats(
            boolean running,
            long cyclesCompleted,
            long cycleErrors,
            long tasksDispatched,
            long workersSubmitted,
            long commandsDelivered,
            Instant lastCycleAt,
            String lastError) {}

    private final WorkflowRuntimeRepository repository;
    private final LocalTaskDispatchService taskDispatchService;
    private final DeviceCommandExecutionService commandExecutionService;
    private final SchedulingGate schedulingGate;
    private final Duration pollInterval;
    private final int batchSize;
    private final String dispatcherId;
    private final UUID scopedTenantId; // null = serve all tenants; non-null = SQL-level tenant isolation!
    private final Consumer<UUID> workerCancelRequester;
    private final BiConsumer<UUID, UUID> graphWakeup;
    private final LongSupplier nanoTimeSupplier;

    private static final long GRAPH_WAKEUP_BASE_BACKOFF_MS = 200L;
    private static final long GRAPH_WAKEUP_MAX_BACKOFF_MS = 5_000L;

    private record WakeupThrottle(int consecutiveCount, long nextAllowedNanos) {}

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicLong cyclesCompleted = new AtomicLong(0);
    private final AtomicLong cycleErrors = new AtomicLong(0);
    private final AtomicLong tasksDispatched = new AtomicLong(0);
    private final AtomicLong workersSubmitted = new AtomicLong(0);
    private final AtomicLong commandsDelivered = new AtomicLong(0);
    private final Map<UUID, WakeupThrottle> graphWakeupThrottle = new ConcurrentHashMap<>();
    // Owned by the single dispatch loop thread; thread-safe backing matches graphWakeupThrottle.
    private final Set<UUID> graphAdvanceSweepSeen = ConcurrentHashMap.newKeySet();
    private volatile WorkflowRuntimeRepository.GraphAdvanceCursor graphAdvanceCursor;
    private volatile Instant lastCycleAt;
    private volatile String lastError;

    private final ScheduledExecutorService scheduler;

    public RuntimeDispatchLoop(
            WorkflowRuntimeRepository repository,
            LocalTaskDispatchService taskDispatchService,
            DeviceCommandExecutionService commandExecutionService,
            SchedulingGate schedulingGate,
            Duration pollInterval,
            int batchSize,
            String dispatcherId,
            UUID scopedTenantId) {
        this(repository, taskDispatchService, commandExecutionService, schedulingGate,
                pollInterval, batchSize, dispatcherId, scopedTenantId, attemptId -> {}, (t, r) -> {});
    }

    public RuntimeDispatchLoop(
            WorkflowRuntimeRepository repository,
            LocalTaskDispatchService taskDispatchService,
            DeviceCommandExecutionService commandExecutionService,
            SchedulingGate schedulingGate,
            Duration pollInterval,
            int batchSize,
            String dispatcherId,
            UUID scopedTenantId,
            Consumer<UUID> workerCancelRequester) {
        this(repository, taskDispatchService, commandExecutionService, schedulingGate,
                pollInterval, batchSize, dispatcherId, scopedTenantId, workerCancelRequester, (t, r) -> {});
    }

    public RuntimeDispatchLoop(
            WorkflowRuntimeRepository repository,
            LocalTaskDispatchService taskDispatchService,
            DeviceCommandExecutionService commandExecutionService,
            SchedulingGate schedulingGate,
            Duration pollInterval,
            int batchSize,
            String dispatcherId,
            UUID scopedTenantId,
            Consumer<UUID> workerCancelRequester,
            BiConsumer<UUID, UUID> graphWakeup) {
        this(repository, taskDispatchService, commandExecutionService, schedulingGate,
                pollInterval, batchSize, dispatcherId, scopedTenantId,
                workerCancelRequester, graphWakeup, System::nanoTime);
    }

    RuntimeDispatchLoop(
            WorkflowRuntimeRepository repository,
            LocalTaskDispatchService taskDispatchService,
            DeviceCommandExecutionService commandExecutionService,
            SchedulingGate schedulingGate,
            Duration pollInterval,
            int batchSize,
            String dispatcherId,
            UUID scopedTenantId,
            Consumer<UUID> workerCancelRequester,
            BiConsumer<UUID, UUID> graphWakeup,
            LongSupplier nanoTimeSupplier) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.taskDispatchService = Objects.requireNonNull(taskDispatchService, "taskDispatchService");
        this.commandExecutionService = Objects.requireNonNull(commandExecutionService, "commandExecutionService");
        this.schedulingGate = Objects.requireNonNull(schedulingGate, "schedulingGate");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isZero() || pollInterval.isNegative())
            throw new IllegalArgumentException("pollInterval must be positive");
        if (batchSize < 1 || batchSize > 500)
            throw new IllegalArgumentException("batchSize must be 1..500");
        if (dispatcherId == null || dispatcherId.isBlank())
            throw new IllegalArgumentException("dispatcherId is required");
        this.batchSize = batchSize;
        this.dispatcherId = dispatcherId;
        this.scopedTenantId = scopedTenantId;
        this.workerCancelRequester = Objects.requireNonNull(workerCancelRequester, "workerCancelRequester");
        this.graphWakeup = Objects.requireNonNull(graphWakeup, "graphWakeup");
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "labos-runtime-dispatch-" + dispatcherId);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start() {
        if (stopped.get()) {
            throw new IllegalStateException("RuntimeDispatchLoop has already been stopped");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long delayMs = Math.max(10L, pollInterval.toMillis());
        scheduler.scheduleWithFixedDelay(this::runCycleSafe, 0L, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Execute a single poll cycle (also usable deterministically in tests). */
    public void runOneCycle() {
        if (stopped.get() || !schedulingGate.isOpen()) {
            return;
        }
        // 0. Self-heal any RUNNING/OPEN runs with completed task activations awaiting graph routing
        //    (e.g. after PAUSE -> RESUME, host crash/restart, or transient mailbox eviction race).
        //    Bounded keyset sweep rotates past stuck runs without raising batchSize, while bounded
        //    exponential backoff per run prevents an unadvanceable run from busy-looping.
        boolean startedFromBeginning = (graphAdvanceCursor == null);
        List<WorkflowRuntimeRepository.AwaitingGraphRunRef> awaitingGraph = queryAwaitingGraphPage(graphAdvanceCursor);
        if (awaitingGraph.isEmpty() && graphAdvanceCursor != null) {
            graphWakeupThrottle.keySet().retainAll(graphAdvanceSweepSeen);
            graphAdvanceSweepSeen.clear();
            graphAdvanceCursor = null;
            startedFromBeginning = true;
            awaitingGraph = queryAwaitingGraphPage(null);
        }
        if (awaitingGraph.isEmpty()) {
            graphWakeupThrottle.clear();
            graphAdvanceSweepSeen.clear();
            graphAdvanceCursor = null;
        } else {
            if (startedFromBeginning) {
                graphAdvanceSweepSeen.clear();
            }
            long nowNanos = nanoTimeSupplier.getAsLong();
            for (WorkflowRuntimeRepository.AwaitingGraphRunRef ref : awaitingGraph) {
                if (stopped.get() || !schedulingGate.isOpen()) return;
                graphAdvanceSweepSeen.add(ref.runId());
                WakeupThrottle prev = graphWakeupThrottle.get(ref.runId());
                if (prev != null && nowNanos < prev.nextAllowedNanos()) {
                    continue;
                }
                int count = prev == null ? 1 : prev.consecutiveCount() + 1;
                long backoffMs = Math.min(GRAPH_WAKEUP_MAX_BACKOFF_MS,
                        GRAPH_WAKEUP_BASE_BACKOFF_MS << Math.min(count - 1, 5));
                graphWakeupThrottle.put(ref.runId(),
                        new WakeupThrottle(count, nowNanos + TimeUnit.MILLISECONDS.toNanos(backoffMs)));
                if (count == 5 || (count > 5 && count % 20 == 0)) {
                    System.err.println("[RuntimeDispatchLoop] Run " + ref.runId()
                            + " remained awaiting graph advance after " + count
                            + " consecutive self-heal wakeups; backing off " + backoffMs + "ms");
                }
                try {
                    graphWakeup.accept(ref.tenantId(), ref.runId());
                } catch (RuntimeException ex) {
                    recordError(ex);
                }
            }
            if (awaitingGraph.size() == batchSize) {
                graphAdvanceCursor = awaitingGraph.get(awaitingGraph.size() - 1).cursor();
            } else {
                graphAdvanceCursor = null;
                graphWakeupThrottle.keySet().retainAll(graphAdvanceSweepSeen);
                graphAdvanceSweepSeen.clear();
            }
        }

        // 1. Replay Wait Inbox signals durably persisted during PAUSE or prior interrupted runs (G5 §6.3/§8.2).
        List<WorkflowRuntimeRepository.PendingWaitInboxReceipt> pendingWaitInbox = scopedTenantId != null
                ? repository.findPendingWaitInboxReceiptsForTenant(scopedTenantId, batchSize)
                : repository.findPendingWaitInboxReceipts(batchSize);
        for (WorkflowRuntimeRepository.PendingWaitInboxReceipt receipt : pendingWaitInbox) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                WorkflowRuntimeRepository.GraphWaitResult res = repository.replayPendingWaitInbox(
                        receipt.tenantId(), receipt.runId(), receipt.inboxId());
                if (WaitOutcomes.COMPLETED.equals(res.outcome())) {
                    graphWakeup.accept(receipt.tenantId(), receipt.runId());
                }
            } catch (RuntimeException ex) {
                recordError(ex);
            }
        }

        // 2. Scan and expire due pure-graph Waits with DB-clock & Run-locked Inbox arbitration (G5 §8.1/§8.2).
        List<WorkflowRuntimeRepository.DueWaitRef> dueWaits = scopedTenantId != null
                ? repository.findDueWaitsForTenant(scopedTenantId, batchSize)
                : repository.findDueWaits(batchSize);
        for (WorkflowRuntimeRepository.DueWaitRef due : dueWaits) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                WorkflowRuntimeRepository.GraphWaitResult res = repository.completeGraphWait(
                        due.tenantId(), due.runId(), due.waitId(),
                        WaitOutcomes.OUTCOME_EXPIRED, due.version(), null);
                if (WaitOutcomes.COMPLETED.equals(res.outcome())) {
                    graphWakeup.accept(due.tenantId(), due.runId());
                }
            } catch (RuntimeException ex) {
                recordError(ex);
            }
        }

        // 3. Reserve & dispatch READY tokens for active RUNNING/OPEN runs.
        List<WorkflowRuntimeRepository.RunnableRunRef> runnableRuns = scopedTenantId != null
                ? repository.findRunnableWorkTargetsForTenant(scopedTenantId, batchSize)
                : repository.findRunnableWorkTargets(batchSize);
        for (WorkflowRuntimeRepository.RunnableRunRef ref : runnableRuns) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                var res = taskDispatchService.dispatchReadyTasks(ref.tenantId(), ref.runId(), batchSize);
                tasksDispatched.addAndGet(res.dispatchedAttemptIds().size());
                if (res.hasReadyControlTokens()
                        || (res.dispatchedAttemptIds().isEmpty() && res.capacityDeferred() == 0 && !res.gateClosed())) {
                    // Remaining READY token(s) include control-flow/WAIT nodes: wake coordinator even when tasks dispatched/deferred.
                    graphWakeup.accept(ref.tenantId(), ref.runId());
                }
            } catch (RuntimeException ex) {
                recordError(ex);
            }
        }

        // 4. Deliver pending WORKER_DISPATCH outbox rows into local WorkerPool.
        List<UUID> workerTenants = scopedTenantId != null
                ? (repository.hasPendingOutboxForTenant(scopedTenantId, "WORKER_DISPATCH")
                        ? List.of(scopedTenantId) : List.of())
                : repository.findTenantsWithPendingOutbox("WORKER_DISPATCH", batchSize);
        for (UUID tenantId : workerTenants) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                var delivered = taskDispatchService.deliverPendingWorkerDispatches(
                        tenantId, batchSize, dispatcherId);
                workersSubmitted.addAndGet(delivered.submitted().size());
            } catch (RuntimeException ex) {
                recordError(ex);
            }
        }

        // 5. Deliver pending DEVICE_COMMAND outbox rows into DeviceGateway.
        List<UUID> commandTenants = scopedTenantId != null
                ? (repository.hasPendingOutboxForTenant(scopedTenantId, "DEVICE_COMMAND")
                        ? List.of(scopedTenantId) : List.of())
                : repository.findTenantsWithPendingOutbox("DEVICE_COMMAND", batchSize);
        for (UUID tenantId : commandTenants) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                var delivered = commandExecutionService.deliverPendingCommands(
                        tenantId, batchSize, dispatcherId);
                commandsDelivered.addAndGet(delivered.outcomes().size());
            } catch (RuntimeException ex) {
                recordError(ex);
            }
        }

        // 6. Progress open PAUSE / STOP / FAILURE drains toward PAUSED / STOPPED / FAILED safe points.
        List<WorkflowRuntimeRepository.OpenDrainRef> openDrains = scopedTenantId != null
                ? repository.findOpenDrainsForTenant(scopedTenantId, batchSize)
                : repository.findOpenDrains(batchSize);
        for (WorkflowRuntimeRepository.OpenDrainRef drain : openDrains) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                // Validate drain_kind before any outbox delivery, scan advance, or continuation cancel.
                String targetStatus = resolveDrainTargetStatus(drain.drainKind());
                repository.deliverDrainRequestOutbox(drain.tenantId(), drain.runId());
                if ("STOP".equals(drain.drainKind()) || "FAILURE".equals(drain.drainKind())) {
                    for (UUID activeAttempt : repository.findActiveAttemptIdsForRun(drain.tenantId(), drain.runId())) {
                        workerCancelRequester.accept(activeAttempt);
                    }
                    repository.invalidateStopContinuations(drain.tenantId(), drain.runId());
                }
                if ("REQUESTED".equals(drain.drainState())) {
                    try {
                        repository.advanceDrainScan(
                                drain.tenantId(), drain.runId(), drain.drainId(),
                                drain.version(), "{\"phase\":\"local-host-drain\"}", List.of());
                    } catch (WorkflowRuntimeRepository.StaleRuntimeWriteException ignored) {
                        // Drain record was updated concurrently (e.g. STOP/FAILURE superseded PAUSE); next cycle retries.
                    }
                }
                repository.tryFinalizeDrain(drain.tenantId(), drain.runId(), drain.drainId(), targetStatus);
            } catch (RuntimeException ex) {
                recordError(ex);
            }
        }

        lastCycleAt = Instant.now();
        cyclesCompleted.incrementAndGet();
    }

    public LoopStats stats() {
        return new LoopStats(
                started.get() && !stopped.get(),
                cyclesCompleted.get(),
                cycleErrors.get(),
                tasksDispatched.get(),
                workersSubmitted.get(),
                commandsDelivered.get(),
                lastCycleAt,
                lastError);
    }

    private List<WorkflowRuntimeRepository.AwaitingGraphRunRef> queryAwaitingGraphPage(
            WorkflowRuntimeRepository.GraphAdvanceCursor after) {
        return scopedTenantId != null
                ? repository.findRunsAwaitingGraphAdvanceForTenantAfter(scopedTenantId, after, batchSize)
                : repository.findRunsAwaitingGraphAdvanceAfter(after, batchSize);
    }

    private void runCycleSafe() {
        try {
            runOneCycle();
        } catch (RuntimeException ex) {
            recordError(ex);
        }
    }

    static String resolveDrainTargetStatus(String drainKind) {
        if (drainKind == null) {
            throw new IllegalStateException("Unsupported open drain kind: null");
        }
        return switch (drainKind) {
            case "PAUSE" -> "PAUSED";
            case "STOP" -> "STOPPED";
            case "FAILURE" -> "FAILED";
            default -> throw new IllegalStateException("Unsupported open drain kind: " + drainKind);
        };
    }

    private void recordError(RuntimeException ex) {
        cycleErrors.incrementAndGet();
        lastError = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage());
    }
}
