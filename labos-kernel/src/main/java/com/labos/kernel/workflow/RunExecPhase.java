package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;

/**
 * run 的执行阶段（P-09，整改计划 Batch 0.1）。
 *
 * <p><b>解决什么</b>：崩溃重启后看到一条 {@code status='running'} 的 run，
 * 必须先回答「它到底派发过设备命令没有」：
 * <ul>
 *   <li>没派发过 ⇒ 可以安全地当没发生（清理 / 重跑）；</li>
 *   <li>派发过 ⇒ 板可能已经动了，物理结果未知，只能隔离 + 人工核对。</li>
 * </ul>
 * 以前只能用 {@code run_command} 反推，而那张表是**步结束才落库**的 ——
 * 崩在「已下发、未回包」的窗口时它数出来是 0，恰好把最危险的情况误判成安全。
 *
 * <p><b>为什么单独一列而不是塞进 status</b>：{@code status} 是业务生命周期
 * （queued/running/paused/…），{@code exec_phase} 是执行体视角的落点，
 * 两者正交。典型例子：{@code paused} 的 run 执行体已经退出，但这一单没走完，
 * 恢复时还要接着跑 ⇒ phase 停在 {@link #DISPATCH_STARTED}，不能算 settled。
 *
 * <p><b>写法都是幂等 + 单向</b>：phase 只向前走
 * （PREPARED → DISPATCH_STARTED → SETTLED），任何一步重复调用都不会倒退。
 */
public final class RunExecPhase {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RunExecPhase.class);

    /** 建单完成，还没派发过任何设备命令。 */
    public static final String PREPARED = "PREPARED";
    /** 已经派发过设备命令（物理结果可能未知）。 */
    public static final String DISPATCH_STARTED = "DISPATCH_STARTED";
    /** 执行体已收尾（run 已落终态）。 */
    public static final String SETTLED = "SETTLED";

    private RunExecPhase() {
    }

    /**
     * 派发前的升级点：**只从 PREPARED 升级**，已经在 DISPATCH_STARTED/SETTLED 就不动。
     *
     * <p>幂等且低成本（主键单行更新，条件命中才写），可以每条命令前都调一次。
     */
    public static void markDispatched(DbConfig db, long tenantId, long runId)
            throws SQLException {
        com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExecutionMapper.class)
                .markDispatched(tenantId, runId);
    }

    /** 收尾：执行体不再派发新命令。paused 不调这个（那一单还没走完）。 */
    public static void markSettled(DbConfig db, long tenantId, long runId)
            throws SQLException {
        com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExecutionMapper.class)
                .markSettled(tenantId, runId);
    }

    /** 读当前阶段；行不存在返回 {@code null}。 */
    public static String of(DbConfig db, long tenantId, long runId) throws SQLException {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExecutionMapper.class)
                .selectExecPhase(tenantId, runId);
    }

    /**
     * 崩溃恢复用：这条 run 是否**可能已经动过设备**。
     *
     * <p>只有明确 {@link #PREPARED} 才算"没动过"；读不到阶段（异常/空）一律按
     * 动过处理 —— fail-closed，宁可让人看一眼，也不自动重跑一条可能已经搬过板的单。
     */
    public static boolean maybeDispatched(DbConfig db, long tenantId, long runId) {
        try {
            String phase = of(db, tenantId, runId);
            return phase == null || !PREPARED.equals(phase);
        } catch (SQLException e) {
            log.error("[WARN] 读 exec_phase 失败，按「可能已派发」处理 run#" + runId
                    + "：" + e.getMessage());
            return true;
        }
    }
}
