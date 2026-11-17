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

    /** 搬运动作开始前落一条事务（含精确源/目标点位，供证据确认路径写位置）。 */
    public static long begin(DbConfig db, long tenantId, long runId, String plate,
                             Long sourceResourceId, Long targetResourceId,
                             Long sourcePositionId, Long targetPositionId)
            throws SQLException {
        Long id = transfers().beginTransfer(tenantId, runId, plate,
                sourceResourceId, targetResourceId, sourcePositionId, targetPositionId);
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
    /**
     * @deprecated P2 阶段 B（2026-11-15）：本方法<b>不写 position_state</b>（不写位置、
     * 不清源位、不写目标占用、不把板回 KNOWN）——证据确认请改用
     * {@link #confirmWithPosition}（原子写位置 + CONFIRMED + KNOWN，只认 REPORTED）。
     * UNKNOWN 的处置走人工盘点 {@code PauseManager.locatePlate}。
     * 仅自测保留（历史口径回归对照）；生产调用方已全部迁移。
     */
    @Deprecated
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

    /**
     * P2 阶段 B：证据确认（位置证据确认后再写 KNOWN/占用 + CONFIRMED）。
     *
     * <p>与旧 {@link #confirm} 的区别：旧 confirm 只写 evidence + 事务状态，
     * 位置写发生在设备回包时的 {@code noteTransferMove}；本方法把「位置写 + 事务确认 +
     * 板状态 KNOWN」放进<b>同一个事务</b>，满足「获得有效位置证据后，原子写位置与板状态」。
     *
     * <p><b>只接受 REPORTED</b>（UNKNOWN = 物理结果未知，必须走人工盘点 locatePlate）；
     * 要求事务已记录精确点位；{@code evidenceType=SCAN} 时必须消费扫码证据成功（防复用），
     * 否则整体回滚。
     *
     * @param scanId 扫码证据 id（evidenceType=SCAN 时必填）
     * @return true = 位置已写 + 板 KNOWN + 事务 CONFIRMED；false = 不可确认（保持 REPORTED 转人工）
     */
    public static boolean confirmWithPosition(DbConfig db, long tenantId, long id,
                                              String evidenceType, String operator,
                                              String note, String scanId)
            throws SQLException {
        String et = evidenceType == null ? "MANUAL" : evidenceType.toUpperCase();
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            java.util.Map<String, Object> row = transfers().selectReportedTransfer(tenantId, id);
            if (row == null) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            String plate = (String) row.get("plateBarcode");
            Long srcRes = toLong(row.get("sourceResourceId"));
            Long dstRes = toLong(row.get("targetResourceId"));
            Long srcPos = toLong(row.get("sourcePositionId"));
            Long dstPos = toLong(row.get("targetPositionId"));
            long runId = row.get("runId") instanceof Number n ? n.longValue() : 0L;
            if (plate == null || plate.isBlank() || srcRes == null || dstRes == null
                    || srcPos == null || dstPos == null || runId == 0L) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            // P1-4：SCAN 证据必须原子消费成功（防复用旧扫码），失败整体回滚。
            if ("SCAN".equals(et)) {
                if (scanId == null || scanId.isBlank()) {
                    com.labos.kernel.persistence.Tx.rollbackOnly();
                    return false;
                }
                boolean consumed = com.labos.kernel.driver.ScanEvidence.consumeForGrab(
                        db, tenantId, scanId, runId, dstPos, plate, plate, id);
                if (!consumed) {
                    com.labos.kernel.persistence.Tx.rollbackOnly();
                    return false;
                }
            }
            com.labos.kernel.driver.PositionLock lock =
                    new com.labos.kernel.driver.PositionLock(db, tenantId);
            com.labos.kernel.driver.PositionLock.TransferMove mv =
                    lock.noteTransferMove(srcRes, dstRes, srcPos, dstPos, plate, runId);
            if (!mv.ok()) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            int n = transfers().markTransferConfirmed(tenantId, id);
            if (n <= 0) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return false;
            }
            transfers().insertPositionEvidenceOnConfirm(tenantId, plate, dstPos, et,
                    operator, note);
            // P1-8：板状态 IN_TRANSFER → KNOWN（否则位置账本与板状态漂移）。
            com.labos.kernel.persistence.Mappers.get(
                            com.labos.kernel.persistence.PauseMapper.class)
                    .updatePlatePositionStatus(tenantId, plate, "KNOWN");
            // 搬运段（可观测副本）：自动确认路径由 DriverStepExecutor 写，
            // 人工/扫码确认路径原先一条都不写 ⇒ 甘特与岛进程视图看不到这次搬运。
            try {
                com.labos.kernel.persistence.BindingMapper bm =
                        com.labos.kernel.persistence.Mappers.get(
                                com.labos.kernel.persistence.BindingMapper.class);
                // ⚠ transport_segment 的 from/to 是 **device_point.id**（外键指向 device_point），
                // 而事务行里存的是点位实体 id（resource_node）⇒ 必须先转换；
                // 直接塞实体 id 会 FK 失败，而 PG 一旦语句报错整个事务就作废 ⇒
                // 表现为"返回 OK 但什么都没落库"（实测踩到）。
                Long fromDp = bm.selectDevicePointIdByResource(tenantId, srcPos);
                Long toDp = bm.selectDevicePointIdByResource(tenantId, dstPos);
                if (fromDp != null && toDp != null) {
                    bm.insertTransportSegmentManual(tenantId, runId, fromDp, toDp, plate);
                }
            } catch (RuntimeException ignore) {
                // 观测数据不该让"确认"失败（物理已经搬完了）
            }
            return true;
        });
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
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
