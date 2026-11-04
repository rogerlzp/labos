package com.labos.kernel.persistence;

import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    private static final String SQL = """
            SELECT p.id,
                   ec.code          AS error_code,
                   p.scope,
                   p.strategy::text AS strategy,
                   p.params::text   AS params,
                   p.max_attempts,
                   p.priority,
                   p.enabled
            FROM recovery_policy p
            LEFT JOIN error_code ec ON ec.id = p.error_code_id
            WHERE p.tenant_id = ?
            ORDER BY p.priority, p.id
            """;

    private final DbConfig db;
    private final long tenantId;
    private final Map<String, Long> codeToId = new LinkedHashMap<>();

    public JdbcRecoveryPolicyRepository(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    public List<RecoveryPolicy> loadAll() throws SQLException {
        List<RecoveryPolicy> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
            loadErrorCodeIds(c);
        }
        return out;
    }

    private RecoveryPolicy map(ResultSet rs) throws SQLException {
        String code = rs.getString("error_code");            // null = 通配
        String scopeRaw = rs.getString("scope");
        String stratRaw = rs.getString("strategy");
        String params = rs.getString("params");

        RecoveryScope scope = scopeRaw == null ? null
                : RecoveryScope.valueOf(scopeRaw.trim().toUpperCase());
        RecoveryStrategy strategy = RecoveryStrategy.valueOf(stratRaw.trim().toUpperCase());

        Backoff backoff = JsonParams.backoff(params, Backoff.FIXED);
        Duration base = JsonParams.baseDelay(params, Duration.ofMillis(1000));

        return new RecoveryPolicy(code, scope, strategy,
                rs.getInt("max_attempts"), backoff, base,
                rs.getInt("priority"), rs.getBoolean("enabled"));
    }

    private void loadErrorCodeIds(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, code FROM error_code WHERE tenant_id = ? AND deleted_at IS NULL");
             ) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codeToId.put(rs.getString("code"), rs.getLong("id"));
                }
            }
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
