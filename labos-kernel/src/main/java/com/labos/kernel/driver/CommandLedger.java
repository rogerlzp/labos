package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 设备命令账本（P-07 第一切片）。
 *
 * <p><b>解决什么</b>：设备指令是「有物理副作用」的调用。进程在
 * 「已下发、还没拿到回包」的窗口崩溃时，重启后无法知道机械臂到底动没动；
 * 如果直接重发，可能重复搬同一块板。
 *
 * <p>本账本把每次下发拆成两个可查事实：
 * <ol>
 *   <li>派发前 {@code INSERT ... DISPATCH_STARTED}（同一 idempotency_key 唯一）；</li>
 *   <li>回包后 {@code ACKED}/{@code FAILED}；启动时把陈旧的
 *       {@code DISPATCH_STARTED} 标成 {@code UNKNOWN}（物理结果未知）。</li>
 * </ol>
 *
 * <p><b>边界</b>：本切片只保证「单条指令账本 + 重启对账 + 同 key 禁止重复下发」；
 * 跨指令尝试/Outbox 传输层、UNKNOWN 的人工核对处置属 P-07.2。
 */
public final class CommandLedger {

    /** 启动对账：超过该分钟数仍是 DISPATCH_STARTED 视为「派发中断」。 */
    public static final int STALE_MINUTES = 10;

    private CommandLedger() {
    }

    /** 账本一行。{@code created=false} 表示同 key 已存在（应阻断重复下发）。 */
    public record Entry(long id, String state, boolean created) {
    }

