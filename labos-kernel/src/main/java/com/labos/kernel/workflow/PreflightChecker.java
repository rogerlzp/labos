package com.labos.kernel.workflow;

import com.labos.kernel.engine.ExecutionListener;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.engine.StepExecutor;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.RealWorkflowRunner.CapabilitySource;
import com.labos.kernel.workflow.WorkflowGraphParser.GEdge;
import com.labos.kernel.workflow.WorkflowGraphParser.GNode;
import com.labos.kernel.workflow.WorkflowGraphParser.Graph;
import com.labos.kernel.workflow.WorkflowGraphParser.Kind;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 保存前预检：在不碰设备的前提下回答「这个流程能不能跑」。
 *
 * <p>两类检查：
 * <ol>
 *   <li><b>静态</b>：能力是否有设备能接、异或是否有出口规则、有没有开始事件</li>
 *   <li><b>干跑</b>：用空执行器按图推一遍，看哪些节点永远走不到</li>
 * </ol>
 *
 * <p>干跑是关键——它直接复用 {@link GraphRunner} 的推进逻辑，
 * 所以能发现「异或只走一条，但下游并行汇合等两条」这种结构性死锁，
 * 静态分析很难查出来。
 *
 * <p>性能：全量流程全量干跑约 2 秒（纯内存，不碰设备）。
 */
public final class PreflightChecker {

