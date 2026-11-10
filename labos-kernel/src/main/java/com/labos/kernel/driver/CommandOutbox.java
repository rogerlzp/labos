package com.labos.kernel.driver;

import com.labos.kernel.persistence.CommandMapper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Tx;

import java.sql.SQLException;
import java.util.List;

/** P-07.4 命令事件 Outbox 派发器（同 pause_event 的最小版本）。 */
public final class CommandOutbox {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CommandOutbox.class);

    public static final int MAX_PER_TICK = 20;

    private CommandOutbox() {
    }

    private static CommandMapper mapper() {
        return com.labos.kernel.persistence.Mappers.get(CommandMapper.class);
    }

    /**
     * 派发 pending 事件（标记 DELIVERED）；返回本次派发条数。
     *
     * <p>阶段3第29批收编：SELECT...FOR UPDATE SKIP LOCKED 与批量 UPDATE 的 SQL 进
     * {@code CommandMapper}。事务仍由 {@link Tx#inTransaction} 包住 —— SKIP LOCKED
     * 抢批与标记必须在同一事务里；lambda 的 {@code c} 形参因此保留但不再使用，
     * mapper 经 Mappers 复用事务绑定连接。
     */
    public static int dispatchPending(DbConfig db, long tenantId, int limit)
            throws SQLException {
        int cap = Math.max(1, Math.min(limit, MAX_PER_TICK));
        return Tx.inTransaction(db, c -> {
            List<Long> ids = mapper().selectPendingOutboxIds(tenantId, cap);
            if (!ids.isEmpty()) {
                mapper().markOutboxDelivered(tenantId, ids);
                log.info("[命令 Outbox] 派发 " + ids.size() + " 条");
            }
            return ids.size();
        });
    }

    /** P-07：重放一条已派发事件（仅供外部投递重试，绝不重发物理命令）。 */
    public static boolean replay(DbConfig db, long tenantId, long outboxId) throws SQLException {
        return mapper().replayOutbox(tenantId, outboxId) > 0;
    }

    /** 某条账本对应的未派发事件数（自测/排障）。 */
    public static long pendingForLedger(DbConfig db, long tenantId, long ledgerId)
            throws SQLException {
        return mapper().countPendingEvents(tenantId, ledgerId);
    }
}
