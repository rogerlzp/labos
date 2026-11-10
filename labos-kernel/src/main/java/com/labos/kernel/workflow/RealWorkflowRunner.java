package com.labos.kernel.workflow;

import com.labos.kernel.engine.ExecutionEvent;
import com.labos.kernel.engine.ExecutionListener;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用**真实工艺定义**驱动内核执行。
 *
 * <p>与之前 Demo 的区别：节点不是写死的，而是从 {@code workflow} / {@code workflow_node} /
 * {@code workflow_edge} 三张表读出来的——包括能力要求和可用资源池。
 *
 * <p>这就是「工艺与设备解耦」的实证：工艺只声明「需要 INCUBATE 能力」，
 * 具体用哪台设备由候选池 + 恢复引擎在运行时决定。
 *
 * <pre>
 *   java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.workflow.RealWorkflowRunner 239
 * </pre>
 */
public final class RealWorkflowRunner {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RealWorkflowRunner.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    private static final long TENANT = 1L;

    /** 流程定义。 */
    public record WorkflowDef(long id, String code, String name, String category) {
    }

    /** 节点定义。 */
    public record NodeDef(long id, String key, String name, String type,
                   String capability, String protocolName, int estimateSec,
                   Long equipmentInstanceId) {
    }

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();

        if (args.length > 0 && "--all".equals(args[0])) {
            runAll(db);
            return;
        }

        long workflowId = args.length > 0 ? Long.parseLong(args[0]) : 239L;

        // ---------- 1. 读真实工艺
        WorkflowDef wf = loadWorkflow(db, workflowId);
        List<NodeDef> nodes = loadNodes(db, workflowId);
        List<String[]> edges = loadEdges(db, workflowId);
        Map<String, List<Long>> capPool = loadCapabilityPool(db);
        Map<String, String> protocolCap = loadProtocolCapability(db);

        log.info("=".repeat(92));
        printf("  真实工艺执行：%s（id=%d, %s）%n", wf.name(), wf.id(), wf.category());
        log.info("=".repeat(92));
        printf("  节点 %d 个，边 %d 条，能力池 %d 类%n%n",
                nodes.size(), edges.size(), capPool.size());

        // ---------- 2. 识别可执行节点
        List<NodeDef> tasks = nodes.stream()
                .filter(n -> "task".equals(n.type()))
                .toList();
        if (tasks.isEmpty()) {
            log.info("  该流程没有可执行节点");
            return;
        }

        printf("  %-24s %-12s %-12s %-10s %s%n",
                "节点", "名称", "所需能力", "能力来源", "候选设备数");
        log.info("  " + "-".repeat(86));
        java.util.Map<String, Integer> srcStat = new java.util.LinkedHashMap<>();
        for (NodeDef n : tasks) {
            CapabilitySource cs = resolveCapability(n, protocolCap);
            srcStat.merge(cs.source(), 1, Integer::sum);
            int pool = LOGIC.equals(cs.capability()) ? 1
                    : capPool.getOrDefault(cs.capability(), List.of()).size();
            printf("  %-24s %-12s %-12s %-10s %d%n",
                    truncate(n.key(), 24), truncate(n.name(), 12),
                    cs.capability(), cs.source(), pool);
        }
        log.info("");
        log.info("  能力来源统计: " + srcStat);
        log.info("");

        // ---------- 3. 拓扑排序
        List<NodeDef> ordered = topoSort(tasks, edges);
        if (ordered.size() != tasks.size()) {
            log.info("  ⚠ 检测到环或孤立节点，已降级为按 id 排序");
        }
        log.info("  拓扑序: " + ordered.stream().map(NodeDef::name).toList());
        log.info("");

