package com.labos.boot.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.ManifestMapper;
import com.labos.kernel.persistence.Mappers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.labos.kernel.api.ApiServer.err;

/**
 * P1 · 实验模板 / 物料清单 / 物料位置配置 / 三条件启动。
 *
 * <p>对齐 外部厂商A 的 Process→Experiment：模板（编排人员建）→ 实例化（操作员）→
 * 物料 config（操作员）→ 三条件 readiness → 一键启动。
 *
 * <p>三条件闸门（StartupReadinessChecker 语义，这里内联）：
 *   C1 system_online = 该 run 涉及设备全部在线；
 *   C2 work_ready = 实验有流程/模板（流程级预检沿用 PreflightChecker，启动时再跑）；
 *   C3 material_verified = required 物料全部 verified。
 */
@RestController
@RequestMapping("/api")
public class ExperimentTemplateController {

    private static final long TENANT = 1L;
    private static final Set<String> TEMPLATE_STATUS = Set.of("draft", "published", "archived");
    private static final Set<String> MATERIAL_KINDS =
            Set.of("sample", "reagent", "consumable", "plate", "lid");

    private final DbConfig db;
    private final AuditRecorder audit;
    /** 导入要建流程（图 + 节点），复用既有创建/保存路径，别另写一套。 */
    private final com.labos.kernel.workflow.WorkflowService workflows;

    public ExperimentTemplateController(DbConfig db, AuditRecorder audit,
                                        com.labos.kernel.workflow.WorkflowService workflows) {
        this.db = db;
        this.audit = audit;
        this.workflows = workflows;
    }

    private ManifestMapper m() {
        return Mappers.get(ManifestMapper.class);
    }

    // ---------------------------------------------------------------- 实验模板

