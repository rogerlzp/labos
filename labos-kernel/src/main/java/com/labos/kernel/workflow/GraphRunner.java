package com.labos.kernel.workflow;

import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.StepExecutor;
import com.labos.kernel.workflow.RealWorkflowRunner.CapabilitySource;
import com.labos.kernel.workflow.WorkflowGraphParser.GEdge;
import com.labos.kernel.workflow.WorkflowGraphParser.GNode;
import com.labos.kernel.workflow.WorkflowGraphParser.Graph;
import com.labos.kernel.workflow.WorkflowGraphParser.Kind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 按图推进：支持并行分叉/汇合与异或二选一。
 *
 * <p>与改动前实现的差别（这是 2.0 的核心修正）：
 * <ul>
 *   <li>改动前只按 {@code sequenceFlow} 顺序推进，**网关被完全忽略**</li>
 *   <li>结果：并行流程被当成串行执行；异或流程两条分支都会执行</li>
 * </ul>
 *
 * <p>本实现的三条规则：
 * <ol>
 *   <li><b>并行分叉</b>：激活所有出边；<b>并行汇合</b>：等所有入边都到达</li>
 *   <li><b>异或分叉</b>：只激活一条出边（条件 → default → 稳定排序第一条）</li>
 *   <li><b>普通汇合</b>（非并行网关）：任一条入边到达即可执行（OR join），靠 {@code done} 去重</li>
 * </ol>
 *
 * <p>关于墙钟并行：本实现是**逻辑并行**——两条分支都会被推进，但按就绪队列顺序执行，
 * 不要求两台设备在时间上重叠（见规格 D3）。
 */
public final class GraphRunner {

    private final RunStateMachine stateMachine;
    private final Map<String, List<Long>> capabilityPool;

    /**
     * 每个能力已分配到第几个候选设备。
     *
     * <p>为什么需要：两个 INCUBATE 节点如果都取 candidates.get(0)，
     * 会选到同一台设备，板子永远不动。按能力轮转后，
     * 第二个节点自然落到另一台。
     *
     * <p>候选池里空设备排前面（WorkflowService.preferEmptyDevices），
     * 所以轮转 = 依次用空设备。
     */
    private final Map<String, Integer> usageCursor = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> protocolCapability;
    /** 库里显式标注的能力：node_key -> capability_code（比反查更准）。 */
    private final Map<String, String> capabilityByKey;
    /** node_key -> workflow_node.id；没有时退回 key 的 hash。 */
    private final Map<String, Long> nodeIdByKey;
    private final Consumer<String> log;

    /**
     * 节点上绑定的资源 → 设备实例（{@code resource_node.id → device_instance.id}）。
     *
     * <p>流程编辑器里"把设备拖到节点上"存的是 {@code bound_resource_id}；
     * 不把它翻成实例，节点就只能按能力轮转选设备 —— 同能力两台时**会跑到另一台上去**，
     * 编排里明明选的是 2 号机，实际跑的是 1 号机。钉住才不会跑偏。
     */
    private Map<Long, Long> boundInstanceByResource = Map.of();

    /**
     * 资源/实例 id -&gt; 能力编码。
     *
     * <p>用于「绑定了哪台设备就用那台设备的能力」这条优先级（见 RealWorkflowRunner）。
     */
    private Map<Long, String> capabilityByResource = Map.of();

    /**
     * **断点续跑**要跳过的节点 key（上一轮已经 completed 的那些）。
     *
     * <p>没有它，点"继续"就只能把 run 置回 queued 干等着 ——
     * 因为重跑会把所有节点再执行一遍（机械臂会把板再搬一次）。
     * 有了它，续跑就是"已经做完的不再做，从卡住那一步接着来"。
     */
    private Set<String> skipKeys = Set.of();

    /**
     * 运行期变量（{@code ${name}} 的取值来源）：条件表达式和循环退出条件用。
     *
     * <p>目前来自 run 的 input.vars（实验启动时注入）。节点输出回写变量还没做 ——
     * 那需要把 run_command 的结果解析成上下文，是独立的一块。
     */
    private Map<String, String> variables = new java.util.LinkedHashMap<>();

