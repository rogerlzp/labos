package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * 物料身份与状态：声明快照 / 效果 / 动作 / 事件 的写入。
 *
 * <p>语句见 {@code mapper/MaterialMapper.xml}。三张 append-only 表
 * （declaration / action / event）只 INSERT；effect 是有状态表（可 UPDATE 状态列）。
 * 第一版不建 FK，引用完整性由业务层 + 巡检兜底（§5）。
 */
public interface MaterialMapper {

    /** 声明快照（H3，追加表；续跑幂等：ON CONFLICT DO NOTHING）。 */
    int insertDeclaration(@Param("t") long tenantId,
                          @Param("runId") long runId,
                          @Param("nodeKey") String nodeKey,
                          @Param("effectIndex") int effectIndex,
                          @Param("declaration") String declarationJson,
                          @Param("hash") String declarationHash,
                          @Param("workflowVer") Integer workflowVer);

    /** 效果意图（T1 写 PENDING；续跑幂等：ON CONFLICT DO NOTHING）。 */
    int insertEffect(@Param("t") long tenantId,
                     @Param("runId") long runId,
                     @Param("nodeKey") String nodeKey,
                     @Param("loopIndex") int loopIndex,
                     @Param("effectIndex") int effectIndex,
                     @Param("attempt") int attempt,
                     @Param("plateId") Long plateId,
                     @Param("declaration") String declarationJson,
                     @Param("declHash") String declHash,
                     @Param("payloadHash") String payloadHash,
                     @Param("targetSnapshot") String targetSnapshot,
                     @Param("resolutionEvidence") String resolutionEvidence,
                     @Param("expectedStateVersion") Long expectedStateVersion,
                     @Param("expectedProcessState") String expectedProcessState,
                     @Param("expectedSealState") String expectedSealState,
                     @Param("status") String status);

    /** 效果动作审计（append-only）。 */
    int insertAction(@Param("t") long tenantId,
                     @Param("effectId") long effectId,
                     @Param("actionType") String actionType,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus,
                     @Param("actorType") String actorType,
                     @Param("operatorUserId") Long operatorUserId,
                     @Param("reason") String reason,
                     @Param("evidenceSnapshot") String evidenceSnapshot,
                     @Param("errorCode") String errorCode);

    /** 物料事件（append-only；完整 before/after + 版本区间 + changed_fields）。 */
    int insertEvent(@Param("t") long tenantId,
                    @Param("plateId") long plateId,
                    @Param("effectId") Long effectId,
                    @Param("eventType") String eventType,
                    @Param("fromProcessState") String fromProcessState,
                    @Param("toProcessState") String toProcessState,
                    @Param("fromSealState") String fromSealState,
                    @Param("toSealState") String toSealState,
                    @Param("changedFields") String changedFieldsJson,
                    @Param("fromStateVersion") long fromStateVersion,
                    @Param("toStateVersion") long toStateVersion,
                    @Param("sourceRunId") Long sourceRunId,
                    @Param("sourceNodeKey") String sourceNodeKey,
                    @Param("sourceLoopIndex") int sourceLoopIndex,
                    @Param("commandId") String commandId,
                    @Param("evidence") String evidence,
                    @Param("evidenceLevel") String evidenceLevel,
                    @Param("evidenceSnapshot") String evidenceSnapshot,
                    @Param("operatorType") String operatorType,
                    @Param("operatorUserId") Long operatorUserId,
                    @Param("note") String note,
                    @Param("occurredAt") String occurredAt);

    /** 写节点执行确认（§3.2）：execution_resolution_status + completed 的物理证据三件套。 */
    int updateNodeExecution(@Param("t") long tenantId,
                            @Param("runId") long runId,
                            @Param("nodeKey") String nodeKey,
                            @Param("resolution") String resolution,
                            @Param("physicalEvidenceLevel") String physicalEvidenceLevel,
                            @Param("physicalEvidence") String physicalEvidenceJson);

