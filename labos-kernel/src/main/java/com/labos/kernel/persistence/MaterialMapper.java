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

    /**
     * 执行确认单向写入（§3.2 / 评审 R13-02）：只在仍是 unresolved（或老数据 NULL）时写。
     *
     * <p>已形成终态（completed / failed_confirmed / reconciliation_required）**不得覆盖**：
     * 否则「第 7 出口」在成功路径的完成处理器已写 completed 之后再抛异常时，
     * 会把 completed 与物理证据三件套清空成 reconciliation_required。
     * 返回 0 = 已有终态，调用方不得重复创建效果意图。
     */
    int updateNodeResolutionIfUnresolved(@Param("t") long tenantId,
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

    /** 闸门前置域（§3.7）：只在给定前驱节点里计阻断。 */
    int countGateBlockersByNodes(@Param("t") long tenantId,
                                 @Param("runId") long runId,
                                 @Param("keys") java.util.List<String> keys);

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

    /** §3.5 跨圈聚合：节点所有圈次效果的最阻塞状态。 */
    String selectNodeMaterialSync(@Param("t") long tenantId,
                                  @Param("runId") long runId,
                                  @Param("nodeKey") String nodeKey);

    /**
     * Worker：扫描一个到期的 PENDING 效果**候选**（普通 SELECT，事务外调用）。
     * 真正的「领取」由 {@code applyEffect} 内的 {@code SELECT ... FOR UPDATE} +
     * 状态 CAS 完成：双 Worker 选中同一行时，后到者看到非 PENDING 直接返回，
     * 不会重复应用（§3.5 / 评审 R13-10）。
     */
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

    /** 人工改状态（§3.6a 步骤 6）：板上已过期的 PENDING 效果 id 列表。 */
    java.util.List<Long> selectStaleEffectIds(@Param("t") long tenantId,
                                              @Param("plateId") long plateId,
                                              @Param("newVersion") long newVersion);

    /** §6 读：效果列表（按 status/runId/plateId 过滤）。 */
    java.util.List<java.util.Map<String, Object>> selectEffects(@Param("t") long tenantId,
                                                               @Param("status") String status,
                                                               @Param("runId") Long runId,
                                                               @Param("plateId") Long plateId);

    /** §6 读：效果动作历史（append-only）。 */
    java.util.List<java.util.Map<String, Object>> selectActions(@Param("t") long tenantId,
                                                               @Param("effectId") long effectId);

    /** 规则 4：processState 是否在启用的字典里。 */
    int countEnabledProcessState(@Param("t") long tenantId, @Param("code") String code);

    /** §6 字典列表（设计器下拉用）：只返回启用的工艺阶段。 */
    java.util.List<java.util.Map<String, Object>> selectEnabledProcessStates(
            @Param("t") long tenantId);

    // ---- §6 接口最小集剩余部分：plates 查询 / 字典维护 ----

    /** §6：按工艺阶段/板型/位置可信度查板（三条件均可空）。 */
    java.util.List<java.util.Map<String, Object>> selectPlates(
            @Param("t") long tenantId,
            @Param("processState") String processState,
            @Param("plateType") String plateType,
            @Param("positionStatus") String positionStatus,
            @Param("limit") int limit);

    /** §6：单板详情（含最近一次变更时间与来源 run）。 */
    java.util.Map<String, Object> selectPlateDetail(@Param("t") long tenantId,
                                                    @Param("barcode") String barcode);

    /** §6：单板事件历史（分页，按 id 倒序）。 */
    java.util.List<java.util.Map<String, Object>> selectPlateEvents(
            @Param("t") long tenantId, @Param("plateId") long plateId,
            @Param("limit") int limit, @Param("offset") int offset);

    /** §6 字典：单条查（新增前判重 / PATCH 前判存在）。 */
    java.util.Map<String, Object> selectProcessState(@Param("t") long tenantId,
                                                     @Param("code") String code);

    /** §6 字典：新增（code 不可改）。 */
    int insertProcessState(@Param("t") long tenantId,
                           @Param("code") String code,
                           @Param("displayName") String displayName,
                           @Param("sortOrder") int sortOrder);

    /** §6 字典：改名 / 排序 / 启停（三参数均可空，COALESCE 保留原值）。 */
    int updateProcessState(@Param("t") long tenantId,
                           @Param("code") String code,
                           @Param("displayName") String displayName,
                           @Param("sortOrder") Integer sortOrder,
                           @Param("enabled") Boolean enabled);

    /** 按唯一键查 effect id（insertEffect 后取回 id 用）。 */
    /**
     * 人工改状态（§3.6a / 评审 R13-10）：先锁该板全部 PENDING effect，再锁板，
     * 与 {@code applyEffect} 的 effect→plate 保持同一锁序，避免死锁。
     */
    java.util.List<java.util.Map<String, Object>> selectPendingEffectIdsForUpdate(
            @Param("t") long tenantId, @Param("plateId") long plateId);

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
