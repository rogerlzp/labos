package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 D · L3 轨道分区并发 连库判别性自测。
 *
 * <p>验证：两个 run 抢同一路径的分区集，第一个拿到，第二个排队超时返回忙
 * （需设 LABOS_RAIL_WAIT_TIMEOUT_MS 缩短等待）。
 */
public final class RailZoneConcurrencyDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN_A = 9996001L;
    private static final long RUN_B = 9996002L;
    private static final long ZONE = 9996011L;
    private static final String PATH = "9996021->9996022";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. RUN_A 抢到分区");
            RailZoneGuard.Acquire a = RailZoneGuard.acquire(db, TENANT, RUN_A, PATH);
            eq("RUN_A 获取成功", true, a.ok());

            section("2. RUN_B 抢同一分区 → 排队超时返回忙");
            RailZoneGuard.Acquire b = RailZoneGuard.acquire(db, TENANT, RUN_B, PATH);
            eq("RUN_B 未获取", false, b.ok());
            eq("错误码 EC-RESOURCE-BUSY", "EC-RESOURCE-BUSY", b.errorCode());

            section("3. RUN_A 释放后 RUN_B 可获取");
            RailZoneGuard.release(db, TENANT, a.lease());
            RailZoneGuard.Acquire b2 = RailZoneGuard.acquire(db, TENANT, RUN_B, PATH);
            eq("释放后 RUN_B 获取成功", true, b2.ok());
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
        // run 行必须存在：否则 tryAcquireDevice 把 current_run_id 当「幽灵锁」回收，并发测不出来。
        exec("INSERT INTO run (id, tenant_id, run_no, run_type, status) "
                + "VALUES (?, ?, 'SELF-RA', 'workflow', 'running')", RUN_A, TENANT);
        exec("INSERT INTO run (id, tenant_id, run_no, run_type, status) "
                + "VALUES (?, ?, 'SELF-RB', 'workflow', 'running')", RUN_B, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-ZONE', '分区', 'virtual')", ZONE, TENANT);
        exec("INSERT INTO rail_zone (tenant_id, zone_code, resource_id) VALUES (?, 'Z1', ?)",
                TENANT, ZONE);
        exec("INSERT INTO transfer_rail_zone (tenant_id, path_key, zone_resource_id, seq) "
                + "VALUES (?, ?, ?, 1)", TENANT, PATH, ZONE);
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
                     "DELETE FROM run WHERE tenant_id = ? AND run_no LIKE 'SELF-R%'")) {
            ps.setLong(1, TENANT);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] run 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM transfer_rail_zone WHERE tenant_id = ? AND path_key = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, PATH);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] transfer 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM rail_zone WHERE tenant_id = ? AND resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, ZONE);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] rail_zone 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM device_state WHERE tenant_id = ? AND resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, ZONE);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] device_state 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM resource_node WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, ZONE);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] resource_node 失败：" + e.getMessage());
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

    private RailZoneConcurrencyDbSelfTest() {
    }
}