    /** 延迟取 Mapper 代理（本类是 new 出来的，无构造注入；见 Mappers）。 */
    private static com.labos.kernel.persistence.QueryMapper mappers() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class);
    }

    public enum Level { ERROR, WARN, INFO }

    public record Issue(Level level, String code, String nodeKey, String message) {
    }

    public record Report(
            long workflowId,
            String workflowName,
            int taskCount,
            int reachableCount,
            int defaultEdges,
            List<Issue> issues) {

        public boolean blocking() {
            return issues.stream().anyMatch(i -> i.level() == Level.ERROR);
        }

        public long errors() {
            return issues.stream().filter(i -> i.level() == Level.ERROR).count();
        }

        public long warnings() {
            return issues.stream().filter(i -> i.level() == Level.WARN).count();
        }
    }

    /** 不碰设备的执行器：所有步骤视为成功。 */
    private static final StepExecutor DRY_RUN = ctx -> {
    };

    private final DbConfig db;
    private final long tenantId;

    // 全量预检时这些数据只该加载一次，否则每个流程都会做一次全表查询
    private Map<String, List<Long>> capPoolCache;
    private Map<String, String> protocolCapCache;
    private Map<Long, String> resourceCapCache;
    private RecoveryDecider deciderCache;

    public PreflightChecker(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    private Map<String, List<Long>> capPool() throws SQLException {
        if (capPoolCache == null) {
            capPoolCache = RealWorkflowRunner.loadCapabilityPool(db);
        }
        return capPoolCache;
    }

    private Map<String, String> protocolCap() throws SQLException {
        if (protocolCapCache == null) {
            protocolCapCache = RealWorkflowRunner.loadProtocolCapability(db);
        }
        return protocolCapCache;
    }

    /** 资源/实例 -&gt; 能力（绑定设备的能力优先于协议反查）。 */
    private Map<Long, String> resourceCapability() throws SQLException {
        if (resourceCapCache == null) {
            resourceCapCache = RealWorkflowRunner.loadResourceCapability(db);
        }
        return resourceCapCache;
    }

    private RecoveryDecider decider() throws SQLException {
        if (deciderCache == null) {
            JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(db, tenantId);
            deciderCache = new RecoveryDecider(new RecoveryPolicyResolver(repo.loadAll()));
        }
        return deciderCache;
    }

    public Report check(long workflowId) throws SQLException {
        return check(workflowId, loadXml(workflowId), loadCapabilityByKey(workflowId),
                loadWorkflowName(workflowId));
    }

    /** 供全量预检复用：XML 与能力映射由调用方批量预加载。 */
    public Report check(long workflowId, String xml,
                        Map<String, String> explicitCap, String workflowName) {
        try {
            return doCheck(workflowId, xml, explicitCap, workflowName);
        } catch (SQLException e) {
            List<Issue> one = new ArrayList<>();
            one.add(new Issue(Level.ERROR, "PF-08", null, "预检失败: " + e.getMessage()));
            return new Report(workflowId, workflowName, 0, 0, 0, one);
        }
    }

    private Report doCheck(long workflowId, String xml,
                           Map<String, String> explicitCap, String workflowName)
            throws SQLException {
        String name = workflowName == null ? "" : workflowName;
        {
            String found = mappers().selectWorkflowName(tenantId, workflowId);
            if (found != null) {
                name = found;
            }
        }

        List<Issue> issues = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            issues.add(new Issue(Level.ERROR, "PF-08", null, "该流程没有 BPMN 图，无法执行"));
            return new Report(workflowId, name, 0, 0, 0, issues);
        }

        Graph graph;
        try {
            graph = WorkflowGraphParser.parse(xml);
        } catch (RuntimeException e) {
            issues.add(new Issue(Level.ERROR, "PF-08", null, "BPMN 解析失败: " + e.getMessage()));
            return new Report(workflowId, name, 0, 0, 0, issues);
        }

        // tasks 只收设备任务（能力/设备检查、任务数统计只认它）；
        // 但"有没有可执行的东西"要把内置逻辑节点（`__setVar` / `__sleep` / `__wait` …）
        // 一起算 —— 内置节点也是一步活，只含内置节点的流程是合法流程，
        // 不该被判"流程里没有任务节点"（PF-07，此前会误报）。
        List<GNode> tasks = new ArrayList<>();
        boolean hasExecutable = false;
        for (GNode n : graph.nodes().values()) {
            if (n.kind() == Kind.TASK) {
                tasks.add(n);
                hasExecutable = true;
            } else if (n.kind() == Kind.BUILTIN) {
                hasExecutable = true;
            }
        }
        if (!hasExecutable) {
            issues.add(new Issue(Level.ERROR, "PF-07", null,
                    "流程里没有可执行节点（设备任务或内置逻辑节点）"));
            return new Report(workflowId, name, 0, 0, 0, issues);
        }

        // ---- 静态检查 1：能力与设备 ----
        Map<String, List<Long>> capPool = capPool();
        Map<String, String> protocolCap = protocolCap();
        Map<Long, String> resourceCap = resourceCapability();
        Map<String, Integer> capUsage = new LinkedHashMap<>();
        for (GNode t : tasks) {
            CapabilitySource cs = RealWorkflowRunner.resolveCapability(
                    t.name(), t.method(), t.resourceId(), explicitCap.get(t.key()),
                    protocolCap, resourceCap);
            String cap = cs.capability();

            if (RealWorkflowRunner.LOGIC.equals(cap)) {
                continue;   // 逻辑节点不占设备
            }
            if ("UNKNOWN".equals(cap)) {
                issues.add(new Issue(Level.ERROR, "PF-01", t.key(),
                        String.format("节点「%s」没有可用的能力标注（方法：%s），无法分配设备",
                                t.name(), t.method() == null ? "空" : t.method())));
                continue;
            }
            capUsage.merge(cap, 1, Integer::sum);
            List<Long> pool = capPool.getOrDefault(cap, List.of());
            if (pool.isEmpty()) {
                issues.add(new Issue(Level.ERROR, "PF-02", t.key(),
                        String.format("节点「%s」需要 %s 能力，但当前没有任何设备提供",
                                t.name(), cap)));
            }
        }

        // ---- 静态检查 1.5：绑定的设备 + 岛边界（执行前的配置快照重校验）----
        //
        // 为什么必须有：绑定的 id 有**两套空间** —— 资源号（resource_node.id）与
        // 设备实例号（device_instance.id）。执行器按**资源号**查（loadBoundInstances
        // 是 resource→instance），于是：
        //   · 存成**实例号**的绑定 ⇒ 查不到 ⇒ 节点静默改按能力轮转挑别的设备；
        //   · 两个空间**都查不到**的 ⇒ 绑定的设备已经没了 ⇒ 同样静默换设备。
        // 两种都不报错，只是"跑的不是你绑的那台" ⇒ 从节点状态上看不出来。
        // 岛边界同理：流程有权威岛时，绑了岛外设备 ⇒ 执行期会被拒绝（P-05），
        // 岛内没有该能力的设备 ⇒ 该节点会转人工 —— 这两件都该在执行**之前**说清。
        issues.addAll(checkBindingsAndIsland(workflowId, tasks, explicitCap,
                protocolCap, resourceCap));

        // ---- 静态检查 2：异或出口规则 ----
        for (GNode n : graph.nodes().values()) {
            if (n.kind() != Kind.SPLIT_XOR) {
                continue;
            }
            List<GEdge> outs = graph.out(n.key());
            boolean hasCond = outs.stream().anyMatch(e -> e.condition() != null);
            boolean hasDefault = outs.stream().anyMatch(GEdge::isDefault);
            if (!hasCond && !hasDefault) {
                issues.add(new Issue(Level.WARN, "PF-04", n.key(),
                        String.format("异或网关有 %d 条出边，既没有条件表达式也没有默认边；"
                                + "执行时会走排序第一条（%s）", outs.size(),
                                outs.isEmpty() ? "无出边" : minKey(outs))));
            }
        }

        // ---- 静态检查 2.5：条件表达式语法 ----
        // 表达式写错时运行时是"走 default 并记日志"，不会报错 —— 现场很难发现。
        // 预检把它提前暴露出来（只验语法，不验类型：变量值运行时才知道）。
        for (GEdge e : graph.edges()) {
            if (e.condition() == null || e.condition().isBlank()) {
                continue;
            }
            try {
                com.labos.kernel.engine.ConditionEvaluator.validate(e.condition());
            } catch (RuntimeException ex) {
                issues.add(new Issue(Level.WARN, "PF-09", e.from(),
                        String.format("边 %s→%s 的条件表达式语法有问题：%s（执行时会走默认边）",
                                e.from(), e.to(), ex.getMessage())));
            }
        }

        // ---- 静态检查 3：开始事件 ----
        boolean hasStart = graph.nodes().values().stream().anyMatch(n -> n.kind() == Kind.START);
        if (!hasStart) {
            issues.add(new Issue(Level.WARN, "PF-06", null,
                    "没有开始事件，执行时会从入度为 0 的节点起算"));
        }

        // ---- 干跑：哪些节点永远走不到 ----
        RunStateMachine sm = new RunStateMachine(decider(), ExecutionListener.NOOP,
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        // 干跑只问「哪些节点走得到」：内置节点走直通（不执行、不取值）。
        // 这里既没注入 builtinParams、也没有真实外部变量，真执行的话
        // `__sleep` 缺参抛异常 / `__wait` 必然超时，都会让整图 break，
        // 把**下游真实设备任务**误判成 PF-03「永远走不到」。
        GraphRunner runner = new GraphRunner(sm, capPool, protocolCap, explicitCap, s -> { })
                .withDryRun(true);

        RunProgress unused = runner.run(graph, DRY_RUN);
        Set<String> activated = runner.activatedKeys();

        // 每个走不到的节点单独一条，前端据此在设计器里标红
        for (GNode t : tasks) {
            if (!activated.contains(t.key())) {
                issues.add(new Issue(Level.WARN, "PF-03", t.key(),
                        String.format("节点「%s」永远不会被执行：从起点出发的路径走不到它", t.name())));
            }
        }

        // ---- 干跑暴露的汇合死锁 ----
        for (GNode n : graph.nodes().values()) {
            if (n.kind() != Kind.JOIN_PARALLEL) {
                continue;
            }
            long arrived = graph.in(n.key()).stream()
                    .filter(e -> activated.contains(e.from())).count();
            int total = graph.in(n.key()).size();
            if (arrived < total) {
                issues.add(new Issue(Level.WARN, "PF-05", n.key(),
                        String.format("并行汇合点只有 %d/%d 条入边能到达，"
                                + "执行到此处会停住（下游不继续）", arrived, total)));
            }
        }

        int reachableTasks = (int) tasks.stream().filter(t -> activated.contains(t.key())).count();
        return new Report(workflowId, name, tasks.size(), reachableTasks,
                runner.defaultEdgeCount(), issues);
    }

    /**
     * 绑定的设备 + 岛边界（执行前的**配置快照重校验**）。
     *
     * <p>为什么单独一套：绑定的 id 有**两套空间** —— 资源号（{@code resource_node.id}）
     * 与设备实例号（{@code device_instance.id}）。执行器按**资源号**查
     * （{@code loadBoundInstances} 是 resource→instance），所以：
     * <ul>
     *   <li>存成**实例号**的绑定 ⇒ 查不到 ⇒ 节点静默改按能力轮转挑别的设备；</li>
     *   <li>两个空间**都查不到**的 ⇒ 绑定的设备已经没了 ⇒ 同样静默换设备。</li>
     * </ul>
     * 两种都不报错，只是"跑的不是你绑的那台" ⇒ 预检把它显式说出来。
     *
     * <p>岛边界（P-05）：流程有权威岛时
     * <ul>
     *   <li>绑定的设备不在岛内 ⇒ 执行期会被**拒绝**（PF-12）；</li>
     *   <li>某能力在岛内没有设备 ⇒ 该节点执行时会**转人工**（PF-13）。</li>
     * </ul>
     * 两者都是"执行之前就该知道"的事，不该等跑到一半才暴露。
     */
    private List<Issue> checkBindingsAndIsland(long workflowId, List<GNode> tasks,
                                               Map<String, String> explicitCap,
                                               Map<String, String> protocolCap,
                                               Map<Long, String> resourceCap)
            throws SQLException {
        List<Issue> out = new ArrayList<>();
        Long islandId = IslandBindingGate.authoritativeIslandId(db, tenantId, workflowId);
        try {
            // 判定逻辑在 IslandBindingGate（预检与实验派发共用同一套口径与文案）；
            // 这里只负责把"当前资源树"快照喂给它（能力映射本类已缓存，不重复查）。
            IslandBindingGate.Context ctx = IslandBindingGate.load(
                    db, tenantId, islandId, capPool(), protocolCap, resourceCap);
            for (IslandBindingGate.Violation v
                    : IslandBindingGate.violations(ctx, tasks, explicitCap)) {
                out.add(new Issue(v.level(), v.code(), v.nodeKey(), v.message()));
            }
        } catch (RuntimeException e) {
            // 读不到就**说读不到**，不能当成"检查通过"（fail-closed：宁可多报一条）
            out.add(new Issue(Level.WARN, "PF-14", null,
                    "读设备资源关系失败，本次跳过绑定与岛边界检查：" + e.getMessage()));
        }
        return out;
    }

    /** resource_node.id → device_instance.id（租户内、未软删）。 */
    private Map<Long, Long> loadResourceInstancePairs() throws SQLException {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : deviceMappers().selectResourceInstancePairs(tenantId)) {
            out.put(((Number) row.get("resourceId")).longValue(),
                    ((Number) row.get("instanceId")).longValue());
        }
        return out;
    }

    private static com.labos.kernel.persistence.DeviceStateMapper deviceMappers() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceStateMapper.class);
    }

    /**
     * 流程的**权威岛**（{@code workflow.island_id}，P-01 落的列）。
     *
     * @return 权威岛 id；没有则 null（1036 个旧流程都这样）
     * @deprecated 统一走 {@link IslandBindingGate#authoritativeIslandId}（预检与实验
     *             派发共用同一套口径，避免两处实现漂移）
     */
    @Deprecated
    private Long authoritativeIslandId(long workflowId) {
        return IslandBindingGate.authoritativeIslandId(db, tenantId, workflowId);
    }

    // ---------------------------------------------------------------- 数据

    private String loadXml(long workflowId) {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.WorkflowWebMapper.class)
                .selectWorkflowXml(tenantId, workflowId);
    }

    private String loadWorkflowName(long workflowId) {
        String n = mappers().selectWorkflowName(tenantId, workflowId);
        return n == null ? "" : n;
    }

    private Map<String, String> loadCapabilityByKey(long workflowId) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class)
                .selectNodeCapabilityCodes(tenantId, workflowId)) {
            out.put((String) row.get("nodeKey"), (String) row.get("capabilityCode"));
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private static String minKey(List<GEdge> outs) {
        return outs.stream().map(GEdge::to).sorted().findFirst().orElse("");
    }

    private static List<String> head(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    /** 全量预检：给"一键体检"用。 */
    public Map<String, Object> checkAll() throws SQLException {
        List<Long> ids = new ArrayList<>(mappers().selectAllWorkflowIds(tenantId));

        // 批量预加载：避免每个流程各建两次连接（跨网络约 100ms/次）
        Map<Long, String> xmlByWf = new LinkedHashMap<>();
        Map<Long, Map<String, String>> capByWf = new LinkedHashMap<>();
        Map<Long, String> nameByWf = new LinkedHashMap<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class)
                .selectPreflightWorkflowRows(tenantId)) {
            long id = ((Number) row.get("workflowId")).longValue();
            nameByWf.putIfAbsent(id, (String) row.get("name"));
            String xml = (String) row.get("xml");
            if (xml != null && !xml.isBlank()) {
                xmlByWf.putIfAbsent(id, xml);
            }
            String key = (String) row.get("nodeKey");
            String code = (String) row.get("capabilityCode");
            if (key != null && code != null) {
                capByWf.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(key, code);
            }
        }

        int ok = 0;
        int blocked = 0;
        int warned = 0;
        Set<String> codeStat = new LinkedHashSet<>();
        Map<String, Integer> byCode = new LinkedHashMap<>();
        List<Long> blockedIds = new ArrayList<>();
        List<Long> warnIds = new ArrayList<>();
        List<String> warnSamples = new ArrayList<>();

        for (Long id : ids) {
            Report r = check(id, xmlByWf.get(id),
                    capByWf.getOrDefault(id, Map.of()), nameByWf.getOrDefault(id, ""));
            if (r.blocking()) {
                blocked++;
                if (blockedIds.size() < 20) {
                    blockedIds.add(id);
                }
            } else if (r.warnings() > 0) {
                warned++;
                if (warnIds.size() < 30) {
                    warnIds.add(id);
                }
                if (warnSamples.size() < 6) {
                    warnSamples.add(String.format("#%d %s: %s", id, r.workflowName(),
                            r.issues().get(0).code() + " " + r.issues().get(0).message()));
                }
            } else {
                ok++;
            }
            for (Issue i : r.issues()) {
                byCode.merge(i.code(), 1, Integer::sum);
                codeStat.add(i.code());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", ids.size());
        out.put("ok", ok);
        out.put("blocked", blocked);
        out.put("warned", warned);
        out.put("byCode", byCode);
        out.put("blockedIds", blockedIds);
        out.put("warnIds", warnIds);
        out.put("warnSamples", warnSamples);
        return out;
    }

    /** 兜底策略（干跑用：任何失败都视为不可重试，避免死循环）。 */
    static RecoveryDecider fallbackDecider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy(null, RecoveryScope.NODE, RecoveryStrategy.MANUAL,
                        0, Backoff.FIXED, Duration.ZERO, 1, true))));
    }
}
