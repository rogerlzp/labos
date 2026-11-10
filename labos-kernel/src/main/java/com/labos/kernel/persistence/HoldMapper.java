package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 现场扣留（{@code runtime_hold}）的数据访问（原 {@code RuntimeHold} 里的裸 SQL）。
 *
 * <p>调用方 {@code RuntimeHold} 是 new 出来的工具类，用 {@link Mappers#get} 取代理 ——
 * 在 {@code Tx.inTransaction} 内会复用同一条绑定连接（raiseTx/clearTx 等签名里的
 * {@code Connection} 参数因此保留但不再使用）。
 *
 * <p>可空的 {@code scopeId}/{@code by}：site 级扣留没有 scope_id，人工解除可不记操作人，
 * XML 里统一 {@code COALESCE(..., 0)} 口径比对。
 */
public interface HoldMapper {

    /**
     * 幂等扣留：同 (scope, scope_id, kind) 已有生效行（released_at IS NULL）时不建。
     *
     * @return 1=本次新建；0=已有生效扣留（沿用）
     */
    int insertHoldIgnoreExisting(@Param("tenantId") long tenantId,
                                 @Param("scope") String scope,
                                 @Param("scopeId") Long scopeId,
                                 @Param("kind") String kind,
                                 @Param("reason") String reason,
                                 @Param("by") Long by);

    /** 解除该 scope 的全部生效扣留；返回 0=没有生效扣留。 */
    int releaseHolds(@Param("tenantId") long tenantId,
                     @Param("scope") String scope,
                     @Param("scopeId") Long scopeId,
                     @Param("by") Long by,
                     @Param("note") String note);

    /** 按 kind 精确解除（多 Hold 并存只解一类，不误伤其它原因，P-03）。 */
    int releaseHoldsByKind(@Param("tenantId") long tenantId,
                           @Param("scope") String scope,
                           @Param("scopeId") Long scopeId,
                           @Param("kind") String kind,
                           @Param("by") Long by,
                           @Param("note") String note);

    /** 全部生效扣留（scope/scopeId/kind/reason，按 held_at 升序）；安全判定用。 */
    List<Map<String, Object>> selectActiveHolds(long tenantId);

    /** 正在执行这条流程的实验（experimentId/islandId，步骤行还在 running 的）。 */
    List<Map<String, Object>> selectRunningWorkflowExperiments(long tenantId, long workflowId);

    /** 生效扣留展示视图（id/scope/scopeId/kind/reason/heldAt/heldBy，heldAt 为 Timestamp）。 */
    List<Map<String, Object>> selectActiveHoldViews(long tenantId);
}
