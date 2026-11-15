package com.labos.kernel.driver;

import java.time.Duration;
import java.time.Instant;

/**
 * P2 阶段 C · L1 转盘互锁（纯逻辑）。
 *
 * <p>机械臂操作提取仪交互位前，转盘必须明确停止且状态新鲜。状态缺失/过期/未知按
 * 不安全处理（fail-closed）；明确运动中 → 有界等待；明确停止 + 新鲜 → 放行。
 */
public final class TurntableGate {

    /** 闸门判定。 */
    public record Verdict(boolean allow, boolean busy, String errorCode, String reason) {
        public static Verdict ok() {
            return new Verdict(true, false, null, "转盘已停止");
        }

        public static Verdict busy(String reason) {
            return new Verdict(false, true, "EC-INTERLOCK-TURNTABLE", reason);
        }

        public static Verdict reject(String errorCode, String reason) {
            return new Verdict(false, false, errorCode, reason);
        }
    }

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

    /**
     * 转盘状态判定（纯函数）。
     *
     * @param factValue  device_fact(turntable).fact_value（moving/stopped 等）
     * @param observedAt 观测时间（epoch millis；0/负 = 未知）
     * @param ttl        允许时效；null 用默认 5s
     */
    public static Verdict check(String factValue, long observedAt, Duration ttl) {
        if (factValue == null || factValue.isBlank()) {
            return Verdict.reject("EC-STATE-UNKNOWN", "转盘状态未知");
        }
        String s = factValue.trim().toLowerCase();
        if (isMoving(s)) {
            return Verdict.busy("转盘运动中");
        }
        if (isStopped(s)) {
            if (isStale(observedAt, ttl == null ? DEFAULT_TTL : ttl)) {
                return Verdict.reject("EC-STATE-STALE", "转盘状态过期");
            }
            return Verdict.ok();
        }
        return Verdict.reject("EC-STATE-UNKNOWN", "转盘状态无法识别：" + factValue);
    }

    private static boolean isMoving(String s) {
        return "moving".equals(s) || "rotating".equals(s) || "running".equals(s)
                || "spinning".equals(s);
    }

    private static boolean isStopped(String s) {
        return "stopped".equals(s) || "idle".equals(s) || "stationary".equals(s)
                || "ready".equals(s);
    }

    private static boolean isStale(long observedAt, Duration ttl) {
        if (observedAt <= 0) {
            return true;
        }
        return Instant.ofEpochMilli(observedAt).plus(ttl).isBefore(Instant.now());
    }

    private TurntableGate() {
    }
}
