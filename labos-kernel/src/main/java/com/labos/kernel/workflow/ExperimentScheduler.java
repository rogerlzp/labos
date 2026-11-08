package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实验调度器：多实验并行的唯一入口。
 *
 * <p><b>核心约束：岛级互斥</b>。一个岛通常只有一台机械臂，两个实验同时跑会打架，
 * 而且机械臂分不清板子归属——可能把 A 的板送到 B 的目的地。
 *
 * <p><b>为什么是"贪心 + 滚动"而不是最优解</b>：
 * 工时精度只有 10 秒级、波动比最大 13.5×，最优解和贪心的实际差距小于工时误差。
 * 且贪心可解释（"它截止最早"），值班员能手工干预。
 *
 * <p><b>岛锁带 TTL</b>：执行线程可能被 kill -9，finally 跑不到。
 * 没有 TTL 的话岛会永久卡死。代价是"可能两个实验同跑一个岛"——比永久卡死好。
 */
public final class ExperimentScheduler {

    /** 岛锁默认 TTL：超过这个时间的占用视为泄漏。 */
    private static final Duration LOCK_TTL = Duration.ofHours(2);

    /** 调度循环间隔。 */
    private static final long TICK_MS = 2_000L;

    /** 同时执行的实验数上限（不是岛数，避免线程爆炸）。 */
    private static final int MAX_CONCURRENT = 4;

    /**
     * 扣留解除后自动续队的扫描节奏：每 N 个 tick 一次（{@link #TICK_MS}=2s ⇒ 约 10s）。
     *
     * <p>为什么不是每 tick 都扫：这是兜底恢复路径（人工解除扣留后），
     * 不是热路径；每 2 秒全表扫一遍 experiment_batch 没有必要。
     */
    private static final int AUTO_CONTINUE_EVERY_TICKS = 5;

    /** 一次自动续队最多处理几个候选批（防止解除后一次建太多、打乱队列）。 */
    private static final int AUTO_CONTINUE_MAX_PER_TICK = 5;

    private record Lock(long experimentRunId, Instant at) {
    }

    /**
     * 队列里的一条待派发实验。包内可见：{@code workflow.SchedulerOrderSelfTest} 直接构造它
     * 来钉住 {@link #QUEUE_ORDER} 的三级排序，不必起调度循环。
     */
    record Queued(long experimentRunId, long experimentId, long islandId,
                  int priority, String deadline, long submittedAt) {
    }

    /**
     * 快照里的一条岛锁（可观测接口 §7.1 / ETA §7.2 共用）。
     *
     * <p>{@code experimentId/experimentNo/islandCode} 来自联查；库抖动时可能为 null，
     * 但 {@code islandId/experimentRunId/since/expiresAt} 永远来自内存态，不受影响。
     */
    public record LockEntry(long islandId, String islandCode, long experimentRunId,
                            Long experimentId, String experimentNo,
                            String since, String expiresAt) {
    }

    /** 快照里的一条排队项，{@code position} 从 1 开始（按派发口径排序后的位次）。 */
    public record QueueEntry(long experimentRunId, long experimentId, long islandId,
                             String experimentNo, String islandCode,
                             int priority, String deadline, String submittedAt,
                             int position) {
    }

    /**
     * 调度器某一刻的自述（一次快照，避免 status 与 ETA 各拍各的）。
     *
     * <p>{@code started} 按 §7.1 口径是<b>累计派发次数</b>（number；boolean
     * "调度循环是否已启动"语义保留在新字段 {@code loopStarted}，避免读取现有字段的
     * 客户端把 true/false 当计数）。
     */
    public record Snapshot(List<LockEntry> islandLocks, List<QueueEntry> queue,
                           int running, int queued, int maxConcurrent,
                           boolean stalled, String lastDispatchAt, long started,
                           boolean loopStarted) {
    }

    /**
     * 队列快照排序口径：<b>与 {@link #dispatch()} 完全一致</b>——
     * deadline ASC（无截止最后，EDD 优先）→ priority DESC → submittedAt ASC。
     *
     * <p>为什么必须和 dispatch() 同口径：页面上的「位次」唯一的用途是回答
     * "我还要等几个才轮到"；快照按别的规则排，位次就是假的。§7.1 草稿把 priority
     * 写在 deadline 前面，与实际派发不一致，2026-11-03 统一为 EDD 优先。
     */
    static final Comparator<Queued> QUEUE_ORDER = Comparator
            .comparing((Queued q) -> q.deadline() == null ? "9999" : q.deadline())
            .thenComparing(Comparator.comparingInt(Queued::priority).reversed())
            .thenComparingLong(Queued::submittedAt);

    private final DbConfig db;
    private final long tenantId;
    private final WorkflowService workflows;

