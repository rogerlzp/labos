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
     * <p>只有设备协议支持停止指令、驱动等待到设备侧停止确认，且该型号/固件/方法
     * 已通过 HIL 验证时，才允许 {@link #supportsPhysicalAbort()} 返回 true。
     * 仅取消本地 Future、Socket 等待或线程，不构成物理中止。
     */
    void abort(String commandId);

    /**
     * 当前驱动是否能保证 abort 返回前设备动作已被设备侧确认停止。
     *
     * <p>安全默认 false。没有经过具体设备型号、固件和方法级 HIL 验证的驱动
     * 必须保持 false；上层将等待当前动作自然完成，再在安全节点边界暂停。
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
