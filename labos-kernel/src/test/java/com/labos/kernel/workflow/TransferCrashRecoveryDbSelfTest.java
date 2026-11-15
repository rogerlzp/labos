package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 B · 崩溃恢复 连库判别性自测（T-01 抓板后崩溃 / T-02 放置后崩溃）。
 *
 * <p>验证：搬运 REPORTED（设备回包成功但未证据确认）后，恢复门禁
 * {@code hasUnconfirmed} 为 true（阻断 continue/retry）；证据确认后才 false。
 */
public final class TransferCrashRecoveryDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = 9997001L;
    private static final String PLATE = "SELF-CRASH-PLATE";
    private static final long SRC_RES = 9997011L;
    private static final long DST_RES = 9997012L;
    private static final long SRC_POS = 9997021L;
    private static final long DST_POS = 9997022L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. 搬运 REPORTED（模拟抓板后/放置后崩溃，未确认）");
            long txn = TransferTransaction.begin(db, TENANT, RUN, PLATE,
                    SRC_RES, DST_RES, SRC_POS, DST_POS);
            TransferTransaction.report(db, TENANT, txn, true, null, null, "{\"ok\":true}");
            eq("状态 REPORTED", "REPORTED", TransferTransaction.statusOf(db, TENANT, txn));
            eq("恢复门禁 hasUnconfirmed=true（阻断继续）", true,
                    TransferTransaction.hasUnconfirmed(db, TENANT, RUN));

            section("2. 证据确认后恢复门禁放行");
            boolean ok = TransferTransaction.confirmWithPosition(db, TENANT, txn,
                    "MANUAL", "tester", "人工确认", null);
            eq("确认成功", true, ok);
            eq("恢复门禁 hasUnconfirmed=false（可继续）", false,
                    TransferTransaction.hasUnconfirmed(db, TENANT, RUN));
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
                + "VALUES (?, ?, 'EV-SRC', '源位', 'storage')", SRC_POS, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-DST', '目标位', 'storage')", DST_POS, TENANT);
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, ?, ?)", TENANT, SRC_POS, PLATE, RUN);
        exec("INSERT INTO position_state (tenant_id, resource_id) VALUES (?, ?)",
                TENANT, DST_POS);
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
        // plate 行（confirmWithPosition 会写 plate.location_id → 引用了 DST_POS），先删。
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM plate WHERE tenant_id = ? AND barcode = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, PLATE);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] plate 失败：" + e.getMessage());
        }
        for (long pos : new long[]{SRC_POS, DST_POS}) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM position_state WHERE tenant_id = ? AND resource_id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, pos);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] position " + pos + " 失败：" + e.getMessage());
            }
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM resource_node WHERE tenant_id = ? AND id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, pos);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] node " + pos + " 失败：" + e.getMessage());
            }
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM transfer_transaction WHERE tenant_id = ? AND plate_barcode = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, PLATE);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] txn 失败：" + e.getMessage());
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

    private TransferCrashRecoveryDbSelfTest() {
    }
}
