package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.ExperimentMapper;
import com.labos.kernel.persistence.Tx;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import static com.labos.kernel.persistence.QJson.*;
import com.labos.kernel.persistence.Jsons;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实验动作域的写 Service：{@code ExperimentActionController} 的交接确认端点。
 *
 * <p>按「Controller → Service → Mapper」从 {@code QueryService} 拆出，
 * 四条 SQL 归到已有的 {@link ExperimentMapper}（同域，不另起 Mapper）。
 *
 * <p>确认交接会连带落一条介入记录和一条已决策的人工窗口（N-08 联动），
 * 三者必须在同一个事务里；事务边界沿用
 * {@code try (Connection c = db.open())}，Mapper 经 Spring
 * {@code DataSourceUtils} 取同一条绑定连接。
 */
@Service
public class ExperimentActionService {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final ExperimentMapper experimentMapper;

    public ExperimentActionService(DbConfig db, ExperimentMapper experimentMapper) {
        this.db = db;
        this.experimentMapper = experimentMapper;
    }

    /** 确认一条工位交接，返回受影响行数；0 表示状态不允许确认。 */
    public int confirmHandoff(long experimentId, long fromWorkcellId, long toWorkcellId,
                              String operator, String note) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                String op = operator == null ? "api" : operator;
                int n = experimentMapper.confirmHandoffStatus(
                        TENANT, experimentId, fromWorkcellId, toWorkcellId);
                if (n > 0) {
                    Long recordId = experimentMapper.insertHandoffIntervention(
                            TENANT, op, String.valueOf(fromWorkcellId),
                            String.valueOf(toWorkcellId), note);
                    // N-08：确认动作同时落一条已决策 manual_window，形成直接联动
                    Long windowId = experimentMapper.insertHandoffWindow(
                            TENANT,
                            "handoff:" + experimentId + ":" + fromWorkcellId + ":"
                                    + toWorkcellId,
                            note == null ? "Handoff 人工确认" : note, op, note);
                    experimentMapper.linkHandoffRecords(TENANT, experimentId,
                            fromWorkcellId, toWorkcellId,
                            recordId == null ? 0L : recordId,
                            windowId == null ? 0L : windowId);
                }
                return n;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    public int setExperimentHandoffs(long experimentId,
                                     java.util.List<long[]> pairs) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                java.util.Set<Long> bound = new java.util.LinkedHashSet<>();
                if (!experimentMapper.experimentExists(TENANT, experimentId)) {
                    return -1;
                }
                bound.addAll(experimentMapper.selectExperimentWorkcellIds(
                        TENANT, experimentId));
                for (long[] p : pairs) {
                    if (p == null || p.length < 2 || p[0] <= 0 || p[1] <= 0 || p[0] == p[1]) {
                        return -2;
                    }
                    if (!bound.isEmpty() && (!bound.contains(p[0]) || !bound.contains(p[1]))) {
                        return -2;
                    }
                }
                experimentMapper.deleteExperimentHandoffs(TENANT, experimentId);
                int n = 0;
                // 原 executeBatch().length 返回批里的语句条数（即使 ON CONFLICT
                // DO NOTHING 也计入），所以这里按 pairs 条数累加，保持计数一致。
                for (long[] p : pairs) {
                    experimentMapper.insertExperimentHandoff(TENANT, experimentId, p[0], p[1]);
                    n++;
                }
                return n;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    public int setExperimentWorkcells(long experimentId, List<Long> workcellIds)
            throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                if (!experimentMapper.experimentExists(TENANT, experimentId)) {
                    return -1;
                }
                experimentMapper.deleteExperimentWorkcells(TENANT, experimentId);
                int n = 0;
                if (workcellIds != null && !workcellIds.isEmpty()) {
                    // 同上：按进入批的有效行数计数，与 executeBatch().length 一致。
                    for (Long wid : workcellIds) {
                        if (wid == null || wid <= 0) {
                            continue;
                        }
                        experimentMapper.insertExperimentWorkcell(TENANT, experimentId, wid);
                        n++;
                    }
                }
                return n;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    public int saveExperimentGraph(long experimentId, String graphJson) throws java.sql.SQLException {
        if (graphJson == null || graphJson.isBlank()) {
            throw new IllegalArgumentException("图不能为空");
        }
        CompiledGraph compiled = compileExperimentGraph(graphJson);
        // 旧实现（QueryService 拆分前）有 @Transactional(rollbackFor = Exception.class)，
        // 阶段2 Service 拆分弄丢了：校验抛错时 upsert 的脏 graph 已 auto-commit 落库。
        // 这里整个方法收回 Spring 事务（Tx.inTransaction），先校验再写库。
        return Tx.inTransaction(db, c -> {
            validateExperimentWorkflowIds(c, compiled.workflowIds());
            experimentMapper.upsertExperimentGraph(TENANT, experimentId, graphJson);
            replaceExperimentProcesses(c, experimentId, compiled, false);
            bumpExperimentVersion(c, experimentId);
            return compiled.steps().size();
        });
    }

