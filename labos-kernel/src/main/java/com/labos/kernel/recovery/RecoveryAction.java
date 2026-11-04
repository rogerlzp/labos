package com.labos.kernel.recovery;

/**
 * 决策引擎输出的实际动作。
 *
 * <p>比 {@link RecoveryStrategy} 多了 {@link #MANUAL}：当策略缺失、
 * 重试耗尽、或没有可用替代资源时，引擎**不猜**，直接交给人。
 * 这是与老系统最大的区别——老系统会一直机械重试并刷日志，
 * 新引擎在无法安全自愈时立刻升级，并在 reason 里说明原因。
 */
public enum RecoveryAction {

    /** 原地重试，需按 delayMillis 等待。 */
    RETRY,

    /** 换设备重做，targetResourceId 指明目标。 */
    REASSIGN,

    /** 跳过该节点。 */
    SKIP,

    /** 终止执行。 */
    ABORT,

    /** 交人工处理。reason 必填，用于告警与审计。 */
    MANUAL
}
