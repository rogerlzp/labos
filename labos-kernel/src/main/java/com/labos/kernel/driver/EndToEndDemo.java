package com.labos.kernel.driver;

import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicyResolver;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 端到端：真实工艺定义 → 状态机 → 驱动契约。
 *
 * <p>这条链打通后，从「一个 workflow 定义」到「驱动返回设备原文」不再有断点。
 *
 * <pre>
 *   java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.driver.EndToEndDemo
 * </pre>
 */
public final class EndToEndDemo {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EndToEndDemo.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    private static final long TENANT = 1L;

    record TaskNode(long id, String key, String name, String capability, String method) {
    }

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();

        // ---------- 1. 驱动
        DriverRegistry registry = new DriverRegistry();
        Map<String, VirtualDriver> drivers = loadDrivers(db, registry);

        section("1. 驱动注册");
        log.info(registry.healthReport());

        // ---------- 2. 真实工艺节点
        List<TaskNode> nodes = loadTasks(db, 6);
        section("2. 一条流程的前 6 个 task");
        printf("  %-4s %-28s %-12s %s%n", "#", "节点", "能力", "方法");
        log.info("  " + "-".repeat(70));
        for (TaskNode n : nodes) {
            printf("  %-4d %-28s %-12s %s%n",
                    n.id(), truncate(n.name(), 28), n.capability(), n.method());
        }

        // ---------- 3. 执行
        section("3. 执行（状态机 -> DriverStepExecutor -> 驱动）");
        RunProgress progress = run(db, registry, nodes);

        printf("  %-4s %-24s %-12s %-6s %-8s %s%n",
                "#", "节点", "能力", "尝试", "设备", "结果");
        log.info("  " + "-".repeat(76));
        for (NodeProgress np : progress.all()) {
            TaskNode n = nodes.stream().filter(t -> t.id() == np.nodeId()).findFirst().orElse(null);
            printf("  %-4d %-24s %-12s %-6d %-8s %s%n",
                    np.nodeId(),
                    n == null ? "?" : truncate(n.name(), 24),
                    n == null ? "?" : n.capability(),
                    np.attempt(),
                    np.currentResourceId() == null ? "—" : np.currentResourceId(),
                    np.status());
        }
        log.info("");
        log.info("  结果: " + progress.summary());

        // ---------- 4. 设备原文
        section("4. 设备原始回包");
        DriverStepExecutor exec = lastExecutor;
        for (TaskNode n : nodes) {
            exec.rawResponse(n.id()).ifPresent(raw ->
                    printf("  node %-3d %s%n", n.id(), raw));
        }

        // ---------- 5. 故障 -> 自动换机
        section("5. 注入故障：第一台设备永久失败");
        long deadDevice = findDeviceFor(registry, nodes.get(0).capability());
        for (VirtualDriver v : drivers.values()) {
            if (v.describe().deviceInstanceIds().contains(deadDevice)) {
                v.fault(deadDevice, nodes.get(0).method(), Integer.MAX_VALUE, "EC-08-0001");
                break;
            }
        }
        printf("  设备 %d 在方法 %s 上永久失败%n%n", deadDevice, nodes.get(0).method());