    /**
     * 派发前登记。同一 {@code idempotencyKey} 已存在时不覆盖，返回现有行供调用方
     * fail-closed 处理（{@link #created()} == false）。
     */
    public static Entry begin(DbConfig db, long tenantId, String idempotencyKey,
                              long runId, long nodeId, int attempt, int stepSeq,
                              long deviceId, String method, String commandId,
                              String traceId) throws SQLException {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                long ledgerId = 0L;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO command_ledger
                          (tenant_id, idempotency_key, run_id, node_id, attempt, step_seq,
                           device_id, method, command_id, trace_id, state)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DISPATCH_STARTED')
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        RETURNING id
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setString(2, idempotencyKey);
                    ps.setLong(3, runId);
                    ps.setLong(4, nodeId);
                    ps.setInt(5, attempt);
                    ps.setInt(6, stepSeq);
                    ps.setLong(7, deviceId);
                    ps.setString(8, method);
                    ps.setString(9, commandId);
                    ps.setString(10, traceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            ledgerId = rs.getLong(1);
                        }
                    }
                }
                if (ledgerId == 0L) {
                    // 已存在：读回状态，不写 Outbox（避免重复事件）
                    String state = "UNKNOWN";
                    try (PreparedStatement ps = c.prepareStatement("""
                            SELECT id, state FROM command_ledger
                             WHERE tenant_id = ? AND idempotency_key = ?
                            """)) {
                        ps.setLong(1, tenantId);
                        ps.setString(2, idempotencyKey);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                ledgerId = rs.getLong(1);
                                state = rs.getString(2);
                            }
                        }
                    }
                    c.rollback();
                    return new Entry(ledgerId, state, false);
                }
                // P-07.4：与账本同事务写 Outbox（账本在 ⇒ 事件在）
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO command_outbox
                          (tenant_id, ledger_id, run_id, command_id, device_id, method, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, ledgerId);
                    ps.setLong(3, runId);
                    ps.setString(4, commandId);
                    ps.setLong(5, deviceId);
                    ps.setString(6, method);
                    ps.setString(7, "{\"deviceId\":" + deviceId
                            + ",\"method\":\"" + (method == null ? "" : method)
                            + "\"}");
                    ps.executeUpdate();
                }
                c.commit();
                return new Entry(ledgerId, "DISPATCH_STARTED", true);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 回包后落终态。 */
    public static void finish(DbConfig db, long ledgerId, boolean ok,
                              String errorCode, String errorMessage,
                              String commandId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE command_ledger
                        SET state = ?, error_code = ?, error_message = ?,
                            command_id = COALESCE(?, command_id), updated_at = now()
                      WHERE id = ?
                     """)) {
            ps.setString(1, ok ? "ACKED" : "FAILED");
            ps.setString(2, errorCode);
            ps.setString(3, trunc(errorMessage, 250));
            ps.setString(4, commandId);
            ps.setLong(5, ledgerId);
            ps.executeUpdate();
        }
    }

    /**
     * 启动对账：把陈旧的 DISPATCH_STARTED 标 UNKNOWN。
     *
     * @return 标记条数
     */
    public static int reconcileOnStartup(DbConfig db, long tenantId, int staleMinutes)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE command_ledger
                        SET state = 'UNKNOWN',
                            error_code = COALESCE(error_code, 'EC-99-0006'),
                            error_message = COALESCE(error_message,
                                '进程重启时命令仍处于派发中，物理结果未知；需人工确认后处置'),
                            updated_at = now()
                      WHERE tenant_id = ? AND state = 'DISPATCH_STARTED'
                        AND created_at < now() - (? || ' minutes')::interval
                     """)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(0, staleMinutes));
            return ps.executeUpdate();
        }
    }

    /** UNKNOWN 账本行（人工核对处置入口用）。 */
    public record Unknown(long id, String key, long runId, Long nodeId, String method,
                          String commandId, String traceId, String errorCode,
                          String errorMessage, String createdAt) {
    }

    /** 列出当前 UNKNOWN（物理结果未知）的命令，最多 limit 条。 */
    public static java.util.List<Unknown> listUnknown(DbConfig db, long tenantId, int limit)
            throws SQLException {
        int cap = Math.max(1, Math.min(limit, 200));
        java.util.List<Unknown> rows = new java.util.ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, idempotency_key, run_id, node_id, method, command_id,
                            trace_id, error_code, error_message, created_at::text
                       FROM command_ledger
                      WHERE tenant_id = ? AND state = 'UNKNOWN'
                      ORDER BY id
                      LIMIT ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long node = rs.getLong("node_id");
                    rows.add(new Unknown(rs.getLong("id"), rs.getString("idempotency_key"),
                            rs.getLong("run_id"), rs.wasNull() ? null : node,
                            rs.getString("method"), rs.getString("command_id"),
                            rs.getString("trace_id"), rs.getString("error_code"),
                            rs.getString("error_message"), rs.getString("created_at")));
                }
            }
        }
        return rows;
    }

    /**
     * 人工核对处置：把 UNKNOWN 行落成 ACKED（confirm-done）或 FAILED（confirm-failed）。
     *
     * <p>只允许从 UNKNOWN 转移（并发二次处置返回 0）；带 resolved_at/resolved_by 留痕。
     *
     * @return 受影响行数（0 = 不存在或已不是 UNKNOWN）
     */
    public static int resolve(DbConfig db, long tenantId, long ledgerId, boolean done,
                              String note, Long by) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE command_ledger
                        SET state = ?, resolved_at = now(), resolved_by = ?,
                            error_code = COALESCE(error_code, 'EC-99-0006'),
                            error_message = ?, updated_at = now()
                      WHERE tenant_id = ? AND id = ? AND state = 'UNKNOWN'
                     """)) {
            ps.setString(1, done ? "ACKED" : "FAILED");
            if (by == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, by);
            }
            ps.setString(3, trunc(note == null ? "人工核对确认" : note, 250));
            ps.setLong(4, tenantId);
            ps.setLong(5, ledgerId);
            return ps.executeUpdate();
        }
    }

    /** 查询某条 run 的命令账本状态（自测/排障）。 */
    public static String stateOf(DbConfig db, long tenantId, String key) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT state FROM command_ledger "
                             + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
