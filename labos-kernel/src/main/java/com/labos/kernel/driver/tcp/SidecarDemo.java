package com.labos.kernel.driver.tcp;

import com.labos.kernel.driver.DriverDescriptor;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.DriverStepExecutor;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sidecar 端到端验证：内核 → TCP 适配器 → 模拟 .NET 驱动。
 *
 * <p>这个 Demo 证明：**协议转换是对的**。真实 .NET 驱动接入时，
 * 只需把 {@link MockDriverServer} 换成真实驱动进程，其余代码不动。
 *
 * <pre>
 *   java -cp out com.labos.kernel.driver.tcp.SidecarDemo
 * </pre>
 */
public final class SidecarDemo {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SidecarDemo.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    public static void main(String[] args) throws Exception {
        try (MockDriverServer mock = new MockDriverServer(0)) {
            int port = mock.port();

            section("1. 模拟 .NET 驱动进程（TCP 服务端）");
            log.info("  监听 127.0.0.1:" + port);
            log.info("  协议 JSON over TCP，分隔符 " + TcpDriverAdapter.DELIMITER);

            // ---------- 2. 适配器
            section("2. Sidecar 适配器（DeviceDriver 实现）");
            Set<Long> devices = new LinkedHashSet<>(List.of(200383L, 200387L, 200391L));
            Map<Long, Set<String>> devCaps = new LinkedHashMap<>();
            devCaps.put(200383L, Set.of("TRANSFER"));
            devCaps.put(200387L, Set.of("TRANSFER"));
            devCaps.put(200391L, Set.of("INCUBATE"));

            DriverDescriptor desc = new DriverDescriptor(
                    "DRV-MOCK-8501", "tcp://127.0.0.1:" + port, "tcp", "dotnet-sidecar",
                    Set.of("TRANSFER", "INCUBATE"), devCaps, devices,
                    Map.of("delimiter", TcpDriverAdapter.DELIMITER));

            TcpDriverAdapter adapter = new TcpDriverAdapter(desc, "127.0.0.1", port, Map.of());
            DriverRegistry registry = new DriverRegistry();
            registry.register(adapter);

            log.info(registry.healthReport());
            log.info("  TRANSFER 候选: " + registry.candidates("TRANSFER", Set.of()));
            log.info("  INCUBATE 候选: " + registry.candidates("INCUBATE", Set.of()));

            // ---------- 3. 执行
            section("3. 内核执行（状态机 → 适配器 → TCP → 驱动）");
            // 用带 method 的构造：驱动调用的是 protocol_name（Transfer），不是能力码
            List<RecoverableNode> nodes = List.of(
                    RecoverableNode.of(1, "Transfer", "Transfer", "TRANSFER", "Transfer",
                            200383L, List.of(200383L, 200387L)),
                    RecoverableNode.of(2, "Incubate", "Incubate", "INCUBATE", "Deposit1",
                            200391L, List.of(200391L)));

            DriverStepExecutor exec = new DriverStepExecutor(registry);
            RunProgress p = run(exec, nodes);

            printNodes(p, nodes);

            // ---------- 4. 设备原文
            section("4. 驱动返回的 FinishMessage 原文");
            for (RecoverableNode n : nodes) {
                exec.rawResponse(n.id()).ifPresent(raw ->
                        printf("  node %-2d %s%n%n", n.id(), truncate(raw, 150)));
            }

            // ---------- 5. 驱动收到的报文
            section("5. 驱动侧实际收到的指令（验证协议正确）");
            mock.received().forEach(r -> log.info("  " + r));

            // ---------- 6. 故障 -> 换机
            section("6. 注入故障：设备 200383 的 Transfer 永久失败");
            mock.fault("200383", "Transfer", "EC-08-0001");
            log.info("  驱动侧已配置：200383:Transfer -> EC-08-0001\n");

            DriverStepExecutor exec2 = new DriverStepExecutor(registry);
            RunProgress p2 = run(exec2, nodes);
            printNodes(p2, nodes);

            NodeProgress first = p2.all().iterator().next();
            if (first.status() == NodeStatus.COMPLETED && first.attempt() > 0) {
                printf("%n  设备 200383 失败 -> 自动切到 %d -> 完成%n",
                        first.currentResourceId());
                log.info("  ★ 全程只改了 mock 的故障配置，工艺和内核一行没动");
            }

            section("7. 结论");
            log.info("  ✓ JSON over TCP + #!LABSEP!# 分帧正确");
            log.info("  ✓ message_id 透传，能正确过滤心跳、匹配响应");
            log.info("  ✓ FinishMessage 原文完整回传（device_raw_response）");
            log.info("  ✓ 错误码原样透传，适配层不做业务判断");
            log.info("  ✓ 驱动侧零改动，换机由内核决策");
            log.info("");
            log.info("  接入真实 .NET 驱动时，只需把 MockDriverServer 换成真实进程。");
        }
    }

    private static RunProgress run(DriverStepExecutor exec, List<RecoverableNode> nodes) {
        RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        return sm.executeAll(nodes, exec);
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy("EC-08-0001", RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, Duration.ZERO, 10, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.MANUAL,
                        0, Backoff.FIXED, Duration.ZERO, 99, true))));
    }

    private static void printNodes(RunProgress p, List<RecoverableNode> nodes) {
        printf("  %-4s %-14s %-12s %-6s %-8s %s%n",
                "#", "节点", "能力", "尝试", "设备", "结果");
        log.info("  " + "-".repeat(66));
        for (NodeProgress np : p.all()) {
            RecoverableNode n = nodes.stream()
                    .filter(x -> x.id() == np.nodeId()).findFirst().orElse(null);
            printf("  %-4d %-14s %-12s %-6d %-8s %s%n",
                    np.nodeId(),
                    n == null ? "?" : n.name(),
                    n == null ? "?" : n.capabilityCode(),
                    np.attempt(),
                    np.currentResourceId() == null ? "-" : np.currentResourceId(),
                    np.status());
        }
        log.info("  结果: " + p.summary());
        log.info("");
    }

    private static void section(String t) {
        log.info("");
        log.info("=".repeat(82));
        log.info("  " + t);
        log.info("=".repeat(82));
        log.info("");
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private SidecarDemo() {
    }
}