    @GetMapping(value = "/experiment-templates", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> templates(@RequestParam(required = false) String islandId,
                                            @RequestParam(required = false) String status) {
        List<Map<String, Object>> rows = m().selectTemplates(TENANT,
                parseLongOrNull(islandId), blankToNull(status));
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @PostMapping(value = "/experiment-templates", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createTemplate(@RequestBody(required = false) String body,
                                                 HttpServletRequest req) {
        JsonNode n = parse(body);
        String name = text(n, "name");
        long islandId = longOf(n, "islandId");
        if (blank(name) || islandId <= 0) return bad("必填 name / islandId");
        String no = text(n, "templateNo");
        if (blank(no)) no = "TPL-" + System.currentTimeMillis() % 100000000L;
        List<JsonNode> workflows = arrayOf(n, "workflowIds");
        List<JsonNode> items = arrayOf(n, "manifestItems");
        try {
            m().insertTemplate(TENANT, no.trim(), name.trim(), islandId,
                    jsonArray(workflows), raw(n, "spec"));
            Long tid = lastTemplateId(no);
            if (tid == null) return bad("建模板失败");
            int seq = 1;
            for (JsonNode w : workflows) {
                m().insertTemplateProcess(TENANT, tid, seq++, longOf(w, "workflowId"),
                        raw(w, "params"));
            }
            seq = 1;
            for (JsonNode it : items) {
                String kind = text(it, "materialKind");
                if (kind == null) kind = "plate";
                if (!MATERIAL_KINDS.contains(kind)) return bad("materialKind 非法：" + kind);
                m().insertManifestItem(TENANT, tid, seq++, text(it, "roleCode"), kind,
                        longOrNull(it, "materialRef"), doubleOf(it, "quantity", 1),
                        boolOf(it, "required", true));
            }
            // 点位白名单是模板层的能力边界（PF-33 用），建模板时就允许一起提交。
            if (n.has("pointWhitelist")) {
                m().updateTemplateWhitelist(tid, raw(n, "pointWhitelist"));
            }
            audit.record(session(req), "experimentTemplate.create", "experiment_template",
                    tid, null, null, req.getRemoteAddr());
            return ok(Map.of("id", tid, "templateNo", no.trim(),
                    "processCount", workflows.size(), "manifestCount", items.size()));
        } catch (Exception e) {
            return writeError(e, "templateNo 可能重复");
        }
    }

    @GetMapping(value = "/experiment-templates/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> template(@PathVariable long id) {
        Map<String, Object> t = m().selectTemplate(TENANT, id);
        if (t == null) return notFound("模板不存在: " + id);
        t.put("processes", m().selectTemplateProcesses(TENANT, id));
        t.put("manifestItems", m().selectManifestItems(TENANT, id));
        return ok(t);
    }

    @PutMapping(value = "/experiment-templates/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateTemplate(@PathVariable long id,
                                                 @RequestBody(required = false) String body,
                                                 HttpServletRequest req) {
        JsonNode n = parse(body);
        String status = text(n, "status");
        if (status != null && !TEMPLATE_STATUS.contains(status)) return bad("status 非法：" + status);
        int r = m().updateTemplate(id, text(n, "name"), status, raw(n, "spec"));
        if (r == 0) return notFound("模板不存在: " + id);
        // ---- 编辑器提交的是整张表：给了就整体替换（没给就保持不动）----
        try {
            if (n.has("workflowIds")) {
                List<JsonNode> workflows = arrayOf(n, "workflowIds");
                m().deleteTemplateProcesses(TENANT, id);
                int seq = 1;
                for (JsonNode w : workflows) {
                    m().insertTemplateProcess(TENANT, id, seq++, longOf(w, "workflowId"),
                            raw(w, "params"));
                }
                m().updateTemplateWorkflowIds(id, jsonArray(workflows));
            }
            if (n.has("manifestItems")) {
                List<JsonNode> items = arrayOf(n, "manifestItems");
                m().deleteManifestItems(TENANT, id);
                int seq = 1;
                for (JsonNode it : items) {
                    String kind = text(it, "materialKind");
                    if (kind == null) kind = "plate";
                    if (!MATERIAL_KINDS.contains(kind)) return bad("materialKind 非法：" + kind);
                    m().insertManifestItem(TENANT, id, seq++, text(it, "roleCode"), kind,
                            longOrNull(it, "materialRef"), doubleOf(it, "quantity", 1),
                            boolOf(it, "required", true));
                }
            }
            if (n.has("pointWhitelist")) {
                m().updateTemplateWhitelist(id, raw(n, "pointWhitelist"));
            }
        } catch (Exception e) {
            return writeError(e, "模板子项更新失败");
        }
        audit.record(session(req), "experimentTemplate.update", "experiment_template", id,
                null, null, req.getRemoteAddr());
        return ok(Map.of("updated", r, "id", id));
    }

    @DeleteMapping(value = "/experiment-templates/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteTemplate(@PathVariable long id, HttpServletRequest req) {
        int r = m().deleteTemplate(TENANT, id);
        if (r == 0) return notFound("模板不存在: " + id);
        audit.record(session(req), "experimentTemplate.delete", "experiment_template", id,
                null, null, req.getRemoteAddr());
        return ok(Map.of("deleted", r, "id", id));
    }

    // ---------------------------------------------------------------- 实例化

    @PostMapping(value = "/experiment-templates/{id:\\d+}/instantiate",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> instantiate(@PathVariable long id,
                                              @RequestBody(required = false) String body,
                                              HttpServletRequest req) {
        Map<String, Object> t = m().selectTemplate(TENANT, id);
        if (t == null) return notFound("模板不存在: " + id);
        JsonNode n = parse(body);
        String name = text(n, "name");
        if (blank(name)) name = (String) t.get("name");
        String no = text(n, "experimentNo");
        if (blank(no)) no = "EXP-" + System.currentTimeMillis() % 100000000L;
        try {
            m().insertExperiment(TENANT, no.trim(), name.trim(),
                    ((Number) t.get("islandId")).longValue(), id, raw(n, "spec"));
            Long expId = m().selectExperimentIdByNo(TENANT, no.trim());
            if (expId == null) return bad("实例化失败");
            m().insertExperimentRun(TENANT, expId);
            Long runId = m().selectExperimentRunId(TENANT, expId);
            if (runId == null) return bad("建 experiment_run 失败");
            m().insertPlacementFromTemplate(TENANT, id, runId);
            audit.record(session(req), "experiment.instantiate", "experiment", expId, null,
                    null, req.getRemoteAddr());
            return ok(Map.of("experimentId", expId, "runId", runId,
                    "templateId", id, "experimentNo", no.trim()));
        } catch (Exception e) {
            return writeError(e, null);
        }
    }

    /**
     * 模板自检：这个模板**还差什么才能实例化并启动**。
     *
     * <p>为什么要有：模板编辑页只有"选流程/填物料"这些表单，缺项要到操作员
     * 配位置时才发现（"实例化出来 0 行物料"、"启动被预检挡住"）。这里把能静态
     * 判定的前置条件一次说清，应用者不用去猜。
     *
     * <p>只做**静态**检查：不碰设备在线、不碰物料是否已摆放（那是 readiness 的事）。
     */
    @GetMapping(value = "/experiment-templates/{id:\\d+}/check",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> check(@PathVariable long id)
            throws java.sql.SQLException {
        Map<String, Object> tpl = m().selectTemplate(TENANT, id);
        if (tpl == null) return notFound("模板不存在: " + id);
        Long islandId = tpl.get("islandId") == null
                ? null : ((Number) tpl.get("islandId")).longValue();
        String status = tpl.get("status") == null ? null : String.valueOf(tpl.get("status"));

        List<Map<String, Object>> issues = new ArrayList<>();

        // ---- ① 流程：存在 / 未删 / 有任务节点 / 岛一致 / 预检不阻塞 ----
        List<Map<String, Object>> procs = m().selectTemplateProcesses(TENANT, id);
        if (procs.isEmpty()) {
            issue(issues, "ERROR", "TEMPLATE-NO-PROCESS",
                    "模板还没有选流程：实例化后没有可执行内容");
        }
        for (Map<String, Object> proc : procs) {
            Object wv = proc.get("workflowId");
            if (!(wv instanceof Number wn)) {
                continue;
            }
            long wid = wn.longValue();
            Map<String, Object> w = m().selectWorkflowBrief(TENANT, wid);
            if (w == null) {
                issue(issues, "ERROR", "TEMPLATE-WORKFLOW-MISSING",
                        "流程 #" + wid + " 不存在（可能已被删除），请在模板里重新选");
                continue;
            }
            String code = w.get("code") == null ? ("#" + wid) : String.valueOf(w.get("code"));
            if (Boolean.TRUE.equals(w.get("deleted"))) {
                issue(issues, "ERROR", "TEMPLATE-WORKFLOW-DELETED",
                        "流程「" + code + "」已删除，模板里的这行流程需要替换");
                continue;
            }
            long taskCount = w.get("taskCount") instanceof Number n ? n.longValue() : 0;
            if (taskCount == 0) {
                issue(issues, "ERROR", "TEMPLATE-EMPTY-GRAPH",
                        "流程「" + code + "」没有任务节点（空图），实例化出来什么都不会跑");
            }
            Object wi = w.get("islandId");
            if (islandId != null && wi instanceof Number wIn
                    && wIn.longValue() != islandId) {
                issue(issues, "ERROR", "TEMPLATE-ISLAND-MISMATCH",
                        "流程「" + code + "」属于另一个岛（" + wIn.longValue()
                                + "），与模板的岛 " + islandId + " 不一致");
            }
            try {
                var report = new com.labos.kernel.workflow.PreflightChecker(db, TENANT).check(wid);
                if (report.blocking()) {
                    issue(issues, "ERROR", "TEMPLATE-PREFLIGHT-BLOCKING",
                            "流程「" + code + "」预检阻塞 " + report.errors()
                                    + " 条：去设计器修完再实例化");
                } else if (report.warnings() > 0) {
                    issue(issues, "WARN", "TEMPLATE-PREFLIGHT-WARN",
                            "流程「" + code + "」有 " + report.warnings()
                                    + " 条预检警告（不阻塞，但建议看一眼）");
                }
            } catch (RuntimeException ignore) {
                // 预检自身失败不当作模板问题
            }
        }

        // ---- ② 物料清单：有内容 + 角色不为空 ----
        List<Map<String, Object>> items = m().selectManifestItems(TENANT, id);
        if (items.isEmpty()) {
            issue(issues, "ERROR", "TEMPLATE-NO-MANIFEST",
                    "没有物料清单：实例化后操作员看不到任何要摆的东西");
        }
        for (Map<String, Object> it : items) {
            Object role = it.get("roleCode");
            if (role == null || String.valueOf(role).isBlank()) {
                issue(issues, "WARN", "TEMPLATE-MANIFEST-ROLE",
                        "有一条物料没有填角色名（操作员看不出这是什么）");
            }
        }

        // ---- ③ 点位白名单：非空 + 点位属于本岛 ----
        List<Long> pids = pointIdsOf(tpl.get("pointWhitelist"));
        if (pids.isEmpty()) {
            issue(issues, "WARN", "TEMPLATE-NO-WHITELIST",
                    "没有配点位白名单：应用者可以用岛内任意点位（建议收窄）");
        }
        for (Long pid : pids) {
            Long isl = m().selectPointIsland(TENANT, pid);
            if (isl == null) {
                issue(issues, "ERROR", "TEMPLATE-POINT-MISSING",
                        "白名单里的点位 #" + pid + " 不存在或不属于任何岛");
            } else if (islandId != null && !isl.equals(islandId)) {
                issue(issues, "ERROR", "TEMPLATE-WHITELIST-FOREIGN",
                        "白名单里的点位 #" + pid + " 属于别的岛（" + isl + "）");
            }
        }

        // ---- ③b 白名单 vs 流程绑定：模板引用的流程里，节点绑的点位必须都在白名单内 ----
        //
        // 为什么放在**模板**而不是流程预检：一张工艺图会被多个模板复用，
        // 把白名单挂到 workflow 预检上会让共享流程在别的场景下变红
        // （实测：给模板配了只含 DISP1 的白名单，43 节点流程立刻报 18 条阻塞）。
        // 白名单是"这个模板允许用哪些点位"，只能在模板这一层判。
        if (!pids.isEmpty()) {
            java.util.Set<Long> allowed = new java.util.LinkedHashSet<>(pids);
            for (Map<String, Object> proc : procs) {
                Object wv = proc.get("workflowId");
                if (!(wv instanceof Number wn)) {
                    continue;
                }
                long wid = wn.longValue();
                List<Map<String, Object>> rows;
                try {
                    rows = Mappers.get(com.labos.kernel.persistence.BindingMapper.class)
                            .selectNodeBindingPreflight(TENANT, wid);
                } catch (RuntimeException e) {
                    continue;
                }
                List<String> offending = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    Object pidObj = r.get("positionId");
                    if (!(pidObj instanceof Number pn)) {
                        continue;
                    }
                    if (!allowed.contains(pn.longValue())) {
                        String node = r.get("nodeName") == null
                                ? String.valueOf(r.get("nodeKey"))
                                : String.valueOf(r.get("nodeName"));
                        String pt = r.get("pointCode") == null
                                ? ("#" + pn.longValue()) : String.valueOf(r.get("pointCode"));
                        offending.add(node + " → " + pt);
                    }
                }
                if (!offending.isEmpty()) {
                    String sample = String.join("、", offending.subList(0,
                            Math.min(5, offending.size())));
                    issue(issues, "ERROR", "TEMPLATE-WHITELIST-BINDING",
                            "流程 #" + wid + " 有 " + offending.size()
                                    + " 个节点绑的点位不在白名单里：" + sample
                                    + (offending.size() > 5 ? " 等" : ""));
                }
            }
        }

        // ---- ④ 状态 ----
        if ("draft".equals(status)) {
            issue(issues, "WARN", "TEMPLATE-DRAFT", "模板还是草稿：现场用之前记得发布");
        }

        int errors = (int) issues.stream().filter(x -> "ERROR".equals(x.get("level"))).count();
        int warnings = issues.size() - errors;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("processes", procs.size());
        stats.put("manifestItems", items.size());
        stats.put("whitelistPoints", pids.size());
        stats.put("status", status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId", id);
        out.put("ok", errors == 0);
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("issues", issues);
        out.put("stats", stats);
        return ok(out);
    }

    private static void issue(List<Map<String, Object>> out, String level,
                              String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("message", message);
        out.add(m);
    }

    /** 从 jsonb 字符串里抠 pointIds（白名单很小，不需要为它引入 JSON 解析）。 */
    static List<Long> pointIdsOf(Object whitelist) {
        List<Long> out = new ArrayList<>();
        if (whitelist == null) {
            return out;
        }
        String json = String.valueOf(whitelist);
        int key = json.indexOf("\"pointIds\"");
        if (key < 0) {
            return out;
        }
        int lb = json.indexOf('[', key);
        int rb = lb < 0 ? -1 : json.indexOf(']', lb);
        if (lb < 0 || rb < 0) {
            return out;
        }
        for (String part : json.substring(lb + 1, rb).split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                try {
                    out.add(Long.parseLong(v));
                } catch (NumberFormatException ignore) {
                    // 跳过非法值
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 模板导出 / 导入
    //
    // 为什么要有：模板是"应用者层"的资产（选流程 + 物料清单 + 白名单），
    // 现场配好一套后要能搬到另一套环境（演示机 → 交付机、测试库 → 生产库）。
    // 关键约束：**导出里不能出现本环境的数据库 id** —— 岛/设备/点位/方法/角色
    // 全部用业务码（islandCode / deviceCode / pointCode / methodName / roleCode），
    // 导入时在目标环境重新解析。否则换个环境就全错位，而且错得很安静。

    private static final String EXPORT_KIND = "labos.experiment-template";
    private static final int EXPORT_VERSION = 1;

    /** 导出模板为 JSON（含引用的流程：图 XML + 阶段 + 绑定；全部用业务码）。 */
    @GetMapping(value = "/experiment-templates/{id:\\d+}/export",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> exportTemplate(@PathVariable long id) {
        Map<String, Object> tpl = m().selectTemplate(TENANT, id);
        if (tpl == null) return notFound("模板不存在: " + id);
        Long islandId = tpl.get("islandId") == null
                ? null : ((Number) tpl.get("islandId")).longValue();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", EXPORT_KIND);
        root.put("version", EXPORT_VERSION);
        root.put("exportedAt", java.time.OffsetDateTime.now().toString());

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", tpl.get("name"));
        t.put("status", tpl.get("status"));
        t.put("islandCode", islandId == null ? null
                : m().selectResourceCodeById(TENANT, islandId));
        t.put("spec", jsonOf(tpl.get("spec")));
        List<String> wlCodes = new ArrayList<>();
        for (Long pid : pointIdsOf(tpl.get("pointWhitelist"))) {
            String code = m().selectPointCodeById(TENANT, pid);
            if (code != null) wlCodes.add(code);
        }
        t.put("whitelistPointCodes", wlCodes);
        root.put("template", t);

        var graphMapper = Mappers.get(com.labos.kernel.persistence.GraphMapper.class);
        List<Map<String, Object>> procs = m().selectTemplateProcesses(TENANT, id);
        List<Object> wfs = new ArrayList<>();
        for (Map<String, Object> proc : procs) {
            if (!(proc.get("workflowId") instanceof Number wn)) {
                continue;
            }
            long wid = wn.longValue();
            Map<String, Object> brief = m().selectWorkflowBrief(TENANT, wid);
            if (brief == null) {
                continue;
            }
            Map<String, Object> wf = new LinkedHashMap<>();
            wf.put("code", brief.get("code"));
            wf.put("name", brief.get("name"));
            Object wi = brief.get("islandId");
            wf.put("islandCode", wi instanceof Number n
                    ? m().selectResourceCodeById(TENANT, n.longValue()) : null);
            wf.put("graphXml", graphMapper.selectGraphXml(TENANT, wid));
            Map<String, String> stages = new LinkedHashMap<>();
            for (Map<String, Object> node : graphMapper.selectWorkflowNodes(wid)) {
                Object k = node.get("nodeKey");
                Object st = node.get("stageCode");
                if (k != null && st != null) {
                    stages.put(String.valueOf(k), String.valueOf(st));
                }
            }
            wf.put("stages", stages);
            List<Object> binds = new ArrayList<>();
            for (Map<String, Object> b : m().selectBindingsForExport(TENANT, wid)) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("nodeKey", b.get("nodeKey"));
                x.put("seq", b.get("seq"));
                x.put("deviceCode", b.get("deviceCode"));
                x.put("pointCode", b.get("pointCode"));
                x.put("methodName", b.get("methodName"));
                x.put("materialRole", b.get("materialRole"));
                x.put("loadingPattern", b.get("loadingPattern"));
                x.put("barcodePolicy", b.get("barcodePolicy"));
                x.put("leaveAtStation", b.get("leaveAtStation"));
                x.put("enabled", b.get("enabled"));
                x.put("params", jsonOf(b.get("params")));
                binds.add(x);
            }
            wf.put("bindings", binds);
            wfs.add(wf);
        }
        root.put("workflows", wfs);

        List<Object> items = new ArrayList<>();
        for (Map<String, Object> it : m().selectManifestItems(TENANT, id)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("roleCode", it.get("roleCode"));
            x.put("materialKind", it.get("materialKind"));
            x.put("materialRef", it.get("materialRef"));
            x.put("quantity", it.get("quantity"));
            x.put("required", it.get("required"));
            items.add(x);
        }
        root.put("manifestItems", items);

        try {
            return ResponseEntity.ok(JSON.writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return bad("导出序列化失败：" + e.getMessage());
        }
    }

    /**
     * 从 JSON 导入模板。
     *
     * <p><b>先全量校验再落库</b>：岛/点位/方法/角色任一解析不到就整单 400 并列出问题，
     * 不会留下"建了一半的流程 + 半个模板"（那种半成品比失败更难收拾）。
     */
    @PostMapping(value = "/experiment-templates/import",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> importTemplate(@RequestBody(required = false) String body,
                                                 HttpServletRequest req) {
        JsonNode n = parse(body);
        if (!EXPORT_KIND.equals(text(n, "kind"))) {
            return bad("不是模板导出文件（kind != " + EXPORT_KIND + "）");
        }
        JsonNode tn = n.path("template");
        String name = text(tn, "name");
        if (blank(name)) return bad("模板缺少 name");
        Long islandId = m().selectIslandIdByCode(TENANT, text(tn, "islandCode"));
        if (islandId == null) {
            return bad("目标环境没有岛「" + text(tn, "islandCode") + "」：请先建岛或改导出文件里的 islandCode");
        }

        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ① 白名单点位码 → id
        List<Long> wlIds = new ArrayList<>();
        for (JsonNode c : n.path("template").path("whitelistPointCodes")) {
            String code = c.asText(null);
            if (blank(code)) continue;
            Long pid = m().selectPointIdByCode(TENANT, code, null);
            if (pid == null) problems.add("白名单点位「" + code + "」在目标环境不存在");
            else wlIds.add(pid);
        }

        // ② 流程与绑定：全部先解析
        List<JsonNode> wfs = arrayOf(n, "workflows");
        if (wfs.isEmpty()) problems.add("导出文件里没有 workflows（模板会是空壳）");
        java.util.Map<Integer, List<Object[]>> resolvedBinds = new LinkedHashMap<>();
        for (int i = 0; i < wfs.size(); i++) {
            JsonNode wf = wfs.get(i);
            String wcode = text(wf, "code");
            if (blank(wcode)) problems.add("第 " + (i + 1) + " 条流程缺少 code");
            if (text(wf, "graphXml") == null) {
                problems.add("流程「" + wcode + "」缺少 graphXml");
            }
            List<Object[]> binds = new ArrayList<>();
            for (JsonNode b : wf.path("bindings")) {
                String pointCode = text(b, "pointCode");
                String deviceCode = text(b, "deviceCode");
                String methodName = text(b, "methodName");
                Long pid = null;
                Long mid = null;
                if (!blank(pointCode)) {
                    pid = m().selectPointIdByCode(TENANT, pointCode, deviceCode);
                    if (pid == null) {
                        problems.add("流程「" + wcode + "」绑定点位「" + pointCode
                                + "」(" + deviceCode + ") 在目标环境不存在");
                    }
                }
                if (!blank(methodName) && !blank(deviceCode)) {
                    mid = m().selectMethodIdByDeviceAndName(TENANT, deviceCode, methodName);
                    if (mid == null) {
                        problems.add("设备「" + deviceCode + "」没有方法「" + methodName + "」");
                    }
                }
                String role = text(b, "materialRole");
                if (!blank(role) && m().selectMaterialRoleIdByCode(TENANT, role) == null) {
                    warnings.add("物料角色「" + role + "」在目标环境不存在，该绑定导入后角色为空（PF-23 会提示）");
                    role = null;
                }
                binds.add(new Object[]{text(b, "nodeKey"), pid, mid, role,
                        text(b, "loadingPattern"), text(b, "barcodePolicy"),
                        Boolean.TRUE.equals(boolOrNull(b, "leaveAtStation")),
                        b.has("params") && !b.get("params").isNull() ? b.get("params").toString() : null});
            }
            resolvedBinds.put(i, binds);
        }
        if (!problems.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "导入前校验未通过（未写入任何数据）");
            out.put("problems", problems);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(toJson(out));
        }

        // ③ 落库：流程（图 + 阶段 + 绑定）→ 模板 → 流程清单 → 物料 → 白名单
        List<Long> newWids = new ArrayList<>();
        try {
            var graphMapper = Mappers.get(com.labos.kernel.persistence.GraphMapper.class);
            var bm = Mappers.get(com.labos.kernel.persistence.BindingMapper.class);
            for (int i = 0; i < wfs.size(); i++) {
                JsonNode wf = wfs.get(i);
                String wcode = text(wf, "code");
                String wname = text(wf, "name");
                // 重名必须改名：uk_workflow=(tenant, code, version)，撞上就整单失败。
                // 后缀保留原编码，现场还能看出"这条是从哪导过来的"。
                if (m().selectWorkflowIdByCode(TENANT, wcode) != null) {
                    String base = wcode;
                    wcode = base + "-IMP-" + (System.currentTimeMillis() % 100000);
                    int guard = 0;
                    while (m().selectWorkflowIdByCode(TENANT, wcode) != null && guard++ < 20) {
                        wcode = base + "-IMP-" + (System.currentTimeMillis() % 100000)
                                + "-" + guard;
                    }
                    warnings.add("流程编码「" + base + "」已存在，导入为「" + wcode + "」");
                }
                long wid = workflows.createWorkflow(
                        blank(wname) ? wcode : wname,
                        wcode, "choreography", islandId);
                workflows.saveGraph(wid, text(wf, "graphXml"), true);
                // 阶段：XML 里通常已带 stageCode，这里再兜一次（老导出文件可能没有）
                var stages = wf.path("stages");
                var it = stages.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    graphMapper.updateNodeStage(TENANT, wid, e.getKey(), e.getValue().asText());
                }
                // 绑定：按 nodeKey 挂（顺序不能反，node 必须先存在）
                java.util.Map<String, List<Object[]>> byNode = new LinkedHashMap<>();
                for (Object[] row : resolvedBinds.getOrDefault(i, List.of())) {
                    String nodeKey = (String) row[0];
                    if (blank(nodeKey)) continue;
                    byNode.computeIfAbsent(nodeKey, k -> new ArrayList<>()).add(row);
                }
                for (var entry : byNode.entrySet()) {
                    Long nodeId = bm.selectNodeIdByKey(TENANT, wid, entry.getKey());
                    if (nodeId == null) {
                        warnings.add("流程「" + wcode + "」找不到节点 " + entry.getKey() + "，该节点绑定未导入");
                        continue;
                    }
                    bm.deleteNodeBindings(TENANT, nodeId);
                    int seq = 1;
                    for (Object[] row : entry.getValue()) {
                        Long pid = (Long) row[1];
                        Long mid = (Long) row[2];
                        if (pid == null && mid == null) continue;
                        bm.insertNodeBinding(TENANT, nodeId, seq++, pid, null, null, mid,
                                (String) row[3], (String) row[4], (Boolean) row[6],
                                (String) row[5], (String) row[7]);
                    }
                }
                newWids.add(wid);
            }

            String tno = "TPL-" + System.currentTimeMillis() % 100000000L;
            // ⚠ 必须是 **PostgreSQL 数组字面量**（{1,2}），不是 JSON 数组（[1,2]）——
            // 列类型是 bigint[]，喂 JSON 会报 malformed array literal（实测踩过）。
            StringBuilder idsJson = new StringBuilder("{");
            for (int i = 0; i < newWids.size(); i++) {
                if (i > 0) idsJson.append(',');
                idsJson.append(newWids.get(i));
            }
            idsJson.append('}');
            m().insertTemplate(TENANT, tno, name.trim(), islandId, idsJson.toString(),
                    tn.has("spec") ? tn.get("spec").toString() : null);
            Long tid = lastTemplateId(tno);
            if (tid == null) throw new IllegalStateException("模板落库失败");
            int seq = 1;
            for (Long wid : newWids) {
                // params 列 NOT NULL：给 "{}" 而不是 null（实测踩过约束）
                m().insertTemplateProcess(TENANT, tid, seq++, wid, "{}");
            }
            seq = 1;
            for (JsonNode it : n.path("manifestItems")) {
                String kind = text(it, "materialKind");
                if (kind == null) kind = "plate";
                m().insertManifestItem(TENANT, tid, seq++, text(it, "roleCode"), kind,
                        longOrNull(it, "materialRef"), doubleOf(it, "quantity", 1),
                        boolOf(it, "required", true));
            }
            StringBuilder wl = new StringBuilder("{\"pointIds\":[");
            for (int i = 0; i < wlIds.size(); i++) {
                if (i > 0) wl.append(',');
                wl.append(wlIds.get(i));
            }
            wl.append("]}");
            m().updateTemplateWhitelist(tid, wl.toString());
            String status = text(tn, "status");
            if (status != null) m().updateTemplate(tid, null, status, null);

            audit.record(session(req), "experimentTemplate.import", "experiment_template", tid,
                    tno, toJson(Map.of("workflows", newWids.size())), req.getRemoteAddr());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("templateId", tid);
            out.put("templateNo", tno);
            out.put("workflowIds", newWids);
            out.put("warnings", warnings);
            return ok(out);
        } catch (Exception e) {
            // 把原因带回响应：导入是"跨环境搬运"，失败必须能自我诊断，
            // 否则只能翻内核日志（而且现场常常拿不到日志）。
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "导入失败（可能留下部分流程，请手工清理）：" + e.getMessage());
            out.put("workflowIds", newWids);
            out.put("warnings", warnings);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(toJson(out));
        }
    }

    private static Object jsonOf(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return s;
        }
    }

    private static Boolean boolOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asBoolean();
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    // ---------------------------------------------------------------- 物料配置

    @GetMapping(value = "/experiment-runs/{runId:\\d+}/manifest",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> manifest(@PathVariable long runId) {
        List<Map<String, Object>> items = m().selectPlacements(TENANT, runId);
        return ok(Map.of("runId", runId, "total", items.size(), "items", items));
    }

    @PutMapping(value = "/experiment-runs/{runId:\\d+}/placements/{placementId:\\d+}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> configPlacement(@PathVariable long runId,
                                                  @PathVariable long placementId,
                                                  @RequestBody(required = false) String body,
                                                  HttpServletRequest req) {
        JsonNode n = parse(body);
        long positionId = longOf(n, "positionId");
        if (positionId <= 0) return bad("必填 positionId");
        int r = m().updatePlacementPosition(TENANT, placementId, positionId);
        if (r == 0) return notFound("物料配置项不存在: " + placementId);
        audit.record(session(req), "manifest.place", "manifest_placement", placementId,
                null, null, req.getRemoteAddr());
        return ok(Map.of("placed", r, "placementId", placementId, "positionId", positionId));
    }

    @PostMapping(value = "/experiment-runs/{runId:\\d+}/placements/{placementId:\\d+}/verify",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> verifyPlacement(@PathVariable long runId,
                                                  @PathVariable long placementId,
                                                  HttpServletRequest req) {
        Long uid = session(req) == null ? null : session(req).userId();
        int r = m().updatePlacementVerify(TENANT, placementId, uid == null ? 1L : uid);
        if (r == 0) return notFound("物料配置项不存在: " + placementId);
        audit.record(session(req), "manifest.verify", "manifest_placement", placementId,
                null, null, req.getRemoteAddr());
        return ok(Map.of("verified", r, "placementId", placementId));
    }

    // ---------------------------------------------------------------- 三条件启动

    @GetMapping(value = "/experiment-runs/{runId:\\d+}/readiness",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> readiness(@PathVariable long runId) {
        List<String> offline = m().selectOfflineDevices(TENANT, runId);
        long unverified = m().countUnverifiedPlacements(TENANT, runId);
        boolean c1 = offline.isEmpty();
        boolean c3 = unverified == 0;
        // C2 work_ready：P1 简化为「有物料清单且无未分配位置」——流程级预检在 start 时再跑
        List<Map<String, Object>> items = m().selectPlacements(TENANT, runId);
        boolean hasUnassigned = items.stream().anyMatch(x -> "unassigned".equals(x.get("status")));
        boolean c2 = !hasUnassigned;
        List<Map<String, Object>> reasons = new ArrayList<>();
        if (!c1) {
            reasons.add(Map.of("code", "NOT_ONLINE", "message", "设备离线: " + String.join(",", offline)));
        }
        if (!c2) {
            reasons.add(Map.of("code", "WORK_NOT_READY", "message", "存在未分配位置的物料"));
        }
        if (!c3) {
            reasons.add(Map.of("code", "MATERIAL_UNVERIFIED", "message",
                    unverified + " 个必填物料未核验"));
        }
        return ok(Map.of("runId", runId, "ready", c1 && c2 && c3,
                "checks", Map.of("c1_system_online", c1, "c2_work_ready", c2,
                        "c3_material_verified", c3),
                "reasons", reasons));
    }

    @PostMapping(value = "/experiment-runs/{runId:\\d+}/start",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> start(@PathVariable long runId, HttpServletRequest req) {
        ResponseEntity<String> rd = readiness(runId);
        JsonNode n = parse(rd.getBody());
        if (!n.path("ready").asBoolean()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(err("启动条件不满足："
                            + n.path("reasons").toString()));
        }
        audit.record(session(req), "experiment.start", "experiment_run", runId, null,
                null, req.getRemoteAddr());
        return ok(Map.of("runId", runId, "started", true, "note",
                "readiness 通过，实际派发走既有调度链路（P1 未改派发核心）"));
    }

    /**
     * R12 §4.5.2：请求卸载 lifecycle='process' 的物料（loaded → unloading，位置仍占用）。
     * 确认卸载（unloading → unloaded + 清位置）走 confirm-unload。
     */
    @PostMapping(value = "/experiment-runs/{runId:\\d+}/unload-process-materials",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> unloadProcessMaterials(@PathVariable long runId,
                                                         HttpServletRequest req) {
        try {
            int[] n = {0};
            com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
                n[0] = m().markProcessPlacementsUnloading(TENANT, runId);
                return null;
            });
            audit.record(session(req), "material.unloadProcess", "experiment_run", runId,
                    null, null, req.getRemoteAddr());
            return ok(Map.of("runId", runId, "unloading", n[0], "note",
                    "已请求卸载（unloading），位置仍占用；确认卸载走 confirm-unload"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(err("请求卸载失败：" + e.getMessage()));
        }
    }

    /**
     * R12：确认卸载——unloading → unloaded + 清 position_state。
     * 「发了卸载请求 ≠ 物料已卸载」，确认后才清位置。
     */
    @PostMapping(value = "/experiment-runs/{runId:\\d+}/confirm-unload",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> confirmUnload(@PathVariable long runId,
                                                HttpServletRequest req) {
        try {
            int[] n = {0};
            com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
                // 先清位置（placement 仍 unloading 才能命中 WHERE），再置 unloaded
                m().clearProcessPositionStates(TENANT, runId);
                n[0] = m().markProcessPlacementsUnloaded(TENANT, runId);
                return null;
            });
            audit.record(session(req), "material.confirmUnload", "experiment_run", runId,
                    null, null, req.getRemoteAddr());
            return ok(Map.of("runId", runId, "unloaded", n[0]));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(err("确认卸载失败：" + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Long lastTemplateId(String no) {
        for (Map<String, Object> t : m().selectTemplates(TENANT, null, null)) {
            if (no.equals(t.get("templateNo"))) return asLong(t, "id");
        }
        return null;
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static JsonNode parse(String body) {
        try {
            return body == null ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean boolOf(JsonNode n, String key, boolean dft) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? dft : v.asBoolean();
    }

    private static long longOf(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? 0L : v.asLong();
    }

    private static Long longOrNull(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static double doubleOf(JsonNode n, String key, double dft) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? dft : v.asDouble();
    }

    private static List<JsonNode> arrayOf(JsonNode n, String key) {
        JsonNode arr = n == null ? null : n.get(key);
        List<JsonNode> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    private static String raw(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? "{}" : v.toString();
    }

    /** JSON 数组 [1,2,3] → Postgres 数组字面量 {1,2,3}（CAST(? AS bigint[]) 用）。 */
    private static String jsonArray(List<JsonNode> items) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(items.get(i).asLong());
        }
        sb.append("}");
        return sb.toString();
    }

    private static Long asLong(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number num ? num.longValue() : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s;
    }

    private static Long parseLongOrNull(String s) {
        if (blank(s)) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }

    private static ResponseEntity<String> bad(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(msg));
    }

    private static ResponseEntity<String> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(msg));
    }

    /** 统一走 ApiErrors：不再吞真因（带 detail + 约束名），2026-11-17。 */
    private static ResponseEntity<String> writeError(Exception e, String hint) {
        return com.labos.boot.web.ApiErrors.writeError(e, hint);
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static ResponseEntity<String> ok(Object o) {
        return ResponseEntity.ok(json(o));
    }
}