    /**
     * 节点执行完把**返回值**读出来写进变量表。
     *
     * <p>没有它，循环就无法"走够次数再退出"（条件里的变量永远不变）；
     * 由 {@code WorkflowService} 实现（查 run_command.result）。
     * 默认空实现 —— 单测/批量验收不需要连库。
     */
    public interface NodeOutputReader {
        Map<String, String> outputsOf(String nodeKey);
    }

    /**
     * 每圈执行完记一行（循环复盘用）。
     *
     * <p>为什么需要：`run_node` 每个业务节点只有一行（唯一键按 node_key），
     * 循环跑 5 圈也只有最后一圈的最终状态 —— "第 3 圈为什么失败"永远查不到。
     * 由 {@code WorkflowService} 实现成一条 INSERT（loop_index = 圈次）。
     */
    public interface RoundRecorder {
        void record(String nodeKey, int round, NodeStatus status,
                    String errorCode, java.time.Instant start, java.time.Instant end);
    }

    private RoundRecorder roundRecorder = (k, r, st, ec, s1, s2) -> { };

    /**
     * 内置节点（`__setVar` / `__expr`）的参数，key = 节点 key。
     *
     * <p>来自 `workflow_node.params->'method_params'`，和普通任务用的是同一个字段 ——
     * 只是内置节点不把它发给设备，而是自己用。
     */
    private Map<String, Map<String, Object>> builtinParams = Map.of();

    public GraphRunner withBuiltinParams(Map<String, Map<String, Object>> p) {
        this.builtinParams = p == null ? Map.of() : p;
        return this;
    }

    public GraphRunner withRoundRecorder(RoundRecorder r) {
        this.roundRecorder = r == null ? (k, n, st, ec, s1, s2) -> { } : r;
        return this;
    }

    private NodeOutputReader outputReader = k -> Map.of();

    public GraphRunner withOutputReader(NodeOutputReader r) {
        this.outputReader = r == null ? (k -> Map.of()) : r;
        return this;
    }

    /** 本次图里有没有环（run() 开头算一次）。 */
    private boolean graphHasCycle;

    /** 循环最多走几圈（防止回边写错导致死循环）。 */
    private int maxVisits = 10;

    /** 注入运行期变量。 */
    public GraphRunner withVariables(Map<String, String> vars) {
        this.variables = new java.util.LinkedHashMap<>(vars == null ? Map.of() : vars);
        return this;
    }

    /** 注入最大圈数（1 = 不允许循环）。 */
    public GraphRunner withMaxVisits(int n) {
        this.maxVisits = Math.max(1, Math.min(n, 100));
        return this;
    }

    /** 注入"已完成、跳过"的节点 key。 */
    public GraphRunner withSkipKeys(Set<String> keys) {
        this.skipKeys = keys == null ? Set.of() : Set.copyOf(keys);
        return this;
    }

    /** 注入"资源 → 设备实例"映射（编排里显式绑定的设备用它）。 */
    /** 注入资源/实例 -&gt; 能力映射（绑定设备的能力优先）。 */
    public GraphRunner withResourceCapability(Map<Long, String> m) {
        this.capabilityByResource = m == null ? Map.of() : m;
        return this;
    }

    public GraphRunner withBoundInstances(Map<Long, Long> m) {
        this.boundInstanceByResource = m == null ? Map.of() : m;
        return this;
    }

    /** 本次推进中被激活的节点 key（预检用它判断"哪些节点永远走不到"）。 */
    private final Set<String> activatedKeys = new LinkedHashSet<>();
    /** 未经条件走默认边的次数（用于验收报告）。 */
    private int defaultEdgeCount;
    /** 条件求值失败的次数。 */
    private int unevaluatedConditionCount;
    /** 条件真正求值且成立、据此选边的次数（验收报告用）。 */
    private int conditionHitCount;

    /**
     * 暂停检查器。
     *
     * <p><b>P1-8 修复（评审 20261102-03）</b>：改动前执行循环<b>没有任何暂停检查点</b> ——
     * {@code PauseManager.pause()} 把 run 标成 paused，但正在跑的那个线程毫不知情，
     * 会一路跑到结束再把状态覆盖回 completed/failed。
     * <b>M4 的整套暂停机制因此完全没有生效过。</b>
     *
     * <p>默认永不暂停（保持现有行为），由调用方注入查库实现。
     */
    private java.util.function.BooleanSupplier pauseChecker = () -> false;

    /** 本次推进是否因暂停而提前停止。 */
    private boolean paused;

