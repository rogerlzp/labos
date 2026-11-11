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
import com.labos.kernel.persistence.JdbcManualWindowStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.ErrorContext;
import com.labos.kernel.recovery.ManualWindow;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.RealWorkflowRunner.CapabilitySource;
import com.labos.kernel.workflow.RealWorkflowRunner.NodeDef;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
public class WorkflowService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkflowService.class);

    /**
     * C 批：workflow 的 SQL 走 MyBatis XML（见 WorkflowMapper.xml）。
     * 由 BeanConfiguration 注入；为空说明有人绕过容器 new 了本类 —— 直接失败，
     * 不留「没 Mapper 就退回裸 JDBC」的兼容分支。
     */
    private com.labos.kernel.persistence.WorkflowMapper workflowMapper;

    public void setWorkflowMapper(com.labos.kernel.persistence.WorkflowMapper mapper) {
        this.workflowMapper = mapper;
    }

    /** C 批：run 状态收尾 SQL（RunMapper.xml）。由 BeanConfiguration 注入。 */
    private com.labos.kernel.persistence.RunMapper runMapper;

    public void setRunMapper(com.labos.kernel.persistence.RunMapper mapper) {
        this.runMapper = mapper;
    }

    /** 流程图保存路径的 SQL（GraphMapper.xml）。由 BeanConfiguration 注入。 */
    private com.labos.kernel.persistence.GraphMapper graphMapper;

    public void setGraphMapper(com.labos.kernel.persistence.GraphMapper mapper) {
        this.graphMapper = mapper;
    }

    /** 读侧高频查询（QueryMapper.xml）。由 BeanConfiguration 注入。 */
    private com.labos.kernel.persistence.QueryMapper queryMapper;

    public void setQueryMapper(com.labos.kernel.persistence.QueryMapper mapper) {
        this.queryMapper = mapper;
    }

    /**
     * 懒取 Mapper：容器里由上面的 setter 注入；**自测/独立工具里本类是裸 new 的**，
     * 没有注入机会，这里回落到 {@link com.labos.kernel.persistence.Mappers}。
     * 不做这个兜底，{@code loadGraphXml} 之类会在裸 new 场景抛 NPE
     * （不是 SQLException，原有 catch 兜不住，直接把批处理链路打挂）。
     */
    /** 延迟取设备态 Mapper（内部类与静态路径都用它）。 */
    private static com.labos.kernel.persistence.DeviceStateMapper dm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceStateMapper.class);
    }

    /** 懒取 RunMapper：同 qm()，裸 new 场景（自测/工具）没有注入机会。 */
    private com.labos.kernel.persistence.RunMapper rm() {
        com.labos.kernel.persistence.RunMapper m = runMapper;
        if (m == null) {
            m = com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.RunMapper.class);
            runMapper = m;
        }
        return m;
    }

    private com.labos.kernel.persistence.QueryMapper qm() {
        com.labos.kernel.persistence.QueryMapper m = queryMapper;
        if (m == null) {
            m = com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.QueryMapper.class);
            queryMapper = m;
        }
        return m;
    }

    /** 懒取 WorkflowMapper：同 qm()，裸 new 场景（自测/工具）没有注入机会。 */
    private com.labos.kernel.persistence.WorkflowMapper wm() {
        com.labos.kernel.persistence.WorkflowMapper m = workflowMapper;
        if (m == null) {
            m = com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.WorkflowMapper.class);
            workflowMapper = m;
        }
        return m;
    }

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
     * <p>改动前只做纯替换（{@code ${i}} → 值），现在支持表达式：
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
     * 流程的**权威岛**（`workflow.island_id`，P-01 定的执行边界）。
     *
     * <p>为什么执行时要读它：岛过滤原先只来自**请求参数**（{@link #withIsland}），
     * 而前端执行压根不传岛 ⇒ 候选池是全岛池，流程的设备可能来自别的岛（P-05）。
     *
     * @return 权威岛 id；旧流程（island_id 为 NULL）返回 null ⇒ 沿用请求里的岛
     */
    public Long authoritativeIslandId(long workflowId) throws SQLException {
        java.util.Map<String, Object> row =
                graphMapper.selectWorkflowHeader(tenantId, workflowId);
        if (row == null || row.get("islandId") == null) {
            return null;
        }
        Object v = row.get("islandId");
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    /**
     * 限定从某个岛选设备。
     *
     * <p>用途：岛内演示时希望"板子在这几台之间流转"，
     * 而不是被设备池里任意一台其他设备接管。
     *
     * <p>⚠ 注意与 {@code workflow.island_id}（权威岛）的区别：这里是**这次执行的
     * 候选池偏好**；权威岛是**流程的归属**，执行时以它为准（见
     * {@link #authoritativeIslandId}）。两者不一致时以权威岛为准。
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
        java.util.Set<Long> busy = new java.util.LinkedHashSet<>(
                dm().selectPlateBusyInstanceIds(tenantId));
        if (busy.isEmpty()) {
            return capPool;
        }

        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : capPool.entrySet()) {
            List<Long> ids = new ArrayList<>(e.getValue());
            // 稳定的：空设备在前，有板的在后（保持相对顺序）
            ids.sort(java.util.Comparator.comparing(busy::contains));
            out.put(e.getKey(), ids);
        }
        return out;
    }

    /** 岛的 device_instance.id 集合（空集表示岛内没设备）。 */    /** 岛的 device_instance.id 集合（空集表示岛内没设备）。 */
    private java.util.Set<Long> loadIslandDeviceIds(long islandId) throws SQLException {
        return new java.util.LinkedHashSet<>(
                dm().selectIslandInstanceIds(tenantId, islandId));
    }

    /**
     * 岛内设备实例 → resource_node.id（**模拟路径**的候选池是资源 id 口径）。
     *
     * <p>为什么要转：driver 路径的候选是 device_instance.id，模拟路径是
     * resource_node.id —— 拿同一种口径去比另一种，必然全部落空（"两套 id"坑的
     * 又一次）。这里按当前路径的 id 口径转换，避免过滤结果恒为空。
     */
    private java.util.Set<Long> resourceIdsOf(java.util.Set<Long> instanceIds) throws SQLException {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        if (instanceIds.isEmpty()) {
            return out;
        }
        for (java.util.Map<String, Object> row : dm().selectResourceInstancePairs(tenantId)) {
            long inst = ((Number) row.get("instanceId")).longValue();
            if (instanceIds.contains(inst)) {
                out.add(((Number) row.get("resourceId")).longValue());
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
        boolean byCategory = category != null && !category.isBlank();
        // **最近改动的在前**。改动前按 task_count DESC 排 —— 结果新建的
        // 单步流程（任务数少）永远落在 limit 之外，界面上根本搜不到，
        // 现象是"刚建的流程不见了"。复杂流程并不等于更该被看见。
        for (Map<String, Object> r : wm().selectWorkflowList(tenantId,
                byCategory ? category : null, Math.max(1, Math.min(limit, 500)))) {
            out.add(new WorkflowDef(((Number) r.get("id")).longValue(),
                    (String) r.get("code"), (String) r.get("name"),
                    (String) r.get("category")));
        }
        return out;
    }

    public Detail detail(long workflowId) throws SQLException {
        WorkflowDef wf = RealWorkflowRunner.loadWorkflow(db, workflowId);
        List<NodeDef> nodes = RealWorkflowRunner.loadNodes(db, workflowId);
        List<String[]> edges = RealWorkflowRunner.loadEdges(db, workflowId);
        Map<String, String> protocolCap = RealWorkflowRunner.loadProtocolCapability(db);
        Map<Long, String> resourceCap = RealWorkflowRunner.loadResourceCapability(db);

        List<TaskView> tasks = new ArrayList<>();
        for (NodeDef n : nodes) {
            if (!"task".equals(n.type())) {
                continue;
            }
            CapabilitySource cs = RealWorkflowRunner.resolveCapability(n, protocolCap, resourceCap);
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

        List<RecipeCompiler.NodePlan> plans = compiler.compileAll(nodes, protocolCap,
                RealWorkflowRunner.loadResourceCapability(db));
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

    /** JVM 内同一 run 只能有一个后台 worker，避免重复 continue/retry 并发起两条执行线程。 */
    private static final java.util.concurrent.ConcurrentMap<Long, String> ACTIVE_RUN_WORKERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static boolean claimRunWorkerSlot(long runId, String owner) {
        return ACTIVE_RUN_WORKERS.putIfAbsent(runId, owner) == null;
    }

    static void releaseRunWorkerSlot(long runId, String owner) {
        ACTIVE_RUN_WORKERS.remove(runId, owner);
    }

    static String activeRunWorkerOwner(long runId) {
        return ACTIVE_RUN_WORKERS.get(runId);
    }

    static void clearRunWorkerSlotsForTest() {
        ACTIVE_RUN_WORKERS.clear();
    }

    private void submitRunWorker(long runId, String owner, Runnable body) {
        if (!claimRunWorkerSlot(runId, owner)) {
            String holder = activeRunWorkerOwner(runId);
            throw new IllegalStateException("run " + runId + " 已有后台执行线程"
                    + (holder == null ? "" : "（" + holder + "）"));
        }
        try {
            RUN_POOL.submit(() -> {
                try {
                    body.run();
                } finally {
                    releaseRunWorkerSlot(runId, owner);
                }
            });
        } catch (RuntimeException e) {
            releaseRunWorkerSlot(runId, owner);
            throw e;
        }
    }

    /**
     * 占位；已被别的 worker 占着就抛。**调用方必须在动任何 DB 状态之前调它** ——
     * 拒绝要发生在状态被改写之前，否则会留下「status=running 却没人跑」的僵尸单。
     */
    static void claimRunWorkerSlotOrThrow(long runId, String owner) {
        if (!claimRunWorkerSlot(runId, owner)) {
            String holder = activeRunWorkerOwner(runId);
            throw new IllegalStateException("run " + runId + " 已有后台执行线程在跑"
                    + (holder == null ? "" : "（" + holder + "）")
                    + "；旧 worker 未退出前拒绝重复启动/续跑");
        }
    }

    /** 起后台线程跑 body；**假定占位已由调用方完成**，结束后释放。 */
    static void launchRunWorker(long runId, String owner, Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } finally {
                releaseRunWorkerSlot(runId, owner);
            }
        }, owner);
        t.setDaemon(true);
        try {
            t.start();
        } catch (RuntimeException | Error e) {
            releaseRunWorkerSlot(runId, owner);
            throw e;
        }
    }

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
            rm().mergeRunInputVars(tenantId, runId, "{\"vars\":" + vj + "}");
        }

        try {
            submitRunWorker(runId, "run-exec-" + runId, () -> {
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
        } catch (IllegalStateException busy) {
            // 起不了线程就别留一条 status=running 的空单。
            markRunFailed(runId, "启动失败：" + busy.getMessage());
            throw busy;
        }
        return runId;
    }

    /** 后台执行异常时把 run 收尾，避免永远停在 running。 */
    private void markRunFailed(long runId, String reason) throws SQLException {
        if (runMapper == null) {
            throw new IllegalStateException("RunMapper 未注入");
        }
        runMapper.markFailed(tenantId, runId,
                Jsons.obj("error", reason == null ? "unknown" : reason));
        // P-09：失败同样是"执行体收尾"，不再会派发新命令。
        try {
            runMapper.markSettledPhase(tenantId, runId);
        } catch (RuntimeException e) {
            log.error("[WARN] run#{} exec_phase 未落 SETTLED：{}", runId, e.getMessage());
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
        // B-03：**先占位再动库**。旧 worker 没退出时直接拒绝，且此时 DB 一个字段都没改。
        String workerOwner = "labos-resume-" + runId;
        claimRunWorkerSlotOrThrow(runId, workerOwner);
        try {
            doResumeAsync(runId, workerOwner);
        } catch (RuntimeException | SQLException e) {
            // 没起线程就失败（读库/事务出错）：立刻释放，否则这个 run 再也续不了。
            releaseRunWorkerSlot(runId, workerOwner);
            throw e;
        }
    }

    private void doResumeAsync(long runId, String workerOwner) throws SQLException {
        Map<String, Object> resumeInfo = rm().selectRunResumeInfo(tenantId, runId);
        if (resumeInfo == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        Long wfId = resumeInfo.get("workflowId") == null ? null
                : ((Number) resumeInfo.get("workflowId")).longValue();
        if (wfId == null) {
            throw new IllegalStateException("run " + runId + " 没有关联 workflow，无法续跑");
        }
        String attendance = (String) resumeInfo.get("attendance");

        java.util.Set<String> doneKeys =
                new java.util.LinkedHashSet<>(rm().selectCompletedNodeKeys(tenantId, runId));

        com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            // 必须**先删步骤行（level=1）**，再删业务节点。
            // run_command 挂在步骤行上（FK -> run_node.id ON DELETE CASCADE），
            // 而步骤行只靠裸字段 parent_node_id 指向业务节点，**没有外键** ——
            // 只删 level=0 级联不到它们（2026-11-03 评审 H1，已实测复现：
            // 续跑发了 2 条命令，run_command 一条没增，日志里 2 条 duplicate key）。
            rm().deleteIncompleteStepRows(tenantId, runId);
            rm().deleteIncompleteTopNodes(tenantId, runId);
            rm().markResumed(tenantId, runId);
            return null;
        });

        final long wid = wfId;
        final String mode = attendance;
        final long rid = runId;
        launchRunWorker(runId, workerOwner, () -> {
            try {
                execute(wid, mode, rid, doneKeys);
            } catch (Exception e) {
                try {
                    markRunFailed(rid, "续跑失败：" + e.getMessage());
                } catch (SQLException ignore) {
                    // 尽力而为
                }
            }
        });
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
            log.info("[计时] 加载定义 " + lap.get() + "ms（累计）");
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
            log.info("[计时] 驱动注册表 " + (now - lastLap) + "ms（累计 "
                    + now + "ms）");
            lastLap = now;
        }
        boolean viaDriver = registry != null;
        Map<Long, Set<String>> devCaps = viaDriver ? loadDeviceCapabilities() : Map.of();

        // ---- P-05：执行期用流程的**权威岛**（workflow.island_id） ----
        // 没有权威岛（旧流程）才用请求里带的岛；两者都有时上游已校验过一致。
        //
        // 为什么以前不生效：岛过滤**只来自请求参数**，而前端执行压根不传岛 ⇒
        // 过滤的内容恒等于空；再加上"过滤不中就回退全局候选" ⇒ 岛约束从来没
        // 真正管过选设备。详见 REMEDIATION_STATUS.md 的 P-05。
        Long authIsland = authoritativeIslandId(workflowId);
        Long effIsland = authIsland != null ? authIsland
                : (islandFilter != null ? islandFilter.get() : null);
        // islandInstances：岛内设备实例（device_instance.id 口径）——
        //   给 GraphRunner 的 pinned 分支做同岛校验（它用的就是实例 id）。
        // islandIds：按**当前路径的候选 id 口径**取 —— driver 路径的候选是
        //   device_instance.id，模拟路径是 resource_node.id（"两套 id"坑的又一次）。
        //   null = 不做岛约束（没有权威岛、也没请求带岛）。
        //   两个集合都写成 final 单表达式赋值：不靠"方法引用的接收者是表达式"
        //   这种宽松语义，免得编译器一收紧就编不过。
        final java.util.Set<Long> islandInstances = effIsland == null
                ? null
                : loadIslandDeviceIds(effIsland);
        final java.util.Set<Long> islandIds = islandInstances == null
                ? null
                : (viaDriver ? islandInstances : resourceIdsOf(islandInstances));

        Map<Long, String> resourceCap = RealWorkflowRunner.loadResourceCapability(db);
        List<RecoverableNode> rec = new ArrayList<>();
        for (NodeDef n : ordered) {
            CapabilitySource cs = RealWorkflowRunner.resolveCapability(n, protocolCap, resourceCap);
            String cap = cs.capability();
            List<Long> candidates;
            if (RealWorkflowRunner.LOGIC.equals(cap)) {
                candidates = List.of(RealWorkflowRunner.LOGIC_RESOURCE);
            } else if (viaDriver) {
                candidates = registry.candidates(cap, Set.of());
            } else {
                candidates = pool.getOrDefault(cap, List.of());
            }
            // P-05：岛约束对**顺序回退路径**同样有效（它用的就是这份 rec）。
            // 逻辑节点不是设备（伪 id LOGIC_RESOURCE）⇒ 不参与岛过滤。
            if (islandIds != null && !RealWorkflowRunner.LOGIC.equals(cap)) {
                candidates = candidates.stream().filter(islandIds::contains).toList();
            }
            if (candidates.isEmpty() && RealWorkflowRunner.LOGIC.equals(cap)) {
                candidates = List.of(RealWorkflowRunner.LOGIC_RESOURCE);
            }
            rec.add(RecoverableNode.of(n.id(), n.key(), n.name(), cap, n.protocolName(),
                    candidates.isEmpty() ? null : candidates.get(0), candidates));
        }

        // 岛内没有该能力的节点 → 记下"为什么没设备"。
        //
        // 为什么必须有：状态机的默认话是"无可执行资源：节点未给出初始设备，且候选集为空"，
        // 现场只看得到"候选集为空"，**看不出为什么空** —— 而这恰恰是 P-05 里最常见的
        // 原因（岛内没有具备该能力的设备）。注入进去，运营不用猜。
        //
        // 只在**岛过滤真的生效**时才有内容（没岛约束的流程拿不到任何注入）。
        final java.util.Map<String, String> islandNoDevice = new java.util.HashMap<>();
        if (islandIds != null) {
            for (RecoverableNode n : rec) {
                if (n.candidateResourceIds() != null && n.candidateResourceIds().isEmpty()
                        && !RealWorkflowRunner.LOGIC.equals(n.capabilityCode())) {
                    islandNoDevice.put(n.key(), "岛 " + effIsland + " 内没有具备 "
                            + n.capabilityCode() + " 能力的设备，拒绝借用其它岛的设备");
                }
            }
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

        // 限定岛（P-05）：**岛内没有该能力的设备 ⇒ 就不跑**（不再回退全局候选）。
        //
        // 此前这里"过滤不中就保留全局候选"，加上过滤内容恒为空 ⇒ 岛约束从来没
        // 真正生效过（根因与修法见 REMEDIATION_STATUS.md 的 P-05）。
        if (islandIds != null) {
            Map<String, List<Long>> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, List<Long>> e : capPool.entrySet()) {
                filtered.put(e.getKey(), e.getValue().stream().filter(islandIds::contains).toList());
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
            log.info("[计时] 候选池+参数 " + (now - lastLap) + "ms（累计 "
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
        // 本次执行是不是在节点边界被暂停（workflow 级 paused / 实验级现场扣留）。
        // 收尾前要用它把 run 落成 paused，否则 markBypassedNodes/settleRun 会把
        // "还没轮到的节点"标成 bypassed、把整单覆盖成 completed。
        boolean pausedAtBoundary = false;
        // GraphRunner 实际停住的节点。页面/探针往往先把 status 写成 paused，
        // 但写不了「下一个任务边界是谁」——那个 key 只在执行线程里知道。
        String pausedAtKey = null;
        try (JdbcEventStore store = new JdbcEventStore(db, tenantId, runId, "api-wf", 100)) {
            // ⚠️ 这里改动前传的是 Sleeper.recording(...)：只**记录**退避时间，没人真睡。
            // 后果：策略写 retry 3 次（500ms/1s/2s），实际 50ms 内就把三次机会用光，
            // 设备"忙两秒"这种瞬时故障必然判失败 → 转人工。退避形同虚设。
            // 生产必须真睡；只有 CI/批量验证嫌慢时才用 LABOS_FAST_RECOVERY=1 关掉。
            Sleeper recoverySleeper = "1".equals(System.getenv("LABOS_FAST_RECOVERY"))
                    ? Sleeper.recording(new ArrayList<>())
                    : Sleeper.REAL;
            RunStateMachine sm = new RunStateMachine(decider,
                    tee(events, store, monitor), recoverySleeper, Clock.systemUTC())
                    .withStepPlanner(id -> stepsByNode.getOrDefault(id, List.of()))
                    .withParamPlanner(id -> paramsByNode.getOrDefault(id, Map.of()))
                    // P-05：岛内没有该能力时，把"为什么没设备"写进节点原因
                    .withNoDeviceReason(islandNoDevice::get);
            // 按图执行（含并行/异或网关）。
            // 之前这里走 executeAll，网关被忽略——批量验收用了 GraphRunner，
            // 于是出现"验收说 100%、产品路径却没有网关"的不一致。
            // 网关执行默认开启（EC-06-0001 已修：候选池 id 口径统一为 device_instance.id）。
            // 需要退回顺序执行时设 LABOS_EXEC_GRAPH=0。
            boolean graphEnabled = !"0".equals(System.getenv("LABOS_EXEC_GRAPH"));
            String xml = graphEnabled ? loadGraphXml(workflowId) : null;
            boolean viaGraph = false;
            if (xml != null && !xml.isBlank()) {
                GraphRunner gr = new GraphRunner(sm, capPool, protocolCap, capByKey,
                        nodeIdByKey, line -> {
                            // 其余图日志仍不刷屏；暂停落点必须进内核 stdout，
                            // 否则「停在哪个节点」只活在被丢弃的回调里，外面无从核对。
                            if (line != null && line.startsWith("[暂停]")) {
                                log.info(line);
                            }
                        })
                        .withResourceCapability(resourceCap)
                        .withBoundInstances(loadBoundInstances())
                        // P-05：编排里**显式绑定**的节点走 pinned 分支，直接用那台
                        // 设备，不经候选池 ⇒ 岛过滤对它无效 ⇒ 这里把岛内实例集合交给
                        // 执行器，越岛绑定**明确拒绝**（不静默换设备）。
                        .withIslandInstances(islandInstances)
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
                        // `__sleep` / `__wait` 会**真的等**：真睡（不是 recording），
                        // 并且每轮重读一次 run.input.vars —— 变量的变化来自执行体之外，
                        // 只读内存表等于永远等不到（见 GraphRunner.withVariableRefresher）。
                        .withSleeper(Sleeper.REAL)
                        .withVariableRefresher(() -> loadRunVars(runId))

                        // P1-8：每到一个节点边界查一次 DB —— 状态是 paused 就停。
                        // 只查单行主键，开销可忽略；不查的话暂停按钮等于没用。
                        // ⚠ 还要查**实验级现场扣留**：实验级暂停落的是扣留、不改
                        // run 状态，只查 run.status 的话底层流程永远看不到它，会照旧跑完
                        // （2026-11-06 实测：实验级暂停停不住 workflow 级 run）。
                        .withPauseChecker(() -> isRunPaused(runId)
                                || new RuntimeHold(db, tenantId)
                                        .coverageForWorkflow(workflowId).blocks())
                        // 逐节点落「执行中 / 已完成」：外壳建的是 pending，
                        // 靠这个回调把三段状态画得出来（见 createNodeShells）。
                        .withNodeStateRecorder((key, st) -> recordNodeState(runId, key, st));
                // B-01：退回顺序执行**只允许发生在解析期**。
                // 解析失败时还没派发任何设备命令，退回是兼容手段；
                // 一旦 GraphRunner 开始执行，RuntimeException 必须原样抛出——
                // 退回 executeAll 等于把同一流程从头再执行一遍，在真实设备上
                // 就是第二次开盖/第二次移液（物理副作用不可撤销）。
                // lambda 里用的必须是 effectively-final（executor 上面可能被
                // TimeConstraintExecutor 包过一层，不能直接捕获）。
                final StepExecutor seqExecutor = executor;
                final List<RecoverableNode> recFinal = rec;
                GraphExecution graphExecution = executeGraphWithParseFallback(
                        xml, () -> sm.executeAll(recFinal, seqExecutor), gr, seqExecutor);
                progress = graphExecution.progress();
                viaGraph = graphExecution.viaGraph();
                if (viaGraph) {
                    pausedAtBoundary = gr.runPaused();
                    pausedAtKey = gr.pausedAtKey();
                }
            } else {
                progress = sm.executeAll(rec, executor);
            }
            pathHolder[0] = viaGraph ? "graph" : "sequential";
            store.flush();
            eventsWritten = store.written();
        } catch (RuntimeException executionFailure) {
            // B-01：执行期异常**不重跑**，只如实收尾。
            // 已完成节点的 run_node / run_command 保持现场（不回滚、不重放），
            // run 落 failed 并记下原因，异常原样抛给调用方 —— 让上层看到"这次失败了"，
            // 而不是在背后把同一批物理动作再执行一遍。
            RunMonitor.INSTANCE.finish(runId);
            log.error("[执行失败] run#{} 执行期异常（不重跑，不退回顺序重跑）",
                    runId, executionFailure);
            try {
                markRunFailed(runId, "执行期异常：" + executionFailure);
            } catch (SQLException e) {
                log.error("[WARN] run#" + runId + " 异常收尾回写失败（保留原异常）："
                        + e.getMessage());
            }
            throw executionFailure;
        }

        RunMonitor.INSTANCE.finish(runId);

        // 执行在节点边界被暂停：先把 run 落成 paused，再收尾。
        // 顺序不能反 —— markBypassedNodes / settleRun 都以 run.status 为准，
        // 不先落就会把"还没轮到的节点"标成 bypassed、把整单覆盖成 completed。
        if (pausedAtBoundary) {
            pauseRunAtBoundary(runId, workflowId, pausedAtKey);
        }

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
                liveAttendance(runId, mode), nodeIdMap);

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

    /**
     * 图执行的失败边界（B-01）：**退回顺序执行只允许发生在解析期**。
     *
     * <p>退回一次顺序执行 = 把同一张流程图从头再跑一遍。真实设备上这意味着
     * 第二次开盖 / 第二次移液 / 第二次搬运，而执行期异常恰恰最常发生在
     * 「已经跑了一半、现场状态已经变了」的时候——此时重跑不只是浪费，
     * 而是**在错误的现场状态下重复物理动作**。
     *
     * <p>因此：
     * <ul>
     *   <li>解析失败（还一条设备命令都没派发）⇒ 允许退回顺序执行，
     *       兼容历史流程图里解析器不认识的写法；</li>
     *   <li>执行期任何 {@link RuntimeException} ⇒ 原样抛出（方法体内的
     *       {@code graphRunner.run} 故意放在 try 之外），由上层按失败收尾。</li>
     * </ul>
     */
    static GraphExecution executeGraphWithParseFallback(
            String xml,
            java.util.function.Supplier<RunProgress> sequentialFallback,
            GraphRunner graphRunner,
            StepExecutor executor) {
        final WorkflowGraphParser.Graph graph;
        try {
            graph = WorkflowGraphParser.parse(xml);
        } catch (RuntimeException parseFailure) {
            log.error("[WARN] BPMN 解析失败（尚未派发设备命令），退回顺序执行: "
                    + parseFailure.getMessage());
            return new GraphExecution(sequentialFallback.get(), false);
        }
        // 故意放在 try/catch 之外：执行期异常绝不能触发顺序重跑。
        return new GraphExecution(graphRunner.run(graph, executor), true);
    }

    /** {@link #executeGraphWithParseFallback} 的结果：进度 + 这次是否真的走了图执行。 */
    record GraphExecution(RunProgress progress, boolean viaGraph) {
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
        // 改动前只在长连接模式缓存，短连接每次都重建 7 个 adapter + 查一遍驱动表，
        // 实测吃掉 4.7 秒（命令本身只要 312ms）——压测时才暴露。
        if (cachedRegistry != null
                && System.currentTimeMillis() - registryBuiltAt < REGISTRY_TTL_MS) {
            return cachedRegistry;
        }

        // **从库里读所有 active 驱动，逐个注册**。
        // 这样"每台设备一个模拟器"才成立：每个模拟器一条 driver 记录、一个端点。
        // 找不到任何配置时才回退到单端点路径（兼容单机演示）。
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
            log.info("[驱动] 单端点回退模式：tcp://127.0.0.1:" + driverPort);
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
        for (Map<String, Object> row : drm().selectDriverDeviceRows(tenantId)) {
            long did = ((Number) row.get("driverId")).longValue();
            meta.putIfAbsent(did, new String[]{
                    (String) row.get("code"), (String) row.get("endpoint"),
                    (String) row.get("protocol")});
            long inst = ((Number) row.get("instanceId")).longValue();
            Map<Long, Set<String>> devs =
                    byDriver.computeIfAbsent(did, k -> new LinkedHashMap<>());
            Set<String> caps = devs.computeIfAbsent(inst, k -> new LinkedHashSet<>());
            String cap = (String) row.get("cap");
            if (cap != null) {
                caps.add(cap);
            }
            String modelId = (String) row.get("deviceModel");
            if (modelId != null && !modelId.isBlank()) {
                modelByDriver.computeIfAbsent(did, k -> new LinkedHashMap<>())
                        .put(inst, modelId.trim());
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
                log.error("[驱动] " + m[0] + " 跳过：名下设备都没有声明能力"
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
                log.info("[驱动] " + m[0] + " → " + endpoint
                        + "（" + devs.size() + " 台设备"
                        + (bindings.isEmpty() ? "，未绑定型号" : "，已绑定型号 " + bindings)
                        + "）");
            } catch (RuntimeException ex) {
                log.error("[驱动] " + m[0] + " 注册失败：" + ex.getMessage());
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
        // ⚠ 2026-11-11：**命令下发仍走短连接**（TcpDriverAdapter）。
        //   在线状态那条链已经交给 **tcp client（sidecar）→ Redis** 了：
        //   sidecar 维持到设备的**长连接**并把状态写进 Redis，中控从 Redis 读它判在线。
        //   ⇒ 命令下发与在线状态**职责分开**：在线状态不再由内核自己开短连接去探
        //   （那会让设备侧看到"接入→断开"反复出现，也不是真在线）；
        //   而命令下发仍由内核直接连设备、用短连接（协议帧与设备侧一致，风险最小）。
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
        for (Map<String, Object> row : drm().selectInstanceCapabilities()) {
            out.computeIfAbsent(((Number) row.get("instanceId")).longValue(),
                            k -> new LinkedHashSet<>())
                    .add((String) row.get("cap"));
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

        for (Map<String, Object> row : wm().selectTimeConstraints(tenantId, workflowId)) {
            Long selfId = idByKey.get(row.get("nodeKey"));
            Long afterId = idByKey.get(row.get("afterKey"));
            int minSec = ((Number) row.get("minSec")).intValue();
            int maxSec = ((Number) row.get("maxSec")).intValue();
            if (selfId == null || afterId == null || (minSec <= 0 && maxSec <= 0)) {
                continue;
            }
            out.put(selfId, new TimeConstraintExecutor.Constraint(afterId, minSec, maxSec));
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
        for (Map<String, Object> row : wm().selectMethodParams(tenantId, workflowId)) {
            Long id = idByKey.get(row.get("nodeKey"));
            Object mpJson = row.get("mp");
            String json = mpJson == null ? null : String.valueOf(mpJson);
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
        for (Map<String, Object> row : wm().selectTaskNodeIds(tenantId, workflowId)) {
            out.put((String) row.get("nodeKey"),
                    ((Number) row.get("id")).longValue());
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
        try {
            Map<String, Object> row = rm().selectLatestNodeOutput(tenantId, runId, nodeKey);
            if (row == null) {
                return out;
            }
            // **优先取设备原始回包里的 data**：run_command.result 只有
            // {ok,durationMs}，设备真正返回的业务字段（plateType 等）在
            // device_raw_response 的 data 里。不读它，节点输出驱动条件等于空谈。
            String payload = (String) row.get("raw");
            if (payload == null || payload.isBlank()) {
                payload = (String) row.get("result");
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
        } catch (RuntimeException e) {
            log.error("[WARN] 读取节点输出失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 流程自己声明的循环圈数上限（{@code workflow.attr.maxIterations}，默认 10）。
     *
     * <p>放流程维度而不是节点维度：一个流程里的回边通常不止一条，
     * 按节点配会让运维记不清"这条到底受谁管"。
     */
    public int loadMaxIterations(long workflowId) {
        try {
            String v = qm().selectWorkflowMaxIterations(tenantId, workflowId);
            if (v != null && !v.isBlank()) {
                return Integer.parseInt(v.trim());
            }
        } catch (RuntimeException e) {
            log.error("[WARN] 读取 maxIterations 失败，用默认 10: " + e.getMessage());
        }
        return 10;
    }

    /**
     * 写流程级的循环圈数上限（{@code workflow.attr.maxIterations}）。
     *
     * <p>与 {@link #loadMaxIterations} 同一个键：设计器里改一处，
     * 运行时 {@code withMaxVisits} 立刻按新值走，不需要重发布流程。
     * 范围由调用方（API 层）校验，这里只负责落库。
     */
    public void setMaxIterations(long workflowId, int value) throws SQLException {
        wm().updateMaxIterations(tenantId, workflowId, value);
    }

    private Map<String, String> loadRunVars(long runId) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            String raw = qm().selectRunVars(tenantId, runId);
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
        } catch (RuntimeException e) {
            log.error("[WARN] 读取 run 变量失败（条件表达式会按空值处理）: " + e.getMessage());
        }
        return out;
    }

    private Map<Long, Long> loadBoundInstances() throws SQLException {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : dm().selectResourceInstancePairs(tenantId)) {
            out.put(((Number) row.get("resourceId")).longValue(),
                    ((Number) row.get("instanceId")).longValue());
        }
        return out;
    }

    private Map<String, String> loadCapabilityByKey(long workflowId) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : wm().selectCapabilityCodeByKey(tenantId, workflowId)) {
            out.put((String) row.get("nodeKey"), (String) row.get("cap"));
        }
        return out;
    }

    private String loadGraphXml(long workflowId) throws SQLException {
        return wwm().selectWorkflowXml(tenantId, workflowId);
    }

    /** 懒取 WorkflowWebMapper：同 qm()，流程图 XML 已按域归位到该 Mapper。 */
    private com.labos.kernel.persistence.WorkflowWebMapper wwm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.WorkflowWebMapper.class);
    }

    /** 懒取 DriverMapper：同 qm()，驱动/设备能力读侧按域归位到该 Mapper。 */
    private com.labos.kernel.persistence.DriverMapper drm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DriverMapper.class);
    }

    /** 依赖缓存：execute 里会多次用到，避免重复查库。 */
    private Map<Long, Long> deviceToResourceOf() throws SQLException {
        return loadDeviceResourceMap();
    }

    private Set<Long> validResourcesOf() throws SQLException {
        return loadResourceIds();
    }

    /**
     * 预创建业务节点的 run_node 外壳（status=pending）。
     *
     * <p>必须提前建：命令级落库时子步骤要引用父节点 id。
     *
     * <p><b>为什么是 pending 而不是 running</b>：外壳是"还没轮到"的占位，
     * 执行到那个节点时由 {@link GraphRunner#withNodeStateRecorder} 的回调改 running、
     * 跑完改 completed。改动前一律建 running，导致整单运行期间查出来全是 running ——
     * 流程图只能全蓝，看不出「已完成 / 执行中 / 还没轮到」（2026-11-07 实测）。
     *
     * <p>返回 nodeId -> run_node.id。
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
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rm().selectTopNodeKeyIds(tenantId, runId)) {
            byKey.put((String) row.get("nodeKey"),
                    ((Number) row.get("id")).longValue());
        }
        for (RecoverableNode d : defs) {
            Long existing = byKey.get(d.key());
            if (existing != null) {
                out.put(d.id(), existing);
            }
        }
        int seq = 0;
        for (RecoverableNode d : defs) {
            if (out.containsKey(d.id())) {
                seq++;
                continue;   // 已有节点（续跑），不重复插
            }
            Long rid = resolveResource(d.initialResourceId(), deviceToResource, validResources);
            Long id = rm().insertTopNode(tenantId, runId, d.key(), d.name(), ++seq, rid,
                    "pending", 0,
                    Jsons.obj("capability", d.capabilityCode(),
                            "method", String.valueOf(d.method())));
            if (id != null) {
                out.put(d.id(), id);
            }
        }
        return out;
    }

    /**
     * 逐节点落「执行中 / 已完成」（{@link GraphRunner#withNodeStateRecorder} 的回调）。
     *
     * <p>外壳建的是 {@code pending}，这里在节点开始执行时改 {@code running}、
     * 结束时改最终态。{@code actual_start} 只补第一次（COALESCE），
     * {@code actual_end} 只在非 running 时写。
     *
     * <p>落库失败不能中断执行（数据丢了比流程停了好）——只留日志。
     */
    private void recordNodeState(long runId, String nodeKey, String status) {
        try {
            rm().updateNodeStateLive(tenantId, runId, nodeKey, status);
        } catch (RuntimeException e) {
            log.error("[WARN] 记录节点状态失败 " + nodeKey + " -> " + status
                    + ": " + e.getMessage());
        }
    }

    /**
     * 把「分支未选中」的节点标成 {@code bypassed}。
     *
     * <p>判定：整单**成功**跑完，但某些业务节点还是 {@code pending}/{@code running} ——
     * 说明异或网关走了另一条路，这些节点永远轮不到。
     *
     * <p>只在成功结束时标：失败/暂停的单子里 pending/running 可能是"待续跑"，
     * 标成 bypassed 会让续跑逻辑（按 completed 跳、其余重做）误判。
     */
    private void markBypassedNodes(long runId, RunProgress progress) throws SQLException {
        // NodeStatus 里没有 FAILED（失败最终都归到 MANUAL/ABORTED），别照抄 run_node 的状态名。
        // SKIPPED 也必须排除：跳过会让 settleRun 把整单落成 paused/waiting_human，
        // 这种单子的下游节点是"还没轮到（pending）"，不是"分支没选中（bypassed）"。
        // 漏排会把"等人处理"画成"这条路不用走"（2026-11-03 评审 H2）。
        // 暂停的单子同理：剩下的节点是"待续跑"，不是"分支没选中"。
        if (isRunPaused(runId)
                || progress == null
                || progress.count(NodeStatus.MANUAL) > 0
                || progress.count(NodeStatus.ABORTED) > 0
                || progress.count(NodeStatus.SKIPPED) > 0
                || progress.aborted()) {
            return;
        }
        int n = rm().markBypassed(tenantId, runId);
        if (n > 0) {
            log.info("[执行] run " + runId + " 有 " + n + " 个节点分支未选中（bypassed）");
        }
    }

    private void updateNodeResults(long runId, RunProgress p, List<RecoverableNode> defs,
                                   Map<Long, Long> deviceToResource,
                                   Set<Long> validResources) throws SQLException {
        Map<Long, RecoverableNode> byId = new java.util.HashMap<>();
        for (RecoverableNode n : defs) {
            byId.put(n.id(), n);
        }
        // error_code 单独落一列（改动前是塞进 input.reason 的字符串里）：
        //   · 前端要显示得用正则从"策略[EC-08-400C@NODE] 指定直接转人工"里抠；
        //   · SQL 没法统计"哪台设备最常报无纸"。
        // 列本来就存在（varchar(64)），只是一直没人写。
        for (NodeProgress np : p.all()) {
            RecoverableNode d = byId.get(np.nodeId());
            if (d == null) {
                continue;
            }
            Long rid = resolveResource(np.currentResourceId(), deviceToResource, validResources);
            String err = np.lastErrorCode();
            rm().updateNodeResult(tenantId, runId, d.key(),
                    np.status().name().toLowerCase(), np.attempt(), rid,
                    err == null || err.isBlank() ? null
                            : (err.length() > 64 ? err.substring(0, 64) : err),
                    Jsons.obj("reason", np.lastReason(), "errorCode", np.lastErrorCode()));
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
        try {
            return qm().selectDeviceResourceByInstance(instanceId);
        } catch (RuntimeException ignore) {
            // 查不到就返回 null，调用方会写 NULL（不写坏数据）
        }
        return null;
    }

    /**
     * 记录循环的第 N 圈。
     *
     * <p>{@code run_node} 每个业务节点只有一行（唯一键按 node_key），
     * 循环跑 5 圈也只有最后一圈的最终状态。这里按圈补一行：
     * {@code loop_index=0} 仍是**汇总行**（最终状态，所有现有查询都只看它），
     * {@code loop_index=1..N} 是每一圈的实际执行。
     *
     * <p>为什么不改汇总行：一改就要动"节点数 / 90% 报表 / 流程图着色"一堆查询。
     * 加行是纯增量，现有查询加一条 {@code loop_index = 0} 就等价于改造前。
     */
    private void recordNodeRound(long runId, String nodeKey, int round, NodeStatus status,
                                 String errorCode, java.time.Instant start, java.time.Instant end) {
        if (round < 1) {
            return;
        }
        try {
            String code = errorCode == null || errorCode.isBlank() ? null
                    : (errorCode.length() > 64 ? errorCode.substring(0, 64) : errorCode);
            int n = rm().insertNodeRound(tenantId, runId, nodeKey,
                    status.name().toLowerCase(),
                    java.sql.Timestamp.from(start), java.sql.Timestamp.from(end),
                    Jsons.obj("reason", "循环第 " + round + " 圈", "loopIndex", round),
                    code, round);
            if (n == 0) {
                log.error("[WARN] 记循环圈次失败（找不到汇总行）: " + nodeKey + " #" + round);
            }
        } catch (RuntimeException e) {
            log.error("[WARN] 记循环圈次失败: " + e.getMessage());
        }
    }

    /**
     * 本 run 已有的最大步骤序号（node_key = "S<数字>"）。
     *
     * <p>续跑时用它给新步骤接着编号，避免与"已完成的已完成步骤"撞
     * uk_run_node(run_id, node_key, attempt)。解析不出来（存量数据不是 S+n）就当 0。
     */
    private int maxStepSeq(long runId) {
        try {
            return rm().selectMaxStepSeq(tenantId, runId);
        } catch (RuntimeException e) {
            log.error("[WARN] 读取最大步骤序号失败，从 0 开始: " + e.getMessage());
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
        /**
         * 命令**真正下发到设备**的时刻（key 与 resourceByStep 相同）。
         *
         * <p>为什么单独记：命令台账是在命令**执行完之后**才写的，
         * 若 sent_at 用落库时刻（now()），它会与 finished_at 同毫秒 ⇒
         * 事后算不出「发出 → 设备响应」的时间差（2026-11-11 实测发现：
         * 库里所有命令的 sent_at == finished_at）。
         */
        private final Map<String, java.sql.Timestamp> sentAtByStep =
                new java.util.concurrent.ConcurrentHashMap<>();

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
            return qm().selectDeviceResourceByInstance(instanceId);
        }

        /** 当前 run id（用于设备锁归属）。 */
        private long runIdOf() {
            return runId;
        }

        /** 设备进入"运行中"。失败不影响执行主链路。 */
        private void markDeviceBusy(long resourceId, String method) {
            try {
                // 入参是 device_instance.id，Mapper 内部按 resource_node.id 落表
                // （直接比较会更新 0 行且不报错——这个坑踩过很多次）。
                dm().markRunning(tenantId, resourceId, method, null);
            } catch (RuntimeException ignore) {
                // 状态上报失败不能影响执行主链路
            }
        }

        /** 设备回到空闲（或错误）。 */
        private void markDeviceIdle(long resourceId, String lastError) {
            try {
                dm().markIdle(tenantId, resourceId,
                        lastError == null ? "idle" : "error", lastError);
            } catch (RuntimeException ignore) {
                // 状态上报失败不能影响执行主链路
            }
        }

        /**
         * 抢不到设备锁时补一句现场状态。
         *
         * <p>为什么需要：光说"设备被占用超过 N 秒"分不清两种完全不同的情况 ——
         * ① **被别的 run 占着**（正常，等一下就好）；② **这台设备自己有问题**
         * （压根没有状态行、已离线、已故障）⇒ 等满超时也是白等，
         * 现场看到的却是同一个消息 ⇒ 会误判成"别人占着设备"。
         * （2026-11-11 实测：用户的实验卡在「设备被占用超过 60 秒：229」，
         * 真相是那台设备**没有 device_state 行**，UPDATE 匹配 0 行 ⇒ 永远抢不到。）
         */
        private String deviceStateHint(long resourceNodeId) {
            try {
                java.util.Map<String, Object> row =
                        dm().selectDeviceStateRow(tenantId, resourceNodeId);
                if (row == null) {
                    return "（该设备没有状态记录：多半没接驱动，或心跳没覆盖到）";
                }
                Object st = row.get("status");
                Object on = row.get("online");
                Object holder = row.get("currentRunId");
                StringBuilder sb = new StringBuilder("（设备状态：");
                sb.append(st == null ? "未知" : st);
                sb.append(Boolean.TRUE.equals(on) ? "/在线" : "/离线");
                if (holder instanceof Number n && n.longValue() > 0) {
                    sb.append("；被 run #").append(n.longValue()).append(" 占用");
                }
                Object err = row.get("lastError");
                if (err != null && !err.toString().isBlank()) {
                    sb.append("；上次错误 ").append(err);
                }
                return sb.append("）").toString();
            } catch (RuntimeException ignore) {
                return "";   // 查不到状态就别添乱
            }
        }

        @Override
        public void onStepStart(long nodeId, int stepSeq, String method,
                                long resourceId, int attempt) {
            resourceByStep.put(nodeId + ":" + stepSeq + ":" + attempt, resourceId);
            // **level=0 汇总行实时回写 running**（2026-11-11 修）。
            //
            // 为什么：图执行路径有 `nodeStateRecorder` 回调实时改汇总行，
            // 而**顺序执行路径**（`sm.executeAll`，图解析失败时回退到它）没有 ⇒
            // 汇总行一直是 pending，直到整单结束才一次性回填
            // ⇒ 运行态流程图"执行中看不到任何变化、最后一次性变"
            // （2026-11-11 实测：顺序路径 8 秒时三行全 pending、16 秒整单结束
            //   才变成 completed/manual/completed）。
            //
            // ⚠ 只在**开始**时写 running，**终态仍由收尾统一回填**
            // （两条路径的终态语义并不完全一致，猜终态会写错）⇒ 零语义风险。
            try {
                Long parentRowId = nodeIdMap.get(nodeId);
                if (parentRowId != null) {
                    rm().updateRunNodeStatus(tenantId, parentRowId, "running");
                }
            } catch (RuntimeException ignore) {
                // 回写失败不影响执行主链路（状态只是展示用；mapper 不声明受检异常）
            }
            // 命令下发的时刻（拿完锁、即将下发这一刻）
            sentAtByStep.put(nodeId + ":" + stepSeq + ":" + attempt,
                    java.sql.Timestamp.from(java.time.Instant.now()));
            // 设备锁：抢不到就等。
            // resourceId 这里是 device_instance.id，DeviceLock 要 resource_node.id，
            // 所以先用 instanceId 查出 resource_id。
            //
            // ⚠ 等待预算**不再写死 60 秒**（2026-11-06）：
            //   改动前是 60_000 —— 与驱动层固定 30 秒超时是同一类 bug：
            //   设备方法耗时随机 30–300 秒（真机时序）时，前一条流程还占着设备，
            //   下一条流程等 60 秒就放弃 ⇒ 「设备被占用超过 60 秒」⇒ 节点转人工。
            //   现在按**该设备自己登记的方法超时上限**取：设备最多可能被合法占用
            //   多久（离心 900s / 孵育 1800s / 通用 600s），就等多久，
            //   下限 60 秒、上限 15 分钟。
            try {
                Long resNodeId = resourceIdOfInstance(resourceId);
                if (resNodeId != null) {
                    long waitMs = deviceLockWaitMs(resourceId);
                    com.labos.kernel.driver.DeviceLock lock =
                            new com.labos.kernel.driver.DeviceLock(db, tenantId);
                    if (!lock.acquireWithWait(resNodeId, runIdOf(), waitMs)) {
                        throw new IllegalStateException(
                                "设备被占用超过 " + (waitMs / 1000) + " 秒：" + resourceId
                                + deviceStateHint(resNodeId));
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
                // 命令**下发时刻**（key 与 onStepStart 一致：nodeId 是步骤节点 id）
                java.sql.Timestamp sentAt =
                        sentAtByStep.get(nodeId + ":" + stepSeq + ":" + attempt);
                writeStep(parentId, stepSeq, method, ok, errorCode, rawResponse,
                        durationMs, attempt, dev == null ? 0L : dev,
                        paramsByNode.getOrDefault(nodeId, Map.of()), sentAt);
            } catch (SQLException | RuntimeException e) {
                // 落库失败不能让执行中断：数据丢了比流程停了好
                log.error("[WARN] 步骤落库失败: " + e.getMessage());
            }
        }

        private void writeStep(Long parentId, int stepSeq, String method, boolean ok,
                               String errorCode, String rawResponse, long durationMs,
                               int attempt, long deviceId,
                               Map<String, Object> params,
                               java.sql.Timestamp sentAt) throws SQLException {
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
                        log.error("[PLATE-WARN] " + method + " dev=" + deviceId
                                + " 没有空层位，板无处安放（板号 " + plateId + "）");
                    } else if (moved != null
                            && moved == com.labos.kernel.driver.PositionLock.NO_PLATE_TO_TAKE) {
                        log.error("[PLATE-WARN] " + method + " dev=" + deviceId
                                + " 没有有板的层位，取不到板");
                    } else {
                        log.error("[PLATE] " + method + " dev=" + deviceId
                                + " plate=" + plateId + " -> pos=" + moved);
                    }
                } catch (Exception pe) {
                    log.error("[PLATE-ERR] " + method + " dev=" + deviceId
                            + " : " + pe.getClass().getSimpleName() + " " + pe.getMessage());
                }
            }
            Long rid = resolveResource(deviceId <= 0 ? null : deviceId,
                    deviceToResource, validResources);
            // error_code 也写进步骤节点：改动前只有 run_command 有，
            // 业务节点又被标成 manual（不是 failed），
            // 于是"第几步、因为什么码失败"只能去 run_command 表里翻。
            Long stepNodeId = rm().insertStepNode(tenantId, runId,
                    "S" + seq.incrementAndGet(), method, stepSeq, rid,
                    stepStatus(ok, errorCode), attempt, parentId, stepSeq,
                    errorCode == null || errorCode.isBlank() ? null
                            : (errorCode.length() > 64 ? errorCode.substring(0, 64) : errorCode),
                    Jsons.obj("durationMs", durationMs, "stepSeq", stepSeq));
            // payload 记实际下发的参数，便于事后核对"当时传了什么"
            java.util.List<Object> kv = new java.util.ArrayList<>();
            kv.add("nodeId");
            kv.add(parentId);
            kv.add("stepSeq");
            kv.add(stepSeq);
            params.forEach((k, v) -> { kv.add(k); kv.add(v); });
            // sent_at 用**下发时刻**（不是落库时刻）⇒ 事后能算出「发出 → 响应」间隔
            rm().insertRunCommand(tenantId, runId, stepNodeId, stepSeq, method,
                    Jsons.obj(kv.toArray()), stepStatus(ok, errorCode), attempt,
                    Jsons.obj("ok", ok, "durationMs", durationMs), rawResponse, errorCode,
                    deviceId, sentAt);
        }
    }

    private long createRun(WorkflowDef wf, String attendance) throws SQLException {
        // 加随机后缀：并发时同一毫秒会生成相同的 run_no，
        // 撞 uk_run_no 唯一约束（实测 4 并发有 2 个失败）。
        String runNo = "WF-" + LocalDateTime.now().format(RUN_FMT)
                + "-" + String.format("%04d", java.util.concurrent.ThreadLocalRandom
                        .current().nextInt(10000));
        return rm().insertRun(tenantId, runNo, wf.id(), attendance);
    }

    /** device_instance.id -> resource_node.id（驱动按实例 id 路由，落库要存资源节点 id）。 */
    private Map<Long, Long> loadDeviceResourceMap() throws SQLException {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (java.util.Map<String, Object> row : dm().selectInstanceResourcePairs()) {
            out.put(((Number) row.get("instanceId")).longValue(),
                    ((Number) row.get("resourceId")).longValue());
        }
        return out;
    }

    private Set<Long> loadResourceIds() throws SQLException {
        return new LinkedHashSet<>(dm().selectAllResourceIds());
    }

    /** 落父节点，返回 nodeId -> run_node.id 映射。 */
    /** 读 run 当前的值守模式（运行中可被切换）。 */
    private String liveAttendance(long runId, String fallback) {
        try {
            String v = qm().selectRunAttendance(tenantId, runId);
            if (v != null && !v.isBlank()) {
                return v;
            }
        } catch (RuntimeException e) {
            // 读不到就用启动时的值
        }
        return fallback;
    }

    /** 设备锁等待的下限（毫秒）：与改动前一致。 */
    private static final long DEVICE_LOCK_WAIT_MIN_MS = 60_000L;
    /** 设备锁等待的上限（毫秒）：15 分钟，再久就转人工更合适。 */
    private static final long DEVICE_LOCK_WAIT_MAX_MS = 900_000L;

    /**
     * 抢设备锁要等多久（毫秒）。
     *
     * <p>依据：<b>该设备登记的方法里最长的那个超时</b>
     * （{@code device_method.timeout_sec} 的最大值）——
     * 设备正被别的流程占用时，它最多可能被**合法**占用这么久
     * （离心 900s / 孵育 1800s / 通用保底 600s），
     * 等不到这个时长就不该再等，直接转人工。
     *
     * <p>为什么不再写死 60 秒：慢设备（真机时序 30–300 秒/次）会让
     * 紧接着的流程全部抢不到锁 ⇒ 「设备被占用超过 60 秒」⇒ 节点转人工。
     */
    private long deviceLockWaitMs(long instanceId) {
        Long sec = null;
        try {
            sec = qm().selectDeviceLockTimeoutSec(tenantId, instanceId);
        } catch (RuntimeException e) {
            sec = null;
        }
        long ms = (sec == null ? 0L : sec) * 1000L;
        return Math.min(DEVICE_LOCK_WAIT_MAX_MS, Math.max(DEVICE_LOCK_WAIT_MIN_MS, ms));
    }

    /**
     * 步骤/命令的落库状态。
     *
     * <p><b>为什么「被暂停中止」要记成 aborted 而不是 failed</b>：
     * 「设备执行中真停」（A1）里，命令是**被暂停打断**的，不是设备坏了 ——
     * 两者在数据上必须分得开：
     * <ul>
     *   <li>{@code aborted} = 被暂停中止 ⇒ 恢复时**重跑这一步**（可重试）；</li>
     *   <li>{@code failed} = 真失败 ⇒ 恢复时同样重跑，但现场看到的语义完全不同
     *       （界面上「已中止·可重试」vs「失败」）。</li>
     * </ul>
     * 判据是错误码 {@code EC-99-0005}（设备命令被中止）——
     * 它是执行侧 {@code driver.abort(commandId)} 唯一的产物。
     */
    private static String stepStatus(boolean ok, String errorCode) {
        if (ok) {
            return "completed";
        }
        return "EC-99-0005".equals(errorCode) ? "aborted" : "failed";
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
        try {
            // 复用 RunMapper.selectStatus（同一条 SQL，暂停边界判定与暂停协调共用）
            return "paused".equals(rm().selectStatus(tenantId, runId));
        } catch (RuntimeException e) {
            return false;   // 查不到就当作没暂停，不阻断执行
        }
    }

    /**
     * 执行在节点边界被暂停 —— 把 workflow 级 {@code run} 如实落成 paused。
     *
     * <p>不落的话下游 {@link #markBypassedNodes}/{@link #settleRun} 只认 {@code run.status}：
     * 前者会把"还没轮到的节点"标成 {@code bypassed}（看着像分支没选中），
     * 后者会把整单覆盖成 {@code completed} —— 用户点了实验级暂停，底层流程却显示"已完成"。
     *
     * <p>暂停类型/原因取覆盖该流程的现场扣留（实验级暂停落的就是它），
     * 与实验口径一致；页面暂停已经写过的值不覆盖（{@code status <> 'paused'}）。
     *
     * @param pendingKey 执行线程实际停住、尚未开始的节点。调用方事先写过
     *                   {@code pause_node_key} 时不覆盖；没写过则补上。
     *                   探针若自己把 key 填成「正在执行的那个节点」，会把真正的边界盖掉，
     *                   所以真边界暂停的探针必须把这一列留空。
     */
    private void pauseRunAtBoundary(long runId, long workflowId, String pendingKey)
            throws SQLException {
        String kind = RuntimeHold.Kind.MANUAL.code();
        String reason = "在节点边界被暂停，待恢复后续跑";
        RuntimeHold.Active a = new RuntimeHold(db, tenantId).coveringForWorkflow(workflowId);
        if (a != null) {
            if (a.kind() != null && !a.kind().isBlank()) {
                kind = a.kind();
            }
            if (a.reason() != null && !a.reason().isBlank()) {
                reason = a.reason();
            }
        }
        rm().pauseRunAtBoundary(tenantId, runId, kind, reason, pendingKey);
        // 页面暂停 / e2e 探针在动作进行中就把 status 写成了 paused。
        // 上面那条 WHERE status <> 'paused' 因此更新 0 行，pause_node_key 仍是空。
        // 边界到达后补上执行线程看到的待执行节点；已有值不覆盖。
        if (pendingKey != null && !pendingKey.isBlank()) {
            rm().fillPauseNodeKey(tenantId, runId, pendingKey);
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

        String status;
        if (aborted) {
            status = "failed";
            runMapper.settleTerminal(tenantId, runId, status);
        } else if (manual) {
            // 转人工 = 暂停，不是失败
            status = "paused";
            runMapper.settleManual(tenantId, runId);
        } else {
            status = "completed";
            runMapper.settleTerminal(tenantId, runId, status);
        }
        // P-09：执行体收尾（含转人工的 paused）—— 但真正的"暂停等我点继续"
        // 走的是上面 isRunPaused 的 early return，那种单不落 SETTLED。
        if (!"paused".equals(status)) {
            try {
                runMapper.markSettledPhase(tenantId, runId);
            } catch (RuntimeException e) {
                // 阶段标记是旁路事实，写不上不能把已经跑完的单标成失败。
                log.warn("run#{} exec_phase 未落 SETTLED：{}", runId, e.getMessage());
            }
        }
        if (manual) {
            // 台账 6.5：有人值守时落一条待决窗口。放在状态 UPDATE 之后 ——
            // run 已经是 paused/waiting_human，开窗失败也不能把主流程带崩；
            // 执行线程随即返回，不占岛锁、不阻塞调度器。
            openManualWindow(runId, p);
        }
    }

    /**
     * 有人值守窗口（台账 6.5 / §13.8）：只有 attended 且转人工才落窗口。
     *
     * <p>默认项取命中的 {@code recovery_policy.strategy}：触发开窗时决策结果恒为
     * MANUAL，若拿它当默认项，弹窗就没有「可改选」的意义（§2.2）。
     * 无人值守沿用原路径：只由 {@code recordOccurrences} 记 error 告警，不写窗口。
     */
    /** 包可见：连库自测直接验证「转人工 → 落窗口」这条接线。 */
    void openManualWindow(long runId, RunProgress p) {
        try {
            if (!ManualWindow.isAttended(liveAttendance(runId, ManualWindow.ATTENDED))) {
                return;
            }
            NodeProgress hit = p.all().stream()
                    .filter(n -> n.status() == NodeStatus.MANUAL
                            || n.status() == NodeStatus.SKIPPED)
                    .reduce((a, b) -> b)
                    .orElse(null);
            String errorCode = (hit == null || hit.lastErrorCode() == null
                    || hit.lastErrorCode().isBlank()) ? "EC-UNKNOWN" : hit.lastErrorCode();
            String nodeKey = hit == null ? "" : String.valueOf(hit.nodeId());
            String reason = (hit == null || hit.lastReason().isBlank())
                    ? "执行到需要人工确认的节点" : hit.lastReason();

            List<RecoveryPolicy> policies =
                    new JdbcRecoveryPolicyRepository(db, tenantId).loadAll();
            // 策略可能配在 command / node / run / device 任一层级；固定在 NODE 上解析
            // 会让库里 @command 的策略永远匹配不上，默认项就退化成恒定 MANUAL。
            // 逐层级试，取第一条命中的（resolver 已按精度 + priority 排序，确定性可回放）。
            var resolver = new RecoveryPolicyResolver(policies);
            RecoveryPolicy matched = null;
            for (RecoveryScope scope : RecoveryScope.values()) {
                var found = resolver.resolve(ErrorContext.of(errorCode, scope, 0));
                if (found.isPresent()) {
                    matched = found.get();
                    break;
                }
            }
            String strategy = matched == null ? null : matched.strategy().name();

            new JdbcManualWindowStore(db, tenantId).open(
                    runId,
                    ManualWindow.windowKey(runId, errorCode, nodeKey),
                    errorCode, nodeKey, strategy,
                    ManualWindow.defaultActionOf(matched),
                    ManualWindow.allowedActions(),
                    reason,
                    30);
        } catch (Exception e) {
            // 开窗是旁路：失败只留日志，人仍可到暂停队列页处置，不影响 run 的暂停态
            log.error("[manual-window] 开窗失败 run=" + runId + ": " + e);
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
        // 改动前是"流程结束时把最后一个执行过的设备标记为有板"，和层位级逻辑并行，
        // 结果是一台设备 has_plate=true 但所有层位都空——后续 Withdraw 取不到板。
        //
        // 现在规则单一：**该设备下任一层位有板 → has_plate=true**（PositionLock.refreshHasPlate）。
        // 没有层位的设备永远是"没板"，因为它没地方放板——这是正确语义，不是缺陷。
    }

    private void recordOccurrences(long runId, RunProgress p, JdbcRecoveryPolicyRepository repo,
                                   Map<Long, Long> deviceToResource, Set<Long> validResources,
                                   String attendance,
                                   Map<Long, Long> nodeIdToRunNodeId) throws SQLException {
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
            // ⚠ **run_node_id 必须是 run_node.id，不是 workflow_node.id**（2026-11-11 修）。
            // `np.nodeId()` 是**流程节点定义 id**（workflow_node.id，来自
            // `GraphRunner.nodeId(node)`：优先用库里的 workflow_node.id）⇒ 直接塞进
            // `error_occurrence.run_node_id` 会被外键
            // `error_occurrence_run_node_id_fkey`（→ run_node(id)）拒掉：
            // 报错形如「Key (run_node_id)=(1001186) is not present in table run_node」
            // —— 1001186 其实是 **workflow_node.id**（实测 workflow_node 最大 id 1001246
            // > run_node 最大 id 1000858，两个 id 空间**重叠但不同**）。
            // 转换靠 `createNodeShells` 返回的映射（nodeId → run_node.id）；
            // 映射不到就置空（外键允许 null）⇒ 宁可少一个关联，也不让整次执行炸掉。
            Long runNodeId = nodeIdToRunNodeId == null ? null : nodeIdToRunNodeId.get(np.nodeId());
            store.record(new Occurrence(runId, runNodeId, mapped,
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
        if (graphMapper == null) {
            throw new IllegalStateException("GraphMapper 未注入");
        }
        // 与 saveGraph 的其余写入同处一个 @Transactional：
        // 备份成功但保存失败时，两者一起回滚，历史里不会多出一条假版本。
        graphMapper.backupGraph(tenantId, workflowId);
    }

    /**
     * 新建一张空白流程（只有 开始 → 结束），返回 id。
     *
     * <p>为什么需要：界面里改动前**没有任何创建流程的入口** —— 流程库只能看、
     * 实验编排的"添加流程"只能选已有的。用户建完实验想顺手画一条流程，无处可去。
     *
     * <p>code 不传就用 {@code AUTO-<时间戳>}：流程编号在库里是唯一键，人工编号容易撞。
     */
    /** A 批：Spring 声明式事务。checked 异常也要回滚（默认只回滚 RuntimeException）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public long createWorkflow(String name, String code, String category, Long islandId)
            throws SQLException {
        String nm = (name == null || name.isBlank()) ? "未命名流程" : name.trim();
        String cd = (code == null || code.isBlank())
                ? "AUTO-" + System.currentTimeMillis() % 100000000L
                : code.trim();
        String cat = (category == null || category.isBlank()) ? "choreography" : category.trim();
        if (workflowMapper == null) {
            throw new IllegalStateException(
                    "WorkflowService 必须由 Spring 容器创建（WorkflowMapper 未注入）");
        }
        // P-01：岛归属是权威执行边界，给了就必须是资源树里真实存在的 island。
        if (islandId != null && islandId > 0
                && workflowMapper.countIsland(tenantId, islandId) == 0) {
            throw new IllegalArgumentException(
                    "islandId " + islandId + " 不是 resource_node 里的岛节点");
        }
        com.labos.kernel.persistence.WorkflowRow row =
                new com.labos.kernel.persistence.WorkflowRow();
        row.setTenantId(tenantId);
        row.setCode(cd);
        row.setName(nm);
        row.setCategory(cat);
        row.setIslandId(islandId != null && islandId > 0 ? islandId : null);
        workflowMapper.insertWorkflow(row);
        long id = row.getId();
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
        return saveGraph(workflowId, xml, false);
    }

    /**
     * 校验「存进来的是一张能跑的图」——不通过就拒绝保存。
     *
     * <p>为什么不在前端拦：设计器保存是整图覆盖，前端漏校验/被绕过时会留下
     * 「节点孤立、没有开始/结束」的垃圾流程（2026-11-08 用户在一个测试残留里
     * 看到过：节点都在、线一条没连，页面却显示已保存）。产品侧必须兜底。
     *
     * <p>中间态（自动化逐边保存、编辑器暂存）可用 {@code ?draft=1} 放行；
     * 草稿图不允许被执行（执行入口另有 preflight/compile 拦截）。
     */
    private static void validateGraphShape(BpmnGraphParser.ParsedGraph graph) {
        long starts = graph.nodes().stream()
                .filter(n -> "start".equals(n.type())).count();
        long ends = graph.nodes().stream()
                .filter(n -> "end".equals(n.type())).count();
        if (starts != 1 || ends != 1) {
            throw new IllegalArgumentException(
                    "流程必须恰好 1 个开始节点和 1 个结束节点（当前 " + starts + " / " + ends + "）");
        }
        java.util.Map<String, Integer> degree = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        for (BpmnGraphParser.ParsedNode n : graph.nodes()) {
            degree.put(n.key(), 0);
            names.put(n.key(), n.name() == null ? n.key() : n.name());
        }
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<String>> in = new java.util.LinkedHashMap<>();
        for (BpmnGraphParser.ParsedEdge e : graph.edges()) {
            if (!degree.containsKey(e.from()) || !degree.containsKey(e.to())) {
                continue;
            }
            degree.merge(e.from(), 1, Integer::sum);
            degree.merge(e.to(), 1, Integer::sum);
            out.computeIfAbsent(e.from(), k -> new java.util.ArrayList<>()).add(e.to());
            in.computeIfAbsent(e.to(), k -> new java.util.ArrayList<>()).add(e.from());
        }
        java.util.List<String> isolated = new java.util.ArrayList<>();
        for (BpmnGraphParser.ParsedNode n : graph.nodes()) {
            if (("start".equals(n.type()) || "end".equals(n.type()))) {
                continue;
            }
            if (degree.getOrDefault(n.key(), 0) == 0) {
                isolated.add(names.get(n.key()) + "(" + n.key() + ")");
            }
        }
        if (!isolated.isEmpty()) {
            throw new IllegalArgumentException(
                    "图里有 " + isolated.size() + " 个孤立节点（没有任何连线）："
                            + String.join(", ", isolated)
                            + "。请先连线或删除后再保存（自动化中间态可用 ?draft=1）");
        }
        // 从 start 可达
        String startKey = graph.nodes().stream()
                .filter(n -> "start".equals(n.type())).findFirst().map(BpmnGraphParser.ParsedNode::key)
                .orElse(null);
        java.util.Set<String> reach = new java.util.HashSet<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        if (startKey != null) {
            stack.push(startKey);
            reach.add(startKey);
        }
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (String nx : out.getOrDefault(cur, java.util.List.of())) {
                if (reach.add(nx)) {
                    stack.push(nx);
                }
            }
        }
        java.util.List<String> unreachable = new java.util.ArrayList<>();
        for (BpmnGraphParser.ParsedNode n : graph.nodes()) {
            if (!reach.contains(n.key())) {
                unreachable.add(names.get(n.key()) + "(" + n.key() + ")");
            }
        }
        if (!unreachable.isEmpty()) {
            throw new IllegalArgumentException(
                    "有节点从开始节点走不到：" + String.join(", ", unreachable)
                            + "。请补连线后再保存（自动化中间态可用 ?draft=1）");
        }
        // 都能到 end
        String endKey = graph.nodes().stream()
                .filter(n -> "end".equals(n.type())).findFirst().map(BpmnGraphParser.ParsedNode::key)
                .orElse(null);
        java.util.Set<String> back = new java.util.HashSet<>();
        java.util.Deque<String> st2 = new java.util.ArrayDeque<>();
        if (endKey != null) {
            st2.push(endKey);
            back.add(endKey);
        }
        while (!st2.isEmpty()) {
            String cur = st2.pop();
            for (String pv : in.getOrDefault(cur, java.util.List.of())) {
                if (back.add(pv)) {
                    st2.push(pv);
                }
            }
        }
        java.util.List<String> deadEnd = new java.util.ArrayList<>();
        for (BpmnGraphParser.ParsedNode n : graph.nodes()) {
            if (!back.contains(n.key())) {
                deadEnd.add(names.get(n.key()) + "(" + n.key() + ")");
            }
        }
        if (!deadEnd.isEmpty()) {
            throw new IllegalArgumentException(
                    "有节点走不到结束节点：" + String.join(", ", deadEnd)
                            + "。请补连线后再保存（自动化中间态可用 ?draft=1）");
        }
    }

    /** A 批：Spring 声明式事务。checked 异常也要回滚（默认只回滚 RuntimeException）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public SaveResult saveGraph(long workflowId, String xml, boolean draft) throws SQLException {
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

        if (!draft) {
            validateGraphShape(graph);
        }

        // ② 保存前备份上一版图：出问题能查回上一版
        backupGraph(workflowId);

        Map<String, Long> methodCap = loadMethodCapability();

        {
                // 1) 现有节点
                Set<String> existing = new LinkedHashSet<>();
                Map<String, Long> capByKey = new LinkedHashMap<>();
                for (Map<String, Object> row : graphMapper.selectNodeCaps(workflowId)) {
                    String k = String.valueOf(row.get("nodeKey"));
                    existing.add(k);
                    Object cap = row.get("capabilityId");
                    if (cap instanceof Number n) {
                        capByKey.put(k, n.longValue());
                    }
                }

                // 2) 清掉上一版边
                graphMapper.deleteEdges(workflowId);

                // 3) 增量 upsert 节点
                int added = 0;
                int updated = 0;
                int unlabeled = 0;
                // 设备绑定与顺序**同时落表**（ROADMAP M9 前置债）：
                // 读侧只查列，不再每次解析 XML；seq 用文档顺序，保证两次运行一致。
                Map<String, Integer> docOrder = BpmnGraphParser.documentOrder(xml,
                        graph.nodes().stream().map(BpmnGraphParser.ParsedNode::key).toList());

                for (BpmnGraphParser.ParsedNode n : graph.nodes()) {
                    Integer seq = docOrder.get(n.key());
                    boolean isExisting = existing.contains(n.key());
                    Long cap = isExisting ? null
                            : (n.protocolName() == null ? null
                                    : methodCap.get(n.protocolName()));

                    // params 两个形态必须分开：
                    //  - 新节点：带 origin=bpmn_editor（编辑器来源标记）；
                    //  - 老节点：只回写 protocol_name / method_params。
                    //    节点设置里选完设备/方法只 patch 了图 XML（见
                    //    QueryService.patchNodeAttrs），再点页面级保存走的是这条
                    //    合并路径；若这里不回写，XML 里的 protocolName 永远进不了
                    //    workflow_node（实测：绑完设备再保存，params->>'protocol_name'
                    //    仍是空串）。
                    String mp = n.methodParams();
                    String proto = n.protocolName() == null ? "" : n.protocolName();
                    String insertParams = mp == null || mp.isBlank()
                            ? Jsons.obj("protocol_name", proto, "origin", "bpmn_editor")
                            : Jsons.obj("protocol_name", proto,
                                    "method_params", new Jsons.Raw(mp),
                                    "origin", "bpmn_editor");
                    String updateParams = mp == null || mp.isBlank()
                            ? Jsons.obj("protocol_name", proto)
                            : Jsons.obj("protocol_name", proto,
                                    "method_params", new Jsons.Raw(mp));

                    graphMapper.upsertNode(tenantId, workflowId, n.key(), n.name(),
                            n.type(), cap, insertParams, updateParams, n.resourceId(), seq);

                    if (isExisting) {
                        updated++;
                        if (!capByKey.containsKey(n.key()) && "task".equals(n.type())) {
                            unlabeled++;
                        }
                    } else {
                        added++;
                        if (cap == null && "task".equals(n.type())) {
                            unlabeled++;
                        }
                    }
                }

                // 4) 删除不在新图里的节点
                List<String> keep = graph.nodes().stream()
                        .map(BpmnGraphParser.ParsedNode::key).toList();
                int removed = 0;
                if (!keep.isEmpty()) {
                    removed = graphMapper.deleteNodesNotIn(workflowId,
                            new ArrayList<>(keep));
                }

                // 5) 重建边（逐条 INSERT ... ON CONFLICT DO NOTHING）
                for (BpmnGraphParser.ParsedEdge e : graph.edges()) {
                    if (!keep.contains(e.from()) || !keep.contains(e.to())) {
                        continue;
                    }
                    graphMapper.insertEdge(tenantId, workflowId, e.from(), e.to());
                }

                // 6) 保存原始 XML
                graphMapper.updateWorkflowGraph(tenantId, workflowId, xml);

                return new SaveResult(graph.nodes().size(), graph.edges().size(),
                        added, updated, removed, unlabeled);
        }
    }

    /** protocol_name -> capability（多数表决），用于新增节点自动标能力。 */
    private Map<String, Long> loadMethodCapability() throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : dm().selectMethodCapabilityVotes()) {
            if (((Number) row.get("rn")).intValue() == 1) {
                out.putIfAbsent((String) row.get("method"),
                        ((Number) row.get("capabilityId")).longValue());
            }
        }
        return out;
    }

}
