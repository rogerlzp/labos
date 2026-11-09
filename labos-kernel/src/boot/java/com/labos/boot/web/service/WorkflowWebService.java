package com.labos.boot.web.service;

import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.QueryService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.AdmissionController;
import com.labos.kernel.workflow.PreflightChecker;
import com.labos.kernel.workflow.RecipeCompiler;
import com.labos.kernel.driver.ShadowRunner;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;
import com.labos.kernel.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程域 Service：编译 / 预检 / 预测 / 执行 / 岛锁 / 影子运行。
 *
 * <p>从 {@code WorkflowApi} 的私有方法整体下沉，逻辑逐字一致。两类内容：
 * <ul>
 *   <li>纯编排 JSON 组装（编译 / 预检 / 预测 / 详情）—— 状态码语义保留在
 *       {@link Result} 里（如「error 前缀 → 400」「detail null → 404」）；</li>
 *   <li>执行编排（岛锁抢/放、withIsland/withVars 的 try-finally 清理、
 *       同步执行）—— {@code workflows} 是单例 Bean，忘了清过滤会让下一个
 *       请求莫名其妙沿用上一个的岛和变量，try-finally 不能丢。</li>
 * </ul>
 */
@Service
public class WorkflowWebService {

    /** HTTP 响应 + 审计参数（entityKey/detail 由 Service 算出，Controller 不自己编）。 */
    public record Result(int status, String json, Long auditEntityId,
                         String auditKey, String auditDetail) {
        public static Result ok(String json, Long entityId, String key, String detail) {
            return new Result(200, json, entityId, key, detail);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WorkflowWebService.class);
    private static final long TENANT = 1L;

    private final DbConfig db;
    private final WorkflowService workflows;
    private final QueryService query;

    public WorkflowWebService(DbConfig db, WorkflowService workflows, QueryService query) {
        this.db = db;
        this.workflows = workflows;
        this.query = query;
    }

    // ---------------------------------------------------------------- 历史

    public String graphHistory(long workflowId) throws SQLException {
        return query.graphHistoryJson(workflowId);
    }

    public Result restore(long workflowId, long historyId) throws SQLException {
        String oldXml = query.graphHistoryXml(workflowId, historyId);
        WorkflowService.SaveResult sr = workflows.saveGraph(workflowId, oldXml);
        String json = Jsons.obj("restored", historyId, "nodes", sr.nodes());
        return Result.ok(json, workflowId, "history#" + historyId,
                Jsons.obj("nodes", sr.nodes()));
    }

    // ---------------------------------------------------------------- 编排转流程

    public Result choreographyBuild(String name, List<WorkflowBuilder.Step> steps)
            throws SQLException {
        WorkflowBuilder builder = new WorkflowBuilder(db, TENANT);
        long newWf = builder.build(name, steps);
        return Result.ok(Jsons.obj("workflowId", newWf, "steps", steps.size()),
                newWf, null, Jsons.obj("steps", steps.size()));
    }

    // ---------------------------------------------------------------- 列表 / 新建 / 详情

    public String workflows(int limit, String category) throws SQLException {
        // 节点数：前端「添加流程」下拉和流程库列表都要显示。
        // 改动前这个字段没返回，界面上显示成"(AUTO-7659, undefined 节点)"。
        Map<Long, Integer> counts = workflowTaskCounts();
        List<String> items = new ArrayList<>();
        for (WorkflowDef w : workflows.list(limit)) {
            items.add(Jsons.obj(
                    "id", w.id(),
                    "code", w.code(),
                    "name", w.name(),
                    "category", w.category(),
                    "nodeCount", counts.getOrDefault(w.id(), 0)));
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }

    public Result create(String name, String code, String category, Long islandId)
            throws SQLException {
        long nid;
        try {
            nid = workflows.createWorkflow(name, code, category, islandId);
        } catch (IllegalArgumentException e) {
            // P-01：给了不存在的岛 id 属于请求错误，不能静默落成脏数据
            return new Result(400, Jsons.obj("error", e.getMessage()),
                    null, null, null);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("duplicate key")) {
                return new Result(400, Jsons.obj("error", "流程编号已存在，换一个或留空自动生成"),
                        null, null, null);
            }
            throw e;
        }
        return Result.ok(Jsons.obj("id", nid), nid, name, null);
    }

    /** 不存在返回 null（Controller 转 404）。 */
    public String detail(long workflowId) throws SQLException {
        WorkflowService.Detail d;
        try {
            d = workflows.detail(workflowId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // 节点绑定设备（搬运流程才有）：用于前端按顺序回放板子路径
        Map<String, Long> boundByKey = new LinkedHashMap<>();
        try (java.sql.Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT node_key, (params->>'bound_resource_id')::bigint AS rid
                     FROM workflow_node
                     WHERE tenant_id = ? AND workflow_id = ?
                       AND params->>'bound_resource_id' IS NOT NULL
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boundByKey.put(rs.getString("node_key"), rs.getLong("rid"));
                }
            }
        }
        List<String> tasks = new ArrayList<>();
        for (WorkflowService.TaskView t : d.tasks()) {
            tasks.add(Jsons.obj(
                    "id", t.id(),
                    "key", t.key(),
                    "name", t.name(),
                    "capability", t.capability(),
                    "source", t.source(),
                    "method", t.method(),
                    "resourceId", boundByKey.get(t.key())));
        }
        return Jsons.obj(
                "workflow", new Jsons.Raw(Jsons.obj(
                        "id", d.workflow().id(),
                        "code", d.workflow().code(),
                        "name", d.workflow().name(),
                        "category", d.workflow().category())),
                "edgeCount", d.edgeCount(),
                "tasks", new Jsons.Raw(Jsons.arr(tasks)));
    }

    // ---------------------------------------------------------------- 执行

    /** 同步执行（可选岛过滤 + 变量代入）。json 以 {"error" 开头表示执行拒绝 → 400。 */
    public String run(long workflowId, String attendance, long islandId, String varsExpr)
            throws SQLException {
        Map<String, String> vars = ApiServer.parseVarsExpr(varsExpr);
        boolean needIsland = islandId > 0;
        boolean needVars = !vars.isEmpty();
        if (!needIsland && !needVars) {
            return run(workflowId, attendance);
        }
        if (needIsland) {
            workflows.withIsland(islandId);
        }
        if (needVars) {
            workflows.withVars(vars);
        }
        try {
            return run(workflowId, attendance);
        } finally {
            // workflows 是单例字段——忘了清会让下一个请求莫名其妙沿用上一个的岛和变量
            if (needIsland) {
                workflows.withIsland(null);
            }
            if (needVars) {
                workflows.withVars(Map.of());
            }
        }
    }

    public String run(long workflowId, String attendance) throws SQLException {
        WorkflowService.Execution e;
        try {
            e = workflows.execute(workflowId, attendance);
        } catch (IllegalArgumentException ex) {
            return Jsons.obj("error", ex.getMessage());
        }
        List<String> nodes = new ArrayList<>();
        for (WorkflowService.NodeResult n : e.nodes()) {
            nodes.add(Jsons.obj(
                    "id", n.id(),
                    "name", n.name(),
                    "capability", n.capability(),
                    "method", n.method(),
                    "resourceId", n.resourceId(),
                    "status", n.status(),
                    "attempt", n.attempt(),
                    "rawResponse", n.rawResponse()));
        }
        List<String> alerts = new ArrayList<>();
        for (com.labos.kernel.alert.Alert a : e.alerts()) {
            alerts.add(Jsons.obj(
                    "kind", a.kind().name(),
                    "fingerprint", a.fingerprint(),
                    "errorCode", a.errorCode(),
                    "resourceId", a.resourceId(),
                    "severity", a.severity(),
                    "count", a.count(),
                    "notify", a.shouldNotify(),
                    "message", a.message()));
        }
        return Jsons.obj(
                "runId", e.runId(),
                "workflowName", e.workflowName(),
                "alerts", new Jsons.Raw(Jsons.arr(alerts)),
                "notifyCount", e.notifyCount(),
                "total", e.total(),
                "completed", e.completed(),
                "manual", e.manual(),
                "aborted", e.aborted(),
                "viaDriver", e.viaDriver(),
                "attendance", e.attendance(),
                "eventsWritten", e.eventsWritten(),
                "nodes", new Jsons.Raw(Jsons.arr(nodes)),
                "detailUrl", "/api/runs/" + e.runId());
    }

    // ---------------------------------------------------------------- 岛锁

    /** 释放岛锁。 */
    public void releaseIslandLock(long bookingId) {
        try (java.sql.Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE resource_booking SET status = 'released',"
                             + " updated_at = now() WHERE id = ?")) {
            ps.setLong(1, bookingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[岛锁] 释放失败：{}", e.getMessage());
        }
    }

    /**
     * 抢岛锁：用 {@code resource_booking} 的 EXCLUDE 约束。
     *
     * <p>插入成功 = 抢到；违反约束 = 岛被占用。<b>不用内存锁</b>——
     * 多实例部署时各管各的，等于没锁。
     */
    public long tryAcquireIslandLock(long islandId) {
        String sql = """
                INSERT INTO resource_booking
                  (tenant_id, resource_id, period, status, source_type, note)
                VALUES (?, ?, tstzrange(now(), now() + interval '4 hours'),
                        'active', 'workflow-run', '执行期岛锁')
                RETURNING id
                """;
        try (java.sql.Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, islandId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            return -1;   // 违反 EXCLUDE = 岛被占用，预期分支
        }
    }

    // ---------------------------------------------------------------- 编译 / 预检 / 预测 / 准入

    /** error 前缀 = 编译拒绝 → 400。 */
    public String compile(long workflowId) throws SQLException {
        WorkflowService.CompileResult r;
        try {
            r = workflows.compile(workflowId);
        } catch (IllegalArgumentException e) {
            return Jsons.obj("error", e.getMessage());
        }
        List<String> nodes = new ArrayList<>();
        for (RecipeCompiler.NodePlan p : r.nodes()) {
            List<String> steps = new ArrayList<>();
            for (RecipeCompiler.Step st : p.steps()) {
                steps.add(Jsons.obj("seq", st.seq(), "method", st.method()));
            }
            nodes.add(Jsons.obj(
                    "nodeId", p.nodeId(),
                    "nodeKey", p.nodeKey(),
                    "name", p.name(),
                    "capability", p.capability(),
                    "capabilitySource", p.capabilitySource(),
                    "fromRecipe", p.fromRecipe(),
                    "sampleCount", p.sampleCount(),
                    "stepCount", p.stepCount(),
                    "steps", new Jsons.Raw(Jsons.arr(steps))));
        }
        return Jsons.obj(
                "workflow", new Jsons.Raw(Jsons.obj(
                        "id", r.workflow().id(),
                        "name", r.workflow().name())),
                "totalSteps", r.totalSteps(),
                "recipeNodes", r.recipeNodes(),
                "nodes", new Jsons.Raw(Jsons.arr(nodes)));
    }

    public String preflight(long workflowId) throws SQLException {
        PreflightChecker.Report r = new PreflightChecker(db, TENANT).check(workflowId);
        List<String> issues = new ArrayList<>();
        for (PreflightChecker.Issue i : r.issues()) {
            issues.add(Jsons.obj(
                    "level", i.level().name(),
                    "code", i.code(),
                    "nodeKey", i.nodeKey(),
                    "message", i.message()));
        }
        return Jsons.obj(
                "workflowId", r.workflowId(),
                "workflowName", r.workflowName(),
                "taskCount", r.taskCount(),
                "reachableCount", r.reachableCount(),
                "defaultEdges", r.defaultEdges(),
                "blocking", r.blocking(),
                "errors", r.errors(),
                "warnings", r.warnings(),
                "issues", new Jsons.Raw(Jsons.arr(issues)));
    }

    public String canStart(long workflowId) throws SQLException {
        AdmissionController.Decision d =
                new AdmissionController(db, TENANT).check(workflowId);
        return d.toJsonString();
    }

    /**
     * 用时长模型预测总时长：编译出的每一步查 duration_model 的 p50，累加。
     * 查不到模型的步骤按 0 计，并计入 unknownSteps（预测可信度）。
     */
    public String predict(long workflowId) throws SQLException {
        WorkflowService.CompileResult c = workflows.compile(workflowId);
        Map<String, Integer> p50 = new LinkedHashMap<>();
        try (java.sql.Connection conn = db.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT method, p50_ms FROM duration_model WHERE tenant_id = ?")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    p50.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        List<String> nodes = new ArrayList<>();
        long totalMs = 0;
        int unknown = 0;
        for (RecipeCompiler.NodePlan plan : c.nodes()) {
            long nodeMs = 0;
            for (RecipeCompiler.Step st : plan.steps()) {
                Integer ms = p50.get(st.method());
                if (ms == null) {
                    unknown++;
                } else {
                    nodeMs += ms;
                }
            }
            totalMs += nodeMs;
            nodes.add(Jsons.obj(
                    "nodeKey", plan.nodeKey(),
                    "name", plan.name(),
                    "capability", plan.capability(),
                    "stepCount", plan.stepCount(),
                    "predictedMs", nodeMs));
        }
        return Jsons.obj(
                "workflowId", workflowId,
                "workflowName", c.workflow().name(),
                "totalSteps", c.totalSteps(),
                "predictedMs", totalMs,
                "predictedSec", totalMs / 1000.0,
                "unknownSteps", unknown,
                "confidence", c.totalSteps() == 0 ? "none"
                        : unknown == 0 ? "high"
                        : unknown * 2 < c.totalSteps() ? "medium" : "low",
                "nodes", new Jsons.Raw(Jsons.arr(nodes)));
    }

    // ---------------------------------------------------------------- 影子运行

    public Result shadowRun(long workflowId) throws SQLException {
        Map<String, String> capByKey = workflowCapabilities(workflowId);
        com.labos.kernel.driver.DriverRegistry reg = workflows.buildDriverRegistry();
        Map<String, List<Long>> pool = new LinkedHashMap<>();
        for (String cap : new java.util.LinkedHashSet<>(capByKey.values())) {
            if (cap != null && !cap.isBlank()) {
                pool.put(cap, reg.candidates(cap, java.util.Set.of()));
            }
        }
        ShadowRunner.Report rep = new ShadowRunner(db, TENANT, reg, pool).run(workflowId);
        return Result.ok(Jsons.obj(ShadowRunner.toJson(rep)), workflowId,
                "nodes=" + rep.total(), null);
    }

    /** resource_node.id -> device_instance.id（0 表示不是设备）。 */
    public long deviceInstanceId(long resourceNodeId) throws SQLException {
        try (java.sql.Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM device_instance WHERE resource_id = ?")) {
            ps.setLong(1, resourceNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 流程里每个节点的能力（node_key -> capability_code）。 */
    private Map<String, String> workflowCapabilities(long workflowId) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        String sql = """
                SELECT n.node_key, c.code
                FROM workflow_node n JOIN capability c ON c.id = n.capability_id
                WHERE n.tenant_id = ? AND n.workflow_id = ? AND n.node_type = 'task'
                """;
        try (java.sql.Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    /** workflow_id → 任务节点数（只数 task，开始/结束/网关不算"一步活"）。 */
    private Map<Long, Integer> workflowTaskCounts() throws SQLException {
        Map<Long, Integer> m = new java.util.HashMap<>();
        try (java.sql.Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT workflow_id, count(*) AS n FROM workflow_node
                      -- workflow_node 没有 deleted_at（只有 run_node 有），别照抄
                      WHERE tenant_id = ? AND node_type = 'task'
                      GROUP BY workflow_id
                     """)) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getLong(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            // 数不出来就当 0，不能让列表整体 500
            log.error("[WARN] 统计流程节点数失败: {}", e.getMessage());
        }
        return m;
    }
}
