package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import static com.labos.kernel.persistence.QJson.*;

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
        String xml = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.WorkflowWebMapper.class)
                .selectWorkflowXml(tenantId, workflowId);

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

    /**
     * 整体替换实验变量（先删后插）。
     *
     * <p>变量数量少（通常 <10），整体替换比逐条 diff 简单可靠，
     * 也不会出现"删了一半"的中间态——全程一个事务。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

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

    /** Spring 声明式事务（checked 异常也回滚）。 */

    /** Spring 声明式事务（checked 异常也回滚）。 */

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

    /** 保存实验编排图（整体替换），并同步编译成 experiment_process（含偏序边）。 */
    /** A 批：Spring 声明式事务。checked 异常也要回滚（默认只回滚 RuntimeException）。 */

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

    /**
     * 改一个错误码：码本身的属性（scope/severity/title）+
     * 它的**主策略**（strategy/max_attempts/params/enabled）。
     *
     * <p>一次事务：码改了但策略没改，运行时就还是改动前的行为，排查会更难。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 按型号部署一台新设备到岛。
     *
     * <p>编号自动递增（{@code Incubator_2C-20}），IP 留空由现场填——
     * 同一型号的多台设备 IP 一定不同，不能猜。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */


    /**
     * 删设备：软删实例 + 从资源树移除。
     *
     * <p>不硬删：历史 run_node 可能引用这台设备，硬删会断链。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

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

    /**
     * 全量替换实验 Handoff 配置。
     *
     * @return 写入条数；-1 实验不存在；-2 起点/终点无效或不在显式 Workcell 绑定内
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * N-10：人工确认一条 Handoff（配置 → confirmed），并落 L-01 介入记录。
     *
     * @return 更新条数（0 = 不存在/已确认）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

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

    /**
     * 全量替换实验 Workcell 绑定（N-10 显式绑定写入口）。
     *
     * @return 写入条数（空列表 = 清空绑定，回到资源树推导）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    // ---------------------------------------------------------------- 工具

    /** 极简 JSON 解析：只处理当前画布保存用到的对象/数组/标量。 */

}
