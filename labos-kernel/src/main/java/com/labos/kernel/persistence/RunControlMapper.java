package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 实验级运行控制（暂停 / 停止 / 恢复）的数据访问
 * （原 {@code RunControl} 里的裸 SQL）。
 *
 * <p>调用方 {@code RunControl} 是 new 出来的工具类，用 {@link Mappers#get} 取代理 ——
 * 在 {@code Tx.inTransaction} 内会复用同一条绑定连接（stopTx/resumeTx/markControlRequestedTx
 * 等签名里的 {@code Connection} 参数因此保留但不再使用）。
 *
 * <p>语句集中在 experiment_run 的控制态推进（control_state / status 守卫），
 * 写集语义见 {@code mapper/RunControlMapper.xml} 注释。
 */
public interface RunControlMapper {

    /**
     * 一条 run 的关键信息（含所属实验与岛）。
     *
     * <p>{@code forUpdate=true} 时对 run/experiment 行加锁
     * （{@code FOR UPDATE OF r, e}）。不存在返回 null（由调用方抛
     * {@code IllegalArgumentException}，保持原语义）。
     */
    Map<String, Object> selectRunInfo(@Param("tenantId") long tenantId,
                                      @Param("runId") long runId,
                                      @Param("forUpdate") boolean forUpdate);

    /** P-06 V2：受理超 staleSeconds 仍在 running 的标成 PAUSING（可观测中间态）。 */
    int markPausing(@Param("tenantId") long tenantId,
                    @Param("staleSeconds") int staleSeconds);

    /**
     * S-04-B：扣留覆盖范围内、还在排队/运行的 run 标 PAUSE_REQUESTED。
     *
     * <p>scope='SITE' 不过滤；'ISLAND' 按 e.island_id；其它按 r.experiment_id。
     * 绝不写 status='paused'（假收敛），status 保持 running/queued。
     */
    int markControlRequested(@Param("tenantId") long tenantId,
                             @Param("scope") String scope,
                             @Param("scopeId") Long scopeId,
                             @Param("kind") String kind,
                             @Param("reason") String reason);

    /** S-04-B 收敛兜底：暂停请求已受理但仍 queued 的直接收成 paused。 */
    int convergeQueuedPauses(@Param("tenantId") long tenantId);

    /** 协作式停止第一步：只落 stop_requested（在途动作由执行器在边界收尾）。 */
    int requestStop(@Param("tenantId") long tenantId, @Param("runId") long runId);

    /** 非运行态直接落 aborted（终态，不可恢复）。 */
    int abortRun(@Param("tenantId") long tenantId, @Param("runId") long runId);

    /**
     * 恢复：run 拉回 queued（等调度器从断点续跑）。
     *
     * <p>{@code clearPauseRequested=true} 时同时清 pause_requested_at
     * （非事务路径）；事务路径（resumeTx）保留原值，语义与原裸 SQL 一致。
     */
    int requeueRun(@Param("tenantId") long tenantId, @Param("runId") long runId,
                   @Param("clearPauseRequested") boolean clearPauseRequested);

    /** 作废被本次暂停中断、即将整段重跑的流程级 run（本实验步 + 仍 paused 的那些）。 */
    int abortOrphanPausedRuns(@Param("tenantId") long tenantId,
                              @Param("experimentRunId") long experimentRunId);
}
