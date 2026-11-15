package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceFactMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

/**
 * P2 阶段 C · L4 恢复前重新取证 + 设备事实重连重查 连库判别性自测。
 */
public final class L4RecoveryAndReconnectDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = 9994001L;
    private static final long STIRRER = 9994011L;
    private static final long DISPENSER = 9994012L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. L4 恢复前重新取证：故障 → 不允许恢复");
            updateStirrer("error");
            eq("error → isRecovered=false", false,
                    StirrerDispenserGuard.isRecovered(db, TENANT, RUN, DISPENSER));

            section("2. 故障清除 + 新鲜 → 允许恢复");
            updateStirrer("idle");
            eq("idle+fresh → isRecovered=true", true,
                    StirrerDispenserGuard.isRecovered(db, TENANT, RUN, DISPENSER));

            section("3. 反例：未配对 → 不允许恢复");
            eq("未配对 → false", false,
                    StirrerDispenserGuard.isRecovered(db, TENANT, RUN, STIRRER));

            section("4. 重连重查：事实新鲜 → 守卫放行");
            Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, STIRRER, "turntable",
                    "stopped", "evt-1", java.time.Instant.now().toString());
            eq("新鲜 → 放行", null,
                    TurntableGuard.checkBeforeTransfer(db, TENANT, RUN, STIRRER));

            section("5. 断线置过期 → 守卫拒绝");
            DeviceFact.invalidate(db, TENANT, STIRRER);
            DriverResult stale = TurntableGuard.checkBeforeTransfer(db, TENANT, RUN, STIRRER);
            eq("过期 → 拒绝", true, stale != null);
            eq("错误码 EC-STATE-STALE", "EC-STATE-STALE", stale == null ? null : stale.errorCode());

            section("6. 重新上报 → 恢复放行");
            Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, STIRRER, "turntable",
                    "stopped", "evt-2", java.time.Instant.now().toString());
            eq("重新上报 → 放行", null,
                    TurntableGuard.checkBeforeTransfer(db, TENANT, RUN, STIRRER));
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
        exec("INSERT INTO stirrer_dispenser_pair (tenant_id, stirrer_resource_id, dispenser_resource_id) "
                + "VALUES (?, ?, ?)", TENANT, STIRRER, DISPENSER);
        updateStirrer("idle");
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
        for (String sql : new String[]{
                "DELETE FROM stirrer_dispenser_pair WHERE tenant_id = ? AND dispenser_resource_id = ?",
                "DELETE FROM device_fact WHERE tenant_id = ? AND device_resource_id = ?"}) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, TENANT);
                ps.setLong(2, sql.contains("pair") ? DISPENSER : STIRRER);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] 失败：" + e.getMessage());
            }
        }
        for (long id : new long[]{STIRRER, DISPENSER}) {
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

    private L4RecoveryAndReconnectDbSelfTest() {
    }
}
