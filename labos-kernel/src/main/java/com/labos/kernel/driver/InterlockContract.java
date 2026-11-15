package com.labos.kernel.driver;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * P2 阶段 A · 互锁契约（错误码恢复作用域 + 事实状态时效）。
 *
 * <p>把评审要求的「错误码分档」与「状态过期/未知」落成可测试的单一真源：
 * 每个互锁错误码都有明确的恢复作用域，每个互锁事实类型都有明确的时效。
 * 不允许未知错误码静默落入 NODE（见 {@link #recoveryScope} 的 fail-closed 返回）。
 *
 * <p>作用域只表达失败类别，是否等待/暂停/转人工还取决于「命令是否已下发、
 * 物理位置是否可信」，由调用方结合上下文决定（docs/设备/核酸提取工作站-P2互锁方案 §6）。
 */
public final class InterlockContract {

    /** 恢复作用域：失败后系统/人工怎么处置。 */
    public enum Scope {
        /** 暂态忙：有界等待/排队，超时转人工。 */
        WAIT,
        /** 安全拒绝：拒绝本次下发，转人工核对（不自动重试）。 */
        MANUAL,
        /** 故障暂停：paused_fault，满足恢复条件后人工恢复。 */
        PAUSE_FAULT,
        /** 阻断并核对账本/物理位置（一板多位置、放置未确认）。 */
        BLOCK_RECONCILE
    }

    private static final Map<String, Scope> SCOPES = Map.ofEntries(
            Map.entry("EC-RESOURCE-BUSY", Scope.WAIT),
            Map.entry("EC-INTERLOCK-TURNTABLE", Scope.WAIT),
            Map.entry("EC-PLATE-DUPLICATE-POS", Scope.BLOCK_RECONCILE),
            Map.entry("EC-INTERLOCK-RAIL", Scope.MANUAL),
            Map.entry("EC-INTERLOCK-STIRRER", Scope.PAUSE_FAULT),
            Map.entry("EC-INTERLOCK-NO-WAIT", Scope.WAIT),
            Map.entry("EC-STATE-STALE", Scope.MANUAL),
            Map.entry("EC-STATE-UNKNOWN", Scope.MANUAL),
            Map.entry("EC-SCAN-MISMATCH", Scope.MANUAL),
            Map.entry("EC-SCAN-EVIDENCE-MISSING", Scope.MANUAL),
            Map.entry("EC-PLACE-UNCONFIRMED", Scope.BLOCK_RECONCILE));

    /**
     * 错误码 → 恢复作用域。未登记的码返回 {@code MANUAL}（fail-closed：宁可转人工，
     * 也不把未知错误静默当成功或自动重试）。
     */
    public static Scope recoveryScope(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return Scope.MANUAL;
        }
        return SCOPES.getOrDefault(errorCode, Scope.MANUAL);
    }

    /** 互锁错误码全集（供测试钉死词表不被悄悄删减）。 */
    public static Set<String> errorCodes() {
        return SCOPES.keySet();
    }

    // ------------------------------------------------------------ 状态时效

    /** 默认事实时效（设备不报 freshness 配置时用）。 */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private static final Map<String, Duration> TTLS = Map.of(
            "turntable", Duration.ofSeconds(5),
            "stirrer_status", Duration.ofSeconds(10),
            "waiting_interaction", Duration.ofSeconds(60),
            "rail_zone", Duration.ofSeconds(30));

    /**
     * 事实类型 → 时效。未知事实类型返回 {@link #DEFAULT_TTL}；返回 {@code ZERO}
     * 表示「该事实类型不允许用缓存/旧值，必须实时取证」。
     */
    public static Duration freshnessTtl(String factType) {
        if (factType == null || factType.isBlank()) {
            return Duration.ZERO;
        }
        return TTLS.getOrDefault(factType, DEFAULT_TTL);
    }

    private InterlockContract() {
    }
}
