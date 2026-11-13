package com.labos.boot.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.BindingMapper;
import com.labos.kernel.persistence.DbConfig;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.labos.kernel.api.ApiServer.err;

/**
 * P0 · 三条绑定链 API：设备池 / 点位方法 / 节点绑定。
 *
 * <p>⚠ ID 空间：{@code /api/positions/{positionId}/methods} 里的 {@code positionId}
 * 是 <b>device_point.id</b>（配置层），不是 resource_node.id（点位实体）。服务端
 * 用 {@code selectPositionInfo} 再解析出点位码与资源 id。
 *
 * <p>薄 HTTP 层，SQL 全在 {@code BindingMapper.xml}，照 {@code StockController} 范本。
 */
@RestController
@RequestMapping("/api")
public class BindingController {

    private static final long TENANT = 1L;
    private static final Set<String> POOL_KINDS = Set.of("operation", "storage");
    private static final Set<String> POOL_STRATEGIES =
            Set.of("first_available", "least_recently_used", "round_robin", "priority_commit");
    private static final Set<String> SCOPE_KINDS = Set.of("global", "workflow", "experiment");
    private static final Set<String> ALLOCATIONS = Set.of("explicit", "auto_first_free", "auto_next_free");
    private static final Set<String> LOADING_PATTERNS =
            Set.of("do_not_load", "load_single", "load_selected", "load_all_remaining");
    private static final Set<String> BARCODE_POLICIES =
            Set.of("none", "read", "update", "validate_in", "validate_out", "validate_both");

    private final DbConfig db;
    private final AuditRecorder audit;

    public BindingController(DbConfig db, AuditRecorder audit) {
        this.db = db;
        this.audit = audit;
    }

    private BindingMapper m() {
        return Mappers.get(BindingMapper.class);
    }

    // ---------------------------------------------------------------- 设备池

