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

    private final RecoveryDecider decider;
    private final ExecutionListener listener;
    private final Sleeper sleeper;
    private final Clock clock;
    private long seq = 0;
    /** 节点 -> 子步骤序列；未设置时按单步执行（老流程 99% 是这种）。 */
    private java.util.function.Function<Long, java.util.List<String>> stepPlanner;
    /** 节点 -> 设备方法参数；未设置时为空（老流程没有参数）。 */
    private java.util.function.Function<Long, java.util.Map<String, Object>> paramPlanner;

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

    /** 批量执行：遇 MANUAL 或 ABORT 即停止后续节点。 */
    public RunProgress executeAll(List<RecoverableNode> nodes, StepExecutor executor) {
        RunProgress progress = new RunProgress();
        for (RecoverableNode node : nodes) {
            if (progress.aborted()) {
                break;
            }
            NodeStatus st = executeNode(node, progress, executor);
            if (st == NodeStatus.ABORTED || st == NodeStatus.MANUAL) {
                break;
            }
        }
        return progress;
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
            np.setLastReason("无可执行资源：节点未给出初始设备，且候选集为空");
            emit(node, EventType.NODE_MANUAL, np.lastReason());
            return NodeStatus.MANUAL;
        }
        np.setCurrentResource(resource);
        emit(node, EventType.NODE_STARTED,
                String.format("resource=%d capability=%s", resource, node.capabilityCode()));

        while (true) {
            try {
                executor.execute(new StepContext(node.id(), node.key(),
                        node.capabilityCode(), node.method(), resource, np.attempt(),
                        stepsOf(node), paramsOf(node)));
                np.setStatus(NodeStatus.COMPLETED);
                emit(node, EventType.NODE_COMPLETED, "attempt=" + np.attempt());
                return NodeStatus.COMPLETED;

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
                        return NodeStatus.SKIPPED;
                    }
                    case ABORT -> {
                        np.setStatus(NodeStatus.ABORTED);
                        progress.markAborted();
                        emit(node, EventType.RUN_ABORTED, d.reason());
                        return NodeStatus.ABORTED;
                    }
                    case MANUAL -> {
                        np.setStatus(NodeStatus.MANUAL);
                        emit(node, EventType.NODE_MANUAL, d.reason());
                        return NodeStatus.MANUAL;
                    }
                    default -> throw new IllegalStateException("未覆盖的决策动作: " + d.action());
                }
            }
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
            System.err.printf("[WARN] 事件监听器异常（已忽略，不影响执行）: %s | %s%n",
                    event, ex.toString());
        }
    }

    public long eventCount() {
        return seq;
    }
}
