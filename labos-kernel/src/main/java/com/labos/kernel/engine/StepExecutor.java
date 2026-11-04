package com.labos.kernel.engine;

/**
 * 执行器抽象：真实驱动与虚拟驱动共用同一接口。
 *
 * <p>这是「仿真不是另一个模式，而是换一个实现」的落点：
 * 真实实现走 gRPC 到设备，虚拟实现直接返回预设结果。
 * 状态机对此无感知。
 */
@FunctionalInterface
public interface StepExecutor {

    /**
     * 执行一步。
     *
     * @throws StepFailure 失败时抛出，必须带 errorCode
     */
    void execute(StepContext ctx) throws StepFailure;
}
