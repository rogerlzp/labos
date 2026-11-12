package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.api.RbacRules;
import com.labos.kernel.engine.MaterialOutcome;
import com.labos.kernel.material.MaterialEffectService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.MaterialMapper;
import com.labos.kernel.persistence.RunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonLong;
import static com.labos.kernel.engine.MaterialOutcome.CONTINUE;
import static com.labos.kernel.engine.MaterialOutcome.HOLD;

/**
 * 物料效果对账/重试/rebase/取消 端点（§6）。
 *
 * <p>写操作由 {@link ApiAuthInterceptor} 统一要求 canWrite（admin/operator）；
 * 具体 material:* 权限码细化归 §8.2 第 17 项。人工操作要求 operator_user_id + reason，
 * 由 DDL {@code ck_action_manual_identity} 强制。
 */
@RestController
@RequestMapping("/api")
public class MaterialEffectController {

    private static final long TENANT = 1L;

    private final DbConfig db;

    public MaterialEffectController(DbConfig db) {
        this.db = db;
    }

    @PostMapping(value = "/material-effects/{id:\\d+}/retry",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> retry(@PathVariable long id,
                                        @RequestBody(required = false) String body,
                                        HttpServletRequest req) {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_RETRY);
        if (denied != null) {
            return denied;
        }
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank()) {
            return bad("必填 reason");
        }
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        try {
            MaterialOutcome o = svc.retry(id, operatorId(req), reason);
            return okOrHold(id, o);
        } catch (SQLException e) {
            return error(e);
        }
    }

    @PostMapping(value = "/material-effects/{id:\\d+}/rebase",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> rebase(@PathVariable long id,
                                         @RequestBody(required = false) String body,
                                         HttpServletRequest req) {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_RECONCILE);
        if (denied != null) {
            return denied;
        }
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank()) {
            return bad("必填 reason");
        }
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        try {
            MaterialOutcome o = svc.rebase(id, operatorId(req), reason);
            return okOrHold(id, o);
        } catch (SQLException e) {
            return error(e);
        }
    }

    @PostMapping(value = "/material-effects/{id:\\d+}/reconcile",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> reconcile(@PathVariable long id,
                                            @RequestBody(required = false) String body,
                                            HttpServletRequest req) {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_RECONCILE);
        if (denied != null) {
            return denied;
        }
        String decision = jsonField(body, "decision");
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank() || decision == null || decision.isBlank()) {
            return bad("必填 decision 与 reason");
        }
        boolean completed = "completed".equals(decision);
        Long confirmedPlateId = completed ? jsonLong(body, "confirmedPlateId") : null;
        if (completed && confirmedPlateId == null) {
            return bad("decision=completed 必填 confirmedPlateId");
        }
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        try {
            MaterialOutcome o = svc.reconcile(id, completed, confirmedPlateId,
                    operatorId(req), reason);
            return okOrHold(id, o);
        } catch (SQLException e) {
            return error(e);
        }
    }

    @PostMapping(value = "/material-effects/{id:\\d+}/cancel",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> cancel(@PathVariable long id,
                                         @RequestBody(required = false) String body,
                                         HttpServletRequest req) {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_CANCEL);
        if (denied != null) {
            return denied;
        }
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank()) {
            return bad("必填 reason");
        }
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        try {
            MaterialOutcome o = svc.cancel(id, operatorId(req), reason);
            return okOrHold(id, o);
        } catch (SQLException e) {
            return error(e);
        }
    }

    /** §6 读：效果列表（对账台用）。 */
    @GetMapping(value = "/material-effects", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> listEffects(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Long plateId) {
        var list = new MaterialEffectService(TENANT, db).listEffects(status, runId, plateId);
        return ResponseEntity.ok(json(list));
    }

    /** §6 读：效果动作历史（append-only）。 */
    @GetMapping(value = "/material-effects/{id:\\d+}/actions",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> listActions(@PathVariable long id) {
        var list = new MaterialEffectService(TENANT, db).listActions(id);
        return ResponseEntity.ok(json(list));
    }

    /** 隔离确认（§3.6 / §8.2 第 14 项）：quarantined → paused(manual_window)，返回未闭环清单。 */
    @PostMapping(value = "/runs/{id:\\d+}/material-safety/confirm",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> materialSafetyConfirm(@PathVariable long id,
                                                        @RequestBody(required = false) String body,
                                                        HttpServletRequest req) {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_RECONCILE);
        if (denied != null) {
            return denied;
        }
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank()) {
            return bad("必填 reason");
        }
        int n = Mappers.get(RunMapper.class).materialSafetyConfirm(TENANT, id, reason);
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(err("run 不是 quarantined 状态，无需隔离确认"));
        }
        int unclosed = new MaterialEffectService(TENANT, db).unclosedCount(id);
        return ResponseEntity.ok(Jsons.obj("runId", id, "confirmed", true,
                "unclosedCount", unclosed));
    }

    /** §3.6a / §6：人工改状态（唯一写入路径，复用 T2 的 mapper）。 */
    @PutMapping(value = "/plates/{barcode}/state",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> manualState(@PathVariable String barcode,
                                              @RequestBody(required = false) String body,
                                              HttpServletRequest req) {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_WRITE);
        if (denied != null) {
            return denied;
        }
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank()) {
            return bad("必填 reason");
        }
        Long expectedVersion = jsonLong(body, "expectedVersion");
        if (expectedVersion == null) {
            return bad("必填 expectedVersion（乐观锁）");
        }
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        Long plateId = svc.plateIdByBarcode(barcode);
        if (plateId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("板不存在: " + barcode));
        }
        try {
            svc.manualStateChange(plateId, jsonField(body, "processState"),
                    jsonField(body, "sealState"), expectedVersion,
                    operatorId(req), reason);
            return ResponseEntity.ok(Jsons.obj("barcode", barcode,
                    "plateId", plateId, "changed", true));
        } catch (IllegalStateException e) {
            // §3.6a 步骤 2：expectedVersion 不符 ⇒ 409 且不写任何行
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err(e.getMessage()));
        } catch (SQLException e) {
            return error(e);
        }
    }

    /** §6：本 run 的物料效果（按 source_run_id，不按当前归属）。 */
    @GetMapping(value = "/runs/{id:\\d+}/material-effects",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> runMaterialEffects(@PathVariable long id) {
        return ResponseEntity.ok(json(
                new MaterialEffectService(TENANT, db).listEffects(null, id, null)));
    }

    /** §6：闸门状态 —— 被阻断的节点、未闭环效果数；恢复入口前置条件。 */
    @GetMapping(value = "/runs/{id:\\d+}/material-gate",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> materialGate(@PathVariable long id) {
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        int blockers = svc.unclosedCount(id);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("runId", id);
        out.put("blocked", blockers > 0);
        out.put("blockerCount", blockers);
        out.put("effects", svc.listEffects(null, id, null));
        return ResponseEntity.ok(json(out));
    }

    // ---------------------------------------------------------------- §6 plates / 字典

    /** §6：按工艺阶段 / 板型 / 位置可信度查板（三个条件都可空）。 */
    @GetMapping(value = "/plates", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> listPlates(
            @RequestParam(required = false) String processState,
            @RequestParam(required = false) String plateType,
            @RequestParam(required = false) String positionStatus,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(json(Mappers.get(MaterialMapper.class)
                .selectPlates(TENANT, processState, plateType, positionStatus, n)));
    }

    /** §6：单板详情（阶段 / 膜 / 位置 / 最近加工）。 */
    @GetMapping(value = "/plates/{barcode}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> plateDetail(@PathVariable String barcode) {
        java.util.Map<String, Object> p = Mappers.get(MaterialMapper.class)
                .selectPlateDetail(TENANT, barcode);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("板不存在: " + barcode));
        }
        return ResponseEntity.ok(json(p));
    }

    /** §6：单板事件历史（分页，按 id 倒序）。 */
    @GetMapping(value = "/plates/{barcode}/state-history",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> plateStateHistory(
            @PathVariable String barcode,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        Long plateId = new MaterialEffectService(TENANT, db).plateIdByBarcode(barcode);
        if (plateId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("板不存在: " + barcode));
        }
        int n = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(json(Mappers.get(MaterialMapper.class)
                .selectPlateEvents(TENANT, plateId, n, Math.max(offset, 0))));
    }

    /** §6 字典：新增（code 不可改；重复 code ⇒ 409）。 */
    @PostMapping(value = "/plate-process-states",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createProcessState(
            @RequestBody(required = false) String body, HttpServletRequest req)
            throws SQLException {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_ADMIN);
        if (denied != null) {
            return denied;
        }
        String code = jsonField(body, "code");
        if (code == null || code.isBlank()) {
            return bad("必填 code");
        }
        String displayName = jsonField(body, "displayName");
        if (displayName == null || displayName.isBlank()) {
            return bad("必填 displayName");
        }
        MaterialMapper m = Mappers.get(MaterialMapper.class);
        if (m.selectProcessState(TENANT, code) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(err("字典已存在: " + code + "（code 不可改）"));
        }
        Long sort = jsonLong(body, "sortOrder");
        m.insertProcessState(TENANT, code, displayName, sort == null ? 0 : sort.intValue());
        return ResponseEntity.ok(Jsons.obj("code", code, "created", true));
    }

    /** §6 字典：改名 / 排序 / 停用（只改传了的字段）。 */
    @PatchMapping(value = "/plate-process-states/{code}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> patchProcessState(
            @PathVariable String code,
            @RequestBody(required = false) String body, HttpServletRequest req)
            throws SQLException {
        ResponseEntity<String> denied = requireMaterial(req, RbacRules.MATERIAL_ADMIN);
        if (denied != null) {
            return denied;
        }
        MaterialMapper m = Mappers.get(MaterialMapper.class);
        if (m.selectProcessState(TENANT, code) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("字典不存在: " + code));
        }
        String displayName = jsonField(body, "displayName");
        Long sort = jsonLong(body, "sortOrder");
        Boolean enabled = jsonBool(body, "enabled");
        if (displayName == null && sort == null && enabled == null) {
            return bad("至少传一个字段：displayName / sortOrder / enabled");
        }
        m.updateProcessState(TENANT, code, displayName,
                sort == null ? null : sort.intValue(), enabled);
        return ResponseEntity.ok(Jsons.obj("code", code, "updated", true));
    }

    // ---------------------------------------------------------------- helpers

    /** 解析 JSON 布尔字段（ApiServer 没有现成 helper）。 */
    private static Boolean jsonBool(String body, String key) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    /**
     * §8.2 第 17 项（评审 R13-08）：写端点按 material:* 细分权限校验，
     * 不能只靠 ApiAuthInterceptor 的通用 canWrite。
     */
    private static ResponseEntity<String> requireMaterial(HttpServletRequest req,
                                                          String permission) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        if (!(v instanceof AuthService.Session s)
                || !RbacRules.canMaterial(s.role(), permission)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(err("当前账号无 " + permission + " 权限"));
        }
        return null;
    }

    private static ResponseEntity<String> okOrHold(long id, MaterialOutcome o) {
        if (o == CONTINUE) {
            return ResponseEntity.ok(Jsons.obj("effectId", id, "outcome", o.name()));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(err("操作未应用（HOLD），请核对效果状态"));
    }

    private static ResponseEntity<String> bad(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(msg));
    }

    private static ResponseEntity<String> error(SQLException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(err("物料操作失败：" + e.getMessage()));
    }

    private static Long operatorId(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s ? s.userId() : null;
    }

    /** 时间列统一输出 ISO-8601 字符串（与其它 API 的 string|null 约定一致）；
        默认会把 java.sql.Timestamp 写成 epoch 毫秒。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
