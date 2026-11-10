package com.labos.kernel.persistence;

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
}