    /** 实验编排：该实验由哪些流程按什么顺序组成。 */
    /**
     * 设置某一步的循环次数。
     *
     * @param loopCount null/空 = 不循环；数字或 {@code ${var}} 表示次数
     */
    public int setLoopCount(long experimentId, int seq, String loopCount) throws java.sql.SQLException {
        return experimentMapper.updateExperimentProcessLoopCount(TENANT, experimentId, seq,
                loopCount == null || loopCount.isBlank() ? null : loopCount.trim());
    }

    public int saveExperimentVariables(long experimentId, List<String[]> vars) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                experimentMapper.deleteExperimentVariables(TENANT, experimentId);
                int n = 0;
                for (String[] v : vars) {
                    // v = [name, type, defaultValue, description]
                    if (v[0] == null || v[0].isBlank()) {
                        continue;
                    }
                    experimentMapper.insertExperimentVariable(TENANT, experimentId,
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

    public int reorderExperiments(List<Long> orderedIds) throws java.sql.SQLException {
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
                        : experimentMapper.selectExperimentStatuses(TENANT, orderedIds)) {
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

                return experimentMapper.reorderExperimentPriorities(TENANT, orderedIds);
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    public String createExperiment(String name, String code, Long islandId,
                                   Integer priority, java.util.List<Long> workflowIds)
            throws java.sql.SQLException {
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
                long id = experimentMapper.insertExperiment(TENANT, no, name.trim(),
                        priority == null ? 5 : priority, islandId);
                if (workflowIds != null) {
                    // 走统一入口：写 process 的同时生成线性链式的偏序边，
                    // 避免"新建的实验有流程、没有边"这种半成品状态。
                    replaceExperimentProcesses(c, id, linearGraph(workflowIds), false);
                }
                return Jsons.obj("id", id, "experimentNo", no,
                        "processes", workflowIds == null ? 0 : workflowIds.size());
            } catch (java.sql.SQLException e) {
                throw e;
            }
        }
    }

    public int replaceExperimentProcesses(long experimentId, List<Long> workflowIds)
            throws java.sql.SQLException {
        CompiledGraph compiled = linearGraph(workflowIds);
        try (Connection c = db.open()) {
            try {
                validateExperimentWorkflowIds(c, compiled.workflowIds());
                int n = replaceExperimentProcesses(c, experimentId, compiled, true);
                return n;
            } catch (java.sql.SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * 这个实验是否已经用「画布编排」保存过（画布 graph 非空）。
     *
     * <p>为什么列表编排要问这个：编排表既是**画布的编译产物**，也是**列表形态的本体**
     * —— 两边都是「全量替换」同一个 {@code experiment_process}。前端画布加载时
     * **graph 非空就用 graph、否则用编排表派生** ⇒ 一旦某实验有了画布，
     * 再从列表改编排就会出现「画布一套、顺序一套」的分裂状态（画布还显示旧顺序）。
     * ⇒ 有画布时**拒绝列表编排写入**，保住"单一权威"。
     *
     * <p>没有画布的实验：写编排表后，画布页会从编排表自动派生出一致的线性画布
     * （前端 {@code ExperimentCanvas} 的加载逻辑），两边不会打架。
     */
    public boolean hasCanvasGraph(long experimentId) throws java.sql.SQLException {
        String g = experimentMapper.selectExperimentGraph(TENANT, experimentId);
        return g != null && !g.isBlank();
    }

    private int replaceExperimentProcesses(Connection c, long experimentId,
                                           CompiledGraph compiled, boolean bumpVersion)
            throws java.sql.SQLException {
        // 删 process 会级联删 experiment_process_edge（见 97_m95_process_edge.sql 的 FK），
        // 所以边不需要单独清 —— 整体替换天然一致。
experimentMapper.deleteExperimentProcesses(TENANT, experimentId);
        // nodeId -> 新生成的 process.id，用于把画布上的边翻译成 process 之间的边
        java.util.Map<String, Long> processIds = new java.util.HashMap<>();
        for (CompiledStep step : compiled.steps()) {
            Long id = experimentMapper.insertExperimentProcess(TENANT, experimentId,
                    step.workflowId(), step.seq());
            processIds.put(step.nodeId(), id);
        }
        for (CompiledEdge edge : compiled.edges()) {
            Long from = processIds.get(edge.fromNodeId());
            Long to = processIds.get(edge.toNodeId());
            if (from == null || to == null) {
                throw new IllegalArgumentException("编排边引用了不存在的节点："
                        + edge.fromNodeId() + " -> " + edge.toNodeId());
            }
            experimentMapper.insertExperimentProcessEdge(TENANT, experimentId, from, to);
        }
        if (bumpVersion) {
            bumpExperimentVersion(c, experimentId);
        }
        return compiled.steps().size();
    }

    /** 画布编译器：当前只接受 开始 -> 流程... -> 结束 的线性主链。 */
    public static CompiledGraph compileExperimentGraph(String graphJson) {
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
    public static java.util.List<Long> compileExperimentGraphWorkflowIds(String graphJson) {
        return compileExperimentGraph(graphJson).workflowIds();
    }

    /** 由线性流程 id 列表构造"链式"编译结果（写入口 / 新建实验用）。 */
    public static CompiledGraph linearGraph(java.util.List<Long> workflowIds) {
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

    /** 编译出的一步：画布流程节点 -> 一行 experiment_process。 */
    public record CompiledStep(String nodeId, long workflowId, int seq) { }

    /** 编译出的一条偏序边：前驱节点 -> 后继节点（画布节点 id 层面）。 */
    public record CompiledEdge(String fromNodeId, String toNodeId) { }

    /** 画布编译结果：步骤（含 seq）+ 偏序边。 */
    public record CompiledGraph(java.util.List<CompiledStep> steps, java.util.List<CompiledEdge> edges) {
        java.util.List<Long> workflowIds() {
            return steps.stream().map(CompiledStep::workflowId).toList();
        }
    }

    private record CanvasNode(String id, String kind, Long workflowId) { }


    private void validateExperimentWorkflowIds(Connection c, List<Long> workflowIds)
            throws java.sql.SQLException {
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
            exist.addAll(experimentMapper.selectExistingWorkflowIds(TENANT, workflowIds));
            java.util.List<Long> missing = workflowIds.stream()
                    .filter(id -> !exist.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("流程不存在或已删除：" + missing);
            }
        }
    }


    private void bumpExperimentVersion(Connection c, long experimentId) throws java.sql.SQLException {
        experimentMapper.bumpExperimentVersion(TENANT, experimentId);
    }

}
