package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** P-08 TransferTransaction 自测：运动报告 ≠ 位置确认，需人工/扫码证据。 */
public final class TransferTransactionDbSelfTest {

    private static int pass;
    private static int fail;
    private static final long TENANT = 1L;
    private static final String PLATE = "SELF-TXN-PLATE";

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        cleanup(db);

        long okId = TransferTransaction.begin(db, TENANT, -998001L, PLATE, 1L, 2L);
        check("搬运事务可创建", okId > 0, okId);
        check("创建后状态 REPORTED（仅运动报告）",
                "REPORTED".equals(TransferTransaction.statusOf(db, TENANT, okId)),
                TransferTransaction.statusOf(db, TENANT, okId));
        TransferTransaction.report(db, TENANT, okId, true, null, null, "{\"ok\":true}");
        check("设备成功回包后仍是 REPORTED（不等于位置确认）",
                "REPORTED".equals(TransferTransaction.statusOf(db, TENANT, okId)),
                TransferTransaction.statusOf(db, TENANT, okId));
        boolean confirmed = TransferTransaction.confirm(db, TENANT, okId,
                "SCAN", "tester", "扫码确认到目标位");
        check("人工/扫码确认成功", confirmed, confirmed);
        check("确认后状态 CONFIRMED",
                "CONFIRMED".equals(TransferTransaction.statusOf(db, TENANT, okId)),
                TransferTransaction.statusOf(db, TENANT, okId));
        check("确认落 position_evidence", evidenceCount(db) == 1, evidenceCount(db));
        check("重复确认返回 false",
                !TransferTransaction.confirm(db, TENANT, okId, "MANUAL", "t", "again"), true);

        long badId = TransferTransaction.begin(db, TENANT, -998002L, PLATE, 1L, 2L);
        TransferTransaction.report(db, TENANT, badId, false, "EC-99-0005", "中断", null);
        check("设备失败回包 → UNKNOWN",
                "UNKNOWN".equals(TransferTransaction.statusOf(db, TENANT, badId)),
                TransferTransaction.statusOf(db, TENANT, badId));
        check("UNKNOWN 可人工确认",
                TransferTransaction.confirm(db, TENANT, badId, "MANUAL", "t", "人工确认"), true);

        cleanup(db);
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static int evidenceCount(DbConfig db) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM position_evidence "
                             + "WHERE tenant_id=1 AND plate_barcode=?")) {
            ps.setString(1, PLATE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void cleanup(DbConfig db) throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM position_evidence WHERE tenant_id=1 AND plate_barcode=?")) {
                ps.setString(1, PLATE);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM transfer_transaction WHERE tenant_id=1 AND plate_barcode=?")) {
                ps.setString(1, PLATE);
                ps.executeUpdate();
            }
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }
}
