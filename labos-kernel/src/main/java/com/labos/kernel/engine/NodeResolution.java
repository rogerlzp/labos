package com.labos.kernel.engine;

/**
 * 节点执行结果边界的上下文（§3.1 第 1 项），传给完成处理器。
 *
 * <p>第一版最小上下文：nodeId / nodeKey / 执行确认四态 / 错误码 / 圈次。
 * runId / runNodeId / 效果序号 / 命令证据由调用方在接入
 * 完成处理器时通过闭包补充（主链路在 WorkflowService 接入）。
 *
 * <p>{@code loopIndex} 由 GraphRunner 的 visit 注入（无环图与顺序路径恒 0）。
 * 它只用于 **Effect 的 {@code source_loop_index}**（R13-06）：执行确认仍写
 * {@code loop_index=0} 的汇总行，因为圈次行在 executeNode 之后才创建，
 * 提前写圈级确认会落到不存在的行上。
 */
public record NodeResolution(
        long nodeId,
        String nodeKey,
        ExecutionResolution resolution,
        String errorCode,
        int loopIndex) {

    /** 兼容 4 参调用点（非循环 / 旧调用点）：loopIndex 默认 0。 */
    public static NodeResolution of(long nodeId, String nodeKey,
                                    ExecutionResolution resolution, String errorCode) {
        return new NodeResolution(nodeId, nodeKey, resolution, errorCode, 0);
    }

    public static NodeResolution of(long nodeId, String nodeKey,
                                    ExecutionResolution resolution, String errorCode,
                                    int loopIndex) {
        return new NodeResolution(nodeId, nodeKey, resolution, errorCode, loopIndex);
    }
}
