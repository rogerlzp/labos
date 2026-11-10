package com.labos.boot.web.service;

import com.labos.kernel.api.ApiServer;
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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.labos.kernel.persistence.QJson.*;

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
    private final com.labos.kernel.persistence.WorkflowWebMapper mapper;
    private final com.labos.kernel.persistence.GraphMapper graphMapper;

    public WorkflowWebService(DbConfig db, WorkflowService workflows,
                              com.labos.kernel.persistence.WorkflowWebMapper mapper,
                              com.labos.kernel.persistence.GraphMapper graphMapper) {
        this.db = db;
        this.workflows = workflows;
        this.mapper = mapper;
        this.graphMapper = graphMapper;
    }

    // ---------------------------------------------------------------- 历史

    public String graphHistory(long workflowId) throws SQLException {
        return graphHistoryJson(workflowId);
    }

    public Result restore(long workflowId, long historyId) throws SQLException {
        String oldXml = graphHistoryXml(workflowId, historyId);
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
        for (Map<String, Object> row : mapper.selectBoundResources(TENANT, workflowId)) {
            boundByKey.put(String.valueOf(row.get("nodeKey")),
                    ((Number) row.get("rid")).longValue());
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
        // 权威岛（P-01 的 `workflow.island_id`）：流程一旦定岛，**设备池就按它锁死**，
        // 前端不再让用户用 localStorage 里随手选的岛挑设备（P-04）。
        // 没有岛（NULL，旧流程）时前端仍可手动选岛，保持兼容。
        Object islandId = null;
        java.util.Map<String, Object> header = graphMapper.selectWorkflowHeader(TENANT, workflowId);
        if (header != null && header.get("islandId") != null) {
            islandId = ((Number) header.get("islandId")).longValue();
        }
        return Jsons.obj(
                "workflow", new Jsons.Raw(Jsons.obj(
                        "id", d.workflow().id(),
                        "code", d.workflow().code(),
                        "name", d.workflow().name(),
                        "category", d.workflow().category(),
                        "islandId", islandId)),
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
            // 权威岛优先（P-05）：请求里指定的岛必须与流程自己的权威岛一致。
            // 不一致就**明确拒绝** —— 否则"流程属于 A 岛"与"这次从 B 岛挑设备"
            // 会同时成立，板子会在两个岛之间乱跑，而界面上看不出任何异常。
            // 没有权威岛（旧流程）⇒ 仍以请求为准，保持兼容。
            Long authIsland = workflows.authoritativeIslandId(workflowId);
            if (authIsland != null && authIsland.longValue() != islandId) {
                return Jsons.obj("error",
                        "请求的岛与流程的权威岛不一致：流程属于岛 " + authIsland
                                + "，请求指定了岛 " + islandId
                                + "。流程的归属由服务端决定，不能在执行时改。");
            }
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
        try {
            mapper.releaseIslandLock(bookingId);
        } catch (RuntimeException e) {
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
        try {
            Long id = mapper.insertIslandLock(TENANT, islandId);
            return id == null ? -1 : id;
        } catch (RuntimeException e) {
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
        for (Map<String, Object> row : mapper.selectDurationP50(TENANT)) {
            p50.put(String.valueOf(row.get("method")),
                    ((Number) row.get("p50Ms")).intValue());
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
    public long deviceInstanceId(long resourceNodeId) {
        Long id = mapper.selectDeviceInstanceId(resourceNodeId);
        return id == null ? 0L : id;
    }

    // ---------------------------------------------------------------- 内部

    /** 流程里每个节点的能力（node_key -> capability_code）。 */
    private Map<String, String> workflowCapabilities(long workflowId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectWorkflowCapabilities(TENANT, workflowId)) {
            out.put(String.valueOf(row.get("nodeKey")), String.valueOf(row.get("code")));
        }
        return out;
    }

    /** workflow_id → 任务节点数（只数 task，开始/结束/网关不算"一步活"）。 */
    private Map<Long, Integer> workflowTaskCounts() {
        Map<Long, Integer> m = new java.util.HashMap<>();
        try {
            for (Map<String, Object> row : mapper.selectWorkflowTaskCounts(TENANT)) {
                m.put(((Number) row.get("workflowId")).longValue(),
                        ((Number) row.get("n")).intValue());
            }
        } catch (RuntimeException e) {
            // 数不出来就当 0，不能让列表整体 500
            log.error("[WARN] 统计流程节点数失败: {}", e.getMessage());
        }
        return m;
    }
    // ---------------------------------------------------------------- 节点绑定

    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public boolean updateNodeBinding(long workflowId, String nodeKey,
                                     String method, String methodParamsJson)
            throws SQLException {
        return updateNodeBinding(workflowId, nodeKey, method, methodParamsJson, null);
    }

    /**
     * 同 {@link #updateNodeBinding(long, String, String, String)}，额外绑定设备。
     *
     * @param resourceId 设备资源 id。**契约（P-10 定案）**：
     *                   {@code null} = 不改动绑定；{@code 0}（或负数）= 显式解绑；
     *                   {@code > 0} = 绑定到该资源。
     *
     * <p>设备绑定只存在图 XML 的 {@code resource="…"} 属性里，{@code workflow_node}
     * 没有这一列。在设计器里选设备本质就是改这个属性；表是读侧快路径，
     * 两边必须同时改，否则「表清了、图里还绑着」。
     */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public boolean updateNodeBinding(long workflowId, String nodeKey,
                                     String method, String methodParamsJson,
                                     Long resourceId) throws SQLException {
        if (nodeKey == null || nodeKey.isBlank()) {
            throw new IllegalArgumentException("nodeKey 不能为空");
        }
        String m = method == null ? "" : method.trim();
        try (java.sql.Connection c = db.open()) {
            try {
                // null = 不改动：与 patchNodeAttrs 的 null 语义保持一致，
                // 只有显式给值才写库，避免「表清了、图里还绑着」。
                if (resourceId != null) {
                    mapper.updateNodeResource(TENANT, workflowId, nodeKey,
                            resourceId > 0 ? resourceId : null);
                }
                mapper.updateNodeParams(TENANT, workflowId, nodeKey,
                        methodParamsJson,
                        methodParamsJson == null || methodParamsJson.isBlank()
                                ? "{}" : methodParamsJson,
                        m.startsWith("__"), m);

                String xml = mapper.selectWorkflowXml(TENANT, workflowId);
                if (xml != null && !xml.isBlank()) {
                    String patched = patchNodeAttrs(xml, nodeKey, m, resourceId);
                    if (!patched.equals(xml)) {
                        graphMapper.updateWorkflowGraph(TENANT, workflowId, patched);
                    }
                }
                return true;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /** 内置节点方法 -> 中文短名（节点名跟着方法名走时用）。 */
    private static String labelOf(String method) {
        return switch (method) {
            case "__setVar" -> "变量";
            case "__expr" -> "表达式";
            case "__sleep" -> "延时";
            case "__wait" -> "等待";
            default -> method;
        };
    }

    /**
     * 只改 BPMN 节点上 method / resource 两个属性，坐标与排版一个字不动。
     * 按属性名逐对解析再重建：直接字符串替换会留下双空格，也容易把值里的
     * 空格当分隔符。
     */
    private static String patchNodeAttrs(String xml, String nodeKey,
                                         String method, Long resourceId) {
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "<([a-zA-Z0-9_:]+)([^>]*?\\sid=\"" + java.util.regex.Pattern.quote(nodeKey)
                        + "\")([^>]*?)(/?)>");
        java.util.regex.Matcher mt = pat.matcher(xml);
        if (!mt.find()) {
            return xml;
        }
        String tag = mt.group(1);
        String all = mt.group(2) + mt.group(3);
        boolean selfClose = !mt.group(4).isEmpty();

        java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher am = java.util.regex.Pattern
                .compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*\"([^\"]*)\"")
                .matcher(all);
        while (am.find()) {
            attrs.put(am.group(1), am.group(2));
        }

        if (method != null) {
            String oldMethod = attrs.get("protocolName");
            String oldName = attrs.get("name");
            if (method.isBlank()) {
                attrs.remove("protocolName");
            } else {
                attrs.put("protocolName", method);
                // 节点名跟着方法名走 —— 但只在「用户没自己起过名」时改：
                // 名字为空 / 等于方法名 / 还是 bpmn-js 的占位名（Task 之类），
                // 否则会把用户写的「封膜第一步」覆盖掉。
                boolean placeholder = oldName == null || oldName.isBlank()
                        || oldName.equals(oldMethod)
                        || oldName.startsWith("Task") || oldName.startsWith("Activity")
                        || oldName.startsWith("任务") || oldName.startsWith("活动");
                if (placeholder) {
                    attrs.put("name", labelOf(method));
                }
            }
        }
        if (resourceId != null) {
            if (resourceId > 0) {
                attrs.put("resource", String.valueOf(resourceId));
            } else {
                attrs.remove("resource");
                attrs.remove("resourceName");
            }
        }

        StringBuilder sb = new StringBuilder("<").append(tag);
        attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(v).append('"'));
        sb.append(selfClose ? " />" : ">");
        return xml.substring(0, mt.start()) + sb + xml.substring(mt.end());
    }


    /** 搬运流程列表（岛屿视图"跑流程"用，按 id 降序）。 */
    public String choreographiesJson(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : mapper.selectChoreographies(TENANT, limit)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "nodeCount", mint(row, "nodeCount")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 取流程的 BPMN 2.0 XML（原样返回，给 bpmn-js 渲染）。 */
    public String workflowGraph(long workflowId) throws java.sql.SQLException {
        return mapper.selectWorkflowXml(TENANT, workflowId);
    }

    /**
     * 改一个节点的**方法绑定 + 方法参数**。
     *
     * <p>为什么需要这个接口：内置节点（`__setVar` / `__expr`）和普通任务参数，
     * 改动前只能改库 —— 设计器里拖出来的 task 没有 `protocolName`，而 bpmn-js 的 modeling
     * 对未声明的自定义属性支持不稳。这里**以库为准**改两处：
     * <ol>
     *   <li>{@code workflow_node.protocol_name} + {@code params.method_params}</li>
     *   <li>{@code workflow.graph->>'xml'} 里该元素的 {@code protocolName} 属性
     *       （只改这一个属性，坐标/排版一个字不动）</li>
     * </ol>
     */
    /**
     * 读一个节点的绑定信息（设计器右侧面板用）。
     *
     * <p>方法名以 XML 的 protocolName 为准（执行引擎解析的就是它），
     * 参数以 workflow_node.params->'method_params' 为准。
     */
    public String nodeBindingJson(long workflowId, String nodeKey) throws java.sql.SQLException {
        String xml = mapper.selectWorkflowXml(TENANT, workflowId);
        String method = null;
        if (xml != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<[a-zA-Z0-9_:]+[^>]*\\sid=\"" + java.util.regex.Pattern.quote(nodeKey)
                            + "\"[^>]*?\\sprotocolName=\"([^\"]*)\"")
                    .matcher(xml);
            if (m.find()) {
                method = m.group(1);
            }
        }
        // 设备绑定优先读表（M9 前置债）；表里为空再退回 XML（存量未回填时兜底）
        Long resourceId = null;
        if (resourceId == null && xml != null) {
            java.util.regex.Matcher em = java.util.regex.Pattern
                    .compile("<[a-zA-Z0-9_:]+[^>]*\\sid=\"" + java.util.regex.Pattern.quote(nodeKey)
                            + "\"[^>]*>")
                    .matcher(xml);
            if (em.find()) {
                java.util.regex.Matcher rm = java.util.regex.Pattern
                        .compile("\\sresource=\"(\\d+)\"").matcher(em.group());
                if (rm.find()) {
                    resourceId = Long.parseLong(rm.group(1));
                }
            }
        }
        String params = "{}";
        java.util.Map<String, Object> binding =
                mapper.selectNodeBinding(TENANT, workflowId, nodeKey);
        if (binding != null) {
            Object mp = binding.get("mp");
            if (mp != null) {
                params = String.valueOf(mp);
            }
            if (binding.get("resourceId") != null) {
                resourceId = ((Number) binding.get("resourceId")).longValue();
            }
        }
        return Jsons.obj("workflowId", workflowId, "nodeKey", nodeKey,
                "method", method, "resourceId", resourceId,
                "params", new Jsons.Raw(params));
    }

    /** 实验列表（650 个）。 */
    /**
     * 这条流程的**时间条**：每个任务节点预计什么时候开始、什么时候结束。
     *
     * <p>甘特的前置：甘特要画条，至少得先有"开始 + 时长"。
     * 这里按 `seq` 顺序串行模拟一遍，并考虑设备当前占用：
     * 节点开始时间 = max(上一步结束, 该设备空闲时刻)。
     *
     * <p>只说清现在能算准的部分：
     * <ul>
     *   <li>时长取 `workflow_node.estimate_sec`，为 0 时按 60 秒兜底并标注 {@code estimated=true}；</li>
     *   <li>不含换型/清洗/人工处置；并行分支按"都在同一条时间轴上串行"处理（保守）；</li>
     *   <li>设备绑定从图 XML 的 {@code resource="…"} 读（`workflow_node` 没有这一列）。</li>
     * </ul>
     */
    public String workflowTimelineJson(long workflowId) throws java.sql.SQLException {
        // 设备空闲时刻（秒，从现在算）
        java.util.Map<Long, Long> freeAt = new java.util.HashMap<>();
        java.util.Map<Long, String> resCode = new java.util.HashMap<>();
        long now = 0;

        List<String> items = new ArrayList<>();
        long cursor = 0;
        // 估算链（每步耗时从哪来，按可靠性从高到低）：
        //   ① manual  —— 人在流程里填的 estimate_sec（最可信）
        //   ② history —— 这个方法的**历史 P50**（run_command 里有 11.6 万条实测）
        //   ③ device  —— 型号库 device_method.estimate_sec（随设备型号来）
        //   ④ default —— 60 秒兜底，标 estimated=true
        // 为什么不是"没数据就 60 秒"：历史样本足够的方法（Transfer 4.3 万条）用实测比猜准得多。
        for (java.util.Map<String, Object> row
                : mapper.selectWorkflowTimelineNodes(TENANT, workflowId)) {
            String key = String.valueOf(row.get("nodeKey"));
            long manual = mint(row, "manual");
            Object p50Obj = row.get("p50");
            double p50 = p50Obj instanceof Number n ? n.doubleValue() : 0d;
            boolean p50Null = p50Obj == null;
            Object nObj = row.get("n");
            long histN = nObj instanceof Number n ? n.longValue() : 0L;
            boolean histNNull = nObj == null;
            Object devObj = row.get("devEst");
            long devEst = devObj instanceof Number n ? n.longValue() : 0L;
            boolean devNull = devObj == null;

            long dur;
            String source;
            if (manual > 0) {
                dur = manual;
                source = "manual";
            } else if (!p50Null && p50 > 0 && !histNNull && histN >= 20) {
                // 样本 <20 不用：一两条的耗时不如型号库的默认值
                dur = Math.max(1, Math.round(p50));
                source = "history";
            } else if (!devNull && devEst > 0) {
                dur = devEst;
                source = "device";
            } else {
                dur = 60;
                source = "default";
            }
            boolean estimated = !"manual".equals(source);
            Object ridObj = row.get("resourceId");
            // 0 是历史数据里的"没有设备"，别当成一台设备
            Long rid = (ridObj == null || !(ridObj instanceof Number)
                    || ((Number) ridObj).longValue() == 0)
                    ? null : ((Number) ridObj).longValue();
            long resFree = 0;
            if (rid != null) {
                resFree = freeAt.computeIfAbsent(rid, r -> remainingOf(r));
                if (!resCode.containsKey(rid)) {
                    String code = resourceCodeOf(rid);
                    // 存量流程里的资源 id 可能已经不在 resource_node 了（换过设备/清过池）：
                    // 显示 "设备#387" 比显示 "未绑定" 诚实 —— 它确实绑了，只是绑的东西没了
                    resCode.put(rid, code == null ? "设备#" + rid + "（已失效）" : code);
                }
            }
            long startSec = Math.max(cursor, resFree);
            long end = startSec + dur;
            cursor = end;
            if (rid != null) {
                freeAt.put(rid, end);
            }
            items.add(Jsons.obj(
                    "nodeKey", key,
                    "name", row.get("name"),
                    "seq", items.size() + 1,
                    "resourceId", rid,
                    "resourceCode", rid == null ? null : resCode.get(rid),
                    "startSec", startSec,
                    "endSec", end,
                    "durationSec", dur,
                    "estimateSource", source,
                    "estimateSamples", histNNull ? 0 : histN,
                    "estimated", estimated));
        }

        return Jsons.obj("workflowId", workflowId, "totalSec", cursor,
                "nodes", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 这条流程现在跑，大概要等多久（秒）。
     *
     * <p>算法：取流程里所有任务节点用到的资源，看每个资源**当前**有没有别人的
     * `run_node` 在跑（`status='running'`），把「预计耗时 - 已跑时长」取最大值。
     * 再加**排队人数**（同资源上 queued 的单数）作为参考。
     *
     * <p>为什么要：点执行之后没有任何反馈，用户不知道是"马上跑"还是"排在 3 个实验后面"。
     * 甘特之前先把"还要等多久"这个数算出来，后面画时间轴才有数据。
     *
     * <p>局限（别当承诺）：按"当前占用者跑完"估算，不含换型/清洗/人工处置时间；
     * 资源被暂停的单占着时，估的是"它继续跑完"而不是"等人处理"。
     */
    public String workflowEtaJson(long workflowId) throws java.sql.SQLException {
        // ① 这条流程要用哪些资源 —— 直接查 workflow_node.resource_id。
        // 这两列是 M9 前置债补的：改动前只能解析图 XML，ETA / 时间条 / 绑定面板各写了一遍正则。
        java.util.Set<Long> resources = new java.util.LinkedHashSet<>(
                mapper.selectWorkflowResources(TENANT, workflowId));
        if (resources.isEmpty()) {
            return Jsons.obj("workflowId", workflowId, "waitSec", 0, "known", false,
                    "detail", "这条流程没有绑定设备资源，不会排队");
        }

        long maxWait = 0;
        List<String> busy = new ArrayList<>();
        for (Long rid : resources) {
            java.util.Map<String, Object> holder =
                    mapper.selectBusyResourceHolder(TENANT, rid);
            if (holder != null) {
                Object rem = holder.get("remain");
                long remain = rem instanceof Number n ? Math.round(n.doubleValue()) : 0L;
                maxWait = Math.max(maxWait, remain);
                busy.add(Jsons.obj(
                        "resourceId", rid,
                        "resourceCode", holder.get("resCode"),
                        "holderRunNo", holder.get("runNo"),
                        "holderRunId", holder.get("runId"),
                        "remainSec", remain));
            }
        }
        return Jsons.obj("workflowId", workflowId, "waitSec", maxWait,
                "known", true, "resources", resources.size(),
                "busy", new Jsons.Raw(Jsons.arr(busy)));
    }

    /**
     * 某条流程的**执行记录**（最近 N 次）。
     *
     * <p>为什么要：跑完的单子在页面上没有入口能翻到 —— 实时看板只列 running，
     * 暂停队列只列 paused，甘特要手动在下拉里找 run id。
     * "这条流程跑过几次、每次结果如何"是最基本的追溯需求。
     */
    public String workflowRunsJson(long workflowId, int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : mapper.selectWorkflowRuns(
                TENANT, workflowId, Math.max(1, Math.min(limit, 100)))) {
            Object dur = row.get("durationSec");
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "runNo", row.get("runNo"),
                    "status", row.get("status"),
                    "startedAt", mstr(row, "actualStart"),
                    "finishedAt", mstr(row, "actualEnd"),
                    "durationSec", dur instanceof Number n ? n.intValue() : null,
                    "total", mint(row, "total"),
                    "done", mint(row, "done"),
                    "manual", mint(row, "manual"),
                    "failed", mint(row, "failed")));
        }
        return Jsons.obj("workflowId", workflowId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 时长模型（命令 → p50/p90），甘特预测与仿真共用。 */
    public String durationsJson(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : mapper.selectDurations(TENANT, Math.max(1, Math.min(limit, 300)))) {
            items.add(Jsons.obj(
                    "method", row.get("method"),
                    "p50Ms", row.get("p50Ms"),
                    "p90Ms", row.get("p90Ms"),
                    "minMs", row.get("minMs"),
                    "maxMs", row.get("maxMs"),
                    "sampleCount", row.get("sampleCount")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 流程图保存历史（按时间倒序）。 */
    public String graphHistoryJson(long workflowId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : mapper.selectGraphHistory(TENANT, workflowId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "nodeCount", mint(row, "nodeCount"),
                    "edgeCount", mint(row, "edgeCount"),
                    "bytes", mint(row, "bytes"),
                    "savedAt", mstr(row, "savedAt")));
        }
        return Jsons.obj("workflowId", workflowId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 读某历史版本的 XML 原文。 */
    public String graphHistoryXml(long workflowId, long historyId) throws java.sql.SQLException {
        String xml = mapper.selectGraphHistoryXml(TENANT, workflowId, historyId);
        if (xml == null) {
            throw new IllegalArgumentException("历史版本不存在: " + historyId);
        }
        return xml;
    }


    /** 某个资源还要被占用多久（秒）；没人用就是 0。 */
    private long remainingOf(long resourceId) {
        try {
            Double remain = mapper.selectRemainingSec(TENANT, resourceId);
            return remain == null ? 0 : Math.round(remain);
        } catch (RuntimeException e) {
            return 0;
        }
    }


    private String resourceCodeOf(long resourceId) {
        try {
            return mapper.selectResourceCode(TENANT, resourceId);
        } catch (RuntimeException e) {
            // 原实现同样吞异常返回 null：资源码只用于展示，不阻断主流程。
            return null;
        }
    }

}
