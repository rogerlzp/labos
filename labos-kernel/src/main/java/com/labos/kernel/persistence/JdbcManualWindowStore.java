package com.labos.kernel.persistence;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * SQL 全部在 {@code mapper/ManualWindowMapper.xml}。
 */
public final class JdbcManualWindowStore {

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

    private static ManualWindowMapper mapper() {
        return Mappers.get(ManualWindowMapper.class);
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
        try {
            return mapper().insertWindowIgnoreExisting(tenantId, runId, windowKey,
                    errorCode, nodeKey, strategy, defaultAction, actionsJson, reason,
                    timeoutMinutes) > 0;
        } catch (RuntimeException e) {
            // 23505 = 并发下另一条 tick 先开了同一扇窗，按「已有窗口」处理
            // （裸 JDBC 时捕获的是 SQLException；MyBatis 已包装成 RuntimeException，
            // 沿 cause 链找 SQLState）
            if (hasSqlState(e, "23505")) {
                return false;
            }
            throw e;
        }
    }

    /** 待决窗口（最新在前）。调用方通常先跑 {@link #expireOverdue()}。 */
    public List<Window> pending(int limit) throws SQLException {
        List<Window> out = new ArrayList<>();
        for (Map<String, Object> m
                : mapper().selectPendingWindows(tenantId, Math.max(1, limit))) {
            out.add(read(m));
        }
        return out;
    }

    public Window byId(long id) throws SQLException {
        return read(mapper().selectWindowById(tenantId, id));
    }

    /** 按幂等键取最近一条（含已处置/已超时），供回查与连库自测使用。 */
    public Window byWindowKey(String windowKey) throws SQLException {
        return read(mapper().selectWindowByKey(tenantId, windowKey));
    }

    /** 决策回写。false = 已被处置 / 已超时 / 不存在（幂等保护）。 */
    public boolean decide(long id, String action, String operator, String note)
            throws SQLException {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action 不能为空");
        }
        return mapper().updateWindowDecided(tenantId, id, operator, action, note) > 0;
    }

    /** 把超时窗口标记为 timed_out；不改变任何 run 的状态。 */
    public int expireOverdue() throws SQLException {
        return mapper().markOverdueTimedOut(tenantId);
    }

    /** 仅供连库自测清理自己造的合成窗口。 */
    public int deleteByKeyPrefix(String prefix) throws SQLException {
        return mapper().deleteWindowsByKeyPrefix(tenantId, prefix + "%");
    }

    private static Window read(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new Window(
                ((Number) m.get("id")).longValue(),
                ((Number) m.get("runId")).longValue(),
                (String) m.get("windowKey"), (String) m.get("errorCode"),
                (String) m.get("nodeKey"), (String) m.get("strategy"),
                (String) m.get("defaultAction"), (String) m.get("allowedActions"),
                (String) m.get("decisionReason"), (String) m.get("status"),
                (String) m.get("openedAt"), (String) m.get("timeoutAt"),
                (String) m.get("decidedAt"), (String) m.get("decidedBy"),
                (String) m.get("decidedAction"), (String) m.get("note"));
    }

    /** 沿异常 cause 链找 SQLState（MyBatis 把 SQLException 包装在深层）。 */
    private static boolean hasSqlState(Throwable t, String state) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