    /** 岛 -> 锁。 */
    private final Map<Long, Lock> islandLocks = new ConcurrentHashMap<>();
    /** 待执行队列（每次派发前重新排序，量小够用）。 */
    private final List<Queued> queue = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT);

    /**
     * 当前**正在跑**的实验数。
     *
     * <p><b>P1-6 修复（评审 20261102-03）</b>：tick 里改动前是
     * {@code int dispatched = 0} —— 每次调度都从 0 数，
     * <b>上一轮提交、仍在跑的实验完全不计入</b>。
     * 于是每轮都能再塞 4 个，固定线程池的队列无限增长，
     * 且这些排队任务**已经占了岛锁**（锁在 submit 前拿）——
     * 岛被"占着但没在跑"，别的实验也用不了。
     */
    private final java.util.concurrent.atomic.AtomicInteger running =
            new java.util.concurrent.atomic.AtomicInteger();
    /** §7.1「累计派发次数」：内存态计数，进程重启归零（与 started/lastDispatchAt 同口径）。 */
    private final java.util.concurrent.atomic.AtomicLong dispatched =
            new java.util.concurrent.atomic.AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 调度循环 tick 计数（只在 loop 线程读写，用于自动续队节流）。 */
    private long ticks;

    private volatile Instant lastDispatchAt = Instant.now();

    public ExperimentScheduler(DbConfig db, long tenantId, WorkflowService workflows) {
        this.db = db;
        this.tenantId = tenantId;
        this.workflows = workflows;
    }

    /** 启动调度循环（幂等）。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::loop, "experiment-scheduler");
        t.setDaemon(true);
        t.start();
        System.out.println("[调度] 已启动（岛级互斥，最多 " + MAX_CONCURRENT + " 个实验并行）");
    }

    /**
     * 提交实验（异步）。
     *
     * @return experimentRunId
     */
    public long submit(long experimentId) throws SQLException {
        return submit(experimentId, null);
    }

    /**
     * 提交实验（异步）并携带用户变量 overrides（S3.2-C）。
     *
     * <p>变量在<b>建 run 时</b>写进 {@code experiment_run.variables}：
     * 调度器执行这条 run 时再读出来作为执行器的用户 overrides
     * （见 {@link #executeOne}）。这样异步提交与同步执行对变量语义一致，
     * 进程重启后 rehydrate 也不会丢变量。
     *
     * @param vars 用户变量覆盖，可空（null/空 = {@code '{}'}）
     * @return experimentRunId
     */
    public long submit(long experimentId, Map<String, String> vars) throws SQLException {
        long islandId = islandOf(experimentId);
        int priority = priorityOf(experimentId);
        String deadline = deadlineOf(experimentId);

        long runId = insertRun(experimentId, vars);

        synchronized (queue) {
            queue.add(new Queued(runId, experimentId, islandId,
                    priority, deadline, System.currentTimeMillis()));
        }
        return runId;
    }

    /**
     * 重启 rehydrate：把库里仍是 {@code queued} 的 experiment_run 重新装进派发队列（S3.2-A）。
     *
     * <h3>解决什么问题</h3>
     * 调度器队列是<b>内存态</b>。进程重启后，之前已入队的 run（async 提交的整单 run、
     * 异步分批已建出来的下一批 queued run）在库里还是 queued，但内存队列空了 ——
     * 没人再把它入队的话它会永远不执行，实验页面一直显示"排队中"。
     *
     * <h3>口径</h3>
     * <ul>
     *   <li>只取 {@code experiment_run.status='queued'}，联查实验拿 island/priority/deadline
     *       （与 {@link #submit} 入队字段同源，派发排序口径不变）；</li>
     *   <li><b>跳过已在内存队列里的 run</b>：重复调用幂等（启动时只调一次，运行中不调）；</li>
     *   <li>{@code submittedAt} 用<b>当前时间</b>：重启后无法还原真实提交时间，
     *       用 now 让这些恢复的 run 排在"重启后新提交"的同类项之前/之后都可解释
     *       （实际主排序是 deadline/priority，submittedAt 只是最后一级）。</li>
     * </ul>
     *
     * <p>只做入队，不派发：派发由调度循环按现场扣留闸门统一判定。
     * 读库失败直接抛给调用方（ApiServer 启动收尾只打日志、不阻断启动）。
     *
     * @return 本次新入队的条数（0 = 没有待派发 run 或都已在队列里）
     */
    public int rehydrateQueued() throws SQLException {
        List<Queued> candidates = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT r.id AS run_id, r.experiment_id,
                            COALESCE(e.island_id, 0) AS island_id,
                            COALESCE(e.priority, 50) AS priority,
                            e.deadline
                       FROM experiment_run r
                       JOIN experiment e
                         ON e.tenant_id = r.tenant_id AND e.id = r.experiment_id
                      WHERE r.tenant_id = ? AND r.status = 'queued'
                      ORDER BY r.id
                     """)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                long now = System.currentTimeMillis();
                while (rs.next()) {
                    Timestamp dl = rs.getTimestamp("deadline");
                    candidates.add(new Queued(rs.getLong("run_id"),
                            rs.getLong("experiment_id"), rs.getLong("island_id"),
                            rs.getInt("priority"),
                            dl == null ? null : dl.toLocalDateTime().toString(), now));
                }
            }
        }
        int added = 0;
        synchronized (queue) {
            for (Queued q : candidates) {
                boolean present = false;
                for (Queued exist : queue) {
                    if (exist.experimentRunId() == q.experimentRunId()) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    continue;
                }
                queue.add(q);
                added++;
            }
        }
        return added;
    }

    /**
     * 把一条**已存在**的 run 重新入队（暂停/失败处置完成后恢复用）。
     *
     * <p>与 {@link #submit} 的区别：不新建 experiment_run —— 恢复要沿用同一条记录，
     * 否则断点续跑（跳过 status=completed 的步）就没依据了。
     * 已在队列里则不重复入队。
     */
    public void resumeRun(long experimentId, long runId) throws SQLException {
        long islandId = islandOf(experimentId);
        int priority = priorityOf(experimentId);
        String deadline = deadlineOf(experimentId);
        synchronized (queue) {
            for (Queued q : queue) {
                if (q.experimentRunId() == runId) {
                    return;
                }
            }
            queue.add(new Queued(runId, experimentId, islandId,
                    priority, deadline, System.currentTimeMillis()));
        }
    }

    /** 调度循环。 */
    private void loop() {
        while (true) {
            try {
                sweepExpiredLocks();
                dispatch();
                // S3.2-B：扣留解除后的自动续队，每 5 个 tick（约 10s）扫一次。
                // 放在 dispatch 之后：正常派发优先；扫描要查库，不能挡派发。
                ticks++;
                if (ticks % AUTO_CONTINUE_EVERY_TICKS == 0) {
                    autoContinueHeldBatches();
                }
            } catch (Exception e) {
                System.err.println("[调度] 循环异常: " + e.getMessage());
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 扣留解除后的自动续队（S3.2-B，§11.3-1）。
     *
     * <h3>解决什么问题</h3>
     * 批边界遇现场扣留时，候选批标 paused、<b>不建 attempt</b>（D7：闸门没解除不能放板子）。
     * 改动前的口径要求人解除扣留后再手动 {@code POST /run?async=1} 从该批继续 ——
     * 忘了点就永远停在 paused。本方法在扣留全部解除后自动补建 attempt 并入队。
     *
     * <h3>口径</h3>
     * <ol>
     *   <li><b>任何扣留生效时一律不动</b>（{@link RuntimeHold#siteState()}）：现场暂停是安全事件，
     *       只有全部解除才允许继续；</li>
     *   <li>候选来自 {@link BatchOrchestrator#pausedBatchCandidates()}：
     *       paused 且<b>无任何尝试</b>、实验不在终态；</li>
     *   <li>逐条再用 {@link RuntimeHold#coverageFor(long, long)} 判覆盖（site/island/experiment
     *       三级作用域）—— 防御"扫描后、建单前"恰好有人新扣留的窗口；</li>
     *   <li>调 {@link BatchOrchestrator#createNextBatchAttempt(long, int, Map)}（afterBatchNo=batchNo-1）
     *       建下一批；只有 CREATED 才 {@link #resumeRun} 入队 + 打日志；</li>
     *   <li>每 tick 最多处理 {@link #AUTO_CONTINUE_MAX_PER_TICK} 个候选，单条失败只记日志。</li>
     * </ol>
     *
     * <p><b>为什么不是"自动重跑失败批"</b>：候选限定"无任何 attempt"，即只补"批边界从未启动"
     * 的那批；有失败尝试的批必须人工处置（D3 失败批不原地重试）。
     *
     * @return 本次新建并入队的 run 条数（自测用；生产只关心日志）
     */
    int autoContinueHeldBatches() {
        int created = 0;
        try {
            if (new RuntimeHold(db, tenantId).siteState().blocks()) {
                return 0;
            }
            List<BatchOrchestrator.PausedBatch> candidates =
                    new BatchOrchestrator(db, tenantId).pausedBatchCandidates();
            int handled = 0;
            for (BatchOrchestrator.PausedBatch cand : candidates) {
                if (handled >= AUTO_CONTINUE_MAX_PER_TICK) {
                    break;
                }
                handled++;
                try {
                    long islandId = islandOf(cand.experimentId());
                    if (new RuntimeHold(db, tenantId)
                            .coverageFor(cand.experimentId(), islandId).blocks()) {
                        continue;
                    }
                    BatchOrchestrator.NextBatchAttempt next =
                            new BatchOrchestrator(db, tenantId).createNextBatchAttempt(
                                    cand.experimentId(), cand.batchNo() - 1, null);
                    if (next.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED) {
                        resumeRun(cand.experimentId(), next.runId());
                        created++;
                        System.out.println("[调度] 扣留解除，自动续跑实验 " + cand.experimentId()
                                + " 第 " + next.batchNo() + " 批（run#" + next.runId() + "）");
                    }
                } catch (Exception e) {
                    System.err.println("[调度] 自动续队失败 实验 " + cand.experimentId()
                            + " 第 " + cand.batchNo() + " 批: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // 自动续队是兜底恢复路径，任何失败都不能影响正常派发
            System.err.println("[调度] 自动续队扫描失败: " + e.getMessage());
        }
        return created;
    }

    /** 派发：按 EDD → 优先级 → 提交时间排序，抢岛。 */
    private void dispatch() {
        List<Queued> snapshot;
        synchronized (queue) {
            if (queue.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(queue);
        }

        // 现场闸门（fail-closed，P-03 / ADR-PAUSE-005）：有任何生效扣留
        // （site/island/experiment）时不派发新实验；**读不到扣留状态时同样不派发**。
        // 旧实现在读库异常时 catch 后放行（"宁可少停，不能把调度卡死"），
        // 那等于「读不到 Hold ⇒ 继续派发」，现场状态未知时让机械臂继续动作，是安全漏洞。
        // 现在改为 UNKNOWN ⇒ 阻断，等库恢复 / 人工确认后下一轮 tick 自动继续。
        if (new RuntimeHold(db, tenantId).siteState().blocks()) {
            return;
        }

        // 单一来源：直接复用 QUEUE_ORDER，避免页面位次（同样用 QUEUE_ORDER）与
        // 实际出队顺序各写一份比较器、日后只改一份导致两边分叉。
        snapshot.sort(QUEUE_ORDER);

        // P1-6：从"当前在跑数"起算，而不是每轮归零
        int runningNow = running.get();
        for (Queued q : snapshot) {
            if (runningNow >= MAX_CONCURRENT) {
                break;
            }
            Long holder = islandHolder(q.islandId());
            if (holder != null && holder != q.experimentRunId()) {
                continue;   // 岛被占，试下一个
            }
            if (!islandLocks.containsKey(q.islandId())
                    && !tryLockIsland(q.islandId(), q.experimentRunId())) {
                continue;
            }

            synchronized (queue) {
                queue.remove(q);
            }
            runningNow++;
            dispatched.incrementAndGet();
            running.incrementAndGet();
            lastDispatchAt = Instant.now();

            pool.submit(() -> executeOne(q));
        }
    }

    /** 执行一个实验，结束后释放岛；批次 run 跑完还要把下一批续进队列（异步分批）。 */
    private void executeOne(Queued q) {
        // D9：先探测这条 run 是不是批次 run。批次 run 不能让单条 run 的 settle
        // 覆盖 experiment.status（那会把"某批跑完"显示成"整单完成"），
        // 实验状态必须按 D6 用所有批次聚合 —— 由 D8 统一回写。
        // 非批次 run 保持现有调用（sync=true），行为一个字不变。
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        Long batchRunId = null;
        BatchOrchestrator.BatchContext batchCtx = null;
        try {
            // 终态由 ExperimentExecutor.settle 统一落库（含 experiment 表联动），
            // 调度器不再回写 —— 回写会把 paused/aborted 覆盖成失败。
            ExperimentExecutor exec = new ExperimentExecutor(db, tenantId, workflows);
            batchRunId = orchestrator.batchRunIdFor(q.experimentRunId());
            // S3.2-C：异步提交时用户传的 vars 落在 experiment_run.variables；执行前读出来
            // 作为执行器的用户 overrides（与同步 run 的变量语义一致）。
            // 读不到（存量数据 '{}'）就是空 overrides，行为与改动前一样按默认值跑。
            Map<String, String> userVars = orchestrator.runVariables(q.experimentRunId());
            // 异步分批的 run 是"先建 queued、后由调度器执行"，D5 的 batchNo/batchSamples
            // 没人替它注入 —— 按批次快照反查后在这里补上（同步分批路径由 executeBatch 注入）。
            Map<String, String> batchVars = null;
            if (batchRunId != null) {
                batchCtx = orchestrator.batchContextFor(q.experimentRunId());
                batchVars = batchCtx != null
                        ? BatchPlanner.batchVariables(batchCtx.batchNo(),
                                batchCtx.barcodesCsv(), userVars)
                        : userVars;
            }
            ExperimentExecutor.Result r = batchRunId == null
                    // 非批次路径：保持语义不变（sync=true，执行器自己同步实验状态）
                    ? exec.run(q.experimentId(), userVars, q.experimentRunId())
                    : exec.run(q.experimentId(), batchVars, q.experimentRunId(), false);
            if (batchRunId != null) {
                // D8：run 落终态后统一回写 attempt 行 + 批次汇总 + 实验聚合状态。
                // 回写失败只记日志，**不能**冒到外层 catch —— 否则会把刚跑完的 run
                // 当成"执行器抛异常"再 markStatus(failed)，用假失败覆盖真终态。
                // 批次层是读侧自愈的：批状态下次读 / 重跑该批时会按 run 状态纠正。
                try {
                    orchestrator.onRunStatusChanged(q.experimentRunId());
                } catch (SQLException e) {
                    System.err.println("[调度] 批次状态回写失败 run#" + q.experimentRunId()
                            + ": " + e.getMessage());
                }
            }
            System.out.println("[调度] 实验 " + q.experimentId() + " 结束: " + r.status()
                    + " " + r.doneSteps() + "/" + r.totalSteps());
        } catch (Exception e) {
            // 执行器在 settle 之前就抛了（例如展开为空），兜底标失败。
            try {
                markStatus(q.experimentRunId(), "failed",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (SQLException ignore) {
                // 连状态都写不进去，只能记日志
            }
            if (batchRunId != null) {
                // D9：异常兜底标记 failed 后同样要回写批次层，
                // 否则这条尝试/批次停在 running，挡住后续批与派发。
                try {
                    orchestrator.onRunStatusChanged(q.experimentRunId());
                } catch (SQLException ignore) {
                    // 回写失败不阻断调度收尾（批次状态读侧还会自愈，可人工重跑该批）
                }
            }
            System.err.println("[调度] 实验 " + q.experimentId() + " 失败: " + e.getMessage());
        } finally {
            // 必须释放，否则岛永久卡住
            // P1-5 修复（评审 20261102-03）：改动前的 remove(islandId) 是**无条件删**——
            // 如果本实验的锁已因 TTL 过期被清理、岛又被别的实验占了，
            // 这一句会把**别人的锁删掉**，于是同岛两个实验并行跑。
            // 改成带值比较：只有"当前持有者仍是自己"时才删。
            // P1-6：无论成败都要减计数，否则并发名额被永久占用
            running.decrementAndGet();

            islandLocks.computeIfPresent(q.islandId(),
                    (k, v) -> v.experimentRunId() == q.experimentRunId() ? null : v);
        }

        // 异步分批续队：放到岛锁释放之后，下一批重新参与派发竞争
        // （不能在上面的 try 里建——那会让"下一批"在这条执行线程还没交还岛锁时进队列）。
        if (batchRunId != null && batchCtx != null) {
            continueNextBatch(q.experimentId(), batchCtx.batchNo(), q.experimentRunId());
        }
    }

    /**
     * 批次续队（异步分批）：一条批次 run 跑完后，按 D3 把"下一批"建出来并重新入队。
     *
     * <h3>语义边界（与同步分批 D3 对齐）</h3>
     * <ul>
     *   <li><b>失败继续</b>：本批 failed/completed 都继续下一批（D3）；</li>
     *   <li><b>aborted / paused 不继续</b>：人工停止（aborted）后剩余批保持 pending，
     *       不自动重跑；paused 是现场扣留/暂停，等人工恢复当前 run（恢复后跑完会再次触发本方法）；</li>
     *   <li><b>扣留中不继续</b>：批边界有覆盖扣留时 {@link BatchOrchestrator#createNextBatchAttempt}
     *       返回 HELD，候选批标 paused、实验聚合 paused。解除扣留后由
     *       {@link #autoContinueHeldBatches()} 每 5 个 tick 自动续队（S3.2-B），
     *       也可人工 POST /run?async=1 立即从该批继续；</li>
     *   <li>续队失败只记日志：下一批没建出来不影响本批已落的状态，批次层读侧可自愈，
     *       也可人工重新发起 async 执行（会从第一非完成批继续）。</li>
     * </ul>
     *
     * @param finishedBatchNo 刚跑完那批的 batchNo（下一批只从它之后找，失败批不原地重跑）
     */
    private void continueNextBatch(long experimentId, int finishedBatchNo, long finishedRunId) {
        try {
            BatchOrchestrator.NextBatchAttempt next =
                    planNextBatch(experimentId, finishedBatchNo, finishedRunId);
            if (next == null) {
                System.out.println("[调度] 实验 " + experimentId + " 第 " + finishedBatchNo
                        + " 批已停止/暂停，不再自动续下一批");
                return;
            }
            switch (next.outcome()) {
                case CREATED -> {
                    resumeRun(experimentId, next.runId());
                    System.out.println("[调度] 实验 " + experimentId + " 续跑第 "
                            + next.batchNo() + " 批（attempt " + next.attemptNo()
                            + "，run#" + next.runId() + "）");
                }
                case EXISTING -> {
                    if (next.queueable()) {
                        resumeRun(experimentId, next.runId());
                    }
                    System.out.println("[调度] 实验 " + experimentId + " 已有批次 run#"
                            + next.runId() + "（" + next.status() + "），批次续队跳过");
                }
                case HELD -> System.out.println("[调度] 实验 " + experimentId
                        + " 批次续队停在批边界：" + next.holdReason() + "（第 " + next.batchNo()
                        + " 批标 paused，全部扣留解除后自动续队）");
                case NO_MORE -> System.out.println("[调度] 实验 " + experimentId
                        + " 所有批次已跑完，聚合状态 " + next.status());
                default -> {
                    // NOT_BATCHED 不会发生在批次 run 上；防御性忽略
                }
            }
        } catch (Exception e) {
            // 续队失败不冒泡：执行线程的 finally 已经释放岛锁，这里只是"下一批没建出来"
            System.err.println("[调度] 实验 " + experimentId + " 批次续队失败: " + e.getMessage());
        }
    }

    /**
     * 续队决策（只读库 + 建下一批尝试，<b>不碰调度队列</b>，自测可直接验证）。
     *
     * <p>单独抽出来的原因：本方法的判定是"异步分批语义"的核心（aborted/paused 不续、
     * 失败继续、扣留停在边界），而 continueNextBatch 还要操作内存队列、不好在
     * 连库自测里构造。把决策抽成只依赖库的方法，语义就能用假执行体 + 合成数据验证。
     *
     * @return {@code null}=本 run 是 aborted/paused，明确不续；
     *         否则为 {@link BatchOrchestrator#createNextBatchAttempt} 的结果
     *         （CREATED/EXISTING/NO_MORE/HELD/NOT_BATCHED 由调用方分派）
     */
    BatchOrchestrator.NextBatchAttempt planNextBatch(long experimentId, int finishedBatchNo,
                                                     long finishedRunId) throws SQLException {
        // 以库里的真实状态为准：异常兜底路径可能刚把 run 标 failed，
        // 但人工停止也可能已把它改成 aborted —— 后者不能续下一批。
        String actual = runStatusOf(finishedRunId);
        if ("aborted".equals(actual) || "paused".equals(actual)) {
            return null;
        }
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        // S3.2-C：续下一批时沿用<b>同一份用户变量</b>（各批变量一致）。
        // 刚结束 run 的 variables 是执行器合并后的（含默认值 + 上一批的 batchNo/batchSamples），
        // 用 continueVars 剥掉批上下文，避免上一批的批号以"用户变量"身份覆盖下一批。
        Map<String, String> userVars =
                BatchOrchestrator.continueVars(orchestrator.runVariables(finishedRunId));
        return orchestrator.createNextBatchAttempt(experimentId, finishedBatchNo, userVars);
    }

    /** 读一条 run 的当前状态（续队前以库为准）。 */
    private String runStatusOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM experiment_run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---------------------------------------------------------- 岛锁

    /**
     * 尝试占岛。
     *
     * <p>用 putIfAbsent 而不是"先查再放"——后者有窗口，两个实验会同时认为岛是空的。
     * 同一个 run 重复占（重入）视为成功。
     */
    private boolean tryLockIsland(long islandId, long experimentRunId) {
        Lock existing = islandLocks.putIfAbsent(islandId,
                new Lock(experimentRunId, Instant.now()));
        return existing == null || existing.experimentRunId() == experimentRunId;
    }

    /** 谁占着这个岛（null 表示空闲）。 */
    private Long islandHolder(long islandId) {
        Lock l = islandLocks.get(islandId);
        return l == null ? null : l.experimentRunId();
    }

    /** 清理超时未释放的锁。 */
    private void sweepExpiredLocks() {
        Instant cutoff = Instant.now().minus(LOCK_TTL);
        islandLocks.entrySet().removeIf(e -> e.getValue().at().isBefore(cutoff));
    }

    // ---------------------------------------------------------- 查询

    private record LockView(long islandId, long experimentRunId, Instant at) {
    }

    /**
     * 调度器自述快照：内存态（锁/队列）+ 一次批量联查（实验编号、岛编码）。
     *
     * <p>为什么先出快照再转 JSON：§7.1 的 /api/scheduler/status 与 §7.2 的
     * /api/experiments/eta 用的是**同一份**队列/岛锁数据。两处各拍一次快照会出现
     * 「锁是 A 的、队列是 B 时刻的」这种自相矛盾的输出。这里只拍一次，下游各自渲染。
     *
     * <p>联查严格 N+1 规避：岛编码、锁 run → 实验、队列实验编号各一条批量 SQL
     * （{@code = ANY(数组)}，不是循环查）。
     *
     * <p>联查失败（库抖动/断连）只记日志并返回内存态（编码为 null），**不整体抛错**：
     * /api/scheduler/status 是事故时的排查入口，不能因为一次联查失败就 500，
     * 「谁占着哪个岛、队列里有几条」这些最关键的信息仍要能看。
     */
    public Snapshot snapshot() {
        List<LockView> lockViews = new ArrayList<>();
        islandLocks.forEach((islandId, lock) ->
                lockViews.add(new LockView(islandId, lock.experimentRunId(), lock.at())));
        lockViews.sort(Comparator.comparingLong(LockView::islandId));

        List<Queued> queueNow;
        synchronized (queue) {
            queueNow = new ArrayList<>(queue);
        }
        queueNow.sort(QUEUE_ORDER);

        Set<Long> islandIds = new HashSet<>();
        Set<Long> lockRunIds = new HashSet<>();
        Set<Long> queueExpIds = new HashSet<>();
        for (LockView l : lockViews) {
            islandIds.add(l.islandId());
            lockRunIds.add(l.experimentRunId());
        }
        for (Queued q : queueNow) {
            if (q.islandId() > 0) {
                islandIds.add(q.islandId());
            }
            queueExpIds.add(q.experimentId());
        }

        Map<Long, String> islandCodes = new HashMap<>();
        Map<Long, Long> expIdByRun = new HashMap<>();
        Map<Long, String> expNoByRun = new HashMap<>();
        Map<Long, String> expNoById = new HashMap<>();
        try (Connection c = db.open()) {
            loadIslandCodes(c, islandIds, islandCodes);
            loadRunExperiments(c, lockRunIds, expIdByRun, expNoByRun);
            loadExperimentNos(c, queueExpIds, expNoById);
        } catch (SQLException e) {
            System.err.println("[调度] 状态联查失败（只返回内存态）: " + e.getMessage());
        }

        List<LockEntry> lockEntries = new ArrayList<>();
        for (LockView l : lockViews) {
            lockEntries.add(new LockEntry(l.islandId(), islandCodes.get(l.islandId()),
                    l.experimentRunId(), expIdByRun.get(l.experimentRunId()),
                    expNoByRun.get(l.experimentRunId()),
                    l.at().toString(), l.at().plus(LOCK_TTL).toString()));
        }
        List<QueueEntry> queueEntries = new ArrayList<>();
        int position = 0;
        for (Queued q : queueNow) {
            position++;
            queueEntries.add(new QueueEntry(q.experimentRunId(), q.experimentId(),
                    q.islandId(), expNoById.get(q.experimentId()),
                    q.islandId() <= 0 ? null : islandCodes.get(q.islandId()),
                    q.priority(), q.deadline(),
                    Instant.ofEpochMilli(q.submittedAt()).toString(), position));
        }
        int runningCount = lockEntries.size();
        int queuedCount = queueEntries.size();
        return new Snapshot(lockEntries, queueEntries, runningCount, queuedCount,
                MAX_CONCURRENT, runningCount == 0 && queuedCount > 0,
                lastDispatchAt.toString(), dispatched.get(), started.get());
    }

    /**
     * 调度状态（给运维看"为什么这个实验没开始"）。
     *
     * <p>返回结构与改动前**向后兼容**（字段都在）：running / queued / maxConcurrent /
     * stalled / lastDispatchAt / islandLocks 原样保留；started 由 boolean 改为
     * **累计派发次数 number**（§7.1 契约，示例 41），boolean 语义挪到新增的
     * loopStarted；另新增 queue / workers。
     */
    public Map<String, Object> status() throws SQLException {
        Snapshot s = snapshot();
        Map<String, Object> m = new LinkedHashMap<>();

        List<Map<String, Object>> locks = new ArrayList<>();
        for (LockEntry l : s.islandLocks()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("islandId", l.islandId());
            x.put("islandCode", l.islandCode());
            x.put("experimentRunId", l.experimentRunId());
            // runId 是 §7.1 示例里的历史别名（与 experimentRunId 同值），保留给读示例的客户端
            x.put("runId", l.experimentRunId());
            x.put("experimentId", l.experimentId());
            x.put("experimentNo", l.experimentNo());
            x.put("since", l.since());
            x.put("expiresAt", l.expiresAt());
            locks.add(x);
        }
        m.put("islandLocks", locks);
        m.put("running", s.running());
        m.put("queued", s.queued());

        List<Map<String, Object>> queueItems = new ArrayList<>();
        for (QueueEntry q : s.queue()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("position", q.position());
            x.put("experimentId", q.experimentId());
            x.put("experimentNo", q.experimentNo());
            x.put("experimentRunId", q.experimentRunId());
            // 未分配岛的实验内核内部用 0 占位，对外是"未指定"（前端按 null 兜底）
            x.put("islandId", q.islandId() <= 0 ? null : q.islandId());
            x.put("islandCode", q.islandCode());
            x.put("priority", q.priority());
            x.put("deadline", q.deadline());
            x.put("submittedAt", q.submittedAt());
            queueItems.add(x);
        }
        m.put("queue", queueItems);

        Map<String, Object> workers = new LinkedHashMap<>();
        workers.put("pool", s.maxConcurrent());
        workers.put("active", s.running());
        workers.put("queue", 0);   // 池子是直接 submit，队列里恒 0（§7.1 保留字段）
        m.put("workers", workers);

        m.put("maxConcurrent", s.maxConcurrent());
        m.put("lastDispatchAt", s.lastDispatchAt());
        m.put("stalled", s.stalled());
        m.put("started", s.started());          // §7.1：累计派发次数
        m.put("loopStarted", s.loopStarted());  // 兼容语义：调度循环是否已启动
        return m;
    }

    /** 岛编码批量联查（resource_node.id -> code）。 */
    private void loadIslandCodes(Connection c, Set<Long> islandIds,
                                 Map<Long, String> into) throws SQLException {
        if (islandIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, code FROM resource_node WHERE tenant_id = ? AND id = ANY(?)")) {
            ps.setLong(1, tenantId);
            ps.setArray(2, c.createArrayOf("bigint", islandIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
    }

    /** 锁的 experiment_run -> 实验 id / 实验编号（一次批量）。 */
    private void loadRunExperiments(Connection c, Set<Long> runIds,
                                    Map<Long, Long> expIdInto,
                                    Map<Long, String> expNoInto) throws SQLException {
        if (runIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT er.id, er.experiment_id, e.experiment_no
                  FROM experiment_run er
                  JOIN experiment e ON e.id = er.experiment_id
                 WHERE er.tenant_id = ? AND er.id = ANY(?)
                """)) {
            ps.setLong(1, tenantId);
            ps.setArray(2, c.createArrayOf("bigint", runIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    expIdInto.put(rs.getLong(1), rs.getLong(2));
                    expNoInto.put(rs.getLong(1), rs.getString(3));
                }
            }
        }
    }

    /** 队列项实验编号（一次批量）。 */
    private void loadExperimentNos(Connection c, Set<Long> expIds,
                                   Map<Long, String> into) throws SQLException {
        if (expIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, experiment_no FROM experiment WHERE tenant_id = ? AND id = ANY(?)")) {
            ps.setLong(1, tenantId);
            ps.setArray(2, c.createArrayOf("bigint", expIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
    }

    // ---------------------------------------------------------- 实验属性

    private long islandOf(long experimentId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT island_id FROM experiment WHERE tenant_id=? AND id=?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? 0L : v;
                }
            }
        }
        return 0L;
    }

    private int priorityOf(long experimentId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COALESCE(priority, 50) FROM experiment
                     WHERE tenant_id=? AND id=?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 50;
            }
        }
    }

    private String deadlineOf(long experimentId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT deadline FROM experiment WHERE tenant_id=? AND id=?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp(1);
                    return ts == null ? null : ts.toLocalDateTime().toString();
                }
            }
        }
        return null;
    }

    private long insertRun(long experimentId, Map<String, String> vars) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment_run (tenant_id, experiment_id, status, variables)
                     VALUES (?, ?, 'queued', ?::jsonb) RETURNING id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            ps.setString(3, BatchOrchestrator.variablesJson(vars));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * 兜底写状态（只在执行器 settle 之前抛异常时用）。
     *
     * <p>加 {@code status NOT IN ('aborted','cancelled')} 守卫：人工已经明确终止的 run，
     * 不能被一个迟到的兜底写回成 failed。
     */
    private void markStatus(long runId, String status, String error) throws SQLException {
        String sql = "completed".equals(status) || "failed".equals(status)
                ? "UPDATE experiment_run SET status=?, error=?, finished_at=now()"
                        + " WHERE tenant_id=? AND id=? AND status NOT IN ('aborted','cancelled')"
                : "UPDATE experiment_run SET status=?, error=?"
                        + " WHERE tenant_id=? AND id=? AND status NOT IN ('aborted','cancelled')";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setLong(3, tenantId);
            ps.setLong(4, runId);
            ps.executeUpdate();
        }
    }
}
