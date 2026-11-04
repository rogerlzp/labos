package com.labos.kernel.engine;

/**
 * 节点执行状态。
 *
 * <p>{@link #MANUAL} 是新增的关键状态：状态机最初只有「成功/失败」，
 * 分不清「失败后自动恢复成功」与「需要人来处理」。这个区分决定了告警量和人机边界。
 */
public enum NodeStatus {

    /** 尚未开始。 */
    PENDING,

    /** 正在执行。 */
    RUNNING,

    /** 执行成功（可能经历多次重试或换机）。 */
    COMPLETED,

    /** 按策略跳过。 */
    SKIPPED,

    /** 无法自动恢复，转人工。 */
    MANUAL,

    /** 执行被终止。 */
    ABORTED,

    /**
     * **分支未选中**：异或网关选了另一条路，这一步根本不需要做。
     *
     * <p>为什么不复用 {@link #SKIPPED}：skipped 的语义是"这步该做但没做，板位不可信"，
     * 遇到它 {@code GraphRunner} 会**停下转人工**。分支没选中是正常流程，
     * 不该让人来处理，也不该污染"跳过"统计。
     */
    BYPASSED
}
