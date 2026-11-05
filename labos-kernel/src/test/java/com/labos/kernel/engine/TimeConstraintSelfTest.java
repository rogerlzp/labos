package com.labos.kernel.engine;

import com.labos.kernel.recovery.RecoveryScope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 时间约束（两节点之间的最短 / 最长间隔）纯逻辑自测（不连库）。
 *
 * <p>对应功能台账 L1-1.6（P0）与规格 7.3：A 层关系管不了「取出和读板之间不得超过 30 分钟」，
 * 孵育窗口 / 撕膜后等待靠 {@link TimeConstraintExecutor}。锁住四条不能退的语义：
 * <ol>
 *   <li><b>间隔不足 {@code minSec} 补等</b>：补齐到 {@code minSec}（不是错误）；</li>
 *   <li><b>间隔超过 {@code maxSec} 抛 {@code EC-11-0001}</b>，scope=NODE，交恢复策略；</li>
 *   <li><b>前驱没跑过不检查</b>：没有基准时刻就不该误报；</li>
 *   <li><b>补等上限守卫</b>：{@code minSec} 配得过大（超过 2 小时）抛 {@code EC-11-0003}，
 *       不把线程挂死。</li>
 * </ol>
 *
 * <p>时钟注入：用可控 {@link Clock}（{@link TickClock}），把「间隔多久」变成纯逻辑判断，
 * 只有「真等」用一次约 1 秒的真实睡眠验证。
 */
public final class TimeConstraintSelfTest {

    private static int passed;
    private static int failed;

    private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");

