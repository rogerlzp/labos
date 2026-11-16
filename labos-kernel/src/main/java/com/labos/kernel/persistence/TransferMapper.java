package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 搬运业务事务（{@code transfer_transaction} / {@code position_evidence}）的数据访问
 * （原 {@code TransferTransaction} 里的裸 SQL）。
 *
 * <p>调用方是静态工具类，用 {@link Mappers#get} 取代理；
 * {@code confirm} 的写集在 {@code Tx.inTransaction} 内经 DataSource 同步复用绑定连接。
 * 口径：设备回包只是「运动报告」（REPORTED），位置确认必须另有独立证据（CONFIRMED）。
 */
public interface TransferMapper {

    /** 搬运动作开始前落一条 REPORTED 事务；返回自增 id。 */
    Long beginTransfer(@Param("tenantId") long tenantId,
                       @Param("runId") long runId,
                       @Param("plateBarcode") String plateBarcode,
                       @Param("sourceResourceId") Long sourceResourceId,
                       @Param("targetResourceId") Long targetResourceId,
                       @Param("sourcePositionId") Long sourcePositionId,
                       @Param("targetPositionId") Long targetPositionId);

    /** 设备回包后写运动报告：REPORTED（成功）/ UNKNOWN（失败/异常）。 */
    int reportTransfer(@Param("tenantId") long tenantId,
                       @Param("id") long id,
                       @Param("status") String status,
                       @Param("deviceReportJson") String deviceReportJson,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage);

    /**
     * 可确认行的板条码与目标资源（仅 REPORTED/UNKNOWN）；不可确认返回 null。
     * targetResourceId 无值时 map 里为 null。
     */
    Map<String, Object> selectConfirmableTransfer(@Param("tenantId") long tenantId,
                                                  @Param("id") long id);

    /**
     * P2 阶段 B：证据确认路径只认 REPORTED（UNKNOWN 必须走人工盘点 locatePlate）。
     * 返回板条码、源/目标设备资源、精确源/目标点位、run_id；不可确认返回 null。
     */
    Map<String, Object> selectReportedTransfer(@Param("tenantId") long tenantId,
                                               @Param("id") long id);

    /**
     * 到位扫码确认：按「run + 板 + 目标位置」找那条 REPORTED 的搬运事务。
     *
     * <p>P2 方案 §4.3：「目标位置扫码」是到位证据；DEVICE_REPORT 不得据此自动确认位置
     * ⇒ 到位确认必须由**扫码**触发。扫码证据落在目标位置上时，用它找到等确认的那次搬运。
     *
     * @return 事务 id；没有等确认的搬运 ⇒ null
     */
    java.util.Map<String, Object> selectReportedTransferByTarget(
            @Param("tenantId") long tenantId,
            @Param("runId") long runId,
            @Param("targetPositionId") long targetPositionId);

    /** 人工/扫码确认：roll 到 CONFIRMED；返回 0 = 行不存在（调用方已预检，防御）。 */
    int markTransferConfirmed(@Param("tenantId") long tenantId,
                              @Param("id") long id);

    /**
     * 位置证据（确认动作附带，无 run_id —— 与 RunMapper.insertPositionEvidence
     * 的复盘路径区分）；resourceId 可空。
     */
    int insertPositionEvidenceOnConfirm(@Param("tenantId") long tenantId,
                                        @Param("plateBarcode") String plateBarcode,
                                        @Param("resourceId") Long resourceId,
                                        @Param("evidenceType") String evidenceType,
                                        @Param("operator") String operator,
                                        @Param("note") String note);

    /**
     * DEVICE_REPORT 自动确认：与人工 confirm 共用同一状态机（status 白名单 + CAS），
     * 额外写 confirmed_by（0 = 系统）。只允许 REPORTED/UNKNOWN → CONFIRMED。
     */
    int markTransferConfirmedAuto(@Param("tenantId") long tenantId,
                                  @Param("id") long id,
                                  @Param("operatorId") long operatorId);

    /**
     * DEVICE_REPORT 位置证据：比人工确认多写 run_id，便于按 run 回溯。
     * 人工路径保持 {@code insertPositionEvidenceOnConfirm} 原样，不受影响。
     */
    int insertPositionEvidenceAuto(@Param("tenantId") long tenantId,
                                   @Param("plateBarcode") String plateBarcode,
                                   @Param("resourceId") Long resourceId,
                                   @Param("evidenceType") String evidenceType,
                                   @Param("operator") String operator,
                                   @Param("note") String note,
                                   @Param("runId") long runId);
}