    /** 注入暂停检查器（每到一个节点边界调用一次）。 */
    public GraphRunner withPauseChecker(java.util.function.BooleanSupplier checker) {
        this.pauseChecker = checker == null ? () -> false : checker;
        return this;
    }

    /** 本次 run 是否因暂停而停（true 时 run 应保持 paused，不要被收尾覆盖）。 */
    public boolean runPaused() {
        return paused;
    }

    public GraphRunner(RunStateMachine stateMachine,
                       Map<String, List<Long>> capabilityPool,
                       Map<String, String> protocolCapability,
                       Consumer<String> log) {
        this(stateMachine, capabilityPool, protocolCapability, Map.of(), log);
    }

    public GraphRunner(RunStateMachine stateMachine,
                       Map<String, List<Long>> capabilityPool,
                       Map<String, String> protocolCapability,
                       Map<String, String> capabilityByKey,
                       Consumer<String> log) {
        this(stateMachine, capabilityPool, protocolCapability, capabilityByKey, Map.of(), log);
    }

    public GraphRunner(RunStateMachine stateMachine,
                       Map<String, List<Long>> capabilityPool,
                       Map<String, String> protocolCapability,
                       Map<String, String> capabilityByKey,
                       Map<String, Long> nodeIdByKey,
                       Consumer<String> log) {
        this.stateMachine = stateMachine;
        this.capabilityPool = capabilityPool;
        this.protocolCapability = protocolCapability;
        this.capabilityByKey = capabilityByKey == null ? Map.of() : capabilityByKey;
        this.nodeIdByKey = nodeIdByKey == null ? Map.of() : nodeIdByKey;
        this.log = log == null ? s -> { } : log;
    }