        // ---------- 4. 转成可恢复节点（能力 -> 候选资源）
        List<RecoverableNode> recoverable = new ArrayList<>();
        Map<Long, String> nodeCap = new HashMap<>();
        long seq = 0;
        for (NodeDef n : ordered) {
            String cap = resolveCapability(n, protocolCap).capability();
            nodeCap.put(n.id(), cap);
            List<Long> candidates;
            Long initial;
            if (LOGIC.equals(cap)) {
                candidates = List.of(LOGIC_RESOURCE);   // 系统自身，不需要设备
                initial = LOGIC_RESOURCE;
            } else {
                candidates = capPool.getOrDefault(cap, List.of());
                initial = candidates.isEmpty() ? null : candidates.get(0);
            }
            recoverable.add(RecoverableNode.of(++seq, n.key(), n.name(), cap,
                    n.protocolName(), initial, candidates));
        }

        // ---------- 5. 注入故障：第一台设备永久离线，验证自动换机
        SimulatedBus bus = new SimulatedBus();
        String failNote = "（未注入故障）";
        for (RecoverableNode rn : recoverable) {
            List<Long> cands = new ArrayList<>(rn.candidateResourceIds());
            if (cands.size() >= 2) {
                long dead = cands.get(0);
                bus.dead(dead, rn.capabilityCode(), "EC-08-0001", RecoveryScope.NODE);
                failNote = String.format("设备 %d 永久离线 -> 应自动切到 %d（%s）",
                        dead, cands.get(1), rn.capabilityCode());
                break;
            }
        }
        log.info("  故障注入: " + failNote);
        log.info("");

        // ---------- 6. 执行
        JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(db, TENANT);
        RecoveryDecider decider = new RecoveryDecider(new RecoveryPolicyResolver(repo.loadAll()));

        List<ExecutionEvent> events = new ArrayList<>();
        // 这一层是**批量验证**（RealWorkflowRunner --all），故意用记录式 sleeper：
        // 几十个流程 × 每个重试等 0.5–2 秒会让验收跑成几分钟。
        // 生产路径（WorkflowService）用的是真睡，别照抄这里。
        RunStateMachine sm = new RunStateMachine(decider, events::add,
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());

        // 逐节点执行，并把已确认故障的设备从后续候选池里剔除 —— 避免重复踩同一个坑
        RunProgress progress = new RunProgress();
        Set<Long> knownBad = new LinkedHashSet<>();
        int avoided = 0;
        for (RecoverableNode rn : recoverable) {
            List<Long> avail = new ArrayList<>();
            for (Long id : rn.candidateResourceIds()) {
                if (knownBad.contains(id)) {
                    avoided++;
                } else {
                    avail.add(id);
                }
            }
            RecoverableNode filtered = rn.withCandidates(avail);

            if (sm.executeNode(filtered, progress, bus) == NodeStatus.MANUAL
                    || progress.aborted()) {
                break;
            }
            knownBad.addAll(progress.node(rn.id()).failedResourceIds());
        }
        if (avoided > 0) {
            printf("  （已从后续候选中剔除 %d 个已知故障设备的分配）%n%n", avoided);
        }

        // ---------- 7. 输出
        log.info("  事件流：");
        for (ExecutionEvent e : events) {
            log.info("    " + e);
        }
        log.info("");

        log.info("  节点结果：");
        printf("  %-14s %-12s %-6s %-14s %s%n", "节点", "能力", "尝试", "最终设备", "状态");
        log.info("  " + "-".repeat(78));
        for (NodeProgress np : progress.all()) {
            RecoverableNode def = recoverable.stream()
                    .filter(r -> r.id() == np.nodeId()).findFirst().orElse(null);
            printf("  %-14s %-12s %-6d %-14s %s%n",
                    def == null ? "?" : truncate(def.name(), 14),
                    def == null ? "?" : def.capabilityCode(),
                    np.attempt(),
                    np.currentResourceId() == null ? "—" : np.currentResourceId().toString(),
                    np.status());
        }
        log.info("");
        log.info("  结果: " + progress.summary());
        log.info("");

