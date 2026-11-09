package com.labos.kernel.driver;

/**
 * 设备驱动契约。
 *
 * <p>四种实现形态共用这一个接口：
 * <ul>
 *   <li><b>虚拟驱动</b>：内存模拟，用于仿真与回归</li>
 *   <li><b>gRPC 驱动</b>：跨进程，对应 {@code proto/driver.proto}</li>
 *   <li><b>.NET Sidecar</b>：把现有 DLL 包一层 gRPC 适配</li>
 *   <li><b>测试桩</b>：单元测试注入故障</li>
 * </ul>
 *
 * <p>「仿真不是另一个运行模式，而是换一个实现」——就在这里落地。
 */
public interface DeviceDriver extends AutoCloseable {

    /** 自描述：能力、设备、协议、端点。 */
    DriverDescriptor describe();

    /**
     * 执行一条指令。
     *
     * <p>实现必须保证：<b>同一 {@code commandId} 重复调用是幂等的</b>。
     */
    DriverResult execute(DriverCommand command);

    /**
     * 请求设备中止正在执行的指令。
     *
     * <p>这是**请求**，不是承诺：只有设备协议本身有停止指令、驱动等到设备侧
     * 停止确认、且该型号/固件/方法已通过 HIL 验证时，{@link #supportsPhysicalAbort()}
     * 才允许返回 {@code true}。仅取消本地 Future / 关闭 Socket / 中断线程
     * **不构成**物理中止——设备可能还在动。
     */
    void abort(String commandId);

    /**
     * 本驱动能否保证 {@link #abort(String)} 返回前设备动作已被设备侧确认停止。
     *
     * <p>安全默认 {@code false}。没有经过具体设备型号、固件、方法级 HIL 验证的
     * 驱动必须保持 {@code false}：此时上层**不得**用「取消本地等待」冒充停止，
     * 只能等当前动作自然跑到安全边界，再在节点/任务边界暂停。
     */
    default boolean supportsPhysicalAbort() {
        return false;
    }

    /** 驱动自身是否健康（进程活着、连接正常）。 */
    boolean healthy();

    @Override
    default void close() {
        // 默认无资源需释放
    }
}