    public RunProgress run(Graph graph, StepExecutor executor) {
        RunProgress progress = new RunProgress();

        // 未完成入边计数
        Map<String, Integer> pending = new HashMap<>();
        for (GNode n : graph.nodes().values()) {
            pending.put(n.key(), graph.in(n.key()).size());
        }

        Deque<String> ready = new ArrayDeque<>();
        Set<String> done = new HashSet<>();
        // 每个节点走了几圈。**只有图里真的存在环（回边）时才允许重入**，
        // 否则并行/异或的重复激活会被误当成循环，把设备动作多跑一遍。
        Map<String, Integer> visits = new HashMap<>();
        boolean hasCycle = hasCycle(graph);
        this.graphHasCycle = hasCycle;
        int steps = 0;
        final int maxSteps = 500;     // 兜底：表达式写错导致无限循环时也必须停下来

        // 起点：START 节点优先；没有 START 时取入度为 0 的节点
        List<GNode> starts = new ArrayList<>();
        for (GNode n : graph.nodes().values()) {
            if (n.kind() == Kind.START) {
                starts.add(n);
            }
        }
        if (starts.isEmpty()) {
            for (GNode n : graph.nodes().values()) {
                if (pending.getOrDefault(n.key(), 0) == 0) {
                    starts.add(n);
                }
            }
        }
        if (starts.isEmpty()) {
            // 全是环或者空图，退化为按 key 顺序
            graph.nodes().values().stream()
                    .sorted(Comparator.comparing(GNode::key))
                    .forEach(n -> ready.add(n.key()));
        } else {
            starts.sort(Comparator.comparing(GNode::key));
            starts.forEach(n -> ready.add(n.key()));
        }

        while (!ready.isEmpty() && !progress.aborted()) {
            String key = ready.poll();
            int visit = visits.merge(key, 1, Integer::sum);
            if (done.contains(key) && !hasCycle) {
                continue;
            }
            if (visit > maxVisits) {
                log.accept(String.format("[循环] 节点 %s 已执行 %d 圈，达到上限 %d，跳过",
                        key, visit - 1, maxVisits));
                continue;
            }
            if (++steps > maxSteps) {
                log.accept("[循环] 总步数超过 " + maxSteps + "，强制停下（检查回边/条件）");
                break;
            }

            GNode node = graph.nodes().get(key);
            if (node == null) {
                continue;
            }
            if (done.contains(key)) {
                log.accept(String.format("[循环] 节点 %s 第 %d 圈", key, visit - 1));
            }
            done.add(key);
            activatedKeys.add(key);

            if ((node.kind() == Kind.TASK || node.kind() == Kind.BUILTIN)
                    && skipKeys.contains(key)) {
                // 断点续跑：这个节点上一轮已经做完了，直接当完成往下走
                log.accept("[续跑] 节点 " + key + " 已完成，跳过");
            } else if (node.kind() == Kind.BUILTIN) {
                // 内置节点：不碰设备，直接算。算不出来就转人工停下（和人工节点一致），
                // 不能带着错值往下跑 —— 后面可能拿它做条件分支。
                try {
                    String var = executeBuiltin(node);
                    progress.complete(nodeId(node), "[内置] " + node.method()
                            + (var == null ? "" : " → " + var + "=" + variables.get(var)));
                    log.accept("[内置] " + key + " " + node.method()
                            + (var == null ? "" : " → " + var + "=" + variables.get(var)));
                } catch (RuntimeException ex) {
                    progress.manual(nodeId(node),
                            "[内置] " + node.method() + " 失败：" + ex.getMessage(),
                            "EC-BUILTIN-001");
                    log.accept("[内置] " + key + " 失败，停止等待人工：" + ex.getMessage());
                    break;
                }
            } else if (node.kind() == Kind.TASK) {
                // P1-8：在**任务节点的动作边界**检查暂停。
                // 不停在动作中间 —— 机械臂抓着板会掉。
                // 也不停 start/gateway —— 它们不是"一步活"，停在那里没意义。
                // 把 key 放回队列，恢复时从这里继续。
                if (pauseChecker.getAsBoolean()) {
                    ready.offer(key);
                    done.remove(key);
                    paused = true;
                    log.accept("[暂停] 收到暂停请求，在节点边界停下，待执行节点：" + key);
                    break;
                }
                RecoverableNode rn = toRecoverable(node);
                java.time.Instant roundStart = java.time.Instant.now();
                NodeStatus st = stateMachine.executeNode(rn, progress, executor);
                // 只在**图里真有环**时记圈次：无环图每个节点只跑一次，
                // 汇总行已经代表那一次，再记一行就是重复（会让"节点数"翻倍，
                // 2026-11-03 verify-device-chain 的 3 步流程就是这么红的）。
                if (graphHasCycle && visit >= 1) {
                    try {
                        NodeProgress np = progress.node(nodeId(node));
                        roundRecorder.record(key, visit,
                                st,
                                np == null ? null : np.lastErrorCode(),
                                roundStart, java.time.Instant.now());
                    } catch (RuntimeException ex) {
                        log.accept("[循环] 记录第 " + visit + " 圈失败（忽略）：" + ex.getMessage());
                    }
                }
                if (st == NodeStatus.COMPLETED) {
                    // 把这一步的返回值写进变量表：循环靠它"走够次数退出"，
                    // 后面的条件也能引用（如 ${count} >= 3）。
                    try {
                        Map<String, String> outs = outputReader.outputsOf(key);
                        if (outs != null && !outs.isEmpty()) {
                            variables.putAll(outs);
                            log.accept("[变量] " + key + " 输出 " + outs);
                        }
                    } catch (RuntimeException ex) {
                        log.accept("[变量] 读取 " + key + " 输出失败（忽略）：" + ex.getMessage());
                    }
                }
                if (st == NodeStatus.MANUAL || st == NodeStatus.ABORTED) {
                    break;   // 转人工/终止：整图停下，保持"卡住等人"语义不变
                }
                if (st == NodeStatus.SKIPPED) {
                    // P1-11 修复（评审 20261102-03）：
                    // 跳过意味着**这一步没做**，板的位置已不可信
                    // （评审指引 D9：「跳过意味着板子位置未知」）。
                    // 继续往下跑，后面的"取板"就会伸进空位置。
                    // 所以停下转人工确认，而不是当没事发生。
                    log.accept("[执行] 节点 " + key + " 被跳过，板位不可信，停止等待确认");
                    break;
                }
            }

            List<GEdge> outs = graph.out(key);
            if (node.kind() == Kind.SPLIT_XOR) {
                GEdge chosen = choose(outs, key);
                if (chosen != null) {
                    activate(graph, chosen, pending, ready, done);
                }
            } else {
                for (GEdge e : outs) {
                    activate(graph, e, pending, ready, done);
                }
            }
        }

        return progress;
    }

    // ---------------------------------------------------------------- 推进

