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

    /**
     * 严格消费（评审 P0-5）：run / 点位 / 期望码 / 实扫码全匹配 + 未消费才成功。
     */
    public static boolean consumeForGrab(DbConfig db, long tenantId, String scanId,
                                         long runId, long sourcePositionId,
                                         String expectedBarcode, String scannedBarcode,
                                         Long transferId) {
        if (db == null || scanId == null || scanId.isBlank()) {
            return false;
        }
        try {
            return Mappers.get(ScanEvidenceMapper.class)
                    .consumeForGrab(tenantId, scanId, runId, sourcePositionId,
                            expectedBarcode, scannedBarcode, transferId) == 1;
        } catch (RuntimeException e) {
            log.warn("[扫码证据] 严格消费失败（按未消费处理，拒绝）：{}", e.getMessage());
            return false;
        }
    }

    /**
     * P2 阶段 B · 抓板闸门接线（唯一入口）：查证据 → 纯判定 → 原子消费。
     *
     * <p>证据必须绑定<b>本次抓取的源位</b>；纯判定做错误码分档，原子消费做防复用与
     * 全匹配（run/点位/期望码/实扫码）。三步任一失败都拒绝抓板。
     */
    public static GrabScanGate.Verdict authorizeAndConsume(DbConfig db, long tenantId,
            String scanId, long runId, long sourcePositionId, String expectedBarcode,
            Long transferId, java.time.Duration ttl) {
        if (db == null || scanId == null || scanId.isBlank()) {
            return GrabScanGate.Verdict.reject("EC-SCAN-EVIDENCE-MISSING", "无扫码证据（scanId 缺失）");
        }
        java.util.Map<String, Object> ev;
        try {
            ev = Mappers.get(ScanEvidenceMapper.class).selectScanEvidence(tenantId, scanId);
        } catch (RuntimeException e) {
            return GrabScanGate.Verdict.reject("EC-SCAN-EVIDENCE-MISSING", "读扫码证据失败：" + e.getMessage());
        }
        if (ev == null) {
            return GrabScanGate.Verdict.reject("EC-SCAN-EVIDENCE-MISSING", "扫码证据不存在：" + scanId);
        }
        Long evPos = toLong(ev.get("sourcePositionId"));
        String scanned = (String) ev.get("scannedBarcode");
        String evExpected = (String) ev.get("expectedBarcode");
        boolean consumed = Boolean.TRUE.equals(ev.get("consumed"));
        long observedAt = toMillis(ev.get("observedAt"));

        // 证据源位必须 == 本次抓取源位（来源位置不明 = 拒绝）
        if (evPos == null || evPos.longValue() != sourcePositionId) {
            return GrabScanGate.Verdict.reject("EC-SCAN-EVIDENCE-MISSING",
                    "扫码未绑定本次抓取源位");
        }
        GrabScanGate.Verdict v = GrabScanGate.check(evExpected, scanned, evPos, scanId,
                observedAt, consumed, ttl);
        if (!v.allow()) {
            return v;
        }
        boolean ok = consumeForGrab(db, tenantId, scanId, runId, sourcePositionId,
                expectedBarcode, scanned, transferId);
        if (!ok) {
            return GrabScanGate.Verdict.reject("EC-SCAN-EVIDENCE-CONSUMED",
                    "扫码证据已被消费或不匹配（防复用）");
        }
        return GrabScanGate.Verdict.ok();
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static long toMillis(Object o) {
        if (o instanceof java.sql.Timestamp ts) {
            return ts.getTime();
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant().toEpochMilli();
        }
        if (o instanceof java.time.Instant inst) {
            return inst.toEpochMilli();
        }
        if (o instanceof java.util.Date d) {
            return d.getTime();
        }
        return 0L;
    }

    private ScanEvidence() {
    }
}
