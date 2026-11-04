package com.labos.kernel.api;

import com.labos.kernel.engine.EngineDemo;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcErrorOccurrenceStore;
import com.labos.kernel.persistence.JdbcEventStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.PreflightChecker;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.RecipeCompiler;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.driver.ShadowRunner;
import com.labos.kernel.workflow.BatchOrchestrator;
import com.labos.kernel.workflow.ExperimentExecutor;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.RunControl;
import com.labos.kernel.workflow.RuntimeHold;
import com.labos.kernel.workflow.SchedulerEta;
import com.labos.kernel.workflow.StatusVocabulary;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.WorkflowService;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.labos.kernel.api.ApiServer.*;


/**
 * OpsApi：从 {@link ApiServer#route} 拆出的 Ops 资源域路由（22 个路由块）。
 *
 * <p>路径：{@code /api/alerts}、{@code /api/audit}、{@code /api/demo/run}、{@code /api/device-alerts}、{@code /api/error-codes}、{@code /api/errors}、{@code /api/policies}、{@code /api/policies/validate}、{@code /api/settings/heartbeat}、{@code /api/users}
 * <p>正则路由：{@code ALERT_ACT}、{@code ERROR_CODE_ONE}、{@code ERROR_CODE_POLICIES}、{@code ERROR_CODE_POLICY}、{@code POLICY_ONE}、{@code USER_ONE}
 *
 * <p><b>拆分约定</b>：路由顺序与原 ApiServer.route() 中完全一致；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class OpsApi {

    /** 宿主：提供库连接/服务与共享 helper（与原 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    OpsApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        if ("/api/users".equals(path)) {
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.usersJson());
                return true;
            }
            if ("POST".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                int n = s.query.createUser(
                        jsonField(body, "username"),
                        jsonField(body, "displayName"),
                        jsonField(body, "role"),
                        jsonField(body, "password"));
                // 绝不能把 password 写进审计——那是明文凭证
                s.audited("user.create", "user", null,
                        jsonField(body, "username"),
                        Jsons.obj("role", jsonField(body, "role"),
                                "displayName", jsonField(body, "displayName")), ex);
                s.send(ex, 200, Jsons.obj("created", n));
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        Matcher userOne = USER_ONE.matcher(path);
        if (userOne.matches()) {
            if (!"PUT".equals(method) && !"PATCH".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long uid = Long.parseLong(userOne.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            int n = s.query.updateUser(uid,
                    jsonField(body, "role"),
                    jsonBool(body, "enabled"),
                    jsonField(body, "password"));
            s.audited("user.update", "user", uid, null,
                    Jsons.obj("role", jsonField(body, "role"),
                            "enabled", jsonBool(body, "enabled"),
                            "resetPassword", jsonField(body, "password") != null), ex);
            s.send(ex, 200, Jsons.obj("updated", n, "id", uid));
            return true;
        }
        if ("/api/audit".equals(path)) {
            s.requireGet(method, ex);
            String q = ex.getRequestURI().getQuery();
            s.send(ex, 200, s.query.auditJson(200, paramStr(q, "action")));
            return true;
        }
        Matcher policyOne = POLICY_ONE.matcher(path);
        if (policyOne.matches()) {
            if (!"PUT".equals(method) && !"PATCH".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long pid = Long.parseLong(policyOne.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            int n = s.query.updatePolicy(pid,
                    jsonField(body, "strategy"),
                    jsonInt(body, "maxAttempts"),
                    jsonInt(body, "priority"),
                    jsonBool(body, "enabled"));
            s.audited("policy.update", "policy", pid, null,
                    Jsons.obj("strategy", jsonField(body, "strategy"),
                            "enabled", jsonBool(body, "enabled"),
                            "maxAttempts", jsonInt(body, "maxAttempts")), ex);
            s.send(ex, 200, Jsons.obj("updated", n, "id", pid));
            return true;
        }
        if ("/api/policies/validate".equals(path)) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String code = jsonField(body, "errorCode");
            Integer prio = jsonInt(body, "priority");
            s.send(ex, 200, s.query.validatePolicy(code, jsonField(body, "scope"),
                    prio == null ? 50 : prio));
            return true;
        }
        Matcher alertAct = ALERT_ACT.matcher(path);
        if (alertAct.matches()) {
            if (!"POST".equals(method) && !"PATCH".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String fp = java.net.URLDecoder.decode(alertAct.group(1), StandardCharsets.UTF_8);
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String assignee = jsonField(body, "assignee");
            String note = jsonField(body, "note");
            int n = s.query.updateAlertState(fp, alertAct.group(2), assignee, note);
            s.audited("alert." + alertAct.group(2), "alert", null, fp,
                    Jsons.obj("assignee", assignee, "note", note), ex);
            s.send(ex, 200, Jsons.obj(
                    "fingerprint", fp,
                    "action", alertAct.group(2),
                    "updated", n));
            return true;
        }
        if ("/api/policies".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.policies());
            return true;
        }
        if ("/api/alerts".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.alerts(intParam(ex, "limit", 20)));
            return true;
        }
        if ("/api/device-alerts".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.deviceAlerts(intParam(ex, "limit", 60)));
            return true;
        }
        if ("/api/settings/heartbeat".equals(path)) {
            // 页面改完**立即生效**：心跳线程每个周期读 RuntimeSettings，不用重启
            if ("PUT".equals(method) || "POST".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                Long iv = jsonLong(body, "intervalMs");
                Long off = jsonLong(body, "offlineMs");
                if (iv == null && off == null) {
                    s.send(ex, 400, err("至少给一个参数：intervalMs / offlineMs"));
                    return true;
                }
                long curIv = com.labos.kernel.driver.RuntimeSettings.heartbeatIntervalMs();
                long curOff = com.labos.kernel.driver.RuntimeSettings.offlineAlertMs();
                long[] eff = com.labos.kernel.driver.RuntimeSettings.updateHeartbeat(
                        iv == null ? curIv : iv, off == null ? curOff : off);
                try {
                    com.labos.kernel.driver.RuntimeSettings.saveHeartbeat(
                            s.db, TENANT, eff[0], eff[1]);
                } catch (java.sql.SQLException e) {
                    // 存不进去也要让内存生效 —— 下次重启回退，不影响当前使用
                    System.err.println("[WARN] 心跳参数落库失败（内存已生效）: " + e.getMessage());
                }
                s.audited("settings.heartbeat", "sys_setting", null,
                        "interval=" + eff[0] + ",offline=" + eff[1], null, ex);
                s.send(ex, 200, Jsons.obj("intervalMs", eff[0], "offlineMs", eff[1]));
                return true;
            }
            s.requireGet(method, ex);
            s.send(ex, 200, Jsons.obj(
                    "intervalMs", com.labos.kernel.driver.RuntimeSettings.heartbeatIntervalMs(),
                    "offlineMs", com.labos.kernel.driver.RuntimeSettings.offlineAlertMs()));
            return true;
        }
        if ("/api/error-codes".equals(path)) {
            if ("POST".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                try {
                    String created = s.query.createErrorCode(
                            jsonField(body, "code"), jsonField(body, "scope"),
                            jsonField(body, "severity"), jsonField(body, "title"),
                            jsonField(body, "detail"), jsonField(body, "source"));
                    s.audited("error_code.create", "error_code", null,
                            jsonField(body, "code"), null, ex);
                    s.send(ex, 200, created);
                } catch (IllegalArgumentException e) {
                    s.send(ex, 400, err(e.getMessage()));
                }
                return true;
            }
            s.requireGet(method, ex);
            String qs = ex.getRequestURI().getQuery();
            s.send(ex, 200, s.query.errorCodesJson(
                    paramStr(qs, "severity"), paramStr(qs, "scope"), paramStr(qs, "q")));
            return true;
        }
        Matcher ecPolicies = ERROR_CODE_POLICIES.matcher(path);
        if (ecPolicies.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long ecid2 = Long.parseLong(ecPolicies.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String scope = jsonField(body, "scope");
            if (scope == null || scope.isBlank()) {
                s.send(ex, 400, err("策略必须指定 scope（command/node/run/device）"));
                return true;
            }
            String p = jsonField(body, "params");
            long pid = s.query.upsertPolicy(ecid2, scope,
                    jsonField(body, "strategy"),
                    jsonInt(body, "maxAttempts"),
                    p,
                    jsonInt(body, "priority"),
                    jsonBool(body, "enabled"));
            s.audited("error_code.policy_save", "error_code", ecid2,
                    "scope=" + scope, null, ex);
            boolean covered = s.query.scopeCovered(ecid2);
            s.send(ex, 200, Jsons.obj("errorCodeId", ecid2, "policyId", pid,
                    "scope", scope, "scopeCovered", covered,
                    "warning", covered ? null
                            : "该码自己的作用域上仍没有启用中的策略，真出错时会落到通配策略"));
            return true;
        }
        Matcher ecPolicy = ERROR_CODE_POLICY.matcher(path);
        if (ecPolicy.matches()) {
            long ecid3 = Long.parseLong(ecPolicy.group(1));
            long pid2 = Long.parseLong(ecPolicy.group(2));
            if ("DELETE".equals(method)) {
                boolean gone = s.query.deletePolicy(ecid3, pid2);
                s.audited("error_code.policy_delete", "error_code", ecid3,
                        "policy=" + pid2, null, ex);
                s.send(ex, gone ? 200 : 404, Jsons.obj("deleted", gone, "policyId", pid2));
                return true;
            }
            if (!"PATCH".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            // 按 id 改：作用域默认不动（改作用域等于换一条策略，前端走新增）
            String pScope = jsonField(body, "scope");
            if (pScope == null || pScope.isBlank()) {
                pScope = scopeOfPolicy(pid2);
            }
            if (pScope == null) {
                s.send(ex, 404, err("策略不存在: " + pid2));
                return true;
            }
            s.query.upsertPolicy(ecid3, pScope,
                    jsonField(body, "strategy"),
                    jsonInt(body, "maxAttempts"),
                    jsonField(body, "params"),
                    jsonInt(body, "priority"),
                    jsonBool(body, "enabled"));
            s.audited("error_code.policy_update", "error_code", ecid3,
                    "policy=" + pid2, null, ex);
            s.send(ex, 200, Jsons.obj("policyId", pid2, "updated", true));
            return true;
        }
        Matcher ecOne = ERROR_CODE_ONE.matcher(path);
        if (ecOne.matches()) {
            if ("DELETE".equals(method)) {
                long delId = Long.parseLong(ecOne.group(1));
                boolean gone = s.query.deleteErrorCode(delId);
                if (gone) {
                    s.audited("error_code.delete", "error_code", delId, null, null, ex);
                }
                s.send(ex, gone ? 200 : 404,
                        Jsons.obj("id", delId, "deleted", gone));
                return true;
            }
            if (!"PATCH".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long ecid = Long.parseLong(ecOne.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            java.util.Map<String, String> f = new java.util.LinkedHashMap<>();
            for (String k : new String[]{"scope", "severity", "title", "detail",
                    "strategy", "maxAttempts", "params", "enabled"}) {
                String v = jsonField(body, k);
                if (v != null) {
                    f.put(k, v);
                }
            }
            if (f.isEmpty()) {
                s.send(ex, 400, err("没有可更新的字段"));
                return true;
            }
            s.query.updateErrorCode(ecid, f);
            s.audited("error_code.update", "error_code", ecid,
                    String.join(",", f.keySet()), null, ex);
            s.send(ex, 200, Jsons.obj("id", ecid, "updated", f.keySet()));
            return true;
        }
        if ("/api/errors".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.occurrences(intParam(ex, "limit", 20)));
            return true;
        }
        if ("/api/demo/run".equals(path) && "POST".equals(method)) {
            s.send(ex, 200, demoRun());
            return true;
        }

        return false;
    }


    private long createRun() throws SQLException {
        String runNo = "API-" + LocalDateTime.now().format(RUN_NO_FMT);
        String sql = """
                INSERT INTO run (tenant_id, run_no, run_type, mode, priority, status, created_at)
                VALUES (?, ?, 'workflow', 'normal', 5, 'running', now())
                RETURNING id
                """;
        try (Connection c = s.db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, TENANT);
            ps.setString(2, runNo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }


    // ---------------------------------------------------------------- 演示执行

    /**
     * 触发一次完整闭环：加载策略 → 执行 → 事件落库 → 错误留痕。
     *
     * <p>三个节点覆盖三种结局：换机成功 / 跳过 / 转人工。
     */
    private String demoRun() throws SQLException {
        JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(s.db, TENANT);
        List<RecoveryPolicy> policies = repo.loadAll();
        RecoveryDecider decider = new RecoveryDecider(new RecoveryPolicyResolver(policies));

        long runId = createRun();

        List<RecoverableNode> nodes = List.of(
                RecoverableNode.of(1, "Transfer", "TRANSFER", 200383L, List.of(200383L, 200387L)),
                RecoverableNode.of(2, "Scan", "SCAN", 200392L, List.of(200392L)),
                RecoverableNode.of(3, "Centrifuge", "CENTRIFUGE", 200401L, List.of(200401L)));

        SimulatedBus bus = new SimulatedBus()
                .dead(200383L, "TRANSFER", "EC-08-0001", RecoveryScope.NODE)       // -> REASSIGN
                .dead(200392L, "SCAN", "EC-08-1005", RecoveryScope.NODE)           // -> SKIP
                .dead(200401L, "CENTRIFUGE", "EC-05-1006", RecoveryScope.COMMAND); // -> RETRY 耗尽 -> MANUAL

        RunProgress progress;
        int written;
        try (JdbcEventStore store = new JdbcEventStore(s.db, TENANT, runId, "api-demo", 50)) {
            RunStateMachine sm = new RunStateMachine(decider, store,
                    Sleeper.recording(new ArrayList<>()), java.time.Clock.systemUTC());
            progress = sm.executeAll(nodes, bus);
            store.flush();
            written = store.written();
        }

        // 节点状态落库 + run 收尾
        persistNodes(runId, progress, nodes);

        JdbcErrorOccurrenceStore errStore = new JdbcErrorOccurrenceStore(s.db, TENANT, repo);
        int recorded = 0;
        for (NodeProgress np : progress.all()) {
            if (!JdbcErrorOccurrenceStore.shouldRecord(np.status())) {
                continue;
            }
            String severity = switch (np.status()) {
                case SKIPPED -> "info";
                case ABORTED -> "error";
                default -> "warn";
            };
            errStore.record(new Occurrence(runId, np.nodeId(), np.currentResourceId(),
                    np.lastErrorCode() == null ? "EC-UNKNOWN" : np.lastErrorCode(),
                    RecoveryScope.NODE, np.attempt(), RecoveryStrategy.MANUAL,
                    np.status().name(), np.lastReason(),
                    Jsons.obj("attempt", np.attempt()), severity));
            recorded++;
        }

        return Jsons.obj(
                "runId", runId,
                "result", progress.summary(),
                "completed", progress.count(com.labos.kernel.engine.NodeStatus.COMPLETED),
                "skipped", progress.count(com.labos.kernel.engine.NodeStatus.SKIPPED),
                "manual", progress.count(com.labos.kernel.engine.NodeStatus.MANUAL),
                "eventsWritten", written,
                "occurrencesRecorded", recorded,
                "detailUrl", "/api/runs/" + runId);
    }


    private static Boolean jsonBool(String body, String key) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }


    /**
     * 把内存中的节点进度写回 run_node，并结算 run 状态。
     */
    private void persistNodes(long runId, RunProgress progress, List<RecoverableNode> defs)
            throws SQLException {
        java.util.Map<Long, RecoverableNode> byId = new java.util.HashMap<>();
        for (RecoverableNode n : defs) {
            byId.put(n.id(), n);
        }

        String nsql = """
                INSERT INTO run_node
                  (tenant_id, run_id, node_key, name, seq, node_type, capability_id,
                   resource_id, status, attempt, actual_start, actual_end, estimate_sec, input)
                VALUES (?, ?, ?, ?, ?, 'task', ?, ?, ?::node_status, ?, now(), now(), ?, ?::jsonb)
                """;
        try (Connection c = s.db.open();
             PreparedStatement ps = c.prepareStatement(nsql)) {
            for (NodeProgress np : progress.all()) {
                RecoverableNode def = byId.get(np.nodeId());
                ps.setLong(1, TENANT);
                ps.setLong(2, runId);
                ps.setString(3, def == null ? ("N" + np.nodeId()) : def.key());
                ps.setString(4, def == null ? ("node" + np.nodeId()) : def.name());
                ps.setInt(5, (int) np.nodeId());
                ps.setNull(6, java.sql.Types.BIGINT);
                if (np.currentResourceId() == null) {
                    ps.setNull(7, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(7, np.currentResourceId());
                }
                ps.setString(8, np.status().name().toLowerCase());
                ps.setInt(9, np.attempt());
                ps.setInt(10, 0);
                ps.setString(11, Jsons.obj(
                        "capabilityCode", def == null ? "" : def.capabilityCode(),
                        "failedResources", np.failedResourceIds().toString(),
                        "reason", np.lastReason()));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        boolean anyManual = progress.count(com.labos.kernel.engine.NodeStatus.MANUAL) > 0
                || progress.aborted();
        String status = anyManual ? "failed" : "completed";
        try (Connection c = s.db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE run SET status = ?::run_status, actual_start = now(), actual_end = now() "
                     + "WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }


    /** 按 id 查一条策略的作用域（PATCH 没带 scope 时用）。查不到返回 null。 */
    private String scopeOfPolicy(long policyId) {
        try (java.sql.Connection c = s.db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT scope FROM recovery_policy WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, policyId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 路由正则

    /** 错误码字典（含主策略）的编辑入口。 */
    private static final Pattern ERROR_CODE_ONE = Pattern.compile("^/api/error-codes/(\\d+)$");

    /** 某个码下的**一条**策略（按作用域唯一，见 QueryService.upsertPolicy）。 */
    private static final Pattern ERROR_CODE_POLICY =
            Pattern.compile("^/api/error-codes/(\\d+)/policies/(\\d+)$");

    private static final Pattern ERROR_CODE_POLICIES =
            Pattern.compile("^/api/error-codes/(\\d+)/policies$");

    private static final Pattern ALERT_ACT = Pattern.compile("^/api/alerts/([^/]+)/(ack|close|silence|reopen)$");

    private static final Pattern POLICY_ONE = Pattern.compile("^/api/policies/(\\d+)$");

    private static final Pattern USER_ONE = Pattern.compile("^/api/users/(\\d+)$");

    // ------------------------------------------------------------ 私有常量

    private static final DateTimeFormatter RUN_NO_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
}
