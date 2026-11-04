package com.labos.kernel.engine;

import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.ErrorContext;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 闭环演示：设备报错 → 引擎决策 → 自动换机 → 继续执行。
 */
public final class EngineDemo {

    private static final Instant T0 = Instant.parse("2026-10-29T02:00:00Z");

    public static void main(String[] args) {
        scenarioA();
        scenarioB();
    }

    /** 场景 A：机械臂坏了，自动换同能力设备。 */
    private static void scenarioA() {
        title("闭环演示 A：主机械臂离线，自动切换到备用机械臂继续跑");

        List<RecoverableNode> nodes = List.of(
                RecoverableNode.of(1, "Transfer", "TRANSFER", 200383L, List.of(200383L, 200387L, 200390L)),
                RecoverableNode.of(2, "Incubate", "INCUBATE", 200391L, List.of(200391L, 200398L)),
                RecoverableNode.of(3, "Centrifuge", "CENTRIFUGE", 200401L, List.of(200401L)));

        printNodes(nodes);

        SimulatedBus bus = new SimulatedBus()
                .dead(200383L, "TRANSFER", "EC-05-1006");     // 主机械臂彻底离线

        System.out.println("  注入故障: 200383(主机械臂) 永久离线 [EC-05-1006]");
        System.out.println("  策略    : EC-05-1006@COMMAND -> REASSIGN，最多 2 次\n");

        List<Long> slept = new ArrayList<>();
        List<ExecutionEvent> events = new ArrayList<>();
        RunStateMachine sm = new RunStateMachine(
                decider(), events::add, Sleeper.recording(slept), fixedClock());

        RunProgress progress = sm.executeAll(nodes, bus);

        printEvents(events);
        printResult(progress, bus, slept);
        System.out.println("  ★ 机械臂 200383 坏了，系统自动切到 200387，实验没有停下来等人。\n");
    }

    /** 场景 B：唯一天平故障，无替代设备，转人工。 */
    private static void scenarioB() {
        title("闭环演示 B：唯一离心机故障，无替代设备 → 转人工（不刷日志、不无限重试）");

        List<RecoverableNode> nodes = List.of(
                RecoverableNode.of(1, "Transfer", "TRANSFER", 200383L, List.of(200383L)),
                RecoverableNode.of(2, "Centrifuge", "CENTRIFUGE", 200401L, List.of(200401L)),
                RecoverableNode.of(3, "Read", "READ_PLATE", 200392L, List.of(200392L)));

        SimulatedBus bus = new SimulatedBus()
                .dead(200401L, "CENTRIFUGE", "EC-08-0001", RecoveryScope.NODE);

        System.out.println("  注入故障: 200401(唯一离心机) 永久失败 [EC-08-0001]");
        System.out.println("  策略    : EC-08-0001@NODE -> REASSIGN，最多 2 次，但候选集只有它自己\n");

        List<Long> slept = new ArrayList<>();
        List<ExecutionEvent> events = new ArrayList<>();
        RunStateMachine sm = new RunStateMachine(
                decider(), events::add, Sleeper.recording(slept), fixedClock());

        RunProgress progress = sm.executeAll(nodes, bus);

        printEvents(events);
        printResult(progress, bus, slept);

        System.out.println("  ★ 没有替代设备时，引擎一次把话说清楚（候选/已失败/原因），");
        System.out.println("    直接进人工队列，而不是像老系统那样重试 5,928 次刷 11,856 条日志。\n");
    }

    // ---------------------------------------------------------------- 组装

    private static RecoveryDecider decider() {
        RecoveryPolicy commDead = new RecoveryPolicy(
                "EC-05-1006", RecoveryScope.COMMAND, RecoveryStrategy.REASSIGN,
                2, Backoff.FIXED, Duration.ofMillis(200), 10, true);
        RecoveryPolicy nodeFail = new RecoveryPolicy(
                "EC-08-0001", RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                2, Backoff.FIXED, Duration.ZERO, 20, true);
        RecoveryPolicy fallback = new RecoveryPolicy(
                null, null, RecoveryStrategy.RETRY,
                1, Backoff.FIXED, Duration.ofSeconds(1), 99, true);
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                commDead, nodeFail, fallback)));
    }

    private static Clock fixedClock() {
        return Clock.fixed(T0, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- 输出

    private static void printNodes(List<RecoverableNode> nodes) {
        System.out.println("  流程节点（只声明能力，不绑定设备）：");
        for (RecoverableNode n : nodes) {
            System.out.printf("    N%-2d %-12s 需要 %-12s 候选设备 %s%n",
                    n.id(), n.name(), n.capabilityCode(), n.candidateResourceIds());
        }
        System.out.println();
    }

    private static void printEvents(List<ExecutionEvent> events) {
        System.out.println("  事件流：");
        for (ExecutionEvent e : events) {
            System.out.println("    " + e);
        }
        System.out.println();
    }

    private static void printResult(RunProgress p, SimulatedBus bus, List<Long> slept) {
        System.out.println("  结果: " + p.summary());
        System.out.println("  设备调用次数:");
        Set<String> keys = new LinkedHashSet<>(bus.callLog());
        for (String k : keys) {
            System.out.printf("    %-24s %d 次%n", k, java.util.Collections.frequency(bus.callLog(), k));
        }
        if (!slept.isEmpty()) {
            System.out.println("  退避等待(ms): " + slept);
        }
        System.out.println();
    }

    private static void title(String s) {
        System.out.println("=".repeat(104));
        System.out.println("  " + s);
        System.out.println("=".repeat(104));
        System.out.println();
    }

    private EngineDemo() {
    }
}
