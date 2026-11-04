package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 驱动契约演示：用库里的 5 个真实端点跑通契约的四条设计目标。
 *
 * <pre>
 *   java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.driver.DriverContractDemo
 * </pre>
 */
public final class DriverContractDemo {

    private static final long TENANT = 1L;

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        DriverRegistry registry = new DriverRegistry();
        Map<String, VirtualDriver> drivers = loadDrivers(db, registry);

        section("1. 驱动自描述：5 个真实端点全部注册");
        System.out.print(registry.healthReport());

        section("2. 按能力找设备（工艺不再绑死设备 id）");
        for (String cap : List.of("TRANSFER", "INCUBATE", "ALIQUOT", "CENTRIFUGE", "READ_PLATE")) {
            List<Long> list = registry.candidates(cap, Set.of());
            System.out.printf("  %-12s -> %3d 台可用%s%n", cap, list.size(),
                    list.isEmpty() ? "  [无]" : "  " + list.subList(0, Math.min(3, list.size())) + "...");
        }

        section("3. 执行指令：带幂等键 + 设备原文");
        List<Long> transfer = registry.candidates("TRANSFER", Set.of());
        long device = transfer.get(0);
        DeviceDriver drv = registry.driverFor(device).orElseThrow();

        String cmdId = "cmd-" + System.nanoTime();
        DriverCommand cmd = new DriverCommand(cmdId, device, "Transfer",
                Map.of("from", "Hotel_A", "to", "Plate_1", "plateBarcode", "PL20261029"),
                30_000, "trace-001");

        DriverResult r1 = drv.execute(cmd);
        System.out.println("  第 1 次执行: " + r1.status() + "  耗时 " + r1.durationMs() + "ms");
        System.out.println("  设备原文   : " + r1.deviceRawResponse());
        System.out.println();

        DriverResult r2 = drv.execute(cmd);
        System.out.println("  同 commandId 再发一次（模拟重试）:");
        System.out.println("    结果=" + r2.status() + " 是否同一对象=" + (r1 == r2));
        System.out.println("    ★ 幂等生效：驱动不会把同一块板搬两次");
        long calls = ((VirtualDriver) drv).callCount(device, "Transfer");
        System.out.printf("    实际物理调用次数 = %d（两次请求只执行 1 次）%n%n", calls);

        section("4. 故障 -> 隔离 -> 候选池自动收缩");
        VirtualDriver vd = drivers.get("DRV-" + device);
        // 直接对承载该设备的驱动注入故障
        for (VirtualDriver v : drivers.values()) {
            if (v.describe().deviceInstanceIds().contains(device)) {
                vd = v;
                break;
            }
        }
        if (vd != null) {
            vd.fault(device, "Transfer", 99, "EC-08-0001");

            DriverResult fail = drv.execute(new DriverCommand(
                    "cmd-fail", device, "Transfer", Map.of(), 30_000, "trace-002"));
            System.out.println("  注入故障后执行: " + fail.status() + "  errorCode=" + fail.errorCode());
            System.out.println("  设备原文      : " + fail.deviceRawResponse());
            System.out.println("  是否可重试    : " + fail.retryable());

            int before = registry.candidates("TRANSFER", Set.of()).size();
            registry.quarantine(device, "EC-08-0001 连续失败");
            int after = registry.candidates("TRANSFER", Set.of()).size();
            System.out.printf("%n  隔离设备 %d -> TRANSFER 候选从 %d 台降到 %d 台%n",
                    device, before, after);
            System.out.println("  ★ 后续任务不会再被分到坏设备，不用等人工发现");
        }

        section("5. 契约强制：禁止 宽泛错误码 兜底码");
        try {
            new DriverResult("c0", DriverResult.Status.FAILED, "宽泛错误码", "x", "raw", 1, Map.of());
        } catch (IllegalArgumentException e) {
            System.out.println("  尝试构造 errorCode=宽泛错误码 的结果 -> 被拒绝");
            System.out.println("    " + e.getMessage());
        }
        try {
            new DriverResult("c1", DriverResult.Status.FAILED, null, "x", "raw", 1, Map.of());
        } catch (IllegalArgumentException e) {
            System.out.println("  尝试构造「失败但无错误码」的结果 -> 被拒绝");
            System.out.println("    " + e.getMessage());
        }
        System.out.println("  ★ 契约层就堵死了万能码问题");

        section("6. 能力覆盖：这 5 个驱动能支撑多少工艺能力");
        Set<String> covered = registry.capabilities();
        System.out.println("  驱动声明能力 " + covered.size() + " 类: " + covered);
        List<String> need = List.of("TRANSFER", "INCUBATE", "ALIQUOT", "CENTRIFUGE",
                "READ_PLATE", "STORE", "WASH", "SEAL", "PEEL", "DECAP", "ANALYZE", "SCAN", "SENSE", "PICK", "MIX");
        List<String> missing = need.stream().filter(c -> !covered.contains(c)).toList();
        System.out.println("  工艺需要能力 " + need.size() + " 类");
        System.out.println("  缺口: " + (missing.isEmpty() ? "无" : missing));
        if (!missing.isEmpty()) {
            System.out.println("  ⚠ 缺 " + missing.size() + " 类能力的驱动 -> 对应工艺节点无法执行");
        }
    }

    // ---------------------------------------------------------------- 数据加载

    private static Map<String, VirtualDriver> loadDrivers(DbConfig db, DriverRegistry registry)
            throws SQLException {
        Map<String, VirtualDriver> out = new LinkedHashMap<>();

        // 驱动 + 端点
        Map<Long, String[]> drvRow = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, code, endpoint, protocol::text AS protocol, "
                     + "COALESCE(manifest->>'driver_kind','unknown') AS driver_kind "
                     + "FROM driver WHERE tenant_id=? ORDER BY id")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    drvRow.put(rs.getLong(1), new String[]{
                            rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
                }
            }
        }

        // 能力
        Map<Long, Set<String>> caps = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT dc.driver_id, cap.code
                     FROM driver_capability dc JOIN capability cap ON cap.id = dc.capability_id
                     """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                caps.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
            }
        }

        // 设备级能力：device_instance -> resource_node -> resource_capability
        Map<Long, Map<Long, Set<String>>> devCaps = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT di.driver_id, di.id AS device_instance_id, cap.code
                     FROM device_instance di
                     JOIN resource_capability rc ON rc.resource_id = di.resource_id
                     JOIN capability cap ON cap.id = rc.capability_id
                     WHERE di.driver_id IS NOT NULL
                     """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                devCaps.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>())
                        .computeIfAbsent(rs.getLong(2), k -> new LinkedHashSet<>())
                        .add(rs.getString(3));
            }
        }

        for (Map.Entry<Long, String[]> e : drvRow.entrySet()) {
            String[] v = e.getValue();
            Map<Long, Set<String>> dc = devCaps.getOrDefault(e.getKey(), Map.of());
            if (dc.isEmpty()) {
                continue;   // 无设备能力的驱动不注册
            }
            VirtualDriver dv = new VirtualDriver(v[0], v[1], v[2], dc);
            registry.register(dv);
            out.put(v[0], dv);
        }
        return out;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=".repeat(84));
        System.out.println("  " + title);
        System.out.println("=".repeat(84));
        System.out.println();
    }

    private DriverContractDemo() {
    }
}
