package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
        StringBuilder runFilter = new StringBuilder();
        if (onlyRunIds != null) {
            runFilter.append(" AND r.id IN (");
            int i = 0;
            for (Long id : onlyRunIds) {
                if (i++ > 0) {
                    runFilter.append(',');
                }
                // 入参是 Long（自测合成负数 id），直接拼数字无注入风险
                runFilter.append(id);
            }
            runFilter.append(')');
        }

        String scan = """
                SELECT r.id, r.run_no,
                       COALESCE(r.input->>'attendance', 'attended') AS attendance,
                       count(n.id) FILTER (WHERE n.level = 0)                        AS total,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.status = 'completed') AS done,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.status IN ('failed','manual')) AS bad
                FROM run r
                LEFT JOIN run_node n ON n.run_id = r.id
                -- P1-7 修复（评审 20261102-03）：改动前只看 running。
                -- queued/pending 在进程崩溃后同样是僵尸（排队排到遥遥无期），
                -- 但**不能立刻判失败**——刚入队几秒的正常记录也会被误杀。
                -- 折中：running 立刻收；queued/pending 超过 1 小时才收。
                WHERE r.tenant_id = ?
                  AND (r.status = 'running'
                       OR (r.status IN ('queued', 'pending')
                           AND r.created_at < now() - interval '1 hour'))
                  -- P-08：物理结果未知（命令账本 UNKNOWN/DISPATCH_STARTED）的 run
                  -- 不交给普通 settle（否则会当 failed/recoverable 并释放设备锁）；
                  -- 由 quarantineUncertainRuns 隔离，保留设备锁等人工确认。
                  AND NOT EXISTS (SELECT 1 FROM command_ledger cl
                                   WHERE cl.tenant_id = r.tenant_id
                                     AND cl.run_id = r.id
                                     AND cl.state IN ('UNKNOWN', 'DISPATCH_STARTED'))
                """ + runFilter + """
                GROUP BY r.id, r.run_no, r.input
                ORDER BY r.id
                """;

        String settleSql = """
                UPDATE run
                SET status = 'failed',
                    actual_end = now(),
                    input = COALESCE(input, '{}'::jsonb) || ?::jsonb
                WHERE tenant_id = ? AND id = ?
                  AND status IN ('running', 'queued', 'pending')
                """;

        // ★ 释放被中断的 run 占着的**设备锁**（device_state.current_run_id）。
        //
        //   为什么必须在这里放：进程被 kill / API 重启时，正在执行的设备方法
        //   不会走 onStepDone ⇒ markDeviceIdle 永远不执行 ⇒
        //   device_state 永远停在 status='running' + current_run_id=<已死的 run>。
        //   而 DeviceLock.tryAcquire 的条件是
        //   `current_run_id IS NULL OR current_run_id = 自己`
        //   ⇒ 这台设备**从此再也不会被任何 run 拿到**（幽灵占用）。
        //   现场表现：一台设备凭空"永远忙"，所有流程都绕开它。
        //   （2026-11-06 实测：机械臂 ID1731 卡在 current_run_id=207893，
        //    那个 run 早就随 API 重启死了。）
        //
        //   口径与 DeviceLock.release 一致：清 current_run_id / current_method，
        //   无错时置 idle、有错时置 error。
        String releaseLockSql = """
                UPDATE device_state
                SET current_run_id = NULL,
                    current_method = NULL,
                    status = CASE WHEN last_error IS NULL THEN 'idle' ELSE 'error' END,
                    updated_at = now()
                WHERE tenant_id = ? AND current_run_id = ?
                """;

        // ★ 释放**孤儿设备锁**：current_run_id 指向的 run 已经不存在了。
        //
        //   为什么上面那条不够：它只处理「本次 settle 掉的 run」，
        //   而 run 被**直接删除**（清理脚本 / 后台清历史）时，
        //   device_state 里那把锁就成了孤儿 —— 没人能再通过 settle 找到它。
        //   孤儿锁的破坏性与幽灵锁一样：
        //   `DeviceLock.tryAcquire` 要求 current_run_id IS NULL 或等于自己
        //   ⇒ 这台设备**从此再也不会被任何 run 拿到**（现场：一台设备凭空永远忙）。
        //   （2026-11-06 实测：测试删掉 run 后，机械臂卡在 current_run_id=<已删 run>。）
        String releaseOrphanLockSql = """
                UPDATE device_state ds
                SET current_run_id = NULL,
                    current_method = NULL,
                    status = CASE WHEN ds.last_error IS NULL THEN 'idle' ELSE 'error' END,
                    updated_at = now()
                 WHERE ds.tenant_id = ?
                   AND ds.current_run_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM run r
                                    WHERE r.tenant_id = ds.tenant_id
                                      AND r.id = ds.current_run_id)
                """;

        try (Connection c = db.open()) {
            // ⚠ 孤儿设备锁的清理必须放在**扫描之前**：
            //   下面有 `if (found.isEmpty()) return orphans;` 的提前返回 ——
            //   放在后面的话，「本次没有孤儿 run」时孤儿锁就永远清不掉
            //   （2026-11-06 实测：机械臂的锁指向一个已被删除的 run，
            //    重启后仍然占着，那台设备又拿不到了）。
            try (PreparedStatement op = c.prepareStatement(releaseOrphanLockSql)) {
                op.setLong(1, tenantId);
                op.executeUpdate();
            }

            List<Orphan> found = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(scan)) {
                ps.setLong(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int total = rs.getInt("total");
                        int done = rs.getInt("done");
                        int bad = rs.getInt("bad");
                        found.add(new Orphan(
                                rs.getLong("id"),
                                rs.getString("run_no"),
                                total, done, bad,
                                // 有完成过节点才值得续跑；一个都没完成 = 从头跑，不算恢复
                                done > 0));
                    }
                }
            }

            if (found.isEmpty()) {
                return orphans;
            }

            try (PreparedStatement ps = c.prepareStatement(settleSql);
                 PreparedStatement lp = c.prepareStatement(releaseLockSql)) {
                for (Orphan o : found) {
                    ps.setString(1, Jsons.obj(
                            "interrupted", true,
                            "interruptedAt", java.time.Instant.now().toString(),
                            "completedNodes", o.completedNodes(),
                            "totalNodes", o.totalNodes(),
                            "failedNodes", o.failedNodes(),
                            "recoverable", o.recoverable()));
                    ps.setLong(2, tenantId);
                    ps.setLong(3, o.runId());
                    ps.executeUpdate();

                    // 释放这台设备（可能被多台设备同时占着）
                    lp.setLong(1, tenantId);
                    lp.setLong(2, o.runId());
                    lp.executeUpdate();
                }
            }

            orphans.addAll(found);
        }

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
        String runFilter = "";
        if (onlyRunIds != null) {
            StringBuilder sb = new StringBuilder(" AND r.id IN (");
            int i = 0;
            for (Long id : onlyRunIds) {
                if (i++ > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
            runFilter = sb.append(')').toString();
        }
        java.util.List<Quarantined> out = new java.util.ArrayList<>();
        try (Connection c = db.open()) {
            java.util.List<long[]> ids = new java.util.ArrayList<>();
            java.util.List<String> nos = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT r.id, r.run_no
                      FROM run r
                     WHERE r.tenant_id = ?
                       AND r.status IN ('running', 'failed')
                       AND EXISTS (SELECT 1 FROM command_ledger cl
                                    WHERE cl.tenant_id = r.tenant_id
                                      AND cl.run_id = r.id
                                      AND cl.state IN ('UNKNOWN', 'DISPATCH_STARTED'))""" + runFilter + """
                     ORDER BY r.id
                    """)) {
                ps.setLong(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(new long[]{rs.getLong(1)});
                        nos.add(rs.getString(2));
                    }
                }
            }
            for (int i = 0; i < ids.size(); i++) {
                long runId = ids.get(i)[0];
                Long expId = experimentIdOfRun(c, tenantId, runId);
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE run
                           SET status = 'quarantined'::run_status,
                               error_code = COALESCE(error_code, 'EC-QUARANTINE-UNKNOWN'),
                               input = COALESCE(input, '{}'::jsonb) || ?::jsonb,
                               actual_end = COALESCE(actual_end, now())
                         WHERE tenant_id = ? AND id = ?
                        """)) {
                    ps.setString(1, Jsons.obj(
                            "quarantine", true,
                            "quarantineReason", "命令物理结果未知（启动隔离）",
                            "recoverable", false));
                    ps.setLong(2, tenantId);
                    ps.setLong(3, runId);
                    ps.executeUpdate();
                }
                if (expId != null) {
                    new RuntimeHold(db, tenantId).raise(RuntimeHold.Scope.EXPERIMENT,
                            expId, RuntimeHold.Kind.HUMAN,
                            "启动隔离：物理结果未知 run#" + runId, null);
                }
                out.add(new Quarantined(runId, nos.get(i), expId));
            }
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
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                Long expId = experimentIdOfRun(c, tenantId, runId);
                int n;
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE run
                           SET status = 'failed'::run_status,
                               error_code = 'EC-QUARANTINE-CONFIRMED',
                               input = COALESCE(input, '{}'::jsonb) || ?::jsonb,
                               actual_end = now()
                         WHERE tenant_id = ? AND id = ? AND status = 'quarantined'
                        """)) {
                    ps.setString(1, Jsons.obj(
                            "quarantine", false,
                            "quarantineConfirmed", true,
                            "quarantineNote", note == null ? "" : note));
                    ps.setLong(2, tenantId);
                    ps.setLong(3, runId);
                    n = ps.executeUpdate();
                }
                if (n > 0 && expId != null) {
                    new RuntimeHold(db, tenantId).clearKindTx(c,
                            RuntimeHold.Scope.EXPERIMENT, expId, RuntimeHold.Kind.HUMAN, by,
                            "隔离解除：" + (note == null ? "人工确认" : note));
                }
                c.commit();
                return n > 0;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static Long experimentIdOfRun(Connection c, long tenantId, long runId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT er.experiment_id
                  FROM experiment_run_step s
                  JOIN experiment_run er
                    ON er.tenant_id = s.tenant_id AND er.id = s.experiment_run_id
                 WHERE s.tenant_id = ? AND s.run_id = ?
                 LIMIT 1
                """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** 收集"被中断且可恢复"的 run，供接口展示。 */
    public static List<Orphan> listRecoverable(DbConfig db, long tenantId) throws SQLException {
        List<Orphan> out = new ArrayList<>();
        String sql = """
                SELECT r.id, r.run_no,
                       (r.input->>'totalNodes')::int     AS total,
                       (r.input->>'completedNodes')::int AS done,
                       (r.input->>'failedNodes')::int    AS bad
                FROM run r
                WHERE r.tenant_id = ?
                  AND r.status = 'failed'
                  AND (r.input->>'recoverable')::boolean IS TRUE
                  -- **只列近 7 天被中断的**。
                  -- 全量列出来是 42 条（存量数据），界面上一直挂着
                  -- "有可恢复"的提示；更糟的是这些十天前的单子点"继续"会真的重跑，
                  -- 而设备状态、板位、配方早就变了 —— 那不是恢复，是制造事故。
                  AND COALESCE((r.input->>'interruptedAt')::timestamptz, r.actual_end)
                      > now() - interval '7 days'
                ORDER BY r.id DESC
                LIMIT 50
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Orphan(rs.getLong("id"), rs.getString("run_no"),
                            rs.getInt("total"), rs.getInt("done"), rs.getInt("bad"), true));
                }
            }
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
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE run
                   SET input = COALESCE(input, '{}'::jsonb)
                               || ?::jsonb,
                       updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status = 'failed'
                   AND (input->>'recoverable')::boolean IS TRUE
                """)) {
            ps.setString(1, Jsons.obj("recoverable", false, "dismissed", true));
            ps.setLong(2, tenantId);
            ps.setLong(3, runId);
            return ps.executeUpdate() > 0;
        }
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
        StringBuilder filter = new StringBuilder();
        if (onlyExperimentIds != null) {
            filter.append(" AND r.experiment_id IN (");
            int i = 0;
            for (Long id : onlyExperimentIds) {
                if (i++ > 0) {
                    filter.append(',');
                }
                // 入参是 Long（自测合成负数 id），直接拼数字无注入风险
                filter.append(id);
            }
            filter.append(')');
        }

        String scan = """
                SELECT DISTINCT r.id, r.experiment_id, e.experiment_no, b.batch_no
                  FROM experiment_run r
                  JOIN experiment e
                    ON e.tenant_id = r.tenant_id AND e.id = r.experiment_id
                  LEFT JOIN experiment_batch_run br
                    ON br.tenant_id = r.tenant_id AND br.experiment_run_id = r.id
                  LEFT JOIN experiment_batch b
                    ON b.tenant_id = br.tenant_id AND b.id = br.batch_id
                 WHERE r.tenant_id = ? AND r.status = 'running'
                """ + filter + " ORDER BY r.id";

        List<SettledExperimentRun> found = new ArrayList<>();
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(scan)) {
                ps.setLong(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // wasNull() 只反映"最后一次 get"的列，必须紧跟着 batch_no 取，
                        // 否则后面再读 id/experiment_no 会把判断覆盖掉（属 batch_run 判定错的坑）
                        long batchNo = rs.getLong("batch_no");
                        boolean batchRun = !rs.wasNull();
                        found.add(new SettledExperimentRun(
                                rs.getLong("id"), rs.getLong("experiment_id"),
                                rs.getString("experiment_no"),
                                batchRun ? (int) batchNo : null,
                                batchRun));
                    }
                }
            }
            if (found.isEmpty()) {
                return found;
            }
            // 收尾本身放同一个事务：要么全部落 failed，要么一条都不动
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE experiment_run
                           SET status = 'failed', finished_at = now(), error = ?
                         WHERE tenant_id = ? AND id = ? AND status = 'running'
                        """)) {
                    for (SettledExperimentRun s : found) {
                        ps.setString(1, STARTUP_SETTLE_ERROR);
                        ps.setLong(2, tenantId);
                        ps.setLong(3, s.runId());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }

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
