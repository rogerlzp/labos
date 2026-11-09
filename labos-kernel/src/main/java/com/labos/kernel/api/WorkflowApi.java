package com.labos.kernel.api;

import com.labos.kernel.engine.EngineDemo;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcErrorOccurrenceStore;
import com.labos.kernel.persistence.JdbcEventStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.PreflightChecker;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.RecipeCompiler;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.driver.ShadowRunner;
import com.labos.kernel.workflow.BatchOrchestrator;
import com.labos.kernel.workflow.ExperimentExecutor;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.RunControl;
import com.labos.kernel.workflow.RuntimeHold;
import com.labos.kernel.workflow.SchedulerEta;
import com.labos.kernel.workflow.StatusVocabulary;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.WorkflowService;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.labos.kernel.api.ApiServer.*;


/**
 * WorkflowApi：从 {@link ApiServer#route} 拆出的 Workflow 资源域路由（34 个路由块）。
 *
 * <p>路径：{@code /api/choreographies}、{@code /api/choreography}、{@code /api/durations}、{@code /api/runs/}、{@code /api/shadow/probe}、{@code /api/workflows}、{@code /api/workflows/}
 * <p>正则路由：{@code NODE_BINDING}、{@code WF_CAN_START}、{@code WF_COMPILE}、{@code WF_DETAIL}、{@code WF_GRAPH}、{@code WF_HISTORY}、{@code WF_PREDICT}、{@code WF_PREFLIGHT}、{@code WF_RESTORE}、{@code WF_RUN}、{@code WF_SHADOW}、{@code WORKFLOW_ETA}、{@code WORKFLOW_RUNS}、{@code WORKFLOW_TIMELINE}
 *
 * <p><b>拆分约定</b>：路由顺序按首次出现顺序分发（跨 controller 稳定）；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class WorkflowApi {

    /** 宿主：提供库连接/服务与共享 helper（与 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    WorkflowApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        Matcher wfHist = WF_HISTORY.matcher(path);
        if (wfHist.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.graphHistoryJson(Long.parseLong(wfHist.group(1))));
            return true;
        }
        Matcher wfRestore = WF_RESTORE.matcher(path);
        if (wfRestore.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long wid2 = Long.parseLong(wfRestore.group(1));
            long hid = Long.parseLong(wfRestore.group(2));
            String oldXml = s.query.graphHistoryXml(wid2, hid);
            WorkflowService.SaveResult sr = s.workflows.saveGraph(wid2, oldXml);
            s.audited("workflow.restore", "workflow", wid2,
                    "history#" + hid, Jsons.obj("nodes", sr.nodes()), ex);
            s.send(ex, 200, Jsons.obj("restored", hid, "nodes", sr.nodes()));
            return true;
        }
        if ("/api/choreography".equals(path)) {
            // 把搬运序列落成正式流程：body {name, steps:[{resourceId, method}]}
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            List<WorkflowBuilder.Step> steps = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\{\\s*\"resourceId\"\\s*:\\s*(\\d+)\\s*,\\s*\"method\"\\s*:\\s*\"([^\"]+)\"\\s*\\}")
                    .matcher(body);
            while (m.find()) {
                steps.add(new WorkflowBuilder.Step(Long.parseLong(m.group(1)), m.group(2)));
            }
            if (steps.isEmpty()) {
                s.send(ex, 400, err("steps 为空：需要 [{resourceId, method}]"));
                return true;
            }
            WorkflowBuilder builder = new WorkflowBuilder(s.db, TENANT);
            long newWf = builder.build(jsonField(body, "name"), steps);
            s.audited("choreography.build", "workflow", newWf, null,
                    Jsons.obj("steps", steps.size()), ex);
            s.send(ex, 200, Jsons.obj("workflowId", newWf, "steps", steps.size(),
                    "runUrl", "/api/workflows/" + newWf + "/run"));
            return true;
        }
        if ("/api/choreographies".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.choreographiesJson(intParam(ex, "limit", 20)));
            return true;
        }
        if ("/api/workflows".equals(path)) {
            // 新建空白流程：{"name":"…","code":"…","category":"…"}
            if ("POST".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                try {
                    long nid = s.workflows.createWorkflow(
                            jsonField(body, "name"), jsonField(body, "code"),
                            jsonField(body, "category"), jsonLong(body, "islandId"));
                    s.audited("workflow.create", "workflow", nid,
                            jsonField(body, "name"), null, ex);
                    s.send(ex, 200, Jsons.obj("id", nid));
                } catch (IllegalArgumentException e) {
                    // P-01：给了不存在的岛 id 属于请求错误，不能静默落成脏数据
                    s.send(ex, 400, err(e.getMessage()));
                } catch (java.sql.SQLException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    s.send(ex, msg.contains("duplicate key")
                            ? 400 : 500,
                            err(msg.contains("duplicate key")
                                    ? "流程编号已存在，换一个或留空自动生成" : msg));
                }
                return true;
            }
            s.requireGet(method, ex);
            s.send(ex, 200, workflowsJson(intParam(ex, "limit", 60),
                    paramStr(ex.getRequestURI().getQuery(), "category")));
            return true;
        }
        Matcher wfDetail = WF_DETAIL.matcher(path);
        if (wfDetail.matches()) {
            s.requireGet(method, ex);
            String body = workflowDetailJson(Long.parseLong(wfDetail.group(1)));
            if (body == null) {
                s.send(ex, 404, err("workflow not found"));
            } else {
                s.send(ex, 200, body);
            }
            return true;
        }
        Matcher wfRun = WF_RUN.matcher(path);
        if (wfRun.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long wid = Long.parseLong(wfRun.group(1));
            String md = modeParam(ex.getRequestURI().getQuery());
            String q = ex.getRequestURI().getQuery();
            if (q != null && q.contains("async=1")) {
                long runId = s.workflows.startAsync(wid, md, paramStr(q, "vars"));
                s.send(ex, 200, Jsons.obj(
                        "runId", runId,
                        "status", "running",
                        "attendance", md,
                        "pollUrl", "/api/runs/" + runId));
            } else {
                // ?islandId=n 限定从该岛选设备；?vars=k1=v1,k2=v2 代入参数
                String qs = ex.getRequestURI().getQuery();
                long islId = paramLong(qs, "islandId");
                // 岛锁默认**不用**——岛级互斥太粗：
                // 一个岛里有孵箱/离心机/读板机/机械臂，只有机械臂真共享，
                // 岛锁会让"孵育 30 分钟"挡住"离心 5 分钟"。
                // 正常的并发保护靠设备锁（DeviceLock）+ 位置锁。
                // 需要独占整岛（检修、调试）时显式传 ?exclusive=1。
                boolean exclusive = qs != null && qs.contains("exclusive=1");
                long lockId = (exclusive && islId > 0) ? tryAcquireIslandLock(islId) : -1;
                if (exclusive && islId > 0 && lockId <= 0) {
                    s.send(ex, 409, Jsons.obj(
                            "error", "岛已被独占（exclusive=1），请等它结束",
                            "errorCode", "EC-ISLAND-BUSY", "islandId", islId));
                    return true;
                }
                String json;
                try {
                    json = workflowRunJson(wid, md, islId, paramStr(qs, "vars"));
                } finally {
                    if (lockId > 0) releaseIslandLock(lockId);
                }
                if (json.startsWith("{\"error\"")) {
                    s.send(ex, 400, json);
                } else {
                    s.audited("run.start", "workflow", wid, null,
                            Jsons.obj("mode", md, "async", false), ex);
                    s.send(ex, 200, json);
                }
            }
            return true;
        }
        Matcher wfGraph = WF_GRAPH.matcher(path);
        if (wfGraph.matches()) {
            long gid = Long.parseLong(wfGraph.group(1));
            if ("GET".equals(method)) {
                String xml = s.query.workflowGraph(gid);
                if (xml == null) {
                    s.send(ex, 404, err("graph not found"));
                    return true;
                }
                String q = ex.getRequestURI().getQuery();
                if (q != null && q.contains("steps=1")) {
                    try {
                        xml = s.workflows.graphWithSteps(gid, xml);
                    } catch (SQLException e) {
                        // 展开失败退回原图，不让设计器打不开
                        System.err.println("[WARN] 展开视图失败: " + e.getMessage());
                    }
                }
                s.sendRaw(ex, 200, xml, "application/xml; charset=utf-8");
                return true;
            }
            if ("PUT".equals(method)) {
                String body = new String(s.readBody(ex),
                        StandardCharsets.UTF_8);
                try {
                    String dq = ex.getRequestURI().getQuery();
                    boolean draft = dq != null && dq.contains("draft=1");
                    WorkflowService.SaveResult r = s.workflows.saveGraph(gid, body, draft);
                    s.send(ex, 200, Jsons.obj(
                            "nodes", r.nodes(), "edges", r.edges(),
                            "added", r.added(), "updated", r.updated(),
                            "removed", r.removed(), "unlabeled", r.unlabeled()));
                } catch (IllegalArgumentException e) {
                    s.send(ex, 400, err(e.getMessage()));
                }
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        Matcher wfCompile = WF_COMPILE.matcher(path);
        if (wfCompile.matches()) {
            s.requireGet(method, ex);
            String cj = compileJson(Long.parseLong(wfCompile.group(1)));
            s.send(ex, cj.startsWith("{\"error\"") ? 400 : 200, cj);
            return true;
        }
        Matcher wfPreflight = WF_PREFLIGHT.matcher(path);
        if (wfPreflight.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, preflightJson(Long.parseLong(wfPreflight.group(1))));
            return true;
        }
        Matcher wfCanStart = WF_CAN_START.matcher(path);
        if (wfCanStart.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, canStartJson(Long.parseLong(wfCanStart.group(1))));
            return true;
        }
        Matcher nodeBind = NODE_BINDING.matcher(path);
        if (nodeBind.matches() && "GET".equals(method)) {
            long widG = Long.parseLong(nodeBind.group(1));
            String keyG = java.net.URLDecoder.decode(nodeBind.group(2),
                    StandardCharsets.UTF_8);
            s.send(ex, 200, s.query.nodeBindingJson(widG, keyG));
            return true;
        }
        if (nodeBind.matches()) {
            if (!"PUT".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long wid2 = Long.parseLong(nodeBind.group(1));
            String nodeKey = java.net.URLDecoder.decode(nodeBind.group(2),
                    StandardCharsets.UTF_8);
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String mth = jsonField(body, "method");
            // params 支持对象或字符串两种写法（见 jsonRawOrString）
            String prm = jsonRawOrString(body, "params");
            // params 允许两种写法：{"params":{...}} 或 {"params":"{...}"}（字符串里再嵌 JSON）。
            // 后者从 body 里抠出来会带转义，这里统一还原一次，不然 ?::jsonb 会报 invalid input。
            if (prm != null) {
                prm = prm.replace("\\\"", "\"");
            }
            Long resId = jsonLong(body, "resourceId");
            s.query.updateNodeBinding(wid2, nodeKey, mth, prm, resId);
            s.audited("workflow.node_binding", "workflow", wid2,
                    nodeKey + " → " + (mth == null ? "" : mth), null, ex);
            s.send(ex, 200, Jsons.obj("workflowId", wid2, "nodeKey", nodeKey,
                    "method", mth, "updated", true));
            return true;
        }
        Matcher wfTl = WORKFLOW_TIMELINE.matcher(path);
        if (wfTl.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.workflowTimelineJson(Long.parseLong(wfTl.group(1))));
            return true;
        }
        Matcher wfEta = WORKFLOW_ETA.matcher(path);
        if (wfEta.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.workflowEtaJson(Long.parseLong(wfEta.group(1))));
            return true;
        }
        Matcher wfRuns = WORKFLOW_RUNS.matcher(path);
        if (wfRuns.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.workflowRunsJson(
                    Long.parseLong(wfRuns.group(1)), intParam(ex, "limit", 20)));
            return true;
        }
        Matcher wfOptions = WORKFLOW_OPTIONS.matcher(path);
        if (wfOptions.matches()) {
            long oid = Long.parseLong(wfOptions.group(1));
            if ("GET".equals(method)) {
                s.send(ex, 200, Jsons.obj("maxIterations", s.workflows.loadMaxIterations(oid)));
                return true;
            }
            if (!"PUT".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String obody = new String(s.readBody(ex), StandardCharsets.UTF_8);
            Long n = jsonLong(obody, "maxIterations");
            // 上限与 GraphRunner.withMaxVisits 的钳位一致（1..100）：
            // 在入口就拦下来，免得存了 0 或 999 而运行时悄悄被改成别的数。
            if (n == null || n < 1 || n > 100) {
                s.send(ex, 400, err("maxIterations 需为 1..100 的整数"));
                return true;
            }
            s.workflows.setMaxIterations(oid, n.intValue());
            s.audited("workflow.options", "workflow", oid,
                    "maxIterations=" + n, null, ex);
            s.send(ex, 200, Jsons.obj("maxIterations", n));
            return true;
        }
        Matcher wfShadow = WF_SHADOW.matcher(path);
        if (wfShadow.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long swid = Long.parseLong(wfShadow.group(1));
            Map<String, String> capByKey = workflowCapabilities(swid);
            DriverRegistry reg = s.workflows.buildDriverRegistry();
            Map<String, List<Long>> pool = new java.util.LinkedHashMap<>();
            for (String cap : new java.util.LinkedHashSet<>(capByKey.values())) {
                if (cap != null && !cap.isBlank()) {
                    pool.put(cap, reg.candidates(cap, java.util.Set.of()));
                }
            }
            ShadowRunner runner = new ShadowRunner(s.db, TENANT, reg, pool);
            ShadowRunner.Report rep = runner.run(swid);
            s.audited("workflow.shadow", "workflow", swid,
                    "nodes=" + rep.total(), null, ex);
            s.send(ex, 200, Jsons.obj(ShadowRunner.toJson(rep)));
            return true;
        }
        if ("/api/shadow/probe".equals(path)) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String q = ex.getRequestURI().getQuery();
            long rid = paramLong(q, "resourceId");
            String m = paramStr(q, "method");
            if (rid <= 0 || m == null || m.isBlank()) {
                s.send(ex, 400, err("需要 resourceId 与 method"));
                return true;
            }
            // 对外统一用 resource_node.id；驱动注册表按 device_instance.id 索引
            long instanceId = deviceInstanceId(rid);
            if (instanceId <= 0) {
                s.send(ex, 404, err("该资源不是可执行设备（无 device_instance）"));
                return true;
            }
            // body 里可带 {"params":{"plateID":"x"}}，空则无参下发
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            if (body != null && body.contains("\"params\"")) {
                java.util.regex.Matcher pm = java.util.regex.Pattern
                        .compile("\"params\"\\s*:\\s*\\{(.*?)\\}", java.util.regex.Pattern.DOTALL)
                        .matcher(body);
                if (pm.find()) {
                    for (String pair : pm.group(1).split(",")) {
                        int idx = pair.indexOf(':');
                        if (idx <= 0) continue;
                        String k = pair.substring(0, idx).trim()
                                .replaceAll("^\"|\"$", "");
                        String v = pair.substring(idx + 1).trim()
                                .replaceAll("^\"|\"$", "");
                        if (k.isEmpty()) continue;
                        try {
                            params.put(k, Long.parseLong(v));
                        } catch (NumberFormatException ex2) {
                            params.put(k, v);
                        }
                    }
                }
            }
            ShadowProbe probe = new ShadowProbe(s.workflows.buildDriverRegistry());
            ShadowProbe.Probe r = probe.probe(instanceId, m, params);
            s.send(ex, 200, Jsons.obj(
                    "resourceId", rid,
                    "deviceInstanceId", r.resourceId(),
                    "method", r.method(),
                    "ok", r.ok(),
                    "errorCode", r.errorCode(),
                    "errorText", r.errorText(),
                    "elapsedMs", r.elapsedMs(),
                    "contract", new Jsons.Raw(Jsons.obj(
                            "messageId", r.contract().get("messageId"),
                            "messageType", r.contract().get("messageType"),
                            "method", r.contract().get("method"),
                            "methodStatus", r.contract().get("methodStatus"))),
                    "rawResponse", r.rawResponse(),
                    "summary", ShadowProbe.describe(r)));
            return true;
        }
        if ("/api/durations".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.durationsJson(200));
            return true;
        }
        Matcher wfPredict = WF_PREDICT.matcher(path);
        if (wfPredict.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, predictJson(Long.parseLong(wfPredict.group(1))));
            return true;
        }
        return false;
    }


    /**
     * 启动前粗筛。
     *
     * <p>返回 canStart / reason / estimatedWaitSeconds / blocking。
     *
     * <p><b>estimatedWaitSeconds 不准</b>（时长预估本来就不准），
     * 界面要标"仅供参考"。
     */
    private String canStartJson(long workflowId) throws SQLException {
        com.labos.kernel.workflow.AdmissionController.Decision d =
                new com.labos.kernel.workflow.AdmissionController(s.db, 1L).check(workflowId);
        return d.toJsonString();
    }


    private String compileJson(long workflowId) throws SQLException {
        WorkflowService.CompileResult r;
        try {
            r = s.workflows.compile(workflowId);
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


    /** resource_node.id -> device_instance.id（0 表示不是设备）。 */
    private long deviceInstanceId(long resourceNodeId) throws SQLException {
        try (Connection c = s.db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM device_instance WHERE resource_id = ?")) {
            ps.setLong(1, resourceNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }


    /**
     * 取一个字段，支持两种写法：`"k":{...}` 或 `"k":"{...}"`。
     *
     * <p>为什么需要：`jsonField` 只认带引号的字符串值，`{"params":{"a":1}}` 会取成 null；
     * 而前端两种都可能发。这里对对象做一次"括号配对"截取，对字符串做反转义。
     */
    private static String jsonRawOrString(String body, String key) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*").matcher(body);
        if (!m.find()) {
            return null;
        }
        int i = m.end();
        if (i >= body.length()) {
            return null;
        }
        char c = body.charAt(i);
        if (c == '{') {
            int depth = 0;
            boolean inStr = false;
            for (int j = i; j < body.length(); j++) {
                char ch = body.charAt(j);
                if (ch == '"' && body.charAt(Math.max(0, j - 1)) != '\\') {
                    inStr = !inStr;
                }
                if (inStr) {
                    continue;
                }
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        return body.substring(i, j + 1);
                    }
                }
            }
            return null;
        }
        if (c == '"') {
            int end = body.indexOf('"', i + 1);
            if (end < 0) {
                return null;
            }
            return body.substring(i + 1, end).replace("\\\"", "\"");
        }
        return null;
    }


    /**
     * 用时长模型预测总时长：编译出的每一步查 duration_model 的 p50，累加。
     * 查不到模型的步骤按 0 计，并计入 unknownSteps（预测可信度）。
     */
    private String predictJson(long workflowId) throws SQLException {
        WorkflowService.CompileResult c = s.workflows.compile(workflowId);

        Map<String, Integer> p50 = new java.util.LinkedHashMap<>();
        try (Connection conn = s.db.open();
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


    private String preflightJson(long workflowId) throws SQLException {
        PreflightChecker checker = new PreflightChecker(s.db, TENANT);
        PreflightChecker.Report r = checker.check(workflowId);

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


    /** 释放岛锁。 */
    private void releaseIslandLock(long bookingId) {
        try (Connection c = s.db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE resource_booking SET status = 'released',"
                             + " updated_at = now() WHERE id = ?")) {
            ps.setLong(1, bookingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[岛锁] 释放失败：" + e.getMessage());
        }
    }


    /**
     * 抢岛锁：用 {@code resource_booking} 的 EXCLUDE 约束。
     *
     * <p>插入成功 = 抢到；违反约束 = 岛被占用。
     * <b>不用内存锁</b>——多实例部署时各管各的，等于没锁。
     */
    private long tryAcquireIslandLock(long islandId) {
        String sql = """
                INSERT INTO resource_booking
                  (tenant_id, resource_id, period, status, source_type, note)
                VALUES (?, ?, tstzrange(now(), now() + interval '4 hours'),
                        'active', 'workflow-run', '执行期岛锁')
                RETURNING id
                """;
        try (Connection c = s.db.open();
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


    /** 流程里每个节点的能力（node_key -> capability_code）。 */
    private Map<String, String> workflowCapabilities(long workflowId) throws SQLException {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String sql = """
                SELECT n.node_key, c.code
                FROM workflow_node n JOIN capability c ON c.id = n.capability_id
                WHERE n.tenant_id = ? AND n.workflow_id = ? AND n.node_type = 'task'
                """;
        try (Connection c = s.db.open();
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


    private String workflowDetailJson(long workflowId) throws SQLException {
        WorkflowService.Detail d;
        try {
            d = s.workflows.detail(workflowId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // 节点绑定设备（搬运流程才有）：用于前端按顺序回放板子路径
        Map<String, Long> boundByKey = new LinkedHashMap<>();
        try (Connection c = s.db.open();
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


    /**
     * 限定岛执行：设置过滤 → 执行 → 清掉。
     *
     * <p>用 finally 清是因为 workflows 是单例字段，
     * 忘了清会让下一个请求莫名其妙只在一个岛里选设备。
     */
    private String workflowRunJson(long workflowId, String attendance, long islandId)
            throws SQLException {
        return workflowRunJson(workflowId, attendance, islandId, null);
    }


    /**
     * 带岛过滤 + 变量代入的执行。
     *
     * <p>用 finally 清是因为 workflows 是单例字段——
     * 忘了清会让下一个请求莫名其妙沿用上一个的岛和变量。
     */
    private String workflowRunJson(long workflowId, String attendance,
                                   long islandId, String varsExpr) throws SQLException {
        Map<String, String> vars = parseVarsExpr(varsExpr);
        boolean needIsland = islandId > 0;
        boolean needVars = !vars.isEmpty();
        if (!needIsland && !needVars) {
            return workflowRunJson(workflowId, attendance);
        }
        if (needIsland) {
            s.workflows.withIsland(islandId);
        }
        if (needVars) {
            s.workflows.withVars(vars);
        }
        try {
            return workflowRunJson(workflowId, attendance);
        } finally {
            if (needIsland) {
                s.workflows.withIsland(null);
            }
            if (needVars) {
                s.workflows.withVars(Map.of());
            }
        }
    }


    private String workflowRunJson(long workflowId, String attendance) throws SQLException {
        WorkflowService.Execution e;
        try {
            e = s.workflows.execute(workflowId, attendance);
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


    /** workflow_id → 任务节点数（只数 task，开始/结束/网关不算"一步活"）。 */
    private java.util.Map<Long, Integer> workflowTaskCounts() throws SQLException {
        java.util.Map<Long, Integer> m = new java.util.HashMap<>();
        try (java.sql.Connection c = s.db.open();
             java.sql.PreparedStatement ps = c.prepareStatement("""
                     SELECT workflow_id, count(*) AS n FROM workflow_node
                      -- workflow_node 没有 deleted_at（只有 run_node 有），别照抄
                      WHERE tenant_id = ? AND node_type = 'task' 
                      GROUP BY workflow_id
                     """)) {
            ps.setLong(1, TENANT);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getLong(1), rs.getInt(2));
                }
            }
        } catch (java.sql.SQLException e) {
            // 数不出来就当 0，不能让列表整体 500
            System.err.println("[WARN] 统计流程节点数失败: " + e.getMessage());
        }
        return m;
    }


    // ---------------------------------------------------------------- 工艺

    private String workflowsJson(int limit) throws SQLException {
        return workflowsJson(limit, null);
    }


    private String workflowsJson(int limit, String category) throws SQLException {
        // 节点数：前端「添加流程」下拉和流程库列表都要显示。
        // 改动前这个字段没返回，界面上显示成"(AUTO-7659, undefined 节点)"。
        java.util.Map<Long, Integer> counts = workflowTaskCounts();
        List<String> items = new ArrayList<>();
        for (WorkflowDef w : s.workflows.list(limit)) {
            items.add(Jsons.obj(
                    "id", w.id(),
                    "code", w.code(),
                    "name", w.name(),
                    "category", w.category(),
                    "nodeCount", counts.getOrDefault(w.id(), 0)));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    // ------------------------------------------------------------ 路由正则

    private static final Pattern WF_DETAIL = Pattern.compile("^/api/workflows/(\\d+)$");

    private static final Pattern WF_RUN = Pattern.compile("^/api/workflows/(\\d+)/run$");

    private static final Pattern WF_GRAPH = Pattern.compile("^/api/workflows/(\\d+)/graph$");

    private static final Pattern WF_COMPILE = Pattern.compile("^/api/workflows/(\\d+)/compile$");

    private static final Pattern WF_PREFLIGHT = Pattern.compile("^/api/workflows/(\\d+)/preflight$");

    /** 启动前粗筛：资源够不够、被谁占着、大约等多久。 */
    private static final Pattern WF_CAN_START =
            Pattern.compile("^/api/workflows/(\\d+)/can-start$");

    private static final Pattern WF_PREDICT = Pattern.compile("^/api/workflows/(\\d+)/predict$");

    private static final Pattern WF_SHADOW = Pattern.compile("^/api/workflows/(\\d+)/shadow-run$");

    /** 改单个节点的方法绑定 + 参数（内置节点/设备任务共用）。 */
    private static final Pattern NODE_BINDING =
            Pattern.compile("^/api/workflows/(\\d+)/nodes/([^/]+)$");

    /** 这条流程的时间条（甘特前置：每步预计开始/结束）。 */
    private static final Pattern WORKFLOW_TIMELINE =
            Pattern.compile("^/api/workflows/(\\d+)/timeline$");

    /** 这条流程现在跑要等多久（按当前资源占用估）。 */
    private static final Pattern WORKFLOW_ETA =
            Pattern.compile("^/api/workflows/(\\d+)/eta$");

    /** 某条流程的执行记录（点进去看每次的节点状态）。 */
    private static final Pattern WORKFLOW_RUNS = Pattern.compile("^/api/workflows/(\\d+)/runs$");

    /**
     * 流程级选项（目前只有循环圈数上限 maxIterations）。
     *
     * <p>为什么不挂在 {@code /api/workflows/{id}}：那条路由是只读详情
     * （{@code requireGet}），而循环上限是流程属性、要可写；
     * 另起一条语义更清楚，也不会把详情页的返回体撑大。
     */
    private static final Pattern WORKFLOW_OPTIONS =
            Pattern.compile("^/api/workflows/(\\d+)/options$");

    private static final Pattern WF_HISTORY = Pattern.compile("^/api/workflows/(\\d+)/history$");

    private static final Pattern WF_RESTORE = Pattern.compile("^/api/workflows/(\\d+)/history/(\\d+)/restore$");
}
