package com.labos.kernel.persistence;

import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.recovery.RecoveryStrategy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 把错误写入 {@code error_occurrence}。
 *
 * <p><b>三种结局都留痕，但告警级别不同</b>：
 * <table border="1">
 *   <tr><th>节点状态</th><th>outcome</th><th>severity</th><th>是否告警</th></tr>
 *   <tr><td>SKIPPED</td><td>SKIPPED</td><td>info</td><td>否</td></tr>
 *   <tr><td>MANUAL</td><td>MANUAL</td><td>warn</td><td>是</td></tr>
 *   <tr><td>ABORTED</td><td>ABORTED</td><td>error</td><td>是</td></tr>
 * </table>
 *
 * <p>这是对「91.2% 直接 Ignore、什么都不留」的修正：
 * 跳过的错误确实不该打扰人，但<b>必须可追溯</b>——否则三个月后又变成"出过什么问题没人知道"。
 */
public final class JdbcErrorOccurrenceStore {

    private static final String INSERT = """
            INSERT INTO error_occurrence
              (tenant_id, error_code_id, fingerprint, run_id, run_node_id, resource_id,
               occurred_at, attempt, strategy, outcome, severity, message, context)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::recovery_strategy, ?, ?::error_severity, ?, ?::jsonb)
            """;

    private final DbConfig db;
    private final long tenantId;
    private final JdbcRecoveryPolicyRepository policies;

    public JdbcErrorOccurrenceStore(DbConfig db, long tenantId,
                                    JdbcRecoveryPolicyRepository policies) {
        this.db = db;
        this.tenantId = tenantId;
        this.policies = policies;
    }

    /** 指纹：同一错误码 + 同一设备，用于告警聚合去重。 */
    static String fingerprint(String errorCode, Long resourceId) {
        return errorCode + "|" + (resourceId == null ? "any" : resourceId);
    }

    /**
     * 按节点最终状态自动决定 severity。
     */
    public void recordFromNode(NodeProgress np) throws SQLException {
        String outcome = np.status().name();
        String severity = switch (np.status()) {
            case SKIPPED -> "info";
            case ABORTED -> "error";
            default -> "warn";
        };
        record(new Occurrence(
                -1L,
                np.nodeId(),
                np.currentResourceId(),
                np.lastErrorCode() == null ? "EC-UNKNOWN" : np.lastErrorCode(),
                com.labos.kernel.recovery.RecoveryScope.NODE,
                np.attempt(),
                RecoveryStrategy.MANUAL,
                outcome,
                np.lastReason(),
                Jsons.obj("failedResources", np.failedResourceIds().toString(),
                        "attempt", np.attempt()),
                severity));
    }

    public void record(Occurrence o) throws SQLException {
        Long errorCodeId = policies.errorCodeId(o.errorCode());

        Mappers.get(OpsMapper.class).insertErrorOccurrence(
                tenantId,
                errorCodeId,
                fingerprint(o.errorCode(), o.resourceId()),
                o.runId(),
                o.runNodeId(),
                o.resourceId(),
                OffsetDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()),
                o.attempt(),
                o.strategy() == null ? null : o.strategy().name().toLowerCase(),
                o.outcome(),
                o.severity(),
                o.message(),
                o.contextJson() == null ? "{}" : o.contextJson());
    }

    /** 便捷判断：该状态是否需要落库（三类都要）。 */
    public static boolean shouldRecord(NodeStatus s) {
        return s == NodeStatus.SKIPPED
                || s == NodeStatus.MANUAL
                || s == NodeStatus.ABORTED;
    }
}
