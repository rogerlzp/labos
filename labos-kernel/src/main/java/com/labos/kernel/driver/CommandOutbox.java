package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** P-07.4 命令事件 Outbox 派发器（同 pause_event 的最小版本）。 */
public final class CommandOutbox {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CommandOutbox.class);

    public static final int MAX_PER_TICK = 20;

    private CommandOutbox() {
    }

    /** 派发 pending 事件（标记 DELIVERED）；返回本次派发条数。 */
    public static int dispatchPending(DbConfig db, long tenantId, int limit)
            throws SQLException {
        int cap = Math.max(1, Math.min(limit, MAX_PER_TICK));
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id FROM command_outbox
                     WHERE tenant_id = ? AND state = 'PENDING'
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
                        UPDATE command_outbox
                           SET state = 'DELIVERED', delivered_at = now(),
                               attempts = attempts + 1
                         WHERE tenant_id = ? AND id = ? AND state = 'PENDING'
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
                log.info("[命令 Outbox] 派发 " + ids.size() + " 条");
            }
            return ids.size();
        });
    }

    /** P-07：重放一条已派发事件（仅供外部投递重试，绝不重发物理命令）。 */
    public static boolean replay(DbConfig db, long tenantId, long outboxId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE command_outbox
                        SET state = 'PENDING', delivered_at = NULL
                      WHERE tenant_id = ? AND id = ? AND state = 'DELIVERED'
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, outboxId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 某条账本对应的未派发事件数（自测/排障）。 */
    public static long pendingForLedger(DbConfig db, long tenantId, long ledgerId)
            throws SQLException {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.CommandMapper.class)
                .countPendingEvents(tenantId, ledgerId);
    }
}
