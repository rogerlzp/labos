package com.labos.kernel.material;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * R6 · IN_TRANSFER（在途未确认）物料门禁的连库自测。
 *
 * <p>验证：位置寻址时，{@code plate.position_status='IN_TRANSFER'} 的板
 * 与 UNKNOWN/LOST 同口径——不可用作目标（返回 null，fail-closed）。
 * 反例：{@code KNOWN} 的板可正常寻址。
 */
public final class InTransitGateDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RES_IN_TRANSIT = -996001L;
    private static final long RES_KNOWN = -996002L;
    private static final long RUN = -996003L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();

            MaterialEffectService svc = new MaterialEffectService(TENANT, db);

            section("1. IN_TRANSFER 板不可做目标（fail-closed）");
            Long got = svc.resolveTargetPlate(
                    "{\"target\":{\"location\":{\"resourceId\":" + RES_IN_TRANSIT + "}}}", RUN);
            if (got == null) {
                ok("IN_TRANSFER 板 → null（位置不可信）", String.valueOf(got));
            } else {
                bad("IN_TRANSFER 板未被拦截", "plate=" + got);
            }

            section("2. KNOWN 板可正常寻址");
            Long known = svc.resolveTargetPlate(
                    "{\"target\":{\"location\":{\"resourceId\":" + RES_KNOWN + "}}}", RUN);
            if (known != null) {
                ok("KNOWN 板 → plateId=" + known, String.valueOf(known));
            } else {
                bad("KNOWN 板寻址失败", String.valueOf(known));
            }

            section("3. selectInTransitPlates 只数 IN_TRANSFER（R5 对账）");
            java.util.List<java.util.Map<String, Object>> inTransit =
                    com.labos.kernel.persistence.Mappers.get(
                            com.labos.kernel.persistence.PauseMapper.class)
                            .selectInTransitPlates(TENANT);
            java.util.Set<String> codes = new java.util.HashSet<>();
            for (var p : inTransit) {
                codes.add(String.valueOf(p.get("barcode")));
            }
            if (codes.contains("VERIFY-R6-IT") && !codes.contains("VERIFY-R6-KN")) {
                ok("IN_TRANSFER 板在对账清单里，KNOWN 不在", "n=" + inTransit.size());
            } else {
                bad("对账清单口径错", "IN_TRANSFER=" + codes.contains("VERIFY-R6-IT")
                        + " KNOWN=" + codes.contains("VERIFY-R6-KN"));
            }
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
        // 两块板：IN_TRANSFER + KNOWN
        exec("INSERT INTO plate (id, tenant_id, barcode, position_status) "
                + "VALUES (-996011, ?, 'VERIFY-R6-IT', 'IN_TRANSFER')", TENANT);
        exec("INSERT INTO plate (id, tenant_id, barcode, position_status) "
                + "VALUES (-996012, ?, 'VERIFY-R6-KN', 'KNOWN')", TENANT);
        // 两个位置：各自挂一块板，occupied_by_run = 当前 run
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, 'VERIFY-R6-IT', ?)", TENANT, RES_IN_TRANSIT, RUN);
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, 'VERIFY-R6-KN', ?)", TENANT, RES_KNOWN, RUN);
    }

    private static void cleanup() {
        del("position_state", "DELETE FROM position_state WHERE resource_id IN (?, ?)",
                RES_IN_TRANSIT, RES_KNOWN);
        del("plate", "DELETE FROM plate WHERE id IN (-996011, -996012)");
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

    private static void ok(String msg, String detail) {
        pass++;
        System.out.println("  ✓ " + msg + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
    }

    private static void bad(String msg, String detail) {
        fail++;
        System.out.println("  ✗ " + msg + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
    }

    private InTransitGateDbSelfTest() {
    }
}
