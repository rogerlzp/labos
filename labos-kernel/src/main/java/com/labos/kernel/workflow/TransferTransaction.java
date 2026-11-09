package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * P-08 TransferTransaction：一次搬运的业务事务。
 *
 * <p>设备成功回包只能算「运动报告」（REPORTED），不能当目标位置确认；
 * 板位必须另有独立证据（人工盘点/扫码/传感器）后由人 confirm 成 CONFIRMED。
 * 失败/异常则进 UNKNOWN，等人工核对。
 */
public final class TransferTransaction {

    private TransferTransaction() {
    }

    /** 搬运动作开始前落一条事务。 */
    public static long begin(DbConfig db, long tenantId, long runId, String plate,
                             Long sourceResourceId, Long targetResourceId)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO transfer_transaction
                       (tenant_id, run_id, plate_barcode, source_resource_id,
                        target_resource_id, status)
                     VALUES (?, ?, ?, ?, ?, 'REPORTED')
                     RETURNING id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            ps.setString(3, plate);
            if (sourceResourceId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, sourceResourceId);
            }
            if (targetResourceId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, targetResourceId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 设备回包后写运动报告：成功=REPORTED（仍待物理确认），失败/异常=UNKNOWN。 */
    public static void report(DbConfig db, long tenantId, long id, boolean ok,
                              String errorCode, String errorMessage, String raw)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE transfer_transaction
                        SET status = ?, device_report = ?::jsonb,
                            error_code = ?, error_message = ?
                      WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setString(1, ok ? "REPORTED" : "UNKNOWN");
            ps.setString(2, "{\"raw\":\"" + esc(raw == null ? "" : raw) + "\"}");
            ps.setString(3, errorCode);
            ps.setString(4, errorMessage);
            ps.setLong(5, tenantId);
            ps.setLong(6, id);
            ps.executeUpdate();
        }
    }

    /**
     * 人工/扫码确认物理位置：roll 到 CONFIRMED，并落一条 position_evidence。
     *
     * @return true=确认成功（只允许 REPORTED/UNKNOWN）
     */
    public static boolean confirm(DbConfig db, long tenantId, long id, String evidenceType,
                                  String operator, String note) throws SQLException {
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            String plate;
            Long target;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT plate_barcode, target_resource_id
                      FROM transfer_transaction
                     WHERE tenant_id = ? AND id = ?
                       AND status IN ('REPORTED', 'UNKNOWN')
                    """)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        com.labos.kernel.persistence.Tx.rollbackOnly();
                        return false;
                    }
                    plate = rs.getString(1);
                    long t = rs.getLong(2);
                    target = rs.wasNull() ? null : t;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE transfer_transaction
                       SET status = 'CONFIRMED', confirmed_at = now()
                     WHERE tenant_id = ? AND id = ?
                    """)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            if (plate != null) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO position_evidence
                          (tenant_id, plate_barcode, resource_id, evidence_type,
                           operator, note)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setString(2, plate);
                    if (target == null) {
                        ps.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(3, target);
                    }
                    ps.setString(4, evidenceType == null ? "MANUAL" : evidenceType.toUpperCase());
                    ps.setString(5, operator);
                    ps.setString(6, note);
                    ps.executeUpdate();
                }
            }
            return true;
        });
    }

    /** 该 run 是否还有未确认的搬运位置（REPORTED/UNKNOWN）。 */
    public static boolean hasUnconfirmed(DbConfig db, long tenantId, long runId)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FROM transfer_transaction
                      WHERE tenant_id = ? AND run_id = ?
                        AND status IN ('REPORTED', 'UNKNOWN')
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 当前状态（自测/排障）。 */
    public static String statusOf(DbConfig db, long tenantId, long id) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM transfer_transaction WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
