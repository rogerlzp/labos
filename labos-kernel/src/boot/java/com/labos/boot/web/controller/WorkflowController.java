package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.boot.web.service.WorkflowWebService;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.api.QueryService;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonLong;
import static com.labos.kernel.api.ApiServer.paramStr;

/**
 * 流程域 Controller（21 个端点）——最后一个从 {@code *Api} 拆出的类。
 * 拆完后旧分发链（{@code ApiServer.route()} 里最后一个
 * {@code workflowApi.handle}）与 {@code ServletHttpExchange} 适配层即可退役。
 *
 * <p>业务在 {@link WorkflowWebService}；本类只做 HTTP 解析、审计与状态码。
 * 语义对齐点：
 * <ul>
 *   <li>同步执行的 json 以 {@code {"error"}} 开头 → 400（执行拒绝，如不可达节点）；</li>
 *   <li>{@code detail} 不存在 → 404；{@code compile} 以 error 开头 → 400；</li>
 *   <li>岛锁只在 {@code ?exclusive=1} 时启用（岛级互斥太粗，默认靠设备锁）；</li>
 *   <li>{@code maxIterations} 入口钳位 1..100，与 GraphRunner 一致。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowWebService web;
    private final QueryService query;
    private final WorkflowService workflows;
    private final AuditRecorder audit;

    public WorkflowController(WorkflowWebService web, QueryService query,
                              WorkflowService workflows, AuditRecorder audit) {
        this.web = web;
        this.query = query;
        this.workflows = workflows;
        this.audit = audit;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
    }

    // ---------------------------------------------------------------- 历史 / 恢复

    @GetMapping(value = "/workflows/{id:\\d+}/history",
            produces = "application/json; charset=utf-8")
    public String history(@PathVariable long id) throws SQLException {
        return web.graphHistory(id);
    }

    @PostMapping(value = "/workflows/{id:\\d+}/history/{hid:\\d+}/restore",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> restore(@PathVariable long id, @PathVariable long hid,
                                          HttpServletRequest req) throws SQLException {
        var r = web.restore(id, hid);
        audit.record(session(req), "workflow.restore", "workflow", id,
                r.auditKey(), r.auditDetail(), req.getRemoteAddr());
        return to(r);
    }

    // ---------------------------------------------------------------- 编排 → 流程

    @PostMapping(value = "/choreography", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> choreography(@RequestBody(required = false) String body,
                                               HttpServletRequest req) throws SQLException {
        // 把搬运序列落成正式流程：body {name, steps:[{resourceId, method}]}
        List<WorkflowBuilder.Step> steps = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\{\\s*\"resourceId\"\\s*:\\s*(\\d+)\\s*,\\s*\"method\"\\s*:\\s*\"([^\"]+)\"\\s*\\}")
                .matcher(body);
        while (m.find()) {
            steps.add(new WorkflowBuilder.Step(
                    Long.parseLong(m.group(1)), m.group(2)));
        }
        if (steps.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("steps 为空：需要 [{resourceId, method}]"));
        }
        var r = web.choreographyBuild(jsonField(body, "name"), steps);
        audit.record(session(req), "choreography.build", "workflow",
                r.auditEntityId(), r.auditKey(), r.auditDetail(), req.getRemoteAddr());
        return to(r);
    }

    @GetMapping(value = "/choreographies", produces = "application/json; charset=utf-8")
    public String choreographies(@RequestParam(required = false) String limit)
            throws SQLException {
        return query.choreographiesJson(intOr(limit, 20));
    }

    // ---------------------------------------------------------------- 列表 / 新建 / 详情

    @GetMapping(value = "/workflows", produces = "application/json; charset=utf-8")
    public String list(@RequestParam(required = false) String limit,
                       @RequestParam(required = false) String category) throws SQLException {
        return web.workflows(intOr(limit, 60), category);
    }

    @PostMapping(value = "/workflows", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> create(@RequestBody(required = false) String body,
                                         HttpServletRequest req) throws SQLException {
        var r = web.create(jsonField(body, "name"), jsonField(body, "code"),
                jsonField(body, "category"), jsonLong(body, "islandId"));
        audit.record(session(req), "workflow.create", "workflow",
                r.auditEntityId(), r.auditKey(), r.auditDetail(), req.getRemoteAddr());
        return to(r);
    }

    @GetMapping(value = "/workflows/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> detail(@PathVariable long id) throws SQLException {
        String body = web.detail(id);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("workflow not found"));
        }
        return ResponseEntity.ok(body);
    }

    // ---------------------------------------------------------------- 执行

    @PostMapping(value = "/workflows/{id:\\d+}/run",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> run(
            @PathVariable long id,
            @RequestParam(required = false) String attendance,
            HttpServletRequest req) throws SQLException {
        String md = attendance != null && !attendance.isBlank() ? attendance : "attended";
        String qs = req.getQueryString();
        if (qs != null && qs.contains("async=1")) {
            long runId = workflows.startAsync(id, md, paramStr(qs, "vars"));
            audit.record(session(req), "run.start", "workflow", id,
                    null, Jsons.obj("mode", md, "async", true), req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj(
                    "runId", runId,
                    "status", "running",
                    "attendance", md,
                    "pollUrl", "/api/runs/" + runId));
        }
        // ?islandId=n 限定从该岛选设备；?vars=k1=v1,k2=v2 代入参数
        long islId = intOr(qs != null && qs.contains("islandId=")
                ? qs.replaceAll(".*islandId=(\\d+).*", "$1") : null, 0);
        // 岛锁默认**不用**——岛级互斥太粗：一个岛里有孵箱/离心机/读板机/机械臂，
        // 只有机械臂真共享，岛锁会让"孵育 30 分钟"挡住"离心 5 分钟"。
        // 正常的并发保护靠设备锁（DeviceLock）+ 位置锁。
        // 需要独占整岛（检修、调试）时显式传 ?exclusive=1。
        boolean exclusive = qs != null && qs.contains("exclusive=1");
        long lockId = (exclusive && islId > 0) ? web.tryAcquireIslandLock(islId) : -1;
        if (exclusive && islId > 0 && lockId <= 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Jsons.obj(
                    "error", "岛已被独占（exclusive=1），请等它结束",
                    "errorCode", "EC-ISLAND-BUSY", "islandId", islId));
        }
        String json;
        try {
            json = web.run(id, md, islId, paramStr(qs, "vars"));
        } finally {
            if (lockId > 0) {
                web.releaseIslandLock(lockId);
            }
        }
        if (json.startsWith("{\"error\"")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(json);
        }
        audit.record(session(req), "run.start", "workflow", id,
                null, Jsons.obj("mode", md, "async", false), req.getRemoteAddr());
        return ResponseEntity.ok(json);
    }

    // ---------------------------------------------------------------- 图

    @GetMapping(value = "/workflows/{id:\\d+}/graph")
    public ResponseEntity<String> graph(@PathVariable long id,
                                        HttpServletRequest req) throws SQLException {
        String xml = query.workflowGraph(id);
        if (xml == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("graph not found"));
        }
        String q = req.getQueryString();
        if (q != null && q.contains("steps=1")) {
            try {
                xml = workflows.graphWithSteps(id, xml);
            } catch (SQLException e) {
                // 展开失败退回原图，不让设计器打不开
                org.slf4j.LoggerFactory.getLogger(WorkflowController.class)
                        .error("[WARN] 展开视图失败: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/xml; charset=utf-8"))
                .body(xml);
    }

    @PutMapping(value = "/workflows/{id:\\d+}/graph",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveGraph(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        boolean draft = req.getQueryString() != null
                && req.getQueryString().contains("draft=1");
        try {
            WorkflowService.SaveResult r = workflows.saveGraph(id, body, draft);
            return ResponseEntity.ok(Jsons.obj(
                    "nodes", r.nodes(), "edges", r.edges(),
                    "added", r.added(), "updated", r.updated(),
                    "removed", r.removed(), "unlabeled", r.unlabeled()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 编译 / 预检 / 准入 / 预测

    @GetMapping(value = "/workflows/{id:\\d+}/compile",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> compile(@PathVariable long id) throws SQLException {
        String cj = web.compile(id);
        return ResponseEntity.status(cj.startsWith("{\"error\"") ? 400 : 200).body(cj);
    }

    @GetMapping(value = "/workflows/{id:\\d+}/preflight",
            produces = "application/json; charset=utf-8")
    public String preflight(@PathVariable long id) throws SQLException {
        return web.preflight(id);
    }

    @GetMapping(value = "/workflows/{id:\\d+}/can-start",
            produces = "application/json; charset=utf-8")
    public String canStart(@PathVariable long id) throws SQLException {
        return web.canStart(id);
    }

    @GetMapping(value = "/workflows/{id:\\d+}/predict",
            produces = "application/json; charset=utf-8")
    public String predict(@PathVariable long id) throws SQLException {
        return web.predict(id);
    }

    // ---------------------------------------------------------------- 节点绑定

    @GetMapping(value = "/workflows/{id:\\d+}/nodes/{nodeKey}",
            produces = "application/json; charset=utf-8")
    public String nodeBinding(@PathVariable long id, @PathVariable String nodeKey)
            throws SQLException {
        return query.nodeBindingJson(id,
                URLDecoder.decode(nodeKey, StandardCharsets.UTF_8));
    }

    @PutMapping(value = "/workflows/{id:\\d+}/nodes/{nodeKey}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveNodeBinding(
            @PathVariable long id,
            @PathVariable String nodeKey,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        String key = URLDecoder.decode(nodeKey, StandardCharsets.UTF_8);
        String mth = jsonField(body, "method");
        // params 支持对象或字符串两种写法（见 jsonRawOrString）：
        // {"params":{...}} 或 {"params":"{...}"}（字符串里再嵌 JSON）。
        // 后者从 body 里抠出来会带转义，统一还原一次，不然 ?::jsonb 会报 invalid input。
        String prm = jsonRawOrString(body, "params");
        if (prm != null) {
            prm = prm.replace("\\\"", "\"");
        }
        Long resId = jsonLong(body, "resourceId");
        query.updateNodeBinding(id, key, mth, prm, resId);
        audit.record(session(req), "workflow.node_binding", "workflow", id,
                key + " → " + (mth == null ? "" : mth), null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("workflowId", id, "nodeKey", key,
                "method", mth, "updated", true));
    }

    @PostMapping(value = "/workflows/{id:\\d+}/nodes/{nodeKey}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveNodeBindingPost(
            @PathVariable long id,
            @PathVariable String nodeKey,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        return saveNodeBinding(id, nodeKey, body, req);
    }

    // ---------------------------------------------------------------- 时间线 / ETA / 运行列表 / 选项

    @GetMapping(value = "/workflows/{id:\\d+}/timeline",
            produces = "application/json; charset=utf-8")
    public String timeline(@PathVariable long id) throws SQLException {
        return query.workflowTimelineJson(id);
    }

    @GetMapping(value = "/workflows/{id:\\d+}/eta",
            produces = "application/json; charset=utf-8")
    public String eta(@PathVariable long id) throws SQLException {
        return query.workflowEtaJson(id);
    }

    @GetMapping(value = "/workflows/{id:\\d+}/runs",
            produces = "application/json; charset=utf-8")
    public String runs(@PathVariable long id,
                       @RequestParam(required = false) String limit) throws SQLException {
        return query.workflowRunsJson(id, intOr(limit, 20));
    }

    @GetMapping(value = "/workflows/{id:\\d+}/options",
            produces = "application/json; charset=utf-8")
    public String options(@PathVariable long id) throws SQLException {
        return Jsons.obj("maxIterations", workflows.loadMaxIterations(id));
    }

    @PutMapping(value = "/workflows/{id:\\d+}/options",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveOptions(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        Long n = jsonLong(body, "maxIterations");
        // 上限与 GraphRunner.withMaxVisits 的钳位一致（1..100）：
        // 在入口就拦下来，免得存了 0 或 999 而运行时悄悄被改成别的数。
        if (n == null || n < 1 || n > 100) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("maxIterations 需为 1..100 的整数"));
        }
        workflows.setMaxIterations(id, n.intValue());
        audit.record(session(req), "workflow.options", "workflow", id,
                "maxIterations=" + n, null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("maxIterations", n));
    }

    @PostMapping(value = "/workflows/{id:\\d+}/options",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveOptionsPost(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        return saveOptions(id, body, req);
    }

    // ---------------------------------------------------------------- 影子运行 / 探针

    @PostMapping(value = "/workflows/{id:\\d+}/shadow-run",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> shadowRun(@PathVariable long id,
                                            HttpServletRequest req) throws SQLException {
        var r = web.shadowRun(id);
        audit.record(session(req), "workflow.shadow", "workflow", id,
                r.auditKey(), r.auditDetail(), req.getRemoteAddr());
        return to(r);
    }

    @PostMapping(value = "/shadow/probe", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> shadowProbe(
            HttpServletRequest req) throws SQLException {
        String q = req.getQueryString();
        long rid = intOr(q != null && q.contains("resourceId=")
                ? q.replaceAll(".*resourceId=(\\d+).*", "$1") : null, 0);
        String m = paramStr(q, "method");
        if (rid <= 0 || m == null || m.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 resourceId 与 method"));
        }
        // 对外统一用 resource_node.id；驱动注册表按 device_instance.id 索引
        long instanceId = web.deviceInstanceId(rid);
        if (instanceId <= 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("该资源不是可执行设备（无 device_instance）"));
        }
        // body 里可带 {"params":{"plateID":"x"}}，空则无参下发
        Map<String, Object> params = new LinkedHashMap<>();
        String body;
        try (java.io.InputStream in = req.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            body = "";
        }
        if (body.contains("\"params\"")) {
            java.util.regex.Matcher pm = java.util.regex.Pattern
                    .compile("\"params\"\\s*:\\s*\\{(.*?)\\}", java.util.regex.Pattern.DOTALL)
                    .matcher(body);
            if (pm.find()) {
                for (String pair : pm.group(1).split(",")) {
                    int idx = pair.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String k = pair.substring(0, idx).trim().replaceAll("^\"|\"$", "");
                    String v = pair.substring(idx + 1).trim().replaceAll("^\"|\"$", "");
                    if (k.isEmpty()) {
                        continue;
                    }
                    try {
                        params.put(k, Long.parseLong(v));
                    } catch (NumberFormatException e2) {
                        params.put(k, v);
                    }
                }
            }
        }
        ShadowProbe probe = new ShadowProbe(workflows.buildDriverRegistry());
        ShadowProbe.Probe r = probe.probe(instanceId, m, params);
        return ResponseEntity.ok(Jsons.obj(
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
    }

    // ---------------------------------------------------------------- 时长库

    @GetMapping(value = "/durations", produces = "application/json; charset=utf-8")
    public String durations() throws SQLException {
        return query.durationsJson(200);
    }

    // ---------------------------------------------------------------- 内部

    private static ResponseEntity<String> to(WorkflowWebService.Result r) {
        return ResponseEntity.status(r.status()).body(r.json());
    }

    /**
     * 与原 {@code jsonRawOrString} 逐字一致：params 既支持对象也支持字符串
     * （字符串里再嵌一层 JSON），返回抠出来的片段。
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

    private static int intOr(String raw, int def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }
}
