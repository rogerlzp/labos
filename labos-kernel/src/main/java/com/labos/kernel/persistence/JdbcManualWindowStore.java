package com.labos.kernel.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 有人值守窗口（台账 6.5）的落库层：开窗 / 列待决 / 决策回写 / 超时标记。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>幂等</b>：{@code (tenant_id, window_key)} 上 pending 只能有一条
 *       （部分唯一索引 {@code uk_manual_window_open}）；并发冲突按「已有窗口」处理。</li>
 *   <li><b>决策乐观条件</b>：{@code WHERE status='pending' AND decided_at IS NULL}，
 *       重复点确定、两人同时处置都只有第一次生效。</li>
 *   <li><b>超时不代决</b>：只把 status 改成 {@code timed_out}，run 仍为
 *       {@code paused/waiting_human}，等人到暂停队列页处置。</li>
 * </ul>
 *
 * <p>本类不碰执行线程、不碰岛锁、不写告警 —— 那些分别属于 WorkflowService 与告警层。
 */
public final class JdbcManualWindowStore {

    private static final String COLUMNS = """
            id, run_id, window_key, error_code, node_key, strategy,
            default_action, allowed_actions::text, decision_reason, status,
            opened_at::text, timeout_at::text, decided_at::text,
            decided_by, decided_action, note
            """;

    private static final String OPEN = """
            INSERT INTO manual_window
              (tenant_id, run_id, window_key, error_code, node_key, strategy,
               default_action, allowed_actions, decision_reason, status, timeout_at)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'pending',
                   now() + make_interval(mins => ?)
            WHERE NOT EXISTS (
                SELECT 1 FROM manual_window
                 WHERE tenant_id = ? AND window_key = ? AND status = 'pending'
            )
            """;

    private static final String DECIDE = """
            UPDATE manual_window
               SET status = 'decided', decided_at = now(),
                   decided_by = ?, decided_action = ?, note = ?
             WHERE tenant_id = ? AND id = ?
               AND status = 'pending' AND decided_at IS NULL
            """;

    private static final String EXPIRE = """
            UPDATE manual_window SET status = 'timed_out'
             WHERE tenant_id = ? AND status = 'pending'
               AND timeout_at IS NOT NULL AND timeout_at <= now()
            """;

    public record Window(
            long id,
            long runId,
            String windowKey,
            String errorCode,
            String nodeKey,
            String strategy,
            String defaultAction,
            String allowedActions,
            String decisionReason,
            String status,
            String openedAt,
            String timeoutAt,
            String decidedAt,
            String decidedBy,
            String decidedAction,
            String note) {
    }

    private final DbConfig db;
    private final long tenantId;

    public JdbcManualWindowStore(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /**
     * 开窗。返回 true = 本次真的开了一扇；false = 该 run 已有 pending 窗口。
     * {@code timeoutMinutes <= 0} 用于连库自测造「已超时」样本。
     */
    public boolean open(long runId, String windowKey, String errorCode, String nodeKey,
                        String strategy, String defaultAction, List<String> allowedActions,
                        String reason, int timeoutMinutes) throws SQLException {
        if (windowKey == null || windowKey.isBlank()) {
            throw new IllegalArgumentException("windowKey 不能为空");
        }
        if (defaultAction == null || defaultAction.isBlank()) {
            throw new IllegalArgumentException("defaultAction 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("decisionReason 不能为空");
        }
        String actionsJson = Jsons.arr(allowedActions.stream().map(Jsons::quote).toList());
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(OPEN)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            ps.setString(3, windowKey);
            setNullable(ps, 4, errorCode);
            setNullable(ps, 5, nodeKey);
            setNullable(ps, 6, strategy);
            ps.setString(7, defaultAction);
            ps.setString(8, actionsJson);
            ps.setString(9, reason);
            ps.setInt(10, timeoutMinutes);
            ps.setLong(11, tenantId);
            ps.setString(12, windowKey);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            // 23505 = 并发下另一条 tick 先开了同一扇窗，按「已有窗口」处理
            if ("23505".equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    /** 待决窗口（最新在前）。调用方通常先跑 {@link #expireOverdue()}。 */
    public List<Window> pending(int limit) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM manual_window "
                + "WHERE tenant_id = ? AND status = 'pending' "
                + "ORDER BY opened_at DESC, id DESC LIMIT ?";
        List<Window> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        }
        return out;
    }

    public Window byId(long id) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM manual_window WHERE tenant_id = ? AND id = ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** 按幂等键取最近一条（含已处置/已超时），供回查与连库自测使用。 */
    public Window byWindowKey(String windowKey) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM manual_window "
                + "WHERE tenant_id = ? AND window_key = ? ORDER BY id DESC LIMIT 1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, windowKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** 决策回写。false = 已被处置 / 已超时 / 不存在（幂等保护）。 */
    public boolean decide(long id, String action, String operator, String note)
            throws SQLException {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action 不能为空");
        }
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(DECIDE)) {
            setNullable(ps, 1, operator);
            ps.setString(2, action);
            setNullable(ps, 3, note);
            ps.setLong(4, tenantId);
            ps.setLong(5, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** 把超时窗口标记为 timed_out；不改变任何 run 的状态。 */
    public int expireOverdue() throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(EXPIRE)) {
            ps.setLong(1, tenantId);
            return ps.executeUpdate();
        }
    }

    /** 仅供连库自测清理自己造的合成窗口。 */
    public int deleteByKeyPrefix(String prefix) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM manual_window WHERE tenant_id = ? AND window_key LIKE ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, prefix + "%");
            return ps.executeUpdate();
        }
    }

    private static Window read(ResultSet rs) throws SQLException {
        return new Window(
                rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16));
    }

    private static void setNullable(PreparedStatement ps, int idx, String v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, v);
        }
    }
}