    public static void main(String[] args) {
        section("1. 无约束：直接执行，不等待，记录完成时刻");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, Map.of(), clock, 0);
            try {
                ex.execute(ctx(1));
            } catch (StepFailure e) {
                check("无约束不应抛异常", false, e.describe());
            }
            eq("inner 执行 1 次", 1, inner.calls);
            check("记录了节点 1 的完成时刻 = 当前时钟", T0.equals(ex.finished().get(1L)));
        }

        section("2. 有约束但前驱没跑过：不检查，直接执行");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            Map<Long, TimeConstraintExecutor.Constraint> cs = new LinkedHashMap<>();
            cs.put(5L, new TimeConstraintExecutor.Constraint(99L, 300, 60));
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, cs, clock, 0);
            try {
                ex.execute(ctx(5));
            } catch (StepFailure e) {
                check("前驱未跑过不应抛异常", false, e.describe());
            }
            eq("inner 执行 1 次（未等、未报错）", 1, inner.calls);
        }

        section("3. 超过最长间隔：抛 EC-11-0001（scope=NODE），不执行");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            Map<Long, TimeConstraintExecutor.Constraint> cs = new LinkedHashMap<>();
            cs.put(2L, new TimeConstraintExecutor.Constraint(1L, 0, 60));
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, cs, clock, 0);
            try {
                ex.execute(ctx(1));             // 记录节点 1 于 T0
            } catch (StepFailure e) {
                check("节点 1 无约束不应抛异常", false, e.describe());
            }
            clock.advance(Duration.ofMinutes(2));   // 间隔 120s > max 60s
            StepFailure f = capture(ex, ctx(2));
            check("抛 EC-11-0001", f != null && "EC-11-0001".equals(f.errorCode()),
                    f == null ? "无异常" : f.errorCode());
            check("scope = NODE", f != null && f.scope() == RecoveryScope.NODE);
            check("超限节点未执行（inner 只跑了节点 1）", inner.calls == 1);
        }

        section("4. 未超过最长间隔：放行");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            Map<Long, TimeConstraintExecutor.Constraint> cs = new LinkedHashMap<>();
            cs.put(2L, new TimeConstraintExecutor.Constraint(1L, 0, 60));
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, cs, clock, 0);
            try {
                ex.execute(ctx(1));
            } catch (StepFailure ignored) {
                // 节点 1 无约束
            }
            clock.advance(Duration.ofSeconds(30));  // 30s <= max 60s
            StepFailure f = capture(ex, ctx(2));
            check("未超限不抛异常", f == null, f == null ? "" : f.describe());
            eq("inner 执行 2 次（节点 1 + 节点 2）", 2, inner.calls);
        }

        section("5. 间隔不足 + 仿真模式（waitRealMs<=0）：只记录、不等待");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            Map<Long, TimeConstraintExecutor.Constraint> cs = new LinkedHashMap<>();
            cs.put(2L, new TimeConstraintExecutor.Constraint(1L, 300, 0));
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, cs, clock, 0);
            try {
                ex.execute(ctx(1));
            } catch (StepFailure ignored) {
                // 节点 1 无约束
            }
            long start = System.nanoTime();
            StepFailure f = capture(ex, ctx(2));    // 间隔 0 < min 300，仿真模式不真等
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            check("仿真模式不抛异常", f == null, f == null ? "" : f.describe());
            eq("inner 执行 2 次", 2, inner.calls);
            check("仿真模式确实没真等（< 200ms）", elapsedMs < 200, "耗时 " + elapsedMs + "ms");
        }

        section("6. 间隔不足 + 真等（waitRealMs>0）：补齐到 minSec");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            Map<Long, TimeConstraintExecutor.Constraint> cs = new LinkedHashMap<>();
            cs.put(2L, new TimeConstraintExecutor.Constraint(1L, 1, 0));
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, cs, clock, 1);
            try {
                ex.execute(ctx(1));
            } catch (StepFailure ignored) {
                // 节点 1 无约束
            }
            long start = System.nanoTime();
            StepFailure f = capture(ex, ctx(2));    // 间隔 0 < min 1，真等约 1000ms
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            check("真等不抛异常", f == null, f == null ? "" : f.describe());
            eq("inner 执行 2 次", 2, inner.calls);
            check("真等补齐约 1 秒（>= 900ms）", elapsedMs >= 900, "耗时 " + elapsedMs + "ms");
        }

        section("7. 间隔已满足 minSec：不等待");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            Map<Long, TimeConstraintExecutor.Constraint> cs = new LinkedHashMap<>();
            cs.put(2L, new TimeConstraintExecutor.Constraint(1L, 300, 0));
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, cs, clock, 1);
            try {
                ex.execute(ctx(1));
            } catch (StepFailure ignored) {
                // 节点 1 无约束
            }
            clock.advance(Duration.ofSeconds(300)); // 间隔 == min，不补等
            long start = System.nanoTime();
            StepFailure f = capture(ex, ctx(2));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            check("间隔满足不抛异常", f == null, f == null ? "" : f.describe());
            eq("inner 执行 2 次", 2, inner.calls);
            check("未发生真等（< 200ms）", elapsedMs < 200, "耗时 " + elapsedMs + "ms");
        }

        section("8. 补等上限守卫：minSec 过大抛 EC-11-0003，不挂死线程");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            Map<Long, TimeConstraintExecutor.Constraint> cs = new LinkedHashMap<>();
            cs.put(2L, new TimeConstraintExecutor.Constraint(1L, 3 * 3600, 0)); // 3 小时 > 2 小时上限
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, cs, clock, 1);
            try {
                ex.execute(ctx(1));
            } catch (StepFailure ignored) {
                // 节点 1 无约束
            }
            StepFailure f = capture(ex, ctx(2));
            check("抛 EC-11-0003", f != null && "EC-11-0003".equals(f.errorCode()),
                    f == null ? "无异常" : f.errorCode());
            check("scope = NODE", f != null && f.scope() == RecoveryScope.NODE);
            eq("超限节点未执行（inner 只跑了节点 1）", 1, inner.calls);
        }

        section("9. 约束 map 为 null：视作无约束");
        {
            TickClock clock = new TickClock(T0);
            Recorder inner = new Recorder();
            TimeConstraintExecutor ex = new TimeConstraintExecutor(inner, null, clock, 0);
            StepFailure f = capture(ex, ctx(1));
            check("null 约束不抛异常", f == null, f == null ? "" : f.describe());
            eq("inner 执行 1 次", 1, inner.calls);
        }

        section("10. Constraint.hasMin/hasMax 语义（0/负数 = 不限制）");
        {
            TimeConstraintExecutor.Constraint none =
                    new TimeConstraintExecutor.Constraint(1L, 0, 0);
            TimeConstraintExecutor.Constraint minOnly =
                    new TimeConstraintExecutor.Constraint(1L, 30, 0);
            TimeConstraintExecutor.Constraint maxOnly =
                    new TimeConstraintExecutor.Constraint(1L, 0, -5);
            eq("min=0,max=0 -> false|false", "false|false",
                    none.hasMin() + "|" + none.hasMax());
            eq("min=30,max=0 -> true|false", "true|false",
                    minOnly.hasMin() + "|" + minOnly.hasMax());
            eq("min=0,max=-5 -> false|false（负值视为不限制）", "false|false",
                    maxOnly.hasMin() + "|" + maxOnly.hasMax());
        }

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 辅助

    private static StepContext ctx(long nodeId) {
        return new StepContext(nodeId, "n" + nodeId, "CAP", "M", 1L, 1);
    }

    /** 执行并捕获 {@link StepFailure}（不抛出，返回 null 表示成功）。 */
    private static StepFailure capture(TimeConstraintExecutor ex, StepContext c) {
        try {
            ex.execute(c);
            return null;
        } catch (StepFailure e) {
            return e;
        }
    }

    /** 记录被调用次数的假执行体。 */
    static final class Recorder implements StepExecutor {
        int calls;

        @Override
        public void execute(StepContext c) {
            calls++;
        }
    }

    /** 可控时钟：默认停在起点，{@link #advance} 手动推进。 */
    static final class TickClock extends Clock {
        private Instant now;

        TickClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static void eq(String name, Object expect, Object got) {
        check(name, Objects.equals(expect, got), "期望 " + expect + "，实际 " + got);
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            failed++;
            System.out.printf("  ✗ %s%s%n", name, detail == null ? "" : "（" + detail + "）");
        }
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private TimeConstraintSelfTest() {
    }
}
