package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实验「预计等待时间」估算（设计文档 §7.2，2026-11-03）。
 *
 * <p><b>解决什么问题</b>：实验点执行之后没有任何反馈，值班不知道是"马上跑"还是"排在
 * 3 个实验后面"。这里把两个数算出来：实验自身预计耗时（{@code estimateSec}）和
 * 预计等待（{@code estimatedWaitSec}）——后者只回答"同岛当前占用者还要跑多久 +
 * 同岛排在我前面的实验还要跑多久"。
 *
 * <p><b>纯函数与 SQL 分离</b>：{@link #estimateWait} 不碰库、可能单测
 * （{@code SchedulerEtaSelfTest}）；{@link #estimateExperimentSec} 等负责把库里的
 * 工时账读出来。本类<b>不持有</b> {@link ExperimentScheduler} 引用——调度器的内存态
 * 由 {@code ApiServer} 以 {@link ExperimentScheduler.QueueEntry}/
 * {@link ExperimentScheduler.LockEntry} 快照传入，这样估算逻辑可以脱离调度器测试，
 * 也避免上层两个接口各自拍快照的不一致。
 *
 * <p><b>局限（前端页面已写明，别当承诺）</b>：不含换型/清洗/人工处置时间；
 * 跨岛不互斥，但受 MAX_CONCURRENT 池上限影响，本估算只看岛锁；暂停中的占用者
 * 按"继续跑完"估算（不按"等人处理"估）。
 */
public final class SchedulerEta {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SchedulerEta.class);

    /** 没填预估工时时的兜底秒数（与甘特/时间条同一口径：没数据就按 60 秒）。 */
    private static final int DEFAULT_ESTIMATE_SEC = 60;

    /** 会继续消耗时间的节点状态（终态 = completed/failed/skipped/aborted，不再等）。 */
    private static final String UNFINISHED_NODE_STATUSES = "('pending','ready','running')";

    /**
     * 纯函数：预计等待秒数（§7.2 算法核心，必须可单测）。
     *
     * <ol>
     *   <li>实验正在运行 → 0（排队信息不再适用）；</li>
     *   <li>不在调度队列（{@code queuePosition == null}）→ null（不适用，前端显示「—」，
     *       <b>不能返回 0 冒充"马上开始"</b>）；</li>
     *   <li>在队列中 → {@code max(0, 岛当前持有者剩余估算) + Σ(同岛排在自己前面的实验 estimateSec)}。</li>
     * </ol>
     *
     * <p>输入里的负数（脏数据 / 时钟回拨）一律按 0 钳制，最终结果也保证 &gt;= 0；
     * {@code aheadEstimateSec} 为 null 当空容器处理。
     *
     * @param running                实验是否正在运行
     * @param queuePosition          队列位次（1 起；null = 不在队列）
     * @param islandHolderRemainSec  同岛当前占用者的剩余耗时估算（秒）
     * @param aheadEstimateSec       同岛排在自己前面的实验各自预计耗时（秒）；null 当空
     * @return 预计等待秒数；null = 不适用（不在运行也不在队列）
     */
    public static Long estimateWait(boolean running, Integer queuePosition,
                                    long islandHolderRemainSec,
                                    List<Long> aheadEstimateSec) {
        if (running) {
            return 0L;
        }
        if (queuePosition == null) {
            return null;
        }
        long total = Math.max(0L, islandHolderRemainSec);
        if (aheadEstimateSec != null) {
            for (Long sec : aheadEstimateSec) {
                if (sec != null) {
                    total += Math.max(0L, sec);
                }
            }
        }
        return Math.max(0L, total);
    }

    /**
     * 实验自身预计耗时（秒）：该实验各流程（{@code experiment_process.workflow_id}）
     * 的任务节点工时之和；没有流程 / 没有任务节点 → 0。
     *
     * <p><b>为什么不是裸 {@code COALESCE(estimate_sec, 60)}</b>：库里
     * {@code workflow_node.estimate_sec} 是 {@code NOT NULL DEFAULT 0}，裸 COALESCE
     * 永远不会命中兜底，没填工时的节点会按 0 秒算——排队估算会显示"约 0 秒"。
     * 这里把 0 视同"未估算"按 60 秒兜底，与甘特/时间条"没数据按 60 秒"同一口径。
     */
    public static long estimateExperimentSec(DbConfig db, long tenantId, long experimentId)
            throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(COALESCE(NULLIF(wn.estimate_sec, 0), 60)), 0) AS total
                  FROM experiment_process ep
                  JOIN workflow_node wn
                    ON wn.workflow_id = ep.workflow_id
                   AND wn.tenant_id = ep.tenant_id
                 WHERE ep.tenant_id = ? AND ep.experiment_id = ?
                   AND wn.node_type = 'task'
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0L, rs.getLong(1)) : 0L;
            }
        }
    }

    /**
     * 某个实验级 run 的剩余耗时估算（秒）：该 run 已展开的 {@code run_node} 里
     * 未完成节点（pending/ready/running）的工时之和；没有未完成节点 → 0。
     *
     * <p>链路：{@code experiment_run_step.run_id → run_node}。每个流程步骤是一次
     * workflow run，尚未开始的步骤还没有 run_node，因此本估算只覆盖"已经开跑、
     * 还没跑完"的部分——这是 §7.2 明确的公式，不是漏算：未来步骤的耗时算在
     * tenant 的 estimateSec 里，不进"占用者剩余"。
     */
    public static long estimateRunRemainSec(DbConfig db, long tenantId, long experimentRunId)
            throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(COALESCE(NULLIF(rn.estimate_sec, 0), 60)), 0) AS remain
                  FROM experiment_run_step s
                  JOIN run_node rn
                    ON rn.run_id = s.run_id
                   AND rn.tenant_id = s.tenant_id
                 WHERE s.tenant_id = ? AND s.experiment_run_id = ?
                   AND rn.status IN """ + UNFINISHED_NODE_STATUSES + """
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0L, rs.getLong(1)) : 0L;
            }
        }
    }

    /** ETA 列表里的一条实验（DB 行 + 调度器快照的合并视图）。 */
    private record ExpRow(long id, String experimentNo, String status,
                          Long islandId, String islandCode) {
    }

    /**
     * {@code GET /api/experiments/eta?limit=}：最近 {@code limit} 个实验的预计耗时/等待。
     *
     * <p>队列与岛锁取自调度器快照（{@link ExperimentScheduler#snapshot()}）：
     * 只有内核进程自己知道"谁在队列里、谁是岛的持有者"，库里的 experiment_run.status
     * 是执行器回写的、有延迟。
     *
     * <p><b>running 判定（§7.2 算法 1）</b>：以调度器<b>岛锁持有者</b>为准，实验表
     * {@code status=running} 兜底。**不看 {@code experiment_run.status}** ——
     * 历史遗留的 running 行（如 EXP0812 的 11-01 两条）会把早已结束的实验误显示成
     * "运行中"，ETA 整个算错（wait=0 + 前面排队的实验都按它占岛算）。
     *
     * @param queue 调度器队列快照（按 position 升序）；null 当空
     * @param locks 调度器岛锁快照；null 当空
     */
    public static String experimentsEtaJson(DbConfig db, long tenantId, int limit,
                                            List<ExperimentScheduler.QueueEntry> queue,
                                            List<ExperimentScheduler.LockEntry> locks)
            throws SQLException {
        int n = Math.max(1, Math.min(limit, 500));
        List<ExperimentScheduler.QueueEntry> queueSnap =
                queue == null ? List.of() : queue;
        List<ExperimentScheduler.LockEntry> lockSnap =
                locks == null ? List.of() : locks;

        Map<Long, ExperimentScheduler.QueueEntry> queueByExperiment = new HashMap<>();
        for (ExperimentScheduler.QueueEntry q : queueSnap) {
            // 同一实验可能有重复入队（多次提交）：取位次最靠前的作为它的队列视图
            queueByExperiment.putIfAbsent(q.experimentId(), q);
        }
        // 占用索引：按**资源**（2026-11-10 起不再是岛锁）。
        // 一个资源同一时刻只被一条 run 占着，所以按 resourceId 建索引。
        Map<Long, ExperimentScheduler.LockEntry> lockByResource = new HashMap<>();
        for (ExperimentScheduler.LockEntry l : lockSnap) {
            lockByResource.putIfAbsent(l.resourceId(), l);
        }

        // 最近 limit 个实验（id DESC，与实验列表同口径）
        List<ExpRow> rows = new ArrayList<>();
        String sql = """
                SELECT e.id, e.experiment_no, e.status, e.island_id, isl.code AS island_code
                  FROM experiment e
                  LEFT JOIN resource_node isl
                    ON isl.id = e.island_id AND isl.tenant_id = e.tenant_id
                 WHERE e.tenant_id = ?
                 ORDER BY e.id DESC
                 LIMIT ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long islandId = rs.getLong("island_id");
                    rows.add(new ExpRow(rs.getLong("id"), rs.getString("experiment_no"),
                            rs.getString("status"),
                            rs.wasNull() ? null : islandId, rs.getString("island_code")));
                }
            }
        }

        // §7.2 算法 1：running 以调度器岛锁持有者为准（内存态，"谁真的在跑"），
        // 实验表 status 兜底；不看 experiment_run.status（历史遗留 running 行会误判）。
        Set<Long> runningIds = new HashSet<>();
        for (ExperimentScheduler.LockEntry l : lockSnap) {
            if (l.experimentId() != null) {
                runningIds.add(l.experimentId());
            }
        }

        Map<Long, Long> estimateCache = new HashMap<>();
        Map<Long, Long> remainCache = new HashMap<>();
        List<String> items = new ArrayList<>();
        for (ExpRow r : rows) {
            boolean running = runningIds.contains(r.id()) || "running".equals(r.status());
            ExperimentScheduler.QueueEntry qe = queueByExperiment.get(r.id());

            String status;
            boolean queued = false;
            Integer position = null;
            Long estimateSec = null;
            Long wait = null;
            String reason = null;

            if (running) {
                status = "running";
                estimateSec = estimateSecCached(db, tenantId, r.id(), estimateCache);
                wait = estimateWait(true, null, 0L, null);
                reason = "运行中";
            } else if (qe != null) {
                queued = true;
                status = "queued";
                position = qe.position();
                estimateSec = estimateSecCached(db, tenantId, r.id(), estimateCache);

                // 等的是**抢资源的那些 run**，不是"同岛占用者"：
                // 与我需要的资源有交集、且不是我自己的那条占用，才算挡我的。
                Set<Long> need = new HashSet<>(qe.resources());
                ExperimentScheduler.LockEntry holder = null;
                long holderRemain = 0L;
                for (ExperimentScheduler.LockEntry l : lockSnap) {
                    if (!need.contains(l.resourceId())) {
                        continue;
                    }
                    if (l.experimentRunId() == qe.experimentRunId()) {
                        continue;   // 自己占的（重入）不算挡路
                    }
                    long remain = remainCache.computeIfAbsent(l.experimentRunId(),
                            rid2 -> estimateRemainQuietly(db, tenantId, rid2));
                    // 多个冲突里取**最晚释放**的那个 —— 它才是真正的瓶颈
                    if (remain > holderRemain) {
                        holderRemain = remain;
                        holder = l;
                    }
                }
                // 排在我前面、且与我抢资源（同一批资源）的实验
                List<Long> ahead = new ArrayList<>();
                for (ExperimentScheduler.QueueEntry other : queueSnap) {
                    if (other.position() >= qe.position()) {
                        continue;
                    }
                    if (other.resources() == null || other.resources().isEmpty()) {
                        continue;
                    }
                    if (need.isEmpty()) {
                        // 我没有绑定任何设备 ⇒ 不与任何人抢资源，不算"排在我前面"
                        continue;
                    }
                    boolean clash = other.resources().stream().anyMatch(need::contains);
                    if (clash) {
                        ahead.add(estimateSecCached(db, tenantId,
                                other.experimentId(), estimateCache));
                    }
                }
                wait = estimateWait(false, position, holderRemain, ahead);
                reason = queuedReason(holder, ahead.size());
            } else {
                // 不在运行也不在调度队列：留给前端显示「—」，不编一个 0
                status = r.status();
                wait = estimateWait(false, null, 0L, null);
            }

            items.add(Jsons.obj(
                    "experimentId", r.id(),
                    "experimentNo", r.experimentNo(),
                    "status", status,
                    "islandId", r.islandId(),
                    "islandCode", r.islandCode(),
                    "queued", queued,
                    "queuePosition", position,
                    "estimateSec", estimateSec,
                    "estimatedWaitSec", wait,
                    "reason", reason));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 预计等待的人话原因（前端 tooltip 直接显示）。 */
    /**
     * 排队原因（2026-11-10 起按**资源**口径，不再按岛）。
     *
     * <p>旧文案写"岛被 X 占用"——调度改成资源级之后，同岛不同实验是可以并行的，
     * 再说"岛被占"会误导值班员以为整个岛都被锁住了。
     */
    private static String queuedReason(ExperimentScheduler.LockEntry holder,
                                       int aheadCount) {
        StringBuilder sb = new StringBuilder();
        if (holder != null) {
            String who = holder.experimentNo() != null && !holder.experimentNo().isBlank()
                    ? holder.experimentNo() : "run#" + holder.experimentRunId();
            String what = holder.resourceName() != null && !holder.resourceName().isBlank()
                    ? holder.resourceName() : holder.resourceCode();
            sb.append("设备 ").append(what == null ? "" : what)
              .append(" 被 ").append(who).append(" 占用");
        } else {
            sb.append("排队中");
        }
        if (aheadCount > 0) {
            sb.append("，前面还有 ").append(aheadCount).append(" 个实验");
        } else if (holder == null) {
            sb.append("，等待派发");
        }
        return sb.toString();
    }

    private static long estimateSecCached(DbConfig db, long tenantId, long experimentId,
                                          Map<Long, Long> cache) {
        return cache.computeIfAbsent(experimentId,
                id -> estimateQuietly(db, tenantId, id));
    }

    /** 估算失败不拖垮整个 ETA 列表：按 0 处理（宁可少算，不让页面 500）。 */
    private static long estimateQuietly(DbConfig db, long tenantId, long experimentId) {
        try {
            return estimateExperimentSec(db, tenantId, experimentId);
        } catch (SQLException e) {
            log.error("[ETA] 实验 " + experimentId + " 工时估算失败: " + e.getMessage());
            return 0L;
        }
    }

    private static long estimateRemainQuietly(DbConfig db, long tenantId, long experimentRunId) {
        try {
            return estimateRunRemainSec(db, tenantId, experimentRunId);
        } catch (SQLException e) {
            log.error("[ETA] run " + experimentRunId + " 剩余估算失败: " + e.getMessage());
            return 0L;
        }
    }

    private SchedulerEta() {
    }
}