    /** 读声明快照（按 run+node+effect 唯一键）。 */
    java.util.Map<String, Object> selectDeclaration(@Param("t") long tenantId,
                                                    @Param("runId") long runId,
                                                    @Param("nodeKey") String nodeKey);

    /** 读板当前 state_version（T1 计算 expected_state_version 用）。 */
    Long selectPlateStateVersion(@Param("t") long tenantId, @Param("plateId") long plateId);

    /** T1 目标解析：按 barcode 找板（未删除）。 */
    java.util.Map<String, Object> selectPlateByBarcode(@Param("t") long tenantId,
                                                      @Param("barcode") String barcode);

    /** T1 目标解析：按位置找板（position_state 投影 + 归属/在途信息）。 */
    java.util.Map<String, Object> selectPlateByLocation(@Param("t") long tenantId,
                                                       @Param("resourceId") long resourceId);

    /** 闸门（§3.7）：本 run 未闭环效果数（PENDING/FAILED/REJECTED/NEEDS_RECONCILIATION）。 */
    int countUnclosedEffects(@Param("t") long tenantId, @Param("runId") long runId);

    /** 闸门阻断计数（§3.7 / 第 12 项）：未闭环效果 + reconciliation_required 节点。 */
    int countGateBlockers(@Param("t") long tenantId, @Param("runId") long runId);

    /** T2：锁效果行（FOR UPDATE）。 */
    java.util.Map<String, Object> selectEffectForUpdate(@Param("t") long tenantId,
                                                       @Param("effectId") long effectId);

    /** T2：锁板行（FOR UPDATE）。 */
    java.util.Map<String, Object> selectPlateForUpdate(@Param("t") long tenantId,
                                                      @Param("plateId") long plateId);

    /** T2：更新板投影（process_state/seal_state/state_version）。 */
    int updatePlateState(@Param("t") long tenantId,
                         @Param("plateId") long plateId,
                         @Param("processState") String processState,
                         @Param("sealState") String sealState,
                         @Param("newStateVersion") long newStateVersion);

    /** T2：更新节点物料同步状态（汇总行 loop_index=0）。 */
    int updateNodeMaterialSync(@Param("t") long tenantId,
                               @Param("runId") long runId,
                               @Param("nodeKey") String nodeKey,
                               @Param("status") String status);

    /** Worker：扫描一个到期的 PENDING 效果（FOR UPDATE SKIP LOCKED 领取）。 */
    Long selectDueEffectId(@Param("t") long tenantId);

    /** Worker：失败计数 + 退避（独立于 applyEffect 事务）。 */
    int recordRetryFailure(@Param("t") long tenantId,
                           @Param("effectId") long effectId,
                           @Param("nextRetryAt") String nextRetryAt,
                           @Param("errorCode") String errorCode,
                           @Param("errorMessage") String errorMessage);

    /** 对账转 PENDING（§3.6）：提交 confirmedTarget + 重读后的 expected_state_version。 */
    int updateEffectReconcile(@Param("t") long tenantId,
                              @Param("effectId") long effectId,
                              @Param("plateId") long plateId,
                              @Param("expectedStateVersion") long expectedStateVersion);

    /** rebase：更新 expected_state_version 为板当前版本 + FAILED→PENDING。 */
    int updateEffectRebase(@Param("t") long tenantId,
                           @Param("effectId") long effectId,
                           @Param("expectedStateVersion") long expectedStateVersion);

    /** 按唯一键查 effect id（insertEffect 后取回 id 用）。 */
    Long selectEffectId(@Param("t") long tenantId,
                        @Param("runId") long runId,
                        @Param("nodeKey") String nodeKey,
                        @Param("loopIndex") int loopIndex,
                        @Param("effectIndex") int effectIndex);

    /** 效果状态流转（T2/Worker 使用；只改状态列，不改声明/快照）。 */
    int updateEffectStatus(@Param("t") long tenantId,
                           @Param("effectId") long effectId,
                           @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus,
                           @Param("lastErrorCode") String lastErrorCode,
                           @Param("lastErrorMessage") String lastErrorMessage);
}
