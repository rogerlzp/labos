package com.labos.kernel.recovery;

/**
 * 恢复策略的「意图」——策略配置里写的是什么。
 *
 * <p>与 {@link RecoveryAction} 的区别：Strategy 是人工配置的意图（比如「重试」），
 * Action 是决策引擎根据当前上下文算出的实际动作（比如「重试次数已耗尽，转人工」）。
 */
public enum RecoveryStrategy {

    /** 原地重试，按退避策略等待。适用于瞬时故障（通讯抖动、临时占用）。 */
    RETRY,

    /** 跳过当前节点，继续后续。仅当该节点非关键路径时安全。 */
    SKIP,

    /** 换一台具备相同能力的设备重做（含主备切换）。需有可用替代资源。 */
    REASSIGN,

    /** 终止当前执行。 */
    ABORT,

    /** 策略层直接指定「不自动处理，交人工」。 */
    MANUAL,

    /** 补偿：回滚已执行的副作用。当前引擎尚未实现，会降级为 MANUAL。 */
    COMPENSATE
}
