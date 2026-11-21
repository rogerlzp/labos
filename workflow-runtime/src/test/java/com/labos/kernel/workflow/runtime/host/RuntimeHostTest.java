package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeHostTest {
    private static WorkerExecutionRecoveryService.StartupResult emptyRecoveryResult() {
        return new WorkerExecutionRecoveryService.StartupResult(
                new com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository.OutboxRestartResult(0, 0),
                0, 0, 0, 0, List.of());
    }

    private static RuntimeHost.LifecycleComponent component(
            String name, List<String> trace, Runnable onStart, Runnable onStop) {
        return new RuntimeHost.LifecycleComponent() {
            @Override public void start() { trace.add("start:" + name); onStart.run(); }
            @Override public void stop() { trace.add("stop:" + name); onStop.run(); }
        };
    }

    @Test
    void recoveryRunsBeforeDispatchersAndSchedulingGateOpensLast() {
        List<String> trace = new ArrayList<>();
        SchedulingGate gate = new SchedulingGate();
        var ingress = component("ingress", trace, () -> {
            assertFalse(gate.isOpen());
        }, () -> {});
        var dispatcher = component("dispatcher", trace, () -> {
            assertFalse(gate.isOpen());
        }, () -> {});
        var scheduler = component("scheduler", trace, () -> {
            assertFalse(gate.isOpen());
        }, () -> {});

        RuntimeHost host = new RuntimeHost((limit, actor) -> {
            trace.add("recovery");
            assertEquals(25, limit);
            assertEquals("host-startup", actor);
            assertFalse(gate.isOpen());
            return emptyRecoveryResult();
        }, ingress, List.of(dispatcher, scheduler), gate, 25, "host-startup");

        host.start();
        assertEquals(RuntimeHost.State.RUNNING, host.state());
        assertTrue(gate.isOpen());
        assertEquals(List.of("start:ingress", "recovery", "start:dispatcher", "start:scheduler"), trace);
        assertNotNull(host.recoveryResult());
        host.close();
        assertEquals(RuntimeHost.State.STOPPED, host.state());
        assertFalse(gate.isOpen());
        assertEquals(List.of("start:ingress", "recovery", "start:dispatcher", "start:scheduler",
                "stop:scheduler", "stop:dispatcher", "stop:ingress"), trace);
        host.close();
        assertEquals(7, trace.size());
    }

    @Test
    void recoveryFailureNeverStartsDispatcherOrOpensSchedulingGate() {
        List<String> trace = new ArrayList<>();
        SchedulingGate gate = new SchedulingGate();
        var ingress = component("ingress", trace, () -> {}, () -> {});
        var dispatcher = component("dispatcher", trace, () -> {}, () -> {});
        RuntimeHost host = new RuntimeHost((limit, actor) -> {
            trace.add("recovery");
            throw new IllegalStateException("database unavailable");
        }, ingress, List.of(dispatcher), gate, 10, "host-startup");

        assertThrows(IllegalStateException.class, host::start);
        assertEquals(RuntimeHost.State.FAILED, host.state());
        assertFalse(gate.isOpen());
        assertEquals(List.of("start:ingress", "recovery", "stop:ingress"), trace);
        assertThrows(IllegalStateException.class, host::start);
    }

    @Test
    void componentStartFailureStopsAlreadyStartedComponentsInReverseOrder() {
        List<String> trace = new ArrayList<>();
        SchedulingGate gate = new SchedulingGate();
        var ingress = component("ingress", trace, () -> {}, () -> {});
        var first = component("first", trace, () -> {}, () -> {});
        var second = component("second", trace, () -> { throw new IllegalStateException("start failed"); }, () -> {});
        RuntimeHost host = new RuntimeHost((limit, actor) -> emptyRecoveryResult(),
                ingress, List.of(first, second), gate, 10, "host-startup");

        assertThrows(IllegalStateException.class, host::start);
        assertFalse(gate.isOpen());
        assertEquals(RuntimeHost.State.FAILED, host.state());
        assertEquals(List.of("start:ingress", "start:first", "start:second",
                "stop:second", "stop:first", "stop:ingress"), trace);
    }

    @Test
    void resolveDrainTargetStatusMapsKnownKindsAndFailsClosedOnUnknown() {
        assertEquals("PAUSED", RuntimeDispatchLoop.resolveDrainTargetStatus("PAUSE"));
        assertEquals("STOPPED", RuntimeDispatchLoop.resolveDrainTargetStatus("STOP"));
        assertEquals("FAILED", RuntimeDispatchLoop.resolveDrainTargetStatus("FAILURE"));
        assertThrows(IllegalStateException.class,
                () -> RuntimeDispatchLoop.resolveDrainTargetStatus("MAINTENANCE"));
        assertThrows(IllegalStateException.class,
                () -> RuntimeDispatchLoop.resolveDrainTargetStatus(null));
    }

    @Test
    void wakeRunIfRunningReturnsCompletedFutureWhenHostNotRunning(@TempDir Path tempDir) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:5432/unused", "unused", "unused");
        ds.setDriverClassName("org.postgresql.Driver");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        TcpDeviceConnection.Config gatewayConfig = new TcpDeviceConnection.Config(
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofMillis(50), Duration.ofMillis(200), 4096, 64);
        EmbeddedWorkflowRuntimeHost.Config config = new EmbeddedWorkflowRuntimeHost.Config(
                tempDir.resolve("wake-test.lock"),
                "wake-unit-host",
                2, 16, 4,
                2, 16, 50,
                Duration.ofMillis(100), 20,
                List.of(),
                gatewayConfig,
                null);
        EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                config, repo, node -> null, node -> Optional.empty());
        try {
            CompletableFuture<Void> beforeStart = host.wakeRunIfRunning(UUID.randomUUID(), UUID.randomUUID());
            assertTrue(beforeStart.isDone() && !beforeStart.isCompletedExceptionally());
        } finally {
            host.close();
        }
        CompletableFuture<Void> afterClose = host.wakeRunIfRunning(UUID.randomUUID(), UUID.randomUUID());
        assertTrue(afterClose.isDone() && !afterClose.isCompletedExceptionally());
    }
}
