package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.driver.RuntimeSettings;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonBool;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonInt;
import static com.labos.kernel.api.ApiServer.jsonLong;

/**
 * 从 {@code OpsApi} 拆出的运维域 Controller（用户 / 审计 / 策略 / 告警 / 心跳）。
 *
 * <p>行为与原实现逐条对齐：
 * <ul>
 *   <li>请求体仍用 {@code jsonField/jsonInt/jsonBool} 解析 —— 与原来完全同一套
 *       正则（含「数字带不带引号都认」这个已修过的坑），不换成 Jackson Map，
 *       避免前端发 {@code "maxAttempts":"2"} 时值被静默丢掉。</li>
 *   <li>审计走 {@link AuditRecorder}，规则与 {@code ApiServer.audited} 同源。</li>
 *   <li>鉴权（登录 / 写权限 / {@code /api/users} 仅管理员）由
 *       {@link ApiAuthInterceptor} 统一处理，这里不再重复判断。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class OpsUserController {

    private static final long TENANT = 1L;
    /** 与原 {@code ALERT_ACT} 正则的第二个分组一致。 */
    private static final java.util.Set<String> ALERT_ACTIONS =
            java.util.Set.of("ack", "close", "silence", "reopen");

    private final com.labos.boot.web.service.OpsUserService opsUserService;
    private final DbConfig db;
    private final AuditRecorder audit;

    public OpsUserController(com.labos.boot.web.service.OpsUserService opsUserService,
                            DbConfig db, AuditRecorder audit) {
        this.opsUserService = opsUserService;
        this.db = db;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 用户

    @GetMapping(value = "/users", produces = "application/json; charset=utf-8")
    public String listUsers() throws SQLException {
        return opsUserService.usersJson();
    }

    @PostMapping(value = "/users", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createUser(@RequestBody(required = false) String body,
                             @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                     required = false) AuthService.Session session,
                             HttpServletRequest req) throws SQLException {
        int n;
        try {
            n = opsUserService.createUser(
                    jsonField(body, "username"),
                    jsonField(body, "displayName"),
                    jsonField(body, "role"),
                    jsonField(body, "password"));
        } catch (IllegalArgumentException e) {
            // 评审 F2：重复用户名/校验不通过别再冒成 Spring 默认 500；
            // 重复 → 409，其它校验 → 400。
            boolean dup = e.getMessage() != null && e.getMessage().contains("已存在");
            return ResponseEntity.status(dup ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST)
                    .body(err(e.getMessage()));
        }
        // 绝不能把 password 写进审计——那是明文凭证
        audit.record(session, "user.create", "user", null,
                jsonField(body, "username"),
                Jsons.obj("role", jsonField(body, "role"),
                        "displayName", jsonField(body, "displayName")),
                req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("created", n));
    }

    @PutMapping(value = "/users/{id}", produces = "application/json; charset=utf-8")
    public String updateUser(@PathVariable long id,
                             @RequestBody(required = false) String body,
                             @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                     required = false) AuthService.Session session,
                             HttpServletRequest req) throws SQLException {
        int n = opsUserService.updateUser(id,
                jsonField(body, "role"),
                jsonBool(body, "enabled"),
                jsonField(body, "password"));
        audit.record(session, "user.update", "user", id, null,
                Jsons.obj("role", jsonField(body, "role"),
                        "enabled", jsonBool(body, "enabled"),
                        "resetPassword", jsonField(body, "password") != null),
                req.getRemoteAddr());
        return Jsons.obj("updated", n, "id", id);
    }

    @PatchMapping(value = "/users/{id}", produces = "application/json; charset=utf-8")
    public String patchUser(@PathVariable long id,
                            @RequestBody(required = false) String body,
                            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                    required = false) AuthService.Session session,
                            HttpServletRequest req) throws SQLException {
        return updateUser(id, body, session, req);
    }

    // ---------------------------------------------------------------- 审计

    @GetMapping(value = "/audit", produces = "application/json; charset=utf-8")
    public String auditLog(@RequestParam(required = false) String action) throws SQLException {
        return opsUserService.auditJson(200, action);
    }

    // ---------------------------------------------------------------- 策略

    @GetMapping(value = "/policies", produces = "application/json; charset=utf-8")
    public String policies() throws SQLException {
        return opsUserService.policies();
    }

    @PutMapping(value = "/policies/{id}", produces = "application/json; charset=utf-8")
    public String updatePolicy(@PathVariable long id,
                               @RequestBody(required = false) String body,
                               @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                       required = false) AuthService.Session session,
                               HttpServletRequest req) throws SQLException {
        int n = opsUserService.updatePolicy(id,
                jsonField(body, "strategy"),
                jsonInt(body, "maxAttempts"),
                jsonInt(body, "priority"),
                jsonBool(body, "enabled"));
        audit.record(session, "policy.update", "policy", id, null,
                Jsons.obj("strategy", jsonField(body, "strategy"),
                        "enabled", jsonBool(body, "enabled"),
                        "maxAttempts", jsonInt(body, "maxAttempts")),
                req.getRemoteAddr());
        return Jsons.obj("updated", n, "id", id);
    }

    @PatchMapping(value = "/policies/{id}", produces = "application/json; charset=utf-8")
    public String patchPolicy(@PathVariable long id,
                              @RequestBody(required = false) String body,
                              @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                      required = false) AuthService.Session session,
                              HttpServletRequest req) throws SQLException {
        return updatePolicy(id, body, session, req);
    }

    @PostMapping(value = "/policies/validate", produces = "application/json; charset=utf-8")
    public String validatePolicy(@RequestBody(required = false) String body) throws SQLException {
        String code = jsonField(body, "errorCode");
        Integer prio = jsonInt(body, "priority");
        return opsUserService.validatePolicy(code, jsonField(body, "scope"),
                prio == null ? 50 : prio);
    }

    // ---------------------------------------------------------------- 告警

    @GetMapping(value = "/alerts", produces = "application/json; charset=utf-8")
    public String alerts(@RequestParam(required = false) String limit) throws SQLException {
        return opsUserService.alerts(intOr(limit, 20));
    }

    @GetMapping(value = "/device-alerts", produces = "application/json; charset=utf-8")
    public String deviceAlerts(@RequestParam(required = false) String limit) throws SQLException {
        return opsUserService.deviceAlerts(intOr(limit, 60));
    }

    @PostMapping(value = "/alerts/{fingerprint}/{action}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> alertAction(@PathVariable String fingerprint,
                                              @PathVariable String action,
                                              @RequestBody(required = false) String body,
                                              @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                                      required = false) AuthService.Session session,
                                              HttpServletRequest req) throws SQLException {
        // 原路由正则只放行这四个动作；放开成 @PathVariable 后必须自己校验，
        // 否则非法动作会进入 updateAlertState 抛 SQL 异常（500）。
        if (!ALERT_ACTIONS.contains(action)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("no route: /api/alerts/" + fingerprint + "/" + action));
        }
        // 原实现 fp 是 URL 编码过的（可能含 / 或空格），Matcher 抓到后再 decode
        String fp = URLDecoder.decode(fingerprint, StandardCharsets.UTF_8);
        String assignee = jsonField(body, "assignee");
        String note = jsonField(body, "note");
        int n = opsUserService.updateAlertState(fp, action, assignee, note);
        audit.record(session, "alert." + action, "alert", null, fp,
                Jsons.obj("assignee", assignee, "note", note), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj(
                "fingerprint", fp,
                "action", action,
                "updated", n));
    }

    @PatchMapping(value = "/alerts/{fingerprint}/{action}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> patchAlertAction(@PathVariable String fingerprint,
                                                   @PathVariable String action,
                                                   @RequestBody(required = false) String body,
                                                   @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                                           required = false) AuthService.Session session,
                                                   HttpServletRequest req) throws SQLException {
        return alertAction(fingerprint, action, body, session, req);
    }

    // ---------------------------------------------------------------- 心跳设置

    @GetMapping(value = "/settings/heartbeat", produces = "application/json; charset=utf-8")
    public String heartbeat() {
        return Jsons.obj(
                "intervalMs", RuntimeSettings.heartbeatIntervalMs(),
                "offlineMs", RuntimeSettings.offlineAlertMs());
    }

    @PutMapping(value = "/settings/heartbeat", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateHeartbeat(@RequestBody(required = false) String body,
                                                  @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                                          required = false) AuthService.Session session,
                                                  HttpServletRequest req) throws SQLException {
        // 必须用 jsonLong：jsonField 只认带引号的字符串，前端发裸数字时会漏掉。
        Long iv = jsonLong(body, "intervalMs");
        Long off = jsonLong(body, "offlineMs");
        if (iv == null && off == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("至少给一个参数：intervalMs / offlineMs"));
        }
        long curIv = RuntimeSettings.heartbeatIntervalMs();
        long curOff = RuntimeSettings.offlineAlertMs();
        long[] eff = RuntimeSettings.updateHeartbeat(
                iv == null ? curIv : iv, off == null ? curOff : off);
        try {
            RuntimeSettings.saveHeartbeat(db, TENANT, eff[0], eff[1]);
        } catch (SQLException e) {
            // 存不进去也要让内存生效 —— 下次重启回退，不影响当前使用
            org.slf4j.LoggerFactory.getLogger(OpsUserController.class)
                    .error("心跳参数落库失败（内存已生效）：{}", e.getMessage());
        }
        audit.record(session, "settings.heartbeat", "sys_setting", null,
                "interval=" + eff[0] + ",offline=" + eff[1], null,
                req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("intervalMs", eff[0], "offlineMs", eff[1]));
    }

    @PostMapping(value = "/settings/heartbeat", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> postHeartbeat(@RequestBody(required = false) String body,
                                                @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR,
                                                        required = false) AuthService.Session session,
                                                HttpServletRequest req) throws SQLException {
        return updateHeartbeat(body, session, req);
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
