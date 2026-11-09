package com.labos.kernel.alert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 用真实历史错误回放告警聚合效果。
 *
 * <p>输入：{@code error_timeline.csv}（从 error_log 导出的存量数据，含时间/错误码/设备）
 * <p>输出：聚合后的告警统计，与原始错误量对比
 *
 * <pre>
 *   java -cp out com.labos.kernel.alert.AlertReplay &lt;csv路径&gt; [静默期分钟]
 * </pre>
 */
public final class AlertReplay {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AlertReplay.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) throws IOException {
        String csvPath = args.length > 0 ? args[0]
                : "../pg_rebuild/etl/out/error_timeline.csv";
        int gapMinutes = args.length > 1 ? Integer.parseInt(args[1]) : 15;

        List<AlertSignal> signals = load(Path.of(csvPath));
        if (signals.isEmpty()) {
            log.info("没有可回放的信号: " + csvPath);
            return;
        }

        Duration gap = Duration.ofMinutes(gapMinutes);
        AlertAggregator agg = new AlertAggregator(gap);

        List<Alert> emitted = new ArrayList<>();
        for (AlertSignal s : signals) {
            emitted.addAll(agg.offer(s));
        }
        // 收尾：结算所有还开着的窗口
        Instant end = signals.get(signals.size() - 1).at().plus(gap).plusSeconds(1);
        emitted.addAll(agg.flushExpired(end));

        print(signals, emitted, gapMinutes);
    }

    private static void print(List<AlertSignal> signals, List<Alert> alerts, int gapMinutes) {
        long opened = alerts.stream().filter(a -> a.kind() == AlertKind.OPENED).count();
        long escalated = alerts.stream().filter(a -> a.kind() == AlertKind.ESCALATED).count();
        long summary = alerts.stream().filter(a -> a.kind() == AlertKind.SUMMARY).count();

        Instant first = signals.get(0).at();
        Instant last = signals.get(signals.size() - 1).at();
        long days = Math.max(1, Duration.between(first, last).toDays());

        log.info("=".repeat(84));
        log.info("  历史错误回放：告警聚合效果");
        log.info("=".repeat(84));
        printf("  数据范围: %s ~ %s（约 %d 天）%n", first, last, days);
        printf("  静默期  : %d 分钟%n%n", gapMinutes);

        long push = opened + escalated;

        printf("  输入错误条数        : %8d%n", signals.size());
        printf("  ├ 推送给人 (OPENED+ESCALATED): %8d%n", push);
        printf("  │   ├ OPENED 首报          : %8d%n", opened);
        printf("  │   └ ESCALATED 阈值升级   : %8d%n", escalated);
        printf("  └ 仅归档 (SUMMARY)         : %8d%n", summary);
        log.info("");

        double reduction = (1 - push / (double) signals.size()) * 100;
        printf("  ★ 推送降噪率: %.2f%%（%d 条错误 -> %d 条推送）%n", reduction,
                signals.size(), push);
        printf("  ★ 含归档总量: %d 条对象（%.2f%% 压缩）%n%n",
                alerts.size(), (1 - alerts.size() / (double) signals.size()) * 100);

        // 需要人工关注的（非 SUMMARY）
        List<Alert> notify = alerts.stream()
                .filter(Alert::shouldNotify)
                .sorted(Comparator.comparingInt(Alert::count).reversed())
                .toList();

        printf("  需要推送给人处理的告警 %d 条。按影响排序 Top 10：%n", notify.size());
        printf("    %-12s %-30s %8s  %s%n", "类型", "指纹(错误码|设备)", "次数", "窗口");
        log.info("    " + "-".repeat(78));
        for (int i = 0; i < Math.min(10, notify.size()); i++) {
            Alert a = notify.get(i);
            printf("    %-12s %-30s %8d  %s ~ %s%n",
                    a.kind(), a.fingerprint(), a.count(),
                    a.firstAt(), a.lastAt());
        }
        log.info("");
        log.info("  说明：SUMMARY 只归档不推送，用于事后统计。");
    }

    private static List<AlertSignal> load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<AlertSignal> out = new ArrayList<>(lines.size());
        for (int i = 1; i < lines.size(); i++) {          // 跳过表头
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split(",", -1);
            if (p.length < 3) {
                continue;
            }
            String at = p[0].trim();
            String code = p[1].trim();
            String res = p[2].trim();
            if (at.isEmpty() || code.isEmpty()) {
                continue;
            }
            Instant ts;
            try {
                ts = LocalDateTime.parse(at, FMT).atZone(ZONE).toInstant();
            } catch (RuntimeException e) {
                continue;
            }
            Long resourceId = res.isEmpty() ? null : Long.parseLong(res);
            out.add(new AlertSignal(code, resourceId, "warn", "", ts));
        }
        out.sort(Comparator.comparing(AlertSignal::at));
        return out;
    }

    private AlertReplay() {
    }
}
