package com.labos.kernel.persistence;

/**
 * 命令通道（Outbox / 幂等账本）的查询。
 *
 * <p>调用方 {@code CommandOutbox} / {@code CommandLedger} 都是 new 出来的
 * 静态/工具类，用 {@link Mappers#get} 取代理。
 */
public interface CommandMapper {

    /** 某条 ledger 下仍处于 PENDING 的事件数（Outbox 派发前自检）。 */
    long countPendingEvents(long tenantId, long ledgerId);

    /** 幂等键对应的最终状态；没见过返回 null。 */
    String selectLedgerState(long tenantId, String idempotencyKey);
}
