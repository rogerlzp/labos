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

    public ExperimentTemplateController(DbConfig db, AuditRecorder audit) {
        this.db = db;
        this.audit = audit;
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
     * §4.5.2 物料生命周期：实验结束后，自动卸载 lifecycle='process' 的物料
     * （清 position_state + 置 placement unloaded）；lifecycle='inventory' 的保持原位。
     * 单事务；返回卸载条数。
     */
    @PostMapping(value = "/experiment-runs/{runId:\\d+}/unload-process-materials",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> unloadProcessMaterials(@PathVariable long runId,
                                                         HttpServletRequest req) {
        try {
            int[] n = {0};
            com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
                n[0] = m().markProcessPlacementsUnloaded(TENANT, runId);
                m().clearProcessPositionStates(TENANT, runId);
                return null;
            });
            audit.record(session(req), "material.unloadProcess", "experiment_run", runId,
                    null, null, req.getRemoteAddr());
            return ok(Map.of("runId", runId, "unloaded", n[0]));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(err("卸载失败：" + e.getMessage()));
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

    private static ResponseEntity<String> writeError(Exception e, String hint) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("duplicate key") || msg.contains("violates unique") || msg.contains("uk_")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err(hint != null ? hint : "唯一约束冲突"));
        }
        if (msg.contains("ck_") || msg.contains("check constraint")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err(hint != null ? hint : "数据约束冲突"));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err("写失败：" + msg));
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
