package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.OpsMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;

/**
 * 运维错误码域的写 Service：{@code OpsErrorController} 的恢复策略端点。
 *
 * <p>按「Controller → Service → Mapper」从 {@code QueryService} 拆出，
 * SQL 归 {@link OpsMapper}。策略按 (tenant, 错误码, 作用域) 唯一：
 * 已有则 COALESCE 更新，没有则新建。
 */
@Service
public class OpsErrorService {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final OpsMapper opsMapper;

    public OpsErrorService(DbConfig db, OpsMapper opsMapper) {
        this.db = db;
        this.opsMapper = opsMapper;
    }

    /** 新建或更新一条恢复策略，返回策略 id。 */
    public long upsertPolicy(long errorCodeId, String scope, String strategy,
                             Integer maxAttempts, String params, Integer priority,
                             Boolean enabled) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                Long existId = opsMapper.selectPolicyId(TENANT, errorCodeId, scope);
                if (existId != null) {
                    opsMapper.updatePolicy(TENANT, existId, strategy,
                            maxAttempts, params, priority, enabled);
                    return existId;
                }
                Long id = opsMapper.insertPolicy(TENANT, errorCodeId, scope,
                        strategy == null ? "manual" : strategy, params,
                        maxAttempts == null ? 1 : maxAttempts,
                        priority == null ? 50 : priority, enabled);
                return id == null ? 0L : id;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }
}
