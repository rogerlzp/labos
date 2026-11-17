package com.labos.kernel.device;

import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实例号（{@code device_instance.id}）→ 型号行号（{@code device.id}）的**唯一换算入口**。
 *
 * <p><b>为什么必须有唯一入口</b>（2026-11-16 事故）：
 * {@code DriverStepExecutor.resolveTimeoutMs} 直接把实例号传给
 * {@code device_method.device_id}（那列存的是 {@code device.id}）。
 * 三套 id 都是 bigint ⇒ SQL 不报错，只是查错行：
 * <ol>
 *   <li>不撞号 ⇒ 0 行 ⇒ {@code COALESCE(MAX(..),0)} 吞成 0 ⇒ 静默退回能力默认（600s），
 *       现场标定的真实耗时<b>永远调不出来</b>；</li>
 *   <li>撞号 ⇒ 白捡另一个型号的上限。DISP3（实例 {@code 1000039}）撞上
 *       {@code GANTRY_ROBOT_GENERIC}（型号行 {@code 1000039}）⇒ 分液被套 7 秒 ⇒
 *       {@code EC-05-1007} ⇒ 重试 3 次 ⇒ 转人工。</li>
 * </ol>
 *
 * <p>本类把换算收成一处：<b>其它任何地方都不许自己写
 * {@code SELECT device_model_id FROM device_instance}</b>。
 * 好处不只是少写一行 SQL —— 解析失败（型号未登记 / 实例不存在 / 软删 / 读库失败）
 * 在这里<b>统一计数 + 告警</b>，不再被 {@code COALESCE} 吞掉。
 *
 * <p><b>缓存</b>：实例→型号在运行期不会变（换型号等于换设备），所以按实例号缓存。
 * 设备台账改型号后调用 {@link #invalidate(long)}。
 */
public final class DeviceIdentityResolver {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeviceIdentityResolver.class);

    /** 累计换算次数（跨实例，进程级）—— 给诊断/自检用。 */
    private static final AtomicLong LOOKUPS = new AtomicLong();
    /** 累计解析失败次数（不知道型号 ⇒ 只能退回能力默认）。 */
    private static final AtomicLong UNRESOLVED = new AtomicLong();
    /** 累计读库异常次数（与"型号未登记"区分：一个是数据问题，一个是环境问题）。 */
    private static final AtomicLong ERRORS = new AtomicLong();

    private final DeviceStateMapper mapper;
    private final long tenantId;
    private final Map<Long, DeviceIds.ModelId> cache = new ConcurrentHashMap<>();
    /** 已经告警过的实例号：解析失败是**长期状态**，每次执行都刷一行日志没人看。 */
    private final Set<Long> warned = ConcurrentHashMap.newKeySet();

    public DeviceIdentityResolver(DeviceStateMapper mapper, long tenantId) {
        this.mapper = mapper;
        this.tenantId = tenantId;
    }

    /** 生产用法：拿全局 Mapper。 */
    public DeviceIdentityResolver(long tenantId) {
        this(Mappers.get(DeviceStateMapper.class), tenantId);
    }

    /**
     * 解析型号。返回 {@link DeviceIds#UNKNOWN_MODEL}（value=0）表示解析不到 ——
     * 调用方据此**显式降级**（退回能力默认），而不是当成"超时 0 秒"。
     */
    public DeviceIds.ModelId modelIdOf(DeviceIds.InstanceId instance) {
        if (instance == null || !instance.known()) {
            return DeviceIds.UNKNOWN_MODEL;
        }
        DeviceIds.ModelId cached = cache.get(instance.value());
        if (cached != null) {
            return cached;
        }
        LOOKUPS.incrementAndGet();
        DeviceIds.ModelId resolved = DeviceIds.UNKNOWN_MODEL;
        try {
            Long v = mapper.selectModelIdOfInstance(tenantId, instance.value());
            if (v != null && v != 0L) {
                resolved = DeviceIds.ofModel(v);
            }
        } catch (RuntimeException e) {
            ERRORS.incrementAndGet();
            log.warn("[设备ID] {} 换算型号失败（读库异常），本次按能力默认走：{}",
                    instance, e.getMessage());
        }
        cache.put(instance.value(), resolved);
        if (!resolved.known()) {
            UNRESOLVED.incrementAndGet();
            warnOnce(instance.value());
        }
        return resolved;
    }

    /** 便捷重载：直接吃 long（调用层已经显式标注过语义时用）。 */
    public DeviceIds.ModelId modelIdOf(long instanceId) {
        return modelIdOf(DeviceIds.ofInstance(instanceId));
    }

    /** 型号行号（0 = 未解析）。给只认 long 的 Mapper 调用点用。 */
    public long modelIdValueOf(long instanceId) {
        return modelIdOf(instanceId).value();
    }

    /** 设备台账改了型号，或自检改了数据：清掉该实例的缓存。 */
    public void invalidate(long instanceId) {
        cache.remove(instanceId);
        warned.remove(instanceId);
    }

    public void invalidateAll() {
        cache.clear();
        warned.clear();
    }

    // ---------------------------------------------------------------- 诊断

    /** 累计换算次数。 */
    public static long lookups() {
        return LOOKUPS.get();
    }

    /** 累计解析失败次数（型号未登记 / 实例不存在）。 */
    public static long unresolved() {
        return UNRESOLVED.get();
    }

    /** 累计读库异常次数。 */
    public static long errors() {
        return ERRORS.get();
    }

    /** 自检用：把累计计数清零。 */
    public static void resetStats() {
        LOOKUPS.set(0L);
        UNRESOLVED.set(0L);
        ERRORS.set(0L);
    }

    /**
     * 解析失败时打一次 WARN，带上**为什么**。
     *
     * <p>为什么必须告警而不是静默 0：这次事故的两个症状（"优先级链整条是死的"、
     * "被套上别人的 7 秒"）都是因为 0 是合法值、没人看得见。
     * 告警文案刻意点名三种成因，现场照着查就能定位。
     */
    private void warnOnce(long instanceId) {
        if (!warned.add(instanceId)) {
            return;
        }
        log.warn("[设备ID] 实例 {} 解析不到型号行（device_instance.device_model_id 为空 / "
                        + "实例不存在 / 已软删）⇒ 设备方法级与型号级超时全部跳过，"
                        + "只能退回能力默认。查：SELECT id, code, device_model_id, resource_id "
                        + "FROM device_instance WHERE tenant_id = {} AND id = {}",
                instanceId, tenantId, instanceId);
    }
}
