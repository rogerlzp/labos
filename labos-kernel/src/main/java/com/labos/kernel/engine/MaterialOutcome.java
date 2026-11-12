package com.labos.kernel.engine;

/**
 * 完成处理器（materialEffectHandler）的返回三态之一（§3.1）。
 *
 * <p>{@code CONTINUE} = 无效果或效果已 APPLIED，节点可完成；
 * {@code HOLD} = 效果 PENDING/FAILED/REJECTED/NEEDS_RECONCILIATION（物理完成、
 * 物料未闭环），节点保持物理完成终态，run 进 {@code paused(material_sync)}。
 * 第三个「抛持久化异常」不在此枚举（T1 写不进时直接抛，调用方按隔离处理）。
 */
public enum MaterialOutcome {
    CONTINUE,
    HOLD
}
