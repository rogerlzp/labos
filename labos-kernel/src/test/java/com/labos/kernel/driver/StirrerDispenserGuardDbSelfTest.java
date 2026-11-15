package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 C · L4 搅拌—分液互锁 运行时守卫 连库判别性自测。
 *
 * <p>验证：分液命令下发前查配对搅拌单元；故障 → 拒绝 + EC-INTERLOCK-STIRRER；
 * 可用 → 放行；未配对/非分液方法 → 不拦。
 */
public final class StirrerDispenserGuardDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = 9990001L;
    private static final long STIRRER = 9990011L;
    private static final long DISPENSER = 9990012L;
    private static final long OTHER = 9990013L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. 搅拌故障 → 分液拒绝 + EC-INTERLOCK-STIRRER");
            DriverResult r = StirrerDispenserGuard.checkBeforeDispense(db, TENANT, RUN,
                    DISPENSER, "Aliquot");
            eq("拒绝（非 null）", true, r != null);
            eq("错误码 EC-INTERLOCK-STIRRER", "EC-INTERLOCK-STIRRER",
                    r == null ? null : r.errorCode());

            section("2. 搅拌可用 + 新鲜 → 放行");
            updateStirrer("idle");
            eq("放行（null）", null,
                    StirrerDispenserGuard.checkBeforeDispense(db, TENANT, RUN,
                            DISPENSER, "Aliquot"));

            section("3. 反例：未配对分液器 → 不拦（null）");
            eq("未配对 → null", null,
                    StirrerDispenserGuard.checkBeforeDispense(db, TENANT, RUN,
                            OTHER, "Aliquot"));

            section("4. 反例：非分液方法 → 不拦（null）");
            updateStirrer("error");
            eq("Home 方法 → null", null,
                    StirrerDispenserGuard.checkBeforeDispense(db, TENANT, RUN,
                            DISPENSER, "Home"));
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
                + "VALUES (?, ?, 'EV-STIRRER', '搅拌', 'device')", STIRRER, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-DISPENSER', '分液器', 'device')", DISPENSER, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-OTHER', '其它', 'device')", OTHER, TENANT);
        exec("INSERT INTO stirrer_dispenser_pair (tenant_id, stirrer_resource_id, dispenser_resource_id) "
                + "VALUES (?, ?, ?)", TENANT, STIRRER, DISPENSER);
        updateStirrer("error");
    }

    private static void updateStirrer(String status) throws SQLException {
        exec("INSERT INTO device_state (tenant_id, resource_id, status) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id, resource_id) DO UPDATE SET status = EXCLUDED.status, updated_at = now()",
                TENANT, STIRRER, status);
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

    private static void cleanup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM stirrer_dispenser_pair WHERE tenant_id = ? AND dispenser_resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, DISPENSER);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] pair 失败：" + e.getMessage());
        }
        for (long id : new long[]{STIRRER, DISPENSER, OTHER}) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM device_state WHERE tenant_id = ? AND resource_id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] state " + id + " 失败：" + e.getMessage());
            }
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM resource_node WHERE tenant_id = ? AND id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] node " + id + " 失败：" + e.getMessage());
            }
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✓ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }

    private StirrerDispenserGuardDbSelfTest() {
    }
}
