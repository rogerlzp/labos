package com.labos.boot.web.controller;

import com.labos.boot.web.service.TwinDiffService;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 孪生差分报告端点：仿真器推送 + 前端只读查询。
 *
 * <p>鉴权由全局 ApiAuthInterceptor 接管：GET 登录可读；POST 需 canWrite()
 * （admin/operator/designer）。没有 PUT/DELETE —— 报告是 append-only 证据。
 * 审计沿用仓库惯例：推送成功后写一条 audit_log。
 */
@RestController
@RequestMapping("/api")
public class TwinDiffController {

    private final TwinDiffService service;

    public TwinDiffController(TwinDiffService service) {
        this.service = service;
    }

    @GetMapping(value = "/twin/diffs", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> list(
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return ok(new Jsons.Raw(service.list(limit)).json());
    }

    @GetMapping(value = "/twin/diffs/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> detail(@PathVariable long id) {
        String body = service.detail(id);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Jsons.obj("error", "报告不存在: " + id));
        }
        return ok(new Jsons.Raw(body).json());
    }

    /**
     * 推送一份差分报告。body 是仿真器 --compare-twin 的完整报告 JSON。
     * 必填：run_id / status / job_status / source.kind（由 comparisons 推定）；
     * 缺失即 400，不猜默认值。
     */
    @PostMapping(value = "/twin/diffs", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> push(@RequestBody(required = false) String body,
                                       HttpServletRequest req) {
        if (body == null || body.isBlank()) {
            return bad("报告内容不能为空");
        }
        String reportUid = ApiServer.jsonField(body, "run_id");
        String status = ApiServer.jsonField(body, "status");
        String jobStatus = ApiServer.jsonField(body, "job_status");
        if (blank(reportUid) || blank(status) || blank(jobStatus)
                || body.indexOf("comparisons") < 0) {
            return bad("必填 run_id / status / job_status / comparisons");
        }
        String jobId = ApiServer.jsonField(body, "job_id");
        String deviceId = ApiServer.jsonField(body, "device_id");
        String sourceKind = sourceKind(body);
        String sourceDetail = sourceDetail(body);
        AuthService.Session who = session(req);
        String createdBy = who == null ? "sim" : who.username();
        try {
            String result = service.push(reportUid, jobId, deviceId,
                    sourceKind, sourceDetail, status, jobStatus, body, createdBy);
            return ok(new Jsons.Raw(result).json());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Jsons.obj("error", String.valueOf(e.getMessage())));
        }
    }

    /** 来源只认三种，取不到就记 unknown（不猜）。 */
    private static String sourceKind(String body) {
        String kind = ApiServer.jsonField(body, "kind");
        if ("ledger".equals(kind) || "db".equals(kind) || "file".equals(kind)) {
            return kind;
        }
        return "unknown";
    }

    private static String sourceDetail(String body) {
        String path = ApiServer.jsonField(body, "path");
        if (path != null && !path.isBlank()) {
            return path;
        }
        // SQL 里含大量引号，regex 取字段容易截断；有 sql 键就标个存在性，不存全文
        return body.contains("\"sql\"") ? "db query" : null;
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(com.labos.boot.web.ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<String> bad(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Jsons.obj("error", msg));
    }

    private static ResponseEntity<String> ok(String json) {
        return ResponseEntity.ok().header("Content-Type", "application/json; charset=utf-8")
                .body(json);
    }
}
