package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * P2 阶段 B · 位置级扫码证据（只增不改 + 原子消费）。
 */
public interface ScanEvidenceMapper {

    /** 登记一条扫码证据；同 scan_id 重复上报幂等（DO NOTHING）。返回受影响行数。 */
    int insertScanEvidence(@Param("tenantId") long tenantId,
                           @Param("runId") Long runId,
                           @Param("nodeId") Long nodeId,
                           @Param("sourcePositionId") long sourcePositionId,
                           @Param("expectedBarcode") String expectedBarcode,
                           @Param("scannedBarcode") String scannedBarcode,
                           @Param("observedAt") String observedAt,
                           @Param("scanId") String scanId,
                           @Param("transferId") Long transferId,
                           @Param("commandId") String commandId);

    /**
     * 原子消费：把未消费的证据置 consumed=true。返回 1 = 本次消费成功；
     * 0 = 不存在或已被消费（防复用旧扫码放行第二次抓板）。
     */
    int consumeScanEvidence(@Param("tenantId") long tenantId,
                            @Param("scanId") String scanId,
                            @Param("transferId") Long transferId,
                            @Param("runId") long runId);

    /**
     * P2 阶段 B（评审 P0-5）：严格消费——匹配 run、点位、期望码、实扫码 + 未消费，
     * 全部满足才置 consumed 并绑定 transfer。任一不匹配返回 0。
     */
    int consumeForGrab(@Param("tenantId") long tenantId,
                       @Param("scanId") String scanId,
                       @Param("runId") long runId,
                       @Param("sourcePositionId") long sourcePositionId,
                       @Param("expectedBarcode") String expectedBarcode,
                       @Param("scannedBarcode") String scannedBarcode,
                       @Param("transferId") Long transferId);

    /** 查一条扫码证据（按 scan_id）；不存在返回 null。 */
    Map<String, Object> selectScanEvidence(@Param("tenantId") long tenantId,
                                           @Param("scanId") String scanId);

    /** 按「当前 run + 源位 + 未消费」查最新一条扫码证据（抓板闸门不用预先填 scanId）。 */
    Map<String, Object> selectLatestUnconsumed(@Param("tenantId") long tenantId,
                                               @Param("runId") long runId,
                                               @Param("sourcePositionId") long sourcePositionId);
}
