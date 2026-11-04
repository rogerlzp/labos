package com.labos.kernel.workflow;

import com.labos.kernel.alert.Alert;
import com.labos.kernel.alert.AlertAggregator;
import com.labos.kernel.alert.AlertSignal;
import com.labos.kernel.driver.DriverDescriptor;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.DriverStepExecutor;
import com.labos.kernel.driver.tcp.TcpDriverAdapter;
import com.labos.kernel.engine.ExecutionEvent;
import com.labos.kernel.engine.ExecutionListener;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.engine.StepExecutor;
import com.labos.kernel.engine.TimeConstraintExecutor;
import com.labos.kernel.engine.StepSink;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcErrorOccurrenceStore;
import com.labos.kernel.persistence.JdbcEventStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.RealWorkflowRunner.CapabilitySource;
import com.labos.kernel.workflow.RealWorkflowRunner.NodeDef;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工艺服务：供 HTTP API 调用的列表、详情与执行。
 *
 * <p>执行时会自动选择执行器：
 * <ul>
 *   <li>本机有模拟驱动（默认 127.0.0.1:19301）→ 走真实 TCP 驱动契约</li>
 *   <li>没有 → 退回内置 {@link SimulatedBus}，保证页面永远能演示</li>
 * </ul>
 */
public final class WorkflowService {

    private static final DateTimeFormatter RUN_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final DbConfig db;
    private final long tenantId;
    private final int driverPort;

    public WorkflowService(DbConfig db, long tenantId, int driverPort) {
        this.db = db;
        this.tenantId = tenantId;
        this.driverPort = driverPort;
    }

    // ---------------------------------------------------------------- 查询

    /** 只在指定岛内选设备（null = 全库）。执行前设置，用完即弃。 */
    /**
     * 本次执行的岛过滤。
     *
     * <p>和 {@link #runtimeVars} 一样必须线程隔离：并发时 A 设了岛 1、
     * B 设了岛 2，普通字段会让 A 用 B 的岛去选设备。
     */
    private final ThreadLocal<Long> islandFilter = new ThreadLocal<>();

    /** 执行期变量：节点参数里的 ${name} 会被替换成值。 */
    /**
     * 本次执行的变量覆盖。
     *
     * <p><b>为什么是 ThreadLocal 而不是普通字段</b>：这个 Service 是单例，
     * 多个执行并发跑时共用一个实例。用普通字段的话，A 线程调 {@code withVars} 设了
     * {"plate": "A-1"}，B 线程紧接着设成 {"plate": "B-1"}，**A 就拿到 B 的变量了**。
     *
     * <p>压测已证明 16 并发可行，所以这个串扰是**现实风险**不是理论问题。
     *
     * <p>用完必须 {@link #clearVars()}——线程池复用线程时，残留的变量会被
     * 下一次执行读到，表现和没隔离一样。
     */
    private final ThreadLocal<Map<String, String>> runtimeVars =
            ThreadLocal.withInitial(Map::of);

    /**
     * 本次执行的 run id。{@link #substVars} 要靠它去查异步结果。
     * 和 runtimeVars 一样必须线程隔离。
     */
    private final ThreadLocal<Long> currentRunId = new ThreadLocal<>();

    /**
     * 本 run 的异步结果缓存。
     *
     * <p><b>为什么缓存</b>：{@code substVars} 每步都调，每次都查库会很慢。
     * 结果只在 {@code onStepDone} 写入时变，那时候主动刷新即可。
     */
    private final ThreadLocal<Map<String, Object>> resultsCache =
            ThreadLocal.withInitial(LinkedHashMap::new);

    /**
     * 设定本次执行的变量。
     *
     * <p>为什么放在 Service 上而不是每层往下传：参数在
     * {@link #loadMethodParams} 里就转成了 Map，再往回改签名要动 4 个方法；
     * 而一次执行就是一个 Service 实例的生命周期，用完清掉即可。
     */
    public WorkflowService withVars(Map<String, String> vars) {
        runtimeVars.set(vars == null ? Map.of() : Map.copyOf(vars));
        return this;
    }

    /**
     * 清掉本线程的变量覆盖。
     *
     * <p><b>必须在 finally 里调</b>：线程池的线程会被复用，
     * 不清就会把这次的变量带进下一次执行。
     */
    /** 设定本次执行的 run id（substVars 查结果用）。 */
    public WorkflowService withRunId(long runId, java.util.Map<String, Object> results) {
        currentRunId.set(runId);
        resultsCache.set(new LinkedHashMap<>(results));
        return this;
    }

    /** 结果刚写入时刷新缓存（同一个 run 内后续节点能立刻看到）。 */
    public void refreshResults(java.util.Map<String, Object> results) {
        resultsCache.get().putAll(results);
    }

    public void clearVars() {
        runtimeVars.remove();
        islandFilter.remove();
        currentRunId.remove();
        resultsCache.remove();
    }

    /** 把 ${name} 换成值；找不到保留原样（执行时报错更直观）。 */
    /**
     * 把 {@code ${...}} 求值并替换。
     *
     * <p>原来只做纯替换（{@code ${i}} → 值），现在支持表达式：
     * <ul>
     *   <li>{@code ID1183-L${i}} —— 循环生成点位</li>
     *   <li>{@code ID1183-L${usedSlots + 1}} —— 算出下一个位置</li>
     *   <li>{@code ${results.readPlate.od > 0.5 ? 'A' : 'B'}} —— 按异步结果分支</li>
     * </ul>
     *
     * <p>找不到变量时**保留原样**（日志里看到 {@code ${i}} 就知道没传），
     * 不替换成空串——那样分不清"漏传"和"真为空"。
     */
    private String substVars(String s) {
        Map<String, String> vars = runtimeVars.get();
        if (s == null || !com.labos.kernel.expr.Template.hasSlot(s)) {
            return s;
        }
        if (vars.isEmpty() && resultsCache.get().isEmpty()) {
            return s;
        }
        // Expression 需要 Object 才能做算术（String "3" + 1 要算成 4）
        Map<String, Object> objVars = new java.util.LinkedHashMap<>();
        // 先放异步结果（results.xxx），再放流程变量——同名时流程变量优先
        objVars.putAll(resultsCache.get());
        objVars.putAll(vars);
        return com.labos.kernel.expr.Template.render(s, objVars);
    }

    /**
     * 限定从某个岛选设备。
     *
     * <p>用途：岛内演示时希望"板子在这几台之间流转"，
     * 而不是被全库 239 台设备里的任意一台接管。
     */
    public WorkflowService withIsland(Long islandId) {
        if (islandId == null) {
            islandFilter.remove();
        } else {
            islandFilter.set(islandId);
        }
        return this;
    }

    /**
     * 把"没有板"的设备排到候选池前面。
     *
     * <p>为什么只排序不筛选：同能力的设备可能全都有板（都装了板在孵育），
     * 这时候必须能复用，否则流程跑不动。排序则让有空设备时优先用它，
     * 板子就会在不同设备间流动。
     *
     * <p>直接返回 device_instance.id 集合，避免再做一次映射——
     * 这个项目里"两套 id"已经坑过三次了。
     */
    private Map<String, List<Long>> preferEmptyDevices(Map<String, List<Long>> capPool)
            throws SQLException {
        java.util.Set<Long> busy = new java.util.LinkedHashSet<>();
        String sql = """
                SELECT di.id
                FROM device_state ds
                JOIN device_instance di
                  ON di.resource_id = ds.resource_id AND di.deleted_at IS NULL
                WHERE ds.tenant_id = ? AND ds.has_plate
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    busy.add(rs.getLong(1));
                }
            }
        }
        if (busy.isEmpty()) {
            return capPool;
        }

        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : capPool.entrySet()) {
            List<Long> ids = new ArrayList<>(e.getValue());
            // 稳定的：空设备在前，有板的在后（保持原有相对顺序）
            ids.sort(java.util.Comparator.comparing(busy::contains));
            out.put(e.getKey(), ids);
        }
        return out;
    }

    /** 岛的 device_instance.id 集合（空集表示岛内没设备）。 */    /** 岛的 device_instance.id 集合（空集表示岛内没设备）。 */
    private java.util.Set<Long> loadIslandDeviceIds(long islandId) throws SQLException {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        String sql = """
                SELECT di.id FROM device_instance di
                JOIN resource_node r ON r.id = di.resource_id
                WHERE di.tenant_id = ? AND r.parent_id = ? AND di.deleted_at IS NULL
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, islandId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }

    public List<WorkflowDef> list(int limit) throws SQLException {
        return list(limit, null);
    }

    /**
     * 按分类筛选流程。
     *
     * <p>为什么要分类查：列表按任务数降序，991 个 experiment 流程会把新生成的
     * 搬运流程（3~5 个节点）挤到 limit 之外，前端永远看不到。
     */
    public List<WorkflowDef> list(int limit, String category) throws SQLException {
        List<WorkflowDef> out = new ArrayList<>();
        // 不写成 "?::text IS NULL"：JDBC 推断不出 NULL 的类型，条件永远为假
        boolean byCategory = category != null && !category.isBlank();
        String sql = """
                SELECT w.id, w.code, w.name, w.category,
                       (SELECT count(*) FROM workflow_node n
                         WHERE n.workflow_id = w.id AND n.node_type = 'task') AS task_count
                FROM workflow w
                WHERE w.tenant_id = ?
                """
                + (byCategory ? " AND w.category = ?" : "")
                // **最近改动的在前**。原来按 task_count DESC 排 —— 结果新建的
                // 单步流程（任务数少）永远落在 limit 之外，界面上根本搜不到，
                // 现象是"刚建的流程不见了"。复杂流程并不等于更该被看见。
                + " ORDER BY w.updated_at DESC NULLS LAST, w.id DESC LIMIT ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WorkflowDef(rs.getLong(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    public Detail detail(long workflowId) throws SQLException {
        WorkflowDef wf = RealWorkflowRunner.loadWorkflow(db, workflowId);
        List<NodeDef> nodes = RealWorkflowRunner.loadNodes(db, workflowId);
        List<String[]> edges = RealWorkflowRunner.loadEdges(db, workflowId);
        Map<String, String> protocolCap = RealWorkflowRunner.loadProtocolCapability(db);

        List<TaskView> tasks = new ArrayList<>();
        for (NodeDef n : nodes) {
            if (!"task".equals(n.type())) {
                continue;
            }
            CapabilitySource cs = RealWorkflowRunner.resolveCapability(n, protocolCap);
            tasks.add(new TaskView(n.id(), n.key(), n.name(), cs.capability(), cs.source(),
                    n.protocolName()));
        }
        return new Detail(wf, tasks, edges.size());
    }

    public record Detail(WorkflowDef workflow, List<TaskView> tasks, int edgeCount) {
    }

    /** 编译结果：每个节点展开成几步。 */
    public record CompileResult(WorkflowDef workflow,
                                List<RecipeCompiler.NodePlan> nodes,
                                int totalSteps, int recipeNodes) {
    }

    /**
     * 返回"展开视图"的 BPMN：多步节点变成可折叠的 subProcess。
     *
     * <p>只用于设计器展示，库里的 graph 不变。
     */
    public String graphWithSteps(long workflowId, String xml) throws SQLException {
        CompileResult r = compile(workflowId);
        Map<String, List<String>> stepsByKey = new LinkedHashMap<>();
        for (RecipeCompiler.NodePlan p : r.nodes()) {
            if (p.stepCount() > 1) {
                stepsByKey.put(p.nodeKey(), p.steps().stream()
                        .map(RecipeCompiler.Step::method).toList());
            }
        }
        return BpmnExpander.expand(xml, stepsByKey);
    }

    /**
     * 编译预览：不执行，只算"每个节点会展开成哪几步"。
     */
    public CompileResult compile(long workflowId) throws SQLException {
        WorkflowDef wf = RealWorkflowRunner.loadWorkflow(db, workflowId);
        List<NodeDef> nodes = RealWorkflowRunner.loadNodes(db, workflowId);
        Map<String, String> protocolCap = RealWorkflowRunner.loadProtocolCapability(db);
        RecipeCompiler compiler = RecipeCompiler.load(db, tenantId);

        List<RecipeCompiler.NodePlan> plans = compiler.compileAll(nodes, protocolCap);
        int totalSteps = plans.stream().mapToInt(RecipeCompiler.NodePlan::stepCount).sum();
        int recipeNodes = (int) plans.stream()
                .filter(RecipeCompiler.NodePlan::fromRecipe).count();
        return new CompileResult(wf, plans, totalSteps, recipeNodes);
    }

