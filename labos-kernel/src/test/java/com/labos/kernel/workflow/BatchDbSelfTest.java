package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分批执行落库自测（需要真实库）。
 *
 * <p>覆盖实施清单 §8.4 的验收点（DB 集成这一侧）：
 * <ol>
 *   <li><b>建批幂等 + 样品快照</b>：首次按 sample_id 升序切批、快照入库；
 *       再次调用不重切；改了 batch_split / 后来又加样品也不重切（D2）。</li>
 *   <li><b>一批失败后重跑</b>：attempt_no=2，历史尝试与历史 run 保留（两条历史），
 *       成功批不受影响（D3/D4，评审 B2 的落库形态）。</li>
 *   <li><b>成功批重跑被拒</b>：只允许非 completed 批（D4）。</li>
 *   <li><b>batch_split=NULL 回归</b>：不分批时走非分批路径（一次 run + 执行器同步实验状态），
 *       不建任何批次（D1）。</li>
 *   <li><b>状态聚合规则</b>：D6 四条优先级逐条覆盖。</li>
 *   <li><b>批边界扣留</b>：有现场扣留时一批都不启动、该批 status=paused（D7）。</li>
 * </ol>
 *
 * <p><b>不自造设备动作</b>：每批的执行体用注入的假 {@link BatchOrchestrator.BatchRunner}
 * 模拟（真实执行器的"一批一次 run"由手工 curl + 执行历史覆盖）。这样即使连着现场库，
 * 自测也<b>不会真的动设备</b>，只碰自己造的负数 id 数据，finally 清干净。
 */