        long switched = events.stream()
                .filter(e -> e.type() == com.labos.kernel.engine.EventType.RESOURCE_SWITCHED)
                .count();
        long completed = progress.count(NodeStatus.COMPLETED);
        printf("  ★ 真实工艺 %d 个节点，完成 %d 个，自动换机 %d 次%n",
                recoverable.size(), completed, switched);
        if (switched > 0) {
            log.info("  ★ 工艺只声明「需要什么能力」，设备坏了由内核自动换——工艺与设备已解耦");
        }
    }

    // ---------------------------------------------------------------- 数据读取

    /** 延迟取 Mapper（本类全是静态方法，见 Mappers）。 */
    private static com.labos.kernel.persistence.GraphMapper gm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class);
    }

    public static WorkflowDef loadWorkflow(DbConfig db, long id) throws SQLException {
        java.util.Map<String, Object> row = gm().selectWorkflowHeader(TENANT, id);
        if (row == null) {
            throw new IllegalArgumentException("workflow 不存在: " + id);
        }
        return new WorkflowDef(((Number) row.get("id")).longValue(),
                (String) row.get("code"), (String) row.get("name"),
                (String) row.get("category"));
    }

    public static List<NodeDef> loadNodes(DbConfig db, long workflowId) throws SQLException {
        List<NodeDef> out = new ArrayList<>();
        for (java.util.Map<String, Object> m : gm().selectWorkflowNodes(workflowId)) {
            Number ei = (Number) m.get("equipmentInstanceId");
            Number est = (Number) m.get("estimateSec");
            out.add(new NodeDef(((Number) m.get("id")).longValue(),
                    (String) m.get("nodeKey"), (String) m.get("name"),
                    (String) m.get("nodeType"), (String) m.get("capability"),
                    (String) m.get("protocolName"),
                    est == null ? 0 : est.intValue(),
                    ei == null ? null : ei.longValue()));
        }
        return out;
    }

    public static List<String[]> loadEdges(DbConfig db, long workflowId) throws SQLException {
        List<String[]> out = new ArrayList<>();
        for (java.util.Map<String, Object> row : gm().selectEdges(workflowId)) {
            out.add(new String[]{(String) row.get("fromKey"), (String) row.get("toKey")});
        }
        return out;
    }

    public static Map<String, List<Long>> loadCapabilityPool(DbConfig db) throws SQLException {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (java.util.Map<String, Object> m : gm().selectCapabilityPools()) {
            // array_agg 列在 Map 里保持 java.sql.Array，拆成 List<Long>
            java.sql.Array arr = (java.sql.Array) m.get("ids");
            List<Long> ids = new ArrayList<>();
            if (arr != null) {
                for (Object o : (Object[]) arr.getArray()) {
                    ids.add(((Number) o).longValue());
                }
            }
            out.put((String) m.get("code"), ids);
        }
        return out;
    }

    /**
     * protocol_name -&gt; capability，用于补齐缺失能力标注的节点。
     *
     * <p>⚠️ <b>同名方法跨能力冲突是常态</b>：设备库里 {@code Home} 这个方法
     * 同时挂在离心机（device 221 / CENTRIFUGE）、转运机器人（145 / TRANSFER）、
     * 洗板机（183 / WASH）上 —— 按方法名反查能力**天然有歧义**。
     *
     * <p>⚠️ 改动前这里是 {@code SELECT dm.method, c.code ...}（**无 DISTINCT、无 ORDER BY**），
     * {@code putIfAbsent} 取的是**物理扫描顺序里的第一行** ——
     * 而 PostgreSQL 的堆顺序会因任何一次批量 UPDATE/重写而变
     * （2026-11-06 实测：补齐 device_method.timeout_sec 的 260 行 UPDATE
     * 就把 {@code Home} 的解析从 TRANSFER 翻成了 CENTRIFUGE，
     * 于是绑定了机械臂的节点改绑离心机、**BPMN 里绑的资源被忽略**）。
     *
     * <p>现在：{@code DISTINCT + ORDER BY} ⇒ 结果**可复现**；
     * 真正解决歧义的是「**绑定了设备就用那台设备的能力**」那条规则
     * （见 {@link #resolveCapability} 的 bound 优先级）。
     */
    public static Map<String, String> loadProtocolCapability(DbConfig db) throws SQLException {
        Map<String, String> out = new HashMap<>();
        for (java.util.Map<String, Object> m : gm().selectProtocolCapabilities()) {
            // DISTINCT + ORDER BY 已保证可复现，putIfAbsent 取排序第一（沿用原语义）
            out.putIfAbsent((String) m.get("method"), (String) m.get("capabilityCode"));
        }
        return out;
    }

    /**
     * 资源/实例 id -&gt; 能力编码（两套 id 空间都覆盖）。
     *
     * <p>为什么必须有它：<b>节点绑定了哪台设备，那台设备的能力就是权威的</b> ——
     * 用户在设计器里明确选了机械臂，能力就该是 TRANSFER，
     * 而不是「因为流程里写的方法名叫 Home，而离心机也有个 Home，就按离心机算」。
     *
     * <p>覆盖两个 id 空间：
     * <ul>
     *   <li>{@code resource_node.id}（流程图里绑的资源，如 200214）</li>
     *   <li>{@code device_instance.id}（老数据里的设备实例，如 214）</li>
     * </ul>
     */
    public static Map<Long, String> loadResourceCapability(DbConfig db) throws SQLException {
        Map<Long, String> out = new HashMap<>();
        for (java.util.Map<String, Object> m : gm().selectResourceCapabilities()) {
            out.putIfAbsent(((Number) m.get("id")).longValue(),
                    (String) m.get("capabilityCode"));
        }
        return out;
    }

    // ---------------------------------------------------------------- 算法

    /**
     * 节点能力解析（三级）：
     * <ol>
     *   <li><b>explicit</b>：节点自带 capability_id（最可信）</li>
     *   <li><b>protocol</b>：按 protocol_name 到 device_method 反查</li>
     *   <li><b>heuristic</b>：按方法名关键词推断（最弱，需复核）</li>
     * </ol>
     */
    /**
     * 合法资源/实例 id 的上界。**只用来识别"伪 id"**（存量数据里的 100000000/101000001）。
     *
     * <p>⚠️ 改动前写的是 10_000，注释说"真实 id 只有三位数(162~405)" —— 这个假设早就失效了：
     * 现在部署出来的设备 resource_node.id 是 **5xxxxx**（如 504830）。
     * 于是任何"节点绑定了真实资源"的流程，都会被当成逻辑节点 ⇒
     * {@code DriverStepExecutor} 直接跳过设备调用、把每一步标成功 ⇒
     * **界面上一片绿，设备根本没动**（这个 bug 2026-11-02 在页面上抓到）。
     *
     * <p>现在的判据只拦 9 位数的历史遗留伪 id（100000000 起）。
     */
    public static final long MAX_PLAUSIBLE_RESOURCE_ID = 99_999_999L;

    static CapabilitySource resolveCapability(NodeDef n, Map<String, String> protocolCap) {
        return resolveCapability(n, protocolCap, Map.of());
    }

    /** NodeDef 版（带设备能力表）：绑定设备的能力优先。 */
    static CapabilitySource resolveCapability(NodeDef n, Map<String, String> protocolCap,
                                              Map<Long, String> capabilityByResource) {
        return resolveCapability(n.name(), n.protocolName(), n.equipmentInstanceId(),
                n.capability(), protocolCap, capabilityByResource);
    }

    /**
     * 能力解析（共享入口）：{@link GraphRunner} 与 NodeDef 路径共用同一套规则。
     *
     * <p>六级的顺序是有意的——**越靠前越可信，最后才是猜**：
     * 逻辑节点 → 伪设备 id → 显式标注 → 协议反查(含纠错) → 简称唯一命中 → 名称启发。
     */
    static CapabilitySource resolveCapability(String name, String protocolName,
                                              Long equipmentInstanceId,
                                              String explicitCapability,
                                              Map<String, String> protocolCap) {
        return resolveCapability(name, protocolName, equipmentInstanceId, explicitCapability,
                protocolCap, Map.of());
    }

    /**
     * 能力解析（带设备能力表）。
     *
     * @param capabilityByResource 资源/实例 id -&gt; 能力编码（两套 id 空间）
     */
    static CapabilitySource resolveCapability(String name, String protocolName,
                                              Long equipmentInstanceId,
                                              String explicitCapability,
                                              Map<String, String> protocolCap,
                                              Map<Long, String> capabilityByResource) {
        // 1) 逻辑节点不占设备——否则会因"无候选资源"误判为转人工
        //
        //    两条判定缺一不可：
        //    - `protocolName` 以 `__` 开头 = 内置节点（与解析器的 Kind.BUILTIN 同源，
        //      WorkflowGraphParser.isBuiltinProtocol）；
        //    - 显示名关键词（delay/variable/script…），覆盖存量"伪逻辑节点"。
        //
        //    只看显示名会漏：拖出来的内置节点叫「延时」「等待变量」，是中文，
        //    关键词一条都匹配不上 ⇒ 走 NodeDef 路径（WorkflowService 建 RecoverableNode）
        //    时能力被解析成 UNKNOWN、候选设备为空（2026-11-06 评审指出的同一条缝）。
        if (WorkflowGraphParser.isBuiltinProtocol(protocolName) || isLogicNode(name)) {
            return new CapabilitySource(LOGIC, "logic");
        }
        // 2) 设备实例 id 是伪值(如 100000000/101000001)说明本就不绑真实设备
        if (equipmentInstanceId != null
                && (equipmentInstanceId <= 0 || equipmentInstanceId > MAX_PLAUSIBLE_RESOURCE_ID)) {
            return new CapabilitySource(LOGIC, "logic");
        }
        // 3) 节点自带能力标注
        if (explicitCapability != null && !explicitCapability.isBlank()) {
            return new CapabilitySource(explicitCapability, "explicit");
        }
        // 3.5) ★ 节点绑定了设备 ⇒ **那台设备的能力就是权威的**
        //   （排在协议反查之前）
        //
        //   为什么必须插在这里：设备库里同名方法跨能力冲突是常态
        //   （`Home` 同时挂在离心机/转运机器人/洗板机上），
        //   按方法名反查能力天然有歧义 —— 用户明明把节点绑到了机械臂，
        //   却因为「离心机也有个 Home」而被解析成 CENTRIFUGE，
        //   **候选池按能力算 ⇒ BPMN 里绑的资源直接被忽略**（2026-11-06 实踩）。
        //
        //   绑定的设备存在且有能力登记时，它的能力**优先于协议反查**。
        if (equipmentInstanceId != null
                && equipmentInstanceId > 0
                && equipmentInstanceId <= MAX_PLAUSIBLE_RESOURCE_ID
                && capabilityByResource != null) {
            String boundCap = capabilityByResource.get(equipmentInstanceId);
            if (boundCap != null && !boundCap.isBlank()) {
                return new CapabilitySource(boundCap, "bound");
            }
        }
        // 4) 按 protocol_name 反查（含拼写纠错，存量数据有 RunProcotol 这类笔误）
        String byProtocol = protocolName == null ? null : protocolCap.get(protocolName);
        if (byProtocol == null && protocolName != null) {
            String fixed = normalizeProtocolName(protocolName);
            if (fixed != null) {
                byProtocol = protocolCap.get(fixed);
            }
        }
        if (byProtocol != null) {
            return new CapabilitySource(byProtocol, "protocol");
        }
        // 5) 简称匹配：存量数据里有略写（ValveSet -> IOMessage_ValveSet），仅唯一命中才采用
        if (protocolName != null && !protocolName.isBlank()) {
            List<String> hits = new ArrayList<>();
            for (String m : protocolCap.keySet()) {
                if (!m.equals(protocolName) && m.endsWith(protocolName)) {
                    hits.add(m);
                }
            }
            if (hits.size() == 1) {
                return new CapabilitySource(protocolCap.get(hits.get(0)), "protocol-short");
            }
        }
        // 6) 名称关键词推断
        String guess = heuristicCapability(name + " " + protocolName);
        return guess == null
                ? new CapabilitySource("UNKNOWN", "unknown")
                : new CapabilitySource(guess, "heuristic");
    }

    /** 常见拼写错误归一化（实测出现过 Procotol / Protocal）。 */
    static String normalizeProtocolName(String name) {
        if (name == null) {
            return null;
        }
        return name.replace("Procotol", "Protocol")
                   .replace("procotol", "protocol")
                   .replace("Protocal", "Protocol")
                   .replace("protocal", "protocol")
                   .replace("Protocel", "Protocol")
                   .replace("protocel", "protocol");
    }

    public record CapabilitySource(String capability, String source) {
    }

    /** 逻辑节点：不占设备，由脚本/内核自身执行。 */
    public static final String LOGIC = "LOGIC";
    /** 逻辑节点使用的伪资源（表示"系统自身"，不是物理设备）。 */
    public static final long LOGIC_RESOURCE = 0L;

    public static boolean isLogicNode(String name) {
        if (name == null) {
            return false;
        }
        String s = name.toLowerCase();
        return s.contains("delay") || s.contains("setvariable") || s.contains("setprefix")
                || s.contains("increment") || s.contains("waitfor") || s.contains("condition")
                || s.contains("script") || s.contains("thirdpartyapi")
                || s.contains("constraint") || s.contains("loop") || s.contains("variable");
    }

    /** 方法名关键词 -> 能力（保守规则，宁可漏判不可错判）。 */
    static String heuristicCapability(String raw) {
        String s = raw == null ? "" : raw.toLowerCase();
        if (s.contains("deposit") || s.contains("withdraw") || s.contains("incubat")) return "INCUBATE";
        if (s.contains("centrifug") || s.contains("spin")) return "CENTRIFUGE";
        if (s.contains("dispens") || s.contains("aspirat") || s.contains("pipett")) return "ALIQUOT";
        if (s.contains("wash")) return "WASH";
        if (s.contains("read") || s.contains("imag") || s.contains("photo")) return "READ_PLATE";
        if (s.contains("seal")) return "SEAL";
        if (s.contains("peel")) return "PEEL";
        if (s.contains("capp")) return "DECAP";
        if (s.contains("scan") || s.contains("barcode")) return "SCAN";
        if (s.contains("load") || s.contains("unload") || s.contains("hotel") || s.contains("stack")) return "STORE";
        if (s.contains("transfer") || s.contains("rail") || s.contains("rotate") || s.contains("move")) return "TRANSFER";
        return null;
    }

    /**
     * 拓扑排序（Kahn）。
     *
     * <p>只用 task 节点构成子图；start/end 不参与执行，但通过它们的边仍会影响顺序。
     * 有环时降级为按 id 排序，并告警——生产流程本不该有环，但历史数据不一定干净。
     */
    public static List<NodeDef> topoSort(List<NodeDef> tasks, List<String[]> edges) {
        Map<String, NodeDef> byKey = new LinkedHashMap<>();
        for (NodeDef n : tasks) {
            byKey.put(n.key(), n);
        }

        Map<String, Set<String>> adj = new HashMap<>();
        Map<String, Integer> indeg = new HashMap<>();
        byKey.keySet().forEach(k -> {
            adj.put(k, new LinkedHashSet<>());
            indeg.put(k, 0);
        });

        for (String[] e : edges) {
            String from = e[0];
            String to = e[1];
            if (!byKey.containsKey(from) || !byKey.containsKey(to)) {
                continue;   // 边一端是 start/end 等非 task 节点
            }
            if (adj.get(from).add(to)) {
                indeg.merge(to, 1, Integer::sum);
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        byKey.keySet().stream().filter(k -> indeg.get(k) == 0).forEach(queue::add);

        List<NodeDef> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            String k = queue.poll();
            out.add(byKey.get(k));
            for (String next : adj.get(k)) {
                if (indeg.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }

        if (out.size() != tasks.size()) {
            return tasks;   // 有环/孤立节点，降级；由调用方决定如何提示
        }
        return out;
    }

    // ---------------------------------------------------------------- 批量验收

    /** 一次性装载全部节点（避免按流程逐条查询）。 */
    private static Map<Long, List<NodeDef>> loadAllNodes(DbConfig db) throws SQLException {
        Map<Long, List<NodeDef>> out = new HashMap<>();
        for (java.util.Map<String, Object> m : gm().selectAllWorkflowNodes()) {
            Number ei = (Number) m.get("equipmentInstanceId");
            Number est = (Number) m.get("estimateSec");
            out.computeIfAbsent(((Number) m.get("workflowId")).longValue(), k -> new ArrayList<>())
                    .add(new NodeDef(((Number) m.get("id")).longValue(),
                            (String) m.get("nodeKey"), (String) m.get("name"),
                            (String) m.get("nodeType"), (String) m.get("capability"),
                            (String) m.get("protocolName"),
                            est == null ? 0 : est.intValue(),
                            ei == null ? null : ei.longValue()));
        }
        return out;
    }

    private static Map<Long, List<String[]>> loadAllEdges(DbConfig db) throws SQLException {
        Map<Long, List<String[]>> out = new HashMap<>();
        for (java.util.Map<String, Object> row : gm().selectAllEdges()) {
            long wfId = ((Number) row.get("workflowId")).longValue();
            out.computeIfAbsent(wfId, k -> new ArrayList<>())
                    .add(new String[]{(String) row.get("fromKey"),
                            (String) row.get("toKey")});
        }
        return out;
    }

    /** 批量验收：全部真实流程按图执行，统计可执行率与网关行为。 */
    static void runAll(DbConfig db) throws SQLException {
        long t0 = System.nanoTime();

        List<WorkflowDef> wfs = new ArrayList<>();
        for (java.util.Map<String, Object> row : gm().selectWorkflowHeaders(TENANT)) {
            wfs.add(new WorkflowDef(((Number) row.get("id")).longValue(),
                    (String) row.get("code"), (String) row.get("name"),
                    (String) row.get("category")));
        }

        Map<Long, String> xmlByWf = loadGraphXml(db);
        Map<Long, Map<String, String>> capByWf = loadCapabilityByKey(db);
        Map<String, List<Long>> capPool = loadCapabilityPool(db);
        Map<String, String> protocolCap = loadProtocolCapability(db);

        JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(db, TENANT);
        RecoveryDecider decider = new RecoveryDecider(new RecoveryPolicyResolver(repo.loadAll()));

        int wfOk = 0, wfPartial = 0, wfFailed = 0, wfEmpty = 0, wfNoGraph = 0;
        long nodeTotal = 0, nodeDone = 0, nodeSkip = 0, nodeManual = 0, nodeAbort = 0;
        int parallelFlows = 0, xorFlows = 0, defaultEdges = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        List<String> worst = new ArrayList<>();
        List<String> manualNodes = new ArrayList<>();

        for (WorkflowDef wf : wfs) {
            String xml = xmlByWf.get(wf.id());
            if (xml == null || xml.isBlank()) {
                wfNoGraph++;
                continue;
            }
            WorkflowGraphParser.Graph g;
            try {
                g = WorkflowGraphParser.parse(xml);
            } catch (Exception e) {
                wfNoGraph++;
                continue;
            }
            if (g.nodes().values().stream().noneMatch(n -> n.kind() == WorkflowGraphParser.Kind.TASK)) {
                wfEmpty++;
                continue;
            }

            boolean hasParallel = g.nodes().values().stream()
                    .anyMatch(n -> n.kind() == WorkflowGraphParser.Kind.SPLIT_PARALLEL);
            boolean hasXor = g.nodes().values().stream()
                    .anyMatch(n -> n.kind() == WorkflowGraphParser.Kind.SPLIT_XOR);

            RunStateMachine sm = new RunStateMachine(decider, ExecutionListener.NOOP,
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            GraphRunner runner = new GraphRunner(sm, capPool, protocolCap,
                    capByWf.getOrDefault(wf.id(), Map.of()), s -> { });
            RunProgress p = runner.run(g, new SimulatedBus());

            if (hasParallel) {
                parallelFlows++;
            }
            if (hasXor) {
                xorFlows++;
            }
            defaultEdges += runner.defaultEdgeCount();

            int done = p.count(NodeStatus.COMPLETED);
            int skip = p.count(NodeStatus.SKIPPED);
            int manual = p.count(NodeStatus.MANUAL);
            int abort = p.count(NodeStatus.ABORTED);

            nodeTotal += p.all().size();
            nodeDone += done;
            nodeSkip += skip;
            nodeManual += manual;
            nodeAbort += abort;

            if (manual > 0 || abort > 0) {
                for (NodeProgress np : p.all()) {
                    if (np.status() == NodeStatus.MANUAL) {
                        String key = np.lastErrorCode() == null ? "(无可执行资源)" : np.lastErrorCode();
                        reasons.merge(key, 1, Integer::sum);
                        if (manualNodes.size() < 15) {
                            manualNodes.add(String.format("    wf#%-5d %-30s 能力=%-12s",
                                    wf.id(), truncate(wf.name(), 30),
                                    np.nodeId() == 0 ? "?" : "见事件"));
                        }
                    }
                }
                if (manual + abort >= p.all().size()) {
                    wfFailed++;
                } else {
                    wfPartial++;
                }
                if (worst.size() < 10) {
                    worst.add(String.format("  #%-6d %-32s 节点%3d  人工%2d  终止%2d",
                            wf.id(), truncate(wf.name(), 32), p.all().size(), manual, abort));
                }
            } else {
                wfOk++;
            }
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;

        log.info("=".repeat(90));
        log.info("  批量验收：按图执行（含网关）");
        log.info("=".repeat(90));
        printf("  流程总数 %d，耗时 %d ms%n%n", wfs.size(), ms);

        log.info("  ── 流程级 ──");
        pct("全部完成", wfOk, wfs.size());
        pct("部分完成", wfPartial, wfs.size());
        pct("全部失败", wfFailed, wfs.size());
        pct("无 task 节点", wfEmpty, wfs.size());
        pct("无 BPMN 图", wfNoGraph, wfs.size());
        log.info("");

        log.info("  ── 节点级 ──");
        pct("完成", (int) nodeDone, (int) nodeTotal);
        pct("跳过", (int) nodeSkip, (int) nodeTotal);
        pct("转人工", (int) nodeManual, (int) nodeTotal);
        pct("终止", (int) nodeAbort, (int) nodeTotal);
        log.info("");

        log.info("  ── 网关行为 ──");
        printf("  含并行网关的流程      %5d 个%n", parallelFlows);
        printf("  含异或网关的流程      %5d 个%n", xorFlows);
        printf("  异或走默认边次数      %5d 次%n", defaultEdges);
        log.info("");

        if (!reasons.isEmpty()) {
            log.info("  ── 转人工原因 ──");
            reasons.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(6)
                    .forEach(e -> printf("  %-24s %6d 次%n", e.getKey(), e.getValue()));
            log.info("");
        }

        if (!worst.isEmpty()) {
            log.info("  ── 问题最多的流程（前 10）──");
            worst.forEach(System.out::println);
            log.info("");
        }

        double rate = nodeTotal == 0 ? 0 : nodeDone * 100.0 / nodeTotal;
        printf("  ★ 节点自动完成率 %.1f%%，流程完全跑通率 %.1f%%%n",
                rate, wfOk * 100.0 / Math.max(1, wfs.size()));
    }

    /** 每个流程的 BPMN XML。 */
    private static Map<Long, String> loadGraphXml(DbConfig db) throws SQLException {
        Map<Long, String> out = new HashMap<>();
        for (java.util.Map<String, Object> row : gm().selectGraphXmlRows(TENANT)) {
            String xml = (String) row.get("xml");
            if (xml != null && !xml.isBlank()) {
                out.put(((Number) row.get("id")).longValue(), xml);
            }
        }
        return out;
    }

    /** 库里的显式能力标注：workflow_id -> (node_key -> capability_code)。 */
    private static Map<Long, Map<String, String>> loadCapabilityByKey(DbConfig db) throws SQLException {
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (java.util.Map<String, Object> m : gm().selectCapabilityAnnotations(TENANT)) {
            out.computeIfAbsent(((Number) m.get("workflowId")).longValue(), k -> new HashMap<>())
                    .put((String) m.get("nodeKey"), (String) m.get("capabilityCode"));
        }
        return out;
    }

    private static void pct(String label, int n, int total) {
        printf("  %-14s %6d 个  %5.1f%%%n", label, n, total == 0 ? 0 : n * 100.0 / total);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private RealWorkflowRunner() {
    }
}
