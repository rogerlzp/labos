package com.labos.kernel.driver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 超时解析的**诊断计数**（2026-11-16）。
 *
 * <p><b>为什么需要它</b>：2026-11-16 的事故里，超时优先级链有两条静默失败路径：
 * <ol>
 *   <li><b>查表落空</b>：实例号被当成型号行号去查 {@code device_method} ⇒ 0 行 ⇒
 *       {@code COALESCE(MAX(..),0)} 吞成 0 ⇒ 永远退回能力默认（600s）。
 *       现场标定的真实耗时<b>调不出来</b>，而且日志上看不出任何异常。</li>
 *   <li><b>撞号</b>：实例号恰好等于另一个型号的行号 ⇒ 白捡别人的上限
 *       （DISP3 分液被套上机械臂 7 秒 ⇒ {@code EC-05-1007}）。</li>
 * </ol>
 *
 * <p>两条都因为「0 是合法值」而无人发现。这里把每次解析的**来源**与**落空次数**
 * 计数起来：{@link #snapshot()} 可以直接从管理端点/自检里读，
 * 「设备方法级命中数一直是 0」这件事就不再隐形。
 *
 * <p>计数是**进程级**的（一次部署内累计），自检用 {@link #reset()} 清零。
 */
public final class TimeoutDiagnostics {

    /** 按来源（{@code MethodTimeoutPolicy.SRC_*}）计命中次数。 */
    private static final Map<String, AtomicLong> BY_SOURCE = new ConcurrentHashMap<>();

    /** 型号解析成功的次数（device_instance → device）。 */
    private static final AtomicLong MODEL_RESOLVED = new AtomicLong();

    /** 型号解析失败的次数 —— 这些执行必然落到能力默认。 */
    private static final AtomicLong MODEL_UNRESOLVED = new AtomicLong();

    /**
     * 「设备方法级 + 型号级都为 0」的次数。
     *
     * <p>这是"优先级链整条是死的"的直接指标：只要它持续增长，就说明
     * {@code device_method} 里没有该型号该方法的超时配置（或配置挂错了型号）。
     */
    private static final AtomicLong DEVICE_LEVEL_MISSING = new AtomicLong();

    /** 已经告警过的 key（{@code instance|method}）：落空是长期状态，只该吵一次。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private TimeoutDiagnostics() {
    }

    /** 记一次来源命中。 */
    static void recordSource(String source) {
        String key = source == null ? "unknown" : source;
        BY_SOURCE.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    /** 记一次型号解析结果。 */
    static void recordModel(boolean resolved) {
        if (resolved) {
            MODEL_RESOLVED.incrementAndGet();
        } else {
            MODEL_UNRESOLVED.incrementAndGet();
        }
    }

    /** 记一次「设备方法级与型号级都没有配置」。 */
    static void recordDeviceLevelMissing() {
        DEVICE_LEVEL_MISSING.incrementAndGet();
    }

    /** 第一次见到这个 key 时返回 true（调用方据此只打一条 WARN）。 */
    static boolean warnOnce(String key) {
        return WARNED.add(key);
    }

    // ---------------------------------------------------------------- 读侧

    public static long modelResolved() {
        return MODEL_RESOLVED.get();
    }

    public static long modelUnresolved() {
        return MODEL_UNRESOLVED.get();
    }

    public static long deviceLevelMissing() {
        return DEVICE_LEVEL_MISSING.get();
    }

    /**
     * 诊断快照：{@code bySource} / {@code modelResolved} / {@code modelUnresolved}
     * / {@code deviceLevelMissing}。
     *
     * <p>判读方式：
     * <ul>
     *   <li>{@code bySource.device_method} 与 {@code bySource.device_instance_method}
     *       长期都是 0，而 {@code deviceLevelMissing > 0}
     *       ⇒ 现场超时配置没生效（查型号是否挂对、该型号有没有登记 timeout_sec）；</li>
     *   <li>{@code bySource.capability_default} 占绝大多数
     *       ⇒ 设备库里根本没登记超时，全是能力默认兜着；</li>
     *   <li>{@code bySource.device_instance_method} 有值
     *       ⇒ 有设备在用实例级覆盖（2026-11-17 起支持），此时型号级改了不影响它；</li>
     *   <li>{@code modelUnresolved > 0}
     *       ⇒ {@code device_instance.device_model_id} 为空（型号登记不全）。</li>
     * </ul>
     */
    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> bySource = new LinkedHashMap<>();
        BY_SOURCE.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> bySource.put(e.getKey(), e.getValue().get()));
        out.put("bySource", bySource);
        out.put("modelResolved", MODEL_RESOLVED.get());
        out.put("modelUnresolved", MODEL_UNRESOLVED.get());
        out.put("deviceLevelMissing", DEVICE_LEVEL_MISSING.get());
        return out;
    }

    /** 自检用：清空全部计数与告警去重表。 */
    public static void reset() {
        BY_SOURCE.clear();
        MODEL_RESOLVED.set(0L);
        MODEL_UNRESOLVED.set(0L);
        DEVICE_LEVEL_MISSING.set(0L);
        WARNED.clear();
    }
}
