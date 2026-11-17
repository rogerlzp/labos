package com.labos.kernel.device;

/**
 * 设备的**三套 id** 的强类型包装（2026-11-16）。
 *
 * <p><b>为什么需要它</b>：同一台设备在库里有三个 bigint 主键，语义完全不同 ——
 * <table border="1">
 *   <caption>三套 id</caption>
 *   <tr><th>id 空间</th><th>表/字段</th><th>谁在用</th></tr>
 *   <tr><td>{@link InstanceId}</td><td>{@code device_instance.id}</td>
 *       <td>运行期 {@code StepContext.resourceId()}、设备锁、心跳、命令账本</td></tr>
 *   <tr><td>{@link ResourceId}</td><td>{@code resource_node.id}</td>
 *       <td>拓扑树、点位、能力绑定、位置锁</td></tr>
 *   <tr><td>{@link ModelId}</td><td>{@code device.id}</td>
 *       <td>设备方法表 {@code device_method}、错误码、型号属性</td></tr>
 * </table>
 *
 * <p>三者都是 {@code bigint} ⇒ 传错**编译能过、SQL 也不报错，只是查错行**。
 * 实测（BUG 台账 2026-11-16）：提取线 DISP3 的实例号 {@code 1000039} 恰好落在
 * 旧演示岛遗留的型号行号区间上，于是它的分液被套上了「轨道机械臂 7 秒」的超时上限，
 * 8 秒的 Run 判 {@code EC-05-1007} ⇒ 重试 3 次 ⇒ 转人工。
 *
 * <p>用三个不可互换的 record 后，{@code modelIdOf(instanceId)} 这类混用
 * <b>编译期就过不去</b>，不用等运行时撞号。
 *
 * <p><b>用法约定</b>：
 * <ul>
 *   <li>查询/换算的<b>入口</b>一律用 {@link DeviceIdentityResolver}，
 *       不要在业务代码里手写 {@code SELECT device_model_id ...}；</li>
 *   <li>Mapper 层仍用 {@code long}（MyBatis 不认识 record），
 *       所以<b>强类型必须在调用层落地</b>：先用 record 拿到值，再 {@code .value()} 传下去。</li>
 * </ul>
 *
 * @see DeviceIdentityResolver 唯一的实例号 → 型号行号换算入口
 */
public final class DeviceIds {

    private DeviceIds() {
    }

    /**
     * {@code device_instance.id}（实例号）。
     *
     * <p>运行期 {@code StepContext.resourceId()} 传的就是它 —— 名字里叫 resource，
     * 实际是实例号，这是历史包袱（见 {@link #ofInstance} 的注释）。
     */
    public record InstanceId(long value) {

        /** 0 = 未知/不适用。自造测试数据可能用负数，所以判据是 {@code != 0}。 */
        public boolean known() {
            return value != 0L;
        }

        public static InstanceId of(long value) {
            return new InstanceId(value);
        }

        @Override
        public String toString() {
            return "instance#" + value;
        }
    }

    /** {@code resource_node.id}（资源号）。 */
    public record ResourceId(long value) {

        public boolean known() {
            return value != 0L;
        }

        public static ResourceId of(long value) {
            return new ResourceId(value);
        }

        @Override
        public String toString() {
            return "resource#" + value;
        }
    }

    /**
     * {@code device.id}（**型号行号**）。
     *
     * <p>注意 {@code device} 表的注释就是「设备型号定义」——它不是"某台设备"，
     * 而是"某一款设备"。{@code device_method}、错误码、型号属性都挂在这一行上。
     */
    public record ModelId(long value) {

        /** 0 = 型号未登记（{@code device_instance.device_model_id} 为空或实例不存在）。 */
        public boolean known() {
            return value != 0L;
        }

        public static ModelId of(long value) {
            return new ModelId(value);
        }

        @Override
        public String toString() {
            return "model#" + value;
        }
    }

    /** 未解析出来的型号（显式常量，避免到处写 {@code new ModelId(0)}）。 */
    public static final ModelId UNKNOWN_MODEL = new ModelId(0L);

    // ---- 语义别名：让"这其实是实例号"在代码里看得出来 ----

    /**
     * 把一个"长得像资源号、其实是实例号"的 long 标成 {@link InstanceId}。
     *
     * <p>存在原因：{@code StepContext} 的字段名是 {@code resourceId}，
     * 但 {@code DriverRegistry.driverFor(long deviceInstanceId)} 与
     * {@code candidates()} 返回的都是<b>实例号</b>（机械臂是 214，不是 200214）。
     * 直接改成 record 会牵动几百处调用，所以先在边界处显式转换，
     * 让"这里就是实例号"变成一个可读的事实。
     */
    public static InstanceId ofInstance(long maybeInstanceId) {
        return new InstanceId(maybeInstanceId);
    }

    /** {@code resource_node.id} 的显式标注。 */
    public static ResourceId ofResource(long resourceNodeId) {
        return new ResourceId(resourceNodeId);
    }

    /** {@code device.id} 的显式标注。 */
    public static ModelId ofModel(long deviceRowId) {
        return new ModelId(deviceRowId);
    }
}
