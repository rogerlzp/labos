package com.labos.kernel.persistence;

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

    /** 撤回幂等键（暂停请求失败回滚时用）。 */
    int deletePauseRequest(long tenantId, String requestId);

    /** 板子可能所在的位置（原 possible_locations 数组，unnest 成行更好处理）。 */
    List<Long> selectPossibleLocations(long tenantId, String barcode);
}
