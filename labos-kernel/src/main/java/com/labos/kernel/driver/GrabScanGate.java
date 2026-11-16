package com.labos.kernel.driver;

import java.time.Duration;
import java.time.Instant;

/**
 * P2 阶段 B · 抓板前的位置级强制扫码闸门（纯逻辑）。
 *
 * <p>与 {@link ScanPolicy} 不同：本闸门是工作站级安全要求，<b>不受</b>节点
 * {@code barcode_policy} 影响；无策略、无期望值一律不放行（fail-closed）。
 * 只复用条码一致性判断的语义，不复用「无期望值放行」的口径。
 *
 * <p>以下任一条件不满足都拒绝抓板：
 * <ul>
 *   <li>无扫码证据（scan_id 缺失）或扫码结果为空；</li>
 *   <li>期望板号缺失；</li>
 *   <li>扫码未绑定到当前源位置；</li>
 *   <li>扫码结果与期望板号不一致；</li>
 *   <li>同一条扫码证据已被别的搬运/命令消费；</li>
 *   <li>扫码证据过期（观测时间超出允许时效）。</li>
 * </ul>
 */
public final class GrabScanGate {

    /** 闸门判定：{@code allow=true} 才允许抓板。 */
    public record Verdict(boolean allow, String errorCode, String reason) {
        public static Verdict ok() {
            return new Verdict(true, null, "扫码一致且未过期");
        }

        public static Verdict reject(String errorCode, String reason) {
            return new Verdict(false, errorCode, reason);
        }
    }

    /**
     * 扫码证据默认时效（超过则拒绝抓板）。
     *
     * <p>可用环境变量 {@code LABOS_SCAN_TTL_SEC} 覆盖（默认 60 秒）——现场"扫码→抓板"
     * 的实际间隔随机械臂行程/换手速度变化，写死 60s 会让慢节拍的现场频繁报
     * {@code EC-STATE-STALE}（证据过期），而调太快又等于放弃防复用。
     * 与 {@code LABOS_RAIL_WAIT_TIMEOUT_MS} / {@code LABOS_STIRRER_WAIT_TIMEOUT_MS} 同一套做法：
     * 非法值（非数字/<=0）静默回落默认，不让配置错误把闸门变成"永远过期"。
     *
     * <p>⚠ 实际调用方多数显式传 {@code ttl}（见 {@code ScanEvidence.authorizeAndConsume}），
     * 那时以传入值为准；本默认只在 {@code ttl == null} 时生效。
     */
    private static final Duration DEFAULT_SCAN_TTL = defaultScanTtl();

    private static Duration defaultScanTtl() {
        String v = System.getenv("LABOS_SCAN_TTL_SEC");
        if (v != null && !v.isBlank()) {
            try {
                long s = Long.parseLong(v.trim());
                if (s > 0) {
                    return Duration.ofSeconds(s);
                }
            } catch (NumberFormatException ignore) {
                // 非法值回落默认
            }
        }
        return Duration.ofSeconds(60);
    }

    /**
     * 位置级抓板闸门判定（纯函数，不碰 IO）。
     *
     * @param expectedBarcode 期望板号（声明/库存）
     * @param scannedBarcode  实际扫到的板号
     * @param sourcePositionId 源位置资源 id
     * @param scanId          设备扫码事件的唯一 id
     * @param observedAt      扫码观测时间（epoch millis；0/负 = 未知）
     * @param alreadyConsumed 该扫码证据是否已被消费
     * @param ttl             允许时效；null 用默认 60s
     */
    public static Verdict check(String expectedBarcode, String scannedBarcode,
                                Long sourcePositionId, String scanId,
                                long observedAt, boolean alreadyConsumed,
                                Duration ttl) {
        if (scanId == null || scanId.isBlank()) {
            return Verdict.reject("EC-SCAN-EVIDENCE-MISSING", "无扫码证据（scan_id 缺失）");
        }
        if (scannedBarcode == null || scannedBarcode.isBlank()) {
            return Verdict.reject("EC-SCAN-EVIDENCE-MISSING", "扫码结果为空");
        }
        if (expectedBarcode == null || expectedBarcode.isBlank()) {
            return Verdict.reject("EC-SCAN-EVIDENCE-MISSING", "期望板号缺失");
        }
        if (sourcePositionId == null) {
            return Verdict.reject("EC-SCAN-EVIDENCE-MISSING", "扫码未绑定源位置");
        }
        if (alreadyConsumed) {
            return Verdict.reject("EC-SCAN-EVIDENCE-CONSUMED", "扫码证据已被别的搬运消费");
        }
        if (!expectedBarcode.equals(scannedBarcode)) {
            return Verdict.reject("EC-SCAN-MISMATCH",
                    "条码不匹配（期望 " + expectedBarcode + "，扫到 " + scannedBarcode + "）");
        }
        if (isStale(observedAt, ttl == null ? DEFAULT_SCAN_TTL : ttl)) {
            return Verdict.reject("EC-STATE-STALE", "扫码证据过期");
        }
        return Verdict.ok();
    }

    /** 观测时间是否超出时效；observedAt<=0（未知）按过期处理（fail-closed）。 */
    private static boolean isStale(long observedAt, Duration ttl) {
        if (observedAt <= 0) {
            return true;
        }
        return Instant.ofEpochMilli(observedAt).plus(ttl).isBefore(Instant.now());
    }

    private GrabScanGate() {
    }
}
