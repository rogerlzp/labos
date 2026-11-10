package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
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
            com.labos.kernel.persistence.PauseMapper pm =
                    com.labos.kernel.persistence.Mappers.get(
                            com.labos.kernel.persistence.PauseMapper.class);
            List<Long> ids = pm.lockPendingPauseEvents(tenantId, cap);
            if (!ids.isEmpty()) {
                pm.markPauseEventsDispatched(tenantId, ids);
            }
            if (!ids.isEmpty()) {
                log.info("[暂停事件] Outbox 派发 " + ids.size() + " 条");
            }
            return ids.size();
        });
    }

    /** 未派发事件数（自测/运维查询用）。 */
    public static long pendingCount(DbConfig db, long tenantId) throws SQLException {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.PauseMapper.class)
                .countUndispatchedEvents(tenantId);
    }
}
