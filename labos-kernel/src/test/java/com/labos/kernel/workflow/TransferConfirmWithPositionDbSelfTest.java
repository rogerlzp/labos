package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 B · 证据确认路径 连库判别性自测。
 *
 * <p>验证：设备回包 REPORTED 后<b>不写位置</b>（源位仍占、目标仍空）；
 * {@code confirmWithPosition} 拿到位置证据后<b>原子</b>写源位清空 + 目标占用 + CONFIRMED。
 */
public final class TransferConfirmWithPositionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -999101L;
    private static final String PLATE = "SELF-EV-PLATE";
    private static final long SRC_RES = -999102L;
    private static final long DST_RES = -999103L;
    private static final long SRC_POS = -999112L;
    private static final long DST_POS = -999113L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. 动作前落事务（含精确点位）");
            long txn = TransferTransaction.begin(db, TENANT, RUN, PLATE,
                    SRC_RES, DST_RES, SRC_POS, DST_POS);
            eq("事务创建", true, txn > 0);

            section("2. 设备回包 REPORTED 后不写位置（反例：只报告不动账本）");
            TransferTransaction.report(db, TENANT, txn, true, null, null, "{\"ok\":true}");
            eq("状态 REPORTED（运动报告 ≠ 位置确认）", "REPORTED",
                    TransferTransaction.statusOf(db, TENANT, txn));
            eq("源位仍有板（未写位置）", PLATE, plateOn(SRC_POS));
            eq("目标位仍空（未写位置）", null, plateOn(DST_POS));

            section("3. confirmWithPosition 原子写位置 + CONFIRMED + KNOWN");
            boolean ok = TransferTransaction.confirmWithPosition(db, TENANT, txn,
                    "MANUAL", "tester", "扫码确认到位", null);
            eq("证据确认成功", true, ok);
            eq("状态 CONFIRMED", "CONFIRMED", TransferTransaction.statusOf(db, TENANT, txn));
            eq("源位已清空", null, plateOn(SRC_POS));
            eq("目标位是这块板", PLATE, plateOn(DST_POS));
            eq("板状态已回 KNOWN", "KNOWN", plateStatus());

            section("4. 反例：缺精确点位的事务 confirmWithPosition 返回 false");
            long txn2 = TransferTransaction.begin(db, TENANT, RUN, PLATE,
                    SRC_RES, DST_RES, null, null);
            eq("无点位不可确认（fail-closed）", false,
                    TransferTransaction.confirmWithPosition(db, TENANT, txn2,
                            "MANUAL", "t", "无点位", null));
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
        // 点位必须在 resource_node 里（plate.location_id / parent 解析都引用它）
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-SRC', '源位', 'storage')", SRC_POS, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-DST', '目标位', 'storage')", DST_POS, TENANT);
        // 源位：本 run 放上去的板（occupied_by_run = RUN，take 才放行）
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, ?, ?)", TENANT, SRC_POS, PLATE, RUN);
        // 目标位：空（预留由 tryReserve 管，这里只验证 put 落板）
        exec("INSERT INTO position_state (tenant_id, resource_id) VALUES (?, ?)",
                TENANT, DST_POS);
    }

    private static String plateOn(long resourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT plate_id FROM position_state WHERE tenant_id = ? AND resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String plateStatus() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT position_status FROM plate WHERE tenant_id = ? AND barcode = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, PLATE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
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
                System.out.println("  [清理] " + pos + " 失败：" + e.getMessage());
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
            System.out.println("  [清理] 事务失败：" + e.getMessage());
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

    private TransferConfirmWithPositionDbSelfTest() {
    }
}
