package com.labos.kernel.engine;

/**
 * 执行事件类型——完整的事件流是审计、回放与仿真的基础。
 *
 * <p>可观测性的最大缺陷：11 万条执行记录里 99.7% 没有设备回包原文，
 * 出了问题无法复盘。新引擎把每一步都变成结构化事件。
 */
public enum EventType {

    /** 节点开始执行。 */
    NODE_STARTED,

    /** 单步执行失败（含错误码）。 */
    STEP_FAILED,

    /** 恢复引擎给出决策。 */
    RECOVERY_DECIDED,

    /** 因决策而切换到另一台设备。 */
    RESOURCE_SWITCHED,

    /** 节点执行成功。 */
    NODE_COMPLETED,

    /** 节点被跳过。 */
    NODE_SKIPPED,

    /** 节点转人工。 */
    NODE_MANUAL,

    /** 整个执行被终止。 */
    RUN_ABORTED
}
