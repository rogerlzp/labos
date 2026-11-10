package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 板子归属校验自测（需要真实库）。
 *
 * <p><b>为什么要有这条</b>：位置上的板归谁放上去的，记在
 * {@code position_state.occupied_by_run}。取板必须只取**自己的**板。
 *
 * <p>以前这条约束是**隐含**的 —— 靠「岛级互斥」保证：岛内同时只有一个实验在跑，
 * 所以设备上的板必然是当前实验自己的。岛内并行一放开，这个隐含保证就没了：
 * A 把板放进孵箱，B 对同一台设备做 Withdraw，就会把 A 的板取走。
 *
 * <p>覆盖：
 * <ol>
 *   <li>取自己放上去的板 → 取得到，位置清空；</li>
 *   <li>取别的 run 放上去的板 → **取不到**，对方的板原封不动；</li>
 *   <li>归属读不到（occupied_by_run 为空）→ 取不到（fail-closed）；</li>
 *   <li>设备上同时有「自己的板」和「别人的板」→ 只取自己的那个。</li>
 * </ol>
 */
public final class PlateOwnershipDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    // 设备（父节点）与两个层位（子节点）
    private static final long DEV = -996001L;
    private static final long POS_A = -996002L;   // 自己的板
    private static final long POS_B = -996003L;   // 别人的板
    private static final long POS_C = -996004L;   // 归属不明的板

    private static final long RUN_MINE = -996101L;
    private static final long RUN_OTHER = -996102L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        seed();

        PositionLock lock = new PositionLock(db, TENANT);

        // ① 取自己的板：取得到
        int taken = lock.takePlate(POS_A, RUN_MINE);
        check("取自己放上去的板 → 取到 1 行", taken == 1, taken);
        check("取走后位置无板", plateOf(POS_A) == null, plateOf(POS_A));
        check("取走后归属清空", ownerOf(POS_A) == null, ownerOf(POS_A));

        // ② 取别人的板：取不到，对方板原封不动
        int stolen = lock.takePlate(POS_B, RUN_MINE);
        check("取别的 run 的板 → 取不到（0 行）", stolen == 0, stolen);
        check("别人的板还在原位", "PLATE-B".equals(plateOf(POS_B)), plateOf(POS_B));
        check("别人的归属没被改", ownerOf(POS_B) == RUN_OTHER, ownerOf(POS_B));

        // ③ 归属读不到 → fail-closed，取不到
        int unknown = lock.takePlate(POS_C, RUN_MINE);
        check("归属读不到 → 取不到（fail-closed）", unknown == 0, unknown);
        check("归属不明的板还在原位", "PLATE-C".equals(plateOf(POS_C)), plateOf(POS_C));

        // ④ 设备上同时有「自己的板」和「别人的板」→ 只取自己的
        //    重新给自己放一块，再走 notePlateMove 的 withdraw 路径
        putPlate(POS_A, "PLATE-A2", RUN_MINE);
        putPlate(POS_B, "PLATE-B", RUN_OTHER);
        Long moved = lock.notePlateMove(deviceInstanceOf(DEV), "Withdraw", null, RUN_MINE);
        check("withdraw 只取自己的板（位置 A）", moved != null && moved == POS_A, moved);
        check("自己的板被取走", plateOf(POS_A) == null, plateOf(POS_A));
        check("别人的板没被动", "PLATE-B".equals(plateOf(POS_B)), plateOf(POS_B));

        // ⑤ 只有别人的板时 → 取不到（NO_PLATE_TO_TAKE）
        Long none = lock.notePlateMove(deviceInstanceOf(DEV), "Withdraw", null, RUN_MINE);
        check("只有别人的板 → withdraw 取不到", none != null && none == PositionLock.NO_PLATE_TO_TAKE, none);
        check("别人的板仍在", "PLATE-B".equals(plateOf(POS_B)), plateOf(POS_B));

        cleanup();
        // 汇总行必须是 test-all.sh 能解析的「通过 N, 失败 M」格式：
        // 原来打印「全部通过 13 / 0」，脚本 grep 不到 → 每轮都被记成
        // "无法解析输出"（2026-11-10 实测：单跑 13/0 但全量里恒为红）。
        System.out.println((fail == 0 ? "全部通过：" : "有失败：")
                + pass + " / " + fail);
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ seed

    private static void seed() throws Exception {
        try (Connection c = db.open()) {
            // 设备节点 + 两个层位节点
            ins(c, "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind)"
                    + " VALUES (?,?,NULL,?,?,'device')", DEV, TENANT, "SELF-TEST-DEV", "自测设备");
            ins(c, "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind)"
                    + " VALUES (?,?,?,?,?,'incubator')", POS_A, TENANT, DEV, "SELF-TEST-POS-A", "自测层位A");
            ins(c, "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind)"
                    + " VALUES (?,?,?,?,?,'incubator')", POS_B, TENANT, DEV, "SELF-TEST-POS-B", "自测层位B");
            ins(c, "INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind)"
                    + " VALUES (?,?,?,?,?,'incubator')", POS_C, TENANT, DEV, "SELF-TEST-POS-C", "自测层位C");
            // device_instance（notePlateMove 按 deviceInstanceId 反查 resource_id）
            ins(c, "INSERT INTO device_instance (id, tenant_id, resource_id, code, name)"
                    + " VALUES (?,?,?,?,?)", -996011L, TENANT, DEV, "SELF-TEST-INST", "自测实例");

            // 位置状态：自己的板 / 别人的板 / 归属不明的板
            ins(c, "INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_since,"
                    + " occupied_by_run, updated_at) VALUES (?,?,?,now(),?,now())",
                    TENANT, POS_A, "PLATE-A", RUN_MINE);
            ins(c, "INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_since,"
                    + " occupied_by_run, updated_at) VALUES (?,?,?,now(),?,now())",
                    TENANT, POS_B, "PLATE-B", RUN_OTHER);
            ins(c, "INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_since,"
                    + " updated_at) VALUES (?,?,?,now(),now())",
                    TENANT, POS_C, "PLATE-C");
        }
    }

    private static void cleanup() throws Exception {
        try (Connection c = db.open()) {
            for (long id : new long[]{POS_A, POS_B, POS_C}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM position_state WHERE tenant_id=? AND resource_id=?")) {
                    ps.setLong(1, TENANT);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM device_instance WHERE id=?")) {
                ps.setLong(1, -996011L);
                ps.executeUpdate();
            }
            for (long id : new long[]{POS_A, POS_B, POS_C, DEV}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM resource_node WHERE tenant_id=? AND id=?")) {
                    ps.setLong(1, TENANT);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static void ins(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void putPlate(long pos, String plate, long runId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE position_state
                     SET plate_id = ?, occupied_since = now(), occupied_by_run = ?, updated_at = now()
                     WHERE tenant_id = ? AND resource_id = ?
                     """)) {
            ps.setString(1, plate);
            ps.setLong(2, runId);
            ps.setLong(3, TENANT);
            ps.setLong(4, pos);
            ps.executeUpdate();
        }
    }

    private static String plateOf(long pos) {
        return str("SELECT plate_id FROM position_state WHERE tenant_id=" + TENANT
                + " AND resource_id=" + pos);
    }

    private static Long ownerOf(long pos) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT occupied_by_run FROM position_state WHERE tenant_id=? AND resource_id=?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, pos);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static Long deviceInstanceOf(long resourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM device_instance WHERE tenant_id=? AND resource_id=?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static String str(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String v = rs.getString(1);
                return v == null ? null : v;
            }
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
        return null;
    }

    private static void check(String what, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + what);
        } else {
            fail++;
            System.out.println("  ✗ " + what + "（实际：" + actual + "）");
        }
    }

    private PlateOwnershipDbSelfTest() {
    }
}
