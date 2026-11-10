package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询服务：把库里的事实转成 JSON。
 *
 * <p>刻意不做 ORM——查询是只读的、SQL 是稳定的，直接用 JDBC 更透明。
 */
public class QueryService {

    private final DbConfig db;
    private final long tenantId;

    public QueryService(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /** 读侧高频单查询（QueryMapper.xml）。由 BeanConfiguration 注入。 */
    private com.labos.kernel.persistence.QueryMapper queryMapper;

    public void setQueryMapper(com.labos.kernel.persistence.QueryMapper m) {
        this.queryMapper = m;
    }

    /** 实验域清场/校验（ExperimentMapper.xml）。由 BeanConfiguration 注入。 */
    private com.labos.kernel.persistence.ExperimentMapper experimentMapper;

    public void setExperimentMapper(com.labos.kernel.persistence.ExperimentMapper m) {
        this.experimentMapper = m;
    }

    /** 健康检查：能连库才算活着。 */
    public String health() throws SQLException {
        java.util.Map<String, Object> row = queryMapper.health();
        return Jsons.obj(
                "status", "ok",
                "database", row.get("db"),
                "version", shortVersion(String.valueOf(row.get("ver"))),
                "url", db.url(),
                "time", OffsetDateTime.now().toString());
    }

    public String policies() throws SQLException {
        String sql = """
                SELECT p.id, COALESCE(ec.code,'*') AS code, p.scope, p.strategy::text AS strategy,
                       p.max_attempts, p.priority, p.enabled,
                       COALESCE(p.params->>'origin','manual') AS origin
                FROM recovery_policy p
                LEFT JOIN error_code ec ON ec.id = p.error_code_id
                WHERE p.tenant_id = ?
                ORDER BY p.priority, p.id
                """;
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "errorCode", rs.getString("code"),
                            "scope", rs.getString("scope"),
                            "strategy", rs.getString("strategy"),
                            "maxAttempts", rs.getInt("max_attempts"),
                            "priority", rs.getInt("priority"),
                            "enabled", rs.getBoolean("enabled"),
                            "origin", rs.getString("origin")));
                }
            }
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    public String recentRuns(int limit) throws SQLException {
        String sql = """
                SELECT r.id, r.run_no, r.run_type, r.status::text AS status, r.priority,
                       r.attendance,
                       r.actual_start, r.actual_end, r.created_at,
                       (SELECT count(*) FROM run_node n WHERE n.run_id = r.id AND n.loop_index = 0) AS node_count
                FROM run r
                WHERE r.tenant_id = ?
                ORDER BY r.id DESC
                LIMIT ?
                """;
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "runNo", rs.getString("run_no"),
                            "runType", rs.getString("run_type"),
                            "attendance", rs.getString("attendance"),
                            "status", rs.getString("status"),
                            "priority", rs.getInt("priority"),
                            "nodeCount", rs.getInt("node_count"),
                            "actualStart", str(rs, "actual_start"),
                            "actualEnd", str(rs, "actual_end"),
                            "createdAt", str(rs, "created_at")));
                }
            }
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    /** 单个 run 的详情：基础信息 + 节点 + 最近事件。 */
    public String runDetail(long runId) throws SQLException {
        try (Connection c = db.open()) {
            String base = null;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, run_no, run_type, pause_kind, pause_reason, pause_node_key, status::text AS status, priority,
                           actual_start, actual_end, created_at, input, attendance
                    FROM run WHERE tenant_id = ? AND id = ?
                    """)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        base = Jsons.obj(
                                "id", rs.getLong("id"),
                                "runNo", rs.getString("run_no"),
                                "runType", rs.getString("run_type"),
                            "attendance", rs.getString("attendance"),
                                "status", rs.getString("status"),
                                "priority", rs.getInt("priority"),
                                "pauseKind", rs.getString("pause_kind"),
                                "pauseReason", rs.getString("pause_reason"),
                                "pauseNodeKey", rs.getString("pause_node_key"),
                                "actualStart", str(rs, "actual_start"),
                                "actualEnd", str(rs, "actual_end"),
                                "createdAt", str(rs, "created_at"),
                                "input", raw(rs.getString("input")));
                    }
                }
            }
            if (base == null) {
                return null;
            }

            List<String> nodes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT rn.id, rn.node_key, rn.name, rn.status::text AS status,
                           rn.attempt, rn.resource_id,
                           rn.capability_id, rn.estimate_sec,
                           res.code AS resource_code,
                           level, parent_node_id, step_seq, loop_index,
                           input->>'reason' AS reason,
                           rn.error_code
                    FROM run_node rn
                    LEFT JOIN resource_node res ON res.id = rn.resource_id
                    WHERE rn.run_id = ?
                    ORDER BY COALESCE(rn.parent_node_id, rn.id), rn.loop_index, rn.level,
                             COALESCE(rn.step_seq, 0), rn.id
                    """)) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        nodes.add(Jsons.obj(
                                "id", rs.getLong("id"),
                                "nodeKey", rs.getString("node_key"),
                                "name", rs.getString("name"),
                                "status", rs.getString("status"),
                                "attempt", rs.getInt("attempt"),
                                "resourceId", rs.getObject("resource_id"),
                                "resourceCode", rs.getString("resource_code"),
                                "capabilityId", rs.getObject("capability_id"),
                                "estimateSec", rs.getInt("estimate_sec"),
                                "level", rs.getInt("level"),
                                "parentNodeId", rs.getObject("parent_node_id"),
                                "loopIndex", rs.getInt("loop_index"),
                                "stepSeq", rs.getObject("step_seq"),
                                "reason", rs.getString("reason"),
                                "errorCode", rs.getString("error_code")));
                    }
                }
            }

            List<String> events = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT occurred_at, event_type, payload
                    FROM run_event
                    WHERE aggregate_type='run' AND aggregate_id = ?
                    ORDER BY COALESCE((payload->>'seq')::int, 0) LIMIT 100
                    """)) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        events.add(Jsons.obj(
                                "at", str(rs, "occurred_at"),
                                "type", rs.getString("event_type"),
                                "payload", raw(rs.getString("payload"))));
                    }
                }
            }

            return Jsons.obj(
                    "run", raw(base),
                    "nodes", raw(Jsons.arr(nodes)),
                    "events", raw(Jsons.arr(events)));
        }
    }

    /**
     * Process Status View：正在跑的 run 卡在哪。
     *
     * <p>回答"为什么不动"——只有日志、没有这个视图时很难定位。
     */
    public String processStatusJson() throws SQLException {
        // 优先用内存态：执行期间 run_node 还没落库，查库看不到"卡在哪"
        List<com.labos.kernel.workflow.RunMonitor.Live> live =
                com.labos.kernel.workflow.RunMonitor.INSTANCE.running();
        if (!live.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (var l : live) {
                out.add(Jsons.obj(
                        "runId", l.runId,
                        "runNo", l.runNo,
                        "attendance", l.attendance,
                        "startedAt", l.startedAt.toString(),
                        "total", l.total,
                        "done", l.done,
                        "manual", l.manual,
                        "current", new Jsons.Raw(Jsons.obj(
                                "nodeKey", l.nodeKey,
                                "name", l.nodeName,
                                "status", l.nodeStatus,
                                "resourceId", l.resourceId,
                                "reason", l.reason,
                                "lastEvent", l.lastEvent))));
            }
            return Jsons.obj("running", out.size(),
                    "source", "memory",
                    "items", new Jsons.Raw(Jsons.arr(out)));
        }

        List<String> runs = new ArrayList<>();
        String sql = """
                SELECT r.id, r.run_no, r.attendance, r.actual_start,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0) AS total,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0
                                            AND n.status = 'completed') AS done,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0
                                            AND n.status = 'manual') AS manual,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0
                                            AND n.status = 'failed') AS failed
                FROM run r
                LEFT JOIN run_node n ON n.run_id = r.id
                WHERE r.tenant_id = ? AND r.status = 'running'
                GROUP BY r.id, r.run_no, r.attendance, r.actual_start
                ORDER BY r.actual_start DESC
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long runId = rs.getLong("id");
                    runs.add(Jsons.obj(
                            "runId", runId,
                            "runNo", rs.getString("run_no"),
                            "attendance", rs.getString("attendance"),
                            "startedAt", String.valueOf(rs.getObject("actual_start")),
                            "total", rs.getInt("total"),
                            "done", rs.getInt("done"),
                            "manual", rs.getInt("manual"),
                            "failed", rs.getInt("failed"),
                            "current", new Jsons.Raw(currentNodeJson(c, runId))));
                }
            }
        }
        return Jsons.obj("running", runs.size(),
                "items", new Jsons.Raw(Jsons.arr(runs)));
    }

    /** 当前卡住/正在执行的节点（取最早一个未完成的业务节点）。 */
    private String currentNodeJson(Connection c, long runId) throws SQLException {
        String sql = """
                SELECT n.id, n.node_key, n.name, n.status::text AS status, n.attempt,
                       n.resource_id, n.input->>'reason' AS reason,
                       (SELECT string_agg(c2.method, ' → ' ORDER BY c2.command_seq)
                          FROM run_command c2 WHERE c2.run_node_id = n.id) AS steps
                FROM run_node n
                WHERE n.run_id = ? AND n.level = 0 AND n.loop_index = 0
                  AND n.status NOT IN ('completed', 'skipped')
                ORDER BY n.id LIMIT 1
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "null";
                }
                return Jsons.obj(
                        "nodeId", rs.getLong("id"),
                        "nodeKey", rs.getString("node_key"),
                        "name", rs.getString("name"),
                        "status", rs.getString("status"),
                        "attempt", rs.getInt("attempt"),
                        "resourceId", rs.getObject("resource_id"),
                        "reason", rs.getString("reason"),
                        "steps", rs.getString("steps"));
            }
        }
    }

    /**
     * 拓扑：岛屿 → 设备。
     *
     * <p>层级来自 {@code resource_node}（kind=island / device），
     * 型号信息来自 {@code device}（经 device_instance 关联）。
     */
    public String topologyJson() throws SQLException {
        List<String> islands = new ArrayList<>();
        String sql = """
                SELECT i.id, i.code, i.name,
                       d.id      AS dev_id,
                       d.code    AS dev_code,
                       d.name    AS dev_name,
                       dev.model, dev.vendor, dev.icon,
                       c.code    AS capability,
                       di.id     AS instance_id,
                       di.ip,
                       dr.endpoint AS driver_endpoint,
                       dr.code     AS driver_code,
                       (d.attr->>'pos_x')::numeric AS pos_x,
                       (d.attr->>'pos_y')::numeric AS pos_y,
                       COALESCE(ds.online, false)   AS online,
                       COALESCE(ds.status, 'idle')  AS dev_status,
                       ds.current_method,
                       ds.has_plate
                FROM resource_node i
                LEFT JOIN resource_node d
                       ON d.parent_id = i.id AND d.kind = 'device'
                LEFT JOIN device_instance di
                       ON di.resource_id = d.id AND di.deleted_at IS NULL
                LEFT JOIN device dev ON dev.id = di.device_id
                LEFT JOIN capability c ON c.id = dev.capability_id
                LEFT JOIN driver dr ON dr.id = di.driver_id AND dr.deleted_at IS NULL
                LEFT JOIN device_state ds
                       ON ds.resource_id = d.id AND ds.tenant_id = i.tenant_id
                WHERE i.tenant_id = ? AND i.kind = 'island'
                ORDER BY i.code, d.code
                """;

        Map<Long, String[]> head = new java.util.LinkedHashMap<>();
        Map<Long, List<String>> devices = new java.util.LinkedHashMap<>();

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long iid = rs.getLong("id");
                    head.putIfAbsent(iid, new String[]{
                            rs.getString("code"), rs.getString("name")});
                    devices.computeIfAbsent(iid, k -> new ArrayList<>());
                    // instance_id 为空说明实例已软删（或从未建过），拓扑里不显示
                    if (rs.getObject("dev_id") != null && rs.getObject("instance_id") != null) {
                        devices.get(iid).add(Jsons.obj(
                                "resourceId", rs.getLong("dev_id"),
                                "code", rs.getString("dev_code"),
                                "name", rs.getString("dev_name"),
                                "model", rs.getString("model"),
                                "vendor", rs.getString("vendor"),
                                "capability", rs.getString("capability"),
                                // 设备示意图（/devices/xxx.svg）。岛上卡片和编排画布都用它，
                                // 没有就由前端退回能力色块
                                "icon", rs.getString("icon"),
                                "instanceId", rs.getObject("instance_id"),
                                "ip", rs.getString("ip"),
                                "driverEndpoint", rs.getString("driver_endpoint"),
                                "driverCode", rs.getString("driver_code"),
                                "x", rs.getObject("pos_x"),
                                "y", rs.getObject("pos_y"),
                                "online", rs.getBoolean("online"),
                                "status", rs.getString("dev_status"),
                                "currentMethod", rs.getString("current_method"),
                                "hasPlate", rs.getBoolean("has_plate")));
                    }
                }
            }
        }

        for (Map.Entry<Long, String[]> e : head.entrySet()) {
            List<String> devs = devices.getOrDefault(e.getKey(), List.of());
            islands.add(Jsons.obj(
                    "id", e.getKey(),
                    "code", e.getValue()[0],
                    "name", e.getValue()[1],
                    "deviceCount", devs.size(),
                    "devices", new Jsons.Raw(Jsons.arr(devs))));
        }
        return Jsons.obj("islands", new Jsons.Raw(Jsons.arr(islands)));
    }

    /** 实验列表（650 个）。 */
    /**
     * 这条流程的**时间条**：每个任务节点预计什么时候开始、什么时候结束。
     *
     * <p>甘特的前置：甘特要画条，至少得先有"开始 + 时长"。
     * 这里按 `seq` 顺序串行模拟一遍，并考虑设备当前占用：
     * 节点开始时间 = max(上一步结束, 该设备空闲时刻)。
     *
     * <p>只说清现在能算准的部分：
     * <ul>
     *   <li>时长取 `workflow_node.estimate_sec`，为 0 时按 60 秒兜底并标注 {@code estimated=true}；</li>
     *   <li>不含换型/清洗/人工处置；并行分支按"都在同一条时间轴上串行"处理（保守）；</li>
     *   <li>设备绑定从图 XML 的 {@code resource="…"} 读（`workflow_node` 没有这一列）。</li>
     * </ul>
     */
    public String workflowTimelineJson(long workflowId) throws SQLException {
        // 设备空闲时刻（秒，从现在算）
        java.util.Map<Long, Long> freeAt = new java.util.HashMap<>();
        java.util.Map<Long, String> resCode = new java.util.HashMap<>();
        long now = 0;

        List<String> items = new ArrayList<>();
        long cursor = 0;
        // 估算链（每步耗时从哪来，按可靠性从高到低）：
        //   ① manual  —— 人在流程里填的 estimate_sec（最可信）
        //   ② history —— 这个方法的**历史 P50**（run_command 里有 11.6 万条实测）
        //   ③ device  —— 型号库 device_method.estimate_sec（随设备型号来）
        //   ④ default —— 60 秒兜底，标 estimated=true
        // 为什么不是"没数据就 60 秒"：历史样本足够的方法（Transfer 4.3 万条）用实测比猜准得多。
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     WITH nd AS (
                         SELECT node_key, name, estimate_sec, resource_id,
                                params->>'protocol_name' AS method,
                                COALESCE(seq, 2147483647) AS ord, id
                           FROM workflow_node
                          WHERE tenant_id = ? AND workflow_id = ? AND node_type = 'task'
                     ), hist AS (
                         SELECT cmd.method,
                                percentile_cont(0.5) WITHIN GROUP (
                                  ORDER BY EXTRACT(EPOCH FROM (cmd.finished_at - cmd.sent_at))) AS p50,
                                count(*) AS n
                           FROM run_command cmd
                          WHERE cmd.tenant_id = ?
                            AND cmd.method IN (SELECT method FROM nd WHERE method IS NOT NULL)
                            AND cmd.finished_at IS NOT NULL AND cmd.sent_at IS NOT NULL
                          GROUP BY cmd.method
                     )
                     SELECT nd.node_key, nd.name, nd.estimate_sec, nd.resource_id, nd.method,
                            hist.p50, hist.n,
                            (SELECT max(dm.estimate_sec) FROM device_method dm
                              WHERE dm.method = nd.method AND dm.estimate_sec > 0) AS dev_est
                       FROM nd LEFT JOIN hist ON hist.method = nd.method
                      ORDER BY nd.ord, nd.id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            ps.setLong(3, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("node_key");
                    long manual = rs.getInt("estimate_sec");
                    double p50 = rs.getDouble("p50");
                    boolean p50Null = rs.wasNull();
                    long histN = rs.getLong("n");
                    boolean histNNull = rs.wasNull();
                    long devEst = rs.getLong("dev_est");
                    boolean devNull = rs.wasNull();

                    long dur;
                    String source;
                    if (manual > 0) {
                        dur = manual;
                        source = "manual";
                    } else if (!p50Null && p50 > 0 && !histNNull && histN >= 20) {
                        // 样本 <20 不用：一两条的耗时不如型号库的默认值
                        dur = Math.max(1, Math.round(p50));
                        source = "history";
                    } else if (!devNull && devEst > 0) {
                        dur = devEst;
                        source = "device";
                    } else {
                        dur = 60;
                        source = "default";
                    }
                    boolean estimated = !"manual".equals(source);
                    long ridRaw = rs.getLong("resource_id");
                    // 0 是历史数据里的"没有设备"，别当成一台设备
                    Long rid = (rs.wasNull() || ridRaw == 0) ? null : ridRaw;
                    long resFree = 0;
                    if (rid != null) {
                        resFree = freeAt.computeIfAbsent(rid, r -> remainingOf(r));
                        if (!resCode.containsKey(rid)) {
                            String code = resourceCodeOf(rid);
                            // 存量流程里的资源 id 可能已经不在 resource_node 了（换过设备/清过池）：
                            // 显示 "设备#387" 比显示 "未绑定" 诚实 —— 它确实绑了，只是绑的东西没了
                            resCode.put(rid, code == null ? "设备#" + rid + "（已失效）" : code);
                        }
                    }
                    long start = Math.max(cursor, resFree);
                    long end = start + dur;
                    cursor = end;
                    if (rid != null) {
                        freeAt.put(rid, end);
                    }
                    items.add(Jsons.obj(
                            "nodeKey", key,
                            "name", rs.getString("name"),
                            "seq", items.size() + 1,
                            "resourceId", rid,
                            "resourceCode", rid == null ? null : resCode.get(rid),
                            "startSec", start,
                            "endSec", end,
                            "durationSec", dur,
                            "estimateSource", source,
                            "estimateSamples", histNNull ? 0 : histN,
                            "estimated", estimated));
                }
            }
        }

        return Jsons.obj("workflowId", workflowId, "totalSec", cursor,
                "nodes", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 从图 XML 里抠某个节点元素上的 resource="…"。 */
    private static Long resourceOf(String xml, String nodeKey) {
        if (xml == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[a-zA-Z0-9_:]+[^>]*\\bid=\"" + java.util.regex.Pattern.quote(nodeKey)
                        + "\"[^>]*>")
                .matcher(xml);
        if (!m.find()) {
            return null;
        }
        java.util.regex.Matcher r = java.util.regex.Pattern
                .compile("\\sresource=\"(\\d+)\"").matcher(m.group());
        return r.find() ? Long.parseLong(r.group(1)) : null;
    }

    /** 某个资源还要被占用多久（秒）；没人用就是 0。 */
    private long remainingOf(long resourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT GREATEST(0, COALESCE(n.estimate_sec, 0)
                               - COALESCE(EXTRACT(EPOCH FROM (now() - n.actual_start)), 0)) AS remain
                       FROM run_node n JOIN run r ON r.id = n.run_id
                      WHERE n.tenant_id = ? AND n.resource_id = ?
                        AND n.level = 0 AND n.status = 'running' AND r.status = 'running'
                      ORDER BY remain DESC LIMIT 1
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.round(rs.getDouble("remain")) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private String resourceCodeOf(long resourceId) {
        try {
            return queryMapper.selectResourceCode(tenantId, resourceId);
        } catch (RuntimeException e) {
            // 原实现同样吞异常返回 null：资源码只用于展示，不阻断主流程。
            return null;
        }
    }

    /**
     * 这条流程现在跑，大概要等多久（秒）。
     *
     * <p>算法：取流程里所有任务节点用到的资源，看每个资源**当前**有没有别人的
     * `run_node` 在跑（`status='running'`），把「预计耗时 - 已跑时长」取最大值。
     * 再加**排队人数**（同资源上 queued 的单数）作为参考。
     *
     * <p>为什么要：点执行之后没有任何反馈，用户不知道是"马上跑"还是"排在 3 个实验后面"。
     * 甘特之前先把"还要等多久"这个数算出来，后面画时间轴才有数据。
     *
     * <p>局限（别当承诺）：按"当前占用者跑完"估算，不含换型/清洗/人工处置时间；
     * 资源被暂停的单占着时，估的是"它继续跑完"而不是"等人处理"。
     */
    public String workflowEtaJson(long workflowId) throws SQLException {
        // ① 这条流程要用哪些资源 —— 直接查 workflow_node.resource_id。
        // 这两列是 M9 前置债补的：改动前只能解析图 XML，ETA / 时间条 / 绑定面板各写了一遍正则。
        java.util.Set<Long> resources = new java.util.LinkedHashSet<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT DISTINCT resource_id FROM workflow_node
                      WHERE tenant_id = ? AND workflow_id = ?
                        AND node_type = 'task' AND resource_id IS NOT NULL
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    resources.add(rs.getLong(1));
                }
            }
        }
        if (resources.isEmpty()) {
            return Jsons.obj("workflowId", workflowId, "waitSec", 0, "known", false,
                    "detail", "这条流程没有绑定设备资源，不会排队");
        }

        long maxWait = 0;
        List<String> busy = new ArrayList<>();
        for (Long rid : resources) {
            String sql = """
                    SELECT n.id, n.node_key, n.run_id, n.estimate_sec, n.actual_start,
                           r.run_no, res.code AS res_code,
                           GREATEST(0, COALESCE(n.estimate_sec, 0)
                                     - COALESCE(EXTRACT(EPOCH FROM (now() - n.actual_start)), 0)) AS remain
                      FROM run_node n
                      JOIN run r ON r.id = n.run_id
                      LEFT JOIN resource_node res ON res.id = n.resource_id
                     WHERE n.tenant_id = ? AND n.resource_id = ?
                       AND n.level = 0 AND n.status = 'running'
                       AND r.status = 'running'
                     ORDER BY remain DESC LIMIT 1
                    """;
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, rid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long remain = Math.round(rs.getDouble("remain"));
                        maxWait = Math.max(maxWait, remain);
                        busy.add(Jsons.obj(
                                "resourceId", rid,
                                "resourceCode", rs.getString("res_code"),
                                "holderRunNo", rs.getString("run_no"),
                                "holderRunId", rs.getLong("run_id"),
                                "remainSec", remain));
                    }
                }
            }
        }
        return Jsons.obj("workflowId", workflowId, "waitSec", maxWait,
                "known", true, "resources", resources.size(),
                "busy", new Jsons.Raw(Jsons.arr(busy)));
    }

    /**
     * 某条流程的**执行记录**（最近 N 次）。
     *
     * <p>为什么要：跑完的单子在页面上没有入口能翻到 —— 实时看板只列 running，
     * 暂停队列只列 paused，甘特要手动在下拉里找 run id。
     * "这条流程跑过几次、每次结果如何"是最基本的追溯需求。
     */
    public String workflowRunsJson(long workflowId, int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT r.id, r.run_no, r.status::text AS status,
                       r.actual_start, r.actual_end,
                       EXTRACT(EPOCH FROM (r.actual_end - r.actual_start)) AS dur,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0) AS total,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0
                                            AND n.status = 'completed') AS done,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0
                                            AND n.status = 'manual') AS manual,
                       count(n.id) FILTER (WHERE n.level = 0 AND n.loop_index = 0
                                            AND n.status = 'failed') AS failed
                FROM run r
                LEFT JOIN run_node n ON n.run_id = r.id
                WHERE r.tenant_id = ? AND r.workflow_id = ?
                GROUP BY r.id
                ORDER BY r.id DESC
                LIMIT ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            ps.setInt(3, Math.max(1, Math.min(limit, 100)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "runNo", rs.getString("run_no"),
                            "status", rs.getString("status"),
                            "startedAt", str(rs, "actual_start"),
                            "finishedAt", str(rs, "actual_end"),
                            "durationSec", rs.getObject("dur") == null
                                    ? null : rs.getBigDecimal("dur").intValue(),
                            "total", rs.getInt("total"),
                            "done", rs.getInt("done"),
                            "manual", rs.getInt("manual"),
                            "failed", rs.getInt("failed")));
                }
            }
        }
        return Jsons.obj("workflowId", workflowId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    public String experimentsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        // 列表页要能一眼看出：哪个岛、什么内容、第几版、编了几条流程
        String sql = """
                SELECT e.id, e.experiment_no, e.name, e.status, e.version, e.priority,
                       e.planned_at, e.started_at, e.finished_at, e.remark,
                       e.workflow_id, w.name AS workflow_name,
                       isl.code AS island_code, isl.name AS island_name,
                       (SELECT string_agg(rn.name, '、' ORDER BY rn.name)
                          FROM experiment_workcell ew
                          JOIN resource_node rn ON rn.id = ew.workcell_id
                         WHERE ew.tenant_id = e.tenant_id
                           AND ew.experiment_id = e.id) AS workcell_names,
                       (SELECT count(*) FROM experiment_process p
                         WHERE p.experiment_id = e.id) AS flow_count
                FROM experiment e
                LEFT JOIN workflow w ON w.id = e.workflow_id
                LEFT JOIN resource_node isl ON isl.id = e.island_id
                WHERE e.tenant_id = ?
                ORDER BY e.id DESC
                LIMIT ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "experimentNo", rs.getString("experiment_no"),
                            "name", rs.getString("name"),
                            "status", rs.getString("status"),
                            "plannedAt", str(rs, "planned_at"),
                            "startedAt", str(rs, "started_at"),
                            "finishedAt", str(rs, "finished_at"),
                            "workflowId", rs.getObject("workflow_id"),
                            "workflowName", rs.getString("workflow_name"),
                            "version", rs.getInt("version"),
                            "priority", rs.getInt("priority"),
                            "remark", rs.getString("remark"),
                            "islandCode", rs.getString("island_code"),
                            "islandName", rs.getString("island_name"),
                            "workcellNames", rs.getString("workcell_names"),
                            "flowCount", rs.getInt("flow_count")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 某台设备（resource_node.id）支持的方法。 */
    public String deviceMethodsJson(long resourceId) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT dm.method, dm.name, c.code AS capability,
                       dm.estimate_sec, dm.timeout_sec, dm.interruptible,
                       dm.param_schema->'params' AS params
                FROM device_instance di
                JOIN device_method dm ON dm.device_id = di.device_id
                LEFT JOIN capability c ON c.id = dm.capability_id
                WHERE di.resource_id = ?
                ORDER BY dm.method
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // 参数定义原样透出：{seq,name,desc,type,default,required,control_type,unit,range}
                    String params = rs.getString("params");
                    items.add(Jsons.obj(
                            "method", rs.getString("method"),
                            "name", rs.getString("name"),
                            "capability", rs.getString("capability"),
                            "estimateSec", rs.getObject("estimate_sec"),
                            "timeoutSec", rs.getObject("timeout_sec"),
                            // 可中断标志：现场确认清单后置 true，暂停才会真的停掉这一步
                            "interruptible", rs.getBoolean("interruptible"),
                            "params", params == null ? new Jsons.Raw("[]")
                                    : new Jsons.Raw(params)));
                }
            }
        }
        return Jsons.obj("resourceId", resourceId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 时长模型（命令 → p50/p90），甘特预测与仿真共用。 */
    public String durationsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT method, p50_ms, p90_ms, min_ms, max_ms, sample_count
                FROM duration_model WHERE tenant_id = ?
                ORDER BY sample_count DESC LIMIT ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 300)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "method", rs.getString("method"),
                            "p50Ms", rs.getInt("p50_ms"),
                            "p90Ms", rs.getInt("p90_ms"),
                            "minMs", rs.getInt("min_ms"),
                            "maxMs", rs.getInt("max_ms"),
                            "sampleCount", rs.getInt("sample_count")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 甘特数据：一个 run 的节点/步骤时间轴。
     *
     * <p>返回相对毫秒（相对 run 开始），前端不用处理时区。
     */
    public String timelineJson(long runId) throws SQLException {
        java.sql.Timestamp runStart = queryMapper.selectRunActualStart(tenantId, runId);
        String sql = """
                SELECT id, node_key, name, status::text AS status, level,
                       parent_node_id, step_seq, resource_id, loop_index,
                       actual_start, actual_end,
                       EXTRACT(EPOCH FROM (actual_end - actual_start)) * 1000 AS dur_ms
                FROM run_node
                WHERE run_id = ? AND actual_start IS NOT NULL
                ORDER BY COALESCE(parent_node_id, id), level, COALESCE(step_seq, 0), id
                """;

        try (Connection c = db.open()) {
            java.time.Instant start = runStart == null ? null : runStart.toInstant();
            if (start == null) {
                return Jsons.obj("runId", runId, "items", new Jsons.Raw(Jsons.arr(List.of())));
            }

            List<String> items = new ArrayList<>();
            long totalMs = 0;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getObject("actual_start") == null) {
                            continue;
                        }
                        java.time.Instant st = rs.getTimestamp("actual_start").toInstant();
                        java.time.Instant en = rs.getObject("actual_end") == null
                                ? null : rs.getTimestamp("actual_end").toInstant();
                        long offsetMs = java.time.Duration.between(start, st).toMillis();
                        double raw = rs.getObject("dur_ms") == null ? 0d : rs.getDouble("dur_ms");
                        long durMs = (long) Math.max(0d, raw);
                        totalMs = Math.max(totalMs, offsetMs + durMs);
                        items.add(Jsons.obj(
                                "id", rs.getLong("id"),
                                "nodeKey", rs.getString("node_key"),
                                "name", rs.getString("name"),
                                "status", rs.getString("status"),
                                "level", rs.getInt("level"),
                                "parentNodeId", rs.getObject("parent_node_id"),
                                "loopIndex", rs.getInt("loop_index"),
                                "stepSeq", rs.getObject("step_seq"),
                                "resourceId", rs.getObject("resource_id"),
                                "offsetMs", offsetMs,
                                "durationMs", durMs));
                    }
                }
            }
            return Jsons.obj(
                    "runId", runId,
                    "startedAt", start.toString(),
                    "totalMs", totalMs,
                    "items", new Jsons.Raw(Jsons.arr(items)));
        }
    }

    /** 实验编排：该实验由哪些流程按什么顺序组成。 */
    /**
     * 设置某一步的循环次数。
     *
     * @param loopCount null/空 = 不循环；数字或 {@code ${var}} 表示次数
     */
    public int setLoopCount(long experimentId, int seq, String loopCount) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE experiment_process SET loop_count = ?
                     WHERE tenant_id = ? AND experiment_id = ? AND seq = ?
                     """)) {
            ps.setString(1, loopCount == null || loopCount.isBlank() ? null : loopCount.trim());
            ps.setLong(2, tenantId);
            ps.setLong(3, experimentId);
            ps.setInt(4, seq);
            return ps.executeUpdate();
        }
    }

    /** 实验执行历史（最近 20 次）。 */
    /**
     * 一次实验执行的**逐步**明细：哪些跑了、哪些没跑。
     *
     * <p>为什么不能只查 {@code experiment_run_step}：那张表是**步骤开始时**才插行，
     * 前面一步失败/暂停后，后面的步骤**根本没行** —— 只查表会让人以为"实验只有 1 步"。
     * 所以这里按 {@code experiment_process × loop_count} 展开出"计划步骤"，
     * 再和实际执行行做左连接，没行的一律标成 {@code pending}（未执行）。
     */
    /**
     * 一次执行的**流程图**：BPMN XML + 每个节点的实际状态。
     *
     * <p>为什么要这个：运行详情只有一张表，看不出"图上有哪几步、卡在哪一步"。
     * 这里把 {@code workflow.graph.xlm} 和 {@code run_node} 合到一起，
     * 前端用 bpmn-js 渲染后给节点染色。
     *
     * <p>两个细节：
     * <ul>
     *   <li>{@code node_key} 就是 BPMN 元素 id，可以直接对上；</li>
     *   <li>外壳建的是 {@code pending}，执行到哪个节点就改成 {@code running}、跑完改
     *       {@code completed}（见 {@code GraphRunner.withNodeStateRecorder}），
     *       所以运行期间能直接读到「已完成 / 执行中 / 还没轮到」三段。残留的
     *       {@code running}（崩溃/中断）在非运行态统一折算成 {@code pending}。</li>
     * </ul>
     */
    public String runGraphJson(long runId) throws SQLException {
        java.util.Map<String, Object> runRow = queryMapper.selectRunWorkflow(tenantId, runId);
        if (runRow == null) {
            throw new IllegalArgumentException("执行不存在: " + runId);
        }
        long workflowId = ((Number) runRow.get("workflowId")).longValue();
        String runStatus = String.valueOf(runRow.get("status"));
        String xml = queryMapper.selectWorkflowXml(tenantId, workflowId);

        // 只有真的在跑，"running" 才是"执行中"；暂停/结束后的 running 都是没执行到
        boolean live = "running".equals(runStatus);

        // 计划节点（图上的 task）——没跑过的也要出现在结果里
        java.util.Map<String, String> planName = new java.util.LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT node_key, name FROM workflow_node
                      WHERE tenant_id = ? AND workflow_id = ? AND node_type = 'task'
                      ORDER BY id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    planName.put(rs.getString(1), rs.getString(2));
                }
            }
        }

        java.util.Map<String, String[]> actual = new java.util.HashMap<>();  // key → [status,name,err]
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT node_key, name, status::text AS status, error_code
                       FROM run_node
                      WHERE tenant_id = ? AND run_id = ? AND level = 0 AND loop_index = 0
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actual.put(rs.getString("node_key"), new String[]{
                            rs.getString("status"), rs.getString("name"),
                            rs.getString("error_code")});
                }
            }
        }

        List<String> nodes = new ArrayList<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(planName.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            String[] a = actual.get(key);
            String st = a == null ? "pending" : a[0];
            // 跑完的单子里残留 running = 压根没执行
            if (!live && "running".equals(st)) {
                st = "pending";
            }
            nodes.add(Jsons.obj(
                    "nodeKey", key,
                    "name", a == null ? planName.get(key) : a[1],
                    "status", st,
                    "errorCode", a == null ? null : a[2]));
        }

        return Jsons.obj("runId", runId, "workflowId", workflowId,
                "runStatus", runStatus,
                "xml", xml,
                "nodes", new Jsons.Raw(Jsons.arr(nodes)));
    }

    public String experimentRunStepsJson(long runId) throws SQLException {
        long expId;
        String runStatus;
        int expanded;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT experiment_id, status, expanded_steps FROM experiment_run
                      WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("实验执行不存在: " + runId);
                }
                expId = rs.getLong(1);
                runStatus = rs.getString(2);
                expanded = rs.getInt(3);
            }
        }

        // 计划：每条流程 × 循环次数
        java.util.List<long[]> plan = new ArrayList<>();          // [seq, loop]
        java.util.Map<String, String> wfName = new java.util.HashMap<>();
        java.util.Map<Integer, Long> wfId = new java.util.HashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT p.seq, p.workflow_id, p.loop_count, w.name
                       FROM experiment_process p LEFT JOIN workflow w ON w.id = p.workflow_id
                      WHERE p.tenant_id = ? AND p.experiment_id = ?
                      ORDER BY p.seq
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, expId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int seq = rs.getInt("seq");
                    long wf = rs.getLong("workflow_id");
                    wfId.put(seq, wf);
                    wfName.put(seq + ":" + wf, rs.getString("name"));
                    int times = 1;
                    String loop = rs.getString("loop_count");
                    if (loop != null && loop.trim().matches("\\d+")) {
                        times = Math.max(1, Integer.parseInt(loop.trim()));
                    }
                    for (int i = 0; i < times; i++) {
                        plan.add(new long[]{seq, i});
                    }
                }
            }
        }

        // 实际执行行
        java.util.Map<String, String[]> done = new java.util.HashMap<>();   // "seq:loop" → [status,runId,err]
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT seq, loop_index, status, run_id, error
                       FROM experiment_run_step
                      WHERE tenant_id = ? AND experiment_run_id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    done.put(rs.getInt(1) + ":" + rs.getInt(2), new String[]{
                            rs.getString(3),
                            rs.getObject(4) == null ? null : String.valueOf(rs.getLong(4)),
                            rs.getString(5)});
                }
            }
        }

        List<String> items = new ArrayList<>();
        int okCnt = 0;
        int badCnt = 0;
        int pendingCnt = 0;
        for (long[] p : plan) {
            int seq = (int) p[0];
            int loop = (int) p[1];
            String[] d = done.get(seq + ":" + loop);
            String st = d == null ? "pending" : d[0];
            if ("completed".equals(st)) {
                okCnt++;
            } else if ("failed".equals(st)) {
                badCnt++;
            } else if ("pending".equals(st)) {
                pendingCnt++;
            }
            items.add(Jsons.obj(
                    "seq", seq,
                    "loopIndex", loop,
                    "workflowId", wfId.get(seq),
                    "workflowName", wfName.get(seq + ":" + wfId.get(seq)),
                    "status", st,
                    "runId", d == null || d[1] == null ? null : Long.valueOf(d[1]),
                    "error", d == null ? null : d[2]));
        }

        return Jsons.obj("runId", runId, "status", runStatus,
                "expandedSteps", expanded,
                "completed", okCnt, "failed", badCnt, "pending", pendingCnt,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    public String experimentRunsJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT r.id, r.status, r.control_state, r.expanded_steps, r.current_seq,
                       r.started_at, r.finished_at, r.error,
                       r.pause_kind, r.pause_reason, r.stop_requested,
                       EXTRACT(EPOCH FROM (r.finished_at - r.started_at)) AS dur_sec
                FROM experiment_run r
                WHERE r.tenant_id = ? AND r.experiment_id = ?
                ORDER BY r.id DESC LIMIT 20
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object dur = rs.getObject("dur_sec");
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "status", rs.getString("status"),
                            "expandedSteps", rs.getInt("expanded_steps"),
                            "currentSeq", rs.getInt("current_seq"),
                            "startedAt", str(rs, "started_at"),
                            "finishedAt", str(rs, "finished_at"),
                            "durationSec", dur == null ? null
                                    : Math.round(((Number) dur).doubleValue()),
                            "pauseKind", rs.getString("pause_kind"),
                            "pauseReason", rs.getString("pause_reason"),
                            "controlState", rs.getString("control_state"),
                            "stopRequested", rs.getBoolean("stop_requested"),
                            "error", rs.getString("error")));
                }
            }
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 实验变量列表。 */
    public String experimentVariablesJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT id, name, type, default_value, description
                FROM experiment_variable
                WHERE tenant_id = ? AND experiment_id = ?
                ORDER BY id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "name", rs.getString("name"),
                            "type", rs.getString("type"),
                            "defaultValue", rs.getString("default_value"),
                            "description", rs.getString("description")));
                }
            }
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 整体替换实验变量（先删后插）。
     *
     * <p>变量数量少（通常 <10），整体替换比逐条 diff 简单可靠，
     * 也不会出现"删了一半"的中间态——全程一个事务。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int saveExperimentVariables(long experimentId, List<String[]> vars) throws SQLException {
        String del = "DELETE FROM experiment_variable WHERE tenant_id = ? AND experiment_id = ?";
        String ins = """
                INSERT INTO experiment_variable
                  (tenant_id, experiment_id, name, type, default_value, description)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = db.open()) {
            try {
                try (PreparedStatement ps = c.prepareStatement(del)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, experimentId);
                    ps.executeUpdate();
                }
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement(ins)) {
                    for (String[] v : vars) {
                        // v = [name, type, defaultValue, description]
                        if (v[0] == null || v[0].isBlank()) {
                            continue;
                        }
                        ps.setLong(1, tenantId);
                        ps.setLong(2, experimentId);
                        ps.setString(3, v[0].trim());
                        ps.setString(4, v[1] == null || v[1].isBlank() ? "number" : v[1]);
                        ps.setString(5, v[2]);
                        ps.setString(6, v[3]);
                        ps.addBatch();
                        n++;
                    }
                    ps.executeBatch();
                }
                return n;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    public String experimentProcessesJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT p.id, p.workflow_id, p.seq, p.run_mode, p.run_after,
                       p.loop_count, p.params_override,
                       w.name AS workflow_name, w.code AS workflow_code,
                       (SELECT count(*) FROM workflow_node n
                         WHERE n.workflow_id = p.workflow_id AND n.node_type = 'task') AS node_count
                FROM experiment_process p
                JOIN workflow w ON w.id = p.workflow_id
                WHERE p.tenant_id = ? AND p.experiment_id = ?
                ORDER BY p.seq
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "workflowId", rs.getLong("workflow_id"),
                            "workflowName", rs.getString("workflow_name"),
                            "workflowCode", rs.getString("workflow_code"),
                            "seq", items.size() + 1,
                            "runMode", rs.getString("run_mode"),
                            "runAfter", rs.getInt("run_after"),
                            "nodeCount", rs.getInt("node_count")));
                }
            }
        }
        return Jsons.obj("experimentId", experimentId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 整体替换编排。
     *
     * <p>用「整体替换」而不是增量增删：编排是一个有序列表，
     * 前端拖完顺序后一次性提交最简单，也不会出现"删了一半"的中间状态。
     */
    /**
     * 人工插队：按给定顺序重排实验的优先级。
     *
     * <p>排程器（`ExperimentScheduler`）的排序是 **priority 从大到小**，其次按提交时间。
     * 所以这里把列表第一个给最高分：`priority = 100 - index`。
     *
     * <p>只允许重排**还没跑**的实验：已经开跑/跑完的单子插到前面没有意义，
     * 而且会让"历史记录里的顺序"和"现在的顺序"对不上。
     *
     * @return 实际更新的条数
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int reorderExperiments(List<Long> orderedIds) throws SQLException {
        if (orderedIds == null || orderedIds.isEmpty()) {
            throw new IllegalArgumentException("orderedIds 不能为空");
        }
        if (orderedIds.size() > 100) {
            throw new IllegalArgumentException("一次最多重排 100 个实验");
        }
        if (new java.util.HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new IllegalArgumentException("orderedIds 里有重复 id");
        }
        try (Connection c = db.open()) {
            try {
                // 先查状态：只允许 created / planned
                java.util.Map<Long, String> status = new java.util.LinkedHashMap<>();
                for (java.util.Map<String, Object> row
                        : queryMapper.selectExperimentStatuses(tenantId, orderedIds)) {
                    status.put(((Number) row.get("id")).longValue(),
                            String.valueOf(row.get("status")));
                }
                java.util.List<Long> unknown = orderedIds.stream()
                        .filter(id -> !status.containsKey(id)).toList();
                if (!unknown.isEmpty()) {
                    throw new IllegalArgumentException("实验不存在：" + unknown);
                }
                java.util.List<String> notAllowed = new java.util.ArrayList<>();
                for (Long id : orderedIds) {
                    String st = status.get(id);
                    if (st != null && !"created".equals(st) && !"planned".equals(st)) {
                        notAllowed.add(id + "(" + st + ")");
                    }
                }
                if (!notAllowed.isEmpty()) {
                    throw new IllegalArgumentException("只有还没跑的实验能调序，这些不是："
                            + notAllowed);
                }

                return queryMapper.reorderExperimentPriorities(tenantId, orderedIds);
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int replaceExperimentProcesses(long experimentId, List<Long> workflowIds)
            throws SQLException {
        CompiledGraph compiled = linearGraph(workflowIds);
        try (Connection c = db.open()) {
            try {
                validateExperimentWorkflowIds(c, compiled.workflowIds());
                int n = replaceExperimentProcesses(c, experimentId, compiled, true);
                return n;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    private void validateExperimentWorkflowIds(Connection c, List<Long> workflowIds)
            throws SQLException {
        if (workflowIds.size() > 50) {
            throw new IllegalArgumentException("单个实验最多编排 50 个流程");
        }
        // 先校验再删 —— 顺序很重要：
        //   ① 非法 id（≤0）多半是前端把"新建流程…"这类哨兵值混进来了
        //   ② 不存在的 id 会在 INSERT 时撞外键，报的是
        //      "Batch entry 1 ... violates foreign key constraint"，看不出是哪个 id
        // 更关键的是：**校验必须在 DELETE 之前**，否则删完才发现插不进去，编排就丢了。
        java.util.List<Long> bad = new java.util.ArrayList<>();
        for (Long id : workflowIds) {
            if (id == null || id <= 0) {
                bad.add(id);
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException("编排里有非法流程 id：" + bad
                    + "（可能把界面上的「新建流程…」当成流程加了进来）");
        }
        if (!workflowIds.isEmpty()) {
            java.util.Set<Long> exist = new java.util.HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM workflow WHERE tenant_id = ? AND id = ANY(?)")) {
                ps.setLong(1, tenantId);
                ps.setArray(2, c.createArrayOf("bigint", workflowIds.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        exist.add(rs.getLong(1));
                    }
                }
            }
            java.util.List<Long> missing = workflowIds.stream()
                    .filter(id -> !exist.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("流程不存在或已删除：" + missing);
            }
        }
    }

    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    private int replaceExperimentProcesses(Connection c, long experimentId,
                                           CompiledGraph compiled, boolean bumpVersion)
            throws SQLException {
        // 删 process 会级联删 experiment_process_edge（见 97_m95_process_edge.sql 的 FK），
        // 所以边不需要单独清 —— 整体替换天然一致。
experimentMapper.deleteExperimentProcesses(tenantId, experimentId);
        // nodeId -> 新生成的 process.id，用于把画布上的边翻译成 process 之间的边
        java.util.Map<String, Long> processIds = new java.util.HashMap<>();
        try (PreparedStatement ins = c.prepareStatement("""
                INSERT INTO experiment_process
                  (tenant_id, experiment_id, workflow_id, seq, run_mode)
                VALUES (?, ?, ?, ?, 'sequential')
                RETURNING id
                """)) {
            for (CompiledStep step : compiled.steps()) {
                ins.setLong(1, tenantId);
                ins.setLong(2, experimentId);
                ins.setLong(3, step.workflowId());
                ins.setInt(4, step.seq());
                try (ResultSet rs = ins.executeQuery()) {
                    rs.next();
                    processIds.put(step.nodeId(), rs.getLong(1));
                }
            }
        }
        try (PreparedStatement insEdge = c.prepareStatement("""
                INSERT INTO experiment_process_edge
                  (tenant_id, experiment_id, from_id, to_id)
                VALUES (?, ?, ?, ?)
                """)) {
            for (CompiledEdge edge : compiled.edges()) {
                Long from = processIds.get(edge.fromNodeId());
                Long to = processIds.get(edge.toNodeId());
                if (from == null || to == null) {
                    throw new IllegalArgumentException("编排边引用了不存在的节点："
                            + edge.fromNodeId() + " -> " + edge.toNodeId());
                }
                insEdge.setLong(1, tenantId);
                insEdge.setLong(2, experimentId);
                insEdge.setLong(3, from);
                insEdge.setLong(4, to);
                insEdge.addBatch();
            }
            insEdge.executeBatch();
        }
        if (bumpVersion) {
            bumpExperimentVersion(c, experimentId);
        }
        return compiled.steps().size();
    }

    /** 用户列表（不含密码）。 */
    public String usersJson() throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT id, username, display_name, role, enabled, last_login_at, created_at
                FROM sys_user WHERE tenant_id = ? ORDER BY id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "username", rs.getString("username"),
                            "displayName", rs.getString("display_name"),
                            "role", rs.getString("role"),
                            "enabled", rs.getBoolean("enabled"),
                            "lastLoginAt", str(rs, "last_login_at"),
                            "createdAt", str(rs, "created_at")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 新建用户。密码用 PBKDF2，调用方传明文。 */
    public int createUser(String username, String displayName, String role, String password)
            throws SQLException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        // 角色必须走白名单：账号表 role 是 varchar，直接落库能塞进 boss/root 这类字符串，
        // 建出来的账号能登录但所有权限判断都落空（表现像"建好了什么都干不了"）。
        String effectiveRole = role == null || role.isBlank() ? "viewer" : role;
        if (!RbacRules.validRole(effectiveRole)) {
            throw new IllegalArgumentException("角色不合法：" + role
                    + "（可选 " + String.join("/", RbacRules.ROLES) + "）");
        }
        String salt = AuthService.newSalt();
        String sql = """
                INSERT INTO sys_user (tenant_id, username, display_name, role, password_hash, salt)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, username.trim());
            ps.setString(3, displayName == null ? username.trim() : displayName);
            ps.setString(4, effectiveRole);
            ps.setString(5, AuthService.pbkdf2(password, salt));
            ps.setString(6, salt);
            return ps.executeUpdate();
        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
    }

    /**
     * 改用户：角色 / 启用 / 重置密码，三者都可为空（只改传了的那几项）。
     *
     * <p><b>三道闸门</b>：
     * <ol>
     *   <li>角色必须是 {@link RbacRules#ROLES} 白名单（未知角色直接 400，不落库）；</li>
     *   <li>不允许把"最后一个启用中的管理员"降级/停用 —— 否则系统再也没人能管账号
     *       （规则见 {@link RbacRules#lastAdminGuard}）；</li>
     *   <li>"读管理员数 → 写角色"必须在<b>同一事务 + 串行化锁</b>里完成，
     *       否则两个 admin 同时降级可能都读到 count=2 而同时通过（见下）。</li>
     * </ol>
     *
     * <p><b>为什么用 PG advisory lock 而不是行锁/表锁</b>：
     * <ul>
     *   <li>这是跨行、跨用户的约束检查（"全局还剩几个启用 admin"）：单行锁只能锁住
     *       目标这一行（或已存在的 admin 行），锁不住"并发停用另一个 admin"；
     *       锁整张 sys_user 又太重，会把普通登录/改密码也堵住；</li>
     *   <li>{@code pg_advisory_xact_lock(hashtext('labos:user-role'))} 用固定字符串取
     *       一把<b>事务级</b>互斥锁：所有走本方法的角色/启用写入天然串行，
     *       事务提交/回滚自动释放，不用手工 unlock，也不占业务表锁；</li>
     *   <li>单实例部署不涉及多节点分布式锁；将来多实例时 PG 仍是同一个库，
     *       这个 advisory lock 依然有效（它本来就是库级原语）。</li>
     * </ul>
     *
     * <p>纯改密码不触发"最后管理员"保护（保持行为不变）；用户不存在返回 0（保持）。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int updateUser(long id, String role, Boolean enabled, String password)
            throws SQLException {
        String newRole = role == null || role.isBlank() ? null : role;
        if (newRole != null && !RbacRules.validRole(newRole)) {
            throw new IllegalArgumentException("角色不合法：" + role
                    + "（可选 " + String.join("/", RbacRules.ROLES) + "）");
        }
        String salt = password == null || password.isBlank() ? null : AuthService.newSalt();
        String hash = salt == null ? null : AuthService.pbkdf2(password, salt);

        String sql = """
                UPDATE sys_user
                SET role = COALESCE(?, role),
                    enabled = COALESCE(?, enabled),
                    password_hash = COALESCE(?, password_hash),
                    salt = COALESCE(?, salt)
                WHERE tenant_id = ? AND id = ?
                """;
        try (Connection c = db.open()) {
            try {
                // 事务内第一句：串行化所有"用户角色/启用状态"写入。
                // 固定 key（不是 per-user）：保护判定看的是全局 admin 计数，
                // 锁必须覆盖"任意两个用户同时改角色"的交叉场景。
                queryMapper.lockUserRole();   // 只为拿到锁；返回值无意义

                int updated;
                // 纯改密码不触发"最后管理员"保护；只有改角色/改启用状态才需要判定。
                if (newRole != null || enabled != null) {
                    // FOR UPDATE：目标行在事务内被锁住，避免同一用户被并发改写
                    //（跨用户串行化由上面的 advisory lock 保证，行锁是第二道保险）。
                    UserRoleEnabled target = findUserRoleEnabled(c, id, true);
                    if (target == null) {
                        return 0;
                    }
                    if ("admin".equals(target.role()) && target.enabled()) {
                        String deny = RbacRules.lastAdminGuard(target.role(), target.enabled(),
                                newRole, enabled, countEnabledAdmins(c));
                        if (deny != null) {
                            throw new IllegalArgumentException(deny);
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, newRole);
                    ps.setObject(2, enabled);
                    ps.setString(3, hash);
                    ps.setString(4, salt);
                    ps.setLong(5, tenantId);
                    ps.setLong(6, id);
                    updated = ps.executeUpdate();
                }
                return updated;
            } catch (SQLException | RuntimeException e) {
                // 拒绝（如最后一个管理员）以及任何异常都整体回滚，不留半改状态。
throw e;
            }
        }
    }

    /**
     * 当前启用中的管理员数量（"最后一个管理员"保护的判据）。
     *
     * <p>口径：本租户、{@code role='admin'}、{@code enabled}。
     * 注意 {@code sys_user} 目前<b>没有软删列</b>（schema 与生产库都没有 deleted_at），
     * 所以这里不写 {@code deleted_at IS NULL}；将来若加软删列，必须在这里补条件，
     * 否则停用/删除后的管理员统计会不一致。
     */
    public int countEnabledAdmins() throws SQLException {
        try (Connection c = db.open()) {
            return countEnabledAdmins(c);
        }
    }

    private int countEnabledAdmins(Connection c) throws SQLException {
        return queryMapper.countEnabledAdmins(tenantId);
    }

    /** 用户当前角色/启用状态；不存在返回 null。 */
    public UserRoleEnabled findUserRoleEnabled(long id) throws SQLException {
        try (Connection c = db.open()) {
            return findUserRoleEnabled(c, id);
        }
    }

    private UserRoleEnabled findUserRoleEnabled(Connection c, long id) throws SQLException {
        return findUserRoleEnabled(c, id, false);
    }

    /**
     * 事务内读目标用户角色/启用状态。
     *
     * @param forUpdate true = {@code FOR UPDATE}（updateUser 的事务里用：
     *                  目标行在事务期间被锁住，防止同一用户被并发改写；
     *                  不存在时返回 null，PostgreSQL 不会报错）
     */
    private UserRoleEnabled findUserRoleEnabled(Connection c, long id, boolean forUpdate)
            throws SQLException {
        java.util.Map<String, Object> row =
                queryMapper.selectUserRoleEnabled(tenantId, id, forUpdate);
        return row == null ? null
                : new UserRoleEnabled(String.valueOf(row.get("role")),
                        Boolean.TRUE.equals(row.get("enabled")));
    }

    /** 账号的角色 + 启用状态（权限保护判定用，不含密码）。 */
    public record UserRoleEnabled(String role, boolean enabled) {
    }

    /** 审计日志：谁在什么时候做了什么。 */
    public String auditJson(int limit, String action) throws SQLException {
        String filter = (action == null || action.isBlank() || "all".equals(action))
                ? null : action;
        String sql = """
                SELECT occurred_at, username, action, entity_type, entity_id,
                       result, ip, before, after
                FROM audit_log
                WHERE tenant_id = ? AND (?::text IS NULL OR action = ?)
                ORDER BY occurred_at DESC
                LIMIT ?
                """;
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, filter);
            ps.setString(3, filter);
            ps.setInt(4, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "occurredAt", str(rs, "occurred_at"),
                            "username", rs.getString("username"),
                            "action", rs.getString("action"),
                            "entityType", rs.getString("entity_type"),
                            "entityId", rs.getObject("entity_id"),
                            "result", rs.getString("result"),
                            "ip", rs.getString("ip"),
                            "before", rs.getString("before") == null ? null
                                    : new Jsons.Raw(rs.getString("before")),
                            "after", rs.getString("after") == null ? null
                                    : new Jsons.Raw(rs.getString("after"))));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 保存设备在岛上的摆放位置。
     *
     * <p>坐标存在 {@code resource_node.attr.pos_x/pos_y}——布局是"资源"的属性，
     * 不是"实例"的，所以换驱动、换 IP 都不影响位置。
     */
    public int saveDevicePosition(long resourceId, double x, double y) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE resource_node
                     SET attr = COALESCE(attr, '{}'::jsonb)
                                || jsonb_build_object('pos_x', ?, 'pos_y', ?),
                         updated_at = now()
                     WHERE tenant_id = ? AND id = ? AND kind = 'device'
                     """)) {
            ps.setDouble(1, x);
            ps.setDouble(2, y);
            ps.setLong(3, tenantId);
            ps.setLong(4, resourceId);
            return ps.executeUpdate();
        }
    }

    /**
     * 把设备挂到某个岛（拖拽入岛）。
     *
     * <p>改的是资源树的 parent_id 与 path。设备实例与驱动关联不动——
     * "放哪个岛"是空间归属，"连哪个驱动"是通信归属，两件事。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int moveDeviceToIsland(long resourceId, long islandId) throws SQLException {
        // islandId <= 0 表示移出（放回设备池）
        if (islandId <= 0) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement("""
                         UPDATE resource_node SET parent_id = NULL, path = '/' || code,
                                                 updated_at = now()
                         WHERE tenant_id = ? AND id = ? AND kind = 'device'
                         """)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, resourceId);
                return ps.executeUpdate();
            }
        }
        try (Connection c = db.open()) {
            try {
                String islandPath = queryMapper.selectIslandPath(tenantId, islandId);
                if (islandPath == null) {
                    throw new IllegalArgumentException("岛屿不存在: " + islandId);
                }

                String code = queryMapper.selectNodeCode(tenantId, resourceId, "device");
                if (code == null) {
                    throw new IllegalArgumentException("设备不存在: " + resourceId);
                }

                int n;
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE resource_node
                        SET parent_id = ?, path = ?, updated_at = now()
                        WHERE tenant_id = ? AND id = ? AND kind = 'device'
                        """)) {
                    ps.setLong(1, islandId);
                    ps.setString(2, (islandPath == null ? "" : islandPath) + "/" + code);
                    ps.setLong(3, tenantId);
                    ps.setLong(4, resourceId);
                    n = ps.executeUpdate();
                }
                return n;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * 删岛。岛下还有设备时拒绝——避免设备变成孤儿（parent 指向已删节点）。
     */
    public int deleteIsland(long islandId) throws SQLException {
        try (Connection c = db.open()) {
            int n;
            int deviceCount = queryMapper.countDevicesUnderIsland(tenantId, islandId);
            if (deviceCount > 0) {
                throw new IllegalArgumentException(
                        "岛上还有 " + deviceCount + " 台设备，先把它们移出再删");
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM resource_node
                    WHERE tenant_id = ? AND id = ? AND kind = 'island'
                    """)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, islandId);
                n = ps.executeUpdate();
            }
            return n;
        }
    }

    /**
     * 设备池：**全量设备**（不只未分配的）。
     *
     * <p>用户要先在池子里看到所有设备，再决定拖到哪个岛。
     * 已经在岛上的会带 islandCode/islandId，池子里标出来；
     * 拖到另一个岛就是换个 parent。
     */
    /**
     * 复制一台设备（同型号的第二个实例）。
     *
     * 场景：一个岛上要放两台同型号孵箱。编号自动加后缀 -2/-3，
     * IP 留空由用户补——不能自动复制 IP，两台设备 IP 通常不同。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public long cloneDevice(long sourceResourceId, long islandId) throws SQLException {
        try (Connection c = db.open()) {
            try {
                String srcCode;
                String srcName;
                String islandPath;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT r.code, r.name, p.path
                        FROM resource_node r
                        LEFT JOIN resource_node p ON p.id = ?
                        WHERE r.tenant_id = ? AND r.id = ? AND r.kind = 'device'
                        """)) {
                    ps.setLong(1, islandId);
                    ps.setLong(2, tenantId);
                    ps.setLong(3, sourceResourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("源设备不存在: " + sourceResourceId);
                        }
                        srcCode = rs.getString(1);
                        srcName = rs.getString(2);
                        islandPath = rs.getString(3);
                        if (islandPath == null) {
                            throw new IllegalArgumentException("岛屿不存在: " + islandId);
                        }
                    }
                }

                String newCode = srcCode + "-2";
                int suffix = 2;
                while (existsDeviceCode(c, newCode)) {
                    suffix++;
                    newCode = srcCode + "-" + suffix;
                }

                long newResourceId;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO resource_node
                          (tenant_id, parent_id, code, name, kind, path, sort_order)
                        VALUES (?, ?, ?, ?, 'device', ?, 100)
                        RETURNING id
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, islandId);
                    ps.setString(3, newCode);
                    ps.setString(4, (srcName == null ? newCode : srcName) + " (" + suffix + ")");
                    ps.setString(5, islandPath + "/" + newCode);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        newResourceId = rs.getLong(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO device_instance
                          (tenant_id, code, name, device_id, resource_id, ip, driver_id)
                        SELECT ?, ?, ?, device_id, ?, NULL, driver_id
                        FROM device_instance
                        WHERE tenant_id = ? AND resource_id = ? AND deleted_at IS NULL
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setString(2, newCode);
                    ps.setString(3, (srcName == null ? newCode : srcName) + " (" + suffix + ")");
                    ps.setLong(4, newResourceId);
                    ps.setLong(5, tenantId);
                    ps.setLong(6, sourceResourceId);
                    ps.executeUpdate();
                }

                return newResourceId;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    private boolean existsDeviceCode(Connection c, String code) throws SQLException {
        // c 只用于表明「调用方已在事务内」；Mapper 会取到同一条 Spring 绑定连接。
        return queryMapper.existsDeviceCode(tenantId, code);
    }

    /**
     * 设备型号池：一个型号可以被拖多次，每次产生一台真实设备。
     *
     * <p>这是实验室的实际语义——"这个岛上要 3 台 Incubator_2C"，
     * 而不是"把某一台具体的孵箱搬过去"。
     */
    /** resource_node.id -> device_instance.id（探针/驱动认这个）。 */
    public Long instanceIdOf(long resourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id FROM device_instance
                     WHERE tenant_id = ? AND resource_id = ? AND deleted_at IS NULL
                     LIMIT 1
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * 从机械臂返回原文里提取坐标。
     *
     * <p>支持的字段名：x/y/z、pos_x/pos_y/pos_z、X/Y/Z。
     * 找到就返回数组 [x,y,z]（缺的为 null），找不到返回 null。
     *
     * <p>为什么用宽松的多字段匹配：不同厂商的驱动返回格式不一样，
     * 有的叫 `x`，有的叫 `pos_x`，有的嵌在 `data` 里。
     * 硬编码一种会导致"某家驱动永远读不到"。
     */
    public static Double[] extractXYZ(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Double x = pick(raw, "x", "pos_x", "X");
        Double y = pick(raw, "y", "pos_y", "Y");
        Double z = pick(raw, "z", "pos_z", "Z");
        return (x == null && y == null && z == null) ? null : new Double[]{x, y, z};
    }

    private static Double pick(String json, String... names) {
        for (String n : names) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(n)
                            + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                    .matcher(json);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        }
        return null;
    }

    /**
     * 更新点位（示教：把当前机械臂坐标记下来）。
     *
     * <p>只更新传了值的字段：示教时可能只改坐标，不改关联设备。
     */
    public int updateRobotPoint(long islandId, String code,
                                Double x, Double y, Double z,
                                Long deviceResourceId, String action) throws SQLException {
        return queryMapper.updateRobotPoint(tenantId, islandId, code,
                x, y, z, deviceResourceId, action);
    }

    /** 点位导出 CSV（现场示教器导出的表可以直接对照）。 */
    public String robotPointsCsv(long islandId) throws SQLException {
        StringBuilder sb = new StringBuilder("code,name,x,y,z,device_code,action\n");
        String sql = """
                SELECT p.code, p.name, p.x, p.y, p.z, p.action, r.code AS device_code
                FROM robot_point p
                LEFT JOIN resource_node r ON r.id = p.device_resource_id
                WHERE p.tenant_id = ? AND p.island_id = ?
                ORDER BY p.robot_resource_id, p.code
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, islandId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append(csv(rs.getString("code"))).append(',')
                      .append(csv(rs.getString("name"))).append(',')
                      .append(num(rs.getBigDecimal("x"))).append(',')
                      .append(num(rs.getBigDecimal("y"))).append(',')
                      .append(num(rs.getBigDecimal("z"))).append(',')
                      .append(csv(rs.getString("device_code"))).append(',')
                      .append(csv(rs.getString("action"))).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        return s.contains(",") || s.contains("\"")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String num(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /**
     * 点位导入 CSV（列：code,name,x,y,z,device_code,action）。
     *
     * <p>按 code 匹配：存在就更新坐标，不存在就插入。
     * device_code 会被解析成 resource_node.id。
     *
     * <p>表头行自动跳过（第一行含 "code"）。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int importRobotPointsCsv(long islandId, long robotResourceId, String body)
            throws SQLException {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("CSV 为空");
        }
        String[] lines = body.split("\\r?\\n");
        int ok = 0;
        try (Connection c = db.open()) {
            try {
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (i == 0 && line.toLowerCase().startsWith("code")) {
                        continue;   // 表头
                    }
                    String[] f = line.split(",", -1);
                    if (f.length < 1 || f[0].isBlank()) {
                        continue;
                    }
                    String code = unq(f[0]);
                    String name = f.length > 1 ? unq(f[1]) : null;
                    java.math.BigDecimal px = dec(f, 2);
                    java.math.BigDecimal py = dec(f, 3);
                    java.math.BigDecimal pz = dec(f, 4);
                    String devCode = f.length > 5 ? unq(f[5]) : null;
                    String action = f.length > 6 ? unq(f[6]) : "PickPlace";
                    Long devId = devCode == null || devCode.isBlank()
                            ? null : resourceIdByCode(c, devCode);

                    String sql = """
                            INSERT INTO robot_point
                              (tenant_id, island_id, robot_resource_id, code, name,
                               x, y, z, device_resource_id, action, synced_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                            ON CONFLICT (tenant_id, robot_resource_id, code) DO UPDATE
                            SET name = COALESCE(EXCLUDED.name, robot_point.name),
                                x = COALESCE(EXCLUDED.x, robot_point.x),
                                y = COALESCE(EXCLUDED.y, robot_point.y),
                                z = COALESCE(EXCLUDED.z, robot_point.z),
                                device_resource_id = COALESCE(EXCLUDED.device_resource_id,
                                                             robot_point.device_resource_id),
                                action = COALESCE(EXCLUDED.action, robot_point.action),
                                updated_at = now()
                            """;
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setLong(1, tenantId);
                        ps.setLong(2, islandId);
                        ps.setLong(3, robotResourceId);
                        ps.setString(4, code);
                        ps.setString(5, name);
                        ps.setBigDecimal(6, px);
                        ps.setBigDecimal(7, py);
                        ps.setBigDecimal(8, pz);
                        if (devId == null) {
                            ps.setNull(9, java.sql.Types.BIGINT);
                        } else {
                            ps.setLong(9, devId);
                        }
                        ps.setString(10, action);
                        ps.executeUpdate();
                        ok++;
                    }
                }
                return ok;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    private Long resourceIdByCode(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id FROM resource_node
                WHERE tenant_id = ? AND code = ? AND kind = 'device' LIMIT 1
                """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static String unq(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1).replace("\"\"", "\"");
        }
        return t;
    }

    private static java.math.BigDecimal dec(String[] f, int i) {
        if (f.length <= i || f[i] == null || f[i].isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(unq(f[i]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 某个岛的机械臂点位列表。 */
    public String robotPointsJson(long islandId) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT p.id, p.robot_resource_id, p.code, p.name,
                       p.x, p.y, p.z, p.action, p.device_resource_id,
                       r.code AS device_code, p.synced_at
                FROM robot_point p
                LEFT JOIN resource_node r ON r.id = p.device_resource_id
                WHERE p.tenant_id = ? AND p.island_id = ?
                ORDER BY p.robot_resource_id, p.code
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, islandId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "robotResourceId", rs.getLong("robot_resource_id"),
                            "code", rs.getString("code"),
                            "name", rs.getString("name"),
                            "x", rs.getObject("x"),
                            "y", rs.getObject("y"),
                            "z", rs.getObject("z"),
                            "action", rs.getString("action"),
                            "deviceResourceId", rs.getObject("device_resource_id"),
                            "deviceCode", rs.getString("device_code"),
                            "syncedAt", str(rs, "synced_at")));
                }
            }
        }
        return Jsons.obj("islandId", islandId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 一次性同步点位：把岛上的设备都建成"默认点位"。
     *
     * <p>为什么提供这个：现场先是手工导出机械臂点位表，再导入。
     * 但演示/首装时没人有那表，按设备自动生成一版能先跑起来，
     * 后续再逐个改坐标。
     *
     * <p>点位 code 用设备编号（`ID1775`）——`Transfer` 的 source/target 直接引用它。
     */
    public int syncRobotPoints(long islandId, long robotResourceId) throws SQLException {
        String sql = """
                INSERT INTO robot_point
                  (tenant_id, island_id, robot_resource_id, code, name,
                   device_resource_id, action, synced_at)
                SELECT ?, ?, ?, d.code, d.name, d.id, 'PickPlace', now()
                FROM resource_node d
                WHERE d.tenant_id = ? AND d.parent_id = ? AND d.kind = 'device'
                  AND d.id <> ?
                ON CONFLICT (tenant_id, robot_resource_id, code) DO UPDATE
                SET name = EXCLUDED.name,
                    device_resource_id = EXCLUDED.device_resource_id,
                    synced_at = now(), updated_at = now()
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, islandId);
            ps.setLong(3, robotResourceId);
            ps.setLong(4, tenantId);
            ps.setLong(5, islandId);
            ps.setLong(6, robotResourceId);
            return ps.executeUpdate();
        }
    }

    /** 岛屿动线（设备节点坐标 + 搬运箭头）。 */
    public String islandFlowJson(long islandId) throws SQLException {
        String sql = """
                SELECT graph FROM island_flow
                WHERE tenant_id = ? AND island_id = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, islandId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String g = rs.getString(1);
                    return g == null ? "{\"nodes\":[],\"edges\":[]}" : g;
                }
            }
        }
        return "{\"nodes\":[],\"edges\":[]}";
    }

    /** 保存岛屿动线（整体替换）。 */
    public int saveIslandFlow(long islandId, String graphJson) throws SQLException {
        if (graphJson == null || graphJson.isBlank()) {
            throw new IllegalArgumentException("动线不能为空");
        }
        String sql = """
                INSERT INTO island_flow (tenant_id, island_id, graph, updated_at)
                VALUES (?, ?, ?::jsonb, now())
                ON CONFLICT (island_id) DO UPDATE
                SET graph = EXCLUDED.graph, updated_at = now()
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, islandId);
            ps.setString(3, graphJson);
            return ps.executeUpdate();
        }
    }

    /** 实验编排图（nodes + edges）。 */
    public String experimentGraphJson(long experimentId) throws SQLException {
        String sql = """
                SELECT graph FROM experiment_graph
                WHERE tenant_id = ? AND experiment_id = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String g = rs.getString(1);
                    return g == null ? "{\"nodes\":[],\"edges\":[]}" : g;
                }
            }
        }
        return "{\"nodes\":[],\"edges\":[]}";
    }

    /** 保存实验编排图（整体替换），并同步编译成 experiment_process（含偏序边）。 */
    /** A 批：Spring 声明式事务。checked 异常也要回滚（默认只回滚 RuntimeException）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int saveExperimentGraph(long experimentId, String graphJson) throws SQLException {
        if (graphJson == null || graphJson.isBlank()) {
            throw new IllegalArgumentException("图不能为空");
        }
        CompiledGraph compiled = compileExperimentGraph(graphJson);
        String sql = """
                INSERT INTO experiment_graph (tenant_id, experiment_id, graph, updated_at)
                VALUES (?, ?, ?::jsonb, now())
                ON CONFLICT (experiment_id) DO UPDATE
                SET graph = EXCLUDED.graph, updated_at = now()
                """;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, experimentId);
                ps.setString(3, graphJson);
                ps.executeUpdate();
                validateExperimentWorkflowIds(c, compiled.workflowIds());
                replaceExperimentProcesses(c, experimentId, compiled, false);
                bumpExperimentVersion(c, experimentId);
                return compiled.steps().size();
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /** 搬运流程列表（岛屿视图"跑流程"用，按 id 降序）。 */
    public String choreographiesJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT w.id, w.code, w.name,
                       (SELECT count(*) FROM workflow_node n
                         WHERE n.workflow_id = w.id AND n.node_type = 'task') AS task_count
                FROM workflow w
                WHERE w.tenant_id = ? AND w.category = 'choreography'
                ORDER BY w.id DESC LIMIT ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "code", rs.getString("code"),
                            "name", rs.getString("name"),
                            "nodeCount", rs.getInt("task_count")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 错误码字典 + 它的**主恢复策略**（priority 最小的那条）。
     *
     * <p>作用域（scope）收口到库以后，这张表就是"出错之后怎么处理"的唯一真相；
     * 没有界面时改一个码要写三条 SQL，现场没人敢动。
     */
    /**
     * 新建一个实验。
     *
     * <p>为什么必须走代码而不是让人写 SQL：{@code experiment} + {@code experiment_process}
     * 是两张表，少写后者就会出现"实验建好了但没有任何流程、点执行什么也不跑"。
     * 之前测试用的 815 就是手工 SQL 建的，正是这个状态。
     *
     * @param workflowIds 可选：按顺序挂上的流程（写进 experiment_process.seq 1..N）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public String createExperiment(String name, String code, Long islandId,
                                   Integer priority, java.util.List<Long> workflowIds)
            throws SQLException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("实验名称不能为空");
        }
        // 编号：EXP-<yyyyMMddHHmmss>-<4 位随机>。撞了极少，但唯一索引会兜住。
        String no = (code == null || code.isBlank())
                ? "EXP-" + java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        + "-" + String.format("%04d",
                            java.util.concurrent.ThreadLocalRandom.current().nextInt(10000))
                : code.trim();

        try (Connection c = db.open()) {
            try {
                long id;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO experiment
                          (tenant_id, experiment_no, name, status, priority, island_id,
                           planned_at)
                        VALUES (?, ?, ?, 'created', ?, ?, now())
                        RETURNING id
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setString(2, no);
                    ps.setString(3, name.trim());
                    ps.setInt(4, priority == null ? 5 : priority);
                    if (islandId == null) {
                        ps.setNull(5, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(5, islandId);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                if (workflowIds != null) {
                    // 走统一入口：写 process 的同时生成线性链式的偏序边，
                    // 避免"新建的实验有流程、没有边"这种半成品状态。
                    replaceExperimentProcesses(c, id, linearGraph(workflowIds), false);
                }
                return Jsons.obj("id", id, "experimentNo", no,
                        "processes", workflowIds == null ? 0 : workflowIds.size());
            } catch (SQLException e) {
                throw e;
            }
        }
    }

    public String errorCodesJson(String severity, String scope, String keyword)
            throws SQLException {
        List<String> items = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.code, e.scope, e.severity::text AS severity,
                       e.source, e.title, e.detail,
                       p.strategy::text AS strategy, p.max_attempts, p.params::text AS params,
                       p.priority, p.enabled, p.scope AS policy_scope,
                       (SELECT count(*) FROM error_occurrence o WHERE o.error_code_id = e.id)
                           AS occurrences,
                       -- 这个码在**它自己的作用域**上有没有策略？
                       -- 没有 = 一旦真抛出来，只能落到通配策略，配置静默失效（M7）
                       EXISTS (SELECT 1 FROM recovery_policy rp2
                                WHERE rp2.error_code_id = e.id AND rp2.enabled
                                  AND (rp2.scope = e.scope OR rp2.scope IS NULL)) AS scope_covered,
                       -- 一个码可能有多条策略（**按作用域分**：同一码在 command 层
                       -- 该重发、在 node 层该转人工，两条都得留）。
                       -- 运行时按"谁匹配当前作用域"选，不是按顺序回退——
                       -- RecoveryPolicyResolver 是 findFirst，别把这里当 fallback 链。
                       (SELECT jsonb_agg(jsonb_build_object(
                                   'id', rp.id,
                                   'scope', rp.scope,
                                   'strategy', rp.strategy::text,
                                   'maxAttempts', rp.max_attempts,
                                   'params', rp.params,
                                   'priority', rp.priority,
                                   'enabled', rp.enabled)
                               ORDER BY rp.priority, rp.id)
                          FROM recovery_policy rp WHERE rp.error_code_id = e.id) AS policies
                FROM error_code e
                LEFT JOIN LATERAL (
                      SELECT * FROM recovery_policy rp
                       WHERE rp.error_code_id = e.id AND rp.enabled
                       ORDER BY rp.priority, rp.id LIMIT 1) p ON true
                WHERE e.tenant_id = ? AND e.deleted_at IS NULL
                """);
        boolean bySev = severity != null && !severity.isBlank();
        boolean byScope = scope != null && !scope.isBlank();
        boolean byKw = keyword != null && !keyword.isBlank();
        if (bySev) {
            sql.append(" AND e.severity::text = ?");
        }
        if (byScope) {
            sql.append(" AND e.scope = ?");
        }
        if (byKw) {
            sql.append(" AND (e.code ILIKE ? OR e.title ILIKE ?)");
        }
        sql.append(" ORDER BY e.code");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, tenantId);
            if (bySev) {
                ps.setString(i++, severity);
            }
            if (byScope) {
                ps.setString(i++, scope);
            }
            if (byKw) {
                ps.setString(i++, "%" + keyword + "%");
                ps.setString(i++, "%" + keyword + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "code", rs.getString("code"),
                            "scope", rs.getString("scope"),
                            "severity", rs.getString("severity"),
                            "source", rs.getString("source"),
                            "title", rs.getString("title"),
                            "detail", rs.getString("detail"),
                            "occurrences", rs.getInt("occurrences"),
                            "scopeCovered", rs.getBoolean("scope_covered"),
                            "strategy", rs.getString("strategy"),
                            "maxAttempts", rs.getObject("max_attempts"),
                            "params", rs.getString("params"),
                            "priority", rs.getObject("priority"),
                            "enabled", rs.getObject("enabled"),
                            "policyScope", rs.getString("policy_scope"),
                            "policies", new Jsons.Raw(rs.getString("policies") == null
                                    ? "[]" : rs.getString("policies"))));
                }
            }
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 改一个错误码：码本身的属性（scope/severity/title）+
     * 它的**主策略**（strategy/max_attempts/params/enabled）。
     *
     * <p>一次事务：码改了但策略没改，运行时就还是改动前的行为，排查会更难。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public boolean updateErrorCode(long id, Map<String, String> fields) throws SQLException {
        try (Connection c = db.open()) {
            try {
                // 只有确实传了字段才发 UPDATE（否则 <set> 会拼出空 SET）。
                boolean anyCodeField = List.of("scope", "severity", "title", "detail")
                        .stream().anyMatch(k -> fields.get(k) != null);
                if (anyCodeField) {
                    queryMapper.updateErrorCodeFields(tenantId, id,
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
                    queryMapper.updatePrimaryRecoveryPolicy(tenantId, id,
                            strategy, maxAttempts, params, enabled);
                }
                return true;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * 改一个节点的**方法绑定 + 方法参数**。
     *
     * <p>为什么需要这个接口：内置节点（`__setVar` / `__expr`）和普通任务参数，
     * 改动前只能改库 —— 设计器里拖出来的 task 没有 `protocolName`，而 bpmn-js 的 modeling
     * 对未声明的自定义属性支持不稳。这里**以库为准**改两处：
     * <ol>
     *   <li>{@code workflow_node.protocol_name} + {@code params.method_params}</li>
     *   <li>{@code workflow.graph->>'xml'} 里该元素的 {@code protocolName} 属性
     *       （只改这一个属性，坐标/排版一个字不动）</li>
     * </ol>
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public boolean updateNodeBinding(long workflowId, String nodeKey,
                                     String method, String methodParamsJson)
            throws SQLException {
        return updateNodeBinding(workflowId, nodeKey, method, methodParamsJson, null);
    }

    /**
     * 同 {@link #updateNodeBinding(long, String, String, String)}，额外绑定设备。
     *
     * @param resourceId 设备资源 id。**契约（P-10 定案，2026-11-09）**：
     *                   {@code null} = 不改动绑定；{@code 0}（或负数）= 显式解绑；
     *                   {@code > 0} = 绑定到该资源。
     *                   改动前 null 会写成 SQL NULL，而 XML 侧的
     *                   {@code patchNodeAttrs} 把 null 当"不改"—— 结果一张表说没绑、
     *                   图里还绑着，正是本方法注释里最怕的那种漂移。
     *
     * <p>为什么设备也要走这个方法：设备绑定只存在图 XML 的 {@code resource="…"} 属性里，
     * `workflow_node` 没有这一列（见 ROADMAP「表结构没表达流程怎么跑」那条债）。
     * 在设计器里选设备，本质就是改这个属性。
     */
    /** A 批：Spring 声明式事务。checked 异常也要回滚（默认只回滚 RuntimeException）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public boolean updateNodeBinding(long workflowId, String nodeKey,
                                     String method, String methodParamsJson,
                                     Long resourceId)
            throws SQLException {
        if (nodeKey == null || nodeKey.isBlank()) {
            throw new IllegalArgumentException("nodeKey 不能为空");
        }
        String m = method == null ? "" : method.trim();
        try (Connection c = db.open()) {
            try {
                // workflow_node 没有 protocol_name 列（方法从 XML 的 protocolName 来，
                // 设备任务另有 protocol_id）。所以这里只写 params：
                //   method_params → 传给执行器/内置节点
                //   builtin       → 记下"这是个内置节点"，方便列表和排查
                // resource_id 也跟着写：XML 是权威（bpmn-js 还得能打开），
                // 表是读侧的快路径 —— 两边必须同时改，否则又会漂。
                // null = 不改动：与 patchNodeAttrs 的 null 语义保持一致
                //（那边是 `if (resourceId != null)` 才动 XML）。
                // 只有显式给值才写库，避免"表清了、图里还绑着"。
                if (resourceId != null) {
                    try (PreparedStatement ps2 = c.prepareStatement("""
                            UPDATE workflow_node SET resource_id = ?, updated_at = now()
                             WHERE tenant_id = ? AND workflow_id = ? AND node_key = ?
                            """)) {
                        if (resourceId > 0) {
                            ps2.setLong(1, resourceId);
                        } else {
                            // 0 / 负数 = 显式解绑
                            ps2.setNull(1, java.sql.Types.BIGINT);
                        }
                        ps2.setLong(2, tenantId);
                        ps2.setLong(3, workflowId);
                        ps2.setString(4, nodeKey);
                        ps2.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE workflow_node
                           SET params = jsonb_set(
                                   CASE
                                       WHEN ?::text IS NULL THEN COALESCE(params, '{}'::jsonb)
                                       ELSE jsonb_set(
                                              jsonb_set(COALESCE(params, '{}'::jsonb),
                                                        '{method_params}', ?::jsonb, true),
                                              '{builtin}', to_jsonb(?::boolean), true)
                                   END,
                                   '{protocol_name}', to_jsonb(?::text), true),
                               updated_at = now()
                         WHERE tenant_id = ? AND workflow_id = ? AND node_key = ?
                        """)) {
                    boolean builtin = m.startsWith("__");
                    ps.setString(1, methodParamsJson);
                    ps.setString(2, methodParamsJson == null || methodParamsJson.isBlank()
                            ? "{}" : methodParamsJson);
                    ps.setBoolean(3, builtin);
                    // 执行器读的是 params.protocol_name，不是 XML 属性。
                    // 只改 XML 的话，节点设置保存后跑起来方法名是空的，能力变成 UNKNOWN。
                    ps.setString(4, m);
                    ps.setLong(5, tenantId);
                    ps.setLong(6, workflowId);
                    ps.setString(7, nodeKey);
                    ps.executeUpdate();
                }

                String xml = null;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT graph->>'xml' FROM workflow WHERE tenant_id = ? AND id = ?
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, workflowId);
                    try (ResultSet rs = ps.executeQuery()) {
                        xml = rs.next() ? rs.getString(1) : null;
                    }
                }
                if (xml != null && !xml.isBlank()) {
                    String patched = patchNodeAttrs(xml, nodeKey, m, resourceId);
                    if (!patched.equals(xml)) {
                        try (PreparedStatement ps = c.prepareStatement("""
                                UPDATE workflow
                                   SET graph = jsonb_set(graph, '{xml}', to_jsonb(?::text)),
                                       updated_at = now()
                                 WHERE tenant_id = ? AND id = ?
                                """)) {
                            ps.setString(1, patched);
                            ps.setLong(2, tenantId);
                            ps.setLong(3, workflowId);
                            ps.executeUpdate();
                        }
                    }
                }
                return true;
            } catch (SQLException e) {
                throw e;
            }
        }
    }

    /**
     * 读一个节点的绑定信息（设计器右侧面板用）。
     *
     * <p>方法名以 XML 的 protocolName 为准（执行引擎解析的就是它），
     * 参数以 workflow_node.params->'method_params' 为准。
     */
    public String nodeBindingJson(long workflowId, String nodeKey) throws SQLException {
        String xml = queryMapper.selectWorkflowXml(tenantId, workflowId);
        String method = null;
        if (xml != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<[a-zA-Z0-9_:]+[^>]*\\sid=\"" + java.util.regex.Pattern.quote(nodeKey)
                            + "\"[^>]*?\\sprotocolName=\"([^\"]*)\"")
                    .matcher(xml);
            if (m.find()) {
                method = m.group(1);
            }
        }
        // 设备绑定优先读表（M9 前置债）；表里为空再退回 XML（存量未回填时兜底）
        Long resourceId = null;
        if (resourceId == null && xml != null) {
            java.util.regex.Matcher em = java.util.regex.Pattern
                    .compile("<[a-zA-Z0-9_:]+[^>]*\\sid=\"" + java.util.regex.Pattern.quote(nodeKey)
                            + "\"[^>]*>")
                    .matcher(xml);
            if (em.find()) {
                java.util.regex.Matcher rm = java.util.regex.Pattern
                        .compile("\\sresource=\"(\\d+)\"").matcher(em.group());
                if (rm.find()) {
                    resourceId = Long.parseLong(rm.group(1));
                }
            }
        }
        String params = "{}";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT params->'method_params' AS mp, resource_id FROM workflow_node
                      WHERE tenant_id = ? AND workflow_id = ? AND node_key = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            ps.setString(3, nodeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    if (rs.getString("mp") != null) {
                        params = rs.getString("mp");
                    }
                    long rid = rs.getLong("resource_id");
                    if (!rs.wasNull()) {
                        resourceId = rid;
                    }
                }
            }
        }
        return Jsons.obj("workflowId", workflowId, "nodeKey", nodeKey,
                "method", method, "resourceId", resourceId,
                "params", new Jsons.Raw(params));
    }

    /** 在 XML 里把指定元素的 {@code protocolName} 换掉（没有就补上），其余原样。 */
    /**
     * 画布上显示的节点名。
     *
     * <p>设备方法直接用方法名（`Seal` / `GetStatus`）—— 现场看图的人认的是方法；
     * 内置节点给中文，否则图上会显示 `__setVar` 这种没人读得懂的字符串。
     */
    private static String labelOf(String method) {
        return switch (method) {
            case "__setVar" -> "变量";
            case "__expr" -> "表达式";
            case "__sleep" -> "延时";
            case "__wait" -> "等待";
            default -> method;
        };
    }

    private static String patchNodeAttrs(String xml, String nodeKey,
                                         String method, Long resourceId) {
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "<([a-zA-Z0-9_:]+)([^>]*?\\sid=\"" + java.util.regex.Pattern.quote(nodeKey)
                        + "\")([^>]*?)(/?)>");
        java.util.regex.Matcher mt = pat.matcher(xml);
        if (!mt.find()) {
            return xml;
        }
        String tag = mt.group(1);
        String all = mt.group(2) + mt.group(3);
        boolean selfClose = !mt.group(4).isEmpty();

        // 按属性名逐对解析再重建：直接字符串替换会留下双空格（"name="x"  protocolName=…"），
        // 也容易把值里的空格当分隔符。
        java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher am = java.util.regex.Pattern
                .compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*\"([^\"]*)\"")
                .matcher(all);
        while (am.find()) {
            attrs.put(am.group(1), am.group(2));
        }

        if (method != null) {
            String oldMethod = attrs.get("protocolName");
            String oldName = attrs.get("name");
            if (method.isBlank()) {
                attrs.remove("protocolName");
            } else {
                attrs.put("protocolName", method);
                // 节点名跟着方法名走 —— 但只在"用户没自己起过名"时改：
                //   名字为空 / 名字等于方法名 / 还是 bpmn-js 的占位名（Task 之类）
                // 否则会把用户写的"封膜第一步"覆盖掉。
                boolean placeholder = oldName == null || oldName.isBlank()
                        || oldName.equals(oldMethod)
                        || oldName.startsWith("Task") || oldName.startsWith("Activity")
                        || oldName.startsWith("任务") || oldName.startsWith("活动");
                if (placeholder) {
                    attrs.put("name", labelOf(method));
                }
            }
        }
        if (resourceId != null) {
            if (resourceId > 0) {
                attrs.put("resource", String.valueOf(resourceId));
            } else {
                attrs.remove("resource");
                attrs.remove("resourceName");
            }
        }

        StringBuilder sb = new StringBuilder("<").append(tag);
        attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(v).append('"'));
        sb.append(selfClose ? " />" : ">");
        return xml.substring(0, mt.start()) + sb + xml.substring(mt.end());
    }

    /** 新建一个错误码（码本身；策略另行添加）。 */
    public String createErrorCode(String code, String scope, String severity,
                                  String title, String detail, String source)
            throws SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("错误码不能为空");
        }
        String c = code.trim();
        try (Connection conn = db.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO error_code
                       (tenant_id, code, scope, severity, title, detail, source)
                     VALUES (?, ?, ?, ?::error_severity, ?, ?, ?)
                     RETURNING id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, c);
            ps.setString(3, scope == null || scope.isBlank() ? "command" : scope);
            ps.setString(4, severity == null || severity.isBlank() ? "error" : severity);
            ps.setString(5, title == null || title.isBlank() ? c : title);
            ps.setString(6, detail);
            ps.setString(7, source == null || source.isBlank() ? "manual" : source);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Jsons.obj("id", rs.getLong(1), "code", c);
            }
        } catch (SQLException e) {
            // 唯一键冲突要给前端看得懂的话，而不是 500
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new IllegalArgumentException("错误码已存在：" + c);
            }
            throw e;
        }
    }

    /**
     * 删除错误码（软删，`deleted_at`）。
     *
     * <p>为什么软删：{@code error_occurrence} 里挂着历史发生记录，
     * 硬删会连带丢掉"这个错以前出过多少次"；软删后列表不再显示，历史仍在。
     */
    public boolean deleteErrorCode(long id) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE error_code SET deleted_at = now()
                      WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
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
    public boolean scopeCovered(long errorCodeId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM error_code e
                           JOIN recovery_policy rp ON rp.error_code_id = e.id AND rp.enabled
                          WHERE e.tenant_id = ? AND e.id = ?
                            AND (rp.scope = e.scope OR rp.scope IS NULL))
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, errorCodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public long upsertPolicy(long errorCodeId, String scope, String strategy,
                             Integer maxAttempts, String params, Integer priority,
                             Boolean enabled) throws SQLException {
        try (Connection c = db.open()) {
            try {
                Long existId = null;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT id FROM recovery_policy
                         WHERE tenant_id = ? AND error_code_id = ? AND scope = ?
                         ORDER BY priority, id LIMIT 1
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, errorCodeId);
                    ps.setString(3, scope);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existId = rs.getLong(1);
                        }
                    }
                }
                long id;
                if (existId != null) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE recovery_policy SET
                              strategy = COALESCE(?::recovery_strategy, strategy),
                              max_attempts = COALESCE(?::smallint, max_attempts),
                              params = COALESCE(?::jsonb, params),
                              priority = COALESCE(?::smallint, priority),
                              enabled = COALESCE(?::boolean, enabled),
                              updated_at = now()
                             WHERE tenant_id = ? AND id = ?
                            """)) {
                        ps.setString(1, strategy);
                        ps.setObject(2, maxAttempts);
                        ps.setString(3, params);
                        ps.setObject(4, priority);
                        ps.setObject(5, enabled);
                        ps.setLong(6, tenantId);
                        ps.setLong(7, existId);
                        ps.executeUpdate();
                    }
                    id = existId;
                } else {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO recovery_policy
                              (tenant_id, error_code_id, scope, strategy, params,
                               max_attempts, priority, enabled)
                            VALUES (?, ?, ?, ?::recovery_strategy, COALESCE(?::jsonb, '{}'::jsonb),
                                    ?, ?, COALESCE(?::boolean, true))
                            RETURNING id
                            """)) {
                        ps.setLong(1, tenantId);
                        ps.setLong(2, errorCodeId);
                        ps.setString(3, scope);
                        ps.setString(4, strategy == null ? "manual" : strategy);
                        ps.setString(5, params);
                        ps.setObject(6, maxAttempts == null ? 1 : maxAttempts);
                        ps.setObject(7, priority == null ? 50 : priority);
                        ps.setObject(8, enabled);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            id = rs.getLong(1);
                        }
                    }
                }
                return id;
            } catch (SQLException e) {
                throw e;
            }
        }
    }

    public boolean deletePolicy(long errorCodeId, long policyId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM recovery_policy
                      WHERE tenant_id = ? AND error_code_id = ? AND id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, errorCodeId);
            ps.setLong(3, policyId);
            return ps.executeUpdate() > 0;
        }
    }

    public String deviceModelsJson() throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT d.id, d.code, d.name, d.device_type, d.vendor, d.icon,
                       c.code AS capability,
                       (SELECT count(*) FROM device_instance di
                         WHERE di.device_id = d.id AND di.deleted_at IS NULL) AS deployed
                FROM device d
                LEFT JOIN capability c ON c.id = d.capability_id
                WHERE d.tenant_id = ?
                ORDER BY deployed DESC, d.name
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "modelId", rs.getLong("id"),
                            "code", rs.getString("code"),
                            "name", rs.getString("name"),
                            "deviceType", rs.getString("device_type"),
                            "vendor", rs.getString("vendor"),
                            "icon", rs.getString("icon"),
                            "capability", rs.getString("capability"),
                            "deployed", rs.getInt("deployed")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 按型号部署一台新设备到岛。
     *
     * <p>编号自动递增（{@code Incubator_2C-20}），IP 留空由现场填——
     * 同一型号的多台设备 IP 一定不同，不能猜。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public long deployModel(long modelId, long islandId) throws SQLException {
        try (Connection c = db.open()) {
            try {
                String modelName;
                String modelCode;
                String capability;
                String islandPath;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT d.name, d.code AS dev_code, c.code AS capability, p.path
                        FROM device d
                        LEFT JOIN capability c ON c.id = d.capability_id
                        LEFT JOIN resource_node p ON p.id = ?
                        WHERE d.tenant_id = ? AND d.id = ?
                        """)) {
                    ps.setLong(1, islandId);
                    ps.setLong(2, tenantId);
                    ps.setLong(3, modelId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("型号不存在: " + modelId);
                        }
                        modelName = rs.getString(1);
                        modelCode = rs.getString(2);
                        capability = rs.getString(3);
                        islandPath = rs.getString(4);
                        if (islandPath == null) {
                            throw new IllegalArgumentException("岛屿不存在: " + islandId);
                        }
                    }
                }

                // 编号：**用设备池 code**（S100 / ASPIN_2K），不用 name。
                // 用 name 的话中文会被逐个替换成下划线，实测出过
                // "_________ASpin_2K-1" 这种编号，运维看不懂。
                String base = (modelCode == null || modelCode.isBlank()
                        ? modelName : modelCode).replaceAll("[^A-Za-z0-9_]", "_");
                int seq = 1;
                String code = base + "-" + seq;
                while (existsDeviceCode(c, code)) {
                    seq++;
                    code = base + "-" + seq;
                }

                long rid;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO resource_node
                          (tenant_id, parent_id, code, name, kind, path, sort_order)
                        VALUES (?, ?, ?, ?, 'device', ?, 100)
                        RETURNING id
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, islandId);
                    ps.setString(3, code);
                    ps.setString(4, modelName + " #" + seq);
                    ps.setString(5, islandPath + "/" + code);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        rid = rs.getLong(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO device_instance
                          (tenant_id, code, name, device_id, resource_id, ip)
                        VALUES (?, ?, ?, ?, ?, NULL)
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setString(2, code);
                    ps.setString(3, modelName + " #" + seq);
                    ps.setLong(4, modelId);
                    ps.setLong(5, rid);
                    ps.executeUpdate();
                }

                // **能力必须一起写**：驱动注册表是按 resource_capability 分能力的。
                // 只建实例不建能力，驱动会因为"名下设备都没有声明能力"被整条跳过，
                // 现象是设备在界面上有、点不动，报 EC-06-0001（驱动未加载）。
                // 改动前这里没有这一段 —— 部署出来的设备一台都驱动不了。
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO resource_capability (resource_id, capability_id)
                        SELECT ?, capability_id FROM device
                         WHERE id = ? AND capability_id IS NOT NULL
                        ON CONFLICT (resource_id, capability_id) DO NOTHING
                        """)) {
                    ps.setLong(1, rid);
                    ps.setLong(2, modelId);
                    ps.executeUpdate();
                }

                return rid;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    public String devicePoolJson() throws SQLException {
        return devicePoolJson(null);
    }

    /**
     * 设备池，可按岛过滤（设计器「按岛找设备」）。
     * islandId 为 null / <=0 时保持全量，保证岛屿视图与 e2e-sim-flow 不回归。
     */
    public String devicePoolJson(Long islandId) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT d.id AS resource_id, d.code, d.name, di.ip,
                       c.code AS capability,
                       dev.icon AS icon,
                       (SELECT p.id FROM resource_node p
                         WHERE p.id = d.parent_id AND p.kind = 'island')   AS island_id,
                       (SELECT p.code FROM resource_node p
                         WHERE p.id = d.parent_id AND p.kind = 'island')   AS island_code
                FROM resource_node d
                JOIN device_instance di ON di.resource_id = d.id AND di.deleted_at IS NULL
                LEFT JOIN device dev ON dev.id = di.device_id
                LEFT JOIN capability c ON c.id = dev.capability_id
                WHERE d.tenant_id = ? AND d.kind = 'device'
                  AND (?::bigint IS NULL OR d.parent_id = ?)
                ORDER BY d.code
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            if (islandId == null || islandId <= 0) {
                ps.setNull(2, java.sql.Types.BIGINT);
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, islandId);
                ps.setLong(3, islandId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long isl = (Long) rs.getObject("island_id");
                    items.add(Jsons.obj(
                            "resourceId", rs.getLong("resource_id"),
                            "code", rs.getString("code"),
                            "name", rs.getString("name"),
                            "ip", rs.getString("ip"),
                            "capability", rs.getString("capability"),
                            // 型号库登记的原始图片路径。多数存量值指向源服务器
                            // /image/device/<uuid>.png（换环境 404），前端 iconFor()
                            // 会按 capability 映射回本地 /devices/<CAP>.svg。
                            "icon", rs.getString("icon"),
                            "islandId", isl,
                            "islandCode", rs.getString("island_code")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 未挂到任何岛的设备（拖拽时的"设备池"）。 */
    public String unassignedDevicesJson() throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT d.id AS resource_id, d.code, d.name, di.ip, c.code AS capability
                FROM resource_node d
                LEFT JOIN resource_node p ON p.id = d.parent_id
                LEFT JOIN device_instance di ON di.resource_id = d.id AND di.deleted_at IS NULL
                LEFT JOIN device dev ON dev.id = di.device_id
                LEFT JOIN capability c ON c.id = dev.capability_id
                WHERE d.tenant_id = ? AND d.kind = 'device'
                  AND (p.id IS NULL OR p.kind <> 'island')
                  AND di.id IS NOT NULL
                ORDER BY d.code
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "resourceId", rs.getLong("resource_id"),
                            "code", rs.getString("code"),
                            "name", rs.getString("name"),
                            "ip", rs.getString("ip"),
                            "capability", rs.getString("capability")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 流程图保存历史（按时间倒序）。 */
    public String graphHistoryJson(long workflowId) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT id, node_count, edge_count, length(xml) AS bytes, saved_at
                FROM workflow_graph_history
                WHERE tenant_id = ? AND workflow_id = ?
                ORDER BY saved_at DESC LIMIT 30
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "nodeCount", rs.getInt("node_count"),
                            "edgeCount", rs.getInt("edge_count"),
                            "bytes", rs.getInt("bytes"),
                            "savedAt", str(rs, "saved_at")));
                }
            }
        }
        return Jsons.obj("workflowId", workflowId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 恢复到某个历史版本。
     *
     * <p>先把当前版本再备份一次（不然回滚后就找不到"回滚前"的样子了），
     * 再写回去。等价于一次普通的保存。
     */
    public int restoreGraph(long workflowId, long historyId) throws SQLException {
        String xml;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT xml FROM workflow_graph_history
                     WHERE tenant_id = ? AND workflow_id = ? AND id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            ps.setLong(3, historyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("历史版本不存在: " + historyId);
                }
                xml = rs.getString(1);
            }
        } catch (SQLException e) {
            throw e;
        } finally {
            // 注意：这里不写回，交由调用方走 saveGraph（复用校验与备份逻辑）
        }
        return xml.length();   // 由 ApiServer 取 XML 再调 saveGraph
    }

    /** 读某历史版本的 XML 原文。 */
    public String graphHistoryXml(long workflowId, long historyId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT xml FROM workflow_graph_history
                     WHERE tenant_id = ? AND workflow_id = ? AND id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            ps.setLong(3, historyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("历史版本不存在: " + historyId);
                }
                return rs.getString(1);
            }
        }
    }

    /** 某个设备方法的参数定义（设计器填参用）。 */
    public String methodParamsJson(String method) throws SQLException {
        String sql = """
                SELECT dm.method, dm.name, c.code AS capability, dm.param_schema->'params' AS params
                FROM device_method dm
                LEFT JOIN capability c ON c.id = dm.capability_id
                WHERE dm.tenant_id = ? AND dm.method = ?
                LIMIT 1
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, method);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Jsons.obj("method", method, "found", false,
                            "params", new Jsons.Raw("[]"));
                }
                String ps1 = rs.getString("params");
                return Jsons.obj(
                        "method", rs.getString("method"),
                        "name", rs.getString("name"),
                        "capability", rs.getString("capability"),
                        "found", true,
                        "params", ps1 == null ? new Jsons.Raw("[]") : new Jsons.Raw(ps1));
            }
        }
    }

    /** 驱动端点列表（新增设备时选）。 */
    public String driversJson() throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT id, code, name, protocol::text AS protocol, endpoint, status
                FROM driver WHERE tenant_id = ? AND deleted_at IS NULL
                ORDER BY id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "code", rs.getString("code"),
                            "name", rs.getString("name"),
                            "protocol", rs.getString("protocol"),
                            "endpoint", rs.getString("endpoint"),
                            "status", rs.getObject("status")));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 新增岛屿。
     *
     * <p>岛屿是资源树的顶层（parent_id 为空），path 用 {@code /CODE}。
     */
    public long createIsland(String code, String name) throws SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("岛屿编号不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("岛屿名称不能为空");
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO resource_node
                       (tenant_id, parent_id, code, name, kind, path, sort_order)
                     VALUES (?, NULL, ?, ?, 'island', ?, 100)
                     RETURNING id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, code.trim());
            ps.setString(3, name.trim());
            ps.setString(4, "/" + code.trim());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            throw new IllegalArgumentException("岛屿编号已存在: " + code);
        }
    }

    /**
     * 新增设备：一次建三样东西。
     *
     * <ol>
     *   <li>资源树上的设备节点（决定位置）</li>
     *   <li>设备型号（不存在则建，决定能力）</li>
     *   <li>设备实例（决定 IP 与驱动端点）</li>
     * </ol>
     *
     * @param driverId 驱动端点 id，可空（单驱动场景下运行时兜底）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public long createDevice(long islandId, String code, String name, String deviceType,
                             String capabilityCode, String ip, Long driverId)
            throws SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("设备编号不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }

        try (Connection c = db.open()) {
            try {
                // 1) 岛屿必须存在且是 island
                String islandPath = queryMapper.selectIslandPath(tenantId, islandId);
                if (islandPath == null) {
                    throw new IllegalArgumentException("岛屿不存在: " + islandId);
                }

                // 2) 资源树节点
                long resourceId;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO resource_node
                          (tenant_id, parent_id, code, name, kind, path, sort_order)
                        VALUES (?, ?, ?, ?, 'device', ?, 100)
                        RETURNING id
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, islandId);
                    ps.setString(3, code.trim());
                    ps.setString(4, name.trim());
                    ps.setString(5, (islandPath == null ? "" : islandPath) + "/" + code.trim());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        resourceId = rs.getLong(1);
                    }
                }

                // 3) 型号（按 code 查，没有则建）
                Long capabilityId = null;
                if (capabilityCode != null && !capabilityCode.isBlank()) {
                    capabilityId = queryMapper.selectCapabilityId(
                            tenantId, capabilityCode.trim());
                }

                long deviceId;
                Long existingDeviceId = queryMapper.selectDeviceIdByCode(tenantId, code.trim());
                {
                    {
                        if (existingDeviceId != null) {
                            deviceId = existingDeviceId;
                        } else {
                            try (PreparedStatement ins = c.prepareStatement("""
                                    INSERT INTO device
                                      (tenant_id, code, name, device_type, capability_id)
                                    VALUES (?, ?, ?, ?, ?)
                                    RETURNING id
                                    """)) {
                                ins.setLong(1, tenantId);
                                ins.setString(2, code.trim());
                                ins.setString(3, name.trim());
                                ins.setString(4, deviceType == null || deviceType.isBlank()
                                        ? "MISC" : deviceType.trim());
                                if (capabilityId == null) {
                                    ins.setNull(5, java.sql.Types.BIGINT);
                                } else {
                                    ins.setLong(5, capabilityId);
                                }
                                try (ResultSet rs2 = ins.executeQuery()) {
                                    rs2.next();
                                    deviceId = rs2.getLong(1);
                                }
                            }
                        }
                    }
                }

                // 4) 设备实例
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO device_instance
                          (tenant_id, code, name, device_id, resource_id, ip, driver_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setString(2, code.trim());
                    ps.setString(3, name.trim());
                    ps.setLong(4, deviceId);
                    ps.setLong(5, resourceId);
                    ps.setString(6, ip == null || ip.isBlank() ? null : ip.trim());
                    if (driverId == null) {
                        ps.setNull(7, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(7, driverId);
                    }
                    ps.executeUpdate();
                }

                return resourceId;
            } catch (SQLException | RuntimeException e) {
                if (e instanceof java.sql.SQLIntegrityConstraintViolationException) {
                    throw new IllegalArgumentException("设备编号已存在: " + code);
                }
                throw e;
            }
        }
    }

    /**
     * 改设备：名称 / IP / 驱动端点 / 能力。
     *
     * <p>名称同时改资源树节点（界面上看到的是那个）；
     * 能力改的是型号，会影响同型号的所有实例——这是刻意的，能力属于型号不属于单台。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int updateDevice(long resourceId, String name, String ip, Long driverId,
                            String capabilityCode) throws SQLException {
        try (Connection c = db.open()) {
            try {
                if (name != null && !name.isBlank()) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE resource_node SET name = ?
                            WHERE tenant_id = ? AND id = ? AND kind = 'device'
                            """)) {
                        ps.setString(1, name.trim());
                        ps.setLong(2, tenantId);
                        ps.setLong(3, resourceId);
                        ps.executeUpdate();
                    }
                }

                if (capabilityCode != null && !capabilityCode.isBlank()) {
                    Long capId = queryMapper.selectCapabilityId(tenantId, capabilityCode.trim());
                    if (capId != null) {
                        try (PreparedStatement ps = c.prepareStatement("""
                                UPDATE device SET capability_id = ?
                                WHERE tenant_id = ?
                                  AND id = (SELECT device_id FROM device_instance
                                            WHERE tenant_id = ? AND resource_id = ?)
                                """)) {
                            ps.setLong(1, capId);
                            ps.setLong(2, tenantId);
                            ps.setLong(3, tenantId);
                            ps.setLong(4, resourceId);
                            ps.executeUpdate();
                        }
                    }
                }

                int n;
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE device_instance
                        SET ip = COALESCE(?, ip),
                            driver_id = COALESCE(?, driver_id),
                            updated_at = now()
                        WHERE tenant_id = ? AND resource_id = ?
                        """)) {
                    ps.setString(1, ip == null || ip.isBlank() ? null : ip.trim());
                    if (driverId == null) {
                        ps.setNull(2, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(2, driverId);
                    }
                    ps.setLong(3, tenantId);
                    ps.setLong(4, resourceId);
                    n = ps.executeUpdate();
                }

                return n;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * 删设备：软删实例 + 从资源树移除。
     *
     * <p>不硬删：历史 run_node 可能引用这台设备，硬删会断链。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int deleteDevice(long resourceId) throws SQLException {
        try (Connection c = db.open()) {
            try {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE device_instance
                        SET deleted_at = now(), updated_at = now()
                        WHERE tenant_id = ? AND resource_id = ? AND deleted_at IS NULL
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, resourceId);
                    ps.executeUpdate();
                }
                // 不删 resource_node：run_node.resource_id 可能引用它（外键会挡住），
                // 且历史执行记录要能查回"这台设备当时在哪"。
                // 拓扑查询会过滤 deleted_at IS NOT NULL，界面上等于删掉了。
                return 1;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /** 能力字典（设计器下拉用）：code + 可用设备数。 */
    public String capabilitiesJson() throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT c.code, c.name, count(rc.resource_id) AS devices
                FROM capability c
                LEFT JOIN resource_capability rc ON rc.capability_id = c.id
                WHERE c.tenant_id = ?
                GROUP BY c.id, c.code, c.name
                ORDER BY devices DESC, c.code
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "code", rs.getString(1),
                            "name", rs.getString(2),
                            "devices", rs.getInt(3)));
                }
            }
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 设置设备方法的「执行中可中断」标志（产品写入口；方法必须已在设备库中）。
     *
     * @return 受影响行数（0 = 设备/方法不存在，调用方回 404）
     */
    public int setDeviceMethodInterruptible(long deviceId, String method, boolean value)
            throws SQLException {
        return queryMapper.setDeviceMethodInterruptible(tenantId, deviceId, method, value);
    }

    /** L-01：记录一条人工介入（决策/Handoff 确认）。 */
    public long recordIntervention(long manualWindowId, Long runId, Long experimentRunId,
                                   String operator, String action, String plateBarcode,
                                   String sampleId, String fromLocation, String toLocation,
                                   String result, String note) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO intervention_record
                       (tenant_id, manual_window_id, run_id, experiment_run_id, operator,
                        decided_action, plate_barcode, sample_id, from_location, to_location,
                        result, note)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     RETURNING id
                     """)) {
            ps.setLong(1, tenantId);
            if (manualWindowId > 0) {
                ps.setLong(2, manualWindowId);
            } else {
                ps.setNull(2, java.sql.Types.BIGINT);
            }
            if (runId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, runId);
            }
            if (experimentRunId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, experimentRunId);
            }
            ps.setString(5, operator == null ? "unknown" : operator);
            ps.setString(6, action);
            ps.setString(7, plateBarcode);
            ps.setString(8, sampleId);
            ps.setString(9, fromLocation);
            ps.setString(10, toLocation);
            ps.setString(11, result == null ? "unknown" : result);
            ps.setString(12, note);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** L-01：人工介入记录列表（最新优先）。 */
    public String interventionRecordsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, manual_window_id, run_id, experiment_run_id, operator,
                            decided_action, plate_barcode, sample_id, from_location,
                            to_location, result, note, created_at::text
                       FROM intervention_record
                      WHERE tenant_id = ?
                      ORDER BY id DESC
                      LIMIT ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long wid = rs.getLong("manual_window_id");
                    Object windowId = rs.wasNull() ? null : wid;
                    long rid = rs.getLong("run_id");
                    Object runId = rs.wasNull() ? null : rid;
                    long erid = rs.getLong("experiment_run_id");
                    Object experimentRunId = rs.wasNull() ? null : erid;
                    items.add(Jsons.obj("id", rs.getLong("id"),
                            "manualWindowId", windowId, "runId", runId,
                            "experimentRunId", experimentRunId,
                            "operator", rs.getString("operator"),
                            "action", rs.getString("decided_action"),
                            "plateBarcode", rs.getString("plate_barcode"),
                            "sampleId", rs.getString("sample_id"),
                            "fromLocation", rs.getString("from_location"),
                            "toLocation", rs.getString("to_location"),
                            "result", rs.getString("result"),
                            "note", rs.getString("note"),
                            "createdAt", rs.getString("created_at")));
                }
            }
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 实验 Handoff 配置列表（N-10）。 */
    public String experimentHandoffsJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT from_workcell_id, to_workcell_id, carrier, status, remark
                       FROM experiment_handoff
                      WHERE tenant_id = ? AND experiment_id = ?
                      ORDER BY from_workcell_id, to_workcell_id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj("fromWorkcellId", rs.getLong(1),
                            "toWorkcellId", rs.getLong(2),
                            "carrier", rs.getString(3),
                            "status", rs.getString(4),
                            "remark", rs.getString(5)));
                }
            }
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)), "count", items.size());
    }

    /**
     * 全量替换实验 Handoff 配置。
     *
     * @return 写入条数；-1 实验不存在；-2 起点/终点无效或不在显式 Workcell 绑定内
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int setExperimentHandoffs(long experimentId,
                                     java.util.List<long[]> pairs) throws SQLException {
        try (Connection c = db.open()) {
            try {
                java.util.Set<Long> bound = new java.util.LinkedHashSet<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM experiment WHERE tenant_id = ? AND id = ?")) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, experimentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) == 0) {
                            return -1;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT workcell_id FROM experiment_workcell "
                                + "WHERE tenant_id = ? AND experiment_id = ?")) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, experimentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            bound.add(rs.getLong(1));
                        }
                    }
                }
                for (long[] p : pairs) {
                    if (p == null || p.length < 2 || p[0] <= 0 || p[1] <= 0 || p[0] == p[1]) {
                        return -2;
                    }
                    if (!bound.isEmpty() && (!bound.contains(p[0]) || !bound.contains(p[1]))) {
                        return -2;
                    }
                }
                experimentMapper.deleteExperimentHandoffs(tenantId, experimentId);
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO experiment_handoff
                          (tenant_id, experiment_id, from_workcell_id, to_workcell_id,
                           carrier, status)
                        VALUES (?, ?, ?, ?, 'manual', 'configured')
                        ON CONFLICT (tenant_id, experiment_id, from_workcell_id, to_workcell_id)
                        DO NOTHING
                        """)) {
                    for (long[] p : pairs) {
                        ps.setLong(1, tenantId);
                        ps.setLong(2, experimentId);
                        ps.setLong(3, p[0]);
                        ps.setLong(4, p[1]);
                        ps.addBatch();
                    }
                    n = ps.executeBatch().length;
                }
                return n;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * N-10：人工确认一条 Handoff（配置 → confirmed），并落 L-01 介入记录。
     *
     * @return 更新条数（0 = 不存在/已确认）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int confirmHandoff(long experimentId, long fromWorkcellId, long toWorkcellId,
                              String operator, String note) throws SQLException {
        try (Connection c = db.open()) {
            try {
                int n;
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE experiment_handoff
                           SET status = 'confirmed', updated_at = now()
                         WHERE tenant_id = ? AND experiment_id = ?
                           AND from_workcell_id = ? AND to_workcell_id = ?
                           AND status IN ('configured', 'unknown')
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, experimentId);
                    ps.setLong(3, fromWorkcellId);
                    ps.setLong(4, toWorkcellId);
                    n = ps.executeUpdate();
                }
                if (n > 0) {
                    long recordId;
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO intervention_record
                              (tenant_id, run_id, operator, decided_action,
                               from_location, to_location, result, note)
                            VALUES (?, NULL, ?, 'HANDOFF_CONFIRM', ?, ?, 'resolved', ?)
                            RETURNING id
                            """)) {
                        ps.setLong(1, tenantId);
                        ps.setString(2, operator == null ? "api" : operator);
                        ps.setString(3, String.valueOf(fromWorkcellId));
                        ps.setString(4, String.valueOf(toWorkcellId));
                        ps.setString(5, note);
                        try (ResultSet rs = ps.executeQuery()) {
                            recordId = rs.next() ? rs.getLong(1) : 0L;
                        }
                    }
                    // N-08：确认动作同时落一条已决策 manual_window，形成直接联动
                    long windowId;
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO manual_window
                              (tenant_id, run_id, window_key, default_action,
                               allowed_actions, decision_reason, status,
                               decided_at, decided_by, decided_action, note)
                            VALUES (?, 0, ?, 'MANUAL',
                                    '["CONTINUE"]'::jsonb, ?, 'decided',
                                    now(), ?, 'CONTINUE', ?)
                            RETURNING id
                            """)) {
                        ps.setLong(1, tenantId);
                        ps.setString(2, "handoff:" + experimentId + ":" + fromWorkcellId + ":"
                                + toWorkcellId);
                        ps.setString(3, note == null ? "Handoff 人工确认" : note);
                        ps.setString(4, operator == null ? "api" : operator);
                        ps.setString(5, note);
                        try (ResultSet rs = ps.executeQuery()) {
                            windowId = rs.next() ? rs.getLong(1) : 0L;
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE experiment_handoff
                               SET intervention_record_id = ?, manual_window_id = ?
                             WHERE tenant_id = ? AND experiment_id = ?
                               AND from_workcell_id = ? AND to_workcell_id = ?
                            """)) {
                        ps.setLong(1, recordId);
                        ps.setLong(2, windowId);
                        ps.setLong(3, tenantId);
                        ps.setLong(4, experimentId);
                        ps.setLong(5, fromWorkcellId);
                        ps.setLong(6, toWorkcellId);
                        ps.executeUpdate();
                    }
                }
                return n;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /** P-07：命令事件 Outbox 列表（可按状态过滤）。 */
    public String commandOutboxJson(String state, int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, ledger_id, run_id, command_id, device_id, method,
                       state, attempts, created_at::text, delivered_at::text
                  FROM command_outbox
                 WHERE tenant_id = ?
                """);
        if (state != null && !state.isBlank()) {
            sql.append(" AND state = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, tenantId);
            if (state != null && !state.isBlank()) {
                ps.setString(i++, state);
            }
            ps.setInt(i, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj("id", rs.getLong("id"),
                            "ledgerId", rs.getLong("ledger_id"),
                            "runId", rs.getObject("run_id"),
                            "commandId", rs.getString("command_id"),
                            "deviceId", rs.getObject("device_id"),
                            "method", rs.getString("method"),
                            "state", rs.getString("state"),
                            "attempts", rs.getInt("attempts"),
                            "createdAt", rs.getString("created_at"),
                            "deliveredAt", rs.getString("delivered_at")));
                }
            }
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-07：重放一条已派发的外部事件（不重发物理命令）。 */
    public boolean replayCommandOutbox(long id) throws SQLException {
        return com.labos.kernel.driver.CommandOutbox.replay(db, tenantId, id);
    }

    /** N-10：跨实验 Handoff 处置列表（可按状态过滤）。 */
    public String handoffsJson(String status, int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT h.id, h.experiment_id, e.experiment_no,
                       h.from_workcell_id, rf.name AS from_name,
                       h.to_workcell_id, rt.name AS to_name,
                       h.status, h.carrier, h.remark
                  FROM experiment_handoff h
                  JOIN experiment e
                    ON e.tenant_id = h.tenant_id AND e.id = h.experiment_id
                  LEFT JOIN resource_node rf
                    ON rf.tenant_id = h.tenant_id AND rf.id = h.from_workcell_id
                  LEFT JOIN resource_node rt
                    ON rt.tenant_id = h.tenant_id AND rt.id = h.to_workcell_id
                 WHERE h.tenant_id = ?
                """);
        if (status != null && !status.isBlank()) {
            sql.append(" AND h.status = ?");
        }
        sql.append(" ORDER BY h.id DESC LIMIT ?");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, tenantId);
            if (status != null && !status.isBlank()) {
                ps.setString(i++, status);
            }
            ps.setInt(i, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj("id", rs.getLong("id"),
                            "experimentId", rs.getLong("experiment_id"),
                            "experimentNo", rs.getString("experiment_no"),
                            "fromWorkcellId", rs.getLong("from_workcell_id"),
                            "fromName", rs.getString("from_name"),
                            "toWorkcellId", rs.getLong("to_workcell_id"),
                            "toName", rs.getString("to_name"),
                            "status", rs.getString("status"),
                            "carrier", rs.getString("carrier"),
                            "remark", rs.getString("remark")));
                }
            }
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：TransferTransaction 列表。 */
    public String transferTransactionsJson(String status, int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, run_id, plate_barcode, source_resource_id, target_resource_id,
                       status, error_code, error_message, created_at::text
                  FROM transfer_transaction
                 WHERE tenant_id = ?
                """);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, tenantId);
            if (status != null && !status.isBlank()) {
                ps.setString(i++, status);
            }
            ps.setInt(i, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj("id", rs.getLong("id"),
                            "runId", rs.getObject("run_id"),
                            "plateBarcode", rs.getString("plate_barcode"),
                            "sourceResourceId", rs.getObject("source_resource_id"),
                            "targetResourceId", rs.getObject("target_resource_id"),
                            "status", rs.getString("status"),
                            "errorCode", rs.getString("error_code"),
                            "errorMessage", rs.getString("error_message"),
                            "createdAt", rs.getString("created_at")));
                }
            }
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：人工/扫码确认搬运物理位置。 */
    public boolean confirmTransfer(long id, String evidenceType, String operator,
                                   String note) throws SQLException {
        return com.labos.kernel.workflow.TransferTransaction.confirm(
                db, tenantId, id, evidenceType, operator, note);
    }

    /** P-08：记录一条物理位置证据。 */
    public long recordPositionEvidence(String plateBarcode, Long resourceId, String type,
                                       Long runId, String operator, String note)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO position_evidence
                       (tenant_id, plate_barcode, resource_id, evidence_type, run_id,
                        operator, note)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     RETURNING id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, plateBarcode);
            if (resourceId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, resourceId);
            }
            ps.setString(4, type == null ? "MANUAL" : type.toUpperCase());
            if (runId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, runId);
            }
            ps.setString(6, operator);
            ps.setString(7, note);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** P-08：物理位置证据列表（按板号可选过滤）。 */
    public String positionEvidenceJson(String plateBarcode, int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, plate_barcode, resource_id, evidence_type, run_id,
                       operator, note, created_at::text
                  FROM position_evidence
                 WHERE tenant_id = ?
                """);
        if (plateBarcode != null && !plateBarcode.isBlank()) {
            sql.append(" AND plate_barcode = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, tenantId);
            if (plateBarcode != null && !plateBarcode.isBlank()) {
                ps.setString(i++, plateBarcode);
            }
            ps.setInt(i, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rid = rs.getLong("resource_id");
                    Object resourceId = rs.wasNull() ? null : rid;
                    long runId = rs.getLong("run_id");
                    Object run = rs.wasNull() ? null : runId;
                    items.add(Jsons.obj("id", rs.getLong("id"),
                            "plateBarcode", rs.getString("plate_barcode"),
                            "resourceId", resourceId,
                            "evidenceType", rs.getString("evidence_type"),
                            "runId", run,
                            "operator", rs.getString("operator"),
                            "note", rs.getString("note"),
                            "createdAt", rs.getString("created_at")));
                }
            }
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：隔离中的 run 列表（处置页用）。 */
    public String quarantinedRunsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT r.id, r.run_no, r.error_code,
                            r.input->>'quarantineReason' AS reason,
                            r.created_at::text,
                            (SELECT er.experiment_id
                               FROM experiment_run_step s
                               JOIN experiment_run er
                                 ON er.tenant_id = s.tenant_id
                                AND er.id = s.experiment_run_id
                              WHERE s.tenant_id = r.tenant_id AND s.run_id = r.id
                              LIMIT 1) AS experiment_id
                       FROM run r
                      WHERE r.tenant_id = ? AND r.status = 'quarantined'
                      ORDER BY r.id DESC
                      LIMIT ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long eid = rs.getLong("experiment_id");
                    Object expId = rs.wasNull() ? null : eid;
                    items.add(Jsons.obj("runId", rs.getLong("id"),
                            "runNo", rs.getString("run_no"),
                            "errorCode", rs.getString("error_code"),
                            "reason", rs.getString("reason"),
                            "createdAt", rs.getString("created_at"),
                            "experimentId", expId));
                }
            }
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** UNKNOWN 命令账本列表（P-07.2 人工核对入口）。 */
    /**
     * B-04：已请求中止、但设备没确认停的命令（人工核验入口）。
     *
     * <p>与 {@link #commandsUnknownJson} 并列而不是合并：UNKNOWN 说的是
     * 「命令结果不知道」，这里说的是「我们试着停过，但没拿到证据」——
     * 两条判断可以同时成立，混成一张表就分不清是哪条原因需要人核查。
     */
    public String commandsAbortUnconfirmedJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        for (com.labos.kernel.driver.CommandLedger.AbortPending a
                : com.labos.kernel.driver.CommandLedger.listUnconfirmedAbort(db, tenantId, limit)) {
            items.add(Jsons.obj("id", a.id(), "key", a.key(), "runId", a.runId(),
                    "nodeId", a.nodeId(), "method", a.method(), "commandId", a.commandId(),
                    "state", a.state(), "abortState", a.abortState(),
                    "abortDetail", a.abortDetail(), "abortAt", a.abortAt()));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)),
                "count", items.size());
    }

    public String commandsUnknownJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        for (com.labos.kernel.driver.CommandLedger.Unknown u
                : com.labos.kernel.driver.CommandLedger.listUnknown(db, tenantId, limit)) {
            items.add(Jsons.obj("id", u.id(), "key", u.key(), "runId", u.runId(),
                    "nodeId", u.nodeId(), "method", u.method(), "commandId", u.commandId(),
                    "traceId", u.traceId(), "errorCode", u.errorCode(),
                    "errorMessage", u.errorMessage(), "createdAt", u.createdAt()));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)),
                "count", items.size());
    }

    /** 处置 UNKNOWN 命令账本（confirm-done / confirm-failed）。 */
    public int resolveCommandLedger(long id, boolean done, String note, Long by)
            throws SQLException {
        return com.labos.kernel.driver.CommandLedger.resolve(db, tenantId, id, done, note, by);
    }

    /** 实验显式 Workcell 绑定（N-10）。 */
    public String experimentWorkcellsJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT ew.workcell_id, rn.name
                       FROM experiment_workcell ew
                       LEFT JOIN resource_node rn
                         ON rn.tenant_id = ew.tenant_id AND rn.id = ew.workcell_id
                      WHERE ew.tenant_id = ? AND ew.experiment_id = ?
                      ORDER BY ew.workcell_id
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj("workcellId", rs.getLong(1),
                            "name", rs.getString(2)));
                }
            }
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)), "count", items.size());
    }

    /**
     * 全量替换实验 Workcell 绑定（N-10 显式绑定写入口）。
     *
     * @return 写入条数（空列表 = 清空绑定，回到资源树推导）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int setExperimentWorkcells(long experimentId, List<Long> workcellIds)
            throws SQLException {
        try (Connection c = db.open()) {
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM experiment WHERE tenant_id = ? AND id = ?")) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, experimentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) == 0) {
                            return -1;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM experiment_workcell "
                                + "WHERE tenant_id = ? AND experiment_id = ?")) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, experimentId);
                    ps.executeUpdate();
                }
                int n = 0;
                if (workcellIds != null && !workcellIds.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO experiment_workcell
                              (tenant_id, experiment_id, workcell_id, source)
                            VALUES (?, ?, ?, 'binding')
                            ON CONFLICT (tenant_id, experiment_id, workcell_id) DO NOTHING
                            """)) {
                        for (Long wid : workcellIds) {
                            if (wid == null || wid <= 0) {
                                continue;
                            }
                            ps.setLong(1, tenantId);
                            ps.setLong(2, experimentId);
                            ps.setLong(3, wid);
                            ps.addBatch();
                        }
                        n = ps.executeBatch().length;
                    }
                }
                return n;
            } catch (SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /** 某能力下可选的设备方法（{@code device_method}）。 */
    public String methodsJson(String capability) throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = """
                SELECT DISTINCT dm.method
                FROM device_method dm
                JOIN capability c ON c.id = dm.capability_id
                WHERE c.tenant_id = ? AND c.code = ?
                ORDER BY dm.method
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, capability);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.quote(rs.getString(1)));
                }
            }
        }
        return Jsons.obj("capability", capability,
                "methods", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 告警处置：认领 / 关闭 / 静音。
     *
     * <p>只改 {@code alert_state}，不动 {@code error_occurrence}——
     * 留痕是事实，处置是人的动作，两者分开。
     */
    public int updateAlertState(String fingerprint, String status, String assignee, String note)
            throws SQLException {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint 不能为空");
        }
        String st = switch (status == null ? "" : status.toLowerCase()) {
            case "acked", "ack" -> "acked";
            case "closed", "close" -> "closed";
            case "silenced", "silence" -> "silenced";
            case "open", "reopen" -> "open";
            default -> throw new IllegalArgumentException("未知处置动作: " + status);
        };
        String sql = """
                INSERT INTO alert_state
                  (tenant_id, fingerprint, status, assignee, note,
                   acked_at, closed_at, updated_at)
                VALUES (?, ?, ?, ?, ?,
                        CASE WHEN ? = 'acked' THEN now() END,
                        CASE WHEN ? = 'closed' THEN now() END,
                        now())
                ON CONFLICT (tenant_id, fingerprint) DO UPDATE SET
                    status     = EXCLUDED.status,
                    assignee   = COALESCE(EXCLUDED.assignee, alert_state.assignee),
                    note       = COALESCE(EXCLUDED.note, alert_state.note),
                    acked_at   = COALESCE(alert_state.acked_at, EXCLUDED.acked_at),
                    closed_at  = CASE WHEN EXCLUDED.status = 'closed'
                                      THEN now() ELSE alert_state.closed_at END,
                    updated_at = now()
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, fingerprint);
            ps.setString(3, st);
            ps.setString(4, assignee);
            ps.setString(5, note);
            ps.setString(6, st);
            ps.setString(7, st);
            return ps.executeUpdate();
        }
    }

    /** 取流程的 BPMN 2.0 XML（原样返回，给 bpmn-js 渲染）。 */
    public String workflowGraph(long workflowId) throws SQLException {
        return queryMapper.selectWorkflowXml(tenantId, workflowId);
    }

    /**
     * 告警列表：按 fingerprint 聚合未解决的错误。
     *
     * <p>只算 severity != 'info' 的——SKIP 类留痕但不告警。
     */
    /** 改一条策略：只允许改动作/次数/优先级/启用，不改错误码与层级。 */
    public int updatePolicy(long id, String strategy, Integer maxAttempts,
                            Integer priority, Boolean enabled) throws SQLException {
        if (strategy != null) {
            String st = strategy.toUpperCase();
            if (!Set.of("RETRY", "REASSIGN", "SKIP", "ABORT", "MANUAL", "COMPENSATE").contains(st)) {
                throw new IllegalArgumentException("未知动作: " + strategy);
            }
        }
        String sql = """
                UPDATE recovery_policy
                SET strategy     = COALESCE(?::recovery_strategy, strategy),
                    max_attempts = COALESCE(?, max_attempts),
                    priority     = COALESCE(?, priority),
                    enabled      = COALESCE(?, enabled),
                    updated_at   = now()
                WHERE tenant_id = ? AND id = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, strategy == null ? null : strategy.toUpperCase());
            ps.setObject(2, maxAttempts);
            ps.setObject(3, priority);
            ps.setObject(4, enabled);
            ps.setLong(5, tenantId);
            ps.setLong(6, id);
            return ps.executeUpdate();
        }
    }

    /**
     * 生效校验：这条策略"改了会不会有用"。
     *
     * 三件事任一不满足，策略就是死的：错误码不存在 / 被更高优先级遮蔽 / 历史没出现过。
     */
    public String validatePolicy(String errorCode, String scope, int priority) throws SQLException {
        String sql = """
                SELECT
                  (SELECT count(*) FROM error_code WHERE tenant_id = ? AND code = ?) AS code_exists,
                  (SELECT count(*) FROM error_occurrence e
                     JOIN error_code c ON c.id = e.error_code_id
                    WHERE e.tenant_id = ? AND c.code = ?)                        AS history_hits,
                  (SELECT count(*) FROM recovery_policy p
                     LEFT JOIN error_code c ON c.id = p.error_code_id
                    WHERE p.tenant_id = ? AND p.enabled
                      AND p.priority < ?
                      AND (p.error_code_id IS NULL OR c.code = ?))                AS shadowed
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, errorCode);
            ps.setLong(3, tenantId);
            ps.setString(4, errorCode);
            ps.setLong(5, tenantId);
            ps.setInt(6, priority);
            ps.setString(7, errorCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Jsons.obj("ok", false, "warning", "查询失败");
                }
                int exists = rs.getInt("code_exists");
                int hits = rs.getInt("history_hits");
                int shadowed = rs.getInt("shadowed");

                String warning = null;
                if (exists == 0) {
                    warning = "错误码不存在，这条策略永远不会命中";
                } else if (shadowed > 0) {
                    warning = "有 " + shadowed + " 条更高优先级的策略会先命中，这条可能永远不生效";
                } else if (hits == 0) {
                    warning = "历史上没出现过这个错误；不是错误，但无法用历史验证效果";
                }
                return Jsons.obj(
                        "errorCode", errorCode,
                        "scope", scope,
                        "codeExists", exists > 0,
                        "historyHits", hits,
                        "shadowedBy", shadowed,
                        "ok", warning == null,
                        "warning", warning);
            }
        }
    }

    public String alerts(int limit) throws SQLException {
        return alerts(limit, "all");
    }

    /**
     * @param status {@code all} / {@code open} / {@code acked} / {@code closed}
     */
    public String alerts(int limit, String status) throws SQLException {
        String want = (status == null || status.isBlank() || "all".equalsIgnoreCase(status))
                ? null : status.toLowerCase();

        String sql = """
                SELECT e.fingerprint,
                       max(e.error_code_id)                      AS code_id,
                       max(e.resource_id)                        AS resource_id,
                       count(*)                                  AS hit_count,
                       min(e.occurred_at)                        AS first_at,
                       max(e.occurred_at)                        AS last_at,
                       max(e.severity::text)                     AS severity,
                       (array_agg(e.outcome ORDER BY e.occurred_at DESC))[1] AS last_outcome,
                       (array_agg(e.message ORDER BY e.occurred_at DESC))[1] AS last_message,
                       COALESCE(max(s.status), 'open')           AS state,
                       max(s.assignee)                           AS assignee,
                       max(s.note)                               AS note
                FROM error_occurrence e
                LEFT JOIN alert_state s
                       ON s.tenant_id = e.tenant_id AND s.fingerprint = e.fingerprint
                WHERE e.tenant_id = ? AND e.severity::text <> 'info' AND e.resolved_at IS NULL
                GROUP BY e.fingerprint
                ORDER BY hit_count DESC, last_at DESC
                LIMIT ?
                """;
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "fingerprint", rs.getString("fingerprint"),
                            "hitCount", rs.getInt("hit_count"),
                            "severity", rs.getString("severity"),
                            "lastOutcome", rs.getString("last_outcome"),
                            "firstAt", str(rs, "first_at"),
                            "lastAt", str(rs, "last_at"),
                            "lastMessage", rs.getString("last_message"),
                            "state", rs.getString("state"),
                            "assignee", rs.getString("assignee"),
                            "note", rs.getString("note")));
                }
            }
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    /** 错误记录明细（含 SKIPPED 留痕，用于追溯）。 */
    public String occurrences(int limit) throws SQLException {
        String sql = """
                SELECT eo.id, eo.fingerprint, eo.outcome, eo.severity::text AS severity,
                       eo.attempt, eo.run_id, eo.run_node_id, eo.resource_id,
                       eo.occurred_at, eo.message
                FROM error_occurrence eo
                WHERE eo.tenant_id = ?
                ORDER BY eo.id DESC LIMIT ?
                """;
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "id", rs.getLong("id"),
                            "fingerprint", rs.getString("fingerprint"),
                            "outcome", rs.getString("outcome"),
                            "severity", rs.getString("severity"),
                            "attempt", rs.getInt("attempt"),
                            "runId", rs.getLong("run_id"),
                            "runNodeId", rs.getLong("run_node_id"),
                            "resourceId", rs.getObject("resource_id"),
                            "occurredAt", str(rs, "occurred_at"),
                            "message", rs.getString("message")));
                }
            }
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    // ---------------------------------------------------------------- 工具

    /** 把已序列化好的 JSON 片段原样嵌入（避免二次转义）。 */
    private static Jsons.Raw raw(String json) {
        return new Jsons.Raw(json == null ? "{}" : json);
    }

    private static String str(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : o.toString();
    }

    private static String shortVersion(String v) {
        if (v == null) {
            return "";
        }
        int i = v.indexOf(" on ");
        return i > 0 ? v.substring(0, i) : v;
    }

    private void bumpExperimentVersion(Connection c, long experimentId) throws SQLException {
        queryMapper.bumpExperimentVersion(tenantId, experimentId);
    }

    /** 画布编译器：当前只接受 开始 -> 流程... -> 结束 的线性主链。 */
    static CompiledGraph compileExperimentGraph(String graphJson) {
        Object parsed = parseJson(graphJson);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("画布格式不对：根节点必须是对象");
        }
        Object nodesObj = root.get("nodes");
        Object edgesObj = root.get("edges");
        if (!(nodesObj instanceof java.util.List<?> nodesRaw)
                || !(edgesObj instanceof java.util.List<?> edgesRaw)) {
            throw new IllegalArgumentException("画布格式不对：需要 nodes 与 edges 数组");
        }

        java.util.Map<String, CanvasNode> nodes = new java.util.LinkedHashMap<>();
        for (Object one : nodesRaw) {
            if (!(one instanceof Map<?, ?> nodeMap)) {
                throw new IllegalArgumentException("画布格式不对：nodes 里有非法节点");
            }
            String id = asString(nodeMap.get("id"));
            Map<?, ?> data = asMap(nodeMap.get("data"), "节点 data");
            String kind = asString(data.get("kind"));
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("画布里有节点缺少 id");
            }
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("节点 " + id + " 缺少 kind");
            }
            if (nodes.put(id, new CanvasNode(id, kind, asLong(data.get("workflowId")))) != null) {
                throw new IllegalArgumentException("画布里有重复节点 id：" + id);
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("画布不能为空");
        }

        java.util.Map<String, java.util.List<String>> outgoing = new java.util.HashMap<>();
        java.util.Map<String, Integer> incoming = new java.util.HashMap<>();
        for (String id : nodes.keySet()) {
            outgoing.put(id, new java.util.ArrayList<>());
            incoming.put(id, 0);
        }
        for (Object one : edgesRaw) {
            if (!(one instanceof Map<?, ?> edgeMap)) {
                throw new IllegalArgumentException("画布格式不对：edges 里有非法连线");
            }
            String source = asString(edgeMap.get("source"));
            String target = asString(edgeMap.get("target"));
            if (!nodes.containsKey(source) || !nodes.containsKey(target)) {
                throw new IllegalArgumentException("连线引用了不存在的节点：" + source + " -> " + target);
            }
            outgoing.get(source).add(target);
            incoming.put(target, incoming.get(target) + 1);
        }

        java.util.List<CanvasNode> starts = nodes.values().stream()
                .filter(n -> "start".equals(n.kind())).toList();
        java.util.List<CanvasNode> ends = nodes.values().stream()
                .filter(n -> "end".equals(n.kind())).toList();
        if (starts.size() != 1 || ends.size() != 1) {
            throw new IllegalArgumentException("当前只支持一条线性主链：需要且只需要 1 个开始节点和 1 个结束节点");
        }

        java.util.Set<String> unsupportedKinds = new java.util.LinkedHashSet<>();
        for (CanvasNode node : nodes.values()) {
            int in = incoming.get(node.id());
            int out = outgoing.get(node.id()).size();
            switch (node.kind()) {
                case "start" -> {
                    if (in != 0 || out != 1) {
                        throw new IllegalArgumentException("开始节点必须恰好连出 1 条线");
                    }
                }
                case "end" -> {
                    if (in != 1 || out != 0) {
                        throw new IllegalArgumentException("结束节点必须恰好接入 1 条线");
                    }
                }
                case "workflow" -> {
                    if (node.workflowId() == null || node.workflowId() <= 0) {
                        throw new IllegalArgumentException("流程节点 " + node.id() + " 还没选具体流程");
                    }
                    if (in != 1 || out != 1) {
                        throw new IllegalArgumentException("当前只支持线性主链：流程节点必须各有 1 条入线和 1 条出线");
                    }
                }
                default -> unsupportedKinds.add(node.kind());
            }
        }
        if (!unsupportedKinds.isEmpty()) {
            throw new IllegalArgumentException("当前保存只支持 开始/结束/流程 节点；请先移除："
                    + String.join("、", unsupportedKinds));
        }

        java.util.List<String> order = new java.util.ArrayList<>();
        java.util.Set<String> visited = new java.util.LinkedHashSet<>();
        String current = starts.get(0).id();
        while (true) {
            if (!visited.add(current)) {
                throw new IllegalArgumentException("画布里存在环，当前只支持线性主链");
            }
            order.add(current);
            CanvasNode node = nodes.get(current);
            if ("end".equals(node.kind())) {
                break;
            }
            java.util.List<String> next = outgoing.get(current);
            if (next.size() != 1) {
                throw new IllegalArgumentException("当前只支持线性主链：节点 " + current + " 的出线数量不为 1");
            }
            current = next.get(0);
        }
        if (visited.size() != nodes.size()) {
            throw new IllegalArgumentException("画布里有未接入主链的节点，请删掉或连到主链后再保存");
        }

        // 遍历序 -> 编译结果：workflow 节点各成一行（seq 1..N），
        // 相邻**流程节点之间**一条偏序边（开始/结束不是流程，不产生边）。
        java.util.List<CompiledStep> steps = new java.util.ArrayList<>();
        java.util.List<CompiledEdge> edges = new java.util.ArrayList<>();
        int seq = 1;
        String prevWorkflow = null;
        for (String nodeId : order) {
            CanvasNode node = nodes.get(nodeId);
            if (!"workflow".equals(node.kind())) {
                continue;
            }
            steps.add(new CompiledStep(nodeId, node.workflowId(), seq++));
            if (prevWorkflow != null) {
                edges.add(new CompiledEdge(prevWorkflow, nodeId));
            }
            prevWorkflow = nodeId;
        }
        return new CompiledGraph(steps, edges);
    }

    /** 只取流程 id（调用方用）。 */
    static java.util.List<Long> compileExperimentGraphWorkflowIds(String graphJson) {
        return compileExperimentGraph(graphJson).workflowIds();
    }

    /** 由线性流程 id 列表构造"链式"编译结果（写入口 / 新建实验用）。 */
    static CompiledGraph linearGraph(java.util.List<Long> workflowIds) {
        java.util.List<CompiledStep> steps = new java.util.ArrayList<>();
        java.util.List<String> nodeIds = new java.util.ArrayList<>();
        int seq = 1;
        for (Long wf : workflowIds) {
            if (wf == null) {
                continue;
            }
            String nodeId = "n" + seq;
            steps.add(new CompiledStep(nodeId, wf, seq));
            nodeIds.add(nodeId);
            seq++;
        }
        java.util.List<CompiledEdge> edges = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < nodeIds.size(); i++) {
            edges.add(new CompiledEdge(nodeIds.get(i), nodeIds.get(i + 1)));
        }
        return new CompiledGraph(steps, edges);
    }

    private static Map<?, ?> asMap(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(what + " 不是对象");
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("workflowId 不是合法数字：" + value);
        }
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new MiniJson(json).parseValue();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("画布 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    private record CanvasNode(String id, String kind, Long workflowId) { }

    /** 编译出的一步：画布流程节点 -> 一行 experiment_process。 */
    record CompiledStep(String nodeId, long workflowId, int seq) { }

    /** 编译出的一条偏序边：前驱节点 -> 后继节点（画布节点 id 层面）。 */
    record CompiledEdge(String fromNodeId, String toNodeId) { }

    /** 画布编译结果：步骤（含 seq）+ 偏序边。 */
    record CompiledGraph(java.util.List<CompiledStep> steps, java.util.List<CompiledEdge> edges) {
        java.util.List<Long> workflowIds() {
            return steps.stream().map(CompiledStep::workflowId).toList();
        }
    }

    /** 极简 JSON 解析：只处理当前画布保存用到的对象/数组/标量。 */
    private static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Object parseValue() {
            skip();
            if (i >= s.length()) {
                return null;
            }
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return parseNumberOrToken();
        }

        private Map<String, Object> parseObject() {
            expect('{');
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            skip();
            if (peek('}')) {
                expect('}');
                return out;
            }
            while (true) {
                String key = parseString();
                skip();
                expect(':');
                out.put(key, parseValue());
                skip();
                if (peek('}')) {
                    expect('}');
                    return out;
                }
                expect(',');
            }
        }

        private java.util.List<Object> parseArray() {
            expect('[');
            java.util.List<Object> out = new java.util.ArrayList<>();
            skip();
            if (peek(']')) {
                expect(']');
                return out;
            }
            while (true) {
                out.add(parseValue());
                skip();
                if (peek(']')) {
                    expect(']');
                    return out;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("非法 unicode 转义");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("未知转义：" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Object parseNumberOrToken() {
            int j = i;
            while (j < s.length() && ",]} \n\r\t".indexOf(s.charAt(j)) < 0) {
                j++;
            }
            String token = s.substring(i, j);
            i = j;
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                return token;
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean peek(char c) {
            skip();
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            skip();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("期待 '" + c + "'");
            }
            i++;
        }
    }

    /**
     * 设备级告警（目前只有"离线"）。
     *
     * <p>和 {@link #alerts(int)} 的区别：那个是**执行错误**（命令失败/超时），
     * 这个是**设备状态**（连不上）。处置方式完全不同——
     * 执行错误看 run_command，离线要先查网络和电源。
     */
    public String deviceAlerts(int limit) throws SQLException {
        String sql = """
                SELECT a.fingerprint, a.status, a.note, a.assignee,
                       a.closed_at, a.updated_at,
                       r.code AS device_code, r.name AS device_name,
                       ds.online, ds.last_heartbeat,
                       CASE WHEN ds.online = false
                            THEN EXTRACT(EPOCH FROM (now() - ds.last_heartbeat))::bigint
                            ELSE NULL END AS offline_sec
                FROM alert_state a
                LEFT JOIN resource_node r
                       ON a.fingerprint = 'DEVICE-OFFLINE|' || r.id
                LEFT JOIN device_state ds
                       ON ds.resource_id = r.id AND ds.tenant_id = a.tenant_id
                WHERE a.tenant_id = ? AND a.fingerprint LIKE 'DEVICE-OFFLINE|%'
                ORDER BY CASE WHEN a.status = 'open' THEN 0 ELSE 1 END,
                         a.updated_at DESC
                LIMIT ?
                """;
        List<String> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Jsons.obj(
                            "fingerprint", rs.getString("fingerprint"),
                            "status", rs.getString("status"),
                            "deviceCode", rs.getString("device_code"),
                            "deviceName", rs.getString("device_name"),
                            "online", rs.getBoolean("online"),
                            "offlineSec", rs.getObject("offline_sec"),
                            "lastHeartbeat", rs.getTimestamp("last_heartbeat") == null
                                    ? null : rs.getTimestamp("last_heartbeat").toInstant().toString(),
                            "note", rs.getString("note"),
                            "assignee", rs.getString("assignee"),
                            "closedAt", rs.getTimestamp("closed_at") == null
                                    ? null : rs.getTimestamp("closed_at").toInstant().toString()));
                }
            }
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }
}
