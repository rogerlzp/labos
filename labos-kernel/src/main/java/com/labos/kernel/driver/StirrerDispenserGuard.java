package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.StirrerMapper;
import com.labos.kernel.workflow.PauseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * P2 阶段 C · L4 搅拌—分液互锁的运行时守卫（分液器启动前查配对搅拌单元状态）。
 *
 * <p>守卫点 = 分液命令下发前（统一派发门禁内）；事实源 = 配对搅拌单元的
 * {@code device_state.status + updated_at}。状态故障/过期/未知 → 拒绝下发 + 当前 run
 * {@code paused_fault}（转人工恢复）。
 *
 * <p>暂态忙（busy/running）不在本守卫拦截（由设备锁的有界等待处理）；未配对的分液器
 * 无事实源，也不在此拦截（配对缺失由配置层负责，按未知处理的语义留给上电预检）。
 */
public final class StirrerDispenserGuard {

    private static final Logger log = LoggerFactory.getLogger(StirrerDispenserGuard.class);

    /**
     * 分液命令下发前检查。@return null = 放行（或非分液方法/未配对）；非 null = 已拒绝的失败结果。
     */
    public static DriverResult checkBeforeDispense(DbConfig db, long tenantId, long runId,
                                                   long dispenserResourceId, String method) {
        if (db == null || runId <= 0 || dispenserResourceId <= 0 || !isDispenseMethod(method)) {
            return null;
        }
        Long stirrer;
        try {
            stirrer = Mappers.get(StirrerMapper.class)
                    .selectStirrerByDispenser(tenantId, dispenserResourceId);
        } catch (RuntimeException e) {
            return blocked(db, tenantId, runId, dispenserResourceId, "EC-STATE-UNKNOWN",
                    "搅拌配对查询失败：" + e.getMessage());
        }
        if (stirrer == null) {
            // 评审 P0：配对缺失按未知处理，不放行分液。
            return blocked(db, tenantId, runId, dispenserResourceId, "EC-STATE-UNKNOWN",
                    "分液器无搅拌配对（配置缺失，fail-closed）");
        }
        // 有界等待：busy 时重查，直到可用或超时；error/过期/未知直接拒绝。
        long deadline = System.currentTimeMillis() + stirrerWaitTimeoutMs();
        for (;;) {
            Map<String, Object> state;
            try {
                state = Mappers.get(DeviceStateMapper.class).selectDeviceStateRow(tenantId, stirrer);
            } catch (RuntimeException e) {
                return blocked(db, tenantId, runId, dispenserResourceId, "EC-STATE-UNKNOWN",
                        "搅拌状态查询失败：" + e.getMessage());
            }
            String status = state == null ? null : (String) state.get("status");
            long observedAt = toMillis(state == null ? null : state.get("updatedAt"));
            StirrerDispenserGate.Verdict v = StirrerDispenserGate.check(status, observedAt, null);
            if (v.allow()) {
                return null;
            }
            if (!v.busy()) {
                return blocked(db, tenantId, runId, dispenserResourceId, v.errorCode(), v.reason());
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("[L4] 搅拌单元 busy 等待超时 {}ms，拒绝分液", stirrerWaitTimeoutMs());
                return blocked(db, tenantId, runId, dispenserResourceId, "EC-RESOURCE-BUSY",
                        "搅拌单元暂态忙等待超时");
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return blocked(db, tenantId, runId, dispenserResourceId, "EC-RESOURCE-BUSY",
                        "等待搅拌单元被中断");
            }
        }
    }

