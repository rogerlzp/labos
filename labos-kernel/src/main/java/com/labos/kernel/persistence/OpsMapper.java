package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 运维错误码/恢复策略域的数据访问（{@code OpsErrorService} 专用）。
 *
 * <p>按「Controller → Service → Mapper」从 {@code QueryService} /
 * {@code QueryMapper} 归位；策略的新建与更新要一起看，别只搬一半。
 */
public interface OpsMapper {

    /** 同一 (tenant, 错误码, 作用域) 下已有的策略 id；没有返回 null。 */
    Long selectPolicyId(long tenantId, long errorCodeId, String scope);

    /** 更新策略（COALESCE：没传的字段不动）。 */
    int updatePolicy(long tenantId, long policyId, String strategy,
                     Integer maxAttempts, String params, Integer priority,
                     Boolean enabled);

    /** 新建策略，返回 id。 */
    Long insertPolicy(long tenantId, long errorCodeId, String scope,
                      String strategy, String params, Integer maxAttempts,
                      Integer priority, Boolean enabled);

    /** 错误码清单：三重可选过滤（severity / scope / keyword），keyword 走 ILIKE。 */
    List<Map<String, Object>> selectErrorCodes(
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("severity") String severity,
            @org.apache.ibatis.annotations.Param("scope") String scope,
            @org.apache.ibatis.annotations.Param("keyword") String keyword);

    /** 新建错误码，返回 id。MyBatis 的 <insert> 不能带 resultType，这里用 <select>。 */
    Long insertErrorCode(long tenantId, String code, String scope, String severity,
                         String title, String detail, String source);

    /** 错误码是否已被同级（或 NULL 通配）的启用策略覆盖。 */
    boolean scopeCovered(long tenantId, long errorCodeId);

    /** 删除恢复策略。 */
    int deleteRecoveryPolicy(long tenantId, long errorCodeId, long policyId);

    /**
     * 错误码本体字段的动态 UPDATE（哪些列会被写由 XML 的 &lt;if&gt; 决定）。
     *
     * <p>severity 是 PG 枚举，必须 {@code ::error_severity}；scope/title/detail
     * 是 varchar，原实现没有 cast，保持一致。
     */
    int updateErrorCodeFields(long tenantId, long id,
                              @Param("scope") String scope,
                              @Param("severity") String severity,
                              @Param("title") String title,
                              @Param("detail") String detail);

    /**
     * 只改「主策略」（priority 最小的那条）。
     *
     * <p>COALESCE 表示「没传就不动」，所以 null 不会被写成 NULL。
     */
    int updatePrimaryRecoveryPolicy(long tenantId, long errorCodeId,
                                    @Param("strategy") String strategy,
                                    @Param("maxAttempts") String maxAttempts,
                                    @Param("params") String params,
                                    @Param("enabled") String enabled);

    /** 错误码软删。 */
    int softDeleteErrorCode(long tenantId, long id);

    /** 错误发生记录（最近 N 条）。 */
    List<Map<String, Object>> selectErrorOccurrences(long tenantId, int limit);

    /**
     * 演示验证：单条 run 的全部错误留痕（runNodeId/fingerprint/attempt/outcome/
     * severity/message，无 tenant 过滤 —— run_id 全局唯一，与原语句一致）。
     */
    List<Map<String, Object>> selectErrorOccurrencesByRun(long runId);

    /**
     * 全部已登记错误码的作用域（code/scope 文本，scope 非空且未软删）；
     * DriverStepExecutor 启动时加载一次做「错误码 → 恢复作用域」映射。
     */
    List<Map<String, Object>> selectErrorCodeScopes(@Param("tenantId") long tenantId);


    /** 按 id 查策略作用域（PATCH 没带 scope 时用）。查不到返回 null。 */
    String selectPolicyScope(long tenantId, long policyId);

    /**
     * 全部恢复策略行（errorCode 为 null 表示通配；strategy/params 显式 ::text）。
     * JdbcRecoveryPolicyRepository 启动时加载。
     */
    List<Map<String, Object>> selectRecoveryPolicies(@Param("tenantId") long tenantId);

    /** 全部未软删错误码的 id/code 对（errorCodeIds 映射用）。 */
    List<Map<String, Object>> selectErrorCodeIdPairs(@Param("tenantId") long tenantId);

    /**
     * 错误留痕写入 error_occurrence。errorCodeId/resourceId 可空；
     * strategy 可空（::recovery_strategy cast）；contextJson 为 JSON 文本。
     */
    int insertErrorOccurrence(@Param("tenantId") long tenantId,
                              @Param("errorCodeId") Long errorCodeId,
                              @Param("fingerprint") String fingerprint,
                              @Param("runId") long runId,
                              @Param("runNodeId") long runNodeId,
                              @Param("resourceId") Long resourceId,
                              @Param("occurredAt") java.time.OffsetDateTime occurredAt,
                              @Param("attempt") int attempt,
                              @Param("strategy") String strategy,
                              @Param("outcome") String outcome,
                              @Param("severity") String severity,
                              @Param("message") String message,
                              @Param("contextJson") String contextJson);

    /**
     * 覆盖率分析：错误码出现次数与历史处置（CoverageAnalyzer 用）。
     * 原语句硬编码 tenant_id = 1，XML 逐字保留。
     */
    List<Map<String, Object>> selectErrorCodeStats();

}
