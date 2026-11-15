package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * P1 步骤 4 · 精确点位的连库判别性自测。
 *
 * <p>验证三点：
 * <ol>
 *   <li>{@code selectPositionResourceBySpec} 能按 slot_index / code / position_label 解析位置；</li>
 *   <li>{@code reserveExplicitPosition} 显式预留成功；</li>
 *   <li><b>反例</b>：指定位置已被别的 run 占用 ⇒ 返回 null（拒绝换位），不存在 ⇒ null。</li>
 * </ol>
 */
public final class ExplicitPositionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long DEV = -997001L;      // 设备
    private static final long POS1 = -997002L;     // 槽位 1
    private static final long POS2 = -997003L;     // 槽位 2
    private static final long DP1 = -997011L;      // device_point 1
    private static final long DP2 = -997012L;      // device_point 2
    private static final long RUN_A = -997021L;
    private static final long RUN_B = -997022L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();

            PositionLock lock = new PositionLock(db, TENANT);

            section("1. selectPositionResourceBySpec 解析");
            Long bySlot = Mappers.get(com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionResourceBySpec(TENANT, DEV, "1");
            eq("slot_index=1 → POS1", POS1, bySlot);
            Long byCode = Mappers.get(com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionResourceBySpec(TENANT, DEV, "VERIFY-EXPL-P1");
            eq("code → POS1", POS1, byCode);

            section("2. reserveExplicitPosition 显式预留成功");
            Long got = lock.reserveExplicitPosition(DEV, "2", RUN_A);
            eq("预留槽位 2 → POS2", POS2, got);

            section("3. 反例：占用即拒绝换位");
            Long occupied = lock.reserveExplicitPosition(DEV, "2", RUN_B);
            eq("槽位 2 已被 RUN_A 占用 → null（不换位）", null, occupied);

            section("4. 反例：不存在 → null");
            Long missing = lock.reserveExplicitPosition(DEV, "999", RUN_A);
            eq("槽位 999 不存在 → null", null, missing);
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void seed() throws SQLException {
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'VERIFY-EXPL-DEV', '验证设备', 'device')", DEV, TENANT);
        insertPoint(POS1, DP1, "VERIFY-EXPL-P1", 1);
        insertPoint(POS2, DP2, "VERIFY-EXPL-P2", 2);
    }

    private static void insertPoint(long resId, long dpId, String code, int slotIndex)
            throws SQLException {
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind, parent_id) "
                + "VALUES (?, ?, ?, ?, 'storage', ?)", resId, TENANT, code, code, DEV);
        exec("INSERT INTO device_point (id, tenant_id, device_resource_id, point_resource_id, "
                + "point_role, robot_reachable, is_entry, is_exit, point_kind, slot_index) "
                + "VALUES (?, ?, ?, ?, 'both', true, true, true, 'nest', ?)",
                dpId, TENANT, DEV, resId, slotIndex);
    }

    private static void cleanup() {
        del("position_state", "DELETE FROM position_state WHERE resource_id IN (?, ?)", POS1, POS2);
        del("device_point", "DELETE FROM device_point WHERE id IN (?, ?)", DP1, DP2);
        del("resource_node", "DELETE FROM resource_node WHERE id IN (?, ?, ?)", POS1, POS2, DEV);
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void del(String label, String sql, Object... args) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                System.out.println("  [清理] " + label + " 失败：" + e.getMessage().replace('\n', ' '));
            }
        } catch (SQLException e) {
            System.out.println("  [清理] " + label + " 连接失败：" + e.getMessage());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (java.util.Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✓ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }

    private ExplicitPositionDbSelfTest() {
    }
}
