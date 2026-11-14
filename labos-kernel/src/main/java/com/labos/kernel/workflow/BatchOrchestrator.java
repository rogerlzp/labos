package com.labos.kernel.workflow;

import com.labos.kernel.persistence.BatchMapper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分批执行编排（S3）：建批 + 按批跑 + 单批重跑 + 批次视图。
 *
 * <h3>为什么单独一个编排层，而不是把批次塞进 {@link ExperimentExecutor}</h3>
 * 执行器只该回答"一条 run 怎么按步骤跑完"；批次是它<span>外面</span>的一层循环
 * （建批、分配 run、记录 attempt、聚合实验状态）。复用执行器（每批一次 run），
 * 不新写执行器 —— 与实施清单 §8 · D3 一致。
 *
 * <h3>硬口径（实施清单 §8 · D1-D7）</h3>
 * <ol>
 *   <li><b>D1</b>：{@code experiment.batch_split} 为 NULL / {@code <= 0} 时
 *       <b>完全走非分批路径</b>（一次 run 跑整单，实验状态由执行器同步）——
 *       非分批实验行为一个字都不许变。</li>
 *   <li><b>D2</b>：首次执行时按 {@code experiment_sample.sample_id} 升序切批，
 *       把 {@code [{sampleId,barcode}]} 快照写进 {@code experiment_batch.samples}；
 *       重跑只用快照，不再读 {@code experiment_sample}（样品集合不漂移）。</li>
 *   <li><b>D3</b>：一次「执行实验」= 按 {@code batch_no} 串行跑<b>所有未成功批次</b>；
 *       每批一个 {@code experiment_run} + 一条 {@code experiment_batch_run}，
 *       {@code attempt_no} 从 1 递增；某批失败<b>继续</b>后续批；
 *       遇暂停扣留停在批边界、不启动下一批。</li>
 *   <li><b>D4</b>：单批重跑只跑该批，新 attempt，历史尝试保留；只允许非 completed 批。</li>
 *   <li><b>D5</b>：每批 run 追加 {@code batchNo} / {@code batchSamples} 变量，
 *       不覆盖用户显式传入的同名变量（见 {@link BatchPlanner#batchVariables}）。</li>
 *   <li><b>D6</b>：实验状态按 {@link BatchPlanner#aggregateExperimentStatus} 聚合；
 *       执行器在本模式下落 run 终态时<b>不</b>同步实验表，避免"第 1 批完成=整单完成"。</li>
 *   <li><b>D7</b>：批次串行；批边界查 {@link RuntimeHold}。</li>
 * </ol>
 *
 * <h3>与运行控制（暂停/停止/恢复）的边界</h3>
 * 批次执行中间，暂停/停止仍由 {@link RunControl} + 执行器在<b>动作边界</b>生效
 * （执行器 per-run 的检查不受分批影响）。本类只保证：一旦某批以 paused/aborted 结束，
 * <b>不再启动后续批</b> —— 现场闸门没解除时不能继续往设备上放板子。
 * 批内断点续跑仍走"恢复同一条 run"的路径；本版本不把它并进分批编排
 * （见实施清单 §8 的本轮范围，记录为残余风险）。
 */
public final class BatchOrchestrator {

    /** 单批执行结果（API 响应 / 内部汇总共用）。 */
    public record BatchResult(int batchNo, long runId, long batchRunId, int attemptNo,
                              String status, int doneSteps, int totalSteps, String error) {
    }

    /**
     * 一次「执行实验」的结果。
     *
     * <p>{@code batched=false} 表示 batch_split 为空、走的是非分批路径：
     * 此时返回的就是执行器原样给出的结果，接口保持只回相同字段。
     */
    public record RunAllResult(boolean batched, long experimentRunId, String status,
                               int totalSteps, int doneSteps, String error,
                               Integer batchSplit, List<BatchResult> batches) {
    }

    /** 一条批次尝试（读过 view 后的形状）。 */
    public record AttemptRow(long batchRunId, long runId, int attemptNo, String status,
                             String startedAt, String finishedAt, String error) {
    }

    /**
     * 一个逻辑批次 + 它跑过的所有尝试。
     *
     * @param status       派生后的当前状态（以最近一次实际 run 为准）
     * @param storedStatus 表里 {@code experiment_batch.status} 原值（自愈判断用）
     */
    public record BatchRow(long id, int batchNo, int sampleCount, String samplesJson,
                           String barcodesCsv, String status, String storedStatus,
                           List<AttemptRow> runs) {
    }

    /**
     * 单批执行体（依赖倒置点）。
     *
     * <p>线上实现是 {@link ExperimentExecutor#run(long, Map, long, boolean)}
     * （传 {@code syncExperimentStatus=false}）；连库自测注入假执行体，
     * 这样能验证编排语义（attempt 递增 / 重跑不覆盖历史 / 状态聚合）而<b>不真的动设备</b>。
     * 真的执行器集成由手工 curl + 执行历史覆盖。
     */
    @FunctionalInterface
    public interface BatchRunner {
        ExperimentExecutor.Result run(long experimentId, Map<String, String> vars,
                                      long existingRunId, boolean syncExperimentStatus)
                throws SQLException;
    }

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DbConfig db;
    private final long tenantId;
    private final BatchRunner runner;

    /** Mapper 懒取器：本类是 new 出来的工具类，拿不到构造注入。 */
    private static BatchMapper bm() {
        return Mappers.get(BatchMapper.class);
    }

    /** 线上构造：每批用真实 {@link ExperimentExecutor} 跑一条 run。 */
    public BatchOrchestrator(DbConfig db, long tenantId, WorkflowService workflows) {
        this(db, tenantId, new ExperimentExecutor(db, tenantId, workflows));
    }

    /** 线上/自测共用：直接给一个执行器（workflows 可为 null，自测不真跑）。 */
    BatchOrchestrator(DbConfig db, long tenantId, ExperimentExecutor executor) {
        this(db, tenantId, (experimentId, vars, runId, sync) ->
                executor.run(experimentId, vars, runId, sync));
    }

    /**
     * 纯回写构造（D8/D11 用）：只读写库，不执行任何批次。
     *
     * <p>{@link #onRunStatusChanged}/{@link #batchRunIdFor}/{@link #setBatchSplit}
     * 都不需要执行体；运行控制（{@link RunControl}）与调度器的"是不是批次 run"
     * 探测走这个构造，避免为了回写状态去装配一个 {@link ExperimentExecutor}。
     */
    public BatchOrchestrator(DbConfig db, long tenantId) {
        this(db, tenantId, (BatchRunner) null);
    }

    /** 自测注入假执行体。 */
    BatchOrchestrator(DbConfig db, long tenantId, BatchRunner runner) {
        this.db = db;
        this.tenantId = tenantId;
        this.runner = runner;
    }

    // ---------------------------------------------------------------- 读

    /** 读实验的批次开关；NULL / {@code <= 0} = 不分批。 */
    public Integer batchSplit(long experimentId) throws SQLException {
        Integer v = bm().selectBatchSplit(tenantId, experimentId);
        return v == null || v <= 0 ? null : v;
    }

    /**
     * 这条 run 属于哪条批次尝试；<b>非批次 run（非分批路径）返回 {@code null}</b>。
     *
     * <p>调度器（D9）与运行控制（D10）用它探测"要不要走批次回写"：
     * <b>只读</b>，不写任何表；返回 null 就保持改动前的行为。
     */
    public Long batchRunIdFor(long experimentRunId) throws SQLException {
        BatchRef ref = findBatchRef(experimentRunId);
        return ref == null ? null : ref.batchRunId();
    }

    /** 一条批次尝试的归属（回写用）。 */
    private record BatchRef(long batchRunId, long batchId, long experimentId) {
    }

    /** 一条批次 run 的批次上下文（异步分批在调度器里执行时注入批变量用）。 */
    public record BatchContext(long batchRunId, long batchId, int batchNo, String barcodesCsv) {
    }

    /**
     * 读一条批次 run 的批次上下文（批次号 + 样品快照条码串）；非批次 run 返回 {@code null}。
     *
     * <p>调度器执行批次 run 时用它按 D5 注入 {@code batchNo}/{@code batchSamples}：
     * 同步分批路径由 {@link #executeBatch} 直接把变量交给执行器，异步分批走调度器，
     * 变量只能从"这条 run 属于哪一批"反查——批次快照是不变量（D2），反查结果稳定。
     */
    public BatchContext batchContextFor(long experimentRunId) throws SQLException {
        Map<String, Object> row = bm().selectBatchContext(tenantId, experimentRunId);
        if (row == null) {
            return null;
        }
        return new BatchContext(((Number) row.get("batchRunId")).longValue(),
                ((Number) row.get("batchId")).longValue(),
                ((Number) row.get("batchNo")).intValue(),
                (String) row.get("barcodes"));
    }

    // ---------------------------------------------------------------- 变量 overrides（S3.2-C）

    /**
     * 把用户变量覆盖编码成 {@code experiment_run.variables} 的 jsonb 文本。
     *
     * <p>平铺字符串对象（与 {@link ExperimentExecutor} 落库的形态一致），
     * null/空 map 编码为 {@code '{}'}（列 NOT NULL DEFAULT '{}'）。
     */
    static String variablesJson(Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) {
            return "{}";
        }
        List<Object> kv = new ArrayList<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            kv.add(e.getKey());
            kv.add(e.getValue());
        }
        return Jsons.obj(kv.toArray());
    }

    /**
     * 读一条 run 的 variables（用户变量覆盖）。
     *
     * <p>用 {@code jsonb_each_text} 在库里摊平，<b>不引第三方 JSON 库、也不手写解析器</b>：
     * variables 约定是平铺字符串对象，数据库已经能拆成 key/value 文本。
     * 非对象（脏数据）返回空 map —— 读变量失败不该让调度失败。
     *
     * @return 键值按 key 升序；run 不存在 / 空对象时返回空 map
     */
    public Map<String, String> runVariables(long runId) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> row : bm().selectRunVariables(tenantId, runId)) {
            String key = (String) row.get("key");
            String value = (String) row.get("value");
            if (key != null && value != null) {
                m.put(key, value);
            }
        }
        return m;
    }

    /**
     * 续队口径的用户变量：去掉调度器注入的批上下文变量（{@code batchNo/batchSamples}）。
     *
     * <p>为什么必须过滤：一条批次 run 执行时，执行器会把<b>合并后</b>的变量
     * （默认值 + 用户覆盖 + 批变量）写回 {@code experiment_run.variables}。
     * 续下一批时若把整份变量原样当"用户变量"传下去，上一批的 {@code batchNo}
     * 会以"用户显式传入"的身份覆盖下一批的批号（D5：用户变量优先）——
     * 于是第 2 批会带着 batchNo=1 跑。批次上下文每批重算，不属于用户变量。
     */
    static Map<String, String> continueVars(Map<String, String> runVars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (runVars == null) {
            return out;
        }
        for (Map.Entry<String, String> e : runVars.entrySet()) {
            if (BatchPlanner.VAR_BATCH_NO.equals(e.getKey())
                    || BatchPlanner.VAR_BATCH_SAMPLES.equals(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * 查 run -> 批次尝试的归属。
     *
     * <p>INNER JOIN {@code experiment_run}：run 行都不存在时返回 null
     * （没有"当前状态"可回写，不能瞎写）。同一 run 理论上一对一；
     * 万一历史脏数据有多条，取最新一条，不放大污染面。
     */
    private BatchRef findBatchRef(long experimentRunId) throws SQLException {
        Map<String, Object> row = bm().selectBatchRef(tenantId, experimentRunId);
        if (row == null) {
            return null;
        }
        return new BatchRef(((Number) row.get("batchRunId")).longValue(),
                ((Number) row.get("batchId")).longValue(),
                ((Number) row.get("experimentId")).longValue());
    }

    /**
     * 当前的切批计划（不落库）：样品按 {@code experiment_sample.sample_id} 升序，
     * 每 {@code batch_split} 个一批。
     *
     * <p>与 {@link #ensureBatches} 共用同一份样品读取，保证"计划"和"首次建批"同源。
     */
    public List<BatchPlanner.Batch> splitPlan(long experimentId) throws SQLException {
        Integer split = batchSplit(experimentId);
        if (split == null) {
            return List.of();
        }
        return BatchPlanner.split(loadSamples(experimentId), split);
    }

    /**
     * 首次执行前幂等建批（D2）；已建过就原样返回，<b>不重切</b>。
     *
     * @return 该实验的全部批次（按 batch_no 升序）；不分批时返回空列表
     * @throws IllegalArgumentException 配了分批却没有样品（配置错误，不静默降级）
     */
    public List<BatchRow> ensureBatches(long experimentId) throws SQLException {
        Integer split = batchSplit(experimentId);
        if (split == null) {
            return loadBatches(experimentId);
        }
        // 已建过就原样返回：**不按当前 batch_split 重切**（D2 快照固化）。
        // 否则改了 batch_split 会悄悄补出新批次，同一实验出现两套切分口径。
        // （并发首次执行时两个调用都可能看到"还没有" -> 插入用 ON CONFLICT 兜底）
        List<BatchRow> existing = loadBatches(experimentId);
        if (!existing.isEmpty()) {
            return existing;
        }
        List<BatchPlanner.Sample> samples = loadSamples(experimentId);
        if (samples.isEmpty()) {
            throw new IllegalArgumentException(
                    "实验配置了分批（每批 " + split + " 个样品），但还没有样品，无法建批；"
                            + "请先给实验挂样品，或把 batch_split 置空");
        }
        List<BatchPlanner.Batch> plan = BatchPlanner.split(samples, split);
        com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            for (BatchPlanner.Batch b : plan) {
                bm().insertBatch(tenantId, experimentId, b.batchNo(), b.sampleCount(),
                        samplesJson(b.samples()));
            }
            return null;
        });
        return loadBatches(experimentId);
    }

    /**
     * 批次视图（GET /api/experiments/{id}/batches，形状严格按实施清单 §8.3）。
     *
     * <p>{@code batchSplit} 为 null 时 items 为空数组（前端据此隐藏批次卡片，
     * 非分批实验页面零变化）。summary 只有契约里的 5 个桶；paused/queued 这类"还没结束"
     * 的状态归入 pending，aborted 与 failed 同归 failed（与 D6 的失败口径一致）。
     */
    public String batchesJson(long experimentId) throws SQLException {
        Integer split = batchSplit(experimentId);
        if (split == null) {
            return Jsons.obj("experimentId", experimentId, "batchSplit", null,
                    "items", new Jsons.Raw("[]"),
                    "summary", new Jsons.Raw(Jsons.obj(
                            "total", 0, "pending", 0, "running", 0,
                            "completed", 0, "failed", 0)));
        }
        List<BatchRow> rows = loadBatches(experimentId);
        List<String> items = new ArrayList<>();
        int pending = 0;
        int running = 0;
        int completed = 0;
        int failed = 0;
        for (BatchRow b : rows) {
            switch (b.status()) {
                case "running" -> running++;
                case "completed" -> completed++;
                case "failed", "aborted", "cancelled" -> failed++;
                default -> pending++;
            }
            List<String> runs = new ArrayList<>();
            for (AttemptRow a : b.runs()) {
                runs.add(Jsons.obj(
                        "batchRunId", a.batchRunId(),
                        "runId", a.runId(),
                        "attemptNo", a.attemptNo(),
                        "status", a.status(),
                        "startedAt", a.startedAt(),
                        "finishedAt", a.finishedAt(),
                        "error", a.error()));
            }
            items.add(Jsons.obj(
                    "id", b.id(),
                    "batchNo", b.batchNo(),
                    "sampleCount", b.sampleCount(),
                    "samples", new Jsons.Raw(b.samplesJson() == null ? "[]" : b.samplesJson()),
                    "status", b.status(),
                    "attemptCount", b.runs().size(),
                    "currentRunId", b.runs().isEmpty()
                            ? null : b.runs().get(b.runs().size() - 1).runId(),
                    "runs", new Jsons.Raw(Jsons.arr(runs))));
        }
        return Jsons.obj("experimentId", experimentId, "batchSplit", split,
                "items", new Jsons.Raw(Jsons.arr(items)),
                "summary", new Jsons.Raw(Jsons.obj(
                        "total", rows.size(), "pending", pending, "running", running,
                        "completed", completed, "failed", failed)));
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 一次「执行实验」：分批时按 batch_no 串行跑所有未成功批（D3）；
     * 不分批时<b>原样委托</b>非分批路径（D1，行为与改动前完全一致）。
     */
    public RunAllResult runAll(long experimentId, Map<String, String> overrides)
            throws SQLException {
        Integer split = batchSplit(experimentId);
        if (split == null) {
            // D1：batch_split 为 NULL -> 非分批路径，接口字段/落库/状态同步都不变
            ExperimentExecutor.Result old = runner.run(experimentId, overrides, 0L, true);
            return new RunAllResult(false, old.experimentRunId(), old.status(),
                    old.totalSteps(), old.doneSteps(), old.error(), null, List.of());
        }

        List<BatchRow> batches = ensureBatches(experimentId);
        markExperimentRunning(experimentId);
        RuntimeHold holds = new RuntimeHold(db, tenantId);
        long islandId = islandOf(experimentId);
        List<BatchResult> results = new ArrayList<>();
        try {
            for (BatchRow b : batches) {
                if ("completed".equals(b.status())) {
                    // D3：只跑未成功批。成功批的重跑入口在 runBatch（且会被拒）。
                    // 顺带把"派生状态已完成但表里还是变更前的值"的情况自愈掉。
                    if (!"completed".equals(b.storedStatus())) {
                        updateBatchStatus(b.id(), "completed");
                    }
                    continue;
                }
                // D7：批边界检查现场扣留 —— 不启动这一批，停在边界
                RuntimeHold.Active covered = holds.coveringFor(experimentId, islandId);
                if (covered != null) {
                    updateBatchStatus(b.id(), "paused");
                    break;
                }
                BatchResult r = executeBatch(experimentId, b, overrides);
                results.add(r);
                // 批内被暂停（现场扣留）或人工停止：停在批边界，后续批不启动
                if ("paused".equals(r.status()) || "aborted".equals(r.status())) {
                    break;
                }
            }
        } finally {
            // 无论正常结束还是中途停，都按 D6 聚合一次实验状态
            syncExperimentStatusFromBatches(experimentId);
        }

        int totalSteps = 0;
        int doneSteps = 0;
        String error = null;
        long lastRunId = 0L;
        for (BatchResult r : results) {
            totalSteps += r.totalSteps();
            doneSteps += r.doneSteps();
            if (error == null) {
                error = r.error();
            }
            lastRunId = r.runId();
        }
        String aggregate = aggregateStatus(experimentId);
        return new RunAllResult(true, lastRunId,
                aggregate == null ? "running" : aggregate,
                totalSteps, doneSteps, error, split, results);
    }

    /**
     * 单批重跑（D4）：只跑该批，新 attempt，历史尝试保留。
     *
     * @throws IllegalArgumentException 批不存在 / 批已完成 / 现场扣留中（接口回 400）
     */
    public BatchResult runBatch(long experimentId, int batchNo, Map<String, String> overrides)
            throws SQLException {
        Integer split = batchSplit(experimentId);
        if (split == null) {
            throw new IllegalArgumentException(
                    "该实验未配置分批（batch_split 为空），请用「执行实验」");
        }
        List<BatchRow> batches = ensureBatches(experimentId);
        BatchRow target = null;
        for (BatchRow b : batches) {
            if (b.batchNo() == batchNo) {
                target = b;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("批次不存在：第 " + batchNo + " 批");
        }
        if ("completed".equals(target.status())) {
            throw new IllegalArgumentException(
                    "第 " + batchNo + " 批已完成，不能重跑（只允许重跑未成功批）");
        }
        // D7：单批重跑同样受现场闸门约束，扣留期间不许往设备上放板子
        RuntimeHold.Active covered = new RuntimeHold(db, tenantId)
                .coveringFor(experimentId, islandOf(experimentId));
        if (covered != null) {
            updateBatchStatus(target.id(), "paused");
            syncExperimentStatusFromBatches(experimentId);
            throw new IllegalArgumentException("现场扣留中，未启动第 " + batchNo + " 批："
                    + (covered.reason() == null ? "现场扣留" : covered.reason()));
        }
        markExperimentRunning(experimentId);
        try {
            return executeBatch(experimentId, target, overrides);
        } finally {
            syncExperimentStatusFromBatches(experimentId);
        }
    }

    // ---------------------------------------------------------------- 异步分批（调度器按批派发）

    /** {@link #createNextBatchAttempt} 的结果类型。 */
    public enum NextBatchOutcome {
        /** 已新建一条 queued 批次 run，调用方应把它交给调度器入队（resumeRun）。 */
        CREATED,
        /** 已有未结束的批次 run（queued/running/paused），不重复建；status 是它当前状态。 */
        EXISTING,
        /** 没有更多未成功批（初始提交=全部跑完；续队=后面没有待跑批）。 */
        NO_MORE,
        /** 现场扣留：批边界停住，未建尝试（候选批已标 paused）。 */
        HELD,
        /** 未配置分批（batch_split 为空），调用方走非分批路径。 */
        NOT_BATCHED
    }

    /**
     * 一次"异步按批"的下一步结果（响应 / 日志 / 自测共用）。
     *
     * @param status   CREATED/EXISTING=该 run 的当前状态；NO_MORE=实验聚合状态（可空）；
     *                 HELD/NOT_BATCHED=null
     * @param holdReason HELD 时的人话原因
     */
    public record NextBatchAttempt(NextBatchOutcome outcome, long experimentId,
                                   long runId, long batchRunId, int batchNo, int attemptNo,
                                   String status, String holdReason) {

        /** 是否拿到了一条应交给调度器入队的 queued run。 */
        public boolean queueable() {
            return outcome == NextBatchOutcome.CREATED
                    || (outcome == NextBatchOutcome.EXISTING && "queued".equals(status));
        }
    }

    /**
     * 异步分批执行：建/取"下一批"的一次尝试（queued），供调度器按批派发。
     *
     * <h3>为什么需要它</h3>
     * S3 的同步路径 {@link #runAll} 在<b>一次请求内</b>按批串行跑完；而 async=1
     * 走调度器时，调度器队列的单位是"一条 experiment_run"，不认"整单多批"。
     * 这里把"取下一批"抽成一个可重复调用的原子步骤：初始提交调一次建第 1 批，
     * 每批跑完后调度器再调一次建下一批 —— 异步也就等价于 D3 的串行分批。
     *
     * <h3>口径</h3>
     * <ol>
     *   <li>先 {@link #ensureBatches}（幂等建批/快照）；配了分批却没有样品仍抛
     *       {@link IllegalArgumentException}（接口 400，不静默降级）；</li>
     *   <li><b>批边界检查现场扣留</b>（D7，在实验行锁内判定）：有覆盖该实验的扣留时
     *       不建尝试，把候选批标 {@code paused} 并按 D6 聚合（实验 paused），返回 {@code HELD}。
     *       与同步 runAll 的批边界口径一致；解除扣留后再调一次本方法
     *       （即再 POST /run?async=1）会从该批继续；此外调度器
     *       {@code ExperimentScheduler#autoContinueHeldBatches()} 会周期扫描
     *       {@link #pausedBatchCandidates()} 自动续队（S3.2-B）。</li>
     *   <li><b>同一实验同时只允许一条批次 run 在队列/执行/暂停</b>：事务里先
     *       {@code SELECT experiment ... FOR UPDATE} 锁住实验，再查有没有
     *       status IN (queued/running/paused) 的批次 run；有就返回 {@code EXISTING}，
     *       不重复建（重复 POST /run?async=1 因此是幂等的）。</li>
     *   <li>按 {@code batch_no} 升序取第一条 <b>{@code batch_no > afterBatchNo}</b>
     *       且派生态不是 completed / running 的批，新建 queued experiment_run +
     *       experiment_batch_run(attempt_no)，批次标 queued；初始提交传
     *       {@code afterBatchNo=0}，调度器续队传<b>刚跑完那批的 batchNo</b>——
     *       所以失败批不会在续队时被原地重跑，而是继续下一批（D3 失败继续）。</li>
     *   <li>建完按 D6 聚合实验状态（queued 批 ⇒ 实验 running）；异步提交后实验状态
     *       由聚合决定，<b>不再</b>由单条 run 同步（与 D6 一致）。</li>
     * </ol>
     *
     * @param afterBatchNo 只看 batch_no 大于它的批；初始提交传 0
     */
    public NextBatchAttempt createNextBatchAttempt(long experimentId, int afterBatchNo)
            throws SQLException {
        return createNextBatchAttempt(experimentId, afterBatchNo, null);
    }

    /**
     * 带用户变量 overrides 的重载（S3.2-C）：新建的 queued run 把变量写进
     * {@code experiment_run.variables}，调度器执行这条 run 时再读出来作为用户 overrides。
     *
     * <p>为什么变量必须<b>先落库再入队</b>：调度器队列是内存态，重启后靠
     * {@code experiment_run.variables} 恢复"用户当时传了什么"；只放内存的话，
     * 重启 rehydrate 出来的 run 会丢掉用户变量、悄悄按默认值跑。
     *
     * <p>写入在实验行锁事务内（与 attempt 建单同一事务），不存在"run 建好了但变量没落"。
     *
     * @param vars 用户变量覆盖，可空（null/空 = {@code '{}'}）
     */
    public NextBatchAttempt createNextBatchAttempt(long experimentId, int afterBatchNo,
                                                   Map<String, String> vars)
            throws SQLException {
        Integer split = batchSplit(experimentId);
        if (split == null) {
            return new NextBatchAttempt(NextBatchOutcome.NOT_BATCHED, experimentId,
                    0L, 0L, 0, 0, null, null);
        }
        // ensureBatches 用独立连接/事务：它是幂等的"建批/读批"，不参与下面的实验行锁，
        // 避免"持实验行锁等建批事务、建批事务等实验行外键锁"的互等。
        ensureBatches(experimentId);

        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            lockExperimentRow(c, experimentId);
            // 顺序很重要：先看"已有未结束的批次 run"，再看扣留。
            // 否则同一实验正在跑第 1 批时重复 async 提交，会被判成 HELD 并顺手把
            // 第 2 批标 paused（一个本不该发生的副作用）。
            ActiveAttempt active = activeBatchAttempt(c, experimentId);
            if (active != null) {
                // 已有未结束的批次 run：不重复建（重复入队 / 双 POST 的幂等口）
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return new NextBatchAttempt(NextBatchOutcome.EXISTING, experimentId,
                        active.runId(), active.batchRunId(), active.batchNo(),
                        active.attemptNo(), active.status(), null);
            }
            CandidateBatch next = firstCandidateInTx(c, experimentId, afterBatchNo);
            if (next == null) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                String aggregate = syncExperimentStatusFromBatches(experimentId);
                return new NextBatchAttempt(NextBatchOutcome.NO_MORE, experimentId,
                        0L, 0L, 0, 0, aggregate, null);
            }
            // D7 批边界：锁内判定现场扣留（与同步 runAll 同口径：标 paused + 按 D6 聚合）
            RuntimeHold.Active covered = new RuntimeHold(db, tenantId)
                    .coveringFor(experimentId, islandOf(experimentId));
            if (covered != null) {
                updateBatchStatus(c, next.batchId(), "paused");
                syncExperimentStatusFromBatches(experimentId);
                String reason = covered.reason() == null ? "现场扣留" : covered.reason();
                return new NextBatchAttempt(NextBatchOutcome.HELD, experimentId, 0L, 0L,
                        next.batchNo(), 0, null, reason);
            }
            long runId = insertQueuedRun(c, experimentId, vars);
            int attemptNo = nextAttemptNo(c, next.batchId());
            long batchRunId = insertQueuedBatchRun(c, next.batchId(), runId, attemptNo);
            updateBatchStatus(c, next.batchId(), "queued");
            // 实验状态由 D6 聚合（queued 批 ⇒ running），不让单条 run 直接覆盖
            syncExperimentStatusFromBatches(experimentId);
            return new NextBatchAttempt(NextBatchOutcome.CREATED, experimentId,
                    runId, batchRunId, next.batchNo(), attemptNo, "queued", null);
        });
    }

    /**
     * 扣留解除后「自动续队」的候选批次（S3.2-B）。
     *
     * <p>只取同时满足以下条件的批：
     * <ol>
     *   <li>{@code experiment_batch.status='paused'}（批边界/现场扣留停住，或人工恢复前）；</li>
     *   <li><b>该批没有任何 experiment_batch_run 行</b> —— 有尝试的批是"跑过但失败/暂停"，
     *       自动补新尝试等于绕过人工处置（D3 失败批不原地重试）；</li>
     *   <li>实验不在终态（finished/failed/aborted/cancelled）—— 人工停止的实验不许被续；</li>
     * </ol>
     *
     * <p>是否仍被现场扣留<b>不在本方法判定</b>：调度器用
     * {@link RuntimeHold#coveringFor(long, long)}（site/island/experiment 三级作用域）
     * 逐条判定，避免这里再实现一遍作用域优先级。
     *
     * @return 候选批（experimentId + batchNo），按 batch_no 升序、同号按 experiment_id
     */
    public record PausedBatch(long experimentId, int batchNo) {
    }

    public List<PausedBatch> pausedBatchCandidates() throws SQLException {
        List<PausedBatch> out = new ArrayList<>();
        for (Map<String, Object> row : bm().selectPausedBatchCandidates(tenantId)) {
            out.add(new PausedBatch(((Number) row.get("experimentId")).longValue(),
                    ((Number) row.get("batchNo")).intValue()));
        }
        return out;
    }

    /** 未结束的批次尝试（同实验只能有一条）。 */
    private record ActiveAttempt(long runId, long batchRunId, int batchNo, int attemptNo,
                                 String status) {
    }

    /** 锁内选出的"下一批"。 */
    private record CandidateBatch(long batchId, int batchNo) {
    }

    /** 事务内版本：在锁内按派生态选下一批（只读，不写）。 */
    private CandidateBatch firstCandidateInTx(Connection c, long experimentId, int afterBatchNo)
            throws SQLException {
        for (Map<String, Object> row : bm().selectBatchIdNo(tenantId, experimentId)) {
            long batchId = ((Number) row.get("id")).longValue();
            int batchNo = ((Number) row.get("batchNo")).intValue();
            if (batchNo <= afterBatchNo) {
                continue;
            }
            String status = derivedBatchStatus(c, batchId);
            if ("completed".equals(status) || "running".equals(status)) {
                continue;
            }
            return new CandidateBatch(batchId, batchNo);
        }
        return null;
    }

    /**
     * 锁住实验行（异步分批"取下一批"的串行化点）。
     *
     * <p>为什么锁实验行而不是批次行：判定"同实验是否已有批次 run 在队列/执行"是
     * 实验级不变量，只锁某一批挡不住两个请求同时给不同批建单。
     */
    private void lockExperimentRow(Connection c, long experimentId) throws SQLException {
        if (bm().lockExperiment(tenantId, experimentId) == null) {
            throw new IllegalArgumentException("实验不存在：" + experimentId);
        }
    }

    /** 查同实验有没有未结束（queued/running/paused）的批次 run；没有返回 null。 */
    private ActiveAttempt activeBatchAttempt(Connection c, long experimentId) throws SQLException {
        Map<String, Object> row = bm().selectActiveAttempt(tenantId, experimentId);
        if (row == null) {
            return null;
        }
        return new ActiveAttempt(((Number) row.get("runId")).longValue(),
                ((Number) row.get("batchRunId")).longValue(),
                ((Number) row.get("batchNo")).intValue(),
                ((Number) row.get("attemptNo")).intValue(),
                (String) row.get("status"));
    }

    /** 在事务里建一条 queued 的 experiment_run（异步分批的"待派发 run"）。 */
    private long insertQueuedRun(Connection c, long experimentId, Map<String, String> vars)
            throws SQLException {
        return bm().insertQueuedRunWithVars(tenantId, experimentId, variablesJson(vars));
    }

    /**
     * 在事务里建一条 queued 的批次尝试。
     *
     * <p>{@code started_at} 留空：这条 run 还没被调度器真正执行；真正开跑时间由
     * {@code experiment_run.started_at} 承载，D8 回写时用 COALESCE 补进 attempt 行。
     */
    private long insertQueuedBatchRun(Connection c, long batchId, long runId, int attemptNo)
            throws SQLException {
        return bm().insertQueuedBatchRun(tenantId, batchId, runId, attemptNo);
    }

    // ---------------------------------------------------------------- 回写

    /**
     * 统一回写点（D8）：一条 run 的状态变了（调度器恢复后跑完 / 人工停止 / 恢复），
     * 把批次层与实验聚合状态补齐。
     *
     * <p><b>非批次 run 直接返回 false，且不写任何表</b> —— 非分批路径（{@code batch_split=NULL}）
     * 的状态同步仍由执行器/运行控制自己做，行为与改动前完全一致。
     *
     * <p>批次 run 做三件事：
     * <ol>
     *   <li>按 {@code experiment_run} 当前 {@code status/error} 回写 attempt 行
     *       （终态落 {@code finished_at}，非终态清掉）；</li>
     *   <li>按该批<b>最新尝试</b>重算 {@code experiment_batch.status}，
     *       并沿用读侧自愈口径：任一次尝试 completed ⇒ 该批 completed；</li>
     *   <li>按 D6 聚合 {@code experiment.status}（复用
     *       {@link #syncExperimentStatusFromBatches}，与读侧同一条规则）。</li>
     * </ol>
     *
     * <p><b>幂等</b>：重复调用只会把同样的状态再写一遍；{@code finished_at}
     * 用 COALESCE 保留第一次落终态的时间，不会被反复刷新。
     *
     * @return true=批次 run，已回写；false=非批次 run，一个字都没写
     */
    public boolean onRunStatusChanged(long experimentRunId) throws SQLException {
        BatchRef ref = findBatchRef(experimentRunId);
        if (ref == null) {
            return false;
        }
        // attempt 行 + 批次汇总在一个事务里落，避免"尝试已 completed、批次还是 running"
        com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            updateAttemptFromRun(c, ref.batchRunId(), experimentRunId);
            String derived = derivedBatchStatus(c, ref.batchId());
            if (derived != null) {
                updateBatchStatus(c, ref.batchId(), derived);
            }
            return null;
        });
        // 再按 D6 聚合实验状态（读侧自愈口径：同批只要有一次完成，整体就算完成）
        syncExperimentStatusFromBatches(ref.experimentId());
        return true;
    }

    /**
     * 按 {@code experiment_run} 当前状态回写 attempt 行。
     *
     * <p>终态才落 {@code finished_at}（COALESCE 保留第一次的终态时间，重复调用不刷新）；
     * paused/queued/running 是非终态，清掉 {@code finished_at} —— 与执行器
     * {@code settle} 的口径一致：暂停不是结束。
     */
    private void updateAttemptFromRun(Connection c, long batchRunId, long experimentRunId)
            throws SQLException {
        // 异步分批的 attempt 是 queued 建的（started_at 为空），真正开跑时间
        // 在 run.started_at；同步路径建尝试时已落 started_at。COALESCE 两边都保留。
        bm().updateAttemptFromRun(tenantId, batchRunId);
    }

    /**
     * 读侧自愈口径重算某批的当前状态（与 {@link #loadBatches} 完全一致）：
     * <b>任一次尝试 completed ⇒ 该批 completed</b>；否则取最新一次尝试的状态
     * （run 状态优先于 attempt 行 —— 人工停止/恢复是直接改 run 的）。
     *
     * @return 派生状态；没有尝试时返回 {@code null}（调用方不写）
     */
    private String derivedBatchStatus(Connection c, long batchId) throws SQLException {
        return bm().selectDerivedBatchStatus(tenantId, batchId);
    }

    // ---------------------------------------------------------------- 落库

    /** 跑一批：先建 queued 的 experiment_run + attempt 行，再交给执行器跑完。 */
    private BatchResult executeBatch(long experimentId, BatchRow batch,
                                     Map<String, String> userOverrides) throws SQLException {
        if (runner == null) {
            // 纯回写实例（D8/D11）误调执行路径时给明确错误，而不是 NPE
            throw new IllegalStateException("该编排实例未装配执行体，只能做状态回写");
        }
        // D5：批变量注入（batchNo / batchSamples），用户显式变量优先
        Map<String, String> vars = BatchPlanner.batchVariables(
                batch.batchNo(), batch.barcodesCsv(), userOverrides);
        long runId = createQueuedRun(experimentId);
        // D12：attempt_no 的"读-算-写"必须串行 —— 先 SELECT ... FOR UPDATE 锁住批次行，
        // 再算 MAX(attempt_no)+1 并 INSERT，同一事务提交。否则同批并发重跑会撞
        // UNIQUE(batch_id, attempt_no)。锁的是 experiment_batch 行（每次重跑都动它）。
        // 事务结果用局部 record 带出：lambda 不能给外部局部变量赋值，
        // 而且把「锁行 → 算 attempt_no → 插 batch_run」的返回值显式化更好排查。
        record BatchAttempt(int attemptNo, long batchRunId) { }
        BatchAttempt attempt = com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            lockBatchRow(c, batch.id());
            int no = nextAttemptNo(c, batch.id());
            long id = insertBatchRun(c, batch.id(), runId, no);
            return new BatchAttempt(no, id);
        });
        int attemptNo = attempt.attemptNo();
        long batchRunId = attempt.batchRunId();
        updateBatchStatus(batch.id(), "running");
        ExperimentExecutor.Result r;
        try {
            // syncExperimentStatus=false：实验状态由本类按 D6 聚合，见类注释
            r = runner.run(experimentId, vars, runId, false);
        } catch (Exception e) {
            // 执行器在 settle 之前就抛了（比如展开为空）：兜底落 failed，
            // 不让 run / 尝试 / 批次停在 running 挡住后面的批（D3：失败继续后续批）
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            markRunFailed(runId, err);
            finishBatchRun(batchRunId, "failed", err, true);
            updateBatchStatus(batch.id(), "failed");
            return new BatchResult(batch.batchNo(), runId, batchRunId, attemptNo,
                    "failed", 0, 0, err);
        }
        finishBatchRun(batchRunId, r.status(), r.error(), isTerminal(r.status()));
        updateBatchStatus(batch.id(), r.status());
        return new BatchResult(batch.batchNo(), runId, batchRunId, attemptNo,
                r.status(), r.doneSteps(), r.totalSteps(), r.error());
    }

    private long createQueuedRun(long experimentId) throws SQLException {
        return bm().insertQueuedRunPlain(tenantId, experimentId);
    }

    /** D12：锁住批次行，串行化同批并发重跑的 attempt_no 计算。 */
    private void lockBatchRow(Connection c, long batchId) throws SQLException {
        if (bm().lockBatch(tenantId, batchId) == null) {
            throw new IllegalArgumentException("批次不存在：batch#" + batchId);
        }
    }

    private int nextAttemptNo(Connection c, long batchId) throws SQLException {
        return bm().nextAttemptNo(tenantId, batchId);
    }

    private long insertBatchRun(Connection c, long batchId, long runId, int attemptNo)
            throws SQLException {
        return bm().insertBatchRunRunning(tenantId, batchId, runId, attemptNo);
    }

    private void finishBatchRun(long batchRunId, String status, String error, boolean terminal)
            throws SQLException {
        bm().finishBatchRun(tenantId, batchRunId, status, error, terminal);
    }

    private void updateBatchStatus(long batchId, String status) throws SQLException {
        bm().updateBatchStatus(tenantId, batchId, status);
    }

    /** 在给定连接/事务上更新批次状态（D8 回写事务用）。 */
    private void updateBatchStatus(Connection c, long batchId, String status)
            throws SQLException {
        bm().updateBatchStatus(tenantId, batchId, status);
    }

    /**
     * 配置入口（D11）：设置 / 清空 {@code experiment.batch_split}。
     *
     * <p>规则：
     * <ol>
     *   <li>入参必须 {@code > 0} 或 {@code null}（0 由接口层归一化成 null，这里再防御一次）；</li>
     *   <li>已有批次行（快照已固化，D2）时<b>拒绝改值</b>：要重切必须先清批次数据，
     *       否则同一个实验会出现两套切分口径；</li>
     *   <li>同值重复设置放行（幂等），前端重复点保存不该报错。</li>
     * </ol>
     *
     * @return 归一化后写进库的值（{@code null} = 已关闭分批）
     * @throws IllegalArgumentException 值非法 / 实验不存在 / 已有批次时改了值
     */
    public Integer setBatchSplit(long experimentId, Integer batchSplit) throws SQLException {
        if (batchSplit != null && batchSplit <= 0) {
            throw new IllegalArgumentException(
                    "batchSplit 必须为正整数或 null（0 表示关闭分批）");
        }
        Integer normalized = BatchPlanner.normalizeSplit(batchSplit);
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Integer current = bm().lockExperimentBatchSplit(tenantId, experimentId);
            if (current == null) {
                throw new IllegalArgumentException("实验不存在：" + experimentId);
            }
            current = current <= 0 ? null : current;
            if (bm().hasBatches(tenantId, experimentId) && !Objects.equals(current, normalized)) {
                throw new IllegalArgumentException(
                        "实验已有批次（快照已固化），不能改 batch_split；"
                                + "要改请先清批次数据");
            }
            bm().updateExperimentBatchSplit(tenantId, experimentId, normalized);
            return normalized;
        });
    }

    /** 执行器在 settle 前抛异常时的兜底落库（与 ExperimentScheduler.markStatus 同口径）。 */
    private void markRunFailed(long runId, String error) throws SQLException {
        bm().markRunFailed(tenantId, runId, error);
    }

    /** 实验进入 running（开始按批执行）。 */
    private void markExperimentRunning(long experimentId) throws SQLException {
        // 收口到 ExperimentStateProjector（S-03 单写者）：SQL 与改前逐字一致。
        try (Connection c = db.open()) {
            ExperimentStateProjector.markRunningByExperiment(c, tenantId, experimentId);
        }
    }

    /**
     * 按 D6 聚合刷新 {@code experiment.status}。
     *
     * @return 写入的聚合状态；{@code null} 表示没有决定性状态（保持原值不动）
     */
    String syncExperimentStatusFromBatches(long experimentId) throws SQLException {
        String aggregate = aggregateStatus(experimentId);
        if (aggregate == null) {
            return null;
        }
        // 收口到 ExperimentStateProjector（S-03 单写者）：SQL 与改前逐字一致，
        // 聚合值仍由 BatchPlanner.aggregateExperimentStatus（D6 纯函数）算出后传入。
        try (Connection c = db.open()) {
            ExperimentStateProjector.syncByBatchAggregate(c, tenantId, experimentId, aggregate);
        }
        // §4.5.2 批次：整单终态时卸载全部批次 run 的 process 物料（失败只 warn，不阻断聚合）。
        if ("completed".equals(aggregate) || "failed".equals(aggregate)
                || "aborted".equals(aggregate) || "cancelled".equals(aggregate)) {
            unloadProcessMaterialsByExperiment(experimentId);
        }
        return aggregate;
    }

    /** §4.5.2 批次：实验终态卸载跨所有批次 run 的 lifecycle='process' 物料。 */
    private void unloadProcessMaterialsByExperiment(long experimentId) {
        try {
            com.labos.kernel.persistence.ManifestMapper mm =
                    com.labos.kernel.persistence.Mappers.get(
                            com.labos.kernel.persistence.ManifestMapper.class);
            com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
                mm.markProcessPlacementsUnloadedByExperiment(tenantId, experimentId);
                mm.clearProcessPositionStatesByExperiment(tenantId, experimentId);
                return null;
            });
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BatchOrchestrator.class)
                    .warn("[物料] 批次实验终态卸载 process 物料失败（不阻断聚合）: {}", e.getMessage());
        }
    }

    private String aggregateStatus(long experimentId) throws SQLException {
        List<String> statuses = new ArrayList<>();
        for (BatchRow b : loadBatches(experimentId)) {
            statuses.add(b.status());
        }
        return BatchPlanner.aggregateExperimentStatus(statuses);
    }

    // ---------------------------------------------------------------- 读模型

    /** 实验样品（按 sample_id 升序，同一样品多角色只算一次）。 */
    private List<BatchPlanner.Sample> loadSamples(long experimentId) throws SQLException {
        List<BatchPlanner.Sample> out = new ArrayList<>();
        for (Map<String, Object> row : bm().selectSamples(tenantId, experimentId)) {
            out.add(new BatchPlanner.Sample(((Number) row.get("sampleId")).longValue(),
                    (String) row.get("barcode")));
        }
        return out;
    }

    /** 批次快照 JSON：[{sampleId,barcode}, ...]。与 GET 里的 samples 同一形状。 */
    private static String samplesJson(List<BatchPlanner.Sample> samples) {
        List<String> items = new ArrayList<>();
        for (BatchPlanner.Sample s : samples) {
            items.add(Jsons.obj("sampleId", s.sampleId(), "barcode", s.barcode()));
        }
        return Jsons.arr(items);
    }

    /**
     * 读批次 + 所有尝试。
     *
     * <p><b>派生状态口径（S3.2-D，§11.3-5）</b>，按优先级：
     * <ol>
     *   <li>任一次尝试 completed → {@code completed}（已成功的批不允许再被重跑）；</li>
     *   <li>否则最新尝试对应的 {@code experiment_run.status} 是 running/queued → 该状态
     *       （正在跑/已排队的批哪怕表里还标 paused，也要显示真实活动态）；</li>
     *   <li>否则 {@code experiment_batch.status='paused'} → {@code paused}
     *       （批停在边界/等人，<b>即使上一次尝试是 failed</b> —— 读数的人该看到"等处置"，
     *       而不是"失败终态"）；</li>
     *   <li>否则取最新尝试的状态；没有尝试时取表里的 storedStatus。</li>
     * </ol>
     *
     * <p>暂停/停止/恢复也会直接改 {@code experiment_run.status}（运行控制路径），
     * 只信 {@code experiment_batch_run.status} 会读到过期值，所以最新尝试以 run 状态优先。
     */
    private List<BatchRow> loadBatches(long experimentId) throws SQLException {
        Map<Long, Acc> accs = new LinkedHashMap<>();
        for (Map<String, Object> row : bm().selectBatchViewRows(tenantId, experimentId)) {
            long batchId = ((Number) row.get("batchId")).longValue();
            Acc acc = accs.computeIfAbsent(batchId, k -> new Acc(batchId,
                    ((Number) row.get("batchNo")).intValue(),
                    ((Number) row.get("sampleCount")).intValue(),
                    (String) row.get("samplesJson"), (String) row.get("barcodes"),
                    (String) row.get("storedStatus")));
            Object batchRunIdObj = row.get("batchRunId");
            if (batchRunIdObj == null) {
                continue;
            }
            long batchRunId = ((Number) batchRunIdObj).longValue();
            String runStatus = (String) row.get("runStatus");
            acc.latestRunStatus = runStatus;
            String attemptStatus = runStatus != null ? runStatus
                    : (String) row.get("attemptStatus");
            acc.runs.add(new AttemptRow(batchRunId,
                    ((Number) row.get("experimentRunId")).longValue(),
                    ((Number) row.get("attemptNo")).intValue(), attemptStatus,
                    tsStr(row.get("startedAt")), tsStr(row.get("finishedAt")),
                    (String) row.get("error")));
            if ("completed".equals(attemptStatus)
                    || "completed".equals(row.get("attemptStatus"))) {
                acc.anyCompleted = true;
            }
        }
        List<BatchRow> out = new ArrayList<>();
        for (Acc a : accs.values()) {
            String latestAttempt = a.runs.isEmpty()
                    ? null : a.runs.get(a.runs.size() - 1).status();
            String status;
            if (a.anyCompleted) {
                status = "completed";
            } else if ("running".equals(a.latestRunStatus) || "queued".equals(a.latestRunStatus)) {
                status = a.latestRunStatus;
            } else if ("paused".equals(a.storedStatus)) {
                status = "paused";
            } else {
                status = latestAttempt != null ? latestAttempt : a.storedStatus;
            }
            out.add(new BatchRow(a.id, a.batchNo, a.sampleCount, a.samplesJson, a.barcodes,
                    status, a.storedStatus, List.copyOf(a.runs)));
        }
        return out;
    }

    /** 可变累加器（record 不适合边读边攒）。 */
    private static final class Acc {
        final long id;
        final int batchNo;
        final int sampleCount;
        final String samplesJson;
        final String barcodes;
        final String storedStatus;
        final List<AttemptRow> runs = new ArrayList<>();
        boolean anyCompleted;
        /** 最新一次尝试对应的 experiment_run 状态（没有尝试/run 行缺失为 null）。 */
        String latestRunStatus;

        Acc(long id, int batchNo, int sampleCount, String samplesJson, String barcodes,
            String storedStatus) {
            this.id = id;
            this.batchNo = batchNo;
            this.sampleCount = sampleCount;
            this.samplesJson = samplesJson;
            this.barcodes = barcodes;
            this.storedStatus = storedStatus;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "aborted".equals(status) || "cancelled".equals(status);
    }

    private static String tsStr(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toLocalDateTime().format(TS_FMT);
    }

    /** 实验所属岛；未分配返回 0（0 不匹配任何 island 级扣留）。 */
    private long islandOf(long experimentId) throws SQLException {
        Long v = bm().selectIslandIdOfExperiment(tenantId, experimentId);
        return v == null ? 0L : v;
    }
}
