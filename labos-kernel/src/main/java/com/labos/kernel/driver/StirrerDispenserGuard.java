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
                rec(db, tenantId, runId, dispenserResourceId, InterlockDecision.ALLOW,
                        status, observedAt, v.reason(), null);
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
     * 锁内最终复核：下发前重读搅拌状态，不再等待——故障/过期/未知一律拒绝。
     * 不 pause（初始检查已 pause 过）；只返回失败结果。
     *
     * <p><b>⚠ 未配对 ⇒ 跳过复核（不是拒绝）**（2026-11-15 修，实测全平台停摆）：
     * <ul>
     *   <li><b>改之前</b>：配对缺失一律 {@code EC-STATE-UNKNOWN} 拒绝。
     *       而这个复核是**对每一条设备命令**调用的（不限于分液方法），
     *       且 {@code stirrer_dispenser_pair} 在客户现场落地前一直是 <b>0 行</b>
     *       ⇒ <b>平台里每一台设备的每一条命令都被拒</b>
     *       （实测：提取仪的 PickTipComb 直接 EC-STATE-UNKNOWN 转人工）。
     *       ⇒ 这不是"守门更严"，是<b>整台平台发不出命令</b>。</li>
     *   <li><b>为什么应当跳过</b>：L4 的事实源是「配对搅拌单元的状态」。
     *       没有配对就<b>没有事实源</b> ⇒ 这台设备根本不是 L4 的目标
     *       ⇒ 同门的 L1（无 turntable 事实 ⇒ 非 L1 目标）与
     *       L5（无交互上下文 ⇒ 跳过）都是这么处理的。</li>
     *   <li><b>「配对缺失 fail-closed」有没有丢？</b>：没有 ——
     *       {@link #checkBeforeDispense} 仍然对<b>真正的分液方法</b>
     *       （Aliquot/Dispense/Pipette/TransferLiquid）在<b>下发前</b>
     *       把「无搅拌配对」拒掉（配对缺失属配置层问题），
     *       且那条路径会 pause 转人工。finalRecheck 只是"已放行后的锁内再确认"，
     *       不承担配置缺失的判定。</li>
     * </ul>
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
            String reason = "搅拌配对复核失败：" + e.getMessage();
            rec(db, tenantId, null, dispenserResourceId, InterlockDecision.REJECT,
                    null, 0, reason, "EC-STATE-UNKNOWN");
            return DriverResult.failed("0", "EC-STATE-UNKNOWN", reason, null, 0);
        }
        if (stirrer == null) {
            // 无配对 ⇒ 无事实源 ⇒ 非 L4 目标 ⇒ 跳过（与 L1/L5 一致）。
            // 「配对缺失」的 fail-closed 由 checkBeforeDispense 在分液方法上把关。
            return null;
        }
        Map<String, Object> state;
        try {
            state = Mappers.get(DeviceStateMapper.class).selectDeviceStateRow(tenantId, stirrer);
        } catch (RuntimeException e) {
            String reason = "搅拌状态复核失败：" + e.getMessage();
            rec(db, tenantId, null, dispenserResourceId, InterlockDecision.REJECT,
                    null, 0, reason, "EC-STATE-UNKNOWN");
            return DriverResult.failed("0", "EC-STATE-UNKNOWN", reason, null, 0);
        }
        String status = state == null ? null : (String) state.get("status");
        long observedAt = toMillis(state == null ? null : state.get("updatedAt"));
        StirrerDispenserGate.Verdict v = StirrerDispenserGate.check(status, observedAt, null);
        if (v.allow()) {
            return null;   // 复核放行不重复落（初检已落 ALLOW）
        }
        String reason = "下发前复核：" + v.reason();
        rec(db, tenantId, null, dispenserResourceId, InterlockDecision.REJECT,
                status, observedAt, reason, v.errorCode());
        return DriverResult.failed("0", v.errorCode(), reason, null, 0);
    }

    /**
     * 落一条 L4 决策快照（旁路审计，失败不影响判定）。
     *
     * <p>只在最终判定点调用（拒绝统一走 {@link #blocked}），不在 2s 等待循环里。
     * {@code finalRecheck} 只落拒绝：复核是同一决策的第二次，放行不重复落。
     */
    private static void rec(DbConfig db, long tenantId, Long runId, long resourceId,
                            String decision, String factValue, long observedAtMs,
                            String reason, String errorCode) {
        InterlockDecision.Rec r = new InterlockDecision.Rec(
                InterlockDecision.L4_STIRRER, decision)
                .fact("stirrer_status", factValue)
                .times(observedAtMs > 0
                                ? java.time.Instant.ofEpochMilli(observedAtMs).toString() : null,
                        java.time.OffsetDateTime.now().toString())
                .reason(reason)
                .error(errorCode);
        InterlockDecision.record(db, tenantId, runId, null, null, resourceId, r);
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
        // 统一拒绝出口：这里落一条 REJECT 即可覆盖本类全部拒绝分支（查询失败/未配对/
        // 状态不可用/超时/中断），不必在每个 return 前各写一遍。
        rec(db, tenantId, runId, dispenserResourceId, InterlockDecision.REJECT,
                null, 0, reason, code);
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
