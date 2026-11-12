package com.labos.kernel.engine;

import java.util.List;

/**
 * 一次节点执行的上下文。
 *
 * <p>{@code capabilityCode} 表达"要什么能力"，{@code resourceId} 是实际分到哪台设备，
 * {@code method} 是驱动方法名（{@code protocol_name}）。
 *
 * <p>{@code steps} 是编译后的子步骤序列：
 * <ul>
 *   <li>空列表或单元素 → 直接执行一步（99% 的流程是这种）</li>
 *   <li>多元素 → 按顺序执行，任一步失败则整个节点失败</li>
 * </ul>
 */
public record StepContext(
        long nodeId,
        String nodeKey,
        String capabilityCode,
        String method,
        long resourceId,
        int attempt,
        List<String> steps,
        /** 设备方法参数（来自 workflow_node.params.method_params）。 */
        java.util.Map<String, Object> params,
        /** 循环圈次（GraphRunner 的 visit；无环/顺序路径恒 0）。命令账本幂等键据此区分圈次。 */
        int loopIndex) {

    /** 兼容原有 8 参构造（loopIndex 默认 0）。 */
    public StepContext(long nodeId, String nodeKey, String capabilityCode, String method,
                       long resourceId, int attempt, List<String> steps,
                       java.util.Map<String, Object> params) {
        this(nodeId, nodeKey, capabilityCode, method, resourceId, attempt, steps, params, 0);
    }

    /** 兼容无参构造。 */
    public StepContext(long nodeId, String nodeKey, String capabilityCode, String method,
                       long resourceId, int attempt, List<String> steps) {
        this(nodeId, nodeKey, capabilityCode, method, resourceId, attempt, steps,
                java.util.Map.of(), 0);
    }

    /** 生成携带圈次的副本（GraphRunner 在派发前包一层，不动其它字段）。 */
    public StepContext withLoopIndex(int visit) {
        return new StepContext(nodeId, nodeKey, capabilityCode, method, resourceId,
                attempt, steps, params, visit);
    }

    public StepContext {
        steps = steps == null ? List.of() : List.copyOf(steps);
        params = params == null ? java.util.Map.of() : java.util.Map.copyOf(params);
    }

    /** 兼容调用：一步。 */
    public StepContext(long nodeId, String nodeKey, String capabilityCode,
                       String method, long resourceId, int attempt) {
        this(nodeId, nodeKey, capabilityCode, method, resourceId, attempt, List.of());
    }

    /** 实际要调用的方法名：method 优先，缺失时退回 capabilityCode。 */
    public String effectiveMethod() {
        return method == null || method.isBlank() ? capabilityCode : method;
    }

    /** 最终要执行的步骤序列：没编译就退化为单步。 */
    public List<String> effectiveSteps() {
        return steps.isEmpty() ? List.of(effectiveMethod()) : steps;
    }
}
