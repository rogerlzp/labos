package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceFactMapper;
import com.labos.kernel.persistence.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * P2 阶段 C · L1 转盘互锁的运行时守卫（机械臂操作交互位前查转盘事实）。
 *
 * <p>事实源 = {@code device_fact(turntable)}；目标设备无该事实时视为「非提取仪 / 无上报」
 * 跳过 L1。明确停止 + 新鲜放行；运动中做有界等待；过期/未知拒绝。
 */
public final class TurntableGuard {

    private static final Logger log = LoggerFactory.getLogger(TurntableGuard.class);

    /** 转盘运动中等待停止的最长时间（默认 60s，可用环境变量缩短）。 */
    private static long waitTimeoutMs() {
        String v = System.getenv("LABOS_TURNTABLE_WAIT_TIMEOUT_MS");
        if (v != null && !v.isBlank()) {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException ignore) {
                // 沿用默认
            }
        }
        return 60_000L;
    }

    /**
     * 搬运下发前检查。@return null = 放行（或目标无转盘事实）；非 null = 拒绝的失败结果。
     */
    public static DriverResult checkBeforeTransfer(DbConfig db, long tenantId, long runId,
                                                   long deviceResourceId) {
        if (db == null || runId <= 0 || deviceResourceId <= 0) {
            return null;
        }
        long deadline = System.currentTimeMillis() + waitTimeoutMs();
        boolean warned = false;
        for (;;) {
            Map<String, Object> fact;
            try {
                fact = Mappers.get(DeviceFactMapper.class)
                        .selectFact(tenantId, deviceResourceId, "turntable");
            } catch (RuntimeException e) {
                String reason = "转盘事实查询失败：" + e.getMessage();
                rec(db, tenantId, runId, deviceResourceId, InterlockDecision.REJECT,
                        null, 0, reason, "EC-STATE-UNKNOWN");
                return DriverResult.failed("0", "EC-STATE-UNKNOWN", reason, null, 0);
            }
            if (fact == null) {
                // 无转盘事实：非提取仪/无上报 → 跳过 L1。
                // 落一条 SKIP：否则表里空空，事后无法区分「放行」与「根本没判」。
                rec(db, tenantId, runId, deviceResourceId, InterlockDecision.SKIP,
                        null, 0, "无 turntable 事实（非提取仪/无上报），L1 跳过", null);
                return null;
            }
            String value = (String) fact.get("factValue");
            long observedAt = toMillis(fact.get("observedAt"));
            TurntableGate.Verdict v = TurntableGate.check(value, observedAt, null);
            if (v.allow()) {
                rec(db, tenantId, runId, deviceResourceId, InterlockDecision.ALLOW,
                        value, observedAt, v.reason(), null);
                return null;
            }
            if (!v.busy()) {
                rec(db, tenantId, runId, deviceResourceId, InterlockDecision.REJECT,
                        value, observedAt, v.reason(), v.errorCode());
                return DriverResult.failed("0", v.errorCode(), v.reason(), null, 0);
            }
            if (!warned) {
                log.warn("[L1] 目标设备 {} 转盘运动中，等待停止…", deviceResourceId);
                warned = true;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("[L1] 转盘等待停止超时 {}ms，拒绝搬运", waitTimeoutMs());
                String reason = "转盘运动中等待超时，拒绝搬运";
                rec(db, tenantId, runId, deviceResourceId, InterlockDecision.REJECT,
                        value, observedAt, reason, "EC-INTERLOCK-TURNTABLE");
                return DriverResult.failed("0", "EC-INTERLOCK-TURNTABLE", reason, null, 0);
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                String reason = "等待转盘停止被中断";
                rec(db, tenantId, runId, deviceResourceId, InterlockDecision.REJECT,
                        value, observedAt, reason, "EC-INTERLOCK-TURNTABLE");
                return DriverResult.failed("0", "EC-INTERLOCK-TURNTABLE", reason, null, 0);
            }
        }
    }

    /**
     * 锁内最终复核：下发前重读转盘事实，不再等待——运动中/过期/未知一律拒绝。
     * 与 {@link #checkBeforeTransfer} 的区别：check 在资源申请后做（可等待），
     * 这里在物理下发前做（状态已变就拒绝，不留等待窗口）。
     */
    public static DriverResult finalRecheck(DbConfig db, long tenantId, long deviceResourceId) {
        if (db == null || deviceResourceId <= 0) {
            return null;
        }
        Map<String, Object> fact;
        try {
            fact = Mappers.get(DeviceFactMapper.class)
                    .selectFact(tenantId, deviceResourceId, "turntable");
        } catch (RuntimeException e) {
            String reason = "转盘事实复核失败：" + e.getMessage();
            rec(db, tenantId, null, deviceResourceId, InterlockDecision.REJECT,
                    null, 0, reason, "EC-STATE-UNKNOWN");
            return DriverResult.failed("0", "EC-STATE-UNKNOWN", reason, null, 0);
        }
        if (fact == null) {
            return null;   // 无事实 → 非 L1 目标（初检已落 SKIP，这里不重复）
        }
        TurntableGate.Verdict v = TurntableGate.check(
                (String) fact.get("factValue"), toMillis(fact.get("observedAt")), null);
        if (v.allow()) {
            return null;   // 复核放行不重复落（初检已落 ALLOW）
        }
        String reason = "下发前复核：" + v.reason();
        rec(db, tenantId, null, deviceResourceId, InterlockDecision.REJECT,
                (String) fact.get("factValue"), toMillis(fact.get("observedAt")),
                reason, v.errorCode());
        return DriverResult.failed("0", v.errorCode(), reason, null, 0);
    }

    /**
     * 落一条 L1 决策快照（旁路审计，失败不影响判定）。
     *
     * <p>只在**最终判定点**调用，不在 2s 轮询循环里调用 —— 否则一次等待会写出几十条。
     * {@code finalRecheck} 只落拒绝：复核是同一决策的第二次，放行不重复落（避免每条命令翻倍）。
     */
    private static void rec(DbConfig db, long tenantId, Long runId, long resourceId,
                            String decision, String factValue, long observedAtMs,
                            String reason, String errorCode) {
        InterlockDecision.Rec r = new InterlockDecision.Rec(
                InterlockDecision.L1_TURNTABLE, decision)
                .fact("turntable", factValue)
                .times(observedAtMs > 0
                                ? java.time.Instant.ofEpochMilli(observedAtMs).toString() : null,
                        java.time.OffsetDateTime.now().toString())
                .reason(reason)
                .error(errorCode);
        InterlockDecision.record(db, tenantId, runId, null, null, resourceId, r);
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

    private TurntableGuard() {
    }
}
