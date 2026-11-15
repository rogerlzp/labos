package com.labos.kernel.driver;

import java.time.Duration;
import java.time.Instant;

/**
 * P2 阶段 C · L4 搅拌—分液互锁（纯逻辑）。
 *
 * <p>分液器启动前必须查配对搅拌单元状态。状态缺失/过期/通讯异常按「不安全」处理
 * （fail-closed）；明确可用且状态新鲜才放行。
 *
 * <p>状态分类（{@code InterlockContract §2.2} 同口径）：
 * <ul>
 *   <li>明确可用（available/idle/ready + 新鲜）→ 放行；</li>
 *   <li>故障（error/fault）→ 拒绝 + paused_fault；</li>
 *   <li>暂态忙（busy/running）→ 有界等待；</li>
 *   <li>状态过期 → 拒绝并刷新；刷新失败转人工；</li>
 *   <li>状态未知/缺失 → 拒绝转人工。</li>
 * </ul>
 */
public final class StirrerDispenserGate {

    /** 闸门判定。 */
    public record Verdict(boolean allow, boolean busy, String errorCode, String reason) {
        public static Verdict ok() {
            return new Verdict(true, false, null, "搅拌单元可用");
        }

        public static Verdict busy(String reason) {
            return new Verdict(false, true, "EC-RESOURCE-BUSY", reason);
        }

        public static Verdict reject(String errorCode, String reason) {
            return new Verdict(false, false, errorCode, reason);
        }
    }

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(10);

    /**
     * 分液前搅拌单元状态判定（纯函数）。
     *
     * @param status      搅拌单元状态（device_state.status）
     * @param observedAt  状态观测时间（epoch millis；0/负 = 未知）
     * @param ttl         允许时效；null 用默认 10s
     */
    public static Verdict check(String status, long observedAt, Duration ttl) {
        if (status == null || status.isBlank()) {
            return Verdict.reject("EC-STATE-UNKNOWN", "搅拌单元状态未知");
        }
        String s = status.trim().toLowerCase();
        if (isError(s)) {
            return Verdict.reject("EC-INTERLOCK-STIRRER", "搅拌单元故障：" + status);
        }
        if (isBusy(s)) {
            return Verdict.busy("搅拌单元暂态忙：" + status);
        }
        if (isAvailable(s)) {
            if (isStale(observedAt, ttl == null ? DEFAULT_TTL : ttl)) {
                return Verdict.reject("EC-STATE-STALE", "搅拌单元状态过期");
            }
            return Verdict.ok();
        }
        return Verdict.reject("EC-STATE-UNKNOWN", "搅拌单元状态无法识别：" + status);
    }

    private static boolean isError(String s) {
        return "error".equals(s) || "fault".equals(s) || "failed".equals(s)
                || "maintenance".equals(s) || "offline".equals(s);
    }

    private static boolean isBusy(String s) {
        return "busy".equals(s) || "running".equals(s) || "working".equals(s)
                || "processing".equals(s);
    }

    private static boolean isAvailable(String s) {
        return "available".equals(s) || "idle".equals(s) || "ready".equals(s)
                || "online".equals(s) || "standby".equals(s);
    }

    private static boolean isStale(long observedAt, Duration ttl) {
        if (observedAt <= 0) {
            return true;
        }
        return Instant.ofEpochMilli(observedAt).plus(ttl).isBefore(Instant.now());
    }

    private StirrerDispenserGate() {
    }
}
