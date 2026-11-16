package com.labos.kernel.device;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.DeviceStateMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备业务状态（客户文档 §1.5）**读时派生**的连库契约自测（2026-11-16）。
 *
 * <p><b>测什么</b>：
 * <ol>
 *   <li>字典口径：分组键是**能力**（EXTRACT/WASH/…），
 *       每类有且仅有一个"静止常态"（is_idle），触发方式取值合法；</li>
 *   <li><b>派生优先级</b>：故障 &gt; 在跑的命令 &gt; 更晚的设备事件 &gt; 已结束的命令 &gt; 静止常态；</li>
 *   <li><b>变异测试</b>：改字典里的 {@code source_key} ⇒ 派生结果跟着变
 *       ⇒ 证明状态是**从配置派生的**，不是写死在代码里的。</li>
 * </ol>
 *
 * <p>只造**自造数据**（负数 id / ZZTEST 前缀），跑完清理 ⇒ 不碰现场数据。
 */
public final class DeviceBusinessStateDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;

    /** 自造：岛 / 设备节点 / 实例 / 能力映射 / 命令 / 事件。 */
    private static final long TEST_ISLAND = -998821L;
    private static final long TEST_NODE = -998822L;
    private static final long TEST_INST = -998823L;
    private static final long TEST_RUN = -998824L;
    private static final long TEST_CODE = -998825L;
    private static final long TEST_NODE_ID = -998826L;
    private static final String TEST_CODE_STR = "ZZTEST-BIZ-EXTRACT";
    /** 必须用**字典里真有的**方法名**：EXTRACT 的 operating 态 source_key 含 Incubate。 */
    private static final String TEST_METHOD = "Incubate";

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        System.out.println("════ 设备业务状态 · 读时派生 · 连库自测 ════");
        System.out.println();
        cleanup();
        try {
            seed();
            testDictionary();
            testDerivation();
            testMutation();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 字典口径

    private static void testDictionary() {
        System.out.println("── 1. 字典口径 ──");
        DeviceStateMapper m = Mappers.get(DeviceStateMapper.class);
        List<Map<String, Object>> defs =
                m.selectBusinessStateDefs(1);
        Map<String, Integer> idleCount = new LinkedHashMap<>();
        for (Map<String, Object> d : defs) {
            String kind = String.valueOf(d.get("deviceKind"));
            String trigger = String.valueOf(d.get("trigger"));
            if (!List.of("command", "command_done", "event", "fault", "idle", "derived")
                    .contains(trigger)) {
                bad("trigger 取值非法：" + kind + "/" + d.get("stateCode") + " = " + trigger);
            }
            if (Boolean.TRUE.equals(d.get("isIdle"))) {
                idleCount.merge(kind, 1, Integer::sum);
            }
        }
        eq("字典条目总数", 22, defs.size());
        // 每类设备有且仅有一个静止常态
        // ⚠ **派生型（STORE）例外**：它的三个态全是按板数算出来的（充足/预警/不足），
        //   没有"静止常态"这个概念 ⇒ 不要求 is_idle。
        for (String kind : List.of("EXTRACT", "WASH", "MIX", "TRANSFER")) {
            eq("「静止常态」每类恰好一个：" + kind, 1, idleCount.getOrDefault(kind, 0));
        }
        eq("派生型（STORE）没有静止常态", 0, idleCount.getOrDefault("STORE", 0));
        // 分组键必须是**能力口径**（不是 device.device_type 的厂商分类）
        boolean allCapability = defs.stream().allMatch(d ->
                List.of("EXTRACT", "WASH", "MIX", "TRANSFER", "STORE")
                        .contains(String.valueOf(d.get("deviceKind"))));
        check("分组键都是能力口径", allCapability);
    }

    // ---------------------------------------------------------------- 派生优先级

    private static void testDerivation() throws Exception {
        System.out.println();
        System.out.println("── 2. 派生优先级 ──");
        DeviceBusinessStateResolver r = new DeviceBusinessStateResolver(db, 1);

        // ② 在跑的命令 ⇒ command 态
        insertCommand("running", null);
        Map<Long, DeviceBusinessStateResolver.DeviceBusinessState> m = r.resolve(null);
        DeviceBusinessStateResolver.DeviceBusinessState s = m.get(TEST_INST);
        eq("在跑的命令 ⇒ command 态", "operating", s == null ? null : s.stateCode());

        // ③ 命令结束 + 更晚的事件 ⇒ event 态
        updateCommand("completed");
        insertEvent("waiting_interaction", true);
        m = r.resolve(null);
        s = m.get(TEST_INST);
        eq("更晚的设备事件 ⇒ event 态（等待交互）", "waiting_interaction",
                s == null ? null : s.stateCode());

        // ④ 命令结束、无事件 ⇒ 回落到静止常态
        //   ⚠ EXTRACT **没有** command_done 态（客户 §1.5 的提取仪只有"流程结束"，
        //   那是**事件**驱动的）⇒ 这一格对提取仪就是"空闲"。
        //   「分液完成」那种 command_done 态在 **WASH** 组（分液器）上。
        deleteEvents();
        m = r.resolve(null);
        s = m.get(TEST_INST);
        eq("命令结束、无事件 ⇒ 回落静止常态（提取仪无 command_done 态）", "idle",
                s == null ? null : s.stateCode());

        // ⑤ 什么都没有 ⇒ 静止常态
        deleteCommands();
        m = r.resolve(null);
        s = m.get(TEST_INST);
        eq("什么都没有 ⇒ 静止常态（空闲）", "idle", s == null ? null : s.stateCode());

        // ① 故障优先
        insertCommand("running", null);
        setDeviceError("ZZTEST-故障");
        m = r.resolve(null);
        s = m.get(TEST_INST);
        eq("故障优先于一切", "fault", s == null ? null : s.stateCode());
        setDeviceError(null);
    }

    // ---------------------------------------------------------------- 变异测试

    /**
     * 变异测试：把字典里的 {@code source_key} 改掉 ⇒ 派生结果必须跟着变。
     *
     * <p>不改的话，说明派生是**写死在代码里**的（而不是从配置来）⇒ 配置改了不生效。
     */
    private static void testMutation() throws Exception {
        System.out.println();
        System.out.println("── 3. 变异测试（改配置必须变） ──");
        DeviceStateMapper m = Mappers.get(DeviceStateMapper.class);

        // 先记下原来的 source_key
        String original = null;
        for (Map<String, Object> d : m.selectBusinessStateDefs(1)) {
            if ("EXTRACT".equals(d.get("deviceKind")) && "operating".equals(d.get("stateCode"))) {
                original = String.valueOf(d.get("sourceKey"));
            }
        }
        check("拿到 operating 的原始 source_key", original != null);

        // 变异：把方法名换成一个不存在的
        exec("UPDATE device_business_state SET source_key = 'ZZTEST_NO_SUCH_METHOD' "
                + "WHERE tenant_id = 1 AND device_kind = 'EXTRACT' AND state_code = 'operating'");
        try {
            insertCommand("running", null);
            DeviceBusinessStateResolver r = new DeviceBusinessStateResolver(db, 1);
            Map<Long, DeviceBusinessStateResolver.DeviceBusinessState> m2 = r.resolve(null);
            DeviceBusinessStateResolver.DeviceBusinessState s = m2.get(TEST_INST);
            eq("改了 source_key ⇒ 不再命中该状态（回落到静止常态）", "idle",
                    s == null ? null : s.stateCode());
            deleteCommands();
        } finally {
            // 复原
            if (original != null) {
                exec("UPDATE device_business_state SET source_key = '" + original
                        + "' WHERE tenant_id = 1 AND device_kind = 'EXTRACT' "
                        + "AND state_code = 'operating'");
            }
        }
        // 复原后再验一次 ⇒ 证明复原有效（不是"碰巧绿"）
        insertCommand("running", null);
        DeviceBusinessStateResolver r = new DeviceBusinessStateResolver(db, 1);
        Map<Long, DeviceBusinessStateResolver.DeviceBusinessState> m3 = r.resolve(null);
        DeviceBusinessStateResolver.DeviceBusinessState s = m3.get(TEST_INST);
        eq("复原后 ⇒ 又命中 operating", "operating", s == null ? null : s.stateCode());
        deleteCommands();
    }

    // ---------------------------------------------------------------- 造数/清理

    private static void seed() throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind, path, sort_order) "
                            + "VALUES (?, 1, NULL, 'ZZTEST-BIZ-ISLAND', 'ZZTEST 自测岛', 'island', 'ZZTEST-BIZ-ISLAND', 100)")) {
                ps.setLong(1, TEST_ISLAND);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind, path, sort_order) "
                            + "VALUES (?, 1, ?, 'ZZTEST-BIZ-DEV', 'ZZTEST 自测设备', 'device', 'ZZTEST-BIZ-ISLAND/ZZTEST-BIZ-DEV', 100)")) {
                ps.setLong(1, TEST_NODE);
                ps.setLong(2, TEST_ISLAND);
                ps.executeUpdate();
            }
            // 能力映射：EXTRACT
            long capId = capabilityId("EXTRACT");
            // device 台账行（device_instance.device_id 外键指向它）
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO device (id, tenant_id, code, name, model, vendor, device_type) "
                            + "VALUES (?, 1, 'ZZTEST-BIZ-DEV', 'ZZTEST 自测设备', 'ZZTEST-M', 'ZZTEST', 'Extractors')")) {
                ps.setLong(1, TEST_NODE);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO resource_capability (resource_id, capability_id) VALUES (?, ?)")) {
                ps.setLong(1, TEST_NODE);
                ps.setLong(2, capId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO device_instance (id, tenant_id, code, name, device_id, resource_id, status) "
                            + "VALUES (?, 1, 'ZZTEST-BIZ-DEV', 'ZZTEST 自测设备', ?, ?, 'available')")) {
                ps.setLong(1, TEST_INST);
                ps.setLong(2, TEST_NODE);
                ps.setLong(3, TEST_NODE);
                ps.executeUpdate();
            }
            // device_state 行（故障判定要用）
            exec("INSERT INTO device_state (tenant_id, resource_id, status, online) "
                    + "VALUES (1, " + TEST_NODE + ", 'idle', true)");
            // 一条"活着的" run（running 命令要挂在未结束的 run 上）
            exec("INSERT INTO run (id, tenant_id, run_no, run_type, status) "
                    + "VALUES (" + TEST_RUN + ", 1, 'ZZTEST-BIZ-RUN', 'workflow', 'running')");
            // run_node 行（run_command.run_node_id 外键指向它）
            exec("INSERT INTO run_node (id, tenant_id, run_id, node_key, name, node_type, status) "
                    + "VALUES (" + TEST_NODE_ID + ", 1, " + TEST_RUN + ", 'ZZTEST-NODE', "
                    + "'ZZTEST 节点', 'task', 'pending')");
        }
    }

    private static long capabilityId(String code) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM capability WHERE tenant_id = 1 AND code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("找不到能力：" + code);
                }
                return rs.getLong(1);
            }
        }
    }

    private static void insertCommand(String status, String errorCode) throws Exception {
        exec("DELETE FROM run_command WHERE id = " + TEST_CODE);
        exec("INSERT INTO run_command (id, tenant_id, run_id, run_node_id, command_seq, method, "
                + "payload, status, attempt, sent_at, device_id) "
                + "VALUES (" + TEST_CODE + ", 1, " + TEST_RUN + ", " + TEST_NODE_ID + ", 1, '"
                + TEST_METHOD + "', '{}', '" + status + "', 1, now(), " + TEST_INST + ")");
    }

    private static void updateCommand(String status) throws Exception {
        exec("UPDATE run_command SET status = '" + status + "', finished_at = now() "
                + "WHERE id = " + TEST_CODE);
    }

    private static void deleteCommands() throws Exception {
        exec("DELETE FROM run_command WHERE id = " + TEST_CODE);
    }

    /** 造一条设备事件（{@code later=true} 时把时间推到命令之后）。 */
    private static void insertEvent(String eventType, boolean later) throws Exception {
        exec("INSERT INTO device_event (tenant_id, device_code, event_type, payload, source_event_id, created_at) "
                + "VALUES (1, 'ZZTEST-BIZ-DEV', '" + eventType + "', '{}', 'ZZTEST-EVT-"
                + System.nanoTime() + "', " + (later ? "now() + interval '1 second'" : "now()") + ")");
    }

    private static void deleteEvents() throws Exception {
        exec("DELETE FROM device_event WHERE device_code = 'ZZTEST-BIZ-DEV'");
    }

    private static void setDeviceError(String err) throws Exception {
        exec("UPDATE device_state SET last_error = " + (err == null ? "NULL" : "'" + err + "'")
                + " WHERE tenant_id = 1 AND resource_id = " + TEST_NODE);
    }

    private static void cleanup() throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM device_event WHERE device_code = 'ZZTEST-BIZ-DEV'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM run_command WHERE id = " + TEST_CODE)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM run_node WHERE id = " + TEST_NODE_ID)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM run WHERE id = " + TEST_RUN)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM device_state WHERE tenant_id = 1 AND resource_id = " + TEST_NODE)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM resource_capability WHERE resource_id = " + TEST_NODE)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM device_instance WHERE id = " + TEST_INST)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM device WHERE id = " + TEST_NODE)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM resource_node WHERE id IN (" + TEST_NODE + ", " + TEST_ISLAND + ")")) {
                ps.executeUpdate();
            }
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- 断言

    private static void ok(String what) {
        pass++;
        System.out.println("  ✔ " + what);
    }

    private static void bad(String what) {
        fail++;
        System.out.println("  ✘ " + what);
    }

    private static void check(String what, boolean cond) {
        if (cond) {
            ok(what);
        } else {
            bad(what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            ok(what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }
}
