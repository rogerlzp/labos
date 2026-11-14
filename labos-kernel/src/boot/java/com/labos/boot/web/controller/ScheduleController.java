package com.labos.boot.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.ScheduleMapper;
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

import java.util.List;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;

/**
 * P2-4 · 排程持久化：schedule_plan（排队优先级/迭代上限/错峰等参数）+ resource_booking（资源预约）。
 *
 * <p>只做数据与 API 层；调度器（ExperimentScheduler）消费这些参数是后续调度核心改造，
 * 本轮不碰。resource_booking 的自动写入（调度决策时）也留给调度核心改造。
 */
@RestController
@RequestMapping("/api")
public class ScheduleController {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final AuditRecorder audit;

    public ScheduleController(DbConfig db, AuditRecorder audit) {
        this.db = db;
        this.audit = audit;
    }

    private ScheduleMapper m() {
        return Mappers.get(ScheduleMapper.class);
    }

    // ---------------------------------------------------------------- schedule_plan

    @GetMapping(value = "/schedule-plans", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> plans() {
        List<Map<String, Object>> rows = m().selectSchedulePlans(TENANT);
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @PostMapping(value = "/schedule-plans", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createPlan(@RequestBody(required = false) String body,
                                             HttpServletRequest req) {
        JsonNode n = parse(body);
        String planNo = text(n, "planNo");
        String algorithm = text(n, "algorithm");
        if (blank(planNo)) planNo = "PLAN-" + System.currentTimeMillis() % 100000000L;
        if (blank(algorithm)) algorithm = "heuristic";
        String status = text(n, "status");
        if (blank(status)) status = "queued";
        try {
            m().insertSchedulePlan(TENANT, planNo.trim(), algorithm, status,
                    intOf(n, "optimizationFactor", 50), intOf(n, "maxIterationsRunning", 0),
                    intOf(n, "iterationDelaySec", 0), text(n, "externalId"),
                    text(n, "scheduledStart"));
            audit.record(session(req), "schedulePlan.create", "schedule_plan", 0L, null,
                    null, req.getRemoteAddr());
            return ok(Map.of("created", 1, "planNo", planNo.trim()));
        } catch (Exception e) {
            return writeError(e, "planNo 可能重复");
        }
    }

    @PutMapping(value = "/schedule-plans/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updatePlan(@PathVariable long id,
                                             @RequestBody(required = false) String body,
                                             HttpServletRequest req) {
        JsonNode n = parse(body);
        int r = m().updateSchedulePlan(id, text(n, "status"), intOrNull(n, "optimizationFactor"),
                intOrNull(n, "maxIterationsRunning"), intOrNull(n, "iterationDelaySec"),
                text(n, "externalId"));
        if (r == 0) return notFound("排程不存在: " + id);
        audit.record(session(req), "schedulePlan.update", "schedule_plan", id, null,
                null, req.getRemoteAddr());
        return ok(Map.of("updated", r, "id", id));
    }

    /**
     * 删一条排程计划。
     *
     * <p>为什么需要它：{@code uk_schedule_plan UNIQUE (tenant_id, plan_no)} 不带状态条件
     * ⇒ 归档（status=archived）<b>不释放 plan_no</b>，同一个号再也建不了。
     * 排程计划建多了要能删（生产需求，也是测试脚本能真正清干净的前提）。
     */
    @DeleteMapping(value = "/schedule-plans/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deletePlan(@PathVariable long id,
                                             HttpServletRequest req) {
        int r = m().deleteSchedulePlan(TENANT, id);
        if (r == 0) return notFound("排程不存在: " + id);
        audit.record(session(req), "schedulePlan.delete", "schedule_plan", id, null,
                null, req.getRemoteAddr());
        return ok(Map.of("deleted", r, "id", id));
    }

    // ---------------------------------------------------------------- resource_booking

    @GetMapping(value = "/resource-bookings", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> bookings(@RequestParam(required = false) String runId,
                                           @RequestParam(defaultValue = "200") int limit) {
        List<Map<String, Object>> rows = m().selectResourceBookings(TENANT,
                parseLongOrNull(runId), Math.min(Math.max(limit, 1), 500));
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @PostMapping(value = "/resource-bookings", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createBooking(@RequestBody(required = false) String body,
                                                HttpServletRequest req) {
        JsonNode n = parse(body);
        long resourceId = longOf(n, "resourceId");
        String from = text(n, "periodFrom");
        String to = text(n, "periodTo");
        if (resourceId <= 0 || blank(from)) return bad("必填 resourceId / periodFrom");
        if (blank(to)) to = from;  // 默认到同一时刻（瞬时预约）
        String status = text(n, "status");
        if (blank(status)) status = "planned";
        String sourceType = text(n, "sourceType");
        if (blank(sourceType)) sourceType = "manual";
        try {
            m().insertResourceBooking(TENANT, resourceId, from, to, status, sourceType,
                    longOrNull(n, "sourceId"), longOrNull(n, "runNodeId"), text(n, "note"));
            audit.record(session(req), "resourceBooking.create", "resource_booking", 0L,
                    null, null, req.getRemoteAddr());
            return ok(Map.of("created", 1, "resourceId", resourceId));
        } catch (Exception e) {
            return writeError(e, "预约时间区间可能与已有预约重叠");
        }
    }

    // ---------------------------------------------------------------- helpers

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

    private static int intOf(JsonNode n, String key, int dft) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? dft : v.asInt();
    }

    private static Integer intOrNull(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static long longOf(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? 0L : v.asLong();
    }

    private static Long longOrNull(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
        if (msg.contains("exclude") || msg.contains("overlap") || msg.contains("ex_booking")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err(hint != null ? hint : "时间区间重叠"));
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
