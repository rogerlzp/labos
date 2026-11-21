package com.labos.boot;

import com.labos.kernel.persistence.DeviceMapper;
import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.sim.DeviceCore;
import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
import com.labos.kernel.workflow.runtime.definition.LegacyKernelWorkflowDefinitionSource;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-JVM {@code kill -9} ({@code SIGKILL}) 3-round crash recovery stress harness.
 *
 * <p>Starts two real {@link DeviceCore} ({@code #!LABSEP!#}) TCP simulators in the parent
 * orchestrator JVM and spawns 4 successive {@link ChildHostMain} OS child JVMs sharing one
 * persistent {@code SingleInstanceHostLock} file against PostgreSQL 17:
 * <ol>
 *   <li>Round 1: {@code SIGKILL} mid in-flight long {@code DEVICE_COMMAND} -&gt; verifies lock
 *       re-acquisition without deleting lock file, {@code UNKNOWN}/{@code MANUAL_REQUIRED}, zero
 *       duplicate resend, and T21 reconciliation.</li>
 *   <li>Round 2: {@code SIGKILL} while holding an {@code ACTIVE} Wait Run and a {@code PAUSED} Run
 *       with deferred {@code RECEIVED} Wait Inbox receipt -&gt; verifies Wait &amp; paused Inbox
 *       durability across crash and clean completion to {@code SUCCEEDED}.</li>
 *   <li>Round 3: {@code SIGKILL} mid pre-side-effect {@code PURE} {@code __sleep} Attempts -&gt;
 *       verifies {@code SAFE_TO_RETRY}/{@code ABANDONED_SAFE} automatic requeue &amp; completion
 *       to {@code SUCCEEDED} with zero manual intervention.</li>
 * </ol>
 */
public final class WorkflowRuntimeKill9StressSelfTest {

    private static final AtomicInteger PASSED_ASSERTIONS = new AtomicInteger(0);

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static DriverManagerDataSource buildDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PGHOST", "localhost") + ":" + env("PGPORT", "5432")
                        + "/" + env("PGDATABASE", "runtime_kill9_e2e"),
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static GraphMapper emptyGraphMapper() {
        return (GraphMapper) Proxy.newProxyInstance(
                GraphMapper.class.getClassLoader(),
                new Class<?>[]{GraphMapper.class},
                (proxy, method, args) -> "selectRuntimeWorkflowNodeMetadata".equals(method.getName())
                        ? List.of() : null);
    }

    private static DeviceMapper registryDeviceMapper(int fastPort, int slowPort) {
        return (DeviceMapper) Proxy.newProxyInstance(
                DeviceMapper.class.getClassLoader(),
                new Class<?>[]{DeviceMapper.class},
                (proxy, method, args) -> {
                    if ("selectDeviceRegistry".equals(method.getName())) {
                        return List.of(
                                Map.of("deviceId", 301L, "resourceId", 601L, "ip", "127.0.0.1", "endpoint", "tcp://127.0.0.1:" + fastPort),
                                Map.of("deviceId", 302L, "resourceId", 602L, "ip", "127.0.0.1", "endpoint", "tcp://127.0.0.1:" + slowPort));
                    }
                    throw new AssertionError("Unexpected DeviceMapper call: " + method.getName());
                });
    }

    /** Child JVM entrypoint hosting an isolated {@link EmbeddedWorkflowRuntimeHost}. */
    public static final class ChildHostMain {
        public static void main(String[] args) throws Exception {
            if (args.length < 6) {
                throw new IllegalArgumentException(
                        "Usage: ChildHostMain <hostId> <lockFile> <readyFile> <fastPort> <slowPort> <cmdTimeoutMs>");
            }
            String hostId = args[0];
            Path lockFile = Path.of(args[1]);
            Path readyFile = Path.of(args[2]);
            int fastPort = Integer.parseInt(args[3]);
            int slowPort = Integer.parseInt(args[4]);
            String commandTimeoutMs = args[5];

            DriverManagerDataSource ds = buildDataSource();
            PlatformTransactionManager tm = new DataSourceTransactionManager(ds);
            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("kill9-child-props", Map.of(
                    WorkflowRuntimeHostProperties.PROP_ENABLED, "true",
                    WorkflowRuntimeHostProperties.PROP_BRIDGE_ENABLED, "true",
                    WorkflowRuntimeHostProperties.PROP_DISCOVER_FROM_REGISTRY, "true",
                    WorkflowRuntimeHostProperties.PROP_LOCK_FILE, lockFile.toString(),
                    WorkflowRuntimeHostProperties.PROP_HOST_ID, hostId,
                    WorkflowRuntimeHostProperties.PROP_POLL_MS, "25",
                    WorkflowRuntimeHostProperties.PROP_SCOPED_KERNEL_TENANT_ID, "1",
                    WorkflowRuntimeHostProperties.PROP_BRIDGE_COMMAND_TIMEOUT_MS, commandTimeoutMs)));
            ctx.registerBean("dataSource", DataSource.class, () -> ds);
            ctx.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
            ctx.registerBean("graphMapper", GraphMapper.class, WorkflowRuntimeKill9StressSelfTest::emptyGraphMapper);
            ctx.registerBean("deviceMapper", DeviceMapper.class, () -> registryDeviceMapper(fastPort, slowPort));
            ctx.register(WorkflowRuntimeKernelConfiguration.class);
            ctx.refresh();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                }
            }, "kill9-child-shutdown"));

            EmbeddedWorkflowRuntimeHost host = ctx.getBean(EmbeddedWorkflowRuntimeHost.class);
            if (!host.deviceGateway().awaitOnline("301", Duration.ofSeconds(10))) {
                throw new IllegalStateException("Device 301 failed to come ONLINE");
            }
            if (!host.deviceGateway().awaitOnline("302", Duration.ofSeconds(10))) {
                throw new IllegalStateException("Device 302 failed to come ONLINE");
            }

            EmbeddedWorkflowRuntimeHost.HealthSnapshot snap = host.healthSnapshot();
            Properties props = new Properties();
            props.setProperty("hostId", snap.hostId());
            props.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            props.setProperty("hostState", String.valueOf(snap.hostState()));
            props.setProperty("hostLockValid", Boolean.toString(snap.hostLockValid()));
            var rec = snap.recoveryResult();
            props.setProperty("recovery.scanned", Integer.toString(rec == null ? -1 : rec.scanned()));
            props.setProperty("recovery.safeToRetry", Integer.toString(rec == null ? -1 : rec.safeToRetry()));
            props.setProperty("recovery.manualRequired", Integer.toString(rec == null ? -1 : rec.manualRequired()));
            props.setProperty("recovery.skipped", Integer.toString(rec == null ? -1 : rec.skipped()));
            props.setProperty("recovery.requeuedOutbox",
                    Integer.toString(rec == null || rec.outbox() == null ? -1 : rec.outbox().safelyRequeued()));

            Path tmp = readyFile.resolveSibling(readyFile.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "ChildHostMain ready snapshot");
            }
            Files.move(tmp, readyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            new CountDownLatch(1).await();
        }
    }

    public static void main(String[] args) throws Exception {
        DriverManagerDataSource ds = buildDataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenantId = repo.resolveTenantId(
                LegacyKernelWorkflowDefinitionSource.TENANT_KEY_PREFIX + "1",
                "Kernel tenant 1");

        DeviceCore fastDev = new DeviceCore(
                0, "301", "LH-K9", "ALIQUOT",
                50, false, 0, "EC-05-0001", false, "liquid_handler", null);
        DeviceCore slowDev = new DeviceCore(
                0, "302", "CF-K9", "CENTRIFUGE",
                4000, true, 0, "EC-05-0001", false, "centrifuge", null);
        fastDev.startServer("127.0.0.1", 0);
        slowDev.startServer("127.0.0.1", 0);

        Path tempDir = Files.createTempDirectory("labos-kill9-stress-");
        Path lockFile = tempDir.resolve("shared-host.lock");
        List<Process> spawned = new ArrayList<>();

        try {
            int fastPort = fastDev.port();
            int slowPort = slowDev.port();

            // ---- Incarnation 1 ----
            Path ready1 = tempDir.resolve("ready-1.properties");
            Path log1 = tempDir.resolve("child-1.log");
            Process child1 = spawnChild("kill9-host-1", lockFile, ready1, log1, fastPort, slowPort, 20000);
            spawned.add(child1);
            Properties snap1 = awaitReady(child1, ready1, log1, Duration.ofSeconds(25));
            require("RUNNING".equals(snap1.getProperty("hostState")), "child-1 hostState=RUNNING: " + snap1);
            require("true".equals(snap1.getProperty("hostLockValid")), "child-1 hostLockValid=true");
            require(0 == intProp(snap1, "recovery.scanned"), "child-1 clean recovery.scanned==0");

            // =========================================================================
            // Round 1: SIGKILL mid in-flight DEVICE_COMMAND -> UNKNOWN + MANUAL_REQUIRED + T21
            // =========================================================================
            UUID run1 = createAndStartRun(repo, tenantId, "k9-r1-" + UUID.randomUUID(), round1DeviceBpmn());
            awaitCondition(Duration.ofSeconds(15), () ->
                    scalarLong(ds, """
                            SELECT count(*) FROM workflow_runtime.command_ledger
                             WHERE tenant_id=? AND run_id=? AND command_state IN ('SEND_STARTED','RUNNING')
                            """, tenantId, run1) == 1L,
                    () -> "Round 1 command_ledger should enter SEND_STARTED/RUNNING before SIGKILL");

            UUID cmd1Id = queryScalar(ds,
                    "SELECT command_id FROM workflow_runtime.command_ledger WHERE tenant_id=? AND run_id=?",
                    rs -> rs.getObject(1, UUID.class), tenantId, run1);
            require(cmd1Id != null, "Round 1 cmd1Id present");

            // SIGKILL child-1 WITHOUT deleting lockFile!
            int rc1 = kill9(child1);
            require(rc1 == 137, "child-1 must exit with SIGKILL 137, got " + rc1);

            // ---- Incarnation 2 recovers Round 1 ----
            Path ready2 = tempDir.resolve("ready-2.properties");
            Path log2 = tempDir.resolve("child-2.log");
            Process child2 = spawnChild("kill9-host-2", lockFile, ready2, log2, fastPort, slowPort, 20000);
            spawned.add(child2);
            Properties snap2 = awaitReady(child2, ready2, log2, Duration.ofSeconds(25));
            require("RUNNING".equals(snap2.getProperty("hostState")), "child-2 hostState=RUNNING");
            require("true".equals(snap2.getProperty("hostLockValid")), "child-2 must re-acquire shared lockFile");
            require(1 == intProp(snap2, "recovery.scanned"), "Round 1 recovery.scanned==1, got " + snap2);
            require(1 == intProp(snap2, "recovery.manualRequired"), "Round 1 recovery.manualRequired==1, got " + snap2);
            require(0 == intProp(snap2, "recovery.safeToRetry"), "Round 1 recovery.safeToRetry==0, got " + snap2);
            require(0 == intProp(snap2, "recovery.skipped"), "Round 1 recovery.skipped==0");

            require("MANUAL_REQUIRED".equals(runStatus(ds, tenantId, run1)),
                    "Round 1 Run must escalate to MANUAL_REQUIRED");
            require(1L == scalarLong(ds,
                    "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id=? AND run_id=?",
                    tenantId, run1),
                    "Round 1 must NEVER duplicate physical device command");
            require("UNKNOWN".equals(queryScalar(ds,
                    "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id=? AND command_id=?",
                    rs -> rs.getString(1), tenantId, cmd1Id)),
                    "Round 1 command_ledger state must be UNKNOWN");
            require(1L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.manual_intervention
                     WHERE tenant_id=? AND run_id=? AND intervention_state='OPEN'
                    """, tenantId, run1),
                    "Round 1 must open manual_intervention");

            require(repo.resolveUnknownCommand(
                    tenantId, cmd1Id, "SUCCEEDED", "kill9-r1-seq",
                    "{\"verifiedBy\":\"kill9-round1\"}", "kill9-auditor"),
                    "T21 reconcile must succeed for Round 1 UNKNOWN command");
            require("SUCCEEDED".equals(queryScalar(ds,
                    "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id=? AND command_id=?",
                    rs -> rs.getString(1), tenantId, cmd1Id)),
                    "Round 1 command state must be SUCCEEDED after T21 reconcile");
            require(1L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.runtime_audit
                     WHERE tenant_id=? AND run_id=? AND action='UNKNOWN_COMMAND_RESOLVED'
                    """, tenantId, run1),
                    "Round 1 T21 audit entry required");

            // =========================================================================
            // Round 2: SIGKILL holding ACTIVE Wait + PAUSED Run with deferred RECEIVED signal
            // =========================================================================
            UUID run2A = createAndStartRun(repo, tenantId, "k9-r2a-" + UUID.randomUUID(), waitThenFastDeviceBpmn("kill9.wait.a"));
            UUID run2B = createAndStartRun(repo, tenantId, "k9-r2b-" + UUID.randomUUID(), waitThenFastDeviceBpmn("kill9.wait.b"));
            awaitCondition(Duration.ofSeconds(15), () ->
                    scalarLong(ds, """
                            SELECT count(*) FROM workflow_runtime.wait_registration
                             WHERE tenant_id=? AND run_id IN (?,?) AND wait_state='ACTIVE'
                            """, tenantId, run2A, run2B) == 2L,
                    () -> "Round 2 both Waits must register ACTIVE");

            repo.requestDrain(tenantId, run2B, "PAUSE");
            awaitCondition(Duration.ofSeconds(10), () -> "PAUSED".equals(runStatus(ds, tenantId, run2B)),
                    () -> "run2B must converge to PAUSED before deferred Wait signal");

            var pausedSignal = repo.receiveWaitInbox(
                    tenantId, run2B, "http-api", "kill9-sig-b", "kill9.wait.b", "{\"stage\":\"before-kill9\"}");
            require(WaitOutcomes.DEFERRED_RUN_NOT_OPEN.equals(pausedSignal.outcome()),
                    "Paused run2B signal must defer as DEFERRED_RUN_NOT_OPEN, got " + pausedSignal.outcome());
            require("RECEIVED".equals(queryScalar(ds,
                    "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                    rs -> rs.getString(1), tenantId, pausedSignal.inboxId())),
                    "Deferred Wait inbox receipt must persist as RECEIVED");

            // SIGKILL child-2 WITHOUT deleting lockFile!
            int rc2 = kill9(child2);
            require(rc2 == 137, "child-2 must exit with SIGKILL 137, got " + rc2);

            // ---- Incarnation 3 ----
            Path ready3 = tempDir.resolve("ready-3.properties");
            Path log3 = tempDir.resolve("child-3.log");
            Process child3 = spawnChild("kill9-host-3", lockFile, ready3, log3, fastPort, slowPort, 20000);
            spawned.add(child3);
            Properties snap3 = awaitReady(child3, ready3, log3, Duration.ofSeconds(25));
            require("RUNNING".equals(snap3.getProperty("hostState")), "child-3 hostState=RUNNING");
            require("true".equals(snap3.getProperty("hostLockValid")), "child-3 must re-acquire shared lockFile");
            require(0 == intProp(snap3, "recovery.scanned"), "Round 2 zero active Attempts -> scanned==0");

            require("RUNNING".equals(runStatus(ds, tenantId, run2A)), "run2A must remain RUNNING after SIGKILL");
            require("PAUSED".equals(runStatus(ds, tenantId, run2B)), "run2B must remain PAUSED after SIGKILL");
            require(2L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.wait_registration
                     WHERE tenant_id=? AND run_id IN (?,?) AND wait_state='ACTIVE'
                    """, tenantId, run2A, run2B),
                    "Round 2 both Waits must survive SIGKILL in ACTIVE state");

            var activeSignal = repo.receiveWaitInbox(
                    tenantId, run2A, "http-api", "kill9-sig-a", "kill9.wait.a", "{\"stage\":\"after-kill9\"}");
            require(WaitOutcomes.COMPLETED.equals(activeSignal.outcome()),
                    "run2A post-SIGKILL Wait signal must return COMPLETED, got " + activeSignal.outcome());
            repo.resumePausedRun(tenantId, run2B);

            awaitCondition(Duration.ofSeconds(20), () ->
                    "SUCCEEDED".equals(runStatus(ds, tenantId, run2A))
                            && "SUCCEEDED".equals(runStatus(ds, tenantId, run2B)),
                    () -> "Round 2 run2A=" + runStatus(ds, tenantId, run2A)
                            + ", run2B=" + runStatus(ds, tenantId, run2B));

            require(2L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.wait_registration
                     WHERE tenant_id=? AND run_id IN (?,?) AND wait_state='SATISFIED'
                    """, tenantId, run2A, run2B),
                    "Round 2 both Waits must become SATISFIED");
            require("PROCESSED".equals(queryScalar(ds,
                    "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                    rs -> rs.getString(1), tenantId, pausedSignal.inboxId())),
                    "Deferred Wait inbox receipt must transition to PROCESSED after resume");
            require(1L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.command_ledger
                     WHERE tenant_id=? AND run_id=? AND command_state='SUCCEEDED'
                    """, tenantId, run2A), "run2A must execute 1 Dispense command");
            require(1L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.command_ledger
                     WHERE tenant_id=? AND run_id=? AND command_state='SUCCEEDED'
                    """, tenantId, run2B), "run2B must execute 1 Dispense command");

            // =========================================================================
            // Round 3: SIGKILL mid pre-side-effect PURE __sleep Attempts -> auto-requeue to SUCCEEDED
            // =========================================================================
            UUID run3A = createAndStartRun(repo, tenantId, "k9-r3a-" + UUID.randomUUID(), pureThenFastDeviceBpmn(2200));
            UUID run3B = createAndStartRun(repo, tenantId, "k9-r3b-" + UUID.randomUUID(), pureThenFastDeviceBpmn(2200));
            awaitCondition(Duration.ofSeconds(15), () ->
                    scalarLong(ds, """
                            SELECT count(*) FROM workflow_runtime.node_attempt a
                            JOIN workflow_runtime.node_activation act
                              ON act.tenant_id=a.tenant_id AND act.activation_id=a.activation_id
                             WHERE a.tenant_id=? AND a.run_id IN (?,?)
                               AND act.node_key='slow_builtin'
                               AND a.attempt_state='STARTED'
                            """, tenantId, run3A, run3B) == 2L,
                    () -> "Round 3 both slow_builtin Attempts must reach STARTED before SIGKILL");

            require(0L == scalarLong(ds,
                    "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id=? AND run_id IN (?,?)",
                    tenantId, run3A, run3B),
                    "Round 3 pre-side-effect PURE stage must have 0 command_ledger rows before SIGKILL");

            // SIGKILL child-3 WITHOUT deleting lockFile!
            int rc3 = kill9(child3);
            require(rc3 == 137, "child-3 must exit with SIGKILL 137, got " + rc3);

            // ---- Incarnation 4 recovers Round 3 cleanly ----
            Path ready4 = tempDir.resolve("ready-4.properties");
            Path log4 = tempDir.resolve("child-4.log");
            Process child4 = spawnChild("kill9-host-4", lockFile, ready4, log4, fastPort, slowPort, 20000);
            spawned.add(child4);
            Properties snap4 = awaitReady(child4, ready4, log4, Duration.ofSeconds(25));
            require("RUNNING".equals(snap4.getProperty("hostState")), "child-4 hostState=RUNNING");
            require("true".equals(snap4.getProperty("hostLockValid")), "child-4 must re-acquire shared lockFile");
            require(2 == intProp(snap4, "recovery.scanned"), "Round 3 recovery.scanned==2, got " + snap4);
            require(2 == intProp(snap4, "recovery.safeToRetry"), "Round 3 recovery.safeToRetry==2, got " + snap4);
            require(0 == intProp(snap4, "recovery.manualRequired"), "Round 3 recovery.manualRequired==0, got " + snap4);
            require(0 == intProp(snap4, "recovery.skipped"), "Round 3 recovery.skipped==0");

            require(2L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.node_attempt
                     WHERE tenant_id=? AND run_id IN (?,?) AND attempt_no=1 AND attempt_state='ABANDONED_SAFE'
                    """, tenantId, run3A, run3B),
                    "Round 3 Attempt #1 on both Runs must become ABANDONED_SAFE");

            awaitCondition(Duration.ofSeconds(25), () ->
                    "SUCCEEDED".equals(runStatus(ds, tenantId, run3A))
                            && "SUCCEEDED".equals(runStatus(ds, tenantId, run3B)),
                    () -> "Round 3 run3A=" + runStatus(ds, tenantId, run3A)
                            + ", run3B=" + runStatus(ds, tenantId, run3B));

            require(2L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.node_attempt a
                    JOIN workflow_runtime.node_activation act
                      ON act.tenant_id=a.tenant_id AND act.activation_id=a.activation_id
                     WHERE a.tenant_id=? AND a.run_id IN (?,?)
                       AND act.node_key='slow_builtin'
                       AND act.activation_no=2 AND act.activation_state='SUCCEEDED'
                       AND a.attempt_state='SUCCEEDED'
                    """, tenantId, run3A, run3B),
                    "Round 3 slow_builtin must auto-requeue and complete on Activation #2");
            require(1L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.command_ledger
                     WHERE tenant_id=? AND run_id=? AND command_state='SUCCEEDED'
                    """, tenantId, run3A), "run3A must execute 1 Dispense command");
            require(1L == scalarLong(ds, """
                    SELECT count(*) FROM workflow_runtime.command_ledger
                     WHERE tenant_id=? AND run_id=? AND command_state='SUCCEEDED'
                    """, tenantId, run3B), "run3B must execute 1 Dispense command");
            require(0L == scalarLong(ds, """
                    SELECT coalesce(sum(reserved_count), 0)
                      FROM workflow_runtime.scheduler_capacity
                     WHERE tenant_id=? AND capacity_key IN ('device:301', 'builtin:__sleep')
                    """, tenantId),
                    "device:301 and builtin:__sleep capacity must be completely released");

            child4.destroy();
            child4.waitFor(10, TimeUnit.SECONDS);
            System.out.println("Workflow Runtime Multi-JVM SIGKILL (3 Rounds)：通过 "
                    + PASSED_ASSERTIONS.get() + ", 失败 0");
        } finally {
            for (Process p : spawned) {
                if (p.isAlive()) {
                    p.destroyForcibly();
                    try {
                        p.waitFor(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            fastDev.stopServer();
            slowDev.stopServer();
            deleteRecursively(tempDir);
        }
    }

    private static Process spawnChild(
            String hostId,
            Path lockFile,
            Path readyFile,
            Path logFile,
            int fastPort,
            int slowPort,
            int commandTimeoutMs) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", cp,
                ChildHostMain.class.getName(),
                hostId,
                lockFile.toString(),
                readyFile.toString(),
                Integer.toString(fastPort),
                Integer.toString(slowPort),
                Integer.toString(commandTimeoutMs));
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        return pb.start();
    }

    private static Properties awaitReady(
            Process child, Path readyFile, Path logFile, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(readyFile)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(readyFile)) {
                    props.load(in);
                }
                return props;
            }
            if (!child.isAlive()) {
                throw new AssertionError("Child exited prematurely with code " + child.exitValue()
                        + "\n--- child log ---\n" + readLogTail(logFile));
            }
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out waiting for child ready file " + readyFile
                + "\n--- child log ---\n" + readLogTail(logFile));
    }

    private static String readLogTail(Path logFile) {
        try {
            if (!Files.exists(logFile)) return "<missing log>";
            byte[] bytes = Files.readAllBytes(logFile);
            int max = 4096;
            if (bytes.length <= max) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return new String(bytes, bytes.length - max, max, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<log read error: " + e + ">";
        }
    }

    private static int kill9(Process proc) throws InterruptedException {
        proc.destroyForcibly();
        if (!proc.waitFor(8, TimeUnit.SECONDS)) {
            throw new AssertionError("Process did not terminate within 8s after SIGKILL");
        }
        return proc.exitValue();
    }

    private static UUID createAndStartRun(
            WorkflowRuntimeRepository repo, UUID tenantId, String defKey, String bpmnXml) {
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(defKey, "1", bpmnXml, Map.of()));
        var created = repo.createRun(
                tenantId,
                "req-" + UUID.randomUUID(),
                "CREATE",
                "{\"k\":\"" + defKey + "\"}",
                plan.definitionKey(),
                plan.definitionVersion(),
                HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),
                GraphPlanCodec.toJson(plan),
                "{}",
                "s");
        repo.startRun(tenantId, created.runId());
        return created.runId();
    }

    private static int intProp(Properties props, String key) {
        String val = props.getProperty(key);
        require(val != null, "Missing child property: " + key);
        return Integer.parseInt(val.trim());
    }

    private static String round1DeviceBpmn() {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p_k9_r1" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:task id="slow_spin" name="Load" protocolName="Load" resource="602" methodParams="{}"/>
                    <bpmn:task id="fast_disp" name="Dispense" protocolName="Dispense" resource="601" methodParams="{}"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="slow_spin"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="slow_spin" targetRef="fast_disp"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="fast_disp" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String waitThenFastDeviceBpmn(String corrKey) {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_k9_r2" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="%s"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:task id="fast_disp" name="Dispense" protocolName="Dispense" resource="601" methodParams="{}"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="fast_disp"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="fast_disp" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(corrKey);
    }

    private static String pureThenFastDeviceBpmn(long sleepMs) {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p_k9_r3" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:task id="slow_builtin" name="__sleep" protocolName="__sleep" methodParams="{&quot;ms&quot;:%d}"/>
                    <bpmn:task id="fast_disp" name="Dispense" protocolName="Dispense" resource="601" methodParams="{}"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="slow_builtin"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="slow_builtin" targetRef="fast_disp"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="fast_disp" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(sleepMs);
    }

    private interface ResultSetExtractor<T> {
        T extract(ResultSet rs) throws Exception;
    }

    private static <T> T queryScalar(DataSource ds, String sql, ResultSetExtractor<T> extractor, Object... args) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? extractor.extract(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("SQL scalar query failed: " + sql, e);
        }
    }

    private static long scalarLong(DataSource ds, String sql, Object... args) {
        Long value = queryScalar(ds, sql, rs -> {
            Number n = (Number) rs.getObject(1);
            return n == null ? 0L : n.longValue();
        }, args);
        return value == null ? 0L : value;
    }

    private static String runStatus(DataSource ds, UUID tenantId, UUID runId) {
        return queryScalar(ds,
                "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                rs -> rs.getString(1), tenantId, runId);
    }

    private static void awaitCondition(
            Duration timeout,
            java.util.function.BooleanSupplier cond,
            java.util.function.Supplier<String> diagnostic) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        require(cond.getAsBoolean(), "Timeout (" + timeout + "): " + diagnostic.get());
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
        PASSED_ASSERTIONS.incrementAndGet();
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    private WorkflowRuntimeKill9StressSelfTest() {}
}
