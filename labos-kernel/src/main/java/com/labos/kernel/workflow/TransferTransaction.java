package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
import java.util.Map;

/**
 * P-08 TransferTransaction：一次搬运的业务事务。
 *
 * <p>设备成功回包只能算「运动报告」（REPORTED），不能当目标位置确认；
 * 板位必须另有独立证据（人工盘点/扫码/传感器）后由人 confirm 成 CONFIRMED。
 * 失败/异常则进 UNKNOWN，等人工核对。
 * SQL 全部在 {@code mapper/TransferMapper.xml}。
 */
public final class TransferTransaction {

    private TransferTransaction() {
    }

    private static com.labos.kernel.persistence.TransferMapper transfers() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.TransferMapper.class);
    }

    /** 搬运动作开始前落一条事务。 */
    public static long begin(DbConfig db, long tenantId, long runId, String plate,
                             Long sourceResourceId, Long targetResourceId)
            throws SQLException {
        Long id = transfers().beginTransfer(tenantId, runId, plate,
                sourceResourceId, targetResourceId);
        return id == null ? 0L : id;
    }

    /** 设备回包后写运动报告：成功=REPORTED（仍待物理确认），失败/异常=UNKNOWN。 */
    public static void report(DbConfig db, long tenantId, long id, boolean ok,
                              String errorCode, String errorMessage, String raw)
            throws SQLException {
        transfers().reportTransfer(tenantId, id, ok ? "REPORTED" : "UNKNOWN",
                "{\"raw\":\"" + esc(raw == null ? "" : raw) + "\"}",
                errorCode, errorMessage);
    }

    /**
     * 人工/扫码确认物理位置：roll 到 CONFIRMED，并落一条 position_evidence。
     *
     * @return true=确认成功（只允许 REPORTED/UNKNOWN）
     */
    public static boolean confirm(DbConfig db, long tenantId, long id, String evidenceType,
                                  String operator, String note) throws SQLException {
        // c 形参保留（SqlWork 签名）：mapper 在 Tx 事务内复用绑定连接
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Map<String, Object> row = transfers().selectConfirmableTransfer(tenantId, id);
            if (row == null) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            String plate = (String) row.get("plateBarcode");
            Number target = (Number) row.get("targetResourceId");
            transfers().markTransferConfirmed(tenantId, id);
            if (plate != null) {
                transfers().insertPositionEvidenceOnConfirm(tenantId, plate,
                        target == null ? null : target.longValue(),
                        evidenceType == null ? "MANUAL" : evidenceType.toUpperCase(),
                        operator, note);
            }
            return true;
        });
    }

    /** 该 run 是否还有未确认的搬运位置（REPORTED/UNKNOWN）。 */
    public static boolean hasUnconfirmed(DbConfig db, long tenantId, long runId)
            throws SQLException {
        // 与 PauseManager 恢复门禁同一口径，直接复用 PauseMapper 的计数语句
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.PauseMapper.class)
                .countUnconfirmedTransfers(tenantId, runId) > 0;
    }

    /** 当前状态（自测/排障）。 */
    public static String statusOf(DbConfig db, long tenantId, long id) throws SQLException {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExecutionMapper.class)
                .selectTransferStatus(tenantId, id);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