public final class BatchDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    // 合成负数 id：与真实自增 id 不可能撞，finally 里按这几组清干净
    private static final long EXP_BATCHED = -993010L;   // batch_split=2，5 个样品 -> 3 批
    private static final long EXP_SNAPSHOT = -993011L;  // batch_split=3，4 个样品 -> 2 批
    private static final long EXP_UNBATCHED = -993012L; // batch_split NULL
    private static final long EXP_ZERO = -993013L;      // batch_split=0
    private static final long EXP_AGG = -993014L;       // batch_split=1，3 个样品
    private static final long EXP_NOSAMPLE = -993015L;  // batch_split=3，无样品
    private static final long EXP_HOLD = -993016L;      // batch_split=1，2 个样品；批边界扣留
    // ---- S3.1（§10）新增：续跑/停止回写、batch_split 配置 ----
    private static final long EXP_WRITEBACK = -993017L; // batch_split=1，1 个样品；D8 回写 + 幂等
    private static final long EXP_RESUME = -993018L;    // batch_split=1，1 个样品；D10 resume 回写
    private static final long EXP_STOP = -993019L;      // batch_split=1，1 个样品；D10 stop 回写
    private static final long EXP_SPLIT = -993020L;     // batch_split=NULL；D11 设置/清空
    private static final long EXP_SPLIT_LOCKED = -993021L; // 已建批；D11 拒绝改值
    private static final long EXP_CONC = -993022L;      // 同批并发重跑（D12 行锁）
    // ---- 异步分批（收口 B）：调度器按批派发的"下一批尝试建单"语义 ----
    private static final long EXP_ASYNC = -993023L;      // batch_split=1，3 个样品；创建顺序/失败继续
    private static final long EXP_ASYNC_STOP = -993024L; // batch_split=1，2 个样品；aborted 不续
    private static final long EXP_ASYNC_HOLD = -993025L; // batch_split=1，2 个样品；扣留不建下一批
    private static final long EXP_ASYNC_DONE = -993026L; // batch_split=1，2 个样品；全部完成后 NO_MORE
    // ---- S3.2 残余（§11.3）：自动续队候选 / async vars / paused 派生状态 ----
    private static final long EXP_AC = -993040L;         // batch_split=1，2 样品；paused 无 attempt 候选
    private static final long EXP_AC_ATTEMPT = -993041L; // batch_split=1，1 样品；paused 但有 attempt -> 不枚举
    private static final long EXP_AC_ABORTED = -993042L; // batch_split=1，1 样品；实验 aborted -> 不枚举
    private static final long EXP_AC_HOLD = -993043L;    // batch_split=1，1 样品；覆盖扣留 -> 不续
    private static final long EXP_VARS = -993044L;       // batch_split=NULL；submit(exp,vars)
    private static final long EXP_VARS_BATCH = -993045L; // batch_split=1，2 样品；批 run vars + 续队继承
    private static final long EXP_DERIVED = -993046L;    // batch_split=2，3 样品；paused 派生状态

    private static final long SAMPLE_BASE = -993100L;

    // 跨用例共享的"上一轮执行的 run id"（验证历史保留 / 成功批不受影响）
    private static long runBatch1;
    private static long runBatch2;
    private static long runBatch3;

    // S3.1 回写用 run：显式负数 id，方便直接按 runId 查/改（不碰真实自增 id）
    private static final long RUN_WB = -993201L;        // EXP_WRITEBACK，批次 run
    private static final long RUN_NONBATCH = -993202L;  // EXP_UNBATCHED，非批次 run
    private static final long RUN_RESUME = -993203L;    // EXP_RESUME，批次 run
    private static final long RUN_STOP = -993204L;      // EXP_STOP，批次 run
    // S3.2 残余：自动续队/派生状态用的显式负数 run id
    private static final long RUN_AC_ATTEMPT = -993205L; // EXP_AC_ATTEMPT，失败的批次 run
    private static final long RUN_DERIVED = -993206L;    // EXP_DERIVED，失败的批次 run

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();
            batchedRunContinuesAfterFailure();
            rerunFailedBatchKeepsHistory();
            completedBatchRerunRejected();
            createBatchesIdempotentAndSnapshot();
            unbatchedGoesOldPath();
            zeroSplitGoesOldPath();
            aggregationRules();
            holdAtBatchBoundary();
            emptySamplesRejected();
            batchesJsonShape();
            runStatusWriteback();
            resumeWriteback();
            stopWriteback();
            batchSplitConfig();
            concurrentRerunNoUniqueViolation();
            asyncNextBatchSequence();
            abortedStopsContinuation();
            holdStopsNextBatch();
            asyncEdgeCases();
            pausedBatchDerivedStatus();
            autoContinueHeldBatchesOnRelease();
            asyncVarsPersistence();
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 1. 分批执行

    private static void batchedRunContinuesAfterFailure() throws Exception {
        section("1. 一次执行 = 按批串行跑所有未成功批（失败继续）");
        FakeRunner runner = new FakeRunner().statuses("completed", "failed", "completed");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, runner);

        BatchOrchestrator.RunAllResult r = orch.runAll(EXP_BATCHED, null);
        check("识别为分批执行", r.batched(), r.batched());
        check("3 个批次都跑了", r.batches().size() == 3, r.batches().size());
        check("批1 completed", "completed".equals(r.batches().get(0).status()),
                r.batches().get(0).status());
        check("批2 failed", "failed".equals(r.batches().get(1).status()),
                r.batches().get(1).status());
        check("批3 completed（失败后继续后续批）", "completed".equals(r.batches().get(2).status()),
                r.batches().get(2).status());
        check("聚合状态 failed", "failed".equals(r.status()), r.status());
        check("实验表按 D6 聚合为 failed",
                "failed".equals(strOfExperiment(EXP_BATCHED, "status")),
                strOfExperiment(EXP_BATCHED, "status"));
        check("每批一个 run（3 条 experiment_run）", countRuns(EXP_BATCHED) == 3,
                countRuns(EXP_BATCHED));
        check("执行器每批被调用一次（串行 3 次）", runner.calls.size() == 3,
                runner.calls.size());
        check("每批都新建 run（existingRunId 互不相同）",
                new java.util.HashSet<>(runner.runIds).size() == 3, runner.runIds);
        check("分批模式不让执行器同步实验状态", !runner.syncFlags.contains(Boolean.TRUE),
                runner.syncFlags);
        check("批变量 batchNo 按批次递增",
                "1".equals(runner.calls.get(0).get("batchNo"))
                        && "2".equals(runner.calls.get(1).get("batchNo"))
                        && "3".equals(runner.calls.get(2).get("batchNo")),
                runner.calls.stream().map(m -> m.get("batchNo")).toList());
        check("批变量 batchSamples 是快照条码逗号串（升序）",
                ("BC-S3-01,BC-S3-02").equals(runner.calls.get(0).get("batchSamples")),
                runner.calls.get(0).get("batchSamples"));
        check("最后一批样品只有 1 个（5=2+2+1）",
                ("BC-S3-05").equals(runner.calls.get(2).get("batchSamples")),
                runner.calls.get(2).get("batchSamples"));

        List<BatchOrchestrator.BatchRow> rows = orch.ensureBatches(EXP_BATCHED);
        check("批次状态落库 completed/failed/completed",
                "completed".equals(rows.get(0).status())
                        && "failed".equals(rows.get(1).status())
                        && "completed".equals(rows.get(2).status()),
                rows.stream().map(BatchOrchestrator.BatchRow::status).toList());
        check("每批 1 次尝试", rows.stream().allMatch(b -> b.runs().size() == 1),
                rows.stream().map(b -> b.runs().size()).toList());
        check("尝试里的 runId = 执行器拿到的 runId",
                rows.get(0).runs().get(0).runId() == runner.runIds.get(0)
                        && rows.get(1).runs().get(0).runId() == runner.runIds.get(1)
                        && rows.get(2).runs().get(0).runId() == runner.runIds.get(2),
                rows.stream().map(b -> b.runs().get(0).runId()).toList());

        runBatch1 = rows.get(0).runs().get(0).runId();
        runBatch2 = rows.get(1).runs().get(0).runId();
        runBatch3 = rows.get(2).runs().get(0).runId();
    }

    // ================================================================ 2. 重跑失败批

    private static void rerunFailedBatchKeepsHistory() throws Exception {
        section("2. 失败批重跑：attempt_no=2、历史保留、成功批不受影响");
        FakeRunner runner = new FakeRunner().statuses("completed");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, runner);

        BatchOrchestrator.BatchResult r = orch.runBatch(EXP_BATCHED, 2, null);
        check("重跑批次号对", r.batchNo() == 2, r.batchNo());
        check("新尝试 attempt_no=2", r.attemptNo() == 2, r.attemptNo());
        check("重跑结果 completed", "completed".equals(r.status()), r.status());
        check("重跑用新 run", r.runId() != runBatch2, r.runId() + " vs " + runBatch2);
        check("重跑只跑一批（执行器只调一次）", runner.calls.size() == 1, runner.calls.size());

        List<BatchOrchestrator.BatchRow> rows = orch.ensureBatches(EXP_BATCHED);
        BatchOrchestrator.BatchRow b2 = rows.get(1);
        check("批2 两条尝试历史", b2.runs().size() == 2, b2.runs().size());
        check("attempt_no 依次 1,2",
                b2.runs().get(0).attemptNo() == 1 && b2.runs().get(1).attemptNo() == 2,
                b2.runs().stream().map(BatchOrchestrator.AttemptRow::attemptNo).toList());
        check("历史尝试仍是 failed 且 run 不变",
                "failed".equals(b2.runs().get(0).status())
                        && b2.runs().get(0).runId() == runBatch2,
                b2.runs().get(0));
        check("新尝试 completed", "completed".equals(b2.runs().get(1).status()),
                b2.runs().get(1).status());
        check("历史 run 记录保留且仍是 failed",
                "failed".equals(runStatus(runBatch2)), runStatus(runBatch2));
        check("批当前状态 = completed", "completed".equals(b2.status()), b2.status());

        BatchOrchestrator.BatchRow b1 = rows.get(0);
        BatchOrchestrator.BatchRow b3 = rows.get(2);
        check("成功批1 尝试数不变", b1.runs().size() == 1, b1.runs().size());
        check("成功批3 尝试数不变", b3.runs().size() == 1, b3.runs().size());
        check("成功批1 的 run 未被覆盖",
                b1.runs().get(0).runId() == runBatch1, b1.runs().get(0).runId());
        check("成功批3 的 run 未被覆盖",
                b3.runs().get(0).runId() == runBatch3, b3.runs().get(0).runId());
        check("重跑后聚合 = finished（三批全完成）",
                "finished".equals(strOfExperiment(EXP_BATCHED, "status")),
                strOfExperiment(EXP_BATCHED, "status"));
    }

    // ================================================================ 3. 成功批拒绝重跑

    private static void completedBatchRerunRejected() throws Exception {
        section("3. 成功批重跑被拒（D4）");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, new FakeRunner());
        try {
            orch.runBatch(EXP_BATCHED, 1, null);
            check("已完成批重跑应被拒绝", false, "没有抛异常");
        } catch (IllegalArgumentException e) {
            check("已完成批重跑被拒（400 口径）",
                    e.getMessage() != null && e.getMessage().contains("已完成"), e.getMessage());
        }
        List<BatchOrchestrator.BatchRow> rows = orch.ensureBatches(EXP_BATCHED);
        check("被拒后批1 尝试数仍为 1", rows.get(0).runs().size() == 1,
                rows.get(0).runs().size());
    }

    // ================================================================ 4. 建批幂等 + 快照

    private static void createBatchesIdempotentAndSnapshot() throws Exception {
        section("4. 建批幂等 + 样品快照（D2）");
        FakeRunner runner = new FakeRunner();
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, runner);

        check("切批计划：4 个样品 / 每批 3 -> 2 批",
                orch.splitPlan(EXP_SNAPSHOT).size() == 2, orch.splitPlan(EXP_SNAPSHOT).size());
        List<BatchOrchestrator.BatchRow> first = orch.ensureBatches(EXP_SNAPSHOT);
        List<Long> ids = first.stream().map(BatchOrchestrator.BatchRow::id).toList();
        check("首次建批 2 条", first.size() == 2, first.size());
        check("快照样品数 3 + 1",
                first.get(0).sampleCount() == 3 && first.get(1).sampleCount() == 1,
                first.stream().map(BatchOrchestrator.BatchRow::sampleCount).toList());
        String snap1 = first.get(0).samplesJson();
        check("快照 json 含 sampleId/barcode", snap1.contains("\"sampleId\"")
                && snap1.contains("\"barcode\""), snap1);
        check("快照按 sample_id 升序",
                snap1.indexOf(String.valueOf(SAMPLE_BASE + 11))
                        < snap1.indexOf(String.valueOf(SAMPLE_BASE + 12)), snap1);

        List<BatchOrchestrator.BatchRow> second = orch.ensureBatches(EXP_SNAPSHOT);
        check("二次建批幂等（id 不变）",
                ids.equals(second.stream().map(BatchOrchestrator.BatchRow::id).toList()),
                second.stream().map(BatchOrchestrator.BatchRow::id).toList());
        check("批次表仍只有 2 条", countBatches(EXP_SNAPSHOT) == 2,
                countBatches(EXP_SNAPSHOT));

        // 首次建批之后再改 batch_split、再加样品：都不许重切（快照固化）
        addSampleToExperiment(EXP_SNAPSHOT, SAMPLE_BASE + 15, "BC-S3-EXTRA");
        exec("UPDATE experiment SET batch_split = 1 WHERE tenant_id = ? AND id = ?",
                TENANT, EXP_SNAPSHOT);
        List<BatchOrchestrator.BatchRow> third = orch.ensureBatches(EXP_SNAPSHOT);
        check("改 batch_split 后不重切（仍 2 批）", third.size() == 2, third.size());
        check("后加的样品不进已有快照",
                !third.get(0).samplesJson().contains(String.valueOf(SAMPLE_BASE + 15))
                        && !third.get(1).samplesJson().contains(String.valueOf(SAMPLE_BASE + 15)),
                third.get(0).samplesJson());
    }

    // ================================================================ 5. NULL 回归

    private static void unbatchedGoesOldPath() throws Exception {
        section("5. batch_split=NULL 回归：完全走非分批路径（D1）");
        FakeRunner runner = new FakeRunner().statuses("completed");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, runner);

        BatchOrchestrator.RunAllResult r = orch.runAll(EXP_UNBATCHED, Map.of("foo", "bar"));
        check("识别为不分批", !r.batched(), r.batched());
        check("执行结果原样透传（status）", "completed".equals(r.status()), r.status());
        check("执行结果原样透传（steps）",
                r.totalSteps() == 2 && r.doneSteps() == 2,
                r.doneSteps() + "/" + r.totalSteps());
        check("不带 batchSplit / batches", r.batchSplit() == null && r.batches().isEmpty(),
                r.batchSplit());
        check("非分批路径用一次 run 跑整单（existingRunId=0）",
                runner.runIds.size() == 1 && runner.runIds.get(0) == 0L, runner.runIds);
        check("非分批路径不建批", countBatches(EXP_UNBATCHED) == 0, countBatches(EXP_UNBATCHED));
        check("非分批路径变量原样传执行器",
                "bar".equals(runner.calls.get(0).get("foo")), runner.calls.get(0));
        check("非分批路径允许执行器同步实验状态（sync=true）",
                Boolean.TRUE.equals(runner.syncFlags.get(0)), runner.syncFlags);
        String json = orch.batchesJson(EXP_UNBATCHED);
        check("批次视图 batchSplit=null", json.contains("\"batchSplit\":null"), json);
        check("批次视图 items=[]", json.contains("\"items\":[]"), json);
    }

    private static void zeroSplitGoesOldPath() throws Exception {
        section("6. batch_split=0 回归：视同不分批");
        FakeRunner runner = new FakeRunner().statuses("completed");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, runner);
        BatchOrchestrator.RunAllResult r = orch.runAll(EXP_ZERO, null);
        check("batch_split=0 走非分批路径", !r.batched(), r.batched());
        check("batch_split=0 不建批", countBatches(EXP_ZERO) == 0, countBatches(EXP_ZERO));
        check("batch_split=0 仍是一次 run", runner.runIds.size() == 1, runner.runIds);
        check("batch_split=0 时批量接口返回 null",
                orch.batchSplit(EXP_ZERO) == null, orch.batchSplit(EXP_ZERO));
    }

    // ================================================================ 7. 状态聚合

    private static void aggregationRules() throws Exception {
        section("7. 状态聚合 D6（逐条）");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, new FakeRunner());
        orch.ensureBatches(EXP_AGG);

        // 7.1 全是 pending -> 不下结论，实验状态保持原值
        exec("UPDATE experiment SET status = 'planned' WHERE tenant_id = ? AND id = ?",
                TENANT, EXP_AGG);
        String agg = orch.syncExperimentStatusFromBatches(EXP_AGG);
        check("全是 pending -> 聚合 null（不写）", agg == null, agg);
        check("全是 pending -> 实验状态保持 planned",
                "planned".equals(strOfExperiment(EXP_AGG, "status")),
                strOfExperiment(EXP_AGG, "status"));

        // 7.2 全部 completed -> finished
        setBatchStatuses(EXP_AGG, "completed", "completed", "completed");
        check("全部 completed -> finished",
                "finished".equals(orch.syncExperimentStatusFromBatches(EXP_AGG)),
                strOfExperiment(EXP_AGG, "status"));
        check("finished 有结束时间", strOfExperiment(EXP_AGG, "finished_at") != null,
                strOfExperiment(EXP_AGG, "finished_at"));

        // 7.3 有 running -> running
        setBatchStatuses(EXP_AGG, "running", "completed", "completed");
        check("有 running -> running",
                "running".equals(orch.syncExperimentStatusFromBatches(EXP_AGG)),
                strOfExperiment(EXP_AGG, "status"));

        // 7.4 paused 优先于 failed（无 running 时）
        setBatchStatuses(EXP_AGG, "paused", "failed", "completed");
        check("paused 优先于 failed",
                "paused".equals(orch.syncExperimentStatusFromBatches(EXP_AGG)),
                strOfExperiment(EXP_AGG, "status"));
        check("paused 不再是终态（清掉 finished_at）",
                strOfExperiment(EXP_AGG, "finished_at") == null,
                strOfExperiment(EXP_AGG, "finished_at"));

        // 7.5 无 running/paused 且任一 failed -> failed
        setBatchStatuses(EXP_AGG, "completed", "failed", "completed");
        check("completed+failed -> failed",
                "failed".equals(orch.syncExperimentStatusFromBatches(EXP_AGG)),
                strOfExperiment(EXP_AGG, "status"));

        // 7.6 aborted 与 failed 同口径
        setBatchStatuses(EXP_AGG, "completed", "aborted", "completed");
        check("aborted 也 -> failed",
                "failed".equals(orch.syncExperimentStatusFromBatches(EXP_AGG)),
                strOfExperiment(EXP_AGG, "status"));
    }

    // ================================================================ 8. 批边界扣留

    private static void holdAtBatchBoundary() throws Exception {
        section("8. 批边界扣留：停在边界、不启动下一批（D7）");
        FakeRunner runner = new FakeRunner().statuses("completed", "completed");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, runner);
        List<BatchOrchestrator.BatchRow> rows = orch.ensureBatches(EXP_HOLD);
        check("2 个逻辑批", rows.size() == 2, rows.size());

        RuntimeHold holds = new RuntimeHold(db, TENANT);
        holds.raise(RuntimeHold.Scope.EXPERIMENT, EXP_HOLD, RuntimeHold.Kind.MANUAL,
                "自测：现场扣留", null);

        BatchOrchestrator.RunAllResult r = orch.runAll(EXP_HOLD, null);
        check("有扣留时一批都不启动", runner.calls.isEmpty(), runner.calls.size());
        check("执行结果 status=paused", "paused".equals(r.status()), r.status());
        List<BatchOrchestrator.BatchRow> after = orch.ensureBatches(EXP_HOLD);
        check("第一批 status=paused（批边界停）",
                "paused".equals(after.get(0).status()), after.get(0).status());
        check("暂停批没有任何尝试（没启动 run）", after.get(0).runs().isEmpty(),
                after.get(0).runs().size());
        check("后续批仍 pending", "pending".equals(after.get(1).status()),
                after.get(1).status());
        check("实验状态聚合 paused",
                "paused".equals(strOfExperiment(EXP_HOLD, "status")),
                strOfExperiment(EXP_HOLD, "status"));

        try {
            orch.runBatch(EXP_HOLD, 1, null);
            check("扣留期间单批重跑应被拒", false, "没有抛异常");
        } catch (IllegalArgumentException e) {
            check("扣留期间单批重跑被拒（400 口径）",
                    e.getMessage() != null && e.getMessage().contains("扣留"), e.getMessage());
        }
        check("被拒后仍没有尝试",
                orch.ensureBatches(EXP_HOLD).get(0).runs().isEmpty(), "has runs");

        holds.clear(RuntimeHold.Scope.EXPERIMENT, EXP_HOLD, null, "自测：解除扣留");
        BatchOrchestrator.RunAllResult r2 = orch.runAll(EXP_HOLD, null);
        check("解除扣留后从第一批继续跑", runner.calls.size() == 2, runner.calls.size());
        check("解除后两批都 completed", r2.batches().size() == 2,
                r2.batches().size());
        check("解除后聚合 finished",
                "finished".equals(strOfExperiment(EXP_HOLD, "status")),
                strOfExperiment(EXP_HOLD, "status"));
        List<BatchOrchestrator.BatchRow> done = orch.ensureBatches(EXP_HOLD);
        check("边界暂停的批从 attempt_no=1 开始（边界没建尝试）",
                done.get(0).runs().size() == 1
                        && done.get(0).runs().get(0).attemptNo() == 1,
                done.get(0).runs().size());
    }

    // ================================================================ 9. 空样品

    private static void emptySamplesRejected() throws Exception {
        section("9. 配了分批却没有样品 -> 明确报错（不静默降级）");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, new FakeRunner());
        try {
            orch.ensureBatches(EXP_NOSAMPLE);
            check("空样品应报错", false, "没有抛异常");
        } catch (IllegalArgumentException e) {
            check("空样品报配置错误",
                    e.getMessage() != null && e.getMessage().contains("没有样品"), e.getMessage());
        }
        try {
            orch.runAll(EXP_NOSAMPLE, null);
            check("空样品执行实验应报错", false, "没有抛异常");
        } catch (IllegalArgumentException e) {
            check("空样品时执行实验也报错",
                    e.getMessage() != null && e.getMessage().contains("没有样品"),
                    e.getMessage());
        }
        check("报错不留半成品批次", countBatches(EXP_NOSAMPLE) == 0,
                countBatches(EXP_NOSAMPLE));
    }

    // ================================================================ 10. 批次视图

    private static void batchesJsonShape() throws Exception {
        section("10. GET /batches JSON 形状（§8.3）");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT, new FakeRunner());
        String json = orch.batchesJson(EXP_BATCHED);
        check("experimentId 字段", json.contains("\"experimentId\":" + EXP_BATCHED), json);
        check("batchSplit 字段", json.contains("\"batchSplit\":2"), json);
        check("items 含 batchNo", json.contains("\"batchNo\":1"), json);
        check("items 含 sampleCount", json.contains("\"sampleCount\":2"), json);
        // jsonb 的文本形式会重排 key（barcode 在 sampleId 前）并按规范加空格；
        // 这里只断言"快照对象原样嵌进 samples 数组"，key 顺序不是接口形状的一部分。
        check("items 含 samples（快照原样嵌入）",
                json.contains("\"samples\":") && json.contains("\"sampleId\":")
                        && json.contains("\"barcode\":"),
                json.contains("\"samples\":"));
        check("items 含 attemptCount=2（失败批重跑过）",
                json.contains("\"attemptCount\":2"), json);
        check("items 含 currentRunId", json.contains("\"currentRunId\":"), json);
        check("items 含 runs 历史", json.contains("\"runs\":[{\"batchRunId\":"), json);
        check("runs 含 attemptNo=2", json.contains("\"attemptNo\":2"), json);
        check("时间格式 yyyy-MM-dd HH:mm:ss（不是 Timestamp.toString）",
                json.contains("\"startedAt\":\"20") && !json.contains("T"), json);
        check("summary total=3", json.contains("\"total\":3"), json);
        check("summary completed=3", json.contains("\"completed\":3"), json);
        check("summary failed=0", json.contains("\"failed\":0"), json);
    }

    // ================================================================ 11. D8 回写

    /**
     * §10.4-1 / §10.4-3：批次 run 状态回写（attempt 行 + 批次汇总 + D6 聚合）与幂等；
     * 非批次 run 必须返回 false 且不写任何批次表、不动实验状态。
     */
    private static void runStatusWriteback() throws Exception {
        section("11. D8 onRunStatusChanged：批次回写 + 幂等 + 非批次零变化");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        List<BatchOrchestrator.BatchRow> batches = orch.ensureBatches(EXP_WRITEBACK);
        check("单批实验建 1 个批次", batches.size() == 1, batches.size());
        long batchId = batches.get(0).id();

        // 现场 A：执行器 settle 成 paused 后（sync=false，实验表还没聚合）
        insertRunRow(RUN_WB, EXP_WRITEBACK, "paused", "自测：现场扣留");
        insertAttempt(batchId, RUN_WB, 1, "paused", "自测：现场扣留");
        exec("UPDATE experiment SET status = 'running', finished_at = NULL "
                + "WHERE tenant_id = ? AND id = ?", TENANT, EXP_WRITEBACK);

        boolean handled = orch.onRunStatusChanged(RUN_WB);
        check("批次 run 返回 true（走回写）", handled, handled);
        check("attempt 行 paused", "paused".equals(attemptCol(RUN_WB, "status")),
                attemptCol(RUN_WB, "status"));
        check("暂停不是终态：attempt.finished_at 为空",
                attemptCol(RUN_WB, "finished_at") == null,
                attemptCol(RUN_WB, "finished_at"));
        check("批次汇总 paused", "paused".equals(batchStoredStatus(EXP_WRITEBACK)),
                batchStoredStatus(EXP_WRITEBACK));
        check("实验按 D6 聚合 paused",
                "paused".equals(strOfExperiment(EXP_WRITEBACK, "status")),
                strOfExperiment(EXP_WRITEBACK, "status"));

        // 现场 B：恢复后跑完（run completed），回写必须补齐 attempt/批次/实验
        setRunStatusAndError(RUN_WB, "completed", null);
        boolean handled2 = orch.onRunStatusChanged(RUN_WB);
        check("run completed 后回写返回 true", handled2, handled2);
        check("attempt 行 completed", "completed".equals(attemptCol(RUN_WB, "status")),
                attemptCol(RUN_WB, "status"));
        check("attempt 落 finished_at", attemptCol(RUN_WB, "finished_at") != null,
                attemptCol(RUN_WB, "finished_at"));
        check("attempt 清掉先前的 error", attemptCol(RUN_WB, "error") == null,
                attemptCol(RUN_WB, "error"));
        check("批次 completed", "completed".equals(batchStoredStatus(EXP_WRITEBACK)),
                batchStoredStatus(EXP_WRITEBACK));
        check("实验按 D6 聚合 finished",
                "finished".equals(strOfExperiment(EXP_WRITEBACK, "status")),
                strOfExperiment(EXP_WRITEBACK, "status"));
        check("实验 finished 有结束时间",
                strOfExperiment(EXP_WRITEBACK, "finished_at") != null,
                strOfExperiment(EXP_WRITEBACK, "finished_at"));
        String finishedAt = attemptCol(RUN_WB, "finished_at");
        boolean handled3 = orch.onRunStatusChanged(RUN_WB);
        check("再调一次仍返回 true（幂等）", handled3, handled3);
        check("幂等：attempt 仍是 completed",
                "completed".equals(attemptCol(RUN_WB, "status")),
                attemptCol(RUN_WB, "status"));
        check("幂等：终态时间不被反复刷新",
                Objects.equals(finishedAt, attemptCol(RUN_WB, "finished_at")),
                attemptCol(RUN_WB, "finished_at"));
        check("幂等：实验仍是 finished",
                "finished".equals(strOfExperiment(EXP_WRITEBACK, "status")),
                strOfExperiment(EXP_WRITEBACK, "status"));

        // 非批次 run：非分批路径零变化（不写批次表、实验状态不由它同步）
        exec("UPDATE experiment SET status = 'planned', finished_at = NULL "
                + "WHERE tenant_id = ? AND id = ?", TENANT, EXP_UNBATCHED);
        insertRunRow(RUN_NONBATCH, EXP_UNBATCHED, "completed", null);
        boolean nonBatch = orch.onRunStatusChanged(RUN_NONBATCH);
        check("非批次 run 返回 false", !nonBatch, nonBatch);
        check("非批次 run：实验状态一个字没动",
                "planned".equals(strOfExperiment(EXP_UNBATCHED, "status")),
                strOfExperiment(EXP_UNBATCHED, "status"));
        check("非批次 run：不建批次", countBatches(EXP_UNBATCHED) == 0,
                countBatches(EXP_UNBATCHED));
    }

    // ================================================================ 12. D10 resume

    /**
     * §10.4-1：批次 run 恢复后，批次层跟着 queued；实验状态按 D6 聚合（running），
     * 而不是被"这一条 run 的 queued"覆盖。再跑完 -> D8 补 finished。
     */
    private static void resumeWriteback() throws Exception {
        section("12. D10 resume 回写：批次层跟着变、实验按聚合而非单 run");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        long batchId = orch.ensureBatches(EXP_RESUME).get(0).id();
        insertRunRow(RUN_RESUME, EXP_RESUME, "paused", "自测：现场扣留");
        insertAttempt(batchId, RUN_RESUME, 1, "paused", "自测：现场扣留");
        exec("UPDATE experiment SET status = 'paused', finished_at = NULL "
                + "WHERE tenant_id = ? AND id = ?", TENANT, EXP_RESUME);

        RunControl rc = new RunControl(db, TENANT);
        rc.resume(rc.info(RUN_RESUME), null, "自测：恢复");

        check("run 拉回 queued", "queued".equals(runStatus(RUN_RESUME)),
                runStatus(RUN_RESUME));
        check("attempt 行 queued", "queued".equals(attemptCol(RUN_RESUME, "status")),
                attemptCol(RUN_RESUME, "status"));
        check("非终态：attempt.finished_at 被清掉",
                attemptCol(RUN_RESUME, "finished_at") == null,
                attemptCol(RUN_RESUME, "finished_at"));
        check("批次跟着 queued", "queued".equals(batchStoredStatus(EXP_RESUME)),
                batchStoredStatus(EXP_RESUME));
        check("实验按 D6 聚合 running（单批 queued => running）",
                "running".equals(strOfExperiment(EXP_RESUME, "status")),
                strOfExperiment(EXP_RESUME, "status"));
        check("实验没有被单 run 的 queued 覆盖",
                !"queued".equals(strOfExperiment(EXP_RESUME, "status")),
                strOfExperiment(EXP_RESUME, "status"));

        // 恢复后该 run 实际跑完 -> D8 补齐到 finished
        setRunStatusAndError(RUN_RESUME, "completed", null);
        boolean afterFinish = orch.onRunStatusChanged(RUN_RESUME);
        check("跑完后回写返回 true", afterFinish, afterFinish);
        check("attempt completed", "completed".equals(attemptCol(RUN_RESUME, "status")),
                attemptCol(RUN_RESUME, "status"));
        check("批次 completed", "completed".equals(batchStoredStatus(EXP_RESUME)),
                batchStoredStatus(EXP_RESUME));
        check("实验 finished", "finished".equals(strOfExperiment(EXP_RESUME, "status")),
                strOfExperiment(EXP_RESUME, "status"));
    }

    // ================================================================ 13. D10 stop

    /** §10.4-2：停止非运行态的批次 run 后，attempt/批次 aborted、实验按 D6 聚合 failed。 */
    private static void stopWriteback() throws Exception {
        section("13. D10 stop 回写：attempt/批次 aborted、实验聚合 failed");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        long batchId = orch.ensureBatches(EXP_STOP).get(0).id();
        insertRunRow(RUN_STOP, EXP_STOP, "paused", null);
        insertAttempt(batchId, RUN_STOP, 1, "paused", null);
        exec("UPDATE experiment SET status = 'paused', finished_at = NULL "
                + "WHERE tenant_id = ? AND id = ?", TENANT, EXP_STOP);

        RunControl rc = new RunControl(db, TENANT);
        boolean changed = rc.stop(rc.info(RUN_STOP), null);
        check("非运行态：停止落终态", changed, changed);
        check("run aborted", "aborted".equals(runStatus(RUN_STOP)), runStatus(RUN_STOP));
        check("attempt 行 aborted", "aborted".equals(attemptCol(RUN_STOP, "status")),
                attemptCol(RUN_STOP, "status"));
        check("attempt 落 finished_at", attemptCol(RUN_STOP, "finished_at") != null,
                attemptCol(RUN_STOP, "finished_at"));
        check("attempt 带停止原因（error）", attemptCol(RUN_STOP, "error") != null,
                attemptCol(RUN_STOP, "error"));
        check("批次 aborted", "aborted".equals(batchStoredStatus(EXP_STOP)),
                batchStoredStatus(EXP_STOP));
        check("实验聚合 failed（D6：aborted 按失败口径）",
                "failed".equals(strOfExperiment(EXP_STOP, "status")),
                strOfExperiment(EXP_STOP, "status"));
    }

    // ================================================================ 14. D11 配置

    /** §10.4-4：batch_split 设置/清空；已有批次拒绝改值；非法值拒绝。 */
    private static void batchSplitConfig() throws Exception {
        section("14. D11 setBatchSplit：设置/清空/已有批次拒绝/非法值拒绝");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);

        check("初始 batch_split 为空", orch.batchSplit(EXP_SPLIT) == null,
                orch.batchSplit(EXP_SPLIT));
        Integer set = orch.setBatchSplit(EXP_SPLIT, 24);
        check("设置为 24", set != null && set == 24, set);
        check("库里 batch_split=24", Integer.valueOf(24).equals(
                intOfExperiment(EXP_SPLIT, "batch_split")),
                intOfExperiment(EXP_SPLIT, "batch_split"));
        check("读开关看到 24", Integer.valueOf(24).equals(orch.batchSplit(EXP_SPLIT)),
                orch.batchSplit(EXP_SPLIT));
        Integer cleared = orch.setBatchSplit(EXP_SPLIT, null);
        check("清空分批（null）", cleared == null, cleared);
        check("库里 batch_split 为 NULL",
                intOfExperiment(EXP_SPLIT, "batch_split") == null,
                intOfExperiment(EXP_SPLIT, "batch_split"));

        expectIllegal("batchSplit=0 被拒绝（0 只由接口层归一化成 null）",
                "batchSplit", () -> orch.setBatchSplit(EXP_SPLIT, 0));
        expectIllegal("batchSplit=-3 被拒绝", "batchSplit",
                () -> orch.setBatchSplit(EXP_SPLIT, -3));
        check("非法值被拒后没有改动",
                orch.batchSplit(EXP_SPLIT) == null, orch.batchSplit(EXP_SPLIT));

        // 已有批次行：快照已固化（D2），改值必须拒绝；同值重复设置放行
        long lockedBatchId = orch.ensureBatches(EXP_SPLIT_LOCKED).get(0).id();
        check("锁定实验先建 1 批", lockedBatchId != 0L, lockedBatchId);
        expectIllegal("已有批次时改值被拒（400 口径，提示清批次）",
                "已有批次", () -> orch.setBatchSplit(EXP_SPLIT_LOCKED, 2));
        check("被拒后库里仍是 1",
                Integer.valueOf(1).equals(intOfExperiment(EXP_SPLIT_LOCKED, "batch_split")),
                intOfExperiment(EXP_SPLIT_LOCKED, "batch_split"));
        Integer same = orch.setBatchSplit(EXP_SPLIT_LOCKED, 1);
        check("已有批次同值重复设置放行（前端重复保存不该报错）",
                same != null && same == 1, same);
        expectIllegal("已有批次时清空也被拒", "已有批次",
                () -> orch.setBatchSplit(EXP_SPLIT_LOCKED, null));
        check("被拒后批次行没多没少", countBatches(EXP_SPLIT_LOCKED) == 1,
                countBatches(EXP_SPLIT_LOCKED));
        expectIllegal("实验不存在 -> 参数错误", "不存在",
                () -> orch.setBatchSplit(-993099L, 2));
    }

    // ================================================================ 15. D12 并发

    /**
     * §10.4-5：同批并发重跑不撞 UNIQUE(batch_id, attempt_no)。
     *
     * <p>两个线程同时进执行体（CountDownLatch 卡住，保证两个 attempt 分配都发生在
     * 任一尝试完成之前）。若没有行锁串行化，两个线程会读到同一个 MAX(attempt_no)，
     * 其中一个 INSERT 抛唯一键冲突；这里断言两个都成功、attempt_no 恰好 1,2。
     */
    private static void concurrentRerunNoUniqueViolation() throws Exception {
        section("15. D12 同批并发重跑：行锁串行化 attempt_no（§10.4-5）");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        orch.ensureBatches(EXP_CONC);
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
        BatchOrchestrator.BatchRunner blocking = (experimentId, vars, runId, sync) -> {
            both.countDown();
            try {
                if (!both.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发用例：两个线程没同时进入执行体");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发用例：等待被中断", ie);
            }
            setRunStatus(runId, "completed");
            return new ExperimentExecutor.Result(runId, "completed", 1, 1, null);
        };
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = () -> {
            try {
                new BatchOrchestrator(db, TENANT, blocking).runBatch(EXP_CONC, 1, null);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        };
        Thread t1 = new Thread(task, "self-s31-conc-a");
        Thread t2 = new Thread(task, "self-s31-conc-b");
        t1.start();
        t2.start();
        t1.join(30000);
        t2.join(30000);
        check("两个并发重跑都成功（没有唯一键冲突）", failure.get() == null,
                failure.get() == null ? "ok" : failure.get().toString());
        check("两个线程都按期结束", !t1.isAlive() && !t2.isAlive(),
                t1.isAlive() + "/" + t2.isAlive());
        List<Integer> attempts = attemptNos(EXP_CONC);
        check("attempt_no 恰好是 1,2（无重复）", List.of(1, 2).equals(attempts), attempts);
        check("两条尝试历史都保留", attempts.size() == 2, attempts.size());
        check("批次最终 completed", "completed".equals(batchStoredStatus(EXP_CONC)),
                batchStoredStatus(EXP_CONC));
        check("实验最终 finished（两次尝试都 completed）",
                "finished".equals(strOfExperiment(EXP_CONC, "status")),
                strOfExperiment(EXP_CONC, "status"));
    }

    // ================================================================ 16. 异步分批

    /**
     * 异步分批的"下一批创建顺序"：batch1 attempt1 -> 跑完 -> batch2 attempt1
     * -> 失败继续 batch3 attempt1 -> aborted 不再续。
     *
     * <p>续队决策直接调 {@link ExperimentScheduler#planNextBatch}（与生产
     * executeOne 的续队走同一条方法），只有"入内存队列 / 打印"那步不同；
     * 用合成负数 id + 直接改 run 状态模拟假执行体跑完，不碰设备。
     */
    private static void asyncNextBatchSequence() throws Exception {
        section("16. 异步分批：下一批创建顺序 + 失败继续 + D5 批变量反查");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);

        BatchOrchestrator.NextBatchAttempt b1 = orch.createNextBatchAttempt(EXP_ASYNC, 0);
        check("初始提交建出第 1 批（CREATED）",
                b1.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED, b1.outcome());
        check("第 1 批 batchNo=1", b1.batchNo() == 1, b1.batchNo());
        check("第 1 批 attemptNo=1", b1.attemptNo() == 1, b1.attemptNo());
        check("run 建为 queued", "queued".equals(runStatus(b1.runId())), runStatus(b1.runId()));
        check("批次标 queued", "queued".equals(batchStatusNo(EXP_ASYNC, 1)),
                batchStatusNo(EXP_ASYNC, 1));
        check("实验按 D6 聚合 running",
                "running".equals(strOfExperiment(EXP_ASYNC, "status")),
                strOfExperiment(EXP_ASYNC, "status"));

        // 重复 async 提交：同一实验同时只允许一条在队列/执行的批次 run
        BatchOrchestrator.NextBatchAttempt dup = orch.createNextBatchAttempt(EXP_ASYNC, 0);
        check("重复提交返回 EXISTING（不重复建）",
                dup.outcome() == BatchOrchestrator.NextBatchOutcome.EXISTING
                        && dup.runId() == b1.runId(), dup.outcome() + " run#" + dup.runId());
        check("重复提交没有多建 run", countRuns(EXP_ASYNC) == 1, countRuns(EXP_ASYNC));

        // D5：调度器执行批次 run 前按批次快照反查 batchNo/batchSamples
        BatchOrchestrator.BatchContext ctx = orch.batchContextFor(b1.runId());
        check("批上下文反查 batchNo=1", ctx != null && ctx.batchNo() == 1, ctx);
        check("批上下文反查 batchSamples=快照条码",
                ctx != null && "BC-ASYNC-51".equals(ctx.barcodesCsv()),
                ctx == null ? null : ctx.barcodesCsv());

        // 第 1 批跑完（模拟执行体 settle + 调度器 D8 回写）
        finishRun(b1.runId(), "completed", null);
        check("第 1 批 completed", "completed".equals(batchStatusNo(EXP_ASYNC, 1)),
                batchStatusNo(EXP_ASYNC, 1));
        check("D8 把 run.started_at 补进 attempt（异步 attempt 建单时 start 为空）",
                attemptCol(b1.runId(), "started_at") != null,
                attemptCol(b1.runId(), "started_at"));

        BatchOrchestrator.NextBatchAttempt b2 = scheduler.planNextBatch(EXP_ASYNC, 1, b1.runId());
        check("续队建出第 2 批（batch1 完成后）",
                b2 != null && b2.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED
                        && b2.batchNo() == 2, b2);
        check("第 2 批 attemptNo 重新从 1 开始", b2.attemptNo() == 1, b2.attemptNo());
        check("第 2 批 run queued", "queued".equals(runStatus(b2.runId())),
                runStatus(b2.runId()));
        check("第 2 批本身标 queued", "queued".equals(batchStatusNo(EXP_ASYNC, 2)),
                batchStatusNo(EXP_ASYNC, 2));

        // 第 2 批失败：D3 失败继续下一批（不原地重跑失败批）
        finishRun(b2.runId(), "failed", "自测：批失败");
        BatchOrchestrator.NextBatchAttempt b3 = scheduler.planNextBatch(EXP_ASYNC, 2, b2.runId());
        check("失败继续下一批（D3）",
                b3 != null && b3.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED
                        && b3.batchNo() == 3, b3);
        check("失败批没有被原地重试（第 2 批仍只有 1 次尝试）",
                batchAttemptCount(EXP_ASYNC, 2) == 1, batchAttemptCount(EXP_ASYNC, 2));

        // 第 3 批 aborted：人工停止，不再续
        finishRun(b3.runId(), "aborted", "自测：人工停止");
        BatchOrchestrator.NextBatchAttempt afterAbort =
                scheduler.planNextBatch(EXP_ASYNC, 3, b3.runId());
        check("aborted 不续下一批（planNextBatch 返回 null）", afterAbort == null, afterAbort);
        check("三批各 1 次尝试（attempt_no 全为 1）",
                attemptNos(EXP_ASYNC).equals(List.of(1, 1, 1)), attemptNos(EXP_ASYNC));
        check("总共只有 3 条 run", countRuns(EXP_ASYNC) == 3, countRuns(EXP_ASYNC));
        check("实验聚合 failed（completed+failed+aborted）",
                "failed".equals(strOfExperiment(EXP_ASYNC, "status")),
                strOfExperiment(EXP_ASYNC, "status"));
    }

    /** aborted（人工停止）后不再续队，剩余批保持 pending、不产生新 run/attempt。 */
    private static void abortedStopsContinuation() throws Exception {
        section("17. 异步分批：aborted 停止后不续队，剩余批保持 pending");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        BatchOrchestrator.NextBatchAttempt b1 = orch.createNextBatchAttempt(EXP_ASYNC_STOP, 0);
        check("停用实验建出第 1 批", b1.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED,
                b1.outcome());

        finishRun(b1.runId(), "aborted", "自测：人工停止");
        BatchOrchestrator.NextBatchAttempt afterStop =
                scheduler.planNextBatch(EXP_ASYNC_STOP, 1, b1.runId());
        check("planNextBatch 返回 null（不续）", afterStop == null, afterStop);
        check("第 2 批仍 pending", "pending".equals(batchStatusNo(EXP_ASYNC_STOP, 2)),
                batchStatusNo(EXP_ASYNC_STOP, 2));
        check("第 2 批没有任何尝试", batchAttemptCount(EXP_ASYNC_STOP, 2) == 0,
                batchAttemptCount(EXP_ASYNC_STOP, 2));
        check("总 run 数仍为 1（没建下一批）", countRuns(EXP_ASYNC_STOP) == 1,
                countRuns(EXP_ASYNC_STOP));
        check("实验聚合 failed（aborted 按失败口径，D6）",
                "failed".equals(strOfExperiment(EXP_ASYNC_STOP, "status")),
                strOfExperiment(EXP_ASYNC_STOP, "status"));
    }

    /** 批边界扣留：不建下一批、不产生 attempt；解除后重新发起可从该批继续。 */
    private static void holdStopsNextBatch() throws Exception {
        section("18. 异步分批：批边界扣留 -> 不建下一批且不产生 attempt");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        RuntimeHold holds = new RuntimeHold(db, TENANT);

        BatchOrchestrator.NextBatchAttempt b1 = orch.createNextBatchAttempt(EXP_ASYNC_HOLD, 0);
        finishRun(b1.runId(), "completed", null);
        int runsBefore = countRuns(EXP_ASYNC_HOLD);

        holds.raise(RuntimeHold.Scope.EXPERIMENT, EXP_ASYNC_HOLD,
                RuntimeHold.Kind.MANUAL, "自测：现场扣留", null);
        BatchOrchestrator.NextBatchAttempt held =
                scheduler.planNextBatch(EXP_ASYNC_HOLD, 1, b1.runId());
        check("扣留时返回 HELD 信号",
                held != null && held.outcome() == BatchOrchestrator.NextBatchOutcome.HELD,
                held == null ? null : held.outcome());
        check("HELD 带现场扣留原因", held != null && held.holdReason() != null
                        && held.holdReason().contains("扣留"),
                held == null ? null : held.holdReason());
        check("候选第 2 批标 paused（批边界停）",
                "paused".equals(batchStatusNo(EXP_ASYNC_HOLD, 2)),
                batchStatusNo(EXP_ASYNC_HOLD, 2));
        check("第 2 批没有产生 attempt", batchAttemptCount(EXP_ASYNC_HOLD, 2) == 0,
                batchAttemptCount(EXP_ASYNC_HOLD, 2));
        check("没有新建 run", countRuns(EXP_ASYNC_HOLD) == runsBefore,
                countRuns(EXP_ASYNC_HOLD));
        check("实验聚合 paused",
                "paused".equals(strOfExperiment(EXP_ASYNC_HOLD, "status")),
                strOfExperiment(EXP_ASYNC_HOLD, "status"));
        BatchOrchestrator.NextBatchAttempt heldAgain =
                orch.createNextBatchAttempt(EXP_ASYNC_HOLD, 0);
        check("扣留中初始 async 提交也停在边界（HELD）",
                heldAgain.outcome() == BatchOrchestrator.NextBatchOutcome.HELD,
                heldAgain.outcome());

        holds.clear(RuntimeHold.Scope.EXPERIMENT, EXP_ASYNC_HOLD, null, "自测：解除扣留");
        BatchOrchestrator.NextBatchAttempt resumed =
                orch.createNextBatchAttempt(EXP_ASYNC_HOLD, 0);
        check("解除扣留后重新发起从第 2 批继续（CREATED）",
                resumed.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED
                        && resumed.batchNo() == 2, resumed);
        check("继续后第 2 批仍从 attemptNo=1 开始", resumed.attemptNo() == 1,
                resumed.attemptNo());
        check("继续后实验聚合 running",
                "running".equals(strOfExperiment(EXP_ASYNC_HOLD, "status")),
                strOfExperiment(EXP_ASYNC_HOLD, "status"));
    }

    /** 边界：未分批 -> NOT_BATCHED；全完成 -> NO_MORE；paused run 不续队。 */
    private static void asyncEdgeCases() throws Exception {
        section("19. 异步分批边界：NOT_BATCHED / NO_MORE / paused 不续");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);

        BatchOrchestrator.NextBatchAttempt notBatched =
                orch.createNextBatchAttempt(EXP_UNBATCHED, 0);
        check("未配置分批 -> NOT_BATCHED（走非分批路径）",
                notBatched.outcome() == BatchOrchestrator.NextBatchOutcome.NOT_BATCHED,
                notBatched.outcome());

        // 全部批次完成 -> NO_MORE（初始提交与续队两个入口）
        BatchOrchestrator.NextBatchAttempt d1 = orch.createNextBatchAttempt(EXP_ASYNC_DONE, 0);
        finishRun(d1.runId(), "completed", null);
        BatchOrchestrator.NextBatchAttempt d2 =
                scheduler.planNextBatch(EXP_ASYNC_DONE, 1, d1.runId());
        check("两批实验第 2 批被续出来",
                d2 != null && d2.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED
                        && d2.batchNo() == 2, d2);
        finishRun(d2.runId(), "completed", null);
        BatchOrchestrator.NextBatchAttempt noMore =
                orch.createNextBatchAttempt(EXP_ASYNC_DONE, 0);
        check("全部批次完成后初始提交 -> NO_MORE",
                noMore.outcome() == BatchOrchestrator.NextBatchOutcome.NO_MORE,
                noMore.outcome());
        check("NO_MORE 带实验聚合状态 finished", "finished".equals(noMore.status()),
                noMore.status());
        check("NO_MORE 不建 run", countRuns(EXP_ASYNC_DONE) == 2,
                countRuns(EXP_ASYNC_DONE));
        BatchOrchestrator.NextBatchAttempt tail =
                scheduler.planNextBatch(EXP_ASYNC_DONE, 2, d2.runId());
        check("最后一批跑完后续队 -> NO_MORE",
                tail != null && tail.outcome() == BatchOrchestrator.NextBatchOutcome.NO_MORE,
                tail);

        // paused run（现场扣留/暂停）不续：等人工恢复当前 run 再跑完才续
        BatchOrchestrator.NextBatchAttempt s2 = orch.createNextBatchAttempt(EXP_ASYNC_STOP, 0);
        check("停止实验第 2 批被建出来",
                s2.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED, s2.outcome());
        finishRun(s2.runId(), "paused", "自测：现场扣留");
        BatchOrchestrator.NextBatchAttempt afterPause =
                scheduler.planNextBatch(EXP_ASYNC_STOP, 2, s2.runId());
        check("paused run 不续下一批（planNextBatch 返回 null）",
                afterPause == null, afterPause);
        check("paused 后实验聚合 paused",
                "paused".equals(strOfExperiment(EXP_ASYNC_STOP, "status")),
                strOfExperiment(EXP_ASYNC_STOP, "status"));
    }

    /** 模拟执行体 settle + 调度器 D8 回写：改 run 终态（终态顺手落 started_at/finished_at）。 */
    private static void finishRun(long runId, String status, String error) throws Exception {
        exec("UPDATE experiment_run SET started_at = COALESCE(started_at, now()) "
                + "WHERE tenant_id = ? AND id = ?", TENANT, runId);
        setRunStatusAndError(runId, status, error);
        new BatchOrchestrator(db, TENANT).onRunStatusChanged(runId);
    }

    /** 某实验第 n 批的表内存储状态（自愈前原值）。 */
    private static String batchStatusNo(long experimentId, int batchNo) throws SQLException {
        return str("SELECT status FROM experiment_batch WHERE tenant_id = ? "
                        + "AND experiment_id = ? AND batch_no = ?",
                TENANT, experimentId, batchNo);
    }

    /** 某实验第 n 批的尝试条数。 */
    private static int batchAttemptCount(long experimentId, int batchNo) throws SQLException {
        return count("SELECT count(*) FROM experiment_batch_run br "
                + "JOIN experiment_batch b ON b.tenant_id = br.tenant_id AND b.id = br.batch_id "
                + "WHERE br.tenant_id = ? AND b.experiment_id = ? AND b.batch_no = ?",
                TENANT, experimentId, batchNo);
    }

    // ================================================================ 20. paused 派生状态（S3.2-D）

    /**
     * §11.3-5：paused 批派生状态口径。
     *
     * <p>场景：某批有一次 failed 尝试，随后批停在边界被标 paused（等人工处置）。
     * 读侧必须显示 paused（"等处置"），而不是 failed；有活动（run running/queued）
     * 时显示活动态；任一尝试 completed 时显示 completed。D6 聚合跟随派生状态。
     */
    private static void pausedBatchDerivedStatus() throws Exception {
        section("20. paused 批派生状态口径（S3.2-D，§11.3-5）");
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        List<BatchOrchestrator.BatchRow> rows = orch.ensureBatches(EXP_DERIVED);
        check("paused 派生实验建出 2 批", rows.size() == 2, rows.size());

        // 现场 1：第 1 批有一次 failed 尝试，随后停在边界标 paused（等人工处置）
        insertRunRow(RUN_DERIVED, EXP_DERIVED, "failed", "自测：批失败");
        insertAttempt(rows.get(0).id(), RUN_DERIVED, 1, "failed", "自测：批失败");
        setBatchStatusOf(EXP_DERIVED, 1, "paused");

        List<BatchOrchestrator.BatchRow> derived = orch.ensureBatches(EXP_DERIVED);
        check("failed 尝试 + 表里 paused -> 派生 paused",
                "paused".equals(derived.get(0).status()), derived.get(0).status());
        check("表里原值仍是 paused（读侧不改库）",
                "paused".equals(derived.get(0).storedStatus()),
                derived.get(0).storedStatus());
        String json = orch.batchesJson(EXP_DERIVED);
        check("GET batches 第 1 批 status=paused", json.contains("\"status\":\"paused\""),
                json.contains("\"status\":\"paused\""));
        check("summary 把 paused 归 pending（不是 failed）",
                json.contains("\"pending\":2") && json.contains("\"failed\":0"),
                json);
        check("D6 聚合 paused（不再是 failed）",
                "paused".equals(orch.syncExperimentStatusFromBatches(EXP_DERIVED)),
                strOfExperiment(EXP_DERIVED, "status"));
        check("实验表 paused", "paused".equals(strOfExperiment(EXP_DERIVED, "status")),
                strOfExperiment(EXP_DERIVED, "status"));

        // 现场 2：批内恢复（run 变 running）-> 派生 running（活动态优先于表里 paused）
        setRunStatusAndError(RUN_DERIVED, "running", null);
        List<BatchOrchestrator.BatchRow> running = orch.ensureBatches(EXP_DERIVED);
        check("run 恢复 running -> 派生 running（即使表里仍 paused）",
                "running".equals(running.get(0).status()), running.get(0).status());
        check("GET batches 派生 running",
                orch.batchesJson(EXP_DERIVED).contains("\"status\":\"running\""),
                orch.batchesJson(EXP_DERIVED).contains("\"status\":\"running\""));
        check("D6 聚合 running",
                "running".equals(orch.syncExperimentStatusFromBatches(EXP_DERIVED)),
                strOfExperiment(EXP_DERIVED, "status"));

        // 现场 3：跑完 completed -> 派生 completed（任一次尝试完成即整批完成）
        setRunStatusAndError(RUN_DERIVED, "completed", null);
        new BatchOrchestrator(db, TENANT).onRunStatusChanged(RUN_DERIVED);
        List<BatchOrchestrator.BatchRow> completed = orch.ensureBatches(EXP_DERIVED);
        check("attempt completed -> 派生 completed",
                "completed".equals(completed.get(0).status()), completed.get(0).status());
        check("表里自愈为 completed",
                "completed".equals(completed.get(0).storedStatus()),
                completed.get(0).storedStatus());
        check("GET batches 派生 completed",
                orch.batchesJson(EXP_DERIVED).contains("\"status\":\"completed\""),
                orch.batchesJson(EXP_DERIVED).contains("\"status\":\"completed\""));
    }

    // ================================================================ 21. 扣留解除自动续队（S3.2-B）

    /**
     * §11.3-1：扣留解除后自动续队。
     *
     * <p>候选口径：paused 且<b>没有任何尝试</b>、实验不在终态；有覆盖扣留时一律不续；
     * 全部扣留解除后扫描 -> createNextBatchAttempt + resumeRun 入内存队列。
     * 不启动调度循环、不真跑设备（续出的 run 只停在内存队列里）。
     */
    private static void autoContinueHeldBatchesOnRelease() throws Exception {
        section("21. 扣留解除自动续队（S3.2-B，§11.3-1）");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
        RuntimeHold holds = new RuntimeHold(db, TENANT);

        List<BatchOrchestrator.BatchRow> acRows = orch.ensureBatches(EXP_AC);
        check("自动续队候选实验 2 批", acRows.size() == 2, acRows.size());
        orch.ensureBatches(EXP_AC_ATTEMPT);
        orch.ensureBatches(EXP_AC_ABORTED);
        orch.ensureBatches(EXP_AC_HOLD);

        // 候选口径造数：
        //  - EXP_AC 第 1 批 paused 且无尝试 -> 应枚举/续队
        //  - EXP_AC_ATTEMPT 第 1 批 paused 但有 failed 尝试 -> 不枚举（人工处置，不自动重跑）
        //  - EXP_AC_ABORTED 第 1 批 paused 但实验 aborted -> 不枚举
        //  - EXP_AC_HOLD 第 1 批 paused 无尝试 + 实验级扣留 -> 有扣留时一律不续
        setBatchStatusOf(EXP_AC, 1, "paused");
        long attemptBatchId = batchIdOf(EXP_AC_ATTEMPT, 1);
        insertRunRow(RUN_AC_ATTEMPT, EXP_AC_ATTEMPT, "failed", "自测：批失败");
        insertAttempt(attemptBatchId, RUN_AC_ATTEMPT, 1, "failed", "自测：批失败");
        setBatchStatusOf(EXP_AC_ATTEMPT, 1, "paused");
        setBatchStatusOf(EXP_AC_ABORTED, 1, "paused");
        exec("UPDATE experiment SET status = 'aborted' WHERE tenant_id = ? AND id = ?",
                TENANT, EXP_AC_ABORTED);
        setBatchStatusOf(EXP_AC_HOLD, 1, "paused");
        holds.raise(RuntimeHold.Scope.EXPERIMENT, EXP_AC_HOLD,
                RuntimeHold.Kind.MANUAL, "自测：现场扣留", null);

        List<BatchOrchestrator.PausedBatch> candidates = orch.pausedBatchCandidates();
        check("paused 且无 attempt 的批被枚举", hasCandidate(candidates, EXP_AC, 1), candidates);
        check("有 attempt 的 paused 批不枚举",
                !hasCandidate(candidates, EXP_AC_ATTEMPT, 1), candidates);
        check("实验已 aborted 不枚举",
                !hasCandidate(candidates, EXP_AC_ABORTED, 1), candidates);
        check("候选按 batch_no 升序", ascendingBatchNo(candidates), candidates);

        // P-03：逐候选评估（ADR-FAULT-SCOPE-001）——被覆盖实验跳过，无关实验照常续队。
        int firstWave = scheduler.autoContinueHeldBatches();
        check("有覆盖扣留时只跳过被覆盖实验（P-03 逐候选，本次建 " + firstWave + " 条）",
                firstWave >= 1, firstWave);
        check("扣留期间 EXP_AC_HOLD 没有新 run", countRuns(EXP_AC_HOLD) == 0,
                countRuns(EXP_AC_HOLD));
        check("无关实验 EXP_AC 正常续队（不再全局闸门）", countRuns(EXP_AC) == 1,
                countRuns(EXP_AC));

        // 解除扣留 -> 补被覆盖实验的下一批 attempt 并 resumeRun 入队列
        holds.clear(RuntimeHold.Scope.EXPERIMENT, EXP_AC_HOLD, null, "自测：解除扣留");
        int created = scheduler.autoContinueHeldBatches();
        check("解除扣留后补被覆盖实验的下一批（本次 " + created + " 条）", created >= 1, created);
        check("EXP_AC_HOLD 建出 queued run", countRuns(EXP_AC_HOLD) == 1,
                countRuns(EXP_AC_HOLD));
        check("EXP_AC_HOLD 第 1 批有 1 次 attempt",
                batchAttemptCount(EXP_AC_HOLD, 1) == 1, batchAttemptCount(EXP_AC_HOLD, 1));
        check("EXP_AC_HOLD 第 1 批标 queued",
                "queued".equals(batchStatusNo(EXP_AC_HOLD, 1)),
                batchStatusNo(EXP_AC_HOLD, 1));
        check("EXP_AC 第 1 批建出 attempt", batchAttemptCount(EXP_AC, 1) == 1,
                batchAttemptCount(EXP_AC, 1));
        check("aborted 实验仍不建 run", countRuns(EXP_AC_ABORTED) == 0,
                countRuns(EXP_AC_ABORTED));
        long heldRunId = runIdOf(EXP_AC_HOLD);
        check("续出的 run 已进调度器内存队列", queueContains(scheduler, heldRunId),
                queueRunIds(scheduler));
        check("再次扫描不重复续队（候选已变 queued）",
                scheduler.autoContinueHeldBatches() == 0, "created != 0");
    }

    // ================================================================ 22. async vars（S3.2-C）

    /**
     * §11.3-4：async 请求体的 vars overrides 持久化。
     *
     * <p>非分批 {@code scheduler.submit(exp, vars)} 与分批
     * {@code createNextBatchAttempt(exp, 0, vars)} 都必须把变量写进
     * {@code experiment_run.variables}；续下一批时从刚结束 run 继承同一份用户变量
     * （用 {@link ExperimentScheduler#planNextBatch} 验证，剥掉调度器注入的批上下文）。
     */
    private static void asyncVarsPersistence() throws Exception {
        section("22. async 变量 overrides 持久化 + 续队继承（S3.2-C，§11.3-4）");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);

        long runId = scheduler.submit(EXP_VARS, Map.of("k", "v", "dose", "12"));
        check("submit(exp,vars) 返回 runId", runId > 0, runId);
        check("submit(exp,vars) 落 queued run", "queued".equals(runStatus(runId)),
                runStatus(runId));
        check("submit(exp,vars) 写 variables jsonb",
                Map.of("k", "v", "dose", "12").equals(orch.runVariables(runId)),
                orch.runVariables(runId));
        long emptyRun = scheduler.submit(EXP_VARS);
        check("submit(exp) 委托空 map -> variables 空对象",
                orch.runVariables(emptyRun).isEmpty(), orch.runVariables(emptyRun));
        check("空变量落库为 {}（列 NOT NULL）", "{}".equals(varsJsonOf(emptyRun)),
                varsJsonOf(emptyRun));

        BatchOrchestrator.NextBatchAttempt b1 =
                orch.createNextBatchAttempt(EXP_VARS_BATCH, 0, Map.of("k", "v"));
        check("createNextBatchAttempt(...,vars) 建出第 1 批",
                b1.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED
                        && b1.batchNo() == 1, b1);
        check("批次 run 的 variables 落库",
                Map.of("k", "v").equals(orch.runVariables(b1.runId())),
                orch.runVariables(b1.runId()));

        // 模拟执行器跑完 + updateRun 把"合并变量（默认值+用户+批上下文）"写回 run
        finishRun(b1.runId(), "completed", null);
        setRunVariables(b1.runId(),
                "{\"k\":\"v\",\"batchNo\":\"1\",\"batchSamples\":\"BC-ASYNC-66\"}");
        check("执行器合并后 variables 含批上下文",
                "1".equals(orch.runVariables(b1.runId()).get("batchNo")),
                orch.runVariables(b1.runId()));

        BatchOrchestrator.NextBatchAttempt b2 =
                scheduler.planNextBatch(EXP_VARS_BATCH, 1, b1.runId());
        check("续队建出第 2 批",
                b2 != null && b2.outcome() == BatchOrchestrator.NextBatchOutcome.CREATED
                        && b2.batchNo() == 2, b2);
        Map<String, String> b2vars = b2 == null ? Map.of() : orch.runVariables(b2.runId());
        check("续队沿用同一份用户变量", "v".equals(b2vars.get("k")), b2vars);
        check("续队剥掉上一批 batchNo（不串批）", !b2vars.containsKey("batchNo"), b2vars);
        check("续队剥掉上一批 batchSamples", !b2vars.containsKey("batchSamples"), b2vars);
        check("第 2 批 run queued",
                b2 != null && "queued".equals(runStatus(b2.runId())),
                b2 == null ? null : runStatus(b2.runId()));
    }

    // ================================================================ 假执行体

    /**
     * 假执行体：按脚本返回状态并落 experiment_run 行，验证编排语义但不碰设备。
     *
     * <p>真的 {@link ExperimentExecutor} 会把 run 落到同一状态；
     * 这里必须同样落库，因为编排层读批次状态时<b>以 run 状态为准</b>。
     */
    private static final class FakeRunner implements BatchOrchestrator.BatchRunner {

        private final ArrayDeque<String> script = new ArrayDeque<>();
        private final List<Map<String, String>> calls = new ArrayList<>();
        private final List<Long> runIds = new ArrayList<>();
        private final List<Boolean> syncFlags = new ArrayList<>();

        FakeRunner statuses(String... sts) {
            script.addAll(Arrays.asList(sts));
            return this;
        }

        @Override
        public ExperimentExecutor.Result run(long experimentId, Map<String, String> vars,
                                             long existingRunId, boolean syncExperimentStatus)
                throws SQLException {
            String st = script.isEmpty() ? "completed" : script.poll();
            calls.add(vars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(vars));
            runIds.add(existingRunId);
            syncFlags.add(syncExperimentStatus);
            setRunStatus(existingRunId, st);
            int total = 2;
            int done = "completed".equals(st) ? 2 : 0;
            String err = switch (st) {
                case "failed" -> "自测：批失败";
                case "paused" -> "自测：现场扣留";
                case "aborted" -> "自测：人工停止";
                default -> null;
            };
            return new ExperimentExecutor.Result(existingRunId, st, total, done, err);
        }
    }

    // ================================================================ 造数

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            insertExperiment(c, EXP_BATCHED, "SELF-S3-BATCHED", 2);
            insertExperiment(c, EXP_SNAPSHOT, "SELF-S3-SNAPSHOT", 3);
            insertExperiment(c, EXP_UNBATCHED, "SELF-S3-UNBATCHED", null);
            insertExperiment(c, EXP_ZERO, "SELF-S3-ZERO", 0);
            insertExperiment(c, EXP_AGG, "SELF-S3-AGG", 1);
            insertExperiment(c, EXP_NOSAMPLE, "SELF-S3-NOSAMPLE", 3);
            insertExperiment(c, EXP_HOLD, "SELF-S3-HOLD", 1);
            insertExperiment(c, EXP_WRITEBACK, "SELF-S31-WB", 1);
            insertExperiment(c, EXP_RESUME, "SELF-S31-RESUME", 1);
            insertExperiment(c, EXP_STOP, "SELF-S31-STOP", 1);
            insertExperiment(c, EXP_SPLIT, "SELF-S31-SPLIT", null);
            insertExperiment(c, EXP_SPLIT_LOCKED, "SELF-S31-LOCKED", 1);
            insertExperiment(c, EXP_CONC, "SELF-S31-CONC", 1);
            insertExperiment(c, EXP_ASYNC, "SELF-ASYNC", 1);
            insertExperiment(c, EXP_ASYNC_STOP, "SELF-ASYNC-STOP", 1);
            insertExperiment(c, EXP_ASYNC_HOLD, "SELF-ASYNC-HOLD", 1);
            insertExperiment(c, EXP_ASYNC_DONE, "SELF-ASYNC-DONE", 1);
            insertExperiment(c, EXP_AC, "SELF-S32-AC", 1);
            insertExperiment(c, EXP_AC_ATTEMPT, "SELF-S32-AC-ATT", 1);
            insertExperiment(c, EXP_AC_ABORTED, "SELF-S32-AC-ABORTED", 1);
            insertExperiment(c, EXP_AC_HOLD, "SELF-S32-AC-HOLD", 1);
            insertExperiment(c, EXP_VARS, "SELF-S32-VARS", null);
            insertExperiment(c, EXP_VARS_BATCH, "SELF-S32-VARS-BATCH", 1);
            insertExperiment(c, EXP_DERIVED, "SELF-S32-DERIVED", 2);

            long[] batchedSamples = {
                    SAMPLE_BASE + 1, SAMPLE_BASE + 2, SAMPLE_BASE + 3,
                    SAMPLE_BASE + 4, SAMPLE_BASE + 5};
            for (int i = 0; i < batchedSamples.length; i++) {
                insertSample(c, batchedSamples[i], String.format("BC-S3-%02d", i + 1));
                linkSample(c, EXP_BATCHED, batchedSamples[i]);
            }
            for (int i = 11; i <= 14; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-S3-SNAP-" + i);
                linkSample(c, EXP_SNAPSHOT, SAMPLE_BASE + i);
            }
            for (int i = 21; i <= 23; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-S3-AGG-" + i);
                linkSample(c, EXP_AGG, SAMPLE_BASE + i);
            }
            for (int i = 31; i <= 32; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-S3-HOLD-" + i);
                linkSample(c, EXP_HOLD, SAMPLE_BASE + i);
            }
            // S3.1：每个单批实验 1 个样品（batch_split=1 -> 恰好 1 批）
            insertSample(c, SAMPLE_BASE + 41, "BC-S31-WB");
            linkSample(c, EXP_WRITEBACK, SAMPLE_BASE + 41);
            insertSample(c, SAMPLE_BASE + 42, "BC-S31-RESUME");
            linkSample(c, EXP_RESUME, SAMPLE_BASE + 42);
            insertSample(c, SAMPLE_BASE + 43, "BC-S31-STOP");
            linkSample(c, EXP_STOP, SAMPLE_BASE + 43);
            insertSample(c, SAMPLE_BASE + 44, "BC-S31-LOCKED");
            linkSample(c, EXP_SPLIT_LOCKED, SAMPLE_BASE + 44);
            insertSample(c, SAMPLE_BASE + 45, "BC-S31-CONC");
            linkSample(c, EXP_CONC, SAMPLE_BASE + 45);
            // 异步分批：EXP_ASYNC 3 个样品 -> 3 批；STOP/HOLD 各 2 个 -> 2 批
            for (int i = 51; i <= 53; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-ASYNC-" + i);
                linkSample(c, EXP_ASYNC, SAMPLE_BASE + i);
            }
            for (int i = 54; i <= 55; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-ASYNC-" + i);
                linkSample(c, EXP_ASYNC_STOP, SAMPLE_BASE + i);
            }
            for (int i = 56; i <= 57; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-ASYNC-" + i);
                linkSample(c, EXP_ASYNC_HOLD, SAMPLE_BASE + i);
            }
            for (int i = 58; i <= 59; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-ASYNC-" + i);
                linkSample(c, EXP_ASYNC_DONE, SAMPLE_BASE + i);
            }
            // S3.2 残余：自动续队 / 派生状态 / async vars 用的样品
            for (int i = 61; i <= 62; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-S32-" + i);
                linkSample(c, EXP_AC, SAMPLE_BASE + i);
            }
            insertSample(c, SAMPLE_BASE + 63, "BC-S32-63");
            linkSample(c, EXP_AC_ATTEMPT, SAMPLE_BASE + 63);
            insertSample(c, SAMPLE_BASE + 64, "BC-S32-64");
            linkSample(c, EXP_AC_ABORTED, SAMPLE_BASE + 64);
            insertSample(c, SAMPLE_BASE + 65, "BC-S32-65");
            linkSample(c, EXP_AC_HOLD, SAMPLE_BASE + 65);
            for (int i = 66; i <= 67; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-S32-" + i);
                linkSample(c, EXP_VARS_BATCH, SAMPLE_BASE + i);
            }
            for (int i = 68; i <= 70; i++) {
                insertSample(c, SAMPLE_BASE + i, "BC-S32-" + i);
                linkSample(c, EXP_DERIVED, SAMPLE_BASE + i);
            }
        }
    }

    private static void insertExperiment(Connection c, long id, String no, Integer batchSplit)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment (id, tenant_id, experiment_no, name, status, batch_split)
                VALUES (?, ?, ?, ?, 'created', ?)
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setString(3, no);
            ps.setString(4, no);
            if (batchSplit == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, batchSplit);
            }
            ps.executeUpdate();
        }
    }

    private static void insertSample(Connection c, long id, String barcode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sample (id, tenant_id, barcode, name, sample_type)
                VALUES (?, ?, ?, ?, 'serum')
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setString(3, barcode);
            ps.setString(4, "自测S3-" + barcode);
            ps.executeUpdate();
        }
    }

    private static void linkSample(Connection c, long experimentId, long sampleId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment_sample (experiment_id, sample_id, sample_role)
                VALUES (?, ?, 'test')
                """)) {
            ps.setLong(1, experimentId);
            ps.setLong(2, sampleId);
            ps.executeUpdate();
        }
    }

    /** 建批之后再给实验加一个样品（验证快照不漂移）。 */
    private static void addSampleToExperiment(long experimentId, long sampleId, String barcode)
            throws SQLException {
        try (Connection c = db.open()) {
            insertSample(c, sampleId, barcode);
            linkSample(c, experimentId, sampleId);
        }
    }

    // ================================================================ 查询 / 改数

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.open()) {
            exec(c, sql, args);
        }
    }

    /** 在给定连接上执行（清理走单事务，避免跨连接外键互等）。 */
    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void setRunStatus(long runId, String status) throws SQLException {
        exec("UPDATE experiment_run SET status = ?, finished_at = now() "
                + "WHERE tenant_id = ? AND id = ?", status, TENANT, runId);
    }

    private static String runStatus(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM experiment_run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int countRuns(long experimentId) throws SQLException {
        return count("SELECT count(*) FROM experiment_run "
                + "WHERE tenant_id = ? AND experiment_id = ?", TENANT, experimentId);
    }

    private static int countBatches(long experimentId) throws SQLException {
        return count("SELECT count(*) FROM experiment_batch "
                + "WHERE tenant_id = ? AND experiment_id = ?", TENANT, experimentId);
    }

    private static int count(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String strOfExperiment(long experimentId, String column) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT " + column
                     + " FROM experiment WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void setBatchStatuses(long experimentId, String... statuses)
            throws SQLException {
        List<BatchOrchestrator.BatchRow> rows =
                new BatchOrchestrator(db, TENANT, new FakeRunner()).ensureBatches(experimentId);
        for (int i = 0; i < statuses.length && i < rows.size(); i++) {
            exec("UPDATE experiment_batch SET status = ?, updated_at = now() "
                            + "WHERE tenant_id = ? AND id = ?",
                    statuses[i], TENANT, rows.get(i).id());
        }
    }

    // ================================================================ S3.1 造数 / 查询

    /** 插入一条 experiment_run（显式负数 id；status/error 由用例给定）。 */
    private static void insertRunRow(long runId, long experimentId, String status, String error)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment_run
                         (id, tenant_id, experiment_id, status, error, started_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     """)) {
            ps.setLong(1, runId);
            ps.setLong(2, TENANT);
            ps.setLong(3, experimentId);
            ps.setString(4, status);
            ps.setString(5, error);
            ps.executeUpdate();
        }
    }

    /** 插入一条批次尝试（id 由自增分配，按 batch_id / run id 反查）。 */
    private static void insertAttempt(long batchId, long runId, int attemptNo, String status,
                                      String error) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment_batch_run
                         (tenant_id, batch_id, experiment_run_id, attempt_no, status,
                          started_at, error)
                     VALUES (?, ?, ?, ?, ?, now(), ?)
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, batchId);
            ps.setLong(3, runId);
            ps.setInt(4, attemptNo);
            ps.setString(5, status);
            ps.setString(6, error);
            ps.executeUpdate();
        }
    }

    /** 改 run 状态：终态顺手落 finished_at，非终态清空（模拟执行器 settle / 人工控制）。 */
    private static void setRunStatusAndError(long runId, String status, String error)
            throws SQLException {
        exec("UPDATE experiment_run SET status = ?, error = ?, "
                + "finished_at = CASE WHEN ? IN ('completed', 'failed', 'aborted', 'cancelled') "
                + "THEN now() ELSE NULL END "
                + "WHERE tenant_id = ? AND id = ?", status, error, status, TENANT, runId);
    }

    /** 读批次尝试的某列（列名只在测试里写死，不是外部输入）。 */
    private static String attemptCol(long runId, String column) throws SQLException {
        return str("SELECT " + column + " FROM experiment_batch_run "
                + "WHERE tenant_id = ? AND experiment_run_id = ? ORDER BY id DESC LIMIT 1",
                TENANT, runId);
    }

    /** 某实验所有批次尝试的 attempt_no（升序）。 */
    private static List<Integer> attemptNos(long experimentId) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT br.attempt_no
                       FROM experiment_batch_run br
                       JOIN experiment_batch b
                         ON b.tenant_id = br.tenant_id AND b.id = br.batch_id
                      WHERE br.tenant_id = ? AND b.experiment_id = ?
                      ORDER BY br.attempt_no
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    /** 实验当前批次汇总状态（表里原值，不是读侧派生值）。 */
    private static String batchStoredStatus(long experimentId) throws SQLException {
        return str("SELECT status FROM experiment_batch "
                + "WHERE tenant_id = ? AND experiment_id = ? ORDER BY batch_no LIMIT 1",
                TENANT, experimentId);
    }

    /** 精确设置某实验第 n 批的表内状态（不做读侧派生/自愈）。 */
    private static void setBatchStatusOf(long experimentId, int batchNo, String status)
            throws SQLException {
        exec("UPDATE experiment_batch SET status = ?, updated_at = now() "
                        + "WHERE tenant_id = ? AND experiment_id = ? AND batch_no = ?",
                status, TENANT, experimentId, batchNo);
    }

    private static long batchIdOf(long experimentId, int batchNo) throws SQLException {
        Long id = longOf("SELECT id FROM experiment_batch WHERE tenant_id = ? "
                + "AND experiment_id = ? AND batch_no = ?", TENANT, experimentId, batchNo);
        return id == null ? 0L : id;
    }

    private static long runIdOf(long experimentId) throws SQLException {
        Long id = longOf("SELECT id FROM experiment_run WHERE tenant_id = ? "
                + "AND experiment_id = ? ORDER BY id DESC LIMIT 1", TENANT, experimentId);
        return id == null ? 0L : id;
    }

    private static void setRunVariables(long runId, String json) throws SQLException {
        exec("UPDATE experiment_run SET variables = ?::jsonb "
                + "WHERE tenant_id = ? AND id = ?", json, TENANT, runId);
    }

    private static String varsJsonOf(long runId) throws SQLException {
        return str("SELECT variables::text FROM experiment_run "
                + "WHERE tenant_id = ? AND id = ?", TENANT, runId);
    }

    private static boolean hasCandidate(List<BatchOrchestrator.PausedBatch> candidates,
                                        long experimentId, int batchNo) {
        for (BatchOrchestrator.PausedBatch p : candidates) {
            if (p.experimentId() == experimentId && p.batchNo() == batchNo) {
                return true;
            }
        }
        return false;
    }

    private static boolean ascendingBatchNo(List<BatchOrchestrator.PausedBatch> candidates) {
        int prev = Integer.MIN_VALUE;
        for (BatchOrchestrator.PausedBatch p : candidates) {
            if (p.batchNo() < prev) {
                return false;
            }
            prev = p.batchNo();
        }
        return true;
    }

    private static List<Long> queueRunIds(ExperimentScheduler scheduler) {
        List<Long> ids = new ArrayList<>();
        for (ExperimentScheduler.QueueEntry q : scheduler.snapshot().queue()) {
            ids.add(q.experimentRunId());
        }
        return ids;
    }

    private static boolean queueContains(ExperimentScheduler scheduler, long runId) {
        return queueRunIds(scheduler).contains(runId);
    }

    private static Long longOf(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static String str(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Integer intOfExperiment(long experimentId, String column) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT " + column
                     + " FROM experiment WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    /** 断言一段调用抛 IllegalArgumentException（接口层会把它映射成 400）。 */
    private static void expectIllegal(String name, String mustContain, SqlRunnable body) {
        try {
            body.run();
            check(name, false, "没有抛异常");
        } catch (IllegalArgumentException e) {
            check(name, e.getMessage() != null && e.getMessage().contains(mustContain),
                    e.getMessage());
        } catch (Exception e) {
            check(name, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 可抛异常的调用片段（自测里只用于 expectIllegal）。 */
    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }

    // ================================================================ 清理

    /** 删掉自测造的行（物理删除，按负数 id 精确匹配；顺序按外键依赖）。 */
    private static void cleanup() throws SQLException {
        String expIn = EXP_BATCHED + "," + EXP_SNAPSHOT + "," + EXP_UNBATCHED + ","
                + EXP_ZERO + "," + EXP_AGG + "," + EXP_NOSAMPLE + "," + EXP_HOLD + ","
                + EXP_WRITEBACK + "," + EXP_RESUME + "," + EXP_STOP + ","
                + EXP_SPLIT + "," + EXP_SPLIT_LOCKED + "," + EXP_CONC + ","
                + EXP_ASYNC + "," + EXP_ASYNC_STOP + "," + EXP_ASYNC_HOLD + ","
                + EXP_ASYNC_DONE + "," + EXP_AC + "," + EXP_AC_ATTEMPT + ","
                + EXP_AC_ABORTED + "," + EXP_AC_HOLD + "," + EXP_VARS + ","
                + EXP_VARS_BATCH + "," + EXP_DERIVED;
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                exec(c, "DELETE FROM experiment_batch_run WHERE tenant_id = ? AND batch_id IN ("
                        + "SELECT id FROM experiment_batch WHERE tenant_id = ? "
                        + "AND experiment_id IN (" + expIn + "))", TENANT, TENANT);
                exec(c, "DELETE FROM experiment_batch WHERE tenant_id = ? AND experiment_id IN ("
                        + expIn + ")", TENANT);
                exec(c, "DELETE FROM experiment_run_step WHERE tenant_id = ? "
                        + "AND experiment_run_id IN (SELECT id FROM experiment_run "
                        + "WHERE tenant_id = ? AND experiment_id IN (" + expIn + "))",
                        TENANT, TENANT);
                exec(c, "DELETE FROM experiment_run WHERE tenant_id = ? AND experiment_id IN ("
                        + expIn + ")", TENANT);
                exec(c, "DELETE FROM runtime_hold WHERE tenant_id = ? AND scope = 'experiment' "
                        + "AND scope_id IN (" + expIn + ")", TENANT);
                exec(c, "DELETE FROM experiment_sample WHERE experiment_id IN (" + expIn + ")");
                exec(c, "DELETE FROM experiment WHERE tenant_id = ? AND id IN (" + expIn + ")",
                        TENANT);
                exec(c, "DELETE FROM sample WHERE tenant_id = ? AND id >= " + SAMPLE_BASE
                        + " AND id <= " + (SAMPLE_BASE + 99), TENANT);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ================================================================ 断言

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %-46s %s%n", name, actual);
        } else {
            fail++;
            System.out.printf("  ✗ %-46s 实际 %s%n", name, actual);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private BatchDbSelfTest() {
    }
}
