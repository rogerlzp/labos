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
            return blocked(db, tenantId, runId, "EC-STATE-UNKNOWN",
                    "搅拌配对查询失败：" + e.getMessage());
        }
        if (stirrer == null) {
            return null;   // 未配对分液器：无事实源，不在此拦（配置层预检负责）
        }
        Map<String, Object> state;
        try {
            state = Mappers.get(DeviceStateMapper.class).selectDeviceStateRow(tenantId, stirrer);
        } catch (RuntimeException e) {
            return blocked(db, tenantId, runId, "EC-STATE-UNKNOWN",
                    "搅拌状态查询失败：" + e.getMessage());
        }
        String status = state == null ? null : (String) state.get("status");
        long observedAt = toMillis(state == null ? null : state.get("updatedAt"));
        StirrerDispenserGate.Verdict v = StirrerDispenserGate.check(status, observedAt, null);
        if (v.allow() || v.busy()) {
            return null;   // 可用 / 暂态忙（忙由设备锁有界等待处理）
        }
        return blocked(db, tenantId, runId, v.errorCode(), v.reason());
    }

    /** 拒绝下发 + 当前 run paused_fault（转人工恢复）。 */
    private static DriverResult blocked(DbConfig db, long tenantId, long runId,
                                        String code, String reason) {
        try {
            new PauseManager(db, tenantId).pause(runId, PauseManager.Kind.PAUSED_FAULT, reason, null);
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
