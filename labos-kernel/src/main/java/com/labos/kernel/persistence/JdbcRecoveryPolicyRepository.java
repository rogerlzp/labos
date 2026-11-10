package com.labos.kernel.persistence;

import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code recovery_policy} 表加载策略。
 *
 * <p>{@code error_code_id} 为 NULL 表示通配，对应 {@link RecoveryPolicy#errorCode()} 为 null。
 */
public final class JdbcRecoveryPolicyRepository {

    private final DbConfig db;
    private final long tenantId;
    private final Map<String, Long> codeToId = new LinkedHashMap<>();

    public JdbcRecoveryPolicyRepository(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    public List<RecoveryPolicy> loadAll() throws SQLException {
        List<RecoveryPolicy> out = new ArrayList<>();
        OpsMapper om = Mappers.get(OpsMapper.class);
        for (Map<String, Object> row : om.selectRecoveryPolicies(tenantId)) {
            out.add(map(row));
        }
        loadErrorCodeIds(om);
        return out;
    }

    private RecoveryPolicy map(Map<String, Object> row) throws SQLException {
        String code = (String) row.get("errorCode");            // null = 通配
        String scopeRaw = (String) row.get("scope");
        String stratRaw = (String) row.get("strategy");
        String params = (String) row.get("params");

        RecoveryScope scope = scopeRaw == null ? null
                : RecoveryScope.valueOf(scopeRaw.trim().toUpperCase());
        RecoveryStrategy strategy = RecoveryStrategy.valueOf(stratRaw.trim().toUpperCase());

        Backoff backoff = JsonParams.backoff(params, Backoff.FIXED);
        Duration base = JsonParams.baseDelay(params, Duration.ofMillis(1000));

        return new RecoveryPolicy(code, scope, strategy,
                ((Number) row.get("maxAttempts")).intValue(), backoff, base,
                ((Number) row.get("priority")).intValue(),
                Boolean.TRUE.equals(row.get("enabled")));
    }

    private void loadErrorCodeIds(OpsMapper om) throws SQLException {
        for (Map<String, Object> row : om.selectErrorCodeIdPairs(tenantId)) {
            codeToId.put((String) row.get("code"), ((Number) row.get("id")).longValue());
        }
    }

    /** 错误码 -> error_code.id，写 error_occurrence 时用。 */
    public Map<String, Long> errorCodeIds() {
        return Map.copyOf(codeToId);
    }

    public Long errorCodeId(String code) {
        return codeToId.get(code);
    }
}
