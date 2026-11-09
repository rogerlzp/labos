package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 暂停控制事件 Outbox 的<b>幂等派发器</b>（P-06 V2 第一切片）。
 *
 * <p>写入侧：{@link PauseCoordinator} 在「Hold + control_state + pause_request +
 * pause_event」同一事务里落事件，保证「闸门在、事件丢」不会发生。
 *
 * <p>派发侧：调度器每 tick 调 {@link #dispatchPending}，用
 * {@code FOR UPDATE SKIP LOCKED} 抢未派发事件并标记 {@code dispatched_at}
 * （带 attempts）。V2 第一切片只保证「单事务 + 幂等派发」，对接真实事件总线/
 * 审计中心是 V2.1；重复调用不会重复派发已标记的行。
 */
public final class PauseEventOutbox {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PauseEventOutbox.class);

    /** 每 tick 最多派发条数（避免一次抢太多、拖慢调度）。 */
    public static final int MAX_PER_TICK = 20;

    private PauseEventOutbox() {
    }

    /**
     * 派发一批未处理事件。
     *
     * @return 本次标记为已派发的条数
     */
    public static int dispatchPending(DbConfig db, long tenantId, int limit) throws SQLException {
        int cap = Math.max(1, Math.min(limit, MAX_PER_TICK));
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id FROM pause_event
                     WHERE tenant_id = ? AND dispatched_at IS NULL
                     ORDER BY id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """)) {
                ps.setLong(1, tenantId);
                ps.setInt(2, cap);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            if (!ids.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE pause_event
                           SET dispatched_at = now(), attempts = attempts + 1
                         WHERE tenant_id = ? AND id = ? AND dispatched_at IS NULL
                        """)) {
                    for (Long id : ids) {
                        ps.setLong(1, tenantId);
                        ps.setLong(2, id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!ids.isEmpty()) {
                log.info("[暂停事件] Outbox 派发 " + ids.size() + " 条");
            }
            return ids.size();
        });
    }

    /** 未派发事件数（自测/运维查询用）。 */
    public static long pendingCount(DbConfig db, long tenantId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pause_event "
                             + "WHERE tenant_id = ? AND dispatched_at IS NULL")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}
