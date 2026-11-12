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
import java.util.List;

/**
 * 执行状态机自测（零依赖，javac 直接可跑）。
 */
public final class EngineSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // ============ 1. 正常路径 ============
        run("首次执行成功 -> COMPLETED", () -> {
            SimulatedBus bus = new SimulatedBus();
            RunStateMachine sm = machine();
            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, bus);
            eq("状态", NodeStatus.COMPLETED, st);
            eq("尝试次数", 0, p.node(1).attempt());
            eq("调用次数", 1, bus.callLog().size());
        });

        // ============ 2. 重试 ============
        run("失败1次后重试成功 -> COMPLETED, attempt=1", () -> {
            SimulatedBus bus = new SimulatedBus().fail(100L, "TRANSFER", "EC-RETRY", 1);
            RunStateMachine sm = machine();
            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, bus);
            eq("状态", NodeStatus.COMPLETED, st);
            eq("尝试次数", 1, p.node(1).attempt());
            eq("调用次数", 2, bus.callLog().size());
        });

        run("重试耗尽 -> MANUAL", () -> {
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-RETRY");
            RunStateMachine sm = machine();     // EC-RETRY 策略 maxAttempts=2
            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, bus);
            eq("状态", NodeStatus.MANUAL, st);
            eq("重试2次后放弃", 2, p.node(1).attempt());
            eq("共调用3次(1次原始+2次重试)", 3, bus.callLog().size());
        });

        // ============ 3. 换机 ============
        run("主设备永久故障 -> 自动换机成功", () -> {
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-DEAD");
            RunStateMachine sm = machine();     // EC-DEAD -> REASSIGN, maxAttempts=2
            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(node(1, "TRANSFER", 100L, 100L, 200L, 300L), p, bus);
            eq("状态", NodeStatus.COMPLETED, st);
            eq("最终设备", 200L, p.node(1).currentResourceId());
            eq("100失败1次", 1, bus.callCount(100L, "TRANSFER"));
            eq("200成功1次", 1, bus.callCount(200L, "TRANSFER"));
            check("已记录失败设备", p.node(1).failedResourceIds().contains(100L));
        });

        run("换机会跳过已失败设备", () -> {
            SimulatedBus bus = new SimulatedBus()
                    .dead(100L, "TRANSFER", "EC-DEAD")
                    .dead(200L, "TRANSFER", "EC-DEAD");
            RunStateMachine sm = machine();
            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(node(1, "TRANSFER", 100L, 100L, 200L, 300L), p, bus);
            eq("状态", NodeStatus.COMPLETED, st);
            eq("最终设备", 300L, p.node(1).currentResourceId());
        });

        run("所有候选设备都坏 -> MANUAL", () -> {
            SimulatedBus bus = new SimulatedBus()
                    .dead(100L, "TRANSFER", "EC-DEAD")
                    .dead(200L, "TRANSFER", "EC-DEAD");
            RunStateMachine sm = machine();
            RunProgress p = new RunProgress();
            NodeStatus st = sm.executeNode(node(1, "TRANSFER", 100L, 100L, 200L), p, bus);
            eq("状态", NodeStatus.MANUAL, st);
        });

        // ============ 4. 跳过 / 终止 ============
        run("SKIP 策略 -> SKIPPED", () -> {
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-SKIP");
            RunStateMachine sm = machine();
            RunProgress p = new RunProgress();
            eq("状态", NodeStatus.SKIPPED,
                    sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, bus));
        });

        run("ABORT 策略 -> ABORTED 且 run 标记中止", () -> {
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-ABORT");
            RunStateMachine sm = machine();
            RunProgress p = new RunProgress();
            eq("状态", NodeStatus.ABORTED,
                    sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, bus));
            check("run 已中止", p.aborted());
        });

        // ============ 5. 批量执行的中断传播 ============
        run("executeAll 遇 MANUAL 停止后续节点", () -> {
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-DEAD")
                    .dead(200L, "TRANSFER", "EC-DEAD");
            RunStateMachine sm = machine();
            List<RecoverableNode> nodes = List.of(
                    node(1, "TRANSFER", 100L, 100L, 200L),   // 全坏 -> MANUAL
                    node(2, "INCUBATE", 300L, 300L));
            RunProgress p = sm.executeAll(nodes, bus);
            eq("节点1 MANUAL", NodeStatus.MANUAL, p.node(1).status());
            eq("节点2 未执行", NodeStatus.PENDING, p.node(2).status());
        });

        run("executeAll 遇 ABORT 停止后续并标记", () -> {
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-ABORT");
            RunStateMachine sm = machine();
            List<RecoverableNode> nodes = List.of(
                    node(1, "TRANSFER", 100L, 100L),
                    node(2, "INCUBATE", 300L, 300L));
            RunProgress p = sm.executeAll(nodes, bus);
            eq("节点1 ABORTED", NodeStatus.ABORTED, p.node(1).status());
            eq("节点2 未执行", NodeStatus.PENDING, p.node(2).status());
            check("run 已中止", p.aborted());
        });

        run("executeAll 全部成功", () -> {
            SimulatedBus bus = new SimulatedBus();
            RunStateMachine sm = machine();
            RunProgress p = sm.executeAll(List.of(
                    node(1, "TRANSFER", 100L, 100L),
                    node(2, "INCUBATE", 300L, 300L)), bus);
            eq("完成数", 2, p.count(NodeStatus.COMPLETED));
            check("全部收尾", p.allSettled());
        });

        // ============ 6. 事件流 ============
        run("事件流: seq 连续递增、首尾类型正确", () -> {
            List<ExecutionEvent> events = new ArrayList<>();
            SimulatedBus bus = new SimulatedBus().dead(100L, "TRANSFER", "EC-DEAD");
            RunStateMachine sm = new RunStateMachine(decider(),
                    events::add, Sleeper.recording(new ArrayList<>()), fixedClock());
            sm.executeNode(node(1, "TRANSFER", 100L, 100L, 200L), new RunProgress(), bus);

            check("有事件", !events.isEmpty());
            eq("首个事件", EventType.NODE_STARTED, events.get(0).type());
            eq("末个事件", EventType.NODE_COMPLETED, events.get(events.size() - 1).type());
            for (int i = 0; i < events.size(); i++) {
                eq("seq 连续", (long) (i + 1), events.get(i).seq());
            }
            check("含 STEP_FAILED",
                    events.stream().anyMatch(e -> e.type() == EventType.STEP_FAILED));
            check("含 RESOURCE_SWITCHED",
                    events.stream().anyMatch(e -> e.type() == EventType.RESOURCE_SWITCHED));
            check("时间戳固定(可复现)",
                    events.stream().allMatch(e -> e.at().equals(T0)));
        });

        // ============ 7. 退避不真睡 ============
        run("退避通过 Sleeper 抽象，测试不真睡", () -> {
            List<Long> slept = new ArrayList<>();
            SimulatedBus bus = new SimulatedBus().fail(100L, "TRANSFER", "EC-RETRY", 1);
            RunStateMachine sm = new RunStateMachine(decider(),
                    ExecutionListener.NOOP, Sleeper.recording(slept), fixedClock());
            long t0 = System.nanoTime();
            sm.executeNode(node(1, "TRANSFER", 100L, 100L), new RunProgress(), bus);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            eq("记录了1次等待", 1, slept.size());
            check("未真实阻塞(耗时<100ms)", elapsedMs < 100);
        });

        // ============ 8. 边界 ============
        run("无资源可用 -> 直接 MANUAL 不调用设备", () -> {
            SimulatedBus bus = new SimulatedBus();
            RunStateMachine sm = machine();
            RecoverableNode n = new RecoverableNode(1, "T", "T", "TRANSFER", null, null, java.util.Set.of());
            eq("状态", NodeStatus.MANUAL,
                    sm.executeNode(n, new RunProgress(), bus));
            eq("设备未被调用", 0, bus.callLog().size());
        });

        // ============ 9. 完成处理器注入（§3.1 第 1 项） ============
        run("默认 NOOP 完成处理器 → CONTINUE，无 materialHold", () -> {
            RunStateMachine sm = machine();
            RunProgress p = new RunProgress();
            sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, new SimulatedBus());
            check("无 materialHold", !p.materialHold());
        });

        run("完成处理器返回 HOLD → progress.materialHold", () -> {
            RunStateMachine sm = machine().withMaterialCompletion(r -> {
                eq("resolution=COMPLETED", ExecutionResolution.COMPLETED, r.resolution());
                return MaterialOutcome.HOLD;
            });
            RunProgress p = new RunProgress();
            sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, new SimulatedBus());
            check("materialHold=true", p.materialHold());
        });

        run("完成处理器抛异常 → 向上传播（中止执行）", () -> {
            RunStateMachine sm = machine().withMaterialCompletion(r -> {
                throw new IllegalStateException("T1 写不进");
            });
            RunProgress p = new RunProgress();
            RuntimeException thrown = null;
            try {
                sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, new SimulatedBus());
            } catch (RuntimeException e) {
                thrown = e;
            }
            check("异常已传播", thrown != null && thrown.getMessage().contains("T1 写不进"));
        });

        run("第 7 出口：非 StepFailure 异常 → 先写 reconciliation_required 再抛", () -> {
            java.util.concurrent.atomic.AtomicReference<ExecutionResolution> seen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            RunStateMachine sm = machine().withMaterialCompletion(r -> {
                seen.set(r.resolution());
                return MaterialOutcome.CONTINUE;
            });
            RunProgress p = new RunProgress();
            RuntimeException thrown = null;
            try {
                sm.executeNode(node(1, "TRANSFER", 100L, 100L), p,
                        ctx -> { throw new IllegalStateException("驱动逃逸异常"); });
            } catch (RuntimeException e) {
                thrown = e;
            }
            check("异常已传播", thrown != null);
            eq("先写 reconciliation_required",
                    ExecutionResolution.RECONCILIATION_REQUIRED, seen.get());
        });

        // ============ 10. 评审 R13-01 / R13-02 回归 ============
        run("完成处理器写成功后抛异常 → 不再二次写确认（R13-02）", () -> {
            java.util.concurrent.atomic.AtomicInteger calls =
                    new java.util.concurrent.atomic.AtomicInteger();
            List<ExecutionResolution> seen = new ArrayList<>();
            RunStateMachine sm = machine().withMaterialCompletion(r -> {
                calls.incrementAndGet();
                seen.add(r.resolution());
                throw new IllegalStateException("T1 写不进");
            });
            RunProgress p = new RunProgress();
            RuntimeException thrown = null;
            try {
                sm.executeNode(node(1, "TRANSFER", 100L, 100L), p, new SimulatedBus());
            } catch (RuntimeException e) {
                thrown = e;
            }
            check("原异常原样传播", thrown != null
                    && thrown.getMessage().contains("T1 写不进"));
            eq("完成处理器只调用 1 次（无第 7 出口双写）", 1, calls.get());
            eq("只写一次执行确认", 1, seen.size());
            eq("写入 COMPLETED，未被覆盖成 reconciliation_required",
                    ExecutionResolution.COMPLETED, seen.get(0));
        });

        run("顺序路径闸门命中 → 不派发任何节点 + materialHold（R13-01）", () -> {
            SimulatedBus bus = new SimulatedBus();
            RunStateMachine sm = machine().withMaterialGate(() -> true);
            RunProgress p = sm.executeAll(List.of(
                    node(1, "TRANSFER", 100L, 100L),
                    node(2, "TRANSFER", 100L, 100L)), bus);
            eq("设备零调用", 0, bus.callLog().size());
            check("materialHold=true", p.materialHold());
        });

        run("顺序路径闸门放行 → 正常执行全部节点（R13-01 不误伤）", () -> {
            SimulatedBus bus = new SimulatedBus();
            RunStateMachine sm = machine().withMaterialGate(() -> false);
            RunProgress p = sm.executeAll(List.of(
                    node(1, "TRANSFER", 100L, 100L),
                    node(2, "TRANSFER", 100L, 100L)), bus);
            eq("设备调用 2 次", 2, bus.callLog().size());
            check("无 materialHold", !p.materialHold());
        });

        System.out.printf("%n================ 引擎自测: 通过 %d, 失败 %d ================%n",
                passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 组装

    private static final Instant T0 = Instant.parse("2026-10-29T02:00:00Z");

    private static Clock fixedClock() {
        return Clock.fixed(T0, ZoneOffset.UTC);
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy("EC-RETRY", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                        2, Backoff.FIXED, Duration.ofMillis(100), 10, true),
                new RecoveryPolicy("EC-DEAD", RecoveryScope.COMMAND, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, Duration.ZERO, 20, true),
                new RecoveryPolicy("EC-SKIP", RecoveryScope.COMMAND, RecoveryStrategy.SKIP,
                        1, Backoff.FIXED, Duration.ZERO, 30, true),
                new RecoveryPolicy("EC-ABORT", RecoveryScope.COMMAND, RecoveryStrategy.ABORT,
                        1, Backoff.FIXED, Duration.ZERO, 40, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.RETRY,
                        1, Backoff.FIXED, Duration.ofSeconds(1), 99, true))));
    }

    private static RunStateMachine machine() {
        return new RunStateMachine(decider(), ExecutionListener.NOOP,
                Sleeper.recording(new ArrayList<>()), fixedClock());
    }

    private static RecoverableNode node(long id, String cap, Long initial, Long... candidates) {
        return RecoverableNode.of(id, "N" + id, cap, initial, List.of(candidates));
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

    private EngineSelfTest() {
    }
}
