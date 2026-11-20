package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.gateway.DeviceEventSink;
import com.labos.kernel.workflow.runtime.gateway.DeviceGateway;
import com.labos.kernel.workflow.runtime.gateway.RuntimeDeviceEventBridge;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionService;
import com.labos.kernel.workflow.runtime.worker.WorkerPool;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Single-JVM embedded Workflow Runtime Host composing {@link SingleInstanceHostLock},
 * {@link DeviceGateway} (in-process TCP gateway), {@link WorkerExecutionRecoveryService},
 * {@link WorkerPool}, {@link WorkflowRunLifecycleCoordinator}, {@link LocalTaskDispatchService},
 * {@link DeviceCommandExecutionService}, and {@link RuntimeDispatchLoop} under {@link RuntimeHost}.
 *
 * <p>Startup order (strictly enforced):
 * <ol>
 *   <li>Acquire local OS {@link SingleInstanceHostLock} (fails fast if another host holds it).</li>
 *   <li>Start {@link DeviceGateway#startIngressOnly()} as {@code eventIngress} (TCP connections +
 *       heartbeats + bounded inbound buffering active while {@link SchedulingGate} remains
 *       {@code CLOSED} and inbound event processing is strictly paused).</li>
 *   <li>Run {@link WorkerExecutionRecoveryService#recoverAtStartup} (reconcile interrupted
 *       Attempts and safely requeue only pre-{@code SEND_STARTED} Outbox rows with zero concurrent
 *       device-event state mutation).</li>
 *   <li>Start {@code afterRecovery} components: {@code lifecycleCoordinator}, {@link WorkerPool},
 *       {@link DeviceGateway#startEventProcessing()}, and {@link RuntimeDispatchLoop}.</li>
 *   <li>Open {@link SchedulingGate} -> {@link RuntimeHost.State#RUNNING}.</li>
 * </ol>
 *
 * <p>Shutdown runs in exact reverse order and releases {@link SingleInstanceHostLock} last.
 *
 * <p>Deployment trust model (V1): single LabOS process inside an isolated laboratory LAN
 * connecting to configured static device endpoints ({@code deviceId -> host:port}) without TLS;
 * command attribution is enforced via connection-bound {@code authenticatedDeviceId} and
 * PostgreSQL transactional verification of {@code inbox_receipt.source == command_ledger.device_id}.
 */
public final class EmbeddedWorkflowRuntimeHost implements AutoCloseable {

    public record Config(
            Path lockFile,
            String hostId,
            int workerThreads,
            int workerQueueCapacity,
            int maxConcurrentRuns,
            int mailboxThreads,
            int mailboxQueueCapacity,
            int recoveryBatchLimit,
            Duration dispatchPollInterval,
            int dispatchBatchSize,
            List<TcpDeviceConnection.Endpoint> deviceEndpoints,
            TcpDeviceConnection.Config gatewayConfig,
            UUID scopedTenantId) {
        public Config {
            Objects.requireNonNull(lockFile, "lockFile");
            if (hostId == null || hostId.isBlank()) throw new IllegalArgumentException("hostId is required");
            if (workerThreads < 1 || workerThreads > 256)
                throw new IllegalArgumentException("workerThreads must be 1..256");
            if (workerQueueCapacity < 1 || workerQueueCapacity > 4096)
                throw new IllegalArgumentException("workerQueueCapacity must be 1..4096");
            if (maxConcurrentRuns < 1 || maxConcurrentRuns > 1024)
                throw new IllegalArgumentException("maxConcurrentRuns must be 1..1024");
            if (mailboxThreads < 1 || mailboxThreads > 64)
                throw new IllegalArgumentException("mailboxThreads must be 1..64");
            if (mailboxQueueCapacity < 1 || mailboxQueueCapacity > 4096)
                throw new IllegalArgumentException("mailboxQueueCapacity must be 1..4096");
            if (recoveryBatchLimit < 1 || recoveryBatchLimit > 1000)
                throw new IllegalArgumentException("recoveryBatchLimit must be 1..1000");
            Objects.requireNonNull(dispatchPollInterval, "dispatchPollInterval");
            if (dispatchBatchSize < 1 || dispatchBatchSize > 500)
                throw new IllegalArgumentException("dispatchBatchSize must be 1..500");
            deviceEndpoints = List.copyOf(Objects.requireNonNull(deviceEndpoints, "deviceEndpoints"));
            Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        }
    }

    public record HealthSnapshot(
            String hostId,
            RuntimeHost.State hostState,
            boolean schedulingGateOpen,
            boolean hostLockValid,
            WorkerExecutionRecoveryService.StartupResult recoveryResult,
            int activeLocalWorkers,
            int activeMailboxes,
            Map<String, TcpDeviceConnection.HealthSnapshot> devices,
            RuntimeDispatchLoop.LoopStats dispatchStats) {}

    private final Config config;
    private final WorkflowRuntimeRepository repository;
    private final SchedulingGate schedulingGate;
    private final WorkerExecutionRegistry workerRegistry;
    private final ExecutorService mailboxExecutor;
    private final WorkflowRunLifecycleCoordinator lifecycleCoordinator;
    private final WorkerPool workerPool;
    private final WorkerExecutionService workerExecutionService;
    private final DeviceGateway deviceGateway;
    private final DeviceCommandExecutionService commandExecutionService;
    private final LocalTaskDispatchService taskDispatchService;
    private final RuntimeDispatchLoop dispatchLoop;
    private final WorkerExecutionRecoveryService recoveryService;
    private final RuntimeHost runtimeHost;

    private final AtomicBoolean startAttempted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile SingleInstanceHostLock hostLock;

    public EmbeddedWorkflowRuntimeHost(
            Config config,
            WorkflowRuntimeRepository repository,
            LocalTaskDispatchService.TaskDispatchSpecResolver specResolver,
            LocalTaskDispatchService.TaskHandlerResolver handlerResolver) {
        this(config, repository, specResolver, handlerResolver, n -> {});
    }

    public EmbeddedWorkflowRuntimeHost(
            Config config,
            WorkflowRuntimeRepository repository,
            LocalTaskDispatchService.TaskDispatchSpecResolver specResolver,
            LocalTaskDispatchService.TaskHandlerResolver handlerResolver,
            Consumer<RuntimeDeviceEventBridge.BridgeNotification> eventListener) {
        this(config, repository, specResolver, handlerResolver, eventListener, null);
    }

    /** Package-private constructor allowing tests to wrap startup recovery for deterministic race verification. */
    EmbeddedWorkflowRuntimeHost(
            Config config,
            WorkflowRuntimeRepository repository,
            LocalTaskDispatchService.TaskDispatchSpecResolver specResolver,
            LocalTaskDispatchService.TaskHandlerResolver handlerResolver,
            Consumer<RuntimeDeviceEventBridge.BridgeNotification> eventListener,
            java.util.function.UnaryOperator<RuntimeHost.StartupRecovery> recoveryDecorator) {
        this.config = Objects.requireNonNull(config, "config");
        this.repository = Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(specResolver, "specResolver");
        Objects.requireNonNull(handlerResolver, "handlerResolver");
        Objects.requireNonNull(eventListener, "eventListener");

        this.schedulingGate = new SchedulingGate();
        this.workerRegistry = new WorkerExecutionRegistry();

        AtomicInteger mailboxSeq = new AtomicInteger(0);
        this.mailboxExecutor = Executors.newFixedThreadPool(config.mailboxThreads(), r -> {
            Thread t = new Thread(r, "labos-runtime-mailbox-" + config.hostId() + "-" + mailboxSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        this.lifecycleCoordinator = new WorkflowRunLifecycleCoordinator(
                repository,
                new GraphCoordinator(repository),
                mailboxExecutor,
                config.mailboxQueueCapacity());

        this.workerPool = new WorkerPool(
                config.workerThreads(),
                config.workerQueueCapacity(),
                config.maxConcurrentRuns(),
                workerRegistry,
                schedulingGate);

        this.workerExecutionService = new WorkerExecutionService(
                repository, lifecycleCoordinator, workerPool, workerRegistry);

        AtomicReference<RuntimeDeviceEventBridge> bridgeRef = new AtomicReference<>();
        DeviceEventSink sink = ev -> {
            RuntimeDeviceEventBridge bridge = bridgeRef.get();
            if (bridge == null) {
                return DeviceEventSink.DeliveryResult.nackRetryable("BRIDGE_NOT_INITIALIZED");
            }
            return bridge.onDeviceEvent(ev);
        };

        this.deviceGateway = new DeviceGateway(config.deviceEndpoints(), config.gatewayConfig(), sink);
        this.commandExecutionService = new DeviceCommandExecutionService(
                repository, deviceGateway.asCommandAdapter());
        bridgeRef.set(RuntimeDeviceEventBridge.withCommandRouting(
                ev -> {
                    if (ev.runId() != null) {
                        UUID tenantId = repository.findTenantIdForCommand(ev.runId(), ev.commandId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Unknown command/run for device event: " + ev.commandId()));
                        return new RuntimeDeviceEventBridge.EventRouting(tenantId, ev.runId());
                    }
                    WorkflowRuntimeRepository.CommandRouting routing = repository.findCommandRoutingById(ev.commandId())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Unknown command for device event: " + ev.commandId()));
                    return new RuntimeDeviceEventBridge.EventRouting(routing.tenantId(), routing.runId());
                },
                commandExecutionService,
                eventListener));

        this.taskDispatchService = new LocalTaskDispatchService(
                repository, workerExecutionService, specResolver, handlerResolver);

        this.dispatchLoop = new RuntimeDispatchLoop(
                repository,
                taskDispatchService,
                commandExecutionService,
                schedulingGate,
                config.dispatchPollInterval(),
                config.dispatchBatchSize(),
                config.hostId(),
                config.scopedTenantId(),
                workerExecutionService::requestCancel,
                lifecycleCoordinator::wake);

        this.recoveryService = new WorkerExecutionRecoveryService(repository, workerRegistry);

        RuntimeHost.LifecycleComponent gatewayIngressComponent = new RuntimeHost.LifecycleComponent() {
            @Override
            public void start() {
                // Start TCP sockets + heartbeats + bounded buffering ONLY; event processing waits for recovery.
                deviceGateway.startIngressOnly();
            }

            @Override
            public void stop() {
                deviceGateway.close();
            }
        };

        RuntimeHost.LifecycleComponent gatewayEventProcessorComponent = new RuntimeHost.LifecycleComponent() {
            @Override
            public void start() {
                deviceGateway.startEventProcessing();
            }

            @Override
            public void stop() {
                deviceGateway.stopEventProcessing();
            }
        };

        RuntimeHost.LifecycleComponent lifecycleComponent = new RuntimeHost.LifecycleComponent() {
            @Override
            public void start() {
                // Mailbox coordinator is ready immediately; stop() closes it during reverse shutdown.
            }

            @Override
            public void stop() {
                lifecycleCoordinator.close();
            }
        };

        RuntimeHost.LifecycleComponent workerPoolComponent = new RuntimeHost.LifecycleComponent() {
            @Override
            public void start() {
                workerPool.start();
            }

            @Override
            public void stop() {
                workerPool.close();
            }
        };

        RuntimeHost.StartupRecovery effectiveRecovery = recoveryDecorator == null
                ? recoveryService::recoverAtStartup
                : Objects.requireNonNull(
                        recoveryDecorator.apply(recoveryService::recoverAtStartup),
                        "decorated recovery");

        this.runtimeHost = new RuntimeHost(
                effectiveRecovery,
                gatewayIngressComponent,
                List.of(lifecycleComponent, gatewayEventProcessorComponent, workerPoolComponent, dispatchLoop),
                schedulingGate,
                config.recoveryBatchLimit(),
                "host-startup:" + config.hostId());
    }

    public synchronized void start() throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("EmbeddedWorkflowRuntimeHost is already closed");
        }
        if (!startAttempted.compareAndSet(false, true)) {
            throw new IllegalStateException("EmbeddedWorkflowRuntimeHost can only start once");
        }
        SingleInstanceHostLock acquired = SingleInstanceHostLock.acquire(config.lockFile());
        this.hostLock = acquired;
        try {
            runtimeHost.start();
        } catch (RuntimeException | Error failure) {
            shutdownMailboxExecutor();
            releaseLockQuietly(acquired, failure);
            this.hostLock = null;
            throw failure;
        }
    }

    public CompletableFuture<Void> startRun(UUID tenantId, UUID runId) {
        if (runtimeHost.state() != RuntimeHost.State.RUNNING) {
            throw new IllegalStateException("RuntimeHost is not RUNNING: " + runtimeHost.state());
        }
        return lifecycleCoordinator.startRun(tenantId, runId);
    }

    public UUID requestPause(UUID tenantId, UUID runId) {
        return repository.requestDrain(tenantId, runId, "PAUSE");
    }

    public CompletableFuture<Void> resumeRun(UUID tenantId, UUID runId) {
        if (runtimeHost.state() != RuntimeHost.State.RUNNING) {
            throw new IllegalStateException("RuntimeHost is not RUNNING: " + runtimeHost.state());
        }
        repository.resumePausedRun(tenantId, runId);
        return lifecycleCoordinator.wake(tenantId, runId);
    }

    public UUID requestStop(UUID tenantId, UUID runId) {
        return repository.requestDrain(tenantId, runId, "STOP");
    }

    public java.util.Optional<WorkflowRuntimeRepository.RunOverview> getRunOverview(UUID tenantId, UUID runId) {
        return repository.findRunOverview(tenantId, runId);
    }

    public boolean requestWorkerCancel(UUID attemptId) {
        return workerExecutionService.requestCancel(attemptId);
    }

    public List<WorkflowRuntimeRepository.ManualRequiredRunSummary> listManualRequiredRuns(
            UUID tenantId, int limit) {
        return repository.findManualRequiredRuns(tenantId, limit);
    }

    public List<WorkflowRuntimeRepository.UnknownCommandSummary> listUnknownCommands(
            UUID tenantId, int limit) {
        return repository.findUnknownCommands(tenantId, limit);
    }

    public boolean reconcileUnknownCommand(
            UUID tenantId, UUID commandId, String resolvedState,
            String deviceSequence, String evidenceJson, String actor) {
        return commandExecutionService.reconcileUnknownCommand(
                tenantId, commandId, resolvedState, deviceSequence, evidenceJson, actor);
    }

    public HealthSnapshot healthSnapshot() {
        SingleInstanceHostLock lock = this.hostLock;
        return new HealthSnapshot(
                config.hostId(),
                runtimeHost.state(),
                schedulingGate.isOpen(),
                lock != null && lock.isValid(),
                runtimeHost.recoveryResult(),
                workerRegistry.activeCount(),
                lifecycleCoordinator.activeMailboxCount(),
                deviceGateway.allHealth(),
                dispatchLoop.stats());
    }

    public RuntimeHost runtimeHost() { return runtimeHost; }

    public DeviceGateway deviceGateway() { return deviceGateway; }

    public DeviceCommandExecutionService commandExecutionService() { return commandExecutionService; }

    public WorkerExecutionService workerExecutionService() { return workerExecutionService; }

    public WorkflowRunLifecycleCoordinator lifecycleCoordinator() { return lifecycleCoordinator; }

    public RuntimeDispatchLoop dispatchLoop() { return dispatchLoop; }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            runIsolated(runtimeHost::close, failure);
            // Ensure executors/pools are cleanly shut down even if start() failed before RuntimeHost started them.
            runIsolated(dispatchLoop::stop, failure);
            runIsolated(deviceGateway::stopEventProcessing, failure);
            runIsolated(workerPool::close, failure);
            runIsolated(lifecycleCoordinator::close, failure);
            runIsolated(deviceGateway::close, failure);
            runIsolated(this::shutdownMailboxExecutor, failure);
        } finally {
            SingleInstanceHostLock lock = this.hostLock;
            this.hostLock = null;
            if (lock != null) {
                try {
                    lock.close();
                } catch (IOException lockCloseError) {
                    recordCloseFailure(failure,
                            new RuntimeException("Failed to release SingleInstanceHostLock", lockCloseError));
                }
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static void runIsolated(Runnable action, AtomicReference<RuntimeException> failure) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            recordCloseFailure(failure, ex);
        }
    }

    private static void recordCloseFailure(AtomicReference<RuntimeException> failure, RuntimeException ex) {
        if (!failure.compareAndSet(null, ex)) {
            failure.get().addSuppressed(ex);
        }
    }

    private void shutdownMailboxExecutor() {
        mailboxExecutor.shutdown();
        try {
            if (!mailboxExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                mailboxExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            mailboxExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void releaseLockQuietly(SingleInstanceHostLock lock, Throwable original) {
        try {
            lock.close();
        } catch (IOException closeErr) {
            original.addSuppressed(closeErr);
        }
    }
}
