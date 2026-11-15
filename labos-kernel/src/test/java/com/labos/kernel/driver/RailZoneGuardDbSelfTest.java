package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * P2 阶段 D · L3 轨道分区互锁 连库判别性自测。
 */
public final class RailZoneGuardDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = 9993001L;
    private static final long Z1 = 9993011L;
    private static final long Z2 = 9993012L;
    private static final long Z3 = 9993013L;
    private static final String PATH = "9993101->9993102";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. 有映射：全有或全无 + resource_id 升序");
            RailZoneGuard.Acquire a = RailZoneGuard.acquire(db, TENANT, RUN, PATH);
            eq("获取成功", true, a.ok());
            eq("分区数 3", 3, a.lease() == null ? 0 : a.lease().zones().size());
            eq("升序 [Z1,Z2,Z3]", List.of(Z1, Z2, Z3),
                    a.lease() == null ? List.of() : a.lease().zones());

            section("2. 释放后可再次获取");
            RailZoneGuard.release(db, TENANT, a.lease());
            eq("再次获取成功", true, RailZoneGuard.acquire(db, TENANT, RUN, PATH).ok());

            section("3. 反例：未配置分区集 → EC-INTERLOCK-RAIL（fail-closed）");
            RailZoneGuard.Acquire missing = RailZoneGuard.acquire(db, TENANT, RUN, "nope->nope");
            eq("未配置拒绝", false, missing.ok());
            eq("错误码 EC-INTERLOCK-RAIL", "EC-INTERLOCK-RAIL", missing.errorCode());
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
        for (long z : new long[]{Z1, Z2, Z3}) {
            exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                    + "VALUES (?, ?, 'EV-Z" + z + "', '分区', 'virtual')", z, TENANT);
            exec("INSERT INTO rail_zone (tenant_id, zone_code, resource_id) VALUES (?, ?, ?)",
                    TENANT, "Z" + z, z);
        }
        // 故意乱序插入 seq，验证按 resource_id 升序获取
        exec("INSERT INTO transfer_rail_zone (tenant_id, path_key, zone_resource_id, seq) "
                + "VALUES (?, ?, ?, 3)", TENANT, PATH, Z3);
        exec("INSERT INTO transfer_rail_zone (tenant_id, path_key, zone_resource_id, seq) "
                + "VALUES (?, ?, ?, 1)", TENANT, PATH, Z1);
        exec("INSERT INTO transfer_rail_zone (tenant_id, path_key, zone_resource_id, seq) "
                + "VALUES (?, ?, ?, 2)", TENANT, PATH, Z2);
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
                "DELETE FROM transfer_rail_zone WHERE tenant_id = ? AND path_key = ?",
                "DELETE FROM rail_zone WHERE tenant_id = ? AND resource_id IN (?,?,?)",
                "DELETE FROM device_state WHERE tenant_id = ? AND resource_id IN (?,?,?)"}) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, TENANT);
                ps.setObject(2, sql.contains("path_key") ? PATH : Z1);
                if (!sql.contains("path_key")) {
                    ps.setObject(3, Z2);
                    ps.setObject(4, Z3);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] 失败：" + e.getMessage());
            }
        }
        for (long z : new long[]{Z1, Z2, Z3}) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM resource_node WHERE tenant_id = ? AND id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, z);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] node " + z + " 失败：" + e.getMessage());
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

    private RailZoneGuardDbSelfTest() {
    }
}
