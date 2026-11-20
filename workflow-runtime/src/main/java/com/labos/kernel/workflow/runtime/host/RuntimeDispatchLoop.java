package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicLong cyclesCompleted = new AtomicLong(0);
    private final AtomicLong cycleErrors = new AtomicLong(0);
    private final AtomicLong tasksDispatched = new AtomicLong(0);
    private final AtomicLong workersSubmitted = new AtomicLong(0);
    private final AtomicLong commandsDelivered = new AtomicLong(0);
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
                pollInterval, batchSize, dispatcherId, scopedTenantId, attemptId -> {});
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
        // 1. Reserve & dispatch READY tokens for active RUNNING/OPEN runs.
        List<WorkflowRuntimeRepository.RunnableRunRef> runnableRuns = scopedTenantId != null
                ? repository.findRunnableWorkTargetsForTenant(scopedTenantId, batchSize)
                : repository.findRunnableWorkTargets(batchSize);
        for (WorkflowRuntimeRepository.RunnableRunRef ref : runnableRuns) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                var res = taskDispatchService.dispatchReadyTasks(ref.tenantId(), ref.runId(), batchSize);
                tasksDispatched.addAndGet(res.dispatchedAttemptIds().size());
            } catch (RuntimeException ex) {
                recordError(ex);
            }
        }

        // 2. Deliver pending WORKER_DISPATCH outbox rows into local WorkerPool.
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

        // 3. Deliver pending DEVICE_COMMAND outbox rows into DeviceGateway.
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

        // 4. Progress open PAUSE / STOP drains toward PAUSED / STOPPED safe points.
        List<WorkflowRuntimeRepository.OpenDrainRef> openDrains = scopedTenantId != null
                ? repository.findOpenDrainsForTenant(scopedTenantId, batchSize)
                : repository.findOpenDrains(batchSize);
        for (WorkflowRuntimeRepository.OpenDrainRef drain : openDrains) {
            if (stopped.get() || !schedulingGate.isOpen()) return;
            try {
                repository.deliverDrainRequestOutbox(drain.tenantId(), drain.runId());
                if ("STOP".equals(drain.drainKind())) {
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
                        // Drain record was updated concurrently (e.g. STOP superseded PAUSE); next cycle retries.
                    }
                }
                String targetStatus = "PAUSE".equals(drain.drainKind()) ? "PAUSED" : "STOPPED";
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

    private void runCycleSafe() {
        try {
            runOneCycle();
        } catch (RuntimeException ex) {
            recordError(ex);
        }
    }

    private void recordError(RuntimeException ex) {
        cycleErrors.incrementAndGet();
        lastError = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage());
    }
}
