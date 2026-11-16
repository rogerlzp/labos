package com.labos.kernel.workflow;

import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.driver.ScanEvidence;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.TransferMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * 到位扫码 → 落位确认 · 连库判别性自测（2026-11-16）。
 *
 * <p><b>为什么补这一层</b>：P2 方案 §4.3 规定「目标位置扫码」是到位证据，
 * 且「DEVICE_REPORT 不得据此自动确认位置」⇒ 搬运落位必须由**扫码**触发确认。
 * 平台里 {@code confirmWithPosition} 早已实现（含 SCAN 证据的原子消费），
 * 缺的是「扫码证据落在目标位置 ⇒ 找到等确认的那次搬运 ⇒ 调它」这段接线。
 *
 * <p><b>本自测锁住的不变量</b>：
 * <ol>
 *   <li>按「run + 目标位置」能找到那条 REPORTED 的搬运事务（并拿到它的板号）；</li>
 *   <li>扫码证据的期望码在「目标位还没写板」时能取到<strong>在途搬运的板号</strong>
 *       （否则到位扫码落不了证据）；</li>
 *   <li>用该扫码证据确认 ⇒ 位置写入 + 事务 CONFIRMED + 板状态 KNOWN；</li>
 *   <li>反例：扫码证据的期望码与在途搬运的板<strong>不一致</strong> ⇒ 不得确认。</li>
 * </ol>
 */
public final class PlacementConfirmByScanDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -999201L;
    private static final long SRC_RES = -999202L;
    private static final long DST_RES = -999203L;
    private static final long SRC_POS = -999212L;
    private static final long DST_POS = -999213L;
    private static final long INST = -999214L;
    private static final String PLATE = "SELF-PC-PLATE";
    private static final String OTHER_PLATE = "SELF-PC-PLATE-2";
    private static final String SCAN = "SELF-PC-SCAN-1";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();

            section("1. 按「run + 目标位置」找到等确认的那次搬运");
            long txn = TransferTransaction.begin(db, TENANT, RUN, PLATE,
                    SRC_RES, DST_RES, SRC_POS, DST_POS);
            TransferTransaction.report(db, TENANT, txn, true, null, null, "{\"ok\":true}");
            eq("事务 REPORTED", "REPORTED", TransferTransaction.statusOf(db, TENANT, txn));

            Map<String, Object> tx = com.labos.kernel.persistence.Mappers
                    .get(TransferMapper.class)
                    .selectReportedTransferByTarget(TENANT, RUN, DST_POS);
            eq("按目标位置找到搬运事务", true, tx != null);
            eq("事务号一致", txn, tx == null ? null : ((Number) tx.get("id")).longValue());
            eq("在途板号正确", PLATE, tx == null ? null : tx.get("plateBarcode"));

            section("2. 目标位还没写板 ⇒ 扫码证据的期望码取「在途搬运的板号」");
            // 目标位此刻是空的（位置账本还没写）⇒ 到位扫码必须拿在途板号当期望码
            eq("目标位此刻没有板", null, plateOn(DST_POS));
            PositionLock lock = new PositionLock(db, TENANT);
            String scanId = lock.recordScanEvidence(INST, "{\"barcode\":\"" + PLATE + "\"}",
                    RUN, -999215L, SCAN, DST_POS);
            eq("到位扫码证据已落库", true, scanId != null);
            eq("证据的期望码 = 在途板号", PLATE, evidenceExpected(scanId));

            section("3. 用该扫码证据确认落位（SCAN 路径：原子消费 + 写位置）");
            boolean ok = TransferTransaction.confirmWithPosition(db, TENANT, txn,
                    "SCAN", "AUTO-SCAN", "到位扫码确认", scanId);
            eq("确认成功", true, ok);
            eq("事务 CONFIRMED", "CONFIRMED", TransferTransaction.statusOf(db, TENANT, txn));
            eq("源位已清空", null, plateOn(SRC_POS));
            eq("目标位是这块板", PLATE, plateOn(DST_POS));
            eq("板状态已回 KNOWN", "KNOWN", plateStatus());
            eq("扫码证据已被消费", true, evidenceConsumed(scanId));

            section("4. 反例：证据的期望码与在途搬运的板不一致 ⇒ 不得确认");
            long txn2 = TransferTransaction.begin(db, TENANT, RUN, OTHER_PLATE,
                    SRC_RES, DST_RES, SRC_POS, DST_POS);
            TransferTransaction.report(db, TENANT, txn2, true, null, null, "{\"ok\":true}");
            String scan2 = lock.recordScanEvidence(INST, "{\"barcode\":\"" + PLATE + "\"}",
                    RUN, -999216L, SCAN + "-2", DST_POS);
            eq("反例证据已落库", true, scan2 != null);
            boolean bad = TransferTransaction.confirmWithPosition(db, TENANT, txn2,
                    "SCAN", "AUTO-SCAN", "错板", scan2);
            eq("不得确认（fail-closed）", false, bad);
            eq("事务仍 REPORTED", "REPORTED", TransferTransaction.statusOf(db, TENANT, txn2));
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
                + "VALUES (?, ?, 'EV-PC-SRC', '源位', 'storage')", SRC_POS, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-PC-DST', '目标位', 'storage')", DST_POS, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-PC-DEV', '设备', 'device')", DST_RES, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-PC-SRCDEV', '源设备', 'device')", SRC_RES, TENANT);
        exec("INSERT INTO device_instance (id, tenant_id, code, name, resource_id) "
                + "VALUES (?, ?, 'EV-PC-INST', '实例', ?)", INST, TENANT, DST_RES);
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, ?, ?)", TENANT, SRC_POS, PLATE, RUN);
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

    private static String evidenceExpected(String scanId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT expected_barcode FROM position_scan_evidence "
                             + " WHERE tenant_id = ? AND scan_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean evidenceConsumed(String scanId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM position_scan_evidence "
                             + " WHERE tenant_id = ? AND scan_id = ? AND consumed = true")) {
            ps.setLong(1, TENANT);
            ps.setString(2, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
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
        try (Connection c = db.open()) {
            exec(c, "DELETE FROM position_scan_evidence WHERE tenant_id = ? AND scan_id LIKE ?",
                    TENANT, "SELF-PC-SCAN%");
            exec(c, "DELETE FROM transfer_transaction WHERE tenant_id = ? AND run_id = ?",
                    TENANT, RUN);
            exec(c, "DELETE FROM plate WHERE tenant_id = ? AND barcode IN (?, ?)",
                    TENANT, PLATE, OTHER_PLATE);
            exec(c, "DELETE FROM position_state WHERE tenant_id = ? AND resource_id IN (?, ?)",
                    TENANT, SRC_POS, DST_POS);
            exec(c, "DELETE FROM device_instance WHERE tenant_id = ? AND id = ?", TENANT, INST);
            exec(c, "DELETE FROM resource_node WHERE tenant_id = ? AND id IN (?, ?, ?, ?)",
                    TENANT, SRC_POS, DST_POS, SRC_RES, DST_RES);
        } catch (SQLException e) {
            System.out.println("  [清理] 失败：" + e.getMessage());
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s);
    }

    private static void eq(String what, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) {
            pass++;
            System.out.println("  ✓ " + what + " = " + got);
        } else {
            fail++;
            System.out.println("  ✗ " + what + "：期望 " + want + "，实际 " + got);
        }
    }
}
