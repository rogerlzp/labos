package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 门/闸/盖管理。
 *
 * <p><b>为什么门是资源</b>：离心机门关着，机械臂伸进去会撞机。
 * 必须"先开门、确认开了、才能进去"。
 *
 * <p><b>状态怎么来：靠动作，不查询设备</b>（已与用户确认）：
 * <pre>
 *   默认 closed → OpenDoor 成功即 open → CloseDoor 成功即 closed
 *   动作失败或恢复后未确认 → unknown
 * </pre>
 * 设备不提供"查询门状态"的接口，也不需要。
 *
 * <p><b>成对开关是强制的</b>：有开门必须有闭门，所有路径（成功/失败/超时/暂停）
 * 都要走关门。关门失败要告警——门可能半开。
 *
 * <h3>为什么恢复时要"先关再开"</h3>
 * 重启后系统不知道门是开是关。假设门是开的直接伸进去，可能撞。
 * 所以第一次操作先 CloseDoor（不管当前状态），成功后再 OpenDoor——**幂等**。
 */
public final class DeviceAccessManager {

    private final DbConfig db;
    private final long tenantId;

    public DeviceAccessManager(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /** 门的配置。 */
    public record Access(long id, long resourceId, String code, String type,
                         String state, String openMethod, String closeMethod,
                         int openTimeoutMs) {

        /** 手动门：没有开合方法，必须人工打开后在中控确认。 */
        public boolean manual() {
            return openMethod == null || openMethod.isBlank();
        }
    }

    /** 查设备有没有需要操作的门；没有返回 null。 */
    public Access accessOf(long resourceId) {
        String sql = """
                SELECT id, resource_id, code, access_type, state,
                       open_method, close_method, open_timeout_ms
                FROM device_access
                WHERE tenant_id = ? AND resource_id = ?
                ORDER BY id LIMIT 1
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Access(
                        rs.getLong("id"),
                        rs.getLong("resource_id"),
                        rs.getString("code"),
                        rs.getString("access_type"),
                        rs.getString("state"),
                        rs.getString("open_method"),
                        rs.getString("close_method"),
                        rs.getInt("open_timeout_ms"));
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * 进门前的检查。
     *
     * @return {@code null} 表示可以进；否则返回拒绝原因（调用方应中止）
     */
    public String ensureOpen(Access a, java.util.function.Function<String, DriverResult> caller) {
        if (a == null) {
            return null;   // 没门，直接进
        }
        if ("open".equals(a.state())) {
            return null;   // 已经开着
        }
        if (a.manual()) {
            return "设备 " + a.code() + " 是手动门，需人工打开后在中控确认";
        }
        if (caller == null) {
            return "无法开门：没有可用的驱动调用入口";
        }
        DriverResult r = caller.apply(a.openMethod());
        if (r == null || !r.ok()) {
            String code = r == null ? "null" : r.errorCode();
            setState(a.id(), "unknown");
            return "开门动作失败：" + code + "（设备 " + a.code() + "）";
        }
        // **不查询，直接信动作结果**
        setState(a.id(), "open");
        return null;
    }

    /**
     * 关门。所有路径都要调（finally 里）。
     *
     * @return {@code null} 表示关成功；否则返回告警文本
     */
    public String close(Access a, java.util.function.Function<String, DriverResult> caller) {
        if (a == null || a.manual()) {
            return null;   // 没门或手动门，系统不管
        }
        if (caller == null) {
            return "关门失败：没有可用的驱动调用入口";
        }
        DriverResult r = caller.apply(a.closeMethod());
        if (r == null || !r.ok()) {
            String code = r == null ? "null" : r.errorCode();
            setState(a.id(), "unknown");
            return "关门动作失败：" + code + "（设备 " + a.code()
                    + "，门可能半开，需人工确认）";
        }
        setState(a.id(), "closed");
        return null;
    }

    /**
     * 恢复时的幂等重置：先关再开。
     *
     * <p>重启后门状态是 unknown，直接伸进去可能撞。
     * 先强制 CloseDoor（不管当前状态），再 OpenDoor。
     */
    public String resetBeforeUse(Access a,
                                 java.util.function.Function<String, DriverResult> caller) {
        if (a == null || a.manual()) {
            return null;
        }
        if (caller == null) {
            return null;
        }
        // 先关（不管当前状态），失败也不阻塞——可能本来就关着
        caller.apply(a.closeMethod());
        setState(a.id(), "closed");
        return ensureOpen(a, caller);
    }

    private void setState(long accessId, String state) {
        String sql = "UPDATE device_access SET state = ?, state_checked_at = now()"
                + " WHERE tenant_id = ? AND id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setLong(2, tenantId);
            ps.setLong(3, accessId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[门] 写状态失败 id=" + accessId + "：" + e.getMessage());
        }
    }
}
