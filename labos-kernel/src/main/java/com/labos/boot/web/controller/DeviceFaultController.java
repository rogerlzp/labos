package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;

/**
 * 设备故障的<b>人工清除</b>（2026-11-19 补，现场最常踩的一个坑）。
 *
 * <p><b>为什么需要</b>
 * 设备命令失败后，内核把 {@code device_state.last_error} 写上、{@code status} 落
 * {@code 'error'}。而能力池（{@code GraphMapper.selectCapabilityPools}）会
 * <b>排除 status='error' 的设备</b> ⇒ 这台设备在岛内「不存在」⇒
 * 岛边界闸门 PF-13 直接拦下整条流程：
 * 「节点需要 TRANSFER 能力，但流程所属的岛 N 内没有具备该能力的设备」。
 * ⇒ <b>一次失败就再也跑不起来</b>：机械臂失败一次 → ARM1 进 error →
 * 之后所有搬运节点被 PF-13 拦死，而平台<b>没有任何</b>清故障的端点
 * ⇒ 现场只能直接改库（实测 run 1001817/1001818 连栽两次）。
 *
 * <p><b>本端点的语义</b>：操作员确认「设备已复位、可以用了」→
 * 清 last_error、status 回 idle；设备锁（current_run_id）<b>只在持有它的 run
 * 已到终态时才释放</b>（在跑的 run 不能被抢走设备）。
 *
 * <p>用法：{@code POST /api/devices/{deviceInstanceId}/clear-fault}
 *
 * <p>⚠ SQL 直接走 JDBC（不挂 MyBatis mapper）：mapper XML 目前不在版本库里
 * （资源目录正被另一条会话搬迁），端点必须<b>自包含</b>，
 * 否则「接口在、SQL 不在」⇒ 干净检出后端点 500。
 */
@RestController
@RequestMapping("/api")
public class DeviceFaultController {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final AuditRecorder audit;

    public DeviceFaultController(DbConfig db, AuditRecorder audit) {
        this.db = db;
        this.audit = audit;
    }

    @PostMapping(value = "/devices/{id:\\d+}/clear-fault",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> clearFault(@PathVariable long id,
                                             @RequestBody(required = false) String body,
                                             HttpServletRequest req) {
        Map<String, Object> before;
        try {
            before = faultState(id);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(err("读设备状态失败：" + e.getMessage()));
        }
        if (before == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("设备实例不存在：" + id));
        }
        int n;
        try {
            n = clear(id);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(err("清故障失败：" + e.getMessage()));
        }
        audit.record(session(req), "device.fault.clear", "device_instance", id,
                "lastError=" + before.get("lastError")
                        + ", status=" + before.get("status")
                        + ", runId=" + before.get("currentRunId"),
                body, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj(
                "ok", n > 0,
                "deviceInstanceId", id,
                "cleared", n,
                "previousStatus", before.get("status"),
                "previousLastError", before.get("lastError"),
                "previousRunId", before.get("currentRunId")));
    }

    /** 清故障前的状态快照；无行 = 设备实例不存在（或没有状态行）。 */
    private Map<String, Object> faultState(long instanceId) throws Exception {
        String sql = "SELECT ds.resource_id, ds.status, ds.last_error, "
                + "       ds.current_method, ds.current_run_id, ds.online "
                + "  FROM device_state ds "
                + " WHERE ds.tenant_id = ? "
                + "   AND ds.resource_id = (SELECT di.resource_id FROM device_instance di "
                + "                        WHERE di.tenant_id = ? AND di.id = ? "
                + "                          AND di.deleted_at IS NULL) "
                + " LIMIT 1";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, TENANT);
            ps.setLong(3, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("resourceId", rs.getLong("resource_id"));
                m.put("status", rs.getString("status"));
                m.put("lastError", rs.getString("last_error"));
                m.put("currentMethod", rs.getString("current_method"));
                long rid = rs.getLong("current_run_id");
                m.put("currentRunId", rs.wasNull() ? null : rid);
                m.put("online", rs.getBoolean("online"));
                return m;
            }
        }
    }

    /**
     * 清故障：last_error → NULL、status → 'idle'。
     *
     * <p>设备锁（current_run_id）<b>只在持有它的 run 已到终态时才释放</b> ——
     * 在跑的 run 不能被抢走设备（否则那条 run 的设备锁凭空消失）。
     */
    private int clear(long instanceId) throws Exception {
        String sql = "UPDATE device_state "
                + "   SET last_error = NULL, status = 'idle', current_method = NULL, "
                + "       current_run_id = CASE "
                + "           WHEN current_run_id IS NULL THEN NULL "
                + "           WHEN current_run_id IN (SELECT r.id FROM run r "
                + "                                    WHERE r.tenant_id = ? "
                + "                                      AND r.status IN ('completed','failed', "
                + "                                                    'aborted','cancelled','quarantined')) "
                + "               THEN NULL "
                + "           ELSE current_run_id END, "
                + "       updated_at = now() "
                + " WHERE tenant_id = ? "
                + "   AND resource_id = (SELECT di.resource_id FROM device_instance di "
                + "                        WHERE di.tenant_id = ? AND di.id = ? "
                + "                          AND di.deleted_at IS NULL)";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, TENANT);
            ps.setLong(3, TENANT);
            ps.setLong(4, instanceId);
            return ps.executeUpdate();
        }
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }
}