    @GetMapping(value = "/pools", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> pools(@RequestParam(required = false) String islandId,
                                        @RequestParam(required = false) String kind) {
        List<Map<String, Object>> rows = m().selectPools(TENANT,
                parseLongOrNull(islandId), blankToNull(kind));
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @GetMapping(value = "/pools/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> pool(@PathVariable long id) {
        Map<String, Object> p = m().selectPool(TENANT, id);
        if (p == null) {
            return notFound("池不存在: " + id);
        }
        p.put("members", m().selectPoolMembers(TENANT, id));
        return ok(p);
    }

    @PostMapping(value = "/pools", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createPool(@RequestBody(required = false) String body,
                                             HttpServletRequest req) {
        JsonNode n = parse(body);
        String code = text(n, "code");
        String name = text(n, "name");
        long islandId = longOf(n, "islandId");
        String kind = text(n, "poolKind");
        String strategy = text(n, "strategy");
        if (blank(code) || blank(name) || islandId <= 0) {
            return bad("必填 code / name / islandId");
        }
        if (kind == null) kind = "operation";
        if (!POOL_KINDS.contains(kind)) return bad("poolKind 非法：" + kind);
        if (strategy == null) strategy = "first_available";
        if (!POOL_STRATEGIES.contains(strategy)) return bad("strategy 非法：" + strategy);

        // operation 池：成员必须同型号（否则 409）
        List<JsonNode> members = membersOf(n);
        String sameModelErr = operationPoolModelCheck(members);
        if (sameModelErr != null) {
            return conflict(sameModelErr);
        }

        try {
            m().insertPool(TENANT, code.trim(), name.trim(), islandId, kind, strategy,
                    boolOf(n, "skipOnError", true), boolOf(n, "skipOnOffline", true),
                    boolOf(n, "allowIterationOnError", true), intOf(n, "minNestCount", 0));
            Long poolId = lastPoolId(code);
            if (poolId != null) {
                for (JsonNode mem : members) {
                    m().insertPoolMember(TENANT, poolId, longOf(mem, "deviceInstanceId"),
                            text(mem, "subGroup"), intOf(mem, "priority", 100));
                }
            }
            audit.record(session(req), "pool.create", "device_pool", poolId == null ? 0L : poolId,
                    null, null, req.getRemoteAddr());
            return ok(Map.of("id", poolId == null ? 0L : poolId,
                    "code", code.trim(), "memberCount", members.size()));
        } catch (Exception e) {
            return writeError(e, "code 可能重复");
        }
    }

    @PutMapping(value = "/pools/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updatePool(@PathVariable long id,
                                             @RequestBody(required = false) String body,
                                             HttpServletRequest req) {
        JsonNode n = parse(body);
        String strategy = text(n, "strategy");
        if (strategy != null && !POOL_STRATEGIES.contains(strategy)) {
            return bad("strategy 非法：" + strategy);
        }
        int n2 = m().updatePool(id, text(n, "name"), strategy,
                boolOf(n, "skipOnError", true), boolOf(n, "skipOnOffline", true),
                boolOf(n, "allowIterationOnError", true), boolOf(n, "enabled", true));
        if (n2 == 0) return notFound("池不存在: " + id);
        audit.record(session(req), "pool.update", "device_pool", id, null, null,
                req.getRemoteAddr());
        return ok(Map.of("updated", n2, "id", id));
    }

    @DeleteMapping(value = "/pools/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deletePool(@PathVariable long id, HttpServletRequest req) {
        if (m().countPoolBindings(TENANT, id) > 0) {
            return conflict("该池被 node_binding 引用，不能删除");
        }
        int n = m().deletePool(TENANT, id);
        if (n == 0) return notFound("池不存在: " + id);
        audit.record(session(req), "pool.delete", "device_pool", id, null, null,
                req.getRemoteAddr());
        return ok(Map.of("deleted", n, "id", id));
    }

    @PostMapping(value = "/pools/{id:\\d+}/members", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> addPoolMember(@PathVariable long id,
                                                @RequestBody(required = false) String body,
                                                HttpServletRequest req) {
        JsonNode n = parse(body);
        long di = longOf(n, "deviceInstanceId");
        if (di <= 0) return bad("必填 deviceInstanceId");
        // operation 池：新成员型号要与已有成员一致
        Map<String, Object> pool = m().selectPool(TENANT, id);
        if (pool == null) return notFound("池不存在: " + id);
        if ("operation".equals(pool.get("poolKind"))) {
            List<Map<String, Object>> existing = m().selectPoolMembers(TENANT, id);
            if (!existing.isEmpty()) {
                Long existingModel = m().selectDeviceModelId(TENANT,
                        asLong(existing.get(0), "deviceInstanceId"));
                Long newModel = m().selectDeviceModelId(TENANT, di);
                if (existingModel != null && newModel != null && !existingModel.equals(newModel)) {
                    return conflict("operation 池的成员必须同型号");
                }
            }
        }
        try {
            m().insertPoolMember(TENANT, id, di, text(n, "subGroup"), intOf(n, "priority", 100));
            audit.record(session(req), "pool.member.add", "device_pool_member", id, null,
                    null, req.getRemoteAddr());
            return ok(Map.of("added", 1, "poolId", id, "deviceInstanceId", di));
        } catch (Exception e) {
            return writeError(e, null);
        }
    }

    @DeleteMapping(value = "/pools/{id:\\d+}/members/{deviceInstanceId:\\d+}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> removePoolMember(@PathVariable long id,
                                                   @PathVariable long deviceInstanceId,
                                                   HttpServletRequest req) {
        int n = m().deletePoolMember(TENANT, id, deviceInstanceId);
        audit.record(session(req), "pool.member.remove", "device_pool_member", id, null,
                null, req.getRemoteAddr());
        return ok(Map.of("removed", n, "poolId", id, "deviceInstanceId", deviceInstanceId));
    }

    // ---------------------------------------------------------------- 点位方法

    @GetMapping(value = "/positions/{positionId:\\d+}/methods",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> positionMethods(@PathVariable long positionId,
                                                  @RequestParam(required = false) String scopeKind,
                                                  @RequestParam(required = false) String scopeId) {
        Map<String, Object> info = m().selectPositionInfo(TENANT, positionId);
        if (info == null) return notFound("点位配置不存在: " + positionId);
        List<Map<String, Object>> bindings = m().selectPositionMethods(TENANT, positionId,
                blankToNull(scopeKind), parseLongOrNull(scopeId));
        Map<String, Object> out = new java.util.LinkedHashMap<>(info);
        out.put("bindings", bindings);
        return ok(out);
    }

    @PostMapping(value = "/positions/{positionId:\\d+}/methods",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> addPositionMethod(@PathVariable long positionId,
                                                    @RequestBody(required = false) String body,
                                                    HttpServletRequest req) {
        JsonNode n = parse(body);
        long methodId = longOf(n, "methodId");
        String scopeKind = text(n, "scopeKind");
        if (methodId <= 0) return bad("必填 methodId");
        if (scopeKind == null) scopeKind = "global";
        if (!SCOPE_KINDS.contains(scopeKind)) return bad("scopeKind 非法：" + scopeKind);
        String allocation = text(n, "allocation");
        if (allocation == null) allocation = "explicit";
        if (!ALLOCATIONS.contains(allocation)) return bad("allocation 非法：" + allocation);
        try {
            m().insertPositionMethod(TENANT, positionId, methodId, scopeKind,
                    longOrNull(n, "scopeId"), text(n, "purpose"), allocation,
                    intOf(n, "priority", 100));
            audit.record(session(req), "positionMethod.create", "position_method", positionId,
                    null, null, req.getRemoteAddr());
            return ok(Map.of("created", 1, "positionId", positionId, "methodId", methodId));
        } catch (Exception e) {
            return writeError(e, "该点位×方法绑定可能已存在");
        }
    }

    @PutMapping(value = "/positions/{positionId:\\d+}/methods/{bindingId:\\d+}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updatePositionMethod(@PathVariable long positionId,
                                                       @PathVariable long bindingId,
                                                       @RequestBody(required = false) String body,
                                                       HttpServletRequest req) {
        JsonNode n = parse(body);
        String allocation = text(n, "allocation");
        if (allocation != null && !ALLOCATIONS.contains(allocation)) {
            return bad("allocation 非法：" + allocation);
        }
        int r = m().updatePositionMethod(bindingId, text(n, "purpose"),
                allocation == null ? "explicit" : allocation,
                intOf(n, "priority", 100), boolOf(n, "enabled", true));
        if (r == 0) return notFound("绑定不存在: " + bindingId);
        audit.record(session(req), "positionMethod.update", "position_method", bindingId,
                null, null, req.getRemoteAddr());
        return ok(Map.of("updated", r, "id", bindingId));
    }

    @DeleteMapping(value = "/positions/{positionId:\\d+}/methods/{bindingId:\\d+}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deletePositionMethod(@PathVariable long positionId,
                                                       @PathVariable long bindingId,
                                                       HttpServletRequest req) {
        int r = m().deletePositionMethod(bindingId);
        audit.record(session(req), "positionMethod.delete", "position_method", bindingId,
                null, null, req.getRemoteAddr());
        return ok(Map.of("deleted", r, "id", bindingId));
    }

    @GetMapping(value = "/positions/method-matrix", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> methodMatrix(@RequestParam(required = false) String islandId) {
        long isl = parseLongOrNull(islandId) == null ? 0L : parseLongOrNull(islandId);
        if (isl <= 0) return bad("需要 islandId");
        List<Map<String, Object>> rows = m().selectPositionMethodMatrix(TENANT, isl);
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    // ---------------------------------------------------------------- 节点绑定

    @GetMapping(value = "/workflows/{id:\\d+}/nodes/{nodeKey}/bindings",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> nodeBindings(@PathVariable long id,
                                               @PathVariable String nodeKey) {
        Long nodeId = m().selectNodeIdByKey(TENANT, id, nodeKey);
        if (nodeId == null) return notFound("节点不存在: " + nodeKey);
        List<Map<String, Object>> rows = m().selectNodeBindings(TENANT, nodeId);
        return ok(Map.of("nodeKey", nodeKey, "count", rows.size(), "bindings", rows));
    }

    @PutMapping(value = "/workflows/{id:\\d+}/nodes/{nodeKey}/bindings",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> replaceNodeBindings(@PathVariable long id,
                                                      @PathVariable String nodeKey,
                                                      @RequestBody(required = false) String body,
                                                      HttpServletRequest req) {
        JsonNode n = parse(body);
        Long nodeId = m().selectNodeIdByKey(TENANT, id, nodeKey);
        if (nodeId == null) return notFound("节点不存在: " + nodeKey);
        List<JsonNode> bindings = arrayOf(n, "bindings");
        // 整体替换：先删后插
        m().deleteNodeBindings(TENANT, nodeId);
        int seq = 1;
        try {
            for (JsonNode b : bindings) {
                Long positionId = longOrNull(b, "positionId");
                Long deviceInstanceId = longOrNull(b, "deviceInstanceId");
                Long poolId = longOrNull(b, "poolId");
                // ck_node_binding_target：三者至少一个非空
                if (positionId == null && deviceInstanceId == null && poolId == null) {
                    return bad("每条绑定至少一个 positionId / deviceInstanceId / poolId");
                }
                String loading = text(b, "loadingPattern");
                String barcode = text(b, "barcodePolicy");
                if (loading != null && !LOADING_PATTERNS.contains(loading)) {
                    return bad("loadingPattern 非法：" + loading);
                }
                if (barcode != null && !BARCODE_POLICIES.contains(barcode)) {
                    return bad("barcodePolicy 非法：" + barcode);
                }
                m().insertNodeBinding(TENANT, nodeId, seq++, positionId, deviceInstanceId,
                        poolId, longOrNull(b, "methodId"), text(b, "materialRole"),
                        loading == null ? "load_single" : loading,
                        boolOf(b, "leaveAtStation", false),
                        barcode == null ? "none" : barcode,
                        raw(b, "params"));
            }
            m().updateNodeBindingMode(TENANT, id, nodeKey,
                    bindings.isEmpty() ? "legacy" : "point");
            audit.record(session(req), "nodeBinding.replace", "node_binding", nodeId, null,
                    null, req.getRemoteAddr());
            return ok(Map.of("nodeKey", nodeKey, "count", bindings.size(),
                    "bindingMode", bindings.isEmpty() ? "legacy" : "point"));
        } catch (Exception e) {
            return writeError(e, null);
        }
    }

    @DeleteMapping(value = "/workflows/{id:\\d+}/nodes/{nodeKey}/bindings",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> clearNodeBindings(@PathVariable long id,
                                                    @PathVariable String nodeKey,
                                                    HttpServletRequest req) {
        Long nodeId = m().selectNodeIdByKey(TENANT, id, nodeKey);
        if (nodeId == null) return notFound("节点不存在: " + nodeKey);
        int r = m().deleteNodeBindings(TENANT, nodeId);
        m().updateNodeBindingMode(TENANT, id, nodeKey, "legacy");
        audit.record(session(req), "nodeBinding.clear", "node_binding", nodeId, null,
                null, req.getRemoteAddr());
        return ok(Map.of("cleared", r, "nodeKey", nodeKey, "bindingMode", "legacy"));
    }

    // ---------------------------------------------------------------- helpers

    /** operation 池建池时校验成员同型号；返回 null=通过，否则中文错误。 */
    private String operationPoolModelCheck(List<JsonNode> members) {
        if (members == null || members.size() < 2) return null;
        Long model = null;
        for (JsonNode mem : members) {
            long di = longOf(mem, "deviceInstanceId");
            if (di <= 0) continue;
            Long mm = m().selectDeviceModelId(TENANT, di);
            if (mm == null) continue;
            if (model == null) model = mm;
            else if (!model.equals(mm)) {
                return "operation 池的成员必须同型号：设备实例 " + di + " 的型号与其他成员不同";
            }
        }
        return null;
    }

    private Long lastPoolId(String code) {
        for (Map<String, Object> p : m().selectPools(TENANT, null, null)) {
            if (code.equals(p.get("code"))) {
                return asLong(p, "id");
            }
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

    private static int intOf(JsonNode n, String key, int dft) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? dft : v.asInt();
    }

    private static long longOf(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? 0L : v.asLong();
    }

    private static Long longOrNull(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static List<JsonNode> membersOf(JsonNode n) {
        JsonNode arr = n == null ? null : n.get("members");
        List<JsonNode> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
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

    private static ResponseEntity<String> conflict(String msg) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(err(msg));
    }

    private static ResponseEntity<String> writeError(Exception e, String hint) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("duplicate key") || msg.contains("violates unique")
                || msg.contains("uk_")) {
            return conflict(hint != null ? hint : "唯一约束冲突");
        }
        if (msg.contains("ck_") || msg.contains("check constraint")) {
            return conflict(hint != null ? hint : "数据约束冲突");
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
