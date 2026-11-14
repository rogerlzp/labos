package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实验执行器：把一条实验按顺序跑完。
 *
 * <p>做四件事：
 * <ol>
 *   <li><b>变量解析</b>：默认值 ← 执行时传入的覆盖值</li>
 *   <li><b>展开</b>：循环块按 {@code loopCount} 展开成 N 份</li>
 *   <li><b>替换</b>：{@code ${var}} 换成实际值（在流程启动前一次性替换）</li>
 *   <li><b>顺序执行</b>：逐个调 {@link WorkflowService#execute}</li>
 * </ol>
 *
 * <p><b>失败即停</b>：实验室里跳过一步意味着板子位置未知，后续步骤全是错的。
 * 所以不做"跳过失败继续跑"。
 *
 * <p><b>变量替换在流程启动前</b>：变量在一次实验内不变，每节点替换是浪费，
 * 也让"改了变量为什么没生效"变得难以解释。
 */
public final class ExperimentExecutor {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** 展开后的一步。 */
    public record Step(long workflowId, int seq, int loopIndex, Map<String, String> params) {
    }

    /** 执行结果。 */
    public record Result(long experimentRunId, String status,
                         int totalSteps, int doneSteps, String error) {
    }

    /**
     * 单步结果：跑完 / 真失败 / 被暂停中断。
     *
     * <p><b>为什么必须区分 FAILED 与 INTERRUPTED</b>：流程执行到需要人工确认的节点
     * （{@code MANUAL}/{@code SKIPPED}）时，{@code WorkflowService} 会把整个流程的
     * run 落成 {@code paused} —— 这是"暂停"，不是"失败"。改动前只看"有没有节点没完成"
     * 就判定步骤失败，导致<b>操作员按一次暂停，实验层面表现为 failed 且不可接回</b>。
     * 这里按流程级暂停口径拆成两态：暂停上浮成扣留（可恢复），失败才终止实验。
     */
    public record StepOutcome(Out state, String error, String pauseKind, String pauseReason) {

        /** 三态。 */
        public enum Out {
            /** 正常跑完。 */
            OK,
            /** 真失败（节点报错/流程被终止），需人工处置，不能自动接回。 */
            FAILED,
            /** 被暂停中断（转人工/跳过/外部暂停），可在动作边界恢复。 */
            INTERRUPTED
        }

        static StepOutcome ok() {
            return new StepOutcome(Out.OK, null, null, null);
        }

        static StepOutcome failed(String error) {
            return new StepOutcome(Out.FAILED, error, null, null);
        }

        static StepOutcome interrupted(String pauseKind, String reason) {
            return new StepOutcome(Out.INTERRUPTED, reason, pauseKind, reason);
        }

        public boolean failed() {
            return state == Out.FAILED;
        }

        public boolean interrupted() {
            return state == Out.INTERRUPTED;
        }
    }

    private final DbConfig db;
    private final long tenantId;
    private final WorkflowService workflows;

    public ExperimentExecutor(DbConfig db, long tenantId, WorkflowService workflows) {
        this.db = db;
        this.tenantId = tenantId;
        this.workflows = workflows;
    }

    /**
     * 跑一次实验。
     *
     * @param overrides 执行时传入的变量值（覆盖默认值），可空
     */
    public Result run(long experimentId, Map<String, String> overrides) throws SQLException {
        return run(experimentId, overrides, 0L);
    }

    /**
     * 跑一次实验。
     *
     * @param existingRunId 复用已有的 experiment_run（调度器提交时创建的）。
     *                      传 0 则新建——直接调 run 的同步路径用。
     *                      不复用会导致每次调度产生两条记录（一条 queued 不动、一条真跑）。
     */
    public Result run(long experimentId, Map<String, String> overrides, long existingRunId)
            throws SQLException {
        return run(experimentId, overrides, existingRunId, true);
    }

    /**
     * 跑一次实验（分批执行专用重载）。
     *
     * @param existingRunId       复用已有的 experiment_run（每批一个 run，调度器/分批编排
     *                            先建 queued 行再传进来），传 0 则新建
     * @param syncExperimentStatus 是否把<b>这一条 run</b> 的状态同步到 {@code experiment} 表。
     *                            <p>非分批路径（整单一次 run）传 {@code true}，与改动前完全一致。
     *                            <p>分批执行传 {@code false}：实验状态必须按 D6 用<b>所有批次</b>
     *                            聚合（{@link BatchPlanner#aggregateExperimentStatus}），
     *                            否则"第 1 批跑完"会把实验改成 finished，后面批次还没跑 ——
     *                            列表上会显示成 "已完成" 的假象。
     */
    public Result run(long experimentId, Map<String, String> overrides, long existingRunId,
                      boolean syncExperimentStatus) throws SQLException {
        Map<String, String> vars = loadVariables(experimentId, overrides);
        List<Step> steps = expand(experimentId, vars);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("实验没有编排任何流程");
        }

        // N-10：跨 Workcell 实验必须有完整 Handoff 配置（ADR-ISLAND-002/004），
        // 缺失/UNKNOWN 在开跑前 fail-closed。
        HandoffGate.check(db, tenantId, experimentId, steps);

        // P-05：派发前**重校验配置快照** —— 每个 Workflow 的绑定还成立吗？还在它
        // 自己的岛上吗？（规格 §6 矩阵「Experiment execution | Revalidate Experiment
        // → Workflow → resource Island chain before dispatch」）
        //
        // 为什么必须在开跑前：绑定的设备被删 / 被移出岛 / 该能力在岛内没有 ⇒ 跑起来
        // 只会在某个节点上转人工，而"为什么"要翻日志才知道 ⇒ 宁可开跑前说清。
        //
        // 只拦**阻塞级**违规（ERROR）：PF-11（绑定存成设备实例号、当前不生效）是
        // WARN —— 流程照样能跑，拿它拦实验会把大量本来能跑的实验误杀。
        //
        // 口径与 HandoffGate 一致：**fail-closed**（抛异常 ⇒ 调度器把这条 run 标失败，
        // 不会留下"永远 queued"的孤儿）。判定逻辑与预检共用 IslandBindingGate。
        List<String> boundaryBad = new ArrayList<>();
        for (Step st : steps) {
            for (IslandBindingGate.Violation v : IslandBindingGate.blocking(
                    IslandBindingGate.violationsOf(db, tenantId, st.workflowId()))) {
                // 带 nodeKey：同一流程里多个节点可能**同名**（三个都叫「Seal」）
                // ⇒ 只写 message 会得到同一句话重复三遍，现场看不出是哪几个节点。
                // ⚠ 只改这里（实验派发的拼接），**不动** IslandBindingGate / 预检的文案
                // （预检那条路线的文案承诺过「逐字保留」）。
                boundaryBad.add("workflow " + st.workflowId()
                        + " 节点 " + (v.nodeKey() == null ? "?" : v.nodeKey())
                        + " " + v.code() + " " + v.message());
            }
        }
        if (!boundaryBad.isEmpty()) {
            throw new IllegalStateException("实验的流程配置已不满足岛边界："
                    + String.join("；", boundaryBad));
        }

        long runId = existingRunId > 0
                ? updateRun(existingRunId, vars, steps.size())
                : createRun(experimentId, vars, steps.size());
        markRunning(runId);

        RuntimeHold holds = new RuntimeHold(db, tenantId);
        long islandId = islandOf(experimentId);
        // 断点续跑：上一轮已经跑完的步直接跳过，从失败/暂停处继续。
        java.util.Set<String> doneKeys = completedStepKeys(runId);

        int done = 0;
        try {
            for (Step st : steps) {
                // ---- 动作边界检查：只在"两步之间"停，不在动作中途停 ----
                // 机械臂抓着板移到半空是不能停的（板会掉），所以暂停/停止都只
                // 在这里生效；正在执行的动作等它自然跑完。
                RuntimeHold.Active covered = holds.coveringForExperiment(experimentId).hold();
                RuntimeHold.Gate gate = RuntimeHold.gate(stopRequested(runId), covered);

                if (gate == RuntimeHold.Gate.STOPPED) {
                    String msg = "人工停止，已在动作边界停下";
                    settle(runId, "aborted", done, msg, null, null, syncExperimentStatus);
                    return new Result(runId, "aborted", steps.size(), done, msg);
                }
                if (gate == RuntimeHold.Gate.HELD) {
                    String reason = covered.reason() == null ? "现场扣留" : covered.reason();
                    settle(runId, "paused", done, reason, covered.kind(), reason,
                            syncExperimentStatus);
                    return new Result(runId, "paused", steps.size(), done, reason);
                }

                if (doneKeys.contains(stepKey(st))) {
                    done++;
                    continue;
                }

                StepOutcome out = runStep(runId, st, vars);
                if (out.failed()) {
                    String err = "第 " + st.seq() + " 步（workflow " + st.workflowId()
                            + "）失败：" + out.error();
                    settle(runId, "failed", done, err, null, null, syncExperimentStatus);
                    return new Result(runId, "failed", steps.size(), done, err);
                }
                if (out.interrupted()) {
                    // 工作流级暂停 → 上浮为**现场扣留**。
                    // 现场暂停是安全事件：同岛其他在跑的实验也要在各自边界停下。
                    // 未分配岛（islandId=0）退化为实验级，至少停住自己。
                    //
                    // ⚠ 已经被扣留时**不重复上浮**：实验级暂停（RunControl.pause）落的
                    //   就是扣留，这里再抬一道岛级扣留会在恢复时留下解除不掉的残留
                    //   （恢复只解除最内层那道）⇒ 实验再也回不到 queued。
                    if (holds.coveringForExperiment(experimentId).hold() == null) {
                        if (islandId > 0) {
                            holds.raise(RuntimeHold.Scope.ISLAND, islandId,
                                    RuntimeHold.Kind.of(out.pauseKind()), out.pauseReason(), null);
                        } else {
                            holds.raise(RuntimeHold.Scope.EXPERIMENT, experimentId,
                                    RuntimeHold.Kind.of(out.pauseKind()), out.pauseReason(), null);
                        }
                    }
                    settle(runId, "paused", done, out.pauseReason(),
                            out.pauseKind(), out.pauseReason(), syncExperimentStatus);
                    return new Result(runId, "paused", steps.size(), done, out.pauseReason());
                }
                done++;
                bumpCurrentSeq(runId, st.seq());
            }
            settle(runId, "completed", done, null, null, null, syncExperimentStatus);
            return new Result(runId, "completed", steps.size(), done, null);
        } catch (Exception e) {
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            settle(runId, "failed", done, err, null, null, syncExperimentStatus);
            return new Result(runId, "failed", steps.size(), done, err);
        }
    }

    /** 断点续跑用的步标识：seq + 循环下标（同一 seq 循环里每一圈都独立）。 */
    private static String stepKey(Step st) {
        return st.seq() + ":" + st.loopIndex();
    }

    /**
     * 展开：先按偏序求执行序，循环块再按 loopCount 复制，同时替换参数里的 {@code ${var}}。
     *
     * <p>执行序来自 {@code experiment_process_edge}（前驱 -> 后继）；没有边的存量数据
     * 退化为按 {@code seq} 升序 —— 与改动前完全一致。
     */
    public List<Step> expand(long experimentId, Map<String, String> vars) throws SQLException {
        List<Step> out = new ArrayList<>();
        // 1) 编排行（seq 只用于"同层谁先跑"的稳定排序，不再是唯一顺序来源）
        Map<Long, ProcRow> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : em().selectExperimentProcessRows(tenantId, experimentId)) {
            ProcRow pr = new ProcRow(((Number) row.get("id")).longValue(),
                    ((Number) row.get("seq")).intValue(),
                    ((Number) row.get("workflowId")).longValue(),
                    (String) row.get("loopCount"),
                    (String) row.get("paramsOverride"));
            rows.put(pr.id(), pr);
        }

        // 2) 偏序边
        Map<Long, List<Long>> successors = new java.util.HashMap<>();
        for (Map<String, Object> row
                : em().selectExperimentProcessEdges(tenantId, experimentId)) {
            successors.computeIfAbsent(((Number) row.get("fromId")).longValue(),
                    k -> new ArrayList<>()).add(((Number) row.get("toId")).longValue());
        }

        // 3) 求执行序（Kahn；同层按 seq 稳定排序）
        Map<Long, Integer> seqOrder = new LinkedHashMap<>();
        rows.forEach((id, row) -> seqOrder.put(id, row.seq()));
        List<Long> order = topologicalOrder(rows.keySet(), successors, seqOrder);

        // 4) 展开
        for (Long id : order) {
            ProcRow row = rows.get(id);
            int times = resolveLoop(row.loopCount(), vars);
            if (times > 20) {
                throw new IllegalArgumentException(
                        "第 " + row.seq() + " 步循环次数 " + times + " 超过上限 20");
            }
            Map<String, String> params = parseParams(row.paramsOverride(), vars);
            for (int i = 0; i < times; i++) {
                out.add(new Step(row.workflowId(), row.seq(), i, params));
            }
        }
        if (out.size() > 500) {
            throw new IllegalArgumentException("展开后 " + out.size() + " 步，超过上限 500");
        }
        return out;
    }

    /** 一行 experiment_process（只取展开需要的列）。 */
    private record ProcRow(long id, int seq, long workflowId,
                           String loopCount, String paramsOverride) {
    }

    /**
     * 偏序求执行序（Kahn 拓扑排序）。
     *
     * <p>S2-A 只承诺<b>偏序</b>：可执行集合里一次取一个（按 {@code seq} 升序），
     * 物理串行推进，<b>不承诺墙钟并发</b>。要真并发必须先接三层资源 + 等待图。
     *
     * <p>有环 = 无法确定顺序，直接抛错，不猜。
     *
     * @param ids       所有节点 id
     * @param successors 前驱 -> 后继列表（无出边的节点可以不出现在 map 里）
     * @param seqOrder  id -> seq，用于同层稳定排序
     */
    static List<Long> topologicalOrder(java.util.Collection<Long> ids,
                                       Map<Long, List<Long>> successors,
                                       Map<Long, Integer> seqOrder) {
        Map<Long, Integer> indegree = new LinkedHashMap<>();
        for (Long id : ids) {
            indegree.put(id, 0);
        }
        for (Map.Entry<Long, List<Long>> e : successors.entrySet()) {
            for (Long to : e.getValue()) {
                if (!indegree.containsKey(to)) {
                    throw new IllegalArgumentException(
                            "编排边指向了不存在的流程：" + to);
                }
                indegree.merge(to, 1, Integer::sum);
            }
        }
        java.util.PriorityQueue<Long> ready =
                new java.util.PriorityQueue<>(java.util.Comparator.comparingInt(seqOrder::get));
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                ready.add(id);
            }
        });
        List<Long> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            Long cur = ready.poll();
            order.add(cur);
            for (Long to : successors.getOrDefault(cur, List.of())) {
                if (indegree.merge(to, -1, Integer::sum) == 0) {
                    ready.add(to);
                }
            }
        }
        if (order.size() != indegree.size()) {
            throw new IllegalArgumentException(
                    "实验编排存在环，无法确定执行顺序（请检查画布里的回边）");
        }
        return order;
    }

    /** 循环次数：null → 1；"3" → 3；"${n}" → 变量值。 */
    private int resolveLoop(String loop, Map<String, String> vars) {
        if (loop == null || loop.isBlank()) {
            return 1;
        }
        String v = substitute(loop, vars);
        try {
            return Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("循环次数不是数字: " + loop + " → " + v);
        }
    }

    /** 参数里的 ${var} 替换。 */
    private Map<String, String> parseParams(String json, Map<String, String> vars) {
        Map<String, String> m = new LinkedHashMap<>();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return m;
        }
        Matcher mm = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        while (mm.find()) {
            m.put(mm.group(1), substitute(mm.group(2), vars));
        }
        return m;
    }

    /** 把 ${name} 换成值；找不到就保留原样（让执行时报错更直观）。 */
    static String substitute(String s, Map<String, String> vars) {
        if (s == null || vars.isEmpty()) {
            return s;
        }
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group(0) : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 变量 = 默认值 ← 覆盖值。 */
    private Map<String, String> loadVariables(long experimentId, Map<String, String> overrides)
            throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> row
                : em().selectExperimentVariables(tenantId, experimentId)) {
            String def = (String) row.get("defaultValue");
            m.put((String) row.get("name"), def == null ? "" : def);
        }
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (v != null) {
                    m.put(k, v);
                }
            });
        }
        return m;
    }

    // ---------------------------------------------------------- 落库

    /** 复用调度器创建的记录：补上变量与展开步数。 */
    private long updateRun(long runId, Map<String, String> vars, int steps) throws SQLException {
        em().updateRunVariables(tenantId, runId, toJson(vars), steps);
        return runId;
    }

    private long createRun(long experimentId, Map<String, String> vars, int steps)
            throws SQLException {
        return em().insertExperimentRun(tenantId, experimentId, toJson(vars), steps);
    }

    /** 包内可见：供 {@code RunControlDbSelfTest} 直接压这条异常敏感路径。 */
    void markRunning(long runId) throws SQLException {
        // ⚠ **已经被现场扣留的 run 不能被标成 running**（2026-11-06 实测发现）：
        //   用户在实验执行到一半时点暂停 → `RunControl.pause` 落下 runtime_hold
        //   并把 experiment_run 标成 'paused'；但随后调度器派发这条 run 时，
        //   `markRunning` 无条件 `status='running'` + 清空 pause_kind/pause_reason，
        //   **把刚建立的暂停冲掉了** —— 执行器随后在动作边界查 `coveringFor`
        //   才又看到扣留并停下，但状态已经被改写成 running，
        //   用户看到的是「点了暂停，状态却还在跑」。
        //
        //   正确顺序：先看有没有生效扣留，有就保持 paused（等人工点继续）。
        long islandId = islandOf(experimentIdOf(runId));
        boolean held = new RuntimeHold(db, tenantId)
                .coveringForExperiment(experimentIdOf(runId)).hold() != null;
        if (held) {
            // 保持 paused：只补「开始时间」，状态与暂停标记都不动。
            // ⚠ 不要写 updated_at：experiment_run 没有这一列（只有 experiment 有），
            //   写它会抛 PSQLException 把这条 run 直接落 failed（2026-11-06 实测）。
            em().touchStartedAtWhenPaused(tenantId, runId);
            return;
        }
        // 恢复时（pause_kind 非空）记一次 resumed_at，并把暂停标记清掉：
        // 这条 run 现在真的在跑，"为什么停过"由 runtime_hold 的历史留存。
        em().markExperimentRunRunning(tenantId, runId);
        try (Connection c = db.open()) {
            // 实验本身也标 running（否则列表上一直是"待执行"）。
            // 收口到 ExperimentStateProjector（S-03 单写者）：SQL 与改前逐字一致。
            ExperimentStateProjector.markRunningByRun(c, tenantId, runId);
        }
    }

    private void bumpCurrentSeq(long runId, int seq) throws SQLException {
        em().bumpCurrentSeq(tenantId, runId, seq);
    }

    /**
     * 落终态。
     *
     * <p>与改动前的 {@code finish} 的差别：除了 {@code status/error}，还要写暂停信息
     * （{@code pause_kind/pause_reason/paused_at}）与终态时间 {@code finished_at}。
     * <b>暂停不是终态</b>——所以 paused 时只记 paused_at，不写 finished_at，
     * 这样"停过又恢复"不会被当成"跑完了"。
     *
     * @param status   completed / failed / aborted / cancelled / paused
     * @param pauseKind 暂停类型（仅 paused 时有值）
     */
    /**
     * 这条 run 现在是不是「已被人工暂停」。
     *
     * <p>与 {@code WorkflowService.isRunPaused} 同一口径（查一行主键）。
     * 收尾前必须问一句：用户刚点暂停、这个 run 已经标 paused 了，
     * 那就不能再用 completed/failed 把它盖掉。
     */
    /** 延迟取 Mapper（执行器是 new 出来的，见 Mappers）。 */
    private com.labos.kernel.persistence.ExecutionMapper em() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExecutionMapper.class);
    }

    private boolean isRunPaused(long runId) {
        try {
            return "paused".equals(em().selectExperimentRunStatus(tenantId, runId));
        } catch (RuntimeException e) {
            // 查不到就当作没暂停（与 workflow 级同一取向：不因读库失败阻断收尾）
            return false;
        }
    }

    /** 包内可见：供 {@code RunControlDbSelfTest} 直接压这条异常敏感路径。 */
    void settle(long runId, String status, int done, String error,
                String pauseKind, String pauseReason, boolean syncExperimentStatus)
            throws SQLException {
        boolean paused = "paused".equals(status);
        boolean terminal = "completed".equals(status) || "failed".equals(status)
                || "aborted".equals(status) || "cancelled".equals(status);

        // ⚠ **已被人工暂停的 run 不能被收尾覆盖**（2026-11-06 实测发现）：
        //   用户在设备方法执行到一半时点暂停 → `RunControl.pause` 把
        //   experiment_run 标成 'paused'（并落下现场扣留）；
        //   但当前动作跑完后执行线程会走这里 `settle(..., "completed", ...)`，
        //   **把 paused 直接盖成 completed** —— 界面立刻显示"已完成"，
        //   用户"点了暂停却什么都没停"。
        //   workflow 级已有同样的保护（`WorkflowService.settleRun` 开头
        //   `if (isRunPaused(runId)) return;`），实验级这里漏了 —— 现在对齐。
        //
        //   为什么必须挡住：扣留（runtime_hold）还在，实验其实处于"等人处理"的状态，
        //   状态写成 completed 会让「暂停队列」页看不到它，现场以为跑完了就走了。
        if (!paused && isRunPaused(runId)) {
            // 已被人工暂停：保持 paused，只补错误信息（若有），不动状态。
            // ⚠ 不要写 updated_at：experiment_run 没有这一列（见上）。
            em().updateErrorWhenPaused(tenantId, runId, error);
            return;
        }

        // S-04-B：任何收尾都表示控制过程走完，control_state 回到 NONE
        // （pause → 执行器在动作边界 settle paused；stop/complete/fail 同理）。
        em().settleExperimentRun(tenantId, runId, status, error,
                pauseKind, pauseReason, paused, terminal);
        try (Connection c = db.open()) {

            // **实验本身的状态也要跟着走**。改动前只更新 experiment_run，
            // experiment.status 永远停在 created —— 实验列表上分不出"跑过"和"没跑过"，
            // 现场点完"执行实验"回列表还是一排"待执行"。
            //
            // 分批执行（syncExperimentStatus=false）不在这里写：实验状态要等所有批次
            // 跑完后按 D6 聚合，避免"第 1 批完成 = 整单完成"。见 BatchOrchestrator。
            if (syncExperimentStatus) {
                syncExperimentStatus(c, tenantId, runId);
            }
        }
        // §4.5.2：非批次（syncExperimentStatus=true）终态卸载本次实验专属物料；
        // 批次实验的 run 终态不等于实验终态，卸载留给 BatchOrchestrator 在整单聚合终态时做（§4.5.2 语义）。
        if (terminal && syncExperimentStatus) {
            unloadProcessMaterials(runId);
        }
    }

    /** §4.5.2：卸载 lifecycle='process' 的物料（清点位状态 + placement 置 unloaded）。 */
    private void unloadProcessMaterials(long runId) {
        try {
            com.labos.kernel.persistence.ManifestMapper mm =
                    com.labos.kernel.persistence.Mappers.get(
                            com.labos.kernel.persistence.ManifestMapper.class);
            com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
                mm.markProcessPlacementsUnloaded(tenantId, runId);
                mm.clearProcessPositionStates(tenantId, runId);
                return null;
            });
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ExperimentExecutor.class)
                    .warn("[物料] 实验结束卸载 process 物料失败（不阻断收尾）: {}", e.getMessage());
        }
    }

    /**
     * 把 experiment_run 的当前状态同步到 experiment 表。
     *
     * <p>抽成静态方法，是因为**人工控制（停止/暂停）也走同一个映射**：
     * 两处各写一套 CASE 迟早会漂移，出现"列表显示已完成、详情显示失败"。
     *
     * <p><b>刻意不写 {@code experiment.run_id}</b>：该列外键指向 {@code run(id)}，
     * 而这里手上只有 {@code experiment_run.id}，两者不是一个 id 空间。
     * 详见 {@link #markRunning(long)} 的说明。
     */
    static void syncExperimentStatus(Connection c, long tenantId, long runId)
            throws SQLException {
        // 收口到 ExperimentStateProjector（S-03 单写者）：SQL 与改前逐字一致，
        // run→experiment 映射仍由 StatusVocabulary.runToExperimentCaseSql 生成
        // （改前的三行 CASE 与它多出的恒等 WHEN 对任意 run.status 结果完全一致）。
        ExperimentStateProjector.syncByRun(c, tenantId, runId);
    }

    /**
     * 断点续跑用：本 run 里已完成的步（{@code seq:loop_index}）。
     *
     * <p>只认 {@code status='completed'} —— 被暂停中断的步（{@code status='paused'}）
     * 不在其中，所以恢复时会<b>从暂停那一步重跑</b>，符合既定口径。
     */
    java.util.Set<String> completedStepKeys(long runId) throws SQLException {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Map<String, Object> row : em().selectCompletedSteps(tenantId, runId)) {
            keys.add(((Number) row.get("seq")).intValue() + ":"
                    + ((Number) row.get("loopIndex")).intValue());
        }
        return keys;
    }

    /** 是否有人请求停止这条 run（协作式：执行器在动作边界看）。 */
    boolean stopRequested(long runId) throws SQLException {
        Boolean v = em().selectStopRequested(tenantId, runId);
        return v != null && v;
    }

    /** 实验所属岛；未分配返回 0（0 表示不匹配任何 island 级扣留）。 */
    private long islandOf(long experimentId) {
        Long v = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExperimentMapper.class)
                .selectExperimentIslandId(tenantId, experimentId);
        return v == null ? 0L : v;
    }

    /** 这条 experiment_run 属于哪个实验；查不到返回 0。 */
    private long experimentIdOf(long runId) {
        Long v = em().selectExperimentIdOfRun(tenantId, runId);
        return v == null ? 0L : v;
    }

    /** 流程级暂停类型（载入 run.pause_kind）；查不到按需人工确认处理。 */
    private String pauseKindOf(Long wfRunId) {
        if (wfRunId == null) {
            return "waiting_human";
        }
        try {
            String k = em().selectPauseKindOfRun(tenantId, wfRunId);
            if (k != null && !k.isBlank()) {
                return k;
            }
        } catch (RuntimeException ignore) {
            // 流程表读不到就退回默认口径，不因此判定实验失败
        }
        return "waiting_human";
    }

    /**
     * 流程级 run 的状态（{@code run.status}，查不到返回 null）。
     *
     * <p>用来判断这一步到底是"跑完了"还是"在节点边界被暂停" ——
     * 流程被暂停时它跑过的节点都已完成、后面的还没轮到，光看节点状态分不出来。
     */
    private String wfRunStatus(Long wfRunId) {
        if (wfRunId == null) {
            return null;
        }
        try {
            return em().selectRunStatus(tenantId, wfRunId);
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    /** 跑一步：落 step 记录 → 执行 → 三态落库。 */
    private StepOutcome runStep(long runId, Step st, Map<String, String> runVars)
            throws SQLException {
        long stepId = em().insertRunStep(tenantId, runId, st.seq(), st.loopIndex(),
                st.workflowId());

        Long wfRunId = null;
        StepOutcome outcome;
        try {
            // 变量代入：节点参数里的 ${name} 换成实际值。
            // finally 里必须 clearVars——变量存在 ThreadLocal，
            // 线程池复用线程时不清会带到下一次执行。
            WorkflowService.Execution e;
            try {
                e = workflows.withVars(runVars).execute(st.workflowId());
            } finally {
                workflows.clearVars();
            }
            wfRunId = e.runId();
            long bad = e.nodes().stream()
                    .filter(n -> !"COMPLETED".equals(String.valueOf(n.status())))
                    .count();
            long needHuman = e.nodes().stream()
                    .filter(n -> "MANUAL".equals(String.valueOf(n.status()))
                            || "SKIPPED".equals(String.valueOf(n.status())))
                    .count();
            // 流程级 run 被暂停（页面暂停 / 实验级现场扣留）在**节点边界**停住 ——
            // 此时"跑过的节点都完成、后面的还没轮到"，只看 bad 会把它误判成成功。
            // 判据落在 run 行本身：WorkflowService 停边界时会把 run 落成 paused。
            boolean wfPaused = "paused".equals(wfRunStatus(wfRunId));
            if (bad == 0 && !wfPaused) {
                outcome = StepOutcome.ok();
            } else if (needHuman > 0) {
                // 流程因"转人工/跳过"落成 paused —— 这是暂停不是失败（见 StepOutcome 说明）
                outcome = StepOutcome.interrupted(pauseKindOf(wfRunId),
                        needHuman + " 个节点需要人工确认（板位不可信，不能当作已完成）");
            } else if (wfPaused) {
                outcome = StepOutcome.interrupted(pauseKindOf(wfRunId),
                        "流程在节点边界被暂停，待恢复后续跑");
            } else {
                outcome = StepOutcome.failed(bad + " 个节点未完成");
            }
        } catch (Exception ex) {
            outcome = StepOutcome.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        String stepStatus = outcome.state() == StepOutcome.Out.OK ? "completed"
                : outcome.state() == StepOutcome.Out.INTERRUPTED ? "paused" : "failed";
        em().settleRunStep(tenantId, stepId, stepStatus, wfRunId, outcome.error());
        return outcome;
    }

    private static String toJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"")
              .append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
