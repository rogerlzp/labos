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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
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

        // ---- 静态检查 2.6：网关结构（成对 / 角色明确）----
        //
        // 为什么要有：用户 2026-11-11 指出「并行网关、异或网关、条件网关应该都有
        // 开始和结束网关成对出现，现在拖出来只有一个，看不清结构」。
        // 文档（docs/测试总纲.md 规则 5/6/7）早就写了「禁止 1 进 1 出的网关」
        // 「分叉必须配同类型汇合」「汇合必须有同类型分叉」——
        // 但那些规则**只写在文档里，实现层从没强制过**，所以用户拖一堆网关出来，
        // 系统一声不吭全收下，跑起来才知道不对。这里把它变成预检里的可见问题。
        issues.addAll(checkGatewayStructure(graph));

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

    /**
     * 网关**结构**检查：成对、角色明确、条件齐备。
     *
     * <p>对应 {@code docs/测试总纲.md} 的规则 5/6/7：
     * <ol>
     *   <li><b>PF-15 禁止 1 进 1 出的网关</b>：单进单出的网关在引擎口径里就是
     *       「直通」（{@code WorkflowGraphParser} 明确按入/出边数判），
     *       画着占地方、缩小后还跟分叉汇合混淆 ⇒ 提示去掉；</li>
     *   <li><b>PF-16 分叉必须有同族汇合</b>：并行/条件分叉的每条分支都该收到
     *       「等齐」的信号，否则分支各跑各的、下游提前开跑；</li>
     *   <li><b>PF-17 汇合必须有同族分叉</b>：孤立的汇合点多半是分叉没画全；</li>
     *   <li><b>PF-18 条件网关的出边要有条件</b>：没条件的出边在条件网关里
     *       只当兜底用（所有条件都不成立时才走），多数时候是漏写了；</li>
     *   <li><b>PF-19 孤立的网关</b>：一条线都没连的网关不参与执行 ——
     *       正是「拖出来只有一个、看不清结构」的那种图。</li>
     * </ol>
     *
     * <p><b>同族</b>的定义：并行↔并行、异或↔异或、条件↔条件。
     * 异或分叉**允许**直接收口到 end（引擎的异或汇合是「任一条到达即放行」，
     * 收到 end 也是同一个意思）⇒ 不对异或报 PF-16/PF-17。
     *
     * <p>全部是 WARN 不是 ERROR：这些是**画法**问题，不是跑不了 ——
     * 阻断保存会让用户以为系统坏了。
     *
     * <p><b>包内可见</b>（不是 private）：这段是**纯逻辑**（只看图，不连库），
     * 让自测能直接调用，不用为了测它去连数据库。
     */
    static List<Issue> checkGatewayStructure(Graph graph) {
        List<Issue> out = new ArrayList<>();

        // ---- PF-15：单进单出 = 直通 ----
        for (GNode n : graph.nodes().values()) {
            if (gatewayFamily(n.kind()) == null) {
                continue;
            }
            int ins = graph.in(n.key()).size();
            int outs = graph.out(n.key()).size();
            if (ins == 1 && outs == 1) {
                out.add(new Issue(Level.WARN, "PF-15", n.key(),
                        String.format("网关「%s」是 1 进 1 出（直通）——"
                                + "引擎按连线数判角色，它既不分叉也不汇合，建议去掉或补上第二条线",
                                n.name())));
            }
            // ---- PF-19：孤立的网关（一条线都没连）----
            //
            // 为什么单列一条：这正是用户 2026-11-11 说的「拖出来只有一个」——
            // 网关拖到画布上却没接到链路里，图上就多一个说不清作用的菱形。
            // PF-15 管不到它（0 进 0 出不是 1 进 1 出），所以另给一个码。
            if (ins == 0 && outs == 0) {
                out.add(new Issue(Level.WARN, "PF-19", n.key(),
                        String.format("网关「%s」没有连任何线（孤立）—— "
                                + "它不参与执行，画图时多半是拖出来忘了接", n.name())));
            }
        }

        // ---- PF-16：分叉的分支要收到同族汇合 ----
        for (GNode n : graph.nodes().values()) {
            String family = gatewayFamily(n.kind());
            if (family == null || !isFork(n.kind())) {
                continue;
            }
            // 异或允许收口到 end ⇒ 不查
            if ("xor".equals(family)) {
                continue;
            }
            List<GEdge> outs = graph.out(n.key());
            List<String> orphans = new ArrayList<>();
            for (GEdge e : outs) {
                if (!reachesJoinOfFamily(graph, e.to(), family)) {
                    orphans.add(e.to());
                }
            }
            if (!orphans.isEmpty()) {
                out.add(new Issue(Level.WARN, "PF-16", n.key(),
                        String.format("%s分叉有 %d 条分支收不到同族汇合点（%s）——"
                                + "这些分支不会参与「等齐」，下游可能提前开跑",
                                familyLabel(family), orphans.size(), String.join("、", orphans))));
            }
        }

        // ---- PF-17：汇合要有同族分叉 ----
        for (GNode n : graph.nodes().values()) {
            String family = gatewayFamily(n.kind());
            if (family == null || !isJoin(n.kind())) {
                continue;
            }
            if ("xor".equals(family)) {
                continue;
            }
            if (!reachedByForkOfFamily(graph, n.key(), family)) {
                out.add(new Issue(Level.WARN, "PF-17", n.key(),
                        String.format("%s汇合点找不到同族分叉 —— 多半是分叉没画全，"
                                + "它会一直等那些永远不会到的分支", familyLabel(family))));
            }
        }

        // ---- PF-18：条件网关的出边该有条件 ----
        for (GNode n : graph.nodes().values()) {
            if (n.kind() != Kind.SPLIT_CONDITIONAL) {
                continue;
            }
            List<GEdge> outs = graph.out(n.key());
            long noCond = outs.stream()
                    .filter(e -> (e.condition() == null || e.condition().isBlank()) && !e.isDefault())
                    .count();
            if (noCond > 0) {
                out.add(new Issue(Level.WARN, "PF-18", n.key(),
                        String.format("条件网关有 %d 条出边没写条件表达式 —— "
                                + "没条件的分支只当兜底用（所有条件都不成立时才走）", noCond)));
            }
        }
        return out;
    }

    /** 网关「家族」：分叉与汇合要同族配对。 */
    private static String gatewayFamily(Kind k) {
        if (k == Kind.SPLIT_PARALLEL || k == Kind.JOIN_PARALLEL) {
            return "par";
        }
        if (k == Kind.SPLIT_XOR || k == Kind.JOIN_XOR) {
            return "xor";
        }
        if (k == Kind.SPLIT_CONDITIONAL || k == Kind.JOIN_CONDITIONAL) {
            return "cond";
        }
        return null;
    }

    private static boolean isFork(Kind k) {
        return k == Kind.SPLIT_PARALLEL || k == Kind.SPLIT_XOR || k == Kind.SPLIT_CONDITIONAL;
    }

    private static boolean isJoin(Kind k) {
        return k == Kind.JOIN_PARALLEL || k == Kind.JOIN_XOR || k == Kind.JOIN_CONDITIONAL;
    }

    /** 家族的中文名（给提示文案用）。 */
    private static String familyLabel(String family) {
        if ("par".equals(family)) {
            return "并行";
        }
        if ("cond".equals(family)) {
            return "条件";
        }
        return "异或";
    }

    /** 从 from 出发能不能走到同族的汇合点（入边 > 1 的那个）。 */
    private static boolean reachesJoinOfFamily(Graph g, String from, String family) {
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(from);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 5000) {
            String cur = stack.pop();
            if (!seen.add(cur)) {
                continue;
            }
            GNode n = g.nodes().get(cur);
            if (n != null && family.equals(gatewayFamily(n.kind())) && isJoin(n.kind())) {
                return true;
            }
            for (GEdge e : g.out(cur)) {
                stack.push(e.to());
            }
        }
        return false;
    }

    /** 能不能从某个同族分叉走到 joinKey。 */
    private static boolean reachedByForkOfFamily(Graph g, String joinKey, String family) {
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(joinKey);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 5000) {
            String cur = stack.pop();
            if (!seen.add(cur)) {
                continue;
            }
            GNode n = g.nodes().get(cur);
            if (n != null && family.equals(gatewayFamily(n.kind())) && isFork(n.kind())) {
                return true;
            }
            for (GEdge e : g.in(cur)) {
                stack.push(e.from());
            }
        }
        return false;
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
