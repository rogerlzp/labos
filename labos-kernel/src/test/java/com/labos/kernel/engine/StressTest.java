package com.labos.kernel.engine;

import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压力与健壮性测试。
 */
public final class StressTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testBatch1000();
        testListenerFailureIsolated();
        testDeterminism();
        testDeepRetry();
        testEventIntegrity();
        testZeroAttemptPolicy();
        testEmptyNodeList();

        System.out.printf("%n================ 压力测试: 通过 %d, 失败 %d ================%n",
                passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1. 批量性能

    private static void testBatch1000() {
        run("1000 节点批量执行（20% 首次失败，全部恢复）", () -> {
            int n = 1000;
            List<RecoverableNode> nodes = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                String cap = switch (i % 3) {
                    case 0 -> "TRANSFER";
                    case 1 -> "INCUBATE";
                    default -> "CENTRIFUGE";
                };
                nodes.add(RecoverableNode.of(i, "N" + i, cap, (long) (1000 + i), List.of((long) (1000 + i))));
            }

            Map<Long, AtomicInteger> calls = new HashMap<>();
            StepExecutor flaky = ctx -> {
                int c = calls.computeIfAbsent(ctx.nodeId(), k -> new AtomicInteger()).getAndIncrement();
                // 每 5 个节点中第 1 次调用失败
                if (c == 0 && ctx.nodeId() % 5 == 0) {
                    throw new StepFailure("EC-RETRY", RecoveryScope.COMMAND, "首次失败");
                }
            };

            RunStateMachine sm = machine();
            long t0 = System.nanoTime();
            RunProgress p = sm.executeAll(nodes, flaky);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            eq("全部完成", n, p.count(NodeStatus.COMPLETED));
            eq("无人工", 0, p.count(NodeStatus.MANUAL));
            check("耗时 < 2000ms（实际 " + ms + "ms）", ms < 2000);
            check("总调用数 = 1000 + 200 次重试", calls.values().stream()
                    .mapToInt(AtomicInteger::get).sum() == 1200);
            System.out.printf("      → 耗时 %dms，事件 %d 条%n", ms, sm.eventCount());
        });
    }

    // ---------------------------------------------------------------- 2. 监听器故障隔离

    private static void testListenerFailureIsolated() {
        run("监听器持续抛异常，执行不被打断", () -> {
            ExecutionListener bad = e -> {
                throw new IllegalStateException("模拟落库失败");
            };
            RunStateMachine sm = new RunStateMachine(decider(), bad,
                    Sleeper.recording(new ArrayList<>()), fixedClock());

            SimulatedBus bus = new SimulatedBus();
            RunProgress p = sm.executeAll(List.of(
                    RecoverableNode.of(1, "A", "TRANSFER", 100L, List.of(100L)),
                    RecoverableNode.of(2, "B", "INCUBATE", 200L, List.of(200L))), bus);

            eq("两个节点仍完成", 2, p.count(NodeStatus.COMPLETED));
        });
    }

    // ---------------------------------------------------------------- 3. 确定性

    private static void testDeterminism() {
        run("相同输入两次执行结果完全一致（可回放）", () -> {
            List<RecoverableNode> nodes = new ArrayList<>();
            Random r = new Random(7);
            for (int i = 1; i <= 50; i++) {
                long res = 100L + r.nextInt(5);
                nodes.add(RecoverableNode.of(i, "N" + i, "TRANSFER", res,
                        List.of(100L, 101L, 102L, 103L, 104L)));
            }

            String a = snapshot(runOnce(nodes));
            String b = snapshot(runOnce(nodes));
            eq("两次快照一致", a, b);
        });
    }

    private static RunProgress runOnce(List<RecoverableNode> nodes) {
        SimulatedBus bus = new SimulatedBus()
                .dead(100L, "TRANSFER", "EC-DEAD")
                .dead(101L, "TRANSFER", "EC-DEAD");
        return machine().executeAll(nodes, bus);
    }

    private static String snapshot(RunProgress p) {
        StringBuilder sb = new StringBuilder();
        p.all().forEach(n -> sb.append(n.toString()).append('\n'));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 4. 深重试

    private static void testDeepRetry() {
        run("单节点连续失败 50 次后成功（maxAttempts=100）", () -> {
            RecoveryDecider d = new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                    new RecoveryPolicy("EC-FLAKY", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                            100, Backoff.FIXED, Duration.ZERO, 1, true))));
            RunStateMachine sm = new RunStateMachine(d, ExecutionListener.NOOP,
                    Sleeper.recording(new ArrayList<>()), fixedClock());

            AtomicInteger cnt = new AtomicInteger();
            StepExecutor flaky = ctx -> {
                if (cnt.getAndIncrement() < 50) {
                    throw new StepFailure("EC-FLAKY", RecoveryScope.COMMAND, "抖动");
                }
            };

            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(
                    RecoverableNode.of(1, "N1", "TRANSFER", 100L, List.of(100L)), p, flaky);

            eq("最终完成", NodeStatus.COMPLETED, st);
            eq("重试 50 次", 50, p.node(1).attempt());
            eq("调用 51 次", 51, cnt.get());
        });
    }

    // ---------------------------------------------------------------- 5. 事件完整性

    private static void testEventIntegrity() {
        run("大量事件下 seq 连续、无重复遗漏", () -> {
            List<ExecutionEvent> events = new ArrayList<>();
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-DEAD");
            RunStateMachine sm = new RunStateMachine(decider(), events::add,
                    Sleeper.recording(new ArrayList<>()), fixedClock());

            List<RecoverableNode> nodes = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                nodes.add(RecoverableNode.of(i, "N" + i, "TRANSFER", 100L, List.of(100L, 200L)));
            }
            sm.executeAll(nodes, bus);

            check("有事件", events.size() > 100);
            for (int i = 0; i < events.size(); i++) {
                eq("seq@" + i, (long) (i + 1), events.get(i).seq());
            }
            System.out.printf("      → %d 个节点产生 %d 条事件%n", 100, events.size());
        });
    }

    // ---------------------------------------------------------------- 6. 边界

    private static void testZeroAttemptPolicy() {
        run("maxAttempts=0 表示不重试，第一次失败即 MANUAL", () -> {
            RecoveryDecider d = new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                    new RecoveryPolicy("EC-NORETRY", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                            0, Backoff.FIXED, Duration.ZERO, 1, true))));
            RunStateMachine sm = new RunStateMachine(d);
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-NORETRY");

            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(
                    RecoverableNode.of(1, "N1", "TRANSFER", 100L, List.of(100L)), p, bus);
            eq("直接 MANUAL", NodeStatus.MANUAL, st);
            eq("只调 1 次", 1, bus.callLog().size());
        });
    }

    private static void testEmptyNodeList() {
        run("空节点列表不报错", () -> {
            RunProgress p = machine().executeAll(List.of(), new SimulatedBus());
            eq("无节点", 0, p.all().size());
            check("全部收尾", p.allSettled());
        });
    }

    // ---------------------------------------------------------------- 组装

    private static final Instant T0 = Instant.parse("2026-10-29T02:00:00Z");

    private static Clock fixedClock() {
        return Clock.fixed(T0, ZoneOffset.UTC);
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy("EC-RETRY", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                        3, Backoff.FIXED, Duration.ZERO, 10, true),
                new RecoveryPolicy("EC-DEAD", RecoveryScope.COMMAND, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, Duration.ZERO, 20, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.RETRY,
                        1, Backoff.FIXED, Duration.ZERO, 99, true))));
    }

    private static RunStateMachine machine() {
        return new RunStateMachine(decider(), ExecutionListener.NOOP,
                Sleeper.recording(new ArrayList<>()), fixedClock());
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

    private StressTest() {
    }
}
