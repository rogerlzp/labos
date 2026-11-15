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
                return DriverResult.failed("0", "EC-STATE-UNKNOWN",
                        "转盘事实查询失败：" + e.getMessage(), null, 0);
            }
            if (fact == null) {
                return null;   // 无转盘事实：非提取仪/无上报 → 跳过 L1
            }
            String value = (String) fact.get("factValue");
            long observedAt = toMillis(fact.get("observedAt"));
            TurntableGate.Verdict v = TurntableGate.check(value, observedAt, null);
            if (v.allow()) {
                return null;
            }
            if (!v.busy()) {
                return DriverResult.failed("0", v.errorCode(), v.reason(), null, 0);
            }
            if (!warned) {
                log.warn("[L1] 目标设备 {} 转盘运动中，等待停止…", deviceResourceId);
                warned = true;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("[L1] 转盘等待停止超时 {}ms，拒绝搬运", waitTimeoutMs());
                return DriverResult.failed("0", "EC-INTERLOCK-TURNTABLE",
                        "转盘运动中等待超时，拒绝搬运", null, 0);
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return DriverResult.failed("0", "EC-INTERLOCK-TURNTABLE",
                        "等待转盘停止被中断", null, 0);
            }
        }
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
