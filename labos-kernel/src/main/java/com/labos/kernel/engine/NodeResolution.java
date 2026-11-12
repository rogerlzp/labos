package com.labos.kernel.engine;

/**
 * 节点执行结果边界的上下文（§3.1 第 1 项），传给完成处理器。
 *
 * <p>第一版最小上下文：nodeId / nodeKey / 执行确认四态 / 错误码。
 * runId / runNodeId / loopIndex / 效果序号 / 命令证据由调用方在接入
 * 完成处理器时通过闭包补充（主链路在 WorkflowService 接入）。
 */
public record NodeResolution(
        long nodeId,
        String nodeKey,
        ExecutionResolution resolution,
        String errorCode) {

    public static NodeResolution of(long nodeId, String nodeKey,
                                    ExecutionResolution resolution, String errorCode) {
        return new NodeResolution(nodeId, nodeKey, resolution, errorCode);
    }
}
