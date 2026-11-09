package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.api.QueryService;
import com.labos.kernel.persistence.QueryMapper;
import com.labos.kernel.persistence.Jsons;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonBool;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonInt;

/**
 * 从 {@code OpsApi} 拆出的错误码 / 命令处置 Controller（8 个端点）。
 *
 * <p>行为对齐点：
 * <ul>
 *   <li>错误码更新用「只写传了的字段」语义（字段循环 + 空 map 拒绝），
 *       与原实现一致，不会把没传的列覆盖成 NULL。</li>
 *   <li>策略 PATCH 不带 scope 时按 id 反查（{@code QueryMapper.selectPolicyScope}），
 *       查不到返回 404 —— 改作用域等于换一条策略，前端走新增。</li>
 *   <li>UNKNOWN 命令的人工核对：{@code action} 只认 confirm-done / confirm-failed，
 *       非法值 400；更新 0 行说明不是 UNKNOWN 状态 → 409。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class OpsErrorController {

    private static final long TENANT = 1L;
    private static final String[] ERROR_CODE_FIELDS = {
            "scope", "severity", "title", "detail", "strategy", "maxAttempts", "params", "enabled"
    };

    private final QueryService query;
    private final QueryMapper queryMapper;
    private final AuditRecorder audit;

    public OpsErrorController(QueryService query, QueryMapper queryMapper, AuditRecorder audit) {
        this.query = query;
        this.queryMapper = queryMapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 错误码字典

    @GetMapping(value = "/error-codes", produces = "application/json; charset=utf-8")
    public String errorCodes(@RequestParam(required = false) String severity,
                             @RequestParam(required = false) String scope,
                             @RequestParam(required = false) String q) throws SQLException {
        return query.errorCodesJson(severity, scope, q);
    }

    @PostMapping(value = "/error-codes", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createErrorCode(
            @RequestBody(required = false) String body,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        try {
            String created = query.createErrorCode(
                    jsonField(body, "code"), jsonField(body, "scope"),
                    jsonField(body, "severity"), jsonField(body, "title"),
                    jsonField(body, "detail"), jsonField(body, "source"));
            audit.record(session, "error_code.create", "error_code", null,
                    jsonField(body, "code"), null, req.getRemoteAddr());
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 错误码主策略

    @PostMapping(value = "/error-codes/{id}/policies",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveErrorCodePolicy(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        String scope = jsonField(body, "scope");
        if (scope == null || scope.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("策略必须指定 scope（command/node/run/device）"));
        }
        long pid = query.upsertPolicy(id, scope,
                jsonField(body, "strategy"),
                jsonInt(body, "maxAttempts"),
                jsonField(body, "params"),
                jsonInt(body, "priority"),
                jsonBool(body, "enabled"));
        audit.record(session, "error_code.policy_save", "error_code", id,
                "scope=" + scope, null, req.getRemoteAddr());
        boolean covered = query.scopeCovered(id);
        return ResponseEntity.ok(Jsons.obj("errorCodeId", id, "policyId", pid,
                "scope", scope, "scopeCovered", covered,
                "warning", covered ? null
                        : "该码自己的作用域上仍没有启用中的策略，真出错时会落到通配策略"));
    }

    @DeleteMapping(value = "/error-codes/{id}/policies/{pid}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteErrorCodePolicy(
            @PathVariable long id,
            @PathVariable long pid,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        boolean gone = query.deletePolicy(id, pid);
        audit.record(session, "error_code.policy_delete", "error_code", id,
                "policy=" + pid, null, req.getRemoteAddr());
        return ResponseEntity.status(gone ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                .body(Jsons.obj("deleted", gone, "policyId", pid));
    }

    @PatchMapping(value = "/error-codes/{id}/policies/{pid}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateErrorCodePolicy(
            @PathVariable long id,
            @PathVariable long pid,
            @RequestBody(required = false) String body,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        // 按 id 改：作用域默认不动（改作用域等于换一条策略，前端走新增）
        String scope = jsonField(body, "scope");
        if (scope == null || scope.isBlank()) {
            scope = queryMapper.selectPolicyScope(TENANT, pid);
        }
        if (scope == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("策略不存在: " + pid));
        }
        query.upsertPolicy(id, scope,
                jsonField(body, "strategy"),
                jsonInt(body, "maxAttempts"),
                jsonField(body, "params"),
                jsonInt(body, "priority"),
                jsonBool(body, "enabled"));
        audit.record(session, "error_code.policy_update", "error_code", id,
                "policy=" + pid, null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("policyId", pid, "updated", true));
    }

    @PostMapping(value = "/error-codes/{id}/policies/{pid}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> postErrorCodePolicy(
            @PathVariable long id,
            @PathVariable long pid,
            @RequestBody(required = false) String body,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        return updateErrorCodePolicy(id, pid, body, session, req);
    }

    // ---------------------------------------------------------------- 错误码本体

    @PatchMapping(value = "/error-codes/{id}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateErrorCode(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String k : ERROR_CODE_FIELDS) {
            String v = jsonField(body, k);
            if (v != null) {
                fields.put(k, v);
            }
        }
        if (fields.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("没有可更新的字段"));
        }
        query.updateErrorCode(id, fields);
        audit.record(session, "error_code.update", "error_code", id,
                String.join(",", fields.keySet()), null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("id", id, "updated", fields.keySet()));
    }

    @PostMapping(value = "/error-codes/{id}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> postErrorCode(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        return updateErrorCode(id, body, session, req);
    }

    @DeleteMapping(value = "/error-codes/{id}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteErrorCode(
            @PathVariable long id,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        boolean gone = query.deleteErrorCode(id);
        if (gone) {
            audit.record(session, "error_code.delete", "error_code", id,
                    null, null, req.getRemoteAddr());
        }
        return ResponseEntity.status(gone ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                .body(Jsons.obj("id", id, "deleted", gone));
    }

    // ---------------------------------------------------------------- 错误发生记录 / 命令处置

    @GetMapping(value = "/errors", produces = "application/json; charset=utf-8")
    public String occurrences(@RequestParam(required = false) String limit) throws SQLException {
        return query.occurrences(intOr(limit, 20));
    }

    @GetMapping(value = "/commands/unknown", produces = "application/json; charset=utf-8")
    public String unknownCommands() throws SQLException {
        return query.commandsUnknownJson(100);
    }

    @GetMapping(value = "/commands/abort-unconfirmed", produces = "application/json; charset=utf-8")
    public String abortUnconfirmedCommands() throws SQLException {
        return query.commandsAbortUnconfirmedJson(100);
    }

    @PostMapping(value = "/commands/{id}/resolve", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> resolveCommand(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session session,
            HttpServletRequest req) throws SQLException {
        String action = jsonField(body, "action");
        boolean done = "confirm-done".equals(action);
        if (!done && !"confirm-failed".equals(action)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("action 必须是 confirm-done / confirm-failed"));
        }
        String note = jsonField(body, "note");
        int n = query.resolveCommandLedger(id, done, note, null);
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(err("不是 UNKNOWN 状态或不存在"));
        }
        audit.record(session, "command.ledger.resolve", "command_ledger", id, action,
                Jsons.obj("state", done ? "ACKED" : "FAILED", "note", note),
                req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("resolved", n, "id", id,
                "state", done ? "ACKED" : "FAILED"));
    }

    /** 与原 {@code intParam(ex, name, def)} 一致的宽松解析：非法值回退默认。 */
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
}