    public record TaskView(long id, String key, String name,
                           String capability, String source, String method) {
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 把执行中的失败信号过一遍静默期聚合。
     *
     * <p>抽成静态方法是为了可测——它不依赖数据库，只依赖 {@link RunProgress}。
     *
     * @param base 聚合基准时刻（单次执行统一用同一时刻，避免窗口错位）
     */
    public static List<Alert> collectAlerts(RunProgress progress, Instant base) {
        AlertAggregator aggregator = new AlertAggregator(Duration.ofMinutes(15));
        List<Alert> alerts = new ArrayList<>();
        for (NodeProgress np : progress.all()) {
            if (np.status() == NodeStatus.COMPLETED || np.status() == NodeStatus.RUNNING) {
                continue;
            }
            String code = np.lastErrorCode() == null ? "EC-UNKNOWN" : np.lastErrorCode();
            String severity = switch (np.status()) {
                case SKIPPED -> "info";     // 留痕不告警
                case ABORTED -> "error";
                default -> "warn";
            };
            alerts.addAll(aggregator.offer(new AlertSignal(
                    code, np.currentResourceId(), severity,
                    np.lastReason() == null ? "" : np.lastReason(), base)));
        }
        // 单次执行结束后强制关闭所有窗口，否则拿不到摘要
        alerts.addAll(aggregator.flushExpired(base.plus(Duration.ofDays(1))));
        return alerts;
    }

    /** 后台执行线程池：UI 发起后立刻返回 runId，避免 HTTP 长时间阻塞。 */
    private static final java.util.concurrent.ExecutorService RUN_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "run-exec");
                t.setDaemon(true);
                return t;
            });

    public Execution execute(long workflowId) throws SQLException {
        return execute(workflowId, ATTENDED);
    }

    /**
     * 异步启动：先创建 run（status=running）并立即返回 runId，后台继续执行。
     *
     * <p>这样才有"运行中的 run"这个概念——运行中切换值守模式、Process Status、
     * 告警认领都需要它。
     */
    public long startAsync(long workflowId, String attendance) throws SQLException {
        return startAsync(workflowId, attendance, null);
    }

    /**
     * @param vars 运行期变量（"k1=v1,k2=v2"），写进 {@code run.input.vars}，
     *             供条件表达式/循环退出条件使用
     */
    public long startAsync(long workflowId, String attendance, String vars) throws SQLException {
        WorkflowDef wf = RealWorkflowRunner.loadWorkflow(db, workflowId);
        String mode = UNATTENDED.equalsIgnoreCase(attendance) ? UNATTENDED : ATTENDED;
        long runId = createRun(wf, mode);
        if (vars != null && !vars.isBlank()) {
            // 必须在**启动线程之前**写：后台线程一开始就会读 input.vars
            StringBuilder vj = new StringBuilder("{");
            for (String kv : vars.split(",")) {
                int i = kv.indexOf('=');
                if (i <= 0) {
                    continue;
                }
                if (vj.length() > 1) {
                    vj.append(',');
                }
                vj.append('"').append(kv.substring(0, i).trim()).append("\":\"")
                  .append(kv.substring(i + 1).trim().replace("\"", "")).append('"');
            }
            vj.append('}');
            String json = vj.toString();
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement("""
                         UPDATE run SET input = COALESCE(input, '{}'::jsonb) || ?::jsonb
                          WHERE tenant_id = ? AND id = ?
                         """)) {
                ps.setString(1, "{\"vars\":" + json + "}");
                ps.setLong(2, tenantId);
                ps.setLong(3, runId);
                ps.executeUpdate();
            }
        }

        RUN_POOL.submit(() -> {
            try {
                execute(workflowId, mode, runId);
            } catch (Exception e) {
                try {
                    markRunFailed(runId, e.getMessage());
                } catch (SQLException ignore) {
                    // 尽力而为
                }
            }
        });
        return runId;
    }

    /** 后台执行异常时把 run 收尾，避免永远停在 running。 */
    private void markRunFailed(long runId, String reason) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE run
                     SET status = 'failed', actual_end = now(),
                         input = COALESCE(input, '{}'::jsonb) || ?::jsonb
                     WHERE tenant_id = ? AND id = ? AND status = 'running'
                     """)) {
            ps.setString(1, Jsons.obj("error", reason == null ? "unknown" : reason));
            ps.setLong(2, tenantId);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    /**
     * **断点续跑**：点"继续"之后真的从卡住的节点接着跑。
     *
     * <p>背景：`PauseManager.resume` 只把 run 置回 {@code queued}（如实表达"等被拾起"），
     * 因为执行体早退出了。没有本方法，界面点"继续"就永远是 queued ——
     * 现场的处理是"换完纸再点继续"，点了没反应等于整套暂停机制白做。
     *
     * <p>做法：
     * <ol>
     *   <li>读出上一轮 {@code run_node} 里已 completed 的 node_key（这些不再执行）；
     *   <li>把**没完成**的节点行删掉（连同它的 run_command，FK 级联）——
     *       它们会被重新执行、重新落库，否则一张 run 里会出现两套节点；
     *   <li>run 置 running，后台用同一个 runId 再跑一遍图。
     * </ol>
     */
    public void resumeAsync(long runId) throws SQLException {
        Long wfId = null;
        String attendance = ATTENDED;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT workflow_id, COALESCE(attendance, 'attended') AS attendance
                     FROM run WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("run 不存在: " + runId);
                }
                long v = rs.getLong(1);
                wfId = rs.wasNull() ? null : v;
                attendance = rs.getString(2);
            }
        }
        if (wfId == null) {
            throw new IllegalStateException("run " + runId + " 没有关联 workflow，无法续跑");
        }

        java.util.Set<String> doneKeys = new java.util.LinkedHashSet<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT node_key FROM run_node
                     WHERE tenant_id = ? AND run_id = ? AND level = 0 AND status = 'completed'
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    doneKeys.add(rs.getString(1));
                }
            }
        }

        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                // 必须**先删步骤行（level=1）**，再删业务节点。
                // run_command 挂在步骤行上（FK -> run_node.id ON DELETE CASCADE），
                // 而步骤行只靠裸字段 parent_node_id 指向业务节点，**没有外键** ——
                // 只删 level=0 级联不到它们（2026-11-03 评审 H1，已实测复现：
                // 续跑发了 2 条命令，run_command 一条没增，日志里 2 条 duplicate key）。
                try (PreparedStatement ps = c.prepareStatement("""
                        DELETE FROM run_node s
                         USING run_node p
                         WHERE s.tenant_id = ? AND s.run_id = ? AND s.level = 1
                           AND p.id = s.parent_node_id
                           AND p.tenant_id = s.tenant_id AND p.run_id = s.run_id
                           AND p.level = 0 AND p.status <> 'completed'
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, runId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        DELETE FROM run_node
                        WHERE tenant_id = ? AND run_id = ? AND level = 0
                          AND status <> 'completed'
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, runId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE run SET status = 'running', pause_kind = NULL, pause_reason = NULL,
                               pause_node_key = NULL, resumed_at = now(), updated_at = now()
                        WHERE tenant_id = ? AND id = ?
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, runId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }

        final long wid = wfId;
        final String mode = attendance;
        final long rid = runId;
        Thread t = new Thread(() -> {
            try {
                execute(wid, mode, rid, doneKeys);
            } catch (Exception e) {
                try {
                    markRunFailed(rid, "续跑失败：" + e.getMessage());
                } catch (SQLException ignore) {
                    // 尽力而为
                }
            }
        }, "labos-resume-" + runId);
        t.setDaemon(true);
        t.start();
    }

    /** 有人值守。 */
    public static final String ATTENDED = "attended";
    /** 无人值守：只按策略执行，MANUAL 记告警但不等人。 */
    public static final String UNATTENDED = "unattended";

    public Execution execute(long workflowId, String attendance) throws SQLException {
        return execute(workflowId, attendance, null);
    }

    /**
     * @param presetRunId 非空时复用已有 run（异步执行先建 run 再跑）
     */
    public Execution execute(long workflowId, String attendance, Long presetRunId)
            throws SQLException {
        return execute(workflowId, attendance, presetRunId, java.util.Set.of());
    }

    /**
     * @param skipKeys 断点续跑时上一轮已完成的节点（{@code workflow_node.node_key}）
     */
    public Execution execute(long workflowId, String attendance, Long presetRunId,
                             java.util.Set<String> skipKeys) throws SQLException {
        // 分段计时：排查"命令只花 312ms 但总耗时 5 秒"用。
        // 打开方式：LABOS_TRACE_TIMING=1
        final boolean trace = System.getenv("LABOS_TRACE_TIMING") != null;
        long t0 = System.currentTimeMillis();
        java.util.function.Supplier<Long> lap = () -> System.currentTimeMillis() - t0;
        long lastLap = 0;

        WorkflowDef wf = RealWorkflowRunner.loadWorkflow(db, workflowId);
        List<NodeDef> nodes = RealWorkflowRunner.loadNodes(db, workflowId);
        List<String[]> edges = RealWorkflowRunner.loadEdges(db, workflowId);
        Map<String, List<Long>> pool = RealWorkflowRunner.loadCapabilityPool(db);
        Map<String, String> protocolCap = RealWorkflowRunner.loadProtocolCapability(db);
        if (trace) {
            System.out.println("[计时] 加载定义 " + lap.get() + "ms（累计）");
            lastLap = lap.get();
        }

        List<NodeDef> tasks = new ArrayList<>();
        for (NodeDef n : nodes) {
            if ("task".equals(n.type())) {
                tasks.add(n);
            }
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("该工艺没有可执行节点");
        }
        List<NodeDef> ordered = RealWorkflowRunner.topoSort(tasks, edges);

        // 驱动（若本机模拟驱动在线）
        DriverRegistry registry = buildDriverRegistry();
        if (trace) {
            long now = lap.get();
            System.out.println("[计时] 驱动注册表 " + (now - lastLap) + "ms（累计 "
                    + now + "ms）");
            lastLap = now;
        }
        boolean viaDriver = registry != null;
        Map<Long, Set<String>> devCaps = viaDriver ? loadDeviceCapabilities() : Map.of();

        List<RecoverableNode> rec = new ArrayList<>();
        for (NodeDef n : ordered) {
            CapabilitySource cs = RealWorkflowRunner.resolveCapability(n, protocolCap);
            String cap = cs.capability();
            List<Long> candidates;
            if (RealWorkflowRunner.LOGIC.equals(cap)) {
                candidates = List.of(RealWorkflowRunner.LOGIC_RESOURCE);
            } else if (viaDriver) {
                candidates = registry.candidates(cap, Set.of());
            } else {
                candidates = pool.getOrDefault(cap, List.of());
            }
            if (candidates.isEmpty() && RealWorkflowRunner.LOGIC.equals(cap)) {
                candidates = List.of(RealWorkflowRunner.LOGIC_RESOURCE);
            }
            rec.add(RecoverableNode.of(n.id(), n.key(), n.name(), cap, n.protocolName(),
                    candidates.isEmpty() ? null : candidates.get(0), candidates));
        }

        Map<String, String> capByKey = loadCapabilityByKey(workflowId);

        // 候选池的 id 口径必须与执行器一致：
        //   驱动路径用 registry.candidates()（device_instance.id，如 171）
        //   模拟路径用 resource_capability（resource_node.id，如 200171）
        // 混用会让 driverFor() 找不到设备 → EC-06-0001。
        Map<String, List<Long>> capPool = viaDriver
                ? registryPool(registry, capByKey.values())
                : RealWorkflowRunner.loadCapabilityPool(db);

        // 优先用"没有板"的设备：同能力两台时，板子会换地方，物流才有意义。
        // 只影响顺序，不改变候选集合——没有空设备时仍会复用有板的。
        capPool = preferEmptyDevices(capPool);

        // 限定岛：只保留该岛内的设备；岛内没有该能力的设备时保留原池（避免跑不动）
        if (islandFilter != null) {
            Long islFilter = islandFilter.get();
            java.util.Set<Long> islandDevs = islFilter == null ? java.util.Set.of()
                    : loadIslandDeviceIds(islFilter);
            Map<String, List<Long>> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, List<Long>> e : capPool.entrySet()) {
                List<Long> keep = e.getValue().stream()
                        .filter(islandDevs::contains).toList();
                filtered.put(e.getKey(), keep.isEmpty() ? e.getValue() : keep);
            }
            capPool = filtered;
        }
        Map<String, Long> nodeIdByKey = loadNodeIdByKey(workflowId);
        Map<Long, Map<String, Object>> paramsByNode = loadMethodParams(workflowId, nodeIdByKey);
        // 内置节点（__setVar/__expr）要按 node_key 取参数：GraphRunner 只认 key
        Map<String, Map<String, Object>> builtinParams = new java.util.HashMap<>();
        nodeIdByKey.forEach((k, id) -> {
            Map<String, Object> pp = paramsByNode.get(id);
            if (pp != null) {
                builtinParams.put(k, pp);
            }
        });

        if (trace) {
            long now = lap.get();
            System.out.println("[计时] 候选池+参数 " + (now - lastLap) + "ms（累计 "
                    + now + "ms）");
            lastLap = now;
        }

        // 编译：每个节点展开成几步
        RecipeCompiler compiler = RecipeCompiler.load(db, tenantId);
        Map<Long, List<String>> stepsByNode = new LinkedHashMap<>();
        for (NodeDef n : ordered) {
            RecipeCompiler.NodePlan plan = compiler.compile(n, protocolCap);
            stepsByNode.put(n.id(), plan.steps().stream()
                    .map(RecipeCompiler.Step::method).toList());
        }

        StepCollector collector = new StepCollector();
        String mode = UNATTENDED.equalsIgnoreCase(attendance) ? UNATTENDED : ATTENDED;
        long runId = presetRunId != null ? presetRunId : createRun(wf, mode);

        // 先把业务节点落成 running 外壳，拿到 run_node.id——
        // 这样执行过程中每一步就能立刻挂到父节点下（命令级落库的前提）。
        Map<Long, Long> nodeIdMap = createNodeShells(runId, rec,
                deviceToResourceOf(), validResourcesOf());

        JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(db, tenantId);
        RecoveryDecider decider = new RecoveryDecider(new RecoveryPolicyResolver(repo.loadAll()));

        List<ExecutionEvent> events = new ArrayList<>();
        // 命令级落库：每步结束立刻写 run_node(level=1) + run_command，
        // 而不是等整个 run 结束再批量写（否则中途崩溃什么都查不到）
        PersistingStepSink persistSink = new PersistingStepSink(runId, nodeIdMap,
                deviceToResourceOf(), validResourcesOf(), collector, paramsByNode,
                new com.labos.kernel.driver.PositionLock(db, tenantId));
        StepExecutor executor = viaDriver
                // P1-1：必须把 db 传进去，否则 executeWithPlan 里 protect 恒 false，
                // 四层资源保护（门/通道/位置/机械臂）全部跳过。
                ? new DriverStepExecutor(registry, persistSink, db, tenantId, runId)
                : new SimulatedBus();

        // 时间约束（两步之间的最短/最长间隔）：装饰在 executor 外面，
        // 所以顺序执行和按图执行都会经过它。
        Map<Long, TimeConstraintExecutor.Constraint> tc = loadTimeConstraints(workflowId);
        if (!tc.isEmpty()) {
            executor = new TimeConstraintExecutor(executor, tc, Clock.systemUTC(), 60_000L);
        }
        // 不要强转：executor 可能被 TimeConstraintExecutor 包过
        DriverStepExecutor driverExec = executor instanceof DriverStepExecutor d ? d : null;

        String[] pathHolder = new String[]{"sequential"};
        RunMonitor.INSTANCE.start(runId, "run-" + runId, mode, rec.size());
        ExecutionListener monitor = e -> RunMonitor.INSTANCE.onEvent(runId, e);

        RunProgress progress;
        int eventsWritten = 0;
        try (JdbcEventStore store = new JdbcEventStore(db, tenantId, runId, "api-wf", 100)) {
            // ⚠️ 这里原来传的是 Sleeper.recording(...)：只**记录**退避时间，没人真睡。
            // 后果：策略写 retry 3 次（500ms/1s/2s），实际 50ms 内就把三次机会用光，
            // 设备"忙两秒"这种瞬时故障必然判失败 → 转人工。退避形同虚设。
            // 生产必须真睡；只有 CI/批量验证嫌慢时才用 LABOS_FAST_RECOVERY=1 关掉。
            Sleeper recoverySleeper = "1".equals(System.getenv("LABOS_FAST_RECOVERY"))
                    ? Sleeper.recording(new ArrayList<>())
                    : Sleeper.REAL;
            RunStateMachine sm = new RunStateMachine(decider,
                    tee(events, store, monitor), recoverySleeper, Clock.systemUTC())
                    .withStepPlanner(id -> stepsByNode.getOrDefault(id, List.of()))
                    .withParamPlanner(id -> paramsByNode.getOrDefault(id, Map.of()));
            // 按图执行（含并行/异或网关）。
            // 之前这里走 executeAll，网关被忽略——批量验收用了 GraphRunner，
            // 于是出现"验收说 100%、产品路径却没有网关"的不一致。
            // 网关执行默认开启（EC-06-0001 已修：候选池 id 口径统一为 device_instance.id）。
            // 需要退回顺序执行时设 LABOS_EXEC_GRAPH=0。
            boolean graphEnabled = !"0".equals(System.getenv("LABOS_EXEC_GRAPH"));
            String xml = graphEnabled ? loadGraphXml(workflowId) : null;
            boolean viaGraph = false;
            if (xml != null && !xml.isBlank()) {
                try {
                    WorkflowGraphParser.Graph g = WorkflowGraphParser.parse(xml);
                    GraphRunner gr = new GraphRunner(sm, capPool, protocolCap, capByKey,
                            nodeIdByKey, s -> { })
                            .withBoundInstances(loadBoundInstances())
                            .withSkipKeys(skipKeys)
                            // 条件表达式/循环退出条件的取值来源（run.input.vars）
                            .withVariables(loadRunVars(runId))
                            // 节点输出 → 变量（循环靠它走够次数退出）
                            .withOutputReader(key -> loadNodeOutputs(runId, key))
                            // 圈数上限：流程可以在 attr.maxIterations 里自己定（默认 10）
                            .withMaxVisits(loadMaxIterations(workflowId))
                            // 循环每圈落一行（loop_index=1..N），汇总行仍是 loop_index=0
                            .withRoundRecorder((key, round, st, ec, s1, e1) ->
                                    recordNodeRound(runId, key, round, st, ec, s1, e1))
                            // 变量/表达式节点的参数（不发给设备）
                            .withBuiltinParams(builtinParams)

                            // P1-8：每到一个节点边界查一次 DB —— 状态是 paused 就停。
        // 只查单行主键，开销可忽略；不查的话暂停按钮等于没用。
        .withPauseChecker(() -> isRunPaused(runId));
                    // 注意：**不能**按执行结果决定是否退回。
                    // 退回意味着重跑，而在真实设备上重跑 = 开两次盖（物理副作用不可撤销）。
                    // 这里只在解析阶段失败时退回（见 catch），执行了就是执行了。
                    progress = gr.run(g, executor);
                    viaGraph = true;
                } catch (RuntimeException ex) {
                    System.err.println("[WARN] 按图执行失败，退回顺序执行: " + ex.getMessage());
                    progress = sm.executeAll(rec, executor);
                }
            } else {
                progress = sm.executeAll(rec, executor);
            }
            pathHolder[0] = viaGraph ? "graph" : "sequential";
            store.flush();
            eventsWritten = store.written();
        }

        RunMonitor.INSTANCE.finish(runId);

        Map<Long, Long> deviceToResource = loadDeviceResourceMap();
        Set<Long> validResources = loadResourceIds();

        // 外壳已在执行前建好，这里只回填最终状态（子步骤由 sink 实时写）
        updateNodeResults(runId, progress, rec, deviceToResource, validResources);
        // 分支未选中的节点：整单跑完之后还挂着 running 的，标成 BYPASSED。
        // 不标的话它们会一直是 running（界面借"没在跑⇒未执行"的规则显示），
        // 看不出"这条分支压根没走"和"还没轮到"的区别。
        markBypassedNodes(runId, progress);
        settleRun(runId, progress);
        // 运行中可能被切过模式，以结束时的库值为准
        // 板位状态：最后停在哪个设备上，就记它在"有板"
        markPlatePosition(runId, progress);

        recordOccurrences(runId, progress, repo, deviceToResource, validResources,
                liveAttendance(runId, mode));

        // ---- 告警聚合：把本次执行的失败信号过一遍静默期聚合 ----
        List<Alert> alerts = collectAlerts(progress, Instant.now());
        long notifyCount = alerts.stream().filter(Alert::shouldNotify).count();

        List<NodeResult> results = new ArrayList<>();
        for (NodeProgress np : progress.all()) {
            RecoverableNode def = rec.stream()
                    .filter(r -> r.id() == np.nodeId()).findFirst().orElse(null);
            String raw = driverExec == null ? null
                    : driverExec.rawResponse(np.nodeId()).orElse(null);
            results.add(new NodeResult(
                    np.nodeId(),
                    def == null ? "?" : def.name(),
                    def == null ? "?" : def.capabilityCode(),
                    def == null ? null : def.method(),
                    np.currentResourceId(),
                    np.status().name(),
                    np.attempt(),
                    raw));
        }

        return new Execution(runId, wf.name(), rec.size(),
                progress.count(NodeStatus.COMPLETED),
                progress.count(NodeStatus.MANUAL),
                progress.count(NodeStatus.ABORTED),
                viaDriver, eventsWritten, results,
                alerts, (int) notifyCount, mode);
    }

    public record Execution(long runId, String workflowName, int total,
                            int completed, int manual, int aborted,
                            boolean viaDriver, int eventsWritten,
                            List<NodeResult> nodes,
                            List<Alert> alerts, int notifyCount,
                            String attendance) {
    }

    public record NodeResult(long id, String name, String capability, String method,
                             Long resourceId, String status, int attempt, String rawResponse) {
    }

    // ---------------------------------------------------------------- 执行器装配

    /** 探测本机模拟驱动；不可用返回 null（调用方退回 SimulatedBus）。 */
    /** 构建驱动注册表（影子探针也用它）。 */
    /** 缓存的驱动注册表（长连接必须复用，否则每次新建连接等于没长连接）。 */
    private DriverRegistry cachedRegistry;
    private long registryBuiltAt;

    /** 缓存有效期：60 秒后重建，让设备能力变更能生效，又不至于每次请求都重连。 */
    private static final long REGISTRY_TTL_MS = 60_000L;

    public DriverRegistry buildDriverRegistry() throws SQLException {
        // **两种模式都要缓存**。
        // 原来只在长连接模式缓存，短连接每次都重建 7 个 adapter + 查一遍驱动表，
        // 实测吃掉 4.7 秒（命令本身只要 312ms）——压测时才暴露。
        if (cachedRegistry != null
                && System.currentTimeMillis() - registryBuiltAt < REGISTRY_TTL_MS) {
            return cachedRegistry;
        }

        // **从库里读所有 active 驱动，逐个注册**。
        // 这样"每台设备一个模拟器"才成立：每个模拟器一条 driver 记录、一个端点。
        // 找不到任何配置时才回退到单端点的老路径（兼容单机演示）。
        DriverRegistry reg = new DriverRegistry();
        int registered = registerAllDriversFromDb(reg);

        if (registered == 0) {
            if (!probe("127.0.0.1", driverPort)) {
                return null;
            }
            Map<Long, Set<String>> devCaps = loadDeviceCapabilities();
            if (devCaps.isEmpty()) {
                return null;
            }
            Set<String> caps = new LinkedHashSet<>();
            devCaps.values().forEach(caps::addAll);
            DriverDescriptor desc = new DriverDescriptor(
                    "DRV-LOCAL-MOCK", "tcp://127.0.0.1:" + driverPort, "tcp", "mock",
                    caps, devCaps, devCaps.keySet(), Map.of("simulated", "true"));
            reg.register(buildAdapter(desc));
            System.out.println("[驱动] 单端点回退模式：tcp://127.0.0.1:" + driverPort);
        }

        cachedRegistry = reg;
        registryBuiltAt = System.currentTimeMillis();
        return reg;
    }

    /**
     * 按库里的 driver 表逐个注册。
     *
     * <p>每个 driver 只认自己名下的设备——这是"每台设备一个模拟器"的关键：
     * 中控发命令给设备 A 时，必须走 A 那个端点，不能走 B 的。
     *
     * @return 注册了几个驱动；0 表示库里没有可用配置
     */
    private int registerAllDriversFromDb(DriverRegistry reg) throws SQLException {
        Map<Long, Map<Long, Set<String>>> byDriver = new LinkedHashMap<>();
        Map<Long, String[]> meta = new LinkedHashMap<>();

        // device_model：设备实例绑定的**型号库 id**（如 s100 / aspin-2k）。
        // 放 device.attr->>'device_model'，因为同一台 device（型号定义）可能
        // 对应多台实例，而"用哪套错误码"是实例级的事实。
        // 没配就留空 —— 宁可不翻译，也不要猜错型号。
        Map<Long, Map<Long, String>> modelByDriver = new LinkedHashMap<>();
        String sql = """
                SELECT d.id AS driver_id, d.code, d.endpoint, d.protocol::text AS protocol,
                       di.id AS instance_id, c.code AS cap,
                       dt.attr->>'device_model' AS device_model
                FROM driver d
                JOIN device_instance di ON di.driver_id = d.id AND di.deleted_at IS NULL
                LEFT JOIN device dt ON dt.id = di.device_id
                LEFT JOIN resource_capability rc ON rc.resource_id = di.resource_id
                LEFT JOIN capability c ON c.id = rc.capability_id
                WHERE d.tenant_id = ? AND d.status = 1
                  AND d.endpoint IS NOT NULL AND d.endpoint <> ''
                ORDER BY d.id, di.id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long did = rs.getLong("driver_id");
                    meta.putIfAbsent(did, new String[]{
                            rs.getString("code"), rs.getString("endpoint"),
                            rs.getString("protocol")});
                    long inst = rs.getLong("instance_id");
                    Map<Long, Set<String>> devs =
                            byDriver.computeIfAbsent(did, k -> new LinkedHashMap<>());
                    Set<String> caps = devs.computeIfAbsent(inst, k -> new LinkedHashSet<>());
                    String cap = rs.getString("cap");
                    if (cap != null) {
                        caps.add(cap);
                    }
                    String modelId = rs.getString("device_model");
                    if (modelId != null && !modelId.isBlank()) {
                        modelByDriver.computeIfAbsent(did, k -> new LinkedHashMap<>())
                                .put(inst, modelId.trim());
                    }
                }
            }
        }

        int ok = 0;
        for (Map.Entry<Long, Map<Long, Set<String>>> e : byDriver.entrySet()) {
            String[] m = meta.get(e.getKey());
            String endpoint = m[1];
            String hostPort = endpoint.startsWith("tcp://")
                    ? endpoint.substring(6) : endpoint;
            int idx = hostPort.lastIndexOf(':');
            String host = idx > 0 ? hostPort.substring(0, idx) : hostPort;
            int port = idx > 0 ? Integer.parseInt(hostPort.substring(idx + 1)) : 8501;

            Map<Long, Set<String>> devs = e.getValue();
            Set<String> allCaps = new LinkedHashSet<>();
            devs.values().forEach(allCaps::addAll);

            // 没有能力的驱动注册不了（DriverDescriptor 会拒绝），
            // 但这不该让整个注册过程中断——跳过它，继续注册后面的。
            if (allCaps.isEmpty()) {
                System.err.println("[驱动] " + m[0] + " 跳过：名下设备都没有声明能力"
                        + "（查 resource_capability）");
                continue;
            }

            DriverDescriptor desc = new DriverDescriptor(
                    m[0], endpoint, m[2], "remote",
                    allCaps, devs, devs.keySet(), Map.of());
            try {
                Map<Long, String> bindings = modelByDriver.getOrDefault(e.getKey(), Map.of());
                reg.register(buildAdapter(desc, host, port, buildErrorMapper(bindings)));
                ok++;
                System.out.println("[驱动] " + m[0] + " → " + endpoint
                        + "（" + devs.size() + " 台设备"
                        + (bindings.isEmpty() ? "，未绑定型号" : "，已绑定型号 " + bindings)
                        + "）");
            } catch (RuntimeException ex) {
                System.err.println("[驱动] " + m[0] + " 注册失败：" + ex.getMessage());
            }
        }
        return ok;
    }

    /** 按模式选短连接或长连接。 */
    private com.labos.kernel.driver.DeviceDriver buildAdapter(DriverDescriptor desc) {
        return buildAdapter(desc, "127.0.0.1", driverPort,
                com.labos.kernel.device.DeviceErrorMapper.fromEnv());
    }

    private com.labos.kernel.driver.DeviceDriver buildAdapter(
            DriverDescriptor desc, String host, int port) {
        return buildAdapter(desc, host, port,
                com.labos.kernel.device.DeviceErrorMapper.fromEnv());
    }

    /**
     * 装配错误码翻译器：**DB 绑定优先，环境变量补位**。
     *
     * <p>DB 里 {@code device.attr.device_model} 是生产口径；
     * 环境变量 {@code LABOS_DEVICE_MODELS} 用于现场联调（还没写库时也能试）。
     * 两者都没有 → 不翻译（设备码原样透传，行为与 1.0 一致）。
     */
    private com.labos.kernel.device.DeviceErrorMapper buildErrorMapper(
            Map<Long, String> dbBindings) {
        com.labos.kernel.device.DeviceErrorMapper env =
                com.labos.kernel.device.DeviceErrorMapper.fromEnv();
        Map<Long, String> merged = new LinkedHashMap<>(env.bindings());
        merged.putAll(dbBindings);   // DB 覆盖 env
        String def = System.getenv("LABOS_DEFAULT_DEVICE_MODEL");
        return new com.labos.kernel.device.DeviceErrorMapper(merged, def);
    }

    private com.labos.kernel.driver.DeviceDriver buildAdapter(
            DriverDescriptor desc, String host, int port,
            com.labos.kernel.device.DeviceErrorMapper errorMapper) {
        if ("persistent".equalsIgnoreCase(System.getenv("LABOS_DRIVER_MODE"))) {
            return new com.labos.kernel.driver.tcp.TcpPersistentDriver(desc, errorMapper);
        }
        return new TcpDriverAdapter(desc, host, port, Map.of(), errorMapper);
    }

    private static boolean probe(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Map<Long, Set<String>> loadDeviceCapabilities() throws SQLException {
        Map<Long, Set<String>> out = new LinkedHashMap<>();
        String sql = """
                SELECT di.id, c.code
                FROM device_instance di
                JOIN resource_capability rc ON rc.resource_id = di.resource_id
                JOIN capability c ON c.id = rc.capability_id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>())
                        .add(rs.getString(2));
            }
        }
        return out;
    }

    /** 把事件同时送给多个监听器（落库 + 内存监控）。 */
    private static ExecutionListener tee(List<ExecutionEvent> sink, ExecutionListener... next) {
        return e -> {
            sink.add(e);
            for (ExecutionListener l : next) {
                l.onEvent(e);
            }
        };
    }

    // ---------------------------------------------------------------- 落库

    /**
     * 从 {@code workflow_node.params->'time_constraint'} 解析时间约束。
     *
     * <pre>
     * {"time_constraint": {"after_node_key": "Activity_x", "min_sec": 0, "max_sec": 1800}}
     * </pre>
     *
     * <p>没有约束的节点不进 Map，避免热路径做无意义的查表。
     */
    private Map<Long, TimeConstraintExecutor.Constraint> loadTimeConstraints(long workflowId)
            throws SQLException {
        Map<String, Long> idByKey = loadNodeIdByKey(workflowId);
        Map<Long, TimeConstraintExecutor.Constraint> out = new LinkedHashMap<>();

        String sql = """
                SELECT node_key,
                       params->'time_constraint'->>'after_node_key' AS after_key,
                       COALESCE((params->'time_constraint'->>'min_sec')::int, 0) AS min_sec,
                       COALESCE((params->'time_constraint'->>'max_sec')::int, 0) AS max_sec
                FROM workflow_node
                WHERE tenant_id = ? AND workflow_id = ? AND node_type = 'task'
                  AND params->'time_constraint' IS NOT NULL
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long selfId = idByKey.get(rs.getString("node_key"));
                    Long afterId = idByKey.get(rs.getString("after_key"));
                    int minSec = rs.getInt("min_sec");
                    int maxSec = rs.getInt("max_sec");
                    if (selfId == null || afterId == null || (minSec <= 0 && maxSec <= 0)) {
                        continue;
                    }
                    out.put(selfId, new TimeConstraintExecutor.Constraint(afterId, minSec, maxSec));
                }
            }
        }
        return out;
    }

    /**
     * 读节点的设备方法参数：{@code workflow_node.params->'method_params'}。
     *
     * <p>格式：{@code {"plateID":"PL001","cycles":3}}，直接透传给驱动。
     */
    private Map<Long, Map<String, Object>> loadMethodParams(long workflowId,
                                                            Map<String, Long> idByKey)
            throws SQLException {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        String sql = """
                SELECT node_key, params->'method_params' AS mp
                FROM workflow_node
                WHERE tenant_id = ? AND workflow_id = ? AND node_type = 'task'
                  AND params->'method_params' IS NOT NULL
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long id = idByKey.get(rs.getString("node_key"));
                    String json = rs.getString("mp");
                    if (id == null || json == null || json.isBlank()) {
                        continue;
                    }
                    Map<String, Object> raw = parseFlatJson(json);
                    // 变量替换：{"plateID":"${plate}"} → {"plateID":"PL001"}
                    // 替换后如果是纯数字，转成数字——驱动端对类型敏感，
                    // "7" 和 7 在某些协议里不是一回事。
                    Map<String, Object> replaced = new LinkedHashMap<>();
                    raw.forEach((k, v) -> {
                        if (!(v instanceof String sv)) {
                            replaced.put(k, v);
                            return;
                        }
                        String rv = substVars(sv);
                        if (!rv.equals(sv) && rv.matches("-?\\d+")) {
                            replaced.put(k, Long.parseLong(rv));
                        } else {
                            replaced.put(k, rv);
                        }
                    });
                    out.put(id, replaced);
                }
            }
        }
        return out;
    }

    /** 极简扁平 JSON 解析：{"k":"v","n":1,"b":true}。够传参用，不引第三方库。 */
    private static Map<String, Object> parseFlatJson(String json) {
        Map<String, Object> m = new LinkedHashMap<>();
        String body = json.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String pair : body.split(",")) {
            int idx = pair.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String k = pair.substring(0, idx).trim().replaceAll("^\"|\"$", "");
            String v = pair.substring(idx + 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                m.put(k, v.substring(1, v.length() - 1));
            } else if ("true".equals(v) || "false".equals(v)) {
                m.put(k, Boolean.parseBoolean(v));
            } else {
                try {
                    m.put(k, Long.parseLong(v));
                } catch (NumberFormatException e) {
                    m.put(k, v);
                }
            }
        }
        return m;
    }

    /** workflow_id -> (node_key -> workflow_node.id)：让图执行的节点 id 与库一致。 */
    private Map<String, Long> loadNodeIdByKey(long workflowId) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT node_key, id FROM workflow_node
                     WHERE tenant_id = ? AND workflow_id = ? AND node_type = 'task'
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

    /** workflow_id -> (node_key -> capability_code)。 */
    /**
     * 编排里绑定的资源 → 设备实例（resource_node.id → device_instance.id）。
     *
     * <p>流程编辑器把"拖到节点上的设备"存成 {@code bound_resource_id}（资源 id），
     * 而驱动注册表按 {@code device_instance.id} 认设备。不翻译这一层，
     * 节点就只能按能力轮转选设备 —— 同能力两台时会跑到另一台上去。
     */
    /**
     * 运行期变量：{@code run.input.vars}（实验启动时把 vars 写进 input）。
     *
     * <p>没有就把 map 留空 —— 条件表达式里引用不存在的变量会走"空串"，
     * 比"抛异常让整单挂掉"友好；真需要强校验的场合在 preflight 里做。
     */
    /**
     * 某个节点最新一次执行的**返回值**，摊平成变量表。
     *
     * <p>例：设备返回 {@code {"plateType":3}} → 变量 {@code plateType=3}，
     * 条件里可以写 {@code ${plateType} < 9} 决定要不要再跑一圈。
     *
     * <p>只取顶层标量（字符串/数字/布尔）；嵌套对象转成 JSON 字符串，
     * 够条件表达式做相等/大小比较了。
     */
    private Map<String, String> loadNodeOutputs(long runId, String nodeKey) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT cmd.result::text AS result,
                            cmd.device_raw_response::text AS raw
                       FROM run_command cmd
                       JOIN run_node n ON n.id = cmd.run_node_id
                       -- 命令挂在**步骤节点**（level=1，node_key=S1）上，
                       -- 业务节点（level=0，node_key=GET）是它的 parent。
                       -- 只按 n.node_key 匹配永远查不到（2026-11-03 踩的）。
                       LEFT JOIN run_node p ON p.id = n.parent_node_id
                      WHERE cmd.tenant_id = ? AND cmd.run_id = ?
                        AND (n.node_key = ? OR p.node_key = ?)
                      ORDER BY cmd.id DESC
                      LIMIT 1
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            ps.setString(3, nodeKey);
            ps.setString(4, nodeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return out;
                }
                // **优先取设备原始回包里的 data**：run_command.result 只有
                // {ok,durationMs}，设备真正返回的业务字段（plateType 等）在
                // device_raw_response 的 data 里。不读它，节点输出驱动条件等于空谈。
                String payload = rs.getString("raw");
                if (payload == null || payload.isBlank()) {
                    payload = rs.getString("result");
                }
                if (payload == null || payload.isBlank() || "null".equals(payload)) {
                    return out;
                }
                // 存的是 JSON 字符串（转义过），先去转义再抽字段
                String raw = payload.replace("\\\"", "\"");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*(\"[^\"]*\"|-?[0-9.]+|true|false)")
                        .matcher(raw);
                while (m.find()) {
                    String v = m.group(2);
                    if (v.startsWith("\"")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    out.put(m.group(1), v);
                }
            }
        } catch (SQLException e) {
            System.err.println("[WARN] 读取节点输出失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 流程自己声明的循环圈数上限（{@code workflow.attr.maxIterations}，默认 10）。
     *
     * <p>放流程维度而不是节点维度：一个流程里的回边通常不止一条，
     * 按节点配会让运维记不清"这条到底受谁管"。
     */
    private int loadMaxIterations(long workflowId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT attr->>'maxIterations' AS n FROM workflow WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null && !v.isBlank()) {
                        return Integer.parseInt(v.trim());
                    }
                }
            }
        } catch (SQLException | NumberFormatException e) {
            System.err.println("[WARN] 读取 maxIterations 失败，用默认 10: " + e.getMessage());
        }
        return 10;
    }

    private Map<String, String> loadRunVars(long runId) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT input->'vars' AS vars FROM run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return out;
                }
                String raw = rs.getString("vars");
                if (raw == null || raw.isBlank() || "null".equals(raw)) {
                    return out;
                }
                // {"temp":"37","plate":"P1"} —— 值统一按字符串处理，
                // 数字比较交给 ConditionEvaluator 判断
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"([^\"]+)\"\\s*:\\s*\"?([^\",}]*)\"?")
                        .matcher(raw);
                while (m.find()) {
                    out.put(m.group(1), m.group(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("[WARN] 读取 run 变量失败（条件表达式会按空值处理）: " + e.getMessage());
        }
        return out;
    }

    private Map<Long, Long> loadBoundInstances() throws SQLException {
        Map<Long, Long> out = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT di.resource_id, di.id
                     FROM device_instance di
                     WHERE di.tenant_id = ? AND di.deleted_at IS NULL AND di.resource_id IS NOT NULL
                     """)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

    private Map<String, String> loadCapabilityByKey(long workflowId) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT n.node_key, cap.code
                     FROM workflow_node n
                     JOIN capability cap ON cap.id = n.capability_id
                     WHERE n.tenant_id = ? AND n.workflow_id = ? AND n.node_type = 'task'
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    private String loadGraphXml(long workflowId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT graph->>'xml' FROM workflow WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 依赖缓存：execute 里会多次用到，避免重复查库。 */
    private Map<Long, Long> deviceToResourceOf() throws SQLException {
        return loadDeviceResourceMap();
    }

    private Set<Long> validResourcesOf() throws SQLException {
        return loadResourceIds();
    }

    /**
     * 预创建业务节点的 run_node 外壳（status=running）。
     *
     * <p>必须提前建：命令级落库时子步骤要引用父节点 id。
     * 返回 nodeId -> run_node.id。
     */
    private Map<Long, Long> createNodeShells(long runId, List<RecoverableNode> defs,
                                             Map<Long, Long> deviceToResource,
                                             Set<Long> validResources) throws SQLException {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (defs.isEmpty()) {
            return out;
        }
        // **同一个 run 上可以重复调本方法**（续跑/恢复重放会再来一次）。
        // 已有节点直接复用它的 id，别再插一遍 —— 否则撞
        // uk_run_node (run_id, node_key, attempt)=(205351, N1, 0)，
        // 表现是"点继续 → run 变 failed，什么都没跑"。
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, node_key FROM run_node
                     WHERE tenant_id = ? AND run_id = ? AND level = 0
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Long> byKey = new LinkedHashMap<>();
                while (rs.next()) {
                    byKey.put(rs.getString("node_key"), rs.getLong("id"));
                }
                for (RecoverableNode d : defs) {
                    Long existing = byKey.get(d.key());
                    if (existing != null) {
                        out.put(d.id(), existing);
                    }
                }
            }
        }
        String sql = """
                INSERT INTO run_node
                  (tenant_id, run_id, node_key, name, seq, node_type, resource_id,
                   status, attempt, actual_start, estimate_sec, input)
                VALUES (?, ?, ?, ?, ?, 'task', ?, 'running', 0, now(), 0, ?::jsonb)
                RETURNING id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int seq = 0;
            for (RecoverableNode d : defs) {
                if (out.containsKey(d.id())) {
                    seq++;
                    continue;   // 已有节点（续跑），不重复插
                }
                Long rid = resolveResource(d.initialResourceId(), deviceToResource, validResources);
                ps.setLong(1, tenantId);
                ps.setLong(2, runId);
                ps.setString(3, d.key());
                ps.setString(4, d.name());
                ps.setInt(5, ++seq);
                if (rid == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, rid);
                }
                ps.setString(7, Jsons.obj("capability", d.capabilityCode(),
                        "method", String.valueOf(d.method())));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        out.put(d.id(), rs.getLong(1));
                    }
                }
            }
        }
        return out;
    }

    /** 执行结束后回填父节点最终状态（不重复插入）。 */
    /**
     * 把「分支未选中」的节点标成 {@code bypassed}。
     *
     * <p>判定：整单**成功**跑完，但某些业务节点还是 {@code running} 外壳 ——
     * 说明异或网关走了另一条路，这些节点永远轮不到。
     *
     * <p>只在成功结束时标：失败/暂停的单子里 running 可能是"待续跑"，
     * 标成 bypassed 会让续跑逻辑（按 completed 跳、其余重做）误判。
     */
    private void markBypassedNodes(long runId, RunProgress progress) throws SQLException {
        // NodeStatus 里没有 FAILED（失败最终都归到 MANUAL/ABORTED），别照抄 run_node 的状态名。
        // SKIPPED 也必须排除：跳过会让 settleRun 把整单落成 paused/waiting_human，
        // 这种单子的下游节点是"还没轮到（pending）"，不是"分支没选中（bypassed）"。
        // 漏排会把"等人处理"画成"这条路不用走"（2026-11-03 评审 H2）。
        if (progress == null
                || progress.count(NodeStatus.MANUAL) > 0
                || progress.count(NodeStatus.ABORTED) > 0
                || progress.count(NodeStatus.SKIPPED) > 0
                || progress.aborted()) {
            return;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE run_node
                        SET status = 'bypassed'::node_status,
                            actual_end = now(),
                            input = COALESCE(input, '{}'::jsonb)
                                    || '{"reason":"分支未选中（异或网关走了另一条）"}'::jsonb
                      WHERE tenant_id = ? AND run_id = ? AND level = 0
                        AND status = 'running'
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            int n = ps.executeUpdate();
            if (n > 0) {
                System.out.println("[执行] run " + runId + " 有 " + n + " 个节点分支未选中（bypassed）");
            }
        }
    }

    private void updateNodeResults(long runId, RunProgress p, List<RecoverableNode> defs,
                                   Map<Long, Long> deviceToResource,
                                   Set<Long> validResources) throws SQLException {
        Map<Long, RecoverableNode> byId = new java.util.HashMap<>();
        for (RecoverableNode n : defs) {
            byId.put(n.id(), n);
        }
        // error_code 单独落一列（原来是塞进 input.reason 的字符串里）：
        //   · 前端要显示得用正则从"策略[EC-08-400C@NODE] 指定直接转人工"里抠；
        //   · SQL 没法统计"哪台设备最常报无纸"。
        // 列本来就存在（varchar(64)），只是一直没人写。
        String sql = """
                UPDATE run_node
                SET status = ?::node_status, attempt = ?, resource_id = ?, error_code = ?,
                    actual_end = now(), input = COALESCE(input, '{}'::jsonb) || ?::jsonb
                WHERE tenant_id = ? AND run_id = ? AND node_key = ? AND level = 0
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (NodeProgress np : p.all()) {
                RecoverableNode d = byId.get(np.nodeId());
                if (d == null) {
                    continue;
                }
                Long rid = resolveResource(np.currentResourceId(), deviceToResource, validResources);
                ps.setString(1, np.status().name().toLowerCase());
                ps.setInt(2, np.attempt());
                if (rid == null) {
                    ps.setNull(3, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(3, rid);
                }
                String err = np.lastErrorCode();
                if (err == null || err.isBlank()) {
                    ps.setNull(4, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(4, err.length() > 64 ? err.substring(0, 64) : err);
                }
                ps.setString(5, Jsons.obj("reason", np.lastReason(),
                        "errorCode", np.lastErrorCode()));
                ps.setLong(6, tenantId);
                ps.setLong(7, runId);
                ps.setString(8, d.key());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 按能力从驱动注册表取候选设备（返回 device_instance.id 口径）。 */
    private static Map<String, List<Long>> registryPool(DriverRegistry reg,
                                                        java.util.Collection<String> caps) {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (String cap : new java.util.LinkedHashSet<>(caps)) {
            if (cap == null || cap.isBlank()) {
                continue;
            }
            out.put(cap, reg.candidates(cap, Set.of()));
        }
        return out;
    }

    /**
     * 设备实例 id -> resource_node.id，越界/不存在则置空（外键必须合法）。
     *
     * <p>映射表可能是空的（构建时机不同），映射不到时**查一次库**兜底——
     * 否则会把 device_instance.id（如 171）原样当成 resource_node.id 写库，
     * 外键直接失败，run_node.resource_id 全是空。
     */
    private Long resolveResource(Long deviceId, Map<Long, Long> deviceToResource,
                                 Set<Long> validResources) {
        if (deviceId == null) {
            return null;
        }
        Long mapped = deviceToResource.get(deviceId);
        if (mapped == null) {
            if (validResources.contains(deviceId)) {
                return deviceId;   // 本来就是 resource_node.id
            }
            mapped = lookupResourceId(deviceId);   // 按 device_instance.id 查库
        }
        return mapped != null && validResources.contains(mapped) ? mapped : null;
    }

    /** device_instance.id -> resource_node.id（查库）。 */
    private Long lookupResourceId(long instanceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT resource_id FROM device_instance WHERE id = ?")) {
            ps.setLong(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (SQLException ignore) {
            // 查不到就返回 null，调用方会写 NULL（不写坏数据）
        }
        return null;
    }

    /**
     * 记录循环的第 N 圈。
     *
     * <p>{@code run_node} 每个业务节点只有一行（唯一键按 node_key），
     * 循环跑 5 圈也只有最后一圈的最终状态。这里按圈补一行：
     * {@code loop_index=0} 仍是**汇总行**（最终状态，所有既有查询都只看它），
     * {@code loop_index=1..N} 是每一圈的实际执行。
     *
     * <p>为什么不改汇总行：一改就要动"节点数 / 90% 报表 / 流程图着色"一堆查询。
     * 加行是纯增量，老查询加一条 {@code loop_index = 0} 就等价于改造前。
     */
    private void recordNodeRound(long runId, String nodeKey, int round, NodeStatus status,
                                 String errorCode, java.time.Instant start, java.time.Instant end) {
        if (round < 1) {
            return;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO run_node
                       (tenant_id, run_id, node_key, name, seq, node_type, status,
                        attempt, actual_start, actual_end, input, error_code,
                        level, loop_index, estimate_sec, progress)
                     SELECT tenant_id, run_id, node_key, name, seq, node_type,
                            ?::node_status, attempt, ?, ?, ?::jsonb, ?,
                            0, ?, estimate_sec, 100
                       FROM run_node
                      WHERE tenant_id = ? AND run_id = ? AND node_key = ?
                        AND level = 0 AND loop_index = 0
                     """)) {
            ps.setString(1, status.name().toLowerCase());
            ps.setTimestamp(2, java.sql.Timestamp.from(start));
            ps.setTimestamp(3, java.sql.Timestamp.from(end));
            ps.setString(4, Jsons.obj("reason", "循环第 " + round + " 圈",
                    "loopIndex", round));
            if (errorCode == null || errorCode.isBlank()) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, errorCode.length() > 64 ? errorCode.substring(0, 64) : errorCode);
            }
            ps.setInt(6, round);
            ps.setLong(7, tenantId);
            ps.setLong(8, runId);
            ps.setString(9, nodeKey);
            int n = ps.executeUpdate();
            if (n == 0) {
                System.err.println("[WARN] 记循环圈次失败（找不到汇总行）: " + nodeKey + " #" + round);
            }
        } catch (SQLException e) {
            System.err.println("[WARN] 记循环圈次失败: " + e.getMessage());
        }
    }

    /**
     * 本 run 已有的最大步骤序号（node_key = "S<数字>"）。
     *
     * <p>续跑时用它给新步骤接着编号，避免与"已完成的旧步骤"撞
     * uk_run_node(run_id, node_key, attempt)。解析不出来（老数据不是 S+n）就当 0。
     */
    private int maxStepSeq(long runId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COALESCE(MAX(NULLIF(regexp_replace(node_key, '\\D', '', 'g'), '')::int), 0)
                       FROM run_node
                      WHERE tenant_id = ? AND run_id = ? AND level = 1
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            System.err.println("[WARN] 读取最大步骤序号失败，从 0 开始: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 会落库的 StepSink：每步结束立即写子 run_node + run_command。
     *
     * <p>这是"崩溃后能查到执行到哪一步"的唯一办法。
     */
    final class PersistingStepSink implements StepSink {
        private final long runId;
        private final Map<Long, Long> nodeIdMap;
        private final Map<Long, Long> deviceToResource;
        private final Set<Long> validResources;
        private final StepCollector mirror;
        /**
         * 步骤序号。**必须从库里已有的最大值续编**，不能每次从 0 开始。
         *
         * <p>步骤行的 node_key 是 "S"+seq，唯一键是 (run_id, node_key, attempt)，
         * 与业务节点无关。续跑时若从头编号，新步骤会撞上"已完成节点留下的 S1/S2"，
         * 异常被 catch 成一条 WARN，于是这一段执行在库里根本没有记录。
         */
        private final java.util.concurrent.atomic.AtomicInteger seq;
        /** "nodeId:step:attempt" -> 执行设备（onStepDone 拿不到，只能在 start 记）。 */
        private final Map<String, Long> resourceByStep = new java.util.concurrent.ConcurrentHashMap<>();

        /** nodeId -> 设备方法参数（落库时一起记，便于复盘"当时传了什么"）。 */
        private final Map<Long, Map<String, Object>> paramsByNode;
        /** 板位登记：Deposit/Withdraw 类方法会动位置。 */
        private final com.labos.kernel.driver.PositionLock plateLock;

        PersistingStepSink(long runId, Map<Long, Long> nodeIdMap,
                           Map<Long, Long> deviceToResource, Set<Long> validResources,
                           StepCollector mirror,
                           Map<Long, Map<String, Object>> paramsByNode,
                           com.labos.kernel.driver.PositionLock plateLock) {
            this.seq = new java.util.concurrent.atomic.AtomicInteger(maxStepSeq(runId));
            this.runId = runId;
            this.nodeIdMap = nodeIdMap;
            this.deviceToResource = deviceToResource;
            this.validResources = validResources;
            this.mirror = mirror;
            this.paramsByNode = paramsByNode == null ? Map.of() : paramsByNode;
            this.plateLock = plateLock;
        }

        /** device_instance.id → resource_node.id（两套 ID 的转换，踩过很多次）。 */
        private Long resourceIdOfInstance(long instanceId) throws SQLException {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT resource_id FROM device_instance WHERE id = ?")) {
                ps.setLong(1, instanceId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        }

        /** 当前 run id（用于设备锁归属）。 */
        private long runIdOf() {
            return runId;
        }

        /** 设备进入"运行中"。失败不影响执行主链路。 */
        private void markDeviceBusy(long resourceId, String method) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         // 入参是 device_instance.id，device_state.resource_id 是 resource_node.id，
                         // 必须转换——直接比较会更新 0 行且不报错（这个坑踩了很多次）。
                         "UPDATE device_state SET status = 'running', current_method = ?,"
                                 + " updated_at = now()"
                                 + " WHERE tenant_id = ? AND resource_id ="
                                 + " (SELECT resource_id FROM device_instance WHERE id = ?)")) {
                ps.setString(1, method);
                ps.setLong(2, tenantId);
                ps.setLong(3, resourceId);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // 状态上报失败不能影响执行主链路
            }
        }

        /** 设备回到空闲（或错误）。 */
        private void markDeviceIdle(long resourceId, String lastError) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE device_state SET status = ?, current_method = NULL,"
                                 + " last_error = ?, updated_at = now()"
                                 + " WHERE tenant_id = ? AND resource_id ="
                                 + " (SELECT resource_id FROM device_instance WHERE id = ?)")) {
                ps.setString(1, lastError == null ? "idle" : "error");
                ps.setString(2, lastError);
                ps.setLong(3, tenantId);
                ps.setLong(4, resourceId);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // 同上
            }
        }

        @Override
        public void onStepStart(long nodeId, int stepSeq, String method,
                                long resourceId, int attempt) {
            resourceByStep.put(nodeId + ":" + stepSeq + ":" + attempt, resourceId);
            // 设备锁：抢不到就等（最多 60 秒）。
            // resourceId 这里是 device_instance.id，DeviceLock 要 resource_node.id，
            // 所以先用 instanceId 查出 resource_id。
            try {
                Long resNodeId = resourceIdOfInstance(resourceId);
                if (resNodeId != null) {
                    com.labos.kernel.driver.DeviceLock lock =
                            new com.labos.kernel.driver.DeviceLock(db, tenantId);
                    if (!lock.acquireWithWait(resNodeId, runIdOf(), 60_000)) {
                        throw new IllegalStateException(
                                "设备被占用超过 60 秒：" + resourceId);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("抢设备锁失败：" + e.getMessage(), e);
            }
            // 标记"正在执行"：前端设备卡片会变成黄灯 + 显示方法名
            markDeviceBusy(resourceId, method);
            if (mirror != null) {
                mirror.onStepStart(nodeId, stepSeq, method, resourceId, attempt);
            }
        }

        @Override
        public void onStepDone(long nodeId, int stepSeq, String method, boolean ok,
                               String errorCode, String rawResponse, long durationMs, int attempt) {
            // 清除"正在执行"：成功回 idle，失败回 error
            Long rid = resourceByStep.get(nodeId + ":" + stepSeq + ":" + attempt);
            if (rid != null && rid > 0) {
                markDeviceIdle(rid, ok ? null : errorCode);
                // 释放设备锁：只释放自己占的（DeviceLock 内部判断 current_run_id）
                try {
                    Long resNodeId = resourceIdOfInstance(rid);
                    if (resNodeId != null) {
                        new com.labos.kernel.driver.DeviceLock(db, tenantId)
                                .release(resNodeId, runIdOf());
                    }
                } catch (SQLException ignore) {
                    // 释放失败不影响主链路，锁会被下次执行覆盖
                }
            }
            if (mirror != null) {
                mirror.onStepDone(nodeId, stepSeq, method, ok, errorCode, rawResponse,
                        durationMs, attempt);
            }
            Long parentId = nodeIdMap.get(nodeId);
            if (parentId == null) {
                return;
            }
            try {
                Long dev = resourceByStep.get(nodeId + ":" + stepSeq + ":" + attempt);
                writeStep(parentId, stepSeq, method, ok, errorCode, rawResponse,
                        durationMs, attempt, dev == null ? 0L : dev,
                        paramsByNode.getOrDefault(nodeId, Map.of()));
            } catch (SQLException e) {
                // 落库失败不能让执行中断：数据丢了比流程停了好
                System.err.println("[WARN] 步骤落库失败: " + e.getMessage());
            }
        }

        private void writeStep(Long parentId, int stepSeq, String method, boolean ok,
                               String errorCode, String rawResponse, long durationMs,
                               int attempt, long deviceId,
                               Map<String, Object> params) throws SQLException {
            // 板位登记：成功的 Deposit/Withdraw 类方法会动位置
            if (ok && deviceId > 0) {
                try {
                    String plateId = com.labos.kernel.driver.PositionLock
                            .resolvePlateId(params, rawResponse, runId);
                    Long moved = new com.labos.kernel.driver.PositionLock(db, tenantId)
                            .notePlateMove(deviceId, method, plateId, runId);
                    if (moved != null
                            && moved == com.labos.kernel.driver.PositionLock.NO_EMPTY_POSITION) {
                        // 该放板但设备下没有空层位：设备会显示有板、层位上没板，
                        // 后续取板就取不到。必须显式告警，不能静默。
                        System.err.println("[PLATE-WARN] " + method + " dev=" + deviceId
                                + " 没有空层位，板无处安放（板号 " + plateId + "）");
                    } else if (moved != null
                            && moved == com.labos.kernel.driver.PositionLock.NO_PLATE_TO_TAKE) {
                        System.err.println("[PLATE-WARN] " + method + " dev=" + deviceId
                                + " 没有有板的层位，取不到板");
                    } else {
                        System.err.println("[PLATE] " + method + " dev=" + deviceId
                                + " plate=" + plateId + " -> pos=" + moved);
                    }
                } catch (Exception pe) {
                    System.err.println("[PLATE-ERR] " + method + " dev=" + deviceId
                            + " : " + pe.getClass().getSimpleName() + " " + pe.getMessage());
                }
            }
            Long rid = resolveResource(deviceId <= 0 ? null : deviceId,
                    deviceToResource, validResources);
            try (Connection c = db.open()) {
                long stepNodeId;
                // error_code 也写进步骤节点：原来只有 run_command 有，
                // 业务节点又被标成 manual（不是 failed），
                // 于是"第几步、因为什么码失败"只能去 run_command 表里翻。
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO run_node
                          (tenant_id, run_id, node_key, name, seq, node_type, resource_id,
                           status, attempt, level, parent_node_id, step_seq,
                           error_code, actual_start, actual_end, estimate_sec, input)
                        VALUES (?, ?, ?, ?, ?, 'task', ?, ?::node_status, ?, 1, ?, ?,
                                ?, now(), now(), 0, ?::jsonb)
                        RETURNING id
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, runId);
                    ps.setString(3, "S" + seq.incrementAndGet());
                    ps.setString(4, method);
                    ps.setInt(5, stepSeq);
                    if (rid == null) {
                        ps.setNull(6, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(6, rid);
                    }
                    ps.setString(7, ok ? "completed" : "failed");
                    ps.setInt(8, attempt);
                    ps.setLong(9, parentId);
                    ps.setInt(10, stepSeq);
                    if (errorCode == null || errorCode.isBlank()) {
                        ps.setNull(11, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(11, errorCode.length() > 64
                                ? errorCode.substring(0, 64) : errorCode);
                    }
                    ps.setString(12, Jsons.obj("durationMs", durationMs, "stepSeq", stepSeq));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        stepNodeId = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO run_command
                          (tenant_id, run_id, run_node_id, command_seq, method, payload,
                           status, attempt, sent_at, finished_at, result,
                           device_raw_response, error_code, device_id)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::node_status, ?, now(), now(),
                                ?::jsonb, to_jsonb(?::text), ?, ?)
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, runId);
                    ps.setLong(3, stepNodeId);
                    ps.setInt(4, stepSeq);
                    ps.setString(5, method);
                    // payload 记实际下发的参数，便于事后核对"当时传了什么"
                    java.util.List<Object> kv = new java.util.ArrayList<>();
                    kv.add("nodeId");
                    kv.add(parentId);
                    kv.add("stepSeq");
                    kv.add(stepSeq);
                    params.forEach((k, v) -> { kv.add(k); kv.add(v); });
                    ps.setString(6, Jsons.obj(kv.toArray()));
                    ps.setString(7, ok ? "completed" : "failed");
                    ps.setInt(8, attempt);
                    ps.setString(9, Jsons.obj("ok", ok, "durationMs", durationMs));
                    ps.setString(10, rawResponse);
                    // 参数序号：12 = device_id（9=result, 10=raw, 11=error_code, 12=device_id）
                    ps.setLong(12, deviceId);
                    ps.setString(11, errorCode);
                    ps.executeUpdate();
                }
            }
        }
    }

    private long createRun(WorkflowDef wf, String attendance) throws SQLException {
        // 加随机后缀：并发时同一毫秒会生成相同的 run_no，
        // 撞 uk_run_no 唯一约束（实测 4 并发有 2 个失败）。
        String runNo = "WF-" + LocalDateTime.now().format(RUN_FMT)
                + "-" + String.format("%04d", java.util.concurrent.ThreadLocalRandom
                        .current().nextInt(10000));
        String sql = """
                INSERT INTO run (tenant_id, run_no, run_type, workflow_id, mode, priority,
                                 status, attendance, actual_start, created_at)
                VALUES (?, ?, 'workflow', ?, 'normal', 5, 'running', ?, now(), now())
                RETURNING id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, runNo);
            ps.setLong(3, wf.id());
            ps.setString(4, attendance);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** device_instance.id -> resource_node.id（驱动按实例 id 路由，落库要存资源节点 id）。 */
    private Map<Long, Long> loadDeviceResourceMap() throws SQLException {
        Map<Long, Long> out = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, resource_id FROM device_instance WHERE resource_id IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong(1), rs.getLong(2));
            }
        }
        return out;
    }

    private Set<Long> loadResourceIds() throws SQLException {
        Set<Long> out = new LinkedHashSet<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM resource_node");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    /** 落父节点，返回 nodeId -> run_node.id 映射。 */
    private Map<Long, Long> persistNodes(long runId, RunProgress p, List<RecoverableNode> defs,
                                         Map<Long, Long> deviceToResource,
                                         Set<Long> validResources) throws SQLException {
        Map<Long, Long> out = new LinkedHashMap<>();
        Map<Long, RecoverableNode> byId = new LinkedHashMap<>();
        defs.forEach(d -> byId.put(d.id(), d));

        String sql = """
                INSERT INTO run_node
                  (tenant_id, run_id, node_key, name, seq, node_type, capability_id,
                   resource_id, status, attempt, actual_start, actual_end, estimate_sec, input)
                VALUES (?, ?, ?, ?, ?, 'task', NULL, ?, ?::node_status, ?, now(), now(), 0, ?::jsonb)
                RETURNING id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int seq = 0;
            for (NodeProgress np : p.all()) {
                RecoverableNode def = byId.get(np.nodeId());
                ps.setLong(1, tenantId);
                ps.setLong(2, runId);
                ps.setString(3, def == null ? ("N" + np.nodeId()) : def.key());
                ps.setString(4, def == null ? ("节点" + np.nodeId()) : def.name());
                ps.setInt(5, ++seq);
                Long rid = null;
                if (np.currentResourceId() != null) {
                    long mapped = deviceToResource.getOrDefault(
                            np.currentResourceId(), np.currentResourceId());
                    if (validResources.contains(mapped)) {
                        rid = mapped;
                    }
                }
                if (rid == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, rid);
                }
                ps.setString(7, np.status().name().toLowerCase());
                ps.setInt(8, np.attempt());
                ps.setString(9, Jsons.obj(
                        "capability", def == null ? "" : def.capabilityCode(),
                        "method", def == null ? "" : String.valueOf(def.method()),
                        "reason", np.lastReason()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        out.put(np.nodeId(), rs.getLong(1));
                    }
                }
            }
        }
        return out;
    }

    /** 读 run 当前的值守模式（运行中可被切换）。 */
    private String liveAttendance(long runId, String fallback) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT attendance FROM run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null && !v.isBlank()) {
                        return v;
                    }
                }
            }
        } catch (SQLException e) {
            // 读不到就用启动时的值
        }
        return fallback;
    }

    /** 落子步骤（level=1）与命令（1:1）。 */
    private void persistStepsAndCommands(long runId, StepCollector collector,
                                         Map<Long, Long> nodeIdMap,
                                         Map<Long, Long> deviceToResource,
                                         Set<Long> validResources) throws SQLException {
        if (collector.done.isEmpty()) {
            return;
        }
        String nodeSql = """
                INSERT INTO run_node
                  (tenant_id, run_id, node_key, name, seq, node_type, resource_id,
                   status, attempt, level, parent_node_id, step_seq,
                   actual_start, actual_end, estimate_sec, input)
                VALUES (?, ?, ?, ?, ?, 'task', ?, ?::node_status, ?, 1, ?, ?, now(), now(), 0, ?::jsonb)
                RETURNING id
                """;
        String cmdSql = """
                INSERT INTO run_command
                  (tenant_id, run_id, run_node_id, command_seq, method, payload,
                   status, attempt, sent_at, finished_at, result,
                   device_raw_response, error_code)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::node_status, 0, now(), now(), ?::jsonb,
                        to_jsonb(?::text), ?)
                """;
        try (Connection c = db.open();
             PreparedStatement nodePs = c.prepareStatement(nodeSql);
             PreparedStatement cmdPs = c.prepareStatement(cmdSql)) {
            int seq = 0;
            for (StepCollector.StepRec r : collector.done) {
                // node_key 必须全局唯一：节点重试会重复报同一 (nodeId, seq)，
                // 而 uk_run_node 是 (run_id, node_key, attempt)，不带 attempt 就冲突。
                String stepKey = "S" + (++seq);
                Long parentId = nodeIdMap.get(r.nodeId());
                if (parentId == null) {
                    continue;
                }
                Long rid = null;
                if (r.resourceId() > 0) {
                    long mapped = deviceToResource.getOrDefault(r.resourceId(), r.resourceId());
                    if (validResources.contains(mapped)) {
                        rid = mapped;
                    }
                }

                nodePs.setLong(1, tenantId);
                nodePs.setLong(2, runId);
                nodePs.setString(3, stepKey);
                nodePs.setString(4, r.method());
                nodePs.setInt(5, r.seq());
                if (rid == null) {
                    nodePs.setNull(6, java.sql.Types.BIGINT);
                } else {
                    nodePs.setLong(6, rid);
                }
                nodePs.setString(7, r.ok() ? "completed" : "failed");
                nodePs.setInt(8, r.attempt());
                nodePs.setLong(9, parentId);
                nodePs.setInt(10, r.seq());
                nodePs.setString(11, Jsons.obj("method", r.method(),
                        "durationMs", r.durationMs(), "attempt", r.attempt()));

                long stepNodeId;
                try (ResultSet rs = nodePs.executeQuery()) {
                    rs.next();
                    stepNodeId = rs.getLong(1);
                }

                cmdPs.setLong(1, tenantId);
                cmdPs.setLong(2, runId);
                cmdPs.setLong(3, stepNodeId);
                cmdPs.setInt(4, r.seq());
                cmdPs.setString(5, r.method());
                cmdPs.setString(6, Jsons.obj("nodeId", r.nodeId(), "seq", r.seq()));
                cmdPs.setString(7, r.ok() ? "completed" : "failed");
                cmdPs.setString(8, Jsons.obj("ok", r.ok(), "durationMs", r.durationMs()));
                cmdPs.setString(9, r.rawResponse());
                cmdPs.setString(10, r.errorCode());
                cmdPs.executeUpdate();
            }
        }
    }

    /** 收集子步骤执行结果。 */
    static final class StepCollector implements StepSink {
        record StepRec(long nodeId, int seq, String method, long resourceId, int attempt,
                       boolean ok, String errorCode, String rawResponse, long durationMs) {
        }

        final List<StepRec> done = new ArrayList<>();

        /**
         * "nodeId:seq:attempt" -> 实际执行设备。
         *
         * <p>onStepDone 的签名里没有 resourceId，只能在 onStepStart 时记下来。
         * 之前硬编码 0，导致子步骤 run_node.resource_id 恒为 NULL——
         * 甘特图"这条命令在哪台设备"就查不到。
         */
        private final Map<String, Long> resourceByStep = new java.util.HashMap<>();

        private static String key(long nodeId, int seq, int attempt) {
            return nodeId + ":" + seq + ":" + attempt;
        }

        @Override
        public void onStepStart(long nodeId, int seq, String method, long resourceId, int attempt) {
            resourceByStep.put(key(nodeId, seq, attempt), resourceId);
        }

        @Override
        public void onStepDone(long nodeId, int seq, String method, boolean ok,
                               String errorCode, String rawResponse, long durationMs, int attempt) {
            Long rid = resourceByStep.get(key(nodeId, seq, attempt));
            done.add(new StepRec(nodeId, seq, method, rid == null ? 0L : rid, attempt,
                    ok, errorCode, rawResponse, durationMs));
        }
    }

    /**
     * 收尾：决定 run 的最终状态。
     *
     * <p><b>三态区分</b>（之前把 MANUAL 一律当 failed，是错的）：
     * <ul>
     *   <li>{@code aborted} —— 被终止，真失败</li>
     *   <li>{@code MANUAL} —— <b>无法自动恢复、转人工</b>，
     *       这不是失败，是"<b>暂停等人</b>"。标成 failed 会让人以为流程坏了，
     *       而且恢复后还得手动改状态。</li>
     *   <li>其余 —— 正常完成</li>
     * </ul>
     *
     * <p>为什么重要：MANUAL 标 failed 时，UI 上看到的是红色"失败"，
     * 操作员不会想到"点继续就能跑"，只能重跑整个流程。
     */
    /** 这个 run 是否已被外部置为 paused。 */
    private boolean isRunPaused(long runId) {
        String sql = "SELECT status::text FROM run WHERE tenant_id = ? AND id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "paused".equals(rs.getString(1));
            }
        } catch (SQLException e) {
            return false;   // 查不到就当作没暂停，不阻断执行
        }
    }

    private void settleRun(long runId, RunProgress p) throws SQLException {
        // P1-8：执行因暂停而中断时，**不能用 completed/failed 覆盖 paused**
        // —— 否则用户点了暂停，界面瞬间变成"完成"。
        if (isRunPaused(runId)) {
            return;
        }
        // P1-11：SKIPPED 也要算"需要人"——跳过意味着板位不可信，
        // 不能悄悄标成 completed 让人以为一切正常。
        boolean manual = p.count(NodeStatus.MANUAL) > 0
                || p.count(NodeStatus.SKIPPED) > 0;
        boolean aborted = p.aborted();

        String sql;
        String status;
        if (aborted) {
            status = "failed";
            sql = "UPDATE run SET status = ?::run_status, actual_end = now()"
                    + " WHERE tenant_id = ? AND id = ?";
        } else if (manual) {
            // 转人工 = 暂停，不是失败
            status = "paused";
            sql = "UPDATE run SET status = ?::run_status, pause_kind = 'waiting_human',"
                    + " pause_reason = '执行到需要人工确认的节点，处理后点继续',"
                    + " paused_at = now()"
                    + " WHERE tenant_id = ? AND id = ?";
        } else {
            status = "completed";
            sql = "UPDATE run SET status = ?::run_status, actual_end = now()"
                    + " WHERE tenant_id = ? AND id = ?";
        }

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, tenantId);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    /**
     * 记录板子在哪个设备上。
     *
     * <p>按执行顺序找**最后一个成功的任务节点**，把它的设备标为"有板"，
     * 其余设备清掉。这不是调度（不决定用哪台），只是把结果记下来，
     * 给前端画板子、给将来做"优先选空设备"留数据。
     */
    private void markPlatePosition(long runId, RunProgress progress) throws SQLException {
        // 已废弃（2026-11-01）：设备级 has_plate 改由 position_state 派生。
        //
        // 原来是"流程结束时把最后一个执行过的设备标记为有板"，和层位级逻辑并行，
        // 结果是一台设备 has_plate=true 但所有层位都空——后续 Withdraw 取不到板。
        //
        // 现在规则单一：**该设备下任一层位有板 → has_plate=true**（PositionLock.refreshHasPlate）。
        // 没有层位的设备永远是"没板"，因为它没地方放板——这是正确语义，不是缺陷。
    }

    /** device_instance.id -> resource_node.id（映射不到返回 null，调用方跳过写入）。 */
    private Long deviceInstanceToResource(Connection c, long instanceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT resource_id FROM device_instance WHERE id = ?")) {
            ps.setLong(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long rid = rs.getLong(1);
                return rs.wasNull() ? null : rid;
            }
        }
    }

    private void recordOccurrences(long runId, RunProgress p, JdbcRecoveryPolicyRepository repo,
                                   Map<Long, Long> deviceToResource, Set<Long> validResources,
                                   String attendance) throws SQLException {
        JdbcErrorOccurrenceStore store = new JdbcErrorOccurrenceStore(db, tenantId, repo);
        for (NodeProgress np : p.all()) {
            if (!JdbcErrorOccurrenceStore.shouldRecord(np.status())) {
                continue;
            }
            // 无人值守下 MANUAL 升为 error：没人盯着，必须让巡检时一眼看到
            boolean unattended = UNATTENDED.equals(attendance);
            String severity = switch (np.status()) {
                case SKIPPED -> "info";
                case ABORTED -> "error";
                case MANUAL -> unattended ? "error" : "warn";
                default -> "warn";
            };
            Long rid = np.currentResourceId();
            Long mapped = rid == null ? null : deviceToResource.getOrDefault(rid, rid);
            if (mapped == null || !validResources.contains(mapped)) {
                mapped = null;   // 外键必须指向 resource_node，否则置空
            }
            store.record(new Occurrence(runId, np.nodeId(), mapped,
                    np.lastErrorCode() == null ? "EC-UNKNOWN" : np.lastErrorCode(),
                    RecoveryScope.NODE, np.attempt(), RecoveryStrategy.MANUAL,
                    np.status().name(), np.lastReason(),
                    Jsons.obj("attempt", np.attempt(), "attendance", attendance), severity));
        }
    }
    // ---------------------------------------------------------------- 保存图

    public record SaveResult(int nodes, int edges, int added, int updated,
                             int removed, int unlabeled) {
    }

    /**
     * 保存 bpmn-js 编辑后的流程图。
     *
     * <p>策略：**按 node_key 增量同步**，不整体重建——
     * <ul>
     *   <li>已存在的节点更新 name/type，**保留 capability_id**（编辑器不管能力标注）</li>
     *   <li>新增节点尝试按 protocolName 反查 device_method 自动标能力</li>
     *   <li>不在新图里的节点删除（历史 run_node 用 node_key，不受影响）</li>
     * </ul>
     */
    /** 把当前 XML 存一份到历史表（保存前调用）。 */
    private void backupGraph(long workflowId) throws SQLException {
        String sql = """
                INSERT INTO workflow_graph_history
                  (tenant_id, workflow_id, xml, node_count, edge_count)
                SELECT ?, id, graph->>'xml',
                       (SELECT count(*) FROM workflow_node n
                         WHERE n.workflow_id = w.id AND n.node_type = 'task'),
                       (SELECT count(*) FROM workflow_edge e WHERE e.workflow_id = w.id)
                FROM workflow w
                WHERE w.tenant_id = ? AND w.id = ? AND w.graph->>'xml' IS NOT NULL
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, tenantId);
            ps.setLong(3, workflowId);
            ps.executeUpdate();
        }
    }

    /**
     * 新建一张空白流程（只有 开始 → 结束），返回 id。
     *
     * <p>为什么需要：界面里原来**没有任何创建流程的入口** —— 流程库只能看、
     * 实验编排的"添加流程"只能选已有的。用户建完实验想顺手画一条流程，无处可去。
     *
     * <p>code 不传就用 {@code AUTO-<时间戳>}：流程编号在库里是唯一键，人工编号容易撞。
     */
    public long createWorkflow(String name, String code, String category) throws SQLException {
        String nm = (name == null || name.isBlank()) ? "未命名流程" : name.trim();
        String cd = (code == null || code.isBlank())
                ? "AUTO-" + System.currentTimeMillis() % 100000000L
                : code.trim();
        String cat = (category == null || category.isBlank()) ? "choreography" : category.trim();
        long id;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO workflow (tenant_id, code, name, category, version, status)
                     VALUES (?, ?, ?, ?, 1, 1)
                     RETURNING id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, cd);
            ps.setString(3, nm);
            ps.setString(4, cat);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        // 建完立刻落一张最小可用的图，否则设计器打开是空的、保存也无从谈起
        saveGraph(id, blankGraphXml(id, nm));
        return id;
    }

    /** 最小可用图：开始 → 结束（带 DI，否则 bpmn-js 画不出来）。 */
    private static String blankGraphXml(long id, String name) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  id="Def_%d" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="Proc_%d" name="%s" isExecutable="false">
                    <bpmn:startEvent id="S" name="开始" />
                    <bpmn:endEvent id="E" name="结束" />
                    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="E" />
                  </bpmn:process>
                  <bpmndi:BPMNDiagram id="Diag_%d">
                    <bpmndi:BPMNPlane id="Plane_%d" bpmnElement="Proc_%d">
                      <bpmndi:BPMNShape id="S_di" bpmnElement="S">
                        <dc:Bounds x="180" y="160" width="36" height="36" />
                      </bpmndi:BPMNShape>
                      <bpmndi:BPMNShape id="E_di" bpmnElement="E">
                        <dc:Bounds x="400" y="160" width="36" height="36" />
                      </bpmndi:BPMNShape>
                      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1">
                        <di:waypoint x="216" y="178" /><di:waypoint x="400" y="178" />
                      </bpmndi:BPMNEdge>
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </bpmn:definitions>
                """.formatted(id, id, name.replace("\"", ""), id, id, id);
    }

    public SaveResult saveGraph(long workflowId, String xml) throws SQLException {
        BpmnGraphParser.ParsedGraph graph = BpmnGraphParser.parse(xml);

        // ① 解析出 0 个节点 → 拒绝。否则一次误传（比如传了展开视图或坏 XML）
        //    就会把原图覆盖成空流程，且没有回滚机会。
        if (graph.nodes().isEmpty()) {
            throw new IllegalArgumentException(
                    "解析出 0 个节点，拒绝保存（XML 可能格式不对，或传了非原图的内容）");
        }

        // ③ 展开视图不能存库：它含 __s 步骤节点，存进去会把步骤变成真节点。
        //    设计器保存前会 collapse() 还原，漏了就会被这里拦下。
        long stepNodes = graph.nodes().stream()
                .filter(n -> n.key().contains(BpmnExpander.STEP_INFIX))
                .count();
        if (stepNodes > 0) {
            throw new IllegalArgumentException(
                    "这是展开视图（含 " + stepNodes + " 个步骤节点），不能直接保存。"
                            + "请在设计器里保存，它会把步骤折叠回业务节点");
        }

        // ② 保存前备份旧图：出问题能查回上一版
        backupGraph(workflowId);

        Map<String, Long> methodCap = loadMethodCapability();

        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                // 1) 现有节点
                Set<String> existing = new LinkedHashSet<>();
                Map<String, Long> capByKey = new LinkedHashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT node_key, capability_id FROM workflow_node WHERE workflow_id = ?")) {
                    ps.setLong(1, workflowId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String k = rs.getString(1);
                            existing.add(k);
                            long cap = rs.getLong(2);
                            if (!rs.wasNull()) {
                                capByKey.put(k, cap);
                            }
                        }
                    }
                }

                // 2) 清掉旧边
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM workflow_edge WHERE workflow_id = ?")) {
                    ps.setLong(1, workflowId);
                    ps.executeUpdate();
                }

                // 3) 增量 upsert 节点
                int added = 0;
                int updated = 0;
                int unlabeled = 0;
                // 设备绑定与顺序**同时落表**（ROADMAP M9 前置债）：
                // 读侧只查列，不再每次解析 XML；seq 用文档顺序，保证两次运行一致。
                Map<String, Integer> docOrder = BpmnGraphParser.documentOrder(xml,
                        graph.nodes().stream().map(BpmnGraphParser.ParsedNode::key).toList());

                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE workflow_node SET name = ?, node_type = ?, "
                                + "params = COALESCE(params, '{}'::jsonb) || ?::jsonb, "
                                + "resource_id = ?, seq = ?, "
                                + "updated_at = now() "
                                + "WHERE workflow_id = ? AND node_key = ?");
                     PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO workflow_node (tenant_id, workflow_id, node_key, name, node_type, "
                                + "capability_id, params, estimate_sec, resource_id, seq) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 0, ?, ?)")) {
                    for (BpmnGraphParser.ParsedNode n : graph.nodes()) {
                        Integer seq = docOrder.get(n.key());
                        if (existing.contains(n.key())) {
                            up.setString(1, n.name());
                            up.setString(2, n.type());
                            String mpU = n.methodParams();
                            up.setString(3, mpU == null || mpU.isBlank()
                                    ? "{}"
                                    : Jsons.obj("method_params", new Jsons.Raw(mpU)));
                            if (n.resourceId() == null) {
                                up.setNull(4, java.sql.Types.BIGINT);
                            } else {
                                up.setLong(4, n.resourceId());
                            }
                            if (seq == null) {
                                up.setNull(5, java.sql.Types.INTEGER);
                            } else {
                                up.setInt(5, seq);
                            }
                            up.setLong(6, workflowId);
                            up.setString(7, n.key());
                            up.addBatch();
                            updated++;
                            if (!capByKey.containsKey(n.key())
                                    && "task".equals(n.type())) {
                                unlabeled++;
                            }
                        } else {
                            Long cap = n.protocolName() == null ? null
                                    : methodCap.get(n.protocolName());
                            ins.setLong(1, tenantId);
                            ins.setLong(2, workflowId);
                            ins.setString(3, n.key());
                            ins.setString(4, n.name());
                            ins.setString(5, n.type());
                            if (cap == null) {
                                ins.setNull(6, java.sql.Types.BIGINT);
                                if ("task".equals(n.type())) {
                                    unlabeled++;
                                }
                            } else {
                                ins.setLong(6, cap);
                            }
                            String mp = n.methodParams();
                            String proto = n.protocolName() == null ? "" : n.protocolName();
                            ins.setString(7, mp == null || mp.isBlank()
                                    ? Jsons.obj("protocol_name", proto, "origin", "bpmn_editor")
                                    : Jsons.obj("protocol_name", proto,
                                            "method_params", new Jsons.Raw(mp),
                                            "origin", "bpmn_editor"));
                            if (n.resourceId() == null) {
                                ins.setNull(8, java.sql.Types.BIGINT);
                            } else {
                                ins.setLong(8, n.resourceId());
                            }
                            if (seq == null) {
                                ins.setNull(9, java.sql.Types.INTEGER);
                            } else {
                                ins.setInt(9, seq);
                            }
                            ins.addBatch();
                            added++;
                        }
                    }
                    up.executeBatch();
                    ins.executeBatch();
                }

                // 4) 删除不在新图里的节点
                List<String> keep = graph.nodes().stream()
                        .map(BpmnGraphParser.ParsedNode::key).toList();
                int removed = 0;
                if (!keep.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM workflow_node WHERE workflow_id = ? "
                                    + "AND NOT (node_key = ANY(?))")) {
                        ps.setLong(1, workflowId);
                        ps.setArray(2, c.createArrayOf("varchar", keep.toArray()));
                        removed = ps.executeUpdate();
                    }
                }

                // 5) 重建边
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO workflow_edge (tenant_id, workflow_id, from_key, to_key, edge_type) "
                                + "VALUES (?, ?, ?, ?, 'sequence') "
                                + "ON CONFLICT DO NOTHING")) {
                    for (BpmnGraphParser.ParsedEdge e : graph.edges()) {
                        if (!keep.contains(e.from()) || !keep.contains(e.to())) {
                            continue;
                        }
                        ps.setLong(1, tenantId);
                        ps.setLong(2, workflowId);
                        ps.setString(3, e.from());
                        ps.setString(4, e.to());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // 6) 保存原始 XML
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE workflow SET graph = jsonb_set("
                                + "COALESCE(graph, '{}'::jsonb), '{xml}', to_jsonb(?::text)), "
                                + "updated_at = now() WHERE tenant_id = ? AND id = ?")) {
                    ps.setString(1, xml);
                    ps.setLong(2, tenantId);
                    ps.setLong(3, workflowId);
                    ps.executeUpdate();
                }

                c.commit();
                return new SaveResult(graph.nodes().size(), graph.edges().size(),
                        added, updated, removed, unlabeled);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** protocol_name -> capability（多数表决），用于新增节点自动标能力。 */
    private Map<String, Long> loadMethodCapability() throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        String sql = """
                SELECT method, capability_id,
                       row_number() OVER (PARTITION BY method
                                          ORDER BY count(*) DESC, capability_id) AS rn
                FROM device_method WHERE capability_id IS NOT NULL
                GROUP BY method, capability_id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getInt(3) == 1) {
                    out.putIfAbsent(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

}
