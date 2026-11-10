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
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectPolicies(tenantId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "errorCode", row.get("code"),
                    "scope", row.get("scope"),
                    "strategy", row.get("strategy"),
                    "maxAttempts", mint(row, "maxAttempts"),
                    "priority", mint(row, "priority"),
                    "enabled", Boolean.TRUE.equals(row.get("enabled")),
                    "origin", row.get("origin")));
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    public String recentRuns(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectRecentRuns(tenantId, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "runNo", row.get("runNo"),
                    "runType", row.get("runType"),
                    "attendance", row.get("attendance"),
                    "status", row.get("status"),
                    "priority", mint(row, "priority"),
                    "nodeCount", mint(row, "nodeCount"),
                    "actualStart", mstr(row, "actualStart"),
                    "actualEnd", mstr(row, "actualEnd"),
                    "createdAt", mstr(row, "createdAt")));
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    /** 单个 run 的详情：基础信息 + 节点 + 最近事件。 */
    public String runDetail(long runId) throws SQLException {
        java.util.Map<String, Object> row = queryMapper.selectRunDetail(tenantId, runId);
        if (row == null) {
            return null;
        }
        String base = Jsons.obj(
                "id", row.get("id"),
                "runNo", row.get("runNo"),
                "runType", row.get("runType"),
                "attendance", row.get("attendance"),
                "status", row.get("status"),
                "priority", mint(row, "priority"),
                "pauseKind", row.get("pauseKind"),
                "pauseReason", row.get("pauseReason"),
                "pauseNodeKey", row.get("pauseNodeKey"),
                "actualStart", mstr(row, "actualStart"),
                "actualEnd", mstr(row, "actualEnd"),
                "createdAt", mstr(row, "createdAt"),
                "input", mraw(row, "input"));

        List<String> nodes = new ArrayList<>();
        for (java.util.Map<String, Object> n : queryMapper.selectRunDetailNodes(runId)) {
            nodes.add(Jsons.obj(
                    "id", n.get("id"),
                    "nodeKey", n.get("nodeKey"),
                    "name", n.get("name"),
                    "status", n.get("status"),
                    "attempt", mint(n, "attempt"),
                    "resourceId", n.get("resourceId"),
                    "resourceCode", n.get("resourceCode"),
                    "capabilityId", n.get("capabilityId"),
                    "estimateSec", mint(n, "estimateSec"),
                    "level", mint(n, "level"),
                    "parentNodeId", n.get("parentNodeId"),
                    "loopIndex", mint(n, "loopIndex"),
                    "stepSeq", n.get("stepSeq"),
                    "reason", n.get("reason"),
                    "errorCode", n.get("errorCode")));
        }

        List<String> events = new ArrayList<>();
        for (java.util.Map<String, Object> e : queryMapper.selectRunEvents(runId)) {
            events.add(Jsons.obj(
                    "at", mstr(e, "at"),
                    "type", e.get("type"),
                    "payload", mraw(e, "payload")));
        }

        return Jsons.obj(
                "run", raw(base),
                "nodes", raw(Jsons.arr(nodes)),
                "events", raw(Jsons.arr(events)));
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
        for (java.util.Map<String, Object> row
                : queryMapper.selectRunningProcessStatus(tenantId)) {
            long runId = ((Number) row.get("id")).longValue();
            runs.add(Jsons.obj(
                    "runId", runId,
                    "runNo", row.get("runNo"),
                    "attendance", row.get("attendance"),
                    "startedAt", String.valueOf(row.get("actualStart")),
                    "total", mint(row, "total"),
                    "done", mint(row, "done"),
                    "manual", mint(row, "manual"),
                    "failed", mint(row, "failed"),
                    "current", new Jsons.Raw(currentNodeJson(runId))));
        }
        return Jsons.obj("running", runs.size(),
                "items", new Jsons.Raw(Jsons.arr(runs)));
    }

    /** 当前卡住/正在执行的节点（取最早一个未完成的业务节点）。 */
    private String currentNodeJson(long runId) throws SQLException {
        java.util.Map<String, Object> row = queryMapper.selectCurrentNode(runId);
        if (row == null) {
            return "null";
        }
        return Jsons.obj(
                "nodeId", row.get("id"),
                "nodeKey", row.get("nodeKey"),
                "name", row.get("name"),
                "status", row.get("status"),
                "attempt", mint(row, "attempt"),
                "resourceId", row.get("resourceId"),
                "reason", row.get("reason"),
                "steps", row.get("steps"));
    }

    /**
     * 拓扑：岛屿 → 设备。
     *
     * <p>层级来自 {@code resource_node}（kind=island / device），
     * 型号信息来自 {@code device}（经 device_instance 关联）。
     */
    public String topologyJson() throws SQLException {
        List<String> islands = new ArrayList<>();
        Map<Long, String[]> head = new java.util.LinkedHashMap<>();
        Map<Long, List<String>> devices = new java.util.LinkedHashMap<>();

        for (java.util.Map<String, Object> row : queryMapper.selectTopology(tenantId)) {
            long iid = ((Number) row.get("id")).longValue();
            head.putIfAbsent(iid, new String[]{
                    String.valueOf(row.get("code")), String.valueOf(row.get("name"))});
            devices.computeIfAbsent(iid, k -> new ArrayList<>());
            // devId / instanceId 为空说明实例已软删（或从未建过），拓扑里不显示
            if (row.get("devId") != null && row.get("instanceId") != null) {
                devices.get(iid).add(Jsons.obj(
                        "resourceId", row.get("devId"),
                        "code", row.get("devCode"),
                        "name", row.get("devName"),
                        "model", row.get("model"),
                        "vendor", row.get("vendor"),
                        "capability", row.get("capability"),
                        // 设备示意图（/devices/xxx.svg）。没有就由前端退回能力色块
                        "icon", row.get("icon"),
                        "instanceId", row.get("instanceId"),
                        "ip", row.get("ip"),
                        "driverEndpoint", row.get("driverEndpoint"),
                        "driverCode", row.get("driverCode"),
                        "x", row.get("posX"),
                        "y", row.get("posY"),
                        "online", Boolean.TRUE.equals(row.get("online")),
                        "status", row.get("devStatus"),
                        "currentMethod", row.get("currentMethod"),
                        "hasPlate", Boolean.TRUE.equals(row.get("hasPlate"))));
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
        for (java.util.Map<String, Object> row
                : queryMapper.selectWorkflowTimelineNodes(tenantId, workflowId)) {
            String key = String.valueOf(row.get("nodeKey"));
            long manual = mint(row, "manual");
            Object p50Obj = row.get("p50");
            double p50 = p50Obj instanceof Number n ? n.doubleValue() : 0d;
            boolean p50Null = p50Obj == null;
            Object nObj = row.get("n");
            long histN = nObj instanceof Number n ? n.longValue() : 0L;
            boolean histNNull = nObj == null;
            Object devObj = row.get("devEst");
            long devEst = devObj instanceof Number n ? n.longValue() : 0L;
            boolean devNull = devObj == null;

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
            Object ridObj = row.get("resourceId");
            // 0 是历史数据里的"没有设备"，别当成一台设备
            Long rid = (ridObj == null || !(ridObj instanceof Number)
                    || ((Number) ridObj).longValue() == 0)
                    ? null : ((Number) ridObj).longValue();
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
            long startSec = Math.max(cursor, resFree);
            long end = startSec + dur;
            cursor = end;
            if (rid != null) {
                freeAt.put(rid, end);
            }
            items.add(Jsons.obj(
                    "nodeKey", key,
                    "name", row.get("name"),
                    "seq", items.size() + 1,
                    "resourceId", rid,
                    "resourceCode", rid == null ? null : resCode.get(rid),
                    "startSec", startSec,
                    "endSec", end,
                    "durationSec", dur,
                    "estimateSource", source,
                    "estimateSamples", histNNull ? 0 : histN,
                    "estimated", estimated));
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
        try {
            Double remain = queryMapper.selectRemainingSec(tenantId, resourceId);
            return remain == null ? 0 : Math.round(remain);
        } catch (RuntimeException e) {
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
        java.util.Set<Long> resources = new java.util.LinkedHashSet<>(
                queryMapper.selectWorkflowResources(tenantId, workflowId));
        if (resources.isEmpty()) {
            return Jsons.obj("workflowId", workflowId, "waitSec", 0, "known", false,
                    "detail", "这条流程没有绑定设备资源，不会排队");
        }

        long maxWait = 0;
        List<String> busy = new ArrayList<>();
        for (Long rid : resources) {
            java.util.Map<String, Object> holder =
                    queryMapper.selectBusyResourceHolder(tenantId, rid);
            if (holder != null) {
                Object rem = holder.get("remain");
                long remain = rem instanceof Number n ? Math.round(n.doubleValue()) : 0L;
                maxWait = Math.max(maxWait, remain);
                busy.add(Jsons.obj(
                        "resourceId", rid,
                        "resourceCode", holder.get("resCode"),
                        "holderRunNo", holder.get("runNo"),
                        "holderRunId", holder.get("runId"),
                        "remainSec", remain));
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
        for (java.util.Map<String, Object> row : queryMapper.selectWorkflowRuns(
                tenantId, workflowId, Math.max(1, Math.min(limit, 100)))) {
            Object dur = row.get("durationSec");
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "runNo", row.get("runNo"),
                    "status", row.get("status"),
                    "startedAt", mstr(row, "actualStart"),
                    "finishedAt", mstr(row, "actualEnd"),
                    "durationSec", dur instanceof Number n ? n.intValue() : null,
                    "total", mint(row, "total"),
                    "done", mint(row, "done"),
                    "manual", mint(row, "manual"),
                    "failed", mint(row, "failed")));
        }
        return Jsons.obj("workflowId", workflowId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    public String experimentsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        // 列表页要能一眼看出：哪个岛、什么内容、第几版、编了几条流程
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperiments(tenantId, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "experimentNo", row.get("experimentNo"),
                    "name", row.get("name"),
                    "status", row.get("status"),
                    "plannedAt", mstr(row, "plannedAt"),
                    "startedAt", mstr(row, "startedAt"),
                    "finishedAt", mstr(row, "finishedAt"),
                    "workflowId", row.get("workflowId"),
                    "workflowName", row.get("workflowName"),
                    "version", mint(row, "version"),
                    "priority", mint(row, "priority"),
                    "remark", row.get("remark"),
                    "islandCode", row.get("islandCode"),
                    "islandName", row.get("islandName"),
                    "workcellNames", row.get("workcellNames"),
                    "flowCount", mint(row, "flowCount")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 某台设备（resource_node.id）支持的方法。 */
    public String deviceMethodsJson(long resourceId) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectDeviceMethods(resourceId)) {
            // 参数定义原样透出：{seq,name,desc,type,default,required,control_type,unit,range}
            Object params = row.get("params");
            items.add(Jsons.obj(
                    "method", row.get("method"),
                    "name", row.get("name"),
                    "capability", row.get("capability"),
                    "estimateSec", row.get("estimateSec"),
                    "timeoutSec", row.get("timeoutSec"),
                    // 可中断标志：现场确认清单后置 true，暂停才会真的停掉这一步
                    "interruptible", Boolean.TRUE.equals(row.get("interruptible")),
                    "params", params == null ? new Jsons.Raw("[]")
                            : new Jsons.Raw(String.valueOf(params))));
        }
        return Jsons.obj("resourceId", resourceId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 时长模型（命令 → p50/p90），甘特预测与仿真共用。 */
    public String durationsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectDurations(tenantId, Math.max(1, Math.min(limit, 300)))) {
            items.add(Jsons.obj(
                    "method", row.get("method"),
                    "p50Ms", row.get("p50Ms"),
                    "p90Ms", row.get("p90Ms"),
                    "minMs", row.get("minMs"),
                    "maxMs", row.get("maxMs"),
                    "sampleCount", row.get("sampleCount")));
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
        java.time.Instant start = runStart == null ? null : runStart.toInstant();
        if (start == null) {
            return Jsons.obj("runId", runId, "items", new Jsons.Raw(Jsons.arr(List.of())));
        }

        List<String> items = new ArrayList<>();
        long totalMs = 0;
        for (java.util.Map<String, Object> row : queryMapper.selectTimelineNodes(runId)) {
            java.time.Instant st = minst(row.get("actualStart"));
            if (st == null) {
                continue;
            }
            long offsetMs = java.time.Duration.between(start, st).toMillis();
            Object dur = row.get("durMs");
            double rawDur = dur instanceof Number n ? n.doubleValue() : 0d;
            long durMs = (long) Math.max(0d, rawDur);
            totalMs = Math.max(totalMs, offsetMs + durMs);
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "nodeKey", row.get("nodeKey"),
                    "name", row.get("name"),
                    "status", row.get("status"),
                    "level", mint(row, "level"),
                    "parentNodeId", row.get("parentNodeId"),
                    "loopIndex", mint(row, "loopIndex"),
                    "stepSeq", row.get("stepSeq"),
                    "resourceId", row.get("resourceId"),
                    "offsetMs", offsetMs,
                    "durationMs", durMs));
        }
        return Jsons.obj(
                "runId", runId,
                "startedAt", start.toString(),
                "totalMs", totalMs,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 实验编排：该实验由哪些流程按什么顺序组成。 */
    /**
     * 设置某一步的循环次数。
     *
     * @param loopCount null/空 = 不循环；数字或 {@code ${var}} 表示次数
     */
    public int setLoopCount(long experimentId, int seq, String loopCount) throws SQLException {
        return queryMapper.updateExperimentProcessLoopCount(tenantId, experimentId, seq,
                loopCount == null || loopCount.isBlank() ? null : loopCount.trim());
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
        for (java.util.Map<String, Object> pn
                : queryMapper.selectWorkflowTaskNames(tenantId, workflowId)) {
            planName.put(String.valueOf(pn.get("nodeKey")), (String) pn.get("name"));
        }

        java.util.Map<String, String[]> actual = new java.util.HashMap<>();  // key → [status,name,err]
        for (java.util.Map<String, Object> an
                : queryMapper.selectRunNodeStates(tenantId, runId)) {
            actual.put(String.valueOf(an.get("nodeKey")), new String[]{
                    (String) an.get("status"), (String) an.get("name"),
                    (String) an.get("errorCode")});
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
        java.util.Map<String, Object> header =
                queryMapper.selectExperimentRunHeader(tenantId, runId);
        if (header == null) {
            throw new IllegalArgumentException("实验执行不存在: " + runId);
        }
        long expId = ((Number) header.get("experimentId")).longValue();
        String runStatus = String.valueOf(header.get("status"));
        int expanded = mint(header, "expandedSteps");

        // 计划：每条流程 × 循环次数
        java.util.List<long[]> plan = new ArrayList<>();          // [seq, loop]
        java.util.Map<String, String> wfName = new java.util.HashMap<>();
        java.util.Map<Integer, Long> wfId = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperimentProcessPlan(tenantId, expId)) {
            int seq = mint(row, "seq");
            long wf = ((Number) row.get("workflowId")).longValue();
            wfId.put(seq, wf);
            wfName.put(seq + ":" + wf,
                    row.get("name") == null ? null : String.valueOf(row.get("name")));
            int times = 1;
            Object loopObj = row.get("loopCount");
            String loop = loopObj == null ? null : String.valueOf(loopObj);
            if (loop != null && loop.trim().matches("\\d+")) {
                times = Math.max(1, Integer.parseInt(loop.trim()));
            }
            for (int i = 0; i < times; i++) {
                plan.add(new long[]{seq, i});
            }
        }

        // 实际执行行
        java.util.Map<String, String[]> done = new java.util.HashMap<>();   // "seq:loop" → [status,runId,err]
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperimentRunStepRows(tenantId, runId)) {
            Object runObj = row.get("runId");
            done.put(mint(row, "seq") + ":" + mint(row, "loopIndex"), new String[]{
                    row.get("status") == null ? null : String.valueOf(row.get("status")),
                    runObj == null ? null : String.valueOf(((Number) runObj).longValue()),
                    row.get("error") == null ? null : String.valueOf(row.get("error"))});
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
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperimentRuns(tenantId, experimentId)) {
            Object dur = row.get("durationSec");
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "status", row.get("status"),
                    "expandedSteps", mint(row, "expandedSteps"),
                    "currentSeq", mint(row, "currentSeq"),
                    "startedAt", mstr(row, "startedAt"),
                    "finishedAt", mstr(row, "finishedAt"),
                    "durationSec", dur instanceof Number n ? Math.round(n.doubleValue()) : null,
                    "pauseKind", row.get("pauseKind"),
                    "pauseReason", row.get("pauseReason"),
                    "controlState", row.get("controlState"),
                    "stopRequested", Boolean.TRUE.equals(row.get("stopRequested")),
                    "error", row.get("error")));
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 实验变量列表。 */
    public String experimentVariablesJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperimentVariables(tenantId, experimentId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "name", row.get("name"),
                    "type", row.get("type"),
                    "defaultValue", row.get("defaultValue"),
                    "description", row.get("description")));
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
        try (Connection c = db.open()) {
            try {
                experimentMapper.deleteExperimentVariables(tenantId, experimentId);
                int n = 0;
                for (String[] v : vars) {
                    // v = [name, type, defaultValue, description]
                    if (v[0] == null || v[0].isBlank()) {
                        continue;
                    }
                    experimentMapper.insertExperimentVariable(tenantId, experimentId,
                            v[0].trim(),
                            v[1] == null || v[1].isBlank() ? "number" : v[1],
                            v[2], v[3]);
                    n++;
                }
                return n;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    public String experimentProcessesJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperimentProcesses(tenantId, experimentId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "workflowId", row.get("workflowId"),
                    "workflowName", row.get("workflowName"),
                    "workflowCode", row.get("workflowCode"),
                    // seq 用输出序号而不是库里的 seq：原实现如此（列表序号从 1 起）
                    "seq", items.size() + 1,
                    "runMode", row.get("runMode"),
                    "runAfter", mint(row, "runAfter"),
                    "nodeCount", mint(row, "nodeCount")));
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
            exist.addAll(queryMapper.selectExistingWorkflowIds(tenantId, workflowIds));
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
        for (java.util.Map<String, Object> row : queryMapper.selectUsers(tenantId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "username", row.get("username"),
                    "displayName", row.get("displayName"),
                    "role", row.get("role"),
                    "enabled", Boolean.TRUE.equals(row.get("enabled")),
                    "lastLoginAt", mstr(row, "lastLoginAt"),
                    "createdAt", mstr(row, "createdAt")));
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
        try {
            return queryMapper.insertUser(tenantId, username.trim(),
                    displayName == null ? username.trim() : displayName,
                    effectiveRole, AuthService.pbkdf2(password, salt), salt);
        } catch (RuntimeException e) {
            // MyBatis/Spring 把唯一键冲突包成 DuplicateKeyException（RuntimeException），
            // 不再是 JDBC 的 SQLIntegrityConstraintViolationException。
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new IllegalArgumentException("用户名已存在: " + username);
            }
            throw e;
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

        try (Connection c = db.open()) {
            try {
                // 事务内第一句：串行化所有"用户角色/启用状态"写入。
                // 固定 key（不是 per-user）：保护判定看的是全局 admin 计数，
                // 锁必须覆盖"任意两个用户同时改角色"的交叉场景。
                queryMapper.lockUserRole();   // 只为拿到锁；返回值无意义

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
                return queryMapper.updateUserFields(tenantId, id, newRole, enabled,
                        hash, salt);
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
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectAuditLog(
                tenantId, filter, Math.max(1, Math.min(limit, 500)))) {
            Object before = row.get("before");
            Object after = row.get("after");
            items.add(Jsons.obj(
                    "occurredAt", mstr(row, "occurredAt"),
                    "username", row.get("username"),
                    "action", row.get("action"),
                    "entityType", row.get("entityType"),
                    "entityId", row.get("entityId"),
                    "result", row.get("result"),
                    "ip", row.get("ip"),
                    // null 保持 null（这里不能像 mraw 那样兜成 {}）
                    "before", before == null ? null : new Jsons.Raw(String.valueOf(before)),
                    "after", after == null ? null : new Jsons.Raw(String.valueOf(after))));
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
        return queryMapper.updateDevicePosition(tenantId, resourceId, x, y);
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
        int deviceCount = queryMapper.countDevicesUnderIsland(tenantId, islandId);
        if (deviceCount > 0) {
            throw new IllegalArgumentException(
                    "岛上还有 " + deviceCount + " 台设备，先把它们移出再删");
        }
        return queryMapper.deleteIslandNode(tenantId, islandId);
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
        return queryMapper.selectInstanceId(tenantId, resourceId);
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
        for (java.util.Map<String, Object> row
                : queryMapper.selectRobotPoints(tenantId, islandId)) {
            sb.append(mcsv(row.get("code"))).append(',')
              .append(mcsv(row.get("name"))).append(',')
              .append(mnum(row.get("x"))).append(',')
              .append(mnum(row.get("y"))).append(',')
              .append(mnum(row.get("z"))).append(',')
              .append(mcsv(row.get("deviceCode"))).append(',')
              .append(mcsv(row.get("action"))).append('\n');
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

    /** Map 版 num：numeric 列可能是 BigDecimal / Double，统一按 BigDecimal 格式化。 */
    private static String mnum(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof java.math.BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        if (o instanceof Number n) {
            return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return o.toString();
    }

    /** Map 版 csv：null 与 csv(String) 一样输出空串。 */
    private static String mcsv(Object o) {
        return csv(o == null ? null : String.valueOf(o));
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
        // c 只表示调用方已在事务内；Mapper 取同一条 Spring 绑定连接。
        return queryMapper.selectResourceIdByDeviceCode(tenantId, code);
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
        for (java.util.Map<String, Object> row
                : queryMapper.selectRobotPointDetails(tenantId, islandId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "robotResourceId", row.get("robotResourceId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "x", row.get("x"),
                    "y", row.get("y"),
                    "z", row.get("z"),
                    "action", row.get("action"),
                    "deviceResourceId", row.get("deviceResourceId"),
                    "deviceCode", row.get("deviceCode"),
                    "syncedAt", mstr(row, "syncedAt")));
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
        return queryMapper.syncRobotPoints(tenantId, islandId, robotResourceId);
    }

    /** 岛屿动线（设备节点坐标 + 搬运箭头）。 */
    public String islandFlowJson(long islandId) throws SQLException {
        String g = queryMapper.selectIslandFlow(tenantId, islandId);
        return g == null ? "{\"nodes\":[],\"edges\":[]}" : g;
    }

    /** 保存岛屿动线（整体替换）。 */
    public int saveIslandFlow(long islandId, String graphJson) throws SQLException {
        if (graphJson == null || graphJson.isBlank()) {
            throw new IllegalArgumentException("动线不能为空");
        }
        return queryMapper.upsertIslandFlow(tenantId, islandId, graphJson);
    }

    /** 实验编排图（nodes + edges）。 */
    public String experimentGraphJson(long experimentId) throws SQLException {
        String g = queryMapper.selectExperimentGraph(tenantId, experimentId);
        return g == null ? "{\"nodes\":[],\"edges\":[]}" : g;
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
        try (Connection c = db.open()) {
            try {
                queryMapper.upsertExperimentGraph(tenantId, experimentId, graphJson);
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
        for (java.util.Map<String, Object> row
                : queryMapper.selectChoreographies(tenantId, limit)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "nodeCount", mint(row, "nodeCount")));
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
                long id = queryMapper.insertExperiment(tenantId, no, name.trim(),
                        priority == null ? 5 : priority, islandId);
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
        // 空串归一化成 null = XML 里对应 <if> 不成立（不过滤）；
        // keyword 的 %..% 在这里拼好，SQL 里同一个参数用两次。
        String sev = severity == null || severity.isBlank() ? null : severity;
        String scp = scope == null || scope.isBlank() ? null : scope;
        String kw = keyword == null || keyword.isBlank()
                ? null : "%" + keyword + "%";
        for (java.util.Map<String, Object> row
                : queryMapper.selectErrorCodes(tenantId, sev, scp, kw)) {
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
        java.util.Map<String, Object> binding =
                queryMapper.selectNodeBinding(tenantId, workflowId, nodeKey);
        if (binding != null) {
            Object mp = binding.get("mp");
            if (mp != null) {
                params = String.valueOf(mp);
            }
            if (binding.get("resourceId") != null) {
                resourceId = ((Number) binding.get("resourceId")).longValue();
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
        try {
            Long id = queryMapper.insertErrorCode(tenantId, c,
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
     * 删除错误码（软删，`deleted_at`）。
     *
     * <p>为什么软删：{@code error_occurrence} 里挂着历史发生记录，
     * 硬删会连带丢掉"这个错以前出过多少次"；软删后列表不再显示，历史仍在。
     */
    public boolean deleteErrorCode(long id) throws SQLException {
        return queryMapper.softDeleteErrorCode(tenantId, id) > 0;
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
        return queryMapper.scopeCovered(tenantId, errorCodeId);
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
        return queryMapper.deleteRecoveryPolicy(tenantId, errorCodeId, policyId) > 0;
    }

    public String deviceModelsJson() throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectDeviceModels(tenantId)) {
            items.add(Jsons.obj(
                    "modelId", row.get("modelId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "deviceType", row.get("deviceType"),
                    "vendor", row.get("vendor"),
                    "icon", row.get("icon"),
                    "capability", row.get("capability"),
                    "deployed", mint(row, "deployed")));
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
        Long islFilter = islandId == null || islandId <= 0 ? null : islandId;
        for (java.util.Map<String, Object> row
                : queryMapper.selectDevicePool(tenantId, islFilter)) {
            items.add(Jsons.obj(
                    "resourceId", row.get("resourceId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "ip", row.get("ip"),
                    "capability", row.get("capability"),
                    // 型号库登记的原始图片路径。多数存量值指向源服务器
                    // /image/device/<uuid>.png（换环境 404），前端 iconFor()
                    // 会按 capability 映射回本地 /devices/<CAP>.svg。
                    "icon", row.get("icon"),
                    "islandId", row.get("islandId"),
                    "islandCode", row.get("islandCode")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 未挂到任何岛的设备（拖拽时的"设备池"）。 */
    public String unassignedDevicesJson() throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectUnassignedDevices(tenantId)) {
            items.add(Jsons.obj(
                    "resourceId", row.get("resourceId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "ip", row.get("ip"),
                    "capability", row.get("capability")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 流程图保存历史（按时间倒序）。 */
    public String graphHistoryJson(long workflowId) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectGraphHistory(tenantId, workflowId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "nodeCount", mint(row, "nodeCount"),
                    "edgeCount", mint(row, "edgeCount"),
                    "bytes", mint(row, "bytes"),
                    "savedAt", mstr(row, "savedAt")));
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
        String xml = queryMapper.selectGraphHistoryXml(tenantId, workflowId, historyId);
        if (xml == null) {
            throw new IllegalArgumentException("历史版本不存在: " + historyId);
        }
        // 注意：这里不写回，交由调用方走 saveGraph（复用校验与备份逻辑）
        return xml.length();   // 由 ApiServer 取 XML 再调 saveGraph
    }

    /** 读某历史版本的 XML 原文。 */
    public String graphHistoryXml(long workflowId, long historyId) throws SQLException {
        String xml = queryMapper.selectGraphHistoryXml(tenantId, workflowId, historyId);
        if (xml == null) {
            throw new IllegalArgumentException("历史版本不存在: " + historyId);
        }
        return xml;
    }

    /** 某个设备方法的参数定义（设计器填参用）。 */
    public String methodParamsJson(String method) throws SQLException {
        java.util.Map<String, Object> row = queryMapper.selectMethodParams(tenantId, method);
        if (row == null) {
            return Jsons.obj("method", method, "found", false,
                    "params", new Jsons.Raw("[]"));
        }
        Object params = row.get("params");
        return Jsons.obj(
                "method", row.get("method"),
                "name", row.get("name"),
                "capability", row.get("capability"),
                "found", true,
                "params", params == null ? new Jsons.Raw("[]")
                        : new Jsons.Raw(String.valueOf(params)));
    }

    /** 驱动端点列表（新增设备时选）。 */
    public String driversJson() throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectDrivers(tenantId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "protocol", row.get("protocol"),
                    "endpoint", row.get("endpoint"),
                    "status", row.get("status")));
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
        try {
            Long id = queryMapper.insertIsland(tenantId, code.trim(), name.trim(),
                    "/" + code.trim());
            return id == null ? 0L : id;
        } catch (RuntimeException e) {
            // Spring 把唯一键冲突包成 DuplicateKeyException（不再是 JDBC 的
            // SQLIntegrityConstraintViolationException），按 message 判断。
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new IllegalArgumentException("岛屿编号已存在: " + code);
            }
            throw e;
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
        // 不删 resource_node：run_node.resource_id 可能引用它（外键会挡住），
        // 且历史执行记录要能查回"这台设备当时在哪"。
        // 拓扑查询会过滤 deleted_at IS NOT NULL，界面上等于删掉了。
        queryMapper.softDeleteDeviceInstance(tenantId, resourceId);
        return 1;
    }

    /** 能力字典（设计器下拉用）：code + 可用设备数。 */
    public String capabilitiesJson() throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectCapabilities(tenantId)) {
            items.add(Jsons.obj(
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "devices", mint(row, "devices")));
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
        Long id = queryMapper.insertIntervention(tenantId,
                manualWindowId > 0 ? manualWindowId : null,
                runId, experimentRunId,
                operator == null ? "unknown" : operator,
                action, plateBarcode, sampleId, fromLocation, toLocation,
                result == null ? "unknown" : result, note);
        return id == null ? 0L : id;
    }

    /** L-01：人工介入记录列表（最新优先）。 */
    public String interventionRecordsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectInterventionRecords(
                tenantId, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "manualWindowId", row.get("manualWindowId"),
                    "runId", row.get("runId"),
                    "experimentRunId", row.get("experimentRunId"),
                    "operator", row.get("operator"),
                    "action", row.get("decidedAction"),
                    "plateBarcode", row.get("plateBarcode"),
                    "sampleId", row.get("sampleId"),
                    "fromLocation", row.get("fromLocation"),
                    "toLocation", row.get("toLocation"),
                    "result", row.get("result"),
                    "note", row.get("note"),
                    "createdAt", row.get("createdAt")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 实验 Handoff 配置列表（N-10）。 */
    public String experimentHandoffsJson(long experimentId) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperimentHandoffs(tenantId, experimentId)) {
            items.add(Jsons.obj("fromWorkcellId", row.get("fromWorkcellId"),
                    "toWorkcellId", row.get("toWorkcellId"),
                    "carrier", row.get("carrier"),
                    "status", row.get("status"),
                    "remark", row.get("remark")));
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
                if (!experimentMapper.experimentExists(tenantId, experimentId)) {
                    return -1;
                }
                bound.addAll(experimentMapper.selectExperimentWorkcellIds(
                        tenantId, experimentId));
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
                // 原 executeBatch().length 返回批里的语句条数（即使 ON CONFLICT
                // DO NOTHING 也计入），所以这里按 pairs 条数累加，保持计数一致。
                for (long[] p : pairs) {
                    queryMapper.insertExperimentHandoff(tenantId, experimentId, p[0], p[1]);
                    n++;
                }
                return n;
            } catch (RuntimeException e) {
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
        String stateFilter = state == null || state.isBlank() ? null : state;
        for (java.util.Map<String, Object> row : queryMapper.selectCommandOutbox(
                tenantId, stateFilter, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "ledgerId", row.get("ledgerId"),
                    "runId", row.get("runId"),
                    "commandId", row.get("commandId"),
                    "deviceId", row.get("deviceId"),
                    "method", row.get("method"),
                    "state", row.get("state"),
                    "attempts", mint(row, "attempts"),
                    "createdAt", row.get("createdAt"),
                    "deliveredAt", row.get("deliveredAt")));
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
        String statusFilter = status == null || status.isBlank() ? null : status;
        for (java.util.Map<String, Object> row : queryMapper.selectHandoffs(
                tenantId, statusFilter, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "experimentId", row.get("experimentId"),
                    "experimentNo", row.get("experimentNo"),
                    "fromWorkcellId", row.get("fromWorkcellId"),
                    "fromName", row.get("fromName"),
                    "toWorkcellId", row.get("toWorkcellId"),
                    "toName", row.get("toName"),
                    "status", row.get("status"),
                    "carrier", row.get("carrier"),
                    "remark", row.get("remark")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：TransferTransaction 列表。 */
    public String transferTransactionsJson(String status, int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        String statusFilter = status == null || status.isBlank() ? null : status;
        for (java.util.Map<String, Object> row : queryMapper.selectTransferTransactions(
                tenantId, statusFilter, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "runId", row.get("runId"),
                    "plateBarcode", row.get("plateBarcode"),
                    "sourceResourceId", row.get("sourceResourceId"),
                    "targetResourceId", row.get("targetResourceId"),
                    "status", row.get("status"),
                    "errorCode", row.get("errorCode"),
                    "errorMessage", row.get("errorMessage"),
                    "createdAt", row.get("createdAt")));
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
        Long id = queryMapper.insertPositionEvidence(tenantId, plateBarcode,
                resourceId, type == null ? "MANUAL" : type.toUpperCase(),
                runId, operator, note);
        return id == null ? 0L : id;
    }

    /** P-08：物理位置证据列表（按板号可选过滤）。 */
    public String positionEvidenceJson(String plateBarcode, int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        String plateFilter = plateBarcode == null || plateBarcode.isBlank()
                ? null : plateBarcode;
        for (java.util.Map<String, Object> row : queryMapper.selectPositionEvidence(
                tenantId, plateFilter, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "plateBarcode", row.get("plateBarcode"),
                    "resourceId", row.get("resourceId"),
                    "evidenceType", row.get("evidenceType"),
                    "runId", row.get("runId"),
                    "operator", row.get("operator"),
                    "note", row.get("note"),
                    "createdAt", row.get("createdAt")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：隔离中的 run 列表（处置页用）。 */
    public String quarantinedRunsJson(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectQuarantinedRuns(
                tenantId, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("runId", row.get("id"),
                    "runNo", row.get("runNo"),
                    "errorCode", row.get("errorCode"),
                    "reason", row.get("reason"),
                    "createdAt", row.get("createdAt"),
                    "experimentId", row.get("experimentId")));
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
        for (java.util.Map<String, Object> row
                : queryMapper.selectExperimentWorkcells(tenantId, experimentId)) {
            items.add(Jsons.obj("workcellId", row.get("workcellId"),
                    "name", row.get("name")));
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
                if (!experimentMapper.experimentExists(tenantId, experimentId)) {
                    return -1;
                }
                experimentMapper.deleteExperimentWorkcells(tenantId, experimentId);
                int n = 0;
                if (workcellIds != null && !workcellIds.isEmpty()) {
                    // 同上：按进入批的有效行数计数，与 executeBatch().length 一致。
                    for (Long wid : workcellIds) {
                        if (wid == null || wid <= 0) {
                            continue;
                        }
                        queryMapper.insertExperimentWorkcell(tenantId, experimentId, wid);
                        n++;
                    }
                }
                return n;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /** 某能力下可选的设备方法（{@code device_method}）。 */
    public String methodsJson(String capability) throws SQLException {
        List<String> items = new ArrayList<>();
        for (String m : queryMapper.selectMethodsByCapability(tenantId, capability)) {
            items.add(Jsons.quote(m));
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
        return queryMapper.upsertAlertState(tenantId, fingerprint, st, assignee, note);
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
        return queryMapper.updateRecoveryPolicy(tenantId, id,
                strategy == null ? null : strategy.toUpperCase(),
                maxAttempts, priority, enabled);
    }

    /**
     * 生效校验：这条策略"改了会不会有用"。
     *
     * 三件事任一不满足，策略就是死的：错误码不存在 / 被更高优先级遮蔽 / 历史没出现过。
     */
    public String validatePolicy(String errorCode, String scope, int priority) throws SQLException {
        java.util.Map<String, Object> v =
                queryMapper.selectPolicyValidation(tenantId, errorCode, priority);
        if (v == null) {
            return Jsons.obj("ok", false, "warning", "查询失败");
        }
        int exists = mint(v, "codeExists");
        int hits = mint(v, "historyHits");
        int shadowed = mint(v, "shadowed");

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

    public String alerts(int limit) throws SQLException {
        return alerts(limit, "all");
    }

    /**
     * @param status {@code all} / {@code open} / {@code acked} / {@code closed}
     */
    public String alerts(int limit, String status) throws SQLException {
        String want = (status == null || status.isBlank() || "all".equalsIgnoreCase(status))
                ? null : status.toLowerCase();

        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : queryMapper.selectAlerts(
                tenantId, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj(
                    "fingerprint", row.get("fingerprint"),
                    "hitCount", mint(row, "hitCount"),
                    "severity", row.get("severity"),
                    "lastOutcome", row.get("lastOutcome"),
                    "firstAt", mstr(row, "firstAt"),
                    "lastAt", mstr(row, "lastAt"),
                    "lastMessage", row.get("lastMessage"),
                    "state", row.get("state"),
                    "assignee", row.get("assignee"),
                    "note", row.get("note")));
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    /** 错误记录明细（含 SKIPPED 留痕，用于追溯）。 */
    public String occurrences(int limit) throws SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectErrorOccurrences(tenantId,
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

    // ---------------------------------------------------------------- 工具

    /** 把已序列化好的 JSON 片段原样嵌入（避免二次转义）。 */
    private static Jsons.Raw raw(String json) {
        return new Jsons.Raw(json == null ? "{}" : json);
    }

    /** Map 版 {@link #raw(String)}：null 仍兜成 {}。 */
    private static Jsons.Raw mraw(java.util.Map<String, Object> row, String key) {
        Object o = row.get(key);
        return new Jsons.Raw(o == null ? "{}" : String.valueOf(o));
    }

    /** Map 里的时间列转 Instant（MyBatis 对 timestamptz 给 java.sql.Timestamp）。 */
    private static java.time.Instant minst(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof java.time.Instant i) {
            return i;
        }
        if (o instanceof java.time.OffsetDateTime d) {
            return d.toInstant();
        }
        return java.time.Instant.parse(o.toString());
    }

    /** Map 版 {@link #str(ResultSet, String)}：null 保留 null。 */
    private static String mstr(java.util.Map<String, Object> row, String key) {
        Object o = row.get(key);
        return o == null ? null : o.toString();
    }

    /** Map 版 rs.getInt：null 当 0。 */
    private static int mint(java.util.Map<String, Object> row, String key) {
        Object o = row.get(key);
        return o instanceof Number n ? n.intValue() : 0;
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
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : queryMapper.selectDeviceAlerts(tenantId, limit)) {
            java.time.Instant hb = minst(row.get("lastHeartbeat"));
            java.time.Instant closed = minst(row.get("closedAt"));
            items.add(Jsons.obj(
                    "fingerprint", row.get("fingerprint"),
                    "status", row.get("status"),
                    "deviceCode", row.get("deviceCode"),
                    "deviceName", row.get("deviceName"),
                    "online", Boolean.TRUE.equals(row.get("online")),
                    "offlineSec", row.get("offlineSec"),
                    "lastHeartbeat", hb == null ? null : hb.toString(),
                    "note", row.get("note"),
                    "assignee", row.get("assignee"),
                    "closedAt", closed == null ? null : closed.toString()));
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }
}
