package com.labos.kernel.engine;

import com.labos.kernel.recovery.ErrorContext;
import com.labos.kernel.recovery.RecoveryAction;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryDecision;

import java.time.Clock;
import java.util.List;

/**
 * 执行状态机：把「执行 → 失败 → 决策 → 重试/换机/放弃」的闭环串起来。
 *
 * <p>职责边界清晰：
 * <ul>
 *   <li>状态机只管控制流与状态，<b>不做决策</b>（决策在 RecoveryDecider）</li>
 *   <li>不碰 IO——执行交给 {@link StepExecutor}，事件交给 {@link ExecutionListener}</li>
 *   <li>不睡真实时间——等待交给 {@link Sleeper}，单测可替换</li>
 * </ul>
 *
 * <p>这套拆分让整条链路可单测、可回放、可仿真。
 *
 * <p><b>线程安全</b>：本类<span>非</span>线程安全（{@code seq} 为实例字段且非原子）。
 * 约定「一个 run 一个实例」，由调用方保证串行驱动；若需并发跑多个 run，
 * 每个 run 各自 new 一个状态机即可（它们是独立的）。
 */
public final class RunStateMachine {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RunStateMachine.class);
    /** 兼容原有 System.err.printf 的格式化输出。 */
    private static void errorf(String format, Object... args) {
        log.error(String.format(format, args));
    }

    private final RecoveryDecider decider;
    private final ExecutionListener listener;
    private final Sleeper sleeper;
    private final Clock clock;
    private long seq = 0;
    /** 节点 -> 子步骤序列；未设置时按单步执行（99% 的流程是这种）。 */
    private java.util.function.Function<Long, java.util.List<String>> stepPlanner;
    /** 节点 -> 设备方法参数；未设置时为空（流程本身不带参数）。 */
    private java.util.function.Function<Long, java.util.Map<String, Object>> paramPlanner;
    /**
     * 节点 key -> "为什么没有设备"（可选）。
     *
     * <p>为什么需要：默认消息是"无可执行资源：节点未给出初始设备，且候选集为空"，
     * 现场只看得到"候选集为空"，**看不出为什么空**。P-05 里最常见的原因是
     * "岛内没有具备该能力的设备" —— 注入它，运营就不用猜。
     */
    private java.util.function.Function<String, String> noDeviceReason;
    /** §3.1 完成处理器注入点（默认 NOOP=未接入；主链路在 WorkflowService 接入）。 */
    private MaterialCompletion materialCompletion = MaterialCompletion.NOOP;
    /**
     * §8.2 第 3 项：顺序执行路径（executeAll）的**派发前**物料闸门。
     * 图路径由 GraphRunner 自带闸门，这里补的是「LABOS_EXEC_GRAPH=0 / 图解析失败退回」
     * 那条顺序路径 —— 评审 R13-01 发现它此前完全没有防线。
     */
    private java.util.function.BooleanSupplier materialGate;

    /**
     * 当前节点在本轮执行中的圈次（R13-06）：由 GraphRunner 在每次派发前注入 visit，
     * 供完成处理器写 Effect 的 {@code source_loop_index}。无环图 / 顺序路径恒 0。
     */
    private java.util.function.IntSupplier loopIndexSupplier = () -> 0;

    public RunStateMachine(RecoveryDecider decider) {
        this(decider, ExecutionListener.NOOP, Sleeper.REAL, Clock.systemUTC());
    }

    public RunStateMachine(RecoveryDecider decider, ExecutionListener listener,
                           Sleeper sleeper, Clock clock) {
        if (decider == null) {
            throw new IllegalArgumentException("decider 不能为空");
        }
        this.decider = decider;
        this.listener = listener == null ? ExecutionListener.NOOP : listener;
        this.sleeper = sleeper == null ? Sleeper.REAL : sleeper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 批量执行：遇 MANUAL / ABORT / 物料未闭环即停止后续节点。 */
    public RunProgress executeAll(List<RecoverableNode> nodes, StepExecutor executor) {
        RunProgress progress = new RunProgress();
        for (RecoverableNode node : nodes) {
            if (progress.aborted()) {
                break;
            }
            // §8.2 第 3 项：派发前闸门（顺序路径）。命中即扣住后继并写 materialHold，
            // 上层按「物理完成、物料未闭环」收尾，不得继续驱动设备。
            if (materialGate != null && materialGate.getAsBoolean()) {
                progress.markMaterialHold();
                break;
            }
            NodeStatus st = executeNode(node, progress, executor);
            if (st == NodeStatus.ABORTED || st == NodeStatus.MANUAL || progress.materialHold()) {
                break;
            }
        }
        return progress;
    }

    /** 注入顺序路径的派发前物料闸门；图路径由 GraphRunner.withMaterialGate 负责。 */
    public RunStateMachine withMaterialGate(java.util.function.BooleanSupplier gate) {
        this.materialGate = gate;
        return this;
    }

    /**
     * 注入圈次来源（R13-06）。GraphRunner 每次派发前设成本圈 visit；
     * 顺序路径不设，保持 0。
     */
    public RunStateMachine withLoopIndexSupplier(java.util.function.IntSupplier supplier) {
        this.loopIndexSupplier = supplier == null ? () -> 0 : supplier;
        return this;
    }

    /**
     * 执行单个节点，内部完成「失败 → 决策 → 处置」循环，直到节点收尾。
     */
    public NodeStatus executeNode(RecoverableNode node, RunProgress progress, StepExecutor executor) {
        if (node == null || progress == null || executor == null) {
            throw new IllegalArgumentException("参数不能为空");
        }

        NodeProgress np = progress.node(node.id());
        Long resource = np.currentResourceId() != null ? np.currentResourceId() : node.initialResourceId();

        if (resource == null) {
            np.setStatus(NodeStatus.MANUAL);
            np.setLastReason(noDeviceReasonText(node));
            emit(node, EventType.NODE_MANUAL, np.lastReason());
            return NodeStatus.MANUAL;
        }
        np.setCurrentResource(resource);
        emit(node, EventType.NODE_STARTED,
                String.format("resource=%d capability=%s", resource, node.capabilityCode()));

        // 评审 R13-02：成功路径的完成处理器一旦写过 completed（哪怕随后抛异常），
        // 第 7 出口都不得再写一次 reconciliation_required 把它与物理证据三件套覆盖掉。
        boolean materialHandlerInvoked = false;
        while (true) {
            try {
                executor.execute(new StepContext(node.id(), node.key(),
                        node.capabilityCode(), node.method(), resource, np.attempt(),
                        stepsOf(node), paramsOf(node)));
                np.setStatus(NodeStatus.COMPLETED);
                emit(node, EventType.NODE_COMPLETED, "attempt=" + np.attempt());
                materialHandlerInvoked = true;
                invokeMaterialHandler(node, ExecutionResolution.COMPLETED, null, progress);
                return NodeStatus.COMPLETED;

            } catch (RuntimeException escape) {
                // 第 7 个物理出口（§8.2 第 1 项 / S10-03）：非 StepFailure 的
                // RuntimeException 逃逸出 executeNode。设备可能已动作但物料未闭环 ⇒
                // 先写执行确认（保守 reconciliation_required）再落 failed，不得直接标 failed。
                // 已形成终态确认的节点不再重复写；写不进时会由完成处理器抛
                // MaterialPersistenceException，上层据此隔离 run（§3.6）。
                if (!materialHandlerInvoked) {
                    invokeMaterialHandler(node,
                            ExecutionResolution.classify(null, null), null, progress);
                }
                throw escape;

            } catch (StepFailure f) {
                np.setLastErrorCode(f.errorCode());
                emit(node, EventType.STEP_FAILED, f.describe());

                ErrorContext ctx = ErrorContext.builder(f.errorCode(), f.scope())
                        .attempt(np.attempt())
                        .currentResourceId(resource)
                        .capabilityCode(node.capabilityCode())
                        .candidateResourceIds(node.candidateResourceIds())
                        .failedResourceIds(np.failedResourceIds())
                        .build();

                RecoveryDecision d = decider.decide(ctx);
                np.setLastReason(d.reason());
                emit(node, EventType.RECOVERY_DECIDED, d.action() + " | " + d.reason());

                switch (d.action()) {
                    case RETRY -> {
                        np.incrementAttempt();
                        if (!sleep(d.delayMillis())) {
                            // P1-16 修复（评审 20261102-03）：这里置了 ABORTED 但
                            // **没调 progress.markAborted()** —— 与下面 ABORT 分支不一致。
                            // 后果：GraphRunner 的 while(!progress.aborted()) 不感知，
                            // 同一张图里其他分支可能继续推进，状态与节点记录对不上。
                            np.setStatus(NodeStatus.ABORTED);
                            progress.markAborted();
                            emit(node, EventType.RUN_ABORTED, "等待中被中断");
                            return NodeStatus.ABORTED;
                        }
                    }
                    case REASSIGN -> {
                        np.addFailedResource(resource);
                        np.incrementAttempt();
                        Long target = d.targetResourceId();
                        np.setCurrentResource(target);
                        emit(node, EventType.RESOURCE_SWITCHED, resource + " -> " + target);
                        resource = target;
                    }
                    case SKIP -> {
                        np.setStatus(NodeStatus.SKIPPED);
                        emit(node, EventType.NODE_SKIPPED, d.reason());
                        // 恢复决定跳过 = 设备确认未执行
                        invokeMaterialHandler(node, ExecutionResolution.FAILED_CONFIRMED,
                                f.errorCode(), progress);
                        return NodeStatus.SKIPPED;
                    }
                    case ABORT -> {
                        np.setStatus(NodeStatus.ABORTED);
                        progress.markAborted();
                        emit(node, EventType.RUN_ABORTED, d.reason());
                        invokeMaterialHandler(node, ExecutionResolution.UNRESOLVED,
                                f.errorCode(), progress);
                        return NodeStatus.ABORTED;
                    }
                    case MANUAL -> {
                        np.setStatus(NodeStatus.MANUAL);
                        emit(node, EventType.NODE_MANUAL, d.reason());
                        // §3.2：超时/未知 → reconciliation_required；确认失败 → failed_confirmed
                        invokeMaterialHandler(node, ExecutionResolution.of(f),
                                f.errorCode(), progress);
                        return NodeStatus.MANUAL;
                    }
                    default -> throw new IllegalStateException("未覆盖的决策动作: " + d.action());
                }
            }
        }
    }

    /** 在节点执行结果边界调用完成处理器；HOLD 写回 progress，处理器异常向上抛（中止执行）。 */
    private void invokeMaterialHandler(RecoverableNode node, ExecutionResolution resolution,
                                       String errorCode, RunProgress progress) {
        try {
            MaterialOutcome outcome = materialCompletion.onNodeComplete(
                    NodeResolution.of(node.id(), node.key(), resolution, errorCode,
                            loopIndexSupplier.getAsInt()));
            if (outcome == MaterialOutcome.HOLD) {
                progress.markMaterialHold();
            }
        } catch (RuntimeException ex) {
            errorf("[执行确认] 完成处理器异常（中止执行）: %s%n", ex.toString());
            throw ex;
        }
    }

    private boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 配置步骤规划器：节点编译成多步。
     *
     * <p>不放在构造器里是为了不破坏现有调用方（5 处 new）。
     */
    public RunStateMachine withStepPlanner(
            java.util.function.Function<Long, java.util.List<String>> planner) {
        this.stepPlanner = planner;
        return this;
    }

    /**
     * 注入"为什么没有设备"的原因（node key -> 原因）。
     *
     * <p>不放在构造器里是为了不破坏现有调用方（十几处 new）。
     *
     * @param f 原因提供器；null = 不注入（用默认那句话）
     */
    public RunStateMachine withNoDeviceReason(
            java.util.function.Function<String, String> reasonProvider) {
        this.noDeviceReason = reasonProvider;
        return this;
    }

    /** 注入完成处理器（§3.1 唯一注入点）；不调则默认 NOOP（CONTINUE）。 */
    public RunStateMachine withMaterialCompletion(MaterialCompletion completion) {
        this.materialCompletion = completion == null ? MaterialCompletion.NOOP : completion;
        return this;
    }

    /**
     * "没有设备"的原因文案：默认那句 + 注入的具体原因。
     *
     * <p>注入的原因提供器**不许把执行搞挂** —— 它只是给现场补一句话，
     * 自己抛异常就当没有（fail-safe：文案缺失不能变成执行失败）。
     */
    private String noDeviceReasonText(RecoverableNode node) {
        String base = "无可执行资源：节点未给出初始设备，且候选集为空";
        if (noDeviceReason == null) {
            return base;
        }
        String extra;
        try {
            extra = noDeviceReason.apply(node.key());
        } catch (RuntimeException e) {
            return base;
        }
        return (extra == null || extra.isBlank()) ? base : base + "（" + extra + "）";
    }

    /** 配置参数规划器：节点执行时携带的设备方法参数。 */
    public RunStateMachine withParamPlanner(
            java.util.function.Function<Long, java.util.Map<String, Object>> planner) {
        this.paramPlanner = planner;
        return this;
    }

    private java.util.Map<String, Object> paramsOf(RecoverableNode node) {
        return paramPlanner == null
                ? java.util.Map.of()
                : paramPlanner.apply(node.id());
    }

    private java.util.List<String> stepsOf(RecoverableNode node) {
        return stepPlanner == null
                ? java.util.List.of()
                : stepPlanner.apply(node.id());
    }

    /**
     * 发出事件。
     *
     * <p>监听器是副作用出口（落库/推送/告警），**它的故障不得影响执行本身**：
     * 否则一次数据库抖动就会让正在跑的实验停摆。这里捕获并告警，
     * 而不是让异常冒泡打断状态机。
     */
    private void emit(RecoverableNode node, EventType type, String detail) {
        seq++;
        ExecutionEvent event = new ExecutionEvent(seq, clock.instant(), type,
                node.id(), node.key(), detail);
        try {
            listener.onEvent(event);
        } catch (RuntimeException ex) {
            errorf("[WARN] 事件监听器异常（已忽略，不影响执行）: %s | %s%n",
                    event, ex.toString());
        }
    }

    public long eventCount() {
        return seq;
    }
}
