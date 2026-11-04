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

    /** 中止正在执行的指令（尽力而为）。 */
    void abort(String commandId);

    /** 驱动自身是否健康（进程活着、连接正常）。 */
    boolean healthy();

    @Override
    default void close() {
        // 默认无资源需释放
    }
}
