package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 有人值守窗口（{@code manual_window}）的数据访问
 * （原 {@code JdbcManualWindowStore} 里的裸 SQL）。
 *
 * <p>调用方 {@code JdbcManualWindowStore} 是 new 出来的落库层，用 {@link Mappers#get}
 * 取代理；本接口只做语句映射，幂等/乐观条件/超时不代决的语义见 XML 注释与
 * {@code JdbcManualWindowStore} 的类注释。
 */
public interface ManualWindowMapper {

    /**
     * 幂等开窗：同 (tenant_id, window_key) 已有 pending 窗口时不建
     * （WHERE NOT EXISTS 预检；并发 23505 由调用方捕获按「已有窗口」处理）。
     *
     * @return 1=本次真的开了一扇；0=已有 pending 窗口
     */
    int insertWindowIgnoreExisting(@Param("tenantId") long tenantId,
                                     @Param("runId") long runId,
                                     @Param("windowKey") String windowKey,
                                     @Param("errorCode") String errorCode,
                                     @Param("nodeKey") String nodeKey,
                                     @Param("strategy") String strategy,
                                     @Param("defaultAction") String defaultAction,
                                     @Param("allowedActionsJson") String allowedActionsJson,
                                     @Param("reason") String reason,
                                     @Param("timeoutMinutes") int timeoutMinutes);

    /** 待决窗口（最新在前，最多 limit 条）；时间字段为 ::text。 */
    List<Map<String, Object>> selectPendingWindows(@Param("tenantId") long tenantId,
                                                   @Param("limit") int limit);

    /** 按 id 取一行；不存在返回 null。 */
    Map<String, Object> selectWindowById(@Param("tenantId") long tenantId,
                                           @Param("id") long id);

    /** 按幂等键取最近一条（含已处置/已超时）；不存在返回 null。 */
    Map<String, Object> selectWindowByKey(@Param("tenantId") long tenantId,
                                          @Param("windowKey") String windowKey);

    /**
     * 决策回写（乐观条件：仅 pending 且未决）；decidedBy/note 可空。
     *
     * @return 1=本次决策生效；0=已被处置/已超时/不存在
     */
    int updateWindowDecided(@Param("tenantId") long tenantId,
                            @Param("id") long id,
                            @Param("decidedBy") String decidedBy,
                            @Param("decidedAction") String decidedAction,
                            @Param("note") String note);

    /** 超时窗口标 timed_out（不代决，run 仍为 paused/waiting_human）；返回标记条数。 */
    int markOverdueTimedOut(@Param("tenantId") long tenantId);

    /** 仅供连库自测清理合成窗口（window_key 前缀匹配）；返回删除条数。 */
    int deleteWindowsByKeyPrefix(@Param("tenantId") long tenantId,
                                 @Param("prefix") String prefix);
}