    /**
     * 锁内最终复核：下发前重读搅拌状态，不再等待——故障/过期/未知/未配对一律拒绝。
     * 不 pause（初始检查已 pause 过）；只返回失败结果。
     */
    public static DriverResult finalRecheck(DbConfig db, long tenantId, long dispenserResourceId) {
        if (db == null || dispenserResourceId <= 0) {
            return null;
        }
        Long stirrer;
        try {
            stirrer = Mappers.get(StirrerMapper.class)
                    .selectStirrerByDispenser(tenantId, dispenserResourceId);
        } catch (RuntimeException e) {
            return DriverResult.failed("0", "EC-STATE-UNKNOWN",
                    "搅拌配对复核失败：" + e.getMessage(), null, 0);
        }
        if (stirrer == null) {
            return DriverResult.failed("0", "EC-STATE-UNKNOWN",
                    "下发前复核：分液器无搅拌配对", null, 0);
        }
        Map<String, Object> state;
        try {
            state = Mappers.get(DeviceStateMapper.class).selectDeviceStateRow(tenantId, stirrer);
        } catch (RuntimeException e) {
            return DriverResult.failed("0", "EC-STATE-UNKNOWN",
                    "搅拌状态复核失败：" + e.getMessage(), null, 0);
        }
        String status = state == null ? null : (String) state.get("status");
        StirrerDispenserGate.Verdict v = StirrerDispenserGate.check(
                status, toMillis(state == null ? null : state.get("updatedAt")), null);
        if (v.allow()) {
            return null;
        }
        return DriverResult.failed("0", v.errorCode(), "下发前复核：" + v.reason(), null, 0);
    }

    private static long stirrerWaitTimeoutMs() {
        String v = System.getenv("LABOS_STIRRER_WAIT_TIMEOUT_MS");
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
     * P2 阶段 C · L4 恢复前重新取证：paused_fault 恢复前必须重新读搅拌单元状态，
     * 只有「明确可用 + 新鲜」才算恢复，不得仅凭状态从 error 变回 idle 自动续跑。
     *
     * @return true = 搅拌单元已恢复（可用 + 新鲜）；false = 仍故障/过期/未知/未配对。
     */
    public static boolean isRecovered(DbConfig db, long tenantId, long runId,
                                      long dispenserResourceId) {
        if (db == null || runId <= 0 || dispenserResourceId <= 0) {
            return false;
        }
        Long stirrer;
        try {
            stirrer = Mappers.get(StirrerMapper.class)
                    .selectStirrerByDispenser(tenantId, dispenserResourceId);
        } catch (RuntimeException e) {
            return false;
        }
        if (stirrer == null) {
            return false;
        }
        Map<String, Object> state;
        try {
            state = Mappers.get(DeviceStateMapper.class).selectDeviceStateRow(tenantId, stirrer);
        } catch (RuntimeException e) {
            return false;
        }
        String status = state == null ? null : (String) state.get("status");
        long observedAt = toMillis(state == null ? null : state.get("updatedAt"));
        return StirrerDispenserGate.check(status, observedAt, null).allow();
    }

    /**
     * 拒绝下发 + 暂停所有受影响 run（paused_fault）。
     * 同一搅拌单元服务多个并发 run 时，受影响的分液器 run 都要停，不能只停先发现的一条。
     * 恢复由心跳自动恢复（网络离线恢复）或操作员手动恢复（物理故障，可绕过重取证）。
     */
    private static DriverResult blocked(DbConfig db, long tenantId, long runId,
                                        long dispenserResourceId, String code, String reason) {
        try {
            java.util.List<Long> affected;
            try {
                affected = Mappers.get(com.labos.kernel.persistence.PauseMapper.class)
                        .selectActiveRunsUsingDevice(tenantId, dispenserResourceId);
            } catch (RuntimeException e) {
                affected = new java.util.ArrayList<>();
            }
            if (affected == null) {
                affected = new java.util.ArrayList<>();
            }
            if (!affected.contains(runId)) {
                affected = new java.util.ArrayList<>(affected);
                affected.add(runId);
            }
            PauseManager pm = new PauseManager(db, tenantId);
            for (long r : affected) {
                try {
                    pm.pause(r, PauseManager.Kind.PAUSED_FAULT, reason, null);
                } catch (Exception e) {
                    log.warn("[L4] 暂停 run {} 失败：{}", r, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[L4] paused_fault 暂停失败（仍拒绝下发）：{}", e.getMessage());
        }
        return DriverResult.failed("0", code, reason, null, 0);
    }

    /** 分液/移液类方法才触发 L4 守卫。 */
    public static boolean isDispenseMethod(String method) {
        if (method == null || method.isBlank()) {
            return false;
        }
        String m = method.trim();
        return m.startsWith("Aliquot") || m.startsWith("Dispense")
                || m.startsWith("Pipette") || m.startsWith("TransferLiquid");
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

    private StirrerDispenserGuard() {
    }
}
