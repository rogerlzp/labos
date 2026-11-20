package com.labos.kernel.workflow.runtime.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labos.kernel.workflow.runtime.gateway.LabSepWireCodec;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-stack end-to-end PG17 + loopback {@code #!LABSEP!#} integration test verifying
 * {@link GatewayDeviceTaskBridge} executing a realistic multi-device BPMN workflow combining
 * {@code __set_var} + XOR branch + Parallel Split/Join + two {@code #!LABSEP!#} TCP devices
 * with mid-parallel {@code PAUSE -> RESUME} and timeout-to-{@code UNKNOWN} T21 escalation.
 */
class GatewayDeviceTaskBridgeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private DriverManagerDataSource dataSource() {
        String host = env("PGHOST", "localhost");
        String port = env("PGPORT", "5432");
        String db = env("PGDATABASE", "runtime_test");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + host + ":" + port + "/" + db,
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static UUID createTenant(DriverManagerDataSource ds, String prefix) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','" + prefix + "-" + tenant + "','bridge-it')");
        }
        return tenant;
    }

    private static TcpDeviceConnection.Config gatewayConfig() {
        return new TcpDeviceConnection.Config(
                Duration.ofSeconds(2),
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                Duration.ofMillis(40),
                Duration.ofMillis(160),
                4096,
                64);
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier cond, String desc)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(15);
        }
        assertTrue(cond.getAsBoolean(), "Condition not satisfied within " + timeout + ": " + desc);
    }

    private static final String FULL_WORKFLOW_BPMN = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <bpmn:process id="lab_protocol">
                <bpmn:startEvent id="start"/>
                <bpmn:task id="init_vars" name="__setVar" protocolName="__setVar" methodParams="{&quot;var&quot;:&quot;score&quot;,&quot;value&quot;:&quot;92&quot;}"/>
                <bpmn:task id="brief_wait" name="__sleep" protocolName="__sleep" methodParams="{&quot;sec&quot;:&quot;0.03&quot;}"/>
                <bpmn:exclusiveGateway id="xor" name="XOR_SPLIT" gatewayDirection="Diverging" default="f_skip"/>
                <bpmn:parallelGateway id="split" name="PARALLEL_SPLIT" gatewayDirection="Diverging"/>
                <bpmn:task id="dispense" name="Dispense" protocolName="Dispense" resource="206" methodParams="{&quot;volumeUl&quot;:150}"/>
                <bpmn:task id="spin" name="Centrifuge Spin" protocolName="Spin" resource="207" methodParams="{&quot;rpm&quot;:3000}"/>
                <bpmn:parallelGateway id="join" name="PARALLEL_MERGE" gatewayDirection="Converging"/>
                <bpmn:task id="seal" name="Seal Plate" protocolName="Seal" resource="206" methodParams="{&quot;tempC&quot;:175}"/>
                <bpmn:task id="skip_noop" name="Skip Path" protocolName="__noop"/>
                <bpmn:exclusiveGateway id="merge" name="XOR_MERGE" gatewayDirection="Converging"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f0" sourceRef="start" targetRef="init_vars"/>
                <bpmn:sequenceFlow id="f0b" sourceRef="init_vars" targetRef="brief_wait"/>
                <bpmn:sequenceFlow id="f1" sourceRef="brief_wait" targetRef="xor"/>
                <bpmn:sequenceFlow id="f_run" sourceRef="xor" targetRef="split">
                  <bpmn:conditionExpression>score &gt;= 80</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f_skip" sourceRef="xor" targetRef="skip_noop"/>
                <bpmn:sequenceFlow id="fa" sourceRef="split" targetRef="dispense"/>
                <bpmn:sequenceFlow id="fb" sourceRef="split" targetRef="spin"/>
                <bpmn:sequenceFlow id="faj" sourceRef="dispense" targetRef="join"/>
                <bpmn:sequenceFlow id="fbj" sourceRef="spin" targetRef="join"/>
                <bpmn:sequenceFlow id="fjs" sourceRef="join" targetRef="seal"/>
                <bpmn:sequenceFlow id="fsm" sourceRef="seal" targetRef="merge"/>
                <bpmn:sequenceFlow id="fskm" sourceRef="skip_noop" targetRef="merge"/>
                <bpmn:sequenceFlow id="fe" sourceRef="merge" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    void multiDeviceLabSepXorParallelWorkflowWithMidLongTaskPauseAndResume() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "bridge-full");

        String dev1 = "pipettor-" + UUID.randomUUID().toString().substring(0, 6);
        String dev2 = "centrifuge-" + UUID.randomUUID().toString().substring(0, 6);

        CountDownLatch spinRunningSent = new CountDownLatch(1);
        CountDownLatch allowSpinComplete = new CountDownLatch(1);
        CountDownLatch dispenseCompleted = new CountDownLatch(1);

        try (SimLabSepServer server1 = new SimLabSepServer((req, conn) -> {
            try {
                conn.sendReply(req.get("message_id").asText(), req.get("method").asText(),
                        req.get("equipment_id").asText(), 2, Map.of("ok", true));
                if ("Dispense".equals(req.get("method").asText())) {
                    dispenseCompleted.countDown();
                }
            } catch (Exception ignored) {
            }
        });
             SimLabSepServer server2 = new SimLabSepServer((req, conn) -> {
                 try {
                     String msgId = req.get("message_id").asText();
                     String method = req.get("method").asText();
                     String eqId = req.get("equipment_id").asText();
                     // Long task: send method_status=1 (RUNNING) immediately, wait for pause to be requested!
                     conn.sendReply(msgId, method, eqId, 1, Map.of("rpm", 1500));
                     spinRunningSent.countDown();
                     assertTrue(allowSpinComplete.await(8, TimeUnit.SECONDS));
                     conn.sendReply(msgId, method, eqId, 2, Map.of("rpm", 3000));
                 } catch (Exception ignored) {
                 }
             })) {

            List<TcpDeviceConnection.Endpoint> endpoints = List.of(
                    new TcpDeviceConnection.Endpoint(dev1, "127.0.0.1", server1.port(), LabSepWireCodec.INSTANCE),
                    new TcpDeviceConnection.Endpoint(dev2, "127.0.0.1", server2.port(), LabSepWireCodec.INSTANCE));
            Map<Long, String> resourceMap = Map.of(206L, dev1, 207L, dev2);
            GatewayDeviceTaskBridge bridge = new GatewayDeviceTaskBridge(
                    repo, endpoints, resourceMap, Duration.ofSeconds(10));

            var cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("bridge-full.lock"),
                    "bridge-full-host",
                    4, 32, 8, 2, 64, 50,
                    Duration.ofMillis(25), 16,
                    endpoints,
                    gatewayConfig(),
                    tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(cfg, repo, bridge.specResolver(), bridge.handlerResolver())) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(dev1, Duration.ofSeconds(3)));
                assertTrue(host.deviceGateway().awaitOnline(dev2, Duration.ofSeconds(3)));

                String planKey = "bridge-full-" + UUID.randomUUID();
                GraphPlan plan = new BpmnGraphCompiler().compile(
                        new BpmnGraphCompiler.CompilationInput(planKey, "1", FULL_WORKFLOW_BPMN, Map.of()));
                var run = repo.createRun(tenant, "req-" + planKey, "CREATE", "{}",
                        planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                        plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");

                host.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

                // Wait until Dispense completed on dev1 AND Spin is actively RUNNING (method_status=1) on dev2.
                assertTrue(dispenseCompleted.await(8, TimeUnit.SECONDS), "Dispense did not execute on dev1");
                assertTrue(spinRunningSent.await(8, TimeUnit.SECONDS), "Spin RUNNING frame not sent on dev2");

                // Request PAUSE while Spin is physically running -> allow Spin to finish -> must PAUSE BEFORE Seal!
                host.requestPause(tenant, run.runId());
                allowSpinComplete.countDown();

                awaitCondition(Duration.ofSeconds(8), () -> {
                    var ov = host.getRunOverview(tenant, run.runId()).orElseThrow();
                    return "PAUSED".equals(ov.status()) && "PAUSE".equals(ov.controlGate())
                            && host.healthSnapshot().activeLocalWorkers() == 0;
                }, "Workflow must converge to PAUSED after in-flight Spin finishes and before Seal");

                assertEquals(1, server1.receivedFrames().size(), "Seal must NOT be sent to dev1 while PAUSED");
                assertEquals("Dispense", server1.receivedFrames().get(0).get("method").asText());
                assertEquals(150, server1.receivedFrames().get(0).get("parameters").get("volumeUl").asInt());
                assertEquals(1, server2.receivedFrames().size());
                assertEquals("Spin", server2.receivedFrames().get(0).get("method").asText());
                assertEquals(3000, server2.receivedFrames().get(0).get("parameters").get("rpm").asInt());

                // RESUME -> Join releases -> Seal executes on dev1 over #!LABSEP!# -> Run SUCCEEDED!
                host.resumeRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

                awaitCondition(Duration.ofSeconds(8), () -> {
                    var ov = host.getRunOverview(tenant, run.runId()).orElseThrow();
                    return "SUCCEEDED".equals(ov.status()) && "CLOSED".equals(ov.controlGate());
                }, "Workflow must finish SUCCEEDED after RESUME executes Seal");

                assertEquals(2, server1.receivedFrames().size());
                assertEquals("Seal", server1.receivedFrames().get(1).get("method").asText());
                assertEquals(175, server1.receivedFrames().get(1).get("parameters").get("tempC").asInt());

                var overview = host.getRunOverview(tenant, run.runId()).orElseThrow();
                assertEquals(3, overview.commands().size());
                assertTrue(overview.commands().stream().allMatch(c -> "SUCCEEDED".equals(c.commandState())));
                assertTrue(overview.completedNodes().containsAll(List.of("init_vars", "dispense", "spin", "seal")));
                assertFalse(overview.completedNodes().contains("skip_noop"));
            }
        }
    }

    @Test
    void bridgeCommandTimeoutMarksCommandUnknownForT21Reconciliation() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "bridge-timeout");
        String dev1 = "silent-dev-" + UUID.randomUUID().toString().substring(0, 6);

        // Silent #!LABSEP!# server: accepts command and never replies.
        try (SimLabSepServer silentServer = new SimLabSepServer((req, conn) -> {})) {
            List<TcpDeviceConnection.Endpoint> endpoints = List.of(
                    new TcpDeviceConnection.Endpoint(dev1, "127.0.0.1", silentServer.port(), LabSepWireCodec.INSTANCE));
            GatewayDeviceTaskBridge bridge = new GatewayDeviceTaskBridge(
                    repo, endpoints, Map.of(900L, dev1), Duration.ofMillis(350));

            var cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("bridge-timeout.lock"),
                    "bridge-timeout-host",
                    2, 16, 4, 2, 32, 50,
                    Duration.ofMillis(25), 16,
                    endpoints,
                    gatewayConfig(),
                    tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(cfg, repo, bridge.specResolver(), bridge.handlerResolver())) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(dev1, Duration.ofSeconds(3)));

                String xml = """
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                          <bpmn:process id="p">
                            <bpmn:startEvent id="start"/>
                            <bpmn:task id="silent_op" protocolName="Heat" resource="900" methodParams="{&quot;tempC&quot;:95}"/>
                            <bpmn:endEvent id="end"/>
                            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="silent_op"/>
                            <bpmn:sequenceFlow id="f2" sourceRef="silent_op" targetRef="end"/>
                          </bpmn:process>
                        </bpmn:definitions>
                        """;
                String planKey = "bridge-to-" + UUID.randomUUID();
                GraphPlan plan = new BpmnGraphCompiler().compile(
                        new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));
                var run = repo.createRun(tenant, "req-" + planKey, "CREATE", "{}",
                        planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                        plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");

                host.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

                awaitCondition(Duration.ofSeconds(8), () ->
                        !host.listUnknownCommands(tenant, 10).isEmpty()
                                && !host.listManualRequiredRuns(tenant, 10).isEmpty()
                                && host.healthSnapshot().activeLocalWorkers() == 0,
                        "Timed-out bridge command must escalate to UNKNOWN and MANUAL_REQUIRED");

                var unknown = host.listUnknownCommands(tenant, 10);
                assertEquals(1, unknown.size());
                assertEquals(dev1, unknown.get(0).deviceId());
                assertEquals("UNKNOWN", unknown.get(0).commandState());
                assertEquals(1, silentServer.receivedFrames().size());

                assertTrue(host.reconcileUnknownCommand(
                        tenant, unknown.get(0).commandId(), "FAILED", "manual-check-1",
                        "{\"operator\":\"verified-idle\"}", "admin"));
                assertTrue(host.listUnknownCommands(tenant, 10).isEmpty());
            }
        }
    }

    private static final class SimLabSepServer implements AutoCloseable {
        private static final byte[] SEP = LabSepWireCodec.SEPARATOR.getBytes(StandardCharsets.UTF_8);

        private final ServerSocket serverSocket;
        private final BiConsumer<JsonNode, ClientConn> onCommand;
        private final CopyOnWriteArrayList<JsonNode> receivedFrames = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        SimLabSepServer(BiConsumer<JsonNode, ClientConn> onCommand) throws IOException {
            this.onCommand = onCommand;
            this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread accept = new Thread(this::acceptLoop, "sim-labsep-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() { return serverSocket.getLocalPort(); }
        List<JsonNode> receivedFrames() { return receivedFrames; }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket s = serverSocket.accept();
                    sockets.add(s);
                    Thread t = new Thread(() -> handle(s), "sim-labsep-client-" + s.getPort());
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (closed.get()) break;
                }
            }
        }

        private void handle(Socket s) {
            try (s) {
                BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                ClientConn conn = new ClientConn(s.getOutputStream());
                ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
                byte[] tail = new byte[SEP.length];
                int tailLen = 0;
                int b;
                while (!closed.get() && (b = in.read()) != -1) {
                    buf.write(b);
                    if (tailLen < SEP.length) {
                        tail[tailLen++] = (byte) b;
                    } else {
                        System.arraycopy(tail, 1, tail, 0, SEP.length - 1);
                        tail[SEP.length - 1] = (byte) b;
                    }
                    if (tailLen == SEP.length && Arrays.equals(tail, SEP)) {
                        byte[] raw = buf.toByteArray();
                        String text = new String(raw, 0, raw.length - SEP.length, StandardCharsets.UTF_8);
                        buf.reset();
                        tailLen = 0;
                        JsonNode node = JSON.readTree(text);
                        receivedFrames.add(node);
                        onCommand.accept(node, conn);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { serverSocket.close(); } catch (IOException ignored) {}
            for (Socket s : sockets) {
                try { s.close(); } catch (IOException ignored) {}
            }
        }

        static final class ClientConn {
            private final OutputStream out;
            ClientConn(OutputStream out) { this.out = out; }

            synchronized void sendReply(
                    String messageId, String method, String equipmentId,
                    int methodStatus, Map<String, Object> data) throws IOException {
                ObjectNode root = JSON.createObjectNode();
                root.put("message_id", messageId);
                root.put("message_type", 5);
                root.put("method", method);
                root.put("equipment_id", equipmentId);
                root.put("equipment_status", 1);
                root.put("method_status", methodStatus);
                root.set("error", JSON.nullNode());
                root.set("data", JSON.valueToTree(data));
                root.set("process_info", JSON.createObjectNode());
                root.put("timestamp", "2026-11-20T23:45:00Z");
                root.put("checksum", "");
                root.put("connected", 1);
                out.write((JSON.writeValueAsString(root) + LabSepWireCodec.SEPARATOR).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }
}
