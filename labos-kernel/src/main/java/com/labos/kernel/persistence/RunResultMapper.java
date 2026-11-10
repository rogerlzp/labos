package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 异步结果（{@code run_result}）的数据访问（原 {@code RunResultService} 里的裸 SQL，
 * 阶段3第24批收编）。
 *
 * <p>调用方是 new 出来的服务类，用 {@link Mappers#get} 取代理。
 * {@code nodeId}/{@code validUntil} 可空，XML 里统一带 jdbcType 保证 null 安全。
 */
public interface RunResultMapper {

    /**
     * 保存结果（同一 key 重复出现由库约束/调用方报错语义保证，静默覆盖不允许）。
     *
     * @param nodeId     产生结果的节点；null = run 级结果
     * @param validUntil 有效期；null = 永不过期
     */
    int insertResult(@Param("tenantId") long tenantId,
                     @Param("runId") long runId,
                     @Param("nodeId") Long nodeId,
                     @Param("key") String key,
                     @Param("payloadJson") String payloadJson,
                     @Param("source") String source,
                     @Param("validUntil") Timestamp validUntil);

    /** 本 run 的全部结果（resultKey/payload 文本/validUntil），无固定排序要求（调用方逐条摊平）。 */
    List<Map<String, Object>> selectResults(@Param("tenantId") long tenantId,
                                            @Param("runId") long runId);

    /** 某 key 存在且未过期（valid_until IS NULL 或在未来）。 */
    boolean hasResult(@Param("tenantId") long tenantId,
                      @Param("runId") long runId,
                      @Param("key") String key);
}
