package com.labos.kernel.device;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

/**
 * 「设备编号三处同步改」的连库自测（2026-11-15）。
 *
 * <p><b>为什么必须测</b>：设备编号是**流程引用的键** ——
 * {@code __awaitEvent} 的 device 参数、点位编码（{@code P_AUTO-1-EXT}）、
 * 客户现场口语（"EXT2 报等了"）全靠它。
 *
 * <p>改编号要同步**三处**：
 * <ol>
 *   <li>{@code resource_node.code} —— 资源树 / 机械臂选点</li>
 *   <li>{@code resource_node.path} —— 层级路径</li>
 *   <li>{@code device_instance.code} —— 设备清单 / 侧车对端</li>
 * </ol>
 * 只改一处 ⇒ **一半系统认新编号、一半认旧的** ⇒ 现场表现为"设备找不到"，
 * 而且**不报错**。
 *
 * <p>连库跑（要真改库才验得了三处是否同步），全部用**自建的自造资源**，
 * 跑完清理 ⇒ 不碰现场数据。
 */
public final class DeviceCodeSyncDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;

    /** 自造：岛 / 设备节点 / 实例 —— 编号与真实设备不冲突（负数）。 */
    private static final long TEST_ISLAND = -998801L;
    private static final long TEST_NODE = -998802L;
    private static final long TEST_INST = -998803L;
    private static final String OLD_CODE = "ZZTEST-OLD-1";
    private static final String NEW_CODE = "ZZTEST-NEW-1";

    public static void main(String[] args) throws Exception {
        com.labos.kernel.support.TestSpringSupport.db();
        db = DbConfig.fromEnv();
        System.out.println("════ 设备编号三处同步 · 连库自测 ════");
        System.out.println();
        cleanup();
        try {
            seed();
            testSync();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 造数

    private static void seed() throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind, path, sort_order) "
                            + "VALUES (?, 1, NULL, ?, ?, 'island', ?, 100)")) {
                ps.setLong(1, TEST_ISLAND);
                ps.setString(2, "ZZTEST-ISLAND");
                ps.setString(3, "ZZTEST 自测岛");
                ps.setString(4, "ZZTEST-ISLAND");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind, path, sort_order) "
                            + "VALUES (?, 1, ?, ?, ?, 'device', ?, 100)")) {
                ps.setLong(1, TEST_NODE);
                ps.setLong(2, TEST_ISLAND);
                ps.setString(3, OLD_CODE);
                ps.setString(4, "ZZTEST 设备");
                ps.setString(5, "ZZTEST-ISLAND/" + OLD_CODE);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO device_instance (id, tenant_id, code, name, resource_id) "
                            + "VALUES (?, 1, ?, ?, ?)")) {
                ps.setLong(1, TEST_INST);
                ps.setString(2, OLD_CODE);
                ps.setString(3, "ZZTEST 设备");
                ps.setLong(4, TEST_NODE);
                ps.executeUpdate();
            }
        }
        System.out.println("已造：岛 " + TEST_ISLAND + " / 设备节点 " + TEST_NODE
                + " / 实例 " + TEST_INST + "（旧编号 " + OLD_CODE + "）");
        System.out.println();
    }

    // ---------------------------------------------------------------- 验证

    private static void testSync() throws Exception {
        System.out.println("── 三处同步改");
        DeviceMapper mapper = Mappers.get(DeviceMapper.class);

        // 改编号：resource_node.code + path 与 device_instance.code 必须一起改
        String islandPath = mapper.selectIslandPathOfDevice(1L, TEST_NODE);
        ok("能查到设备所属岛的层级路径", islandPath != null && !islandPath.isBlank());

        int n1 = mapper.updateDeviceCode(1L, TEST_NODE, NEW_CODE,
                (islandPath == null ? "" : islandPath) + "/" + NEW_CODE);
        int n2 = mapper.updateDeviceInstanceCode(1L, TEST_NODE, NEW_CODE);
        eq("resource_node 改 1 行", 1, n1);
        eq("device_instance 改 1 行", 1, n2);

        String nodeCode = one("SELECT code FROM resource_node WHERE id = " + TEST_NODE);
        String nodePath = one("SELECT path FROM resource_node WHERE id = " + TEST_NODE);
        String instCode = one("SELECT code FROM device_instance WHERE id = " + TEST_INST);
        eq("① resource_node.code 已改", NEW_CODE, nodeCode);
        eq("② resource_node.path 已改（含新编号）",
                (islandPath == null ? "" : islandPath) + "/" + NEW_CODE, nodePath);
        eq("③ device_instance.code 已改", NEW_CODE, instCode);
        ok("三处编号一致（不会一半认新一半认旧）",
                NEW_CODE.equals(nodeCode) && NEW_CODE.equals(instCode));

        System.out.println();
        System.out.println("── 编号唯一性：撞号要能被查出来");
        // existsDeviceCode 是 service 层的前置校验依据
        boolean exists = mapper.existsDeviceCode(1L, NEW_CODE);
        ok("existsDeviceCode 能查到刚改的编号", exists);
        boolean notExists = mapper.existsDeviceCode(1L, "ZZTEST-NOT-EXIST-XYZ");
        ok("不存在的编号查不到", !notExists);
    }

    // ---------------------------------------------------------------- 工具

    private static String one(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void cleanup() {
        exec("DELETE FROM device_instance WHERE id = " + TEST_INST);
        exec("DELETE FROM resource_node WHERE id = " + TEST_NODE);
        exec("DELETE FROM resource_node WHERE id = " + TEST_ISLAND);
    }

    private static void exec(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            // 清理失败不掩盖正文结果
        }
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }
}
