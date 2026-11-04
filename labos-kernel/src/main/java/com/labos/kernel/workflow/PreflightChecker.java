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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM workflow WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    name = rs.getString(1);
                }
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

        List<GNode> tasks = new ArrayList<>();
        for (GNode n : graph.nodes().values()) {
            if (n.kind() == Kind.TASK) {
                tasks.add(n);
            }
        }
        if (tasks.isEmpty()) {
            issues.add(new Issue(Level.ERROR, "PF-07", null, "流程里没有任务节点"));
            return new Report(workflowId, name, 0, 0, 0, issues);
        }

        // ---- 静态检查 1：能力与设备 ----
        Map<String, List<Long>> capPool = capPool();
        Map<String, String> protocolCap = protocolCap();
        Map<String, Integer> capUsage = new LinkedHashMap<>();
        for (GNode t : tasks) {
            CapabilitySource cs = RealWorkflowRunner.resolveCapability(
                    t.name(), t.method(), t.resourceId(), explicitCap.get(t.key()), protocolCap);
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
        GraphRunner runner = new GraphRunner(sm, capPool, protocolCap, explicitCap, s -> { });

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

    // ---------------------------------------------------------------- 数据

    private String loadXml(long workflowId) throws SQLException {
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

    private String loadWorkflowName(long workflowId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM workflow WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
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

    // ---------------------------------------------------------------- 工具

    private static String minKey(List<GEdge> outs) {
        return outs.stream().map(GEdge::to).sorted().findFirst().orElse("");
    }

    private static List<String> head(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    /** 全量预检：给"一键体检"用。 */
    public Map<String, Object> checkAll() throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM workflow WHERE tenant_id = ? ORDER BY id")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }

        // 批量预加载：避免每个流程各建两次连接（跨网络约 100ms/次）
        Map<Long, String> xmlByWf = new LinkedHashMap<>();
        Map<Long, Map<String, String>> capByWf = new LinkedHashMap<>();
        Map<Long, String> nameByWf = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT w.id, w.name, w.graph->>'xml' AS xml, n.node_key, cap.code
                     FROM workflow w
                     LEFT JOIN workflow_node n
                            ON n.workflow_id = w.id AND n.node_type = 'task'
                     LEFT JOIN capability cap ON cap.id = n.capability_id
                     WHERE w.tenant_id = ?
                     """)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    nameByWf.putIfAbsent(id, rs.getString(2));
                    String xml = rs.getString(3);
                    if (xml != null && !xml.isBlank()) {
                        xmlByWf.putIfAbsent(id, xml);
                    }
                    String key = rs.getString(4);
                    String code = rs.getString(5);
                    if (key != null && code != null) {
                        capByWf.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(key, code);
                    }
                }
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
