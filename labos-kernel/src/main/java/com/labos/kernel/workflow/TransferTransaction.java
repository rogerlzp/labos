package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
import java.util.Map;

/**
 * P-08 TransferTransaction：一次搬运的业务事务。
 *
 * <p>设备成功回包只能算「运动报告」（REPORTED），不能当目标位置确认；
 * 板位必须另有位置证据才能成 CONFIRMED。失败/异常则进 UNKNOWN，等人工核对。
 *
 * <p><b>M 方案（2026-11-13，用户拍板）</b>：证据来源扩展为「人工盘点/扫码/传感器」
 * <b>+</b> 满足九项条件且由 {@code LABOS_TRANSFER_AUTO_CONFIRM} 开关启用的
 * {@code DEVICE_REPORT}（见 {@link #confirmByDeviceReport} 与评审清单「区 4 定稿方案」）。
 * 恢复强门禁（存在 REPORTED/UNKNOWN 则拒绝 continue/retry）**判定不变**：
 * 自动确认只是提供一条合法的 CONFIRMED 路径，不短路门禁。
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

    /**
     * M 方案：DEVICE_REPORT 自动确认（与人工 {@link #confirm} **共用同一状态机**）。
     *
     * <p>调用方（{@code DriverStepExecutor.autoConfirmTransfer}）必须已逐条验证九项条件：
     * P-08 事务已创建、板号合法、抢锁成功、设备回包 OK、源位归属无冲突、
     * 取板/放板影响行数 > 0、目标位板号一致、has_plate 已刷新、audit 两行已写。
     * 任一不满足都不得调本方法。
     *
     * <p>这里仍用 {@code selectConfirmableTransfer} 兜一层状态白名单 CAS，并复核
     * 事务里的板号与传入一致，避免「位置登记成功但事务被别处确认过」时写出错配证据。
     *
     * @return true=已置 CONFIRMED；false=事务不可确认（调用方保持 REPORTED/UNKNOWN）
     */
    public static boolean confirmByDeviceReport(DbConfig db, long tenantId, long id,
                                                String plate, Long targetRes, long runId,
                                                String note) throws SQLException {
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Map<String, Object> row = transfers().selectConfirmableTransfer(tenantId, id);
            if (row == null) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            String rowPlate = (String) row.get("plateBarcode");
            if (rowPlate == null || !rowPlate.equals(plate)) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            int n = transfers().markTransferConfirmedAuto(tenantId, id, 0L);
            if (n <= 0) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            transfers().insertPositionEvidenceAuto(tenantId, plate, targetRes,
                    "DEVICE_REPORT", "AUTO", note, runId);
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
