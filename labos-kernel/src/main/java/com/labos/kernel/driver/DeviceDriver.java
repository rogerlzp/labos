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
     * 请求设备中止正在执行的指令，并返回**这一次请求到底走到了哪一步**（B-04）。
     *
     * <p>这是**请求**，不是承诺：只有设备协议本身有停止指令、驱动等到设备侧
     * 停止确认、且该型号/固件/方法已通过 HIL 验证时，{@link #supportsPhysicalAbort()}
     * 才允许返回 {@code true}。仅取消本地 Future / 关闭 Socket / 中断线程
     * **不构成**物理中止——设备可能还在动。
     *
     * <p>返回值必须诚实：没有能力就 {@link AbortResult#unsupported}，
     * 发出去了没确认就 {@code REQUESTED/DEVICE_ACKNOWLEDGED/UNKNOWN}，
     * **只有设备确认动作已停**才 {@link AbortResult#stopped}。
     * 安全默认是 {@code UNSUPPORTED}——没实现的驱动不该被当成"能停"。
     */
    default AbortResult abort(String commandId) {
        return AbortResult.unsupported("该驱动未实现中止，不能假定设备已停");
    }

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

    /**
     * 查询一条已下发命令在设备侧的真实状态（R4 超时先查询）。
     *
     * <p>默认不支持：实现必须返回 {@link CommandQueryResult.State#UNKNOWN}，
     * 不能因为本地没有回包就猜成 NOT_FOUND、COMPLETED 或 FAILED。
     * 只有设备协议确实能按 commandId 查询时，驱动才应覆盖本方法。
     */
    default CommandQueryResult queryStatus(String commandId) {
        return CommandQueryResult.unknown("该驱动未实现设备侧命令状态查询");
    }

    /** 驱动自身是否健康（进程活着、连接正常）。 */
    boolean healthy();

    @Override
    default void close() {
        // 默认无资源需释放
    }
}
