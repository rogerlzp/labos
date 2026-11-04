package com.labos.kernel.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警聚合自测（零依赖）。
 */
public final class AlertSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final Instant T0 = Instant.parse("2026-10-29T00:00:00Z");
    private static final Duration GAP = Duration.ofMinutes(15);

    public static void main(String[] args) {
        run("单条信号 -> 立即 OPENED", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            List<Alert> out = agg.offer(sig("EC-05-1006", 100L, T0));
            eq("1 条告警", 1, out.size());
            eq("类型", AlertKind.OPENED, out.get(0).kind());
            eq("计数", 1, out.get(0).count());
        });

        // P1-17：同一窗口内后出现更高级别，不能还按首条的 warn 报
        run("窗口内升级：warn → error，级别取最高", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            agg.offer(sig("EC-05-1006", 100L, "warn", T0));
            agg.offer(sig("EC-05-1006", 100L, "error", T0.plusSeconds(10)));
            // 隔很久再发一条 -> 触发旧窗口结算（SUMMARY）
            List<Alert> out = agg.offer(
                    sig("EC-05-1006", 100L, "warn", T0.plus(Duration.ofMinutes(20))));
            boolean hasError = out.stream()
                    .anyMatch(a -> a.kind() == AlertKind.SUMMARY
                            && "error".equalsIgnoreCase(a.severity()));
            check("结算的 SUMMARY 级别是 error 而不是 warn", hasError);
        });

        run("连续 5 次 -> 只发 1 条 OPENED", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            List<Alert> all = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                all.addAll(agg.offer(sig("EC-05-1006", 100L, T0.plusSeconds(i * 10L))));
            }
            eq("总共 1 条", 1, all.size());
            eq("类型", AlertKind.OPENED, all.get(0).kind());
        });

        run("连续 12 次 -> OPENED + ESCALATED(10)", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            List<Alert> all = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                all.addAll(agg.offer(sig("EC-05-1006", 100L, T0.plusSeconds(i * 10L))));
            }
            eq("2 条", 2, all.size());
            eq("首条 OPENED", AlertKind.OPENED, all.get(0).kind());
            eq("次条 ESCALATED", AlertKind.ESCALATED, all.get(1).kind());
            eq("升级时计数", 10, all.get(1).count());
        });

        run("静默期过后 -> 先 SUMMARY 再新 OPENED", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            agg.offer(sig("EC-05-1006", 100L, T0));
            agg.offer(sig("EC-05-1006", 100L, T0.plusSeconds(30)));

            List<Alert> out = agg.offer(sig("EC-05-1006", 100L, T0.plus(Duration.ofMinutes(20))));
            eq("2 条", 2, out.size());
            eq("先摘要", AlertKind.SUMMARY, out.get(0).kind());
            eq("摘要计数=2", 2, out.get(0).count());
            eq("后新开", AlertKind.OPENED, out.get(1).kind());
        });

        run("不同设备互不干扰", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            agg.offer(sig("EC-05-1006", 100L, T0));
            List<Alert> out = agg.offer(sig("EC-05-1006", 200L, T0.plusSeconds(5)));
            eq("另一台设备也要告警", 1, out.size());
            eq("窗口数", 2, agg.activeWindowCount());
        });

        run("flushExpired 结算静默窗口", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            agg.offer(sig("EC-A", 1L, T0));
            agg.offer(sig("EC-B", 2L, T0.plusSeconds(10)));

            List<Alert> out = agg.flushExpired(T0.plus(Duration.ofMinutes(30)));
            eq("2 条摘要", 2, out.size());
            eq("窗口清空", 0, agg.activeWindowCount());
            check("都是 SUMMARY", out.stream().allMatch(a -> a.kind() == AlertKind.SUMMARY));
        });

        run("未到静默期的窗口不结算", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            agg.offer(sig("EC-A", 1L, T0));
            List<Alert> out = agg.flushExpired(T0.plusSeconds(60));
            eq("不结算", 0, out.size());
            eq("窗口仍在", 1, agg.activeWindowCount());
        });

        // ============ 关键：真实生产场景 ============
        run("生产实例：5,928 次连续报错 -> 5 条告警", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            int total = 5928;
            long stepSec = 14;   // 平均 14 秒一次，约 24 小时

            List<Alert> all = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                all.addAll(agg.offer(sig("EC-05-1006", 200383L, T0.plusSeconds(i * stepSec))));
            }
            Instant last = T0.plusSeconds((total - 1L) * stepSec);
            all.addAll(agg.flushExpired(last.plus(Duration.ofHours(1))));

            long opened = all.stream().filter(a -> a.kind() == AlertKind.OPENED).count();
            long escalated = all.stream().filter(a -> a.kind() == AlertKind.ESCALATED).count();
            long summary = all.stream().filter(a -> a.kind() == AlertKind.SUMMARY).count();

            eq("OPENED 1 条", 1, (int) opened);
            eq("ESCALATED 3 条(10/100/1000)", 3, (int) escalated);
            eq("SUMMARY 1 条", 1, (int) summary);
            eq("合计 5 条", 5, all.size());

            Alert sum = all.stream().filter(a -> a.kind() == AlertKind.SUMMARY).findFirst().orElseThrow();
            eq("摘要里记录了完整次数", total, sum.count());

            double reduction = (1 - all.size() / (double) (total * 2)) * 100;
            System.out.printf("      → %d 次报错(老系统约 %d 条日志) 压成 %d 条告警，降幅 %.2f%%%n",
                    total, total * 2, all.size(), reduction);
        });

        run("间歇抖动（间隔 > 静默期）各算一次", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            List<Alert> all = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                all.addAll(agg.offer(sig("EC-A", 1L, T0.plus(Duration.ofHours(i)))));
            }
            long opened = all.stream().filter(a -> a.kind() == AlertKind.OPENED).count();
            long summary = all.stream().filter(a -> a.kind() == AlertKind.SUMMARY).count();
            eq("5 次独立 OPENED", 5, (int) opened);
            eq("4 条摘要（每次静默后补一条）", 4, (int) summary);
            eq("合计 9 条", 9, all.size());
        });

        run("窗口边界：恰好等于静默期仍算同一窗口", () -> {
            AlertAggregator agg = new AlertAggregator(GAP);
            agg.offer(sig("EC-A", 1L, T0));
            List<Alert> out = agg.offer(sig("EC-A", 1L, T0.plus(GAP)));
            eq("不结算", 0, out.size());
        });

        run("非法输入被拒绝", () -> {
            check("空错误码", throwsIae(() -> new AlertSignal("", 1L, "warn", "m", T0)));
            check("空时间", throwsIae(() -> new AlertSignal("E", 1L, "warn", "m", null)));
            check("零窗口", throwsIae(() -> new AlertAggregator(Duration.ZERO)));
            check("负计数", throwsIae(() -> new Alert(AlertKind.OPENED, "f", "E", 1L, "warn", 0, T0, T0, "m")));
        });

        run("SUMMARY 不推送但可归档", () -> {
            Alert a = new Alert(AlertKind.SUMMARY, "f", "E", 1L, "warn", 3, T0, T0, "m");
            check("SUMMARY 不通知", !a.shouldNotify());
            Alert b = new Alert(AlertKind.OPENED, "f", "E", 1L, "warn", 1, T0, T0, "m");
            check("OPENED 通知", b.shouldNotify());
        });

        System.out.printf("%n================ 告警聚合: 通过 %d, 失败 %d ================%n",
                passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static AlertSignal sig(String code, Long res, Instant at) {
        return new AlertSignal(code, res, "warn", "设备通讯超时", at);
    }

    private static AlertSignal sig(String code, Long res, String severity, Instant at) {
        return new AlertSignal(code, res, severity, "设备通讯超时", at);
    }

    // ---------------------------------------------------------------- harness

    private static void run(String name, Runnable t) {
        try {
            t.run();
            passed++;
            System.out.printf("  ✔ %s%n", name);
        } catch (AssertionError e) {
            failed++;
            System.out.printf("  ✘ %s%n      %s%n", name, e.getMessage());
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("  ✘ %s%n      异常: %s%n", name, e);
        }
    }

    private static void check(String desc, boolean cond) {
        if (!cond) {
            throw new AssertionError(desc + " 断言失败");
        }
    }

    private static void eq(String desc, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(desc + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private AlertSelfTest() {
    }
}
