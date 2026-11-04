package com.labos.kernel.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警聚合器：把"一台设备反复报同一个错"压成有限的几条告警。
 *
 * <p>背景：设备离线时一台会一天刷出 11,856 条 ERROR 日志，运维完全无法从中发现问题。
 *
 * <h2>为什么用「静默期」而不是「固定时间窗」</h2>
 * 固定窗口（如每 15 分钟切一刀）在**持续故障**场景下会每 15 分钟吐一条摘要——
 * 一台离线设备 24 小时会产生 96 条摘要，等于没聚合。
 *
 * <p>静默期聚合的规则是：<b>只要还在持续发生就一直累积，停止发生超过
 * {@code silenceGap} 后才结算一次摘要</b>。于是：
 * <ul>
 *   <li>持续离线 24 小时 → 1 条 OPENED + 阈值升级 + 1 条 SUMMARY</li>
 *   <li>间歇性抖动（间隔 &gt; 静默期）→ 每次抖动静默后各结算一条</li>
 * </ul>
 *
 * <p><b>时间来源</b>：全部用信号自带的 {@code at}，可对历史数据完整回放。
 *
 * <p><b>线程安全</b>：非线程安全，约定单线程驱动。
 */
public final class AlertAggregator {

    /** 默认升级阈值：10 次、100 次、1000 次。 */
    public static final int[] DEFAULT_THRESHOLDS = {10, 100, 1000};

    private final Duration silenceGap;
    private final int[] thresholds;
    private final Map<String, Window> windows = new LinkedHashMap<>();

    public AlertAggregator(Duration silenceGap) {
        this(silenceGap, DEFAULT_THRESHOLDS);
    }

    public AlertAggregator(Duration silenceGap, int[] thresholds) {
        if (silenceGap == null || silenceGap.isZero() || silenceGap.isNegative()) {
            throw new IllegalArgumentException("silenceGap 必须为正");
        }
        this.silenceGap = silenceGap;
        this.thresholds = thresholds == null ? DEFAULT_THRESHOLDS.clone() : thresholds.clone();
        Arrays.sort(this.thresholds);
    }

    /**
     * 投递一条信号。
     *
     * @return 需要发出的告警（0~2 条：上一窗口摘要 + 新窗口首报）
     */
    public List<Alert> offer(AlertSignal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal 不能为空");
        }
        List<Alert> out = new ArrayList<>(2);
        String key = signal.fingerprint();
        Window w = windows.get(key);

        // 首次出现
        if (w == null) {
            Window fresh = new Window(signal);
            windows.put(key, fresh);
            out.add(fresh.toOpened());
            return out;
        }

        // 静默期已过 -> 结算上一窗口，开新窗口
        if (signal.at().isAfter(w.last.plus(silenceGap))) {
            out.add(w.toSummary());
            Window fresh = new Window(signal);
            windows.put(key, fresh);
            out.add(fresh.toOpened());
            return out;
        }

        // 持续发生 -> 累积
        w.count++;
        w.last = signal.at();
        // P1-17：同窗口内出现更高级别的信号，把整段抬上去（只升不降）
        w.raise(signal.severity());
        if (signal.message() != null && !signal.message().isBlank()) {
            w.lastMessage = signal.message();
        }
        // 恰好跨过阈值时升级一次
        for (int t : thresholds) {
            if (w.count == t) {
                out.add(w.toEscalated());
                break;
            }
        }
        return out;
    }

    /**
     * 结算所有静默超过阈值的窗口。定时任务调用（如每分钟一次）。
     */
    public List<Alert> flushExpired(Instant now) {
        List<Alert> out = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Window> e : windows.entrySet()) {
            Window w = e.getValue();
            if (now.isAfter(w.last.plus(silenceGap))) {
                out.add(w.toSummary());
                expired.add(e.getKey());
            }
        }
        expired.forEach(windows::remove);
        return out;
    }

    public int activeWindowCount() {
        return windows.size();
    }

    public void reset() {
        windows.clear();
    }

    // ---------------------------------------------------------------- 内部窗口

    private static final class Window {
        final Instant start;
        final String fingerprint;
        final String errorCode;
        final Long resourceId;
        /**
         * 窗口内**最高**的严重级别。
         *
         * <p><b>P1-17 修复（评审 20261102-03）</b>：改动前是 {@code final}，
         * 由窗口第一条信号定死。同一 fingerprint 先来一条 warn、
         * 后来真正的 error，告警级别仍是 warn —— <b>高危信号被降级淹没</b>。
         * 现在改为取窗口内最高级。
         */
        String severity;
        Instant last;
        int count = 1;
        String lastMessage;

        Window(AlertSignal s) {
            this.start = s.at();
            this.last = s.at();
            this.fingerprint = s.fingerprint();
            this.errorCode = s.errorCode();
            this.resourceId = s.resourceId();
            this.severity = s.severity();
            this.lastMessage = s.message();
        }

        /**
         * 收一条同级或更高级的信号时，把窗口级别抬到更高那个。
         *
         * <p>级别序：{@code info < warn < error < fatal}（未知一律按 warn）。
         * 只升不降 —— 窗口内出现过 error，整段就按 error 报。
         */
        void raise(String incoming) {
            if (rank(incoming) > rank(this.severity)) {
                this.severity = incoming;
            }
        }

        private static int rank(String sev) {
            if (sev == null) {
                return 1;
            }
            return switch (sev.toLowerCase()) {
                case "info" -> 0;
                case "warn", "warning" -> 1;
                case "error" -> 2;
                case "fatal", "critical" -> 3;
                default -> 1;
            };
        }

        Alert toOpened() {
            return build(AlertKind.OPENED);
        }

        Alert toEscalated() {
            return build(AlertKind.ESCALATED);
        }

        Alert toSummary() {
            return build(AlertKind.SUMMARY);
        }

        private Alert build(AlertKind kind) {
            return new Alert(kind, fingerprint, errorCode, resourceId, severity,
                    count, start, last, lastMessage);
        }
    }
}
