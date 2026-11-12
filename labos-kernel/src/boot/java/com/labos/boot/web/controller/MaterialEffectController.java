package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.engine.MaterialOutcome;
import com.labos.kernel.material.MaterialEffectService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.RunMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank()) {
            return bad("必填 reason");
        }
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        try {
            MaterialOutcome o = svc.retry(id, operatorId(req), reason);
            return ResponseEntity.ok(Jsons.obj("effectId", id, "outcome", o.name()));
        } catch (SQLException e) {
            return error(e);
        }
    }

    @PostMapping(value = "/material-effects/{id:\\d+}/rebase",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> rebase(@PathVariable long id,
                                         @RequestBody(required = false) String body,
                                         HttpServletRequest req) {
        String reason = jsonField(body, "reason");
        if (reason == null || reason.isBlank()) {
            return bad("必填 reason");
        }
        MaterialEffectService svc = new MaterialEffectService(TENANT, db);
        try {
            MaterialOutcome o = svc.rebase(id, operatorId(req), reason);
            return ResponseEntity.ok(Jsons.obj("effectId", id, "outcome", o.name()));
        } catch (SQLException e) {
            return error(e);
        }
    }

    @PostMapping(value = "/material-effects/{id:\\d+}/reconcile",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> reconcile(@PathVariable long id,
                                            @RequestBody(required = false) String body,
                                            HttpServletRequest req) {
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

    /** 隔离确认（§3.6 / §8.2 第 14 项）：quarantined → paused(manual_window)，返回未闭环清单。 */
    @PostMapping(value = "/runs/{id:\\d+}/material-safety/confirm",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> materialSafetyConfirm(@PathVariable long id,
                                                        @RequestBody(required = false) String body,
                                                        HttpServletRequest req) {
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

    // ---------------------------------------------------------------- helpers

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
}
