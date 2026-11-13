package com.labos.kernel.workflow;

import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.Sleeper;
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
import java.util.function.Supplier;

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
    private Map<String, Long> nodeBindingInstanceByKey = Map.of();

    /**
     * 本次执行允许的设备实例（岛内设备，device_instance.id 口径）。
     *
     * <p>null = 不做岛约束（没有权威岛的旧流程、或没带岛的请求）；
     * **空集 = 岛内确实没有设备** ⇒ 一台都不许用（P-05：岛内没有就不跑）。
     */
    private java.util.Set<Long> islandInstanceIds;

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
     * **本图自己写过**的变量名（`__setVar` / `__expr` 的目标）。
     *
     * <p>为什么需要：`__wait` 每轮都从外部刷新变量表，而刷新是整表 `putAll` ——
     * 会把本图刚写进内存、但**还没回写库**的值盖回去。
     * 例：实验启动时已经带了 `ready=0`，前面一个 `__setVar` 写成 `ready=1`，
     * 后面 `__wait ready == 1`：第一次轮询就把 1 盖回 0，然后一路等到超时。
     *
     * <p>刷新因此**只补本图没写过的键**：外部写入（设备回包、人工改库）照旧生效，
     * 本图自己的赋值以本图为准（赋值节点只改内存，见 {@link #executeBuiltin}）。
     */
    private final Set<String> locallyWrittenVars = new HashSet<>();

    /** 记一次"本图自己写过的变量"，供 {@link #withVariableRefresher} 的刷新过滤用。 */
    private String writeVar(String var, String value) {
        variables.put(var, value);
        locallyWrittenVars.add(var);
        return var;
    }

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

    /**
     * 内置节点的**过程监听器**（`__sleep` / `__wait` / `__setVar` / `__expr`）。
     *
     * <p><b>为什么必须有</b>：内置节点**不碰设备**，所以它们从来不经过
     * {@code StepSink}（设备步骤的回调）⇒ 命令台账（{@code run_command}）里
     * **一条记录都没有**。现场看到的现象是：
     * 「延时节点明明显示正在运行中，但命令台账里什么都没有」——
     * 于是台账只剩设备指令，看不出这个节点到底在做什么。
     *
     * <p>三个回调：
     * <ul>
     *   <li>{@code onStart} —— 开始执行（{@code __wait} 这类长节点要能看出"开始了"）；</li>
     *   <li>{@code onProgress} —— 等待途中的进度（"已等 12 秒"），
     *       让长节点不是一块死板；</li>
     *   <li>{@code onDone} —— 结束（成功 / 失败），带一句**人话**描述。</li>
     * </ul>
     *
     * <p>默认空实现：纯逻辑单测不连库，不该被迫实现这些回调。
     */
    public interface BuiltinListener {
        void onStart(String nodeKey, String method, Map<String, Object> params);

        /** @param note 一句人话，例如"已等 12 秒" */
        void onProgress(String nodeKey, String method, Map<String, Object> params, String note);

        void onDone(String nodeKey, String method, Map<String, Object> params,
                    boolean ok, String error, String note, long durationMs);
    }

    private BuiltinListener builtinListener = null;

    public GraphRunner withBuiltinListener(BuiltinListener l) {
        this.builtinListener = l;
        return this;
    }

    /**
     * 内置节点的一句**人话描述**（台账与节点提示都用它）。
     *
     * <p>为什么要有：{@code __wait} 这种节点光看方法名根本不知道它在等什么
     * —— 参数里是 {@code var=gate, value=open}，得翻成"等待 gate = open"。</p>
     */
    public static String describeBuiltin(String method, Map<String, Object> p) {
        if (p == null) {
            p = Map.of();
        }
        String var = str(p.get("var"));
        switch (method == null ? "" : method) {
            case "__sleep":
                return "延时 " + str(p.get("sec")) + " 秒";
            case "__wait":
                // 超时没写就显示**默认值**（DEFAULT_WAIT_TIMEOUT_SEC）——
                // 显示 "超时 null 秒" 会让人以为这个节点没设超时。
                Object to = p.get("timeout");
                String toText = (to == null || to.toString().isBlank())
                        ? String.valueOf(DEFAULT_WAIT_TIMEOUT_SEC) : str(to);
                return "等待 " + (var == null ? "(未指定变量)" : var)
                        + " = " + str(p.get("value"))
                        + "（超时 " + toText + " 秒）";
            case "__setVar":
                return "把 " + (var == null ? "(未指定变量)" : var)
                        + " 设为 " + str(p.get("value"));
            case "__expr":
                return "计算 " + (var == null ? "(未指定变量)" : var)
                        + " = " + str(p.get("expr"));
            default:
                return method == null ? "" : method;
        }
    }

    /**
     * 干跑模式（保存前预检用）：只回答「哪些节点走得到」，**不真的执行内置节点**。
     *
     * <p>为什么需要：干跑给的是一份空参数表，`__wait` 在没有真实外部写入时必然等到超时，
     * `__sleep` 缺 `sec` 会直接抛异常 —— 两种情况都会走上「转人工 + break」，
     * 于是**下游真实设备任务**被误判成「永远走不到」（预检 `PF-03`）。
     * 干跑要的是结构可达性，内置节点的取值语义在这里根本不成立。
     */
    private boolean dryRun;

    public GraphRunner withDryRun(boolean v) {
        this.dryRun = v;
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

    /** 本次图里**真正在环上**的节点（run() 开头算一次）；用于圈次/圈次行判定，非图级一刀切。 */
    private java.util.Set<String> cyclicNodeKeys = java.util.Set.of();

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

    /** 节点绑定（node_binding.position_id → device_instance.id）：node_key → instance id。
     *  命中即 pinned，跳过候选池（比 XML resource 的设备绑定优先级更高）。 */
    public GraphRunner withNodeBindingInstances(Map<String, Long> m) {
        this.nodeBindingInstanceByKey = m == null ? Map.of() : m;
        return this;
    }

    /**
     * 岛内设备实例集合（device_instance.id 口径）—— 给**显式绑定**的节点做同岛校验。
     *
     * <p>为什么 pinned 要单独校验：pinned 分支直接用绑定的那台设备，
     * **不经过能力候选池** ⇒ 岛过滤对它完全无效 ⇒ 能给本岛流程绑一台别岛的设备
     * 并在执行时真的用它。越岛 ⇒ **明确拒绝**（不静默换设备）。
     *
     * @param ids 岛内实例 id 集合；null = 不做岛约束
     */
    public GraphRunner withIslandInstances(java.util.Set<Long> ids) {
        this.islandInstanceIds = ids == null ? null : java.util.Set.copyOf(ids);
        return this;
    }

    /**
     * 离线设备实例集合（device_instance.id 口径）—— 给**显式绑定**的节点做在线校验。
     *
     * <p><b>为什么 pinned 也要查在线</b>（2026-11-11 实测发现）：
     * pinned 分支不经过能力候选池 ⇒ 候选池里"排除离线设备"对它无效。
     * 在在线状态改成「tcp client → Redis」之前，这没暴露问题：
     * 内核自己探 TCP，设备离线 ⇒ 端口不通 ⇒ 命令发出去会失败（难看但至少不假成功）。
     * 改成 Redis 判定之后，命令下发仍是内核短连接直连 ⇒ <b>设备端口可能仍然通</b>
     * ⇒ 离线设备被选中后命令照样成功 ⇒ 流程 completed，而设备其实没动。
     * 实测：把设备下线后跑流程，仍然 completed。
     *
     * <p>离线时怎么处理：走**既有的「无可用设备」路径**（候选列表为空 ⇒
     * {@code initial = null} ⇒ RunStateMachine 按"没有可用设备"处理），
     * 而不是新造一种失败语义 —— 复用现成路径，零新风险。
     */
    public GraphRunner withOfflineInstances(java.util.Set<Long> ids) {
        this.offlineInstanceIds = ids == null ? null : java.util.Set.copyOf(ids);
        return this;
    }

    /** 离线设备实例 id 集合；null = 不做在线约束（没注入就当全在线）。 */
    private java.util.Set<Long> offlineInstanceIds;

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

    /** S2 §8.2 第 3 项：派发前物料闸门（true=物料未闭环，停）。 */
    /** §3.7 闸门前置域：入参（本节点 key，直接前驱 key 列表）→ 是否阻断。默认不阻断。 */
    private java.util.function.BiFunction<String, java.util.List<String>, Boolean> materialGate =
            (key, preds) -> false;
    /** 本次推进是否因物料闸门而停止。 */
    private boolean materialGateBlocked;

    /**
     * 因暂停而没开始执行的那个节点。
     *
     * <p>任务节点：检查点在 {@code executeNode} 之前，记的是<b>待执行</b>节点。
     * 内置等待节点：{@code __sleep}/{@code __wait} 在等待途中被打断，记的是这个节点自己
     * （这一步没做完，恢复时要重跑）。
     */
    private String pausedAtKey;

    /**
     * 节点状态回调：{@code (nodeKey, status)} —— 任务/内置节点「开始执行」与「执行结束」各调一次。
     *
     * <p>为什么需要：{@code run_node}（level=0）过去是**执行前一律建 running 外壳**，
     * 到收尾才回填最终状态。于是整单运行期间查出来的状态恒为 running ——
     * 流程图只能全蓝，看不出「已完成 / 执行中 / 还没轮到」三段（2026-11-07 实测）。
     * 有了这个回调，外壳建 pending，执行时逐节点改成 running / completed。
     *
     * <p>默认空实现 —— 纯逻辑单测不需要落库。
     */
    private java.util.function.BiConsumer<String, String> nodeStateRecorder = (k, s) -> { };

    /** 注入暂停检查器（每到一个节点边界调用一次）。 */
    public GraphRunner withPauseChecker(java.util.function.BooleanSupplier checker) {
        this.pauseChecker = checker == null ? () -> false : checker;
        return this;
    }

    /** 注入派发前物料闸门（§3.7：按节点 + 其直接前驱判定，true=阻断）。 */
    public GraphRunner withMaterialGate(
            java.util.function.BiFunction<String, java.util.List<String>, Boolean> gate) {
        this.materialGate = gate == null ? (key, preds) -> false : gate;
        return this;
    }

    /** 注入节点状态回调（nodeKey, status）；status 取 {@code node_status} 词表的小写名。 */
    public GraphRunner withNodeStateRecorder(
            java.util.function.BiConsumer<String, String> rec) {
        this.nodeStateRecorder = rec == null ? (k, s) -> { } : rec;
        return this;
    }

    /** 本次 run 是否因暂停而停（true 时 run 应保持 paused，不要被收尾覆盖）。 */
    public boolean runPaused() {
        return paused;
    }

    /** 暂停停在哪个节点；没暂停时为 null。 */
    public String pausedAtKey() {
        return pausedAtKey;
    }

    /** 本次 run 是否因物料闸门而停（true 时 run 进 paused(material_sync)）。 */
    public boolean materialGateBlocked() {
        return materialGateBlocked;
    }

    /**
     * 睡眠实现。
     *
     * <p>`__sleep` / `__wait` 两个内置节点会让执行线程**真的等**，
     * 所以必须走这个抽象：单测注入 {@link Sleeper#recording(java.util.List)}，
     * 不会为了验一条等待语义把 CI 拖慢几十秒。
     */
    private Sleeper sleeper = Sleeper.REAL;

    public GraphRunner withSleeper(Sleeper s) {
        this.sleeper = s == null ? Sleeper.REAL : s;
        return this;
    }

    /**
     * 变量外部刷新源（`__wait` 用）。
     *
     * <p>`__wait` 等的是「变量变成某个值」，而这个变化**来自执行体之外**
     * （运维在库里改 `run.input.vars`、别的系统间接写入）。
     * 内存里的 {@code variables} 只有本图自己写过的值，不再读一次外部源，
     * 等待就永远是"值没变 → 超时"。
     *
     * <p>**刷新只补本图没写过的键**（见 {@link #locallyWrittenVars}）：
     * `__setVar` / `__expr` 只改内存、不回写库，整表覆盖会把刚写的值盖回旧值。
     *
     * <p>默认空实现 —— 纯逻辑单测不需要连库。
     */
    private Supplier<Map<String, String>> variablesRefresher = Map::of;

    public GraphRunner withVariableRefresher(Supplier<Map<String, String>> r) {
        this.variablesRefresher = r == null ? Map::of : r;
        return this;
    }

    /** 等待时轮询的粒度：既保证暂停能及时生效，又不至于把等待切成太碎的片。 */
    private static final long BUILTIN_WAIT_SLICE_MS = 500L;

    /** `__sleep` / `__wait` 的单次等待上限（1 天），防止参数写错把线程挂死。 */
    private static final long BUILTIN_WAIT_MAX_MS = 86_400_000L;

    /** `__wait` 没写 `timeout` 时的默认上限（秒）。和"设备让出资源"的量级一致。 */
    private static final long DEFAULT_WAIT_TIMEOUT_SEC = 300L;

    /**
     * 等待途中**报进度**的间隔（毫秒）。
     *
     * <p>为什么要有：长等待在页面上是一块死板 —— 只知道"等待节点执行中"，
     * 不知道它等了多久、还差多少。5 秒一次，够看出"在动"，又不至于刷爆库。</p>
     */
    private static final long BUILTIN_PROGRESS_MS = 5_000L;

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
        // 条件网关（包容）的分支存活性：条件汇合点靠它知道
        // 「哪条入边是分支被跳过的、永远不会来」⇒ 只等真正会到的分支。
        ConditionalBranchTracker tracker = new ConditionalBranchTracker(graph);
        // 每个节点走了几圈。**只有图里真的存在环（回边）时才允许重入**，
        // 否则并行/异或的重复激活会被误当成循环，把设备动作多跑一遍。
        Map<String, Integer> visits = new HashMap<>();
        boolean hasCycle = hasCycle(graph);
        this.graphHasCycle = hasCycle;
        this.cyclicNodeKeys = cyclicNodes(graph);
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
            } else if ((node.kind() == Kind.TASK || node.kind() == Kind.BUILTIN) && dryRun) {
                // 干跑：任务/内置节点**只当一步走过去**，不执行、不取值、不分配设备。
                // 真执行的话，`__wait` 必然超时、`__sleep` 缺参会抛异常；
                // 任务节点还要选设备 —— 干跑没有真实设备可分配（能力解析不到就 MANUAL），
                // 三种情况都会让整图 break，把下游真实设备任务误判成"走不到"（PF-03）。
                progress.complete(nodeId(node), "[干跑] " + node.method());
            } else if (node.kind() == Kind.BUILTIN) {
                // 内置节点：不碰设备，直接算。算不出来就转人工停下（和人工节点一致），
                // 不能带着错值往下跑 —— 后面可能拿它做条件分支。
                nodeStateRecorder.accept(key, "running");
                Map<String, Object> bp = builtinParams.getOrDefault(key, Map.of());
                String desc = describeBuiltin(node.method(), bp);
                long t0 = System.currentTimeMillis();
                if (builtinListener != null) {
                    try {
                        builtinListener.onStart(key, node.method(), bp);
                    } catch (RuntimeException ignore) {
                        // 台账写不进去不能影响执行
                    }
                }
                try {
                    String var = executeBuiltin(node);
                    if (paused) {
                        // `__sleep` / `__wait` 在等待途中收到暂停请求：这一步**没做完**，
                        // 所以不留 completed 记录、也不往下走 —— 恢复时它不在 skipKeys 里，
                        // 会被重新执行（和在任务节点边界暂停是同一套语义）。
                        pausedAtKey = key;
                        if (builtinListener != null) {
                            try {
                                builtinListener.onDone(key, node.method(), bp, false,
                                        "paused", desc, System.currentTimeMillis() - t0);
                            } catch (RuntimeException ignore) {
                                // 台账写不进去不能影响执行
                            }
                        }
                        log.accept("[暂停] 内置节点 " + key + " 等待期间收到暂停请求，停在边界");
                        break;
                    }
                    progress.complete(nodeId(node), "[内置] " + node.method()
                            + (var == null ? "" : " → " + var + "=" + variables.get(var)));
                    nodeStateRecorder.accept(key, "completed");
                    if (builtinListener != null) {
                        try {
                            String note = var == null ? desc
                                    : desc + " → " + var + " = " + variables.get(var);
                            builtinListener.onDone(key, node.method(), bp, true, null, note,
                                    System.currentTimeMillis() - t0);
                        } catch (RuntimeException ignore) {
                            // 台账写不进去不能影响执行
                        }
                    }
                    log.accept("[内置] " + key + " " + node.method()
                            + (var == null ? "" : " → " + var + "=" + variables.get(var)));
                } catch (RuntimeException ex) {
                    progress.manual(nodeId(node),
                            "[内置] " + node.method() + " 失败：" + ex.getMessage(),
                            "EC-BUILTIN-001");
                    nodeStateRecorder.accept(key, "manual");
                    if (builtinListener != null) {
                        try {
                            builtinListener.onDone(key, node.method(), bp, false,
                                    ex.getMessage(), desc, System.currentTimeMillis() - t0);
                        } catch (RuntimeException ignore) {
                            // 台账写不进去不能影响执行
                        }
                    }
                    log.accept("[内置] " + key + " 失败，停止等待人工：" + ex.getMessage());
                    break;
                }
            } else if (node.kind() == Kind.TASK) {
                // S2 §8.2 第 3/12 项：派发前物料闸门（§3.7 前置域）——
                // 只判「直接前驱」的物料是否闭环，不是整 run 一刀切。
                java.util.List<String> preds = graph.in(key).stream()
                        .map(GEdge::from).toList();
                if (materialGate.apply(key, preds)) {
                    ready.offer(key);
                    done.remove(key);
                    paused = true;
                    materialGateBlocked = true;
                    pausedAtKey = key;
                    log.accept("[闸门] 物料未闭环，停止派发，待执行节点：" + key);
                    break;
                }
                // P1-8：在**任务节点的动作边界**检查暂停。
                // 不停在动作中间 —— 机械臂抓着板会掉。
                // 也不停 start/gateway —— 它们不是"一步活"，停在那里没意义。
                // 把 key 放回队列，恢复时从这里继续。
                if (pauseChecker.getAsBoolean()) {
                    ready.offer(key);
                    done.remove(key);
                    paused = true;
                    pausedAtKey = key;
                    log.accept("[暂停] 收到暂停请求，在节点边界停下，待执行节点：" + key);
                    break;
                }
                RecoverableNode rn = toRecoverable(node);
                java.time.Instant roundStart = java.time.Instant.now();
                nodeStateRecorder.accept(key, "running");
                // 评审 20261112-13 / 复杂流程实证：把本圈 visit 透传到 StepContext，
                // 让命令账本幂等键能区分圈次。第 1 圈 visit=1 与旧行为一致（不拼后缀），
                // 第 2 圈起才加 |vN，因此非循环流程的 key 完全不变。
                // 圈次口径：只有**真有回边**的图才有圈次行（loop_index=1..N），
                // 无环图的 visit 恒为 1 但没有对应 run_node 行 —— 若把 1 传给物料，
                // Effect.source_loop_index=1 会破坏 Q3（effect → run_node 按 node_key+loop_index）。
                // 命令账本 key 只看 loopIndex>1，所以无环图传 0 不影响幂等。
                // 圈次口径：只给**真正在环上**的节点 visit（1..N）；图里有环但不在环上的
                // 节点（如环外的前置/收尾节点）仍应传 0，否则会写错 source_loop_index / 圈次行。
                final int round = cyclicNodeKeys.contains(key) ? visit : 0;
                StepExecutor roundAware = c -> executor.execute(c.withLoopIndex(round));
                // R13-06：完成处理器也要拿到圈次，才能把 Effect 写到真实的
                // source_loop_index（执行确认仍写 loop_index=0 的汇总行）。
                stateMachine.withLoopIndexSupplier(() -> round);
                NodeStatus st = stateMachine.executeNode(rn, progress, roundAware);
                nodeStateRecorder.accept(key, st.name().toLowerCase());
                // 只在**图里真有环**时记圈次：无环图每个节点只跑一次，
                // 汇总行已经代表那一次，再记一行就是重复（会让"节点数"翻倍，
                // 2026-11-03 verify-device-chain 的 3 步流程就是这么红的）。
                if (cyclicNodeKeys.contains(key) && visit >= 1) {
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
            if (node.kind() == Kind.SPLIT_XOR && !dryRun) {
                GEdge chosen = choose(outs, key);
                // 先记「被跳过的分支」⇒ 条件汇合点知道哪条入边永远不会来。
                // 必须在激活分支之前做：分支节点马上就会跑。
                tracker.markSkipped(graph, outs,
                        chosen == null ? Set.of() : Set.of(chosen.id()));
                if (chosen != null) {
                    activate(graph, chosen, pending, ready, done, tracker);
                }
                recheckConditionalJoins(graph, ready, done, tracker);
            } else if (node.kind() == Kind.SPLIT_CONDITIONAL && !dryRun) {
                // 条件分叉（包容语义）：**条件成立的几条分支都走**。
                List<GEdge> taken = chooseAll(outs, key);
                Set<String> takenIds = new HashSet<>();
                for (GEdge e : taken) {
                    takenIds.add(e.id());
                }
                tracker.markSkipped(graph, outs, takenIds);
                for (GEdge e : taken) {
                    activate(graph, e, pending, ready, done, tracker);
                }
                recheckConditionalJoins(graph, ready, done, tracker);
            } else {
                // 干跑（保存前预检）里异或/条件**不分叉**，出边全部算可达。
                // 条件读的是运行时变量，干跑时值未知 —— 例如 `${mode} == 'go'`，
                // `mode` 由 `__setVar` 在运行到那一步时才写；干跑按 default 走单边，
                // 会把另一条分支判成「永远走不到」（PF-03 假报）。
                // 预检只回答「有没有可能走到」，所以这里取并集。
                for (GEdge e : outs) {
                    activate(graph, e, pending, ready, done, tracker);
                }
            }
        }

        return progress;
    }

    // ---------------------------------------------------------------- 推进

    /**
     * 返回「真正在环上」的节点集合：存在从自己出发、经过至少一条边回到自己的路径。
     *
     * <p>与图级 {@link #hasCycle} 的区别：图里有环时，环外的前置/收尾节点也在同一张图里，
     * 但它们不会被回边重入，圈次应写 0 而不是 1。逐个节点 BFS 判「能否回到自己」。
     */
    private static java.util.Set<String> cyclicNodes(Graph g) {
        java.util.Set<String> cyclic = new java.util.HashSet<>();
        for (String start : g.nodes().keySet()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
            for (GEdge e : g.out(start)) {
                stack.push(e.to());
            }
            while (!stack.isEmpty()) {
                String cur = stack.pop();
                if (cur.equals(start)) {
                    cyclic.add(start);
                    break;
                }
                if (!seen.add(cur)) {
                    continue;
                }
                for (GEdge e : g.out(cur)) {
                    stack.push(e.to());
                }
            }
        }
        return cyclic;
    }

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
     * <p>四种（`protocolName` 以 `__` 开头，解析器直接归为 {@link Kind#BUILTIN}）：
     * <ul>
     *   <li>{@code __setVar}：`var` + `value`（value 里的 `${x}` 会先替换）</li>
     *   <li>{@code __expr}：`var` + `expr`（用条件求值器算，支持 `+ - * /`）</li>
     *   <li>{@code __sleep}：`sec`（在这里停多少秒）</li>
     *   <li>{@code __wait}：`var` + `value`（等它变成这个值才算通过）+
     *       `timeout`（秒，默认 300）+ `interval`（轮询间隔秒，默认 1）</li>
     * </ul>
     *
     * <p>`__sleep` / `__wait` 都会**真的等**，但分成 500ms 的片，每片之间查一次暂停 ——
     * 否则一个"停 1800 秒"会让暂停按钮半小时没反应（暂停是现场级事件）。
     */
    private String executeBuiltin(GNode node) {
        Map<String, Object> p = builtinParams.getOrDefault(node.key(), Map.of());
        String method = node.method();
        if ("__sleep".equals(method)) {
            sleepSeconds(node, p);
            return null;
        }
        if ("__wait".equals(method)) {
            waitForVariable(node, p);
            return null;
        }
        String var = requireVar(p);
        if ("__setVar".equals(method)) {
            String raw = str(p.get("value"));
            return writeVar(var, substitute(raw == null ? "" : raw));
        }
        if ("__expr".equals(method)) {
            String expr = str(p.get("expr"));
            Object v = com.labos.kernel.engine.ConditionEvaluator.value(expr, variables);
            return writeVar(var, fmt(v));
        }
        throw new IllegalArgumentException("未知内置方法：" + method);
    }

    private static String requireVar(Map<String, Object> p) {
        String var = str(p.get("var"));
        if (var == null || var.isBlank()) {
            throw new IllegalArgumentException("缺参数 var（写到哪个变量）");
        }
        return var.trim();
    }

    /** `__sleep`：在这里停多少秒（`sec`，也接受 `seconds`）。缺失或非正数直接报错，不做静默空转。 */
    private void sleepSeconds(GNode node, Map<String, Object> p) {
        Object raw = p.get("sec") != null ? p.get("sec") : p.get("seconds");
        double sec = toDouble(raw);
        if (sec <= 0) {
            throw new IllegalArgumentException("缺参数 sec（在这里停多少秒，须为正数）");
        }
        long ms = Math.min(Math.round(sec * 1000L), BUILTIN_WAIT_MAX_MS);
        log.accept("[内置] " + node.key() + " 延时 " + fmt(sec) + " 秒");
        waitInterruptibly(ms);
    }

    /**
     * `__wait`：等到 `var` 变成 `value` 才算通过。
     *
     * <p>每轮先调一次外部刷新源再比对 —— 变量的变化来自执行体之外，
     * 只读内存表等于永远等不到。超时不成立就抛异常 → 上层记 manual 停下等人，
     * 不会带着"条件没满足"继续往下跑。
     */
    private void waitForVariable(GNode node, Map<String, Object> p) {
        String var = requireVar(p);
        String rawWant = str(p.get("value"));
        String want = rawWant == null ? "" : substitute(rawWant);
        double timeoutSec = toDouble(p.get("timeout"));
        if (timeoutSec <= 0) {
            timeoutSec = DEFAULT_WAIT_TIMEOUT_SEC;
        }
        long timeoutMs = Math.min(Math.round(timeoutSec * 1000L), BUILTIN_WAIT_MAX_MS);
        double intervalSec = toDouble(p.get("interval"));
        if (intervalSec <= 0) {
            intervalSec = 1;
        }
        long intervalMs = Math.max(1L, Math.min(Math.round(intervalSec * 1000L), timeoutMs));
        log.accept("[内置] " + node.key() + " 等待 " + var + " = " + want
                + "（超时 " + fmt(timeoutSec) + " 秒）");
        // 用「已经睡掉的量」当计时，不读墙钟：单测注入的 Sleeper 不会推进系统时间，
        // 拿 System.currentTimeMillis() 当 deadline 会让纯逻辑用例永远等不到超时。
        long waited = 0;
        long lastReport = 0;
        while (true) {
            try {
                Map<String, String> ext = variablesRefresher.get();
                if (ext != null && !ext.isEmpty()) {
                    // 只补本图没写过的键：本图的 `__setVar` / `__expr` 只改内存、
                    // 不回写 run.input.vars，整表 putAll 会把刚写的值盖回旧值，
                    // 于是"前面赋值、后面等它"在有同名初始变量时必然等到超时。
                    for (Map.Entry<String, String> e : ext.entrySet()) {
                        if (!locallyWrittenVars.contains(e.getKey())) {
                            variables.put(e.getKey(), e.getValue());
                        }
                    }
                }
            } catch (RuntimeException ex) {
                log.accept("[内置] 读取外部变量失败（忽略）：" + ex.getMessage());
            }
            String cur = variables.get(var);
            if (sameValue(cur, want)) {
                log.accept("[内置] " + node.key() + " 条件成立：" + var + " = " + want);
                return;
            }
            if (waited >= timeoutMs) {
                throw new IllegalStateException("等待超时（" + fmt(timeoutSec) + " 秒）："
                        + var + " 仍未变为 " + want + "（当前 " + (cur == null ? "未设置" : cur) + "）");
            }
            // 等待途中**定期报进度**：否则长等待在页面上是一块死板 ——
            // 现场看到「等待节点 执行中」但不知道它在等什么、等了多久。
            if (builtinListener != null && waited - lastReport >= BUILTIN_PROGRESS_MS) {
                lastReport = waited;
                try {
                    builtinListener.onProgress(node.key(), node.method(), p,
                            "已等 " + (waited / 1000) + " 秒（当前 "
                                    + (cur == null ? "未设置" : cur) + "）");
                } catch (RuntimeException ignore) {
                    // 台账写不进去不能影响执行
                }
            }
            long nap = Math.min(intervalMs, timeoutMs - waited);
            waitInterruptibly(nap);
            waited += nap;
            if (paused) {
                return;   // 上层看到 paused 会停在边界，这一步不算完成
            }
        }
    }

    /**
     * 分片等待：每 {@link #BUILTIN_WAIT_SLICE_MS} 毫秒查一次暂停。
     *
     * <p>查到暂停就置 {@link #paused} 并立刻返回（不等完剩下的时间）。
     */
    private void waitInterruptibly(long totalMs) {
        long left = totalMs;
        while (left > 0) {
            long slice = Math.min(left, BUILTIN_WAIT_SLICE_MS);
            try {
                sleeper.sleep(slice);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断");
            }
            left -= slice;
            if (pauseChecker.getAsBoolean()) {
                paused = true;
                return;
            }
        }
    }

    /** 变量取值比较：两边都能当数字就按数字比（`10` == `10.0`），否则按字符串比。 */
    private static boolean sameValue(String a, String b) {
        if (a == null) {
            return b == null;
        }
        if (a.equals(b)) {
            return true;
        }
        try {
            return Double.parseDouble(a.trim()) == Double.parseDouble(b.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static double toDouble(Object o) {
        if (o == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
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
                          Deque<String> ready, Set<String> done,
                          ConditionalBranchTracker tracker) {
        GNode target = g.nodes().get(edge.to());
        if (target == null) {
            return;
        }
        tracker.markArrived(edge);
        if (target.kind() == Kind.JOIN_PARALLEL) {
            // AND join：所有入边都到齐才推进
            int left = pending.merge(edge.to(), -1, Integer::sum);
            if (left <= 0) {
                ready.add(edge.to());
            }
        } else if (target.kind() == Kind.JOIN_CONDITIONAL) {
            // 条件汇合：只等**确实会到**的入边到齐 —— 被跳过的分支不算数，
            // 否则那个分支永远不会来，汇合点就死等了。
            if (tracker.joinReady(g, edge.to())
                    && (!done.contains(edge.to()) || graphHasCycle)) {
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
     * 死分支集合变了 ⇒ 重新判定所有条件汇合点。
     *
     * <p><b>为什么需要</b>：条件汇合点可能在「另一条分支还没决定」时
     * 先收到一条入边，那它按「还有未知入边」继续等。等分叉决定完、
     * 那条分支被跳过（判死），汇合点「要等几条」就变少了 ——
     * 得回头再判一次，否则它会一直等下去。
     */
    private void recheckConditionalJoins(Graph g, Deque<String> ready, Set<String> done,
                                         ConditionalBranchTracker tracker) {
        for (GNode n : g.nodes().values()) {
            if (n.kind() != Kind.JOIN_CONDITIONAL) {
                continue;
            }
            if (done.contains(n.key()) && !graphHasCycle) {
                continue;
            }
            if (tracker.joinReady(g, n.key())) {
                ready.add(n.key());
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

    /**
     * <b>条件分叉</b>选边：<b>所有条件成立的出边都走</b>（包容语义）。
     *
     * <p>与异或 {@link #choose} 的差别只有一处：异或是「第一条成立就赢」
     * （后面的条件根本不会被求值），这里是「成立的全都走」——
     * 这正是条件网关存在的意义：表达「合格就<b>同时</b>送去分析和存档」
     * 这种多路并行的编排。
     *
     * <p><b>兜底口径与异或保持一致</b>：一条都没成立时走 {@code default} 边，
     * 没有 default 就走稳定排序第一条。
     *
     * <p><b>没写条件的出边不自动走</b>，只在兜底时可能被选中 ——
     * 否则「无条件边 = 永远走」会和「default = 兜底」两套规则打架，
     * 同一张图里两种网关行为不一致更难排查。
     */
    private List<GEdge> chooseAll(List<GEdge> outs, String gatewayKey) {
        if (outs.isEmpty()) {
            return List.of();
        }
        if (outs.size() == 1) {
            // 单出边 = 直通（与异或同一口径：不求值条件）
            return outs;
        }
        List<GEdge> taken = new ArrayList<>();
        for (GEdge e : outs) {
            if (e.condition() == null || e.condition().isBlank()) {
                continue;
            }
            try {
                if (com.labos.kernel.engine.ConditionEvaluator.eval(e.condition(), variables)) {
                    conditionHitCount++;
                    taken.add(e);
                    log.accept(String.format("网关 %s 条件成立 -> %s（%s）",
                            gatewayKey, e.to(), e.condition().trim()));
                }
            } catch (RuntimeException ex) {
                // 求值失败**不静默**：记数，然后这条分支当没成立
                // （不能因为一个写错的表达式把整单卡死，也不能猜它成立）。
                unevaluatedConditionCount++;
                log.accept(String.format("网关 %s 条件求值失败（%s），该分支不走：%s",
                        gatewayKey, ex.getMessage(), e.condition()));
            }
        }
        if (!taken.isEmpty()) {
            return taken;
        }
        // 一条都没成立：default → 稳定排序第一条
        for (GEdge e : outs) {
            if (e.isDefault()) {
                defaultEdgeCount++;
                log.accept(String.format("网关 %s 走 default 边 -> %s（无分支条件成立）",
                        gatewayKey, e.to()));
                return List.of(e);
            }
        }
        GEdge first = outs.stream()
                .sorted(Comparator.comparing(GEdge::to))
                .findFirst()
                .orElse(outs.get(0));
        defaultEdgeCount++;
        log.accept(String.format("网关 %s 无 default，走稳定排序第一条 -> %s（无分支条件成立）",
                gatewayKey, first.to()));
        return List.of(first);
    }

    // ---------------------------------------------------------------- 节点转换

    private RecoverableNode toRecoverable(GNode n) {
        CapabilitySource cs = resolveCapability(n);
        String cap = cs.capability();

        List<Long> candidates;
        Long initial;

        // 节点绑定（node_binding.position_id → device_instance）：优先级最高，
        // 命中即 pinned、跳过候选池。比 XML resource 的设备绑定更优先。
        Long bindingInstance = nodeBindingInstanceByKey.get(n.key());
        if (bindingInstance != null) {
            if (islandInstanceIds != null && !islandInstanceIds.contains(bindingInstance)) {
                throw new IllegalArgumentException("节点 " + n.key() + " 绑定的点位所在设备（实例 "
                        + bindingInstance + "）不在流程所属的岛内，拒绝执行");
            }
            if (offlineInstanceIds != null && offlineInstanceIds.contains(bindingInstance)) {
                return RecoverableNode.of(nodeId(n), n.key(), n.name(), cap,
                        n.method(), null, List.of());
            }
            return RecoverableNode.of(nodeId(n), n.key(), n.name(), cap,
                    n.method(), bindingInstance, List.of(bindingInstance));
        }

        // 编排里显式绑了设备 → 就用这台，不参与能力轮转
        Long pinned = n.resourceId() == null ? null : boundInstanceByResource.get(n.resourceId());
        if (pinned != null) {
            // P-05：pinned 绕过候选池 ⇒ 岛过滤对它无效 ⇒ 这里单独守同岛。
            // 越岛绑定**明确拒绝**（不静默换一台岛内的设备跑 —— 那等于偷偷改了
            // 工艺要的设备，现场看到的却是"跑通了"）。
            if (islandInstanceIds != null && !islandInstanceIds.contains(pinned)) {
                throw new IllegalArgumentException("节点 " + n.key() + " 绑定的设备（实例 "
                        + pinned + "）不在流程所属的岛内，拒绝执行：设备归属由服务端决定，"
                        + "执行时不能换设备");
            }
            // 绑定的设备**离线** ⇒ 走既有的「无可用设备」路径（候选列表为空）。
            // 不能静默用别的设备（那是偷偷改了工艺要的设备），
            // 也不能当没事发生（命令走短连接仍可能发得出去 ⇒ 假成功）。
            if (offlineInstanceIds != null && offlineInstanceIds.contains(pinned)) {
                return RecoverableNode.of(nodeId(n), n.key(), n.name(), cap,
                        n.method(), null, List.of());
            }
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
                    || n.kind() == Kind.SPLIT_XOR || n.kind() == Kind.JOIN_XOR
                    || n.kind() == Kind.SPLIT_CONDITIONAL || n.kind() == Kind.JOIN_CONDITIONAL) {
                out.add(n.key() + "(" + n.kind() + ")");
            }
        }
        return out;
    }
}
