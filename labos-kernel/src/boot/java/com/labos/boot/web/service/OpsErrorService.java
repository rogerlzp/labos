package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.OpsMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import static com.labos.kernel.persistence.QJson.*;
import com.labos.kernel.persistence.Jsons;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public String errorCodesJson(String severity, String scope, String keyword)
            throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        // 空串归一化成 null = XML 里对应 <if> 不成立（不过滤）；
        // keyword 的 %..% 在这里拼好，SQL 里同一个参数用两次。
        String sev = severity == null || severity.isBlank() ? null : severity;
        String scp = scope == null || scope.isBlank() ? null : scope;
        String kw = keyword == null || keyword.isBlank()
                ? null : "%" + keyword + "%";
        for (java.util.Map<String, Object> row
                : opsMapper.selectErrorCodes(TENANT, sev, scp, kw)) {
            Object policies = row.get("policies");
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "code", row.get("code"),
                    "scope", row.get("scope"),
                    "severity", row.get("severity"),
                    "source", row.get("source"),
                    "title", row.get("title"),
                    "detail", row.get("detail"),
                    "occurrences", mint(row, "occurrences"),
                    "scopeCovered", Boolean.TRUE.equals(row.get("scopeCovered")),
                    "strategy", row.get("strategy"),
                    "maxAttempts", row.get("maxAttempts"),
                    "params", row.get("params"),
                    "priority", row.get("priority"),
                    "enabled", row.get("enabled"),
                    "policyScope", row.get("policyScope"),
                    "policies", new Jsons.Raw(policies == null
                            ? "[]" : String.valueOf(policies))));
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 在 XML 里把指定元素的 {@code protocolName} 换掉（没有就补上），其余原样。 */
    /** 新建一个错误码（码本身；策略另行添加）。 */
    public String createErrorCode(String code, String scope, String severity,
                                  String title, String detail, String source)
            throws java.sql.SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("错误码不能为空");
        }
        String c = code.trim();
        try {
            Long id = opsMapper.insertErrorCode(TENANT, c,
                    scope == null || scope.isBlank() ? "command" : scope,
                    severity == null || severity.isBlank() ? "error" : severity,
                    title == null || title.isBlank() ? c : title,
                    detail,
                    source == null || source.isBlank() ? "manual" : source);
            return Jsons.obj("id", id, "code", c);
        } catch (RuntimeException e) {
            // 唯一键冲突要给前端看得懂的话，而不是 500
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new IllegalArgumentException("错误码已存在：" + c);
            }
            throw e;
        }
    }

    /**
     * 新增或更新一条策略（同一个码 + 同一个作用域**只该有一条**）。
     *
     * <p>为什么按作用域唯一：{@code RecoveryPolicyResolver} 是 {@code findFirst}，
     * 同码同作用域的两条策略里只有 priority 小的会用上，另一条是死配置。
     * 真正需要多条的场景是"同一码在 command 层和 node 层处理不同"。
     */
    /** 该码在它自己的作用域上是否有启用中的策略（保存后回给前端做提示）。 */
    public boolean scopeCovered(long errorCodeId) throws java.sql.SQLException {
        return opsMapper.scopeCovered(TENANT, errorCodeId);
    }

    public boolean deletePolicy(long errorCodeId, long policyId) throws java.sql.SQLException {
        return opsMapper.deleteRecoveryPolicy(TENANT, errorCodeId, policyId) > 0;
    }

    public boolean updateErrorCode(long id, Map<String, String> fields) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                // 只有确实传了字段才发 UPDATE（否则 <set> 会拼出空 SET）。
                boolean anyCodeField = List.of("scope", "severity", "title", "detail")
                        .stream().anyMatch(k -> fields.get(k) != null);
                if (anyCodeField) {
                    opsMapper.updateErrorCodeFields(TENANT, id,
                            fields.get("scope"), fields.get("severity"),
                            fields.get("title"), fields.get("detail"));
                }

                String strategy = fields.get("strategy");
                String maxAttempts = fields.get("maxAttempts");
                String params = fields.get("params");
                String enabled = fields.get("enabled");
                if (strategy != null || maxAttempts != null || params != null || enabled != null) {
                    // 只改主策略（priority 最小那条）。多条策略的精细编辑放后面再说——
                    // 现在库里每个码最多 1 条，先把 80% 的场景做掉。
                    opsMapper.updatePrimaryRecoveryPolicy(TENANT, id,
                            strategy, maxAttempts, params, enabled);
                }
                return true;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * 删除错误码（软删，`deleted_at`）。
     *
     * <p>为什么软删：{@code error_occurrence} 里挂着历史发生记录，
     * 硬删会连带丢掉"这个错以前出过多少次"；软删后列表不再显示，历史仍在。
     */
    public boolean deleteErrorCode(long id) throws java.sql.SQLException {
        return opsMapper.softDeleteErrorCode(TENANT, id) > 0;
    }

    /** 错误记录明细（含 SKIPPED 留痕，用于追溯）。 */
    public String occurrences(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : opsMapper.selectErrorOccurrences(TENANT,
                        Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "fingerprint", row.get("fingerprint"),
                    "outcome", row.get("outcome"),
                    "severity", row.get("severity"),
                    "attempt", mint(row, "attempt"),
                    "runId", row.get("runId"),
                    "runNodeId", row.get("runNodeId"),
                    "resourceId", row.get("resourceId"),
                    "occurredAt", mstr(row, "occurredAt"),
                    "message", row.get("message")));
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    public String commandsUnknownJson(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (com.labos.kernel.driver.CommandLedger.Unknown u
                : com.labos.kernel.driver.CommandLedger.listUnknown(db, TENANT, limit)) {
            items.add(Jsons.obj("id", u.id(), "key", u.key(), "runId", u.runId(),
                    "nodeId", u.nodeId(), "method", u.method(), "commandId", u.commandId(),
                    "traceId", u.traceId(), "errorCode", u.errorCode(),
                    "errorMessage", u.errorMessage(), "createdAt", u.createdAt()));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)),
                "count", items.size());
    }

    /** UNKNOWN 命令账本列表（P-07.2 人工核对入口）。 */
    /**
     * B-04：已请求中止、但设备没确认停的命令（人工核验入口）。
     *
     * <p>与 {@link #commandsUnknownJson} 并列而不是合并：UNKNOWN 说的是
     * 「命令结果不知道」，这里说的是「我们试着停过，但没拿到证据」——
     * 两条判断可以同时成立，混成一张表就分不清是哪条原因需要人核查。
     */
    public String commandsAbortUnconfirmedJson(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (com.labos.kernel.driver.CommandLedger.AbortPending a
                : com.labos.kernel.driver.CommandLedger.listUnconfirmedAbort(db, TENANT, limit)) {
            items.add(Jsons.obj("id", a.id(), "key", a.key(), "runId", a.runId(),
                    "nodeId", a.nodeId(), "method", a.method(), "commandId", a.commandId(),
                    "state", a.state(), "abortState", a.abortState(),
                    "abortDetail", a.abortDetail(), "abortAt", a.abortAt()));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)),
                "count", items.size());
    }

    /** 处置 UNKNOWN 命令账本（confirm-done / confirm-failed）。 */
    public int resolveCommandLedger(long id, boolean done, String note, Long by)
            throws java.sql.SQLException {
        return com.labos.kernel.driver.CommandLedger.resolve(db, TENANT, id, done, note, by);
    }


    /** 按 id 查策略作用域（PATCH 没带 scope 时用）。查不到返回 null。 */
    public String policyScope(long policyId) throws java.sql.SQLException {
        return opsMapper.selectPolicyScope(TENANT, policyId);
    }

}
