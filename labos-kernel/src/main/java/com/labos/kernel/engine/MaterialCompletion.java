package com.labos.kernel.engine;

/**
 * 完成处理器注入点（§3.1）：设备 TASK 节点的执行结果边界统一回调。
 *
 * <p>由 boot 层 {@code MaterialEffectService} 实现；引擎（RunStateMachine）只依赖
 * 此接口，不反向依赖物料实现。返回 {@link MaterialOutcome#HOLD} 时，调用方
 * 应让 run 进 {@code paused(material_sync)}（不伪造设备失败）。
 */
@FunctionalInterface
public interface MaterialCompletion {

    /** 默认空实现：任何节点都 CONTINUE（未接入完成处理器的执行路径）。 */
    MaterialCompletion NOOP = r -> MaterialOutcome.CONTINUE;

    MaterialOutcome onNodeComplete(NodeResolution resolution);
}
