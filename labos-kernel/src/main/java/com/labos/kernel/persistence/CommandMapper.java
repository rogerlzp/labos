package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

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

    // ---- CommandLedger（P-07 幂等账本）----

    /**
     * 派发前登记：同 (tenant_id, idempotency_key) 已存在时 ON CONFLICT DO NOTHING。
     *
     * @return 新建行的 id；冲突（已存在）返回 null
     */
    Long insertDispatchBegin(@Param("tenantId") long tenantId,
                             @Param("idempotencyKey") String idempotencyKey,
                             @Param("runId") long runId,
                             @Param("nodeId") long nodeId,
                             @Param("attempt") int attempt,
                             @Param("stepSeq") int stepSeq,
                             @Param("deviceId") long deviceId,
                             @Param("method") String method,
                             @Param("commandId") String commandId,
                             @Param("traceId") String traceId);

    /** 幂等键已存在时读回 id/state（fail-closed 阻断重复下发）。 */
    Map<String, Object> selectLedgerEntry(@Param("tenantId") long tenantId,
                                          @Param("idempotencyKey") String idempotencyKey);

    /** P-07.4：与账本同事务写 Outbox 事件（payload 为 jsonb 文本）。 */
    int insertOutboxEvent(@Param("tenantId") long tenantId,
                          @Param("ledgerId") long ledgerId,
                          @Param("runId") long runId,
                          @Param("commandId") String commandId,
                          @Param("deviceId") long deviceId,
                          @Param("method") String method,
                          @Param("payloadJson") String payloadJson);

    /** 中止结果落账（abort_state 与命令 state 分开存，B-04）。 */
    int updateAbortResult(@Param("ledgerId") long ledgerId,
                          @Param("abortState") String abortState,
                          @Param("abortDetail") String abortDetail);

    /** 回包后落终态：ACKED/FAILED + 错误码/消息，command_id 仅补缺。 */
    int finishLedger(@Param("ledgerId") long ledgerId,
                     @Param("state") String state,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("commandId") String commandId);

    /** 启动对账：陈旧 DISPATCH_STARTED 标 UNKNOWN（返回标记条数）。 */
    int markStaleUnknown(@Param("tenantId") long tenantId,
                         @Param("staleMinutes") int staleMinutes);

    /** UNKNOWN 账本行（id/key/runId/nodeId/method/commandId/traceId/errorCode/errorMessage/createdAt）。 */
    List<Map<String, Object>> selectUnknownList(@Param("tenantId") long tenantId,
                                                @Param("limit") int limit);

    /** 中止未确认行（abort_state 非空且非 PHYSICALLY_STOPPED，abortAt 为 String）。 */
    List<Map<String, Object>> selectUnconfirmedAborts(@Param("tenantId") long tenantId,
                                                      @Param("limit") int limit);

    /** 人工核对处置：仅允许 UNKNOWN 转移（并发二次处置返回 0），留痕 resolved_at/by。 */
    int resolveLedger(@Param("tenantId") long tenantId,
                      @Param("ledgerId") long ledgerId,
                      @Param("state") String state,
                      @Param("resolvedBy") Long resolvedBy,
                      @Param("note") String note);

    // ---- CommandOutbox（P-07.4 命令事件 Outbox 派发器，阶段3第29批收编）----

    /**
     * 待派发的 outbox id（id 升序，FOR UPDATE SKIP LOCKED，最多 limit 条）。
     * 调用方在事务内使用（与随后的 markOutboxDelivered 同事务）。
     */
    List<Long> selectPendingOutboxIds(@Param("tenantId") long tenantId,
                                      @Param("limit") int limit);

    /**
     * 批量标记 DELIVERED（delivered_at + attempts+1）；每行仍带 state='PENDING' 护栏，
     * 返回实际更新条数。
     */
    int markOutboxDelivered(@Param("tenantId") long tenantId,
                            @Param("ids") List<Long> ids);

    /**
     * 重放一条已派发事件（DELIVERED → PENDING，仅供外部投递重试，绝不重发物理命令）；
     * 返回 1 = 重放成功。
     */
    int replayOutbox(@Param("tenantId") long tenantId, @Param("outboxId") long outboxId);
}