        RunProgress p2 = run(db, registry, nodes);
        printf("  %-4s %-24s %-6s %-8s %s%n", "#", "节点", "尝试", "设备", "结果");
        log.info("  " + "-".repeat(70));
        for (NodeProgress np : p2.all()) {
            printf("  %-4d %-24s %-6d %-8s %s%n",
                    np.nodeId(),
                    nodes.stream().filter(t -> t.id() == np.nodeId())
                            .map(t -> truncate(t.name(), 24)).findFirst().orElse("?"),
                    np.attempt(),
                    np.currentResourceId() == null ? "—" : np.currentResourceId(),
                    np.status());
        }
        log.info("");
        log.info("  结果: " + p2.summary());
        NodeProgress first = p2.all().iterator().next();
        if (first.attempt() > 0) {
            if (first.status() == NodeStatus.COMPLETED) {
                printf("%n  第 1 个节点换了 %d 次设备：%d -> %d，最终 COMPLETED%n",
                        first.attempt(), deadDevice, first.currentResourceId());
                log.info("  ★ 工艺没改一行，设备坏了自动换");
            } else {
                printf("%n  第 1 个节点尝试 %d 次，最终 %s：%s%n",
                        first.attempt(), first.status(), first.lastReason());
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    private static DriverStepExecutor lastExecutor;

    private static RunProgress run(DbConfig db, DriverRegistry registry, List<TaskNode> nodes)
            throws SQLException {
        JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(db, TENANT);
        RecoveryDecider decider = new RecoveryDecider(new RecoveryPolicyResolver(repo.loadAll()));

        List<RecoverableNode> rec = new ArrayList<>();
        for (TaskNode n : nodes) {
            List<Long> cands = registry.candidates(n.capability(), Set.of());
            if (cands.isEmpty()) {
                continue;
            }
            // 用真实节点 id，保证与 workflow_node 对得上（也便于回查设备原文）
            rec.add(RecoverableNode.of(n.id(), n.key(), n.name(), n.capability(),
                    n.method(), cands.get(0), cands));
        }

        DriverStepExecutor exec = new DriverStepExecutor(registry);
        lastExecutor = exec;
        RunStateMachine sm = new RunStateMachine(decider, e -> { },
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        return sm.executeAll(rec, exec);
    }

    private static long findDeviceFor(DriverRegistry registry, String capability) {
        List<Long> list = registry.candidates(capability, Set.of());
        return list.isEmpty() ? 0L : list.get(0);
    }

    // ---------------------------------------------------------------- 数据

    private static List<TaskNode> loadTasks(DbConfig db, int limit) throws SQLException {
        List<TaskNode> out = new ArrayList<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class)
                .selectDemoTaskNodes(limit)) {
            out.add(new TaskNode(((Number) row.get("id")).longValue(),
                    (String) row.get("nodeKey"), (String) row.get("name"),
                    (String) row.get("capability"), (String) row.get("method")));
        }
        return out;
    }

    private static Map<String, VirtualDriver> loadDrivers(DbConfig db, DriverRegistry registry)
            throws SQLException {
        Map<Long, String[]> drv = new LinkedHashMap<>();
        for (java.util.Map<String, Object> row
                : com.labos.kernel.persistence.Mappers.get(
                        com.labos.kernel.persistence.DriverMapper.class)
                        .selectDrivers(TENANT)) {
            drv.put(((Number) row.get("id")).longValue(),
                    new String[]{String.valueOf(row.get("code")),
                            String.valueOf(row.get("endpoint")),
                            String.valueOf(row.get("protocol"))});
        }

        Map<Long, Map<Long, Set<String>>> devCaps = new LinkedHashMap<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DriverMapper.class)
                .selectDriverDeviceCapabilities()) {
            devCaps.computeIfAbsent(((Number) row.get("driverId")).longValue(),
                            k -> new LinkedHashMap<>())
                    .computeIfAbsent(((Number) row.get("deviceId")).longValue(),
                            k -> new LinkedHashSet<>())
                    .add((String) row.get("capabilityCode"));
        }

        Map<String, VirtualDriver> out = new LinkedHashMap<>();
        for (Map.Entry<Long, String[]> e : drv.entrySet()) {
            Map<Long, Set<String>> dc = devCaps.getOrDefault(e.getKey(), Map.of());
            if (dc.isEmpty()) {
                continue;
            }
            String[] v = e.getValue();
            VirtualDriver d = new VirtualDriver(v[0], v[1], v[2], dc);
            registry.register(d);
            out.put(v[0], d);
        }
        return out;
    }

    private static void section(String title) {
        log.info("");
        log.info("=".repeat(84));
        log.info("  " + title);
        log.info("=".repeat(84));
        log.info("");
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private EndToEndDemo() {
    }
}
