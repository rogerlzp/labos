package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 暂停/盘点路径的数据访问（原 {@code PauseManager} 里的裸 SQL）。
 *
 * <p>调用方 {@code PauseManager} 是 new 出来的工具类，拿不到构造注入，
 * 用 {@link Mappers#get} 取本接口的代理 —— 在 {@code Tx.inTransaction}
 * 或 {@code @Transactional} 内会复用同一条绑定连接。
 */
public interface PauseMapper {

    /** 是否已有待决的人工窗口（同 run 不重复开）。 */
    boolean hasPendingManualWindow(long tenantId, long runId);

    /** 暂停类型（仅 status='paused' 时有效）。 */
    String selectPauseKind(long tenantId, long runId);

    /**  crashed run 是否标记为可恢复（run.input->>'recoverable'）。 */
    boolean isCrashRecoverable(long tenantId, long runId);

    int updatePlatePositionStatus(long tenantId, String barcode, String positionStatus);

    /** 未派发的暂停事件数（派发器 backlog 展示）。 */
    long countUndispatchedEvents(long tenantId);

    /**
     * 抢未派发事件 id（{@code FOR UPDATE SKIP LOCKED}）——**必须**在
     * {@code Tx.inTransaction} 内调用，行锁才有意义。
     */
    java.util.List<Long> lockPendingPauseEvents(@Param("tenantId") long tenantId,
                                                  @Param("limit") int limit);

    /** 批量标记已派发（dispatched_at=now, attempts+1；仍带 IS NULL 兜底，幂等）。 */
    int markPauseEventsDispatched(@Param("tenantId") long tenantId,
                                  @Param("ids") java.util.List<Long> ids);

    /** 撤回幂等键（暂停请求失败回滚时用）。 */
    int deletePauseRequest(long tenantId, String requestId);

    /** 板子可能所在的位置（原 possible_locations 数组，unnest 成行更好处理）。 */
    List<Long> selectPossibleLocations(long tenantId, String barcode);

    // ---- PauseManager 暂停/恢复写路径 ----

    /** run 落 paused（仅当还在 running）；返回 0 = 无需暂停。 */
    int pauseRun(long tenantId, long runId, String kind, String reason, String nodeKey);

    /** waiting_human 暂停自动开 pending 人工窗口。 */
    int insertManualWindow(long tenantId, long runId, String windowKey,
                           String nodeKey, String reason);

    /** run 恢复/终止时关闭其 pending 人工窗口。 */
    int closeManualWindow(long tenantId, long runId, String operator, String action);

    /** 释放位置预约（非豁免：没板且非 PLATE_UNKNOWN 封锁）。 */
    int releasePositionReservations(long tenantId, long runId);

    /** 释放资源预约（回程承诺 release_exempt 自动跳过）。 */
    int releaseResourceBookings(long tenantId, long runId);

    /** 释放本租户通道占用（暂停即不在搬运中）。 */
    int releasePortUsage(long tenantId);

    /** 释放设备锁：只放"状态不是 running"的。 */
    int releaseDeviceLocks(long tenantId, long runId);

    /** 恢复为 queued（已放行、等待被重新拾起）。 */
    int resumeToQueued(long tenantId, long runId);

    /** 人工终止：aborted + EC-HUMAN-ABORT。 */
    int resumeToAborted(long tenantId, long runId);

    /** 忽略一条暂停的 run（aborted + EC-HUMAN-DISMISS，仅 paused）。 */
    int dismissRun(long tenantId, long runId);

    /**
     * 条件批量忽略（ids 为空时按条件；两者都空 = 全部 paused）。
     *
     * <p>pause_kind 是 varchar 不是枚举（别加 ::pause_kind，会报 type does not exist）；
     * beforeHours 用 updated_at 近似"最后一次动过的时间"。
     */
    int dismissRunsConditional(@Param("tenantId") long tenantId,
                               @Param("kind") String kind,
                               @Param("beforeHours") Integer beforeHours);

    /** 板标 UNKNOWN + 记录可能位置（foreach 拼 bigint[]，调用方不再碰 java.sql.Array）。 */
    int markPlateUnknown(@Param("tenantId") long tenantId,
                         @Param("barcode") String barcode,
                         @Param("possible") java.util.List<Long> possible);

    /** 封锁可能位置（24 小时，只封空位）。 */
    int blockPossiblePositions(@Param("tenantId") long tenantId,
                               @Param("possible") java.util.List<Long> possible);

    /** 解封"因这块板被封"的位置。 */
    int unblockPossiblePositions(@Param("tenantId") long tenantId,
                                 @Param("possible") java.util.List<Long> possible);

    /** 该 run 是否还有未确认的搬运位置（REPORTED/UNKNOWN）。 */
    int countUnconfirmedTransfers(long tenantId, long runId);

    /** 人工盘点的位置证据（设备回包不算位置确认）。 */
    int insertPositionEvidenceManual(long tenantId, String barcode, Long runId,
                                       String operator, String note);

    /** 板位审计（盘点/解封/封锁都记）。 */
    /**
     * 板子流转轨迹（{@code plate_location_audit}），按时间倒序。
     *
     * @param barcode 板号；null/空 = 全部板子
     * @param limit   最多取几条
     */
    java.util.List<java.util.Map<String, Object>> selectPlateTraces(
            @Param("tenantId") long tenantId,
            @Param("barcode") String barcode,
            @Param("limit") int limit);

    int insertPlateAudit(long tenantId, String barcode, String fromStatus,
                         String toStatus, String actualLocation, String operator,
                         String note, Long runId);

    /** 暂停队列：为什么停、停在哪（第一个没完成的业务节点，LATERAL）。 */
    java.util.List<java.util.Map<String, Object>> selectPausedRuns(long tenantId);

    /** 位置未知的板（id 数组翻成 code，操作员看得懂）。 */
    java.util.List<java.util.Map<String, Object>> selectUnknownPlates(long tenantId);

    /**
     * 故障类暂停自动恢复（paused_fault）：只看本 run 流程用到的设备是否在线
     * （旧口径"全租户设备都在线"会让一台无关离线就永不恢复）。
     */
    int autoResumeFaulted(long tenantId);

    // ---- PauseCoordinator（V1-5 幂等键 + pause_event，ADR-PAUSE-009）----

    /**
     * 抢占登记：同 (tenant_id, request_id) 已存在时 ON CONFLICT DO NOTHING。
     *
     * @return 1=首次（插入成功）；0=重复请求（冲突）
     */
    int insertPauseRequest(@Param("tenantId") long tenantId,
                           @Param("requestId") String requestId,
                           @Param("targetType") String targetType,
                           @Param("targetId") long targetId,
                           @Param("action") String action,
                           @Param("source") String source,
                           @Param("kind") String kind,
                           @Param("reason") String reason,
                           @Param("requestedBy") String requestedBy);

    /** 重复请求时读回首次结果（resultState/kind/resultDetail）；不存在返回 null。 */
    java.util.Map<String, Object> selectPauseRequestResult(
            @Param("tenantId") long tenantId,
            @Param("requestId") String requestId);

    /** 回写首次结果供幂等回放；requestId 为空时调用方不调用本方法。 */
    int updatePauseRequestResult(@Param("tenantId") long tenantId,
                                 @Param("requestId") String requestId,
                                 @Param("resultState") String resultState,
                                 @Param("resultDetail") String resultDetail);

    /** Outbox 事件：与 Hold/control_state/pause_request 同事务写入；requestId/scope 可空。 */
    int insertPauseEvent(@Param("tenantId") long tenantId,
                         @Param("requestId") String requestId,
                         @Param("eventType") String eventType,
                         @Param("targetType") String targetType,
                         @Param("targetId") long targetId,
                         @Param("scope") String scope,
                         @Param("payloadJson") String payloadJson);
}
