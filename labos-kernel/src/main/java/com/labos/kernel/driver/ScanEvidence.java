package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.ScanEvidenceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 阶段 B · 位置级扫码证据的登记与原子消费（DB 侧）。
 *
 * <p>纯判定在 {@link GrabScanGate}；这里只做两件事：
 * <ol>
 *   <li>{@link #record}：登记扫码证据（同 scan_id 幂等）；</li>
 *   <li>{@link #consume}：原子消费（未消费 → 已消费），返回 false 表示已被消费/不存在，
 *       阻止复用旧扫码放行第二次抓板。</li>
 * </ol>
 *
 * <p>登记失败只记日志不阻断（抓板闸门会在判定阶段因「无证据」拒绝抓板，fail-closed）。
 */
public final class ScanEvidence {

    private static final Logger log = LoggerFactory.getLogger(ScanEvidence.class);

    public static void record(DbConfig db, long tenantId, Long runId, Long nodeId,
                              long sourcePositionId, String expectedBarcode,
                              String scannedBarcode, String observedAt, String scanId,
                              Long transferId, String commandId) {
        if (db == null || scanId == null || scanId.isBlank()) {
            return;
        }
        try {
            Mappers.get(ScanEvidenceMapper.class).insertScanEvidence(
                    tenantId, runId, nodeId, sourcePositionId,
                    expectedBarcode, scannedBarcode, observedAt,
                    scanId, transferId, commandId);
        } catch (RuntimeException e) {
            log.warn("[扫码证据] 登记失败（抓板闸门会按无证据拒绝）：{}", e.getMessage());
        }
    }

    /**
     * 原子消费。@return true = 本次消费成功；false = 证据不存在或已被消费。
     */
    public static boolean consume(DbConfig db, long tenantId, String scanId,
                                  Long transferId, long runId) {
        if (db == null || scanId == null || scanId.isBlank()) {
            return false;
        }
        try {
            return Mappers.get(ScanEvidenceMapper.class)
                    .consumeScanEvidence(tenantId, scanId, transferId, runId) == 1;
        } catch (RuntimeException e) {
            log.warn("[扫码证据] 消费失败（按未消费处理，抓板闸门拒绝）：{}", e.getMessage());
            return false;
        }
    }

    private ScanEvidence() {
    }
}
