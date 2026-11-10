package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动时的崩溃收尾。
 *
 * <p>解决的问题：进程在执行中途挂掉后，`run.status` 永远停在 `running`
 * （历史上清理出 51 条这样的僵尸记录），运维看到的是"一直在跑"，实际早没了。
 *
 * <p>做法分两步，**不自动续跑**：
 * <ol>
 *   <li><b>收尾</b>：把 `running` 的 run 标成 `failed`，并在 `input` 里写清楚
 *       "被中断、已完成几个节点、总共几个节点"</li>
 *   <li><b>标记可恢复</b>：如果有 completed 的节点，置 `recoverable=true`，
 *       供后续 {@code /api/runs/{id}/resume} 从断点继续</li>
 * </ol>
 *
 * <p>为什么不自动续跑：续跑要求每个命令幂等（开盖执行两次会怎样？），
 * 而存量驱动没有这个保证。自动续跑等于赌设备状态，赌错就是撞机。
 * 所以先收尾 + 报告，把"要不要续"的决定权交给人。
 */
public final class StartupRecovery {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StartupRecovery.class);

    /** Mapper 懒取器：本类是静态工具类，拿不到构造注入。 */
    private static com.labos.kernel.persistence.RunMapper rm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.RunMapper.class);
    }

    /** 一条被中断的 run。 */
    public record Orphan(
            long runId,
            String runNo,
            int totalNodes,
            int completedNodes,
            int failedNodes,
            boolean recoverable) {
    }

    public static List<Orphan> settle(DbConfig db, long tenantId) throws SQLException {
        return settle(db, tenantId, null);
    }

    /**
     * 启动兜底 settle 的包内重载：可限定只处理指定 run（自测用）。
     *
     * <p>与实验级 {@code settleOrphanExperimentRuns} 同款做法：
     * 自测若跑全量，会顺手把库里**真实**遗留的 running 一起收掉 ——
     * 那应该由重启日志来证明，不该被自测提前"消费"掉。
     *
     * @param onlyRunIds null / 空 = 全量；否则只处理这些 run id
     */
    static List<Orphan> settle(DbConfig db, long tenantId,
                               java.util.Collection<Long> onlyRunIds) throws SQLException {
        List<Orphan> orphans = new ArrayList<>();
        if (onlyRunIds != null && onlyRunIds.isEmpty()) {
            return orphans;
        }

        // ★ 先释放**孤儿设备锁**（current_run_id 指向的 run 已被删除）：
        //   run 被**直接删除**（清理脚本 / 后台清历史）时，device_state 里那把锁
        //   就成了孤儿 —— 没人能再通过 settle 找到它。孤儿锁的破坏性与幽灵锁一样：
        //   `DeviceLock.tryAcquire` 要求 current_run_id IS NULL 或等于自己 ⇒
        //   这台设备从此再也不会被任何 run 拿到（现场：一台设备凭空永远忙）。
        //   （2026-11-06 实测：测试删掉 run 后，机械臂卡在 current_run_id=<已删 run>。）
        //
        //   ⚠ 必须放在**扫描之前**：扫描后有 `if (found.isEmpty()) return` 的提前返回 ——
        //   放在后面的话，「本次没有孤儿 run」时孤儿锁就永远清不掉
        //   （2026-11-06 实测：机械臂的锁指向一个已被删除的 run，重启后仍然占着）。
        rm().releaseOrphanDeviceLocks(tenantId);

        List<Orphan> found = new ArrayList<>();
        // P1-7：running 立刻收；queued/pending 超 1 小时才收（刚入队的正常记录不误杀）。
        // P-08：命令账本 UNKNOWN/DISPATCH_STARTED 的不交普通 settle，留给隔离。
        for (Map<String, Object> row : rm().selectInterruptedRuns(tenantId, onlyRunIds)) {
            int done = ((Number) row.get("done")).intValue();
            found.add(new Orphan(
                    ((Number) row.get("id")).longValue(),
                    (String) row.get("runNo"),
                    ((Number) row.get("total")).intValue(),
                    done,
                    ((Number) row.get("bad")).intValue(),
                    // 有完成过节点才值得续跑；一个都没完成 = 从头跑，不算恢复
                    done > 0));
        }

        if (found.isEmpty()) {
            return orphans;
        }

        for (Orphan o : found) {
            rm().settleInterruptedRun(tenantId, o.runId(), Jsons.obj(
                    "interrupted", true,
                    "interruptedAt", java.time.Instant.now().toString(),
                    "completedNodes", o.completedNodes(),
                    "totalNodes", o.totalNodes(),
                    "failedNodes", o.failedNodes(),
                    "recoverable", o.recoverable()));

            // ★ 释放这台设备（可能被多台设备同时占着）占着的**设备锁**。
            //   为什么必须在这里放：进程被 kill / API 重启时，正在执行的设备方法
            //   不会走 onStepDone ⇒ markDeviceIdle 永远不执行 ⇒ device_state 永远停在
            //   status='running' + current_run_id=<已死的 run>，而 DeviceLock.tryAcquire
            //   的条件是 `current_run_id IS NULL OR = 自己` ⇒ 这台设备从此再也拿不到
            //   （幽灵占用）。口径与 DeviceLock.release 一致（RunMapper.releaseRunDeviceLocks）。
            rm().releaseRunDeviceLocks(tenantId, o.runId());
        }

        orphans.addAll(found);
        return orphans;
    }

    /** P-08：被隔离的 run（物理结果未知，等人工确认）。 */
    public record Quarantined(long runId, String runNo, Long experimentId) {
    }

    /**
     * P-08 第一切片：启动恢复隔离。
     *
     * <p>命令账本里已经出现 UNKNOWN / DISPATCH_STARTED 的 run，说明崩溃时设备
     * 可能有物理动作没拿到回包；这类 run 不能按普通 failed/recoverable 放回执行，
     * 统一标 quarantined 并抬实验级 Hold，等人工确认。
     *
     * <p>必须在 CommandLedger.reconcileOnStartup 之后调用。
     *
     * @return 本次隔离的 run
     */
    public static java.util.List<Quarantined> quarantineUncertainRuns(
            DbConfig db, long tenantId) throws SQLException {
        return quarantineUncertainRuns(db, tenantId, null);
    }

    /** 自测用重载：只处理指定 run，避免顺手隔离库里真实遗留。 */
    static java.util.List<Quarantined> quarantineUncertainRuns(
            DbConfig db, long tenantId, java.util.Collection<Long> onlyRunIds)
            throws SQLException {
        if (onlyRunIds != null && onlyRunIds.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<Quarantined> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : rm().selectUncertainRuns(tenantId, onlyRunIds)) {
            long runId = ((Number) row.get("id")).longValue();
            String runNo = (String) row.get("runNo");
            Long expId = rm().selectExperimentIdOfRun(tenantId, runId);
            rm().quarantineRun(tenantId, runId, Jsons.obj(
                    "quarantine", true,
                    "quarantineReason", "命令物理结果未知（启动隔离）",
                    "recoverable", false));
            if (expId != null) {
                new RuntimeHold(db, tenantId).raise(RuntimeHold.Scope.EXPERIMENT,
                        expId, RuntimeHold.Kind.HUMAN,
                        "启动隔离：物理结果未知 run#" + runId, null);
            }
            out.add(new Quarantined(runId, runNo, expId));
        }
        return out;
    }

    /**
     * P-08：人工确认隔离 run 的物理状态后放行（确认失败/终止）。
     *
     * <p>只允许从 quarantined 转移；同时解除隔离时抬的实验级 Hold。
     *
     * @return true=已处置
     */
    public static boolean resolveQuarantine(DbConfig db, long tenantId, long runId,
                                            long by, String note) throws SQLException {
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Long expId = rm().selectExperimentIdOfRun(tenantId, runId);
            int n = rm().confirmQuarantine(tenantId, runId, Jsons.obj(
                    "quarantine", false,
                    "quarantineConfirmed", true,
                    "quarantineNote", note == null ? "" : note));
            if (n > 0 && expId != null) {
                new RuntimeHold(db, tenantId).clearKindTx(c,
                        RuntimeHold.Scope.EXPERIMENT, expId, RuntimeHold.Kind.HUMAN, by,
                        "隔离解除：" + (note == null ? "人工确认" : note));
            }
            // ★ 人工确认后**释放该 run 占着的设备锁**。
            //   为什么隔离期间不释放、确认后才释放：隔离的含义是"物理结果未知"
            //   —— 设备上可能还有动作没结束 ⇒ 那台设备不能被别人拿去用（撞车）。
            //   但人工已经确认完（或终止）了，run 已是终态 ⇒ 锁必须还回去：
            //   否则这台设备**永久不可用**（DeviceLock.tryAcquire 要求
            //   current_run_id IS NULL 或等于自己），现场表现就是
            //   "一台设备凭空永远忙"，后续实验全卡在等它。
            //   （2026-11-11 实测：两条被隔离的 run 各自锁着一台设备，
            //   人工确认后锁一直没还 ⇒ 并发验收 B 段两台设备都拿不到。）
            if (n > 0) {
                rm().releaseRunDeviceLocks(tenantId, runId);
            }
            return n > 0;
        });
    }

    /** 收集"被中断且可恢复"的 run，供接口展示。 */
    public static List<Orphan> listRecoverable(DbConfig db, long tenantId) throws SQLException {
        // **只列近 7 天被中断的**（RunMapper.selectRecoverableRuns）。
        // 全量列出来是 42 条（存量数据），界面上一直挂着"有可恢复"的提示；
        // 更糟的是这些十天前的单子点"继续"会真的重跑，而设备状态、板位、配方
        // 早就变了 —— 那不是恢复，是制造事故。
        List<Orphan> out = new ArrayList<>();
        for (Map<String, Object> row : rm().selectRecoverableRuns(tenantId)) {
            out.add(new Orphan(((Number) row.get("id")).longValue(), (String) row.get("runNo"),
                    ((Number) row.get("total")).intValue(), ((Number) row.get("done")).intValue(),
                    ((Number) row.get("bad")).intValue(), true));
        }
        return out;
    }

    /**
     * 显式忽略一条"可恢复"记录（人判断不该续跑）。
     *
     * <p>为什么需要：7 天窗口只是兜底。窗口内也可能有"就是不该续跑"的单子
     * （比如设备已经拆了），不给忽略入口的话，提示会一直挂着。
     */
    public static boolean dismiss(DbConfig db, long tenantId, long runId) throws SQLException {
        try (Connection c = db.open()) {
            return dismissTx(c, tenantId, runId);
        }
    }

    /** 同 {@link #dismiss}，但复用调用方事务连接（P-06 V2 E-3b）。 */
    public static boolean dismissTx(Connection c, long tenantId, long runId)
            throws SQLException {
        return rm().dismissRecoverable(tenantId, runId,
                Jsons.obj("recoverable", false, "dismissed", true)) > 0;
    }

    // ========================================================================
    // experiment_run 孤儿 running 兜底 settle（BUG-14，2026-11-03）
    //
    // 与上面 {@code run} 表那套收尾是**两层不同的记录**：
    //   run            = 单条工艺流程的执行（WorkflowService 执行节点用）
    //   experiment_run = 一次实验的执行（一单实验 / 分批时一批一条）
    // 历史数据里 experiment_run 也留有 2026-11-01 的 status='running' 僵尸
    // （如 EXP0812 的 run#2/#3），会让"是否在运行"的判断、ETA/报表误判。
    // ========================================================================

    /** 启动兜底 settle 写进 {@code experiment_run.error} 的固定文案（自测也断言它）。 */
    public static final String STARTUP_SETTLE_ERROR =
            "API 重启：遗留 running 由启动兜底 settle（无活动线程持有）";

    /** 启动兜底 settle 掉的一条遗留 running（供启动日志/自测断言）。 */
    public record SettledExperimentRun(long runId, long experimentId, String experimentNo,
                                       Integer batchNo, boolean batchRun) {
    }

    /**
     * 启动兜底 settle：把库里遗留的 {@code experiment_run.status='running'} 全部落 failed。
     *
     * <h3>为什么可以一律 settle（口径说明）</h3>
     * <b>当前是单实例部署</b>：执行线程都活在本进程内，启动瞬间本进程还没有任何
     * 执行线程持有 run，所以库里此时仍是 {@code running} 的 experiment_run
     * <b>一定是上次进程退出/崩溃遗留的孤儿</b>（也可能是历史脏数据）。
     *
     * <p>若未来改成多实例部署，<b>本口径不再成立</b>：另一个实例可能正在跑一条
     * 合法的 running。届时要改成按 owner/心跳判断（例如给 experiment_run 加
     * owner 实例 id + 心跳时间，只 settle 心跳过期的），不能一律失败。
     *
     * <h3>落库与回写</h3>
     * <ol>
     *   <li>状态落 {@code failed}，{@code finished_at=now()}，
     *       {@code error=}{@link #STARTUP_SETTLE_ERROR}（说明是谁收的尾）；</li>
     *   <li>批次 run（{@code experiment_batch_run} 有归属）→ 交给
     *       {@link BatchOrchestrator#onRunStatusChanged(long)} 统一回写
     *       attempt + 批次汇总 + 按 D6 聚合实验状态；</li>
     *   <li>非批次 run（非分批路径整单一条 run）→ 走
     *       {@link ExperimentExecutor#syncExperimentStatus} 把实验表同步成 failed，
     *       与执行器 settle 的映射一致。</li>
     * </ol>
     *
     * <p>只收 {@code running}：{@code queued} 可能是别的事故（进程刚入队就崩），
     * 留给调度器/人工处置，避免把正常排队记录误杀；{@code paused} 是现场扣留态，
     * 更不该由重启自动改判。
     *
     * @return 本次 settle 的 run 列表（用于启动日志打印条数）
     */
    public static List<SettledExperimentRun> settleOrphanExperimentRuns(DbConfig db, long tenantId)
            throws SQLException {
        return settleOrphanExperimentRuns(db, tenantId, null);
    }

    /**
     * 启动兜底 settle 的包内重载：可限定只处理指定实验（自测用合成负数 id）。
     *
     * <p>生产只走上面的全量口径。自测若跑全量，会顺手把库里<b>真实</b>遗留的
     * running 一起收掉 —— 那应该由重启日志来证明，不该被自测提前"消费"掉。
     *
     * @param onlyExperimentIds null / 空 = 全量；否则只处理这些 experiment_id
     */
    static List<SettledExperimentRun> settleOrphanExperimentRuns(
            DbConfig db, long tenantId, java.util.Collection<Long> onlyExperimentIds)
            throws SQLException {
        if (onlyExperimentIds != null && onlyExperimentIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<SettledExperimentRun> found = new ArrayList<>();
        // 扫描（SELECT）与收尾（UPDATE）同处一个 Spring 事务：
        // 转换脚本原先只保留了 setAutoCommit 之后的 UPDATE，把扫描段丢了，
        // 导致 found 永远为空、启动兜底 settle 完全不生效（已由 StartupSettleSelfTest 抓到）。
        com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            for (Map<String, Object> row
                    : rm().selectOrphanExperimentRuns(tenantId, onlyExperimentIds)) {
                Object batchNoObj = row.get("batchNo");
                found.add(new SettledExperimentRun(
                        ((Number) row.get("id")).longValue(),
                        ((Number) row.get("experimentId")).longValue(),
                        (String) row.get("experimentNo"),
                        batchNoObj == null ? null : ((Number) batchNoObj).intValue(),
                        batchNoObj != null));
            }
            if (found.isEmpty()) {
                return null;      // 等价于原来的提前 return found
            }
            for (SettledExperimentRun s : found) {
                rm().settleOrphanExperimentRun(tenantId, s.runId(), STARTUP_SETTLE_ERROR);
            }
            return null;
        });

        // run 落 failed 之后再回写上层状态：
        // 批次 run 走 D8（attempt + 批次汇总 + 实验 D6 聚合）；非批次 run 走执行器同款映射。
        // 单条回写失败只记日志：启动不能被一条脏数据卡住（批次状态读侧还会自愈）。
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        for (SettledExperimentRun s : found) {
            try {
                if (s.batchRun()) {
                    orchestrator.onRunStatusChanged(s.runId());
                } else {
                    try (Connection c = db.open()) {
                        ExperimentExecutor.syncExperimentStatus(c, tenantId, s.runId());
                    }
                }
            } catch (Exception e) {
                log.error("[启动收尾] 实验状态回写失败 run#" + s.runId()
                        + ": " + e.getMessage());
            }
        }
        return found;
    }

    private StartupRecovery() {
    }
}
