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

    public static final int MAX_PER_TICK = 20;

    private CommandOutbox() {
    }

    /** 派发 pending 事件（标记 DELIVERED）；返回本次派发条数。 */
    public static int dispatchPending(DbConfig db, long tenantId, int limit)
            throws SQLException {
        int cap = Math.max(1, Math.min(limit, MAX_PER_TICK));
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
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
                c.commit();
                if (!ids.isEmpty()) {
                    System.out.println("[命令 Outbox] 派发 " + ids.size() + " 条");
                }
                return ids.size();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 某条账本对应的未派发事件数（自测/排障）。 */
    public static long pendingForLedger(DbConfig db, long tenantId, long ledgerId)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM command_outbox "
                             + "WHERE tenant_id = ? AND ledger_id = ? AND state = 'PENDING'")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}