    /** 图里有没有环（DFS 三色标记）。决定"重入"是循环还是重复激活。 */
    private static boolean hasCycle(Graph g) {
        Map<String, Integer> color = new HashMap<>();   // 0=白 1=灰 2=黑
        for (String start : g.nodes().keySet()) {
            if (dfsCycle(g, start, color, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfsCycle(Graph g, String key, Map<String, Integer> color, Set<String> stack) {
        int c = color.getOrDefault(key, 0);
        if (c == 1) {
            return true;      // 回到正在递归的节点 = 有环
        }
        if (c == 2) {
            return false;
        }
        color.put(key, 1);
        stack.add(key);
        for (GEdge e : g.out(key)) {
            if (dfsCycle(g, e.to(), color, stack)) {
                return true;
            }
        }
        stack.remove(key);
        color.put(key, 2);
        return false;
    }

    /**
     * 执行一个内置节点，返回被赋值的变量名（没赋值返回 null）。
     *
     * <p>两种：
     * <ul>
     *   <li>{@code __setVar}：`var` + `value`（value 里的 `${x}` 会先替换）</li>
     *   <li>{@code __expr}：`var` + `expr`（用条件求值器算，支持 `+ - * /`）</li>
     * </ul>
     */
    private String executeBuiltin(GNode node) {
        Map<String, Object> p = builtinParams.getOrDefault(node.key(), Map.of());
        String method = node.method();
        String var = str(p.get("var"));
        if (var == null || var.isBlank()) {
            throw new IllegalArgumentException("缺参数 var（写到哪个变量）");
        }
        if ("__setVar".equals(method)) {
            String raw = str(p.get("value"));
            variables.put(var, substitute(raw == null ? "" : raw));
            return var;
        }
        if ("__expr".equals(method)) {
            String expr = str(p.get("expr"));
            Object v = com.labos.kernel.engine.ConditionEvaluator.value(expr, variables);
            variables.put(var, fmt(v));
            return var;
        }
        throw new IllegalArgumentException("未知内置方法：" + method);
    }

    /** 把 `${x}` 换成变量表里的值；查不到就留空串（和条件求值器一致）。 */
    private String substitute(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\$\\{([^}]+)\\}").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(
                            variables.getOrDefault(m.group(1).trim(), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 数值去掉多余的 .0（3.0 → 3），其它类型原样。 */
    private static String fmt(Object v) {
        if (v instanceof Double d) {
            return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    private void activate(Graph g, GEdge edge, Map<String, Integer> pending,
                          Deque<String> ready, Set<String> done) {
        GNode target = g.nodes().get(edge.to());
        if (target == null) {
            return;
        }
        if (target.kind() == Kind.JOIN_PARALLEL) {
            // AND join：所有入边都到齐才推进
            int left = pending.merge(edge.to(), -1, Integer::sum);
            if (left <= 0) {
                ready.add(edge.to());
            }
        } else {
            // OR join：任一条到达即可（BPMN 语义），靠 done 去重。
            // 图里有环时不能只看 done —— 回边指向的节点本来就"做过"，不放行就永远循环不了。
            if (!done.contains(edge.to()) || graphHasCycle) {
                ready.add(edge.to());
            }
        }
    }

    /**
     * 异或选择：条件 → default → 稳定排序第一条。
     *
     * <p>存量图里 {@code conditionExpression} 使用数为 0（实测），
     * 所以绝大多数情况走 default 或第一条，并在日志里标明「未经条件」。
     */
    private GEdge choose(List<GEdge> outs, String gatewayKey) {
        if (outs.isEmpty()) {
            return null;
        }
        if (outs.size() == 1) {
            return outs.get(0);
        }
        // 条件求值：按出边的声明顺序（BPMN 里就是文档顺序），第一个成立的胜出。
        // 求值失败**不静默**：记 unevaluatedConditionCount，然后落到 default —— 
        // 宁可走默认边并留痕，也不要因为一个写错的表达式把整单卡死。
        for (GEdge e : outs) {
            if (e.condition() == null || e.condition().isBlank()) {
                continue;
            }
            try {
                if (com.labos.kernel.engine.ConditionEvaluator.eval(e.condition(), variables)) {
                    conditionHitCount++;
                    log.accept(String.format("网关 %s 条件成立 -> %s（%s）",
                            gatewayKey, e.to(), e.condition().trim()));
                    return e;
                }
            } catch (RuntimeException ex) {
                unevaluatedConditionCount++;
                log.accept(String.format("网关 %s 条件求值失败（%s），按默认边处理：%s",
                        gatewayKey, ex.getMessage(), e.condition()));
            }
        }
        for (GEdge e : outs) {
            if (e.isDefault()) {
                defaultEdgeCount++;
                log.accept(String.format("网关 %s 走 default 边 -> %s（未经条件）",
                        gatewayKey, e.to()));
                return e;
            }
        }
        GEdge first = outs.stream()
                .sorted(Comparator.comparing(GEdge::to))
                .findFirst()
                .orElse(outs.get(0));
        defaultEdgeCount++;
        log.accept(String.format("网关 %s 无 default，走稳定排序第一条 -> %s（未经条件）",
                gatewayKey, first.to()));
        return first;
    }

    // ---------------------------------------------------------------- 节点转换

    private RecoverableNode toRecoverable(GNode n) {
        CapabilitySource cs = resolveCapability(n);
        String cap = cs.capability();

        List<Long> candidates;
        Long initial;

        // 编排里显式绑了设备 → 就用这台，不参与能力轮转
        Long pinned = n.resourceId() == null ? null : boundInstanceByResource.get(n.resourceId());
        if (pinned != null) {
            return RecoverableNode.of(nodeId(n), n.key(), n.name(), cap,
                    n.method(), pinned, List.of(pinned));
        }

        if (RealWorkflowRunner.LOGIC.equals(cap)) {
            candidates = List.of(RealWorkflowRunner.LOGIC_RESOURCE);
            initial = RealWorkflowRunner.LOGIC_RESOURCE;
        } else {
            candidates = capabilityPool.getOrDefault(cap, List.of());
            if (candidates.isEmpty()) {
                initial = null;
            } else {
                // 按能力轮转：第 1 个节点用 [0]，第 2 个用 [1]，绕回后从 [0] 重新开始
                int used = usageCursor.merge(cap, 1, Integer::sum) - 1;
                initial = candidates.get(Math.floorMod(used, candidates.size()));
            }
        }

        return RecoverableNode.of(nodeId(n), n.key(), n.name(), cap,
                n.method(), initial, candidates);
    }

    /**
     * 节点能力：直接复用 {@link RealWorkflowRunner} 的六级解析。
     *
     * <p>自行实现会漏掉逻辑节点识别（Increment/SetVariable）和拼写纠错（RunProcotol），
     * 导致 3 个流程误判转人工；直接复用可避免这两类问题。
     */
    private CapabilitySource resolveCapability(GNode n) {
        return RealWorkflowRunner.resolveCapability(
                n.name(), n.method(), n.resourceId(),
                capabilityByKey.get(n.key()), protocolCapability, capabilityByResource);
    }

    /**
     * 节点 id：优先用库里的 workflow_node.id（落库、事件、子步骤都靠它对上）。
     * 只有在图里有、库里没有的节点（刚拖出来还没保存）才退回 key 的 hash。
     */
    private long nodeId(GNode n) {
        Long real = nodeIdByKey.get(n.key());
        return real != null ? real : Math.abs((long) n.key().hashCode());
    }

    // ---------------------------------------------------------------- 统计

    public int defaultEdgeCount() {
        return defaultEdgeCount;
    }

    /** 被激活的节点 key（含网关）。 */
    public Set<String> activatedKeys() {
        return Set.copyOf(activatedKeys);
    }

    public int conditionHitCount() {
        return conditionHitCount;
    }

    public int unevaluatedConditionCount() {
        return unevaluatedConditionCount;
    }

    /** 诊断：图里各类节点的数量。 */
    public static Map<String, Integer> summary(Graph g) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (GNode n : g.nodes().values()) {
            out.merge(n.kind().name(), 1, Integer::sum);
        }
        return out;
    }

    /** 诊断：并行/异或网关清单。 */
    public static Set<String> gateways(Graph g) {
        Set<String> out = new LinkedHashSet<>();
        for (GNode n : g.nodes().values()) {
            if (n.kind() == Kind.SPLIT_PARALLEL || n.kind() == Kind.JOIN_PARALLEL
                    || n.kind() == Kind.SPLIT_XOR || n.kind() == Kind.JOIN_XOR) {
                out.add(n.key() + "(" + n.kind() + ")");
            }
        }
        return out;
    }
}
